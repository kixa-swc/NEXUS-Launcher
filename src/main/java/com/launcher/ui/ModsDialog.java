package com.launcher.ui;

import com.launcher.core.CancelToken;
import com.launcher.core.Catalog;
import com.launcher.core.ContentManager;
import com.launcher.core.ModLoaderManager;
import com.launcher.core.ModManager;
import com.launcher.core.ModpackInstaller;
import com.launcher.core.ModrinthClient;
import com.launcher.util.FileUtils;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public final class ModsDialog extends VBox {
    private enum Kind {
        MOD("мод", ".jar"), PACK("сборка", ".mrpack"), RESOURCEPACK("ресурспак", ".zip"), SHADER("шейдер", ".zip");
        final String noun;
        final String extension;
        Kind(String noun, String extension) { this.noun = noun; this.extension = extension; }
    }

    private record Local(String key, String title, String subtitle, String projectId, String versionId, Object source, byte[] icon) { }

    private record Running(CancelToken token, String versionId) { }

    private interface Task { String run(CancelToken cancel, Consumer<String> status) throws Exception; }

    private final ExecutorService executor;
    private final Consumer<String> logger;
    private final Consumer<ModpackInstaller.Result> onPackInstalled;

    private final Label instanceLabel = new Label();
    private final Label problemLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label countLabel = new Label();
    private final ToggleButton installedTab = new ToggleButton("Установленные");
    private final ToggleButton modsTab = new ToggleButton("Моды");
    private final ToggleButton packsTab = new ToggleButton("Сборки");
    private final ToggleButton rpTab = new ToggleButton("Ресурспаки");
    private final ToggleButton shaderTab = new ToggleButton("Шейдеры");
    private final StackPane content = new StackPane();
    private final VBox installedPane = new VBox(10);
    private final ListView<ModManager.Mod> installedList = new ListView<>();
    private final Button deleteButton = new Button("Удалить выбранный");
    private final Button updateSelectedButton = new Button("Обновить выбранный");
    private final Button updateAllButton = new Button("Обновить все");
    private final Button versionsButton = new Button("Версии");
    private final ProgressBar packProgress = new ProgressBar(0);
    private final Section mods;
    private final Section packs;
    private final Section resourcePacks;
    private final Section shaders;

    private final VBox versionPane = new VBox(10);
    private final Label versionTitle = new Label();
    private final Label versionHint = new Label();
    private final CheckBox versionMcOnly = new CheckBox();
    private final TextField versionSearch = new TextField();
    private List<ModrinthClient.Release> versionAll = new ArrayList<>();
    private final ListView<ModrinthClient.Release> versionList = new ListView<>();
    private Section versionSection;
    private String versionProject = "";
    private String versionName = "";
    private String versionCurrent = "";
    private int versionGeneration;

    private final Map<String, Image> jarIcons = new HashMap<>();
    private final PauseTransition deleteDisarm = new PauseTransition(Duration.seconds(4));
    private boolean deleteArmed;
    private boolean packBusy;
    private int currentTab;
    private int openGeneration;
    private String modsProblem;
    private String gameProblem;
    private String mc = "";
    private String loader = "";
    private File baseDir;
    private File gameDir;
    private File modsDir;

    public ModsDialog(ExecutorService executor, Runnable onClose, Consumer<String> logger,
                      Consumer<ModpackInstaller.Result> onPackInstalled) {
        this.executor = executor;
        this.logger = logger;
        this.onPackInstalled = onPackInstalled == null ? r -> { } : onPackInstalled;
        getStyleClass().addAll("dialog", "small-dialog");
        setSpacing(12);
        setPrefSize(900, 600);
        setMaxSize(900, 600);
        setVisible(false);
        setManaged(false);

        Label title = new Label("Менеджер модов, сборок и ресурсов");
        title.getStyleClass().add("dialog-title");
        Button close = new Button();
        close.getStyleClass().addAll("dialog-close-inline", "icon-button");
        close.setGraphic(Icons.icon("close", 16));
        close.setOnAction(e -> onClose.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);

        instanceLabel.getStyleClass().add("hint");

        ToggleButton[] tabButtons = {installedTab, modsTab, packsTab, rpTab, shaderTab};
        ToggleGroup tabs = new ToggleGroup();
        for (ToggleButton tab : tabButtons) {
            tab.setToggleGroup(tabs);
            tab.getStyleClass().add("news-tab");
        }
        installedTab.setSelected(true);
        tabs.selectedToggleProperty().addListener((o, a, b) -> {
            if (b == null) { if (a != null) a.setSelected(true); return; }
            for (int i = 0; i < tabButtons.length; i++) if (tabButtons[i] == b) showTab(i);
        });
        HBox tabRow = new HBox(4, tabButtons);

        packProgress.getStyleClass().add("pack-progress");
        packProgress.setMaxWidth(Double.MAX_VALUE);
        packProgress.setVisible(false);
        packProgress.setManaged(false);

        mods = new Section(Kind.MOD);
        packs = new Section(Kind.PACK);
        resourcePacks = new Section(Kind.RESOURCEPACK);
        shaders = new Section(Kind.SHADER);

        buildInstalledPane();
        buildVersionPane();
        content.getChildren().addAll(installedPane, mods.pane, packs.pane, resourcePacks.pane, shaders.pane, versionPane);
        VBox.setVgrow(content, Priority.ALWAYS);

        problemLabel.setWrapText(true);
        problemLabel.getStyleClass().add("hint");
        problemLabel.setMaxWidth(640);
        problemLabel.setVisible(false);
        problemLabel.setManaged(false);

        statusLabel.getStyleClass().add("hint");
        statusLabel.setMinHeight(18);
        statusLabel.setWrapText(true);

        getChildren().addAll(header, instanceLabel, tabRow, content, problemLabel, statusLabel);
        deleteDisarm.setOnFinished(e -> disarmDelete());
        showTab(0);
    }

    private void buildInstalledPane() {
        installedList.getStyleClass().add("mod-list");
        installedList.setCellFactory(list -> new ModCell());
        installedList.setPlaceholder(new Label("Модов пока нет: найдите их во вкладке «Моды» или поставьте сборку."));
        VBox.setVgrow(installedList, Priority.ALWAYS);
        countLabel.getStyleClass().add("hint");
        Button folder = new Button("Папка");
        folder.getStyleClass().addAll("soft-button", "row-button");
        folder.setOnAction(e -> { if (modsDir != null) FileUtils.openDirectory(modsDir); });
        Button refresh = new Button("Обновить список");
        refresh.getStyleClass().addAll("soft-button", "row-button");
        refresh.setOnAction(e -> refreshInstalled());
        versionsButton.getStyleClass().addAll("soft-button", "row-button");
        versionsButton.setOnAction(e -> versionsOfSelectedMod());
        updateSelectedButton.getStyleClass().addAll("accent-button", "row-button");
        updateSelectedButton.setOnAction(e -> updateSelectedMod());
        updateAllButton.getStyleClass().addAll("accent-button", "row-button");
        updateAllButton.setOnAction(e -> updateAllMods());
        deleteButton.getStyleClass().addAll("danger-button", "row-button");
        deleteButton.setOnAction(e -> deleteSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, folder, refresh, spacer, versionsButton, updateSelectedButton, updateAllButton, deleteButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        installedPane.getChildren().addAll(installedList, countLabel, actions);
    }

    private void refreshInstalled() {
        final File dir = modsDir;
        if (dir == null) return;
        executor.execute(() -> {
            List<ModManager.Mod> list = ModManager.list(dir);
            Platform.runLater(() -> {
                if (!dir.equals(modsDir)) return;
                ModManager.Mod selected = installedList.getSelectionModel().getSelectedItem();
                installedList.getItems().setAll(list);
                if (selected != null) for (ModManager.Mod mod : list)
                    if (mod.baseName().equals(selected.baseName())) { installedList.getSelectionModel().select(mod); break; }
                mods.have.clear();
                mods.localByProject.clear();
                for (ModManager.Mod mod : list) {
                    if (mod.projectId().isBlank()) continue;
                    mods.have.add(mod.projectId());
                    mods.localByProject.put(mod.projectId(), new Local(mod.baseName(), mod.name(), mod.version(), mod.projectId(),
                            mod.versionId(), mod, mod.icon()));
                }
                long enabled = list.stream().filter(ModManager.Mod::enabled).count();
                countLabel.setText(list.isEmpty() ? "" : "Включено " + enabled + " из " + list.size()
                        + ". Изменения применятся при следующем запуске игры.");
                mods.catalog.refresh();
            });
        });
    }

    private void toggle(ModManager.Mod mod, boolean enable) {
        executor.execute(() -> {
            String error = null;
            try { ModManager.setEnabled(mod, enable); }
            catch (Exception e) { error = rootMessage(e); }
            final String failure = error;
            Platform.runLater(() -> {
                if (failure != null) statusLabel.setText(failure);
                refreshInstalled();
            });
        });
    }

    private void deleteSelected() {
        ModManager.Mod mod = installedList.getSelectionModel().getSelectedItem();
        if (mod == null) { statusLabel.setText("Выберите мод в списке."); return; }
        if (!deleteArmed) {
            deleteArmed = true;
            deleteButton.setText("Ещё раз: удалить «" + shorten(mod.name(), 18) + "»");
            deleteDisarm.playFromStart();
            return;
        }
        disarmDelete();
        executor.execute(() -> {
            String error = null;
            try { ModManager.delete(mod); }
            catch (Exception e) { error = rootMessage(e); }
            final String failure = error;
            Platform.runLater(() -> {
                statusLabel.setText(failure != null ? failure : "Удалён: " + mod.name());
                if (failure == null) logger.accept("Мод удалён: " + mod.file().getName());
                refreshInstalled();
            });
        });
    }

    private void disarmDelete() {
        deleteArmed = false;
        deleteDisarm.stop();
        deleteButton.setText("Удалить выбранный");
    }

    private void updateSelectedMod() {
        ModManager.Mod mod = installedList.getSelectionModel().getSelectedItem();
        if (mod == null) { statusLabel.setText("Выберите мод в списке."); return; }
        if (modsDir == null) return;
        final File dir = modsDir;
        final String loaderNow = loader, mcNow = mc;
        mods.runTask("mod:" + mod.baseName(), null, "Обновление «" + mod.name() + "»", (cancel, status) -> {
            status.accept("Проверяю «" + mod.name() + "»…");
            String file = ModManager.update(dir, loaderNow, mcNow, mod, status, cancel);
            return file == null ? "«" + mod.name() + "» уже последней версии для Minecraft " + mcNow + "." : "Обновлено: " + mod.name() + " → " + file;
        });
    }

    private void updateAllMods() {
        if (modsDir == null) return;
        final File dir = modsDir;
        final String loaderNow = loader, mcNow = mc;
        final List<ModManager.Mod> snapshot = new ArrayList<>(installedList.getItems());
        if (snapshot.isEmpty()) { statusLabel.setText("Модов пока нет."); return; }
        mods.runTask("mods:all", null, "Обновление всех модов", (cancel, status) -> {
            int updated = 0, same = 0, failed = 0, index = 0;
            for (ModManager.Mod mod : snapshot) {
                cancel.check();
                index++;
                status.accept("Обновление " + index + " из " + snapshot.size() + ": " + mod.name());
                try {
                    String file = ModManager.update(dir, loaderNow, mcNow, mod, s -> { }, cancel);
                    if (file == null) same++; else updated++;
                } catch (CancellationException e) {
                    throw e;
                } catch (Exception e) {
                    failed++;
                    logger.accept("Не обновлён «" + mod.name() + "»: " + rootMessage(e));
                }
            }
            return "Моды: обновлено " + updated + ", уже свежие " + same + (failed > 0 ? ", не удалось или нет в каталоге " + failed : "") + ".";
        });
    }

    private void versionsOfSelectedMod() {
        ModManager.Mod mod = installedList.getSelectionModel().getSelectedItem();
        if (mod == null) { statusLabel.setText("Выберите мод в списке."); return; }
        final File dir = modsDir;
        statusLabel.setText("Ищу «" + mod.name() + "» в каталоге…");
        executor.execute(() -> {
            ModManager.Mod known = dir == null ? mod : ModManager.identify(dir, mod);
            Platform.runLater(() -> {
                if (known.projectId().isBlank()) {
                    statusLabel.setText("«" + mod.name() + "» не найден в каталоге, список версий недоступен.");
                    return;
                }
                statusLabel.setText("");
                mods.showVersions(known.projectId(), known.name(), known.versionId());
            });
        });
    }

    private void showTab(int tab) {
        currentTab = tab;
        boolean blocked = (tab <= 1 && modsProblem != null) || (tab >= 3 && gameProblem != null);
        setPane(installedPane, tab == 0 && !blocked);
        setPane(mods.pane, tab == 1 && !blocked);
        setPane(packs.pane, tab == 2);
        setPane(resourcePacks.pane, tab == 3 && !blocked);
        setPane(shaders.pane, tab == 4 && !blocked);
        setPane(versionPane, false);
        boolean showProblem = blocked;
        problemLabel.setText(!showProblem ? "" : tab <= 1 ? modsProblem : gameProblem);
        problemLabel.setVisible(showProblem);
        problemLabel.setManaged(showProblem);
        if (!blocked && tab >= 1) sectionOf(tab).onShown();
    }

    private Section sectionOf(int tab) {
        return switch (tab) { case 1 -> mods; case 2 -> packs; case 3 -> resourcePacks; default -> shaders; };
    }

    private static void setPane(Region pane, boolean shown) {
        pane.setVisible(shown);
        pane.setManaged(shown);
    }

    public void open(String mcVersion, String loaderName, String loaderBuild, File baseDir0) {
        mc = mcVersion == null ? "" : mcVersion;
        loader = loaderName == null || loaderName.isBlank() ? "Vanilla" : loaderName;
        baseDir = baseDir0;
        gameDir = null;
        modsDir = null;
        installedList.getItems().clear();
        jarIcons.clear();
        for (Section s : new Section[]{mods, packs, resourcePacks, shaders}) s.reset();
        modsProblem = null;
        gameProblem = null;
        installedTab.setSelected(true);
        disarmDelete();
        final int generation = ++openGeneration;
        final String loaderNow = loader;
        final String mcNow = mc;
        boolean vanilla = "Vanilla".equalsIgnoreCase(loaderNow);
        instanceLabel.setText(mcNow.isEmpty() ? "Версия не выбрана"
                : (vanilla ? "Vanilla" : loaderNow + (loaderBuild == null || loaderBuild.isBlank() ? "" : " " + loaderBuild)) + "  ·  Minecraft " + mcNow);
        statusLabel.setText("");
        if (mcNow.isEmpty()) {
            modsProblem = "Сначала выберите версию Minecraft (кнопка с сеткой слева).";
            gameProblem = modsProblem;
            showTab(0);
            return;
        }
        if (vanilla) {
            modsProblem = "Моды работают с Fabric, Forge и NeoForge. Выберите загрузчик на панели слева — для Vanilla модов нет. "
                    + "Сборки, ресурспаки и шейдеры доступны и без загрузчика.";
            gameDir = new File(baseDir0, "versions/" + mcNow + "/.minecraft");
            showTab(0);
            return;
        }
        showTab(0);
        statusLabel.setText("Ищу папку профиля…");
        executor.execute(() -> {
            String problem = null;
            File dir = null;
            try {
                String profile = ModLoaderManager.findInstalledProfile(loaderNow, mcNow, loaderBuild, baseDir0);
                if (profile == null) problem = loaderNow + " ещё не установлен для Minecraft " + mcNow
                        + ". Нажмите «Играть» один раз — NEXUS установит загрузчик, и вкладки станут доступны.";
                else dir = new File(baseDir0, "versions/" + profile + "/.minecraft");
            } catch (Exception e) {
                problem = "Не удалось определить папку профиля: " + rootMessage(e);
            }
            final String failure = problem;
            final File found = dir;
            Platform.runLater(() -> {
                if (generation != openGeneration) return;
                statusLabel.setText("");
                if (failure != null) { modsProblem = failure; gameProblem = failure; showTab(currentTab); return; }
                gameDir = found;
                modsDir = new File(found, "mods");
                showTab(currentTab);
                refreshInstalled();
            });
        });
    }

    private void buildVersionPane() {
        Button back = new Button("← Назад");
        back.getStyleClass().addAll("soft-button", "row-button");
        back.setOnAction(e -> showTab(currentTab));
        versionTitle.getStyleClass().add("dialog-title");
        versionTitle.setStyle("-fx-font-size:16px");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, back, versionTitle, spacer);
        top.setAlignment(Pos.CENTER_LEFT);
        versionHint.getStyleClass().add("hint");
        versionHint.setWrapText(true);
        versionMcOnly.setOnAction(e -> loadVersions());
        versionSearch.setPromptText("Поиск по версиям: номер, название, Minecraft (например 1.21.1), бета, Fabric");
        versionSearch.textProperty().addListener((o, a, b) -> applyVersionFilter());
        HBox.setHgrow(versionSearch, Priority.ALWAYS);
        Button clearSearch = new Button("✕");
        clearSearch.getStyleClass().addAll("soft-button", "row-button");
        clearSearch.setOnAction(e -> versionSearch.clear());
        HBox searchRow = new HBox(8, versionSearch, clearSearch);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        versionList.getStyleClass().add("mod-list");
        versionList.setFixedCellSize(64);
        versionList.setCellFactory(list -> new VersionCell());
        versionList.setPlaceholder(new Label("Подходящих версий нет. Снимите галочку, чтобы увидеть остальные."));
        VBox.setVgrow(versionList, Priority.ALWAYS);
        versionPane.getChildren().addAll(top, versionHint, searchRow, versionMcOnly, versionList);
        setPane(versionPane, false);
    }

    private void openVersionPicker(Section section, String projectId, String name, String currentVersionId) {
        versionSection = section;
        versionProject = projectId;
        versionName = name;
        versionCurrent = currentVersionId == null ? "" : currentVersionId;
        versionTitle.setText("Версии: " + shorten(name, 48));
        boolean mod = section.kind == Kind.MOD;
        versionMcOnly.setText(mc.isEmpty() ? "Только для выбранной версии Minecraft" : "Только для Minecraft " + mc
                + (mod ? " и " + loader : ""));
        versionSearch.clear();
        versionMcOnly.setDisable(mc.isEmpty());
        versionMcOnly.setSelected(!mc.isEmpty() && section.kind != Kind.PACK);
        versionHint.setText(switch (section.kind) {
            case MOD -> "Это версии самого мода, а не игры. Выбранная версия заменит установленную; включённое/выключенное состояние сохранится.";
            case PACK -> "Это версии самой сборки. Установка выбранной версии обновит файлы сборки; миры и скриншоты не затрагиваются.";
            case RESOURCEPACK -> "Это версии самого ресурспака. Выбранная версия заменит установленную.";
            case SHADER -> "Это версии самого шейдера. Выбранная версия заменит установленную.";
        });
        setPane(installedPane, false);
        setPane(mods.pane, false);
        setPane(packs.pane, false);
        setPane(resourcePacks.pane, false);
        setPane(shaders.pane, false);
        setPane(versionPane, true);
        problemLabel.setVisible(false);
        problemLabel.setManaged(false);
        loadVersions();
    }

    private void loadVersions() {
        final Section section = versionSection;
        if (section == null) return;
        final String project = versionProject;
        final int generation = ++versionGeneration;
        final String loaderFilter = section.kind == Kind.MOD ? loader : "";
        final String mcFilter = versionMcOnly.isSelected() ? mc : "";
        versionAll = new ArrayList<>();
        versionList.getItems().clear();
        statusLabel.setText("Загружаю список версий…");
        executor.execute(() -> {
            try {
                List<ModrinthClient.Release> found = Catalog.listReleases(project, loaderFilter, mcFilter, section.kind.extension);
                Platform.runLater(() -> {
                    if (generation != versionGeneration) return;
                    versionAll = found;
                    applyVersionFilter();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (generation == versionGeneration) statusLabel.setText("Не удалось загрузить версии: " + rootMessage(e));
                });
            }
        });
    }

    private void applyVersionFilter() {
        String text = versionSearch.getText() == null ? "" : versionSearch.getText().trim().toLowerCase(Locale.ROOT);
        String[] words = text.isEmpty() ? new String[0] : text.split("\\s+");
        List<ModrinthClient.Release> shown = new ArrayList<>();
        for (ModrinthClient.Release r : versionAll) {
            String hay = (r.name() + " " + r.number() + " " + r.typeLabel() + " " + r.type() + " " + r.date() + " "
                    + String.join(" ", r.gameVersions()) + " " + String.join(" ", r.loaders())).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (all) shown.add(r);
        }
        versionList.getItems().setAll(shown);
        if (versionAll.isEmpty()) return;
        statusLabel.setText(shown.isEmpty() ? "Ничего не найдено по запросу." : words.length == 0 ? "Версий: " + versionAll.size() + "." : "Найдено " + shown.size() + " из " + versionAll.size() + ".");
    }

    private final class Section {
        final Kind kind;
        final VBox pane = new VBox(10);
        final TextField search = new TextField();
        final ComboBox<String> loaderBox = new ComboBox<>();
        final TextField versionField = new TextField();
        final ToggleButton catalogButton = new ToggleButton("Каталог");
        final ToggleButton localButton = new ToggleButton("Установленные");
        final ListView<ModrinthClient.Project> catalog = new ListView<>();
        final ListView<Local> local = new ListView<>();
        final Set<String> have = new HashSet<>();
        final Map<String, Local> localByProject = new HashMap<>();
        final Map<String, ModrinthClient.Project> info = new HashMap<>();
        final Map<String, Running> running = new HashMap<>();
        final PauseTransition disarm = new PauseTransition(Duration.seconds(4));
        String armedKey;
        int searchGeneration;
        int localGeneration;
        boolean searched;

        Section(Kind kind) {
            this.kind = kind;
            search.setPromptText(switch (kind) {
                case MOD -> "Название мода, например Sodium или Fabric API";
                case PACK -> "Название сборки, например Fabulously Optimized";
                case RESOURCEPACK -> "Название ресурспака, например Faithful";
                case SHADER -> "Название шейдера, например Complementary";
            });
            search.setOnAction(e -> search());
            HBox.setHgrow(search, Priority.ALWAYS);
            versionField.setPromptText("Версия, напр. 1.21.1");
            versionField.setPrefWidth(170);
            versionField.setMinWidth(130);
            versionField.setOnAction(e -> { search(); refreshLocalList(); });
            Button find = new Button("Найти");
            find.getStyleClass().add("accent-button");
            find.setOnAction(e -> search());
            HBox bar = new HBox(8, search, versionField);
            bar.setAlignment(Pos.CENTER_LEFT);
            if (kind == Kind.PACK) {
                loaderBox.getItems().setAll("Любой загрузчик", "Fabric", "Forge", "NeoForge");
                loaderBox.setValue("Любой загрузчик");
                loaderBox.setPrefWidth(170);
                loaderBox.setOnAction(e -> search());
                bar.getChildren().add(loaderBox);
            }
            bar.getChildren().add(find);

            HBox options = new HBox(14);
            options.setAlignment(Pos.CENTER_LEFT);
            if (kind != Kind.MOD) {
                ToggleGroup group = new ToggleGroup();
                for (ToggleButton b : new ToggleButton[]{catalogButton, localButton}) {
                    b.setToggleGroup(group);
                    b.getStyleClass().add("news-tab");
                }
                catalogButton.setSelected(true);
                group.selectedToggleProperty().addListener((o, a, b) -> {
                    if (b == null) { if (a != null) a.setSelected(true); return; }
                    showLocal(b == localButton);
                });
                options.getChildren().addAll(catalogButton, localButton);
            }

            catalog.getStyleClass().add("mod-list");
            catalog.setFixedCellSize(wide() ? 92 : kind == Kind.PACK ? 84 : 72);
            catalog.setCellFactory(list -> new CatalogCell(this));
            catalog.setPlaceholder(new Label("Ничего не найдено."));
            local.getStyleClass().add("mod-list");
            local.setFixedCellSize(wide() ? 92 : 72);
            local.setCellFactory(list -> new LocalCell(this));
            local.setPlaceholder(new Label("Здесь пока пусто."));
            local.setVisible(false);
            local.setManaged(false);
            StackPane lists = new StackPane(catalog, local);
            VBox.setVgrow(lists, Priority.ALWAYS);

            Label hint = new Label(switch (kind) {
                case MOD -> "Моды ищутся на Modrinth для загрузчика профиля; версия игры берётся из поля справа от названия (очистите его, чтобы искать по всем версиям). Обязательные зависимости (например, Fabric API) ставятся автоматически. "
                        + "«Версии» открывает список версий мода, «Обновить» ставит самую новую для вашей версии Minecraft.";
                case PACK -> "Сборка ставится целиком: NEXUS сам поставит нужную версию Minecraft и загрузчик, загрузит моды и настройки. "
                        + "Установку можно отменить кнопкой «Отмена», все изменения откатятся. «Удалить» убирает файлы сборки, миры не трогает.";
                case RESOURCEPACK -> "Ресурспаки скачиваются в папку resourcepacks профиля. После установки включите пак в игре: Настройки → Ресурспаки.";
                case SHADER -> "Шейдеры скачиваются в папку shaderpacks профиля. Нужны Iris (Fabric/NeoForge) или OptiFine; включаются в игре: Настройки → Шейдеры.";
            });
            hint.setWrapText(true);
            hint.getStyleClass().add("hint");

            pane.getChildren().add(bar);
            if (kind != Kind.MOD) pane.getChildren().add(options);
            pane.getChildren().add(lists);
            if (kind == Kind.PACK) pane.getChildren().add(packProgress);
            pane.getChildren().add(hint);
            disarm.setOnFinished(e -> { armedKey = null; local.refresh(); });
            setPane(pane, false);
        }

        boolean wide() { return kind == Kind.RESOURCEPACK || kind == Kind.SHADER; }

        void reset() {
            searched = false;
            searchGeneration++;
            localGeneration++;
            catalog.getItems().clear();
            local.getItems().clear();
            have.clear();
            localByProject.clear();
            info.clear();
            armedKey = null;
            if (kind != Kind.MOD) {
                catalogButton.setSelected(true);
            }
            versionField.setText(kind == Kind.PACK ? "" : mc);
            showLocal(false);
        }

        void onShown() {
            if (!searched) search();
            if (kind != Kind.MOD) refreshLocal();
        }

        void showLocal(boolean showLocal) {
            local.setVisible(showLocal);
            local.setManaged(showLocal);
            catalog.setVisible(!showLocal);
            catalog.setManaged(!showLocal);
            if (showLocal) refreshLocal();
        }

        boolean busy(String key) { return running.containsKey(key); }

        String mcFilter() { return versionField.getText() == null ? "" : versionField.getText().trim(); }

        File targetDir() {
            return switch (kind) {
                case MOD -> modsDir;
                case PACK -> baseDir;
                case RESOURCEPACK -> gameDir == null ? null : ContentManager.dir(gameDir, ContentManager.Kind.RESOURCEPACK);
                case SHADER -> gameDir == null ? null : ContentManager.dir(gameDir, ContentManager.Kind.SHADER);
            };
        }

        void search() {
            if (kind == Kind.MOD && modsDir == null) return;
            if (kind == Kind.PACK && baseDir == null) return;
            if ((kind == Kind.RESOURCEPACK || kind == Kind.SHADER) && gameDir == null) return;
            searched = true;
            final String query = search.getText() == null ? "" : search.getText().trim();
            final String loaderFilter;
            if (kind == Kind.MOD) loaderFilter = loader;
            else if (kind == Kind.PACK) {
                String picked = loaderBox.getValue();
                loaderFilter = picked == null || picked.startsWith("Любой") ? "" : picked;
            } else loaderFilter = "";
            final String mcNow = mcFilter();
            final String type = switch (kind) {
                case MOD -> ModrinthClient.TYPE_MOD;
                case PACK -> ModrinthClient.TYPE_PACK;
                case RESOURCEPACK -> ModrinthClient.TYPE_RESOURCEPACK;
                case SHADER -> ModrinthClient.TYPE_SHADER;
            };
            final int generation = ++searchGeneration;
            statusLabel.setText("Поиск на Modrinth…");
            executor.execute(() -> {
                try {
                    List<ModrinthClient.Project> found = Catalog.searchProjects(type, query, loaderFilter, mcNow, wide() ? 20 : 30);
                    Platform.runLater(() -> {
                        if (generation != searchGeneration) return;
                        catalog.getItems().setAll(found);
                        statusLabel.setText(found.isEmpty() ? "Ничего не найдено." : "");
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        if (generation != searchGeneration) return;
                        searched = false;
                        statusLabel.setText("Modrinth недоступен: " + rootMessage(e));
                    });
                }
            });
        }

        void refreshLocal() {
            if (kind == Kind.MOD) { refreshInstalled(); return; }
            final File dir = targetDir();
            if (dir == null) return;
            final int generation = ++localGeneration;
            final File base = baseDir;
            executor.execute(() -> {
                List<Local> items = new ArrayList<>();
                if (kind == Kind.PACK) {
                    for (ModpackInstaller.Installed p : ModpackInstaller.listInstalled(base)) {
                        String where = "Vanilla".equals(p.loader()) || p.loader().isBlank() ? "Vanilla" : p.loader() + " " + p.build();
                        items.add(new Local(p.profile(), p.name(), (p.number().isBlank() ? "" : p.number() + "  ·  ") + where + "  ·  Minecraft " + p.mc(),
                                p.projectId(), p.versionId(), p, null));
                    }
                } else {
                    ContentManager.Kind ck = kind == Kind.RESOURCEPACK ? ContentManager.Kind.RESOURCEPACK : ContentManager.Kind.SHADER;
                    for (ContentManager.Item item : ContentManager.list(dir, ck)) {
                        String number = item.versionNumber().isBlank() ? "" : item.versionNumber() + "  ·  ";
                        items.add(new Local(item.file().getName(), item.name(), number + item.file().getName() + "  ·  " + formatSize(item.size())
                                + (item.folder() ? "  ·  папка" : ""), item.projectId(), item.versionId(), item, item.icon()));
                    }
                }
                List<String> ids = new ArrayList<>(new LinkedHashSet<>(items.stream().map(Local::projectId).filter(id -> !id.isBlank()).toList()));
                List<ModrinthClient.Project> projects = new ArrayList<>();
                try { projects = Catalog.projects(ids); } catch (Exception ignored) { }
                final List<ModrinthClient.Project> fetched = projects;
                Platform.runLater(() -> {
                    if (generation != localGeneration) return;
                    for (ModrinthClient.Project p : fetched) info.put(p.id(), p);
                    have.clear();
                    localByProject.clear();
                    for (Local l : items) if (!l.projectId().isBlank()) { have.add(l.projectId()); localByProject.put(l.projectId(), l); }
                    refreshLocalList(items);
                    catalog.refresh();
                });
            });
        }

        private List<Local> lastLocal = new ArrayList<>();

        private void refreshLocalList(List<Local> items) {
            lastLocal = items;
            refreshLocalList();
        }

        void refreshLocalList() {
            List<Local> shown = new ArrayList<>();
            String onlyMc = kind == Kind.PACK ? mcFilter() : "";
            for (Local l : lastLocal) {
                if (!onlyMc.isEmpty() && l.source() instanceof ModpackInstaller.Installed p && !onlyMc.equalsIgnoreCase(p.mc())) continue;
                shown.add(l);
            }
            local.getItems().setAll(shown);
        }

        void runTask(String key, String versionId, String title, Task task) {
            if (running.containsKey(key)) return;
            if (kind == Kind.PACK && packBusy) { statusLabel.setText("Дождитесь окончания установки другой сборки."); return; }
            final CancelToken token = new CancelToken();
            running.put(key, new Running(token, versionId));
            if (kind == Kind.PACK) {
                packBusy = true;
                packProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                setPane(packProgress, true);
            }
            refreshAll();
            statusLabel.setText(title + "…");
            executor.execute(() -> {
                String message = null, error = null;
                boolean cancelled = false;
                try {
                    message = task.run(token, s -> Platform.runLater(() -> statusLabel.setText(s)));
                } catch (CancellationException e) {
                    cancelled = true;
                } catch (Exception e) {
                    error = rootMessage(e);
                }
                final String done = message, failure = error;
                final boolean wasCancelled = cancelled;
                Platform.runLater(() -> {
                    running.remove(key);
                    if (kind == Kind.PACK) { packBusy = false; setPane(packProgress, false); }
                    if (wasCancelled) statusLabel.setText("Отменено: " + title + ".");
                    else if (failure != null) { statusLabel.setText(title + ": ошибка. " + failure); logger.accept(title + ": " + failure); }
                    else {
                        statusLabel.setText(done == null ? "Готово." : done);
                        logger.accept(done == null ? title : done);
                        if (versionId != null && versionSection == this && key.equals(versionProject)) versionCurrent = versionId;
                    }
                    if (kind == Kind.MOD) refreshInstalled(); else refreshLocal();
                    refreshAll();
                });
            });
        }

        void cancel(String key) {
            Running r = running.get(key);
            if (r == null) return;
            r.token().cancel();
            statusLabel.setText("Отменяю…");
        }

        void refreshAll() {
            catalog.refresh();
            local.refresh();
            if (versionSection == this) versionList.refresh();
        }

        void startInstall(String projectId, String title, ModrinthClient.Release release) {
            final String relId = release == null ? null : release.id();
            final String loaderNow = loader, mcNow = mc;
            final File dir = targetDir();
            if (dir == null && kind != Kind.PACK) { statusLabel.setText("Папка профиля ещё не найдена."); return; }
            final String filter = mcFilter();
            final File base = baseDir;
            final String key = projectId;
            runTask(key, relId, "Установка «" + title + "»", (cancel, status) -> switch (kind) {
                case MOD -> {
                    List<String> files = release == null
                            ? ModManager.install(dir, loaderNow, mcNow, projectId, status, cancel)
                            : ModManager.installRelease(dir, loaderNow, mcNow, projectId, release, status, cancel);
                    yield "Установлено: " + title + (files.size() > 1 ? " и зависимости (" + (files.size() - 1) + ")" : "") + ".";
                }
                case PACK -> {
                    if (base == null) throw new IllegalStateException("Папка игры не задана.");
                    ModpackInstaller.Result done = ModpackInstaller.install(projectId, title, base, relId, filter, status,
                            line -> Platform.runLater(() -> logger.accept(line)),
                            p -> Platform.runLater(() -> packProgress.setProgress(p < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p)), cancel);
                    Platform.runLater(() -> onPackInstalled.accept(done));
                    String where = "Vanilla".equals(done.loader()) ? "Minecraft " + done.mc() : done.loader() + " " + done.build() + " · Minecraft " + done.mc();
                    yield "Готово: " + done.name() + " " + done.versionNumber() + " (" + where + "). Нажмите «Играть»."
                            + (done.movedMods() > 0 ? " Старые моды (" + done.movedMods() + ") сохранены в mods-backup." : "");
                }
                default -> {
                    ModrinthClient.Release chosen = release != null ? release : Catalog.latestRelease(projectId, null, mcNow, ".zip");
                    if (chosen == null) throw new java.io.IOException("Для Minecraft " + mcNow + " у «" + title + "» нет подходящей версии.");
                    String file = ContentManager.install(dir, projectId, chosen, status, cancel);
                    yield "Установлено: " + file + ". Включите " + kind.noun + " в настройках игры.";
                }
            });
            if (versionSection == this && release != null) versionList.refresh();
        }

        void startUpdate(Local item) {
            final String loaderNow = loader, mcNow = mc;
            final File dir = targetDir();
            final File base = baseDir;
            final String title = item.title();
            runTask(item.projectId().isBlank() ? item.key() : item.projectId(), null, "Обновление «" + title + "»", (cancel, status) -> switch (kind) {
                case MOD -> {
                    ModManager.Mod mod = (ModManager.Mod) item.source();
                    String file = ModManager.update(dir, loaderNow, mcNow, mod, status, cancel);
                    yield file == null ? "«" + title + "» уже последней версии для Minecraft " + mcNow + "." : "Обновлено: " + title + " → " + file;
                }
                case PACK -> {
                    ModpackInstaller.Installed pack = (ModpackInstaller.Installed) item.source();
                    status.accept("Ищу новую версию сборки для Minecraft " + pack.mc() + "…");
                    ModrinthClient.Release latest = Catalog.latestRelease(pack.projectId(), null, pack.mc(), ".mrpack");
                    if (latest == null) throw new java.io.IOException("У сборки нет версий для Minecraft " + pack.mc() + ".");
                    boolean newer = !latest.id().equals(pack.versionId());
                    if (newer && !pack.versionId().isBlank()) {
                        ModrinthClient.Release current = Catalog.releaseById(pack.versionId(), ".mrpack");
                        if (current != null && current.published().compareTo(latest.published()) >= 0) newer = false;
                    }
                    if (!newer) yield "«" + title + "» уже последней версии для Minecraft " + pack.mc() + " (" + pack.number() + ").";
                    ModpackInstaller.Result done = ModpackInstaller.install(pack.projectId(), pack.name(), base, latest.id(), null, status,
                            line -> Platform.runLater(() -> logger.accept(line)),
                            p -> Platform.runLater(() -> packProgress.setProgress(p < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p)), cancel);
                    Platform.runLater(() -> onPackInstalled.accept(done));
                    yield "Сборка обновлена: " + done.name() + " " + pack.number() + " → " + done.versionNumber() + ". Нажмите «Играть».";
                }
                default -> {
                    ContentManager.Item pack = (ContentManager.Item) item.source();
                    String file = ContentManager.update(dir, pack, mcNow, status, cancel);
                    yield file == null ? "«" + title + "» уже последней версии для Minecraft " + mcNow + "." : "Обновлено: " + title + " → " + file;
                }
            });
        }

        void startRemove(Local item) {
            if (!item.key().equals(armedKey)) {
                armedKey = item.key();
                disarm.playFromStart();
                local.refresh();
                return;
            }
            armedKey = null;
            disarm.stop();
            final File dir = targetDir();
            final String title = item.title();
            statusLabel.setText("Удаление «" + title + "»…");
            executor.execute(() -> {
                String message = null, error = null;
                try {
                    switch (kind) {
                        case PACK -> {
                            ModpackInstaller.Installed pack = (ModpackInstaller.Installed) item.source();
                            int n = ModpackInstaller.uninstall(pack.gameDir(), line -> { });
                            message = "Сборка «" + title + "» удалена (файлов: " + n + "). Миры и скриншоты не тронуты.";
                        }
                        case MOD -> {
                            ModManager.delete((ModManager.Mod) item.source());
                            message = "Удалён: " + title;
                        }
                        default -> {
                            ContentManager.delete(dir, (ContentManager.Item) item.source());
                            message = "Удалён: " + title;
                        }
                    }
                } catch (Exception e) {
                    error = rootMessage(e);
                }
                final String done = message, failure = error;
                Platform.runLater(() -> {
                    statusLabel.setText(failure != null ? "Не удалось удалить «" + title + "»: " + failure : done);
                    if (failure == null) logger.accept(done);
                    if (kind == Kind.MOD) refreshInstalled(); else refreshLocal();
                });
            });
        }

        void showVersions(String projectId, String title, String currentVersionId) {
            openVersionPicker(this, projectId, title, currentVersionId);
        }

        String currentVersion(String projectId) {
            Local l = localByProject.get(projectId);
            return l == null ? "" : l.versionId();
        }
    }

    private final class ModCell extends ListCell<ModManager.Mod> {
        private final CheckBox box = new CheckBox();
        private final IconBox icon = new IconBox(40);
        private final Label name = new Label();
        private final Label meta = new Label();
        private final HBox row;

        ModCell() {
            name.getStyleClass().add("mod-name");
            meta.getStyleClass().add("hint");
            VBox text = new VBox(2, name, meta);
            row = new HBox(12, box, icon, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 4, 6, 4));
            box.setOnAction(e -> { if (getItem() != null) toggle(getItem(), box.isSelected()); });
        }

        @Override protected void updateItem(ModManager.Mod mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) { setGraphic(null); setText(null); return; }
            box.setSelected(mod.enabled());
            icon.reset(mod.name(), mod);
            icon.setImage(jarIcon(mod), mod);
            name.setText(mod.name());
            String version = mod.version().isBlank() ? "" : mod.version() + "  ·  ";
            meta.setText(version + mod.baseName() + "  ·  " + formatSize(mod.size()) + (mod.enabled() ? "" : "  ·  выключен"));
            row.setOpacity(mod.enabled() ? 1.0 : 0.55);
            setText(null);
            setGraphic(row);
        }
    }

    private final class CatalogCell extends ListCell<ModrinthClient.Project> {
        private final Section section;
        private final IconBox icon;
        private final Label title = new Label();
        private final Label description = new Label();
        private final Label tags = new Label();
        private final Button primary = new Button();
        private final Button versions = new Button("Версии");
        private final HBox row;

        CatalogCell(Section section) {
            this.section = section;
            icon = section.wide() ? new IconBox(128, 72) : new IconBox(section.kind == Kind.PACK ? 56 : 48);
            title.getStyleClass().add("mod-name");
            description.getStyleClass().add("hint");
            description.setWrapText(true);
            description.setMaxHeight(32);
            tags.getStyleClass().add("hint");
            VBox text = new VBox(3, title, description, tags);
            HBox.setHgrow(text, Priority.ALWAYS);
            primary.setMinWidth(104);
            primary.getStyleClass().add("row-button");
            versions.getStyleClass().addAll("soft-button", "row-button");
            HBox buttons = new HBox(6, primary, versions);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            row = new HBox(12, icon, text, buttons);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 6, 4, 4));
            text.maxWidthProperty().bind(section.catalog.widthProperty().subtract(icon.getPrefWidth() + 270));
            primary.setOnAction(e -> {
                ModrinthClient.Project p = getItem();
                if (p == null) return;
                if (section.busy(p.id())) { section.cancel(p.id()); return; }
                Local installed = section.localByProject.get(p.id());
                if (installed != null) section.startUpdate(installed); else section.startInstall(p.id(), p.title(), null);
            });
            versions.setOnAction(e -> {
                ModrinthClient.Project p = getItem();
                if (p != null) section.showVersions(p.id(), p.title(), section.currentVersion(p.id()));
            });
        }

        @Override protected void updateItem(ModrinthClient.Project project, boolean empty) {
            super.updateItem(project, empty);
            if (empty || project == null) { setGraphic(null); setText(null); return; }
            icon.reset(project.title(), project);
            String url = section.wide() ? project.previewUrl() : project.iconUrl();
            RemoteImages.load(url, section.wide() ? 360 : 112, image -> icon.setImage(image, project));
            if (section.wide() && !project.iconUrl().isBlank() && !project.iconUrl().equals(url))
                RemoteImages.load(project.iconUrl(), 112, image -> { });
            title.setText(project.title() + "  ·  " + project.author() + "  ·  ↓ " + formatCount(project.downloads()));
            description.setText(project.description());
            String latest = project.latestMinecraft();
            String loaders = section.kind == Kind.PACK ? String.join(" · ", project.loaders()) : "";
            tags.setText(loaders + (loaders.isEmpty() || latest.isEmpty() ? "" : "  ·  ") + (latest.isEmpty() ? "" : "до Minecraft " + latest));
            boolean busy = section.busy(project.id());
            boolean have = section.have.contains(project.id());
            primary.getStyleClass().removeAll("accent-button", "danger-button");
            primary.getStyleClass().add(busy ? "danger-button" : "accent-button");
            primary.setText(busy ? "Отмена" : have ? "Обновить" : "Установить");
            primary.setDisable(!busy && section.kind == Kind.PACK && packBusy);
            versions.setDisable(busy);
            setText(null);
            setGraphic(row);
        }
    }

    private final class LocalCell extends ListCell<Local> {
        private final Section section;
        private final IconBox icon;
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Button update = new Button("Обновить");
        private final Button versions = new Button("Версии");
        private final Button remove = new Button("Удалить");
        private final HBox row;

        LocalCell(Section section) {
            this.section = section;
            icon = section.wide() ? new IconBox(128, 72) : new IconBox(48);
            title.getStyleClass().add("mod-name");
            meta.getStyleClass().add("hint");
            meta.setWrapText(true);
            VBox text = new VBox(3, title, meta);
            HBox.setHgrow(text, Priority.ALWAYS);
            update.getStyleClass().addAll("accent-button", "row-button");
            versions.getStyleClass().addAll("soft-button", "row-button");
            remove.getStyleClass().addAll("danger-button", "row-button");
            HBox buttons = new HBox(6, update, versions, remove);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            row = new HBox(12, icon, text, buttons);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 6, 4, 4));
            text.maxWidthProperty().bind(section.local.widthProperty().subtract(icon.getPrefWidth() + 330));
            update.setOnAction(e -> {
                Local l = getItem();
                if (l == null) return;
                String key = l.projectId().isBlank() ? l.key() : l.projectId();
                if (section.busy(key)) section.cancel(key); else section.startUpdate(l);
            });
            versions.setOnAction(e -> {
                Local l = getItem();
                if (l == null) return;
                if (l.projectId().isBlank()) { statusLabel.setText("«" + l.title() + "» не найден в каталоге, список версий недоступен."); return; }
                section.showVersions(l.projectId(), l.title(), l.versionId());
            });
            remove.setOnAction(e -> { if (getItem() != null) section.startRemove(getItem()); });
        }

        @Override protected void updateItem(Local item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setGraphic(null); setText(null); return; }
            icon.reset(item.title(), item);
            ModrinthClient.Project p = item.projectId().isBlank() ? null : section.info.get(item.projectId());
            Image jar = item.icon() == null ? null : RemoteImages.fromBytes(item.icon(), 96);
            if (jar != null) icon.setImage(jar, item);
            if (p != null) {
                String url = section.wide() ? p.previewUrl() : p.iconUrl();
                RemoteImages.load(url, section.wide() ? 360 : 112, image -> icon.setImage(image, item));
            }
            title.setText(item.title());
            meta.setText(item.subtitle());
            String key = item.projectId().isBlank() ? item.key() : item.projectId();
            boolean busy = section.busy(key);
            update.getStyleClass().removeAll("accent-button", "danger-button");
            update.getStyleClass().add(busy ? "danger-button" : "accent-button");
            update.setText(busy ? "Отмена" : "Обновить");
            update.setDisable(!busy && section.kind == Kind.PACK && packBusy);
            versions.setDisable(busy);
            remove.setDisable(busy);
            remove.setText(item.key().equals(section.armedKey) ? "Точно?" : "Удалить");
            setText(null);
            setGraphic(row);
        }
    }

    private final class VersionCell extends ListCell<ModrinthClient.Release> {
        private final Label title = new Label();
        private final Label meta = new Label();
        private final Button action = new Button();
        private final HBox row;

        VersionCell() {
            title.getStyleClass().add("mod-name");
            meta.getStyleClass().add("hint");
            VBox text = new VBox(3, title, meta);
            HBox.setHgrow(text, Priority.ALWAYS);
            action.setMinWidth(120);
            action.getStyleClass().add("row-button");
            row = new HBox(12, text, action);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));
            text.maxWidthProperty().bind(versionList.widthProperty().subtract(190));
            action.setOnAction(e -> {
                ModrinthClient.Release release = getItem();
                Section section = versionSection;
                if (release == null || section == null) return;
                Running r = section.running.get(versionProject);
                if (r != null) { section.cancel(versionProject); return; }
                section.startInstall(versionProject, versionName, release);
            });
        }

        @Override protected void updateItem(ModrinthClient.Release release, boolean empty) {
            super.updateItem(release, empty);
            if (empty || release == null) { setGraphic(null); setText(null); return; }
            boolean current = release.id().equals(versionCurrent);
            String label = release.name().equals(release.number()) ? release.number() : release.name() + "  (" + release.number() + ")";
            title.setText(label + (release.typeLabel().isEmpty() ? "" : "  ·  " + release.typeLabel()) + (current ? "  ·  установлена" : ""));
            List<String> gv = release.gameVersions();
            String games = gv.isEmpty() ? "" : "Minecraft " + String.join(", ", gv.subList(0, Math.min(4, gv.size()))) + (gv.size() > 4 ? " +" + (gv.size() - 4) : "");
            List<String> parts = new ArrayList<>();
            if (!release.date().isEmpty()) parts.add(release.date());
            if (!games.isEmpty()) parts.add(games);
            if (!release.loaders().isEmpty() && versionSection != null && versionSection.kind != Kind.RESOURCEPACK && versionSection.kind != Kind.SHADER)
                parts.add(String.join(", ", release.loaders()));
            meta.setText(String.join("  ·  ", parts));
            Running r = versionSection == null ? null : versionSection.running.get(versionProject);
            boolean thisOne = r != null && release.id().equals(r.versionId());
            action.getStyleClass().removeAll("accent-button", "danger-button", "soft-button");
            if (thisOne) { action.getStyleClass().add("danger-button"); action.setText("Отмена"); action.setDisable(false); }
            else {
                action.getStyleClass().add(current ? "soft-button" : "accent-button");
                action.setText(current ? "Установлена" : "Установить");
                action.setDisable(current || r != null);
            }
            setText(null);
            setGraphic(row);
        }
    }

    private Image jarIcon(ModManager.Mod mod) {
        if (mod.icon() == null) return null;
        String key = mod.baseName() + ":" + mod.size();
        if (jarIcons.containsKey(key)) return jarIcons.get(key);
        Image image = RemoteImages.fromBytes(mod.icon(), 80);
        jarIcons.put(key, image);
        return image;
    }

    private static String formatCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fK", n / 1_000.0);
        return Long.toString(n);
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f МБ", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " КБ";
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
