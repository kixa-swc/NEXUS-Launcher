package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class NewsService {
    private static final String MOJANG_HOST = "https://launchercontent.mojang.com";
    private static final String MOJANG_FEED = MOJANG_HOST + "/v2/javaPatchNotes.json";

    private NewsService() {}

    public record Entry(String title, String meta, String imageUrl, String linkUrl, String body) {}

    public static List<Entry> loadLauncherNotes() {
        List<Entry> result = new ArrayList<>();
        try (InputStream index = NewsService.class.getResourceAsStream("/notes/index.txt")) {
            if (index == null) return result;
            String[] names = new String(index.readAllBytes(), StandardCharsets.UTF_8).split("\\R");
            for (String name : names) {
                name = name.trim();
                if (name.isEmpty()) continue;
                try (InputStream in = NewsService.class.getResourceAsStream("/notes/" + name)) {
                    if (in == null) continue;
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
                    String[] lines = text.split("\\R");
                    String version = name.replace("PATCH-NOTES-", "").replace(".txt", "");
                    String subtitle = "";
                    for (int i = 1; i < lines.length; i++) {
                        if (!lines[i].isBlank()) { subtitle = lines[i].trim(); break; }
                    }
                    String title = subtitle.isEmpty() ? "NEXUS " + version : subtitle;
                    result.add(new Entry(title, "v" + version, null, null, text.trim()));
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return result;
    }

    public static List<Entry> fetchMojangNews() {
        List<Entry> result = new ArrayList<>();
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(MOJANG_FEED).toURL().openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "NEXUS-Launcher");
            if (c.getResponseCode() != 200) return result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray entries = root.getAsJsonArray("entries");
                if (entries == null) return result;
                for (JsonElement element : entries) {
                    if (result.size() >= 30) break;
                    JsonObject o = element.getAsJsonObject();
                    String title = str(o, "title");
                    String version = str(o, "version");
                    if (title == null || title.isBlank()) title = version == null ? "Minecraft" : "Minecraft " + version;
                    String date = str(o, "date");
                    String meta = date != null && date.length() >= 10 ? shortDate(date) : (version == null ? "" : version);
                    String image = null;
                    if (o.has("image") && o.get("image").isJsonObject()) {
                        String path = str(o.getAsJsonObject("image"), "url");
                        if (path != null) image = path.startsWith("http") ? path : MOJANG_HOST + path;
                    }
                    String link = version == null ? null
                            : "https://www.minecraft.net/en-us/article/minecraft-java-edition-" + version.replace('.', '-').replace(' ', '-').toLowerCase();
                    String body = str(o, "shortText");
                    result.add(new Entry(title, meta, image, link, body == null ? title : body));
                }
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String shortDate(String iso) {
        try { return iso.substring(8, 10) + "." + iso.substring(5, 7); }
        catch (Exception e) { return iso; }
    }
}
