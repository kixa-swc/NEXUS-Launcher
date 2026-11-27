package com.launcher.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

public final class NtfyRelay implements Relay {
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public NtfyRelay(String baseUrl) {
        String s = baseUrl == null ? "" : baseUrl.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        this.base = s;
    }

    @Override
    public void publish(String topic, String payload, boolean cache) throws ChatService.ChatException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/" + topic))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "NEXUS-Launcher-Chat/2")
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            if (!cache) b.header("Cache", "no");
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = r.statusCode();
            if (code == 429) throw new ChatService.ChatException("Слишком много сообщений подряд — подождите несколько секунд.", 429, "rate");
            if (code >= 400) throw new ChatService.ChatException("Сервис доставки сообщений ответил ошибкой (код " + code + ").", code, "relay");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChatService.ChatException("Прервано.", 0, "interrupted");
        } catch (IOException e) {
            throw new ChatService.ChatException("Нет связи с интернетом или сервисом доставки сообщений.", 0, "network");
        } catch (IllegalArgumentException e) {
            throw new ChatService.ChatException("Некорректный адрес сервиса доставки.", 0, "bad_url");
        }
    }

    @Override
    public Subscription subscribe(String topic, String since, Consumer<Incoming> handler, StateListener state) {
        Sub s = new Sub(topic, since, handler, state);
        s.thread.start();
        return s;
    }

    private final class Sub implements Subscription {
        final String topic;
        final Consumer<Incoming> handler;
        final StateListener state;
        final Thread thread;
        volatile String since;
        volatile boolean closed;
        volatile InputStream stream;
        volatile long lastActivity = System.currentTimeMillis();

        Sub(String topic, String since, Consumer<Incoming> handler, StateListener state) {
            this.topic = topic;
            this.since = since == null || since.isBlank() ? "all" : since;
            this.handler = handler;
            this.state = state;
            this.thread = new Thread(this::run, "nexus-chat-relay");
            this.thread.setDaemon(true);
        }

        @Override public void close() {
            closed = true;
            closeStream();
            thread.interrupt();
        }

        private void closeStream() {
            InputStream in = stream;
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }

        private void run() {
            int failures = 0;
            Thread watchdog = new Thread(() -> {
                while (!closed) {
                    try { Thread.sleep(15_000); } catch (InterruptedException e) { return; }
                    if (stream != null && System.currentTimeMillis() - lastActivity > 120_000) closeStream();
                }
            }, "nexus-chat-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                while (!closed) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/" + topic + "/json?since=" + since))
                                .header("User-Agent", "NEXUS-Launcher-Chat/2").GET().build();
                        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                        if (resp.statusCode() >= 400) {
                            resp.body().close();
                            throw new IOException("HTTP " + resp.statusCode());
                        }
                        stream = resp.body();
                        lastActivity = System.currentTimeMillis();
                        failures = 0;
                        state.onState(true, "");
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                            String line;
                            while (!closed && (line = br.readLine()) != null) {
                                lastActivity = System.currentTimeMillis();
                                if (line.isBlank()) continue;
                                Map<String, Object> ev;
                                try { ev = MiniJson.parseObject(line); } catch (RuntimeException e) { continue; }
                                if (!"message".equals(MiniJson.str(ev, "event", ""))) continue;
                                long time = MiniJson.num(ev, "time", 0);
                                String message = MiniJson.str(ev, "message", "");
                                if (time > 0) since = String.valueOf(time);
                                if (!message.isEmpty()) {
                                    try { handler.accept(new Incoming(message, time)); } catch (RuntimeException e) { e.printStackTrace(); }
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (IOException | RuntimeException e) {
                    } finally {
                        stream = null;
                    }
                    if (closed) return;
                    failures++;
                    state.onState(false, "Нет связи с интернетом или сервисом доставки сообщений. Пробую снова…");
                    try { Thread.sleep(Math.min(20_000L, 1000L << Math.min(failures, 4))); } catch (InterruptedException e) { return; }
                }
            } finally {
                watchdog.interrupt();
            }
        }
    }
}
