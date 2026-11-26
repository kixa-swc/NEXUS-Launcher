package com.launcher.core;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class TrayManager {
    private final Runnable onOpen;
    private final Runnable onPlay;
    private final Runnable onExit;
    private final Supplier<List<RecentLaunches.Entry>> recent;
    private final Consumer<RecentLaunches.Entry> onPlayRecent;
    private TrayIcon icon;
    private PopupMenu menu;
    private volatile String lastError = "";
    private boolean retrying;
    private volatile long lastOpen;

    public TrayManager(Runnable onOpen, Runnable onPlay, Supplier<List<RecentLaunches.Entry>> recent,
                       Consumer<RecentLaunches.Entry> onPlayRecent, Runnable onExit) {
        this.onOpen = onOpen;
        this.onPlay = onPlay;
        this.recent = recent;
        this.onPlayRecent = onPlayRecent;
        this.onExit = onExit;
    }

    private void fillMenu(PopupMenu target) {
        target.removeAll();
        MenuItem open = new MenuItem("Открыть NEXUS");
        try {
            Font base = open.getFont();
            open.setFont(base != null ? base.deriveFont(Font.BOLD) : new Font(Font.DIALOG, Font.BOLD, 12));
        } catch (Throwable ignored) { }
        open.addActionListener(e -> onOpen.run());
        MenuItem play = new MenuItem("Играть");
        play.addActionListener(e -> onPlay.run());
        MenuItem exit = new MenuItem("Закрыть полностью");
        exit.addActionListener(e -> onExit.run());
        target.add(open);
        target.add(play);
        List<RecentLaunches.Entry> last = recent.get();
        if (last != null && !last.isEmpty()) {
            target.addSeparator();
            MenuItem header = new MenuItem("Последние запуски");
            header.setEnabled(false);
            target.add(header);
            for (RecentLaunches.Entry entry : last) {
                MenuItem item = new MenuItem(entry.label());
                item.addActionListener(e -> onPlayRecent.accept(entry));
                target.add(item);
            }
        }
        target.addSeparator();
        target.add(exit);
    }

    public synchronized void refreshMenu() {
        if (icon == null || menu == null) return;
        try {
            fillMenu(menu);
        } catch (Throwable ignored) { }
    }

    public static boolean isSupported() {
        try {
            return SystemTray.isSupported();
        } catch (Throwable t) {
            return false;
        }
    }

    public synchronized boolean install(String balloonTitle, String balloonText) {
        if (icon != null) return true;
        if (!isSupported()) {
            lastError = "SystemTray не поддерживается в этой системе";
            logFailure("Системный трей недоступен", new IllegalStateException(lastError));
            return false;
        }
        final boolean[] ok = {false};
        try {
            if (EventQueue.isDispatchThread()) ok[0] = addIcon(balloonTitle, balloonText);
            else EventQueue.invokeAndWait(() -> ok[0] = addIcon(balloonTitle, balloonText));
        } catch (Exception e) {
            lastError = e.toString();
            logFailure("Не удалось добавить значок в трей", e);
            icon = null;
            menu = null;
            return false;
        }
        return ok[0];
    }

    private boolean addIcon(String balloonTitle, String balloonText) {
        try {
            PopupMenu popup = new PopupMenu();
            fillMenu(popup);

            TrayIcon created = new TrayIcon(buildIcon(trayPixels()), "NEXUS Launcher", popup);
            created.setImageAutoSize(true);
            created.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getButton() != MouseEvent.BUTTON1) return;
                    long now = System.currentTimeMillis();
                    if (now - lastOpen < 400) return;
                    lastOpen = now;
                    onOpen.run();
                }
            });
            SystemTray.getSystemTray().add(created);
            icon = created;
            menu = popup;
            lastError = "";
            logFailure("Значок в трее добавлен (размер " + created.getSize().width + " px)", null);
            if (balloonText != null) created.displayMessage(balloonTitle, balloonText, TrayIcon.MessageType.INFO);
            return true;
        } catch (AWTException | RuntimeException | Error e) {
            lastError = e.toString();
            logFailure("Не удалось добавить значок в трей", e);
            icon = null;
            menu = null;
            return false;
        }
    }

    public boolean installWithRetry(String balloonTitle, String balloonText) {
        if (install(balloonTitle, balloonText)) return true;
        if (!isSupported()) return false;
        synchronized (this) {
            if (retrying) return false;
            retrying = true;
        }
        Thread retry = new Thread(() -> {
            try {
                for (long delay : new long[]{3000, 8000, 20000, 45000}) {
                    Thread.sleep(delay);
                    if (install(balloonTitle, null)) return;
                }
            } catch (InterruptedException ignored) {
            } finally {
                synchronized (this) { retrying = false; }
            }
        }, "nexus-tray-retry");
        retry.setDaemon(true);
        retry.start();
        return false;
    }

    public String lastError() {
        return lastError;
    }

    private static void logFailure(String message, Throwable error) {
        if (error != null) System.err.println(message + ": " + error);
    }

    private static int trayPixels() {
        try {
            Dimension d = SystemTray.getSystemTray().getTrayIconSize();
            return Math.max(16, Math.min(64, Math.max(d.width, d.height)));
        } catch (Throwable t) {
            return 32;
        }
    }

    public synchronized boolean isInstalled() {
        return icon != null;
    }

    public synchronized void showBalloon(String title, String text) {
        if (icon == null || text == null) return;
        try {
            icon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        } catch (Throwable ignored) { }
    }

    public synchronized void setTooltip(String text) {
        if (icon != null) icon.setToolTip(text);
    }

    public synchronized void remove() {
        if (icon == null) return;
        try {
            SystemTray.getSystemTray().remove(icon);
        } catch (Throwable ignored) { }
        icon = null;
        menu = null;
    }

    private static BufferedImage buildIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(new Color(0x1b1b1d));
            int arc = Math.max(4, Math.round(size * 0.28f));
            g.fillRoundRect(0, 0, size, size, arc, arc);
            try (InputStream in = TrayManager.class.getResourceAsStream("/images/nexus-logo-light.png")) {
                if (in != null) {
                    BufferedImage logo = ImageIO.read(in);
                    if (logo != null) {
                        int pad = Math.max(2, Math.round(size * 0.14f)), box = size - pad * 2;
                        double scale = Math.min(box / (double) logo.getWidth(), box / (double) logo.getHeight());
                        int w = (int) Math.round(logo.getWidth() * scale), h = (int) Math.round(logo.getHeight() * scale);
                        g.drawImage(logo, (size - w) / 2, (size - h) / 2, w, h, null);
                    }
                }
            } catch (Exception ignored) { }
        } finally {
            g.dispose();
        }
        return img;
    }
}
