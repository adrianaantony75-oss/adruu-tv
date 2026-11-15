package com.adruu.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * Startup animation (about 6 seconds, any remote key skips it):
 * a jet lands on the runway and rolls to a stop, the door opens, stairs come down,
 * a pilot in uniform walks down and toward you, waves, and "ADRUU" appears below him.
 */
final class IntroView extends View {
    // Timeline (seconds)
    private static final float DESCENT = 2.0f, ROLL = 2.8f, DOOR = 3.1f, APPEAR = 3.3f,
            STAIRS = 3.9f, WALK = 4.8f, END = 5.9f;
    private static final float ROLL_TIME = ROLL - DESCENT;

    private static final int NAVY = 0xFF1C2B4D, GOLD = 0xFFF2C14E, SHIRT = 0xFFF5F7FA,
            SHOE = 0xFF0A0A0A, HULL = 0xFFE9EEF5, ENGINE = 0xFFB7C0CC, WINDOW = 0xFF2A3445;

    private final Theme t;
    private final Runnable done;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fuselage = new Path(), fin = new Path(), wing = new Path(),
            cockpit = new Path(), jacket = new Path(), collar = new Path();
    private final RectF r = new RectF();
    private long start;
    private boolean finished;

    IntroView(Context c, Theme theme, Runnable onDone) {
        super(c);
        t = theme;
        done = onDone;
        setBackground(UI.backdrop(t));
        logo.setColor(t.text);
        logo.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        logo.setTextAlign(Paint.Align.CENTER);
        logo.setLetterSpacing(0.35f);

        // Airliner side view, facing right. Units: nose at x=402, fuselage centre y=0, wheels touch y=48.
        fuselage.moveTo(30, -18);
        fuselage.lineTo(350, -18);
        fuselage.cubicTo(380, -18, 402, -10, 402, 0);
        fuselage.cubicTo(402, 8, 385, 18, 350, 18);
        fuselage.lineTo(30, 18);
        fuselage.lineTo(8, 4);
        fuselage.lineTo(0, -12);
        fuselage.lineTo(10, -18);
        fuselage.close();
        fin.moveTo(10, -18);
        fin.lineTo(58, -18);
        fin.lineTo(26, -64);
        fin.lineTo(8, -64);
        fin.close();
        wing.moveTo(150, 6);
        wing.lineTo(236, 6);
        wing.lineTo(200, 21);
        wing.lineTo(128, 21);
        wing.close();
        cockpit.moveTo(366, -12);
        cockpit.lineTo(386, -10);
        cockpit.lineTo(394, -3);
        cockpit.lineTo(369, -3);
        cockpit.close();

        // Pilot, units: feet at y=0, top of cap at y=-101.
        jacket.moveTo(-11, -76);
        jacket.lineTo(11, -76);
        jacket.lineTo(9, -38);
        jacket.lineTo(-9, -38);
        jacket.close();
        collar.moveTo(-4.5f, -76);
        collar.lineTo(4.5f, -76);
        collar.lineTo(0, -63);
        collar.close();
    }

    void begin() {
        start = SystemClock.uptimeMillis();
        postInvalidateOnAnimation();
    }

    void skip() {
        if (!finished) {
            finished = true;
            done.run();
        }
    }

    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }
    private static float lerp(float a, float b, float k) { return a + (b - a) * k; }
    private static float easeOut(float x) { return 1 - (1 - x) * (1 - x); }
    private static float smooth(float x) { return x * x * (3 - 2 * x); }

    @Override
    protected void onDraw(Canvas c) {
        if (finished) return;
        float s = (SystemClock.uptimeMillis() - start) / 1000f;
        float w = getWidth(), h = getHeight();
        float groundY = h * 0.66f;
        float u = w * 0.42f / 402f;                    // plane scale

        drawScenery(c, w, h, groundY);

        // ---- plane position ----
        float yGround = groundY - 48 * u;
        float stopX = w * 0.60f - 335 * u;              // door ends up at 60% of the width
        float x0 = -402 * u, y0 = groundY - h * 0.47f;
        float v = (stopX - x0) / (DESCENT + ROLL_TIME / 2);
        float touchX = stopX - v * ROLL_TIME / 2;
        float px, py, angle;
        if (s < DESCENT) {
            float k = s / DESCENT;
            px = lerp(x0, touchX, k);
            py = lerp(y0, yGround, (float) (1 - Math.pow(1 - k, 1.6)));
            angle = k < 0.8f ? -3f : lerp(-3f, -7f, (k - 0.8f) / 0.2f);   // flare before touchdown
        } else {
            float k = clamp((s - DESCENT) / ROLL_TIME);
            px = touchX + (stopX - touchX) * easeOut(k);
            py = yGround;
            angle = lerp(-7f, 0f, smooth(clamp((s - DESCENT) / 0.35f)));  // nose wheel comes down
        }
        drawPlane(c, px, py, u, angle, clamp((s - ROLL) / (DOOR - ROLL)));

        // ---- stairs ----
        float doorLX = stopX + 326 * u, doorBY = yGround + 13 * u;
        float stairBX = doorLX - 46 * u;
        if (s >= ROLL) {
            float k = smooth(clamp((s - ROLL) / (DOOR - ROLL + 0.15f)));
            drawStairs(c, doorLX, doorBY, lerp(doorLX, stairBX, k), lerp(doorBY, groundY, k), u);
        }

        // ---- pilot ----
        float ps0 = u * 0.30f, ps1 = h * 0.30f / 100f;
        float standY = groundY + h * 0.14f;
        if (s >= DOOR) {
            float fx, fy, scale, phase = s * 7f;
            boolean walking = true;
            int face = -1;
            float alpha = 1f;
            if (s < APPEAR) {
                fx = stopX + 335 * u; fy = doorBY; scale = ps0; walking = false;
                alpha = clamp((s - DOOR) / (APPEAR - DOOR));
            } else if (s < STAIRS) {
                float k = (s - APPEAR) / (STAIRS - APPEAR);
                fx = lerp(stopX + 335 * u, stairBX, k); fy = lerp(doorBY, groundY, k); scale = ps0;
            } else if (s < WALK) {
                float k = smooth((s - STAIRS) / (WALK - STAIRS));
                fx = lerp(stairBX, w / 2f, k); fy = lerp(groundY, standY, k); scale = lerp(ps0, ps1, k * k);
                if (k > 0.85f) face = 0;
            } else {
                fx = w / 2f; fy = standY; scale = ps1; walking = false; face = 0;
            }
            if (s >= STAIRS) {          // dim the plane as he comes closer
                p.setColor(t.bg);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha((int) (120 * smooth(clamp((s - STAIRS) / (WALK - STAIRS)))));
                c.drawRect(0, 0, w, h, p);
            }
            float wave = s >= WALK ? (float) (0.55 + 0.35 * Math.sin((s - WALK) * 9)) : -1f;
            drawPilot(c, fx, fy, scale, phase, walking, face, wave, alpha);
        }

        // ---- ADRUU below the pilot ----
        float tk = clamp((s - WALK) / 0.5f);
        if (tk > 0) {
            float size = h * 0.075f;
            float ty = standY + size * 1.35f;
            logo.setTextSize(size * (0.9f + 0.1f * easeOut(tk)));
            logo.setAlpha((int) (255 * tk));
            c.drawText("ADRUU", w / 2f + size * 0.17f, ty, logo);
            p.setColor(t.accent);
            p.setAlpha((int) (255 * tk));
            float lw = w * 0.09f * easeOut(tk);
            c.drawRect(w / 2f - lw, ty + size * 0.28f, w / 2f + lw, ty + size * 0.28f + 5, p);
        }

        if (s >= END) skip();
        else postInvalidateOnAnimation();
    }

    private void drawScenery(Canvas c, float w, float h, float groundY) {
        p.setStyle(Paint.Style.FILL);
        // control tower
        p.setColor(t.surface);
        c.drawRect(w * 0.095f, groundY - h * 0.22f, w * 0.11f, groundY, p);
        r.set(w * 0.083f, groundY - h * 0.27f, w * 0.122f, groundY - h * 0.22f);
        c.drawRoundRect(r, 6, 6, p);
        p.setColor(t.accent);
        p.setAlpha(150);
        c.drawRect(w * 0.087f, groundY - h * 0.255f, w * 0.118f, groundY - h * 0.235f, p);
        // runway
        p.setColor(t.surface);
        c.drawRect(0, groundY - 2, w, groundY + h * 0.20f, p);
        p.setColor(t.dim);
        p.setAlpha(110);
        float dashY = groundY + h * 0.095f;
        for (float x = 0; x < w; x += w * 0.07f) c.drawRect(x, dashY - 3, x + w * 0.04f, dashY + 3, p);
        p.setColor(t.accent);
        p.setAlpha(200);
        for (float x = w * 0.02f; x < w; x += w * 0.06f) {
            c.drawCircle(x, groundY + 2, 3.5f, p);
            c.drawCircle(x, groundY + h * 0.20f - 3, 4.5f, p);
        }
    }

    private void drawPlane(Canvas c, float x, float y, float u, float angle, float doorK) {
        c.save();
        c.translate(x, y);
        c.scale(u, u);
        c.rotate(angle, 190, 48);                      // pivot on the main wheels
        p.setStyle(Paint.Style.FILL);
        p.setColor(t.accent);
        c.drawPath(fin, p);
        c.drawRect(0, -9, 44, -4, p);                  // tail plane
        p.setColor(HULL);
        c.drawPath(fuselage, p);
        p.setColor(t.accent);
        c.drawRect(40, 4, 362, 7, p);                  // cheat line
        p.setColor(WINDOW);
        for (float wx = 90; wx <= 318; wx += 14) c.drawCircle(wx, -6, 3, p);
        c.drawPath(cockpit, p);
        // door: outline when shut, dark opening when open
        r.set(326, -13, 344, 13);
        if (doorK > 0) {
            p.setColor(WINDOW);
            p.setAlpha((int) (255 * doorK));
            c.drawRoundRect(r, 3, 3, p);
            p.setAlpha(255);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f);
        p.setColor(ENGINE);
        c.drawRoundRect(r, 3, 3, p);
        p.setStyle(Paint.Style.FILL);
        // wing, engine, landing gear
        p.setColor(ENGINE);
        c.drawPath(wing, p);
        r.set(174, 16, 222, 31);
        c.drawRoundRect(r, 7, 7, p);
        p.setColor(WINDOW);
        c.drawRect(190, 18, 194, 40, p);
        c.drawRect(353, 18, 357, 40, p);
        p.setColor(SHOE);
        c.drawCircle(192, 41, 7, p);
        c.drawCircle(355, 41, 7, p);
        c.restore();
    }

    private void drawStairs(Canvas c, float tx, float ty, float bx, float by, float u) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(t.dim);
        p.setStrokeWidth(3.2f * u);
        c.drawLine(tx, ty, bx, by, p);
        c.drawLine(tx, ty - 9 * u, bx, by - 9 * u, p); // hand rail
        p.setStrokeWidth(1.4f * u);
        for (int i = 1; i < 6; i++) {
            float k = i / 6f;
            float sx = lerp(tx, bx, k), sy = lerp(ty, by, k);
            c.drawLine(sx, sy, sx, sy - 9 * u, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** face: -1 facing left, 1 facing right, 0 facing you. wave < 0 = not waving. */
    private void drawPilot(Canvas c, float x, float y, float scale, float phase, boolean walking,
                           int face, float wave, float alpha) {
        int a = (int) (255 * alpha);
        c.save();
        c.translate(x, y);
        c.scale(scale, scale);
        float swing = walking ? (float) Math.sin(phase) * 0.45f : 0f;
        p.setStrokeCap(Paint.Cap.ROUND);

        // legs and shoes
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6.5f);
        p.setColor(NAVY);
        p.setAlpha(a);
        float lx1 = (float) Math.sin(swing) * 40, ly1 = -40 + (float) Math.cos(swing) * 40;
        float lx2 = (float) Math.sin(-swing) * 40, ly2 = -40 + (float) Math.cos(-swing) * 40;
        if (face == 0) { lx1 = -6; lx2 = 6; ly1 = ly2 = 0; }
        c.drawLine(face == 0 ? -4 : 0, -40, lx1, ly1, p);
        c.drawLine(face == 0 ? 4 : 0, -40, lx2, ly2, p);
        p.setColor(SHOE);
        p.setAlpha(a);
        p.setStrokeWidth(5.5f);
        float toe = face == 0 ? 0 : face * 7;
        c.drawLine(lx1, ly1, lx1 + toe + (face == 0 ? -4 : 0), ly1, p);
        c.drawLine(lx2, ly2, lx2 + toe + (face == 0 ? 4 : 0), ly2, p);

        // arms (behind the jacket edges)
        p.setColor(NAVY);
        p.setAlpha(a);
        p.setStrokeWidth(5.5f);
        float arm = -swing * 0.8f;
        float ax1 = -11 + (float) Math.sin(arm) * 28, ay1 = -74 + (float) Math.cos(arm) * 28;
        float ax2, ay2;
        if (wave >= 0) {
            ax2 = 11 + (float) Math.sin(wave) * 28;
            ay2 = -74 - (float) Math.cos(wave) * 28;
            ax1 = -14; ay1 = -46;
        } else {
            ax2 = 11 + (float) Math.sin(-arm) * 28;
            ay2 = -74 + (float) Math.cos(-arm) * 28;
        }
        c.drawLine(-11, -74, ax1, ay1, p);
        c.drawLine(11, -74, ax2, ay2, p);
        p.setColor(GOLD);
        p.setAlpha(a);
        p.setStrokeWidth(2f);
        c.drawPoint(lerp(-11, ax1, 0.85f), lerp(-74, ay1, 0.85f), p);   // cuff stripes
        c.drawPoint(lerp(11, ax2, 0.85f), lerp(-74, ay2, 0.85f), p);

        // jacket, shirt, tie, buttons, epaulettes
        p.setStyle(Paint.Style.FILL);
        p.setColor(NAVY);
        p.setAlpha(a);
        c.drawPath(jacket, p);
        p.setColor(SHIRT);
        p.setAlpha(a);
        c.drawPath(collar, p);
        p.setColor(t.accent);
        p.setAlpha(a);
        c.drawRect(-1.6f, -72, 1.6f, -56, p);
        p.setColor(GOLD);
        p.setAlpha(a);
        c.drawCircle(-5, -50, 1.4f, p);
        c.drawCircle(-5, -44, 1.4f, p);
        c.drawRect(-11, -77, -5, -75, p);
        c.drawRect(5, -77, 11, -75, p);

        // hands, neck, head
        p.setColor(t.text);
        p.setAlpha(a);
        c.drawCircle(ax1, ay1, 2.8f, p);
        c.drawCircle(ax2, ay2, 2.8f, p);
        c.drawRect(-2, -80, 2, -75, p);
        c.drawCircle(0, -88, 9, p);

        // pilot cap
        p.setColor(NAVY);
        p.setAlpha(a);
        r.set(-10.5f, -102, 10.5f, -95);
        c.drawRoundRect(r, 3, 3, p);
        p.setColor(SHOE);
        p.setAlpha(a);
        c.drawRect(-10.5f, -96, 10.5f, -94.5f, p);
        if (face == 0) c.drawRect(-12, -94.5f, 12, -93, p);
        else c.drawRect(face < 0 ? -15 : 0, -95, face < 0 ? 0 : 15, -93, p);
        p.setColor(GOLD);
        p.setAlpha(a);
        c.drawCircle(0, -98.5f, 2, p);
        c.restore();
    }
}
