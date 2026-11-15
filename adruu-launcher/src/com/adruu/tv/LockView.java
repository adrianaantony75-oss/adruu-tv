package com.adruu.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen 6-digit PIN lock. Works with the remote's arrow keys or its number buttons. */
final class LockView extends LinearLayout {
    interface Listener { void unlocked(); }

    static final int LEN = 6;
    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 60_000;

    private final Prefs p;
    private final Theme t;
    private final Listener listener;
    private final StringBuilder pin = new StringBuilder();
    private final View[] dots = new View[LEN];
    private final TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView first;

    LockView(Context c, Prefs prefs, Theme theme, Listener l) {
        super(c);
        p = prefs;
        t = theme;
        listener = l;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setBackground(UI.backdrop(t));
        setClickable(true);

        TextView logo = UI.text(c, "ADRUU", 30, t.accent, true);
        logo.setLetterSpacing(0.3f);
        addView(logo);
        TextView title = UI.text(c, "Enter PIN to unlock", 18, t.text, false);
        title.setPadding(0, UI.dp(c, 14), 0, UI.dp(c, 22));
        addView(title);

        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < LEN; i++) {
            View d = new View(c);
            int s = UI.dp(c, 16);
            LayoutParams lp = new LayoutParams(s, s);
            lp.setMargins(UI.dp(c, 9), 0, UI.dp(c, 9), 0);
            row.addView(d, lp);
            dots[i] = d;
        }
        addView(row);

        status = UI.text(c, " ", 14, t.dim, false);
        status.setPadding(0, UI.dp(c, 16), 0, UI.dp(c, 18));
        addView(status);

        GridLayout pad = new GridLayout(c);
        pad.setColumnCount(3);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "⌫"};
        for (String k : keys) {
            TextView b = UI.pill(c, t, k, 20);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = UI.dp(c, 92);
            lp.height = UI.dp(c, 56);
            lp.setMargins(UI.dp(c, 7), UI.dp(c, 7), UI.dp(c, 7), UI.dp(c, 7));
            b.setLayoutParams(lp);
            if (k.equals("Clear")) b.setTextSize(14);
            b.setOnClickListener(v -> {
                if (k.equals("Clear")) clear();
                else if (k.equals("⌫")) back();
                else digit(k.charAt(0) - '0');
            });
            if (first == null) first = b;
            pad.addView(b);
        }
        addView(pad);
        updateDots();
    }

    void reset() {
        clear();
        tickLockout();
        if (first != null) first.requestFocus();
    }

    void digit(int d) {
        if (lockedOut()) return;
        if (pin.length() < LEN) pin.append(d);
        updateDots();
        if (pin.length() == LEN) h.postDelayed(this::check, 120);
    }

    void back() {
        if (pin.length() > 0) pin.setLength(pin.length() - 1);
        updateDots();
    }

    private void clear() {
        pin.setLength(0);
        updateDots();
    }

    private void check() {
        if (p.checkPin(pin.toString())) {
            p.fails(0);
            status.setText(" ");
            clear();
            listener.unlocked();
            return;
        }
        int f = p.fails() + 1;
        p.addEvent(f >= MAX_FAILS ? "Too many wrong PINs on the TV - locked for 1 minute" : "Wrong PIN entered on the TV");
        if (f >= MAX_FAILS) {
            p.fails(0);
            p.lockoutUntil(System.currentTimeMillis() + LOCKOUT_MS);
            tickLockout();
        } else {
            p.fails(f);
            status.setText("Wrong PIN · " + (MAX_FAILS - f) + " tries left");
        }
        status.setTextColor(0xFFFF5A5F);
        animate().translationX(UI.dp(getContext(), 14)).setDuration(50).withEndAction(() ->
                animate().translationX(-UI.dp(getContext(), 14)).setDuration(80).withEndAction(() ->
                        animate().translationX(0).setDuration(50)));
        clear();
    }

    private boolean lockedOut() {
        return System.currentTimeMillis() < p.lockoutUntil();
    }

    private void tickLockout() {
        long left = p.lockoutUntil() - System.currentTimeMillis();
        if (left > 0) {
            status.setTextColor(0xFFFF5A5F);
            status.setText("Too many wrong tries · wait " + ((left + 999) / 1000) + " s");
            h.postAtTime(this::tickLockout, SystemClock.uptimeMillis() + 1000);
        } else if (status.getText().toString().startsWith("Too many")) {
            status.setTextColor(t.dim);
            status.setText(" ");
        }
    }

    private void updateDots() {
        for (int i = 0; i < LEN; i++) {
            dots[i].setBackground(i < pin.length()
                    ? UI.round(t.accent, UI.dp(getContext(), 8))
                    : UI.ring(t.dim, UI.dp(getContext(), 8), UI.dp(getContext(), 2)));
        }
    }
}
