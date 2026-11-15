package com.adruu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Photo wallpapers (planes, cars, bikes) bundled as assets/wall_*.jpg, 1920x1080.
 * Decoded off the screen thread in RGB_565 (about 4 MB) so the home screen stays smooth.
 */
final class Wallpaper {
    interface Callback { void done(Bitmap b, String name); }

    /** all, mine (family uploads), plane, car, bike, off */
    static final String[] KINDS = {"all", "mine", "plane", "car", "bike", "off"};
    private static final Random RNG = new Random();
    private static String last = "";

    private Wallpaper() {}

    static boolean isValid(String k) {
        for (String x : KINDS) if (x.equals(k)) return true;
        return false;
    }

    static String label(String k) {
        switch (k) {
            case "plane": return "Planes";
            case "car": return "Cars";
            case "bike": return "Bikes";
            case "mine": return "My photos";
            case "off": return "Off (theme colours)";
            default: return "Planes, cars and bikes";
        }
    }

    static String next(String k) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(k)) return KINDS[(i + 1) % KINDS.length];
        return "all";
    }

    static List<String> list(Context c, String kind) {
        List<String> out = new ArrayList<>();
        try {
            // Flat in assets/ as wall_<kind>-NN.jpg (sub-folders get Windows backslashes when built on Windows).
            // Family uploads (full paths) come first; "My photos" shows only those.
            java.io.File[] mine = Media.wallDir(c).listFiles();
            if (mine != null && (kind.equals("all") || kind.equals("mine")))
                for (java.io.File f : mine) out.add(f.getPath());
            if (kind.equals("mine")) return out;
            String[] files = c.getAssets().list("");
            if (files == null) return out;
            for (String f : files) {
                if (!f.startsWith("wall_") || !f.endsWith(".jpg")) continue;
                if (kind.equals("all") || f.startsWith("wall_" + kind + "-")) out.add(f);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Picks a different photo than last time and decodes it in the background. */
    static void loadRandom(Context c, String kind, Handler main, Callback cb) {
        if (kind.equals("off")) { main.post(() -> cb.done(null, null)); return; }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<String> l = list(app, kind);
            if (l.isEmpty()) { main.post(() -> cb.done(null, null)); return; }
            String pick = l.get(RNG.nextInt(l.size()));
            if (l.size() > 1) while (pick.equals(last)) pick = l.get(RNG.nextInt(l.size()));
            last = pick;
            try (InputStream in = pick.startsWith("/") ? new java.io.FileInputStream(pick) : app.getAssets().open(pick)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeStream(in, null, o);
                final String name = pick;
                main.post(() -> cb.done(b, name));
            } catch (Exception e) {
                main.post(() -> cb.done(null, null));
            }
        }, "adruu-wallpaper").start();
    }

    static String credits(Context c) {
        try (InputStream in = c.getAssets().open("wall_credits.txt")) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
