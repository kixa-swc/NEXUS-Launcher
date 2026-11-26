package com.launcher.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

import java.util.Map;

public final class Icons {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("gear", "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"),
            Map.entry("close", "M6.7 5.3a1 1 0 0 0-1.4 1.4L10.6 12l-5.3 5.3a1 1 0 1 0 1.4 1.4L12 13.4l5.3 5.3a1 1 0 0 0 1.4-1.4L13.4 12l5.3-5.3a1 1 0 0 0-1.4-1.4L12 10.6z"),
            Map.entry("minimize", "M6 10.8h12a1.2 1.2 0 0 1 0 2.4H6a1.2 1.2 0 0 1 0-2.4z"),
            Map.entry("person", "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v1.2c0 .44.36.8.8.8h14.4c.44 0 .8-.36.8-.8V18c0-2.66-5.33-4-8-4z"),
            Map.entry("skin", "M5 4h14a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H5a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3zm0 2a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1V7a1 1 0 0 0-1-1zM9 8.2a2.1 2.1 0 1 1 0 4.2 2.1 2.1 0 0 1 0-4.2zM5.9 16.4c.4-1.6 1.7-2.4 3.1-2.4s2.7.8 3.1 2.4zM14 9h4a.8.8 0 0 1 0 1.6h-4A.8.8 0 0 1 14 9zm0 3.2h3a.8.8 0 0 1 0 1.6h-3a.8.8 0 0 1 0-1.6z"),
            Map.entry("menu", "M5 5.2h14a1.3 1.3 0 0 1 0 2.6H5a1.3 1.3 0 0 1 0-2.6zM5 10.7h14a1.3 1.3 0 0 1 0 2.6H5a1.3 1.3 0 0 1 0-2.6zM5 16.2h14a1.3 1.3 0 0 1 0 2.6H5a1.3 1.3 0 0 1 0-2.6z"),
            Map.entry("grid", "M5 4h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm10 0h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM5 14h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1zm10 0h4a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1z"),
            Map.entry("play-circle", "M12 2.5a9.5 9.5 0 1 0 0 19 9.5 9.5 0 0 0 0-19zm0 2a7.5 7.5 0 1 1 0 15 7.5 7.5 0 0 1 0-15zM10.2 8.6v6.8c0 .4.4.6.7.4l5.3-3.4a.5.5 0 0 0 0-.8l-5.3-3.4a.5.5 0 0 0-.7.4z"),
            Map.entry("stop-circle", "M12 2.5a9.5 9.5 0 1 0 0 19 9.5 9.5 0 0 0 0-19zm0 2a7.5 7.5 0 1 1 0 15 7.5 7.5 0 0 1 0-15zM9.6 9.6h4.8v4.8H9.6z"),
            Map.entry("info", "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"),
            Map.entry("puzzle", "M20.5 11H19V7c0-1.1-.9-2-2-2h-4V3.5C13 2.12 11.88 1 10.5 1S8 2.12 8 3.5V5H4c-1.1 0-1.99.9-1.99 2v3.8H3.5c1.49 0 2.7 1.21 2.7 2.7s-1.21 2.7-2.7 2.7H2V20c0 1.1.9 2 2 2h3.8v-1.5c0-1.49 1.21-2.7 2.7-2.7 1.49 0 2.7 1.21 2.7 2.7V22H17c1.1 0 2-.9 2-2v-4h1.5c1.38 0 2.5-1.12 2.5-2.5S21.88 11 20.5 11z"),
            Map.entry("server", "M5 3.5h14a2 2 0 0 1 2 2V9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5.5a2 2 0 0 1 2-2zM6.8 6.2a1.05 1.05 0 1 0 0 2.1 1.05 1.05 0 0 0 0-2.1zM5 13.5h14a2 2 0 0 1 2 2V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3.5a2 2 0 0 1 2-2zM6.8 16.2a1.05 1.05 0 1 0 0 2.1 1.05 1.05 0 0 0 0-2.1z"),
            Map.entry("home", "M12 3.2 3 11h2.6v8.2c0 .5.4.9.9.9h3.6v-5.6h3.8v5.6h3.6c.5 0 .9-.4.9-.9V11H21z"),
            Map.entry("layers", "M12 2.5 2.5 7.5 12 12.5l9.5-5zM4.6 11.2 2.5 12.3 12 17.3l9.5-5-2.1-1.1L12 15.1zM4.6 15.9 2.5 17 12 22l9.5-5-2.1-1.1L12 19.8z"),
            Map.entry("news", "M6 3h8l5 5v11a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm7 1.5V9h4.5zM7.5 12h9v1.5h-9zm0 3.5h9V17h-9z"),
            Map.entry("play", "M8 5.6v12.8a1 1 0 0 0 1.5.9l10.2-6.4a1 1 0 0 0 0-1.7L9.5 4.7A1 1 0 0 0 8 5.6z"),
            Map.entry("stop", "M7 6.5A1.5 1.5 0 0 1 8.5 5h7A1.5 1.5 0 0 1 17 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-7A1.5 1.5 0 0 1 7 17.5z"),
            Map.entry("chevron-down", "M6.2 9.2a1 1 0 0 1 1.4 0L12 13.6l4.4-4.4a1 1 0 1 1 1.4 1.4l-5.1 5.1a1 1 0 0 1-1.4 0L6.2 10.6a1 1 0 0 1 0-1.4z"),
            Map.entry("win-min", "M7 11.4h10a.6.6 0 0 1 0 1.2H7a.6.6 0 0 1 0-1.2z"),
            Map.entry("win-max", "M8.2 7h7.6A1.2 1.2 0 0 1 17 8.2v7.6a1.2 1.2 0 0 1-1.2 1.2H8.2A1.2 1.2 0 0 1 7 15.8V8.2A1.2 1.2 0 0 1 8.2 7zM8.3 8.2h7.4v7.6H8.3z"),
            Map.entry("win-restore", "M6.5 9h8.5v8.5H6.5zM7.7 10.2v6.1h6.1v-6.1zM9 9V7h8.5v8.5H15v-1.2h1.3V8.2H10.2V9z"),
            Map.entry("win-close", "M6.576 7.424 7.424 6.576 12 11.15 16.576 6.576 17.424 7.424 12.85 12 17.424 16.576 16.576 17.424 12 12.85 7.424 17.424 6.576 16.576 11.15 12z"),
            Map.entry("pin", "M14.5 3.5 20.5 9.5l-1.4 1.4-1.1-.4-3.3 3.3.4 3.7-1.4 1.4-3.6-3.6L5 19l-1-1 4.7-4.7L5.1 9.7l1.4-1.4 3.7.4 3.3-3.3-.4-1.1z"),
            Map.entry("logo-n", "M4 4h4.3l7.4 10V4H20v16h-4.3L8.3 10v10H4z"),
            Map.entry("spark", "M10.5 3.5c.3 0 .6.2.7.5l1.4 4.1c.4 1.2 1.3 2.1 2.5 2.5l4.1 1.4c.6.2.6 1.1 0 1.4l-4.1 1.4c-1.2.4-2.1 1.3-2.5 2.5l-1.4 4.1c-.2.6-1.1.6-1.4 0l-1.4-4.1c-.4-1.2-1.3-2.1-2.5-2.5l-4.1-1.4c-.6-.2-.6-1.1 0-1.4l4.1-1.4c1.2-.4 2.1-1.3 2.5-2.5l1.4-4.1c.1-.3.4-.5.7-.5zM18.5 2.5c.2 0 .4.1.5.3l.6 1.7c.2.5.6.9 1.1 1.1l1.7.6c.4.1.4.7 0 .8l-1.7.6c-.5.2-.9.6-1.1 1.1l-.6 1.7c-.1.4-.7.4-.8 0l-.6-1.7c-.2-.5-.6-.9-1.1-1.1l-1.7-.6c-.4-.1-.4-.7 0-.8l1.7-.6c.5-.2.9-.6 1.1-1.1l.6-1.7c.1-.2.2-.3.4-.3z"),
            Map.entry("send", "M21.6 2.6 2.7 9.9c-.9.35-.87 1.6.05 1.9l5.1 1.65 2 6.1c.28.87 1.4 1.05 1.95.32l2.2-2.9 4.5 3.3c.7.5 1.7.12 1.9-.74l3.1-15.7c.2-1-.8-1.9-1.9-1.5zM9.6 12.6l8.7-5.4c.2-.1.35.15.18.3l-7.1 6.6c-.2.2-.33.45-.36.73l-.3 2.4c-.03.3-.45.34-.55.06z"),
            Map.entry("dot", "M12 8a4 4 0 100 8 4 4 0 000-8z")
    );

    private static final Map<String, String[][]> LOADERS = Map.ofEntries(
            Map.entry("fabric", new String[][]{{"M6.6 3.4h11.9c.9 0 1.4 1 .8 1.7l-1.3 1.6c-.2.3-.6.4-.9.4H10.4v3.2h5.4c.9 0 1.4 1 .8 1.7l-1.2 1.5c-.2.3-.6.4-.9.4h-4.1v5.4c0 .6-.5 1.1-1.1 1.1H7.7c-.6 0-1.1-.5-1.1-1.1z", "main"}, {"M6.6 3.4h4.2L6.6 8.2z", "soft"}, {"M8.5 10.4h.9v1.7h-.9zM8.5 13.9h.9v1.7h-.9zM8.5 17.4h.9v1.7h-.9z", "cut"}}),
            Map.entry("forge", new String[][]{{"M2.4 5.4h13.4c.5 2.7 2.5 4.2 5.8 4.5-.5 2.2-2.5 3.7-5.3 3.9-.3 1.2-.9 2-2.1 2.6l.3 1.8h2.7c.6 0 1 .4 1 1V21H5.7v-1.8c0-.6.4-1 1-1h2.7l.3-1.8c-1.2-.6-1.8-1.4-2.1-2.6-2.5-.5-4.3-3-5.2-5.6C2.6 7.6 2.5 6.5 2.4 5.4z", "main"}}),
            Map.entry("neoforge", new String[][]{{"M2.8 9.6h11.6c.4 2.2 2 3.4 4.8 3.7-.4 1.8-2 3.1-4.3 3.3-.3 1-.8 1.7-1.7 2.2l.3 1.5h2.2c.5 0 .9.4.9.9v1.3H6.3v-1.3c0-.5.4-.9.9-.9h2.2l.3-1.5c-1-.5-1.5-1.2-1.7-2.2-2-.4-3.5-2.5-4.3-4.7-.7.1-.9-.2-.9-.6z", "main"}, {"M16.2 1.6c.3 2 2.4 2.7 2.4 5 0 1.3-.8 2.3-1.9 2.8.3-.9.1-1.7-.5-2.4-.4 1-1.2 1.5-1.2 2.5 0 .5.2.9.5 1.2-1.4-.4-2.3-1.6-2.3-3 0-2.3 2.7-3.2 3-6.1z", "soft"}})
    );

    private Icons() {}

    public static Node icon(String name, double size) {
        String path = PATHS.getOrDefault(name, PATHS.get("dot"));
        return build(new String[][]{{path, "main"}}, size);
    }

    public static Node loader(String name, double size) {
        String[][] layers = LOADERS.get(name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
        return layers == null ? null : build(layers, size);
    }

    private static Node build(String[][] layers, double size) {
        Group group = new Group();
        for (String[] layer : layers) {
            SVGPath shape = new SVGPath();
            shape.setContent(layer[0]);
            shape.setFillRule(FillRule.EVEN_ODD);
            shape.getStyleClass().add("icon");
            if ("soft".equals(layer[1])) shape.getStyleClass().add("icon-soft");
            else if ("cut".equals(layer[1])) shape.getStyleClass().add("icon-cut");
            group.getChildren().add(shape);
        }
        double scale = size / 24.0;
        group.setScaleX(scale);
        group.setScaleY(scale);
        StackPane box = new StackPane(group);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }
}
