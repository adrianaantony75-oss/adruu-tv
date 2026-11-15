package com.adruu.tv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;

/** Small corner readout of free RAM, system load and chip temperature, shown over every app. */
final class PerfOverlay {
    private static TextView view;

    private PerfOverlay() {}

    static void update(Context c, boolean on) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        if (!on || !Settings.canDrawOverlays(c)) {
            if (view != null) {
                try { wm.removeView(view); } catch (Exception ignored) { }
                view = null;
            }
            return;
        }
        if (view == null) {
            view = new TextView(c.getApplicationContext());
            view.setTextSize(12);
            view.setTextColor(0xFFFFFFFF);
            view.setBackground(UI.round(0xAA000000, UI.dp(c, 8)));
            int pd = UI.dp(c, 8);
            view.setPadding(pd, pd / 2, pd, pd / 2);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = UI.dp(c, 12);
            lp.y = UI.dp(c, 12);
            wm.addView(view, lp);
        }
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        String load = firstLine("/proc/loadavg");
        String temp = firstLine("/sys/class/thermal/thermal_zone0/temp");
        StringBuilder s = new StringBuilder();
        s.append("RAM free ").append(mi.availMem / (1024 * 1024)).append(" / ").append(mi.totalMem / (1024 * 1024)).append(" MB");
        if (load != null) s.append("   Load ").append(load.split(" ")[0]);
        if (temp != null) {
            try {
                int t = Integer.parseInt(temp.trim());
                s.append("   ").append(t > 1000 ? t / 1000 : t).append("°C");
            } catch (Exception ignored) {
            }
        }
        view.setText(s);
    }

    private static String firstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
