package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class NativesManager {
    private NativesManager() {}

    public static void extractNatives(File baseDir, File jsonFile, File nativesDir, Consumer<String> logger) {
        try {
            clearDirectory(nativesDir);
            if (!nativesDir.mkdirs() && !nativesDir.isDirectory()) {
                throw new IllegalStateException("Не удалось создать папку natives: " + nativesDir);
            }

            File libDir = new File(baseDir, "libraries");
            if (!jsonFile.isFile()) {
                logger.accept("Предупреждение: JSON версии не найден, natives не распакованы.");
                return;
            }

            int extracted = 0;
            try (FileReader reader = new FileReader(jsonFile)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray libraries = root.has("libraries") && root.get("libraries").isJsonArray()
                        ? root.getAsJsonArray("libraries") : new JsonArray();

                for (JsonElement element : libraries) {
                    if (!element.isJsonObject()) continue;
                    JsonObject library = element.getAsJsonObject();
                    if (library.has("rules") && library.get("rules").isJsonArray()
                            && !rulesAllow(library.getAsJsonArray("rules"))) continue;

                    String classifier = resolveWindowsClassifier(library);
                    if (classifier == null) continue;
                    JsonObject nativeInfo = null;
                    File nativeJar = null;
                    if (library.has("downloads") && library.getAsJsonObject("downloads").has("classifiers")) {
                        JsonObject classifiers = library.getAsJsonObject("downloads").getAsJsonObject("classifiers");
                        if (classifiers.has(classifier) && classifiers.get(classifier).isJsonObject()) {
                            nativeInfo = classifiers.getAsJsonObject(classifier);
                            if (nativeInfo.has("path")) nativeJar = new File(libDir, nativeInfo.get("path").getAsString());
                        }
                    }
                    if (nativeJar == null && library.has("name")) {
                        String[] parts = library.get("name").getAsString().split(":");
                        if (parts.length >= 3) {
                            String relative = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2] + "-" + classifier + ".jar";
                            nativeJar = new File(libDir, relative);
                        }
                    }
                    if (nativeJar == null || !nativeJar.isFile() || nativeJar.length() == 0) continue;
                    if (nativeInfo != null && !isManifestFileValid(nativeJar, nativeInfo)) {
                        throw new IllegalStateException("Native-архив не прошёл SHA-1 проверку: " + nativeJar.getName());
                    }
                    JsonObject extractConfig = library.has("extract") && library.get("extract").isJsonObject()
                            ? library.getAsJsonObject("extract") : null;
                    extracted += extractNativeArchive(nativeJar, nativesDir, extractConfig);
                }
            }

            File versionLibDir = new File(jsonFile.getParentFile(), "lib");
            if (extracted == 0 && versionLibDir.isDirectory()) {
                extracted += extractVersionLocalNatives(versionLibDir, nativesDir);
            }

            logger.accept("Нативные библиотеки подготовлены. Извлечено файлов: " + extracted
                    + ". Используются только библиотеки выбранной версии.");
        } catch (Exception e) {
            logger.accept("Ошибка при распаковке нативных библиотек: " + e.getMessage());
            throw new IllegalStateException("Не удалось подготовить natives для этой версии", e);
        }
    }

    public static int extractPlatformNatives(File baseDir, File jsonFile, File targetDir, Consumer<String> logger) {
        int extracted = 0;
        try {
            if (!targetDir.mkdirs() && !targetDir.isDirectory()) throw new IllegalStateException("Не удалось создать папку " + targetDir);
            File libDir = new File(baseDir, "libraries");
            boolean arm = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT).contains("aarch64");
            try (FileReader reader = new FileReader(jsonFile)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (!root.has("libraries") || !root.get("libraries").isJsonArray()) return 0;
                for (JsonElement element : root.getAsJsonArray("libraries")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject library = element.getAsJsonObject();
                    if (library.has("rules") && library.get("rules").isJsonArray()
                            && !rulesAllow(library.getAsJsonArray("rules"))) continue;
                    String name = library.has("name") ? library.get("name").getAsString() : "";
                    String[] parts = name.split(":");
                    if (parts.length < 4) continue;
                    String classifier = parts[3];
                    if (!(arm ? classifier.equals("natives-windows-arm64") : classifier.equals("natives-windows"))) continue;
                    File jar = null;
                    if (library.has("downloads") && library.get("downloads").isJsonObject()
                            && library.getAsJsonObject("downloads").has("artifact")) {
                        JsonObject artifact = library.getAsJsonObject("downloads").getAsJsonObject("artifact");
                        if (artifact.has("path")) jar = new File(libDir, artifact.get("path").getAsString());
                    }
                    if (jar == null) {
                        String version = parts[2].contains("@") ? parts[2].substring(0, parts[2].indexOf('@')) : parts[2];
                        jar = new File(libDir, parts[0].replace('.', '/') + "/" + parts[1] + "/" + version + "/"
                                + parts[1] + "-" + version + "-" + classifier + ".jar");
                    }
                    if (!jar.isFile() || jar.length() == 0) {
                        logger.accept("ОШИБКА: native-архив не найден: " + name);
                        continue;
                    }
                    extracted += extractNativeArchive(jar, targetDir, null);
                }
            }
            logger.accept("Native-библиотеки (" + targetDir.getName() + "): извлечено файлов " + extracted);
        } catch (Exception e) {
            logger.accept("Ошибка при распаковке native-библиотек в " + targetDir + ": " + e.getMessage());
        }
        return extracted;
    }

    private static String resolveWindowsClassifier(JsonObject library) {
        String mapped = null;
        if (library.has("natives") && library.get("natives").isJsonObject()) {
            JsonObject natives = library.getAsJsonObject("natives");
            if (natives.has("windows")) {
                mapped = natives.get("windows").getAsString()
                        .replace("${arch}", "64");
            }
        }

        if (mapped != null && library.has("downloads")
                && library.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject classifiers = library.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            if (classifiers.has(mapped)) return mapped;
        }
        if (mapped != null && library.has("natives") && library.getAsJsonObject("natives").has("windows")) return mapped;

        if (library.has("downloads") && library.getAsJsonObject("downloads").has("classifiers")) {
            JsonObject classifiers = library.getAsJsonObject("downloads").getAsJsonObject("classifiers");
            if (classifiers.has("natives-windows")) return "natives-windows";
            if (classifiers.has("natives-windows-64")) return "natives-windows-64";
            if (classifiers.has("windows")) return "windows";
        }
        return null;
    }

    private static boolean rulesAllow(JsonArray rules) {
        boolean allowed = false;
        for (JsonElement element : rules) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            if (!ruleMatches(rule)) continue;
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule) {
        if (!rule.has("os") || !rule.get("os").isJsonObject()) return true;
        JsonObject os = rule.getAsJsonObject("os");
        if (os.has("name") && !"windows".equalsIgnoreCase(os.get("name").getAsString())) return false;
        if (os.has("arch")) {
            String arch = os.get("arch").getAsString();
            String runtimeArch = System.getProperty("os.arch", "amd64");
            String metadataArch = runtimeArch.equalsIgnoreCase("amd64") ? "x86_64" : runtimeArch;
            if (!Pattern.matches(arch, runtimeArch) && !Pattern.matches(arch, metadataArch)) return false;
        }
        if (os.has("version")) {
            try {
                return Pattern.compile(os.get("version").getAsString())
                        .matcher(System.getProperty("os.version", "")).find();
            } catch (Exception ignored) {
                return false;
            }
        }
        return true;
    }

    private static int extractNativeArchive(File archive, File destination, JsonObject extractConfig) throws Exception {
        int count = 0;
        JsonArray excludes = extractConfig != null && extractConfig.has("exclude")
                && extractConfig.get("exclude").isJsonArray()
                ? extractConfig.getAsJsonArray("exclude") : new JsonArray();

        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || isExcluded(name, excludes)) continue;

                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!(lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib"))) continue;

                File output = new File(destination, new File(name).getName());
                String root = destination.getCanonicalPath() + File.separator;
                if (!output.getCanonicalPath().startsWith(root)) {
                    throw new IllegalStateException("Недопустимый путь native-файла: " + name);
                }
                output.getParentFile().mkdirs();
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, output.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        return count;
    }

    private static boolean isExcluded(String name, JsonArray excludes) {
        for (JsonElement exclude : excludes) {
            if (name.startsWith(exclude.getAsString())) return true;
        }
        return false;
    }

    private static int extractVersionLocalNatives(File directory, File destination) throws Exception {
        int count = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += extractVersionLocalNatives(file, destination);
            } else if (file.getName().toLowerCase(java.util.Locale.ROOT).contains("natives")
                    && (file.getName().endsWith(".jar") || file.getName().endsWith(".zip"))) {
                count += extractNativeArchive(file, destination, null);
            }
        }
        return count;
    }

    private static boolean isManifestFileValid(File file, JsonObject metadata) throws Exception {
        if (!file.isFile() || file.length() == 0) return false;
        if (metadata.has("size") && file.length() != metadata.get("size").getAsLong()) return false;
        if (metadata.has("sha1")) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
            if (!hex.toString().equalsIgnoreCase(metadata.get("sha1").getAsString())) return false;
        }
        return true;
    }

    private static void downloadFile(String url, File destination) throws Exception {
        destination.getParentFile().mkdirs();
        File temporary = new File(destination.getParentFile(), destination.getName() + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "NexusLauncher/1.2.3");
        try {
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            connection.disconnect();
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static void clearDirectory(File directory) throws Exception {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) clearDirectory(file);
            Files.deleteIfExists(file.toPath());
        }
    }
}
