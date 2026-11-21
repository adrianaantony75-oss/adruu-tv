package com.adruu.tv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Mouse pointer mode. While on: arrow keys move the pointer (faster when held),
 * OK clicks where the pointer is, pushing past the top/bottom edge scrolls, Back turns it off.
 * Turned on from the ADRUU home screen, the phone page, or through the control API.
 */
public class MouseService extends AccessibilityService {
    private static MouseService sInstance;

    private WindowManager wm;
    private Pointer pointer;
    private WindowManager.LayoutParams lp;
    private float x, y;
    private int sw, sh;

    static boolean isReady() { return sInstance != null; }
    static boolean isActive() { return sInstance != null && sInstance.pointer != null; }

    static void setActive(boolean on) {
        if (sInstance != null) sInstance.show(on);
    }

    static void toggle() { setActive(!isActive()); }

    /** Back / Home / Recents / screen off for the phone remote (accessibility global actions). */
    static boolean global(int action) {
        return sInstance != null && sInstance.performGlobalAction(action);
    }

    // ---- phone touchpad / air mouse (called on the main thread) ----

    /** Moves the pointer by (dx, dy) screen pixels, showing it first if needed. */
    static boolean move(float dx, float dy) {
        MouseService m = sInstance;
        if (m == null) return false;
        if (m.pointer == null) m.show(true);
        m.lastKey = System.currentTimeMillis();
        m.x = Math.max(0, Math.min(m.sw - 2, m.x + dx));
        m.y = Math.max(0, Math.min(m.sh - 2, m.y + dy));
        m.place();
        return true;
    }

    static boolean tapHere() {
        MouseService m = sInstance;
        if (m == null || m.pointer == null) return false;
        m.lastKey = System.currentTimeMillis();
        m.tap();
        return true;
    }

    /** Scroll buttons: swipes in the middle of the screen (or at the pointer when it's showing). */
    static boolean scroll(String dir) {
        MouseService m = sInstance;
        if (m == null) return false;
        DisplayMetrics dm = m.getResources().getDisplayMetrics();
        float cx = m.pointer != null ? m.x : dm.widthPixels / 2f, cy = m.pointer != null ? m.y : dm.heightPixels / 2f;
        float d = dm.heightPixels * 0.3f;
        Path p = new Path();
        switch (dir) {
            case "up": p.moveTo(cx, cy - d / 2); p.lineTo(cx, cy + d / 2); break;       // content moves down
            case "down": p.moveTo(cx, cy + d / 2); p.lineTo(cx, cy - d / 2); break;
            case "left": p.moveTo(cx - d / 2, cy); p.lineTo(cx + d / 2, cy); break;
            case "right": p.moveTo(cx + d / 2, cy); p.lineTo(cx - d / 2, cy); break;
            default: return false;
        }
        return m.dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 220)).build(), null, null);
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        filterKeys(false);
    }

    /** Only intercept remote keys while the pointer is on, so normal use has zero added key latency. */
    private void filterKeys(boolean on) {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int f = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.flags = on ? (info.flags | f) : (info.flags & ~f);
        setServiceInfo(info);
    }

    private void show(boolean on) {
        if (on && pointer == null) {
            filterKeys(true);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = dm.heightPixels;
            x = sw / 2f;
            y = sh / 2f;
            pointer = new Pointer(this);
            int size = UI.dp(this, 30);
            lp = new WindowManager.LayoutParams(size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            place();
            wm.addView(pointer, lp);
            lastKey = System.currentTimeMillis();
            idle.postDelayed(idleCheck, 15_000);
        } else if (!on && pointer != null) {
            wm.removeView(pointer);
            pointer = null;
            filterKeys(false);
        }
    }

    private void place() {
        lp.x = Math.round(x);
        lp.y = Math.round(y);
        if (pointer != null && pointer.isAttachedToWindow()) wm.updateViewLayout(pointer, lp);
    }

    private long lastKey;
    private final android.os.Handler idle = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Switches the pointer off after 2 minutes without a key press, so it never gets left on screen. */
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (pointer == null) return;
            if (System.currentTimeMillis() - lastKey > 120_000) show(false);
            else idle.postDelayed(this, 15_000);
        }
    };

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (pointer == null) return false;
        lastKey = System.currentTimeMillis();
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        float step = UI.dp(this, 9) * (1 + Math.min(e.getRepeatCount(), 25) * 0.35f);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) { x = Math.max(0, x - step); place(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) { x = Math.min(sw - 2, x + step); place(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (y <= 0) swipe(sh * 0.35f, sh * 0.65f);
                    else { y = Math.max(0, y - step); place(); }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (y >= sh - 2) swipe(sh * 0.65f, sh * 0.35f);
                    else { y = Math.min(sh - 2, y + step); place(); }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) tap();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) show(false);
                return true;
            default:
                return false;
        }
    }

    private void tap() {
        Path p = new Path();
        p.moveTo(x + 1, y + 1);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    private void swipe(float fromY, float toY) {
        Path p = new Path();
        p.moveTo(x, fromY);
        p.lineTo(x, toY);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build(), null, null);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        show(false);
        sInstance = null;
        return super.onUnbind(intent);
    }

    /** White arrow pointer with a dark outline. */
    private static final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        Pointer(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF111111);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(UI.dp(c, 1.5f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 30f;
            arrow.reset();
            arrow.moveTo(2 * s, 2 * s);
            arrow.lineTo(2 * s, 24 * s);
            arrow.lineTo(8 * s, 18 * s);
            arrow.lineTo(12 * s, 27 * s);
            arrow.lineTo(16 * s, 25 * s);
            arrow.lineTo(12 * s, 16 * s);
            arrow.lineTo(20 * s, 16 * s);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }
}
