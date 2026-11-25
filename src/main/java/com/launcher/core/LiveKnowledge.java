package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LiveKnowledge {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String NEWS = "https://launchercontent.mojang.com/v2/javaPatchNotes.json";
    private static final String MODRINTH = "https://api.modrinth.com/v2";
    private static final String UA = "NEXUS-Launcher/1.8 (Minecraft launcher AI assistant)";

    private static final long STATIC_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long SEARCH_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int WAIT_SECONDS = 14;

    private static final Executor POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nexus-ai-live");
        t.setDaemon(true);
        return t;
    });

    private record Cached(String body, long at) { }
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

    public record Versions(String latestRelease, String latestReleaseDate, String latestSnapshot, String latestSnapshotDate,
                           List<String> recent, List<String> recentSnapshots) { }

    private LiveKnowledge() { }

    public static String build(String question, String launcherMc, String launcherLoad) {
        String q = question == null ? "" : question;
        StringBuilder out = new StringBuilder();
        out.append("СЕГОДНЯ: ").append(LocalDate.now()).append(" (данные ниже получены только что из интернета)\n");

        CompletableFuture<Versions> versionsF = CompletableFuture.supplyAsync(LiveKnowledge::versions, POOL);
        CompletableFuture<String> newsF = CompletableFuture.supplyAsync(LiveKnowledge::news, POOL);
        Versions versions = await(versionsF, null);
        String news = await(newsF, "");

        if (versions != null && versions.latestRelease() != null) {
            out.append("\nВЕРСИИ MINECRAFT JAVA (официальный каталог Mojang):\n")
               .append("• Последний релиз: ").append(versions.latestRelease()).append('\n');
            if (versions.latestSnapshot() != null && !versions.latestSnapshot().equals(versions.latestRelease()))
                out.append("• Последний снапшот/пре-релиз: ").append(versions.latestSnapshot())
                   .append(" — ").append(kindOf(versions.latestSnapshot()))
                   .append(versions.latestSnapshotDate().isEmpty() ? "" : " (" + versions.latestSnapshotDate() + ")").append('\n');
            if (!versions.recentSnapshots().isEmpty())
                out.append("• Последние тестовые сборки: ").append(String.join("; ", versions.recentSnapshots())).append('\n');
            out.append("• Недавние релизы (новые сверху): ").append(String.join(", ", versions.recent())).append('\n');
        } else {
            out.append("\nВЕРСИИ MINECRAFT: получить актуальный список не удалось — не утверждай, какая версия последняя.\n");
        }
        if (!news.isEmpty()) out.append("\nПОСЛЕДНИЕ НОВОСТИ И ПАТЧНОУТЫ MOJANG:\n").append(news);

        String latest = versions == null ? null : versions.latestRelease();
        String type = detectType(q);
        String target = targetVersion(q, launcherMc, latest);
        String loader = detectLoader(q, launcherLoad);
        out.append("\nПОДБОР С MODRINTH (поиск выполнен сейчас)\n");
        out.append("Тема запроса: ").append(typeLabel(type)).append("; версия игры для подбора: ")
           .append(target == null ? "любая" : target)
           .append("; загрузчик: ").append(loader.isEmpty() ? "любой" : loader).append("\n");
        String found = modrinth(q, type, target, loader);
        out.append(found.isEmpty()
                ? "Modrinth сейчас недоступен или ничего не нашёл — не называй конкретные моды и сборки с уверенностью, предложи проверить на modrinth.com.\n"
                : found);
        return out.toString();
    }

    public static Versions versions() {
        try {
            String body = cachedGet("manifest", MANIFEST, STATIC_TTL_MS);
            if (body == null) return null;
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject latest = root.getAsJsonObject("latest");
            String release = latest.get("release").getAsString();
            String snapshot = latest.get("snapshot").getAsString();
            String snapshotDate = "", releaseDate = "";
            List<String> recent = new ArrayList<>();
            List<String> snaps = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("versions")) {
                JsonObject v = e.getAsJsonObject();
                String id = v.get("id").getAsString();
                String type = v.get("type").getAsString();
                String time = v.has("releaseTime") ? v.get("releaseTime").getAsString() : "";
                String date = time.length() >= 10 ? time.substring(0, 10) : "";
                if (id.equals(snapshot)) snapshotDate = date;
                if (id.equals(release)) releaseDate = date;
                if ("snapshot".equals(type) && snaps.size() < 6) snaps.add(id + " — " + kindOf(id) + (date.isEmpty() ? "" : ", " + date));
                if ("release".equals(type) && recent.size() < 8) recent.add(date.isEmpty() ? id : id + " (" + date + ")");
            }
            return new Versions(release, releaseDate, snapshot, snapshotDate, recent, snaps);
        } catch (Exception e) {
            return null;
        }
    }

    private static String news() {
        try {
            String body = cachedGet("news", NEWS, STATIC_TTL_MS);
            if (body == null) return "";
            JsonArray entries = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("entries");
            if (entries == null) return "";
            List<JsonObject> list = new ArrayList<>();
            for (JsonElement e : entries) if (e.isJsonObject()) list.add(e.getAsJsonObject());
            list.sort((a, b) -> str(b, "date").compareTo(str(a, "date")));
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (JsonObject o : list) {
                if (n++ >= 6) break;
                String date = str(o, "date");
                String title = str(o, "title");
                String version = str(o, "version");
                String text = str(o, "shortText").replaceAll("\\s+", " ").trim();
                if (text.length() > 170) text = text.substring(0, 170) + "…";
                sb.append("• ").append(date.length() >= 10 ? date.substring(0, 10) : "без даты").append(" — ")
                  .append(title.isEmpty() ? "Minecraft " + version : title);
                if (!version.isEmpty() && !title.contains(version)) sb.append(" [").append(version).append(']');
                if (!text.isEmpty()) sb.append(": ").append(text);
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String modrinth(String q, String type, String target, String loader) {
        List<CompletableFuture<String>> parts = new ArrayList<>();
        for (String name : namedThings(q)) parts.add(CompletableFuture.supplyAsync(() -> named(name, type, target, loader), POOL));
        String category = detectCategory(q, type);
        parts.add(CompletableFuture.supplyAsync(() -> topList(type, target, loader, category), POOL));
        StringBuilder sb = new StringBuilder();
        for (CompletableFuture<String> f : parts) sb.append(await(f, ""));
        return sb.toString();
    }

    private static String named(String name, String type, String target, String loader) {
        try {
            List<Hit> hits = search(type, name, target, loader, "", "relevance", 3);
            if (hits.isEmpty() && target != null) {
                List<Hit> any = search(type, name, null, loader, "", "relevance", 2);
                if (any.isEmpty()) return "Поиск «" + name + "»: ничего не найдено.\n";
                StringBuilder sb = new StringBuilder("Поиск «" + name + "»: под версию " + target + " НЕТ; найдено для других версий:\n");
                for (Hit h : any) sb.append(h.line(null));
                return sb.toString();
            }
            if (hits.isEmpty()) return "Поиск «" + name + "»: ничего не найдено.\n";
            StringBuilder sb = new StringBuilder("Поиск «" + name + "»" + (target == null ? "" : " (поддерживают " + target + ")") + ":\n");
            for (Hit h : hits) sb.append(h.line(target));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String topList(String type, String target, String loader, String category) {
        try {
            List<Hit> hits = search(type, "", target, loader, category, "downloads", 7);
            if (hits.isEmpty() && target != null)
                return "Для версии " + target + " популярных проектов типа «" + typeLabel(type) + "» не найдено — скажи об этом честно "
                        + "и предложи предыдущую стабильную версию игры.\n";
            if (hits.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("Самые популярные проекты (" + typeLabel(type)
                    + (category.isEmpty() ? "" : ", категория " + category) + ")"
                    + (target == null ? "" : ", поддерживающие " + target) + ":\n");
            for (Hit h : hits) sb.append(h.line(target));
            List<Hit> fresh = search(type, "", target, loader, category, "updated", 5);
            Set<String> seen = new LinkedHashSet<>();
            for (Hit h : hits) seen.add(h.slug());
            StringBuilder extra = new StringBuilder();
            for (Hit h : fresh) if (seen.add(h.slug()) && h.downloads() >= 20_000) extra.append(h.line(target));
            if (extra.length() > 0) sb.append("Недавно обновлённые и тоже востребованные:\n").append(extra);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private record Hit(String title, String slug, String author, long downloads, String description,
                       List<String> loaders, String latestMc, String updated) {
        String line(String target) {
            StringBuilder sb = new StringBuilder("• ").append(title);
            if (!author.isEmpty()) sb.append(" (автор ").append(author).append(')');
            sb.append(" — ").append(compact(downloads)).append(" скачиваний");
            if (!loaders.isEmpty()) sb.append("; ").append(String.join("/", loaders));
            if (!latestMc.isEmpty()) sb.append("; последняя версия игры: ").append(latestMc);
            if (!updated.isEmpty()) sb.append("; обновлён ").append(updated);
            String d = description.replaceAll("\\s+", " ").trim();
            if (d.length() > 110) d = d.substring(0, 110) + "…";
            if (!d.isEmpty()) sb.append(". ").append(d);
            return sb.append('\n').toString();
        }
    }

    private static List<Hit> search(String type, String query, String mcVersion, String loader, String category,
                                    String index, int limit) throws Exception {
        StringBuilder facets = new StringBuilder("[[\"project_type:").append(type).append("\"]");
        if (!loader.isEmpty() && (type.equals("mod") || type.equals("modpack")))
            facets.append(",[\"categories:").append(loader.toLowerCase(Locale.ROOT)).append("\"]");
        if (mcVersion != null && !mcVersion.isBlank()) facets.append(",[\"versions:").append(mcVersion).append("\"]");
        if (!category.isEmpty()) facets.append(",[\"categories:").append(category).append("\"]");
        facets.append("]");
        String url = MODRINTH + "/search?query=" + enc(query) + "&facets=" + enc(facets.toString())
                + "&index=" + index + "&limit=" + limit;
        String body = cachedGet(url, url, SEARCH_TTL_MS);
        if (body == null) throw new java.io.IOException("Modrinth недоступен");
        List<Hit> out = new ArrayList<>();
        JsonArray hits = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("hits");
        if (hits == null) return out;
        for (JsonElement e : hits) {
            JsonObject o = e.getAsJsonObject();
            List<String> cats = strings(o, "categories");
            for (String extra : strings(o, "additional_categories")) if (!cats.contains(extra)) cats.add(extra);
            List<String> loaders = new ArrayList<>();
            if (cats.contains("fabric")) loaders.add("Fabric");
            if (cats.contains("forge")) loaders.add("Forge");
            if (cats.contains("neoforge")) loaders.add("NeoForge");
            if (cats.contains("quilt")) loaders.add("Quilt");
            String latest = "";
            for (String v : strings(o, "versions")) {
                if (v.matches("\\d+\\.\\d+(\\.\\d+)?") && (latest.isEmpty() || compareMc(v, latest) > 0)) latest = v;
            }
            String modified = str(o, "date_modified");
            long downloads = o.has("downloads") && o.get("downloads").isJsonPrimitive() ? o.get("downloads").getAsLong() : 0L;
            out.add(new Hit(str(o, "title"), str(o, "slug"), str(o, "author"), downloads, str(o, "description"),
                    loaders, latest, modified.length() >= 10 ? modified.substring(0, 10) : ""));
        }
        return out;
    }

    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])((?:1\\.\\d{1,2}|2\\d\\.\\d{1,2})(?:\\.\\d{1,2})?)(?![\\d.])");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z][A-Za-z0-9'+\\-]{2,}(?: [A-Z][A-Za-z0-9'+\\-]+){0,3}");
    private static final Set<String> STOP = Set.of("minecraft", "fps", "forge", "fabric", "neoforge", "quilt", "mod", "mods", "modpack",
            "shader", "shaders", "java", "ram", "gpu", "cpu", "the", "and", "for", "optifine", "launcher", "nexus", "pc", "ssd", "hdd",
            "pre-release", "prerelease", "pre", "snapshot", "release", "latest", "edition", "mojang", "update", "nvidia", "amd", "intel", "rtx", "gtx", "radeon", "geforce", "windows", "vanilla", "jvm", "gb", "ddr4", "ddr5");

    static String detectType(String q) {
        String s = q.toLowerCase(Locale.ROOT);
        if (s.contains("шейдер") || s.contains("shader")) return "shader";
        if (s.contains("сборк") || s.contains("modpack") || s.contains("мод-пак") || s.contains("модпак")) return "modpack";
        if (s.contains("ресурспак") || s.contains("ресурс-пак") || s.contains("текстур") || s.contains("resource pack") || s.contains("resourcepack")) return "resourcepack";
        return "mod";
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "shader" -> "шейдеры";
            case "modpack" -> "сборки модов";
            case "resourcepack" -> "ресурспаки";
            default -> "моды";
        };
    }

    static String detectLoader(String q, String launcherLoader) {
        String s = q.toLowerCase(Locale.ROOT);
        if (s.contains("neoforge")) return "NeoForge";
        if (s.contains("fabric") || s.contains("фабрик")) return "Fabric";
        if (s.contains("forge") || s.contains("фордж")) return "Forge";
        if (launcherLoader != null && !launcherLoader.isBlank() && !launcherLoader.equalsIgnoreCase("vanilla")) return launcherLoader;
        return "";
    }

    static String targetVersion(String q, String launcherMc, String latest) {
        Matcher m = VERSION.matcher(q);
        if (m.find()) return m.group(1);
        String s = q.toLowerCase(Locale.ROOT);
        boolean wantsNewest = s.contains("последн") || s.contains("новейш") || s.contains("свеж") || s.contains("новую верси")
                || s.contains("новая верси") || s.contains("latest") || s.contains("актуальн");
        if (wantsNewest && latest != null) return latest;
        if (launcherMc != null && launcherMc.matches("\\d+\\.\\d+(\\.\\d+)?")) return launcherMc;
        return latest;
    }

    static String detectCategory(String q, String type) {
        String s = q.toLowerCase(Locale.ROOT);
        if (type.equals("mod") && (s.contains("fps") || s.contains("лаг") || s.contains("тормоз") || s.contains("оптимиз") || s.contains("производительн")))
            return "optimization";
        return "";
    }

    static List<String> namedThings(String q) {
        List<String> names = new ArrayList<>();
        Matcher m = LATIN.matcher(q);
        while (m.find() && names.size() < 3) {
            String name = m.group().trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (STOP.contains(lower) || lower.matches("\\d+.*|pre\\d*|rc\\d*") || name.length() < 3) continue;
            boolean allStop = true;
            for (String w : lower.split(" ")) if (!STOP.contains(w)) { allStop = false; break; }
            if (allStop || names.contains(name)) continue;
            names.add(name);
        }
        return names;
    }

    public static String kindOf(String id) {
        if (id == null) return "";
        String s = id.toLowerCase(Locale.ROOT);
        if (s.contains("-pre") || s.contains("pre-release")) return "пре-релиз";
        if (s.contains("-rc")) return "релиз-кандидат";
        if (s.matches("\\d\\dw\\d\\d[a-z]")) return "еженедельный снапшот";
        if (s.matches("\\d+\\.\\d+(\\.\\d+)?")) return "релиз";
        return "снапшот";
    }

    public static String baseOf(String id) {
        if (id == null) return null;
        Matcher m = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)-(?:pre|rc)\\d*").matcher(id.toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : null;
    }

    public static int count(String type, String mcVersion) {
        try {
            String facets = "[[\"project_type:" + type + "\"],[\"versions:" + mcVersion + "\"]]";
            String url = MODRINTH + "/search?query=&limit=1&facets=" + enc(facets);
            String body = cachedGet(url, url, SEARCH_TTL_MS);
            if (body == null) return -1;
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            return o.has("total_hits") ? o.get("total_hits").getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static Map<String, int[]> counts(List<String> versions) {
        Map<String, CompletableFuture<int[]>> futures = new java.util.LinkedHashMap<>();
        for (String v : versions)
            futures.put(v, CompletableFuture.supplyAsync(() -> new int[]{count("mod", v), count("modpack", v)}, POOL));
        Map<String, int[]> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, CompletableFuture<int[]>> e : futures.entrySet()) out.put(e.getKey(), await(e.getValue(), new int[]{-1, -1}));
        return out;
    }

    public static List<String[]> newsEntries(int max) {
        List<String[]> out = new ArrayList<>();
        try {
            String body = cachedGet("news", NEWS, STATIC_TTL_MS);
            if (body == null) return out;
            JsonArray entries = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("entries");
            if (entries == null) return out;
            List<JsonObject> list = new ArrayList<>();
            for (JsonElement e : entries) if (e.isJsonObject()) list.add(e.getAsJsonObject());
            list.sort((a, b) -> str(b, "date").compareTo(str(a, "date")));
            for (JsonObject o : list) {
                if (out.size() >= max) break;
                String date = str(o, "date");
                out.add(new String[]{date.length() >= 10 ? date.substring(0, 10) : "", str(o, "title"),
                        str(o, "shortText").replaceAll("\\s+", " ").trim(), str(o, "version")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static String modrinthFor(String question, String launcherMc, String launcherLoader) {
        Versions v = versions();
        String type = detectType(question);
        String target = targetVersion(question, launcherMc, v == null ? null : v.latestRelease());
        return modrinth(question, type, target, detectLoader(question, launcherLoader));
    }

    static int compareMc(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, r = i < y.length ? parse(y[i]) : 0;
            if (p != r) return Integer.compare(p, r);
        }
        return 0;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1f млн", n / 1_000_000.0);
        if (n >= 1_000) return (n / 1_000) + " тыс.";
        return Long.toString(n);
    }

    private static <T> T await(CompletableFuture<T> f, T fallback) {
        try { return f.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return fallback; }
        catch (Exception e) { return fallback; }
    }

    private static String cachedGet(String key, String url, long ttlMs) {
        Cached c = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < ttlMs) return c.body();
        try {
            HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(9000);
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "application/json");
            if (con.getResponseCode() / 100 != 2) return c == null ? null : c.body();
            try (InputStream in = con.getInputStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                CACHE.put(key, new Cached(body, now));
                return body;
            }
        } catch (Exception e) {
            return c == null ? null : c.body();
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonPrimitive()) out.add(e.getAsString());
        return out;
    }
}
