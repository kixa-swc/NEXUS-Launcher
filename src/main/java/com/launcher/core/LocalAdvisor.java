package com.launcher.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalAdvisor {
    private LocalAdvisor() { }

    public record Context(String mcVersion, String loader, int ramGb) { }

    private static final Pattern NVIDIA = Pattern.compile("(RTX|GTX|GT)\\s*(\\d{3,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMD_RX = Pattern.compile("\\bRX\\s*(\\d{3,4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARC = Pattern.compile("\\bArc\\s*(?:\\(TM\\)\\s*)?([AB])\\s*(\\d{3})", Pattern.CASE_INSENSITIVE);

    public static int gpuTier(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.ROOT);
        Matcher m = NVIDIA.matcher(name);
        if (m.find()) {
            String kind = m.group(1).toUpperCase(Locale.ROOT);
            int num = Integer.parseInt(m.group(2));
            if (kind.equals("RTX")) {
                int cls = num % 100;
                if (cls <= 50) return 2;
                if (cls == 60) return 3;
                if (cls == 70) return 4;
                return 5;
            }
            if (kind.equals("GTX")) {
                if (num >= 1600) return num >= 1660 ? 2 : 1;
                int cls = num % 100;
                return cls <= 50 ? 1 : cls == 60 ? 2 : 3;
            }
            return 1;
        }
        m = AMD_RX.matcher(name);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            if (num < 1000) return num >= 570 ? 2 : 1;
            int cls = (num % 1000) / 100;
            return cls <= 4 ? 1 : cls == 5 ? 2 : cls == 6 ? 3 : cls == 7 ? 4 : 5;
        }
        m = ARC.matcher(name);
        if (m.find()) {
            int num = Integer.parseInt(m.group(2));
            return num >= 770 ? 4 : num >= 580 ? 3 : 2;
        }
        if (n.contains("quadro") || n.contains("radeon pro")) return 3;
        return 0;
    }

    public static int cpuTier(HardwareInfo.Info hw) {
        String n = hw.cpuName().toLowerCase(Locale.ROOT);
        if (n.contains("celeron") || n.contains("pentium") || n.contains("athlon") || n.contains("atom")) return 0;
        if (hw.cores() >= 12) return 4;
        if (hw.cores() >= 8) return 3;
        if (hw.cores() >= 6) return 2;
        if (hw.cores() >= 4) return 1;
        return 0;
    }

    private static int ramGb(HardwareInfo.Info hw) { return (int) Math.round(hw.ramTotalMb() / 1024.0); }

    public static int overall(HardwareInfo.Info hw) {
        HardwareInfo.Gpu g = hw.mainGpu();
        int gpu = g == null ? 0 : gpuTier(g.name());
        int ram = ramGb(hw) >= 32 ? 4 : ramGb(hw) >= 16 ? 3 : ramGb(hw) >= 12 ? 2 : ramGb(hw) >= 8 ? 1 : 0;
        double score = gpu * 0.9 + cpuTier(hw) * 0.8 + ram * 0.5;
        if (score >= 7.0) return 4;
        if (score >= 5.0) return 3;
        if (score >= 3.2) return 2;
        if (score >= 1.6) return 1;
        return 0;
    }

    private static final String[] LEVEL = {"слабый", "скромный", "хороший", "мощный", "очень мощный"};

    public static String answer(String question, HardwareInfo.Info hw, Context ctx) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<String> parts = new ArrayList<>();
        boolean any = false;
        if (has(q, "шейдер", "shader", "iris", "оптифайн", "optifine")) { parts.add(shaders(hw)); any = true; }
        if (has(q, "памят", "озу", "ram", "выдел", "гб", "gb", "гиг")) { parts.add(ram(hw, ctx)); any = true; }
        if (has(q, "верси", "version", "какую 1", "1.20", "1.21", "1.19", "1.16", "1.12", "1.8")) { parts.add(version(hw)); any = true; }
        if (has(q, "сборк", "модпак", "modpack", "fabulously", "all the mods", "rlcraft", "create")) { parts.add(modpacks(hw)); any = true; }
        if (has(q, "мод", "mod", "fps", "фпс", "лаг", "тормоз", "оптимиз", "подвис", "просад")) { parts.add(mods(hw, ctx)); any = true; }
        if (has(q, "апгрейд", "upgrade", "улучш", "купить", "заменить", "ssd", "ссд", "диск", "процессор", "видеокарт", "железо", "пк", "ноутбук", "сборк")) { parts.add(upgrade(hw)); any = true; }
        if (!any) parts.add(overview(hw, ctx));
        return String.join("\n\n", parts.subList(0, Math.min(3, parts.size())));
    }

    private static boolean has(String q, String... words) {
        for (String w : words) if (q.contains(w)) return true;
        return false;
    }

    public static String overview(HardwareInfo.Info hw, Context ctx) {
        int lvl = overall(hw);
        HardwareInfo.Gpu g = hw.mainGpu();
        StringBuilder sb = new StringBuilder();
        sb.append("Ваш ПК для Minecraft — ").append(LEVEL[lvl]).append(".\n");
        sb.append("• Процессор: ").append(hw.cpuName()).append(" (").append(hw.cores()).append(" ядер)\n");
        sb.append("• Видеокарта: ").append(g == null ? "не определена" : g.name()).append(gpuTier(g == null ? null : g.name()) == 0 ? " — встроенная или слабая\n" : "\n");
        sb.append("• Память: ").append(ramGb(hw)).append(" ГБ").append(hw.ramSticks() == 1 ? " (одна планка — двухканальный режим даст +10–20% FPS)" : "").append('\n');
        sb.append("• Накопитель: ").append(hw.hasSsd() ? "есть SSD — хорошо" : "только HDD — миры и моды будут грузиться долго").append("\n\n");
        sb.append("Что я могу подобрать: моды для FPS, шейдеры, версию игры, объём памяти, готовые сборки модов и улучшения для ПК. Спросите, например: «Какие моды поставить для FPS?»");
        return sb.toString();
    }

    public static String ram(HardwareInfo.Info hw, Context ctx) {
        int total = ramGb(hw);
        int vanilla = total <= 4 ? 2 : total <= 8 ? 3 : total <= 16 ? 4 : 6;
        int modded = total <= 4 ? 2 : total <= 8 ? 4 : total <= 16 ? 6 : total <= 24 ? 8 : 10;
        int heavy = total <= 8 ? 4 : total <= 16 ? 8 : total <= 24 ? 10 : 12;
        StringBuilder sb = new StringBuilder("Память для игры (у вас " + total + " ГБ):\n");
        sb.append("• Чистый Minecraft или несколько оптимизирующих модов: ").append(vanilla).append(" ГБ\n");
        sb.append("• Сборка на 50–150 модов: ").append(modded).append(" ГБ\n");
        sb.append("• Тяжёлые сборки и шейдеры с большой дальностью: ").append(heavy).append(" ГБ\n");
        sb.append("Не выделяйте больше половины оперативной памяти ПК: Windows и браузер тоже нужны. Слишком большое значение даже вредит — сборщик мусора Java начинает делать долгие паузы.");
        if (ctx != null && ctx.ramGb() > 0) {
            if (ctx.ramGb() > total * 0.6) sb.append("\n\n⚠ Сейчас в лаунчере выделено ").append(ctx.ramGb()).append(" ГБ — это слишком много для ").append(total).append(" ГБ. Уменьшите в Настройки → Игра.");
            else if (ctx.ramGb() < vanilla) sb.append("\n\nСейчас выделено ").append(ctx.ramGb()).append(" ГБ — этого мало, попробуйте ").append(vanilla).append(" ГБ (Настройки → Игра).");
            else sb.append("\n\nСейчас выделено ").append(ctx.ramGb()).append(" ГБ — это нормальное значение.");
        }
        return sb.toString();
    }

    public static String version(HardwareInfo.Info hw) {
        int lvl = overall(hw);
        StringBuilder sb = new StringBuilder("Какую версию взять:\n");
        if (lvl == 0) {
            sb.append("• Для слабого ПК лучше всего 1.12.2 или 1.16.5 с оптимизирующими модами: они требовательны меньше новых версий.\n");
            sb.append("• Если нужны новые блоки — 1.20.1 на Fabric с Sodium тоже запустится, но дальность прорисовки ставьте 6–8 чанков.");
        } else if (lvl <= 2) {
            sb.append("• 1.20.1 или 1.21.x на Fabric + Sodium — лучший баланс: свежая игра и высокий FPS.\n");
            sb.append("• Для классических сборок с множеством модов подойдут 1.12.2, 1.16.5 и 1.20.1 (больше всего модов).");
        } else {
            sb.append("• Любая актуальная версия 1.21.x — ПК справится.\n");
            sb.append("• Для больших сборок 1.20.1 остаётся самой «модной» версией: на неё больше всего модов.");
        }
        sb.append("\nЛаунчер сам докачает нужную Java и версию игры.");
        return sb.toString();
    }

    public static String mods(HardwareInfo.Info hw, Context ctx) {
        int lvl = overall(hw);
        boolean fabricish = ctx == null || ctx.loader() == null || !ctx.loader().toLowerCase(Locale.ROOT).contains("forge");
        StringBuilder sb = new StringBuilder("Моды для FPS и плавности (все на Modrinth, вкладка «Моды» в лаунчере):\n");
        if (fabricish) {
            sb.append("• Sodium — главный мод: сильно ускоряет отрисовку\n• Lithium — ускоряет сам мир и логику игры\n• FerriteCore — экономит память\n• ImmediatelyFast — быстрее интерфейс и частицы\n• Entity Culling — не рисует то, что скрыто за стенами\n• ModernFix — быстрее запуск и меньше памяти\n");
        } else {
            sb.append("• Embeddium (на NeoForge — Sodium) — главный мод для FPS\n• FerriteCore — экономит память\n• ModernFix — быстрее запуск и меньше памяти\n• Entity Culling — не рисует скрытые объекты\n• Canary / Radium — ускоряют мир и логику (если есть для вашей версии)\n");
        }
        if (lvl <= 1) sb.append("• Dynamic FPS — снижает нагрузку, когда окно игры не в фокусе\n\nДля вашего ПК важнее всего настройки: дальность прорисовки 6–8, плавное освещение выкл, облака выкл, частицы «минимум».");
        else if (lvl == 2) sb.append("\nС этим набором на вашем ПК можно держать дальность 12–16 чанков без просадок.");
        else sb.append("• Distant Horizons — рисует очень далёкие земли (для мощных ПК)\n\nПК позволяет дальность 24+ чанков и шейдеры — смотрите совет по шейдерам.");
        sb.append("\n\nСовместимость проверяйте по версии игры и загрузчику (сейчас: ")
                .append(ctx == null || ctx.mcVersion() == null ? "не выбрана" : ctx.mcVersion()).append(", ")
                .append(ctx == null || ctx.loader() == null ? "Vanilla" : ctx.loader()).append(").");
        return sb.toString();
    }

    public static String shaders(HardwareInfo.Info hw) {
        HardwareInfo.Gpu g = hw.mainGpu();
        int t = g == null ? 0 : gpuTier(g.name());
        StringBuilder sb = new StringBuilder("Шейдеры (нужен мод Iris вместе с Sodium; на Forge — Oculus):\n");
        switch (t) {
            case 0, 1 -> sb.append("С такой видеокартой шейдеры, скорее всего, дадут 10–25 FPS. Лучше обойтись без них. Если очень хочется — Complementary Reimagined на профиле Potato/Low, дальность 6 чанков.");
            case 2 -> sb.append("• Complementary Reimagined (профиль Low–Medium)\n• MakeUp Ultra Fast — очень лёгкий\n• Дальность 8–10 чанков");
            case 3 -> sb.append("• Complementary Reimagined (Medium–High)\n• BSL (Medium)\n• Дальность 12 чанков");
            case 4 -> sb.append("• Complementary Unbound или BSL (High)\n• Photon / Solas — красивая вода и свет\n• Дальность 16 чанков");
            default -> sb.append("• Complementary Unbound (Ultra), Photon, SEUS PTGI HRR — трассировка лучей\n• Дальность 24+ чанков с Distant Horizons");
        }
        if (g != null && g.vramMb() > 0 && g.vramMb() < 3000 && t >= 2) sb.append("\n\n⚠ Видеопамяти мало (").append(g.vramMb() / 1024).append(" ГБ): текстуры 16x–32x, без больших ресурспаков.");
        sb.append("\n\nШейдеры включаются в игре: Настройки видео → Шейдеры.");
        return sb.toString();
    }

    public static String modpacks(HardwareInfo.Info hw) {
        int lvl = overall(hw);
        int total = ramGb(hw);
        StringBuilder sb = new StringBuilder("Готовые сборки (ищите на Modrinth и CurseForge, раздел «Сборки модов»):\n");
        if (lvl <= 1) {
            sb.append("• Fabulously Optimized — облегчённая сборка для FPS\n• Simply Optimized — ещё легче, почти ванильная\n• Better Minecraft Lite — приключения без большой нагрузки\n\nНа ваш ПК не стоит ставить тяжёлые сборки вроде All the Mods 9/10 и RLCraft.");
        } else if (lvl == 2) {
            sb.append("• Fabulously Optimized — FPS и красота\n• Cobblemon / Better MC — приключения\n• Create: Above and Beyond — техника, нужно 6–8 ГБ\n• Prominence II — RPG, нужно 8 ГБ");
        } else {
            sb.append("• All the Mods 9/10 — тысячи модов, нужно 10–12 ГБ\n• Prominence II RPG, Better MC, Create: Astral\n• Fabulously Optimized + шейдеры");
        }
        if (total < 8) sb.append("\n\n⚠ При ").append(total).append(" ГБ памяти сборки на 150+ модов будут тормозить: выбирайте до 80 модов.");
        return sb.toString();
    }

    public static String upgrade(HardwareInfo.Info hw) {
        List<String> tips = new ArrayList<>();
        HardwareInfo.Gpu g = hw.mainGpu();
        int gt = g == null ? 0 : gpuTier(g.name());
        if (!hw.hasSsd()) tips.add("SSD вместо HDD — самое выгодное: миры, моды и запуск ускорятся в разы, стоит недорого (от 500 ГБ).");
        if (ramGb(hw) < 8) tips.add("Оперативная память: добавьте до 16 ГБ — при " + ramGb(hw) + " ГБ любые сборки модов будут тормозить.");
        else if (ramGb(hw) < 16) tips.add("Оперативная память: 16 ГБ комфортны для сборок модов (сейчас " + ramGb(hw) + " ГБ).");
        if (hw.ramSticks() == 1 && ramGb(hw) >= 8) tips.add("Одна планка памяти: вторая такая же включит двухканальный режим — до +20% FPS.");
        if (gt <= 1) tips.add("Видеокарта: " + (g == null ? "не определена" : g.name()) + " — самое слабое место. Подойдёт любая от GTX 1650 / RX 580 / RTX 3050 и выше.");
        else if (gt == 2) tips.add("Видеокарта справляется с Minecraft без шейдеров; для шейдеров нужна RTX 3060 / RX 6600 или лучше.");
        if (cpuTier(hw) <= 1) tips.add("Процессор: Minecraft сильно зависит от одного быстрого ядра. Нужен хотя бы современный 6-ядерный (Ryzen 5 / Core i5).");
        if (hw.gameDiskFreeGb() >= 0 && hw.gameDiskFreeGb() < 20) tips.add("На диске с игрой осталось " + hw.gameDiskFreeGb() + " ГБ — освободите место (версии и сборки занимают 5–15 ГБ каждая).");
        StringBuilder sb = new StringBuilder();
        if (tips.isEmpty()) sb.append("Ваш ПК хорошо подходит для Minecraft — улучшать ничего не нужно. Лучше потратить силы на шейдеры и сборки модов.");
        else {
            sb.append("Что улучшить в ПК (по важности):\n");
            for (String t : tips) sb.append("• ").append(t).append('\n');
        }
        return sb.toString().trim();
    }
}
