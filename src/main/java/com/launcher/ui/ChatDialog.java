package com.launcher.ui;

import com.launcher.chat.ChatHub;
import com.launcher.chat.ChatService;
import com.launcher.chat.ChatService.ChatException;
import com.launcher.chat.ChatService.Message;
import com.launcher.chat.ChatService.Person;
import com.launcher.chat.ChatService.Request;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatDialog extends StackPane {

    public interface Host {
        void close();
        String suggestedName();
    }

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String GREEN = "#3ddc84";

    private final Host host;
    private final ChatService chat = ChatHub.service();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexus-chat-ui");
        t.setDaemon(true);
        return t;
    });

    private final VBox setupView = new VBox(12);
    private final HBox mainView = new HBox();

    private final TextField setupName = new TextField();
    private final TextField setupServer = new TextField();
    private final Label setupMessage = new Label();
    private final Button setupConnect = new Button("Подключиться");
    private final Button setupHost = new Button("Запустить сервер на этом ПК и подключиться");
    private final VBox setupServerBox = new VBox(6);

    private final Label myName = new Label();
    private final Label myId = new Label();
    private final Label statusLabel = new Label();
    private final TextField addField = new TextField();
    private final Button addButton = new Button();
    private final Label addFeedback = new Label();
    private final ToggleButton friendsTab = new ToggleButton("Друзья");
    private final ToggleButton requestsTab = new ToggleButton("Заявки");
    private final VBox listBox = new VBox(4);

    private final StackPane right = new StackPane();
    private final VBox emptyView = new VBox(10);
    private final VBox chatView = new VBox();
    private final VBox settingsView = new VBox(14);
    private final HBox chatHeader = new HBox(12);
    private final VBox messages = new VBox(6);
    private final ScrollPane messageScroll = new ScrollPane(messages);
    private final TextField input = new TextField();
    private final Button sendButton = new Button();
    private final Label counter = new Label();
    private final Label flash = new Label();

    private final TextField nameField = new TextField();
    private final Label settingsServer = new Label();
    private final Label hostStatus = new Label();
    private final Button hostToggle = new Button();
    private final Label settingsMessage = new Label();
    private final Button logoutButton = new Button("Выйти из чата на этом ПК");

    private String selected;
    private boolean showSettings;
    private boolean olderPending;
    private double anchorHeight, anchorValue, anchorViewport;
    private boolean logoutArmed;

    public ChatDialog(Host host) {
        this.host = host;
        getStyleClass().addAll("dialog");
        setPrefSize(940, 590);
        setMaxSize(940, 590);
        setVisible(false);
        setManaged(false);

        buildSetupView();
        buildMainView();
        getChildren().addAll(mainView, setupView);

        chat.addListener(new ChatService.Listener() {
            @Override public void onConnection(ChatService.Conn conn, String detail) { Platform.runLater(ChatDialog.this::refreshAll); }
            @Override public void onRoster() { Platform.runLater(ChatDialog.this::refreshAll); }
            @Override public void onMessage(Message m, boolean mine) {
                Platform.runLater(() -> {
                    String peer = mine ? m.to() : m.from();
                    if (peer.equals(selected) && !showSettings) renderMessages(mine ? Stick.FORCE : Stick.IF_NEAR);
                });
            }
            @Override public void onRead(String friendId, long upTo) {
                Platform.runLater(() -> { if (friendId.equals(selected) && !showSettings) renderMessages(Stick.KEEP); });
            }
            @Override public void onNotice(String text) { Platform.runLater(() -> flash(text, false)); }
        });

        visibleProperty().addListener((o, was, now) -> {
            if (now) onShown();
            else { chat.setActiveChat(null); chat.flush(); }
        });
        refreshAll();
    }

    public void open() {
        if (setupName.getText().isBlank()) setupName.setText(host.suggestedName() == null ? "" : host.suggestedName());
        refreshAll();
        Platform.runLater(() -> {
            if (!chat.isConfigured()) setupName.requestFocus();
            else if (selected != null) input.requestFocus();
            else addField.requestFocus();
        });
    }

    private void onShown() {
        if (chat.isConfigured()) {
            chat.start();
            worker.execute(chat::refresh);
            if (selected != null && chat.friend(selected) != null) activate(selected);
        }
        refreshAll();
    }

    private void buildSetupView() {
        setupView.setAlignment(Pos.CENTER);
        setupView.setPadding(new Insets(28, 40, 28, 40));

        Button close = closeButton();
        HBox top = new HBox(new Region(), close);
        HBox.setHgrow(top.getChildren().get(0), Priority.ALWAYS);

        StackPane iconBadge = new StackPane(Icons.icon("chat", 34));
        iconBadge.getStyleClass().add("chat-hero-icon");
        iconBadge.setMinSize(76, 76);
        iconBadge.setMaxSize(76, 76);
        Label title = new Label("Чаты NEXUS");
        title.getStyleClass().add("dialog-title");
        Label sub = new Label("Добавляйте друзей по ID лаунчера и переписывайтесь прямо здесь — не выходя из игры.");
        sub.getStyleClass().add("hint");
        sub.setWrapText(true);
        sub.setTextAlignment(TextAlignment.CENTER);
        sub.setMaxWidth(480);
        if (!ChatService.DEFAULT_SERVER.isEmpty()) sub.setText("Введите ник — и вы получите свой код. Отправьте его другу, добавьте друга по его коду и переписывайтесь прямо в лаунчере. Серверы и настройки не нужны.");

        Label nameCaption = new Label(ChatService.DEFAULT_SERVER.isEmpty() ? "Ваше имя в чате" : "Ваш ник");
        nameCaption.getStyleClass().add("chat-caption");
        setupName.setPromptText("Например, Steve");
        if (!ChatService.DEFAULT_SERVER.isEmpty()) setupConnect.setText("Получить код и войти в чат");
        setupName.setMaxWidth(420);
        setupName.setOnAction(e -> connect());
        VBox nameBox = new VBox(6, nameCaption, setupName);
        nameBox.setMaxWidth(420);

        Label serverCaption = new Label("Адрес сервера чатов");
        serverCaption.getStyleClass().add("chat-caption");
        setupServer.setPromptText("http://адрес:8765");
        setupServer.setMaxWidth(420);
        setupServer.setOnAction(e -> connect());
        setupServerBox.getChildren().addAll(serverCaption, setupServer);
        setupServerBox.setMaxWidth(420);

        setupConnect.getStyleClass().add("accent-button");
        setupConnect.setMaxWidth(420);
        setupConnect.setPrefHeight(44);
        setupConnect.setOnAction(e -> connect());

        Label or = new Label("— или —");
        or.getStyleClass().add("hint");
        setupHost.getStyleClass().add("soft-button");
        setupHost.setMaxWidth(420);
        setupHost.setPrefHeight(40);
        setupHost.setOnAction(e -> connectLocal());
        Label hostHint = new Label("Сервер запустится внутри лаунчера на порту " + ChatHub.LOCAL_PORT
                + ". Друзья подключаются по вашему адресу (нужен проброс порта или VPN/ngrok). Для постоянной работы лучше поставить сервер на VPS — см. папку chat-server.");
        hostHint.getStyleClass().add("hint");
        hostHint.setWrapText(true);
        hostHint.setMaxWidth(420);
        hostHint.setTextAlignment(TextAlignment.CENTER);
        VBox hostBox = new VBox(8, or, setupHost, hostHint);
        hostBox.setAlignment(Pos.CENTER);
        hostBox.setMaxWidth(420);
        hostBox.setManaged(ChatService.DEFAULT_SERVER.isEmpty());
        hostBox.setVisible(ChatService.DEFAULT_SERVER.isEmpty());

        setupMessage.setWrapText(true);
        setupMessage.setMaxWidth(420);
        setupMessage.setTextAlignment(TextAlignment.CENTER);
        setupMessage.setManaged(false);
        setupMessage.setVisible(false);

        if (!ChatService.DEFAULT_SERVER.isEmpty()) {
            setupServerBox.setManaged(false);
            setupServerBox.setVisible(false);
        }

        VBox center = new VBox(14, iconBadge, title, sub, nameBox, setupServerBox, setupMessage, setupConnect, hostBox);
        center.setAlignment(Pos.CENTER);
        VBox.setVgrow(center, Priority.ALWAYS);
        setupView.getChildren().addAll(top, center);
    }

    private void buildMainView() {
        Label title = new Label("Чаты");
        title.getStyleClass().add("dialog-title");
        Button gear = iconButton("gear", "Профиль и настройки чата");
        gear.setOnAction(e -> { showSettings = true; refreshAll(); });
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox head = new HBox(8, Icons.icon("chat", 22), title, sp, gear);
        head.setAlignment(Pos.CENTER_LEFT);

        myName.getStyleClass().add("chat-name");
        myId.getStyleClass().add("chat-id");
        Button copy = iconButton("copy", "Скопировать мой ID");
        copy.setOnAction(e -> copyMyId());
        HBox idRow = new HBox(4, myId, copy);
        idRow.setAlignment(Pos.CENTER_LEFT);
        VBox who = new VBox(1, myName, idRow);
        statusLabel.getStyleClass().add("chat-status");
        statusLabel.setWrapText(true);
        VBox profile = new VBox(6, who, statusLabel);
        profile.getStyleClass().add("chat-profile");

        addField.setPromptText("ID друга: NX-XXXX-XXXX");
        addField.setOnAction(e -> addFriend());
        HBox.setHgrow(addField, Priority.ALWAYS);
        addButton.getStyleClass().addAll("accent-button", "send-button");
        addButton.setGraphic(Icons.icon("user-add", 20));
        addButton.setTooltip(new Tooltip("Отправить заявку в друзья"));
        addButton.setMinSize(42, 42);
        addButton.setPrefSize(42, 42);
        addButton.setOnAction(e -> addFriend());
        HBox addRow = new HBox(8, addField, addButton);
        addRow.setAlignment(Pos.CENTER);
        addFeedback.setWrapText(true);
        addFeedback.setManaged(false);
        addFeedback.setVisible(false);

        ToggleGroup tabs = new ToggleGroup();
        friendsTab.setToggleGroup(tabs);
        requestsTab.setToggleGroup(tabs);
        friendsTab.getStyleClass().add("chat-tab");
        requestsTab.getStyleClass().add("chat-tab");
        friendsTab.setSelected(true);
        tabs.selectedToggleProperty().addListener((o, a, b) -> { if (b == null) friendsTab.setSelected(true); else renderLists(); });
        HBox tabRow = new HBox(6, friendsTab, requestsTab);

        ScrollPane listScroll = new ScrollPane(listBox);
        listScroll.getStyleClass().add("ai-scroll");
        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(listScroll, Priority.ALWAYS);

        VBox left = new VBox(12, head, profile, addRow, addFeedback, tabRow, listScroll);
        left.setPadding(new Insets(20, 16, 18, 20));
        left.setMinWidth(318);
        left.setPrefWidth(318);
        left.setMaxWidth(318);

        Region divider = new Region();
        divider.getStyleClass().add("chat-divider");

        buildEmptyView();
        buildChatView();
        buildSettingsView();
        right.getChildren().addAll(emptyView, chatView, settingsView);
        HBox.setHgrow(right, Priority.ALWAYS);

        Button close = closeButton();
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        StackPane.setMargin(close, new Insets(14, 14, 0, 0));
        flash.getStyleClass().add("chat-flash");
        flash.setManaged(false);
        flash.setVisible(false);
        flash.setMaxWidth(420);
        flash.setWrapText(true);
        StackPane.setAlignment(flash, Pos.BOTTOM_CENTER);
        StackPane.setMargin(flash, new Insets(0, 0, 84, 0));
        right.getChildren().addAll(flash, close);

        mainView.getChildren().addAll(left, divider, right);
    }

    private void buildEmptyView() {
        emptyView.setAlignment(Pos.CENTER);
        StackPane badge = new StackPane(Icons.icon("chat", 30));
        badge.getStyleClass().add("chat-hero-icon");
        badge.setMinSize(68, 68);
        badge.setMaxSize(68, 68);
        Label t = new Label("Выберите друга, чтобы начать чат");
        t.getStyleClass().add("card-title");
        Label h = new Label("Чтобы добавить друга, отправьте ему свой ID (кнопка копирования слева) и введите его ID в поле поиска.");
        h.getStyleClass().add("hint");
        h.setWrapText(true);
        h.setMaxWidth(380);
        h.setTextAlignment(TextAlignment.CENTER);
        emptyView.getChildren().addAll(badge, t, h);
    }

    private void buildChatView() {
        chatHeader.setAlignment(Pos.CENTER_LEFT);
        chatHeader.setPadding(new Insets(18, 64, 12, 24));

        messages.setPadding(new Insets(6, 22, 6, 24));
        messageScroll.getStyleClass().add("ai-scroll");
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(messageScroll, Priority.ALWAYS);
        messages.heightProperty().addListener((o, oldH, newH) -> {
            if (olderPending) {
                olderPending = false;
                double h = newH.doubleValue();
                double range = h - anchorViewport;
                if (range > 1) {
                    double target = (h - anchorHeight) + anchorValue * Math.max(0, anchorHeight - anchorViewport);
                    messageScroll.setVvalue(Math.max(0, Math.min(1, target / range)));
                }
            }
        });

        input.setPromptText("Написать сообщение…");
        input.setTextFormatter(new TextFormatter<String>(c -> c.getControlNewText().length() <= 2000 ? c : null));
        HBox.setHgrow(input, Priority.ALWAYS);
        input.setOnAction(e -> sendCurrent());
        input.textProperty().addListener((o, a, b) -> {
            int n = b == null ? 0 : b.length();
            counter.setText(n >= 1800 ? n + "/2000" : "");
            sendButton.setDisable(b == null || b.isBlank());
        });
        counter.getStyleClass().add("chat-time");
        sendButton.getStyleClass().addAll("accent-button", "send-button");
        sendButton.setGraphic(Icons.icon("send", 20));
        sendButton.setTooltip(new Tooltip("Отправить (Enter)"));
        sendButton.setMinSize(46, 46);
        sendButton.setPrefSize(46, 46);
        sendButton.setDisable(true);
        sendButton.setOnAction(e -> sendCurrent());
        HBox inputRow = new HBox(8, input, counter, sendButton);
        inputRow.setAlignment(Pos.CENTER);
        inputRow.setPadding(new Insets(8, 22, 18, 24));

        chatView.getChildren().addAll(chatHeader, messageScroll, inputRow);
        chatView.setManaged(false);
        chatView.setVisible(false);
    }

    private void buildSettingsView() {
        settingsView.setPadding(new Insets(22, 28, 22, 28));
        Label title = new Label("Профиль чата");
        title.getStyleClass().add("dialog-title");
        Button back = new Button("← К чатам");
        back.getStyleClass().add("soft-button");
        back.setOnAction(e -> { showSettings = false; refreshAll(); });
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(10, title, sp, back);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(0, 44, 0, 0));

        Label nameCap = new Label("Имя в чате");
        nameCap.getStyleClass().add("chat-caption");
        nameField.setPromptText("Ваше имя");
        nameField.setOnAction(e -> saveName());
        HBox.setHgrow(nameField, Priority.ALWAYS);
        Button save = new Button("Сохранить");
        save.getStyleClass().add("accent-button");
        save.setOnAction(e -> saveName());
        HBox nameRow = new HBox(8, nameField, save);
        VBox nameBox = new VBox(6, nameCap, nameRow);
        nameBox.getStyleClass().add("card");

        Label idCap = new Label("Ваш ID лаунчера");
        idCap.getStyleClass().add("chat-caption");
        Label bigId = new Label();
        bigId.getStyleClass().add("chat-id-big");
        bigId.textProperty().bind(myId.textProperty());
        Button copy = new Button("Скопировать");
        copy.getStyleClass().add("soft-button");
        copy.setOnAction(e -> copyMyId());
        Label idHint = new Label("Отправьте этот ID другу — он добавит вас во вкладке «Друзья».");
        idHint.getStyleClass().add("hint");
        idHint.setWrapText(true);
        HBox idRow = new HBox(12, bigId, copy);
        idRow.setAlignment(Pos.CENTER_LEFT);
        VBox idBox = new VBox(6, idCap, idRow, idHint);
        idBox.getStyleClass().add("card");

        Label srvCap = new Label("Сервер чатов");
        srvCap.getStyleClass().add("chat-caption");
        settingsServer.getStyleClass().add("chat-id");
        hostStatus.getStyleClass().add("hint");
        hostStatus.setWrapText(true);
        hostToggle.getStyleClass().add("soft-button");
        hostToggle.setOnAction(e -> toggleHost());
        VBox srvBox = new VBox(6, srvCap, settingsServer, hostStatus, hostToggle);
        srvBox.getStyleClass().add("card");
        srvBox.setManaged(ChatService.DEFAULT_SERVER.isEmpty());
        srvBox.setVisible(ChatService.DEFAULT_SERVER.isEmpty());

        logoutButton.getStyleClass().add("danger-button");
        logoutButton.setOnAction(e -> logout());
        settingsMessage.setWrapText(true);
        settingsMessage.setManaged(false);
        settingsMessage.setVisible(false);

        settingsView.getChildren().addAll(top, nameBox, idBox, srvBox, settingsMessage, logoutButton);
        settingsView.setManaged(false);
        settingsView.setVisible(false);
    }

    private Button closeButton() {
        Button close = new Button();
        close.getStyleClass().addAll("dialog-close-inline", "icon-button");
        close.setGraphic(Icons.icon("close", 16));
        close.setOnAction(e -> host.close());
        return close;
    }

    private Button iconButton(String icon, String tip) {
        Button b = new Button();
        b.getStyleClass().add("chat-icon-btn");
        b.setGraphic(Icons.icon(icon, 16));
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    private enum Stick { FORCE, IF_NEAR, KEEP }

    private void refreshAll() {
        boolean ready = chat.isConfigured() && chat.connection() != ChatService.Conn.AUTH_LOST;
        setupView.setVisible(!ready);
        setupView.setManaged(!ready);
        mainView.setVisible(ready);
        mainView.setManaged(ready);
        if (!ready) {
            if (chat.connection() == ChatService.Conn.AUTH_LOST && !setupConnect.isDisabled()) {
                showSetupMessage("Сервер не узнал ваш аккаунт чата (его могли сбросить). Подключитесь заново — ID лаунчера изменится.", true);
            }
            if (setupServer.getText().isBlank() && !chat.server().isEmpty()) setupServer.setText(chat.server());
            return;
        }
        if (selected != null && chat.friend(selected) == null) selected = null;
        myName.setText(chat.myName());
        myId.setText(chat.myId());
        updateStatus();
        renderLists();
        updateRight();
    }

    private void updateStatus() {
        ChatService.Conn c = chat.connection();
        statusLabel.getStyleClass().removeAll("chat-ok", "chat-error", "chat-muted");
        switch (c) {
            case ONLINE:
                statusLabel.setText("● В сети");
                statusLabel.getStyleClass().add("chat-ok");
                break;
            case CONNECTING:
                statusLabel.setText("Подключение…");
                statusLabel.getStyleClass().add("chat-muted");
                break;
            default:
                String d = chat.connectionDetail();
                statusLabel.setText("● Нет связи" + (d.isEmpty() ? "" : ": " + d) + " Пробую переподключиться…");
                statusLabel.getStyleClass().add("chat-error");
        }
    }

    private void updateRight() {
        Node target = showSettings ? settingsView : selected != null ? chatView : emptyView;
        for (Node n : new Node[]{emptyView, chatView, settingsView}) {
            boolean on = n == target;
            n.setVisible(on);
            n.setManaged(on);
        }
        if (target == settingsView) updateSettings();
        if (target == chatView) { updateChatHeader(); renderMessages(Stick.KEEP); }
    }

    private void renderLists() {
        List<Person> friends = chat.friends();
        List<Request> in = chat.incoming();
        List<Request> out = chat.outgoing();
        int unreadTotal = chat.totalUnread();
        friendsTab.setText(unreadTotal > 0 ? "Друзья · " + unreadTotal : "Друзья");
        requestsTab.setText(in.isEmpty() ? "Заявки" : "Заявки · " + in.size());
        listBox.getChildren().clear();

        if (friendsTab.isSelected()) {
            if (friends.isEmpty()) {
                listBox.getChildren().add(emptyHint("Пока никого нет.\nВведите ID друга выше и отправьте заявку."));
                return;
            }
            List<Person> sorted = new java.util.ArrayList<>(friends);
            sorted.sort((a, b) -> {
                long ta = a.last() == null ? 0 : a.last().ts(), tb = b.last() == null ? 0 : b.last().ts();
                if (ta != tb) return Long.compare(tb, ta);
                if (a.online() != b.online()) return a.online() ? -1 : 1;
                return a.name().compareToIgnoreCase(b.name());
            });
            for (Person p : sorted) listBox.getChildren().add(friendRow(p));
        } else {
            if (in.isEmpty() && out.isEmpty()) {
                listBox.getChildren().add(emptyHint("Заявок нет."));
                return;
            }
            if (!in.isEmpty()) {
                listBox.getChildren().add(sectionLabel("ВХОДЯЩИЕ"));
                for (Request r : in) listBox.getChildren().add(incomingRow(r));
            }
            if (!out.isEmpty()) {
                listBox.getChildren().add(sectionLabel("ОТПРАВЛЕННЫЕ"));
                for (Request r : out) listBox.getChildren().add(outgoingRow(r));
            }
        }
    }

    private Label emptyHint(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hint");
        l.setWrapText(true);
        l.setPadding(new Insets(14, 8, 0, 8));
        return l;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("chat-section");
        return l;
    }

    private Node friendRow(Person p) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-row");
        if (p.id().equals(selected) && !showSettings) row.getStyleClass().add("chat-row-selected");
        row.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(p.name());
        name.getStyleClass().add("chat-name");
        name.setMaxWidth(150);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        String preview;
        if (p.last() == null) preview = p.online() ? "В сети" : "Нет сообщений";
        else preview = (p.last().from().equals(chat.myId()) ? "Вы: " : "") + oneLine(p.last().text());
        Label prev = new Label(preview);
        prev.getStyleClass().add("chat-preview");
        prev.setMaxWidth(150);
        prev.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox mid = new VBox(2, name, prev);
        mid.setMinWidth(0);
        HBox.setHgrow(mid, Priority.ALWAYS);

        VBox side = new VBox(4);
        side.setAlignment(Pos.TOP_RIGHT);
        if (p.last() != null) {
            Label t = new Label(shortTime(p.last().ts()));
            t.getStyleClass().add("chat-time");
            side.getChildren().add(t);
        }
        if (p.unread() > 0) {
            Label badge = new Label(p.unread() > 99 ? "99+" : String.valueOf(p.unread()));
            badge.getStyleClass().add("chat-unread");
            side.getChildren().add(badge);
        }
        row.getChildren().addAll(avatar(p.name(), 38, p.online()), mid, side);
        row.setOnMouseClicked(e -> select(p.id()));
        return row;
    }

    private Node incomingRow(Request r) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-row-static");
        row.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(r.name());
        name.getStyleClass().add("chat-name");
        name.setMaxWidth(120);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label id = new Label(r.id());
        id.getStyleClass().add("chat-preview");
        VBox mid = new VBox(2, name, id);
        mid.setMinWidth(0);
        HBox.setHgrow(mid, Priority.ALWAYS);
        Button ok = new Button();
        ok.getStyleClass().addAll("accent-button", "send-button");
        ok.setGraphic(Icons.icon("check", 18));
        ok.setTooltip(new Tooltip("Принять"));
        ok.setMinSize(34, 34);
        ok.setPrefSize(34, 34);
        ok.setOnAction(e -> respond(r, true));
        Button no = new Button();
        no.getStyleClass().addAll("soft-button", "send-button");
        no.setGraphic(Icons.icon("close", 16));
        no.setTooltip(new Tooltip("Отклонить"));
        no.setMinSize(34, 34);
        no.setPrefSize(34, 34);
        no.setOnAction(e -> respond(r, false));
        row.getChildren().addAll(avatar(r.name(), 36, false), mid, ok, no);
        return row;
    }

    private Node outgoingRow(Request r) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-row-static");
        row.setAlignment(Pos.CENTER_LEFT);
        Label name = new Label(r.name());
        name.getStyleClass().add("chat-name");
        name.setMaxWidth(120);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label st = new Label("Ожидает ответа");
        st.getStyleClass().add("chat-preview");
        VBox mid = new VBox(2, name, st);
        mid.setMinWidth(0);
        HBox.setHgrow(mid, Priority.ALWAYS);
        Button cancel = new Button("Отменить");
        cancel.getStyleClass().add("soft-button");
        cancel.setStyle("-fx-padding:6 12;-fx-font-size:12px;");
        cancel.setOnAction(e -> cancelRequest(r));
        row.getChildren().addAll(avatar(r.name(), 36, false), mid, cancel);
        return row;
    }

    private void updateChatHeader() {
        chatHeader.getChildren().clear();
        Person p = selected == null ? null : chat.friend(selected);
        if (p == null) return;
        Label name = new Label(p.name());
        name.getStyleClass().add("dialog-title");
        Label st = new Label(statusText(p));
        st.getStyleClass().add(p.online() ? "chat-ok" : "chat-muted");
        st.setStyle("-fx-font-size:12px;");
        VBox who = new VBox(1, name, st);
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);
        Button del = iconButton("trash", "Удалить чат / друга");
        del.setOnAction(e -> showDeleteMenu(del, p));
        Button copy = iconButton("copy", "Скопировать ID друга: " + p.id());
        copy.setOnAction(e -> { copyText(p.id()); flash("ID друга скопирован", false); });
        chatHeader.getChildren().addAll(avatar(p.name(), 42, p.online()), who, sp, copy, del);
    }

    private String statusText(Person p) {
        if (p.online()) return "в сети";
        if (p.lastSeen() <= 0) return "не в сети";
        ZonedDateTime t = Instant.ofEpochMilli(p.lastSeen()).atZone(ZoneId.systemDefault());
        LocalDate d = t.toLocalDate();
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "был(а) в сети в " + HM.format(t);
        if (d.equals(today.minusDays(1))) return "был(а) в сети вчера в " + HM.format(t);
        return "был(а) в сети " + DM.format(t);
    }

    private void renderMessages(Stick stick) {
        if (selected == null) return;
        Person p = chat.friend(selected);
        if (p == null) return;
        boolean near = messageScroll.getVvalue() >= 0.97 || messages.getChildren().isEmpty();
        List<Message> list = chat.conversation(selected);
        messages.getChildren().clear();

        if (chat.hasMoreHistory(selected)) {
            Button older = new Button("Показать более ранние сообщения");
            older.getStyleClass().add("soft-button");
            older.setStyle("-fx-padding:6 14;-fx-font-size:12px;");
            older.setOnAction(e -> loadOlder());
            HBox oh = new HBox(older);
            oh.setAlignment(Pos.CENTER);
            messages.getChildren().add(oh);
        }
        if (list.isEmpty()) {
            Label l = new Label("Сообщений пока нет. Напишите первым!");
            l.getStyleClass().add("hint");
            HBox h = new HBox(l);
            h.setAlignment(Pos.CENTER);
            h.setPadding(new Insets(40, 0, 0, 0));
            messages.getChildren().add(h);
        }
        LocalDate lastDay = null;
        String me = chat.myId();
        for (Message m : list) {
            ZonedDateTime t = Instant.ofEpochMilli(m.ts()).atZone(ZoneId.systemDefault());
            LocalDate day = t.toLocalDate();
            if (!day.equals(lastDay)) {
                lastDay = day;
                Label sep = new Label(dayTitle(day));
                sep.getStyleClass().add("chat-day");
                HBox sh = new HBox(sep);
                sh.setAlignment(Pos.CENTER);
                sh.setPadding(new Insets(8, 0, 4, 0));
                messages.getChildren().add(sh);
            }
            messages.getChildren().add(bubble(m, m.from().equals(me), HM.format(t), p.peerRead()));
        }
        if (stick == Stick.FORCE || (stick == Stick.IF_NEAR && near)) {
            Platform.runLater(() -> messageScroll.setVvalue(1.0));
            Platform.runLater(() -> Platform.runLater(() -> messageScroll.setVvalue(1.0)));
        }
    }

    private Node bubble(Message m, boolean mine, String time, long peerRead) {
        Label body = new Label(m.text());
        body.setWrapText(true);
        body.setMaxWidth(440);
        body.getStyleClass().add(mine ? "chat-text-mine" : "chat-text-their");
        Label meta = new Label(mine ? time + "  " + (m.seq() <= peerRead ? "✓✓" : "✓") : time);
        meta.getStyleClass().add(mine ? "chat-meta-mine" : "chat-meta-their");
        if (mine && m.seq() <= peerRead) meta.getStyleClass().add("chat-read");
        HBox metaRow = new HBox(meta);
        metaRow.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(2, body, metaRow);
        box.getStyleClass().addAll("chat-bubble", mine ? "chat-bubble-mine" : "chat-bubble-their");
        box.setMaxWidth(470);
        MenuItem copy = new MenuItem("Копировать текст");
        copy.setOnAction(e -> copyText(m.text()));
        MenuItem delMine = new MenuItem("Удалить у меня");
        delMine.setOnAction(e -> deleteOne(m, false));
        ContextMenu cm = new ContextMenu(copy, new SeparatorMenuItem(), delMine);
        if (mine) {
            MenuItem delBoth = new MenuItem("Удалить у обоих");
            delBoth.setOnAction(e -> deleteOne(m, true));
            cm.getItems().add(delBoth);
        }
        box.setOnContextMenuRequested(e -> {
            cm.show(box, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        HBox row = new HBox(box);
        row.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return row;
    }

    private void updateSettings() {
        if (!nameField.isFocused()) nameField.setText(chat.myName());
        settingsServer.setText(chat.server());
        boolean running = ChatHub.isLocalServerRunning();
        hostToggle.setText(running ? "Остановить сервер на этом ПК" : "Запустить сервер на этом ПК");
        hostStatus.setText(running
                ? "Сервер работает на порту " + ChatHub.LOCAL_PORT + ". Друзья подключаются по вашему адресу, например http://ВАШ_IP:" + ChatHub.LOCAL_PORT
                  + " (нужен проброс порта). Он запускается вместе с лаунчером."
                : "Свой сервер на этом ПК выключен.");
        logoutArmed = false;
        logoutButton.setText("Выйти из чата на этом ПК");
    }

    private Node avatar(String name, double size, boolean online) {
        int h = Math.abs((name == null ? "" : name).hashCode()) % 360;
        Circle c = new Circle(size / 2, Color.hsb(h, 0.5, 0.72));
        String initial = name == null || name.isBlank() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
        Label letter = new Label(initial);
        letter.setStyle("-fx-text-fill:white;-fx-font-weight:800;-fx-font-size:" + Math.round(size * 0.42) + "px;");
        StackPane sp = new StackPane(c, letter);
        sp.setMinSize(size, size);
        sp.setPrefSize(size, size);
        sp.setMaxSize(size, size);
        if (online) {
            Circle dot = new Circle(size * 0.14, Color.web(GREEN));
            dot.setStroke(Color.web("#131315"));
            dot.setStrokeWidth(2);
            StackPane.setAlignment(dot, Pos.BOTTOM_RIGHT);
            sp.getChildren().add(dot);
        }
        return sp;
    }

    private void showSetupMessage(String text, boolean error) {
        setupMessage.setText(text);
        setupMessage.getStyleClass().removeAll("chat-ok", "chat-error");
        setupMessage.getStyleClass().add(error ? "chat-error" : "chat-ok");
        setupMessage.setManaged(true);
        setupMessage.setVisible(true);
    }

    private void setSetupBusy(boolean busy) {
        setupConnect.setDisable(busy);
        setupHost.setDisable(busy);
        setupName.setDisable(busy);
        setupServer.setDisable(busy);
    }

    private void connect() {
        String name = setupName.getText().trim();
        String server = ChatService.DEFAULT_SERVER.isEmpty() ? setupServer.getText().trim() : ChatService.DEFAULT_SERVER;
        if (name.length() < 2) { showSetupMessage("Введите ник (минимум 2 символа).", true); return; }
        if (server.isEmpty()) { showSetupMessage("Введите адрес сервера чатов или запустите свой.", true); return; }
        setSetupBusy(true);
        showSetupMessage("Подключаюсь…", false);
        worker.execute(() -> {
            try {
                chat.setup(server, name);
                Platform.runLater(() -> {
                    setSetupBusy(false);
                    setupMessage.setManaged(false);
                    setupMessage.setVisible(false);
                    refreshAll();
                    flash("Готово! Ваш код: " + chat.myId(), false);
                });
            } catch (ChatException e) {
                Platform.runLater(() -> { setSetupBusy(false); showSetupMessage(e.getMessage(), true); });
            }
        });
    }

    private void connectLocal() {
        String name = setupName.getText().trim();
        if (name.length() < 2) { showSetupMessage("Введите имя (минимум 2 символа).", true); return; }
        setSetupBusy(true);
        showSetupMessage("Запускаю сервер…", false);
        worker.execute(() -> {
            try {
                ChatHub.startLocalServer();
                chat.setup("http://127.0.0.1:" + ChatHub.LOCAL_PORT, name);
                Platform.runLater(() -> {
                    setSetupBusy(false);
                    setupMessage.setManaged(false);
                    setupMessage.setVisible(false);
                    refreshAll();
                    flash("Сервер запущен. Ваш ID: " + chat.myId(), false);
                });
            } catch (java.io.IOException e) {
                Platform.runLater(() -> { setSetupBusy(false); showSetupMessage(e.getMessage(), true); });
            } catch (ChatException e) {
                Platform.runLater(() -> { setSetupBusy(false); showSetupMessage(e.getMessage(), true); });
            }
        });
    }

    private void addFriend() {
        String raw = addField.getText();
        if (raw == null || raw.isBlank()) { feedback(addFeedback, "Введите ID друга, например NX-AB12-CD34.", true); return; }
        addButton.setDisable(true);
        worker.execute(() -> {
            try {
                String status = chat.addFriend(raw);
                Platform.runLater(() -> {
                    addButton.setDisable(false);
                    addField.clear();
                    feedback(addFeedback, "accepted".equals(status) ? "Вы теперь друзья!" : "Заявка отправлена. Друг увидит её в разделе «Заявки».", false);
                    if (!"accepted".equals(status)) requestsTab.setSelected(true);
                    else friendsTab.setSelected(true);
                });
            } catch (ChatException e) {
                Platform.runLater(() -> { addButton.setDisable(false); feedback(addFeedback, e.getMessage(), true); });
            }
        });
    }

    private void respond(Request r, boolean accept) {
        worker.execute(() -> {
            try {
                chat.respond(r.id(), accept);
                Platform.runLater(() -> {
                    flash(accept ? r.name() + " добавлен в друзья" : "Заявка отклонена", false);
                    if (accept) friendsTab.setSelected(true);
                });
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void cancelRequest(Request r) {
        worker.execute(() -> {
            try {
                chat.remove(r.id());
                Platform.runLater(() -> flash("Заявка отменена", false));
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void select(String id) {
        showSettings = false;
        selected = id;
        activate(id);
        refreshAll();
        renderMessages(Stick.FORCE);
        Platform.runLater(input::requestFocus);
    }

    private void activate(String id) {
        chat.setActiveChat(id);
        worker.execute(() -> {
            try {
                if (chat.conversation(id).isEmpty()) chat.history(id, 0);
                chat.markRead(id);
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
            Platform.runLater(() -> { if (id.equals(selected) && !showSettings) renderMessages(Stick.FORCE); });
        });
    }

    private void loadOlder() {
        String id = selected;
        if (id == null) return;
        List<Message> cur = chat.conversation(id);
        if (cur.isEmpty()) return;
        long before = cur.get(0).seq();
        anchorHeight = messages.getHeight();
        anchorValue = messageScroll.getVvalue();
        anchorViewport = messageScroll.getViewportBounds().getHeight();
        worker.execute(() -> {
            try {
                chat.history(id, before);
                Platform.runLater(() -> {
                    if (id.equals(selected)) {
                        olderPending = true;
                        renderMessages(Stick.KEEP);
                    }
                });
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void sendCurrent() {
        String id = selected;
        String text = input.getText() == null ? "" : input.getText().strip();
        if (id == null || text.isEmpty()) return;
        input.clear();
        worker.execute(() -> {
            try {
                chat.send(id, text);
            } catch (ChatException e) {
                Platform.runLater(() -> {
                    if (input.getText().isEmpty()) input.setText(text);
                    flash(e.getMessage(), true);
                });
            }
        });
    }

    private void showDeleteMenu(Node anchor, Person p) {
        MenuItem mine = new MenuItem("Удалить чат у меня");
        mine.setOnAction(e -> clearSelected(false));
        MenuItem both = new MenuItem("Удалить чат у обоих");
        both.setOnAction(e -> clearSelected(true));
        MenuItem friend = new MenuItem("Удалить из друзей");
        friend.setOnAction(e -> removeSelected());
        ContextMenu menu = new ContextMenu(mine, both, new SeparatorMenuItem(), friend);
        javafx.geometry.Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        if (b != null) menu.show(anchor, b.getMinX(), b.getMaxY() + 4);
        else menu.show(anchor, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void clearSelected(boolean forBoth) {
        String id = selected;
        if (id == null) return;
        worker.execute(() -> {
            try {
                chat.clearChat(id, forBoth);
                Platform.runLater(() -> {
                    if (id.equals(selected) && !showSettings) renderMessages(Stick.FORCE);
                    refreshAll();
                    flash(forBoth ? "Чат удалён у вас и у собеседника" : "Чат удалён у вас", false);
                });
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void deleteOne(Message m, boolean forBoth) {
        String id = selected;
        if (id == null) return;
        worker.execute(() -> {
            try {
                chat.deleteMessage(id, m.seq(), forBoth);
                Platform.runLater(() -> {
                    if (id.equals(selected) && !showSettings) renderMessages(Stick.KEEP);
                    refreshAll();
                });
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void removeSelected() {
        String id = selected;
        if (id == null) return;
        worker.execute(() -> {
            try {
                chat.remove(id);
                Platform.runLater(() -> {
                    if (id.equals(selected)) selected = null;
                    refreshAll();
                    flash("Друг удалён", false);
                });
            } catch (ChatException e) {
                Platform.runLater(() -> flash(e.getMessage(), true));
            }
        });
    }

    private void saveName() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.length() < 2) { feedback(settingsMessage, "Имя должно быть от 2 до 32 символов.", true); return; }
        worker.execute(() -> {
            try {
                chat.rename(name);
                Platform.runLater(() -> feedback(settingsMessage, "Имя сохранено.", false));
            } catch (ChatException e) {
                Platform.runLater(() -> feedback(settingsMessage, e.getMessage(), true));
            }
        });
    }

    private void toggleHost() {
        hostToggle.setDisable(true);
        worker.execute(() -> {
            String error = null;
            if (ChatHub.isLocalServerRunning()) ChatHub.stopLocalServer();
            else {
                try { ChatHub.startLocalServer(); } catch (java.io.IOException e) { error = e.getMessage(); }
            }
            final String err = error;
            Platform.runLater(() -> {
                hostToggle.setDisable(false);
                updateSettings();
                if (err != null) feedback(settingsMessage, err, true);
            });
        });
    }

    private void logout() {
        if (!logoutArmed) {
            logoutArmed = true;
            logoutButton.setText("Точно выйти? Нажмите ещё раз");
            PauseTransition p = new PauseTransition(Duration.seconds(4));
            p.setOnFinished(e -> { logoutArmed = false; logoutButton.setText("Выйти из чата на этом ПК"); });
            p.play();
            return;
        }
        logoutArmed = false;
        worker.execute(() -> {
            chat.logout();
            Platform.runLater(() -> {
                selected = null;
                showSettings = false;
                messages.getChildren().clear();
                refreshAll();
            });
        });
    }

    private void copyMyId() {
        copyText(chat.myId());
        flash("ID скопирован: " + chat.myId(), false);
    }

    private static void copyText(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void feedback(Label label, String text, boolean error) {
        label.setText(text);
        label.getStyleClass().removeAll("chat-ok", "chat-error");
        label.getStyleClass().add(error ? "chat-error" : "chat-ok");
        label.setManaged(true);
        label.setVisible(true);
        PauseTransition hide = new PauseTransition(Duration.seconds(6));
        hide.setOnFinished(e -> {
            if (text.equals(label.getText())) { label.setManaged(false); label.setVisible(false); }
        });
        hide.play();
    }

    private PauseTransition flashTimer;

    private void flash(String text, boolean error) {
        flash.setText(text);
        flash.getStyleClass().removeAll("chat-flash-error");
        if (error) flash.getStyleClass().add("chat-flash-error");
        flash.setManaged(true);
        flash.setVisible(true);
        if (flashTimer != null) flashTimer.stop();
        flashTimer = new PauseTransition(Duration.seconds(error ? 5 : 3));
        flashTimer.setOnFinished(e -> { flash.setManaged(false); flash.setVisible(false); });
        flashTimer.play();
    }

    private static String oneLine(String s) {
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private static String shortTime(long ts) {
        ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault());
        LocalDate today = LocalDate.now();
        if (t.toLocalDate().equals(today)) return HM.format(t);
        if (t.toLocalDate().equals(today.minusDays(1))) return "вчера";
        return DM.format(t);
    }

    private static String dayTitle(LocalDate d) {
        LocalDate today = LocalDate.now();
        if (d.equals(today)) return "Сегодня";
        if (d.equals(today.minusDays(1))) return "Вчера";
        return DMY.format(d);
    }
}
