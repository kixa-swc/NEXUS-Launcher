package com.launcher;

import com.launcher.controller.MainController;
import com.launcher.core.DiscordPresence;
import com.launcher.core.RecentLaunches;
import com.launcher.core.SettingsManager;
import com.launcher.core.SingleInstance;
import com.launcher.core.TrayManager;
import com.launcher.ui.SplashOverlay;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.Pane;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.InputStream;
import java.util.Objects;

public class App extends Application {
    public static final int MIN_WIDTH = 1020;
    public static final int MIN_HEIGHT = 640;

    private static volatile boolean started;
    private TrayManager tray;
    private boolean trayHintShown;
    private boolean fallbackMinimize;

    @Override
    public void start(Stage stage) {
        try {
            startImpl(stage);
        } catch (Throwable t) {
            fatal("Не удалось запустить окно лаунчера", t);
        }
    }

    private void startImpl(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getResource("/fxml/main.fxml")));
        Parent root = loader.load();
        MainController controller = loader.getController();
        Platform.setImplicitExit(false);
        int width = readDimension("launcher_window_width", 1240, MIN_WIDTH);
        int height = readDimension("launcher_window_height", 720, MIN_HEIGHT);
        boolean fullscreen = Boolean.parseBoolean(SettingsManager.getSetting("launcher_fullscreen", "false"));

        Scene scene = new Scene(root, width, height);
        scene.setFill(Color.TRANSPARENT);

        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setTitle("NEXUS Launcher — Minecraft Java Edition");
        try (InputStream icon = getClass().getResourceAsStream("/images/nexus-logo.png")) {
            if (icon != null) stage.getIcons().add(new Image(icon));
        } catch (Exception ignored) { }
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setScene(scene);
        stage.setFullScreenExitHint("Нажмите ESC, чтобы выйти из полноэкранного режима");
        stage.setFullScreen(fullscreen);

        tray = new TrayManager(
                () -> Platform.runLater(() -> restoreFromTray(stage)),
                () -> Platform.runLater(controller::launchFromTray),
                RecentLaunches::load,
                entry -> Platform.runLater(() -> controller.launchRecent(entry)),
                () -> Platform.runLater(() -> exitApp(stage)));
        controller.setRecentChangedHandler(tray::refreshMenu);
        controller.setCloseHandlers(() -> hideToTray(stage), () -> exitApp(stage));
        controller.setTrayHandlers(() -> restoreFromTray(stage), (title, text) -> tray.showBalloon(title, text),
                () -> stage.isShowing() && !stage.isIconified());
        stage.setOnCloseRequest(event -> {
            event.consume();
            saveWindowState(stage);
            controller.requestClose();
        });
        stage.iconifiedProperty().addListener((obs, was, now) -> {
            if (now && !fallbackMinimize && MainController.minimizeToTray()) Platform.runLater(() -> hideToTray(stage));
        });

        final boolean startHidden = getParameters().getRaw().contains("--autostart") && TrayManager.isSupported()
                && Boolean.parseBoolean(SettingsManager.getSetting("autostart_hidden", "false"));
        SingleInstance.listen(() -> Platform.runLater(() -> restoreFromTray(stage)));

        SplashOverlay splash = null;
        if (!startHidden && Boolean.parseBoolean(SettingsManager.getSetting("splash_intro", "true"))
                && root.lookup("#frame") instanceof Pane frame && root.lookup("#appContent") instanceof Node content) {
            splash = SplashOverlay.attach(frame, content, null);
        }
        if (!startHidden) stage.show();
        started = true;
        DiscordPresence.start();
        if (splash != null) splash.play();
        Thread installer = new Thread(() -> tray.installWithRetry("NEXUS Launcher", null), "nexus-tray-install");
        installer.setDaemon(true);
        installer.start();
    }

    @Override
    public void stop() {
        DiscordPresence.stop();
        if (tray != null) tray.remove();
    }

    private void hideToTray(Stage stage) {
        if (!stage.isShowing()) return;
        saveWindowState(stage);
        boolean inTray = tray.install("NEXUS Launcher", null);
        if (inTray && !trayHintShown) {
            tray.showBalloon("NEXUS Launcher", "Лаунчер свёрнут в трей. Нажмите на значок, чтобы открыть его снова.");
            trayHintShown = true;
        }
        if (inTray) {
            stage.hide();
        } else {
            fallbackMinimize = true;
            stage.setIconified(true);
            fallbackMinimize = false;
        }
    }

    private void restoreFromTray(Stage stage) {
        if (!stage.isShowing()) stage.show();
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
    }

    private void exitApp(Stage stage) {
        saveWindowState(stage);
        tray.remove();
        Platform.exit();
    }

    private static void saveWindowState(Stage stage) {
        if (!stage.isFullScreen() && !stage.isMaximized() && !stage.isIconified()) {
            SettingsManager.saveSetting("launcher_window_width", Integer.toString((int) Math.round(stage.getWidth())));
            SettingsManager.saveSetting("launcher_window_height", Integer.toString((int) Math.round(stage.getHeight())));
        }
        SettingsManager.saveSetting("launcher_fullscreen", Boolean.toString(stage.isFullScreen()));
    }

    private int readDimension(String key, int fallback, int minimum) {
        try {
            return Math.max(minimum, Math.min(7680, Integer.parseInt(SettingsManager.getSetting(key, Integer.toString(fallback)))));
        } catch (Exception ignored) {
            return Math.max(minimum, fallback);
        }
    }

    static void fatal(String title, Throwable t) {
        java.io.File log = new java.io.File(System.getProperty("user.home"), ".nexus/startup-error.log");
        try {
            log.getParentFile().mkdirs();
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            java.nio.file.Files.writeString(log.toPath(), java.time.LocalDateTime.now() + "\n" + title + "\n"
                    + "java " + System.getProperty("java.version") + ", " + System.getProperty("os.name") + "\n\n" + sw);
        } catch (Throwable ignored) { }
        try { t.printStackTrace(); } catch (Throwable ignored) { }
        try {
            SingleInstance.release();
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(null,
                        title + ":\n" + t + "\n\nПодробности: " + log.getAbsolutePath(),
                        "NEXUS Launcher", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        } catch (Throwable ignored) { }
        Runtime.getRuntime().halt(1);
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if ("JavaFX Application Thread".equals(thread.getName()) && !started) fatal("Ошибка при запуске", error);
            else error.printStackTrace();
        });
        boolean autostart = java.util.Arrays.asList(args).contains("--autostart");
        if (!SingleInstance.acquire(!autostart)) return;
        try {
            launch(args);
        } catch (Throwable t) {
            fatal("Не удалось запустить JavaFX", t);
        }
    }
}
