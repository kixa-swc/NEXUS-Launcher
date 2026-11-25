package com.launcher.ui;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import java.util.Locale;

public final class IconBox extends StackPane {
    private final ImageView view = new ImageView();
    private final Label letter = new Label();
    private final int width;
    private final int height;
    private Object token;

    public IconBox(int size) { this(size, size); }

    public IconBox(int width, int height) {
        this.width = width;
        this.height = height;
        getStyleClass().add("icon-box");
        setMinSize(width, height);
        setPrefSize(width, height);
        setMaxSize(width, height);
        setAlignment(Pos.CENTER);
        letter.getStyleClass().add("icon-box-letter");
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(width == height);
        view.setSmooth(true);
        view.setVisible(false);
        Rectangle clip = new Rectangle(width, height);
        double arc = Math.min(width, height) * (width == height ? 0.34 : 0.22);
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        setClip(clip);
        getChildren().addAll(letter, view);
    }

    public void reset(String name, Object key) {
        token = key;
        view.setImage(null);
        view.setViewport(null);
        view.setVisible(false);
        String trimmed = name == null ? "" : name.trim();
        letter.setText(trimmed.isEmpty() ? "?" : trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT));
        letter.setVisible(true);
    }

    public void setImage(Image image, Object key) {
        if (image == null || token != key) return;
        if (width != height && image.getWidth() > 0 && image.getHeight() > 0) {
            double target = (double) width / height;
            double iw = image.getWidth(), ih = image.getHeight();
            double vw = iw, vh = ih;
            if (iw / ih > target) vw = ih * target; else vh = iw / target;
            view.setViewport(new Rectangle2D((iw - vw) / 2, (ih - vh) / 2, vw, vh));
        } else {
            view.setViewport(null);
        }
        view.setImage(image);
        view.setVisible(true);
        letter.setVisible(false);
    }

    public int iconSize() { return width; }
}
