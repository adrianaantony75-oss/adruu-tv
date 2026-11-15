package com.adruu.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.SecureRandom;

/**
 * Pairs the ADRUU Remote phone app: the phone asks to pair, the TV shows a 4-digit code on screen
 * for 2 minutes, and only someone who can see the TV can type it. Wrong codes are limited to 5 tries.
 */
final class Pairing {
    private static final long VALID_MS = 120_000;
    private static String code;
    private static long until;
    private static int tries;
    private static LinearLayout overlay;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Pairing() {}

    static synchronized void start(Context c) {
        code = String.format("%04d", new SecureRandom().nextInt(10000));
        until = System.currentTimeMillis() + VALID_MS;
        tries = 0;
        final String shown = code;
        main.post(() -> show(c.getApplicationContext(), shown));
        main.postDelayed(Pairing::hide, VALID_MS);
    }

    /** Returns true once, for the right code within 2 minutes. */
    static synchronized boolean check(String attempt) {
        if (code == null || System.currentTimeMillis() > until) return false;
        if (++tries > 5) { code = null; main.post(Pairing::hide); return false; }
        if (!code.equals(attempt)) return false;
        code = null;
        main.post(Pairing::hide);
        return true;
    }

    private static void show(Context c, String digits) {
        hide();
        if (!Settings.canDrawOverlays(c)) return;
        Theme t = Theme.current(new Prefs(c));
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(UI.round(0xF0000000 | (t.surface & 0xFFFFFF), UI.dp(c, 20)));
        int pd = UI.dp(c, 32);
        box.setPadding(pd, pd, pd, pd);
        box.addView(UI.text(c, "Pair ADRUU Remote", 20, t.text, true));
        TextView big = UI.text(c, digits, 64, t.accent, true);
        big.setLetterSpacing(0.3f);
        big.setPadding(0, UI.dp(c, 12), 0, UI.dp(c, 12));
        box.addView(big);
        box.addView(UI.text(c, "Type this code on your phone. It works for 2 minutes.", 15, t.dim, false));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        try {
            c.getSystemService(WindowManager.class).addView(box, lp);
            overlay = box;
        } catch (Exception ignored) {
        }
    }

    private static void hide() {
        if (overlay == null) return;
        try {
            overlay.getContext().getSystemService(WindowManager.class).removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }
}
