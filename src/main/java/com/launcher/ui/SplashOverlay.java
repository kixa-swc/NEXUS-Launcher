package com.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class SplashOverlay extends StackPane {
    private static final double LOGO_WIDTH = 190;
    private static final double BURST_AT = 3600;
    private static final double TOTAL = 5000;

    private static final Color ACCENT = Color.web("#fff200");
    private static final Color[] PALETTE = {
            Color.web("#fff200"), Color.web("#ffffff"), Color.web("#ffc400"), Color.web("#ffe85c"),
            Color.web("#59d2ff"), Color.web("#ff5fa2"), Color.web("#ffffff")
    };

    private static final Interpolator EXPO_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);
    private static final Interpolator SOFT = Interpolator.SPLINE(0.25, 0.1, 0.25, 1);
    private static final Interpolator CAMERA_IN = Interpolator.SPLINE(0.55, 0.0, 0.85, 0.35);

    private record Rain(double pos, double width, double length, double delay, double duration, Color color) { }
    private record Bar(double pos, double width, Color color) { }

    private final Node reveal;
    private final Pane host;
    private final Timeline timeline = new Timeline();
    private final DoubleProperty clock = new SimpleDoubleProperty(0);
    private final Canvas canvas = new Canvas();
    private final List<Rain> rain = new ArrayList<>();
    private final List<Bar> bars = new ArrayList<>();
    private final Rectangle shape = new Rectangle();
    private boolean done;

    private SplashOverlay(Pane host, Node reveal, Image logoImage, Runnable onFinished) {
        this.host = host;
        this.reveal = reveal;
        getStyleClass().add("splash-overlay");
        setAlignment(Pos.CENTER);

        shape.widthProperty().bind(widthProperty());
        shape.heightProperty().bind(heightProperty());
        shape.setArcWidth(58);
        shape.setArcHeight(58);
        setClip(shape);
        sceneProperty().addListener((o, a, scene) -> {
            if (scene == null) return;
            scene.windowProperty().addListener((o2, a2, w) -> hookWindow(w));
            hookWindow(scene.getWindow());
        });

        Random rnd = new Random(11);
        for (int i = 0; i < 150; i++) {
            double pos = rnd.nextDouble() < 0.35
                    ? Math.max(0.02, Math.min(0.98, 0.5 + rnd.nextGaussian() * 0.10))
                    : rnd.nextDouble();
            rain.add(new Rain(pos, 1 + rnd.nextDouble() * 2.6, 110 + rnd.nextDouble() * 260,
                    rnd.nextDouble() * 520, 650 + rnd.nextDouble() * 450, PALETTE[rnd.nextInt(PALETTE.length)]));
        }
        int barCount = 76;
        for (int i = 0; i < barCount; i++) {
            double pos = (i + 0.5) / barCount + (rnd.nextDouble() - 0.5) * 0.01;
            bars.add(new Bar(pos, 2 + rnd.nextDouble() * 8, PALETTE[rnd.nextInt(PALETTE.length)]));
        }
        canvas.setMouseTransparent(true);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.widthProperty().addListener((o, a, b) -> draw(clock.get()));
        canvas.heightProperty().addListener((o, a, b) -> draw(clock.get()));
        clock.addListener((o, a, b) -> draw(b.doubleValue()));

        Region glow = new Region();
        glow.setPrefSize(600, 600);
        glow.setMinSize(600, 600);
        glow.setMaxSize(600, 600);
        glow.setMouseTransparent(true);
        glow.setStyle("-fx-background-color:radial-gradient(radial,rgba(255,255,255,0.16) 0%,rgba(255,255,255,0.05) 38%,transparent 70%);"
                + "-fx-background-radius:300;");
        glow.setOpacity(0);

        ImageView logo = new ImageView(logoImage);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        logo.setFitWidth(LOGO_WIDTH);
        double logoHeight = LOGO_WIDTH * logoImage.getHeight() / logoImage.getWidth();

        Rectangle left = new Rectangle(0, 0, LOGO_WIDTH * 0.375, 0);
        Rectangle mid = new Rectangle(LOGO_WIDTH * 0.37, 0, LOGO_WIDTH * 0.27, 0);
        Rectangle right = new Rectangle(LOGO_WIDTH * 0.63, logoHeight, LOGO_WIDTH * 0.38, 0);
        logo.setClip(new Group(left, mid, right));

        Rectangle sweepBar = new Rectangle(56, logoHeight * 2.4);
        sweepBar.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(1, 1, 1, 0)),
                new Stop(0.5, Color.color(1, 1, 1, 0.9)),
                new Stop(1, Color.color(1, 1, 1, 0))));
        sweepBar.setRotate(18);
        sweepBar.setLayoutY(-logoHeight * 0.7);
        sweepBar.setTranslateX(-130);
        Pane sweep = new Pane(sweepBar);
        sweep.setPrefSize(LOGO_WIDTH, logoHeight);
        sweep.setMinSize(LOGO_WIDTH, logoHeight);
        sweep.setMaxSize(LOGO_WIDTH, logoHeight);
        sweep.setMouseTransparent(true);
        ImageView mask = new ImageView(logoImage);
        mask.setPreserveRatio(true);
        mask.setSmooth(true);
        mask.setFitWidth(LOGO_WIDTH);
        sweep.setClip(mask);

        StackPane logoBox = new StackPane(logo, sweep);
        logoBox.setMaxSize(LOGO_WIDTH, logoHeight);
        DropShadow bloom = new DropShadow(0, ACCENT.deriveColor(0, 1, 1, 0.8));
        bloom.setSpread(0.05);
        logoBox.setEffect(bloom);
        logoBox.setScaleX(1.16);
        logoBox.setScaleY(1.16);

        VBox content = new VBox(logoBox);
        content.setAlignment(Pos.CENTER);
        content.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        getChildren().addAll(glow, canvas, content);

        reveal.setOpacity(0);
        reveal.setScaleX(0.985);
        reveal.setScaleY(0.985);
        reveal.setTranslateY(10);

        Interpolator both = Interpolator.EASE_BOTH;
        timeline.getKeyFrames().addAll(
                new KeyFrame(Duration.ZERO, new KeyValue(clock, 0.0)),
                new KeyFrame(Duration.millis(TOTAL), new KeyValue(clock, TOTAL, Interpolator.LINEAR)),

                new KeyFrame(Duration.millis(150), new KeyValue(left.heightProperty(), 0.0)),
                new KeyFrame(Duration.millis(680), new KeyValue(left.heightProperty(), logoHeight, both)),
                new KeyFrame(Duration.millis(300),
                        new KeyValue(right.heightProperty(), 0.0), new KeyValue(right.yProperty(), logoHeight)),
                new KeyFrame(Duration.millis(830),
                        new KeyValue(right.heightProperty(), logoHeight, both), new KeyValue(right.yProperty(), 0.0, both)),
                new KeyFrame(Duration.millis(520), new KeyValue(mid.heightProperty(), 0.0)),
                new KeyFrame(Duration.millis(1080), new KeyValue(mid.heightProperty(), logoHeight, both)),

                new KeyFrame(Duration.ZERO,
                        new KeyValue(logoBox.scaleXProperty(), 1.16), new KeyValue(logoBox.scaleYProperty(), 1.16),
                        new KeyValue(bloom.radiusProperty(), 0.0), new KeyValue(glow.opacityProperty(), 0.0)),
                new KeyFrame(Duration.millis(1400),
                        new KeyValue(logoBox.scaleXProperty(), 1.0, EXPO_OUT), new KeyValue(logoBox.scaleYProperty(), 1.0, EXPO_OUT)),
                new KeyFrame(Duration.millis(1100), new KeyValue(bloom.radiusProperty(), 60.0, SOFT)),
                new KeyFrame(Duration.millis(1600), new KeyValue(bloom.radiusProperty(), 30.0, SOFT)),
                new KeyFrame(Duration.millis(1300), new KeyValue(glow.opacityProperty(), 1.0, SOFT)),

                new KeyFrame(Duration.millis(1150), new KeyValue(sweepBar.translateXProperty(), -130.0)),
                new KeyFrame(Duration.millis(1950), new KeyValue(sweepBar.translateXProperty(), LOGO_WIDTH + 100, both)),

                new KeyFrame(Duration.millis(BURST_AT - 50),
                        new KeyValue(logoBox.opacityProperty(), 1.0),
                        new KeyValue(logoBox.scaleXProperty(), 1.0), new KeyValue(logoBox.scaleYProperty(), 1.0),
                        new KeyValue(glow.opacityProperty(), 1.0), new KeyValue(bloom.radiusProperty(), 30.0)),
                new KeyFrame(Duration.millis(BURST_AT + 300), new KeyValue(bloom.radiusProperty(), 95.0, SOFT)),
                new KeyFrame(Duration.millis(BURST_AT + 900),
                        new KeyValue(logoBox.scaleXProperty(), 2.9, CAMERA_IN), new KeyValue(logoBox.scaleYProperty(), 2.9, CAMERA_IN),
                        new KeyValue(logoBox.opacityProperty(), 0.0, SOFT), new KeyValue(glow.opacityProperty(), 0.0, SOFT)),

                new KeyFrame(Duration.millis(BURST_AT + 450),
                        new KeyValue(opacityProperty(), 1.0), new KeyValue(reveal.opacityProperty(), 0.0),
                        new KeyValue(reveal.scaleXProperty(), 0.985), new KeyValue(reveal.scaleYProperty(), 0.985),
                        new KeyValue(reveal.translateYProperty(), 10.0)),
                new KeyFrame(Duration.millis(TOTAL),
                        new KeyValue(opacityProperty(), 0.0, SOFT), new KeyValue(reveal.opacityProperty(), 1.0, SOFT),
                        new KeyValue(reveal.scaleXProperty(), 1.0, EXPO_OUT), new KeyValue(reveal.scaleYProperty(), 1.0, EXPO_OUT),
                        new KeyValue(reveal.translateYProperty(), 0.0, EXPO_OUT)));

        timeline.setOnFinished(e -> finish(onFinished));
        setOnMouseClicked(e -> {
            if (timeline.getCurrentTime().toMillis() < BURST_AT - 150) timeline.jumpTo(Duration.millis(BURST_AT - 150));
        });
    }

    private void hookWindow(Window window) {
        if (!(window instanceof Stage stage)) return;
        Runnable update = () -> {
            double arc = stage.isMaximized() || stage.isFullScreen() ? 0 : 58;
            shape.setArcWidth(arc);
            shape.setArcHeight(arc);
        };
        stage.maximizedProperty().addListener((o, a, b) -> update.run());
        stage.fullScreenProperty().addListener((o, a, b) -> update.run());
        update.run();
    }

    private void draw(double t) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        if (w < 4 || h < 4) return;
        double cx = w / 2, cy = h / 2;
        g.setGlobalBlendMode(BlendMode.ADD);

        if (t < 1700) {
            for (Rain r : rain) {
                double p = (t - r.delay()) / r.duration();
                if (p <= 0 || p >= 1) continue;
                double head = (h + r.length()) * p;
                double alpha = Math.sin(Math.PI * p) * 0.9;
                g.setFill(new LinearGradient(0, head - r.length(), 0, head, false, CycleMethod.NO_CYCLE,
                        new Stop(0, r.color().deriveColor(0, 1, 1, 0)),
                        new Stop(1, r.color().deriveColor(0, 1, 1, alpha))));
                g.fillRect(r.pos() * w - r.width() / 2, head - r.length(), r.width(), r.length());
            }
        }

        if (t > BURST_AT - 50) {
            for (Bar b : bars) {
                double x = b.pos() * w;
                double dist = Math.abs(x - cx) / (w / 2);
                double p = (t - BURST_AT - dist * 380) / 720;
                if (p <= 0 || p >= 1) continue;
                double half = h * 0.62 * easeOutExpo(p);
                double alpha = Math.pow(1 - p, 1.3) * 0.9;
                g.setFill(new LinearGradient(0, cy - half, 0, cy + half, false, CycleMethod.NO_CYCLE,
                        new Stop(0, b.color().deriveColor(0, 1, 1, 0)),
                        new Stop(0.5, b.color().deriveColor(0, 1, 1, alpha)),
                        new Stop(1, b.color().deriveColor(0, 1, 1, 0))));
                g.fillRect(x - b.width() / 2, cy - half, b.width(), half * 2);
            }
        }
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
    }

    private static double easeOutCubic(double p) { double q = 1 - p; return 1 - q * q * q; }
    private static double easeOutExpo(double p) { return p >= 1 ? 1 : 1 - Math.pow(2, -10 * p); }

    private void finish(Runnable onFinished) {
        if (done) return;
        done = true;
        host.getChildren().remove(this);
        reveal.setOpacity(1);
        reveal.setScaleX(1);
        reveal.setScaleY(1);
        reveal.setTranslateY(0);
        if (onFinished != null) onFinished.run();
    }

    public static SplashOverlay attach(Pane host, Node reveal, Runnable onFinished) {
        Image logo = loadLogo();
        if (logo == null) {
            if (onFinished != null) onFinished.run();
            return null;
        }
        SplashOverlay overlay = new SplashOverlay(host, reveal, logo, onFinished);
        host.getChildren().add(overlay);
        return overlay;
    }

    public void play() {
        timeline.playFromStart();
    }

    private static Image loadLogo() {
        for (String path : new String[]{"/images/nexus-logo-light.png", "/images/nexus-logo.png"}) {
            try (InputStream in = SplashOverlay.class.getResourceAsStream(path)) {
                if (in != null) return new Image(in);
            } catch (Exception ignored) { }
        }
        return null;
    }
}
