package com.adruu.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

/**
 * Keeps ADRUU's background jobs alive while other apps are open:
 * the phone/control server, Auto mode switching, the bug-report bot and the performance overlay.
 * Slow work runs on a background thread so the TV's screen never stutters.
 */
public class ControlService extends Service {
    private RemoteServer server;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg;
    private String lastMode = "";
    private long lastQuery = System.currentTimeMillis() - 2 * 3600_000L;
    private int ticks;

    /**
     * From the home screen (app in front) a plain start is allowed and can't time out.
     * Only if Android refuses that (app in background) fall back to a foreground start,
     * which onStartCommand always confirms.
     */
    static void start(Context c) {
        try {
            c.startService(new Intent(c, ControlService.class));
        } catch (Exception notAllowedInBackground) {
            try { c.startForegroundService(new Intent(c, ControlService.class)); } catch (Exception ignored) { }
        }
    }

    private Notification note() {
        return new Notification.Builder(this, "adruu")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("ADRUU is running")
                .build();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("adruu", "ADRUU", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, note());   // first thing, so Android never kills us for starting slowly
        Health.install(this);

        worker = new HandlerThread("adruu-bg");
        worker.start();
        bg = new Handler(worker.getLooper());
        bg.post(() -> {
            server = new RemoteServer(this);
            server.start();
            writeControlFile();
            keepAccessibilityOn();
        });
        bg.post(tick);
    }

    /** Every 4 s, off the screen thread: which app is in front, apply the mode, health checks. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Prefs p = new Prefs(ControlService.this);
            try {
                long now = System.currentTimeMillis();
                String fg = Modes.foreground(ControlService.this, lastQuery - 5000);   // only new events
                lastQuery = now;
                if (fg != null) Modes.sForeground = fg;
                String m = Modes.effective(ControlService.this, p.mode());
                if (!m.equals(lastMode)) {
                    lastMode = m;
                    Modes.apply(ControlService.this, m);
                }
            } catch (Exception ignored) {
            }
            Health.watchdog(main);
            ticks++;
            if (ticks % 15 == 0) keepAccessibilityOn();                      // ~every minute
            if (ticks % 450 == 5) Health.selfCheck(ControlService.this);     // ~every 30 min, first after 20 s
            final boolean perf = p.perfOverlay();
            main.post(() -> PerfOverlay.update(ControlService.this, perf));   // views need the screen thread
            bg.postDelayed(this, 4000);
        }
    };

    /**
     * Switches the mouse / remote-Back service back on when Android or TCL turned it off (for example
     * after an update). Needs WRITE_SECURE_SETTINGS, granted once over ADB; without it the bot reports it.
     */
    private void keepAccessibilityOn() {
        try {
            ContentResolver cr = getContentResolver();
            String me = "com.adruu.tv/com.adruu.tv.MouseService";
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur != null && cur.contains(me)) return;
            String next = (cur == null || cur.isEmpty() || cur.equals("null")) ? me : cur + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Health.record("repair", "Switched the mouse / remote-Back service back on", "was: " + cur);
        } catch (SecurityException noPermission) {
            // reported by Health.selfCheck instead
        }
    }

    /**
     * Writes the control address where ADB can read it, so a PC tool (over ADB) can find it:
     * /sdcard/Android/data/com.adruu.tv/files/control.json
     */
    private void writeControlFile() {
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) return;
            Prefs p = new Prefs(this);
            String json = new org.json.JSONObject()
                    .put("port", RemoteServer.PORT)
                    .put("token", p.token())
                    .put("ip", RemoteServer.address(this))
                    .put("version", BuildInfo.VERSION)
                    .toString();
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, "control.json"))) {
                o.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    /** Called when the token changes so PC tools keep finding the right link. */
    static void rewriteControlFile(Context c) {
        start(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, note());   // confirms every foreground start, so none can time out
        if (bg != null) bg.post(this::writeControlFile);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (bg != null) bg.removeCallbacksAndMessages(null);
        if (server != null) server.stop();
        if (worker != null) worker.quitSafely();
        PerfOverlay.update(this, false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
