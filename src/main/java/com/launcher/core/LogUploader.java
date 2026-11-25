package com.launcher.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class LogUploader {
    private static final String ENDPOINT = "https://api.mclo.gs/1/log";
    private static final int MAX_CHARS = 1_500_000;
    private static final int MAX_LINES = 20_000;

    private LogUploader() { }

    public static String upload(String text) throws IOException {
        String content = trim(text);
        byte[] body = ("content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(ENDPOINT).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "NEXUS-Launcher/1.6 (Minecraft launcher)");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String reply = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject json;
            try {
                json = JsonParser.parseString(reply).getAsJsonObject();
            } catch (Exception e) {
                throw new IOException("Сервис вернул непонятный ответ (код " + code + ").");
            }
            if (json.has("success") && json.get("success").getAsBoolean() && json.has("url")) {
                return json.get("url").getAsString();
            }
            String error = json.has("error") && json.get("error").isJsonPrimitive() ? json.get("error").getAsString() : "код " + code;
            throw new IOException("Сервис отклонил лог: " + error);
        } finally {
            conn.disconnect();
        }
    }

    static String trim(String text) {
        String t = text == null ? "" : text;
        String[] lines = t.split("\\R", -1);
        if (lines.length > MAX_LINES) {
            StringBuilder sb = new StringBuilder("[... начало лога обрезано ...]\n");
            for (int i = lines.length - MAX_LINES; i < lines.length; i++) sb.append(lines[i]).append('\n');
            t = sb.toString();
        }
        if (t.length() > MAX_CHARS) t = "[... начало лога обрезано ...]\n" + t.substring(t.length() - MAX_CHARS);
        return t;
    }
}
