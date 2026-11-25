package com.launcher.util;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class TokenVault {
    private static final String DPAPI_PREFIX = "dpapi:";
    private static final String LEGACY_PREFIX = "legacy:";
    private TokenVault() {}

    public static String protect(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            if (isWindows()) {
                byte[] encrypted = Crypt32Util.cryptProtectData(value.getBytes(StandardCharsets.UTF_8));
                return DPAPI_PREFIX + Base64.getEncoder().encodeToString(encrypted);
            }
        } catch (Throwable e) {
            throw new IllegalStateException("Не удалось защитить токен через Windows DPAPI", e);
        }
        return LEGACY_PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String unprotect(String value) {
        if (value == null || value.isBlank()) return value;
        try {
            if (value.startsWith(DPAPI_PREFIX)) {
                byte[] encrypted = Base64.getDecoder().decode(value.substring(DPAPI_PREFIX.length()));
                return new String(Crypt32Util.cryptUnprotectData(encrypted), StandardCharsets.UTF_8);
            }
            if (value.startsWith(LEGACY_PREFIX)) {
                return new String(Base64.getDecoder().decode(value.substring(LEGACY_PREFIX.length())), StandardCharsets.UTF_8);
            }
        } catch (Throwable e) {
            throw new IllegalStateException("Не удалось расшифровать сохранённый токен. Войдите в Microsoft заново.", e);
        }
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
