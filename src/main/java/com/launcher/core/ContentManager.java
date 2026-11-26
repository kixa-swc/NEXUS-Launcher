package com.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ContentManager {
    private static final String INDEX = ".nexus-content.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ICON_BYTES = 400 * 1024;

    private ContentManager() { }

    public enum Kind {
        RESOURCEPACK("resourcepacks", ModrinthClient.TYPE_RESOURCEPACK, "Ресурспак"),
        SHADER("shaderpacks", ModrinthClient.TYPE_SHADER, "Шейдер");

        public final String folder;
        public final String modrinthType;
        public final String label;

        Kind(String folder, String modrinthType, String label) {
            this.folder = folder;
            this.modrinthType = modrinthType;
            this.label = label;
        }
    }

    public record Item(File file, String name, long size, boolean folder, String projectId, String versionId,
                       String versionNumber, byte[] icon) { }

    public static File dir(File gameDir, Kind kind) { return new File(gameDir, kind.folder); }

    public static List<Item> list(File dir, Kind kind) {
        List<Item> result = new ArrayList<>();
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return result;
        JsonObject index = readIndex(dir).getAsJsonObject("files");
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".") || name.endsWith(".part")) continue;
            boolean zip = f.isFile() && name.toLowerCase(Locale.ROOT).endsWith(".zip");
            if (!zip && !f.isDirectory()) continue;
            String base = zip ? name.substring(0, name.length() - 4) : name;
            JsonObject entry = index.has(name) && index.get(name).isJsonObject() ? index.getAsJsonObject(name) : null;
            result.add(new Item(f, base, f.isFile() ? f.length() : folderSize(f), f.isDirectory(),
                    entry == null ? "" : str(entry, "project"), entry == null ? "" : str(entry, "version"),
                    entry == null ? "" : str(entry, "number"),
                    kind == Kind.RESOURCEPACK && zip ? readIcon(f) : null));
        }
        result.sort(Comparator.comparing((Item i) -> i.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    public static String install(File dir, String projectId, ModrinthClient.Release release, Consumer<String> status,
                                 CancelToken cancel) throws Exception {
        Files.createDirectories(dir.toPath());
        String fileName = safeName(release.file().filename());
        status.accept("Загрузка " + fileName + "…");
        ModrinthClient.download(release.file(), new File(dir, fileName), cancel);
        String project = release.projectId().isBlank() ? projectId : release.projectId();
        for (String old : namesOf(dir, project)) {
            if (old.equals(fileName)) continue;
            deleteRecursively(new File(dir, old).toPath());
            forget(dir, old);
        }
        remember(dir, fileName, project, release.id(), release.number());
        return fileName;
    }

    public static String update(File dir, Item item, String mc, Consumer<String> status, CancelToken cancel) throws Exception {
        Item known = identify(dir, item);
        if (known.projectId().isBlank()) throw new IOException("«" + item.name() + "» не найден на Modrinth, обновить его нельзя.");
        ModrinthClient.Release latest = Catalog.latestRelease(known.projectId(), null, mc, ".zip");
        if (latest == null) throw new IOException("Для Minecraft " + mc + " у «" + item.name() + "» нет подходящих версий.");
        if (latest.id().equals(known.versionId())) return null;
        if (!known.versionId().isBlank()) {
            ModrinthClient.Release current = Catalog.releaseById(known.versionId(), ".zip");
            if (current != null && current.published().compareTo(latest.published()) >= 0) return null;
        }
        return install(dir, known.projectId(), latest, status, cancel);
    }

    public static Item identify(File dir, Item item) {
        if (!item.projectId().isBlank() && !item.versionId().isBlank()) return item;
        if (item.folder()) return item;
        try {
            ModrinthClient.Release found = ModrinthClient.releaseByHash(ModrinthClient.sha1(item.file()), ".zip");
            if (found == null || found.projectId().isBlank()) return item;
            remember(dir, item.file().getName(), found.projectId(), found.id(), found.number());
            return new Item(item.file(), item.name(), item.size(), false, found.projectId(), found.id(), found.number(), item.icon());
        } catch (Exception e) {
            return item;
        }
    }

    public static void delete(File dir, Item item) throws IOException {
        try {
            deleteRecursively(item.file().toPath());
        } catch (IOException e) {
            throw new IOException("Не удалось удалить «" + item.file().getName() + "» (возможно, игра запущена).", e);
        }
        forget(dir, item.file().getName());
    }

    private static List<String> namesOf(File dir, String projectId) {
        List<String> names = new ArrayList<>();
        JsonObject files = readIndex(dir).getAsJsonObject("files");
        for (String name : files.keySet())
            if (files.get(name).isJsonObject() && projectId.equals(str(files.getAsJsonObject(name), "project"))) names.add(name);
        return names;
    }

    private static synchronized void remember(File dir, String fileName, String projectId, String versionId, String number) {
        JsonObject root = readIndex(dir);
        JsonObject entry = new JsonObject();
        entry.addProperty("project", projectId);
        entry.addProperty("version", versionId);
        entry.addProperty("number", number == null ? "" : number);
        root.getAsJsonObject("files").add(fileName, entry);
        writeIndex(dir, root);
    }

    private static synchronized void forget(File dir, String fileName) {
        JsonObject root = readIndex(dir);
        if (root.getAsJsonObject("files").remove(fileName) != null) writeIndex(dir, root);
    }

    private static JsonObject readIndex(File dir) {
        JsonObject root = new JsonObject();
        File file = new File(dir, INDEX);
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                var parsed = JsonParser.parseReader(reader);
                if (parsed != null && parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (Exception ignored) { root = new JsonObject(); }
        }
        if (!root.has("files") || !root.get("files").isJsonObject()) root.add("files", new JsonObject());
        return root;
    }

    private static void writeIndex(File dir, JsonObject root) {
        try {
            Files.writeString(new File(dir, INDEX).toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
    }

    private static String safeName(String name) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\") || clean.contains("..")
                || !clean.toLowerCase(Locale.ROOT).endsWith(".zip") || clean.startsWith("."))
            throw new IOException("Недопустимое имя файла: " + name);
        return clean;
    }

    private static byte[] readIcon(File zipFile) {
        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry("pack.png");
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) return null;
            try (var in = zip.getInputStream(entry)) {
                byte[] data = in.readNBytes(MAX_ICON_BYTES + 1);
                return data.length > MAX_ICON_BYTES || data.length == 0 ? null : data;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static long folderSize(File folder) {
        try (Stream<Path> walk = Files.walk(folder.toPath())) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        if (Files.isDirectory(path)) {
            List<Path> all;
            try (Stream<Path> walk = Files.walk(path)) {
                all = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : all) Files.deleteIfExists(p);
        } else {
            Files.deleteIfExists(path);
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
