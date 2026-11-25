package com.launcher.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class HardwareInfo {
    private HardwareInfo() { }

    public record Gpu(String name, long vramMb) { }
    public record Disk(String name, String type, long sizeGb) { }

    public record Info(String os, String cpuName, int cores, int threads, int cpuMhz, long ramTotalMb, long ramFreeMb,
                       int ramSticks, int ramMhz, List<Gpu> gpus, List<Disk> disks, long gameDiskFreeGb) {

        public Gpu mainGpu() {
            Gpu best = null;
            int bestScore = -1;
            for (Gpu g : gpus) {
                int score = LocalAdvisor.gpuTier(g.name()) * 100 + (int) Math.min(99, g.vramMb() / 256);
                if (score > bestScore) { bestScore = score; best = g; }
            }
            return best;
        }

        public boolean hasSsd() {
            for (Disk d : disks) if (!"HDD".equals(d.type())) return true;
            return false;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Система: ").append(os).append('\n');
            sb.append("Процессор: ").append(cpuName);
            if (cores > 0) sb.append(" — ").append(cores).append(" ядер / ").append(threads).append(" потоков");
            if (cpuMhz > 0) sb.append(String.format(Locale.ROOT, ", до %.1f ГГц", cpuMhz / 1000.0));
            sb.append('\n');
            sb.append("Оперативная память: ").append(fmtGb(ramTotalMb)).append(" (свободно сейчас ").append(fmtGb(ramFreeMb)).append(')');
            if (ramSticks > 0) sb.append(", планок: ").append(ramSticks);
            if (ramMhz > 0) sb.append(", ").append(ramMhz).append(" МГц");
            sb.append('\n');
            if (gpus.isEmpty()) sb.append("Видеокарта: не определена\n");
            for (Gpu g : gpus) {
                sb.append("Видеокарта: ").append(g.name());
                if (g.vramMb() > 0) sb.append(", видеопамять ").append(fmtGb(g.vramMb()));
                sb.append('\n');
            }
            if (disks.isEmpty()) sb.append("Накопители: не определены\n");
            for (Disk d : disks) sb.append("Накопитель: ").append(d.name()).append(" — ").append(d.type()).append(", ").append(d.sizeGb()).append(" ГБ\n");
            if (gameDiskFreeGb >= 0) sb.append("Свободно на диске с игрой: ").append(gameDiskFreeGb).append(" ГБ\n");
            return sb.toString().trim();
        }
    }

    static String fmtGb(long mb) {
        double gb = mb / 1024.0;
        return gb >= 10 ? String.format(Locale.ROOT, "%.0f ГБ", gb) : String.format(Locale.ROOT, "%.1f ГБ", gb);
    }

    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference='SilentlyContinue'",
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8",
            "Get-CimInstance Win32_Processor | Select-Object -First 1 | ForEach-Object { \"CPU|$($_.Name)|$($_.NumberOfCores)|$($_.NumberOfLogicalProcessors)|$($_.MaxClockSpeed)\" }",
            "Get-CimInstance Win32_VideoController | ForEach-Object { \"GPU|$($_.Name)|$($_.AdapterRAM)\" }",
            "Get-ItemProperty 'HKLM:\\SYSTEM\\ControlSet001\\Control\\Class\\{4d36e968-e325-11cd-bfc1-08002be10318}\\0*' | ForEach-Object { $m=$_.'HardwareInformation.qwMemorySize'; if ($m) { \"VRAM|$($_.DriverDesc)|$([uint64]$m)\" } }",
            "Get-CimInstance Win32_PhysicalMemory | ForEach-Object { \"RAM|$($_.Capacity)|$($_.ConfiguredClockSpeed)|$($_.Speed)\" }",
            "Get-PhysicalDisk | ForEach-Object { \"DISK|$($_.FriendlyName)|$($_.MediaType)|$($_.BusType)|$($_.Size)\" }");

    public static Info detect(File gameDir) {
        String os = System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "");
        String cpu = "неизвестно";
        int cores = Runtime.getRuntime().availableProcessors(), threads = cores, mhz = 0, sticks = 0, ramMhz = 0;
        List<Gpu> gpus = new ArrayList<>();
        List<Disk> disks = new ArrayList<>();
        java.util.Map<String, Long> vram = new java.util.HashMap<>();
        long physSticksMb = 0;

        long totalMb = 0, freeMb = 0;
        try {
            com.sun.management.OperatingSystemMXBean bean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            totalMb = bean.getTotalMemorySize() / (1024L * 1024L);
            freeMb = bean.getFreeMemorySize() / (1024L * 1024L);
        } catch (Throwable ignored) { }

        if (os.toLowerCase(Locale.ROOT).contains("win")) {
            for (String line : runPowerShell()) {
                String[] p = line.split("\\|", -1);
                try {
                    switch (p[0]) {
                        case "CPU" -> { cpu = p[1].trim().replaceAll("\\s+", " "); cores = toInt(p[2], cores); threads = toInt(p[3], threads); mhz = toInt(p[4], 0); }
                        case "GPU" -> gpus.add(new Gpu(p[1].trim(), toLong(p[2]) / (1024 * 1024)));
                        case "VRAM" -> vram.put(p[1].trim(), toLong(p[2]) / (1024 * 1024));
                        case "RAM" -> { sticks++; physSticksMb += toLong(p[1]) / (1024 * 1024); ramMhz = Math.max(ramMhz, Math.max(toInt(p[2], 0), toInt(p[3], 0))); }
                        case "DISK" -> disks.add(new Disk(p[1].trim(), diskType(p[2], p[3]), toLong(p[4]) / 1_000_000_000L));
                        default -> { }
                    }
                } catch (RuntimeException ignored) { }
            }
            for (int i = 0; i < gpus.size(); i++) {
                Long real = vram.get(gpus.get(i).name());
                if (real != null && real > 0) gpus.set(i, new Gpu(gpus.get(i).name(), real));
            }
        }
        if (totalMb <= 0) totalMb = Math.max(physSticksMb, 4096);
        long freeGb = -1;
        try {
            File probe = gameDir != null ? gameDir : new File(System.getProperty("user.home"));
            while (probe != null && !probe.exists()) probe = probe.getParentFile();
            if (probe != null) freeGb = probe.getUsableSpace() / (1024L * 1024L * 1024L);
        } catch (RuntimeException ignored) { }
        return new Info(os.trim(), cpu, cores, threads, mhz, totalMb, freeMb, sticks, ramMhz, gpus, disks, freeGb);
    }

    private static String diskType(String media, String bus) {
        String m = media == null ? "" : media.trim().toUpperCase(Locale.ROOT), b = bus == null ? "" : bus.trim().toUpperCase(Locale.ROOT);
        if (b.equals("NVME")) return "NVMe SSD";
        if (m.equals("SSD")) return "SSD";
        if (m.equals("HDD")) return "HDD";
        return b.equals("USB") ? "USB-накопитель" : "SSD/HDD (тип не определён)";
    }

    private static List<String> runPowerShell() {
        List<String> lines = new ArrayList<>();
        try {
            String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-EncodedCommand", encoded).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly();
            for (String line : new String(out, StandardCharsets.UTF_8).split("\\R")) {
                String t = line.trim();
                if (t.startsWith("CPU|") || t.startsWith("GPU|") || t.startsWith("VRAM|") || t.startsWith("RAM|") || t.startsWith("DISK|")) lines.add(t);
            }
        } catch (Exception ignored) { }
        return lines;
    }

    private static int toInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return fallback; }
    }

    private static long toLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }
}
