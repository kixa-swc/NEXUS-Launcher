package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.model.Account;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class SkinManager {
    private static final String API = "https://api.minecraftservices.com";
    private static final File CACHE_DIR = new File(System.getProperty("user.home"), ".nexus/skins");
    private SkinManager() {}

    public record SkinInfo(File imageFile, String variant, String skinUrl) {}

    public static SkinInfo cachedSkin(Account account, String variant) {
        if (account == null || account.getUuid() == null) return null;
        File cached = new File(CACHE_DIR, account.getUuid() + ".png");
        return cached.isFile() ? new SkinInfo(cached, variant == null ? "classic" : variant, "") : null;
    }

    public static SkinInfo fetchAndCache(Account account) throws Exception {
        requireMicrosoft(account);
        JsonObject profile = getProfile(account);
        JsonArray skins = profile.has("skins") && profile.get("skins").isJsonArray()
                ? profile.getAsJsonArray("skins") : new JsonArray();
        JsonObject active = null;
        for (var element : skins) {
            JsonObject skin = element.getAsJsonObject();
            if ("ACTIVE".equalsIgnoreCase(skin.has("state") ? skin.get("state").getAsString() : "")) {
                active = skin; break;
            }
        }
        if (active == null && !skins.isEmpty()) active = skins.get(0).getAsJsonObject();
        if (active == null || !active.has("url")) throw new IOException("В профиле Minecraft не найден активный скин.");
        String url = active.get("url").getAsString();
        String variant = active.has("variant") ? active.get("variant").getAsString().toLowerCase() : "classic";
        CACHE_DIR.mkdirs();
        File target = new File(CACHE_DIR, account.getUuid() + ".png");
        download(url, target);
        validateSkin(target);
        return new SkinInfo(target, variant, url);
    }

    public static SkinInfo upload(Account account, File skinFile, String variant) throws Exception {
        requireMicrosoft(account);
        validateSkin(skinFile);
        if (!"slim".equalsIgnoreCase(variant) && !"classic".equalsIgnoreCase(variant))
            throw new IllegalArgumentException("Выберите модель Classic или Slim.");
        String boundary = "----NexusSkin" + Long.toHexString(System.nanoTime());
        HttpURLConnection conn = (HttpURLConnection) new URL(API + "/minecraft/profile/skins").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Authorization", "Bearer " + account.getAccessToken());
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("User-Agent", "NEXUS-Launcher/1.5.2");
        try (OutputStream out = conn.getOutputStream()) {
            writePart(out, boundary, "variant", null, "text/plain", variant.toLowerCase().getBytes(StandardCharsets.UTF_8));
            writePart(out, boundary, "file", skinFile.getName(), "image/png", Files.readAllBytes(skinFile.toPath()));
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new IOException("Minecraft Services отклонил скин (HTTP " + code + "): " + response);
        CACHE_DIR.mkdirs();
        File cached = new File(CACHE_DIR, account.getUuid() + ".png");
        Files.copy(skinFile.toPath(), cached.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return new SkinInfo(cached, variant.toLowerCase(), "");
    }

    private static void writePart(OutputStream out, String boundary, String name, String filename,
                                  String contentType, byte[] data) throws IOException {
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\""
                + (filename == null ? "" : "; filename=\"" + filename.replace("\"", "") + "\"")
                + "\r\nContent-Type: " + contentType + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8)); out.write(data); out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject getProfile(Account account) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API + "/minecraft/profile").openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + account.getAccessToken());
        conn.setRequestProperty("User-Agent", "NEXUS-Launcher/1.5.2");
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new IOException("Не удалось получить профиль Minecraft (HTTP " + code + "): " + response);
        return JsonParser.parseString(response).getAsJsonObject();
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "NEXUS-Launcher/1.5.2");
        try (InputStream in = conn.getInputStream()) { Files.copy(in, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    public static void validateSkin(File file) throws Exception {
        if (file == null || !file.isFile()) throw new FileNotFoundException("Файл скина не найден.");
        BufferedImage image = ImageIO.read(file);
        if (image == null || !"png".equalsIgnoreCase(extension(file))) throw new IOException("Скин должен быть PNG-файлом.");
        int w = image.getWidth(), h = image.getHeight();
        if (w != 64 || (h != 64 && h != 32)) throw new IOException("Размер скина должен быть 64×64 или 64×32 пикселя.");
        if (file.length() > 1_000_000) throw new IOException("Файл скина слишком большой (максимум 1 МБ).");
    }
    private static String extension(File file) { String n = file.getName(); int i = n.lastIndexOf('.'); return i < 0 ? "" : n.substring(i + 1); }
    private static void requireMicrosoft(Account account) {
        if (account == null || !"msa".equalsIgnoreCase(account.getUserType()))
            throw new IllegalArgumentException("Управление скинами доступно только для Microsoft-аккаунтов с Minecraft Java Edition.");
    }
}
