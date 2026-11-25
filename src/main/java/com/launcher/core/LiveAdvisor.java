package com.launcher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LiveAdvisor {
    private LiveAdvisor() { }

    private static final String[][] CLASSICS = {
            {"1.7.10", "золотая классика: Thaumcraft, IndustrialCraft 2, Twilight Forest, Forge-сборки эпохи 2014–2015. Самая «ламповая» ностальгия"},
            {"1.8.9", "версия для PvP и мини-игр, на ней живут Hypixel и многие серверы"},
            {"1.12.2", "огромная база модов и сборок старой школы (RLCraft, Tekkit, техно- и магия-сборки). Лучший выбор «и ностальгия, и много модов»"},
            {"1.16.5", "Nether Update: много сборок, есть Forge и Fabric, игра уже выглядит современно"},
            {"1.18.2", "новые пещеры и высота мира, стабильные сборки"},
            {"1.19.2", "последняя версия с Forge-классикой перед большими изменениями; много сборок"},
            {"1.20.1", "самая популярная современная база: Forge, Fabric и больше всего свежих сборок"},
            {"1.21.1", "современные сборки на NeoForge и Fabric"}
    };

    private static String low(String q) { return q == null ? "" : q.toLowerCase(Locale.ROOT); }

    private static boolean any(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    public static boolean isVersionChoice(String q) {
        String s = low(q);
        boolean version = any(s, "верси", "version");
        return any(s, "ностальг", "олдскул", "олд скул", "как раньше", "в детств")
                || (version && any(s, "выбрать", "выбери", "посоветуй", "какую", "какая лучше", "лучше всего", "для сборк", "для модов", "для игры", "поставить"));
    }

    private static boolean isModTopic(String q) {
        String s = low(q);
        return s.matches("(?s).*(?<![а-яё])мод(ы|ов|ам|ах|ами|а|у|е)?(?![а-яё]).*")
                || any(s, "шейдер", "сборк", "ресурспак", "текстур", "fps", "фпс", "памят", "озу")
                || !LiveKnowledge.namedThings(q).isEmpty();
    }

    public static boolean isLatestVersionQuestion(String q) {
        String s = low(q);
        if (isVersionChoice(q) || isModTopic(q)) return false;
        boolean about = any(s, "верси", "версия", "pre-release", "prerelease", "pre release", "пре-релиз", "пререлиз", "пре релиз",
                "снапшот", "snapshot", "релиз", "release candidate", "кандидат", "-pre", "-rc", "обновлени", "апдейт", "update");
        boolean fresh = any(s, "послед", "свеж", "актуальн", "нов", "вышл", "выйд", "сейчас", "сегодня", "latest", "current", "newest", "pre", "снапшот", "snapshot");
        return about && fresh;
    }

    public static boolean isNewsQuestion(String q) {
        String s = low(q);
        if (isVersionChoice(q) || isModTopic(q)) return false;
        return any(s, "новост", "что нового", "что новенького", "патчноут", "патч-ноут", "патч ноут", "changelog", "чейнджлог", "моджанг", "mojang", "анонс");
    }

    public static String direct(String question) {
        if (isNewsQuestion(question) && !isLatestVersionQuestion(question)) return news();
        if (isLatestVersionQuestion(question)) return latest();
        return null;
    }

    public static String latest() {
        LiveKnowledge.Versions v = LiveKnowledge.versions();
        if (v == null || v.latestRelease() == null)
            return "Не получилось связаться с серверами Mojang, поэтому не могу назвать актуальную версию — не хочу угадывать. "
                    + "Проверьте интернет и спросите ещё раз: данные запрашиваются заново при каждом вопросе.";
        StringBuilder sb = new StringBuilder();
        sb.append("Свежие версии Minecraft Java Edition (данные Mojang на сегодня):\n\n");
        sb.append("• Последний стабильный релиз: ").append(v.latestRelease());
        if (!v.latestReleaseDate().isEmpty()) sb.append(" — вышел ").append(v.latestReleaseDate());
        sb.append('\n');
        String snap = v.latestSnapshot();
        if (snap != null && !snap.equals(v.latestRelease())) {
            sb.append("• Самая новая тестовая сборка: ").append(snap).append(" (").append(LiveKnowledge.kindOf(snap));
            if (!v.latestSnapshotDate().isEmpty()) sb.append(", ").append(v.latestSnapshotDate());
            sb.append(")\n");
            String base = LiveKnowledge.baseOf(snap);
            if (base != null) {
                if (LiveKnowledge.compareMc(base, v.latestRelease()) > 0)
                    sb.append("  Это проба будущего релиза ").append(base).append(" — он ещё не вышел.\n");
                else
                    sb.append("  Релиз ").append(base).append(" уже вышел; это сборка для следующего этапа.\n");
            }
        }
        if (!v.recentSnapshots().isEmpty()) {
            sb.append("\nПоследние тестовые сборки (новые сверху):\n");
            for (String s : v.recentSnapshots()) sb.append("• ").append(s).append('\n');
        }
        if (!v.recent().isEmpty()) sb.append("\nНедавние релизы: ").append(String.join(", ", v.recent())).append('\n');
        List<String[]> news = LiveKnowledge.newsEntries(1);
        if (!news.isEmpty()) {
            String[] n = news.get(0);
            sb.append("\nСвежее у Mojang: ").append(n[0].isEmpty() ? "" : n[0] + " — ").append(n[1].isEmpty() ? "Minecraft " + n[3] : n[1]).append('\n');
        }
        sb.append("\nТестовые сборки и пре-релизы нестабильны: для модов и сборок берите стабильный релиз.");
        return sb.toString().trim();
    }

    public static String news() {
        List<String[]> items = LiveKnowledge.newsEntries(6);
        if (items.isEmpty())
            return "Не получилось загрузить новости Mojang — похоже, нет связи с их сервером. Попробуйте ещё раз чуть позже или откройте вкладку «Новости» в лаунчере.";
        StringBuilder sb = new StringBuilder("Последние новости и патчноуты Mojang (новые сверху):\n\n");
        for (String[] n : items) {
            String title = n[1].isEmpty() ? "Minecraft " + n[3] : n[1];
            sb.append("• ").append(n[0].isEmpty() ? "" : n[0] + " — ").append(title);
            if (!n[3].isEmpty() && !title.contains(n[3])) sb.append(" [").append(n[3]).append(']');
            sb.append('\n');
            String text = n[2];
            if (text.length() > 190) text = text.substring(0, 190) + "…";
            if (!text.isEmpty()) sb.append("   ").append(text).append('\n');
        }
        LiveKnowledge.Versions v = LiveKnowledge.versions();
        if (v != null && v.latestRelease() != null) {
            sb.append("\nСейчас: релиз ").append(v.latestRelease());
            if (v.latestSnapshot() != null && !v.latestSnapshot().equals(v.latestRelease()))
                sb.append(", тестовая сборка ").append(v.latestSnapshot()).append(" (").append(LiveKnowledge.kindOf(v.latestSnapshot())).append(')');
            sb.append('.');
        }
        return sb.toString().trim();
    }

    public static String versionGuideBlock(String question) {
        return isVersionChoice(question) ? "\nГАЙД ПО ВЫБОРУ ВЕРСИИ (с живыми числами из Modrinth):\n" + guideLines() : "";
    }

    public static String versionGuide(String question, HardwareInfo.Info hw) {
        String s = low(question);
        StringBuilder sb = new StringBuilder();
        boolean nostalgia = any(s, "ностальг", "олдскул", "олд скул", "как раньше", "в детств");
        boolean packs = any(s, "сборк", "модпак", "modpack");
        if (nostalgia) sb.append("Для ностальгии лучше всего три версии:\n• 1.7.10 — «та самая» классика с Forge-модами\n• 1.12.2 — максимум старых модов и сборок\n• 1.16.5 — ностальгия, но уже со свежей графикой и оптимизацией\n\n");
        else if (packs) sb.append("Для сборок модов чаще всего берут 1.20.1 (больше всего новых сборок) или 1.12.2 (классические). Вот как выглядит выбор:\n\n");
        else sb.append("Как выбрать версию Minecraft — по цели:\n\n");
        sb.append(guideLines());
        if (hw != null && LocalAdvisor.overall(hw) <= 1)
            sb.append("\nВаш ПК скромный: берите 1.12.2 или 1.16.5 и лёгкие сборки — они требовательны меньше, чем новейшие версии.");
        sb.append("\nЕсли хотите просто поиграть без модов — берите последний стабильный релиз.");
        return sb.toString().trim();
    }

    private static String guideLines() {
        List<String> versions = new ArrayList<>();
        for (String[] c : CLASSICS) versions.add(c[0]);
        LiveKnowledge.Versions live = LiveKnowledge.versions();
        String latest = live == null ? null : live.latestRelease();
        if (latest != null && !versions.contains(latest)) versions.add(latest);
        Map<String, int[]> counts = LiveKnowledge.counts(versions);
        StringBuilder sb = new StringBuilder();
        for (String[] c : CLASSICS) sb.append(line(c[0], c[1], counts.get(c[0])));
        if (latest != null && !isClassic(latest))
            sb.append(line(latest, "самый новый релиз: новейшие функции, но сборок и модов пока меньше", counts.get(latest)));
        return sb.toString();
    }

    private static boolean isClassic(String v) {
        for (String[] c : CLASSICS) if (c[0].equals(v)) return true;
        return false;
    }

    private static String line(String version, String text, int[] c) {
        StringBuilder sb = new StringBuilder("• ").append(version).append(" — ").append(text);
        if (c != null && c[0] >= 0) {
            sb.append(" [на Modrinth сейчас: ").append(c[0]).append(" модов");
            if (c[1] >= 0) sb.append(", ").append(c[1]).append(" сборок");
            sb.append(']');
        }
        return sb.append('\n').toString();
    }
}
