package com.launcher.controller;

import com.launcher.App;
import com.launcher.core.*;
import com.launcher.model.Account;
import com.launcher.model.GameVersion;
import com.launcher.util.FileUtils;
import com.launcher.ui.SkinPreview3D;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.css.PseudoClass;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Group;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import com.launcher.ui.Icons;
import com.launcher.ui.CrashDialog;
import com.launcher.core.ModpackInstaller;
import com.launcher.ui.AiChatDialog;
import com.launcher.ui.ChatDialog;
import com.launcher.chat.ChatHub;
import com.launcher.chat.ChatService;
import com.launcher.ui.ModsDialog;
import com.launcher.ui.ServersDialog;
import com.launcher.core.GameSessions;
import com.launcher.ui.ThemeArt;
import com.launcher.ui.Themes;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.layout.FlowPane;
import com.launcher.ui.WindowChrome;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class MainController {
    @FXML private ComboBox<GameVersion> versionComboBox;
    @FXML private ComboBox<String> versionFilterComboBox;
    @FXML private TextField versionSearchField;
    private final javafx.animation.PauseTransition versionSearchDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(350));
    @FXML private ComboBox<String> modLoaderComboBox;
    @FXML private ComboBox<String> loaderVersionComboBox;
    @FXML private ComboBox<Account> accountComboBox;
    @FXML private TextField usernameField;
    @FXML private TextField minecraftDirField;
    @FXML private Slider ramSlider;
    @FXML private TextField ramInputField;
    @FXML private ComboBox<String> ramUnitComboBox;
    @FXML private Label ramAvailableLabel;
    @FXML private TextField windowWidthField;
    @FXML private TextField jvmArgsField;
    @FXML private ComboBox<String> jvmPresetComboBox;
    @FXML private Button jvmClearButton;
    @FXML private ComboBox<String> sessionActionComboBox;
    @FXML private TextField windowHeightField;
    @FXML private CheckBox fullscreenCheckBox;
    @FXML private Label ramLabel;
    @FXML private Label javaStatusLabel;
    @FXML private TextField javaPathField;
    @FXML private ImageView playerSkinImageView;
    @FXML private StackPane skinPreviewHost;
    @FXML private ComboBox<String> skinVariantComboBox;
    @FXML private Label skinStatusLabel;
    @FXML private TextField launcherWidthField;
    @FXML private TextField launcherHeightField;
    @FXML private CheckBox launcherFullscreenCheckBox;
    @FXML private ComboBox<String> gameResolutionComboBox;
    @FXML private ComboBox<String> launcherResolutionComboBox;
    private SkinPreview3D skinPreview3D;
    @FXML private TextArea launcherGameLogs;
    @FXML private VBox msCodeCard;
    @FXML private Label msCodeLabel, msCodeHint;
    @FXML private TextArea monitorLogs;
    @FXML private TabPane logsTabPane;
    @FXML private Button launchButton;
    @FXML private Button aiButton;
    @FXML private VBox heroBox;
    private Timeline playtimeTimeline;
    private Image currentSkinImage;
    @FXML private StackPane rootPane;
    @FXML private StackPane brandMark;
    @FXML private FlowPane themeGrid;
    private final ThemeArt themeArt = new ThemeArt();
    private Themes.Theme theme = Themes.byId(Themes.DEFAULT_ID);
    private final java.util.Map<String, VBox> themeCards = new java.util.LinkedHashMap<>();

    private final java.util.Set<Future<?>> pendingLaunches = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore prepGate = new java.util.concurrent.Semaphore(1);
    private final java.util.concurrent.atomic.AtomicInteger busy = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean launchRunning;
    private final ExecutorService launchPool = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "nexus-game");
        thread.setDaemon(true);
        return thread;
    });

    private final java.util.Set<String> launchingKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String launchKey(Account account, String address) {
        String server = GameSessions.normalize(address);
        return account == null || account.getUuid() == null || server == null ? null : account.getUuid().toLowerCase() + "|" + server;
    }

    private void beginBusy() {
        busy.incrementAndGet();
        launchRunning = true;
    }

    private void endBusy() {
        launchRunning = busy.updateAndGet(v -> Math.max(0, v - 1)) > 0;
    }

    private final ExecutorService background = Executors.newFixedThreadPool(3, r -> {
        Thread thread = new Thread(r, "nexus-background");
        thread.setDaemon(true);
        return thread;
    });
    private record PendingLog(boolean microsoft, boolean game, String message) {}
    private final ConcurrentLinkedQueue<PendingLog> pendingLogs = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean logFlushScheduled = new AtomicBoolean(false);
    private List<GameVersion> allVersions = new ArrayList<>();
    private List<Account> accounts = new ArrayList<>();

    @FXML
    public void initialize() {
        if (uninstallTip != null && Boolean.parseBoolean(SettingsManager.getSetting("uninstall_tip_hidden", "false"))) {
            uninstallTip.setVisible(false);
            uninstallTip.setManaged(false);
        }
        long totalMemoryMb = getTotalPhysicalMemoryMb();
        int safeMaxGb = (int)Math.max(1, Math.floor(totalMemoryMb * 0.85 / 1024.0));
        ramSlider.setMin(1);
        ramSlider.setMax(safeMaxGb);
        ramSlider.setValue(Math.min(4, safeMaxGb));
        if (ramUnitComboBox != null) {
            ramUnitComboBox.getItems().setAll("GB", "MB");
            ramUnitComboBox.setValue("GB");
            ramUnitComboBox.setOnAction(e -> {
                if (ramInputField != null) {
                    int gb = (int)Math.round(ramSlider.getValue());
                    ramInputField.setText("MB".equals(ramUnitComboBox.getValue()) ? Integer.toString(gb * 1024) : Integer.toString(gb));
                }
                syncRamLabel();
            });
        }
        ramSlider.valueProperty().addListener((obs, oldVal, value) -> {
            if (ramInputField != null) {
                String unit = ramUnitComboBox == null ? "GB" : ramUnitComboBox.getValue();
                ramInputField.setText("MB".equals(unit) ? Integer.toString(value.intValue() * 1024) : Integer.toString(value.intValue()));
            }
            syncRamLabel();
            try { SettingsManager.saveSetting("ram_mb", Integer.toString(getRamMb())); } catch (Exception ignored) {}
        });
        ramLabel.setText((int) ramSlider.getValue() + " GB");
        if (ramAvailableLabel != null) ramAvailableLabel.setText(String.format(java.util.Locale.ROOT, "Обнаружено %.1f GB RAM · безопасный максимум %d GB", totalMemoryMb / 1024.0, safeMaxGb));
        if (ramInputField != null) {
            ramInputField.setText(Integer.toString((int)ramSlider.getValue()));
            ramInputField.setOnAction(e -> applyRamInput());
            ramInputField.focusedProperty().addListener((o, was, focused) -> { if (!focused) applyRamInput(); });
        }
        int savedRam;
        try { savedRam = Integer.parseInt(SettingsManager.getSetting("ram_mb", "4096")); }
        catch (Exception ignored) { savedRam = 4096; }
        int savedRamGb = Math.min(safeMaxGb, Math.max(1, (int)Math.ceil(savedRam / 1024.0)));
        ramSlider.setValue(savedRamGb);
        if (ramInputField != null) ramInputField.setText(Integer.toString(savedRamGb));
        syncRamLabel();
        setupJvmAndSessionSettings();
        if (windowWidthField != null) windowWidthField.setText(SettingsManager.getSetting("game_window_width", "1920"));
        if (windowHeightField != null) windowHeightField.setText(SettingsManager.getSetting("game_window_height", "1080"));
        if (fullscreenCheckBox != null) fullscreenCheckBox.setSelected(Boolean.parseBoolean(SettingsManager.getSetting("game_fullscreen", "false")));
        if (launcherWidthField != null) launcherWidthField.setText(SettingsManager.getSetting("launcher_window_width", "1240"));
        if (launcherHeightField != null) launcherHeightField.setText(SettingsManager.getSetting("launcher_window_height", "720"));
        if (launcherFullscreenCheckBox != null) launcherFullscreenCheckBox.setSelected(Boolean.parseBoolean(SettingsManager.getSetting("launcher_fullscreen", "false")));
        setupCloseBehaviorSetting();
        if (gameResolutionComboBox != null) {
            gameResolutionComboBox.getItems().setAll("1920×1080", "1600×900", "1280×720", "1024×768", "800×600", "640×520", "Пользовательский");
            gameResolutionComboBox.setValue("Пользовательский");
            gameResolutionComboBox.setOnAction(e -> { applyResolutionPreset(gameResolutionComboBox, windowWidthField, windowHeightField); saveGameWindowSettings(); });
        }
        if (windowWidthField != null) {
            windowWidthField.setOnAction(e -> saveGameWindowSettings());
            windowWidthField.focusedProperty().addListener((o, was, focused) -> { if (!focused) saveGameWindowSettings(); });
        }
        if (windowHeightField != null) {
            windowHeightField.setOnAction(e -> saveGameWindowSettings());
            windowHeightField.focusedProperty().addListener((o, was, focused) -> { if (!focused) saveGameWindowSettings(); });
        }
        if (fullscreenCheckBox != null)
            fullscreenCheckBox.selectedProperty().addListener((o, oldValue, newValue) -> saveGameWindowSettings());
        if (launcherResolutionComboBox != null) {
            launcherResolutionComboBox.getItems().setAll("1920×1080", "1600×900", "1280×720", "1024×768", "800×600", "640×520", "Пользовательский");
            launcherResolutionComboBox.setValue("Пользовательский");
            launcherResolutionComboBox.setOnAction(e -> applyResolutionPreset(launcherResolutionComboBox, launcherWidthField, launcherHeightField));
        }
        if (skinVariantComboBox != null) {
            skinVariantComboBox.getItems().setAll("Classic (Steve)", "Slim (Alex)");
            skinVariantComboBox.setValue(SettingsManager.getSetting("skin_variant", "classic").equalsIgnoreCase("slim") ? "Slim (Alex)" : "Classic (Steve)");
            skinVariantComboBox.setOnAction(e -> {
                boolean slim = skinVariantComboBox.getValue() != null && skinVariantComboBox.getValue().startsWith("Slim");
                SettingsManager.saveSetting("skin_variant", slim ? "slim" : "classic");
                Account selected = accountComboBox == null ? null : accountComboBox.getValue();
                if (selected != null) SettingsManager.saveSetting("skin_variant_" + selected.getUuid(), slim ? "slim" : "classic");
                if (skinPreview3D != null && currentSkinImage != null) skinPreview3D.setSkin(currentSkinImage, slim);
            });
        }
        if (skinPreviewHost != null) {
            skinPreview3D = new SkinPreview3D();
            skinPreviewHost.getChildren().setAll(skinPreview3D);
        }

        File gameDir = FileUtils.getMinecraftDir();
        minecraftDirField.setText(gameDir.getAbsolutePath());
        String configuredDir = SettingsManager.getSetting("minecraft_dir", "");
        if (!configuredDir.isBlank()) minecraftDirField.setText(configuredDir);

        if (modLoaderComboBox != null) {
            modLoaderComboBox.getItems().setAll("Vanilla", "Fabric", "Forge", "NeoForge");
            modLoaderComboBox.setValue("Vanilla");
            modLoaderComboBox.setOnAction(e -> refreshLoaderVersions());
        }
        versionFilterComboBox.getItems().setAll("release", "snapshot", "old_beta", "old_alpha", "Все");
        versionFilterComboBox.setValue("release");
        versionFilterComboBox.setOnAction(e -> filterVersions());
        if (versionSearchField != null) {
            versionSearchDelay.setOnFinished(e -> { filterVersions(); if (versionComboBox.getItems().size() > 1 && !versionSearchField.getText().isBlank()) versionComboBox.show(); });
            versionSearchField.textProperty().addListener((o, a, b) -> versionSearchDelay.playFromStart());
            versionSearchField.setOnAction(e -> { versionSearchDelay.stop(); filterVersions(); versionComboBox.hide(); });
        }
        versionComboBox.valueProperty().addListener((obs, oldVersion, newVersion) -> {
            updateJavaPathPreview();
            refreshLoaderVersions();
            updateLaunchMetadata();
        });
        if (modLoaderComboBox != null) modLoaderComboBox.valueProperty().addListener((o, oldV, newV) -> updateLaunchMetadata());
        if (loaderVersionComboBox != null) loaderVersionComboBox.valueProperty().addListener((o, oldV, newV) -> updateLaunchMetadata());
        PlayTimeTracker.reconcile();

        accounts = AccountManager.loadAccounts();
        if (accounts == null) accounts = new ArrayList<>();
        if (!accounts.isEmpty()) {
            try { AccountManager.saveAccounts(accounts); }
            catch (Exception migrationError) { log("[MS] Не удалось перенести файл аккаунтов: " + rootMessage(migrationError)); }
        }
        if (accounts.isEmpty()) {
            Account starter = AuthManager.createOfflineAccount("Player");
            starter.setAutoCreated(true);
            accounts.add(starter);
            AccountManager.saveAccounts(accounts);
        }
        String activeUuid = SettingsManager.getSetting("active_account_uuid", "");
        Account activeAccount = accounts.stream().filter(a -> a.getUuid() != null && a.getUuid().equalsIgnoreCase(activeUuid)).findFirst().orElse(accounts.get(0));
        refreshAccounts(activeAccount);
        accountComboBox.valueProperty().addListener((obs, oldAccount, newAccount) -> {
            if (newAccount != null) SettingsManager.saveSetting("active_account_uuid", newAccount.getUuid());
            refreshSkinPreview(newAccount);
        });
        log("NEXUS Launcher запущен. Загружаем каталог версий Minecraft…");
        if (accounts.stream().anyMatch(a -> "msa".equalsIgnoreCase(a.getUserType()) && (a.getRefreshToken() == null || a.getRefreshToken().isBlank())))
            log("[MS] Для старых Microsoft-профилей выполните вход ещё раз один раз — тогда будет сохранён refresh token для автоматического восстановления сессии.");
        loadCustomSkin();
        refreshSkinPreview(activeAccount);
        updateJavaPathPreview();

        setupShell();
        setupAiButton();
        setupChat();
        if (launchButton != null) launchButton.setOnContextMenuRequested(e -> { openLaunchMenu(); e.consume(); });

        background.execute(() -> {
            List<GameVersion> loaded = VersionManager.getAllVersions();
            Platform.runLater(() -> {
                allVersions = loaded == null ? new ArrayList<>() : loaded;
                filterVersions();
                log("Каталог версий загружен: " + allVersions.size());
            });
        });
    }

    private long getTotalPhysicalMemoryMb() {
        try {
            com.sun.management.OperatingSystemMXBean bean =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return Math.max(1024L, bean.getTotalMemorySize() / (1024L * 1024L));
        } catch (Exception ignored) {
            return Math.max(4096L, Runtime.getRuntime().maxMemory() / (1024L * 1024L) * 2L);
        }
    }

    private void syncRamLabel() {
        if (ramLabel == null || ramSlider == null) return;
        int amount = (int)Math.round(ramSlider.getValue());
        String unit = ramUnitComboBox == null || ramUnitComboBox.getValue() == null ? "GB" : ramUnitComboBox.getValue();
        ramLabel.setText(unit.equals("MB") ? (amount * 1024) + " MB" : amount + " GB");
    }

    private int getRamMb() {
        String raw = ramInputField == null ? Integer.toString((int)ramSlider.getValue()) : ramInputField.getText().trim();
        int amount = Integer.parseInt(raw);
        String unit = ramUnitComboBox == null || ramUnitComboBox.getValue() == null ? "GB" : ramUnitComboBox.getValue();
        long mb = unit.equals("MB") ? amount : (long)amount * 1024L;
        long maxMb = (long)Math.floor(getTotalPhysicalMemoryMb() * 0.90);
        if (mb < 512 || mb > maxMb) throw new IllegalArgumentException("Укажите от 512 MB до " + maxMb + " MB (90% физической RAM).");
        return (int)mb;
    }

    private void applyRamInput() {
        if (ramInputField == null) return;
        try {
            int mb = getRamMb();
            if (ramUnitComboBox != null && "MB".equals(ramUnitComboBox.getValue())) {
                int gb = Math.max(1, (int)Math.ceil(mb / 1024.0));
                ramSlider.setValue(Math.min(ramSlider.getMax(), gb));
                ramInputField.setText(Integer.toString(mb));
                ramLabel.setText(mb + " MB");
                SettingsManager.saveSetting("ram_mb", Integer.toString(mb));
            } else {
                ramSlider.setValue(Math.min(ramSlider.getMax(), mb / 1024.0));
                ramInputField.setText(Integer.toString(mb / 1024));
                syncRamLabel();
                SettingsManager.saveSetting("ram_mb", Integer.toString(mb));
            }
        } catch (Exception ex) {
            ramInputField.setText(ramUnitComboBox != null && "MB".equals(ramUnitComboBox.getValue()) ? "4096" : Integer.toString((int)ramSlider.getValue()));
        }
    }

    private File selectedVersionGameDir() {
        GameVersion selected = versionComboBox == null ? null : versionComboBox.getValue();
        File base = new File(minecraftDirField.getText());
        if (selected == null) return base;
        return new File(base, "versions/" + selected.getId() + "/.minecraft");
    }

    private void stopLaunch() {
        for (Future<?> task : new ArrayList<>(pendingLaunches)) task.cancel(true);
        pendingLaunches.clear();
        busy.set(0);
        launchRunning = false;
        refreshLaunchButton();
        javaStatusLabel.setText("Остановлено");
        log("Операция остановлена пользователем.");
    }

    private void refreshAccounts(Account selected) {
        accountComboBox.getItems().setAll(accounts);
        if (selected != null) accountComboBox.setValue(selected);
        else if (!accounts.isEmpty()) accountComboBox.setValue(accounts.get(0));
    }

    private void filterVersions() {
        if (allVersions == null || versionFilterComboBox.getValue() == null) return;
        String filter = versionFilterComboBox.getValue();
        String query = versionSearchField == null || versionSearchField.getText() == null ? ""
                : versionSearchField.getText().trim().toLowerCase(java.util.Locale.ROOT);
        GameVersion previous = versionComboBox.getValue();
        List<GameVersion> filtered = allVersions.stream()
                .filter(v -> query.isEmpty() ? ("Все".equals(filter) || filter.equals(v.getType()))
                        : v.getId().toLowerCase(java.util.Locale.ROOT).contains(query))
                .collect(Collectors.toList());
        if (filtered.isEmpty() && !query.isEmpty()) return;
        versionComboBox.getItems().setAll(filtered);
        GameVersion pick = null;
        if (filtered.isEmpty()) {
            pick = null;
        } else if (query.isEmpty()) {
            pick = previous != null && filtered.contains(previous) ? previous : filtered.get(0);
        } else {
            for (GameVersion v : filtered) if (v.getId().equalsIgnoreCase(query)) { pick = v; break; }
            if (pick == null) for (GameVersion v : filtered) if (v.getId().toLowerCase(java.util.Locale.ROOT).startsWith(query)) { pick = v; break; }
            if (pick == null) pick = filtered.get(0);
        }
        versionComboBox.setValue(pick);
    }

    private void loadCustomSkin() {
        try (var stream = getClass().getResourceAsStream("/images/skin.png")) {
            if (stream == null || playerSkinImageView == null) return;
            Image image = new Image(stream);
            PixelReader reader = image.getPixelReader();
            if (reader != null && image.getWidth() >= 16 && image.getHeight() >= 16) {
                playerSkinImageView.setImage(new WritableImage(reader, 8, 8, 8, 8));
            }
        } catch (Exception e) {
            log("Не удалось загрузить превью скина: " + e.getMessage());
        }
    }

    private void refreshSkinPreview(Account account) {
        if (skinPreview3D == null) return;
        if (account == null || !"msa".equalsIgnoreCase(account.getUserType())) {
            skinPreview3D.setDefaultSkin();
            if (skinStatusLabel != null) skinStatusLabel.setText("Для просмотра и смены скина выберите Microsoft-аккаунт.");
            return;
        }
        if (account.getAccessToken() == null || account.getAccessToken().isBlank() || "0".equals(account.getAccessToken())) {
            if (skinStatusLabel != null) skinStatusLabel.setText("Требуется повторный вход в Microsoft для восстановления токена.");
            return;
        }
        if (skinStatusLabel != null) skinStatusLabel.setText("Загружаем скин профиля " + account.getUsername() + "…");
        background.execute(() -> {
            try {
                AuthManager.refreshIfNeeded(account, this::log);
                SkinManager.SkinInfo info = SkinManager.fetchAndCache(account);
                Platform.runLater(() -> {
                    try {
                        Image image = new Image(info.imageFile().toURI().toString(), false);
                        boolean slim = "slim".equalsIgnoreCase(info.variant());
                        currentSkinImage = image; updateAvatar();
                        skinPreview3D.setSkin(image, slim);
                        if (skinVariantComboBox != null) skinVariantComboBox.setValue(slim ? "Slim (Alex)" : "Classic (Steve)");
                        SettingsManager.saveSetting("skin_variant_" + account.getUuid(), info.variant());
                        SettingsManager.saveSetting("skin_variant", info.variant());
                        if (skinStatusLabel != null) skinStatusLabel.setText("Активный скин загружен · перетаскивай модель мышью, колесо — масштаб");
                    } catch (Exception imageError) {
                        if (skinStatusLabel != null) skinStatusLabel.setText("Не удалось отобразить скин: " + imageError.getMessage());
                    }
                });
            } catch (Exception e) {
                SkinManager.SkinInfo cached = SkinManager.cachedSkin(account,
                        SettingsManager.getSetting("skin_variant_" + account.getUuid(), "classic"));
                if (cached != null) {
                    Platform.runLater(() -> {
                        Image image = new Image(cached.imageFile().toURI().toString(), false);
                        currentSkinImage = image; updateAvatar();
                        skinPreview3D.setSkin(image, "slim".equalsIgnoreCase(cached.variant()));
                        if (skinStatusLabel != null) skinStatusLabel.setText("Показан сохранённый локальный скин · сервер сейчас недоступен");
                    });
                } else {
                    Platform.runLater(() -> { if (skinStatusLabel != null) skinStatusLabel.setText("Превью недоступно: " + rootMessage(e)); });
                }
                log("[SKIN] Не удалось обновить скин с сервера: " + rootMessage(e));
            }
        });
    }

    @FXML private void handleUploadSkin() {
        Account account = accountComboBox == null ? null : accountComboBox.getValue();
        if (account == null || !"msa".equalsIgnoreCase(account.getUserType())) {
            showAlert("Нужен Microsoft-аккаунт", "Войдите в Microsoft-аккаунт с Minecraft Java Edition, чтобы менять скин.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите PNG-скин Minecraft (64×64 или 64×32)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG skin", "*.png"));
        File selected = chooser.showOpenDialog(skinPreviewHost.getScene().getWindow());
        if (selected == null) return;
        String variant = skinVariantComboBox != null && skinVariantComboBox.getValue() != null
                && skinVariantComboBox.getValue().startsWith("Slim") ? "slim" : "classic";
        if (skinStatusLabel != null) skinStatusLabel.setText("Загружаем скин в Minecraft Services…");
        background.execute(() -> {
            try {
                AuthManager.refreshIfNeeded(account, this::log);
                SkinManager.SkinInfo info = SkinManager.upload(account, selected, variant);
                Platform.runLater(() -> {
                    Image image = new Image(info.imageFile().toURI().toString(), false);
                    currentSkinImage = image; updateAvatar();
                    skinPreview3D.setSkin(image, "slim".equals(variant));
                    SettingsManager.saveSetting("skin_variant_" + account.getUuid(), variant);
                    SettingsManager.saveSetting("skin_variant", variant);
                    if (skinStatusLabel != null) skinStatusLabel.setText("Скин установлен на аккаунте Minecraft и сохранён в локальном кэше.");
                });
                log("[SKIN] Скин успешно установлен для " + account.getUsername() + ".");
            } catch (Exception e) {
                log("[SKIN] Ошибка установки скина: " + rootMessage(e));
                Platform.runLater(() -> { if (skinStatusLabel != null) skinStatusLabel.setText("Ошибка: " + rootMessage(e)); });
            }
        });
    }

    @FXML private void handleRefreshSkin() { refreshSkinPreview(accountComboBox == null ? null : accountComboBox.getValue()); }

    private void saveGameWindowSettings() {
        if (windowWidthField == null || windowHeightField == null) return;
        int width = parseDimension(windowWidthField, 1920);
        int height = parseDimension(windowHeightField, 1080);
        SettingsManager.saveSetting("game_window_width", Integer.toString(width));
        SettingsManager.saveSetting("game_window_height", Integer.toString(height));
        SettingsManager.saveSetting("game_fullscreen", Boolean.toString(fullscreenCheckBox != null && fullscreenCheckBox.isSelected()));
    }

    private void applyResolutionPreset(ComboBox<String> preset, TextField width, TextField height) {
        if (preset == null || width == null || height == null) return;
        String value = preset.getValue();
        if (value == null || "Пользовательский".equals(value)) return;
        String[] parts = value.split("×");
        if (parts.length == 2) { width.setText(parts[0]); height.setText(parts[1]); }
    }

    @FXML private void handleApplyLauncherWindow() {
        try {
            int width = parseDimension(launcherWidthField, 1240);
            int height = parseDimension(launcherHeightField, 720);
            if (width < App.MIN_WIDTH || height < App.MIN_HEIGHT) throw new IllegalArgumentException("Минимальный размер окна лаунчера — " + App.MIN_WIDTH + "×" + App.MIN_HEIGHT + ".");
            SettingsManager.saveSetting("launcher_window_width", Integer.toString(width));
            SettingsManager.saveSetting("launcher_window_height", Integer.toString(height));
            boolean fullscreen = launcherFullscreenCheckBox != null && launcherFullscreenCheckBox.isSelected();
            SettingsManager.saveSetting("launcher_fullscreen", Boolean.toString(fullscreen));
            Stage stage = (Stage) rootPane.getScene().getWindow();
            stage.setFullScreen(fullscreen);
            if (!fullscreen) { stage.setWidth(width); stage.setHeight(height); stage.centerOnScreen(); }
            log("Настройки окна лаунчера сохранены: " + (fullscreen ? "Fullscreen" : width + "×" + height) + ".");
        } catch (Exception e) { showAlert("Размер окна лаунчера", rootMessage(e)); }
    }

    @FXML private void handleSelectDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Выберите папку Minecraft");
        File current = new File(minecraftDirField.getText());
        if (current.isDirectory()) chooser.setInitialDirectory(current);
        File selected = chooser.showDialog(minecraftDirField.getScene().getWindow());
        if (selected != null) {
            SettingsManager.saveSetting("minecraft_dir", selected.getAbsolutePath());
            minecraftDirField.setText(selected.getAbsolutePath());
            log("Папка игры изменена: " + selected.getAbsolutePath());
        }
    }

    @FXML private void handleAddOfflineAccount() {
        String name = usernameField.getText() == null ? "" : usernameField.getText().trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            showAlert("Некорректный ник", "Ник должен содержать 3–16 символов: латинские буквы, цифры или _.");
            return;
        }
        Account acc = AuthManager.createOfflineAccount(name);
        accounts.removeIf(a -> a.getUsername().equalsIgnoreCase(name));
        accounts.removeIf(Account::isAutoCreated);
        accounts.add(acc);
        AccountManager.saveAccounts(accounts);
        refreshAccounts(acc);
        usernameField.clear();
        log("Создан offline-профиль: " + name);
    }

    @FXML private void handleDeleteAccount() {
        Account selected = accountComboBox.getValue();
        if (selected == null || accounts.size() <= 1) {
            showAlert("Нельзя удалить", "В лаунчере должен оставаться хотя бы один аккаунт.");
            return;
        }
        accounts.remove(selected);
        AccountManager.saveAccounts(accounts);
        refreshAccounts(accounts.get(0));
        log("Профиль удалён: " + selected.getUsername());
    }

    private String msCode = "";

    @FXML private void handleMicrosoftLogin() {
        log("[MS] Запрашиваем код входа Microsoft…");
        showMsCode("Запрашиваем код…", "Подождите несколько секунд.");
        background.execute(() -> {
            try {
                Account acc = AuthManager.loginMicrosoft(this::log, (code, uri, expiresIn) -> Platform.runLater(() -> {
                    msCode = code;
                    showMsCode(code, "Откройте " + uri + " (браузер откроется сам), введите код и подтвердите вход. Код действует "
                            + Math.max(1, expiresIn / 60) + " мин.");
                }));
                Platform.runLater(() -> {
                    accounts.removeIf(a -> a.getUsername().equalsIgnoreCase(acc.getUsername())
                            || (a.getUuid() != null && a.getUuid().equals(acc.getUuid())));
                    accounts.removeIf(Account::isAutoCreated);
                    accounts.add(0, acc);
                    AccountManager.saveAccounts(accounts);
                    refreshAccounts(acc);
                    refreshSkinPreview(acc);
                    hideMsCode();
                    log("[MS] Вход выполнен: " + acc.getUsername());
                });
            } catch (Exception e) {
                Platform.runLater(() -> showMsCode("Вход не выполнен", rootMessage(e)));
                log("[MS] Ошибка входа Microsoft: " + rootMessage(e));
            }
        });
    }

    private void showMsCode(String code, String hint) {
        if (msCodeCard == null) return;
        msCodeLabel.setText(code);
        msCodeHint.setText(hint);
        msCodeCard.setVisible(true);
        msCodeCard.setManaged(true);
    }

    private void hideMsCode() {
        msCode = "";
        if (msCodeCard == null) return;
        msCodeCard.setVisible(false);
        msCodeCard.setManaged(false);
    }

    @FXML private void handleCopyMsCode() {
        if (msCode == null || msCode.isBlank()) return;
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(msCode);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        msCodeHint.setText("Код скопирован. Вставьте его на странице Microsoft.");
    }

    @FXML private void handleSelectJava() {
        GameVersion version = versionComboBox.getValue();
        if (version == null) {
            showAlert("Сначала выбери версию", "Выбери Minecraft, чтобы NEXUS знал, какая версия Java нужна.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите исполняемый файл Java");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java executable", "*.exe"));
        File selected = chooser.showOpenDialog(javaPathField.getScene().getWindow());
        if (selected == null) return;
        int required = JavaManager.getRequiredJavaMajor(version.getId());
        try {
            int actual = JavaManager.validateAndRememberJava(required, selected.getAbsolutePath());
            javaPathField.setText(selected.getAbsolutePath());
            javaStatusLabel.setText("Java " + actual + " выбрана");
            log("Для Minecraft " + version.getId() + " сохранена Java " + actual + ": " + selected.getAbsolutePath());
        } catch (Exception e) {
            showAlert("Java не подходит", rootMessage(e));
        }
    }

    @FXML private void handleAutoJava() {
        GameVersion version = versionComboBox.getValue();
        if (version == null) return;
        int required = JavaManager.getRequiredJavaMajor(version.getId());
        SettingsManager.saveSetting("java_custom_" + required, "");
        javaPathField.clear();
        javaStatusLabel.setText("Автоматический выбор");
        log("Для Java " + required + " включён автоматический подбор. При запуске NEXUS найдёт или установит runtime.");
    }

    private void updateJavaPathPreview() {
        if (javaPathField == null || versionComboBox == null) return;
        GameVersion version = versionComboBox.getValue();
        if (version == null) {
            javaPathField.clear();
            javaStatusLabel.setText("Ожидание версии");
            return;
        }
        int required = JavaManager.getRequiredJavaMajor(version.getId());
        String custom = SettingsManager.getSetting("java_custom_" + required, "");
        String automatic = SettingsManager.getSetting("java_" + required, "");
        String selected = custom != null && !custom.isBlank() ? custom : automatic;
        javaPathField.setText(selected == null ? "" : selected);
        javaStatusLabel.setText("Требуется Java " + required);
    }

    private record LaunchSpec(GameVersion version, Account account, String loader, String build, String server) { }

    @FXML private void handleLaunch() {
        if (launchRunning) { stopLaunch(); return; }
        GameVersion version = versionComboBox.getValue();
        Account account = accountComboBox.getValue();
        if (version == null || account == null) {
            showAlert("Не всё готово", "Выберите версию Minecraft и игровой профиль.");
            return;
        }
        String selectedLoader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
        String selectedLoaderBuild = loaderVersionComboBox == null ? null : loaderVersionComboBox.getValue();
        if (!"Vanilla".equals(selectedLoader) && (selectedLoaderBuild == null || selectedLoaderBuild.isBlank())) {
            showAlert("Не выбрана версия загрузчика", "Дождитесь загрузки списка " + selectedLoader + " и выберите доступную сборку.");
            return;
        }
        startLaunch(new LaunchSpec(version, account, selectedLoader, selectedLoaderBuild, null));
    }

    private void startLaunch(LaunchSpec spec) {
        final GameVersion version = spec.version();
        final Account account = spec.account();
        final String selectedLoader = spec.loader();
        final String selectedLoaderBuild = spec.build();
        final String serverAddress = spec.server();
        if (account != null && "msa".equalsIgnoreCase(account.getUserType())
                && (account.getAccessToken() == null || account.getAccessToken().isBlank() || "0".equals(account.getAccessToken()))) {
            showAlert("Нужно войти в Microsoft заново", "Сохранённый токен недоступен. Нажмите Microsoft login и подтвердите вход, чтобы восстановить игровую сессию.");
            return;
        }
        applyRamInput();
        int ramMb;
        try { ramMb = getRamMb(); }
        catch (Exception invalidRam) { showAlert("Некорректная RAM", invalidRam.getMessage()); return; }
        int width = parseDimension(windowWidthField, 1920);
        int height = parseDimension(windowHeightField, 1080);
        if (windowWidthField != null) SettingsManager.saveSetting("game_window_width", Integer.toString(width));
        if (windowHeightField != null) SettingsManager.saveSetting("game_window_height", Integer.toString(height));
        if (fullscreenCheckBox != null) SettingsManager.saveSetting("game_fullscreen", Boolean.toString(fullscreenCheckBox.isSelected()));
        SettingsManager.saveSetting("ram_mb", Integer.toString(ramMb));
        if (version.getUrl() == null || version.getUrl().isBlank()) {
            showAlert("Нет каталога версий", "Не удалось получить данные Mojang. Проверьте подключение к интернету и обновите список версий.");
            return;
        }
        RecentLaunches.record(new RecentLaunches.Entry(version.getId(), selectedLoader,
                "Vanilla".equals(selectedLoader) || selectedLoaderBuild == null ? "" : selectedLoaderBuild,
                account.getUuid(), account.getUsername()));
        onRecentChanged.run();
        beginBusy();
        refreshLaunchButton();
        final boolean[] prepared = {false};
        final GameSessions.Session[] session = {null};
        final String[] failure = {null};
        final String launchKey = launchKey(account, serverAddress);
        if (launchKey != null) launchingKeys.add(launchKey);
        final java.util.concurrent.atomic.AtomicBoolean gateHeld = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Future<?>[] self = {null};
        javaStatusLabel.setText("Подготовка…");
        log("Подготовка к запуску " + version.getId() + " для " + account.getUsername());
        Runnable job = () -> {
            try {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Остановлено пользователем");
                if (!prepGate.tryAcquire()) {
                    Platform.runLater(() -> javaStatusLabel.setText("В очереди: " + account.getUsername()));
                    log("Запуск " + account.getUsername() + " поставлен в очередь: заканчивается подготовка другой игры.");
                    prepGate.acquire();
                }
                gateHeld.set(true);
                AuthManager.refreshIfNeeded(account, this::log);
                AccountManager.saveAccounts(accounts);
                int requiredJava = JavaManager.getRequiredJavaMajor(version.getId());
                Platform.runLater(() -> javaStatusLabel.setText("Java " + requiredJava));
                String javaPath = JavaManager.findOrDownloadJava(requiredJava, this::log);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Остановлено пользователем");
                Platform.runLater(() -> { javaStatusLabel.setText("Java " + requiredJava + " готова"); javaPathField.setText(javaPath); });
                GameLauncher.launch(version, account, ramMb, javaPath, new File(minecraftDirField.getText()), width, height,
                        fullscreenCheckBox != null && fullscreenCheckBox.isSelected(), selectedLoader, selectedLoaderBuild,
                        serverAddress, message -> {
                            log(message);
                            if (message != null && message.startsWith("[GAME")) GameSessions.observe(session[0], message);
                        }, () -> {
                            session[0] = GameSessions.add(account.getUuid(), account.getUsername(), version.getId(), selectedLoader, serverAddress);
                            session[0].attach(GameLauncher.currentProcess());
                            session[0].attachLink(GameLauncher.currentLink());
                            prepared[0] = true;
                            pendingLaunches.remove(self[0]);
                            if (launchKey != null) launchingKeys.remove(launchKey);
                            if (gateHeld.getAndSet(false)) prepGate.release();
                            endBusy();
                            Platform.runLater(this::refreshLaunchButton);
                        });
                analyzeCrashIfAny(version.getId(), selectedLoader, ramMb);
            } catch (InterruptedException | CancellationException e) {
                Thread.currentThread().interrupt();
                log("Загрузка/запуск отменены.");
            } catch (Exception e) {
                log("Ошибка запуска: " + rootMessage(e));
                failure[0] = rootMessage(e);
                Platform.runLater(() -> javaStatusLabel.setText("Ошибка запуска"));
            } finally {
                GameSessions.remove(session[0]);
                pendingLaunches.remove(self[0]);
                if (launchKey != null) launchingKeys.remove(launchKey);
                if (gateHeld.getAndSet(false)) prepGate.release();
                if (!prepared[0]) endBusy();
                Platform.runLater(() -> { refreshLaunchButton(); onLaunchFinished(failure[0]); });
            }
        };
        java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(job, null);
        self[0] = task;
        pendingLaunches.add(task);
        launchPool.execute(task);
    }

    private void refreshLoaderVersions() {
        if (loaderVersionComboBox == null || modLoaderComboBox == null || versionComboBox == null) return;
        String loader = modLoaderComboBox.getValue();
        GameVersion mc = versionComboBox.getValue();
        final int generation = ++loaderRefreshGeneration;
        loaderVersionComboBox.getItems().clear();
        boolean vanilla = loader == null || "Vanilla".equals(loader);
        loaderVersionComboBox.setDisable(vanilla || mc == null);
        loaderVersionComboBox.setPromptText(vanilla ? "Без загрузчика" : "Загрузка версий…");
        if (vanilla || mc == null) { pendingLoaderBuild = null; afterLoaderReady = null; return; }
        String selectedLoader = loader;
        String selectedMc = mc.getId();
        background.execute(() -> {
            try {
                List<String> allBuilds = ModLoaderManager.getVersions(selectedLoader, selectedMc);
                Platform.runLater(() -> {
                    if (generation != loaderRefreshGeneration) return;
                    if (!selectedLoader.equals(modLoaderComboBox.getValue()) || versionComboBox.getValue() == null
                            || !selectedMc.equals(versionComboBox.getValue().getId())) return;
                    String wanted = pendingLoaderBuild;
                    pendingLoaderBuild = null;
                    List<String> builds = new ArrayList<>(allBuilds);
                    if (wanted != null && !builds.isEmpty() && !builds.contains(wanted)) builds.add(0, wanted);
                    loaderVersionComboBox.getItems().setAll(builds);
                    loaderVersionComboBox.setDisable(false);
                    Runnable then = afterLoaderReady;
                    afterLoaderReady = null;
                    if (!builds.isEmpty()) {
                        loaderVersionComboBox.setValue(wanted != null && builds.contains(wanted) ? wanted : builds.get(0));
                        loaderVersionComboBox.setPromptText("Версия загрузчика");
                        log("Доступно сборок " + selectedLoader + " для Minecraft " + selectedMc + ": " + builds.size());
                        if (then != null) then.run();
                    } else {
                        loaderVersionComboBox.setPromptText("Нет совместимых сборок");
                        loaderVersionComboBox.setDisable(true);
                        log("Для " + selectedLoader + " и Minecraft " + selectedMc + " совместимые сборки не найдены.");
                        if (then != null) showAlert("Не удалось запустить", "Для " + selectedLoader + " и Minecraft " + selectedMc + " совместимые сборки не найдены.");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (generation != loaderRefreshGeneration) return;
                    pendingLoaderBuild = null;
                    Runnable then = afterLoaderReady;
                    afterLoaderReady = null;
                    if (selectedLoader.equals(modLoaderComboBox.getValue())) {
                        loaderVersionComboBox.setPromptText("Не удалось загрузить");
                        loaderVersionComboBox.setDisable(true);
                        log("Не удалось получить версии " + selectedLoader + ": " + rootMessage(ex));
                        if (then != null) showAlert("Не удалось запустить", "Не удалось получить версии " + selectedLoader + ": " + rootMessage(ex));
                    }
                });
            }
        });
    }

    private int parseDimension(TextField field, int fallback) {
        try { int v = Integer.parseInt(field.getText().trim()); return Math.max(320, Math.min(7680, v)); }
        catch (Exception e) { return fallback; }
    }

    private String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private void log(String message) {
        if (message == null) return;
        boolean microsoft = message.startsWith("[MS]");
        boolean game = message.startsWith("[GAME");
        String cleanMessage = (microsoft || game) ? message.substring(message.indexOf(']') + 1).trim() : message;
        if (game) {
            String who = message.substring(5, message.indexOf(']')).trim();
            cleanMessage = formatGameLine(who, cleanMessage);
        }
        pendingLogs.add(new PendingLog(microsoft, game, cleanMessage));
        scheduleLogFlush();
    }

    private static final java.util.regex.Pattern COLOR_CODES = java.util.regex.Pattern.compile("\u00a7[0-9a-fk-orA-FK-OR]");
    private static final java.util.regex.Pattern CHAT_LINE = java.util.regex.Pattern.compile("^(?:\\[(\\d{2}:\\d{2}:\\d{2})\\]\\s*)?.*?\\[CHAT\\]\\s*(.*)$");

    private static String formatGameLine(String nick, String line) {
        String clean = COLOR_CODES.matcher(line).replaceAll("");
        String tag = nick == null || nick.isEmpty() ? "" : "[" + nick + "] ";
        java.util.regex.Matcher chat = CHAT_LINE.matcher(clean);
        if (chat.matches()) {
            String time = chat.group(1) == null ? "" : chat.group(1) + " ";
            return tag + time + "ЧАТ: " + chat.group(2).trim();
        }
        return tag + clean;
    }

    private void scheduleLogFlush() {
        if (logFlushScheduled.compareAndSet(false, true)) Platform.runLater(this::flushPendingLogs);
    }

    private void flushPendingLogs() {
        StringBuilder launcher = new StringBuilder();
        StringBuilder game = new StringBuilder();
        boolean showGameTab = false;
        PendingLog item;
        int drained = 0;
        while (drained++ < 1200 && (item = pendingLogs.poll()) != null) {
            StringBuilder target = item.game() ? game : launcher;
            target.append("› ").append(item.message()).append(System.lineSeparator());
            if (!item.game()) addEvent(item.message());
            if (item.game()) showGameTab = true;
        }
        appendLogBatch(launcherGameLogs, launcher);
        appendLogBatch(monitorLogs, game);
        if (showGameTab && logsTabPane != null) logsTabPane.getSelectionModel().select(1);
        logFlushScheduled.set(false);
        if (!pendingLogs.isEmpty()) scheduleLogFlush();
    }

    private void appendLogBatch(TextArea target, StringBuilder batch) {
        if (target == null || batch.length() == 0) return;
        target.appendText(batch.toString());
        int length = target.getLength();
        if (length > 40000) target.deleteText(0, length - 30000);
        target.setScrollTop(Double.MAX_VALUE);
    }

    @FXML private void openMinecraftFolder() { openInstanceFolder(null, false); }
    @FXML private void openModsFolder() { openInstanceFolder("mods", true); }
    @FXML private void openResourcePacksFolder() { openInstanceFolder("resourcepacks", false); }
    @FXML private void openShadersFolder() { openInstanceFolder("shaderpacks", false); }
    @FXML private void openScreenshotsFolder() { openInstanceFolder("screenshots", false); }

    private void openInstanceFolder(String subFolder, boolean needsLoader) {
        GameVersion selected = versionComboBox == null ? null : versionComboBox.getValue();
        String loader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
        String build = loaderVersionComboBox == null ? null : loaderVersionComboBox.getValue();
        boolean vanilla = "Vanilla".equalsIgnoreCase(loader);
        if (needsLoader && vanilla) {
            showAlert("Моды", "Папка модов открывается для Fabric, Forge или NeoForge. Выберите загрузчик в окне «Версия игры».");
            return;
        }
        if (selected == null) {
            showAlert("Версия не выбрана", "Сначала выберите версию Minecraft.");
            return;
        }
        File base = new File(minecraftDirField.getText());
        String mcId = selected.getId();
        background.execute(() -> {
            try {
                File gameDir;
                if (vanilla) {
                    gameDir = new File(base, "versions/" + mcId + "/.minecraft");
                } else {
                    String profile = ModLoaderManager.findInstalledProfile(loader, mcId, build, base);
                    if (profile == null) {
                        String what = loader + (build == null || build.isBlank() ? "" : " " + build);
                        Platform.runLater(() -> showAlert(loader + " не установлен",
                                what + " ещё не установлен для Minecraft " + mcId + ". Нажмите «Играть» — NEXUS установит загрузчик, "
                                        + "и папка станет доступна."));
                        return;
                    }
                    gameDir = new File(base, "versions/" + profile + "/.minecraft");
                }
                File target = subFolder == null ? gameDir : new File(gameDir, subFolder);
                Platform.runLater(() -> FileUtils.openDirectory(target));
            } catch (Exception e) {
                Platform.runLater(() -> showAlert("Не удалось открыть папку", rootMessage(e)));
            }
        });
    }

    private static final PseudoClass MAXIMIZED = PseudoClass.getPseudoClass("maximized");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final String AUTOSTART_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    @FXML private StackPane stagePane, modalLayer, newsImageHost, settingsPages;
    @FXML private StackPane appContent, bgLayer;
    @FXML private HBox newsPanel, settingsDialog, uninstallTip, loaderRail, brandRow, railFooter;
    @FXML private VBox railPane, navBox, railLoaderBox, versionsDialog, accountDialog, skinsDialog, newsDialog, closeDialog, uninstallDialog;
    @FXML private CheckBox rememberCloseCheckBox, confirmUninstallCheckBox;
    @FXML private Label uninstallListLabel;
    @FXML private Button confirmUninstallButton;
    @FXML private ComboBox<String> closeBehaviorComboBox;
    @FXML private ImageView heroImage, heroLogo, newsImage;
    @FXML private Label heroSubtitle, launchMetaLabel, launchStatusLabel, playtimeLabel, backgroundPathLabel, aboutVersionLabel, newsDialogTitle;
    @FXML private Button accountButton, menuButton, newsOpenButton, versionButton, maximizeButton, pinButton;
    @FXML private Label brandName, railTagline;
    @FXML private ListView<NewsService.Entry> newsListView;
    @FXML private ToggleGroup newsTabGroup, settingsNavGroup;
    @FXML private ToggleButton tabNews, tabEvents;
    @FXML private CheckBox autostartCheckBox;
    @FXML private Region settingsTitleIcon;

    private final Map<String, StackPane> loaderTiles = new LinkedHashMap<>();
    private final List<Node> dialogNodes = new ArrayList<>();
    private final ObservableList<NewsService.Entry> newsItems = FXCollections.observableArrayList();
    private final List<NewsService.Entry> eventEntries = new ArrayList<>();
    private List<NewsService.Entry> mojangNews = new ArrayList<>();
    private boolean mojangLoaded;
    private NewsService.Entry openedNews;
    private Image defaultHero;

    private void setupShell() {
        loadBrandLogo();
        installIcons();
        setupLoaderRail();
        setupRail();
        setupPlayerChip();
        setupHero();
        setupNews();
        setupSettingsDialog();
        setupWindowChrome();
        if (accountComboBox != null) {
            accountComboBox.valueProperty().addListener((o, a, b) -> updateAccountButton());
            updateAccountButton();
        }
        javaStatusLabel.textProperty().addListener((o, a, b) -> syncLaunchStatus());
        updateLaunchMetadata();
        refreshLaunchButton();
        startPlaytimeDisplay();
    }

    private void installIcons() {
        for (Node node : rootPane.lookupAll(".icon-button")) {
            if (!(node instanceof Labeled labeled) || !(labeled.getUserData() instanceof String name)) continue;
            List<String> css = labeled.getStyleClass();
            double size = css.contains("play-menu") ? 22
                    : css.contains("win-btn") ? 18 : css.contains("rail-pin") ? 16
                    : css.contains("dialog-close") || css.contains("dialog-close-inline") ? 16 : 18;
            labeled.setGraphic(Icons.icon(name, size));
        }
        if (settingsTitleIcon != null && settingsTitleIcon.getParent() instanceof javafx.scene.layout.Pane pane) {
            int index = pane.getChildren().indexOf(settingsTitleIcon);
            if (index >= 0) pane.getChildren().set(index, Icons.icon("gear", 22));
        }
    }

    private void loadBrandLogo() {
        if (brandMark == null) return;
        brandMark.getStyleClass().add("brand-logo");
        brandMark.getChildren().setAll(Icons.icon("logo-n", 30));
    }

    private void setupLoaderRail() {
        String[] loaders = {"Vanilla", "Fabric", "Forge", "NeoForge"};
        for (String name : loaders) {
            final String loader = name;
            StackPane tile = new StackPane();
            tile.getStyleClass().addAll("loader-tile", "loader-" + loader.toLowerCase(java.util.Locale.ROOT));
            Node glyph = "Vanilla".equals(loader) ? cubeGlyph() : Icons.loader(loader, 20);
            if (glyph != null) tile.getChildren().add(glyph);
            Tooltip.install(tile, new Tooltip(loader));
            tile.setOnMouseClicked(e -> { if (modLoaderComboBox != null) modLoaderComboBox.setValue(loader); });
            loaderTiles.put(loader, tile);
            loaderRail.getChildren().add(tile);
        }
        if (modLoaderComboBox != null) modLoaderComboBox.valueProperty().addListener((o, a, b) -> syncLoaderRail());
        syncLoaderRail();
    }

    private void syncLoaderRail() {
        String current = modLoaderComboBox == null ? null : modLoaderComboBox.getValue();
        loaderTiles.forEach((name, tile) -> tile.pseudoClassStateChanged(ACTIVE, name.equals(current)));
    }

    private Node cubeGlyph() {
        Polygon left = new Polygon(9, 17, 25, 25, 25, 42, 9, 34);
        left.setFill(Color.web("#8a5a2e"));
        Polygon right = new Polygon(41, 17, 25, 25, 25, 42, 41, 34);
        right.setFill(Color.web("#6a4322"));
        Polygon leftGrass = new Polygon(9, 17, 25, 25, 25, 29.5, 9, 21.5);
        leftGrass.setFill(Color.web("#5da23a"));
        Polygon rightGrass = new Polygon(41, 17, 25, 25, 25, 29.5, 41, 21.5);
        rightGrass.setFill(Color.web("#4a8a2d"));
        Polygon top = new Polygon(25, 9, 41, 17, 25, 25, 9, 17);
        top.setFill(Color.web("#86d256"));
        Group cube = new Group(left, right, leftGrass, rightGrass, top);
        cube.setScaleX(0.56);
        cube.setScaleY(0.56);
        cube.setMouseTransparent(true);
        return cube;
    }

    private void setupHero() {
        theme = Themes.byId(SettingsManager.getSetting("theme", Themes.DEFAULT_ID));
        bgLayer.getChildren().add(0, themeArt);
        URL heroUrl = MainController.class.getResource("/images/hero.jpg");
        if (heroUrl != null) defaultHero = new Image(heroUrl.toExternalForm());
        heroImage.setManaged(false);
        heroImage.fitWidthProperty().bind(bgLayer.widthProperty());
        heroImage.fitHeightProperty().bind(bgLayer.heightProperty());
        bgLayer.widthProperty().addListener((o, a, b) -> updateHeroViewport());
        bgLayer.heightProperty().addListener((o, a, b) -> updateHeroViewport());
        bgClip.widthProperty().bind(bgLayer.widthProperty());
        bgClip.heightProperty().bind(bgLayer.heightProperty());
        bgClip.setArcWidth(58);
        bgClip.setArcHeight(58);
        bgLayer.setClip(bgClip);
        buildThemeGrid();
        applyTheme(theme, false);
    }

    private final Rectangle bgClip = new Rectangle();

    private void applyTheme(Themes.Theme next, boolean save) {
        theme = next;
        if (save) SettingsManager.saveSetting("theme", next.id());
        rootPane.setStyle(next.style());
        themeArt.setTheme(next);
        applyHeroBackground();
        for (java.util.Map.Entry<String, VBox> card : themeCards.entrySet()) {
            boolean selected = card.getKey().equals(next.id());
            card.getValue().getStyleClass().remove("theme-card-selected");
            if (selected) card.getValue().getStyleClass().add("theme-card-selected");
        }
    }

    private void buildThemeGrid() {
        if (themeGrid == null) return;
        themeGrid.getChildren().clear();
        themeCards.clear();
        for (Themes.Theme item : Themes.ALL) {
            StackPane thumb = new StackPane();
            thumb.setPrefSize(160, 92);
            thumb.setMinSize(160, 92);
            thumb.setMaxSize(160, 92);
            Rectangle clip = new Rectangle(160, 92);
            clip.setArcWidth(18);
            clip.setArcHeight(18);
            thumb.setClip(clip);
            thumb.setStyle("-fx-background-color:" + item.style().replaceAll(".*-nx-stage:([^;]+);.*", "$1") + ";");
            ImageView picture = new ImageView();
            picture.setFitWidth(160);
            picture.setFitHeight(92);
            picture.setPreserveRatio(false);
            thumb.getChildren().add(picture);
            background.execute(() -> {
                javafx.scene.image.WritableImage img = ThemeArt.toFx(com.launcher.ui.ThemeRenderer.render(item, 320, 184));
                Platform.runLater(() -> picture.setImage(img));
            });
            Region button = new Region();
            button.setPrefSize(54, 15);
            button.setMinSize(54, 15);
            button.setMaxSize(54, 15);
            button.setStyle("-fx-background-color:" + item.accent() + ";-fx-background-radius:9;");
            StackPane.setAlignment(button, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(button, new Insets(0, 8, 8, 0));
            thumb.getChildren().add(button);
            Label name = new Label(item.name());
            name.getStyleClass().add("theme-name");
            VBox card = new VBox(7, thumb, name);
            card.getStyleClass().add("theme-card");
            card.setPrefWidth(176);
            card.setOnMouseClicked(e -> applyTheme(item, true));
            themeCards.put(item.id(), card);
            themeGrid.getChildren().add(card);
        }
    }

    private void applyHeroBackground() {
        Image image = null;
        String label = "Фон темы";
        String custom = SettingsManager.getSetting("launcher_background", "");
        if (custom != null && !custom.isBlank()) {
            File file = new File(custom);
            if (file.isFile()) {
                Image candidate = new Image(file.toURI().toString(), 2560, 0, true, true);
                if (!candidate.isError()) { image = candidate; label = file.getName(); }
            }
        }
        heroImage.setImage(image);
        heroImage.setVisible(image != null);
        heroImage.setEffect(null);
        if (backgroundPathLabel != null) backgroundPathLabel.setText(label);
        updateHeroViewport();
    }

    private void updateHeroViewport() {
        Image image = heroImage.getImage();
        double w = bgLayer.getWidth(), h = bgLayer.getHeight();
        if (image == null || image.getWidth() <= 0 || w <= 0 || h <= 0) return;
        double imageRatio = image.getWidth() / image.getHeight(), stageRatio = w / h;
        if (stageRatio > imageRatio) {
            double vh = image.getWidth() / stageRatio;
            heroImage.setViewport(new Rectangle2D(0, (image.getHeight() - vh) / 2, image.getWidth(), vh));
        } else {
            double vw = image.getHeight() * stageRatio;
            heroImage.setViewport(new Rectangle2D((image.getWidth() - vw) / 2, 0, vw, image.getHeight()));
        }
    }

    @FXML private void handleChooseBackground() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите фоновое изображение");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Изображения", "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif"));
        File selected = chooser.showOpenDialog(rootPane.getScene().getWindow());
        if (selected == null) return;
        Image test = new Image(selected.toURI().toString(), 2560, 0, true, true);
        if (test.isError()) { showAlert("Не удалось открыть изображение", rootMessage(test.getException() == null ? new RuntimeException("Файл повреждён") : test.getException())); return; }
        SettingsManager.saveSetting("launcher_background", selected.getAbsolutePath());
        applyHeroBackground();
    }

    @FXML private void handleResetBackground() {
        SettingsManager.saveSetting("launcher_background", "");
        applyHeroBackground();
    }

    private void setupNews() {
        Rectangle panelClip = new Rectangle();
        panelClip.setArcWidth(40);
        panelClip.setArcHeight(40);
        panelClip.widthProperty().bind(newsPanel.widthProperty());
        panelClip.heightProperty().bind(newsPanel.heightProperty());
        newsPanel.setClip(panelClip);
        newsImageHost.setClip(new Rectangle(236, 152));
        newsPanel.prefWidthProperty().bind(Bindings.max(430, Bindings.min(720, stagePane.widthProperty().subtract(400))));

        newsListView.setItems(newsItems);
        newsListView.setCellFactory(v -> new ListCell<>() {
            private final Label title = new Label();
            private final Label meta = new Label();
            private final Region spacer = new Region();
            private final HBox box = new HBox(8, title, spacer, meta);
            {
                title.getStyleClass().add("news-title");
                meta.getStyleClass().add("news-meta");
                title.setMinWidth(0);
                HBox.setHgrow(spacer, Priority.ALWAYS);
                box.setAlignment(Pos.CENTER_LEFT);
                setPrefWidth(0);
            }
            @Override protected void updateItem(NewsService.Entry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                title.setText(item.title());
                meta.setText(item.meta());
                setGraphic(box);
            }
        });
        newsListView.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showNewsPreview(b));
        newsListView.setOnMouseClicked(e -> {
            Node target = e.getTarget() instanceof Node n ? n : null;
            while (target != null && !(target instanceof ListCell<?>)) target = target.getParent();
            if (target instanceof ListCell<?> cell && !cell.isEmpty()) {
                NewsService.Entry selected = newsListView.getSelectionModel().getSelectedItem();
                if (selected != null) openNews(selected);
            }
        });
        newsTabGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            showNewsTab();
        });

        showNewsTab();
        background.execute(() -> {
            List<NewsService.Entry> loaded = NewsService.fetchMojangNews();
            Platform.runLater(() -> {
                mojangNews = loaded;
                mojangLoaded = true;
                showNewsTab();
            });
        });
    }

    private void showNewsTab() {
        List<NewsService.Entry> source = mojangNews;
        String placeholder = mojangLoaded ? "Не удалось загрузить новости Mojang" : "Загружаем новости…";
        newsListView.setPlaceholder(new Label(placeholder));
        newsItems.setAll(source);
        if (source.isEmpty()) showNewsPreview(null);
        else newsListView.getSelectionModel().select(0);
    }

    private void showNewsPreview(NewsService.Entry entry) {
        Image image = defaultHero;
        if (entry != null && entry.imageUrl() != null) {
            Image remote = new Image(entry.imageUrl(), 0, 152, true, true, true);
            remote.errorProperty().addListener((o, was, failed) -> { if (failed && defaultHero != null) newsImage.setImage(defaultHero); });
            image = remote;
        }
        newsImage.setImage(image);
    }

    private void addEvent(String message) {
    }

    private void openNews(NewsService.Entry entry) {
        openedNews = entry;
        newsDialogTitle.setText(entry.title());
        newsDialogText.setText(entry.body() == null ? entry.title() : entry.body());
        boolean hasLink = entry.linkUrl() != null;
        newsOpenButton.setVisible(hasLink);
        newsOpenButton.setManaged(hasLink);
        showDialog(newsDialog);
    }

    @FXML private TextArea newsDialogText;

    @FXML private void openNewsLink() {
        if (openedNews == null || openedNews.linkUrl() == null) return;
        String link = openedNews.linkUrl();
        background.execute(() -> {
            try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(link)); }
            catch (Exception e) { log("Не удалось открыть ссылку: " + rootMessage(e)); }
        });
    }

    private final Label playerName = new Label("Войти");
    private final javafx.scene.shape.Circle playerDot = new javafx.scene.shape.Circle(4);
    private final javafx.scene.shape.Circle avatarCircle = new javafx.scene.shape.Circle(17);
    private final Label avatarLetter = new Label("P");
    private final ImageView avatarFace = new ImageView();

    private void setupPlayerChip() {
        playerName.getStyleClass().add("player-name");
        playerDot.getStyleClass().add("player-dot");
        avatarCircle.setFill(Color.web("#3a3a40"));
        avatarLetter.setStyle("-fx-text-fill:white;-fx-font-weight:800;-fx-font-size:13px;");
        avatarFace.setFitWidth(34);
        avatarFace.setFitHeight(34);
        avatarFace.setSmooth(true);
        avatarFace.setClip(new javafx.scene.shape.Circle(17, 17, 17));
        avatarFace.setVisible(false);
        StackPane avatar = new StackPane(avatarCircle, avatarLetter, avatarFace);
        avatar.setMinSize(34, 34);
        avatar.setMaxSize(34, 34);
        HBox box = new HBox(9, avatar, playerName, playerDot);
        box.setAlignment(Pos.CENTER_LEFT);
        accountButton.setText(null);
        accountButton.setGraphic(box);
        accountButton.setTooltip(new Tooltip("Профиль и скин"));
    }

    private void updateAccountButton() {
        Account account = accountComboBox.getValue();
        String name = account == null || account.getUsername() == null ? "Войти" : account.getUsername();
        playerName.setText(name);
        avatarLetter.setText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase());
        updateAvatar();
    }

    private void updateAvatar() {
        Image skin = currentSkinImage;
        Account account = accountComboBox == null ? null : accountComboBox.getValue();
        boolean usable = skin != null && !skin.isError() && skin.getWidth() >= 64 && account != null && "msa".equalsIgnoreCase(account.getUserType());
        avatarFace.setVisible(usable);
        avatarLetter.setVisible(!usable);
        if (!usable) return;
        final int k = 16;
        javafx.scene.image.WritableImage face = new javafx.scene.image.WritableImage(8 * k, 8 * k);
        PixelReader reader = skin.getPixelReader();
        if (reader == null) return;
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            int base = reader.getArgb(8 + x, 8 + y), hat = reader.getArgb(40 + x, 8 + y);
            int argb = (hat >>> 24) > 0 ? hat : base;
            for (int dy = 0; dy < k; dy++) for (int dx = 0; dx < k; dx++) face.getPixelWriter().setArgb(x * k + dx, y * k + dy, argb);
        }
        avatarFace.setImage(face);
    }

    private void refreshLaunchButton() {
        if (launchButton == null) return;
        launchButton.setText(launchRunning ? "Стоп" : "Играть");
        launchButton.setGraphic(Icons.icon(launchRunning ? "stop" : "play", 18));
        launchButton.setDisable(false);
        launchButton.getStyleClass().remove("stopping");
        if (launchRunning) launchButton.getStyleClass().add("stopping");
        playerDot.getStyleClass().remove("busy");
        if (launchRunning) playerDot.getStyleClass().add("busy");
        syncLaunchStatus();
    }

    private void syncLaunchStatus() {
        if (launchStatusLabel == null || javaStatusLabel == null) return;
        String text = javaStatusLabel.getText();
        boolean show = text != null && (launchRunning || text.startsWith("Ошибка") || text.startsWith("Остановлено"));
        int games = GameLauncher.runningCount();
        launchStatusLabel.setText(show ? text : games > 0 ? "Запущено игр: " + games : "");
    }

    private void updateLaunchMetadata() {
        if (launchMetaLabel == null) return;
        boolean hasVersion = versionComboBox != null && versionComboBox.getValue() != null;
        String mc = hasVersion ? versionComboBox.getValue().getId() : "не выбрана";
        String loader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
        String build = loaderVersionComboBox == null ? null : loaderVersionComboBox.getValue();
        boolean vanilla = "Vanilla".equalsIgnoreCase(loader);
        launchMetaLabel.setText(vanilla ? "" : loader + (build == null || build.isBlank() ? "" : " " + build));
        if (versionButton != null) {
            versionButton.setText("Версия: " + mc);
            if (versionButton.getGraphic() == null) versionButton.setGraphic(Icons.icon("chevron-down", 16));
        }
    }

    @FXML private void openVersionMenu() {
        ContextMenu menu = new ContextMenu();
        List<GameVersion> items = versionComboBox == null ? List.of() : new ArrayList<>(versionComboBox.getItems());
        for (int i = 0; i < Math.min(8, items.size()); i++) {
            GameVersion v = items.get(i);
            menu.getItems().add(menuItem(v.getId(), () -> versionComboBox.setValue(v)));
        }
        if (!items.isEmpty()) menu.getItems().add(new SeparatorMenuItem());
        menu.getItems().add(menuItem("Все версии и загрузчики…", this::openVersions));
        menu.show(versionButton, Side.BOTTOM, 0, 6);
    }

    private void startPlaytimeDisplay() {
        if (playtimeTimeline != null) playtimeTimeline.stop();
        playtimeLabel.setText(PlayTimeTracker.statusText());
        playtimeTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> { playtimeLabel.setText(PlayTimeTracker.statusText()); syncLaunchStatus(); }));
        playtimeTimeline.setCycleCount(Timeline.INDEFINITE);
        playtimeTimeline.play();
    }

    @FXML private void openLaunchMenu() {
        ContextMenu menu = new ContextMenu();
        List<RecentLaunches.Entry> recent = RecentLaunches.load();
        if (!recent.isEmpty()) {
            MenuItem header = new MenuItem("Последние запуски");
            header.setDisable(true);
            menu.getItems().add(header);
            for (int i = 0; i < Math.min(3, recent.size()); i++) {
                RecentLaunches.Entry entry = recent.get(i);
                menu.getItems().add(menuItem(entry.label(), () -> launchRecent(entry)));
            }
            menu.getItems().add(new SeparatorMenuItem());
        }
        menu.getItems().addAll(
                menuItem("Папка игры", this::openMinecraftFolder),
                menuItem("Папка модов", this::openModsFolder),
                menuItem("Ресурспаки", this::openResourcePacksFolder),
                menuItem("Шейдеры", this::openShadersFolder),
                menuItem("Скриншоты", this::openScreenshotsFolder));
        menu.show(menuButton, Side.TOP, 0, -8);
    }

    private MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private void showDialog(Node dialog) {
        if (dialogNodes.isEmpty()) dialogNodes.addAll(List.of(settingsDialog, versionsDialog, accountDialog, skinsDialog, newsDialog, closeDialog, uninstallDialog));
        for (Node extra : new Node[]{modsDialog, serversDialog, crashDialog, aiDialog, chatDialog}) {
            if (extra != null && !dialogNodes.contains(extra)) dialogNodes.add(extra);
        }
        for (Node node : dialogNodes) {
            boolean on = node == dialog;
            node.setVisible(on);
            node.setManaged(on);
        }
        setActiveNav(dialog == versionsDialog ? "versions" : dialog == modsDialog ? "mods" : dialog == serversDialog ? "servers"
                : dialog == chatDialog ? "chats" : dialog == settingsDialog ? "settings" : "home");
        collapseRail(true);
        modalLayer.setManaged(true);
        modalLayer.setVisible(true);
        FadeTransition fade = new FadeTransition(Duration.millis(140), modalLayer);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
        appContent.setEffect(new GaussianBlur(14));
    }

    @FXML private void closeModal() {
        if (!modalLayer.isVisible()) return;
        modalLayer.setVisible(false);
        modalLayer.setManaged(false);
        appContent.setEffect(null);
        setActiveNav("home");
    }

    @FXML private void scrimClicked(MouseEvent event) {
        if (event.getTarget() == modalLayer) closeModal();
    }

    @FXML private void dismissUninstallTip() {
        if (uninstallTip == null) return;
        uninstallTip.setVisible(false);
        uninstallTip.setManaged(false);
        SettingsManager.saveSetting("uninstall_tip_hidden", "true");
    }

    @FXML private void openSettings() { showDialog(settingsDialog); }
    @FXML private void openAccounts() { showDialog(accountDialog); }
    @FXML private void openSkins() { showDialog(skinsDialog); }
    @FXML private void openVersions() { showDialog(versionsDialog); }

    private ModsDialog modsDialog;

    @FXML private void openMods() {
        if (modsDialog == null) {
            modsDialog = new ModsDialog(background, this::closeModal, this::log, this::selectInstalledPack);
            modalLayer.getChildren().add(modsDialog);
        }
        GameVersion version = versionComboBox == null ? null : versionComboBox.getValue();
        String loader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
        String build = loaderVersionComboBox == null ? null : loaderVersionComboBox.getValue();
        showDialog(modsDialog);
        modsDialog.open(version == null ? null : version.getId(), loader, build, new File(minecraftDirField.getText()));
    }
    @FXML private void openLogs() { selectSettingsPage(3); showDialog(settingsDialog); }

    private static final String[][] JVM_PRESETS = {
            {"Свои аргументы", ""},
            {"Лёгкая оптимизация (G1)", "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+DisableExplicitGC -XX:+AlwaysPreTouch"},
            {"Плавность (Aikar)", "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1"},
            {"Java 21: ZGC", "-XX:+UseZGC -XX:+ZGenerational"}
    };
    private static final String[] SESSION_ACTIONS = {"Спрашивать каждый раз", "Переключать запущенную игру на сервер (без перезапуска)", "Всегда открывать отдельную сессию"};
    private static final String[] SESSION_KEYS = {"ask", "join", "separate"};

    private void setupJvmAndSessionSettings() {
        if (jvmArgsField != null) {
            jvmArgsField.setText(SettingsManager.getSetting("jvm_args", ""));
            Runnable save = () -> SettingsManager.saveSetting("jvm_args", jvmArgsField.getText().trim());
            jvmArgsField.setOnAction(e -> save.run());
            jvmArgsField.focusedProperty().addListener((o, was, focused) -> { if (!focused) save.run(); });
        }
        if (jvmPresetComboBox != null) {
            for (String[] preset : JVM_PRESETS) jvmPresetComboBox.getItems().add(preset[0]);
            jvmPresetComboBox.setValue(JVM_PRESETS[0][0]);
            jvmPresetComboBox.setOnAction(e -> {
                int i = jvmPresetComboBox.getItems().indexOf(jvmPresetComboBox.getValue());
                if (i <= 0 || jvmArgsField == null) return;
                jvmArgsField.setText(JVM_PRESETS[i][1]);
                SettingsManager.saveSetting("jvm_args", JVM_PRESETS[i][1]);
            });
        }
        if (jvmClearButton != null) jvmClearButton.setOnAction(e -> {
            if (jvmArgsField != null) jvmArgsField.clear();
            SettingsManager.saveSetting("jvm_args", "");
            if (jvmPresetComboBox != null) jvmPresetComboBox.setValue(JVM_PRESETS[0][0]);
        });
        if (sessionActionComboBox != null) {
            sessionActionComboBox.getItems().setAll(SESSION_ACTIONS);
            String saved = SettingsManager.getSetting("server_session_action", "separate");
            int idx = 0;
            for (int i = 0; i < SESSION_KEYS.length; i++) if (SESSION_KEYS[i].equals(saved)) idx = i;
            sessionActionComboBox.setValue(SESSION_ACTIONS[idx]);
            sessionActionComboBox.setOnAction(e -> {
                int i = sessionActionComboBox.getItems().indexOf(sessionActionComboBox.getValue());
                if (i >= 0) SettingsManager.saveSetting("server_session_action", SESSION_KEYS[i]);
            });
        }
    }

    private AiChatDialog aiDialog;

    private void setupAiButton() {
        if (aiButton == null) return;
        aiButton.setText("ИИ-помощник");
        aiButton.setGraphic(Icons.icon("spark", 18));
        aiButton.setTooltip(new Tooltip("Бесплатный ИИ-помощник: подберёт моды, шейдеры и память под ваш ПК"));
    }

    @FXML private void openAiChat() {
        if (aiDialog == null) {
            aiDialog = new AiChatDialog(new AiChatDialog.Host() {
                @Override public void close() { closeModal(); }
                @Override public File gameDir() { return new File(minecraftDirField.getText()); }
                @Override public com.launcher.core.LocalAdvisor.Context context() {
                    GameVersion version = versionComboBox == null ? null : versionComboBox.getValue();
                    String loader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
                    int ram = ramSlider == null ? 0 : (int) Math.round(ramSlider.getValue());
                    return new com.launcher.core.LocalAdvisor.Context(version == null ? null : version.getId(), loader, ram);
                }
            });
            modalLayer.getChildren().add(aiDialog);
        }
        showDialog(aiDialog);
        aiDialog.open();
    }

    private ChatDialog chatDialog;
    private Label chatBadge;

    private void setupChat() {
        ChatService chat = ChatHub.service();
        chat.addListener(new ChatService.Listener() {
            @Override public void onRoster() { Platform.runLater(() -> updateChatBadge()); }
            @Override public void onMessage(ChatService.Message message, boolean mine) { Platform.runLater(() -> updateChatBadge()); }
            @Override public void onConnection(ChatService.Conn conn, String detail) { Platform.runLater(() -> updateChatBadge()); }
        });
        updateChatBadge();
        background.execute(ChatHub::autostart);
    }

    private void updateChatBadge() {
        if (chatBadge == null) return;
        ChatService chat = ChatHub.service();
        int n = chat.totalUnread() + chat.incoming().size();
        chatBadge.setText(n > 99 ? "99+" : String.valueOf(n));
        chatBadge.setVisible(n > 0);
        chatBadge.setManaged(n > 0);
    }

    private void openChats() {
        if (chatDialog == null) {
            chatDialog = new ChatDialog(new ChatDialog.Host() {
                @Override public void close() { closeModal(); }
                @Override public String suggestedName() {
                    Account a = accountComboBox == null ? null : accountComboBox.getValue();
                    return a == null ? "" : a.getUsername();
                }
            });
            modalLayer.getChildren().add(chatDialog);
        }
        showDialog(chatDialog);
        chatDialog.open();
    }

    private ServersDialog serversDialog;

    @FXML private void openServers() {
        if (serversDialog == null) {
            serversDialog = new ServersDialog(new ServersDialog.Host() {
                @Override public void close() { closeModal(); }
                @Override public List<String> versionIds() { return serverVersionIds(); }
                @Override public List<Account> accounts() { return new ArrayList<>(accounts); }
                @Override public Account defaultNick(ServerList.Entry entry) { return defaultServerNick(entry); }
                @Override public void connect(ServerList.Entry entry, Account account) { connectToServer(entry, account); }
            });
            modalLayer.getChildren().add(serversDialog);
        }
        showDialog(serversDialog);
        serversDialog.open();
    }

    private List<String> serverVersionIds() {
        List<String> ids = new ArrayList<>();
        if (allVersions != null) for (GameVersion v : allVersions) if ("release".equals(v.getType())) ids.add(v.getId());
        return ids;
    }

    private Account accountByUuid(String uuid) {
        if (uuid == null) return null;
        return accounts.stream().filter(a -> uuid.equalsIgnoreCase(a.getUuid())).findFirst().orElse(null);
    }

    private Account defaultServerNick(ServerList.Entry entry) {
        List<Account> assigned = new ArrayList<>();
        for (Account a : accounts) if (a.getUuid() != null && entry.nickList().contains(a.getUuid())) assigned.add(a);
        Account selected = accountComboBox.getValue();
        if (assigned.isEmpty()) return selected;
        if (selected != null && assigned.contains(selected)) return selected;
        return assigned.size() == 1 ? assigned.get(0) : null;
    }

    private void connectToServer(ServerList.Entry entry, Account account) {
        if (entry == null) return;
        if (account == null) {
            showAlert("Нет профиля", "Добавьте игровой профиль на главной странице, чтобы подключаться к серверам.");
            return;
        }
        String target = GameSessions.normalize(entry.address());
        String pendingKey = launchKey(account, entry.address());
        if (pendingKey != null && launchingKeys.contains(pendingKey)) {
            showAlert("Уже запускается", account.getUsername() + " уже запускается на «" + entry.name() + "». Вторую сессию не запускаю.");
            return;
        }
        List<GameSessions.Session> mine = GameSessions.forAccount(account.getUuid());
        for (GameSessions.Session session : mine) {
            if (target != null && target.equals(session.server())) {
                showAlert("Уже на сервере", account.getUsername() + " уже подключён к «" + entry.name() + "». Вторую сессию не запускаю.");
                return;
            }
        }
        if (!mine.isEmpty()) {
            String action = SettingsManager.getSetting("server_session_action", "separate");
            if ("separate".equals(action)) launchOnServer(entry, account);
            else if ("join".equals(action)) redirectRunningGame(entry, account, mine);
            else askAboutActiveSession(entry, account, mine, null);
            return;
        }
        launchOnServer(entry, account);
    }

    private void askAboutActiveSession(ServerList.Entry entry, Account account, List<GameSessions.Session> mine, String redirectFailure) {
        boolean failed = redirectFailure != null;
        ButtonType join = new ButtonType(failed ? "Перезапустить на сервере" : "Переключить на сервер", ButtonBar.ButtonData.OK_DONE);
        ButtonType another = new ButtonType("Отдельная сессия", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.NONE,
                failed ? "Переключить запущенную игру на «" + entry.name() + "» на лету не получилось: " + redirectFailure + ".\n\n"
                        + "Можно перезапустить игру сразу на сервере (мир сохранится) или открыть отдельную сессию."
                       : "Игра под ником " + account.getUsername() + " уже запущена. NEXUS переключит её на сервер «" + entry.name()
                        + "» без перезапуска. Адрес копировать не нужно.",
                join, another, cancel);
        if (rootPane != null && rootPane.getScene() != null) alert.initOwner(rootPane.getScene().getWindow());
        URL css = getClass().getResource("/css/styles.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.getDialogPane().getStyleClass().add("nexus-alert");
        alert.getDialogPane().setStyle(theme.style());
        alert.setTitle("Сессия уже запущена");
        alert.setHeaderText(null);
        ButtonType choice = alert.showAndWait().orElse(cancel);
        if (choice == another) { launchOnServer(entry, account); return; }
        if (choice != join) return;
        if (failed) restartOnServer(entry, account, mine);
        else redirectRunningGame(entry, account, mine);
    }

    private void redirectRunningGame(ServerList.Entry entry, Account account, List<GameSessions.Session> mine) {
        GameSessions.Session session = null;
        for (GameSessions.Session s : mine) if (s.link() != null) { session = s; break; }
        ServerList.Address address = ServerList.parseAddress(entry.address());
        if (session == null || address == null) {
            askAboutActiveSession(entry, account, mine, session == null
                    ? "эта игра запущена раньше обновления и без связи с лаунчером — после её перезапуска переключение будет мгновенным"
                    : "неверный адрес сервера");
            return;
        }
        final GameSessions.Session target = session;
        closeModal();
        javaStatusLabel.setText("Переключаю на «" + entry.name() + "»…");
        log("Переключение запущенной игры " + account.getUsername() + " на «" + entry.name() + "» (" + entry.address() + ")");
        background.execute(() -> {
            AgentLink.Result result = AgentLink.connect(target.link(), address.host(), address.port());
            Platform.runLater(() -> {
                if (result.ok()) {
                    target.setServer(GameSessions.normalize(entry.address()));
                    javaStatusLabel.setText("Подключение к «" + entry.name() + "»");
                    log("Игра переключена на «" + entry.name() + "».");
                } else {
                    log("Не удалось переключить игру на лету: " + result.message());
                    javaStatusLabel.setText("Игра запущена");
                    askAboutActiveSession(entry, account, mine, result.message());
                }
            });
        });
    }

    private void restartOnServer(ServerList.Entry entry, Account account, List<GameSessions.Session> mine) {
        beginBusy();
        refreshLaunchButton();
        javaStatusLabel.setText("Закрываю игру…");
        log("Переподключение: закрываю сессию " + account.getUsername() + " и запускаю её на «" + entry.name() + "».");
        background.execute(() -> {
            for (GameSessions.Session old : mine) {
                if (old.process() != null) GameLauncher.closeGracefully(old.process(), 25_000);
            }
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline && mine.stream().anyMatch(old -> GameSessions.all().contains(old))) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            Platform.runLater(() -> {
                endBusy();
                refreshLaunchButton();
                launchOnServer(entry, account);
            });
        });
    }

    private void launchOnServer(ServerList.Entry entry, Account account) {
        String mcId = entry.mcOrEmpty();
        if (mcId.isEmpty() && versionComboBox.getValue() != null) mcId = versionComboBox.getValue().getId();
        String loader = entry.loaderOrEmpty();
        if (loader.isEmpty()) loader = modLoaderComboBox == null || modLoaderComboBox.getValue() == null ? "Vanilla" : modLoaderComboBox.getValue();
        if (mcId == null || mcId.isEmpty()) {
            showAlert("Не выбрана версия", "Выберите версию Minecraft на главной странице или для этого сервера.");
            return;
        }
        final String wantedMc = mcId, wantedLoader = loader;
        GameVersion version = allVersions == null ? null : allVersions.stream().filter(v -> wantedMc.equals(v.getId())).findFirst().orElse(null);
        if (version == null) {
            showAlert("Версия не найдена", "Minecraft " + wantedMc + " нет в каталоге версий. Возможно, он ещё загружается: попробуйте через несколько секунд.");
            return;
        }
        closeModal();
        log("Запуск " + wantedLoader + " " + wantedMc + " под ником " + account.getUsername() + " с подключением к «" + entry.name() + "» (" + entry.address() + ")");
        if ("Vanilla".equals(wantedLoader)) {
            startLaunch(new LaunchSpec(version, account, "Vanilla", null, entry.address()));
            return;
        }
        GameVersion mainVersion = versionComboBox.getValue();
        String mainBuild = loaderVersionComboBox == null ? null : loaderVersionComboBox.getValue();
        if (mainBuild != null && !mainBuild.isBlank() && mainVersion != null && wantedMc.equals(mainVersion.getId())
                && wantedLoader.equals(modLoaderComboBox.getValue())) {
            startLaunch(new LaunchSpec(version, account, wantedLoader, mainBuild, entry.address()));
            return;
        }
        beginBusy();
        refreshLaunchButton();
        javaStatusLabel.setText("Ищу сборку " + wantedLoader + "…");
        background.execute(() -> {
            try {
                List<String> builds = ModLoaderManager.getVersions(wantedLoader, wantedMc);
                Platform.runLater(() -> {
                    endBusy();
                    refreshLaunchButton();
                    if (builds.isEmpty()) {
                        showAlert("Не удалось запустить", "Для " + wantedLoader + " и Minecraft " + wantedMc + " совместимые сборки не найдены.");
                        return;
                    }
                    startLaunch(new LaunchSpec(version, account, wantedLoader, builds.get(0), entry.address()));
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    endBusy();
                    refreshLaunchButton();
                    showAlert("Не удалось запустить", "Не удалось получить версии " + wantedLoader + ": " + rootMessage(ex));
                });
            }
        });
    }

    private CrashDialog crashDialog;
    private volatile CrashAnalyzer.Report lastCrash;
    private volatile CrashAnalyzer.Report pendingCrash;

    private void analyzeCrashIfAny(String minecraft, String loader, int ramMb) {
        GameLauncher.Outcome outcome = GameLauncher.lastOutcome();
        if (outcome == null || !outcome.crashed()) return;
        try {
            CrashAnalyzer.Report report = CrashAnalyzer.analyze(new CrashAnalyzer.Input(outcome.exitCode(), outcome.gameDir(),
                    outcome.startedAtMs(), outcome.outputTail(), ramMb, minecraft, loader));
            if (report == null) return;
            log("Разбор вылета: " + report.headline() + (report.reportFile() == null ? "" : " (" + report.reportFile().getName() + ")"));
            lastCrash = report;
            pendingCrash = report;
        } catch (Exception e) {
            log("Не удалось разобрать вылет: " + rootMessage(e));
        }
    }

    private void showCrash(CrashAnalyzer.Report report) {
        if (report == null) return;
        if (crashDialog == null) {
            crashDialog = new CrashDialog(background, this::closeModal, this::openMods, this::openLogs, this::log);
            modalLayer.getChildren().add(crashDialog);
        }
        crashDialog.show(report);
        showDialog(crashDialog);
    }

    private void setupSettingsDialog() {
        settingsNavGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            showSettingsPage(Integer.parseInt(String.valueOf(b.getUserData())));
        });
        autostartCheckBox.setSelected(Boolean.parseBoolean(SettingsManager.getSetting("launcher_autostart", "false")));
        String version = MainController.class.getPackage().getImplementationVersion();
        aboutVersionLabel.setText("Версия " + (version == null ? "1.8.1 (dev)" : version) + "  •  Java " + Runtime.version().feature());
    }

    private void selectSettingsPage(int index) {
        for (Toggle toggle : settingsNavGroup.getToggles())
            if (String.valueOf(index).equals(String.valueOf(toggle.getUserData()))) toggle.setSelected(true);
    }

    private void showSettingsPage(int index) {
        List<Node> pages = settingsPages.getChildren();
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisible(i == index);
            pages.get(i).setManaged(i == index);
        }
    }

    @FXML private void handleAutostartToggle() {
        final boolean enabled = autostartCheckBox.isSelected();
        SettingsManager.saveSetting("launcher_autostart", Boolean.toString(enabled));
        background.execute(() -> {
            try {
                if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"))
                    throw new IllegalStateException("Автозапуск поддерживается только в Windows.");
                if (enabled) {
                    String command = ProcessHandle.current().info().command().orElse("");
                    String lower = command.toLowerCase(java.util.Locale.ROOT);
                    if (command.isBlank() || lower.endsWith("java.exe") || lower.endsWith("javaw.exe"))
                        throw new IllegalStateException("Автозапуск работает в установленной версии (.exe), а не при запуске через Gradle или java.");
                    int code = runReg("reg", "add", AUTOSTART_KEY, "/v", "NexusLauncher", "/t", "REG_SZ", "/d", "\"" + command + "\" --autostart", "/f");
                    if (code != 0) throw new IllegalStateException("reg.exe вернул код " + code);
                    log("Автозапуск включён.");
                } else {
                    runReg("reg", "delete", AUTOSTART_KEY, "/v", "NexusLauncher", "/f");
                    log("Автозапуск выключен.");
                }
            } catch (Exception e) {
                log("Автозапуск: " + rootMessage(e));
                Platform.runLater(() -> {
                    autostartCheckBox.setSelected(!enabled);
                    SettingsManager.saveSetting("launcher_autostart", Boolean.toString(!enabled));
                    showAlert("Автозапуск", rootMessage(e));
                });
            }
        });
    }

    private int runReg(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    private static final PseudoClass ACTIVE_NAV = PseudoClass.getPseudoClass("active-nav");
    private static final double RAIL_COLLAPSED = 76, RAIL_EXPANDED = 252;
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final List<Node> railExpandOnly = new ArrayList<>();
    private final javafx.beans.property.SimpleDoubleProperty railProgress = new javafx.beans.property.SimpleDoubleProperty(0);
    private final javafx.animation.PauseTransition railCollapseDelay = new javafx.animation.PauseTransition(Duration.millis(260));
    private Timeline railAnimation;
    private boolean railPinned;
    private boolean newsShown;

    private void setupRail() {
        railPinned = Boolean.parseBoolean(SettingsManager.getSetting("sidebar_pinned", "false"));
        addNav("home", "home", "Главная", this::closeModalAndHome);
        addNav("versions", "layers", "Версии", this::openVersions);
        addNav("mods", "puzzle", "Моды", this::openMods);
        addNav("servers", "server", "Серверы", this::openServers);
        StackPane chatIcon = addNav("chats", "chat", "Чаты", this::openChats);
        chatBadge = new Label();
        chatBadge.getStyleClass().add("nav-badge");
        chatBadge.setVisible(false);
        chatBadge.setManaged(false);
        chatBadge.setMouseTransparent(true);
        StackPane.setAlignment(chatBadge, Pos.TOP_RIGHT);
        StackPane.setMargin(chatBadge, new Insets(-6, 8, 0, 0));
        chatIcon.getChildren().add(chatBadge);
        addNav("news", "news", "Новости", this::toggleNews);
        addNav("settings", "gear", "Настройки", this::openSettings);
        setActiveNav("home");
        railExpandOnly.addAll(List.of(brandName, railLoaderBox, railTagline, pinButton));
        Rectangle railClip = new Rectangle();
        railClip.widthProperty().bind(railPane.widthProperty());
        railClip.heightProperty().bind(railPane.heightProperty());
        railPane.setClip(railClip);
        railProgress.addListener((o, a, b) -> applyRailProgress(b.doubleValue()));
        railPane.setOnMouseEntered(e -> { railCollapseDelay.stop(); animateRail(1); });
        railPane.setOnMouseExited(e -> railCollapseDelay.playFromStart());
        railCollapseDelay.setOnFinished(e -> { if (!railPinned && !railPane.isHover()) animateRail(0); });
        pinButton.getStyleClass().remove("pinned");
        if (railPinned) { pinButton.getStyleClass().add("pinned"); railProgress.set(1); }
        applyRailProgress(railProgress.get());
    }

    private StackPane addNav(String id, String icon, String title, Runnable action) {
        Label label = new Label(title);
        label.getStyleClass().add("nav-label");
        Node glyph = Icons.icon(icon, 22);
        StackPane iconBox = new StackPane(glyph);
        iconBox.setMinWidth(56);
        iconBox.setPrefWidth(56);
        HBox row = new HBox(0, iconBox, label);
        row.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button();
        button.getStyleClass().add("rail-nav");
        button.setGraphic(row);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        Tooltip.install(button, new Tooltip(title));
        navButtons.put(id, button);
        navBox.getChildren().add(button);
        railExpandOnly.add(label);
        return iconBox;
    }

    private void closeModalAndHome() {
        closeModal();
        if (newsShown) toggleNews();
        setActiveNav("home");
    }

    private void toggleNews() {
        closeModal();
        newsShown = !newsShown;
        newsPanel.setVisible(newsShown);
        newsPanel.setManaged(newsShown);
        setActiveNav(newsShown ? "news" : "home");
        collapseRail(true);
    }

    private void setActiveNav(String id) {
        String shown = newsShown && "home".equals(id) ? "news" : id;
        navButtons.forEach((key, button) -> button.pseudoClassStateChanged(ACTIVE_NAV, key.equals(shown)));
    }

    private void animateRail(double target) {
        if (railAnimation != null) railAnimation.stop();
        railAnimation = new Timeline(new KeyFrame(Duration.millis(190),
                new javafx.animation.KeyValue(railProgress, target, javafx.animation.Interpolator.EASE_BOTH)));
        railAnimation.play();
    }

    private void collapseRail(boolean force) {
        if (railPinned && !force) return;
        railCollapseDelay.stop();
        if (!railPinned) animateRail(0);
    }

    private void applyRailProgress(double p) {
        double width = RAIL_COLLAPSED + (RAIL_EXPANDED - RAIL_COLLAPSED) * p;
        railPane.setPrefWidth(width);
        railPane.setMinWidth(width);
        railPane.setMaxWidth(width);
        if (heroBox != null) heroBox.setTranslateX((RAIL_EXPANDED - RAIL_COLLAPSED) * p);
        boolean open = p > 0.03;
        for (Node node : railExpandOnly) {
            node.setOpacity(Math.max(0, (p - 0.35) / 0.65));
            node.setVisible(open);
        }
        railPane.getStyleClass().remove("expanded");
        if (p > 0.5) railPane.getStyleClass().add("expanded");
    }

    @FXML private void togglePinRail() {
        railPinned = !railPinned;
        SettingsManager.saveSetting("sidebar_pinned", Boolean.toString(railPinned));
        pinButton.getStyleClass().remove("pinned");
        if (railPinned) pinButton.getStyleClass().add("pinned");
        if (!railPinned && !railPane.isHover()) animateRail(0);
    }

    private void setupWindowChrome() {
        rootPane.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene == null) return;
            scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE && modalLayer.isVisible()) { closeModal(); e.consume(); }
            });
            scene.windowProperty().addListener((o2, oldWindow, window) -> { if (window instanceof Stage stage) attachStage(stage); });
            if (scene.getWindow() instanceof Stage stage) attachStage(stage);
        });
    }

    private void attachStage(Stage stage) {
        WindowChrome.install(stage, rootPane, e -> e.getSceneY() < 64 || e.getSceneX() < 100);
        stage.maximizedProperty().addListener((o, a, b) -> updateMaximized(stage));
        stage.fullScreenProperty().addListener((o, a, b) -> updateMaximized(stage));
        updateMaximized(stage);
    }

    private void updateMaximized(Stage stage) {
        boolean big = stage.isMaximized() || stage.isFullScreen();
        rootPane.pseudoClassStateChanged(MAXIMIZED, big);
        bgClip.setArcWidth(big ? 0 : 58);
        bgClip.setArcHeight(big ? 0 : 58);
        if (maximizeButton != null) maximizeButton.setGraphic(Icons.icon(big ? "win-restore" : "win-max", 18));
    }

    @FXML private void toggleMaximize() {
        if (rootPane.getScene().getWindow() instanceof Stage stage && !stage.isFullScreen()) stage.setMaximized(!stage.isMaximized());
    }

    public static boolean minimizeToTray() {
        return Boolean.parseBoolean(SettingsManager.getSetting("minimize_to_tray", "true"));
    }

    @FXML private void minimizeWindow() {
        if (minimizeToTray()) { onCloseToTray.run(); return; }
        if (rootPane.getScene().getWindow() instanceof Stage stage) stage.setIconified(true);
    }

    @FXML private void closeWindow() {
        if (rootPane.getScene().getWindow() instanceof Stage stage)
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    @FXML private void openUninstallDialog() {
        if (launchRunning || GameLauncher.isRunning()) {
            showAlert("Игра запущена", "Закройте Minecraft и дождитесь завершения запуска, затем повторите удаление.");
            return;
        }
        if (confirmUninstallCheckBox != null) confirmUninstallCheckBox.setSelected(false);
        if (confirmUninstallButton != null) confirmUninstallButton.setDisable(true);
        uninstallListLabel.setText("Считаю размер файлов…");
        showDialog(uninstallDialog);
        background.execute(() -> {
            StringBuilder sb = new StringBuilder();
            for (UninstallCleanup.Target target : UninstallCleanup.targets())
                sb.append("• ").append(target.label()).append(" — ").append(UninstallCleanup.humanSize(target.sizeBytes())).append('\n');
            if (sb.length() == 0) sb.append("Файлов NEXUS на диске не найдено.\n");
            sb.append("• Сама программа NEXUS (через установщик Windows)");
            String text = sb.toString();
            Platform.runLater(() -> uninstallListLabel.setText(text));
        });
    }

    @FXML private void updateUninstallConfirm() {
        if (confirmUninstallButton != null)
            confirmUninstallButton.setDisable(confirmUninstallCheckBox == null || !confirmUninstallCheckBox.isSelected());
    }

    @FXML private void confirmUninstall() {
        if (confirmUninstallCheckBox == null || !confirmUninstallCheckBox.isSelected()) return;
        if (confirmUninstallButton != null) confirmUninstallButton.setDisable(true);
        boolean keep = false;
        uninstallListLabel.setText("Удаляю файлы…");
        background.execute(() -> {
            UninstallCleanup.Result result = UninstallCleanup.run(keep, this::log);
            String productCode = UninstallCleanup.findProductCode();
            boolean started = UninstallCleanup.startSystemUninstall(productCode);
            Platform.runLater(() -> {
                if (started) {
                    Platform.exit();
                    return;
                }
                closeModal();
                String problems = result.failed().isEmpty() ? "" : "\n\nНе удалось удалить файлов: " + result.failed().size()
                        + " (они могут быть заняты другой программой).";
                showAlert("Данные NEXUS удалены",
                        "Файлы лаунчера и игры удалены, включая миры и моды." + problems
                                + "\n\nСаму программу не удалось удалить автоматически (возможно, запрос прав администратора был отклонён) — удалите NEXUS через «Параметры → Приложения».");
                Platform.exit();
            });
        });
    }

    private static final String CLOSE_ASK = "ask", CLOSE_TRAY = "tray", CLOSE_EXIT = "exit";
    private Runnable onCloseToTray = () -> { };
    private Runnable onCloseExit = () -> { };

    private Runnable onRestore = () -> { };
    private java.util.function.BiConsumer<String, String> onTrayBalloon = (title, text) -> { };
    private java.util.function.BooleanSupplier windowVisible = () -> true;
    private volatile boolean hiddenForGame;

    private Runnable onRecentChanged = () -> { };
    private int loaderRefreshGeneration;
    private String pendingLoaderBuild;
    private Runnable afterLoaderReady;

    public void setRecentChangedHandler(Runnable handler) {
        this.onRecentChanged = handler == null ? () -> { } : handler;
    }

    public void launchRecent(RecentLaunches.Entry entry) {
        if (entry == null) return;
        if (launchRunning) {
            if (windowVisible.getAsBoolean()) showAlert("Идёт подготовка запуска", "Дождитесь, пока предыдущая игра запустится, или нажмите «Стоп».");
            else onTrayBalloon.accept("NEXUS Launcher", "Предыдущая игра ещё запускается.");
            return;
        }
        if (entry.accountUuid() != null) {
            accounts.stream().filter(a -> entry.accountUuid().equalsIgnoreCase(a.getUuid())).findFirst().ifPresent(accountComboBox::setValue);
        }
        GameVersion target = findVersion(entry.mc());
        if (target == null && versionFilterComboBox != null) {
            versionFilterComboBox.setValue("Все");
            if (versionSearchField != null) versionSearchField.clear();
            filterVersions();
            target = findVersion(entry.mc());
        }
        if (target == null) {
            showAlert("Версия не найдена", "Minecraft " + entry.mc() + " нет в каталоге версий. Возможно, он ещё загружается — попробуйте через несколько секунд.");
            return;
        }
        versionComboBox.setValue(target);
        if (modLoaderComboBox != null) modLoaderComboBox.setValue(entry.isVanilla() ? "Vanilla" : entry.loader());
        if (entry.isVanilla()) {
            handleLaunch();
        } else {
            pendingLoaderBuild = entry.build();
            afterLoaderReady = this::handleLaunch;
            refreshLoaderVersions();
        }
    }

    private void selectInstalledPack(ModpackInstaller.Result result) {
        if (result == null) return;
        GameVersion target = findVersion(result.mc());
        if (target == null && versionFilterComboBox != null) {
            versionFilterComboBox.setValue("Все");
            if (versionSearchField != null) versionSearchField.clear();
            filterVersions();
            target = findVersion(result.mc());
        }
        if (target == null) return;
        boolean vanilla = "Vanilla".equals(result.loader());
        versionComboBox.setValue(target);
        if (modLoaderComboBox != null) modLoaderComboBox.setValue(result.loader());
        if (!vanilla) {
            pendingLoaderBuild = result.build();
            afterLoaderReady = null;
            refreshLoaderVersions();
        }
    }

    private GameVersion findVersion(String id) {
        if (versionComboBox == null || id == null) return null;
        return versionComboBox.getItems().stream().filter(v -> id.equals(v.getId())).findFirst().orElse(null);
    }

    public void setTrayHandlers(Runnable restore, java.util.function.BiConsumer<String, String> balloon,
                                java.util.function.BooleanSupplier visible) {
        this.onRestore = restore;
        this.onTrayBalloon = balloon;
        this.windowVisible = visible;
        GameLauncher.onProcessStarted = () -> Platform.runLater(() -> {
            if (windowVisible.getAsBoolean() && Boolean.parseBoolean(SettingsManager.getSetting("hide_on_game_start", "false"))) {
                hiddenForGame = true;
                onCloseToTray.run();
            }
        });
    }

    public void launchFromTray() {
        if (launchRunning) {
            onTrayBalloon.accept("NEXUS Launcher", "Предыдущая игра ещё запускается.");
            return;
        }
        handleLaunch();
    }

    private void onLaunchFinished(String failure) {
        boolean wasHiddenForGame = hiddenForGame;
        hiddenForGame = false;
        CrashAnalyzer.Report crash = pendingCrash;
        pendingCrash = null;
        if (crash != null) {
            if (!windowVisible.getAsBoolean()) onRestore.run();
            showCrash(crash);
            return;
        }
        if (windowVisible.getAsBoolean()) return;
        if (wasHiddenForGame || failure != null) onRestore.run();
        else onTrayBalloon.accept("NEXUS Launcher", "Игра закрыта.");
    }

    public void setCloseHandlers(Runnable toTray, Runnable exit) {
        this.onCloseToTray = toTray;
        this.onCloseExit = exit;
    }

    public void requestClose() {
        if (rootPane.getScene() != null && rootPane.getScene().getWindow() instanceof Stage stage && stage.isIconified())
            stage.setIconified(false);
        switch (SettingsManager.getSetting("close_action", CLOSE_ASK)) {
            case CLOSE_TRAY -> onCloseToTray.run();
            case CLOSE_EXIT -> onCloseExit.run();
            default -> {
                if (rememberCloseCheckBox != null) rememberCloseCheckBox.setSelected(false);
                showDialog(closeDialog);
            }
        }
    }

    @FXML private void chooseCloseToTray() { applyCloseChoice(CLOSE_TRAY); }
    @FXML private void chooseCloseExit() { applyCloseChoice(CLOSE_EXIT); }

    private void applyCloseChoice(String action) {
        if (rememberCloseCheckBox != null && rememberCloseCheckBox.isSelected()) {
            SettingsManager.saveSetting("close_action", action);
            syncCloseBehaviorCombo();
        }
        closeModal();
        if (CLOSE_TRAY.equals(action)) onCloseToTray.run(); else onCloseExit.run();
    }

    private static final String CLOSE_LABEL_ASK = "Каждый раз спрашивать";
    private static final String CLOSE_LABEL_TRAY = "Сворачивать в трей";
    private static final String CLOSE_LABEL_EXIT = "Закрывать полностью";

    private void setupCloseBehaviorSetting() {
        if (closeBehaviorComboBox == null) return;
        closeBehaviorComboBox.getItems().setAll(CLOSE_LABEL_ASK, CLOSE_LABEL_TRAY, CLOSE_LABEL_EXIT);
        syncCloseBehaviorCombo();
        closeBehaviorComboBox.setOnAction(e -> {
            String v = closeBehaviorComboBox.getValue();
            SettingsManager.saveSetting("close_action", CLOSE_LABEL_TRAY.equals(v) ? CLOSE_TRAY : CLOSE_LABEL_EXIT.equals(v) ? CLOSE_EXIT : CLOSE_ASK);
        });
    }

    private void syncCloseBehaviorCombo() {
        if (closeBehaviorComboBox == null) return;
        String saved = SettingsManager.getSetting("close_action", CLOSE_ASK);
        closeBehaviorComboBox.setValue(CLOSE_TRAY.equals(saved) ? CLOSE_LABEL_TRAY : CLOSE_EXIT.equals(saved) ? CLOSE_LABEL_EXIT : CLOSE_LABEL_ASK);
    }

    private void showAlert(String title, String content) {
        if (!windowVisible.getAsBoolean()) onRestore.run();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        if (rootPane != null && rootPane.getScene() != null) alert.initOwner(rootPane.getScene().getWindow());
        URL css = getClass().getResource("/css/styles.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.getDialogPane().getStyleClass().add("nexus-alert");
        alert.getDialogPane().setStyle(theme.style());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
