package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.model.Account;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class AuthManager {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    public static Account createOfflineAccount(String username) {
        if (username == null || username.trim().isEmpty()) {
            username = "Player_" + (int)(Math.random() * 1000);
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return new Account(username.trim(), uuid.toString(), "0", "legacy");
    }

    public static List<Account> loadSavedAccounts() { return AccountManager.loadAccounts(); }
    public static void saveAccounts(List<Account> accounts) { AccountManager.saveAccounts(accounts); }

    public interface DeviceCodeListener { void onCode(String userCode, String verificationUri, int expiresInSeconds); }

    public static Account loginMicrosoft(java.util.function.Consumer<String> logger) throws Exception {
        return loginMicrosoft(logger, null);
    }

    public static Account loginMicrosoft(java.util.function.Consumer<String> logger, DeviceCodeListener codeListener) throws Exception {
        String clientId = "00000000402b5328";
        String scope = "service::user.auth.xboxlive.com::MBI_SSL offline_access";
        logger.accept("[MS] Запрос Device Code у Microsoft...");
        String deviceCodeData = "client_id=" + clientId + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                + "&response_type=device_code";
        JsonObject deviceJson = postJson("https://login.live.com/oauth20_connect.srf", deviceCodeData,
                "application/x-www-form-urlencoded", false);
        if (!deviceJson.has("device_code") || !deviceJson.has("user_code") || !deviceJson.has("verification_uri"))
            throw new Exception("Не удалось получить данные Device Code от Microsoft Live.");

        String deviceCode = deviceJson.get("device_code").getAsString();
        String userCode = deviceJson.get("user_code").getAsString();
        String verificationUri = deviceJson.get("verification_uri").getAsString();
        int interval = deviceJson.has("interval") ? deviceJson.get("interval").getAsInt() : 5;
        int expiresIn = deviceJson.has("expires_in") ? deviceJson.get("expires_in").getAsInt() : 900;
        if (codeListener != null) codeListener.onCode(userCode, verificationUri, expiresIn);
        if (Desktop.isDesktopSupported()) try { Desktop.getDesktop().browse(new URI(verificationUri)); } catch (Exception ignored) {}
        logger.accept("[MS] Ожидание подтверждения в браузере. Код показан в окне профиля.");

        JsonObject oauth = null;
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < expiresIn * 1000L) {
            Thread.sleep(interval * 1000L);
            String tokenData = "grant_type=device_code&client_id=" + clientId + "&device_code="
                    + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);
            JsonObject response = postFormAllowOAuthErrors("https://login.live.com/oauth20_token.srf", tokenData);
            if (response.has("access_token")) { oauth = response; break; }
            String error = response.has("error") ? response.get("error").getAsString() : "";
            if ("authorization_pending".equals(error)) continue;
            if ("slow_down".equals(error)) { interval += 2; continue; }
            if ("expired_token".equals(error)) throw new Exception("Время действия кода истекло.");
            if ("access_denied".equals(error)) throw new Exception("Вход отменён пользователем.");
            if (!error.isBlank()) throw new Exception("Microsoft OAuth: " + error + ". " +
                    (response.has("error_description") ? response.get("error_description").getAsString() : ""));
        }
        if (oauth == null) throw new Exception("Время ожидания авторизации истекло.");
        logger.accept("[MS] Microsoft OAuth подтверждён. Получен токен длительного доступа: "
                + (oauth.has("refresh_token") ? "да" : "нет") + ".");
        return completeMicrosoftLogin(oauth, logger);
    }

    public static synchronized Account refreshIfNeeded(Account account, java.util.function.Consumer<String> logger) throws Exception {
        if (account == null || !"msa".equalsIgnoreCase(account.getUserType())) return account;
        long now = System.currentTimeMillis();
        if (account.getAccessTokenExpiresAt() > now + 5 * 60_000L) return account;
        if (account.getRefreshToken() == null || account.getRefreshToken().isBlank()) {
            if (account.getAccessTokenExpiresAt() == 0L) return account;
            throw new Exception("Для аккаунта нет Microsoft refresh token. Удалите профиль и войдите заново.");
        }
        logger.accept("[MS] Обновляем сохранённую Microsoft-сессию для " + account.getUsername() + "...");
        String data = "client_id=00000000402b5328&grant_type=refresh_token&refresh_token="
                + URLEncoder.encode(account.getRefreshToken(), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL offline_access", StandardCharsets.UTF_8);
        JsonObject oauth = postFormAllowOAuthErrors("https://login.live.com/oauth20_token.srf", data);
        if (!oauth.has("access_token")) {
            String detail = oauth.has("error_description") ? oauth.get("error_description").getAsString()
                    : (oauth.has("error") ? oauth.get("error").getAsString() : "неизвестная ошибка");
            throw new Exception("Не удалось обновить Microsoft-сессию: " + detail + ". Войдите в аккаунт заново.");
        }
        Account refreshed = completeMicrosoftLogin(oauth, logger);
        if (!refreshed.getUuid().equalsIgnoreCase(account.getUuid()))
            throw new Exception("Microsoft вернула другой игровой профиль. Обновление сессии отменено.");
        account.setAccessToken(refreshed.getAccessToken());
        if (refreshed.getRefreshToken() != null && !refreshed.getRefreshToken().isBlank())
            account.setRefreshToken(refreshed.getRefreshToken());
        account.setAccessTokenExpiresAt(refreshed.getAccessTokenExpiresAt());
        AccountManager.saveAccount(account);
        logger.accept("[MS] Сессия обновлена и сохранена.");
        return account;
    }

    private static Account completeMicrosoftLogin(JsonObject oauth, java.util.function.Consumer<String> logger) throws Exception {
        String msaToken = oauth.get("access_token").getAsString();
        String refreshToken = oauth.has("refresh_token") ? oauth.get("refresh_token").getAsString() : null;
        if (refreshToken == null || refreshToken.isBlank())
            logger.accept("[MS] ВНИМАНИЕ: Microsoft не вернула refresh_token. Эта сессия сохранится, но после истечения игрового токена потребуется повторный вход.");
        logger.accept("[MS] Авторизация Xbox Live...");
        String xboxToken = exchangeXboxToken(msaToken);
        logger.accept("[MS] Получение XSTS токена...");
        JsonObject xstsObj = exchangeXstsToken(xboxToken);
        String xstsToken = xstsObj.get("Token").getAsString();
        String userHash = xstsObj.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0)
                .getAsJsonObject().get("uhs").getAsString();
        logger.accept("[MS] Аутентификация в Minecraft Services...");
        JsonObject mcAuth = authenticateMinecraftData(xstsToken, userHash);
        String mcToken = mcAuth.get("access_token").getAsString();
        long expiresIn = mcAuth.has("expires_in") ? mcAuth.get("expires_in").getAsLong() : 86_400L;
        logger.accept("[MS] Проверка и загрузка игрового профиля...");
        JsonObject profile = getMinecraftProfile(mcToken);
        return new Account(profile.get("name").getAsString(), profile.get("id").getAsString(), mcToken,
                "msa", refreshToken, System.currentTimeMillis() + expiresIn * 1000L);
    }

    private static JsonObject postFormAllowOAuthErrors(String targetUrl, String payload) throws Exception {
        URL url = new URL(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) { os.write(payload.getBytes(StandardCharsets.UTF_8)); }
        int code = conn.getResponseCode();
        InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) throw new Exception("Microsoft OAuth вернул HTTP " + code);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String exchangeXboxToken(String msaToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("AuthMethod", "RPS");
        properties.addProperty("SiteName", "user.auth.xboxlive.com");
        properties.addProperty("RpsTicket", "d=" + msaToken);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject json = postJsonWithXboxHeaders("https://user.auth.xboxlive.com/user/authenticate", body.toString());
        return json.get("Token").getAsString();
    }

    private static JsonObject exchangeXstsToken(String xboxToken) throws Exception {
        JsonObject body = new JsonObject();
        JsonObject properties = new JsonObject();
        properties.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xboxToken);
        properties.add("UserTokens", userTokens);
        body.add("Properties", properties);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        JsonObject json = postJsonWithXboxHeaders("https://xsts.auth.xboxlive.com/xsts/authorize", body.toString());
        if (json.has("XErr")) {
            long xerr = json.get("XErr").getAsLong();
            String message = json.has("Message") ? json.get("Message").getAsString() : "Unknown";
            
            if (xerr == 2148916233L) {
                throw new Exception("Ошибка XSTS: У аккаунта нет взрослой верификации Xbox. Требуется подтвердить возраст на сайте Microsoft.");
            } else if (xerr == 2148916238L) {
                throw new Exception("Ошибка XSTS: Детский аккаунт. Требуется родительское разрешение.");
            }
            throw new Exception("Ошибка XSTS (код " + xerr + "): " + message);
        }
        return json;
    }

    private static JsonObject authenticateMinecraftData(String xstsToken, String userHash) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("identityToken", "XBL3.0 x=" + userHash + ";" + xstsToken);
        return postJson("https://api.minecraftservices.com/authentication/login_with_xbox", body.toString(), "application/json", true);
    }

    private static JsonObject getMinecraftProfile(String mcToken) throws Exception {
        URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + mcToken);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static JsonObject postJsonWithXboxHeaders(String targetUrl, String payload) throws Exception {
        URL url = new URL(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Xbl-Contract-Version", "1");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream stream = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            throw new Exception("HTTP Error " + responseCode + " при запросе к " + targetUrl);
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (responseCode >= 400) {
                throw new Exception("HTTP " + responseCode + " при запросе к " + url.getHost());
            }
            return json;
        }
    }

    private static JsonObject postJson(String targetUrl, String payload, String contentType, boolean acceptJson) throws Exception {
        URL url = new URL(targetUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        if (acceptJson) {
            conn.setRequestProperty("Accept", "application/json");
        }
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        InputStream stream = (responseCode >= 400) ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            throw new Exception("HTTP Error " + responseCode + " при запросе к " + targetUrl);
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (responseCode >= 400) {
                throw new Exception("HTTP " + responseCode + " при запросе к " + url.getHost());
            }
            return json;
        }
    }
}