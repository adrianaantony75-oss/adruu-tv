package com.adruu.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All ADRUU settings, kept small on purpose. */
final class Prefs {
    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("adruu", Context.MODE_PRIVATE);
    }

    String theme() { return p.getString("theme", "midnight"); }
    void theme(String id) { p.edit().putString("theme", id).apply(); }

    boolean festival() { return p.getBoolean("festival", true); }
    void festival(boolean on) { p.edit().putBoolean("festival", on).apply(); }

    boolean clicks() { return p.getBoolean("clicks", true); }
    void clicks(boolean on) { p.edit().putBoolean("clicks", on).apply(); }

    boolean intro() { return p.getBoolean("intro", true); }
    void intro(boolean on) { p.edit().putBoolean("intro", on).apply(); }

    /** Photo wallpaper: all, plane, car, bike, off */
    String wallpaper() { return p.getString("wallpaper", "all"); }
    void wallpaper(String k) { p.edit().putString("wallpaper", k).apply(); }

    // ---- phone alerts (ntfy.sh) ----
    boolean notifyOn() { return p.getBoolean("notify", false); }
    void notifyOn(boolean on) { p.edit().putBoolean("notify", on).apply(); if (on) notifyTopic(); }
    /** Private random topic name, created once. */
    String notifyTopic() {
        String t = p.getString("ntopic", null);
        if (t == null) {
            byte[] b = new byte[12];
            new SecureRandom().nextBytes(b);
            t = "adruu-" + hex(b);
            p.edit().putString("ntopic", t).apply();
        }
        return t;
    }

    // ---- lock-screen "intruder" message ----
    /** Text shown (and spoken) when someone tries to use the locked TV. */
    String intruderText() { return p.getString("itext", "നീ പോ മോനേ ദിനേശാ"); }
    void intruderText(String s) { p.edit().putString("itext", s).apply(); }

    /** App tile corners on the TV home screen: rounded or square. */
    String tileStyle() { return p.getString("tiles", "rounded"); }
    void tileStyle(String s) { p.edit().putString("tiles", s).apply(); }

    /** Minutes between wallpaper changes on the home screen (0 = only when the TV wakes). */
    int slideshow() { return p.getInt("slideshow", 10); }
    void slideshow(int m) { p.edit().putInt("slideshow", m).apply(); }

    boolean perfOverlay() { return p.getBoolean("perf", false); }
    void perfOverlay(boolean on) { p.edit().putBoolean("perf", on).apply(); }

    boolean subtitles() { return p.getBoolean("subs", false); }
    void subtitles(boolean on) { p.edit().putBoolean("subs", on).apply(); }

    /** normal, movie, music, game, auto */
    String mode() { return p.getString("mode", "auto"); }
    void mode(String m) { p.edit().putString("mode", m).apply(); }

    boolean guest() { return p.getBoolean("guest", false); }
    void guest(boolean on) { p.edit().putBoolean("guest", on).apply(); }

    String city() { return p.getString("city", "Kochi"); }
    void city(String c) {
        p.edit().putString("city", c).remove("lat").remove("lon").remove("weather").apply();
    }

    boolean hasCoords() { return p.contains("lat"); }
    double lat() { return Double.longBitsToDouble(p.getLong("lat", 0)); }
    double lon() { return Double.longBitsToDouble(p.getLong("lon", 0)); }
    void coords(double lat, double lon) {
        p.edit().putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon)).apply();
    }

    /** Colours picked from the family's own photo (see Media.themeFromPhoto). */
    void photoTheme(int bg, int bg2, int surface, int accent) {
        p.edit().putInt("ptBg", bg).putInt("ptBg2", bg2).putInt("ptSurface", surface).putInt("ptAccent", accent)
                .putBoolean("ptSet", true).apply();
    }
    Theme photoTheme() {
        if (!p.getBoolean("ptSet", false)) return null;
        return new Theme("photo", "From my photo", p.getInt("ptBg", 0xFF0B0F1A), p.getInt("ptBg2", 0xFF141B2D),
                p.getInt("ptSurface", 0xFF1B2438), p.getInt("ptAccent", 0xFF4F8CFF), 0xFF000000,
                0xFFF2F5FA, 0xFFA0A8B8, null);
    }

    /** JSON array of the next 12 hours: {h, t, r (rain %), w (wind km/h), c (sky)} */
    String hourly() { return p.getString("hourly", "[]"); }
    void hourly(String json) { p.edit().putString("hourly", json).apply(); }

    String lastWeather() { return p.getString("weather", ""); }
    void lastWeather(String w) { p.edit().putString("weather", w).apply(); }

    /** Apps hidden from the home screen (package names). */
    List<String> hidden() { return list("hidden"); }
    void hidden(List<String> pkgs) { putList("hidden", pkgs); }

    /** Custom order for the first tiles; empty = built-in favourites. */
    List<String> favourites() { return list("favs"); }
    void favourites(List<String> pkgs) { putList("favs", pkgs); }

    /** Apps hidden while guest mode is on. */
    List<String> personal() {
        List<String> l = list("personal");
        if (!p.contains("personal")) l = new ArrayList<>(Arrays.asList(
                "com.android.vending", "com.esaba.downloader", "com.yablio.sendfilestotv",
                "adruu:settings", "adruu:tvsettings"));
        return l;
    }
    void personal(List<String> pkgs) { putList("personal", pkgs); }

    // ---- phone / API control ----
    /** Secret for the phone page and the control API. Created once. */
    String token() {
        String t = p.getString("token", null);
        if (t == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            t = hex(b);
            p.edit().putString("token", t).apply();
        }
        return t;
    }
    void newToken() { p.edit().remove("token").apply(); token(); }

    /** Recent alerts (wrong PIN etc.), newest first, max 20. */
    synchronized void addEvent(String text) {
        try {
            JSONArray old = new JSONArray(p.getString("events", "[]"));
            JSONArray n = new JSONArray();
            n.put(new JSONObject().put("t", System.currentTimeMillis()).put("text", text));
            for (int i = 0; i < old.length() && i < 19; i++) n.put(old.get(i));
            p.edit().putString("events", n.toString()).apply();
        } catch (Exception ignored) {
        }
    }
    String eventsJson() { return p.getString("events", "[]"); }

    // ---- TV lock ----
    boolean lockOn() { return p.getBoolean("lock", false) && p.contains("pinHash"); }
    void lockOn(boolean on) { p.edit().putBoolean("lock", on).apply(); }
    boolean hasPin() { return p.contains("pinHash"); }

    void setPin(String pin) {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        String salt = hex(b);
        p.edit().putString("pinSalt", salt).putString("pinHash", hash(salt, pin)).apply();
    }

    void removePin() { p.edit().remove("pinHash").remove("pinSalt").putBoolean("lock", false).apply(); }

    boolean checkPin(String pin) {
        String salt = p.getString("pinSalt", "");
        return hash(salt, pin).equals(p.getString("pinHash", "-"));
    }

    int fails() { return p.getInt("fails", 0); }
    void fails(int n) { p.edit().putInt("fails", n).apply(); }
    long lockoutUntil() { return p.getLong("lockout", 0); }
    void lockoutUntil(long t) { p.edit().putLong("lockout", t).apply(); }

    // ---- helpers ----
    private List<String> list(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void putList(String key, List<String> l) {
        p.edit().putString(key, new JSONArray(l).toString()).apply();
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
