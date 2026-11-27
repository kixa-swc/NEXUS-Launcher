package com.launcher.chat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class ChatHub {
    public static final int LOCAL_PORT = 8765;

    private static ChatService service;
    private static ChatServer local;

    private ChatHub() { }

    public static File dir() {
        return new File(new File(System.getProperty("user.home"), ".nexus"), "chat");
    }

    public static synchronized ChatService service() {
        if (service == null) {
            service = new ChatService(new File(dir(), "identity.json"));
            final ChatService svc = service;
            Runtime.getRuntime().addShutdownHook(new Thread(svc::flush, "nexus-chat-flush"));
        }
        return service;
    }

    public static synchronized void autostart() {
        File flag = new File(dir(), "local-server.enabled");
        if (flag.exists() && local == null) {
            try { startLocalServer(); } catch (IOException e) { System.err.println("[chat] Свой сервер не запущен: " + e.getMessage()); }
        }
        service().start();
    }

    public static synchronized boolean isLocalServerRunning() { return local != null; }

    public static synchronized void startLocalServer() throws IOException {
        if (local != null) return;
        try {
            local = ChatServer.start("0.0.0.0", LOCAL_PORT, new File(dir(), "server-data.json"));
        } catch (java.net.BindException e) {
            throw new IOException("Порт " + LOCAL_PORT + " уже занят другой программой (возможно, сервер чатов уже запущен).");
        }
        dir().mkdirs();
        try { Files.writeString(new File(dir(), "local-server.enabled").toPath(), "1"); } catch (IOException ignored) { }
    }

    public static synchronized void stopLocalServer() {
        if (local != null) { local.stop(); local = null; }
        try { Files.deleteIfExists(new File(dir(), "local-server.enabled").toPath()); } catch (IOException ignored) { }
    }

    public static synchronized void shutdown() {
        if (service != null) { service.stop(); service.flush(); }
        if (local != null) { local.stop(); local = null; }
    }
}
