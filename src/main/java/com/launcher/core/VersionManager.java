package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.model.GameVersion;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class VersionManager {
    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private VersionManager() {}

    public static List<GameVersion> getAllVersions() {
        List<GameVersion> versions = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(MANIFEST_URL).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "NEXUS-Launcher/1.5.2");
            connection.setRequestProperty("Accept", "application/json");
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray array = json.getAsJsonArray("versions");
                for (JsonElement element : array) {
                    JsonObject item = element.getAsJsonObject();
                    versions.add(new GameVersion(
                            item.get("id").getAsString(),
                            item.get("type").getAsString(),
                            item.get("url").getAsString()
                    ));
                }
            }
        } catch (Exception error) {
            System.err.println("Не удалось загрузить каталог Mojang: " + error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        return versions;
    }
}
