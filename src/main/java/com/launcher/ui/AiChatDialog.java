package com.launcher.ui;

import com.launcher.core.AiChatService;
import com.launcher.core.HardwareInfo;
import com.launcher.core.LiveAdvisor;
import com.launcher.core.LiveKnowledge;
import com.launcher.core.LocalAdvisor;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AiChatDialog extends VBox {

    public interface Host {
        void close();
        File gameDir();
        LocalAdvisor.Context context();
    }

    private static final String[] QUICK = {
            "Какие моды поставить для FPS?", "Подбери шейдеры под мою видеокарту", "Сколько памяти выделить игре?",
            "Какую версию Minecraft выбрать?", "Подбери сборку модов", "Что улучшить в моём ПК?"};

    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexus-ai-chat");
        t.setDaemon(true);
        return t;
    });

    private final VBox messages = new VBox(10);
    private final ScrollPane scroll = new ScrollPane(messages);
    private final TextField input = new TextField();
    private final Button sendButton = new Button();
    private final Label hwShort = new Label("Определяю ваш ПК…");
    private final Label hwFull = new Label();
    private final Button hwToggle = new Button("Подробнее");

    private final List<AiChatService.Message> history = new ArrayList<>();
    private volatile CompletableFuture<HardwareInfo.Info> hardware;
    private HardwareInfo.Info hw;
    private boolean greeted;
    private boolean busy;

    public AiChatDialog(Host host) {
        this.host = host;
        getStyleClass().addAll("dialog", "small-dialog");
        setSpacing(10);
        setPrefSize(780, 620);
        setMaxSize(780, 620);
        setVisible(false);
        setManaged(false);

        Label title = new Label("ИИ-помощник");
        title.getStyleClass().add("dialog-title");
        Button clear = new Button("Очистить");
        clear.getStyleClass().add("soft-button");
        clear.setOnAction(e -> clearChat());
        Button close = new Button();
        close.getStyleClass().addAll("dialog-close-inline", "icon-button");
        close.setGraphic(Icons.icon("close", 16));
        close.setOnAction(e -> host.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(10, Icons.icon("spark", 22), title, spacer, clear, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hwTitle = new Label("Ваш ПК");
        hwTitle.getStyleClass().add("card-title");
        hwShort.getStyleClass().add("hint");
        hwShort.setWrapText(true);
        hwFull.getStyleClass().add("hint");
        hwFull.setWrapText(true);
        hwFull.setVisible(false);
        hwFull.setManaged(false);
        hwToggle.getStyleClass().add("soft-button");
        hwToggle.setOnAction(e -> {
            boolean show = !hwFull.isVisible();
            hwFull.setVisible(show);
            hwFull.setManaged(show);
            hwShort.setVisible(!show);
            hwShort.setManaged(!show);
            hwToggle.setText(show ? "Свернуть" : "Подробнее");
        });
        Button rescan = new Button("Определить заново");
        rescan.getStyleClass().add("soft-button");
        rescan.setOnAction(e -> scanHardware(true));
        Region hwSpacer = new Region();
        HBox.setHgrow(hwSpacer, Priority.ALWAYS);
        HBox hwHead = new HBox(8, hwTitle, hwSpacer, hwToggle, rescan);
        hwHead.setAlignment(Pos.CENTER_LEFT);
        VBox hwCard = new VBox(6, hwHead, hwShort, hwFull);
        hwCard.getStyleClass().add("card");
        hwCard.setPadding(new Insets(12, 16, 12, 16));

        FlowPane chips = new FlowPane(8, 8);
        for (String q : QUICK) {
            Button chip = new Button(q);
            chip.getStyleClass().add("soft-button");
            chip.setStyle("-fx-padding:6 12;-fx-font-size:12px;");
            chip.setOnAction(e -> send(q));
            chips.getChildren().add(chip);
        }

        messages.setPadding(new Insets(4, 6, 4, 2));
        scroll.getStyleClass().add("ai-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setPannable(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        messages.heightProperty().addListener((o, a, b) -> scroll.setVvalue(1.0));

        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> send(input.getText()));
        sendButton.getStyleClass().addAll("accent-button", "send-button");
        sendButton.setGraphic(Icons.icon("send", 20));
        sendButton.setTooltip(new Tooltip("Отправить"));
        sendButton.setMinSize(46, 46);
        sendButton.setPrefSize(46, 46);
        sendButton.setOnAction(e -> send(input.getText()));
        HBox inputRow = new HBox(8, input, sendButton);
        inputRow.setAlignment(Pos.CENTER);

        getChildren().addAll(header, hwCard, chips, scroll, inputRow);
    }

    public void open() {
        if (hw == null && hardware == null) scanHardware(false);
        Platform.runLater(input::requestFocus);
    }

    private void scanHardware(boolean force) {
        if (!force && hardware != null) return;
        hwShort.setText("Определяю ваш ПК…");
        CompletableFuture<HardwareInfo.Info> future = new CompletableFuture<>();
        hardware = future;
        File gameDir = host.gameDir();
        Thread t = new Thread(() -> {
            HardwareInfo.Info info = HardwareInfo.detect(gameDir);
            future.complete(info);
            Platform.runLater(() -> onHardware(info));
        }, "nexus-hw-scan");
        t.setDaemon(true);
        t.start();
    }

    private void onHardware(HardwareInfo.Info info) {
        hw = info;
        HardwareInfo.Gpu gpu = info.mainGpu();
        hwShort.setText(info.cpuName() + "  ·  " + Math.round(info.ramTotalMb() / 1024.0) + " ГБ ОЗУ  ·  "
                + (gpu == null ? "видеокарта не определена" : gpu.name()) + "  ·  " + (info.hasSsd() ? "SSD" : "только HDD"));
        hwFull.setText(info.summary());
        if (!greeted) {
            greeted = true;
            addBubble(false, "Здравствуйте! Я посмотрел ваш ПК.\n\n" + LocalAdvisor.overview(info, host.context()), "Офлайн-советчик");
        }
    }

    private void send(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || busy) return;
        input.clear();
        busy = true;
        sendButton.setDisable(true);
        addBubble(true, text, null);
        Label typing = addBubble(false, "Проверяю свежие данные…", null);
        final List<AiChatService.Message> past = new ArrayList<>(history);
        final LocalAdvisor.Context ctx = host.context();
        CompletableFuture<HardwareInfo.Info> pending = hardware;
        worker.execute(() -> {
            HardwareInfo.Info info = null;
            try { info = pending == null ? null : pending.get(40, TimeUnit.SECONDS); } catch (Exception ignored) { }
            if (info == null) info = HardwareInfo.detect(host.gameDir());
            String answer, source = null;
            String direct = LiveAdvisor.direct(text);
            if (direct != null) {
                answer = direct;
            } else {
                try {
                    answer = AiChatService.plain(AiChatService.ask(past, text, info, ctx));
                } catch (AiChatService.AiUnavailableException e) {
                    answer = fallback(text, info, ctx);
                }
            }
            final String shown = answer, from = source;
            Platform.runLater(() -> {
                setBubbleText(typing, shown, from);
                history.add(new AiChatService.Message("user", text));
                history.add(new AiChatService.Message("assistant", shown));
                busy = false;
                sendButton.setDisable(false);
                input.requestFocus();
            });
        });
    }

    private static String fallback(String text, HardwareInfo.Info info, LocalAdvisor.Context ctx) {
        try {
            if (LiveAdvisor.isVersionChoice(text)) return LiveAdvisor.versionGuide(text, info);
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("мод") || lower.contains("сборк") || lower.contains("шейдер") || lower.contains("ресурспак") || lower.contains("fps")) {
                String found = LiveKnowledge.modrinthFor(text, ctx == null ? null : ctx.mcVersion(), ctx == null ? null : ctx.loader());
                if (!found.isBlank()) return "Свежий подбор прямо с Modrinth:\n\n" + found + "\n" + LocalAdvisor.answer(text, info, ctx);
            }
        } catch (RuntimeException ignored) { }
        return LocalAdvisor.answer(text, info, ctx);
    }

    private Label addBubble(boolean user, String text, String source) {
        Label body = new Label(text);
        body.setWrapText(true);
        body.setMaxWidth(560);
        body.getStyleClass().add(user ? "ai-text-user" : "ai-text-bot");
        VBox bubble = new VBox(4, body);
        bubble.getStyleClass().addAll("ai-bubble", user ? "ai-bubble-user" : "ai-bubble-bot");
        bubble.setMaxWidth(588);
        if (source != null) {
            Label meta = new Label(source);
            meta.getStyleClass().add("ai-meta");
            meta.setWrapText(true);
            meta.setMaxWidth(560);
            bubble.getChildren().add(meta);
        }
        MenuItem copy = new MenuItem("Копировать");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(body.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        bubble.setOnContextMenuRequested(e -> {
            ContextMenu menu = new ContextMenu(copy);
            menu.show(bubble, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        HBox row = new HBox(bubble);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        messages.getChildren().add(row);
        return body;
    }

    private void setBubbleText(Label body, String text, String source) {
        body.setText(text);
        if (body.getParent() instanceof VBox bubble && source != null) {
            Label meta = new Label(source);
            meta.getStyleClass().add("ai-meta");
            meta.setWrapText(true);
            meta.setMaxWidth(560);
            bubble.getChildren().add(meta);
        }
    }

    private void clearChat() {
        if (busy) return;
        messages.getChildren().clear();
        history.clear();
        greeted = false;
        if (hw != null) onHardware(hw);
    }
}
