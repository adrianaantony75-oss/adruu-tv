package com.adruu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Things the family uploads from the phone app:
 *  - wallpaper photos (centre-cropped to 1920x1080 and saved as JPEG),
 *  - a custom startup video that replaces the plane intro (size limit {@link #MAX_INTRO_MB}),
 *  - a colour theme made from a photo ("photo" theme).
 * Everything lives in the app's own storage on the TV.
 */
final class Media {
    static final int MAX_PHOTO_MB = 25;
    static final int MAX_INTRO_MB = 200;

    private Media() {}

    static File wallDir(Context c) {
        File d = new File(c.getFilesDir(), "walls");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static File introFile(Context c) { return new File(c.getFilesDir(), "intro.mp4"); }

    /** Album names are folder names: letters, digits, spaces, - and _ only. */
    static String cleanAlbum(String a) {
        if (a == null) return "My photos";
        a = a.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (a.length() > 30) a = a.substring(0, 30);
        return a.isEmpty() ? "My photos" : a;
    }

    static File albumDir(Context c, String album) {
        File d = new File(wallDir(c), cleanAlbum(album));
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    static JSONArray albums(Context c) throws Exception {
        JSONArray a = new JSONArray();
        File[] ds = wallDir(c).listFiles();
        if (ds != null) for (File d : ds) if (d.isDirectory()) {
            File[] fs = d.listFiles();
            a.put(new JSONObject().put("name", d.getName()).put("count", fs == null ? 0 : fs.length));
        }
        return a;
    }

    /** All photo files in one album, or in every album when album is null. */
    static java.util.List<File> photos(Context c, String album) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File[] ds = album == null ? wallDir(c).listFiles() : new File[]{new File(wallDir(c), cleanAlbum(album))};
        if (ds == null) return out;
        for (File d : ds) {
            if (d.isFile()) { out.add(d); continue; }   // photos saved before albums existed
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) out.add(f);
        }
        return out;
    }

    /** Streams an upload to a temp file (never the whole thing in RAM: the TV only has 2 GB). */
    static File receive(Context c, InputStream in, int len, String name) throws Exception {
        File tmp = new File(c.getCacheDir(), name);
        try (OutputStream o = new FileOutputStream(tmp)) {
            byte[] b = new byte[64 * 1024];
            int left = len;
            while (left > 0) {
                int n = in.read(b, 0, Math.min(b.length, left));
                if (n < 0) break;
                o.write(b, 0, n);
                left -= n;
            }
        }
        return tmp;
    }

    /** Decodes a phone photo at a safe size, crops it to 16:9, saves 1920x1080 JPEG. Returns the file name. */
    static String saveWallpaper(Context c, File src, String album) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(src.getPath(), probe);
        if (probe.outWidth <= 0) throw new IllegalArgumentException("That isn't a photo the TV can read");
        int sample = 1;
        while (probe.outWidth / (sample * 2) >= 1920 && probe.outHeight / (sample * 2) >= 1080) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(src.getPath(), o);
        if (b == null) throw new IllegalArgumentException("That isn't a photo the TV can read");
        float target = 16f / 9f;
        int w = b.getWidth(), h = b.getHeight(), cw = w, ch = h;
        if ((float) w / h > target) cw = Math.round(h * target); else ch = Math.round(w / target);
        Rect from = new Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch);
        Bitmap out = Bitmap.createBitmap(1920, 1080, Bitmap.Config.RGB_565);
        new Canvas(out).drawBitmap(b, from, new Rect(0, 0, 1920, 1080), new Paint(Paint.FILTER_BITMAP_FLAG));
        b.recycle();
        String name = "my-" + System.currentTimeMillis() + ".jpg";
        try (OutputStream os = new FileOutputStream(new File(albumDir(c, album), name))) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, os);
        }
        out.recycle();
        //noinspection ResultOfMethodCallIgnored
        src.delete();
        return name;
    }

    static JSONArray listWallpapers(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (File f : photos(c, null)) {
            String album = f.getParentFile().equals(wallDir(c)) ? "" : f.getParentFile().getName();
            a.put(new JSONObject().put("album", album).put("name", f.getName()).put("kb", f.length() / 1024));
        }
        return a;
    }

    static boolean deleteWallpaper(Context c, String album, String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        File dir = album == null || album.isEmpty() ? wallDir(c) : new File(wallDir(c), cleanAlbum(album));
        boolean ok = new File(dir, name).delete();
        String[] left = dir.list();
        if (ok && !dir.equals(wallDir(c)) && left != null && left.length == 0) //noinspection ResultOfMethodCallIgnored
            dir.delete();
        return ok;
    }

    /**
     * Picks theme colours from a photo: a dark background from its overall colour and the most vivid,
     * bright colour in it as the accent. Saved as the "photo" theme.
     */
    static void themeFromPhoto(Context c, File photo) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 16;
        Bitmap b = BitmapFactory.decodeFile(photo.getPath(), o);
        if (b == null) return;
        Bitmap s = Bitmap.createScaledBitmap(b, 48, 27, true);
        b.recycle();
        float[] hsv = new float[3];
        long r = 0, g = 0, bl = 0;
        int best = 0xFF4F8CFF;
        float bestScore = -1;
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                int px = s.getPixel(x, y);
                r += Color.red(px); g += Color.green(px); bl += Color.blue(px);
                Color.colorToHSV(px, hsv);
                float score = hsv[1] * (hsv[2] > 0.35f ? hsv[2] : 0);
                if (score > bestScore) { bestScore = score; best = px; }
            }
        }
        int n = s.getWidth() * s.getHeight();
        s.recycle();
        int avg = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        Color.colorToHSV(avg, hsv);
        hsv[2] = 0.10f; hsv[1] = Math.min(hsv[1], 0.6f);
        int bg = Color.HSVToColor(hsv);
        hsv[2] = 0.18f;
        int bg2 = Color.HSVToColor(hsv);
        hsv[2] = 0.22f;
        int surface = Color.HSVToColor(hsv);
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f); hsv[2] = Math.max(hsv[2], 0.85f);
        int accent = Color.HSVToColor(hsv);
        new Prefs(c).photoTheme(bg, bg2, surface, accent);
    }

    /** Saves an uploaded video as the startup animation if it's within the size limit. */
    static void saveIntro(Context c, File tmp) throws Exception {
        File dst = introFile(c);
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (!tmp.renameTo(dst)) throw new IllegalStateException("Couldn't save the video");
    }
}
