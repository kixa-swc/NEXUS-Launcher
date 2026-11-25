package com.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModManager {
    private static final String INDEX = ".nexus-mods.json";
    private static final String DISABLED = ".disabled";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern TOML_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_VERSION = Pattern.compile("(?m)^\\s*version\\s*=\\s*[\"']([^\"']+)[\"']");

    private ModManager() { }

    public record Mod(File file, String baseName, boolean enabled, String name, String version, long size, String projectId,
                      byte[] icon, String versionId) { }

    private record Meta(String name, String version, byte[] icon) { }

    private static final int MAX_ICON_BYTES = 400 * 1024;
    private static final Pattern TOML_LOGO = Pattern.compile("(?m)^\\s*logoFile\\s*=\\s*[\"']([^\"']+)[\"']");

    public static List<Mod> list(File modsDir) {
        List<Mod> result = new ArrayList<>();
        File[] files = modsDir == null ? null : modsDir.listFiles();
        if (files == null) return result;
        JsonObject index = readIndex(modsDir).getAsJsonObject("files");
        for (File f : files) {
            String name = f.getName();
            String lower = name.toLowerCase(Locale.ROOT);
            boolean disabled = lower.endsWith(".jar" + DISABLED);
            if (!f.isFile() || !(disabled || lower.endsWith(".jar"))) continue;
            String base = disabled ? name.substring(0, name.length() - DISABLED.length()) : name;
            Meta meta = readMeta(f);
            String shown = meta.name().isBlank() ? base.substring(0, base.length() - 4) : meta.name();
            String project = index.has(base) && index.get(base).isJsonObject() ? str(index.getAsJsonObject(base), "project") : "";
            String versionId = index.has(base) && index.get(base).isJsonObject() ? str(index.getAsJsonObject(base), "version") : "";
            result.add(new Mod(f, base, !disabled, shown, meta.version(), f.length(), project, meta.icon(), versionId));
        }
        result.sort(Comparator.comparing((Mod m) -> m.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    public static void setEnabled(Mod mod, boolean enabled) throws IOException {
        File target = new File(mod.file().getParentFile(), enabled ? mod.baseName() : mod.baseName() + DISABLED);
        if (target.equals(mod.file())) return;
        if (target.exists()) throw new IOException("Файл " + target.getName() + " уже существует.");
        try {
            Files.move(mod.file().toPath(), target.toPath());
        } catch (IOException e) {
            throw new IOException("Не удалось переименовать файл (возможно, игра запущена): " + mod.file().getName(), e);
        }
    }

    public static void delete(Mod mod) throws IOException {
        try {
            Files.deleteIfExists(mod.file().toPath());
        } catch (IOException e) {
            throw new IOException("Не удалось удалить файл (возможно, игра запущена): " + mod.file().getName(), e);
        }
        forget(mod.file().getParentFile(), mod.baseName());
    }

    public static List<String> install(File modsDir, String loader, String mcVersion, String projectId,
                                       Consumer<String> status) throws Exception {
        return install(modsDir, loader, mcVersion, projectId, status, CancelToken.NONE);
    }

    public static List<String> install(File modsDir, String loader, String mcVersion, String projectId,
                                       Consumer<String> status, CancelToken cancel) throws Exception {
        Files.createDirectories(modsDir.toPath());
        List<String> installed = new ArrayList<>();
        installRecursive(modsDir, loader, mcVersion, projectId, "", status, installed, new HashSet<>(), 0, cancel);
        return installed;
    }

    private static void installRecursive(File dir, String loader, String mc, String projectId, String versionId,
                                         Consumer<String> status, List<String> installed, Set<String> seen, int depth,
                                         CancelToken cancel) throws Exception {
        cancel.check();
        if (depth > 4 || !seen.add(projectId)) return;
        if (isInstalled(dir, projectId)) {
            if (depth == 0) throw new IOException("Этот мод уже установлен.");
            return;
        }
        ModrinthClient.Release release = Catalog.latestRelease(projectId, loader, mc, ".jar");
        if (release == null && versionId != null && !versionId.isBlank()) release = Catalog.releaseById(versionId, ".jar");
        if (release == null) {
            if (depth == 0) throw new IOException("Для " + loader + " и Minecraft " + mc + " у этого мода нет подходящей версии.");
            status.accept("Не найдена обязательная зависимость, пропускаю.");
            return;
        }
        installFile(dir, loader, mc, projectId, release, status, installed, seen, depth, cancel);
    }

    public static List<String> installRelease(File dir, String loader, String mc, String projectId,
                                              ModrinthClient.Release release, Consumer<String> status,
                                              CancelToken cancel) throws Exception {
        Files.createDirectories(dir.toPath());
        List<String> installed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(projectId);
        installFile(dir, loader, mc, projectId, release, status, installed, seen, 0, cancel);
        return installed;
    }

    private static void installFile(File dir, String loader, String mc, String projectId, ModrinthClient.Release release,
                                    Consumer<String> status, List<String> installed, Set<String> seen, int depth,
                                    CancelToken cancel) throws Exception {
        String fileName = safeFileName(release.file().filename());
        status.accept("Загрузка " + fileName + "…");
        ModrinthClient.download(release.file(), new File(dir, fileName), cancel);
        String project = release.projectId().isBlank() ? projectId : release.projectId();
        boolean wasDisabled = false;
        for (String old : filesOf(dir, project)) {
            File on = new File(dir, old);
            File off = new File(dir, old + DISABLED);
            if (off.isFile()) { wasDisabled = true; Files.deleteIfExists(off.toPath()); }
            if (!old.equals(fileName)) {
                Files.deleteIfExists(on.toPath());
                forget(dir, old);
            }
        }
        if (wasDisabled) {
            Files.move(new File(dir, fileName).toPath(), new File(dir, fileName + DISABLED).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        remember(dir, fileName, project, release.id());
        installed.add(fileName);
        for (ModrinthClient.Dependency dep : release.required())
            installRecursive(dir, loader, mc, dep.projectId(), dep.versionId(), status, installed, seen, depth + 1, cancel);
    }

    public static String update(File dir, String loader, String mc, Mod mod, Consumer<String> status,
                                CancelToken cancel) throws Exception {
        Mod known = identify(dir, mod);
        if (known.projectId().isBlank()) throw new IOException("«" + mod.name() + "» не найден на Modrinth, обновить его нельзя.");
        ModrinthClient.Release latest = Catalog.latestRelease(known.projectId(), loader, mc, ".jar");
        if (latest == null) throw new IOException("Для " + loader + " " + mc + " у «" + mod.name() + "» нет подходящих версий.");
        if (latest.id().equals(known.versionId())) return null;
        if (!known.versionId().isBlank()) {
            ModrinthClient.Release current = Catalog.releaseById(known.versionId(), ".jar");
            if (current != null && current.published().compareTo(latest.published()) >= 0) return null;
        }
        List<String> files = installRelease(dir, loader, mc, known.projectId(), latest, status, cancel);
        return files.isEmpty() ? null : files.get(0);
    }

    public static Mod identify(File dir, Mod mod) {
        if (!mod.projectId().isBlank() && !mod.versionId().isBlank()) return mod;
        try {
            ModrinthClient.Release found = ModrinthClient.releaseByHash(ModrinthClient.sha1(mod.file()), ".jar");
            if (found == null || found.projectId().isBlank()) return mod;
            remember(dir, mod.baseName(), found.projectId(), found.id());
            return new Mod(mod.file(), mod.baseName(), mod.enabled(), mod.name(), mod.version(), mod.size(), found.projectId(),
                    mod.icon(), found.id());
        } catch (Exception e) {
            return mod;
        }
    }

    private static List<String> filesOf(File dir, String projectId) {
        List<String> names = new ArrayList<>();
        JsonObject files = readIndex(dir).getAsJsonObject("files");
        for (String base : files.keySet())
            if (files.get(base).isJsonObject() && projectId.equals(str(files.getAsJsonObject(base), "project"))) names.add(base);
        return names;
    }

    private static String safeFileName(String name) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.contains("/") || clean.contains("\\") || clean.contains("..")
                || !clean.toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Недопустимое имя файла мода: " + name);
        return clean;
    }

    private static boolean isInstalled(File dir, String projectId) {
        JsonObject files = readIndex(dir).getAsJsonObject("files");
        for (String base : files.keySet()) {
            if (!files.get(base).isJsonObject() || !projectId.equals(str(files.getAsJsonObject(base), "project"))) continue;
            if (new File(dir, base).isFile() || new File(dir, base + DISABLED).isFile()) return true;
        }
        return false;
    }

    private static synchronized void remember(File dir, String baseName, String projectId, String versionId) {
        JsonObject root = readIndex(dir);
        JsonObject entry = new JsonObject();
        entry.addProperty("project", projectId);
        entry.addProperty("version", versionId);
        root.getAsJsonObject("files").add(baseName, entry);
        writeIndex(dir, root);
    }

    private static synchronized void forget(File dir, String baseName) {
        JsonObject root = readIndex(dir);
        if (root.getAsJsonObject("files").remove(baseName) != null) writeIndex(dir, root);
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

    private static Meta readMeta(File jar) {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry fabric = zip.getEntry("fabric.mod.json");
            if (fabric != null) {
                try (Reader reader = new InputStreamReader(zip.getInputStream(fabric), StandardCharsets.UTF_8)) {
                    JsonObject o = JsonParser.parseReader(reader).getAsJsonObject();
                    String name = str(o, "name");
                    return new Meta(name.isBlank() ? str(o, "id") : name, cleanVersion(str(o, "version")),
                            readIcon(zip, fabricIconPath(o)));
                }
            }
            for (String path : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                ZipEntry toml = zip.getEntry(path);
                if (toml == null) continue;
                String text = new String(zip.getInputStream(toml).readAllBytes(), StandardCharsets.UTF_8);
                Matcher name = TOML_NAME.matcher(text);
                Matcher version = TOML_VERSION.matcher(text);
                Matcher logo = TOML_LOGO.matcher(text);
                return new Meta(name.find() ? name.group(1) : "", version.find() ? cleanVersion(version.group(1)) : "",
                        readIcon(zip, logo.find() ? logo.group(1) : ""));
            }
        } catch (Exception ignored) { }
        return new Meta("", "", null);
    }

    private static String fabricIconPath(JsonObject o) {
        if (!o.has("icon")) return "";
        var icon = o.get("icon");
        if (icon.isJsonPrimitive()) return icon.getAsString();
        if (!icon.isJsonObject()) return "";
        String best = "";
        long bestScore = Long.MIN_VALUE;
        for (var entry : icon.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            int size;
            try { size = Integer.parseInt(entry.getKey().trim()); } catch (NumberFormatException e) { size = 0; }
            long score = size <= 256 ? size : 100_000L - size;
            if (score > bestScore) { bestScore = score; best = entry.getValue().getAsString(); }
        }
        return best;
    }

    private static byte[] readIcon(ZipFile zip, String path) {
        try {
            if (path == null) return null;
            String clean = path.trim();
            while (clean.startsWith("/")) clean = clean.substring(1);
            String lower = clean.toLowerCase(Locale.ROOT);
            if (clean.isEmpty() || clean.contains("..") || !(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".gif"))) return null;
            ZipEntry entry = zip.getEntry(clean);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) return null;
            try (var in = zip.getInputStream(entry)) {
                byte[] data = in.readNBytes(MAX_ICON_BYTES + 1);
                return data.length > MAX_ICON_BYTES || data.length == 0 ? null : data;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String cleanVersion(String v) { return v == null || v.startsWith("${") ? "" : v; }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
