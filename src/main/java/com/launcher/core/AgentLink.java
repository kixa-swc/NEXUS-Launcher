package com.launcher.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.zip.CRC32;

public final class AgentLink {
    private AgentLink() { }

    public record Link(int port, String token) { }

    public record Result(boolean ok, String message) { }

    public static String prepare(File baseDir, Link[] linkOut, File gameJar) {
        try {
            if (!Boolean.parseBoolean(SettingsManager.getSetting("game_link", "true"))) return null;
            byte[] jar;
            try (InputStream in = AgentLink.class.getResourceAsStream("/agent/nexus-link.jar")) {
                if (in == null) return null;
                jar = in.readAllBytes();
            }
            CRC32 crc = new CRC32();
            crc.update(jar);
            File target = new File(baseDir, "nexus-link-" + Long.toHexString(crc.getValue()) + ".jar");
            if (!target.isFile() || target.length() != jar.length) {
                baseDir.mkdirs();
                Files.write(target.toPath(), jar);
            }
            int port;
            try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = probe.getLocalPort(); }
            byte[] raw = new byte[12];
            new SecureRandom().nextBytes(raw);
            StringBuilder token = new StringBuilder();
            for (byte b : raw) token.append(String.format("%02x", b));
            linkOut[0] = new Link(port, token.toString());
            return "-javaagent:" + target.getAbsolutePath() + "=" + port + ":" + token
                    + (gameJar != null && gameJar.isFile() ? ":" + gameJar.getAbsolutePath() : "");
        } catch (Exception e) {
            return null;
        }
    }

    public static Result connect(Link link, String host, int port) {
        if (link == null) return new Result(false, "у этой игры нет связи с лаунчером (она запущена без неё)");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", link.port()), 2000);
            socket.setSoTimeout(45_000);
            socket.getOutputStream().write(("CONNECT " + link.token() + " " + host + " " + port + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String reply = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (reply == null) return new Result(false, "игра не ответила");
            if (reply.equals("OK")) return new Result(true, "");
            return new Result(false, reply.startsWith("ERR ") ? reply.substring(4) : reply);
        } catch (java.net.ConnectException e) {
            return new Result(false, "связь с игрой недоступна");
        } catch (Exception e) {
            return new Result(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
