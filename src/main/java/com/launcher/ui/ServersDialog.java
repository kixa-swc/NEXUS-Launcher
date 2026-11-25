package com.launcher.ui;

import com.launcher.core.GameSessions;
import com.launcher.core.ServerList;
import com.launcher.core.ServerPinger;
import com.launcher.model.Account;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;

public final class ServersDialog extends VBox {
    private static final int PING_TIMEOUT_MS = 3000;

    public interface Host {
        void close();
        List<String> versionIds();
        List<Account> accounts();
        Account defaultNick(ServerList.Entry entry);
        void connect(ServerList.Entry entry, Account account);
    }

    private static final String DEFAULT_VERSION = "Как на главной";
    private static final String[] LOADERS = {DEFAULT_VERSION, "Vanilla", "Fabric", "Forge", "NeoForge"};

    private final Host host;
    private final ComboBox<String> mcCombo = new ComboBox<>();
    private final ComboBox<String> loaderCombo = new ComboBox<>();
    private final FlowPane nickBox = new FlowPane(12, 6);
    private final VBox detailCard = new VBox(8);
    private final Label sessionsLabel = new Label();
    private final Button asNickButton = new Button("Под ником…");
    private final Button editButton = new Button("Изменить адрес");
    private final TextField editNameField = new TextField();
    private final TextField editAddressField = new TextField();
    private final HBox editRow = new HBox();
    private boolean populating;
    private final ExecutorService pinger = Executors.newFixedThreadPool(6, r -> {
        Thread thread = new Thread(r, "nexus-server-ping");
        thread.setDaemon(true);
        return thread;
    });

    private final ListView<ServerList.Entry> list = new ListView<>();
    private final TextField nameField = new TextField();
    private final TextField addressField = new TextField();
    private final Label statusLabel = new Label();
    private final Button connectButton = new Button("Подключиться");
    private final Button deleteButton = new Button("Удалить выбранный");

    private final Map<String, ServerPinger.Status> statuses = new HashMap<>();
    private final Set<String> pinging = new HashSet<>();
    private int refreshGeneration;
    private boolean deleteArmed;
    private final PauseTransition deleteDisarm = new PauseTransition(Duration.seconds(4));

    public ServersDialog(Host host) {
        this.host = host;
        final Runnable onClose = host::close;
        getStyleClass().addAll("dialog", "small-dialog");
        setSpacing(12);
        setPrefSize(780, 600);
        setMaxSize(780, 600);
        setVisible(false);
        setManaged(false);

        Label title = new Label("Серверы");
        title.getStyleClass().add("dialog-title");
        Button close = new Button();
        close.getStyleClass().addAll("dialog-close-inline", "icon-button");
        close.setGraphic(Icons.icon("close", 16));
        close.setOnAction(e -> onClose.run());
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(title, headerSpacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("«Подключиться» запускает игру и сразу заходит на сервер; если игра уже запущена, NEXUS сам перезапустит её на этом сервере. Версию, загрузчик и ники для сервера выберите ниже.");
        hint.setWrapText(true);
        hint.getStyleClass().add("hint");

        nameField.setPromptText("Название (необязательно)");
        nameField.setPrefWidth(220);
        addressField.setPromptText("Адрес: play.example.com или 192.168.1.5:25566");
        addressField.setOnAction(e -> addServer());
        nameField.setOnAction(e -> addServer());
        HBox.setHgrow(addressField, Priority.ALWAYS);
        Button add = new Button("Добавить");
        add.getStyleClass().add("soft-button");
        add.setOnAction(e -> addServer());
        HBox addRow = new HBox(8, nameField, addressField, add);
        addRow.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("mod-list");
        list.setFixedCellSize(62);
        list.setCellFactory(l -> new ServerCell());
        list.setPlaceholder(new Label("Серверов пока нет — добавьте адрес выше."));
        VBox.setVgrow(list, Priority.ALWAYS);

        Button refresh = new Button("Обновить");
        refresh.getStyleClass().add("soft-button");
        refresh.setOnAction(e -> refreshAll());
        deleteButton.getStyleClass().add("danger-button");
        deleteButton.setOnAction(e -> deleteSelected());
        connectButton.getStyleClass().add("accent-button");
        connectButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        connectButton.setOnAction(e -> connectSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        asNickButton.getStyleClass().add("soft-button");
        asNickButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        asNickButton.setOnAction(e -> showNickMenu(asNickButton));
        HBox actions = new HBox(8, refresh, deleteButton, spacer, asNickButton, connectButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        buildDetailCard();
        sessionsLabel.getStyleClass().add("hint");
        sessionsLabel.setWrapText(true);

        statusLabel.getStyleClass().add("hint");
        statusLabel.setMinHeight(18);
        statusLabel.setWrapText(true);

        getChildren().addAll(header, hint, addRow, list, detailCard, sessionsLabel, actions, statusLabel);
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (editRow.isVisible() && !populating) toggleEdit();
            editButton.setDisable(b == null);
            showDetails(b);
        });
        editButton.setDisable(true);
        deleteDisarm.setOnFinished(e -> disarmDelete());
    }

    public void open() {
        disarmDelete();
        statusLabel.setText("");
        list.getItems().setAll(ServerList.load());
        if (!list.getItems().isEmpty()) list.getSelectionModel().select(0);
        else showDetails(null);
        refreshSessions();
        refreshAll();
    }

    private void buildDetailCard() {
        detailCard.getStyleClass().add("card");
        Label title = new Label("Запуск на выбранном сервере");
        title.getStyleClass().add("card-title");
        mcCombo.setPrefWidth(190);
        loaderCombo.setPrefWidth(160);
        loaderCombo.getItems().setAll(LOADERS);
        mcCombo.setOnAction(e -> saveLaunchChoice());
        loaderCombo.setOnAction(e -> saveLaunchChoice());
        Label versionLabel = new Label("Версия");
        versionLabel.getStyleClass().add("field-label");
        HBox versionRow = new HBox(8, versionLabel, mcCombo, loaderCombo);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        Label nicksLabel = new Label("Ники");
        nicksLabel.getStyleClass().add("field-label");
        nicksLabel.setMinWidth(Region.USE_PREF_SIZE);
        nickBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nickBox, Priority.ALWAYS);
        HBox nickRow = new HBox(8, nicksLabel, nickBox);
        nickRow.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("Отметьте ники для этого сервера. Если ничего не отмечено, доступны все. «Как на главной» берёт версию и загрузчик с главной страницы.");
        note.getStyleClass().add("hint");
        note.setWrapText(true);
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        editButton.getStyleClass().add("soft-button");
        editButton.setOnAction(e -> toggleEdit());
        HBox titleRow = new HBox(8, title, titleSpacer, editButton);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        editNameField.setPromptText("Название");
        editNameField.setPrefWidth(190);
        editAddressField.setPromptText("Адрес: play.example.com или 192.168.1.5:25566");
        HBox.setHgrow(editAddressField, Priority.ALWAYS);
        editNameField.setOnAction(e -> saveEdit());
        editAddressField.setOnAction(e -> saveEdit());
        Button saveEdit = new Button("Сохранить");
        saveEdit.getStyleClass().add("accent-button");
        saveEdit.setOnAction(e -> saveEdit());
        Button cancelEdit = new Button("Отмена");
        cancelEdit.getStyleClass().add("soft-button");
        cancelEdit.setOnAction(e -> toggleEdit());
        editRow.getChildren().addAll(editNameField, editAddressField, saveEdit, cancelEdit);
        editRow.setSpacing(8);
        editRow.setAlignment(Pos.CENTER_LEFT);
        editRow.setVisible(false);
        editRow.setManaged(false);
        detailCard.getChildren().addAll(titleRow, editRow, versionRow, nickRow, note);
    }

    private void toggleEdit() {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        boolean show = !editRow.isVisible();
        if (show && entry == null) { statusLabel.setText("Выберите сервер в списке."); return; }
        if (show) {
            editNameField.setText(entry.name());
            editAddressField.setText(entry.address());
            Platform.runLater(editAddressField::requestFocus);
        }
        editRow.setVisible(show);
        editRow.setManaged(show);
        editButton.setText(show ? "Закрыть" : "Изменить адрес");
    }

    private void saveEdit() {
        ServerList.Entry old = list.getSelectionModel().getSelectedItem();
        if (old == null) return;
        String error = ServerList.edit(old, editNameField.getText(), editAddressField.getText());
        if (error != null) { statusLabel.setText(error); return; }
        String address = ServerList.parseAddress(editAddressField.getText()).display();
        statuses.remove(old.address());
        pinging.remove(old.address());
        List<ServerList.Entry> all = ServerList.load();
        list.getItems().setAll(all);
        for (ServerList.Entry e : all) {
            if (e.address().equals(address)) { list.getSelectionModel().select(e); ping(e, refreshGeneration); break; }
        }
        editRow.setVisible(false);
        editRow.setManaged(false);
        editButton.setText("Изменить адрес");
        statusLabel.setText("Сервер изменён.");
    }

    private void showDetails(ServerList.Entry entry) {
        detailCard.setVisible(entry != null);
        detailCard.setManaged(entry != null);
        if (entry == null) return;
        populating = true;
        try {
            List<String> versions = new ArrayList<>();
            versions.add(DEFAULT_VERSION);
            for (String id : host.versionIds()) if (!versions.contains(id)) versions.add(id);
            if (!entry.mcOrEmpty().isEmpty() && !versions.contains(entry.mcOrEmpty())) versions.add(1, entry.mcOrEmpty());
            mcCombo.getItems().setAll(versions);
            mcCombo.setValue(entry.mcOrEmpty().isEmpty() ? DEFAULT_VERSION : entry.mcOrEmpty());
            String loader = entry.loaderOrEmpty();
            loaderCombo.setValue(loader.isEmpty() ? DEFAULT_VERSION : loader);
            nickBox.getChildren().clear();
            for (Account account : host.accounts()) {
                CheckBox box = new CheckBox(account.getUsername());
                box.setSelected(account.getUuid() != null && entry.nickList().contains(account.getUuid()));
                box.setOnAction(e -> saveNicks());
                box.setUserData(account.getUuid());
                nickBox.getChildren().add(box);
            }
        } finally {
            populating = false;
        }
    }

    private void saveLaunchChoice() {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        if (populating || entry == null) return;
        String mc = mcCombo.getValue() == null || DEFAULT_VERSION.equals(mcCombo.getValue()) ? "" : mcCombo.getValue();
        String loader = loaderCombo.getValue() == null || DEFAULT_VERSION.equals(loaderCombo.getValue()) ? "" : loaderCombo.getValue();
        updateSelected(entry.withLaunch(mc, loader));
    }

    private void saveNicks() {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        if (populating || entry == null) return;
        List<String> nicks = new ArrayList<>();
        for (javafx.scene.Node node : nickBox.getChildren())
            if (node instanceof CheckBox box && box.isSelected() && box.getUserData() != null) nicks.add(String.valueOf(box.getUserData()));
        updateSelected(entry.withNicks(nicks));
    }

    private void updateSelected(ServerList.Entry updated) {
        ServerList.update(updated);
        int index = list.getSelectionModel().getSelectedIndex();
        populating = true;
        try {
            list.getItems().set(index, updated);
            list.getSelectionModel().select(index);
        } finally {
            populating = false;
        }
    }

    public void refreshSessions() {
        List<GameSessions.Session> sessions = GameSessions.all();
        if (sessions.isEmpty()) { sessionsLabel.setText("Сейчас ни одна игра не запущена."); return; }
        StringBuilder text = new StringBuilder("Запущено: ");
        for (int i = 0; i < sessions.size(); i++) {
            GameSessions.Session session = sessions.get(i);
            if (i > 0) text.append("; ");
            text.append(session.nick()).append(session.server() == null ? " — не на сервере" : " — " + session.server());
        }
        sessionsLabel.setText(text.toString());
    }

    private List<Account> candidates(ServerList.Entry entry) {
        List<Account> all = host.accounts(), chosen = new ArrayList<>();
        for (Account account : all) if (account.getUuid() != null && entry.nickList().contains(account.getUuid())) chosen.add(account);
        return chosen.isEmpty() ? all : chosen;
    }

    private String statusOf(Account account, ServerList.Entry entry) {
        List<GameSessions.Session> mine = GameSessions.forAccount(account.getUuid());
        if (mine.isEmpty()) return "не запущена";
        String target = GameSessions.normalize(entry.address());
        for (GameSessions.Session session : mine) if (target != null && target.equals(session.server())) return "уже на этом сервере";
        for (GameSessions.Session session : mine) if (session.server() != null) return "сейчас на " + session.server();
        return "запущена, перезапустится на сервер";
    }

    private void showNickMenu(javafx.scene.Node anchor) {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) { statusLabel.setText("Выберите сервер в списке."); return; }
        List<Account> options = candidates(entry);
        if (options.isEmpty()) { statusLabel.setText("Нет аккаунтов: добавьте профиль на главной странице."); return; }
        ContextMenu menu = new ContextMenu();
        for (Account account : options) {
            MenuItem item = new MenuItem(account.getUsername() + "  ·  " + statusOf(account, entry));
            item.setOnAction(e -> host.connect(entry, account));
            menu.getItems().add(item);
        }
        menu.show(anchor, Side.TOP, 0, -4);
    }

    private void addServer() {
        String error = ServerList.add(nameField.getText(), addressField.getText());
        if (error != null) {
            statusLabel.setText(error);
            return;
        }
        String added = ServerList.parseAddress(addressField.getText()).display();
        nameField.clear();
        addressField.clear();
        List<ServerList.Entry> all = ServerList.load();
        list.getItems().setAll(all);
        for (ServerList.Entry e : all) {
            if (e.address().equals(added)) {
                list.getSelectionModel().select(e);
                ping(e, refreshGeneration);
                break;
            }
        }
        statusLabel.setText("Сервер добавлен.");
    }

    private void deleteSelected() {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) { statusLabel.setText("Выберите сервер в списке."); return; }
        if (!deleteArmed) {
            deleteArmed = true;
            deleteButton.setText("Нажмите ещё раз: удалить «" + shorten(entry.name(), 22) + "»");
            deleteDisarm.playFromStart();
            return;
        }
        disarmDelete();
        ServerList.remove(entry);
        statuses.remove(entry.address());
        pinging.remove(entry.address());
        list.getItems().setAll(ServerList.load());
        statusLabel.setText("Удалён: " + entry.name());
    }

    private void disarmDelete() {
        deleteArmed = false;
        deleteDisarm.stop();
        deleteButton.setText("Удалить выбранный");
    }

    private void connectSelected() {
        ServerList.Entry entry = list.getSelectionModel().getSelectedItem();
        if (entry == null) { statusLabel.setText("Выберите сервер в списке."); return; }
        Account nick = host.defaultNick(entry);
        if (nick != null || host.accounts().isEmpty()) host.connect(entry, nick);
        else showNickMenu(connectButton);
    }

    private void refreshAll() {
        final int generation = ++refreshGeneration;
        statuses.clear();
        pinging.clear();
        List<ServerList.Entry> items = list.getItems();
        for (ServerList.Entry entry : items) ping(entry, generation);
        statusLabel.setText(items.isEmpty() ? "" : "Проверяю серверы…");
        list.refresh();
    }

    private void ping(ServerList.Entry entry, int generation) {
        final String address = entry.address();
        statuses.remove(address);
        pinging.add(address);
        list.refresh();
        pinger.execute(() -> {
            ServerPinger.Status status = ServerPinger.ping(address, PING_TIMEOUT_MS);
            Platform.runLater(() -> {
                if (generation != refreshGeneration) return;
                pinging.remove(address);
                statuses.put(address, status);
                list.refresh();
                if (pinging.isEmpty()) {
                    long online = list.getItems().stream().filter(e -> {
                        ServerPinger.Status s = statuses.get(e.address());
                        return s != null && s.online();
                    }).count();
                    statusLabel.setText(list.getItems().isEmpty() ? "" : "Онлайн: " + online + " из " + list.getItems().size() + ".");
                }
            });
        });
    }

    private final class ServerCell extends ListCell<ServerList.Entry> {
        private final Circle dot = new Circle(5);
        private final Label name = new Label();
        private final Label meta = new Label();
        private final Label motd = new Label();
        private final Label players = new Label();
        private final Label latency = new Label();
        private final HBox row;

        ServerCell() {
            dot.getStyleClass().add("server-dot");
            name.getStyleClass().add("mod-name");
            meta.getStyleClass().add("hint");
            motd.getStyleClass().add("hint");
            players.getStyleClass().add("mod-name");
            latency.getStyleClass().add("hint");
            VBox text = new VBox(2, name, meta, motd);
            HBox.setHgrow(text, Priority.ALWAYS);
            VBox right = new VBox(2, players, latency);
            right.setAlignment(Pos.CENTER_RIGHT);
            right.setMinWidth(110);
            row = new HBox(12, dot, text, right);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            text.maxWidthProperty().bind(list.widthProperty().subtract(200));
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !isEmpty() && getItem() != null) {
                    list.getSelectionModel().select(getItem());
                    connectSelected();
                }
            });
        }

        @Override protected void updateItem(ServerList.Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) { setGraphic(null); setText(null); return; }
            ServerPinger.Status status = statuses.get(entry.address());
            name.setText(entry.name());
            String state = "server-dot-unknown";
            if (status != null && status.online()) {
                state = "server-dot-online";
                String version = status.version().isBlank() ? "" : "  ·  " + status.version();
                meta.setText(entry.address() + version);
                motd.setText(shorten(status.motd(), 80));
                players.setText(status.players() + " / " + status.maxPlayers());
                latency.setText(status.latencyMs() > 0 ? status.latencyMs() + " мс" : "");
            } else if (status != null) {
                state = "server-dot-offline";
                meta.setText(entry.address());
                motd.setText("");
                players.setText("не отвечает");
                latency.setText(status.error() == null ? "" : shorten(status.error(), 24));
            } else {
                meta.setText(entry.address());
                motd.setText("");
                players.setText(pinging.contains(entry.address()) ? "проверка…" : "—");
                latency.setText("");
            }
            dot.getStyleClass().setAll("server-dot", state);
            row.setOpacity(status != null && !status.online() ? 0.7 : 1.0);
            setText(null);
            setGraphic(row);
        }
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
