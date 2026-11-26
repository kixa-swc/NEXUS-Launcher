package com.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class SettingsManager {
    private static final File CONFIG_FILE = new File(System.getProperty("user.home"), ".custom_launcher_settings.json");

    private static volatile boolean frozen;

    public static void freeze() { frozen = true; }

    public static File configFile() { return CONFIG_FILE; }

    public static synchronized void saveSetting(String key, String value) {
        if (frozen) return;
        try {
            JsonObject json = loadConfig();
            json.addProperty(key, value);
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                writer.write(json.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static synchronized String getSetting(String key, String defaultValue) {
        try {
            JsonObject json = loadConfig();
            if (json.has(key)) {
                return json.get(key).getAsString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return defaultValue;
    }

    private static JsonObject loadConfig() {
        if (!CONFIG_FILE.exists()) return new JsonObject();
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }
}