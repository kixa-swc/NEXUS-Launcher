package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Hashtable;

public final class ServerPinger {
    private static final int PROTOCOL = 767;
    private static final int MAX_JSON_BYTES = 1 << 20;

    private ServerPinger() { }

    public record Status(boolean online, int players, int maxPlayers, String version, String motd, long latencyMs, String error) {
        static Status offline(String error) { return new Status(false, 0, 0, "", "", -1, error); }
    }

    public static Status ping(String address, int timeoutMs) {
        ServerList.Address parsed = ServerList.parseAddress(address);
        if (parsed == null) return Status.offline("неверный адрес");
        String host = parsed.host();
        int port = parsed.port();
        if (!parsed.explicitPort()) {
            String[] srv = lookupSrv(host);
            if (srv != null) { host = srv[0]; port = Integer.parseInt(srv[1]); }
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream h = new DataOutputStream(handshake);
            writeVarInt(h, 0x00);
            writeVarInt(h, PROTOCOL);
            byte[] hostBytes = parsed.host().getBytes(StandardCharsets.UTF_8);
            writeVarInt(h, hostBytes.length);
            h.write(hostBytes);
            h.writeShort(parsed.port());
            writeVarInt(h, 1);
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0x00});
            out.flush();

            readVarInt(in);
            int id = readVarInt(in);
            if (id != 0x00) return Status.offline("неожиданный ответ сервера");
            int length = readVarInt(in);
            if (length <= 0 || length > MAX_JSON_BYTES) return Status.offline("слишком большой ответ");
            byte[] json = new byte[length];
            in.readFully(json);

            long latency = -1;
            try {
                long sent = System.nanoTime();
                ByteArrayOutputStream ping = new ByteArrayOutputStream();
                DataOutputStream p = new DataOutputStream(ping);
                writeVarInt(p, 0x01);
                p.writeLong(sent);
                writePacket(out, ping.toByteArray());
                out.flush();
                readVarInt(in);
                if (readVarInt(in) == 0x01) { in.readLong(); latency = Math.max(1, (System.nanoTime() - sent) / 1_000_000L); }
            } catch (IOException ignored) { }

            return parse(new String(json, StandardCharsets.UTF_8), latency);
        } catch (java.net.UnknownHostException e) {
            return Status.offline("сервер не найден");
        } catch (java.net.SocketTimeoutException e) {
            return Status.offline("нет ответа");
        } catch (java.net.ConnectException e) {
            return Status.offline("соединение отклонено");
        } catch (Exception e) {
            return Status.offline(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static Status parse(String json, long latency) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int online = 0, max = 0;
            if (root.has("players") && root.get("players").isJsonObject()) {
                JsonObject players = root.getAsJsonObject("players");
                online = intOf(players, "online");
                max = intOf(players, "max");
            }
            String version = "";
            if (root.has("version") && root.get("version").isJsonObject()) {
                JsonElement name = root.getAsJsonObject("version").get("name");
                if (name != null && name.isJsonPrimitive()) version = stripFormatting(name.getAsString());
            }
            StringBuilder motd = new StringBuilder();
            if (root.has("description")) flatten(root.get("description"), motd);
            String text = stripFormatting(motd.toString()).replaceAll("\\s*\\R\\s*", " · ").replaceAll("\\s{2,}", " ").trim();
            return new Status(true, online, max, version, text, latency, null);
        } catch (Exception e) {
            return Status.offline("непонятный ответ сервера");
        }
    }

    private static void flatten(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) { out.append(element.getAsString()); return; }
        if (element.isJsonArray()) { for (JsonElement part : element.getAsJsonArray()) flatten(part, out); return; }
        JsonObject o = element.getAsJsonObject();
        if (o.has("text") && o.get("text").isJsonPrimitive()) out.append(o.get("text").getAsString());
        if (o.has("extra") && o.get("extra").isJsonArray()) {
            JsonArray extra = o.getAsJsonArray("extra");
            for (JsonElement part : extra) flatten(part, out);
        }
    }

    private static int intOf(JsonObject o, String key) {
        try { return o.has(key) ? o.get(key).getAsInt() : 0; } catch (Exception e) { return 0; }
    }

    static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }

    private static String[] lookupSrv(String host) {
        if (host.matches("[0-9.]+") || host.contains(":")) return null;
        String[] viaJdk = lookupSrvJdk(host);
        if (viaJdk != null) return viaJdk;
        for (String endpoint : new String[]{"https://cloudflare-dns.com/dns-query", "https://dns.google/resolve"}) {
            String[] found = lookupSrvHttps(endpoint, host);
            if (found != null) return found;
        }
        return null;
    }

    private static String[] lookupSrvHttps(String endpoint, String host) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) java.net.URI.create(
                    endpoint + "?type=SRV&name=" + java.net.URLEncoder.encode("_minecraft._tcp." + host, StandardCharsets.UTF_8)).toURL().openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestProperty("Accept", "application/dns-json");
            if (c.getResponseCode() / 100 != 2) return null;
            JsonObject root;
            try (java.io.InputStream in = c.getInputStream()) {
                root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (!root.has("Answer") || !root.get("Answer").isJsonArray()) return null;
            String[] best = null;
            int bestPriority = Integer.MAX_VALUE;
            for (JsonElement e : root.getAsJsonArray("Answer")) {
                if (!e.isJsonObject() || intOf(e.getAsJsonObject(), "type") != 33) continue;
                String[] parts = e.getAsJsonObject().get("data").getAsString().trim().split("\\s+");
                if (parts.length < 4) continue;
                int priority = Integer.parseInt(parts[0]);
                if (priority >= bestPriority) continue;
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                if (target.isEmpty()) continue;
                bestPriority = priority;
                best = new String[]{target, Integer.toString(Integer.parseInt(parts[2]))};
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String[] lookupSrvJdk(String host) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "1500");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
            try {
                javax.naming.directory.Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
                javax.naming.directory.Attribute srv = attrs.get("SRV");
                if (srv == null || srv.size() == 0) return null;
                String[] parts = srv.get(0).toString().split("\\s+");
                if (parts.length < 4) return null;
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new String[]{target, Integer.toString(Integer.parseInt(parts[2]))};
            } finally {
                ctx.close();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writePacket(DataOutputStream out, byte[] payload) throws IOException {
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IOException("VarInt слишком длинный");
    }
}
