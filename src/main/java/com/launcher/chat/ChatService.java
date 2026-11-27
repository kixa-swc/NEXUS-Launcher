package com.launcher.chat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class ChatService {

    public static final String DEFAULT_SERVER = "https://ntfy.sh";

    public static final int MAX_TEXT = 2000;
    private static final int CHUNK_BYTES = 1400;
    private static final long ONLINE_MS = 150_000, PING_SEC = 75;
    private static final int MAX_HISTORY = 10000, MAX_SEEN = 4000, MAX_FRIENDS = 200, MAX_PENDING = 100;
    private static final int MAX_PAYLOAD = 3900;
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Conn { NOT_CONFIGURED, CONNECTING, ONLINE, OFFLINE, AUTH_LOST }

    public record Message(long seq, String from, String to, String text, long ts) { }

    public record Person(String id, String name, boolean online, long lastSeen, int unread, long peerRead, Message last) { }

    public record Request(String id, String name, long ts) { }

    public record Page(List<Message> messages, boolean hasMore) { }

    public static final class ChatException extends Exception {
        private final int status;
        private final String code;
        public ChatException(String message, int status, String code) { super(message); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }

    public interface Listener {
        default void onConnection(Conn conn, String detail) { }
        default void onRoster() { }
        default void onMessage(Message message, boolean mine) { }
        default void onRead(String friendId, long upTo) { }
        default void onNotice(String text) { }
    }

    private static final class F {
        String id, name;
        long lastSeen, peerRead;
        int unread;
        boolean bye;
    }

    private static final class R {
        String id, name;
        long ts;
        R(String id, String name, long ts) { this.id = id; this.name = name; this.ts = ts; }
    }

    private final File stateFile;
    private final Function<String, Relay> relayFactory;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object model = new Object();

    private String relayUrl = "", myId = "", myName = "";
    private final Map<String, F> friendMap = new LinkedHashMap<>();
    private final Map<String, R> in = new LinkedHashMap<>();
    private final Map<String, R> out = new LinkedHashMap<>();
    private final Map<String, List<Message>> convs = new HashMap<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private long sinceSec;
    private long lastTs;
    private String activeChat;

    private volatile Conn conn = Conn.NOT_CONFIGURED;
    private volatile String connDetail = "";
    private volatile boolean running;
    private volatile boolean needHello = true;
    private Relay relay;
    private Relay.Subscription sub;
    private ScheduledExecutorService sched;
    private ExecutorService io;
    private final ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nexus-chat-save");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean savePending;

    public ChatService(File stateFile) { this(stateFile, NtfyRelay::new); }

    public ChatService(File stateFile, Function<String, Relay> relayFactory) {
        this.stateFile = stateFile;
        this.relayFactory = relayFactory;
        load();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public boolean isConfigured() { synchronized (model) { return !myId.isEmpty(); } }
    public String server() { synchronized (model) { return relayUrl; } }
    public String myId() { synchronized (model) { return myId; } }
    public String myName() { synchronized (model) { return myName; } }
    public Conn connection() { return conn; }
    public String connectionDetail() { return connDetail; }

    public List<Person> friends() {
        synchronized (model) {
            long now = System.currentTimeMillis();
            List<Person> list = new ArrayList<>();
            for (F f : friendMap.values()) list.add(person(f, now));
            return Collections.unmodifiableList(list);
        }
    }

    public List<Request> incoming() { synchronized (model) { return toRequests(in); } }
    public List<Request> outgoing() { synchronized (model) { return toRequests(out); } }

    private static List<Request> toRequests(Map<String, R> m) {
        List<Request> list = new ArrayList<>();
        for (R r : m.values()) list.add(new Request(r.id, r.name, r.ts));
        return Collections.unmodifiableList(list);
    }

    public int totalUnread() {
        synchronized (model) {
            int n = 0;
            for (F f : friendMap.values()) n += f.unread;
            return n;
        }
    }

    public Person friend(String id) {
        synchronized (model) {
            F f = friendMap.get(id);
            return f == null ? null : person(f, System.currentTimeMillis());
        }
    }

    public List<Message> conversation(String friendId) {
        synchronized (model) { return new ArrayList<>(convs.getOrDefault(friendId, Collections.emptyList())); }
    }

    public boolean hasMoreHistory(String friendId) { return false; }

    private Person person(F f, long now) {
        List<Message> c = convs.get(f.id);
        Message last = c == null || c.isEmpty() ? null : c.get(c.size() - 1);
        boolean online = !f.bye && f.lastSeen > 0 && now - f.lastSeen < ONLINE_MS;
        return new Person(f.id, f.name, online, f.lastSeen, f.unread, f.peerRead, last);
    }

    private void setConn(Conn c, String detail) {
        boolean changed = conn != c || !String.valueOf(detail).equals(connDetail);
        conn = c;
        connDetail = detail == null ? "" : detail;
        if (changed) for (Listener l : listeners) safe(() -> l.onConnection(c, connDetail));
    }

    private void fireRoster() { for (Listener l : listeners) safe(l::onRoster); }
    private void notice(String text) { for (Listener l : listeners) safe(() -> l.onNotice(text)); }

    private static void safe(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { e.printStackTrace(); }
    }

    public static String normalizeServer(String raw) throws ChatException {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new ChatException("Введите адрес сервиса доставки.", 0, "bad_url");
        if (!s.matches("(?i)^https?://.*")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        try {
            URI u = URI.create(s);
            if (u.getHost() == null) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ChatException("Некорректный адрес сервиса доставки.", 0, "bad_url");
        }
        return s;
    }

    static String cleanName(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : (raw == null ? "" : raw).toCharArray()) sb.append(Character.isISOControl(c) ? ' ' : c);
        String n = sb.toString().trim().replaceAll("\\s+", " ");
        if (n.length() > 24) n = n.substring(0, 24).trim();
        return n;
    }

    public void setup(String serverUrl, String name) throws ChatException {
        String url = normalizeServer(serverUrl == null || serverUrl.isBlank() ? DEFAULT_SERVER : serverUrl);
        String nick = cleanName(name);
        if (nick.length() < 2) throw new ChatException("Введите ник (минимум 2 символа).", 400, "bad_name");
        stop(false);
        synchronized (model) {
            relayUrl = url;
            myId = newId();
            myName = nick;
            friendMap.clear(); in.clear(); out.clear(); convs.clear(); seen.clear();
            sinceSec = 0;
            activeChat = null;
            relay = null;
        }
        saveNow();
        start();
        fireRoster();
    }

    private static String newId() {
        String alphabet = ChatServer.ALPHABET;
        StringBuilder sb = new StringBuilder("NX-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    public synchronized void start() {
        if (!isConfigured() || running) return;
        running = true;
        needHello = true;
        setConn(Conn.CONNECTING, "");
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nexus-chat-io");
            t.setDaemon(true);
            return t;
        });
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nexus-chat-beat");
            t.setDaemon(true);
            return t;
        });
        String since;
        synchronized (model) { since = sinceSec <= 0 ? "all" : String.valueOf(sinceSec); }
        sub = relay().subscribe(topicOf(myId()), since, this::onIncoming, this::onRelayState);
        sched.scheduleWithFixedDelay(() -> { pingAll(false); fireRoster(); }, PING_SEC, PING_SEC, TimeUnit.SECONDS);
    }

    public void stop() { stop(true); }

    public void flush() { saveNow(); }

    private synchronized void stop(boolean announceOffline) {
        if (!running) { saveNow(); return; }
        running = false;
        if (announceOffline && conn == Conn.ONLINE) {
            for (String id : friendIds()) {
                try { sendEnv(id, "bye", null, false); } catch (ChatException e) { break; }
            }
        }
        if (sub != null) sub.close();
        sub = null;
        if (sched != null) sched.shutdownNow();
        if (io != null) io.shutdown();
        sched = null;
        io = null;
        saveNow();
        if (isConfigured()) setConn(Conn.OFFLINE, "");
    }

    public void logout() {
        stop(true);
        synchronized (model) {
            relayUrl = ""; myId = ""; myName = "";
            friendMap.clear(); in.clear(); out.clear(); convs.clear(); seen.clear();
            sinceSec = 0;
            activeChat = null;
            relay = null;
        }
        synchronized (fileLock) {
            try { Files.deleteIfExists(stateFile.toPath()); } catch (IOException ignored) { }
            try { Files.deleteIfExists(backupFile().toPath()); } catch (IOException ignored) { }
        }
        setConn(Conn.NOT_CONFIGURED, "");
        fireRoster();
    }

    private synchronized Relay relay() {
        if (relay == null) relay = relayFactory.apply(server());
        return relay;
    }

    private List<String> friendIds() {
        synchronized (model) { return new ArrayList<>(friendMap.keySet()); }
    }

    private void onRelayState(boolean connected, String detail) {
        if (!running) return;
        if (connected) {
            setConn(Conn.ONLINE, "");
            if (needHello) {
                needHello = false;
                ExecutorService e = io;
                if (e != null) try { e.execute(() -> pingAll(true)); } catch (RuntimeException ignored) { }
            }
        } else {
            needHello = true;
            setConn(Conn.OFFLINE, detail);
        }
    }

    public String addFriend(String rawId) throws ChatException {
        String id = ChatServer.normalizeId(rawId);
        if (id == null) throw new ChatException("Неверный формат ID. Он выглядит так: NX-AB12-CD34.", 400, "bad_id");
        if (id.equals(myId())) throw new ChatException("Нельзя добавить самого себя.", 400, "self");
        boolean hasIncoming;
        synchronized (model) {
            if (friendMap.containsKey(id)) throw new ChatException("Этот игрок уже у вас в друзьях.", 409, "already");
            hasIncoming = in.containsKey(id);
            if (!hasIncoming) {
                if (friendMap.size() >= MAX_FRIENDS) throw new ChatException("Достигнут лимит друзей (" + MAX_FRIENDS + ").", 409, "friends_limit");
                if (out.size() >= MAX_PENDING && !out.containsKey(id)) throw new ChatException("Слишком много неотвеченных заявок.", 409, "pending_limit");
            }
        }
        if (hasIncoming) {
            respond(id, true);
            return "accepted";
        }
        boolean fresh;
        synchronized (model) {
            fresh = !out.containsKey(id);
            if (fresh) out.put(id, new R(id, id, System.currentTimeMillis()));
        }
        try {
            sendEnv(id, "req", null, true);
        } catch (ChatException e) {
            if (fresh) synchronized (model) { out.remove(id); }
            throw e;
        }
        scheduleSave();
        fireRoster();
        return "requested";
    }

    public void respond(String id, boolean accept) throws ChatException {
        R r;
        synchronized (model) {
            r = in.remove(id);
            if (r == null) throw new ChatException("Эта заявка уже не актуальна.", 404, "no_request");
            if (accept) addFriendLocked(id, r.name);
        }
        try {
            sendEnv(id, accept ? "acc" : "rej", null, true);
        } catch (ChatException e) {
            if (accept) synchronized (model) { friendMap.remove(id); in.put(id, r); }
            else synchronized (model) { in.put(id, r); }
            fireRoster();
            throw e;
        }
        scheduleSave();
        fireRoster();
    }

    public void remove(String id) throws ChatException {
        boolean wasFriend, hadIn, hadOut;
        synchronized (model) {
            wasFriend = friendMap.remove(id) != null;
            hadIn = in.remove(id) != null;
            hadOut = out.remove(id) != null;
            convs.remove(id);
            if (id.equals(activeChat)) activeChat = null;
        }
        if (!wasFriend && !hadIn && !hadOut) throw new ChatException("Не найдено.", 404, "not_found");
        scheduleSave();
        fireRoster();
        try {
            if (wasFriend || hadOut) sendEnv(id, "rm", null, true);
            else sendEnv(id, "rej", null, true);
        } catch (ChatException ignored) { }
    }

    public Message send(String to, String text) throws ChatException {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) throw new ChatException("Пустое сообщение.", 400, "empty");
        if (t.length() > MAX_TEXT) throw new ChatException("Сообщение длиннее " + MAX_TEXT + " символов.", 400, "too_long");
        if (friend(to) == null) throw new ChatException("Этот игрок не в вашем списке друзей.", 404, "not_friend");
        Message last = null;
        for (String chunk : chunks(t)) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("text", chunk);
            Map<String, Object> env = sendEnv(to, "msg", extra, true);
            long ts = MiniJson.num(env, "ts", System.currentTimeMillis());
            Message m = new Message(seqOf(ts, myId(), to), myId(), to, chunk, ts);
            synchronized (model) {
                if (friendMap.containsKey(to)) {
                    merge(to, m);
                    seenAdd(MiniJson.str(env, "id", ""));
                }
            }
            last = m;
            saveNow();
            fireRoster();
            Message fm = m;
            for (Listener l : listeners) safe(() -> l.onMessage(fm, true));
        }
        return last;
    }

    private static List<String> chunks(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            int b = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + b > CHUNK_BYTES) { parts.add(cur.toString()); cur.setLength(0); bytes = 0; }
            cur.appendCodePoint(cp);
            bytes += b;
            i += n;
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts;
    }

    public Page history(String friendId, long before) throws ChatException {
        List<Message> all = conversation(friendId);
        List<Message> older = new ArrayList<>();
        for (Message m : all) if (before <= 0 || m.seq() < before) older.add(m);
        return new Page(older, false);
    }

    public void clearChat(String friendId, boolean forBoth) throws ChatException {
        long cutoff;
        synchronized (model) {
            if (!friendMap.containsKey(friendId)) throw new ChatException("Этот игрок не в вашем списке друзей.", 404, "not_friend");
            cutoff = System.currentTimeMillis();
            for (Message m : convs.getOrDefault(friendId, Collections.emptyList())) cutoff = Math.max(cutoff, m.ts());
        }
        if (forBoth) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("upTo", cutoff);
            sendEnv(friendId, "clr", extra, true);
        }
        synchronized (model) {
            dropUpTo(friendId, cutoff);
        }
        saveNow();
        fireRoster();
    }

    public void deleteMessage(String friendId, long seq, boolean forBoth) throws ChatException {
        synchronized (model) {
            if (!friendMap.containsKey(friendId)) throw new ChatException("Этот игрок не в вашем списке друзей.", 404, "not_friend");
            Message target = null;
            for (Message m : convs.getOrDefault(friendId, Collections.emptyList())) if (m.seq() == seq) { target = m; break; }
            if (target == null) throw new ChatException("Сообщение уже удалено.", 404, "no_message");
            if (forBoth && !target.from().equals(myId)) throw new ChatException("У собеседника можно удалить только свои сообщения.", 403, "not_yours");
        }
        if (forBoth) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("seq", seq);
            sendEnv(friendId, "del", extra, true);
        }
        synchronized (model) { dropMessage(friendId, friendId, seq, true); }
        saveNow();
        fireRoster();
    }

    private void dropUpTo(String friendId, long cutoff) {
        List<Message> list = convs.get(friendId);
        if (list != null) {
            list.removeIf(m -> m.ts() <= cutoff);
            if (list.isEmpty()) convs.remove(friendId);
        }
        capUnread(friendId);
    }

    private void dropMessage(String friendId, String author, long seq, boolean local) {
        List<Message> list = convs.get(friendId);
        if (list != null) {
            list.removeIf(m -> m.seq() == seq && (local || m.from().equals(author)));
            if (list.isEmpty()) convs.remove(friendId);
        }
        capUnread(friendId);
    }

    private void capUnread(String friendId) {
        F f = friendMap.get(friendId);
        if (f == null) return;
        int theirs = 0;
        for (Message m : convs.getOrDefault(friendId, Collections.emptyList())) if (m.from().equals(friendId)) theirs++;
        f.unread = Math.min(f.unread, theirs);
    }

    public void markRead(String friendId) throws ChatException {
        boolean had;
        long upTo = 0;
        synchronized (model) {
            F f = friendMap.get(friendId);
            had = f != null && f.unread > 0;
            if (f != null) f.unread = 0;
            List<Message> c = convs.get(friendId);
            if (c != null) for (Message m : c) if (m.from().equals(friendId)) upTo = Math.max(upTo, m.seq());
        }
        if (!had) return;
        scheduleSave();
        fireRoster();
        if (upTo > 0) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("upTo", upTo);
            try { sendEnv(friendId, "read", extra, true); } catch (ChatException ignored) { }
        }
    }

    public void setActiveChat(String friendId) {
        synchronized (model) { activeChat = friendId; }
    }

    public void rename(String name) throws ChatException {
        String nick = cleanName(name);
        if (nick.length() < 2) throw new ChatException("Ник — минимум 2 символа.", 400, "bad_name");
        synchronized (model) { myName = nick; }
        scheduleSave();
        fireRoster();
        for (String id : friendIds()) {
            try { sendEnv(id, "name", null, true); } catch (ChatException e) { break; }
        }
    }

    public void refresh() {
        ExecutorService e = io;
        if (running && e != null) try { e.execute(() -> pingAll(true)); } catch (RuntimeException ignored) { }
        fireRoster();
    }

    private void pingAll(boolean reply) {
        for (String id : friendIds()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("reply", reply);
            try { sendEnv(id, "ping", extra, false); } catch (ChatException e) { return; }
        }
    }

    private void onIncoming(Relay.Incoming incoming) {
        Map<String, Object> env;
        try {
            env = MiniJson.parseObject(open(myId(), incoming.payload()));
        } catch (RuntimeException e) {
            return;
        }
        synchronized (model) { sinceSec = Math.max(sinceSec, incoming.timeSec()); }
        process(env);
        scheduleSave();
    }

    private void process(Map<String, Object> env) {
        String id = MiniJson.str(env, "id", "");
        String from = ChatServer.normalizeId(MiniJson.str(env, "from", ""));
        String type = MiniJson.str(env, "t", "");
        if (id.isEmpty() || from == null || from.equals(myId())) return;
        String name = cleanName(MiniJson.str(env, "name", ""));
        if (name.isEmpty()) name = from;
        long now = System.currentTimeMillis();
        long ts = MiniJson.num(env, "ts", now);
        if (ts > now + 86_400_000L || ts <= 0) ts = now;

        List<Runnable> after = new ArrayList<>();
        boolean roster = false;
        synchronized (model) {
            if (seen.contains(id)) return;
            seenAdd(id);
            F f = friendMap.get(from);
            switch (type) {
                case "ping": {
                    if (f == null) return;
                    f.lastSeen = now; f.bye = false; f.name = name;
                    if (MiniJson.bool(env, "reply", false)) after.add(() -> quiet(() -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("reply", false);
                        sendEnv(from, "ping", x, false);
                    }));
                    roster = true;
                    break;
                }
                case "bye": {
                    if (f == null) return;
                    f.bye = true;
                    roster = true;
                    break;
                }
                case "req": {
                    if (f != null) {
                        after.add(() -> quiet(() -> sendEnv(from, "acc", null, true)));
                    } else if (out.containsKey(from)) {
                        out.remove(from);
                        addFriendLocked(from, name);
                        String n = name;
                        after.add(() -> quiet(() -> sendEnv(from, "acc", null, true)));
                        after.add(() -> notice(n + " теперь в вашем списке друзей"));
                        roster = true;
                    } else if (!in.containsKey(from) && in.size() < MAX_PENDING) {
                        in.put(from, new R(from, name, ts));
                        String n = name;
                        after.add(() -> notice(n + " хочет добавить вас в друзья"));
                        roster = true;
                    } else if (in.containsKey(from)) {
                        in.get(from).name = name;
                        roster = true;
                    }
                    break;
                }
                case "acc": {
                    if (out.containsKey(from)) {
                        out.remove(from);
                        addFriendLocked(from, name);
                        String n = name;
                        after.add(() -> notice(n + " принял вашу заявку — теперь вы друзья"));
                        roster = true;
                    } else if (f != null) {
                        f.name = name;
                        roster = true;
                    }
                    break;
                }
                case "rej": {
                    roster = out.remove(from) != null;
                    break;
                }
                case "rm": {
                    boolean was = friendMap.remove(from) != null;
                    boolean hadIn = in.remove(from) != null;
                    boolean hadOut = out.remove(from) != null;
                    if (was) {
                        convs.remove(from);
                        if (from.equals(activeChat)) activeChat = null;
                        String n = name;
                        after.add(() -> notice(n + " удалил вас из друзей"));
                    }
                    roster = was || hadIn || hadOut;
                    break;
                }
                case "name": {
                    if (f == null) return;
                    f.name = name;
                    roster = true;
                    break;
                }
                case "clr": {
                    if (f == null) return;
                    dropUpTo(from, MiniJson.num(env, "upTo", 0));
                    roster = true;
                    after.add(() -> saveNow());
                    break;
                }
                case "del": {
                    if (f == null) return;
                    dropMessage(from, from, MiniJson.num(env, "seq", -1), false);
                    roster = true;
                    after.add(() -> saveNow());
                    break;
                }
                case "read": {
                    if (f == null) return;
                    long upTo = MiniJson.num(env, "upTo", 0);
                    f.peerRead = Math.max(f.peerRead, upTo);
                    after.add(() -> { for (Listener l : listeners) safe(() -> l.onRead(from, upTo)); });
                    break;
                }
                case "msg": {
                    if (f == null) return;
                    String text = MiniJson.str(env, "text", "");
                    if (text.isEmpty() || text.length() > MAX_TEXT) return;
                    f.name = name; f.lastSeen = now; f.bye = false;
                    Message m = new Message(seqOf(ts, from, myId), from, myId, text, ts);
                    boolean isActive = from.equals(activeChat);
                    if (merge(from, m) && !isActive) f.unread++;
                    roster = true;
                    after.add(() -> { for (Listener l : listeners) safe(() -> l.onMessage(m, false)); });
                    if (isActive) after.add(() -> quiet(() -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("upTo", m.seq());
                        sendEnv(from, "read", x, true);
                    }));
                    break;
                }
                default:
                    return;
            }
        }
        if (roster) fireRoster();
        ExecutorService e = io;
        for (Runnable r : after) {
            if (e != null) try { e.execute(r); continue; } catch (RuntimeException ignored) { }
            safe(r);
        }
    }

    private interface Throwing { void run() throws ChatException; }

    private static void quiet(Throwing t) {
        try { t.run(); } catch (ChatException ignored) { }
    }

    private void addFriendLocked(String id, String name) {
        F f = new F();
        f.id = id;
        f.name = name == null || name.isEmpty() ? id : name;
        friendMap.put(id, f);
    }

    private boolean merge(String friendId, Message m) {
        List<Message> list = convs.computeIfAbsent(friendId, k -> new ArrayList<>());
        for (Message x : list) if (x.seq() == m.seq()) return false;
        list.add(m);
        list.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        while (list.size() > MAX_HISTORY) list.remove(0);
        return true;
    }

    private void seenAdd(String id) {
        if (id.isEmpty()) return;
        seen.add(id);
        while (seen.size() > MAX_SEEN) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
    }

    static long seqOf(long ts, String from, String to) {
        return ts * 2 + (from.compareTo(to) < 0 ? 0 : 1);
    }

    private Map<String, Object> sendEnv(String to, String type, Map<String, Object> extra, boolean cache) throws ChatException {
        if (!isConfigured()) throw new ChatException("Чат не настроен.", 0, "not_configured");
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("v", 1);
        env.put("t", type);
        env.put("id", UUID.randomUUID().toString());
        env.put("from", myId());
        env.put("name", myName());
        long ts;
        synchronized (model) { ts = Math.max(System.currentTimeMillis(), lastTs + 1); lastTs = ts; }
        env.put("ts", ts);
        if (extra != null) env.putAll(extra);
        String payload = seal(to, MiniJson.write(env));
        if (payload.length() > MAX_PAYLOAD) throw new ChatException("Сообщение слишком длинное.", 400, "too_long");
        relay().publish(topicOf(to), payload, cache);
        return env;
    }

    static String topicOf(String id) {
        return "nxc-" + hex(sha256("nexus-chat-v1|topic|" + id)).substring(0, 40);
    }

    private static byte[] sha256(String s) {
        try { return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String seal(String toId, String json) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sha256("nexus-chat-v1|key|" + toId), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String open(String myId, String payload) {
        try {
            byte[] all = Base64.getUrlDecoder().decode(payload.trim());
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256("nexus-chat-v1|key|" + myId), "AES"), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("bad payload");
        }
    }

    private void scheduleSave() {
        if (savePending) return;
        savePending = true;
        try {
            saver.schedule(() -> { savePending = false; saveNow(); }, 150, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            savePending = false;
        }
    }

    private File backupFile() { return new File(stateFile.getAbsolutePath() + ".bak"); }

    private void load() {
        if (stateFile == null) return;
        if (tryLoad(stateFile)) return;
        File bak = backupFile();
        if (bak.exists() && tryLoad(bak)) {
            System.err.println("Данные чата восстановлены из резервной копии.");
            return;
        }
        if (stateFile.exists() && MiniJson.num(safeParse(stateFile), "v", 0) == 2) {
            try { Files.copy(stateFile.toPath(), new File(stateFile.getAbsolutePath() + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { }
        }
    }

    private static Map<String, Object> safeParse(File f) {
        try { return MiniJson.parseObject(Files.readString(f.toPath(), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }

    private boolean tryLoad(File file) {
        if (!file.exists()) return false;
        try {
            Map<String, Object> m = MiniJson.parseObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            if (MiniJson.num(m, "v", 0) != 2) return false;
            relayUrl = MiniJson.str(m, "relay", "");
            myId = MiniJson.str(m, "id", "");
            myName = MiniJson.str(m, "name", "");
            sinceSec = MiniJson.num(m, "since", 0);
            for (Object o : MiniJson.list(m, "friends")) {
                @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                F f = new F();
                f.id = MiniJson.str(x, "id", "");
                f.name = MiniJson.str(x, "name", f.id);
                f.lastSeen = MiniJson.num(x, "lastSeen", 0);
                f.peerRead = MiniJson.num(x, "peerRead", 0);
                f.unread = (int) MiniJson.num(x, "unread", 0);
                friendMap.put(f.id, f);
            }
            for (Object o : MiniJson.list(m, "incoming")) {
                @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                in.put(MiniJson.str(x, "id", ""), new R(MiniJson.str(x, "id", ""), MiniJson.str(x, "name", "?"), MiniJson.num(x, "ts", 0)));
            }
            for (Object o : MiniJson.list(m, "outgoing")) {
                @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                out.put(MiniJson.str(x, "id", ""), new R(MiniJson.str(x, "id", ""), MiniJson.str(x, "name", "?"), MiniJson.num(x, "ts", 0)));
            }
            Map<String, Object> cv = MiniJson.obj(m, "convs");
            if (cv != null) {
                for (Map.Entry<String, Object> e : cv.entrySet()) {
                    List<Message> list = new ArrayList<>();
                    if (e.getValue() instanceof List<?> l) {
                        for (Object o : l) {
                            @SuppressWarnings("unchecked") Map<String, Object> x = (Map<String, Object>) o;
                            list.add(new Message(MiniJson.num(x, "seq", 0), MiniJson.str(x, "from", ""), MiniJson.str(x, "to", ""),
                                    MiniJson.str(x, "text", ""), MiniJson.num(x, "ts", 0)));
                        }
                    }
                    convs.put(e.getKey(), list);
                }
            }
            for (Object o : MiniJson.list(m, "seen")) seen.add(String.valueOf(o));
            if (!myId.isEmpty()) conn = Conn.OFFLINE;
            return !myId.isEmpty();
        } catch (Exception e) {
            System.err.println("Не удалось прочитать данные чата: " + e.getMessage());
            relayUrl = ""; myId = ""; myName = "";
            friendMap.clear(); in.clear(); out.clear(); convs.clear(); seen.clear();
            sinceSec = 0;
            return false;
        }
    }

    private final Object fileLock = new Object();

    private void saveNow() {
        if (stateFile == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        synchronized (model) {
            if (myId.isEmpty()) return;
            m.put("v", 2);
            m.put("relay", relayUrl);
            m.put("id", myId);
            m.put("name", myName);
            m.put("since", sinceSec);
            List<Object> fr = new ArrayList<>();
            for (F f : friendMap.values()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("id", f.id); x.put("name", f.name); x.put("lastSeen", f.lastSeen);
                x.put("peerRead", f.peerRead); x.put("unread", f.unread);
                fr.add(x);
            }
            m.put("friends", fr);
            m.put("incoming", reqList(in));
            m.put("outgoing", reqList(out));
            Map<String, Object> cv = new LinkedHashMap<>();
            for (Map.Entry<String, List<Message>> e : convs.entrySet()) {
                List<Object> l = new ArrayList<>();
                for (Message x : e.getValue()) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("seq", x.seq()); o.put("from", x.from()); o.put("to", x.to()); o.put("text", x.text()); o.put("ts", x.ts());
                    l.add(o);
                }
                cv.put(e.getKey(), l);
            }
            m.put("convs", cv);
            m.put("seen", new ArrayList<Object>(seen));
        }
        String json = MiniJson.write(m);
        synchronized (fileLock) {
            try {
                File parent = stateFile.getAbsoluteFile().getParentFile();
                if (parent != null) parent.mkdirs();
                File tmp = new File(stateFile.getAbsolutePath() + ".tmp");
                Files.writeString(tmp.toPath(), json, StandardCharsets.UTF_8);
                if (stateFile.exists()) {
                    try { Files.copy(stateFile.toPath(), backupFile().toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) { }
                }
                Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("Не удалось сохранить данные чата: " + e.getMessage());
            }
        }
    }

    private static List<Object> reqList(Map<String, R> src) {
        List<Object> l = new ArrayList<>();
        for (R r : src.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.id); x.put("name", r.name); x.put("ts", r.ts);
            l.add(x);
        }
        return l;
    }
}
