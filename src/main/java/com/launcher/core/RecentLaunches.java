package com.launcher.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RecentLaunches {
    public static final int MAX = 4;
    private static final String KEY = "recent_launches";
    private static final Gson GSON = new Gson();

    private RecentLaunches() { }

    public record Entry(String mc, String loader, String build, String accountUuid, String accountName) {
        public boolean isVanilla() { return loader == null || loader.isBlank() || "Vanilla".equalsIgnoreCase(loader); }

        public String label() {
            String name = accountName == null || accountName.isBlank() ? "" : " · " + accountName;
            return (isVanilla() ? "Vanilla" : loader) + " " + mc + name;
        }

        boolean sameLaunch(Entry other) {
            return other != null
                    && Objects.equals(mc, other.mc)
                    && Objects.equals(isVanilla() ? "Vanilla" : loader, other.isVanilla() ? "Vanilla" : other.loader)
                    && Objects.equals(isVanilla() ? "" : build, other.isVanilla() ? "" : other.build)
                    && Objects.equals(accountUuid, other.accountUuid);
        }
    }

    public static synchronized List<Entry> load() {
        try {
            List<Entry> list = GSON.fromJson(SettingsManager.getSetting(KEY, "[]"), new TypeToken<List<Entry>>() { }.getType());
            List<Entry> clean = new ArrayList<>();
            if (list != null) for (Entry e : list) if (e != null && e.mc() != null && !e.mc().isBlank() && clean.size() < MAX) clean.add(e);
            return clean;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized void record(Entry entry) {
        if (entry == null || entry.mc() == null || entry.mc().isBlank()) return;
        List<Entry> list = load();
        list.removeIf(entry::sameLaunch);
        list.add(0, entry);
        while (list.size() > MAX) list.remove(list.size() - 1);
        SettingsManager.saveSetting(KEY, GSON.toJson(list));
    }
}
