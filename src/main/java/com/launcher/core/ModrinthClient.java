package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ModrinthClient {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "NEXUS-Launcher/1.7 (Minecraft launcher)";
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    public static final String TYPE_MOD = "mod";
    public static final String TYPE_PACK = "modpack";
    public static final String TYPE_RESOURCEPACK = "resourcepack";
    public static final String TYPE_SHADER = "shader";

    private ModrinthClient() { }

    public record Project(String id, String title, String author, String description, long downloads,
                          String iconUrl, String imageUrl, List<String> categories, List<String> versions) {
        public String latestMinecraft() {
            String best = "";
            for (String v : versions) {
                if (!v.matches("\\d+\\.\\d+(\\.\\d+)?")) continue;
                if (best.isEmpty() || compareMc(v, best) > 0) best = v;
            }
            return best;
        }

        public List<String> loaders() {
            List<String> out = new ArrayList<>();
            if (categories.contains("fabric")) out.add("Fabric");
            if (categories.contains("forge")) out.add("Forge");
            if (categories.contains("neoforge")) out.add("NeoForge");
            return out;
        }

        public String previewUrl() { return imageUrl == null || imageUrl.isBlank() ? iconUrl : imageUrl; }
    }

    public record ModFile(String url, String filename, String sha1) { }
    public record Dependency(String projectId, String versionId) { }

    public record Release(String id, String projectId, String number, String name, String type, String published,
                          List<String> gameVersions, List<String> loaders, ModFile file, List<Dependency> required) {
        public String typeLabel() {
            return switch (type == null ? "" : type) {
                case "release" -> "Релиз";
                case "beta" -> "Бета";
                case "alpha" -> "Альфа";
                default -> type == null || type.isBlank() ? "" : type;
            };
        }

        public String date() { return published != null && published.length() >= 10 ? published.substring(0, 10) : ""; }
    }

    public static List<Project> search(String query, String loader, String mcVersion, int limit) throws IOException {
        return searchProjects(TYPE_MOD, query, loader, mcVersion, limit);
    }

    public static List<Project> searchPacks(String query, String loader, String mcVersion, int limit) throws IOException {
        return searchProjects(TYPE_PACK, query, loader, mcVersion, limit);
    }

    public static List<Project> searchProjects(String type, String query, String loader, String mcVersion, int limit) throws IOException {
        StringBuilder facets = new StringBuilder("[[\"project_type:").append(type).append("\"]");
        if (loader != null && !loader.isBlank()) facets.append(",[\"categories:").append(loader.toLowerCase(Locale.ROOT)).append("\"]");
        if (mcVersion != null && !mcVersion.isBlank()) facets.append(",[\"versions:").append(mcVersion).append("\"]");
        facets.append("]");
        String q = query == null ? "" : query.trim();
        String url = API + "/search?query=" + enc(q) + "&facets=" + enc(facets.toString())
                + "&index=" + (q.isEmpty() ? "downloads" : "relevance") + "&limit=" + Math.max(1, Math.min(50, limit));
        JsonObject root = JsonParser.parseString(get(url)).getAsJsonObject();
        List<Project> result = new ArrayList<>();
        JsonArray hits = root.has("hits") && root.get("hits").isJsonArray() ? root.getAsJsonArray("hits") : new JsonArray();
        for (JsonElement el : hits) {
            if (!el.isJsonObject()) continue;
            Project project = parseProject(el.getAsJsonObject());
            if (project == null) continue;
            if (TYPE_PACK.equals(type) && project.loaders().isEmpty()) continue;
            result.add(project);
        }
        return result;
    }

    public static List<Project> projects(List<String> ids) throws IOException {
        List<Project> result = new ArrayList<>();
        if (ids == null || ids.isEmpty()) return result;
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) array.append(i == 0 ? "" : ",").append('"').append(ids.get(i)).append('"');
        array.append("]");
        JsonElement parsed = JsonParser.parseString(get(API + "/projects?ids=" + enc(array.toString())));
        if (!parsed.isJsonArray()) return result;
        for (JsonElement el : parsed.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            Project project = parseProject(el.getAsJsonObject());
            if (project != null) result.add(project);
        }
        return result;
    }

    private static Project parseProject(JsonObject o) {
        String id = str(o, "project_id");
        if (id.isBlank()) id = str(o, "id");
        if (id.isBlank()) return null;
        long downloads = o.has("downloads") && o.get("downloads").isJsonPrimitive() ? o.get("downloads").getAsLong() : 0L;
        String image = str(o, "featured_gallery");
        if (image.isBlank() && o.has("gallery") && o.get("gallery").isJsonArray()) {
            String first = "";
            for (JsonElement g : o.getAsJsonArray("gallery")) {
                if (!g.isJsonObject()) continue;
                JsonObject gallery = g.getAsJsonObject();
                String url = str(gallery, "url");
                if (first.isBlank()) first = url;
                if (gallery.has("featured") && gallery.get("featured").isJsonPrimitive() && gallery.get("featured").getAsBoolean()) { image = url; break; }
            }
            if (image.isBlank()) image = first;
        }
        List<String> categories = strings(o, "categories");
        for (String extra : strings(o, "additional_categories")) if (!categories.contains(extra)) categories.add(extra);
        for (String loader : strings(o, "loaders")) if (!categories.contains(loader)) categories.add(loader);
        List<String> versions = strings(o, "versions");
        if (versions.isEmpty()) versions = strings(o, "game_versions");
        return new Project(id, str(o, "title"), str(o, "author"), str(o, "description"), downloads,
                str(o, "icon_url"), image, categories, versions);
    }

    public static List<Release> listReleases(String projectId, String loader, String mcVersion, String... extensions) throws IOException {
        StringBuilder url = new StringBuilder(API + "/project/" + enc(projectId) + "/version");
        char sep = '?';
        if (loader != null && !loader.isBlank()) {
            url.append(sep).append("loaders=").append(enc("[\"" + loader.toLowerCase(Locale.ROOT) + "\"]"));
            sep = '&';
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            url.append(sep).append("game_versions=").append(enc("[\"" + mcVersion + "\"]"));
        }
        JsonArray versions = JsonParser.parseString(get(url.toString())).getAsJsonArray();
        List<Release> result = new ArrayList<>();
        for (JsonElement el : versions) {
            if (!el.isJsonObject()) continue;
            Release release = parseRelease(el.getAsJsonObject(), extensions);
            if (release != null) result.add(release);
        }
        return result;
    }

    public static Release latestRelease(String projectId, String loader, String mcVersion, String... extensions) throws IOException {
        List<Release> all = listReleases(projectId, loader, mcVersion, extensions);
        for (Release release : all) if ("release".equals(release.type())) return release;
        return all.isEmpty() ? null : all.get(0);
    }

    public static Release releaseById(String versionId, String... extensions) throws IOException {
        return parseRelease(JsonParser.parseString(get(API + "/version/" + enc(versionId))).getAsJsonObject(), extensions);
    }

    public static Release releaseByHash(String sha1, String... extensions) throws IOException {
        String body = getOrNull(API + "/version_file/" + enc(sha1) + "?algorithm=sha1");
        if (body == null) return null;
        return parseRelease(JsonParser.parseString(body).getAsJsonObject(), extensions);
    }

    private static Release parseRelease(JsonObject o, String... extensions) {
        if (!o.has("files") || !o.get("files").isJsonArray()) return null;
        JsonObject chosen = null;
        for (JsonElement f : o.getAsJsonArray("files")) {
            if (!f.isJsonObject()) continue;
            JsonObject file = f.getAsJsonObject();
            String name = str(file, "filename").toLowerCase(Locale.ROOT);
            boolean fits = extensions == null || extensions.length == 0;
            if (!fits) for (String ext : extensions) if (name.endsWith(ext.toLowerCase(Locale.ROOT))) { fits = true; break; }
            if (!fits) continue;
            boolean primary = file.has("primary") && file.get("primary").isJsonPrimitive() && file.get("primary").getAsBoolean();
            if (chosen == null || primary) chosen = file;
            if (primary) break;
        }
        if (chosen == null) return null;
        String sha1 = chosen.has("hashes") && chosen.get("hashes").isJsonObject() ? str(chosen.getAsJsonObject("hashes"), "sha1") : "";
        ModFile file = new ModFile(str(chosen, "url"), str(chosen, "filename"), sha1);
        List<Dependency> deps = new ArrayList<>();
        if (o.has("dependencies") && o.get("dependencies").isJsonArray()) {
            for (JsonElement d : o.getAsJsonArray("dependencies")) {
                if (!d.isJsonObject()) continue;
                JsonObject dep = d.getAsJsonObject();
                if (!"required".equals(str(dep, "dependency_type"))) continue;
                String project = str(dep, "project_id");
                if (!project.isBlank()) deps.add(new Dependency(project, str(dep, "version_id")));
            }
        }
        String number = str(o, "version_number");
        String name = str(o, "name");
        return new Release(str(o, "id"), str(o, "project_id"), number, name.isBlank() ? number : name,
                str(o, "version_type"), str(o, "date_published"), strings(o, "game_versions"), strings(o, "loaders"), file, deps);
    }

    public static void downloadPackFile(String url, String sha1, File target, CancelToken cancel) throws Exception {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && (host.equals("cdn.modrinth.com") || host.equals("github.com")
                || host.equals("raw.githubusercontent.com") || host.equals("gitlab.com"));
        if (!allowed) throw new IOException("Недопустимый адрес файла сборки: " + url);
        fetchChecked(url, sha1, target, 90000, cancel, "Контрольная сумма не совпала: " + target.getName());
    }

    public static void download(ModFile file, File target, CancelToken cancel) throws Exception {
        if (!trustedCdn(file.url())) throw new IOException("Неожиданный адрес загрузки: " + file.url());
        fetchChecked(file.url(), file.sha1(), target, 60000, cancel, "Контрольная сумма файла не совпала, загрузка отменена.");
    }

    public static void download(ModFile file, File target) throws Exception {
        download(file, target, CancelToken.NONE);
    }

    private static void fetchChecked(String url, String sha1, File target, int readTimeout, CancelToken cancel, String badHash) throws Exception {
        cancel.check();
        File parent = target.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        File part = new File(target.getPath() + ".part");
        try {
            HttpURLConnection c = open(url, readTimeout);
            int status = c.getResponseCode();
            if (status / 100 != 2) throw new IOException("Сервер ответил кодом " + status + " для " + target.getName());
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = new DigestInputStream(c.getInputStream(), digest);
                 OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    cancel.check();
                    out.write(buffer, 0, read);
                }
            }
            String expected = sha1 == null ? "" : sha1.trim().toLowerCase(Locale.ROOT);
            if (!expected.isEmpty() && !expected.contentEquals(hex(digest.digest()))) throw new IOException(badHash);
            cancel.check();
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part.toPath());
        }
    }

    public static byte[] downloadImage(String url) throws IOException {
        if (!trustedCdn(url)) throw new IOException("Недопустимый адрес картинки");
        HttpURLConnection c = open(url, 20000);
        c.setRequestProperty("Accept", "image/*");
        if (c.getResponseCode() / 100 != 2) throw new IOException("Картинка недоступна: " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            byte[] data = in.readNBytes(MAX_IMAGE_BYTES + 1);
            if (data.length > MAX_IMAGE_BYTES) throw new IOException("Картинка слишком большая");
            return data;
        }
    }

    static boolean trustedCdn(String url) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equals("cdn.modrinth.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String sha1(File file) {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
    }

    private static int compareMc(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0, q = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }

    private static HttpURLConnection open(String url, int readTimeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String get(String url) throws IOException {
        String body = request(url, false);
        return body == null ? "" : body;
    }

    private static String getOrNull(String url) throws IOException {
        return request(url, true);
    }

    private static String request(String url, boolean nullOn404) throws IOException {
        HttpURLConnection c = open(url, 15000);
        int code = c.getResponseCode();
        if (code == 404 && nullOn404) return null;
        if (code == 429) throw new IOException("Modrinth просит подождать: слишком много запросов. Повторите через минуту.");
        if (code / 100 != 2) throw new IOException("Modrinth ответил кодом " + code);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
