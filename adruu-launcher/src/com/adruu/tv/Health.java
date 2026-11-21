package com.adruu.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * ADRUU's bug-report bot. It records, on the TV:
 *  - crashes (any uncaught error in ADRUU, with the stack trace),
 *  - freezes (the screen thread stuck for 5+ seconds, with where it was stuck),
 *  - health problems found by a self-check every 30 minutes (mouse service off, permissions lost,
 *    server down, RAM very low).
 * Reports live in /sdcard/Android/data/com.adruu.tv/files/reports/ and at GET /api/reports, so the
 * phone app shows them and a developer can read them over ADB to ship a fixed version.
 */
final class Health {
    private static final int MAX_REPORTS = 40;
    private static Context app;
    private static long lastFreeze;
    private static volatile long mainBeat;

    private Health() {}

    static synchronized void install(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            record("crash", "ADRUU crashed on thread " + t.getName() + ": " + e, sw.toString());
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    /** Called every few seconds from a background thread: checks the screen thread is still responding. */
    static void watchdog(Handler main) {
        long now = System.currentTimeMillis();
        if (mainBeat != 0 && now - mainBeat > 5000 && now - lastFreeze > 5 * 60_000L) {
            lastFreeze = now;
            StackTraceElement[] st = Looper.getMainLooper().getThread().getStackTrace();
            record("freeze", "The ADRUU screen was stuck for " + ((now - mainBeat) / 1000) + " s",
                    Arrays.toString(st).replace(", ", "\n  at "));
        }
        main.post(() -> mainBeat = System.currentTimeMillis());
    }

    /** Every 30 minutes: are the things ADRUU needs still switched on? */
    static void selfCheck(Context c) {
        String a11y = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (a11y == null || !a11y.contains("com.adruu.tv/")) {
            record("health", "Mouse / remote Back service is switched off in Accessibility", "enabled_accessibility_services=" + a11y);
        }
        if (!Settings.canDrawOverlays(c)) {
            record("health", "Permission 'display over other apps' is off (lock screen and pairing code can't show over apps)", "");
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        if (mi.availMem < 150L * 1024 * 1024) {
            record("health", "TV RAM very low: " + (mi.availMem >> 20) + " MB free", "threshold=150 MB");
        }
    }

    static synchronized void record(String kind, String summary, String detail) {
        if (app == null) return;
        try {
            File dir = new File(app.getExternalFilesDir(null), "reports");
            if (!dir.exists() && !dir.mkdirs()) return;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            JSONObject j = new JSONObject()
                    .put("kind", kind).put("time", System.currentTimeMillis()).put("summary", summary)
                    .put("version", BuildInfo.VERSION).put("android", android.os.Build.VERSION.RELEASE)
                    .put("model", android.os.Build.MODEL).put("detail", detail);
            try (FileOutputStream o = new FileOutputStream(new File(dir, stamp + "-" + kind + ".json"))) {
                o.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            File[] all = dir.listFiles();
            if (all != null && all.length > MAX_REPORTS) {
                Arrays.sort(all);
                for (int i = 0; i < all.length - MAX_REPORTS; i++) //noinspection ResultOfMethodCallIgnored
                    all[i].delete();
            }
        } catch (Exception ignored) {
        }
    }

    static JSONArray reports(Context c) {
        JSONArray out = new JSONArray();
        File[] all = new File(c.getExternalFilesDir(null), "reports").listFiles();
        if (all == null) return out;
        Arrays.sort(all);
        for (int i = all.length - 1; i >= 0; i--) {
            try {
                byte[] b = java.nio.file.Files.readAllBytes(all[i].toPath());
                JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
                j.put("file", all[i].getName());
                out.put(j);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static int clear(Context c) {
        File[] all = new File(c.getExternalFilesDir(null), "reports").listFiles();
        int n = 0;
        if (all != null) for (File f : all) if (f.delete()) n++;
        return n;
    }
}
