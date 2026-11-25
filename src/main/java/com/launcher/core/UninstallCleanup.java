package com.launcher.core;

import com.launcher.util.FileUtils;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class UninstallCleanup {
    private UninstallCleanup() {}

    private static final List<String> GAME_ROOT_ENTRIES = List.of(
            "versions", "libraries", "assets", "logs", ".nexus-installers", "launcher_profiles.json", "launcher_accounts.json");
    private static final Set<String> USER_CONTENT = Set.of("saves", "screenshots", "mods", "resourcepacks", "shaderpacks", "config");

    public record Target(String label, List<File> files) {
        public long sizeBytes() {
            long total = 0;
            for (File f : files) total += size(f.toPath());
            return total;
        }
    }

    public record Result(int deleted, List<String> failed, boolean keptUserContent) { }

    public static File gameRoot() { return FileUtils.getMinecraftDir(); }

    public static List<Target> targets() {
        List<Target> list = new ArrayList<>();
        File home = new File(System.getProperty("user.home"));
        File nexus = new File(home, ".nexus");
        if (nexus.exists()) list.add(new Target("Данные NEXUS: Java, аккаунты, скины, статистика (" + nexus.getAbsolutePath() + ")", List.of(nexus)));
        File settings = SettingsManager.configFile();
        if (settings.exists()) list.add(new Target("Настройки лаунчера (" + settings.getAbsolutePath() + ")", List.of(settings)));
        File root = gameRoot();
        List<File> gameFiles = new ArrayList<>();
        for (String name : GAME_ROOT_ENTRIES) {
            File f = new File(root, name);
            if (f.exists()) gameFiles.add(f);
        }
        if (!gameFiles.isEmpty()) list.add(new Target("Файлы игры: версии, библиотеки, ресурсы (" + root.getAbsolutePath() + ")", gameFiles));
        return list;
    }

    public static Result run(boolean keepUserContent, Consumer<String> log) {
        int[] deleted = {0};
        List<String> failed = new ArrayList<>();
        File home = new File(System.getProperty("user.home"));
        File root = gameRoot();

        for (String name : GAME_ROOT_ENTRIES) {
            File f = new File(root, name);
            if (!f.exists()) continue;
            if (keepUserContent && name.equals("versions")) wipeVersions(f.toPath(), deleted, failed);
            else deleteTree(f.toPath(), p -> false, deleted, failed);
            log.accept("Удалено: " + f.getAbsolutePath());
        }
        File canonicalRoot = canonical(root);
        if (canonicalRoot != null && canonicalRoot.getParentFile() != null && !canonicalRoot.equals(canonical(home))) {
            String[] left = canonicalRoot.list();
            if (left != null && left.length == 0 && canonicalRoot.delete()) log.accept("Удалена пустая папка игры: " + canonicalRoot);
        }

        File nexus = new File(home, ".nexus");
        if (nexus.exists()) {
            deleteTree(nexus.toPath(), p -> false, deleted, failed);
            log.accept("Удалено: " + nexus.getAbsolutePath());
        }
        try {
            new ProcessBuilder("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "NexusLauncher", "/f")
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) { }
        File settings = SettingsManager.configFile();
        SettingsManager.freeze();
        if (settings.exists()) {
            deleteTree(settings.toPath(), p -> false, deleted, failed);
            log.accept("Удалено: " + settings.getAbsolutePath());
        }
        return new Result(deleted[0], failed, keepUserContent);
    }

    private static void wipeVersions(Path versions, int[] deleted, List<String> failed) {
        deleteTree(versions, p -> {
            Path rel = versions.relativize(p);
            return rel.getNameCount() >= 3 && rel.getName(1).toString().equals(".minecraft")
                    && USER_CONTENT.contains(rel.getName(2).toString());
        }, deleted, failed);
    }

    private static void deleteTree(Path start, java.util.function.Predicate<Path> keep, int[] deleted, List<String> failed) {
        if (!Files.exists(start)) return;
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!keep.test(file)) remove(file, deleted, failed);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    failed.add(file.toString());
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try { Files.delete(dir); deleted[0]++; } catch (IOException ignored) { }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            failed.add(start + " (" + e.getMessage() + ")");
        }
    }

    private static void remove(Path file, int[] deleted, List<String> failed) {
        try {
            Files.deleteIfExists(file);
            deleted[0]++;
        } catch (IOException first) {
            try {
                file.toFile().setWritable(true);
                Files.deleteIfExists(file);
                deleted[0]++;
            } catch (IOException second) {
                failed.add(file.toString());
            }
        }
    }

    static long size(Path start) {
        if (!Files.exists(start)) return 0;
        long[] total = {0};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { }
        return total[0];
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " КБ";
        if (bytes < 1024L * 1024 * 1024) return String.format("%.0f МБ", bytes / 1048576.0);
        return String.format("%.1f ГБ", bytes / 1073741824.0);
    }

    private static File canonical(File f) {
        try { return f.getCanonicalFile(); } catch (IOException e) { return null; }
    }

    public static String findProductCode() {
        String[] roots = {
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
                "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"
        };
        for (String root : roots) {
            try {
                Process p = new ProcessBuilder("reg", "query", root, "/s", "/v", "DisplayName").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), Charset.defaultCharset());
                p.waitFor();
                String key = null;
                for (String raw : out.split("\\R")) {
                    String line = raw.trim();
                    if (line.regionMatches(true, 0, "HKEY", 0, 4)) { key = line; continue; }
                    if (key != null && line.startsWith("DisplayName")) {
                        String value = line.replaceFirst("^DisplayName\\s+REG_\\w+\\s+", "").trim();
                        String name = key.substring(key.lastIndexOf('\\') + 1);
                        if (value.equalsIgnoreCase("NEXUS") && name.startsWith("{") && name.endsWith("}")) return name;
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    public static boolean startSystemUninstall(String productCode) {
        if (productCode == null || !productCode.matches("\\{[0-9A-Fa-f-]{36}\\}")) return false;
        try {
            Path script = Files.createTempFile("nexus-uninstall-", ".ps1");
            try (InputStream in = UninstallCleanup.class.getResourceAsStream("/scripts/uninstall.ps1")) {
                if (in == null) return false;
                Files.write(script, in.readAllBytes());
            }
            String launch = "try { Start-Process -FilePath 'powershell.exe' -Verb RunAs -WindowStyle Hidden -ArgumentList @("
                    + "'-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File','\"" + psq(script.toString()) + "\"',"
                    + "'-ProductCode','" + productCode + "','-ParentPid','" + ProcessHandle.current().pid() + "',"
                    + "'-InstallDir','\"" + psq(installDir()) + "\"'); exit 0 } catch { exit 1 }";
            String encoded = Base64.getEncoder().encodeToString(launch.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-WindowStyle", "Hidden", "-EncodedCommand", encoded).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(180, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String installDir() {
        try {
            String cmd = ProcessHandle.current().info().command().orElse("");
            if (cmd.isEmpty()) return "";
            File exe = new File(cmd);
            if (exe.getName().toLowerCase().startsWith("java")) return "";
            File dir = exe.getParentFile();
            return dir == null ? "" : dir.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    private static String psq(String s) { return s.replace("'", "''"); }
}
