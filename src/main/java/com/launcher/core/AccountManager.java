package com.launcher.core;

import com.google.gson.*;
import com.launcher.model.Account;
import com.launcher.util.FileUtils;
import com.launcher.util.TokenVault;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class AccountManager {
    private static final File ACCOUNTS_FILE = new File(new File(System.getProperty("user.home"), ".nexus"), "launcher_accounts.json");
    private static final File LEGACY_ACCOUNTS_FILE = new File(FileUtils.getMinecraftDir(), "launcher_accounts.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static synchronized List<Account> loadAccounts() {
        File source = ACCOUNTS_FILE.exists() ? ACCOUNTS_FILE : LEGACY_ACCOUNTS_FILE;
        if (!source.exists()) return new ArrayList<>();
        List<Account> accounts = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                String username = string(obj, "username", "Player");
                String uuid = string(obj, "uuid", "");
                String token = string(obj, "accessToken", string(obj, "token", "0"));
                String type = string(obj, "userType", string(obj, "type", "legacy"));
                String refresh = string(obj, "refreshToken", null);
                try { token = TokenVault.unprotect(token); } catch (Exception ignored) { token = "0"; }
                try { refresh = TokenVault.unprotect(refresh); } catch (Exception ignored) { refresh = null; }
                long expires = obj.has("accessTokenExpiresAt") ? obj.get("accessTokenExpiresAt").getAsLong() : 0L;
                Account loaded = new Account(username, uuid, token, type, refresh, expires);
                if (obj.has("autoCreated") && !obj.get("autoCreated").isJsonNull()) loaded.setAutoCreated(obj.get("autoCreated").getAsBoolean());
                else if (array.size() == 1 && "Player".equals(username) && !"msa".equalsIgnoreCase(type))
                    loaded.setAutoCreated(true);
                accounts.add(loaded);
            }
        } catch (Exception e) {
            System.err.println("Не удалось прочитать аккаунты: " + e.getMessage());
        }
        return accounts;
    }

    private static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    public static synchronized void saveAccounts(List<Account> accounts) {
        JsonArray array = new JsonArray();
        for (Account account : accounts) {
            JsonObject obj = new JsonObject();
            obj.addProperty("username", account.getUsername());
            obj.addProperty("uuid", account.getUuid());
            obj.addProperty("accessToken", TokenVault.protect(account.getAccessToken()));
            obj.addProperty("userType", account.getUserType());
            obj.addProperty("refreshToken", TokenVault.protect(account.getRefreshToken()));
            obj.addProperty("accessTokenExpiresAt", account.getAccessTokenExpiresAt());
            obj.addProperty("autoCreated", account.isAutoCreated());
            array.add(obj);
        }
        try {
            File parent = ACCOUNTS_FILE.getParentFile();
            if (parent != null) parent.mkdirs();
            File temp = new File(ACCOUNTS_FILE.getParentFile(), ACCOUNTS_FILE.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp.toPath(), StandardCharsets.UTF_8)) { GSON.toJson(array, writer); }
            try { Files.move(temp.toPath(), ACCOUNTS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception ignored) { Files.move(temp.toPath(), ACCOUNTS_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING); }
            if (LEGACY_ACCOUNTS_FILE.exists() && !LEGACY_ACCOUNTS_FILE.getAbsoluteFile().equals(ACCOUNTS_FILE.getAbsoluteFile())) {
                try { Files.deleteIfExists(LEGACY_ACCOUNTS_FILE.toPath()); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сохранить аккаунты: " + e.getMessage(), e);
        }
    }

    public static void saveAccount(Account newAccount) {
        List<Account> accounts = loadAccounts();
        accounts.removeIf(acc -> acc.getUsername().equalsIgnoreCase(newAccount.getUsername()) ||
                (acc.getUuid() != null && acc.getUuid().equals(newAccount.getUuid())));
        accounts.add(0, newAccount);
        saveAccounts(accounts);
    }

    public static Account getLastActiveAccount() {
        List<Account> accounts = loadAccounts();
        return accounts.isEmpty() ? null : accounts.get(0);
    }
}
