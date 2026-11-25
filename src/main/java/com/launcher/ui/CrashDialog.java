package com.launcher.ui;

import com.launcher.core.CrashAnalyzer;
import com.launcher.core.LogUploader;
import com.launcher.util.FileUtils;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class CrashDialog extends VBox {
    private final ExecutorService executor;
    private final Consumer<String> logger;

    private final Label headline = new Label();
    private final Label cause = new Label();
    private final VBox adviceBox = new VBox(6);
    private final Label adviceTitle = sectionTitle("Что сделать");
    private final VBox detailsBox = new VBox(6);
    private final Label detailsTitle = sectionTitle("Что именно не так");
    private final Label excerptTitle = sectionTitle("Из отчёта");
    private final TextArea excerpt = new TextArea();
    private final Label statusLabel = new Label();
    private final Button modsButton = new Button("Менеджер модов");
    private final Button folderButton = new Button("Открыть папку отчёта");
    private final Button copyButton = new Button("Скопировать лог");
    private final Button sendButton = new Button("Отправить лог");
    private final Button linkButton = new Button("Открыть ссылку");

    private CrashAnalyzer.Report report;
    private String uploadedUrl;
    private boolean uploading;

    public CrashDialog(ExecutorService executor, Runnable onClose, Runnable onOpenMods, Runnable onOpenLogs, Consumer<String> logger) {
        this.executor = executor;
        this.logger = logger;
        getStyleClass().addAll("dialog", "small-dialog");
        setSpacing(12);
        setPrefSize(780, 600);
        setMaxSize(780, 600);
        setVisible(false);
        setManaged(false);

        Label title = new Label("Игра завершилась с ошибкой");
        title.getStyleClass().add("dialog-title");
        Button close = new Button();
        close.getStyleClass().addAll("dialog-close-inline", "icon-button");
        close.setGraphic(Icons.icon("close", 16));
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        headline.getStyleClass().add("crash-headline");
        headline.setWrapText(true);
        cause.setWrapText(true);
        adviceBox.setFillWidth(true);
        detailsBox.setFillWidth(true);
        excerpt.setEditable(false);
        excerpt.setWrapText(false);
        excerpt.setPrefRowCount(9);
        excerpt.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 11px;");

        VBox body = new VBox(10, headline, cause, adviceTitle, adviceBox, detailsTitle, detailsBox, excerptTitle, excerpt);
        body.setPadding(new javafx.geometry.Insets(0, 10, 0, 0));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        modsButton.getStyleClass().add("soft-button");
        modsButton.setOnAction(e -> onOpenMods.run());
        folderButton.getStyleClass().add("soft-button");
        folderButton.setOnAction(e -> openFolder());
        Button logsButton = new Button("Логи");
        logsButton.getStyleClass().add("soft-button");
        logsButton.setOnAction(e -> onOpenLogs.run());
        copyButton.getStyleClass().add("soft-button");
        copyButton.setOnAction(e -> copyLog());
        sendButton.getStyleClass().add("accent-button");
        sendButton.setOnAction(e -> sendLog());
        linkButton.getStyleClass().add("soft-button");
        linkButton.setOnAction(e -> openLink());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, modsButton, folderButton, logsButton, spacer, copyButton, linkButton, sendButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label privacy = new Label("«Отправить лог» загрузит текст выше на mclo.gs и скопирует ссылку. Токены входа и имя пользователя Windows из текста убираются; "
                + "ссылку увидит каждый, кому вы её дадите.");
        privacy.setWrapText(true);
        privacy.getStyleClass().add("hint");
        statusLabel.setWrapText(true);
        statusLabel.getStyleClass().add("hint");

        getChildren().addAll(header, scroll, actions, privacy, statusLabel);
    }

    public void show(CrashAnalyzer.Report newReport) {
        this.report = newReport;
        this.uploadedUrl = null;
        this.uploading = false;
        statusLabel.setText("");
        headline.setText(newReport.headline());
        cause.setText(newReport.cause());

        adviceBox.getChildren().clear();
        for (String line : newReport.advice()) adviceBox.getChildren().add(bullet(line));
        setSectionVisible(adviceTitle, adviceBox, !newReport.advice().isEmpty());

        detailsBox.getChildren().clear();
        for (String line : newReport.details()) {
            Label label = bullet(line);
            label.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12px;");
            detailsBox.getChildren().add(label);
        }
        setSectionVisible(detailsTitle, detailsBox, !newReport.details().isEmpty());

        boolean hasExcerpt = newReport.excerpt() != null && !newReport.excerpt().isBlank();
        excerpt.setText(hasExcerpt ? newReport.excerpt() : "");
        excerptTitle.setVisible(hasExcerpt);
        excerptTitle.setManaged(hasExcerpt);
        excerpt.setVisible(hasExcerpt);
        excerpt.setManaged(hasExcerpt);

        modsButton.setVisible(newReport.modRelated());
        modsButton.setManaged(newReport.modRelated());
        boolean hasFolder = folderTarget() != null;
        folderButton.setVisible(hasFolder);
        folderButton.setManaged(hasFolder);
        folderButton.setText(newReport.reportFile() != null ? "Открыть папку отчёта" : "Открыть папку логов");
        linkButton.setVisible(false);
        linkButton.setManaged(false);
        sendButton.setDisable(false);
        sendButton.setText("Отправить лог");
    }

    private File folderTarget() {
        if (report == null) return null;
        if (report.reportFile() != null) return report.reportFile().getParentFile();
        if (report.logFile() != null) return report.logFile().getParentFile();
        return null;
    }

    private void openFolder() {
        File dir = folderTarget();
        if (dir != null) FileUtils.openDirectory(dir);
    }

    private void copyLog() {
        if (report == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(report.shareText());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Лог скопирован в буфер обмена (токены и имя пользователя убраны).");
    }

    private void sendLog() {
        if (report == null || uploading) return;
        uploading = true;
        sendButton.setDisable(true);
        sendButton.setText("Отправка…");
        statusLabel.setText("Загружаю лог на mclo.gs…");
        final CrashAnalyzer.Report current = report;
        executor.execute(() -> {
            String url = null;
            String error = null;
            try {
                url = LogUploader.upload(current.shareText());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String finalUrl = url;
            final String finalError = error;
            Platform.runLater(() -> {
                if (current != report) return;
                uploading = false;
                sendButton.setDisable(false);
                sendButton.setText("Отправить лог");
                if (finalError != null) {
                    statusLabel.setText("Не удалось отправить лог: " + finalError + " Можно скопировать его кнопкой «Скопировать лог».");
                    return;
                }
                uploadedUrl = finalUrl;
                ClipboardContent content = new ClipboardContent();
                content.putString(finalUrl);
                Clipboard.getSystemClipboard().setContent(content);
                statusLabel.setText("Лог загружен: " + finalUrl + " — ссылка скопирована в буфер обмена.");
                logger.accept("Лог вылета загружен на mclo.gs: " + finalUrl);
                linkButton.setVisible(true);
                linkButton.setManaged(true);
            });
        });
    }

    private void openLink() {
        if (uploadedUrl == null) return;
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(uploadedUrl));
        } catch (Exception e) {
            statusLabel.setText("Не удалось открыть браузер. Ссылка уже в буфере обмена: " + uploadedUrl);
        }
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label bullet(String text) {
        Label label = new Label("•  " + text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static void setSectionVisible(Label title, VBox box, boolean visible) {
        title.setVisible(visible);
        title.setManaged(visible);
        box.setVisible(visible);
        box.setManaged(visible);
    }
}
