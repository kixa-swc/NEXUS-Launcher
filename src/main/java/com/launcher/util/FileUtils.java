package com.launcher.util;

import com.launcher.core.SettingsManager;
import java.awt.Desktop;
import java.io.File;

public class FileUtils {

    public static File getMinecraftDir() {
        String customPath = SettingsManager.getSetting("minecraft_dir", null);
        if (customPath != null) {
            File f = new File(customPath);
            if (f.exists() || f.mkdirs()) return f;
        }

        String os = System.getProperty("os.name").toLowerCase();
        File defaultDir;
        if (os.contains("win")) {
            defaultDir = new File(System.getenv("APPDATA"), ".minecraft");
        } else if (os.contains("mac")) {
            defaultDir = new File(System.getProperty("user.home"), "Library/Application Support/minecraft");
        } else {
            defaultDir = new File(System.getProperty("user.home"), ".minecraft");
        }
        if (!defaultDir.exists()) defaultDir.mkdirs();
        return defaultDir;
    }

    public static void openDirectory(File dir) {
        try {
            if (!dir.exists()) dir.mkdirs();
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}