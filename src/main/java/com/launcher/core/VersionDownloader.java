package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class VersionDownloader {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    public static void downloadVersion(File gameDir, String versionId, Consumer<String> logger) {
        try {
            File versionsDir = new File(gameDir, "versions/" + versionId);
            File versionJsonFile = new File(versionsDir, versionId + ".json");
            File versionJarFile = new File(versionsDir, versionId + ".jar");
            File nativesDir = new File(versionsDir, "natives");

            versionsDir.mkdirs();
            nativesDir.mkdirs();

            if (!versionJsonFile.exists()) {
                logger.accept("Загрузка манифеста версий Mojang...");
                String manifestJsonStr = downloadString("https://launchermeta.mojang.com/mc/game/version_manifest.json");
                JsonObject manifest = JsonParser.parseString(manifestJsonStr).getAsJsonObject();
                JsonArray versions = manifest.getAsJsonArray("versions");

                String versionUrl = null;
                for (JsonElement el : versions) {
                    JsonObject v = el.getAsJsonObject();
                    if (v.get("id").getAsString().equals(versionId)) {
                        versionUrl = v.get("url").getAsString();
                        break;
                    }
                }

                if (versionUrl == null) {
                    logger.accept("Ошибка: Версия " + versionId + " не найдена!");
                    return;
                }

                logger.accept("Скачивание JSON для версии " + versionId + "...");
                downloadFile(versionUrl, versionJsonFile);
            }

            JsonObject versionJson;
            try (FileReader reader = new FileReader(versionJsonFile)) {
                versionJson = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (!versionJarFile.exists()) {
                if (versionJson.has("downloads")) {
                    JsonObject downloads = versionJson.getAsJsonObject("downloads");
                    if (downloads.has("client")) {
                        String clientUrl = downloads.getAsJsonObject("client").get("url").getAsString();
                        logger.accept("Скачивание client.jar...");
                        downloadFile(clientUrl, versionJarFile);
                    }
                }
            }

            if (versionJson.has("libraries")) {
                JsonArray libraries = versionJson.getAsJsonArray("libraries");
                File libDir = new File(gameDir, "libraries");
                libDir.mkdirs();

                logger.accept("Загрузка библиотек и распаковка natives...");

                for (JsonElement el : libraries) {
                    JsonObject lib = el.getAsJsonObject();
                    
                    if (lib.has("rules")) {
                        boolean allow = false;
                        JsonArray rules = lib.getAsJsonArray("rules");
                        for (JsonElement ruleEl : rules) {
                            JsonObject rule = ruleEl.getAsJsonObject();
                            String action = rule.get("action").getAsString();
                            if (rule.has("os")) {
                                String osName = rule.getAsJsonObject("os").get("name").getAsString();
                                if ("windows".equals(osName)) {
                                    allow = "allow".equals(action);
                                }
                            } else {
                                allow = "allow".equals(action);
                            }
                        }
                        if (!allow) continue;
                    }

                    if (lib.has("downloads")) {
                        JsonObject downloads = lib.getAsJsonObject("downloads");
                        
                        if (downloads.has("artifact")) {
                            JsonObject artifact = downloads.getAsJsonObject("artifact");
                            String path = artifact.get("url").getAsString();
                            String relPath = artifact.get("path").getAsString();
                            File targetLib = new File(libDir, relPath);
                            
                            if (!targetLib.exists()) {
                                targetLib.getParentFile().mkdirs();
                                try {
                                    downloadFile(path, targetLib);
                                } catch (Exception ignored) {}
                            }
                        }

                        if (downloads.has("classifiers")) {
                            JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                            String nativeKey = classifiers.has("natives-windows") ? "natives-windows" : 
                                              (classifiers.has("natives-windows-64") ? "natives-windows-64" : null);

                            if (nativeKey != null) {
                                JsonObject nativeObj = classifiers.getAsJsonObject(nativeKey);
                                String nativeUrl = nativeObj.get("url").getAsString();
                                String nativePath = nativeObj.get("path").getAsString();
                                File targetNativeJar = new File(libDir, nativePath);

                                if (!targetNativeJar.exists()) {
                                    targetNativeJar.getParentFile().mkdirs();
                                    try {
                                        downloadFile(nativeUrl, targetNativeJar);
                                    } catch (Exception ignored) {}
                                }

                                if (targetNativeJar.exists()) {
                                    extractNatives(targetNativeJar, nativesDir);
                                }
                            }
                        }
                    }
                }
            }

            logger.accept("Версия " + versionId + " полностью готова к запуску!");

        } catch (Exception e) {
            logger.accept("Ошибка загрузки версии: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void extractNatives(File nativeJar, File destDir) {
        try (JarFile jar = new JarFile(nativeJar)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF")) continue;

                File destFile = new File(destDir, entry.getName());
                destFile.getParentFile().mkdirs();

                try (InputStream in = jar.getInputStream(entry);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void downloadFile(String urlStr, File destination) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream in = conn.getInputStream()) {
            destination.getParentFile().mkdirs();
            Files.copy(in, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String downloadString(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}