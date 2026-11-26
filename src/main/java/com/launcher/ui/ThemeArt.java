package com.launcher.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThemeArt extends Pane {
    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexus-theme-render");
        t.setDaemon(true);
        return t;
    });

    private final ImageView view = new ImageView();
    private final PauseTransition resizeDelay = new PauseTransition(Duration.millis(160));
    private Themes.Theme theme;
    private int generation;

    public ThemeArt() {
        view.setPreserveRatio(false);
        view.setSmooth(true);
        getChildren().add(view);
        setMouseTransparent(true);
        resizeDelay.setOnFinished(e -> redraw());
    }

    public void setTheme(Themes.Theme theme) {
        this.theme = theme;
        redraw();
    }

    @Override protected void layoutChildren() {
        view.setFitWidth(getWidth());
        view.setFitHeight(getHeight());
        resizeDelay.playFromStart();
    }

    private void redraw() {
        if (theme == null || getWidth() < 8 || getHeight() < 8) return;
        final Themes.Theme t = theme;
        final int w = (int) Math.ceil(getWidth()), h = (int) Math.ceil(getHeight()), id = ++generation;
        RENDER.execute(() -> {
            BufferedImage img = ThemeRenderer.render(t, w, h);
            Platform.runLater(() -> { if (id == generation) view.setImage(toFx(img)); });
        });
    }

    public static WritableImage toFx(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return out;
    }
}
