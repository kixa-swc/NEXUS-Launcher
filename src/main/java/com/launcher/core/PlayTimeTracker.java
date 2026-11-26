package com.launcher.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Properties;

public final class PlayTimeTracker {
    private static final File DIR = new File(System.getProperty("user.home"), ".nexus/playtime/sessions");
    private static volatile File activeSession;
    private static volatile long activeStartedAt;
    private static volatile long activePid;

    private PlayTimeTracker() {}

    public static synchronized void start(Process process, String minecraftVersion, String loader, String loaderVersion) {
        try {
            DIR.mkdirs();
            long pid = process.pid();
            long start = System.currentTimeMillis();
            File session = new File(DIR, "session-" + pid + "-" + start + ".properties");
            Properties p = new Properties();
            p.setProperty("pid", Long.toString(pid));
            p.setProperty("start", Long.toString(start));
            p.setProperty("minecraft", safe(minecraftVersion));
            p.setProperty("loader", safe(loader));
            p.setProperty("loaderVersion", safe(loaderVersion));
            try (FileOutputStream out = new FileOutputStream(session)) { p.store(out, "NEXUS Minecraft play session"); }
            activeSession = session;
            activeStartedAt = start;
            activePid = pid;
            launchDetachedWatcher(pid, start, session);
        } catch (Exception ignored) { }
    }

    private static void launchDetachedWatcher(long pid, long start, File session) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            File endFile = endFile(session);
            String script = "$p=" + pid + "; try { Wait-Process -Id $p -ErrorAction SilentlyContinue } catch {}; " +
                    "$t=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); [IO.File]::WriteAllText('" +
                    endFile.getAbsolutePath().replace("'", "''") + "',[string]$t,[Text.Encoding]::ASCII)";
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script)
                    .redirectErrorStream(true).start();
        } catch (Exception ignored) { }
    }

    public static synchronized void reconcile() {
        DIR.mkdirs();
        File[] sessions = DIR.listFiles((dir, name) -> name.endsWith(".properties"));
        if (sessions == null) return;
        for (File session : sessions) {
            try {
                Properties p = load(session);
                long pid = Long.parseLong(p.getProperty("pid", "0"));
                long start = Long.parseLong(p.getProperty("start", "0"));
                File end = endFile(session);
                if (!end.exists() && pid > 0 && !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    Files.writeString(end.toPath(), Long.toString(Math.max(start, System.currentTimeMillis())), StandardCharsets.US_ASCII);
                }
                if (!end.exists() && pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    activeSession = session; activeStartedAt = start; activePid = pid;
                }
            } catch (Exception ignored) { }
        }
    }

    public static String statusText() {
        reconcile();
        long total = completedMillis();
        File session = activeSession;
        if (session != null && activePid > 0 && ProcessHandle.of(activePid).map(ProcessHandle::isAlive).orElse(false)) {
            long current = Math.max(0, System.currentTimeMillis() - activeStartedAt);
            return "СЕЙЧАС " + format(current) + "  •  ВСЕГО " + format(total + current);
        }
        return "ВСЕГО В ИГРЕ " + format(total);
    }

    public static String totalElapsedText() { return format(completedMillis()); }
    public static String currentElapsedText() {
        File session = activeSession;
        return session == null ? "00:00:00" : format(Math.max(0, System.currentTimeMillis() - activeStartedAt));
    }

    private static long completedMillis() {
        DIR.mkdirs();
        File[] sessions = DIR.listFiles((dir, name) -> name.endsWith(".properties"));
        if (sessions == null) return 0;
        long total = 0;
        for (File session : sessions) try {
            Properties p = load(session);
            long start = Long.parseLong(p.getProperty("start", "0"));
            File end = endFile(session);
            if (end.isFile()) {
                long finish = Long.parseLong(Files.readString(end.toPath()).trim());
                total += Math.max(0, finish - start);
            }
        } catch (Exception ignored) { }
        return total;
    }

    private static Properties load(File file) throws Exception {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(file)) { p.load(in); }
        return p;
    }
    private static File endFile(File session) { return new File(session.getParentFile(), session.getName() + ".end"); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String format(long millis) {
        Duration d = Duration.ofMillis(Math.max(0, millis));
        long hours = d.toHours();
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, d.toMinutesPart(), d.toSecondsPart());
    }
}
