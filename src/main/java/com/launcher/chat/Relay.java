package com.launcher.chat;

import java.util.function.Consumer;

public interface Relay {
    record Incoming(String payload, long timeSec) { }

    interface Subscription { void close(); }

    interface StateListener { void onState(boolean connected, String detail); }

    void publish(String topic, String payload, boolean cache) throws ChatService.ChatException;

    Subscription subscribe(String topic, String since, Consumer<Incoming> handler, StateListener state);
}
