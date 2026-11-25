package com.launcher.ui;

import javafx.scene.paint.Color;

import java.util.List;

public final class Themes {
    private Themes() { }

    public enum Art { NIGHT, MOON, SILK, ECLIPSE, CHROME, LIGHT, GLASS, ORBIT, AURORA, VIOLET, EMBER, OCEAN }

    public record Theme(String id, String name, boolean light, String tint, String accent, String accentText, Art art) {
        public String style() {
            Color tint = Color.web(this.tint), acc = Color.web(accent);
            Color white = Color.WHITE, black = Color.BLACK, ov = light ? black : white;
            boolean bright = acc.getBrightness() > 0.62 && acc.getSaturation() < 0.9 || acc.getBrightness() > 0.8;
            Color dim = bright ? mix(acc, black, 0.68) : mix(acc, white, 0.28);
            StringBuilder s = new StringBuilder();
            put(s, "accent", hex(acc)); put(s, "accent-text", accentText);
            put(s, "accent-hover", hex(bright ? mix(acc, black, 0.09) : mix(acc, white, 0.2)));
            put(s, "accent-soft", rgba(acc, 0.16)); put(s, "accent-dim", hex(dim)); put(s, "accent-dim-hover", hex(mix(dim, white, 0.12)));
            put(s, "glow", rgba(acc, 0.30));
            put(s, "stop", light ? "#e5484d" : "#ff5d73"); put(s, "stop-text", "#ffffff"); put(s, "danger", light ? "#c4314b" : "#ff8b9b");
            put(s, "base", hex(tint));
            put(s, "frame", rgba(tint, 0.97));
            put(s, "panel", hex(mix(tint, ov, light ? 0.03 : 0.035)));
            put(s, "card", hex(mix(tint, ov, light ? 0.05 : 0.07)));
            put(s, "field", hex(light ? white : mix(tint, white, 0.045)));
            put(s, "dialog", hex(light ? mix(tint, white, 0.5) : mix(tint, white, 0.03)));
            put(s, "inset", hex(light ? mix(tint, black, 0.04) : mix(tint, black, 0.3)));
            put(s, "popup", hex(light ? white : mix(tint, white, 0.09)));
            put(s, "selected", hex(light ? mix(tint, black, 0.1) : mix(tint, white, 0.18)));
            put(s, "stage", hex(light ? tint : mix(tint, black, 0.35)));
            put(s, "text", light ? "#1a1a1f" : "#f2f2f4"); put(s, "strong", light ? "#0b0b0f" : "#ffffff");
            put(s, "muted", light ? "#6b6b73" : "#8e8e95"); put(s, "icon", light ? "#1a1a1f" : "#ffffff");
            put(s, "line", rgba(ov, 0.10)); put(s, "ov1", rgba(ov, 0.06)); put(s, "ov2", rgba(ov, 0.10));
            put(s, "ov3", rgba(ov, 0.18)); put(s, "ov4", rgba(ov, 0.30));
            put(s, "hero-text", light ? "#111116" : "#ffffff");
            put(s, "hero-text-soft", light ? "rgba(17,17,22,0.80)" : "rgba(255,255,255,0.86)");
            put(s, "hero-shadow", light ? "rgba(255,255,255,0.55)" : "rgba(0,0,0,0.70)");
            put(s, "pill-bg", light ? "rgba(255,255,255,0.82)" : "rgba(10,10,12,0.78)");
            put(s, "pill-hover", light ? "rgba(255,255,255,0.97)" : "rgba(40,40,44,0.92)");
            put(s, "news-bg", light ? "rgba(255,255,255,0.84)" : "rgba(12,12,14,0.82)");
            put(s, "shade-a", light ? "rgba(255,255,255,0.45)" : "rgba(0,0,0,0.38)");
            put(s, "shade-b", light ? "rgba(255,255,255,0.62)" : "rgba(0,0,0,0.62)");
            put(s, "shade-clear", light ? "rgba(255,255,255,0)" : "rgba(0,0,0,0)");
            put(s, "log-text", light ? "#1f4d2b" : "#d9ecd2");
            put(s, "rail-bg", light ? "rgba(255,255,255,0.55)" : "rgba(8,8,10,0.46)");
            put(s, "rail-line", light ? "rgba(0,0,0,0.10)" : "rgba(255,255,255,0.10)");
            put(s, "nav-active", light ? "rgba(0,0,0,0.09)" : "rgba(255,255,255,0.14)");
            put(s, "glass", light ? "rgba(255,255,255,0.55)" : "rgba(255,255,255,0.07)");
            put(s, "glass-line", light ? "rgba(0,0,0,0.22)" : "rgba(255,255,255,0.28)");
            put(s, "glass-hover", light ? "rgba(255,255,255,0.85)" : "rgba(255,255,255,0.15)");
            return s.toString();
        }
    }

    public static final List<Theme> ALL = List.of(
            new Theme("night", "Ночной мир", false, "#0b0b0d", "#ffffff", "#0b0b0e", Art.NIGHT),
            new Theme("moon", "Лунный свет", false, "#0d0d10", "#ffffff", "#0b0b0e", Art.MOON),
            new Theme("silk", "Шёлк", false, "#08080a", "#ffffff", "#0b0b0e", Art.SILK),
            new Theme("eclipse", "Затмение", false, "#060608", "#ffffff", "#0b0b0e", Art.ECLIPSE),
            new Theme("chrome", "Жидкий хром", false, "#09090b", "#ffffff", "#0b0b0e", Art.CHROME),
            new Theme("light", "Светлая", true, "#eceef2", "#111115", "#ffffff", Art.LIGHT),
            new Theme("glass", "Грани", false, "#08080a", "#ffffff", "#0b0b0e", Art.GLASS),
            new Theme("orbit", "Орбита", false, "#050507", "#ffffff", "#0b0b0e", Art.ORBIT),
            new Theme("aurora", "Северное сияние", false, "#06130f", "#eafff4", "#04211a", Art.AURORA),
            new Theme("violet", "Ультрафиолет", false, "#0e0a1c", "#f1ebff", "#1b0f3a", Art.VIOLET),
            new Theme("ember", "Угли", false, "#120807", "#fff1e2", "#2a0e00", Art.EMBER),
            new Theme("ocean", "Глубина", false, "#060d1a", "#e8f3ff", "#06214a", Art.OCEAN));

    public static final String DEFAULT_ID = "night";

    public static Theme byId(String id) {
        for (Theme theme : ALL) if (theme.id().equals(id)) return theme;
        return ALL.get(0);
    }

    private static Color mix(Color a, Color b, double p) { return a.interpolate(b, p); }

    private static void put(StringBuilder s, String name, String value) { s.append("-nx-").append(name).append(':').append(value).append(';'); }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
    }

    private static String rgba(Color c, double alpha) {
        return "rgba(" + Math.round(c.getRed() * 255) + "," + Math.round(c.getGreen() * 255) + "," + Math.round(c.getBlue() * 255) + "," + alpha + ")";
    }
}
