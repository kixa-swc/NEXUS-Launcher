package com.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModpackInstaller {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MARKER = ".nexus-pack.json";
    private static final int PARALLEL_DOWNLOADS = 4;

    private ModpackInstaller() { }

    public record Result(String name, String versionNumber, String mc, String loader, String build, String profile,
                         File gameDir, int files, int movedMods) { }

    private record Item(String path, List<String> urls, String sha1) { }

    public record Installed(String projectId, String versionId, String number, String name, String mc, String loader,
                            String build, String profile, File gameDir) { }

    private static final List<String> PROTECTED = List.of("saves/", "screenshots/", "logs/", "crash-reports/", "options.txt",
            "servers.dat", "optionsof.txt", "usercache.json");

    public static Result install(String projectId, String fallbackName, File baseDir, Consumer<String> status,
                                 Consumer<String> log, DoubleConsumer progress) throws Exception {
        return install(projectId, fallbackName, baseDir, null, null, status, log, progress, CancelToken.NONE);
    }

    public static Result install(String projectId, String fallbackName, File baseDir, String versionId, String mcFilter,
                                 Consumer<String> status, Consumer<String> log, DoubleConsumer progress,
                                 CancelToken cancel) throws Exception {
        progress.accept(-1);
        ModrinthClient.Release pv;
        if (versionId != null && !versionId.isBlank()) {
            status.accept("Загружаю сведения о выбранной версии…");
            pv = Catalog.releaseById(versionId, ".mrpack");
        } else {
            status.accept("Ищу последнюю версию сборки…");
            pv = Catalog.latestRelease(projectId, null, mcFilter == null || mcFilter.isBlank() ? null : mcFilter, ".mrpack");
        }
        if (pv == null) {
            throw new IOException(mcFilter == null || mcFilter.isBlank() ? "У этой сборки нет файла .mrpack, установить её нельзя."
                    : "У этой сборки нет версии для Minecraft " + mcFilter + ".");
        }
        cancel.check();

        File cache = new File(baseDir, ".nexus-packs");
        Files.createDirectories(cache.toPath());
        File pack = new File(cache, cleanName(pv.file().filename()));
        List<File> created = Collections.synchronizedList(new ArrayList<>());
        File[] backup = {null};
        File[] modsFolder = {null};
        try {
            status.accept("Скачивание сборки…");
            log.accept("Сборка Modrinth: " + fallbackName + " " + pv.number());
            ModrinthClient.download(pv.file(), pack, cancel);

            JsonObject index = readIndex(pack);
            String mc;
            String loader = "Vanilla";
            String build = "";
            String name;
            JsonObject deps = index.has("dependencies") && index.get("dependencies").isJsonObject()
                    ? index.getAsJsonObject("dependencies") : new JsonObject();
            mc = str(deps, "minecraft");
            if (mc.isBlank()) throw new IOException("В сборке не указана версия Minecraft.");
            if (deps.has("fabric-loader")) {
                loader = "Fabric"; build = str(deps, "fabric-loader");
            } else if (deps.has("neoforge")) {
                loader = "NeoForge"; build = str(deps, "neoforge");
            } else if (deps.has("forge")) {
                loader = "Forge"; build = str(deps, "forge");
            } else if (deps.has("quilt-loader")) {
                throw new IOException("Эта сборка требует Quilt, а NEXUS пока ставит только Fabric, Forge и NeoForge.");
            }
            name = str(index, "name").isBlank() ? fallbackName : str(index, "name");

            cancel.check();
            String profile = mc;
            if (!"Vanilla".equals(loader)) {
                status.accept("Установка " + loader + " " + build + " для Minecraft " + mc + "… (эту часть отменить нельзя, дождитесь конца)");
                String javaExe = "java";
                try {
                    String found = JavaManager.findOrDownloadJava(JavaManager.getRequiredJavaMajor(mc), log);
                    if (found != null && !found.isBlank()) javaExe = found;
                } catch (Exception e) {
                    log.accept("Не удалось подготовить Java для установщика, пробую системную: " + e.getMessage());
                }
                final String loaderName = loader;
                profile = ModLoaderManager.ensureInstalled(loader, mc, build, baseDir, javaExe, line -> {
                    log.accept(line);
                    if (!line.startsWith("[INSTALLER]")) status.accept(line);
                });
                log.accept(loaderName + " готов: " + profile);
            }
            cancel.check();

            File gameDir = new File(baseDir, "versions/" + profile + "/.minecraft");
            Files.createDirectories(gameDir.toPath());
            File mods = new File(gameDir, "mods");
            Files.createDirectories(mods.toPath());
            modsFolder[0] = mods;

            int moved = 0;
            boolean samePack = isSamePack(gameDir, projectId);
            if (!samePack) {
                backup[0] = backupExistingMods(gameDir, mods, log);
                moved = backup[0] == null ? 0 : countJars(backup[0]);
            }

            List<Item> items = readItems(index);
            int total = items.size();
            log.accept("Файлов в сборке: " + total);
            downloadAll(items, gameDir, status, progress, cancel, created);

            cancel.check();
            status.accept("Распаковка настроек сборки…");
            progress.accept(0.95);
            Set<String> tracked = new HashSet<>();
            for (Item item : items) tracked.add(normalize(item.path()));
            tracked.addAll(extractOverrides(pack, gameDir, created,
                    new String[]{"overrides/", "client-overrides/"}));

            if (samePack) removeStale(gameDir, readTracked(gameDir), tracked, log);

            writeMarker(gameDir, projectId, pv, name, mc, loader, build, tracked);
            Files.deleteIfExists(pack.toPath());
            progress.accept(1.0);
            return new Result(name, pv.number(), mc, loader, build, profile, gameDir, total, moved);
        } catch (CancellationException e) {
            rollback(created, backup[0], modsFolder[0], log);
            throw e;
        } catch (Exception e) {
            rollback(created, backup[0], modsFolder[0], log);
            throw e;
        } finally {
            try { Files.deleteIfExists(pack.toPath()); } catch (IOException ignored) { }
        }
    }

    public static List<Installed> listInstalled(File baseDir) {
        List<Installed> result = new ArrayList<>();
        File[] profiles = new File(baseDir, "versions").listFiles(File::isDirectory);
        if (profiles == null) return result;
        for (File profile : profiles) {
            File gameDir = new File(profile, ".minecraft");
            File marker = new File(gameDir, MARKER);
            if (!marker.isFile()) continue;
            try (Reader reader = Files.newBufferedReader(marker.toPath(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonObject()) continue;
                JsonObject o = parsed.getAsJsonObject();
                if (str(o, "project").isBlank()) continue;
                result.add(new Installed(str(o, "project"), str(o, "version"), str(o, "number"), str(o, "name"),
                        str(o, "minecraft"), str(o, "loader"), str(o, "build"), profile.getName(), gameDir));
            } catch (Exception ignored) { }
        }
        result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return result;
    }

    public static int uninstall(File gameDir, Consumer<String> log) throws IOException {
        File marker = new File(gameDir, MARKER);
        Set<String> tracked = readTracked(gameDir);
        int deleted = 0;
        if (tracked.isEmpty()) {
            File mods = new File(gameDir, "mods");
            File[] jars = mods.listFiles(f -> {
                String n = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && (n.endsWith(".jar") || n.endsWith(".jar.disabled") || n.equals(".nexus-mods.json"));
            });
            if (jars != null) for (File jar : jars) {
                try {
                    Files.deleteIfExists(jar.toPath());
                    deleted++;
                } catch (IOException e) {
                    throw new IOException("Не удалось удалить файл (возможно, игра запущена): " + jar.getName(), e);
                }
            }
        } else {
            for (String rel : tracked) {
                if (isProtected(rel)) continue;
                File target;
                try { target = resolveSafe(gameDir, rel); } catch (IOException e) { continue; }
                if (!target.isFile()) continue;
                try {
                    Files.delete(target.toPath());
                    deleted++;
                } catch (IOException e) {
                    throw new IOException("Не удалось удалить файл (возможно, игра запущена): " + target.getName(), e);
                }
                removeEmptyParents(target.getParentFile(), gameDir);
            }
        }
        Files.deleteIfExists(marker.toPath());
        log.accept("Сборка удалена, файлов: " + deleted);
        return deleted;
    }

    private static void removeStale(File gameDir, Set<String> previous, Set<String> now, Consumer<String> log) {
        if (previous.isEmpty()) {
            log.accept("Предыдущая установка не хранила список файлов: старые версии модов не удалялись.");
            return;
        }
        int removed = 0;
        for (String rel : previous) {
            if (now.contains(rel) || isProtected(rel)) continue;
            try {
                File target = resolveSafe(gameDir, rel);
                if (target.isFile() && Files.deleteIfExists(target.toPath())) {
                    removed++;
                    removeEmptyParents(target.getParentFile(), gameDir);
                }
            } catch (IOException ignored) { }
        }
        if (removed > 0) log.accept("Убраны файлы прошлой версии сборки: " + removed);
    }

    private static void rollback(List<File> created, File backup, File mods, Consumer<String> log) {
        synchronized (created) {
            for (File file : created) {
                try { Files.deleteIfExists(file.toPath()); } catch (IOException ignored) { }
            }
        }
        if (backup != null && mods != null) {
            File[] saved = backup.listFiles();
            if (saved != null) for (File file : saved) {
                try { Files.move(file.toPath(), new File(mods, file.getName()).toPath()); } catch (IOException ignored) { }
            }
            try { Files.deleteIfExists(backup.toPath()); } catch (IOException ignored) { }
        }
        log.accept("Установка сборки отменена или прервана, изменения откатены.");
    }

    private static boolean isProtected(String rel) {
        String n = normalize(rel);
        for (String p : PROTECTED) if (n.equals(p) || (p.endsWith("/") && n.startsWith(p))) return true;
        return false;
    }

    private static String normalize(String path) {
        String rel = path == null ? "" : path.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        return rel;
    }

    private static void removeEmptyParents(File dir, File stop) {
        File root = stop.getAbsoluteFile();
        File current = dir == null ? null : dir.getAbsoluteFile();
        while (current != null && !current.equals(root) && current.getPath().startsWith(root.getPath())) {
            String[] left = current.list();
            if (left == null || left.length > 0) return;
            if (!current.delete()) return;
            current = current.getParentFile();
        }
    }

    private static int countJars(File dir) {
        File[] files = dir.listFiles();
        int n = 0;
        if (files != null) for (File f : files) if (f.getName().toLowerCase(Locale.ROOT).contains(".jar")) n++;
        return n;
    }

    private static JsonObject readIndex(File pack) throws IOException {
        try (ZipFile zip = new ZipFile(pack)) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) throw new IOException("В файле сборки нет modrinth.index.json — это не сборка Modrinth.");
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed == null || !parsed.isJsonObject()) throw new IOException("Повреждён modrinth.index.json.");
                return parsed.getAsJsonObject();
            }
        }
    }

    private static List<Item> readItems(JsonObject index) throws IOException {
        List<Item> items = new ArrayList<>();
        if (!index.has("files") || !index.get("files").isJsonArray()) return items;
        for (JsonElement el : index.getAsJsonArray("files")) {
            if (!el.isJsonObject()) continue;
            JsonObject file = el.getAsJsonObject();
            if (file.has("env") && file.get("env").isJsonObject()
                    && "unsupported".equals(str(file.getAsJsonObject("env"), "client"))) continue;
            String path = str(file, "path");
            if (path.isBlank()) continue;
            List<String> urls = new ArrayList<>();
            if (file.has("downloads") && file.get("downloads").isJsonArray())
                for (JsonElement u : file.getAsJsonArray("downloads")) if (u.isJsonPrimitive()) urls.add(u.getAsString());
            if (urls.isEmpty()) throw new IOException("У файла " + path + " нет адреса загрузки.");
            String sha1 = file.has("hashes") && file.get("hashes").isJsonObject() ? str(file.getAsJsonObject("hashes"), "sha1") : "";
            items.add(new Item(path, urls, sha1));
        }
        return items;
    }

    private static void downloadAll(List<Item> items, File gameDir, Consumer<String> status, DoubleConsumer progress,
                                    CancelToken cancel, List<File> created) throws Exception {
        int total = items.size();
        if (total == 0) return;
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_DOWNLOADS, r -> {
            Thread t = new Thread(r, "nexus-pack-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Item item : items) {
                File target = resolveSafe(gameDir, item.path());
                futures.add(pool.submit(() -> {
                    cancel.check();
                    if (!target.exists()) created.add(target);
                    fetch(item, target, cancel);
                    int n = done.incrementAndGet();
                    progress.accept(0.1 + 0.85 * n / total);
                    status.accept("Загрузка файлов сборки: " + n + " из " + total);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof Exception ex) throw ex;
                    throw e;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void fetch(Item item, File target, CancelToken cancel) throws Exception {
        if (target.isFile() && !item.sha1().isBlank() && item.sha1().equalsIgnoreCase(sha1Of(target))) return;
        Exception last = null;
        for (String url : item.urls()) {
            try {
                ModrinthClient.downloadPackFile(url, item.sha1(), target, cancel);
                return;
            } catch (CancellationException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("Не удалось скачать " + target.getName() + ": " + (last == null ? "нет адресов" : last.getMessage()), last);
    }

    private static String sha1Of(File file) { return ModrinthClient.sha1(file); }

    private static List<String> extractOverrides(File pack, File gameDir, List<File> created, String[] prefixes) throws IOException {
        List<String> written = new ArrayList<>();
        try (ZipFile zip = new ZipFile(pack)) {
            for (String prefix : prefixes) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory() || !name.startsWith(prefix) || name.length() == prefix.length()) continue;
                    String relative = name.substring(prefix.length());
                    File target = resolveSafe(gameDir, relative);
                    Files.createDirectories(target.getParentFile().toPath());
                    if (!target.exists()) created.add(target);
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    written.add(normalize(relative));
                }
            }
        }
        return written;
    }

    private static File resolveSafe(File gameDir, String relative) throws IOException {
        String rel = relative == null ? "" : relative.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isBlank() || rel.contains(":") || rel.equals("..") || rel.startsWith("../") || rel.contains("/../") || rel.endsWith("/.."))
            throw new IOException("Небезопасный путь в сборке: " + relative);
        Path root = gameDir.toPath().toAbsolutePath().normalize();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("Небезопасный путь в сборке: " + relative);
        return target.toFile();
    }

    private static boolean isSamePack(File gameDir, String projectId) {
        File marker = new File(gameDir, MARKER);
        if (!marker.isFile()) return false;
        try (Reader reader = Files.newBufferedReader(marker.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            return parsed != null && parsed.isJsonObject() && projectId.equals(str(parsed.getAsJsonObject(), "project"));
        } catch (Exception e) {
            return false;
        }
    }

    private static File backupExistingMods(File gameDir, File mods, Consumer<String> log) throws IOException {
        File[] existing = mods.listFiles(f -> {
            String n = f.getName().toLowerCase(Locale.ROOT);
            return f.isFile() && (n.endsWith(".jar") || n.endsWith(".jar.disabled") || n.equals(".nexus-mods.json"));
        });
        if (existing == null || existing.length == 0) return null;
        File backup = new File(gameDir, "mods-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()));
        Files.createDirectories(backup.toPath());
        int moved = 0;
        for (File file : existing) {
            try {
                Files.move(file.toPath(), new File(backup, file.getName()).toPath());
            } catch (IOException e) {
                throw new IOException("Не удалось убрать старый мод (возможно, игра запущена): " + file.getName(), e);
            }
            if (file.getName().toLowerCase(Locale.ROOT).contains(".jar")) moved++;
        }
        log.accept("Старые моды (" + moved + ") перенесены в " + backup.getName());
        return backup;
    }

    private static void writeMarker(File gameDir, String projectId, ModrinthClient.Release pv, String name,
                                    String mc, String loader, String build, Set<String> files) {
        JsonObject o = new JsonObject();
        o.addProperty("project", projectId);
        o.addProperty("version", pv.id());
        o.addProperty("number", pv.number());
        o.addProperty("name", name);
        o.addProperty("minecraft", mc);
        o.addProperty("loader", loader);
        o.addProperty("build", build);
        JsonArray list = new JsonArray();
        List<String> sorted = new ArrayList<>(files);
        Collections.sort(sorted);
        for (String f : sorted) list.add(f);
        o.add("files", list);
        try {
            Files.writeString(new File(gameDir, MARKER).toPath(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private static Set<String> readTracked(File gameDir) {
        Set<String> out = new HashSet<>();
        File marker = new File(gameDir, MARKER);
        if (!marker.isFile()) return out;
        try (Reader reader = Files.newBufferedReader(marker.toPath(), StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed != null && parsed.isJsonObject() && parsed.getAsJsonObject().has("files")
                    && parsed.getAsJsonObject().get("files").isJsonArray()) {
                for (JsonElement e : parsed.getAsJsonObject().getAsJsonArray("files"))
                    if (e.isJsonPrimitive()) out.add(normalize(e.getAsString()));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "pack.mrpack";
        return clean;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
