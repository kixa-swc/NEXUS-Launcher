package com.launcher.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class SingleInstance {
    private static final int PORT = 47653;
    private static final String SHOW = "NEXUS-SHOW", PING = "NEXUS-PING", OK = "NEXUS-OK";
    private static ServerSocket server;

    private SingleInstance() { }

    private static volatile Runnable showHandler;
    private static volatile boolean showPending;

    public static synchronized boolean acquire(boolean wake) {
        InetAddress local = InetAddress.getLoopbackAddress();
        try {
            server = new ServerSocket(PORT, 4, local);
            startAccepting();
            return true;
        } catch (IOException busy) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(local, PORT), 1500);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(((wake ? SHOW : PING) + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                if (OK.equals(reply)) return false;
            } catch (java.net.SocketTimeoutException slow) {
                return false;
            } catch (IOException ignored) { }
            return true;
        }
    }

    public static synchronized void release() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    public static void listen(Runnable onShow) {
        showHandler = onShow;
        if (showPending) { showPending = false; onShow.run(); }
    }

    private static void startAccepting() {
        final ServerSocket listening = server;
        Thread thread = new Thread(() -> {
            while (!listening.isClosed()) {
                try (Socket client = listening.accept()) {
                    client.setSoTimeout(2000);
                    String line = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    if (SHOW.equals(line) || PING.equals(line)) {
                        client.getOutputStream().write((OK + "\n").getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        if (SHOW.equals(line)) {
                            Runnable handler = showHandler;
                            if (handler != null) handler.run(); else showPending = true;
                        }
                    }
                } catch (IOException ignored) { }
            }
        }, "nexus-single-instance");
        thread.setDaemon(true);
        thread.start();
    }
}
