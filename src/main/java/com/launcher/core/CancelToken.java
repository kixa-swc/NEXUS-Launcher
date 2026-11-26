package com.launcher.core;

import java.util.concurrent.CancellationException;

public final class CancelToken {
    public static final CancelToken NONE = new CancelToken();

    private volatile boolean cancelled;

    public void cancel() { cancelled = true; }

    public boolean isCancelled() { return cancelled; }

    public void check() {
        if (cancelled) throw new CancellationException("Установка отменена.");
    }
}
