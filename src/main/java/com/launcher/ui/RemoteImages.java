package com.launcher.ui;

import com.launcher.core.ModrinthClient;
import javafx.application.Platform;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class RemoteImages {
    private static final int CACHE_LIMIT = 400;
    private static final Map<String, Image> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) { return size() > CACHE_LIMIT; }
    };
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> LOADING = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "nexus-icons");
        t.setDaemon(true);
        return t;
    });

    private RemoteImages() { }

    public static void load(String url, int pixels, Consumer<Image> onReady) {
        if (url == null || url.isBlank() || FAILED.contains(url)) return;
        String key = url + "@" + pixels;
        Image cached;
        synchronized (CACHE) { cached = CACHE.get(key); }
        if (cached != null) { onReady.accept(cached); return; }
        POOL.execute(() -> {
            try {
                byte[] data = normalize(ModrinthClient.downloadImage(url), pixels);
                Platform.runLater(() -> {
                    Image image = new Image(new ByteArrayInputStream(data), pixels, pixels, true, true);
                    if (image.isError()) { FAILED.add(url); return; }
                    synchronized (CACHE) { CACHE.put(key, image); }
                    onReady.accept(image);
                });
            } catch (Exception e) {
                FAILED.add(url);
            }
        });
    }

    private static byte[] normalize(byte[] data, int max) {
        try {
            ImageIO.setUseCache(false);
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(data));
            if (source == null) return data;
            int w = source.getWidth(), h = source.getHeight();
            double scale = Math.min(1.0, (double) max / Math.max(w, h));
            BufferedImage out = source;
            if (scale < 1.0) {
                int targetW = Math.max(1, (int) Math.round(w * scale)), targetH = Math.max(1, (int) Math.round(h * scale));
                BufferedImage step = toArgb(source);
                int curW = w, curH = h;
                while (curW / 2 >= targetW && curH / 2 >= targetH) {
                    step = resize(step, curW / 2, curH / 2);
                    curW /= 2;
                    curH /= 2;
                }
                out = resize(step, targetW, targetH);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return ImageIO.write(out, "png", bytes) ? bytes.toByteArray() : data;
        } catch (Throwable e) {
            return data;
        }
    }

    private static BufferedImage toArgb(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) return source;
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        try { g.drawImage(source, 0, 0, null); } finally { g.dispose(); }
        return copy;
    }

    private static BufferedImage resize(BufferedImage source, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    public static Image fromBytes(byte[] data, int pixels) {
        if (data == null || data.length == 0) return null;
        Image image = new Image(new ByteArrayInputStream(data), pixels, pixels, true, true);
        return image.isError() ? null : image;
    }
}
