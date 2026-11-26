package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class DiscordPresence {
    public static final String CLIENT_ID = "1575210613250982089";
    public static final String LARGE_IMAGE = "nexus";
    public static final String BUTTON_LABEL = "GitHub";
    public static final String BUTTON_URL = "https://github.com/kixa-swc/NEXUS-Launcher";
    private static final long ROTATE_MS = 10_000;
    private static final long RECONNECT_MS = 15_000;

    private static final DiscordPresence INSTANCE = new DiscordPresence();

    public static void start() { INSTANCE.begin(); }

    public static void stop() { INSTANCE.end(); }

    private ScheduledExecutorService scheduler;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "nexus-discord-io");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;
    private long nextConnectAt;
    private long nextRotateAt;
    private long lastSentAt;
    private long launcherStartedAt;
    private int rotation;
    private GameSessions.Session anchor;
    private String anchorServer;
    private String lastPayload;
    private boolean wasEnabled = true;

    private DiscordPresence() { }

    private static void log(String text) {
        try {
            File file = new File(SettingsManager.configFile().getParentFile(), ".nexus_discord.log");
            if (file.length() > 200_000) Files.deleteIfExists(file.toPath());
            Files.writeString(file.toPath(), LocalTime.now().withNano(0) + " " + text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    private synchronized void begin() {
        if (scheduler != null) return;
        launcherStartedAt = System.currentTimeMillis();
        log("start, client_id=" + CLIENT_ID + ", os=" + System.getProperty("os.name"));
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "nexus-discord");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private synchronized void end() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
        if (connection != null) {
            try { exchange(connection, activityFrame(null), 1500); } catch (Exception ignored) { }
            connection.close();
            connection = null;
        }
    }

    private synchronized void tick() {
        try {
            boolean enabled = Boolean.parseBoolean(SettingsManager.getSetting("discord_presence", "true"));
            long now = System.currentTimeMillis();
            if (!enabled) {
                if (wasEnabled && connection != null) {
                    try { exchange(connection, activityFrame(null), 3000); } catch (Exception e) { dropConnection(e); }
                    if (connection != null) { connection.close(); connection = null; }
                }
                wasEnabled = false;
                lastPayload = null;
                return;
            }
            wasEnabled = true;
            if (connection != null && !connection.alive()) dropConnection(null);
            if (connection == null) {
                if (now < nextConnectAt) return;
                connection = Connection.open(CLIENT_ID, io);
                if (connection == null) { nextConnectAt = now + RECONNECT_MS; return; }
                lastPayload = null;
            }
            List<GameSessions.Session> sessions = GameSessions.all();
            JsonObject activity;
            if (sessions.isEmpty()) {
                anchor = null;
                anchorServer = null;
                rotation = 0;
                nextRotateAt = 0;
                activity = idleActivity();
            } else {
                if (nextRotateAt == 0) nextRotateAt = now + ROTATE_MS;
                if (now >= nextRotateAt) { rotation++; nextRotateAt = now + ROTATE_MS; }
                chooseAnchor(sessions);
                activity = buildActivity(sessions);
            }
            String payload = activity.toString();
            if (payload.equals(lastPayload) && now - lastSentAt < 30_000) return;
            exchange(connection, activityFrame(activity), 8000);
            if (!payload.equals(lastPayload)) log("activity: " + payload);
            lastPayload = payload;
            lastSentAt = now;
        } catch (Throwable t) {
            dropConnection(t);
        }
    }

    private void exchange(Connection c, String json, long timeoutMs) throws Exception {
        Future<?> task = io.submit(() -> { c.send(1, json); c.awaitReply(); return null; });
        try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new IOException("Discord did not answer in " + timeoutMs + " ms");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    private void dropConnection(Throwable why) {
        if (why != null) log("connection dropped: " + why);
        if (connection != null) connection.close();
        connection = null;
        lastPayload = null;
        nextConnectAt = System.currentTimeMillis() + RECONNECT_MS;
    }

    private JsonObject idleActivity() {
        JsonObject activity = new JsonObject();
        activity.addProperty("type", 0);
        activity.addProperty("details", "В лаунчере");
        activity.addProperty("state", "Игра не запущена");
        addCommon(activity, launcherStartedAt == 0 ? System.currentTimeMillis() : launcherStartedAt);
        return activity;
    }

    private static void addCommon(JsonObject activity, long startedAtMs) {
        JsonObject timestamps = new JsonObject();
        timestamps.addProperty("start", startedAtMs / 1000);
        activity.add("timestamps", timestamps);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", LARGE_IMAGE);
        assets.addProperty("large_text", "NEXUS Launcher");
        activity.add("assets", assets);
        JsonArray buttons = new JsonArray();
        JsonObject button = new JsonObject();
        button.addProperty("label", BUTTON_LABEL);
        button.addProperty("url", BUTTON_URL);
        buttons.add(button);
        activity.add("buttons", buttons);
    }

    private void chooseAnchor(List<GameSessions.Session> sessions) {
        if (anchor != null && sessions.contains(anchor) && anchorServer != null) return;
        anchor = null;
        anchorServer = null;
        for (GameSessions.Session session : sessions) {
            if (session.server() != null) { anchor = session; anchorServer = session.server(); return; }
        }
        anchor = sessions.get(0);
    }

    private JsonObject buildActivity(List<GameSessions.Session> sessions) {
        Set<String> nicks = new LinkedHashSet<>();
        for (GameSessions.Session session : sessions) if (session.nick() != null && !session.nick().isBlank()) nicks.add(session.nick());
        List<String> order = new ArrayList<>(nicks);
        String nick = order.isEmpty() ? "Игрок" : order.get(Math.floorMod(rotation, order.size()));

        GameSessions.Session shown = null;
        for (GameSessions.Session session : sessions) {
            if (!Objects.equals(session.nick(), nick)) continue;
            if (shown == null) shown = session;
            if (anchorServer != null && anchorServer.equals(session.server())) { shown = session; break; }
        }
        String rank = shown == null || shown.rank() == null ? "Игрок" : shown.rank();

        JsonObject activity = new JsonObject();
        activity.addProperty("type", 0);
        activity.addProperty("details", clip(nick + " · " + rank, 120));
        activity.addProperty("state", clip(anchorServer == null ? "В главном меню" : serverText(anchorServer), 120));
        addCommon(activity, (anchor == null ? sessions.get(0) : anchor).startedAt());
        return activity;
    }

    private static String serverText(String hostPort) {
        ServerList.Address parsed = ServerList.parseAddress(hostPort);
        String display = parsed == null ? hostPort : parsed.display();
        try {
            for (ServerList.Entry entry : ServerList.load()) {
                if (hostPort.equals(GameSessions.normalize(entry.address())) && entry.name() != null && !entry.name().isBlank()
                        && !entry.name().equalsIgnoreCase(display)) {
                    return display + " · " + entry.name().trim();
                }
            }
        } catch (RuntimeException ignored) { }
        return display;
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) return text;
        return text.substring(0, max - 1) + "…";
    }

    private static String activityFrame(JsonObject activity) {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject frame = new JsonObject();
        frame.addProperty("cmd", "SET_ACTIVITY");
        frame.add("args", args);
        frame.addProperty("nonce", UUID.randomUUID().toString());
        return frame.toString();
    }

    private static final class Connection {
        private final Closeable handle;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean alive = true;

        private Connection(Closeable handle, InputStream in, OutputStream out) {
            this.handle = handle;
            this.in = in;
            this.out = out;
        }

        boolean alive() { return alive; }

        static Connection open(String clientId, ExecutorService io) {
            boolean anyPipe = false;
            for (int i = 0; i < 10; i++) {
                Connection connection = null;
                try {
                    connection = connect(i);
                    if (connection == null) continue;
                    anyPipe = true;
                    final Connection candidate = connection;
                    Future<?> hello = io.submit(() -> { candidate.handshake(clientId); return null; });
                    try {
                        hello.get(6, TimeUnit.SECONDS);
                        log("connected to discord-ipc-" + i);
                        return connection;
                    } catch (TimeoutException timeout) {
                        hello.cancel(true);
                        log("discord-ipc-" + i + ": handshake timed out");
                    } catch (java.util.concurrent.ExecutionException e) {
                        log("discord-ipc-" + i + ": handshake failed: " + e.getCause());
                    }
                } catch (Exception e) {
                    log("discord-ipc-" + i + ": " + e);
                }
                if (connection != null) connection.close();
            }
            if (!anyPipe) log("Discord IPC pipe not found (is the Discord desktop app running?)");
            return null;
        }

        private static Connection connect(int index) throws IOException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                RandomAccessFile file;
                try { file = new RandomAccessFile("\\\\?\\pipe\\discord-ipc-" + index, "rw"); }
                catch (IOException notThere) { return null; }
                InputStream in = new InputStream() {
                    @Override public int read() throws IOException {
                        byte[] one = new byte[1];
                        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException { return file.read(b, off, len); }
                };
                OutputStream out = new OutputStream() {
                    @Override public void write(int b) throws IOException { file.write(b); }
                    @Override public void write(byte[] b, int off, int len) throws IOException { file.write(b, off, len); }
                };
                return new Connection(file, in, out);
            }
            List<String> dirs = new ArrayList<>();
            for (String name : new String[] {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"}) {
                String value = System.getenv(name);
                if (value != null && !value.isBlank()) dirs.add(value);
            }
            dirs.add("/tmp");
            for (String dir : dirs) {
                for (String sub : new String[] {"", "/app/com.discordapp.Discord", "/snap.discord"}) {
                    File socket = new File(dir + sub, "discord-ipc-" + index);
                    if (!socket.exists()) continue;
                    try {
                        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
                        channel.connect(UnixDomainSocketAddress.of(socket.toPath()));
                        return new Connection(channel, Channels.newInputStream(channel), Channels.newOutputStream(channel));
                    } catch (IOException ignored) { }
                }
            }
            return null;
        }

        void handshake(String clientId) throws IOException {
            JsonObject hello = new JsonObject();
            hello.addProperty("v", 1);
            hello.addProperty("client_id", clientId);
            send(0, hello.toString());
            while (true) {
                Frame frame = readFrame();
                if (frame.opcode == 2) throw new IOException("Discord closed the connection: " + frame.text);
                if (frame.opcode == 3) { send(4, frame.text); continue; }
                JsonObject json = parse(frame.text);
                String evt = json != null && json.has("evt") && !json.get("evt").isJsonNull() ? json.get("evt").getAsString() : "";
                if ("READY".equals(evt)) return;
                if ("ERROR".equals(evt)) throw new IOException("handshake error: " + frame.text);
            }
        }

        void awaitReply() throws IOException {
            while (true) {
                Frame frame = readFrame();
                if (frame.opcode == 2) throw new IOException("Discord closed the connection: " + frame.text);
                if (frame.opcode == 3) { send(4, frame.text); continue; }
                JsonObject json = parse(frame.text);
                if (json != null && json.has("evt") && !json.get("evt").isJsonNull() && "ERROR".equals(json.get("evt").getAsString())) {
                    log("Discord rejected the activity: " + frame.text);
                }
                if (json != null && json.has("cmd") && "SET_ACTIVITY".equals(json.get("cmd").getAsString())) return;
            }
        }

        private static JsonObject parse(String text) {
            try { return JsonParser.parseString(text).getAsJsonObject(); } catch (RuntimeException e) { return null; }
        }

        private record Frame(int opcode, String text) { }

        private Frame readFrame() throws IOException {
            byte[] header = new byte[8];
            readFully(header, 8);
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int opcode = buffer.getInt();
            int length = buffer.getInt();
            if (length < 0 || length > 1 << 20) throw new IOException("bad frame length " + length);
            byte[] body = new byte[length];
            readFully(body, length);
            return new Frame(opcode, new String(body, StandardCharsets.UTF_8));
        }

        private void readFully(byte[] target, int length) throws IOException {
            int read = 0;
            while (read < length) {
                int n = in.read(target, read, length - read);
                if (n < 0) { alive = false; throw new IOException("Discord closed the pipe"); }
                read += n;
            }
        }

        void send(int opcode, String json) throws IOException {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(opcode).putInt(body.length).put(body);
            try {
                out.write(frame.array());
                out.flush();
            } catch (IOException e) {
                alive = false;
                throw e;
            }
        }

        void close() {
            alive = false;
            try { handle.close(); } catch (IOException ignored) { }
        }
    }
}
