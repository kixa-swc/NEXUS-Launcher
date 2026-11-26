package com.launcher.ui;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.util.function.Predicate;

public final class WindowChrome {
    private static final double EDGE = 9;
    private static final int L = 1, R = 2, T = 4, B = 8, MOVE = 16;

    private WindowChrome() {}

    public static void install(Stage stage, Region root, Predicate<MouseEvent> dragZone) {
        final double[] s = new double[6];
        final int[] mode = {0};

        root.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isMaximized() || stage.isFullScreen()) { root.setCursor(null); return; }
            root.setCursor(cursor(hit(e, root)));
        });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            mode[0] = 0;
            if (e.getButton() != MouseButton.PRIMARY || stage.isFullScreen()) return;
            int m = stage.isMaximized() ? 0 : hit(e, root);
            if (m == 0 && dragZone.test(e) && !interactive(e.getTarget())) m = MOVE;
            mode[0] = m;
            s[0] = e.getScreenX(); s[1] = e.getScreenY();
            s[2] = stage.getX(); s[3] = stage.getY(); s[4] = stage.getWidth(); s[5] = stage.getHeight();
        });
        root.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            int m = mode[0];
            if (m == 0 || stage.isMaximized() || stage.isFullScreen()) return;
            double dx = e.getScreenX() - s[0], dy = e.getScreenY() - s[1];
            if (m == MOVE) { stage.setX(s[2] + dx); stage.setY(s[3] + dy); return; }
            double minW = Math.max(stage.getMinWidth(), 300), minH = Math.max(stage.getMinHeight(), 300);
            double x = s[2], y = s[3], w = s[4], h = s[5];
            if ((m & R) != 0) w = Math.max(minW, s[4] + dx);
            if ((m & B) != 0) h = Math.max(minH, s[5] + dy);
            if ((m & L) != 0) { w = Math.max(minW, s[4] - dx); x = s[2] + (s[4] - w); }
            if ((m & T) != 0) { h = Math.max(minH, s[5] - dy); y = s[3] + (s[5] - h); }
            stage.setX(x); stage.setY(y); stage.setWidth(w); stage.setHeight(h);
        });
        root.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> mode[0] = 0);
        root.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && hit(e, root) == 0
                    && dragZone.test(e) && !interactive(e.getTarget()) && !stage.isFullScreen()) {
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    private static int hit(MouseEvent e, Region root) {
        double x = e.getSceneX(), y = e.getSceneY(), w = root.getWidth(), h = root.getHeight();
        int m = 0;
        if (x < EDGE) m |= L; else if (x > w - EDGE) m |= R;
        if (y < EDGE) m |= T; else if (y > h - EDGE) m |= B;
        return m;
    }

    private static Cursor cursor(int m) {
        return switch (m) {
            case L, R -> Cursor.H_RESIZE;
            case T, B -> Cursor.V_RESIZE;
            case L | T, R | B -> Cursor.NW_RESIZE;
            case R | T, L | B -> Cursor.NE_RESIZE;
            default -> null;
        };
    }

    private static boolean interactive(Object target) {
        Node n = target instanceof Node node ? node : null;
        while (n != null) {
            if (n instanceof ButtonBase || n instanceof ComboBoxBase || n instanceof TextInputControl
                    || n instanceof Slider || n instanceof ScrollBar || n instanceof ListView || n instanceof TabPane) return true;
            n = n.getParent();
        }
        return false;
    }
}
