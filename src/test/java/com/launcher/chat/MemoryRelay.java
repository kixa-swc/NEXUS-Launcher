package com.launcher.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class MemoryRelay implements Relay {
    final Map<String, List<Incoming>> cached = new HashMap<>();
    final Map<String, List<Sub>> subs = new HashMap<>();
    final List<String> published = new CopyOnWriteArrayList<>();
    volatile boolean down;
    private long clock = 1_000_000;

    static final class Sub implements Subscription, Runnable {
        final Consumer<Incoming> handler;
        final StateListener state;
        volatile boolean closed;
        Sub(Consumer<Incoming> h, StateListener s) { handler = h; state = s; }
        @Override public void close() { closed = true; }
        @Override public void run() { }
    }

    @Override
    public synchronized void publish(String topic, String payload, boolean cache) throws ChatService.ChatException {
        if (down) throw new ChatService.ChatException("Нет связи.", 0, "network");
        published.add(payload);
        Incoming in = new Incoming(payload, ++clock);
        if (cache) cached.computeIfAbsent(topic, k -> new ArrayList<>()).add(in);
        for (Sub s : new ArrayList<>(subs.getOrDefault(topic, List.of()))) {
            if (!s.closed) {
                new Thread(() -> s.handler.accept(in)).start();
            }
        }
    }

    @Override
    public synchronized Subscription subscribe(String topic, String since, Consumer<Incoming> handler, StateListener state) {
        Sub s = new Sub(handler, state);
        subs.computeIfAbsent(topic, k -> new ArrayList<>()).add(s);
        long from = "all".equals(since) ? 0 : Long.parseLong(since);
        List<Incoming> replay = new ArrayList<>();
        for (Incoming i : cached.getOrDefault(topic, List.of())) if (i.timeSec() >= from) replay.add(i);
        new Thread(() -> {
            state.onState(true, "");
            for (Incoming i : replay) handler.accept(i);
        }).start();
        return s;
    }
}
