package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.util.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JavaManager {

    public static int getRequiredJavaMajor(String versionId) {
        if (versionId == null) return 8;
        String v = versionId.toLowerCase();

        if (v.startsWith("26.") || v.startsWith("25.") || v.startsWith("26w") || v.startsWith("25w")) return 25;
        if (v.startsWith("1.21") || v.startsWith("24w")) return 21;
        if (v.equals("1.20.5") || v.equals("1.20.6")) return 21;

        if (v.startsWith("1.20") || v.startsWith("1.19") || v.startsWith("1.18")) return 17;
        if (v.startsWith("1.17")) return 16;

        if (v.startsWith("1.16") || v.startsWith("1.15") || v.startsWith("1.14") || 
            v.startsWith("1.13") || v.startsWith("1.12") || v.startsWith("1.11") || 
            v.startsWith("1.10") || v.startsWith("1.9") || v.startsWith("1.8") || 
            v.startsWith("1.7") || v.startsWith("1.6") || v.startsWith("a") || 
            v.startsWith("b") || v.startsWith("c")) {
            return 8;
        }

        try {
            if (v.startsWith("1.")) {
                String[] parts = v.split("[.-]");
                if (parts.length >= 2) {
                    int minor = Integer.parseInt(parts[1]);
                    if (minor >= 21) return 21;
                    if (minor >= 18) return 17;
                    if (minor == 17) return 16;
                    if (minor <= 16) return 8;
                }
            }
        } catch (Exception ignored) {}

        return 8;
    }

    public static String findOrDownloadJava(int requiredMajor, java.util.function.Consumer<String> logger) throws Exception {
        String customPath = SettingsManager.getSetting("java_custom_" + requiredMajor, "");
        if (customPath != null && !customPath.isBlank() && new File(customPath).isFile()) {
            int customMajor = checkJavaVersion(customPath);
            if (isCompatible(requiredMajor, customMajor)) {
                logger.accept("Используется выбранная пользователем Java " + customMajor + ": " + customPath);
                SettingsManager.saveSetting("java_" + requiredMajor, customPath);
                return customPath;
            }
            logger.accept("Сохранённая Java не подходит для этой версии. Выполняем автоматический поиск.");
        }
        String savedPath = SettingsManager.getSetting("java_" + requiredMajor, null);
        if (savedPath != null && new File(savedPath).exists()) {
            int verifiedMajor = checkJavaVersion(savedPath);
            if (requiredMajor == 8 ? verifiedMajor == 8 : verifiedMajor >= requiredMajor) {
                logger.accept("Найдена сохраненная Java " + requiredMajor + ": " + savedPath);
                return savedPath;
            }
        }

        logger.accept("Ищем подходящую Java " + requiredMajor + " на компьютере...");
        List<File> searchRoots = new ArrayList<>();
        searchRoots.add(new File("C:\\Program Files\\Java"));
        searchRoots.add(new File("C:\\Program Files\\Eclipse Adoptium"));
        searchRoots.add(new File("C:\\Program Files\\Microsoft"));
        searchRoots.add(new File("C:\\Program Files\\BellSoft"));
        searchRoots.add(new File("C:\\Program Files\\Zulu"));
        searchRoots.add(new File("C:\\Program Files\\Amazon Corretto"));
        searchRoots.add(new File("C:\\Program Files (x86)\\Java"));
        searchRoots.add(new File(System.getProperty("user.home"), ".jdks"));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) searchRoots.add(new File(javaHome));
        String localPrograms = System.getenv("LOCALAPPDATA");
        if (localPrograms != null) searchRoots.add(new File(localPrograms, "Programs"));

        for (File root : searchRoots) {
            String found = scanFolderForJava(root, requiredMajor);
            if (found != null) {
                logger.accept("Java успешно найдена: " + found);
                SettingsManager.saveSetting("java_" + requiredMajor, found);
                return found;
            }
        }

        logger.accept("Java " + requiredMajor + " не найдена на ПК. Автоматическая загрузка с сервера...");
        String downloadedPath = downloadJavaRuntime(requiredMajor, logger);
        SettingsManager.saveSetting("java_" + requiredMajor, downloadedPath);
        return downloadedPath;
    }

    private static String scanFolderForJava(File dir, int targetMajor) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File file : files) {
            if (file.isDirectory()) {
                File javaExe = new File(file, "bin/java.exe");
                if (!javaExe.exists()) {
                    javaExe = new File(file, "contents/home/bin/java.exe");
                }
                
                if (javaExe.exists()) {
                    int major = checkJavaVersion(javaExe.getAbsolutePath());
                    if (targetMajor == 8) {
                        if (major == 8) return javaExe.getAbsolutePath();
                    } else {
                        if (major >= targetMajor) return javaExe.getAbsolutePath();
                    }
                }
                
                String deep = scanFolderForJava(file, targetMajor);
                if (deep != null) return deep;
            }
        }
        return null;
    }

    public static int checkJavaVersion(String javaPath) {
        if (javaPath == null || javaPath.isBlank() || !new File(javaPath).isFile()) return 0;
        try {
            File executable = new File(javaPath);
            if (executable.getName().equalsIgnoreCase("javaw.exe")) {
                File consoleJava = new File(executable.getParentFile(), "java.exe");
                if (consoleJava.isFile()) executable = consoleJava;
            }
            Process process = new ProcessBuilder(executable.getAbsolutePath(), "-version").redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) text.append(line).append(' ');
                output = text.toString();
            }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            Matcher matcher = Pattern.compile("(?:version\\s+)?[\"]?(?:1\\.([0-9]+)|([0-9]{1,2}))(?:[._+\"\\s]|$)", Pattern.CASE_INSENSITIVE).matcher(output);
            if (matcher.find()) {
                String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                return Integer.parseInt(value);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static boolean isCompatible(int requiredMajor, int actualMajor) {
        return requiredMajor == 8 ? actualMajor == 8 : actualMajor >= requiredMajor;
    }

    public static int validateAndRememberJava(int requiredMajor, String javaPath) throws Exception {
        int actualMajor = checkJavaVersion(javaPath);
        if (!isCompatible(requiredMajor, actualMajor)) {
            throw new Exception("Для выбранной версии Minecraft нужна Java " + requiredMajor + ", а выбранный файл запускает Java " + (actualMajor == 0 ? "неизвестной версии" : actualMajor) + ". Выбери соответствующий bin\\java.exe.");
        }
        SettingsManager.saveSetting("java_custom_" + requiredMajor, new File(javaPath).getAbsolutePath());
        SettingsManager.saveSetting("java_" + requiredMajor, new File(javaPath).getAbsolutePath());
        return actualMajor;
    }

    private static String downloadJavaRuntime(int major, java.util.function.Consumer<String> logger) throws Exception {
        File javaDir = new File(new File(System.getProperty("user.home"), ".nexus"), "java/java_" + major);
        File javaExe = new File(javaDir, "bin/java.exe");
        if (javaExe.exists() && isCompatible(major, checkJavaVersion(javaExe.getAbsolutePath()))) return javaExe.getAbsolutePath();

        javaDir.mkdirs();
        logger.accept("Java будет установлена в общую папку: " + javaDir.getAbsolutePath());
        String api = "https://api.adoptium.net/v3/assets/feature_releases/" + major + "/ga?os=windows&architecture=x64&image_type=jdk&heap_size=normal";
        HttpURLConnection conn = (HttpURLConnection) new URL(api).openConnection();
        conn.setRequestMethod("GET");
        
        JsonObject release;
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), "UTF-8")) {
            JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
            release = arr.get(0).getAsJsonObject();
        }

        JsonObject binary = release.getAsJsonArray("binaries").get(0).getAsJsonObject();
        String pkgUrl = binary.getAsJsonObject("package").get("link").getAsString();

        logger.accept("Скачивание архива Java " + major + "...");
        File zipFile = new File(javaDir, "java.zip");
        DownloadManager.downloadFile(pkgUrl, zipFile);

        logger.accept("Распаковка Java...");
        unzip(zipFile, javaDir);
        zipFile.delete();

        String found = scanFolderForJava(javaDir, major);
        if (found != null) return found;

        throw new Exception("Не удалось установить Java " + major);
    }

    private static void unzip(File zipFile, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Установка Java остановлена");
                File file = new File(targetDir, entry.getName());
                String targetRoot = targetDir.getCanonicalPath() + File.separator;
                String targetFile = file.getCanonicalPath();
                if (!targetFile.equals(targetDir.getCanonicalPath()) && !targetFile.startsWith(targetRoot)) {
                    throw new IOException("Недопустимый путь в архиве Java: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    file.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Установка Java остановлена");
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}