package com.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

public final class ThemeRenderer {
    private ThemeRenderer() { }

    public static BufferedImage render(Themes.Theme theme, int w, int h) {
        w = Math.max(16, w); h = Math.max(16, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            switch (theme.art()) {
                case NIGHT -> landscape(g, img, w, h, 0);
                case MOON -> landscape(g, img, w, h, 1);
                case LIGHT -> landscape(g, img, w, h, 2);
                case SILK -> silk(g, w, h);
                case ECLIPSE -> eclipse(g, w, h);
                case CHROME -> chrome(g, w, h);
                case GLASS -> glass(g, w, h);
                case ORBIT -> orbit(g, w, h);
                case AURORA -> ribbons(g, w, h, "#031210", "#0a3a2c", "#3ddc84", "#22d3ee", "#a7f3d0");
                case VIOLET -> ribbons(g, w, h, "#09061a", "#1d1146", "#8b5cf6", "#ec4899", "#c4b5fd");
                case EMBER -> ribbons(g, w, h, "#0e0504", "#2e0d07", "#ff6a1a", "#ff3b5c", "#ffd29a");
                case OCEAN -> ribbons(g, w, h, "#030812", "#08224a", "#3b82f6", "#22d3ee", "#bae6fd");
            }
            vignette(g, w, h, theme.light());
        } finally { g.dispose(); }
        grain(img, theme.light() ? 3 : 4);
        return img;
    }

    private static void landscape(Graphics2D g, BufferedImage img, int w, int h, int mode) {
        String[] sky = switch (mode) { case 0 -> new String[]{"#04050a", "#0d0f16", "#1c1f28"};
                                       case 1 -> new String[]{"#08090d", "#20232b", "#565a65"};
                                       default -> new String[]{"#c9ccd3", "#e4e6ea", "#fafafb"}; };
        g.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[]{0f, 0.55f, 1f}, new Color[]{c(sky[0]), c(sky[1]), c(sky[2])}));
        g.fillRect(0, 0, w, h);
        double u = Math.max(6, Math.round(h / 44.0));
        Random r = new Random(7 + mode);
        if (mode < 2) {
            int n = w * h / 5200;
            for (int i = 0; i < n; i++) {
                double y = r.nextDouble() * h * 0.62, a = 0.12 + r.nextDouble() * 0.6 * (1 - y / (h * 0.7));
                g.setColor(new Color(1f, 1f, 1f, (float) Math.max(0.05, a)));
                double sz = r.nextInt(14) == 0 ? u * 0.25 : u * 0.12;
                g.fill(new java.awt.geom.Rectangle2D.Double(Math.floor(r.nextDouble() * w), Math.floor(y), Math.max(1, sz), Math.max(1, sz)));
            }
        }
        double sx = w * (mode == 1 ? 0.66 : 0.72), sy = h * (mode == 1 ? 0.22 : 0.20), sz = u * 3.2;
        float glowR = (float) (h * (mode == 1 ? 0.75 : 0.55));
        Color glow = mode == 2 ? new Color(1f, 1f, 1f, 0.95f) : new Color(1f, 1f, 1f, mode == 1 ? 0.34f : 0.16f);
        g.setPaint(new RadialGradientPaint((float) (sx + sz / 2), (float) (sy + sz / 2), glowR, new float[]{0f, 1f},
                new Color[]{glow, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0)}));
        g.fillRect(0, 0, w, h);
        g.setColor(mode == 2 ? Color.WHITE : new Color(0xf2f3f7));
        g.fill(new java.awt.geom.Rectangle2D.Double(sx, sy, sz, sz));
        g.setColor(mode == 2 ? new Color(1f, 1f, 1f, 0.75f) : new Color(1f, 1f, 1f, 0.05f));
        for (int i = 0; i < 6; i++) {
            double cx = r.nextDouble() * w, cy = h * (0.08 + r.nextDouble() * 0.30), cw = u * (5 + r.nextInt(8));
            g.fill(new java.awt.geom.Rectangle2D.Double(Math.round(cx / u) * u, Math.round(cy / u) * u, cw, u * 1.6));
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        String[][] cols = {{"#262932", "#1d1f27", "#14151b", "#0b0c10"}, {"#4b4f5a", "#363943", "#22242b", "#101115"}, {"#bfc2ca", "#a3a7b0", "#868a94", "#6a6e78"}};
        for (int layer = 0; layer < 4; layer++) {
            double base = h * (0.50 + layer * 0.12), amp = h * (0.15 - layer * 0.022);
            double f1 = 2.2 + layer * 1.3, f2 = 7.0 + layer * 2.1, ph = layer * 2.1 + mode;
            g.setColor(c(cols[mode][layer]));
            double[] tops = new double[(int) (w / u) + 2];
            for (int i = 0; i < tops.length; i++) {
                double x = i * u / w;
                double v = Math.sin(x * f1 + ph) * amp + Math.sin(x * f2 + ph * 1.7) * amp * 0.35;
                tops[i] = Math.round((base - v) / u) * u;
                g.fill(new java.awt.geom.Rectangle2D.Double(i * u, tops[i], u + 1, h - tops[i] + 1));
            }
            if (layer >= 2) {
                int step = layer == 2 ? 7 : 5;
                for (int i = 2; i < tops.length - 2; i += step + r.nextInt(4)) {
                    double tx = i * u, ty = tops[i], th = u * (layer == 2 ? 6 + r.nextInt(3) : 8 + r.nextInt(4));
                    for (int row = 0; row < th / u; row++) {
                        double half = u * (0.5 + row * 0.5);
                        g.fill(new java.awt.geom.Rectangle2D.Double(tx - half + u * 0.5, ty - th + row * u, half * 2, u));
                    }
                }
            }
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fog = mode == 2 ? new Color(1f, 1f, 1f, 0.0f) : new Color(0f, 0f, 0f, 0f);
            g.setPaint(fog);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float a = mode == 2 ? 0.35f : 0.10f;
        g.setPaint(new LinearGradientPaint(0, (float) (h * 0.45), 0, (float) (h * 0.85), new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(1f, 1f, 1f, 0f), new Color(1f, 1f, 1f, a), new Color(1f, 1f, 1f, 0f)}));
        g.fillRect(0, (int) (h * 0.45), w, (int) (h * 0.4));
    }

    private static void silk(Graphics2D g, int w, int h) {
        fillV(g, w, h, "#050506", "#0e0e11");
        double s = h / 720.0;
        glow(g, w * 0.55, h * 0.78, h * 0.9, Color.WHITE, 0.10f);
        int n = 90;
        for (int i = 0; i < n; i++) {
            double t = i / (double) (n - 1);
            double fade = Math.sin(t * Math.PI);
            g.setStroke(new BasicStroke((float) Math.max(0.7, 1.1 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.45f, 1f},
                    new Color[]{new Color(1f, 1f, 1f, 0f), new Color(1f, 1f, 1f, (float) (0.04 + 0.30 * fade)), new Color(0.85f, 0.87f, 0.95f, (float) (0.10 + 0.45 * fade))}));
            Path2D p = new Path2D.Double();
            for (int k = 0; k <= 90; k++) {
                double x = w * (-0.05 + 1.1 * k / 90.0);
                double u = x / w;
                double y = h * (0.50 + 0.52 * t - 0.14 * u) + Math.sin(u * 4.6 + t * 3.4) * h * (0.13 + 0.10 * t)
                        + Math.sin(u * 11 - t * 3.1) * h * 0.018 * (0.4 + t) - Math.pow(u, 2.2) * h * 0.22 * (1 - t * 0.6);
                if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            g.draw(p);
        }
    }

    private static void eclipse(Graphics2D g, int w, int h) {
        fillV(g, w, h, "#030304", "#09090d");
        stars(g, w, h, 11, 0.9);
        double s = h / 720.0, r = h * 0.62, cx = w * 0.78, cy = h * 0.60;
        glow(g, cx, cy, r * 1.45, Color.WHITE, 0.10f);
        Area rim = new Area(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        rim.subtract(new Area(new Ellipse2D.Double(cx - r + r * 0.07, cy - r + r * 0.05, 2 * r, 2 * r)));
        g.setPaint(new LinearGradientPaint((float) (cx - r), (float) (cy - r), (float) cx, (float) (cy + r * 0.2), new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(1f, 1f, 1f, 0.95f), new Color(1f, 1f, 1f, 0.55f), new Color(1f, 1f, 1f, 0.02f)}));
        g.fill(rim);
        g.setColor(new Color(0.01f, 0.01f, 0.015f));
        g.fill(new Ellipse2D.Double(cx - r + r * 0.07, cy - r + r * 0.05, 2 * r, 2 * r));
        for (int i = 1; i <= 5; i++) {
            g.setStroke(new BasicStroke((float) (1.0 * s)));
            g.setColor(new Color(1f, 1f, 1f, 0.07f - i * 0.011f));
            double rr = r + i * 14 * s;
            g.draw(new Ellipse2D.Double(cx - rr, cy - rr, 2 * rr, 2 * rr));
        }
        sparkle(g, w * 0.58, h * 0.16, 11 * s);
        sparkle(g, w * 0.93, h * 0.82, 7 * s);
    }

    private static void chrome(Graphics2D g, int w, int h) {
        fillV(g, w, h, "#060607", "#0d0d10");
        double s = h / 720.0;
        glow(g, w * 0.78, h * 0.35, h * 0.8, Color.WHITE, 0.06f);
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D l = layer.createGraphics();
        l.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int bands = 46;
        for (int ribbon = 0; ribbon < 3; ribbon++) {
            for (int k = 0; k < bands; k++) {
                double t = k / (double) (bands - 1);
                double shade = 0.5 + 0.5 * Math.cos((t * 2.2 + ribbon * 0.4) * Math.PI);
                float v = (float) (0.06 + 0.94 * Math.pow(shade, 1.6));
                l.setColor(new Color(v, v, Math.min(1f, v * 1.03f), 0.92f));
                l.setStroke(new BasicStroke((float) (9 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double off = (t - 0.5) * 150 * s, ro = ribbon * 0.2;
                Path2D p = new Path2D.Double();
                p.moveTo(w * (0.40 + ro * 0.2) + off, -h * 0.05);
                p.curveTo(w * (0.78 + ro * 0.1) + off * 0.4, h * (0.12 + ro * 0.4), w * (0.35 + ro * 0.4) + off, h * (0.40 + ro * 0.2),
                        w * (0.78 + ro * 0.3) - off * 0.6, h * (0.60 + ro * 0.15));
                p.curveTo(w * (1.00 + ro * 0.1), h * (0.74 + ro * 0.1), w * (0.70 + ro * 0.2) + off, h * (0.92 + ro * 0.05), w * (0.96 + ro * 0.1), h * 1.06);
                l.draw(p);
            }
        }
        l.dispose();
        g.drawImage(layer, 0, 0, null);
        g.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.45f, 1f},
                new Color[]{new Color(0.02f, 0.02f, 0.03f, 0.96f), new Color(0.02f, 0.02f, 0.03f, 0.55f), new Color(0, 0, 0, 0f)}));
        g.fillRect(0, 0, w, h);
    }

    private static void glass(Graphics2D g, int w, int h) {
        fillV(g, w, h, "#060608", "#101014");
        double s = h / 720.0;
        glow(g, w * 0.62, h * 0.50, h * 0.9, Color.WHITE, 0.08f);
        stars(g, w, h, 3, 0.4);
        double[][] boxes = {{0.50, 0.12, 0.26, 0.56, 1}, {0.66, 0.42, 0.22, 0.50, 2}, {0.84, 0.06, 0.13, 0.30, 3}, {0.42, 0.62, 0.14, 0.30, 4}};
        for (double[] b : boxes) {
            double x = w * b[0], y = h * b[1], bw = w * b[2], bh = h * b[3], arc = Math.min(bw, bh) * 0.14;
            RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, bw, bh, arc, arc);
            g.setColor(new Color(0f, 0f, 0f, 0.35f));
            g.fill(new RoundRectangle2D.Double(x + 6 * s, y + 14 * s, bw, bh, arc, arc));
            g.setPaint(new GradientPaint((float) x, (float) y, new Color(1f, 1f, 1f, 0.13f), (float) (x + bw), (float) (y + bh), new Color(1f, 1f, 1f, 0.015f)));
            g.fill(rr);
            g.setStroke(new BasicStroke((float) Math.max(1, 1.3 * s)));
            g.setPaint(new GradientPaint((float) x, (float) y, new Color(1f, 1f, 1f, 0.55f), (float) (x + bw), (float) (y + bh), new Color(1f, 1f, 1f, 0.06f)));
            g.draw(rr);
        }
        sparkle(g, w * 0.44, h * 0.22, 10 * s);
        sparkle(g, w * 0.90, h * 0.64, 7 * s);
        sparkle(g, w * 0.70, h * 0.92, 6 * s);
    }

    private static void orbit(Graphics2D g, int w, int h) {
        fillV(g, w, h, "#040405", "#0a0a0d");
        stars(g, w, h, 5, 0.7);
        double s = h / 720.0, cx = w * 0.72, cy = h * 0.62, r = h * 0.78;
        g.setColor(new Color(0.008f, 0.008f, 0.012f));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        for (int i = 0; i < 6; i++) {
            g.setStroke(new BasicStroke((float) ((6 - i) * 3.2 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(1f, 1f, 1f, 0.05f + i * 0.05f));
            g.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 52, 150, java.awt.geom.Arc2D.OPEN));
        }
        g.setStroke(new BasicStroke((float) Math.max(1, 1.4 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setPaint(new LinearGradientPaint((float) (cx - r), (float) (cy - r), (float) cx, (float) (cy + r), new float[]{0f, 0.5f, 1f},
                new Color[]{new Color(1f, 1f, 1f, 0.95f), new Color(1f, 1f, 1f, 0.35f), new Color(1f, 1f, 1f, 0f)}));
        g.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 70, 130, java.awt.geom.Arc2D.OPEN));
        for (int i = 1; i <= 3; i++) {
            double rr = r * (0.80 + i * 0.07);
            g.setStroke(new BasicStroke((float) Math.max(0.8, s)));
            g.setColor(new Color(1f, 1f, 1f, 0.09f));
            g.draw(new java.awt.geom.Arc2D.Double(cx - rr, cy - rr, 2 * rr, 2 * rr, 30, 220, java.awt.geom.Arc2D.OPEN));
        }
        sparkle(g, w * 0.50, h * 0.10, 9 * s);
    }

    private static void ribbons(Graphics2D g, int w, int h, String bg1, String bg2, String c1, String c2, String c3) {
        fillV(g, w, h, bg1, bg2);
        double s = h / 720.0;
        glow(g, w * 0.78, h * 0.28, Math.max(w, h) * 0.52, c(c1), 0.34f);
        glow(g, w * 0.92, h * 0.90, Math.max(w, h) * 0.44, c(c2), 0.20f);
        glow(g, w * 0.50, h * 1.00, Math.max(w, h) * 0.40, c(c1), 0.12f);
        glow(g, w * 0.16, h * 0.10, Math.max(w, h) * 0.38, c(c2), 0.12f);
        glow(g, w * 0.04, h * 0.72, Math.max(w, h) * 0.32, c(c1), 0.08f);
        stars(g, w, h, 9, 0.6);
        BufferedImage layer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D l = layer.createGraphics();
        l.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ribbonSet(l, w, h, s, c(c1), c(c2), c(c3), 64, 1.0, 0.86, -0.62, 0.60, 5.2, 0.0, 0.34);
        ribbonSet(l, w, h, s, c(c2), c(c1), c(c3), 36, 0.34, 0.14, 0.50, 0.46, 4.0, 1.7, 0.28);
        l.dispose();
        g.drawImage(layer, 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
        g.drawImage(blur(layer, 6), 0, 0, null);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void ribbonSet(Graphics2D l, int w, int h, double s, Color c1, Color c2, Color c3, int lines, double strength,
                                  double y0, double slope, double spread, double waves, double phase, double wave) {
        for (int i = 0; i < lines; i++) {
            double t = i / (lines - 1.0), fade = Math.sin(t * Math.PI);
            l.setStroke(new BasicStroke((float) Math.max(0.8, 1.2 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Color a = lerp(c1, c2, t);
            l.setPaint(new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.45f, 1f},
                    new Color[]{alpha(a, (float) (strength * (0.10 + 0.22 * fade))), alpha(a, (float) (strength * (0.18 + 0.42 * fade))),
                            alpha(lerp(a, c3, 0.5), (float) (strength * (0.25 + 0.55 * fade)))}));
            Path2D p = new Path2D.Double();
            for (int k = 0; k <= 90; k++) {
                double u = -0.06 + 1.12 * k / 90.0, x = w * u;
                double y = h * (y0 + slope * u + spread * (t - 0.5) * 0.55)
                        + Math.sin(u * waves + t * 4.2 + phase) * h * (wave * (0.5 + 0.5 * t));
                if (k == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            l.draw(p);
        }
    }

    private static void vignette(Graphics2D g, int w, int h, boolean light) {
        Color edge = light ? new Color(1f, 1f, 1f, 0.0f) : new Color(0f, 0f, 0f, 0.55f);
        g.setPaint(new RadialGradientPaint(w / 2f, h / 2f, (float) (Math.max(w, h) * 0.72), new float[]{0.55f, 1f},
                new Color[]{new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 0), edge}));
        g.fillRect(0, 0, w, h);
        Color calm = light ? new Color(1f, 1f, 1f, 0.22f) : new Color(0f, 0f, 0f, 0.22f);
        g.setPaint(new LinearGradientPaint(0, 0, w * 0.5f, 0, new float[]{0f, 1f}, new Color[]{calm, alpha(calm, 0f)}));
        g.fillRect(0, 0, w, h);
    }

    private static void grain(BufferedImage img, int amount) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        long seed = 12345;
        for (int i = 0; i < px.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int n = (int) ((seed >>> 40) % (2 * amount + 1)) - amount;
            int p = px[i];
            int r = clamp(((p >> 16) & 255) + n), gg = clamp(((p >> 8) & 255) + n), b = clamp((p & 255) + n);
            px[i] = (p & 0xff000000) | (r << 16) | (gg << 8) | b;
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    private static BufferedImage blur(BufferedImage src, int factor) {
        int sw = Math.max(1, src.getWidth() / factor), sh = Math.max(1, src.getHeight() / factor);
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, sw, sh, null);
        g.dispose();
        BufferedImage big = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(small, 0, 0, src.getWidth(), src.getHeight(), null);
        g.dispose();
        return big;
    }

    private static void fillV(Graphics2D g, int w, int h, String top, String bottom) {
        g.setPaint(new GradientPaint(0, 0, c(top), 0, h, c(bottom)));
        g.fillRect(0, 0, w, h);
    }

    private static void glow(Graphics2D g, double cx, double cy, double r, Color col, float a) {
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) r, new float[]{0f, 1f},
                new Color[]{alpha(col, a), alpha(col, 0f)}));
        g.fill(new java.awt.geom.Rectangle2D.Double(cx - r, cy - r, 2 * r, 2 * r));
    }

    private static void stars(Graphics2D g, int w, int h, long seed, double density) {
        Random r = new Random(seed);
        int n = (int) (w * h / 7000 * density) + 6;
        double s = h / 720.0;
        for (int i = 0; i < n; i++) {
            double sz = (0.6 + r.nextDouble() * 1.4) * Math.max(0.8, s);
            g.setColor(new Color(1f, 1f, 1f, (float) (0.12 + r.nextDouble() * 0.6)));
            g.fill(new Ellipse2D.Double(r.nextDouble() * w, r.nextDouble() * h, sz, sz));
        }
    }

    private static void sparkle(Graphics2D g, double x, double y, double r) {
        glow(g, x, y, r * 3.2, Color.WHITE, 0.28f);
        Path2D p = new Path2D.Double();
        p.moveTo(x, y - r); p.quadTo(x, y, x + r, y); p.quadTo(x, y, x, y + r); p.quadTo(x, y, x - r, y); p.quadTo(x, y, x, y - r);
        g.setColor(new Color(1f, 1f, 1f, 0.95f));
        g.fill(p);
    }

    private static Color c(String hex) { return Color.decode(hex); }

    private static Color alpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(Math.max(0f, Math.min(1f, a)) * 255)); }

    private static Color lerp(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
}
