package com.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class AssetManager {
    private static final String USER_AGENT = "NEXUS-Launcher/1.2.1";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    public static void verifyAndDownloadAssets(File baseDir, String versionId, Consumer<String> logger) {
        ExecutorService assetExecutor = null;
        List<Future<Boolean>> assetTasks = new ArrayList<>();
        try {
            File versionJsonFile = new File(new File(baseDir, "versions/" + versionId), versionId + ".json");
            if (!versionJsonFile.isFile()) {
                logger.accept("Индекс ресурсов не проверен: отсутствует JSON версии " + versionId);
                return;
            }

            JsonObject versionJson;
            try (BufferedReader reader = Files.newBufferedReader(versionJsonFile.toPath(), StandardCharsets.UTF_8)) {
                versionJson = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (!versionJson.has("assetIndex") || !versionJson.get("assetIndex").isJsonObject()) {
                logger.accept("У версии " + versionId + " нет assetIndex — проверка ресурсов пропущена.");
                return;
            }

            JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
            String assetVersion = assetIndex.get("id").getAsString();
            String indexUrl = assetIndex.get("url").getAsString();

            File assetsDir = new File(baseDir, "assets");
            File indexesDir = new File(assetsDir, "indexes");
            File indexFile = new File(indexesDir, assetVersion + ".json");

            if (!indexFile.isFile() || indexFile.length() == 0) {
                logger.accept("Скачивание индекса ассетов " + assetVersion + "…");
                downloadAtomically(indexUrl, indexFile.toPath());
            }

            JsonObject indexJson;
            try (BufferedReader reader = Files.newBufferedReader(indexFile.toPath(), StandardCharsets.UTF_8)) {
                indexJson = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (!indexJson.has("objects") || !indexJson.get("objects").isJsonObject()) {
                throw new IOException("В индексе ассетов отсутствует раздел objects");
            }

            JsonObject objects = indexJson.getAsJsonObject("objects");
            File objectsDir = new File(assetsDir, "objects");
            if (!objectsDir.exists() && !objectsDir.mkdirs()) {
                throw new IOException("Не удалось создать каталог " + objectsDir);
            }

            int totalFiles = objects.size();
            logger.accept("Проверка ресурсов Minecraft (" + totalFiles + " файлов) в " + assetsDir.getAbsolutePath() + "…");

            int threads = Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            assetExecutor = executor;
            List<Future<Boolean>> tasks = assetTasks;
            AtomicInteger repaired = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            for (String logicalName : objects.keySet()) {
                if (Thread.currentThread().isInterrupted()) { executor.shutdownNow(); throw new InterruptedException("Проверка ресурсов отменена"); }
                JsonObject object = objects.getAsJsonObject(logicalName);
                tasks.add(executor.submit(new AssetTask(objectsDir, object, repaired, failed)));
            }

            executor.shutdown();
            int checked = 0;
            int reportStep = Math.max(1, totalFiles / 10);
            for (Future<Boolean> task : tasks) {
                if (Thread.currentThread().isInterrupted()) { tasks.forEach(t -> t.cancel(true)); executor.shutdownNow(); throw new InterruptedException("Проверка ресурсов отменена"); }
                try {
                    task.get();
                } catch (ExecutionException e) {
                    failed.incrementAndGet();
                    logger.accept("Ошибка проверки ресурса: " + rootMessage(e));
                }
                checked++;
                if (checked % reportStep == 0 || checked == totalFiles) {
                    logger.accept("Проверка ресурсов: " + (checked * 100 / Math.max(1, totalFiles))
                            + "% [" + checked + "/" + totalFiles + "]");
                }
            }

            if (failed.get() > 0) {
                logger.accept("Проверка ресурсов завершена с ошибками: " + failed.get()
                        + ". Повтори запуск при стабильном интернете.");
            } else {
                logger.accept("Ассеты проверены. Скачано/восстановлено файлов: " + repaired.get());
            }
        } catch (InterruptedException e) {
            assetTasks.forEach(task -> task.cancel(true));
            if (assetExecutor != null) assetExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.accept("Проверка ассетов остановлена.");
        } catch (Exception e) {
            logger.accept("Ошибка при проверке ассетов: " + rootMessage(e));
        }
    }

    private static final class AssetTask implements Callable<Boolean> {
        private final File objectsDir;
        private final JsonObject object;
        private final AtomicInteger repaired;
        private final AtomicInteger failed;

        private AssetTask(File objectsDir, JsonObject object, AtomicInteger repaired, AtomicInteger failed) {
            this.objectsDir = objectsDir;
            this.object = object;
            this.repaired = repaired;
            this.failed = failed;
        }

        @Override
        public Boolean call() {
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Загрузка ресурсов остановлена");
                String hash = object.get("hash").getAsString().toLowerCase();
                if (!hash.matches("[0-9a-f]{40}")) {
                    throw new IOException("Некорректный SHA-1 в индексе: " + hash);
                }
                long expectedSize = object.has("size") ? object.get("size").getAsLong() : -1L;
                File target = new File(new File(objectsDir, hash.substring(0, 2)), hash);

                if (isValid(target.toPath(), hash, expectedSize)) return true;

                File parent = target.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Не удалось создать " + parent);
                }

                String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
                downloadAtomically(url, target.toPath());

                if (!isValid(target.toPath(), hash, expectedSize)) {
                    Files.deleteIfExists(target.toPath());
                    throw new IOException("Скачанный ресурс не прошёл проверку SHA-1: " + hash);
                }

                repaired.incrementAndGet();
                return true;
            } catch (Exception e) {
                failed.incrementAndGet();
                return false;
            }
        }
    }

    private static boolean isValid(Path file, String expectedSha1, long expectedSize) throws Exception {
        if (!Files.isRegularFile(file)) return false;
        if (expectedSize >= 0 && Files.size(file) != expectedSize) return false;
        return sha1(file).equalsIgnoreCase(expectedSha1);
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder(40);
        for (byte b : digest.digest()) result.append(String.format("%02x", b & 0xff));
        return result.toString();
    }

    private static void downloadAtomically(String urlString, Path destination) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = destination.resolveSibling(destination.getFileName().toString() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " при загрузке " + urlString);
            }
            try (InputStream input = connection.getInputStream(); java.io.OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Загрузка ресурсов остановлена");
                    output.write(buffer, 0, count);
                }
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveNotSupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (connection != null) connection.disconnect();
            Files.deleteIfExists(temporary);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
