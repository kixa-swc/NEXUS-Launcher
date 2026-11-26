package com.launcher.core;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CrashAnalyzer {
    private static final long MAX_REPORT_BYTES = 1_000_000;
    private static final long MAX_LOG_TAIL_BYTES = 256_000;
    private static final long CLOCK_SLACK_MS = 5_000;

    private CrashAnalyzer() { }

    public record Input(int exitCode, File gameDir, long startedAtMs, List<String> outputTail, int ramMb,
                        String minecraft, String loader) { }

    public record Report(String headline, String cause, List<String> advice, List<String> details, List<String> suspects,
                         String excerpt, File reportFile, File logFile, String shareText, boolean modRelated,
                         boolean memoryRelated, boolean identified, int exitCode) { }

    private enum Scope { ALL, JVM_HEADER }

    private record Rule(Pattern pattern, Scope scope, String headline, String cause, List<String> advice,
                        boolean mods, boolean memory) { }

    private static Rule rule(String regex, Scope scope, String headline, String cause, boolean mods, boolean memory, String... advice) {
        return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE), scope, headline, cause,
                List.of(advice), mods, memory);
    }

    private static final List<Rule> RULES = List.of(
            rule("There is insufficient memory for the Java Runtime|Native memory allocation \\((?:mmap|malloc)\\) failed|Out of Memory Error \\(",
                    Scope.JVM_HEADER, "Не хватило памяти компьютера",
                    "Системе не удалось выделить игре столько оперативной памяти, сколько указано в настройках.",
                    false, true,
                    "Уменьшите объём ОЗУ для игры в настройках лаунчера (хватит значения заметно ниже свободной памяти).",
                    "Закройте браузер и другие тяжёлые программы перед запуском.",
                    "Если памяти мало, увеличьте файл подкачки Windows."),
            rule("Could not reserve enough space for.{0,40}heap|Invalid maximum heap size|Could not create the Java Virtual Machine",
                    Scope.ALL, "Java не смогла выделить память",
                    "Виртуальная машина Java не запустилась: запрошенный объём ОЗУ недоступен.",
                    false, true,
                    "Уменьшите объём ОЗУ в настройках лаунчера.",
                    "Проверьте, что выбрана 64-битная Java (кнопка «Авто» в настройках подберёт подходящую)."),
            rule("java\\.lang\\.OutOfMemoryError\\s*:?\\s*(?:Java heap space|GC overhead limit exceeded|Requested array size)|OutOfMemoryError: Java heap space",
                    Scope.ALL, "Игре не хватило выделенной памяти",
                    "Minecraft исчерпал память, которую ему дали. Чаще всего так бывает со сборками на много модов, шейдерами и большой дальностью прорисовки.",
                    false, true,
                    "Увеличьте ОЗУ для игры в настройках лаунчера. Для лёгких сборок хватает 3–4 ГБ, для больших 6–8 ГБ.",
                    "Не выделяйте больше половины физической памяти компьютера.",
                    "Снизьте дальность прорисовки и отключите шейдеры."),
            rule("UnsupportedClassVersionError|compiled by a more recent version of the Java Runtime|class file version \\d+",
                    Scope.ALL, "Не та версия Java",
                    "Мод или загрузчик собран под более новую Java, чем та, которой запущена игра.",
                    false, false,
                    "В настройках лаунчера нажмите «Авто» рядом с путём к Java: NEXUS подберёт подходящую версию.",
                    "Если вы указывали java.exe вручную, выберите более новую Java или очистите поле."),
            rule("Could not find or load main class|Unable to access jarfile|Invalid or corrupt jarfile|NoClassDefFoundError: net/minecraft",
                    Scope.ALL, "Повреждены файлы игры",
                    "Не нашёлся или повреждён основной файл Minecraft.",
                    false, false,
                    "Запустите игру ещё раз: лаунчер перепроверит и докачает файлы.",
                    "Если ошибка повторяется, удалите папку этой версии в «versions» и запустите заново."),
            rule("Failed to locate library: |UnsatisfiedLinkError|Failed to load library|Failed to extract LWJGL|lwjgl.{0,60}(?:\\.dll|\\.so).{0,60}(?:not found|failed)",
                    Scope.ALL, "Не загрузились системные библиотеки игры",
                    "Игре не удалось загрузить нативные библиотеки (LWJGL и подобные). Часто их удаляет или блокирует антивирус.",
                    false, false,
                    "Добавьте папку игры и папку NEXUS в исключения антивируса и запустите игру снова.",
                    "Лаунчер при каждом запуске заново распаковывает библиотеки — повторная попытка часто помогает."),
            rule("No space left on device|There is not enough space on the disk|Недостаточно места на диске",
                    Scope.ALL, "Закончилось место на диске",
                    "Игре или лаунчеру некуда записывать файлы.",
                    false, false,
                    "Освободите место на диске, где лежит папка игры, и запустите снова."),
            rule("Incompatible mods found|Some of your mods are incompatible with the game or each other|which is missing!|Missing or unsupported mandatory dependencies|Mod ID: '[^']+', Requested by:|Mod [^\\n]{0,80} requires [^\\n]{0,80}(?:minecraft|fabricloader|forge|neoforge)",
                    Scope.ALL, "Моды несовместимы или не хватает зависимостей",
                    "Загрузчик отказался запускать игру: одному из модов нужен другой мод (например, Fabric API) или другая версия Minecraft.",
                    true, false,
                    "Откройте «Менеджер модов» и поставьте недостающие моды из списка ниже — зависимости обычно есть на Modrinth.",
                    "Проверьте, что все моды сделаны именно под эту версию Minecraft и этот загрузчик.",
                    "Если сомневаетесь, временно отключите последние добавленные моды и запустите снова."),
            rule("DuplicateModsFoundException|Found duplicate mods|[Dd]uplicate mod(?:s| ID| id)?\\b|present in multiple (?:jars|files)|more than one (?:mod|version) of",
                    Scope.ALL, "Один мод установлен дважды",
                    "В папке mods лежат две копии одного мода (или две версии), и загрузчик не знает, какую выбрать.",
                    true, false,
                    "Откройте папку модов и оставьте только одну версию каждого мода — в «Менеджере модов» лишние можно удалить или выключить."),
            rule("Mixin apply (?:for mod \\S+ )?failed|MixinApplyError|MixinTransformerError|InvalidInjectionException|Critical injection failure|Mixin transformation of .{1,120} failed|MixinPrepareError",
                    Scope.ALL, "Мод конфликтует с другим модом или версией игры",
                    "Мод не смог внести изменения в код игры (ошибка Mixin). Это почти всегда значит, что он сделан для другой версии Minecraft или спорит с другим модом.",
                    true, false,
                    "Отключите мод, названный ниже, и запустите игру снова.",
                    "Обновите мод до версии под вашу версию Minecraft.",
                    "Если подозреваемых нет, отключайте недавно добавленные моды по одному."),
            rule("NoSuchMethodError|NoSuchFieldError|AbstractMethodError|IncompatibleClassChangeError|NoClassDefFoundError|ClassNotFoundException",
                    Scope.ALL, "Моды разных версий не подходят друг другу",
                    "Игра ищет код, которого нет: мод собран под другую версию Minecraft, загрузчика или другого мода.",
                    true, false,
                    "Обновите моды и их зависимости (например, Fabric API) до версий под вашу версию игры.",
                    "Отключите последние добавленные моды и проверьте, запускается ли игра.",
                    "Проверьте, что не смешаны моды для Fabric и Forge."),
            rule("EXCEPTION_ACCESS_VIOLATION|SIGSEGV|(?:ig\\d+i?cd\\d*|igd\\w*|nvoglv\\d*|nvwgf2um\\w*|nvd3dum\\w*|atio6axx|atioglxx|aticfx\\d*|amdvlk\\d*|ig9icd\\d*|ig75icd\\d*|ig4icd\\d*)\\.dll|(?:libnvidia-glcore|iris_dri|radeonsi_dri|i965_dri)\\.so",
                    Scope.JVM_HEADER, "Сбой видеодрайвера",
                    "Игра упала внутри системной библиотеки — судя по отчёту, это драйвер видеокарты или программа, которая вмешивается в отрисовку.",
                    false, false,
                    "Обновите драйвер видеокарты с сайта NVIDIA, AMD или Intel.",
                    "На ноутбуке с двумя видеокартами включите для java.exe производительную видеокарту в настройках графики Windows.",
                    "Закройте оверлеи (Discord, MSI Afterburner, GeForce Experience, записывающие программы).",
                    "Если вы ставили шейдеры или оптимизационные моды, отключите их и проверьте снова."),
            rule("GLFW error|GLFW_PLATFORM_ERROR|Failed to create window|Pixel format not accelerated|Couldn'?t set pixel format|Could not create context|OpenGL.{0,40}(?:error|not supported|version)|WGL: |Failed to initialize GLFW",
                    Scope.ALL, "Проблема с видеокартой или OpenGL",
                    "Игра не смогла создать окно или контекст OpenGL.",
                    false, false,
                    "Обновите драйвер видеокарты. Старым картам может не хватать поддержки OpenGL для новых версий — попробуйте более раннюю версию Minecraft.",
                    "Проверьте, что игра запускается на нужной видеокарте, а не на встроенной в процессор.",
                    "В удалённом рабочем столе или виртуальной машине OpenGL часто недоступен."),
            rule("ZipException|zip END header not found|invalid LOC header|invalid (?:stored block|distance|code)|Not in GZIP format|Invalid or corrupt|ZipFile invalid",
                    Scope.ALL, "Повреждённый файл мода или мира",
                    "Какой-то файл не читается как архив: скорее всего, мод докачался не до конца или повреждён.",
                    true, false,
                    "Переустановите недавно добавленные моды (в «Менеджере модов» их можно удалить и поставить заново).",
                    "Если ошибка называет файл мира, восстановите его из резервной копии."),
            rule("Exception ticking world|Ticking (?:entity|block entity)|Exception in server tick loop|Rendering (?:screen|entity|overlay)|Exception rendering|Initializing game|Registering (?:block|item|entity)",
                    Scope.ALL, "Сбой внутри игры",
                    "Игра упала в момент обычной работы — при обработке мира, существа или отрисовке.",
                    true, false,
                    "Если в игре есть моды, отключите подозреваемые (они показаны ниже) и проверьте снова.",
                    "Не получается — запустите без модов (Vanilla) и проверьте, повторяется ли вылет.",
                    "Если вылет повторяется на конкретном месте мира, сделайте резервную копию мира и откройте отчёт ниже.")
    );

    private static final Pattern CRASH_PATH_LINE = Pattern.compile("Crash report saved to:\\s*(?:#@!@#\\s*)?(.+?)\\s*$");
    private static final Pattern SUSPECT_LINE = Pattern.compile("^\\s*(.+?)\\s*\\(([\\w.\\-]+)\\)(?:,?\\s*Version:\\s*(\\S+))?\\s*$");

    public static Report analyze(Input in) {
        if (in == null) return null;
        File gameDir = in.gameDir();
        List<String> tail = in.outputTail() == null ? List.of() : in.outputTail();

        File reportFile = findReportFile(gameDir, tail, in.startedAtMs());
        boolean jvmCrash = reportFile != null && reportFile.getName().startsWith("hs_err_pid");
        String reportText = reportFile == null ? "" : readHead(reportFile, MAX_REPORT_BYTES);
        File latest = gameDir == null ? null : new File(gameDir, "logs/latest.log");
        boolean latestFresh = latest != null && latest.isFile() && latest.lastModified() >= in.startedAtMs() - CLOCK_SLACK_MS;
        String latestTail = latestFresh ? readTail(latest, MAX_LOG_TAIL_BYTES) : "";
        String outputText = String.join("\n", tail);

        String jvmHeader = jvmCrash ? jvmHeader(reportText) : "";
        String haystack = reportText + "\n" + outputText + "\n" + latestTail;

        List<Rule> matched = new ArrayList<>();
        for (Rule r : RULES) {
            String text = r.scope() == Scope.JVM_HEADER ? jvmHeader : haystack;
            if (!text.isEmpty() && r.pattern().matcher(text).find()) matched.add(r);
        }

        if (matched.isEmpty() && reportFile == null && windowsExit(in.exitCode()) == null && !endsWithFailure(tail)) return null;

        Set<String> suspects = new LinkedHashSet<>();
        collectSuspects(reportText, suspects);
        collectSuspects(outputText + "\n" + latestTail, suspects);
        List<String> details = collectDetails(haystack);

        String root = rootException(reportText.isEmpty() ? outputText + "\n" + latestTail : reportText);
        String description = describe(reportText);

        String headline, cause;
        List<String> advice = new ArrayList<>();
        boolean mods = false, memory = false, identified = !matched.isEmpty();
        if (identified) {
            Rule main = matched.get(0);
            headline = main.headline();
            cause = main.cause();
            advice.addAll(main.advice());
            mods = main.mods();
            memory = main.memory();
            if (matched.size() > 1) {
                List<String> others = new ArrayList<>();
                for (int i = 1; i < matched.size() && others.size() < 2; i++) others.add(matched.get(i).headline().toLowerCase(Locale.ROOT));
                advice.add("Также в отчёте замечено: " + String.join("; ", others) + ".");
            }
        } else {
            WindowsExit exit = windowsExit(in.exitCode());
            headline = exit != null ? exit.headline() : "Игра завершилась с ошибкой";
            cause = exit != null ? exit.cause()
                    : "Точную причину автоматически определить не удалось" + (reportFile == null ? ": игра не оставила отчёта о вылете." : ".");
            if (exit != null) advice.addAll(exit.advice());
            advice.add("Откройте отчёт ниже или журнал игры в «Логи и мониторинг» — последние строки обычно называют виновника.");
            if (!"Vanilla".equalsIgnoreCase(in.loader())) advice.add("Запустите ту же версию без модов (Vanilla): если всё работает, причина в одном из модов.");
            advice.add("Если не получается разобраться самому, отправьте лог — по ссылке его можно показать тому, кто поможет.");
            mods = !"Vanilla".equalsIgnoreCase(in.loader());
        }
        if (!suspects.isEmpty()) {
            mods = true;
            advice.add(0, "Подозреваемые моды: " + String.join(", ", suspects) + ". Отключите их в «Менеджере модов» и запустите снова.");
        }
        if (memory && in.ramMb() > 0 && headline.startsWith("Игре не хватило")) {
            cause += " Сейчас для игры выделено " + in.ramMb() + " МБ.";
        }
        if (!identified && description != null && !description.isBlank()) cause += " Описание из отчёта: " + description + ".";
        if (!identified && root != null && !root.isBlank()) details.add(0, shorten(root, 300));
        else if (identified && root != null && details.isEmpty()) details.add(shorten(root, 300));

        String excerpt = buildExcerpt(reportFile, reportText, tail, jvmCrash);
        String share = buildShareText(in, reportFile, reportText, tail, latestTail, headline);
        File logFile = latest != null && latest.isFile() ? latest : null;
        return new Report(headline, cause, advice, limit(details, 8), new ArrayList<>(limit(new ArrayList<>(suspects), 6)),
                excerpt, reportFile, logFile, share, mods, memory, identified, in.exitCode());
    }

    private static File findReportFile(File gameDir, List<String> tail, long startedAt) {
        if (gameDir == null) return null;
        for (int i = tail.size() - 1; i >= 0; i--) {
            Matcher m = CRASH_PATH_LINE.matcher(tail.get(i));
            if (m.find()) {
                File f = new File(m.group(1).replace("#@!@#", "").trim());
                if (f.isFile()) return f;
            }
        }
        File newest = null;
        File[] reports = new File(gameDir, "crash-reports").listFiles((d, n) -> n.startsWith("crash-") && n.endsWith(".txt"));
        File[] jvm = gameDir.listFiles((d, n) -> n.startsWith("hs_err_pid") && n.endsWith(".log"));
        for (File[] group : new File[][]{reports, jvm}) {
            if (group == null) continue;
            for (File f : group) {
                if (f.lastModified() < startedAt - CLOCK_SLACK_MS) continue;
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
        }
        return newest;
    }

    private static String jvmHeader(String text) {
        StringBuilder header = new StringBuilder();
        int count = 0;
        for (String line : text.split("\\R")) {
            if (count++ > 90) break;
            if (line.startsWith("#")) header.append(line).append('\n');
        }
        return header.toString();
    }

    private static void collectSuspects(String text, Set<String> out) {
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length && out.size() < 8; i++) {
            String line = lines[i];
            Matcher head = Pattern.compile("^\\s*Suspected Mods?:\\s*(.*)$").matcher(line);
            if (head.find()) {
                String first = head.group(1).trim();
                if (!first.isEmpty() && !first.equalsIgnoreCase("none") && !first.startsWith("UNKNOWN")) addSuspect(first, out);
                for (int j = i + 1; j < lines.length && j < i + 10; j++) {
                    String next = lines[j];
                    if (next.isBlank() || !Character.isWhitespace(next.charAt(0))) break;
                    addSuspect(next.trim(), out);
                }
            }
        }
        Matcher mixin = Pattern.compile("Mixin apply for mod (\\S+) failed").matcher(text);
        while (mixin.find() && out.size() < 8) out.add(mixin.group(1));
        Matcher forge = Pattern.compile("Mod ID: '([^']+)', Requested by: '([^']+)'").matcher(text);
        while (forge.find() && out.size() < 8) out.add(forge.group(2));
    }

    private static void addSuspect(String line, Set<String> out) {
        for (String piece : line.split(",\\s*(?=[^,]+\\([\\w.\\-]+\\))")) {
            Matcher m = SUSPECT_LINE.matcher(piece.trim());
            String name = m.find() ? m.group(1) + (m.group(3) != null ? " " + m.group(3) : "") : piece.trim();
            if (!name.isBlank() && !name.equalsIgnoreCase("none") && name.length() < 80) out.add(name);
        }
    }

    private static List<String> collectDetails(String text) {
        List<String> details = new ArrayList<>();
        Matcher fabric = Pattern.compile("^\\s*[-*•]?\\s*(?:Mod|Install|Replace|Remove|Update|Could not find)\\b.*(?:requires|incompatible|conflict|which is missing|depends on|breaks).*$",
                Pattern.MULTILINE | Pattern.CASE_INSENSITIVE).matcher(text);
        while (fabric.find() && details.size() < 6) {
            String line = fabric.group().trim().replaceFirst("^[-*•]\\s*", "");
            if (!details.contains(line)) details.add(shorten(line, 240));
        }
        Matcher forge = Pattern.compile("Mod ID: '([^']+)', Requested by: '([^']+)', Expected range: '([^']*)', Actual version: '([^']*)'").matcher(text);
        while (forge.find() && details.size() < 8) {
            String actual = forge.group(4).equalsIgnoreCase("[MISSING]") ? "не установлен" : "у вас " + forge.group(4);
            details.add("Моду «" + forge.group(2) + "» нужен «" + forge.group(1) + "» версии " + forge.group(3) + " (" + actual + ")");
        }
        return details;
    }

    private static String rootException(String text) {
        String last = null;
        Pattern p = Pattern.compile("^(?:Exception in thread \"[^\"]*\"\\s+)?(?:Caused by:\\s*)?([\\w.$]+(?:Exception|Error|Throwable))(?::\\s*(.*))?$");
        for (String line : text.split("\\R")) {
            if (line.startsWith("A detailed walkthrough of the error")) break;
            String t = line.trim().replaceFirst("^(?:\\[[^\\]]*\\]\\s*)+:?\\s*", "");
            Matcher m = p.matcher(t);
            if (m.find()) last = t.replaceFirst("^Exception in thread \"[^\"]*\"\\s+", "").replaceFirst("^Caused by:\\s*", "");
        }
        return last;
    }

    private static String describe(String reportText) {
        Matcher m = Pattern.compile("^Description:\\s*(.+)$", Pattern.MULTILINE).matcher(reportText);
        if (!m.find()) return null;
        String d = m.group(1).trim();
        return switch (d.toLowerCase(Locale.ROOT)) {
            case "ticking entity" -> "ошибка при обработке существа (ticking entity)";
            case "ticking block entity" -> "ошибка при обработке блока с данными (ticking block entity)";
            case "exception ticking world" -> "ошибка при обработке мира";
            case "rendering screen" -> "ошибка при отрисовке окна";
            case "initializing game" -> "ошибка при запуске игры";
            case "unexpected error" -> "непредвиденная ошибка";
            default -> d;
        };
    }

    private record WindowsExit(String headline, String cause, List<String> advice) { }

    private static WindowsExit windowsExit(int code) {
        return switch (code) {
            case -1073741819 -> new WindowsExit("Игра упала на уровне системы",
                    "Процесс завершился с ошибкой доступа к памяти (0xC0000005). Обычно это драйвер видеокарты, оверлей или антивирус.",
                    List.of("Обновите драйвер видеокарты.", "Закройте оверлеи и программы записи экрана.", "Добавьте папку игры в исключения антивируса."));
            case -1073740791 -> new WindowsExit("Игра упала на уровне системы",
                    "Процесс завершился с кодом 0xC0000409 — аварийный сбой в нативной библиотеке, чаще всего в драйвере видеокарты.",
                    List.of("Обновите драйвер видеокарты.", "Закройте оверлеи (Discord, GeForce Experience, MSI Afterburner)."));
            case 137 -> new WindowsExit("Игру принудительно завершила система",
                    "Процесс убит извне (код 137): чаще всего системе не хватило памяти.",
                    List.of("Уменьшите ОЗУ для игры в настройках и закройте лишние программы."));
            default -> null;
        };
    }

    private static String buildExcerpt(File reportFile, String reportText, List<String> tail, boolean jvmCrash) {
        if (reportFile != null && !reportText.isBlank()) {
            List<String> lines = new ArrayList<>();
            for (String line : reportText.split("\\R")) {
                if (!jvmCrash && line.startsWith("A detailed walkthrough of the error")) break;
                if (jvmCrash && lines.size() >= 24) break;
                lines.add(line);
                if (lines.size() >= 40) break;
            }
            return String.join("\n", lines).trim();
        }
        int from = Math.max(0, tail.size() - 30);
        return String.join("\n", tail.subList(from, tail.size())).trim();
    }

    private static String buildShareText(Input in, File reportFile, String reportText, List<String> tail, String latestTail, String headline) {
        StringBuilder sb = new StringBuilder();
        sb.append("NEXUS Launcher — разбор вылета\n");
        sb.append("Вывод: ").append(headline).append('\n');
        sb.append("Minecraft: ").append(nullToDash(in.minecraft())).append("  Загрузчик: ").append(nullToDash(in.loader()))
                .append("  ОЗУ: ").append(in.ramMb()).append(" МБ  Код выхода: ").append(in.exitCode()).append('\n');
        sb.append("Java: ").append(System.getProperty("java.version", "?")).append("  ОС: ").append(System.getProperty("os.name", "?")).append("\n\n");
        if (reportFile != null && !reportText.isBlank()) {
            sb.append("===== ").append(reportFile.getName()).append(" =====\n").append(reportText.trim()).append("\n\n");
        }
        if (!tail.isEmpty()) {
            sb.append("===== Вывод игры (последние строки) =====\n").append(String.join("\n", tail)).append("\n\n");
        }
        if (!latestTail.isBlank()) {
            sb.append("===== logs/latest.log (конец) =====\n").append(latestTail.trim()).append('\n');
        }
        return redact(sb.toString());
    }

    public static String redact(String text) {
        if (text == null) return "";
        String result = text;
        result = result.replaceAll("(?i)(--accessToken\\s+)\\S+", "$1[REDACTED]");
        result = result.replaceAll("(?i)(--(?:clientId|xuid)\\s+)\\S+", "$1[REDACTED]");
        result = result.replaceAll("token:[A-Za-z0-9_\\-.:]+", "token:[REDACTED]");
        result = result.replaceAll("eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}", "[REDACTED_TOKEN]");
        result = result.replaceAll("(?i)([A-Z]:[\\\\/]+Users[\\\\/]+)[^\\\\/\\s\"']+", "$1<user>");
        result = result.replaceAll("(/home/|/Users/)[^/\\s\"']+", "$1<user>");
        return result;
    }

    private static String readHead(File file, long maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] buf = new byte[(int) Math.min(maxBytes, raf.length())];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readTail(File file, long maxBytes) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long start = Math.max(0, length - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (length - start)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = text.indexOf('\n');
                if (nl >= 0) text = text.substring(nl + 1);
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean endsWithFailure(List<String> tail) {
        Pattern failure = Pattern.compile("exception|fatal|crash|caused by|\\bat [\\w.$]+\\(", Pattern.CASE_INSENSITIVE);
        for (int i = Math.max(0, tail.size() - 15); i < tail.size(); i++) {
            if (failure.matcher(tail.get(i)).find()) return true;
        }
        return false;
    }

    private static <T> List<T> limit(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static List<String> ruleHeadlines() {
        return Arrays.stream(RULES.toArray(new Rule[0])).map(Rule::headline).toList();
    }
}
