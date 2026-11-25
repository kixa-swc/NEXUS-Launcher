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

    public static void observe(Session session, String line) {
        if (session == null || line == null) return;
        Matcher connecting = CONNECTING.matcher(line);
        if (connecting.find()) {
            if (session.server == null) session.server = normalize(connecting.group(1) + ":" + connecting.group(2));
        } else if (LEFT.matcher(line).find()) {
            session.server = null;
        }
    }
}
