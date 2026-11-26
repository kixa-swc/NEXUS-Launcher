package com.launcher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GameSessions {
    private GameSessions() { }

    public static final class Session {
        private final String accountUuid, nick, mc, loader;
        private volatile String server;
        private final long startedAt = System.currentTimeMillis();
        private volatile String rank;

        Session(String accountUuid, String nick, String mc, String loader, String server) {
            this.accountUuid = accountUuid;
            this.nick = nick;
            this.mc = mc;
            this.loader = loader;
            this.server = server;
        }

        public String accountUuid() { return accountUuid; }
        public String nick() { return nick; }
        public String mc() { return mc; }
        public String loader() { return loader; }
        public String server() { return server; }
        public long startedAt() { return startedAt; }
        public String rank() { return rank; }
        private volatile Process process;
        public Process process() { return process; }
        public void attach(Process process) { this.process = process; }
        private volatile AgentLink.Link link;
        public AgentLink.Link link() { return link; }
        public void attachLink(AgentLink.Link link) { this.link = link; }
        public void setServer(String hostPort) { this.server = hostPort; }
    }

    private static final List<Session> ACTIVE = new CopyOnWriteArrayList<>();
    private static final Pattern CONNECTING = Pattern.compile("Connecting to ([^,\\s]+), (\\d+)");
    private static final Pattern LEFT = Pattern.compile("(?i)(lost connection|disconnected from|disconnecting from|stopping singleplayer)");

    public static Session add(String accountUuid, String nick, String mc, String loader, String serverAddress) {
        Session session = new Session(accountUuid, nick, mc, loader, normalize(serverAddress));
        ACTIVE.add(session);
        return session;
    }

    public static void remove(Session session) { if (session != null) ACTIVE.remove(session); }

    public static List<Session> all() { return new ArrayList<>(ACTIVE); }

    public static List<Session> forAccount(String uuid) {
        List<Session> result = new ArrayList<>();
        if (uuid != null) for (Session session : ACTIVE) if (uuid.equalsIgnoreCase(session.accountUuid)) result.add(session);
        return result;
    }

    public static String normalize(String address) {
        ServerList.Address parsed = ServerList.parseAddress(address);
        return parsed == null ? null : parsed.hostPort().toLowerCase(Locale.ROOT);
    }

    private static final Pattern BRACKETS = Pattern.compile("[\\[({\u3010]([^\\])}\u3011]{1,24})[\\])}\u3011]");
    private static final Pattern LEVEL_LIKE = Pattern.compile("(?iu)^(lv|lvl|level|ур|уров)?\\.?\\s*\\d.*");
    private static final String SPEAKER_END = ":\u00bb>\u203a\u226b|\u2192-";

    public static void observe(Session session, String line) {
        if (session == null || line == null) return;
        int chat = line.indexOf("[CHAT] ");
        if (chat >= 0) {
            String detected = detectRank(line.substring(chat + 7), session.nick);
            if (detected != null) session.rank = detected;
            return;
        }
        Matcher connecting = CONNECTING.matcher(line);
        if (connecting.find()) {
            if (session.server == null) session.server = normalize(connecting.group(1) + ":" + connecting.group(2));
        } else if (LEFT.matcher(line).find()) {
            session.server = null;
        }
    }

    static String detectRank(String text, String nick) {
        if (text == null || nick == null || nick.isEmpty()) return null;
        int from = 0, idx = -1;
        while ((from = text.indexOf(nick, from)) >= 0) {
            boolean startOk = from == 0 || !isNickChar(text.charAt(from - 1));
            int end = from + nick.length();
            boolean endOk = end >= text.length() || !isNickChar(text.charAt(end));
            if (startOk && endOk) { idx = from; break; }
            from = end;
        }
        if (idx < 0) return null;
        String after = text.substring(idx + nick.length()).stripLeading();
        if (after.startsWith("]") || after.startsWith(")")) after = after.substring(1).stripLeading();
        if (after.isEmpty() || SPEAKER_END.indexOf(after.charAt(0)) < 0) return null;
        return rankFrom(text.substring(0, idx));
    }

    private static boolean isNickChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private static String rankFrom(String before) {
        String clean = before.replaceAll("\u00a7.", "");
        List<String> candidates = new ArrayList<>();
        Matcher groups = BRACKETS.matcher(clean);
        while (groups.find()) candidates.add(groups.group(1).trim());
        final int bracketed = candidates.size();
        String remainder = BRACKETS.matcher(clean).replaceAll(" ");
        for (String part : remainder.split("[|\u2022\u00b7\u00bb:<>]")) {
            String piece = part.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}+]+$", "").trim();
            if (!piece.isEmpty() && piece.length() <= 24) candidates.add(piece);
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String known = knownRank(candidates.get(i));
            if (known != null) return known;
        }
        for (int i = bracketed - 1; i >= 0; i--) {
            String c = candidates.get(i);
            if (!c.isEmpty() && !LEVEL_LIKE.matcher(c).matches()) return c;
        }
        return "Игрок";
    }

    private static String knownRank(String raw) {
        String t = raw.toLowerCase(Locale.ROOT);
        if (t.contains("owner") || t.contains("владел") || t.contains("founder") || t.contains("основат")) return "Владелец";
        if (t.contains("admin") || t.contains("админ")) return "Администратор";
        if (t.matches(".*\\bmod(er(ator)?)?\\b.*") || t.contains("модер")) return "Модератор";
        if (t.contains("helper") || t.contains("хелп") || t.contains("помощ")) return "Помощник";
        if (t.matches(".*\\bdev(eloper)?\\b.*") || t.contains("разраб")) return "Разработчик";
        return null;
    }
}
