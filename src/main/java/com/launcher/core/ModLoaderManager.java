package com.launcher.core;

import com.google.gson.*;
import com.launcher.model.GameVersion;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;

public final class ModLoaderManager {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private ModLoaderManager() {}

    public static String ensureInstalled(String loader, String mcVersion, String build, File gameDir,
                                         String javaExe, Consumer<String> log) throws Exception {
        if (loader == null || loader.equalsIgnoreCase("Vanilla")) return mcVersion;
        if (build == null || build.isBlank()) throw new IOException("Выберите версию " + loader + " перед запуском.");
        String installed = findInstalledProfile(loader, mcVersion, build, gameDir);
        if (installed != null) {
            validateProfile(installed, mcVersion, gameDir);
            log.accept(loader + " " + build + " уже установлен: " + installed);
            return installed;
        }
        Files.createDirectories(gameDir.toPath());
        ensureVanilla(mcVersion, gameDir, log);
        ensureLauncherProfile(gameDir, mcVersion);
        String installerName;
        String url;
        List<String> args = new ArrayList<>();
        switch (loader) {
            case "Fabric" -> {
                JsonArray installers = getJson("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray();
                if (installers.isEmpty()) throw new IOException("Официальный сервис Fabric не вернул установщик.");
                String installerVersion = installers.get(0).getAsJsonObject().get("version").getAsString();
                JsonArray compatible = getJson("https://meta.fabricmc.net/v2/versions/loader/" + enc(mcVersion)).getAsJsonArray();
                boolean supported = false;
                for (JsonElement row : compatible) if (build.equals(row.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString())) supported = true;
                if (!supported) throw new IOException("Fabric Loader " + build + " больше не найден среди совместимых сборок Minecraft " + mcVersion + ". Обновите список.");
                installerName = "fabric-installer-" + installerVersion + ".jar";
                url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + installerVersion + "/" + installerName;
                Collections.addAll(args, "client", "-dir", gameDir.getAbsolutePath(), "-mcversion", mcVersion, "-loader", build);
            }
            case "Forge" -> {
                if (!fetchVersions("Forge", mcVersion, false).contains(build)) throw new IOException("Forge " + build + " не является доступной сборкой для Minecraft " + mcVersion + ".");
                installerName = "forge-" + mcVersion + "-" + build + "-installer.jar";
                url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + mcVersion + "-" + build + "/" + installerName;
                Collections.addAll(args, "--installClient", gameDir.getAbsolutePath());
            }
            case "NeoForge" -> {
                if (!fetchVersions("NeoForge", mcVersion, false).contains(build)) throw new IOException("NeoForge " + build + " не является доступной сборкой для Minecraft " + mcVersion + ".");
                installerName = "neoforge-" + build + "-installer.jar";
                url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + build + "/" + installerName;
                Collections.addAll(args, "--install-client", gameDir.getAbsolutePath());
            }
            default -> throw new IOException("Неизвестный загрузчик: " + loader);
        }
        File cache = new File(gameDir, ".nexus-installers"); Files.createDirectories(cache.toPath());
        File installer = new File(cache, installerName);
        if (!installer.isFile() || installer.length() < 4096) {
            Files.deleteIfExists(installer.toPath());
            log.accept("Скачивание официального установщика " + loader + " " + build + "...");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "NEXUS-Launcher/1.5.2").GET().build();
            HttpResponse<Path> response = HTTP.send(request, HttpResponse.BodyHandlers.ofFile(installer.toPath()));
            if (response.statusCode() != 200 || Files.size(installer.toPath()) < 4096) {
                Files.deleteIfExists(installer.toPath());
                throw new IOException("Не удалось скачать установщик " + loader + ": HTTP " + response.statusCode());
            }
        }
        List<String> command = new ArrayList<>(); command.add(javaExe); command.add("-jar"); command.add(installer.getAbsolutePath()); command.addAll(args);
        log.accept("Установка " + loader + " " + build + " для Minecraft " + mcVersion + "...");
        Process process = new ProcessBuilder(command).directory(gameDir).redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), detectInstallerCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) { process.destroyForcibly(); throw new InterruptedException("Установка отменена"); }
                log.accept("[INSTALLER] " + line);
            }
        } catch (InterruptedException ex) { process.destroyForcibly(); throw ex; }
        int exit = process.waitFor();
        if (exit != 0) throw new IOException(loader + " installer завершился с кодом " + exit);
        String profile = findInstalledProfile(loader, mcVersion, build, gameDir);
        if (profile == null) {
            log.accept("Профиль " + loader + " не найден по стандартному шаблону. JSON-файлы в versions:");
            File versionsRoot = new File(gameDir, "versions");
            File[] profileDirs = versionsRoot.listFiles(File::isDirectory);
            if (profileDirs != null) for (File dir : profileDirs) {
                File[] jsonFiles = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
                if (jsonFiles != null) for (File candidate : jsonFiles) {
                    log.accept("  " + dir.getName() + "/" + candidate.getName());
                }
            }
            throw new IOException("Установщик завершился без ошибки, но NEXUS не нашёл JSON-профиль " + loader + " " + build + " для Minecraft " + mcVersion + ". Проверьте список профилей выше и папку versions.");
        }
        validateProfile(profile, mcVersion, gameDir);
        log.accept("Профиль " + loader + " создан: " + profile);
        return profile;
    }

    private static void ensureVanilla(String mcVersion, File gameDir, Consumer<String> log) throws Exception {
        File json = new File(gameDir, "versions/" + mcVersion + "/" + mcVersion + ".json");
        File jar = new File(gameDir, "versions/" + mcVersion + "/" + mcVersion + ".jar");
        if (json.isFile() && jar.isFile() && jar.length() > 0) return;
        log.accept("Подготовка базовой Minecraft " + mcVersion + "...");
        GameVersion base = VersionManager.getAllVersions().stream().filter(v -> mcVersion.equals(v.getId())).findFirst()
                .orElseThrow(() -> new IOException("Minecraft " + mcVersion + " отсутствует в официальном каталоге Mojang."));
        DownloadManager.installBaseFiles(base, gameDir, log);
    }

    public static String findInstalledProfile(String loader, String mcVersion, String build, File gameDir) throws IOException {
        File versions = new File(gameDir, "versions");
        if (!versions.isDirectory()) return null;
        List<String> matches = new ArrayList<>();
        String normalizedBuild = build == null ? "" : build.toLowerCase(Locale.ROOT).trim();
        String normalizedMc = mcVersion.toLowerCase(Locale.ROOT);
        List<Path> candidates;
        try (var paths = Files.walk(versions.toPath(), 4)) {
            candidates = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .filter(p -> !p.getFileName().toString().startsWith(".nexus-"))
                    .toList();
        }
        for (Path candidate : candidates) {
            File json = candidate.toFile();
            File dir = json.getParentFile();
            if (dir == null) continue;
            try (Reader reader = Files.newBufferedReader(json.toPath(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    String parent = string(root, "inheritsFrom").toLowerCase(Locale.ROOT).trim();
                    String jarBase = string(root, "jar").toLowerCase(Locale.ROOT).trim();
                    String id = string(root, "id");
                    if (id.isBlank()) id = json.getName().substring(0, json.getName().length() - 5);
                    String normalizedId = id.toLowerCase(Locale.ROOT);
                    String folderId = dir.equals(versions) ? "" : dir.getName().toLowerCase(Locale.ROOT);
                    String fileId = json.getName().substring(0, json.getName().length() - 5).toLowerCase(Locale.ROOT);
                    String combined = normalizedId + " " + folderId + " " + fileId;
                    String neoForgeMcPrefix = neoForgeVersionPrefix(mcVersion);
                    boolean neoForgeBuildMatchesMc = "NeoForge".equalsIgnoreCase(loader)
                            && normalizedBuild.startsWith(neoForgeMcPrefix);
                    boolean parentMatches = normalizedMc.equals(parent)
                            || parent.startsWith(normalizedMc + "-")
                            || normalizedMc.equals(jarBase)
                            || (parent.isBlank() && (combined.contains(normalizedMc) || neoForgeBuildMatchesMc));
                    if (!parentMatches || !combined.contains(normalizedBuild)) continue;
                    boolean kind = loader.equalsIgnoreCase("Fabric") ? combined.contains("fabric-loader-")
                            : loader.equalsIgnoreCase("Forge") ? (combined.contains("-forge-") || combined.startsWith("forge-")) && !combined.contains("neoforge")
                            : loader.equalsIgnoreCase("NeoForge") && combined.contains("neoforge");
                    if (kind && root.has("mainClass") && root.has("libraries")
                            && root.get("libraries").isJsonArray()) {
                        String profileId = dir.equals(versions) ? json.getName().substring(0, json.getName().length() - 5) : dir.getName();
                        Path canonicalDir = versions.toPath().resolve(profileId);
                        Path canonicalJson = canonicalDir.resolve(profileId + ".json");
                        if (!json.toPath().equals(canonicalJson)) {
                            Files.createDirectories(canonicalDir);
                            if (!Files.exists(canonicalJson)) Files.copy(json.toPath(), canonicalJson);
                        }
                        matches.add(profileId);
                    }
            } catch (Exception ignored) { }
        }
        return matches.stream().distinct().sorted().findFirst().orElse(null);
    }

    private static void ensureLauncherProfile(File gameDir, String mcVersion) throws IOException {
        File file = new File(gameDir, "launcher_profiles.json");
        JsonObject root = new JsonObject();
        if (file.isFile()) {
            try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed != null && parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (Exception ignored) { root = new JsonObject(); }
        }
        if (!root.has("profiles") || !root.get("profiles").isJsonObject()) root.add("profiles", new JsonObject());
        JsonObject profiles = root.getAsJsonObject("profiles");
        JsonObject profile = new JsonObject();
        profile.addProperty("name", "NEXUS");
        profile.addProperty("type", "custom");
        profile.addProperty("lastVersionId", mcVersion);
        profiles.add("NEXUS", profile);
        root.addProperty("selectedProfile", "NEXUS");
        root.addProperty("version", 3);
        if (!root.has("settings")) root.add("settings", new JsonObject());
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
    }

    private static void validateProfile(String id, String mcVersion, File gameDir) throws IOException {
        File json = new File(gameDir, "versions/" + id + "/" + id + ".json");
        try (Reader reader = Files.newBufferedReader(json.toPath(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String parent = string(root, "inheritsFrom");
            String jarBase = string(root, "jar");
            String lowerId = id.toLowerCase(Locale.ROOT);
            boolean directMatch = mcVersion.equals(parent) || mcVersion.equals(jarBase)
                    || lowerId.contains(mcVersion.toLowerCase(Locale.ROOT));
            boolean neoForgeMatch = lowerId.contains("neoforge-")
                    && lowerId.contains(neoForgeVersionPrefix(mcVersion));
            if (!directMatch && !neoForgeMatch)
                throw new IOException("Профиль " + id + " не связан с базовой Minecraft " + mcVersion + ".");
            if (string(root, "mainClass").isBlank()) throw new IOException("В профиле " + id + " отсутствует mainClass.");
            if (!root.has("libraries") || !root.get("libraries").isJsonArray()) throw new IOException("В профиле " + id + " отсутствует список библиотек загрузчика.");
        }
    }

    private static String neoForgeVersionPrefix(String mcVersion) {
        String value = mcVersion == null ? "" : mcVersion.trim();
        if (value.startsWith("1.")) value = value.substring(2);
        String[] parts = value.split("\\.");
        if (parts.length == 1) value += ".0";
        return value + ".";
    }

    public static JsonObject mergedProfileJson(String profileId, File gameDir) throws Exception {
        File profile = new File(gameDir, "versions/" + profileId + "/" + profileId + ".json");
        if (!profile.isFile()) throw new FileNotFoundException("Не найден профиль: " + profile);
        JsonObject child;
        try (Reader reader = Files.newBufferedReader(profile.toPath(), StandardCharsets.UTF_8)) { child = JsonParser.parseReader(reader).getAsJsonObject(); }
        String parentId = string(child, "inheritsFrom");
        if (parentId.isBlank()) return child;
        File parentFile = new File(gameDir, "versions/" + parentId + "/" + parentId + ".json");
        if (!parentFile.isFile()) throw new FileNotFoundException("Профиль " + profileId + " требует базовую версию " + parentId + ", но её JSON отсутствует.");
        JsonObject parent;
        try (Reader reader = Files.newBufferedReader(parentFile.toPath(), StandardCharsets.UTF_8)) { parent = JsonParser.parseReader(reader).getAsJsonObject(); }
        JsonObject merged = parent.deepCopy();
        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey(); JsonElement value = entry.getValue();
            if (key.equals("libraries") && value.isJsonArray()) merged.add(key, mergeLibraries(merged.getAsJsonArray(key), value.getAsJsonArray()));
            else if (key.equals("arguments") && value.isJsonObject()) merged.add(key, mergeArguments(merged.getAsJsonObject(key), value.getAsJsonObject()));
            else if (key.equals("downloads") && value.isJsonObject()) merged.add(key, mergeObjects(merged.getAsJsonObject(key), value.getAsJsonObject()));
            else if (!key.equals("inheritsFrom")) merged.add(key, value.deepCopy());
        }
        merged.addProperty("id", profileId);
        merged.addProperty("inheritsFrom", parentId);
        return merged;
    }

    private static JsonArray mergeLibraries(JsonArray parent, JsonArray child) {
        LinkedHashMap<String, JsonElement> map = new LinkedHashMap<>();
        if (parent != null) for (JsonElement e : parent) map.put(libraryKey(e), e.deepCopy());
        for (JsonElement e : child) map.put(libraryKey(e), e.deepCopy());
        JsonArray out = new JsonArray(); map.values().forEach(out::add); return out;
    }
    private static String libraryKey(JsonElement e) {
        try {
            String coordinate = e.getAsJsonObject().get("name").getAsString();
            String[] parts = coordinate.split(":", 4);
            if (parts.length < 3) return coordinate;
            String versionAndExtension = parts[2];
            String extension = "jar";
            int at = versionAndExtension.indexOf('@');
            if (at >= 0) {
                extension = versionAndExtension.substring(at + 1);
                versionAndExtension = versionAndExtension.substring(0, at);
            }
            String classifier = parts.length == 4 ? parts[3] : "";
            return parts[0] + ":" + parts[1] + ":" + classifier + "@" + extension;
        } catch (Exception ex) { return e.toString(); }
    }
    private static JsonObject mergeArguments(JsonObject parent, JsonObject child) {
        JsonObject out = parent == null ? new JsonObject() : parent.deepCopy();
        for (String key : List.of("game", "jvm")) {
            JsonArray values = new JsonArray();
            if (out.has(key) && out.get(key).isJsonArray()) out.getAsJsonArray(key).forEach(v -> values.add(v.deepCopy()));
            if (child.has(key) && child.get(key).isJsonArray()) child.getAsJsonArray(key).forEach(v -> values.add(v.deepCopy()));
            if (values.size() > 0) out.add(key, values);
        }
        child.entrySet().forEach(e -> { if (!e.getKey().equals("game") && !e.getKey().equals("jvm")) out.add(e.getKey(), e.getValue().deepCopy()); });
        return out;
    }
    private static JsonObject mergeObjects(JsonObject parent, JsonObject child) {
        JsonObject out = parent == null ? new JsonObject() : parent.deepCopy();
        child.entrySet().forEach(e -> out.add(e.getKey(), e.getValue().deepCopy())); return out;
    }

    public static List<String> getVersions(String loader, String mcVersion) throws Exception {
        return fetchVersions(loader, mcVersion, true);
    }

    public static List<String> fetchVersions(String loader, String mcVersion, boolean limited) throws Exception {
        if (loader == null || mcVersion == null || mcVersion.isBlank() || loader.equalsIgnoreCase("Vanilla")) return List.of();
        if (loader.equals("Fabric")) {
            JsonArray rows = getJson("https://meta.fabricmc.net/v2/versions/loader/" + enc(mcVersion)).getAsJsonArray();
            List<String> versions = new ArrayList<>();
            for (JsonElement row : rows) { String v = row.getAsJsonObject().getAsJsonObject("loader").get("version").getAsString(); if (!versions.contains(v)) versions.add(v); }
            return limited ? versions.stream().limit(30).toList() : versions;
        }
        boolean neo = loader.equals("NeoForge");
        String metadataUrl = neo ? "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml" : "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
        String xml = getText(metadataUrl);
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        String prefix = neo ? neoForgeVersionPrefix(mcVersion) : mcVersion + "-";
        List<String> out = new ArrayList<>();
        while (matcher.find()) { String v = matcher.group(1).trim(); if (v.startsWith(prefix)) out.add(neo ? v : v.substring(prefix.length())); }
        out.sort((a,b) -> compareVersions(b,a));
        return limited ? out.stream().distinct().limit(50).toList() : out.stream().distinct().toList();
    }
    private static int compareVersions(String a, String b) {
        String[] x=a.split("[.-]"), y=b.split("[.-]");
        for(int i=0;i<Math.max(x.length,y.length);i++) { long p=i<x.length?number(x[i]):0, q=i<y.length?number(y[i]):0; int c=Long.compare(p,q); if(c!=0)return c; }
        return a.compareToIgnoreCase(b);
    }
    private static long number(String s) { try { return Long.parseLong(s.replaceAll("[^0-9].*$", "")); } catch(Exception e) { return 0; } }
    private static Charset detectInstallerCharset() { return StandardCharsets.UTF_8; }
    private static String string(JsonObject o,String key) { return o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():""; }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static JsonElement getJson(String url) throws Exception { return JsonParser.parseString(getText(url)); }
    private static String getText(String url) throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent","NEXUS-Launcher/1.5.2").GET().build();
        HttpResponse<String> response=HTTP.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new IOException("HTTP "+response.statusCode()+" при запросе официального каталога загрузчика.");
        return response.body();
    }
}
