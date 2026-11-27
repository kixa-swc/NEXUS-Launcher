package com.launcher.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ChatServer {
    public static final int PROTOCOL = 1;
    static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final long ONLINE_MS = 45_000;
    static final long POLL_MS = 20_000;
    static final int MAX_TEXT = 2000, MAX_FRIENDS = 200, MAX_PENDING = 100, MAX_HISTORY = 5000, MAX_EVENTS = 1000;
    static final int MAX_BODY = 32 * 1024;

    private static final class User {
        String id, name, tokenHash;
        long created, lastSeen;
        final Set<String> friends = new LinkedHashSet<>();
        final Map<String, Long> incoming = new LinkedHashMap<>();
        final Set<String> outgoing = new LinkedHashSet<>();
        final Map<String, Long> readUpTo = new HashMap<>();
        final List<Event> events = new ArrayList<>();
        long eventSeq;
    }

    private static final class Msg {
        long seq, ts;
        String from, to, text;
    }

    private static final class Event {
        long n;
        String type;
        Map<String, Object> data;
    }

    private static final class ApiException extends RuntimeException {
        final int status;
        final String code;
        ApiException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
    }

    private final Object lock = new Object();
    private final Map<String, User> users = new HashMap<>();
    private final Map<String, User> byToken = new HashMap<>();
    private final Map<String, List<Msg>> convs = new HashMap<>();
    private long nextSeq = 1;
    private final String epoch;
    private final SecureRandom random = new SecureRandom();
    private final File dataFile;
    private final boolean trustProxy;
    private HttpServer http;
    private ScheduledExecutorService saver;
    private volatile boolean dirty, stopping;
    private final Map<String, Deque<Long>> rates = new HashMap<>();
    private final int msgRate = Integer.getInteger("nexus.chat.msgRate", 30);
    private final int regRate = Integer.getInteger("nexus.chat.regRate", 20);

    private ChatServer(File dataFile, boolean trustProxy) {
        this.dataFile = dataFile;
        this.trustProxy = trustProxy;
        byte[] e = new byte[6];
        random.nextBytes(e);
        this.epoch = hex(e);
    }

    public static ChatServer start(String host, int port, File dataFile) throws IOException {
        return start(host, port, dataFile, false);
    }

    public static ChatServer start(String host, int port, File dataFile, boolean trustProxy) throws IOException {
        ChatServer s = new ChatServer(dataFile, trustProxy);
        s.load();
        s.http = HttpServer.create(new InetSocketAddress(host, port), 128);
        s.http.createContext("/", s::handle);
        s.http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-chat-saver");
            t.setDaemon(true);
            return t;
        });
        s.saver.scheduleWithFixedDelay(s::saveIfDirty, 1500, 1500, TimeUnit.MILLISECONDS);
        s.http.start();
        return s;
    }

    public int port() { return http.getAddress().getPort(); }

    public void stop() {
        stopping = true;
        synchronized (lock) { lock.notifyAll(); }
        if (http != null) http.stop(0);
        if (saver != null) saver.shutdownNow();
        saveIfDirty();
    }

    public static void main(String[] args) throws Exception {
        String host = "0.0.0.0";
        int port = 8765;
        String envPort = System.getenv("PORT");
        if (envPort != null && envPort.matches("\\d{1,5}")) port = Integer.parseInt(envPort);
        File data = new File("nexus-chat-data.json");
        boolean proxy = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--host": host = args[++i]; break;
                case "--data": data = new File(args[++i]); break;
                case "--trust-proxy": proxy = true; break;
                default:
                    System.out.println("Использование: java -jar nexus-chat-server.jar [--host 0.0.0.0] [--port 8765] [--data nexus-chat-data.json] [--trust-proxy]");
                    return;
            }
        }
        ChatServer s = start(host, port, data, proxy);
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        System.out.println("NEXUS Chat Server запущен на " + host + ":" + s.port() + "  (данные: " + data.getAbsolutePath() + ")");
        Thread.currentThread().join();
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        Object body;
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            body = route(ex, method, path, query);
        } catch (ApiException e) {
            status = e.status;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.code);
            err.put("message", e.getMessage());
            body = err;
        } catch (MiniJson.JsonException e) {
            status = 400;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "bad_json");
            err.put("message", "Некорректный запрос.");
            body = err;
        } catch (Exception e) {
            status = 500;
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "internal");
            err.put("message", "Внутренняя ошибка сервера.");
            body = err;
            e.printStackTrace();
        }
        byte[] out = MiniJson.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private Object route(HttpExchange ex, String method, String path, Map<String, String> q) throws IOException {
        if (path.equals("/api/health")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("ok", true);
            r.put("service", "nexus-chat");
            r.put("protocol", PROTOCOL);
            r.put("epoch", epoch);
            return r;
        }
        if (path.equals("/api/register") && method.equals("POST")) return register(ex);
        User me = auth(ex);
        switch (method + " " + path) {
            case "GET /api/me": return meView(me);
            case "GET /api/state": return state(me);
            case "GET /api/sync": return sync(me, parseLong(q.get("cursor")), q.get("epoch"));
            case "POST /api/me/name": return rename(me, readBody(ex));
            case "POST /api/offline": synchronized (lock) { me.lastSeen = 0; } return ok();
            case "POST /api/friends/add": return friendAdd(me, readBody(ex));
            case "POST /api/friends/respond": return friendRespond(me, readBody(ex));
            case "POST /api/friends/remove": return friendRemove(me, readBody(ex));
            case "GET /api/messages": return history(me, q);
            case "POST /api/messages": return send(me, readBody(ex));
            case "POST /api/messages/read": return markRead(me, readBody(ex));
            default: throw new ApiException(404, "not_found", "Неизвестный запрос.");
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        return r;
    }

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY) throw new ApiException(413, "too_large", "Слишком большой запрос.");
            }
            String s = buf.toString(StandardCharsets.UTF_8);
            return s.isBlank() ? new LinkedHashMap<>() : MiniJson.parseObject(s);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static long parseLong(String s) {
        try { return s == null ? 0 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private User auth(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-Token");
        if (token == null || token.isBlank()) throw new ApiException(401, "unauthorized", "Нужна авторизация.");
        String h = sha256(token.trim());
        synchronized (lock) {
            User u = byToken.get(h);
            if (u == null) throw new ApiException(401, "unauthorized", "Аккаунт чата не найден на сервере.");
            u.lastSeen = System.currentTimeMillis();
            return u;
        }
    }

    private String clientIp(HttpExchange ex) {
        if (trustProxy) {
            String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (f != null && !f.isBlank()) return f.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private void rate(String key, int max, long windowMs, String message) {
        long now = System.currentTimeMillis();
        synchronized (rates) {
            if (rates.size() > 20000) rates.values().removeIf(d -> d.isEmpty() || now - d.peekLast() > 3_600_000L);
            Deque<Long> d = rates.computeIfAbsent(key, k -> new ArrayDeque<>());
            while (!d.isEmpty() && now - d.peekFirst() > windowMs) d.pollFirst();
            if (d.size() >= max) throw new ApiException(429, "rate_limited", message);
            d.addLast(now);
        }
    }

    private Object register(HttpExchange ex) throws IOException {
        Map<String, Object> body = readBody(ex);
        rate("reg|" + clientIp(ex), regRate, 3_600_000L, "Слишком много регистраций с вашего адреса. Попробуйте позже.");
        String name = cleanName(MiniJson.str(body, "name", ""));
        synchronized (lock) {
            User u = new User();
            do { u.id = newId(); } while (users.containsKey(u.id));
            byte[] t = new byte[24];
            random.nextBytes(t);
            String token = hex(t);
            u.name = name;
            u.tokenHash = sha256(token);
            u.created = System.currentTimeMillis();
            u.lastSeen = u.created;
            users.put(u.id, u);
            byToken.put(u.tokenHash, u);
            dirty = true;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", u.id);
            r.put("name", u.name);
            r.put("token", token);
            r.put("epoch", epoch);
            return r;
        }
    }

    private Object meView(User me) {
        synchronized (lock) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", me.id);
            r.put("name", me.name);
            r.put("epoch", epoch);
            return r;
        }
    }

    private Object rename(User me, Map<String, Object> body) {
        String name = cleanName(MiniJson.str(body, "name", ""));
        synchronized (lock) {
            me.name = name;
            dirty = true;
            return meView(me);
        }
    }

    private Object friendAdd(User me, Map<String, Object> body) {
        rate("add|" + me.id, 20, 60_000L, "Слишком много запросов. Подождите минуту.");
        String id = normalizeId(MiniJson.str(body, "id", ""));
        if (id == null) throw new ApiException(400, "bad_id", "Неверный формат ID. Он выглядит так: NX-AB12-CD34.");
        synchronized (lock) {
            if (id.equals(me.id)) throw new ApiException(400, "self", "Нельзя добавить самого себя.");
            User other = users.get(id);
            if (other == null) throw new ApiException(404, "user_not_found", "Пользователь с таким ID не найден.");
            if (me.friends.contains(id)) throw new ApiException(409, "already_friends", "Вы уже друзья.");
            if (me.outgoing.contains(id)) throw new ApiException(409, "already_requested", "Заявка уже отправлена.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("user", userView(other));
            if (me.incoming.containsKey(id)) {
                makeFriends(me, other);
                r.put("status", "accepted");
                return r;
            }
            if (me.friends.size() >= MAX_FRIENDS) throw new ApiException(409, "friends_limit", "Достигнут лимит друзей (" + MAX_FRIENDS + ").");
            if (me.outgoing.size() >= MAX_PENDING) throw new ApiException(409, "pending_limit", "Слишком много неотвеченных заявок.");
            if (other.incoming.size() >= MAX_PENDING) throw new ApiException(409, "target_busy", "У этого пользователя слишком много заявок.");
            me.outgoing.add(id);
            other.incoming.put(me.id, System.currentTimeMillis());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("user", userView(me));
            push(other, "friend_request", d);
            dirty = true;
            r.put("status", "requested");
            return r;
        }
    }

    private Object friendRespond(User me, Map<String, Object> body) {
        String id = normalizeId(MiniJson.str(body, "id", ""));
        boolean accept = MiniJson.bool(body, "accept", false);
        synchronized (lock) {
            User other = id == null ? null : users.get(id);
            if (other == null || !me.incoming.containsKey(id)) throw new ApiException(404, "no_request", "Заявка не найдена (возможно, её уже отменили).");
            if (accept) {
                if (me.friends.size() >= MAX_FRIENDS) throw new ApiException(409, "friends_limit", "Достигнут лимит друзей (" + MAX_FRIENDS + ").");
                makeFriends(me, other);
            } else {
                me.incoming.remove(id);
                other.outgoing.remove(me.id);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", me.id);
                push(other, "request_removed", d);
                dirty = true;
            }
            Map<String, Object> r = ok();
            r.put("user", userView(other));
            return r;
        }
    }

    private void makeFriends(User a, User b) {
        a.incoming.remove(b.id);
        b.incoming.remove(a.id);
        a.outgoing.remove(b.id);
        b.outgoing.remove(a.id);
        a.friends.add(b.id);
        b.friends.add(a.id);
        Map<String, Object> da = new LinkedHashMap<>();
        da.put("user", userView(b));
        push(a, "friend_added", da);
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("user", userView(a));
        push(b, "friend_added", db);
        dirty = true;
    }

    private Object friendRemove(User me, Map<String, Object> body) {
        String id = normalizeId(MiniJson.str(body, "id", ""));
        synchronized (lock) {
            User other = id == null ? null : users.get(id);
            if (other == null) throw new ApiException(404, "user_not_found", "Пользователь не найден.");
            boolean changed = false;
            if (me.friends.remove(id)) {
                other.friends.remove(me.id);
                convs.remove(pairKey(me.id, id));
                me.readUpTo.remove(id);
                other.readUpTo.remove(me.id);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", me.id);
                d.put("name", me.name);
                push(other, "friend_removed", d);
                changed = true;
            }
            if (me.outgoing.remove(id)) {
                other.incoming.remove(me.id);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", me.id);
                push(other, "request_removed", d);
                changed = true;
            }
            if (me.incoming.remove(id) != null) {
                other.outgoing.remove(me.id);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("id", me.id);
                push(other, "request_removed", d);
                changed = true;
            }
            if (!changed) throw new ApiException(404, "not_friends", "Этого пользователя нет в вашем списке.");
            dirty = true;
            return ok();
        }
    }

    private Object send(User me, Map<String, Object> body) {
        rate("msg|" + me.id, msgRate, 10_000L, "Слишком быстро! Подождите пару секунд.");
        String to = normalizeId(MiniJson.str(body, "to", ""));
        String text = MiniJson.str(body, "text", "").strip();
        if (text.isEmpty()) throw new ApiException(400, "empty", "Пустое сообщение.");
        if (text.length() > MAX_TEXT) throw new ApiException(400, "too_long", "Сообщение длиннее " + MAX_TEXT + " символов.");
        synchronized (lock) {
            if (to == null || !me.friends.contains(to)) throw new ApiException(403, "not_friends", "Писать можно только друзьям.");
            User other = users.get(to);
            Msg m = new Msg();
            m.seq = nextSeq++;
            m.ts = System.currentTimeMillis();
            m.from = me.id;
            m.to = to;
            m.text = text;
            List<Msg> conv = convs.computeIfAbsent(pairKey(me.id, to), k -> new ArrayList<>());
            conv.add(m);
            while (conv.size() > MAX_HISTORY) conv.remove(0);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("message", msgView(m));
            push(other, "message", d);
            dirty = true;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", msgView(m));
            return r;
        }
    }

    private Object history(User me, Map<String, String> q) {
        String with = normalizeId(q.getOrDefault("with", ""));
        long before = parseLong(q.get("before"));
        int limit = (int) Math.max(1, Math.min(100, q.containsKey("limit") ? parseLong(q.get("limit")) : 50));
        synchronized (lock) {
            if (with == null || !me.friends.contains(with)) throw new ApiException(403, "not_friends", "Это не ваш друг.");
            List<Msg> conv = convs.getOrDefault(pairKey(me.id, with), new ArrayList<>());
            List<Object> out = new ArrayList<>();
            int end = conv.size();
            if (before > 0) {
                end = 0;
                while (end < conv.size() && conv.get(end).seq < before) end++;
            }
            int start = Math.max(0, end - limit);
            for (int i = start; i < end; i++) out.add(msgView(conv.get(i)));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("messages", out);
            r.put("hasMore", start > 0);
            r.put("peerRead", users.get(with).readUpTo.getOrDefault(me.id, 0L));
            return r;
        }
    }

    private Object markRead(User me, Map<String, Object> body) {
        String with = normalizeId(MiniJson.str(body, "with", ""));
        synchronized (lock) {
            if (with == null || !me.friends.contains(with)) throw new ApiException(403, "not_friends", "Это не ваш друг.");
            long last = 0;
            for (Msg m : convs.getOrDefault(pairKey(me.id, with), new ArrayList<>())) if (m.from.equals(with)) last = Math.max(last, m.seq);
            if (last > me.readUpTo.getOrDefault(with, 0L)) {
                me.readUpTo.put(with, last);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("by", me.id);
                d.put("upTo", last);
                push(users.get(with), "read", d);
                dirty = true;
            }
            Map<String, Object> r = ok();
            r.put("upTo", last);
            return r;
        }
    }

    private Object state(User me) {
        synchronized (lock) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("me", meView(me));
            List<Object> fr = new ArrayList<>();
            for (String fid : me.friends) {
                User f = users.get(fid);
                if (f == null) continue;
                Map<String, Object> v = userView(f);
                v.put("lastSeen", f.lastSeen);
                List<Msg> conv = convs.getOrDefault(pairKey(me.id, fid), new ArrayList<>());
                long readSeq = me.readUpTo.getOrDefault(fid, 0L);
                int unread = 0;
                for (Msg m : conv) if (m.from.equals(fid) && m.seq > readSeq) unread++;
                v.put("unread", unread);
                v.put("peerRead", f.readUpTo.getOrDefault(me.id, 0L));
                v.put("last", conv.isEmpty() ? null : msgView(conv.get(conv.size() - 1)));
                fr.add(v);
            }
            r.put("friends", fr);
            List<Object> in = new ArrayList<>();
            for (Map.Entry<String, Long> e : me.incoming.entrySet()) {
                User o = users.get(e.getKey());
                if (o == null) continue;
                Map<String, Object> v = userView(o);
                v.put("ts", e.getValue());
                in.add(v);
            }
            r.put("incoming", in);
            List<Object> out = new ArrayList<>();
            for (String oid : me.outgoing) {
                User o = users.get(oid);
                if (o != null) out.add(userView(o));
            }
            r.put("outgoing", out);
            r.put("cursor", me.eventSeq);
            r.put("epoch", epoch);
            return r;
        }
    }

    private Object sync(User me, long cursor, String clientEpoch) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("epoch", epoch);
        synchronized (lock) {
            if (clientEpoch != null && !clientEpoch.equals(epoch)) { r.put("reset", true); r.put("cursor", me.eventSeq); r.put("events", new ArrayList<>()); return r; }
            if (cursor > me.eventSeq) { r.put("reset", true); r.put("cursor", me.eventSeq); r.put("events", new ArrayList<>()); return r; }
            if (!me.events.isEmpty() && cursor < me.events.get(0).n - 1) { r.put("reset", true); r.put("cursor", me.eventSeq); r.put("events", new ArrayList<>()); return r; }
            long deadline = System.currentTimeMillis() + POLL_MS;
            while (!stopping) {
                List<Object> evs = new ArrayList<>();
                long last = cursor;
                for (Event e : me.events) {
                    if (e.n > cursor) {
                        Map<String, Object> v = new LinkedHashMap<>();
                        v.put("n", e.n);
                        v.put("type", e.type);
                        v.put("data", e.data);
                        evs.add(v);
                        last = e.n;
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (!evs.isEmpty() || left <= 0) {
                    r.put("events", evs);
                    r.put("cursor", last);
                    return r;
                }
                try { lock.wait(Math.min(left, 5000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                me.lastSeen = System.currentTimeMillis();
            }
            r.put("events", new ArrayList<>());
            r.put("cursor", cursor);
            return r;
        }
    }

    private void push(User u, String type, Map<String, Object> data) {
        Event e = new Event();
        e.n = ++u.eventSeq;
        e.type = type;
        e.data = data;
        u.events.add(e);
        while (u.events.size() > MAX_EVENTS) u.events.remove(0);
        lock.notifyAll();
    }

    private Map<String, Object> userView(User u) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", u.id);
        v.put("name", u.name);
        v.put("online", System.currentTimeMillis() - u.lastSeen < ONLINE_MS);
        return v;
    }

    private static Map<String, Object> msgView(Msg m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("seq", m.seq);
        v.put("from", m.from);
        v.put("to", m.to);
        v.put("text", m.text);
        v.put("ts", m.ts);
        return v;
    }

    private static String pairKey(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    private String newId() {
        StringBuilder sb = new StringBuilder("NX-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String normalizeId(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toUpperCase(java.util.Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) sb.append(c);
        String s = sb.toString();
        if (s.length() == 10 && s.startsWith("NX")) s = s.substring(2);
        if (s.length() != 8) return null;
        for (char c : s.toCharArray()) if (ALPHABET.indexOf(c) < 0) return null;
        return "NX-" + s.substring(0, 4) + "-" + s.substring(4);
    }

    private static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) sb.append(Character.isISOControl(c) ? ' ' : c);
        String n = sb.toString().trim().replaceAll("\\s+", " ");
        if (n.length() < 2 || n.length() > 32) throw new ApiException(400, "bad_name", "Имя должно быть от 2 до 32 символов.");
        return n;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String sha256(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void saveIfDirty() {
        if (!dirty || dataFile == null) return;
        String json;
        synchronized (lock) {
            dirty = false;
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("nextSeq", nextSeq);
            List<Object> us = new ArrayList<>();
            for (User u : users.values()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", u.id);
                o.put("name", u.name);
                o.put("tokenHash", u.tokenHash);
                o.put("created", u.created);
                o.put("friends", new ArrayList<>(u.friends));
                o.put("incoming", new LinkedHashMap<>(u.incoming));
                o.put("outgoing", new ArrayList<>(u.outgoing));
                o.put("readUpTo", new LinkedHashMap<>(u.readUpTo));
                us.add(o);
            }
            root.put("users", us);
            List<Object> ms = new ArrayList<>();
            for (List<Msg> conv : convs.values()) for (Msg m : conv) ms.add(msgView(m));
            root.put("messages", ms);
            json = MiniJson.write(root);
        }
        try {
            File parent = dataFile.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            File tmp = new File(dataFile.getAbsolutePath() + ".tmp");
            Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
            try { Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException e) { Files.move(tmp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) {
            dirty = true;
            System.err.println("Не удалось сохранить данные чата: " + e.getMessage());
        }
    }

    private void load() {
        if (dataFile == null || !dataFile.exists()) return;
        try {
            Map<String, Object> root = MiniJson.parseObject(Files.readString(dataFile.toPath(), StandardCharsets.UTF_8));
            nextSeq = Math.max(1, MiniJson.num(root, "nextSeq", 1));
            for (Object o : MiniJson.list(root, "users")) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                User u = new User();
                u.id = MiniJson.str(m, "id", null);
                u.name = MiniJson.str(m, "name", "Player");
                u.tokenHash = MiniJson.str(m, "tokenHash", null);
                if (u.id == null || u.tokenHash == null) continue;
                u.created = MiniJson.num(m, "created", 0);
                for (Object f : MiniJson.list(m, "friends")) u.friends.add(String.valueOf(f));
                for (Object f : MiniJson.list(m, "outgoing")) u.outgoing.add(String.valueOf(f));
                Map<String, Object> in = MiniJson.obj(m, "incoming");
                if (in != null) for (Map.Entry<String, Object> e : in.entrySet()) u.incoming.put(e.getKey(), e.getValue() instanceof Number n ? n.longValue() : 0L);
                Map<String, Object> rd = MiniJson.obj(m, "readUpTo");
                if (rd != null) for (Map.Entry<String, Object> e : rd.entrySet()) u.readUpTo.put(e.getKey(), e.getValue() instanceof Number n ? n.longValue() : 0L);
                users.put(u.id, u);
                byToken.put(u.tokenHash, u);
            }
            for (Object o : MiniJson.list(root, "messages")) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
                Msg msg = new Msg();
                msg.seq = MiniJson.num(m, "seq", 0);
                msg.ts = MiniJson.num(m, "ts", 0);
                msg.from = MiniJson.str(m, "from", "");
                msg.to = MiniJson.str(m, "to", "");
                msg.text = MiniJson.str(m, "text", "");
                convs.computeIfAbsent(pairKey(msg.from, msg.to), k -> new ArrayList<>()).add(msg);
                nextSeq = Math.max(nextSeq, msg.seq + 1);
            }
            for (List<Msg> c : convs.values()) c.sort((a, b) -> Long.compare(a.seq, b.seq));
        } catch (Exception e) {
            System.err.println("Не удалось прочитать данные чата (" + e.getMessage() + "). Файл сохранён как .broken");
            try { Files.move(dataFile.toPath(), new File(dataFile.getAbsolutePath() + ".broken").toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
            users.clear(); byToken.clear(); convs.clear();
        }
    }
}
