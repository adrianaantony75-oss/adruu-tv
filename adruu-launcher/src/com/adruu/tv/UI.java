package com.adruu.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** Small helpers so the UI is built in code (no heavy layout inflation). */
final class UI {
    private UI() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable ring(int color, float radiusPx, int widthPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0);
        g.setStroke(widthPx, color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable backdrop(Theme t) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{t.bg, t.bg2});
    }

    static StateListDrawable button(Theme t, float r) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(t.accent, r));
        s.addState(new int[]{android.R.attr.state_pressed}, round(t.accent, r));
        s.addState(new int[]{}, round(t.surface, r));
        return s;
    }

    static ColorStateList buttonText(Theme t) {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{t.onAccent, t.onAccent, t.text});
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Fonts.ui(c, bold));
        v.setIncludeFontPadding(false);
        // Soft shadow keeps text readable on top of photo wallpapers.
        v.setShadowLayer(dp(c, 4), 0, dp(c, 1), 0xB0000000);
        return v;
    }

    /** A focusable pill/button that reacts to the remote. */
    static TextView pill(Context c, Theme t, String s, float sp) {
        TextView v = text(c, s, sp, t.text, true);
        v.setFocusable(true);
        v.setClickable(true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(button(t, dp(c, 14)));
        v.setTextColor(buttonText(t));
        int ph = dp(c, 22), pv = dp(c, 12);
        v.setPadding(ph, pv, ph, pv);
        return v;
    }
}
