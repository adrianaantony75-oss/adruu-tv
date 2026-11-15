package com.adruu.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/**
 * The "intruder" moment on the lock screen: when someone types a wrong PIN or tries to use the TV while
 * it's locked, a man points straight at them and says the owner's dialogue (default: "നീ പോ മോനേ ദിനേശാ").
 *
 * Customisable from the phone app: the text (Malayalam or English), an image instead of the drawn man,
 * and a voice clip (recorded on the phone or any audio file). Without a clip the TV speaks the text with
 * its own text-to-speech in Malayalam when that voice is installed.
 */
final class Intruder {
    private static final long SHOW_MS = 4800;
    private static TextToSpeech tts;
    private static boolean ttsReady, ttsMalayalam;
    private static long lastShown;

    private Intruder() {}

    static File imageFile(Context c) { return new File(c.getFilesDir(), "intruder_image"); }
    static File audioFile(Context c) { return new File(c.getFilesDir(), "intruder_audio"); }

    /** Starts the TV's text-to-speech early (it takes a moment) and checks for a Malayalam voice. */
    static void warmUp(Context c) {
        if (tts != null) return;
        final Context app = c.getApplicationContext();
        tts = new TextToSpeech(app, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int r = tts.setLanguage(new Locale("ml", "IN"));
            ttsMalayalam = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsMalayalam) tts.setLanguage(new Locale("en", "IN"));
            tts.setSpeechRate(0.92f);
            tts.setPitch(0.85f);
            ttsReady = true;
        });
    }

    static boolean hasMalayalamVoice() { return ttsMalayalam; }

    /** Shows the moment over {@code parent} (the home screen root). At most once every 6 seconds. */
    static void show(FrameLayout parent, Theme t, Prefs p) {
        long now = SystemClock.uptimeMillis();
        if (now - lastShown < 6000) return;
        lastShown = now;
        Context c = parent.getContext();
        String text = p.intruderText();

        FrameLayout box = new FrameLayout(c);
        box.setBackgroundColor(0xF0050608);
        box.setClickable(true);

        File img = imageFile(c);
        Bitmap bmp = null;
        if (img.exists()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            bmp = BitmapFactory.decodeFile(img.getPath(), o);
        }
        if (bmp != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(bmp);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, UI.dp(c, 360), Gravity.TOP);
            lp.topMargin = UI.dp(c, 40);
            box.addView(iv, lp);
        } else {
            box.addView(new Pointer(c, t), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        TextView say = new TextView(c);
        say.setText(text);
        say.setTypeface(isMalayalam(text) ? Fonts.malayalam(c) : Fonts.ui(c, true));
        say.setTextSize(56);
        say.setTextColor(0xFFFFFFFF);
        say.setGravity(Gravity.CENTER);
        say.setShadowLayer(UI.dp(c, 12), 0, 0, 0xFF000000);
        int pd = UI.dp(c, 30);
        say.setPadding(pd, UI.dp(c, 18), pd, UI.dp(c, 22));
        android.graphics.drawable.GradientDrawable bubble = UI.round(0xD0101218, UI.dp(c, 28));
        bubble.setStroke(UI.dp(c, 3), t.accent);
        say.setBackground(bubble);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = UI.dp(c, 44);
        box.addView(say, sp);

        parent.addView(box);
        box.setAlpha(0f);
        box.animate().alpha(1f).setDuration(220).start();
        say.setScaleX(0.6f);
        say.setScaleY(0.6f);
        say.animate().scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();

        speak(c, text);
        box.postDelayed(() -> box.animate().alpha(0f).setDuration(300).withEndAction(() -> parent.removeView(box)), SHOW_MS);
    }

    private static boolean isMalayalam(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x0D00 && ch <= 0x0D7F) return true;
        }
        return false;
    }

    private static void speak(Context c, String text) {
        File a = audioFile(c);
        if (a.exists()) {
            try {
                MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(a.getPath());
                mp.setOnCompletionListener(MediaPlayer::release);
                mp.setOnErrorListener((m, w, x) -> { m.release(); return true; });
                mp.prepare();
                mp.start();
                return;
            } catch (Exception e) {
                Health.record("health", "The lock-screen voice clip couldn't play (format not supported)", String.valueOf(e));
            }
        }
        if (ttsReady && (ttsMalayalam || !isMalayalam(text))) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "adruu-intruder");
        }
    }

    /**
     * An original illustrated man (white shirt, gold-bordered mundu, moustache) pointing straight at the
     * viewer: his hand and index finger come towards the camera, drawn large in the foreground.
     */
    private static final class Pointer extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Theme t;
        private final long start = SystemClock.uptimeMillis();
        private final RectF r = new RectF();
        private final Path path = new Path();

        Pointer(Context c, Theme theme) {
            super(c);
            t = theme;
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(1f, (SystemClock.uptimeMillis() - start) / 650f);
            float ease = 1 - (1 - k) * (1 - k) * (1 - k);
            float w = getWidth(), h = getHeight();
            float u = h / 100f;                     // drawing unit: 1% of the screen height
            float cx = w / 2f, top = h * 0.06f;

            // glow
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.accent);
            p.setAlpha(40);
            c.drawCircle(cx, top + 34 * u, 34 * u, p);
            p.setAlpha(255);

            // body: white shirt
            p.setColor(0xFFF2F2EE);
            path.reset();
            path.moveTo(cx - 17 * u, top + 42 * u);
            path.quadTo(cx, top + 37 * u, cx + 17 * u, top + 42 * u);
            path.lineTo(cx + 19 * u, top + 70 * u);
            path.lineTo(cx - 19 * u, top + 70 * u);
            path.close();
            c.drawPath(path, p);
            // collar + buttons
            p.setColor(0xFFD9D9D2);
            c.drawRect(cx - 0.5f * u, top + 43 * u, cx + 0.5f * u, top + 69 * u, p);
            // mundu with gold border
            p.setColor(0xFFF7F4EA);
            c.drawRect(cx - 19 * u, top + 70 * u, cx + 19 * u, top + 92 * u, p);
            p.setColor(0xFFD9A93A);
            c.drawRect(cx - 19 * u, top + 89 * u, cx + 19 * u, top + 92 * u, p);
            c.drawRect(cx + 3 * u, top + 70 * u, cx + 5 * u, top + 92 * u, p);

            // left arm on the hip
            p.setColor(0xFFF2F2EE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(6 * u);
            c.drawLine(cx - 17 * u, top + 45 * u, cx - 25 * u, top + 58 * u, p);
            c.drawLine(cx - 25 * u, top + 58 * u, cx - 18 * u, top + 68 * u, p);

            // neck + head
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFC68A5E);
            c.drawRect(cx - 3 * u, top + 33 * u, cx + 3 * u, top + 40 * u, p);
            r.set(cx - 10 * u, top + 12 * u, cx + 10 * u, top + 36 * u);
            c.drawOval(r, p);
            // hair
            p.setColor(0xFF1B1410);
            r.set(cx - 10.5f * u, top + 10 * u, cx + 10.5f * u, top + 22 * u);
            c.drawArc(r, 180, 180, true, p);
            // eyes (narrowed, stern) + eyebrows
            p.setStrokeWidth(1.2f * u);
            p.setStyle(Paint.Style.STROKE);
            c.drawLine(cx - 7 * u, top + 20.5f * u, cx - 2.5f * u, top + 22 * u, p);
            c.drawLine(cx + 7 * u, top + 20.5f * u, cx + 2.5f * u, top + 22 * u, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(cx - 4.5f * u, top + 24 * u, 1.1f * u, p);
            c.drawCircle(cx + 4.5f * u, top + 24 * u, 1.1f * u, p);
            // moustache
            path.reset();
            path.moveTo(cx - 6 * u, top + 30.5f * u);
            path.quadTo(cx, top + 27.5f * u, cx + 6 * u, top + 30.5f * u);
            path.quadTo(cx, top + 29.5f * u, cx - 6 * u, top + 30.5f * u);
            c.drawPath(path, p);

            // pointing arm: from the right shoulder towards the viewer, growing as it comes forward
            float sx = cx + 16 * u, sy = top + 45 * u;
            float hx = cx + (6 + 6 * ease) * u, hy = top + (52 + 10 * ease) * u;
            p.setColor(0xFFF2F2EE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth((6 + 6 * ease) * u);
            c.drawLine(sx, sy, hx, hy, p);
            // hand + index finger pointing out of the screen (foreshortened = big round fingertip)
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFC68A5E);
            float hand = (5 + 9 * ease) * u;
            c.drawCircle(hx, hy, hand, p);
            p.setColor(0xFFB57A50);
            c.drawCircle(hx - hand * 0.15f, hy + hand * 0.15f, hand * 0.55f, p);
            p.setColor(0xFFD9A07A);
            c.drawCircle(hx - hand * 0.2f, hy + hand * 0.1f, hand * 0.32f, p);   // fingertip facing you

            if (k < 1f) postInvalidateOnAnimation();
        }
    }
}
