package com.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.launcher.model.Account;
import com.launcher.model.GameVersion;
import com.launcher.util.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class GameLauncher {
    private static final java.util.Set<Process> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Process> STOP_REQUESTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, Integer> VERSION_USERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ThreadLocal<Outcome> LAST_OUTCOME = new ThreadLocal<>();
    private static final ThreadLocal<AgentLink.Link> CURRENT_LINK = new ThreadLocal<>();
    public static AgentLink.Link currentLink() { return CURRENT_LINK.get(); }

    public static volatile Runnable onProcessStarted;

    public record Outcome(int exitCode, File gameDir, long startedAtMs, boolean stoppedByUser, List<String> outputTail) {
        public boolean crashed() { return !stoppedByUser && exitCode != 0; }
    }

    public static Outcome lastOutcome() { return LAST_OUTCOME.get(); }

    public static boolean isRunning() { return runningCount() > 0; }

    public static int runningCount() {
        int count = 0;
        for (Process process : RUNNING) if (process.isAlive()) count++;
        return count;
    }

    public static void stopRunningProcess() {
        for (Process process : RUNNING) stop(process);
    }

    private static final ThreadLocal<Process> CURRENT = new ThreadLocal<>();

    public static Process currentProcess() { return CURRENT.get(); }

    public static boolean closeGracefully(Process process, long timeoutMs) {
        if (process == null || !process.isAlive()) return true;
        STOP_REQUESTED.add(process);
        try {
            java.util.Set<Long> pids = new java.util.HashSet<>();
            pids.add(process.pid());
            process.descendants().forEach(h -> pids.add(h.pid()));
            com.sun.jna.platform.win32.User32 user32 = com.sun.jna.platform.win32.User32.INSTANCE;
            user32.EnumWindows((hwnd, data) -> {
                com.sun.jna.ptr.IntByReference pid = new com.sun.jna.ptr.IntByReference();
                user32.GetWindowThreadProcessId(hwnd, pid);
                if (pids.contains((long) pid.getValue()) && user32.IsWindowVisible(hwnd))
                    user32.PostMessage(hwnd, 0x0010, null, null);
                return true;
            }, null);
        } catch (Throwable ignored) { }
        try {
            if (process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        stop(process);
        try { return process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return !process.isAlive(); }
    }

    private static void stop(Process process) {
        if (process == null || !process.isAlive()) return;
        STOP_REQUESTED.add(process);
        process.descendants().forEach(handle -> { try { handle.destroyForcibly(); } catch (Exception ignored) {} });
        process.destroyForcibly();
    }

    private static void release(Process process, String versionId) {
        if (RUNNING.remove(process)) {
            VERSION_USERS.compute(versionId, (key, users) -> users == null || users <= 1 ? null : users - 1);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Операция отменена");
    }

    public static void launch(GameVersion version, Account account, int ramMb, String customJavaPath, File baseDir,
                              int windowWidth, int windowHeight, boolean fullscreen,
                              java.util.function.Consumer<String> logger) throws Exception {
        launch(version, account, ramMb, customJavaPath, baseDir, windowWidth, windowHeight, fullscreen,
                "Vanilla", null, logger);
    }

    public static void launch(GameVersion vanillaVersion, Account account, int ramMb, String customJavaPath, File baseDir,
                              int windowWidth, int windowHeight, boolean fullscreen, String loader, String loaderBuild,
                              java.util.function.Consumer<String> logger) throws Exception {
        launch(vanillaVersion, account, ramMb, customJavaPath, baseDir, windowWidth, windowHeight, fullscreen,
                loader, loaderBuild, null, logger);
    }

    public static void launch(GameVersion vanillaVersion, Account account, int ramMb, String customJavaPath, File baseDir,
                              int windowWidth, int windowHeight, boolean fullscreen, String loader, String loaderBuild,
                              String serverAddress, java.util.function.Consumer<String> logger) throws Exception {
        launch(vanillaVersion, account, ramMb, customJavaPath, baseDir, windowWidth, windowHeight, fullscreen, loader, loaderBuild,
                serverAddress, logger, null);
    }

    public static void launch(GameVersion vanillaVersion, Account account, int ramMb, String customJavaPath, File baseDir,
                              int windowWidth, int windowHeight, boolean fullscreen, String loader, String loaderBuild,
                              String serverAddress, java.util.function.Consumer<String> logger, Runnable onStarted) throws Exception {
        if (baseDir == null) baseDir = FileUtils.getMinecraftDir();
        String baseVersionId = vanillaVersion.getId();
        String launchVersionId = "Vanilla".equalsIgnoreCase(loader) ? baseVersionId
                : ModLoaderManager.ensureInstalled(loader, baseVersionId, loaderBuild, baseDir,
                        customJavaPath == null || customJavaPath.isBlank() ? "java" : customJavaPath, logger);
        GameVersion version = new GameVersion(launchVersionId, loader, null);
        File versionsDir = new File(baseDir, "versions/" + launchVersionId);
        File clientJar;
        File jsonFile;
        if ("Vanilla".equalsIgnoreCase(loader)) {
            clientJar = new File(versionsDir, launchVersionId + ".jar");
            jsonFile = new File(versionsDir, launchVersionId + ".json");
            if (!clientJar.isFile() || !jsonFile.isFile()) {
                logger.accept("Файлы базовой версии отсутствуют. Загружаем Minecraft " + baseVersionId + "...");
                DownloadManager.installVersion(vanillaVersion, baseDir, logger);
            }
        } else {
            File vanillaJson = new File(baseDir, "versions/" + baseVersionId + "/" + baseVersionId + ".json");
            File vanillaJar = new File(baseDir, "versions/" + baseVersionId + "/" + baseVersionId + ".jar");
            if (!vanillaJson.isFile() || !vanillaJar.isFile()) throw new FileNotFoundException("Базовая версия Minecraft " + baseVersionId + " не установлена.");
            clientJar = vanillaJar;
            JsonObject merged = ModLoaderManager.mergedProfileJson(launchVersionId, baseDir);
            jsonFile = new File(versionsDir, ".nexus-effective-version.json");
            try (Writer writer = Files.newBufferedWriter(jsonFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(merged, writer);
            }
            logger.accept("Подготовлен объединённый профиль " + loader + ": базовые и loader-библиотеки подключены.");
        }
        checkCancelled();

        File gameDir = new File(versionsDir, ".minecraft");
        File assetsDir = new File(baseDir, "assets");
        File nativesDir = new File(versionsDir, "natives");

        gameDir.mkdirs();
        new File(gameDir, "mods").mkdirs();
        new File(gameDir, "saves").mkdirs();
        new File(gameDir, "resourcepacks").mkdirs();
        new File(gameDir, "resources").mkdirs();
        
        File iconsDir = new File(versionsDir, "assets/icons");
        iconsDir.mkdirs();
        ensureIconsExist(iconsDir);
        
        assetsDir.mkdirs();
        nativesDir.mkdirs();

        AssetManager.verifyAndDownloadAssets(baseDir, baseVersionId, logger);
        checkCancelled();

        ensureLibraryArtifacts(baseDir, jsonFile, logger);
        checkCancelled();

        final boolean nativesInUse = VERSION_USERS.getOrDefault(launchVersionId, 0) > 0;
        if (nativesInUse) logger.accept("Нативные библиотеки уже используются другой запущенной сессией этой версии, пропускаю распаковку.");
        else {
            logger.accept("Подготовка нативных библиотек...");
            NativesManager.extractNatives(baseDir, jsonFile, nativesDir, logger);
        }

        String vid = baseVersionId.toLowerCase();
        boolean isOldVersion = false;
        boolean usesLaunchWrapper = false;
        String mainClass = null;

        if (vid.startsWith("b") || vid.startsWith("a") || vid.startsWith("rd") || vid.startsWith("c") || vid.startsWith("inf") || 
            vid.startsWith("1.0") || vid.startsWith("1.1") || vid.startsWith("1.2") || vid.startsWith("1.3") || vid.startsWith("1.4") || vid.startsWith("1.5")) {
            isOldVersion = true;
        }

        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("mainClass")) {
                mainClass = root.get("mainClass").getAsString();
                if (mainClass.contains("launchwrapper") || mainClass.contains("Launch") || mainClass.contains("fml")) {
                    usesLaunchWrapper = true;
                }
            }
        } catch (Exception ignored) {}

        if (mainClass == null || mainClass.isEmpty()) {
            if (isOldVersion) {
                mainClass = "net.minecraft.client.Minecraft";
            } else {
                mainClass = "net.minecraft.client.main.Main";
            }
        }

        String assetIndexId = baseVersionId;
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("assetIndex")) {
                assetIndexId = root.getAsJsonObject("assetIndex").get("id").getAsString();
            }
        } catch (Exception ignored) {}

        if (baseVersionId.startsWith("1.8") && !baseVersionId.equals("1.8")) {
            assetIndexId = "1.8";
        }

        int requiredJava = JavaManager.getRequiredJavaMajor(baseVersionId);
        if (usesLaunchWrapper && requiredJava > 8) {
            requiredJava = 8;
        }

        logger.accept("Поиск подходящей Java (" + requiredJava + ") для версии " + baseVersionId + "...");
        String javaExe;
        if (customJavaPath != null && !customJavaPath.isEmpty()) {
            javaExe = customJavaPath;
        } else {
            javaExe = JavaManager.findOrDownloadJava(requiredJava, logger);
        }

        if (javaExe == null || javaExe.isEmpty()) {
            javaExe = "java";
        }

        List<File> libFiles = collectLibraryFiles(baseDir, jsonFile, clientJar, isOldVersion, logger);
        logger.accept("Подготовлено библиотек для classpath: " + libFiles.size());
        logger.accept("Клиентский JAR: " + clientJar.getAbsolutePath());

        String fullClasspath = buildFullClasspath(libFiles, clientJar);

        List<String> command = new ArrayList<>();
        command.add(javaExe);
        AgentLink.Link[] linkHolder = new AgentLink.Link[1];
        String agentOption = AgentLink.prepare(baseDir, linkHolder, clientJar);
        CURRENT_LINK.set(agentOption == null ? null : linkHolder[0]);
        if (agentOption != null) command.add(agentOption);
        
        int effectiveRamMb = Math.max(512, ramMb);
        boolean legacyLwjglClient = "1.16.5".equals(baseVersionId) || "1.15.2".equals(baseVersionId);
        List<String> userJvmArgs = userJvmArguments();
        boolean userHeap = userJvmArgs.stream().anyMatch(a -> a.startsWith("-Xmx"));
        boolean userGc = userJvmArgs.stream().anyMatch(a -> a.matches("-XX:\\+Use\\w*GC"));
        if (!userHeap) command.add("-Xmx" + effectiveRamMb + "M");
        command.add("-XX:+UnlockExperimentalVMOptions");
        if (!userGc) command.add(legacyLwjglClient ? "-XX:+UseSerialGC" : "-XX:+UseG1GC");
        if (!userJvmArgs.isEmpty()) {
            command.addAll(userJvmArgs);
            logger.accept("Свои аргументы JVM: " + String.join(" ", userJvmArgs));
        }

        List<String> manifestJvmArgs = parseJvmArguments(jsonFile, version, account,
                gameDir, assetsDir, assetIndexId, fullClasspath);
        if (("Forge".equalsIgnoreCase(loader) || "NeoForge".equalsIgnoreCase(loader))
                && hasModulePathArguments(manifestJvmArgs)) {
            removeClientJarFromModulePath(manifestJvmArgs, clientJar, logger);
            fullClasspath = removeClientJarFromClasspath(fullClasspath, clientJar);
            removeClientJarFromClasspathArguments(manifestJvmArgs, clientJar);
            logger.accept("Forge/NeoForge ModLauncher: базовый client JAR исключён из classpath и module-path; используется преобразованный клиент загрузчика.");
        }
        logger.accept("Длина classpath: " + fullClasspath.length() + " символов");
        command.addAll(manifestJvmArgs);
        boolean hasClasspath = manifestJvmArgs.contains("-cp") || manifestJvmArgs.contains("-classpath")
                || manifestJvmArgs.contains("--class-path");
        if (!hasClasspath) {
            command.add("-cp");
            command.add(fullClasspath);
        }

        File manifestLibDir = null;
        for (String arg : manifestJvmArgs) {
            if (arg.startsWith("-Djava.library.path=") && arg.length() > "-Djava.library.path=".length())
                manifestLibDir = new File(arg.substring("-Djava.library.path=".length()));
        }
        if (manifestLibDir != null && !manifestLibDir.getAbsoluteFile().equals(nativesDir.getAbsoluteFile())) {
            if (!nativesInUse) NativesManager.extractPlatformNatives(baseDir, jsonFile, manifestLibDir, logger);
        } else {
            command.add("-Djava.library.path=" + nativesDir.getAbsolutePath());
            command.add("-Dorg.lwjgl.librarypath=" + nativesDir.getAbsolutePath());
        }
        command.add("-Dorg.lwjgl.util.Debug=false");
        command.add("-Dorg.lwjgl.util.NoChecks=false");

        if (isOldVersion || !mainClass.contains("main.Main")) {
            command.add("-Dsun.java2d.dpiaware=false");
            command.add("-Dnet.java.games.input.plugins=net.java.games.input.DefaultControllerEnvironment");
            command.add("-Dorg.lwjgl.opengl.Window.undecorated=false");
        }

        command.add(mainClass);

        List<String> gameArgs = parseGameArguments(jsonFile, version, account, gameDir, assetsDir,
                assetIndexId, isOldVersion, fullClasspath);
        if (!gameArgs.contains("--launchTarget") && mainClass.toLowerCase(java.util.Locale.ROOT).contains("bootstrap")) {
            if ("Forge".equalsIgnoreCase(loader)) {
                gameArgs.add("--launchTarget"); gameArgs.add("forgeclient");
            } else if ("NeoForge".equalsIgnoreCase(loader)) {
                gameArgs.add("--launchTarget"); gameArgs.add("neoforgeclient");
            }
        }
        logger.accept("Точка входа: " + mainClass + "; игровых аргументов: " + gameArgs.size());
        setGameOption(gameArgs, "--width", Integer.toString(windowWidth));
        setGameOption(gameArgs, "--height", Integer.toString(windowHeight));
        if (fullscreen) {
            if (!gameArgs.contains("--fullscreen")) gameArgs.add("--fullscreen");
        } else {
            gameArgs.removeIf("--fullscreen"::equals);
        }
        appendServerArguments(gameArgs, serverAddress, jsonFile, baseVersionId, logger);
        command.addAll(gameArgs);

        File javaArgFile = null;
        if (fullClasspath.length() > 18000) {
            javaArgFile = new File(versionsDir, "nexus-launch-" + System.nanoTime() + ".args");
            writeJavaArgumentFile(javaArgFile, command.subList(1, command.size()));
            String executable = command.get(0);
            command.clear(); command.add(executable); command.add("@" + javaArgFile.getAbsolutePath());
            logger.accept("Длинная команда вынесена в Java argument-файл.");
        }

        logger.accept("Инициализация запуска процесса игры...");
        logger.accept("Команда JVM: " + redactCommand(command, account == null ? null : account.getAccessToken()));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(gameDir);
        pb.redirectErrorStream(true);
        checkCancelled();
        Process process;
        LAST_OUTCOME.remove();
        final long startedAtMs = System.currentTimeMillis();
        try { process = pb.start(); }
        catch (Exception startError) {
            if (javaArgFile != null) Files.deleteIfExists(javaArgFile.toPath());
            throw startError;
        }
        RUNNING.add(process);
        CURRENT.set(process);
        VERSION_USERS.merge(launchVersionId, 1, Integer::sum);
        PlayTimeTracker.start(process, launchVersionId, loader, loaderBuild);
        if (onStarted != null) {
            try { onStarted.run(); } catch (RuntimeException ignored) { }
        }
        Runnable started = onProcessStarted;
        if (started != null) {
            try { started.run(); } catch (RuntimeException ignored) { }
        }
        final String tag = account == null || account.getUsername() == null ? "" : " " + account.getUsername();

        final java.util.ArrayDeque<String> outputTail = new java.util.ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    stop(process);
                    throw new InterruptedException("Запуск остановлен пользователем");
                }
                outputTail.addLast(line);
                if (outputTail.size() > 250) outputTail.removeFirst();
                logger.accept("[GAME" + tag + "] " + line);
            }
        } catch (InterruptedException interrupted) {
            stop(process);
            STOP_REQUESTED.remove(process);
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            if (!process.isAlive() || Thread.currentThread().isInterrupted()) release(process, launchVersionId);
        }
        int exitCode = 0;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!process.isAlive()) exitCode = process.exitValue();
        }
        release(process, launchVersionId);
        final boolean stopRequested = STOP_REQUESTED.remove(process);
        LAST_OUTCOME.set(new Outcome(exitCode, gameDir, startedAtMs, stopRequested, new ArrayList<>(outputTail)));
        if (exitCode != 0 && !stopRequested) logger.accept("Игра завершилась с кодом " + exitCode + ".");
        if (javaArgFile != null) Files.deleteIfExists(javaArgFile.toPath());
        redactAccessTokenFromCrashReports(gameDir, account == null ? null : account.getAccessToken(), logger);
    }

    private static void appendServerArguments(List<String> gameArgs, String address, File jsonFile, String baseVersionId,
                                              java.util.function.Consumer<String> logger) {
        if (address == null || address.isBlank()) return;
        ServerList.Address parsed = ServerList.parseAddress(address);
        if (parsed == null) {
            logger.accept("Адрес сервера «" + address + "» выглядит неверно — игра запустится без автоподключения.");
            return;
        }
        if (!supportsServerArgument(baseVersionId)) {
            logger.accept("Minecraft " + baseVersionId + " не умеет подключаться к серверу при запуске — игра запустится без автоподключения.");
            return;
        }
        boolean quickPlay = false;
        try { quickPlay = Files.readString(jsonFile.toPath()).contains("quickPlayMultiplayer"); }
        catch (Exception ignored) { }
        if (quickPlay) {
            setGameOption(gameArgs, "--quickPlayMultiplayer", parsed.hostPort());
        } else {
            setGameOption(gameArgs, "--server", parsed.host());
            setGameOption(gameArgs, "--port", Integer.toString(parsed.port()));
        }
        logger.accept("Автоподключение к серверу: " + parsed.hostPort());
    }

    private static boolean supportsServerArgument(String baseVersionId) {
        String v = baseVersionId == null ? "" : baseVersionId.toLowerCase(java.util.Locale.ROOT);
        if (v.startsWith("a") || v.startsWith("b") || v.startsWith("c") || v.startsWith("rd") || v.startsWith("inf")) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^1\\.(\\d+)").matcher(v);
        return !m.find() || Integer.parseInt(m.group(1)) >= 6;
    }

    private static String buildFullClasspath(List<File> libraries, File clientJar) throws IOException {
        if (clientJar == null || !clientJar.isFile() || clientJar.length() == 0)
            throw new FileNotFoundException("Клиентский JAR Minecraft отсутствует или пуст: " + clientJar);
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (File library : libraries) {
            if (library != null && library.isFile() && library.length() > 0)
                entries.add(library.getAbsolutePath());
        }
        entries.add(clientJar.getAbsolutePath());
        return String.join(File.pathSeparator, entries);
    }

    private static boolean hasModulePathArguments(List<String> args) {
        for (String arg : args) {
            if ("-p".equals(arg) || "--module-path".equals(arg)
                    || arg.startsWith("--module-path=") || (arg.startsWith("-p=") && arg.length() > 3)) return true;
        }
        return false;
    }

    private static String removeClientJarFromClasspath(String classpath, File clientJar) throws IOException {
        String target = normalizedPath(clientJar);
        List<String> kept = new ArrayList<>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            if (!entry.isBlank() && !normalizedPath(new File(entry)).equalsIgnoreCase(target)) kept.add(entry);
        }
        return String.join(File.pathSeparator, kept);
    }

    private static void removeClientJarFromClasspathArguments(List<String> args, File clientJar) throws IOException {
        String target = normalizedPath(clientJar);
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-cp".equals(arg) || "-classpath".equals(arg) || "--class-path".equals(arg)) {
                if (i + 1 < args.size()) args.set(i + 1, filterModulePath(args.get(i + 1), target));
            } else if (arg.startsWith("--class-path=")) {
                String value = arg.substring("--class-path=".length());
                args.set(i, "--class-path=" + filterModulePath(value, target));
            }
        }
    }

    private static void removeClientJarFromModulePath(List<String> args, File clientJar,
                                                       java.util.function.Consumer<String> logger) throws IOException {
        String target = normalizedPath(clientJar);
        boolean removed = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ("-p".equals(arg) || "--module-path".equals(arg)) {
                if (i + 1 < args.size()) {
                    String filtered = filterModulePath(args.get(i + 1), target);
                    if (!filtered.equals(args.get(i + 1))) {
                        args.set(i + 1, filtered);
                        removed = true;
                    }
                }
            } else if (arg.startsWith("--module-path=")) {
                String value = arg.substring("--module-path=".length());
                String filtered = filterModulePath(value, target);
                if (!filtered.equals(value)) {
                    args.set(i, "--module-path=" + filtered);
                    removed = true;
                }
            } else if (arg.startsWith("-p=") && arg.length() > 3) {
                String value = arg.substring(3);
                String filtered = filterModulePath(value, target);
                if (!filtered.equals(value)) {
                    args.set(i, "-p=" + filtered);
                    removed = true;
                }
            }
        }
        if (removed) logger.accept("Forge/NeoForge: ванильный client JAR исключён из module-path; его предоставляет ModLauncher.");
    }

    private static String filterModulePath(String modulePath, String targetNormalizedPath) throws IOException {
        String[] entries = modulePath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        List<String> kept = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (entry.isBlank()) continue;
            if (!normalizedPath(new File(entry)).equalsIgnoreCase(targetNormalizedPath)) kept.add(entry);
        }
        return String.join(File.pathSeparator, kept);
    }

    private static String normalizedPath(File file) throws IOException {
        return file.getCanonicalPath().replace('\\', '/');
    }

    private static void writeJavaArgumentFile(File file, List<String> args) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String arg : args) {
            content.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"').append(System.lineSeparator());
        }
        Files.writeString(file.toPath(), content.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String redactCommand(List<String> command, String accessToken) {
        List<String> safe = new ArrayList<>(command.size());
        for (String part : command) {
            String value = part;
            if (accessToken != null && !accessToken.isBlank()) value = value.replace(accessToken, "[REDACTED]");
            if (value.length() > 1200) value = value.substring(0, 1200) + "…";
            safe.add(value);
        }
        return String.join(" ", safe);
    }

    static List<String> userJvmArguments() {
        List<String> out = new ArrayList<>();
        String raw = SettingsManager.getSetting("jvm_args", "");
        if (raw == null || raw.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : raw.toCharArray()) {
            if (c == '"') { quoted = !quoted; continue; }
            if (Character.isWhitespace(c) && !quoted) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (cur.length() > 0) out.add(cur.toString());
        out.removeIf(a -> !a.startsWith("-"));
        return out;
    }

    private static void redactAccessTokenFromCrashReports(File gameDir, String accessToken,
                                                           java.util.function.Consumer<String> logger) {
        if (accessToken == null || accessToken.isBlank()) return;
        File[] reports = gameDir.listFiles((dir, name) -> name.startsWith("hs_err_pid") && name.endsWith(".log"));
        if (reports == null) return;
        byte[] needle = accessToken.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] replacement = "[REDACTED_MICROSOFT_ACCESS_TOKEN]"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (File report : reports) {
            try {
                byte[] source = Files.readAllBytes(report.toPath());
                byte[] redacted = replaceBytes(source, needle, replacement);
                if (redacted != source) {
                    Files.write(report.toPath(), redacted);
                    logger.accept("Из crash-report удалён токен Microsoft: " + report.getName());
                }
            } catch (Exception e) {
                logger.accept("Не удалось безопасно обработать " + report.getName() + ": "
                        + e.getClass().getSimpleName());
            }
        }
    }

    private static byte[] replaceBytes(byte[] source, byte[] needle, byte[] replacement) {
        if (needle.length == 0 || source.length < needle.length) return source;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(source.length);
        boolean changed = false;
        for (int i = 0; i < source.length;) {
            boolean match = i + needle.length <= source.length;
            if (match) {
                for (int j = 0; j < needle.length; j++) {
                    if (source[i + j] != needle[j]) { match = false; break; }
                }
            }
            if (match) {
                output.write(replacement, 0, replacement.length);
                i += needle.length;
                changed = true;
            } else {
                output.write(source[i]);
                i++;
            }
        }
        return changed ? output.toByteArray() : source;
    }

    private static void ensureLibraryArtifacts(File baseDir, File jsonFile,
                                               java.util.function.Consumer<String> logger) throws Exception {
        File libDir = new File(baseDir, "libraries");
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("libraries") || !root.get("libraries").isJsonArray()) return;
            for (JsonElement element : root.getAsJsonArray("libraries")) {
                if (!element.isJsonObject()) continue;
                JsonObject library = element.getAsJsonObject();
                if (!libraryAllowed(library)) continue;
                String name = library.has("name") ? library.get("name").getAsString() : "";
                if (library.has("downloads") && library.get("downloads").isJsonObject()) {
                    JsonObject downloads = library.getAsJsonObject("downloads");
                    if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                        JsonObject artifact = downloads.getAsJsonObject("artifact");
                        if (artifact.has("path") && artifact.has("url")) {
                            File target = new File(libDir, artifact.get("path").getAsString());
                            if (!isManifestFileValid(target, artifact)) {
                                logger.accept("Загрузка библиотеки: " + target.getName());
                                DownloadManager.downloadFile(artifact.get("url").getAsString(), target);
                            }
                            if (!isManifestFileValid(target, artifact)) throw new IOException("Контрольная сумма библиотеки не совпадает: " + target.getName());
                        }
                    } else if (!name.isBlank()) {
                        downloadMavenLibrary(libDir, library, name, logger);
                    }
                    if (downloads.has("classifiers") && downloads.get("classifiers").isJsonObject()) {
                        JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                        String windowsClassifier = null;
                        if (library.has("natives") && library.get("natives").isJsonObject()
                                && library.getAsJsonObject("natives").has("windows")) {
                            windowsClassifier = library.getAsJsonObject("natives").get("windows").getAsString().replace("${arch}", "64");
                        }
                        if (windowsClassifier == null || !classifiers.has(windowsClassifier)) {
                            for (String key : classifiers.keySet()) if (key.startsWith("natives-windows")) { windowsClassifier = key; break; }
                        }
                        if (windowsClassifier != null && classifiers.has(windowsClassifier)) {
                            JsonObject artifact = classifiers.getAsJsonObject(windowsClassifier);
                            if (artifact.has("path") && artifact.has("url")) {
                                File target = new File(libDir, artifact.get("path").getAsString());
                                if (!isManifestFileValid(target, artifact)) DownloadManager.downloadFile(artifact.get("url").getAsString(), target);
                                if (!isManifestFileValid(target, artifact)) throw new IOException("Не удалось проверить classifier " + target.getName());
                            }
                        }
                    }
                } else if (!name.isBlank()) {
                    downloadMavenLibrary(libDir, library, name, logger);
                }
                if (!name.isBlank()) downloadLegacyNativeIfDeclared(libDir, library, name, logger);
            }
        }
    }

    private static void downloadMavenLibrary(File libDir, JsonObject library, String coordinate,
                                             java.util.function.Consumer<String> logger) throws Exception {
        String[] parts = coordinate.split(":");
        if (parts.length < 3) return;
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        String extension = "jar";
        if (version.contains("@")) { String[] v=version.split("@",2); version=v[0]; extension=v[1]; }
        String relative = group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + "." + extension;
        File target = new File(libDir, relative);
        if (target.isFile() && target.length() > 0) return;
        String repository = library.has("url") ? library.get("url").getAsString() : "https://libraries.minecraft.net/";
        if (!repository.endsWith("/")) repository += "/";
        logger.accept("Загрузка библиотеки загрузчика: " + artifact + " " + version);
        DownloadManager.downloadFile(repository + relative, target);
        if (!target.isFile() || target.length() == 0) throw new IOException("Не удалось загрузить библиотеку " + coordinate);
    }

    private static void downloadLegacyNativeIfDeclared(File libDir, JsonObject library, String coordinate,
                                                        java.util.function.Consumer<String> logger) throws Exception {
        if (!library.has("natives") || !library.get("natives").isJsonObject()) return;
        JsonObject natives = library.getAsJsonObject("natives");
        if (!natives.has("windows")) return;
        String classifier = natives.get("windows").getAsString().replace("${arch}", "64");
        String[] parts = coordinate.split(":"); if (parts.length < 3) return;
        String group = parts[0].replace('.', '/'), artifact = parts[1], version = parts[2];
        String relative = group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + "-" + classifier + ".jar";
        File target = new File(libDir, relative);
        if (target.isFile() && target.length() > 0) return;
        String repository = library.has("url") ? library.get("url").getAsString() : "https://libraries.minecraft.net/";
        if (!repository.endsWith("/")) repository += "/";
        try { logger.accept("Загрузка native-библиотеки: " + artifact); DownloadManager.downloadFile(repository + relative, target); }
        catch (Exception e) { logger.accept("Native classifier пропущен: " + artifact + " — " + e.getMessage()); }
    }

    private static boolean isManifestFileValid(File file, JsonObject metadata) throws Exception {
        if (!file.isFile() || file.length() == 0) return false;
        if (metadata.has("size") && file.length() != metadata.get("size").getAsLong()) return false;
        if (metadata.has("sha1")) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[32768];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b & 0xff));
            if (!hex.toString().equalsIgnoreCase(metadata.get("sha1").getAsString())) return false;
        }
        return true;
    }

    private static void setGameOption(List<String> args, String option, String value) {
        for (int i = 0; i < args.size(); i++) {
            String current = args.get(i);
            if (option.equals(current)) {
                if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) args.set(i + 1, value);
                else args.add(i + 1, value);
                return;
            }
            if (current.startsWith(option + "=")) {
                args.set(i, option + "=" + value);
                return;
            }
        }
        args.add(option);
        args.add(value);
    }

    private static List<String> parseGameArguments(File jsonFile, GameVersion version, Account account,
                                                   File gameDir, File assetsDir, String assetIndexId,
                                                   boolean isOldVersion, String classpath) {
        List<String> args = new ArrayList<>();
        boolean parsed = false;
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("arguments") && root.get("arguments").isJsonObject()) {
                JsonObject arguments = root.getAsJsonObject("arguments");
                if (arguments.has("game") && arguments.get("game").isJsonArray()) {
                    appendManifestArguments(arguments.getAsJsonArray("game"), args, version, account,
                            gameDir, assetsDir, assetIndexId, classpath);
                    parsed = true;
                }
            } else if (root.has("minecraftArguments")) {
                String legacy = resolvePlaceholder(root.get("minecraftArguments").getAsString(),
                        version, account, gameDir, assetsDir, assetIndexId, classpath);
                args.addAll(splitCommandLine(legacy));
                parsed = true;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать аргументы Minecraft: " + e.getMessage(), e);
        }

        if (!parsed) {
            if (isOldVersion) {
                args.add(account != null ? account.getUsername() : "Player");
                args.add(account != null && account.getAccessToken() != null ? account.getAccessToken() : "-");
            } else {
                args.add("--username"); args.add(account != null ? account.getUsername() : "Player");
                args.add("--version"); args.add(version.getId());
                args.add("--gameDir"); args.add(gameDir.getAbsolutePath());
                args.add("--assetsDir"); args.add(assetsDir.getAbsolutePath());
                args.add("--assetIndex"); args.add(assetIndexId);
                args.add("--uuid"); args.add(account != null && account.getUuid() != null ? account.getUuid() : "00000000-0000-0000-0000-000000000000");
                args.add("--accessToken"); args.add(account != null && account.getAccessToken() != null ? account.getAccessToken() : "0");
                args.add("--userType"); args.add(account != null && account.getUserType() != null ? account.getUserType() : "mojang");
            }
        }

        int quickPlayCount = 0;
        List<String> sanitized = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.startsWith("--quickPlay") && ++quickPlayCount > 1) {
                if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) i++;
                continue;
            }
            sanitized.add(arg);
        }
        ensureNoUnresolvedPlaceholders(sanitized);
        return sanitized;
    }

    private static List<String> parseJvmArguments(File jsonFile, GameVersion version, Account account,
                                                   File gameDir, File assetsDir, String assetIndexId,
                                                   String classpath) {
        List<String> result = new ArrayList<>();
        try (FileReader reader = new FileReader(jsonFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("arguments") && root.get("arguments").isJsonObject()) {
                JsonObject arguments = root.getAsJsonObject("arguments");
                if (arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
                    appendManifestArguments(arguments.getAsJsonArray("jvm"), result, version, account,
                            gameDir, assetsDir, assetIndexId, classpath);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать JVM-аргументы Minecraft: " + e.getMessage(), e);
        }
        ensureNoUnresolvedPlaceholders(result);
        return result;
    }

    private static void appendManifestArguments(JsonArray source, List<String> target, GameVersion version,
                                                 Account account, File gameDir, File assetsDir,
                                                 String assetIndexId, String classpath) {
        for (JsonElement element : source) {
            if (element.isJsonPrimitive()) {
                target.add(resolvePlaceholder(element.getAsString(), version, account, gameDir, assetsDir, assetIndexId, classpath));
            } else if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                if (!manifestRulesAllow(entry)) continue;
                JsonElement value = entry.get("value");
                if (value == null) continue;
                if (value.isJsonPrimitive()) {
                    target.add(resolvePlaceholder(value.getAsString(), version, account, gameDir, assetsDir, assetIndexId, classpath));
                } else if (value.isJsonArray()) {
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (item.isJsonPrimitive()) {
                            target.add(resolvePlaceholder(item.getAsString(), version, account, gameDir, assetsDir, assetIndexId, classpath));
                        }
                    }
                }
            }
        }
    }

    private static boolean libraryAllowed(JsonObject lib) {
        if (!lib.has("rules") || !lib.get("rules").isJsonArray()) return true;
        if (manifestRulesAllow(lib)) return true;
        String name = lib.has("name") ? lib.get("name").getAsString() : "";
        if (name.split(":").length != 3) return false;
        for (JsonElement element : lib.getAsJsonArray("rules")) {
            if (!element.isJsonObject()) continue;
            JsonObject rule = element.getAsJsonObject();
            if (rule.has("os")) return false;
            if (rule.has("action") && "disallow".equalsIgnoreCase(rule.get("action").getAsString())) return false;
        }
        return true;
    }

    private static boolean manifestRulesAllow(JsonObject entry) {
        if (!entry.has("rules") || !entry.get("rules").isJsonArray()) return true;
        boolean allowed = false;
        for (JsonElement ruleElement : entry.getAsJsonArray("rules")) {
            if (!ruleElement.isJsonObject()) continue;
            JsonObject rule = ruleElement.getAsJsonObject();
            if (!ruleMatches(rule)) continue;
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    private static boolean ruleMatches(JsonObject rule) {
        if (rule.has("os") && rule.get("os").isJsonObject()) {
            JsonObject os = rule.getAsJsonObject("os");
            if (os.has("name") && !"windows".equalsIgnoreCase(os.get("name").getAsString())) return false;
            if (os.has("arch")) {
                String arch = os.get("arch").getAsString();
                String runtimeArch = System.getProperty("os.arch", "amd64");
                String metadataArch = runtimeArch.equalsIgnoreCase("amd64") ? "x86_64"
                        : runtimeArch.equalsIgnoreCase("aarch64") ? "arm64" : runtimeArch;
                if (!java.util.regex.Pattern.matches(arch, runtimeArch)
                        && !java.util.regex.Pattern.matches(arch, metadataArch)) return false;
            }
            if (os.has("version")) {
                try {
                    if (!java.util.regex.Pattern.compile(os.get("version").getAsString())
                            .matcher(System.getProperty("os.version", "")).find()) return false;
                } catch (Exception ignored) { return false; }
            }
        }
        if (rule.has("features") && rule.get("features").isJsonObject()) {
            JsonObject features = rule.getAsJsonObject("features");
            for (String key : features.keySet()) {
                boolean actual = "has_custom_resolution".equals(key);
                if (features.get(key).getAsBoolean() != actual) return false;
            }
        }
        return true;
    }

    private static String resolvePlaceholder(String template, GameVersion version, Account account,
                                             File gameDir, File assetsDir, String assetIndexId,
                                             String classpath) {
        String username = account != null && account.getUsername() != null ? account.getUsername() : "Player";
        String uuid = account != null && account.getUuid() != null ? account.getUuid() : "00000000-0000-0000-0000-000000000000";
        String accessToken = account != null && account.getAccessToken() != null ? account.getAccessToken() : "0";
        String userType = account != null && account.getUserType() != null ? account.getUserType() : "mojang";
        java.util.Map<String, String> values = new java.util.HashMap<>();
        values.put("auth_player_name", username);
        values.put("version_name", version.getId());
        values.put("game_dir", gameDir.getAbsolutePath());
        values.put("game_directory", gameDir.getAbsolutePath());
        File versionDirectory = gameDir.getParentFile();
        File versionsDirectory = versionDirectory == null ? null : versionDirectory.getParentFile();
        File minecraftRoot = versionsDirectory == null ? null : versionsDirectory.getParentFile();
        File libraryDirectory = minecraftRoot == null ? new File("libraries")
                : new File(minecraftRoot, "libraries");
        values.put("library_directory", libraryDirectory.getAbsolutePath());
        values.put("assets_root", assetsDir.getAbsolutePath());
        values.put("game_assets", resolveGameAssetsDirectory(assetsDir, assetIndexId).getAbsolutePath());
        values.put("natives_directory", new File(gameDir.getParentFile(), "natives").getAbsolutePath());
        values.put("launcher_name", "NEXUS");
        values.put("launcher_version", "1.5.2");
        values.put("classpath_separator", File.pathSeparator);
        values.put("user_properties", "{}");
        values.put("classpath", classpath == null ? "" : classpath);
        values.put("assets_index_name", assetIndexId);
        values.put("auth_uuid", uuid);
        values.put("auth_access_token", accessToken);
        values.put("user_type", userType);
        values.put("clientid", "0");
        values.put("auth_xuid", "0");
        values.put("version_type", "release");
        values.put("resolution_width", "1280");
        values.put("resolution_height", "720");
        values.put("auth_session", "token:" + accessToken + ":" + uuid);
        String resolved = template;
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolved;
    }

    private static File resolveGameAssetsDirectory(File assetsDir, String assetIndexId) {
        File indexFile = new File(new File(assetsDir, "indexes"), assetIndexId + ".json");
        try (FileReader reader = new FileReader(indexFile)) {
            JsonObject index = JsonParser.parseReader(reader).getAsJsonObject();
            if (index.has("virtual") && index.get("virtual").getAsBoolean()) {
                File virtualDir = new File(new File(assetsDir, "virtual"), assetIndexId);
                if (virtualDir.isDirectory()) return virtualDir;
            }
        } catch (Exception ignored) {
        }
        File legacyVirtual = new File(assetsDir, "virtual/legacy");
        if (legacyVirtual.isDirectory()) return legacyVirtual;
        return assetsDir;
    }

    private static void ensureNoUnresolvedPlaceholders(List<String> args) {
        for (String arg : args) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(arg);
            if (matcher.find()) {
                throw new IllegalArgumentException("Не удалось подставить параметр Minecraft: ${" + matcher.group(1) + "}");
            }
        }
    }

    private static List<String> splitCommandLine(String commandLine) {
        List<String> parts = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"|'([^']*)'|([^\\s]+)").matcher(commandLine);
        while (matcher.find()) {
            String part = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            parts.add(part);
        }
        return parts;
    }

    private static void ensureIconsExist(File iconsDir) {
        try {
            File icon16 = new File(iconsDir, "icon_16x16.png");
            File icon32 = new File(iconsDir, "icon_32x32.png");
            byte[] tinyPng = new byte[] {
                (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 
                0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4, (byte)0x89, 0x00, 0x00, 0x00, 
                0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte)0x9C, 0x63, 0x00, 0x01, 0x00, 
                0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte)0xB4, 0x00, 0x00, 0x00, 0x00, 
                0x49, 0x45, 0x4E, 0x44, (byte)0xAE, 0x42, 0x60, (byte)0x82
            };
            if (!icon16.exists() || icon16.length() == 0) {
                Files.write(icon16.toPath(), tinyPng);
            }
            if (!icon32.exists() || icon32.length() == 0) {
                Files.write(icon32.toPath(), tinyPng);
            }
        } catch (Exception ignored) {}
    }

    private static List<File> collectLibraryFiles(File baseDir, File jsonFile, File clientJar, boolean isOldVersion,
                                                        java.util.function.Consumer<String> logger) {
        List<File> files = new ArrayList<>();
        File libDir = new File(baseDir, "libraries");

        if (jsonFile != null && jsonFile.exists()) {
            try (FileReader reader = new FileReader(jsonFile)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("libraries")) {
                    JsonArray libs = root.getAsJsonArray("libraries");
                    for (JsonElement el : libs) {
                        if (!el.isJsonObject()) continue;
                        JsonObject lib = el.getAsJsonObject();
                        String libName = lib.has("name") ? lib.get("name").getAsString() : "";
                        if (!libraryAllowed(lib)) {
                            if (libName.split(":").length == 3) logger.accept("Библиотека пропущена правилами манифеста: " + libName);
                            continue;
                        }
                        File libFile = null;
                        JsonObject artifact = null;
                        if (lib.has("downloads") && lib.get("downloads").isJsonObject() && lib.getAsJsonObject("downloads").has("artifact")) {
                            artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                            if (artifact.has("path")) libFile = new File(libDir, artifact.get("path").getAsString());
                        }
                        if (libFile == null && !libName.isBlank()) libFile = mavenCoordinateFile(libDir, libName);
                        if (libFile != null && !libFile.isFile()) {
                            try {
                                if (artifact != null && artifact.has("url")) DownloadManager.downloadFile(artifact.get("url").getAsString(), libFile);
                                else if (!libName.isBlank()) downloadMavenLibrary(libDir, lib, libName, logger);
                            } catch (Exception downloadError) {
                                logger.accept("ОШИБКА: не удалось скачать библиотеку " + libName + ": " + downloadError.getMessage());
                            }
                        }
                        if (libFile != null && libFile.isFile()) {
                            files.add(libFile);
                            if (libName.startsWith("org.lwjgl:")) logger.accept("LWJGL для текущей ОС: " + libName);
                        } else if (libFile != null) {
                            logger.accept("ОШИБКА: библиотека отсутствует на диске: " + libName);
                        }
                    }
                }
            } catch (Exception e) {
                addJarsRecursive(libDir, files);
            }
        }
        if (files.isEmpty()) {
            File localLib = new File(clientJar.getParentFile(), "lib");
            addJarsRecursive(localLib, files);
            if (files.isEmpty()) {
                logger.accept("ВНИМАНИЕ: в JSON версии нет подходящих библиотек; глобальная папка libraries не добавляется во избежание смешивания LWJGL.");
            }
        }
        return files;
    }

    private static File mavenCoordinateFile(File libDir, String coordinate) {
        try {
            String[] parts = coordinate.split(":"); if (parts.length < 3) return null;
            if (parts.length > 3 && parts[3].toLowerCase(java.util.Locale.ROOT).contains("natives")) return null;
            String group=parts[0].replace('.', '/'), artifact=parts[1], version=parts[2], classifier=parts.length>3?"-"+parts[3]:"", ext="jar";
            if (version.contains("@")) { String[] v=version.split("@",2); version=v[0]; ext=v[1]; }
            return new File(libDir, group+"/"+artifact+"/"+version+"/"+artifact+"-"+version+classifier+"."+ext);
        } catch (Exception ignored) { return null; }
    }

    private static void addJarsRecursive(File dir, List<File> files) {
        if (dir == null || !dir.exists()) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                addJarsRecursive(f, files);
            } else if (f.getName().endsWith(".jar") || f.getName().endsWith(".zip")) {
                files.add(f);
            }
        }
    }

    private static void unsealLwjglJars(List<File> libFiles) {
        for (File file : libFiles) {
            if (file.getName().toLowerCase().contains("lwjgl")) {
                removeJarSealing(file);
            }
        }
    }

    private static void removeJarSealing(File jarFile) {
        try {
            JarFile jar = new JarFile(jarFile, false);
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                jar.close();
                return;
            }
            boolean modified = false;
            Attributes mainAttrs = manifest.getMainAttributes();
            if (mainAttrs.containsKey(Attributes.Name.SEALED)) {
                mainAttrs.remove(Attributes.Name.SEALED);
                modified = true;
            }
            for (Attributes attrs : manifest.getEntries().values()) {
                if (attrs.containsKey(Attributes.Name.SEALED)) {
                    attrs.remove(Attributes.Name.SEALED);
                    modified = true;
                }
            }
            jar.close();

            if (modified) {
                File tempJar = new File(jarFile.getParentFile(), jarFile.getName() + ".tmp");
                try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), manifest);
                     JarFile srcJar = new JarFile(jarFile)) {
                    Enumeration<JarEntry> entries = srcJar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                            continue;
                        }
                        jos.putNextEntry(new JarEntry(entry.getName()));
                        try (java.io.InputStream is = srcJar.getInputStream(entry)) {
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                jos.write(buffer, 0, bytesRead);
                            }
                        }
                        jos.closeEntry();
                    }
                }
                if (jarFile.delete()) {
                    tempJar.renameTo(jarFile);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void createClasspathJar(File cpJar, List<File> libFiles, File clientJar, String mainClass) {
        try {
            Manifest manifest = new Manifest();
            Attributes attrs = manifest.getMainAttributes();
            attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attrs.put(Attributes.Name.MAIN_CLASS, mainClass);

            StringBuilder sb = new StringBuilder();
            for (File f : libFiles) {
                sb.append(f.toURI().toURL().toString()).append(" ");
            }
            sb.append(clientJar.toURI().toURL().toString());

            attrs.put(Attributes.Name.CLASS_PATH, sb.toString());

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(cpJar), manifest)) {
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}