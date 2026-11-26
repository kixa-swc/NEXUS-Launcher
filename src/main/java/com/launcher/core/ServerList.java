package com.launcher.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ServerList {
    public static final int MAX = 50;
    public static final int DEFAULT_PORT = 25565;
    private static final String KEY = "favorite_servers";
    private static final Gson GSON = new Gson();

    private ServerList() { }

    public record Entry(String name, String address, String mc, String loader, List<String> nicks) {
        public Entry(String name, String address) { this(name, address, "", "", List.of()); }

        public Address parsed() { return parseAddress(address); }

        public String mcOrEmpty() { return mc == null ? "" : mc; }

        public String loaderOrEmpty() { return loader == null ? "" : loader; }

        public List<String> nickList() { return nicks == null ? List.of() : nicks; }

        public Entry withLaunch(String mc, String loader) { return new Entry(name, address, mc, loader, nickList()); }

        public Entry withNicks(List<String> nicks) { return new Entry(name, address, mcOrEmpty(), loaderOrEmpty(), nicks); }
    }

    public record Address(String host, int port, boolean explicitPort) {
        public String hostPort() {
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
        public String display() {
            String h = host.contains(":") ? "[" + host + "]" : host;
            return explicitPort && port != DEFAULT_PORT ? h + ":" + port : h;
        }
    }

    public static Address parseAddress(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.length() > 255) return null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == '\\' || c == '@' || c == '?' || c == '#') return null;
        }
        String host;
        int port = DEFAULT_PORT;
        boolean explicit = false;
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 2) return null;
            host = text.substring(1, close);
            String rest = text.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) return null;
                Integer p = parsePort(rest.substring(1));
                if (p == null) return null;
                port = p;
                explicit = true;
            }
            if (!host.contains(":")) return null;
        } else {
            int colon = text.lastIndexOf(':');
            if (colon >= 0 && text.indexOf(':') == colon) {
                host = text.substring(0, colon);
                Integer p = parsePort(text.substring(colon + 1));
                if (p == null) return null;
                port = p;
                explicit = true;
            } else if (colon >= 0) {
                return null;
            } else {
                host = text;
            }
        }
        if (host.isBlank() || host.startsWith(".") || host.endsWith(".") || host.startsWith("-")) return null;
        return new Address(host.toLowerCase(Locale.ROOT), port, explicit);
    }

    private static Integer parsePort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            return p >= 1 && p <= 65535 ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static synchronized List<Entry> load() {
        try {
            List<Entry> list = GSON.fromJson(SettingsManager.getSetting(KEY, "[]"), new TypeToken<List<Entry>>() { }.getType());
            List<Entry> clean = new ArrayList<>();
            if (list != null) for (Entry e : list) {
                if (e != null && e.address() != null && parseAddress(e.address()) != null && clean.size() < MAX) clean.add(e);
            }
            return clean;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static synchronized String add(String name, String address) {
        Address parsed = parseAddress(address);
        if (parsed == null) return "Адрес сервера выглядит неверно. Примеры: play.example.com или 192.168.1.5:25566.";
        List<Entry> list = load();
        if (list.size() >= MAX) return "В списке уже " + MAX + " серверов — удалите ненужные.";
        String normalized = parsed.display();
        for (Entry e : list) if (sameServer(e.address(), normalized)) return "Этот сервер уже есть в списке.";
        String title = name == null || name.isBlank() ? normalized : name.trim();
        if (title.length() > 40) title = title.substring(0, 40);
        list.add(new Entry(title, normalized));
        save(list);
        return null;
    }

    public static synchronized void update(Entry entry) {
        if (entry == null) return;
        List<Entry> list = load();
        for (int i = 0; i < list.size(); i++) {
            if (sameServer(list.get(i).address(), entry.address())) { list.set(i, entry); break; }
        }
        save(list);
    }

    public static synchronized String edit(Entry old, String name, String address) {
        if (old == null) return "Сервер не выбран.";
        Address parsed = parseAddress(address);
        if (parsed == null) return "Адрес сервера выглядит неверно. Примеры: play.example.com или 192.168.1.5:25566.";
        String normalized = parsed.display();
        List<Entry> list = load();
        int index = -1;
        for (int i = 0; i < list.size(); i++) {
            if (sameServer(list.get(i).address(), old.address())) index = i;
            else if (sameServer(list.get(i).address(), normalized)) return "Сервер с таким адресом уже есть в списке.";
        }
        if (index < 0) return "Этот сервер уже удалён из списка.";
        String title = name == null || name.isBlank() ? normalized : name.trim();
        if (title.length() > 40) title = title.substring(0, 40);
        Entry current = list.get(index);
        list.set(index, new Entry(title, normalized, current.mcOrEmpty(), current.loaderOrEmpty(), current.nickList()));
        save(list);
        return null;
    }

    public static synchronized void remove(Entry entry) {
        if (entry == null) return;
        List<Entry> list = load();
        list.removeIf(e -> sameServer(e.address(), entry.address()));
        save(list);
    }

    private static boolean sameServer(String a, String b) {
        Address x = parseAddress(a), y = parseAddress(b);
        return x != null && y != null && x.host().equals(y.host()) && x.port() == y.port();
    }

    private static void save(List<Entry> list) {
        SettingsManager.saveSetting(KEY, GSON.toJson(list));
    }
}
