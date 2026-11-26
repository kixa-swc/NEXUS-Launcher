package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.model.GameVersion;
import com.launcher.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class DownloadManager {
    private DownloadManager() {}

    public static void installVersion(GameVersion version, java.util.function.Consumer<String> logger) throws Exception {
        installVersion(version, FileUtils.getMinecraftDir(), logger);
    }

    public static void installBaseFiles(GameVersion version, File gameDir, java.util.function.Consumer<String> logger) throws Exception {
        checkCancelled();
        File versionDir = new File(gameDir, "versions/" + version.getId());
        if (!versionDir.exists() && !versionDir.mkdirs()) throw new IllegalStateException("Не удалось создать " + versionDir);
        File jsonFile = new File(versionDir, version.getId() + ".json");
        File clientJar = new File(versionDir, version.getId() + ".jar");
        if (!jsonFile.isFile() || jsonFile.length() == 0) {
            logger.accept("Загрузка манифеста Minecraft " + version.getId() + "…");
            downloadFile(version.getUrl(), jsonFile);
        }
        JsonObject manifest;
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(jsonFile.toPath()), StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        if (!manifest.has("downloads") || !manifest.getAsJsonObject("downloads").has("client"))
            throw new IOException("В манифесте Minecraft " + version.getId() + " отсутствует клиентский JAR.");
        JsonObject client = manifest.getAsJsonObject("downloads").getAsJsonObject("client");
        if (!clientJar.isFile() || clientJar.length() == 0) {
            logger.accept("Загрузка клиента Minecraft " + version.getId() + "…");
            downloadFile(client.get("url").getAsString(), clientJar);
        }
        if (client.has("size") && clientJar.length() != client.get("size").getAsLong()) {
            Files.deleteIfExists(clientJar.toPath());
            downloadFile(client.get("url").getAsString(), clientJar);
        }
        logger.accept("Базовый клиент Minecraft " + version.getId() + " подготовлен; библиотеки установит загрузчик.");
    }

    public static void installVersion(GameVersion version, File gameDir, java.util.function.Consumer<String> logger) throws Exception {
        checkCancelled();
        File versionDir = new File(gameDir, "versions/" + version.getId());
        if (!versionDir.exists() && !versionDir.mkdirs()) throw new IllegalStateException("Не удалось создать " + versionDir);

        File jsonFile = new File(versionDir, version.getId() + ".json");
        File clientJar = new File(versionDir, version.getId() + ".jar");
        logger.accept("Загрузка манифеста " + version.getId() + "…");
        downloadFile(version.getUrl(), jsonFile);

        JsonObject manifest;
        try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(jsonFile.toPath()), StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }

        if (manifest.has("downloads") && manifest.getAsJsonObject("downloads").has("client")) {
            String clientUrl = manifest.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
            if (!clientJar.isFile() || clientJar.length() == 0) {
                logger.accept("Загрузка клиента Minecraft…");
                downloadFile(clientUrl, clientJar);
            }
        }

        File librariesDir = new File(gameDir, "libraries");
        JsonArray libraries = manifest.has("libraries") ? manifest.getAsJsonArray("libraries") : new JsonArray();
        for (JsonElement element : libraries) {
            checkCancelled();
            JsonObject library = element.getAsJsonObject();
            if (!library.has("downloads")) continue;
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (!downloads.has("artifact")) continue;
            JsonObject artifact = downloads.getAsJsonObject("artifact");
            File target = new File(librariesDir, artifact.get("path").getAsString());
            if (!target.isFile() || target.length() == 0) {
                target.getParentFile().mkdirs();
                try { downloadFile(artifact.get("url").getAsString(), target); }
                catch (Exception error) { logger.accept("Не удалось скачать библиотеку " + target.getName() + ": " + error.getMessage()); }
            }
        }

        if (manifest.has("assetIndex")) {
            JsonObject index = manifest.getAsJsonObject("assetIndex");
            File indexFile = new File(new File(gameDir, "assets/indexes"), index.get("id").getAsString() + ".json");
            if (!indexFile.isFile() || indexFile.length() == 0) {
                indexFile.getParentFile().mkdirs();
                logger.accept("Загрузка индекса ресурсов…");
                downloadFile(index.get("url").getAsString(), indexFile);
            }
        }
        logger.accept("Базовые файлы версии " + version.getId() + " подготовлены.");
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Загрузка отменена");
    }

    public static void downloadFile(String fileUrl, File destination) throws Exception {
        checkCancelled();
        if (fileUrl == null || fileUrl.isBlank()) throw new IllegalArgumentException("Пустой URL загрузки");
        destination.getParentFile().mkdirs();
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(fileUrl).toURL().openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "NEXUS-Launcher/1.5.2");
            try (InputStream input = connection.getInputStream(); java.io.OutputStream output = Files.newOutputStream(temporary.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    checkCancelled();
                    output.write(buffer, 0, count);
                }
            }
            checkCancelled();
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (connection != null) connection.disconnect();
            Files.deleteIfExists(temporary.toPath());
        }
    }
}
