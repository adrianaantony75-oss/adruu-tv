package com.adruu.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** ADRUU home screen: clock, weather, quick buttons, app tiles, startup animation and TV lock. */
public class MainActivity extends Activity {
    private static boolean sColdStart = true;
    private static boolean sLockInit;
    static boolean sLocked;
    private static WeakReference<MainActivity> sInstance = new WeakReference<>(null);

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private Theme theme;
    private FrameLayout root;
    private LinearLayout home;
    private GridView grid;
    private AppAdapter adapter;
    private TextView clock, date, weather, greet, modeBtn, mouseBtn;
    private LockView lock;
    private IntroView intro;
    private View highlighted;
    private boolean appsDirty = true;
    private long lastWeather;
    private android.widget.ImageView wallView;
    private View scrim;
    private String wallKind = "";
    private static final int COLS = 5;
    private LinearLayout header;
    private android.widget.HorizontalScrollView hourScroll;
    private LinearLayout hourRow;
    private int headerH;

    /** Page height = screen + header, so sliding the header away reveals more tiles. */
    private boolean measureHeader() {
        if (header == null || root == null) return false;
        int hh = header.getHeight(), rh = root.getHeight();
        if (hh <= 0 || rh <= 0) return false;
        headerH = hh;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) home.getLayoutParams();
        if (lp.height != rh + hh) {
            lp.height = rh + hh;
            home.setLayoutParams(lp);
        }
        if (collapsed) home.setTranslationY(-hh);
        return true;
    }

    /** Fills the weather strip from the saved forecast: one small card per hour. */
    private void showHours() {
        if (hourRow == null) return;
        hourRow.removeAllViews();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs.hourly());
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject h = a.getJSONObject(i);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setFocusable(true);
                cell.setBackground(UI.button(theme, UI.dp(this, 12)));
                int pv = UI.dp(this, 8), ph = UI.dp(this, 14);
                cell.setPadding(ph, pv, ph, pv);
                android.content.res.ColorStateList tc = UI.buttonText(theme);
                TextView t1 = UI.text(this, h.getString("h"), 12, theme.text, false);
                TextView t2 = UI.text(this, h.getInt("t") + "°", 18, theme.text, true);
                TextView t3 = UI.text(this, "Rain " + h.getInt("r") + "%", 11, theme.text, false);
                TextView t4 = UI.text(this, "Wind " + h.getInt("w"), 11, theme.text, false);
                for (TextView tv : new TextView[]{t1, t2, t3, t4}) {
                    tv.setTextColor(tc);
                    tv.setDuplicateParentStateEnabled(true);
                    tv.setGravity(Gravity.CENTER);
                    cell.addView(tv);
                }
                t2.setPadding(0, UI.dp(this, 3), 0, UI.dp(this, 3));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UI.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = UI.dp(this, 10);
                hourRow.addView(cell, lp);
            }
        } catch (Exception ignored) {
        }
        hourScroll.setVisibility(hourRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }
    private boolean collapsed;

    /**
     * Website-style scroll: going down past the first row slides the clock, weather and buttons up
     * out of view while the wallpaper drifts more slowly (parallax); going back up brings them back.
     */
    private void collapse(boolean c) {
        if (c == collapsed || headerH == 0) return;
        collapsed = c;
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.7f);
        home.animate().translationY(c ? -headerH : 0).setDuration(420).setInterpolator(ease).start();
        header.animate().alpha(c ? 0f : 1f).setDuration(320).start();
        wallView.animate().translationY(c ? -headerH * 0.22f : 0).setDuration(520).setInterpolator(ease).start();
        scrim.animate().alpha(c ? 0.85f : 1f).setDuration(420).start();
    }

    private final BroadcastReceiver system = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = String.valueOf(i.getAction());
            if (a.equals(Intent.ACTION_SCREEN_OFF)) {
                if (prefs.lockOn()) sLocked = true;
            } else if (a.equals(Intent.ACTION_SCREEN_ON)) {
                loadWallpaper(true);   // a fresh photo every time the TV wakes
                Notify.send(c, "TV turned on", "Someone switched the TV on", "tv");
                if (prefs.intro() && !isLockedNow(prefs)) {
                    // Switching on with the remote is a wake from standby, not a restart: replay the intro.
                    wakeIntro = true;
                    startActivity(new Intent(c, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                }
                if (isLockedNow(prefs)) {
                    // Bring the lock to the front even if an app was open (needs "display over other apps").
                    startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } else {
                tick();
            }
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            appsDirty = true;
            loadApps();
        }
    };

    /** Called when the phone page or Claude changed something. */
    static void refreshFromOutside() {
        MainActivity a = sInstance.get();
        if (a == null || a.isDestroyed() || a.isFinishing()) return;
        a.appsDirty = true;
        if (!Theme.current(a.prefs).id.equals(a.theme.id)) {
            a.highlighted = null;
            a.build();
        }
        a.refreshLock();
        a.refreshButtons();
        a.loadApps();
        a.loadWallpaper(false);
    }

    /** Shows a random plane/car/bike photo behind the home screen (or the theme colours if off). */
    private void loadWallpaper(boolean force) {
        String kind = prefs.wallpaper();
        if (!force && kind.equals(wallKind)) return;
        wallKind = kind;
        Wallpaper.loadRandom(this, kind, h, (bmp, name) -> {
            if (isDestroyed() || wallView == null) return;
            if (bmp == null) {
                wallView.setImageDrawable(null);
                scrim.setVisibility(View.GONE);
                return;
            }
            wallView.setAlpha(0f);
            wallView.setImageBitmap(bmp);
            wallView.animate().alpha(1f).setDuration(450).start();
            scrim.setVisibility(View.VISIBLE);
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Health.install(this);
        Intruder.warmUp(this);
        if (sColdStart) Notify.send(this, "TV turned on", "The TV was switched on", "tv");
        sInstance = new WeakReference<>(this);
        prefs = new Prefs(this);
        if (!sLockInit) {
            sLocked = prefs.lockOn();
            sLockInit = true;
        }
        build();
        ControlService.start(this);

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(system, f);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);

        if (sColdStart && prefs.intro()) showIntro();
        sColdStart = false;
    }

    private void build() {
        theme = Theme.current(prefs);
        root = new FrameLayout(this);
        root.setBackground(UI.backdrop(theme));

        // Photo wallpaper + a shade so text stays readable on any picture.
        wallView = new android.widget.ImageView(this);
        wallView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        // Slightly enlarged so the parallax drift never shows an edge.
        wallView.setScaleX(1.12f);
        wallView.setScaleY(1.12f);
        root.addView(wallView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim = new View(this);
        int b = theme.bg & 0x00FFFFFF;
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD8000000 | b, 0x90000000 | b, 0xA8000000 | b, 0xE8000000 | b}));
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wallKind = "";

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        int side = UI.dp(this, 56);
        home.setPadding(side, UI.dp(this, 32), side, 0);
        home.setClipChildren(false);
        home.setClipToPadding(false);

        // Top bar: logo + greeting on the left, clock + weather on the right.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView logo = UI.text(this, "ADRUU", 26, theme.accent, true);
        logo.setLetterSpacing(0.3f);
        left.addView(logo);
        greet = UI.text(this, "", 16, theme.dim, false);
        greet.setPadding(0, UI.dp(this, 8), 0, 0);
        left.addView(greet);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clock = UI.text(this, "", 44, theme.text, true);
        right.addView(clock);
        date = UI.text(this, "", 14, theme.dim, false);
        date.setPadding(0, UI.dp(this, 6), 0, 0);
        right.addView(date);
        weather = UI.text(this, prefs.lastWeather(), 14, theme.dim, false);
        weather.setPadding(0, UI.dp(this, 4), 0, 0);
        right.addView(weather);
        top.addView(right);
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(top);

        // Next-12-hours weather strip: scroll it sideways with the remote.
        hourScroll = new android.widget.HorizontalScrollView(this);
        hourScroll.setHorizontalScrollBarEnabled(false);
        hourScroll.setClipToPadding(false);
        hourRow = new LinearLayout(this);
        hourScroll.addView(hourRow);
        LinearLayout.LayoutParams hsp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsp.topMargin = UI.dp(this, 14);
        header.addView(hourScroll, hsp);
        showHours();

        // Quick buttons
        LinearLayout pills = new LinearLayout(this);
        pills.setPadding(0, UI.dp(this, 18), 0, 0);
        modeBtn = addPill(pills, "", v -> {
            prefs.mode(Modes.next(prefs.mode()));
            refreshButtons();
        });
        mouseBtn = addPill(pills, "Mouse", v -> {
            if (!MouseService.isReady()) {
                Toast.makeText(this, "Mouse mode isn't switched on in Accessibility yet", Toast.LENGTH_LONG).show();
                return;
            }
            MouseService.toggle();
            refreshButtons();
        });
        addPill(pills, "Lock now", v -> {
            if (!prefs.lockOn()) {
                Toast.makeText(this, "Set a PIN in ADRUU settings first", Toast.LENGTH_LONG).show();
                return;
            }
            sLocked = true;
            refreshLock();
        });
        addPill(pills, "Phone remote", v -> showPhoneLink());
        header.addView(pills);
        home.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(COLS);
        grid.setHorizontalSpacing(UI.dp(this, 26));
        grid.setVerticalSpacing(UI.dp(this, 18));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, UI.dp(this, 22), 0, UI.dp(this, 40));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter(this, theme);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.getItem(pos)));
        // Hold OK on a tile to rearrange it.
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (adapter.getItem(pos).pkg.startsWith("adruu:")) return false;
            startMove(pos);
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (grid.hasFocus()) {
                    highlight(v);
                    collapse(pos >= COLS);   // past the first row: scroll the header away
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) { highlight(null); }
        });
        grid.setOnFocusChangeListener((v, has) -> {
            highlight(has ? grid.getSelectedView() : null);
            if (!has) collapse(false);            // moved up to the buttons: bring the header back
            else collapse(grid.getSelectedItemPosition() >= COLS);
        });
        // Tiles glide in row by row when the home screen appears.
        android.view.animation.AnimationSet in = new android.view.animation.AnimationSet(true);
        in.addAnimation(new android.view.animation.AlphaAnimation(0f, 1f));
        in.addAnimation(new android.view.animation.TranslateAnimation(0, 0, UI.dp(this, 36), 0));
        in.setDuration(380);
        in.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f));
        android.view.animation.GridLayoutAnimationController lac = new android.view.animation.GridLayoutAnimationController(in, 0.06f, 0.18f);
        lac.setDirection(android.view.animation.GridLayoutAnimationController.DIRECTION_TOP_TO_BOTTOM | android.view.animation.GridLayoutAnimationController.DIRECTION_LEFT_TO_RIGHT);
        grid.setLayoutAnimation(lac);
        home.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(home);
        collapsed = false;
        // Make the page taller than the screen by the header's height, so sliding it up
        // (a GPU-only translation, cheap on this TV) reveals more tiles instead of empty space.
        home.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (measureHeader()) home.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            }
        });

        lock = new LockView(this, prefs, theme, this::unlock);
        root.addView(lock);
        if (intro != null) {
            if (intro.getParent() != null) ((ViewGroup) intro.getParent()).removeView(intro);
            root.addView(intro);
        }
        setContentView(root);
        appsDirty = true;
        loadApps();
        tick();
        refreshButtons();
        refreshLock();
        loadWallpaper(true);
        if (lock.getVisibility() != View.VISIBLE) grid.requestFocus();
    }

    private TextView addPill(LinearLayout row, String text, View.OnClickListener l) {
        TextView v = UI.pill(this, theme, text, 14);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = UI.dp(this, 12);
        row.addView(v, lp);
        return v;
    }

    private void refreshButtons() {
        if (modeBtn == null) return;
        String m = prefs.mode();
        modeBtn.setText("Mode: " + Modes.label(m)
                + (m.equals("auto") ? " (" + Modes.label(Modes.effective(this, m)) + ")" : ""));
        mouseBtn.setText(MouseService.isActive() ? "Mouse: on" : "Mouse");
        Modes.apply(this, Modes.effective(this, m));
    }

    private void showPhoneLink() {
        new AlertDialog.Builder(this)
                .setTitle("Phone remote")
                .setMessage("On your phone (same Wi-Fi), open:\n\n" + RemoteServer.phoneUrl(this)
                        + "\n\nAdd it to your home screen for one-tap access. Keep this link private: "
                        + "anyone with it can unlock the TV.")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Focused tile grows slightly and gets an accent outline. */
    private void highlight(View v) {
        if (highlighted == v) return;
        if (highlighted != null) {
            highlighted.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            AppAdapter.Holder old = (AppAdapter.Holder) highlighted.getTag();
            old.card.setForeground(null);
            old.name.setTextColor(theme.text);
        }
        highlighted = v;
        if (v != null) {
            v.animate().scaleX(1.09f).scaleY(1.09f).setDuration(140).start();
            AppAdapter.Holder hd = (AppAdapter.Holder) v.getTag();
            hd.card.setForeground(UI.ring(theme.accent, AppAdapter.radius(this), UI.dp(this, 3)));
            hd.name.setTextColor(theme.text);
        }
    }

    private void launch(AppAdapter.App a) {
        try {
            startActivity(a.launch);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadApps() {
        if (!appsDirty) return;
        appsDirty = false;
        final AppAdapter target = adapter;
        new Thread(() -> {
            List<AppAdapter.App> list = AppAdapter.load(getApplicationContext(), true);
            h.post(() -> target.setApps(list));
        }, "adruu-apps").start();
    }

    private void tick() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        if (theme.greeting != null) {
            greet.setText(theme.greeting);
        } else {
            int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            greet.setText(hr < 5 ? "Good night" : hr < 12 ? "Good morning" : hr < 17 ? "Good afternoon" : "Good evening");
        }
        if (prefs.guest()) greet.append("  ·  Guest mode");
        // Refresh every 30 min, or straight away after the city changed (the saved weather is cleared then).
        if (prefs.lastWeather().isEmpty() && System.currentTimeMillis() - lastWeather > 15_000L) lastWeather = 0;
        if (System.currentTimeMillis() - lastWeather > 30 * 60_000L) {
            lastWeather = System.currentTimeMillis();
            Weather.fetch(prefs, h, text -> {
                weather.setText(text);
                showHours();
                headerH = 0;   // header got taller/shorter: re-measure for the scroll animation
                home.requestLayout();
                home.post(this::measureHeader);
            });
        }
    }

    /** Locked from the phone (works without a PIN). Kept in memory only: a TV restart unlocks, as a safety net. */
    static volatile boolean sPhoneLocked;

    static boolean isLockedNow(Prefs p) {
        return (sLocked && p.lockOn()) || sPhoneLocked;
    }

    private void refreshLock() {
        boolean show = isLockedNow(prefs);
        lock.setVisibility(show ? View.VISIBLE : View.GONE);
        home.setDescendantFocusability(show ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (show) {
            lock.phoneMode(sPhoneLocked && !prefs.hasPin());
            lock.reset();
        } else if (!home.hasFocus()) grid.requestFocus();
    }

    private void unlock() {
        sLocked = false;
        sPhoneLocked = false;
        refreshLock();
    }

    // ---- rearrange tiles: hold OK, move with arrows, OK (or Back) to drop ----
    private int moving = -1;
    private Toast moveHint;

    private void startMove(int pos) {
        moving = pos;
        moveHint = Toast.makeText(this, "Moving " + adapter.getItem(pos).label + ": use the arrows, then press OK", Toast.LENGTH_LONG);
        moveHint.show();
        markMoving();
    }

    private void markMoving() {
        grid.post(() -> {
            View v = grid.getSelectedView();
            if (v == null) return;
            AppAdapter.Holder hd = (AppAdapter.Holder) v.getTag();
            hd.card.setForeground(UI.ring(0xFFFFFFFF, AppAdapter.radius(this), UI.dp(this, 5)));
            v.setScaleX(1.14f);
            v.setScaleY(1.14f);
        });
    }

    private boolean handleMoveKey(int k) {
        int to;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = moving - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = moving + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: to = moving - COLS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: to = moving + COLS; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BACK:
                prefs.favourites(adapter.order());
                moving = -1;
                if (moveHint != null) moveHint.cancel();
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                highlighted = null;
                adapter.notifyDataSetChanged();
                grid.post(() -> highlight(grid.getSelectedView()));
                return true;
            default:
                return true;
        }
        moving = adapter.move(moving, to);
        highlighted = null;
        grid.setSelection(moving);
        markMoving();
        return true;
    }

    static MainActivity current() {
        MainActivity a = sInstance.get();
        return a == null || a.isDestroyed() ? null : a;
    }

    /** Called when a setting that changes the whole layout (tile style) was changed from the phone. */
    static void rebuildFromOutside() {
        MainActivity a = sInstance.get();
        if (a == null || a.isDestroyed()) return;
        a.highlighted = null;
        a.build();
    }

    /** Called after a wallpaper upload or delete from the phone. */
    static void reloadWallpaper() {
        refreshFromOutside();
        MainActivity a = sInstance.get();
        if (a != null && !a.isDestroyed()) a.loadWallpaper(true);
    }

    private View introVideo;

    /** The family's own startup video (uploaded from the phone app) instead of the plane intro. */
    private void showVideoIntro(java.io.File f) {
        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        android.widget.VideoView v = new android.widget.VideoView(this);
        box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(box);
        introVideo = box;
        Runnable end = this::endVideoIntro;
        v.setOnCompletionListener(mp -> end.run());
        v.setOnErrorListener((mp, what, extra) -> {
            Health.record("health", "The startup video couldn't play (format not supported by this TV)", "what=" + what + " extra=" + extra);
            end.run();
            return true;
        });
        v.setVideoPath(f.getPath());
        v.start();
        h.postDelayed(end, 60_000);   // never longer than a minute
    }

    private void endVideoIntro() {
        if (introVideo == null) return;
        final View v = introVideo;
        introVideo = null;
        v.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        });
        refreshLock();
    }

    /**
     * The soundbar wakes a few seconds after the TV (HDMI-CEC). Waits until the HDMI ARC audio output
     * exists (max 6 s) so the intro sound isn't lost, then runs {@code go}.
     */
    private void whenAudioReady(Runnable go) {
        final android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        final long t0 = android.os.SystemClock.uptimeMillis();
        h.post(new Runnable() {
            @Override public void run() {
                boolean arc = false;
                for (android.media.AudioDeviceInfo d : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS))
                    if (d.getType() == android.media.AudioDeviceInfo.TYPE_HDMI_ARC) arc = true;
                long waited = android.os.SystemClock.uptimeMillis() - t0;
                if ((arc && waited >= 900) || waited >= 6000) go.run();
                else h.postDelayed(this, 250);
            }
        });
    }

    /** Set when the TV wakes from standby: play the startup animation again, like switching on. */
    private boolean wakeIntro;

    private void showIntro() {
        if (intro != null || introVideo != null) return;
        java.io.File custom = Media.introFile(this);
        if (custom.exists() && custom.length() > 0) {
            FrameLayout cover = new FrameLayout(this);
            cover.setBackgroundColor(0xFF000000);
            root.addView(cover);
            introVideo = cover;
            whenAudioReady(() -> {
                if (introVideo != cover) return;   // skipped meanwhile
                root.removeView(cover);
                introVideo = null;
                showVideoIntro(custom);
            });
            return;
        }
        intro = new IntroView(this, theme, () -> {
            final IntroView v = intro;
            intro = null;
            if (v != null) v.animate().alpha(0f).setDuration(250).withEndAction(() -> ((ViewGroup) v.getParent()).removeView(v));
            refreshLock();
        });
        root.addView(intro);
        final IntroView v = intro;
        whenAudioReady(() -> {
            if (intro != v) return;   // skipped meanwhile
            v.begin();
            Synth.intro();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Theme.current(prefs).id.equals(theme.id)) {
            highlighted = null;
            build();
        }
        if (!prefs.lockOn()) sLocked = false;
        appsDirty = true;
        refreshLock();
        refreshButtons();
        loadApps();
        loadWallpaper(false);
        tick();
        if (wakeIntro) {
            wakeIntro = false;
            showIntro();
        }
        if (intruderPending) {
            intruderPending = false;
            h.postDelayed(() -> intruder("Someone tried to open an app while the TV is locked"), 300);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first tile.
        if (intro == null && !isLockedNow(prefs) && grid != null) {
            grid.setSelection(0);
            grid.requestFocus();
        }
        refreshLock();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (intro != null) {
                intro.skip();
                return true;
            }
            if (introVideo != null) {
                endVideoIntro();
                return true;
            }
            boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER;
            if (nav && prefs.clicks() && e.getRepeatCount() % 3 == 0) Synth.click();
            if (moving >= 0 && handleMoveKey(k)) return true;
            if (isLockedNow(prefs)) {
                if (!prefs.hasPin() && k != KeyEvent.KEYCODE_HOME) {   // phone lock: TV keys do nothing...
                    intruder("Someone pressed the remote while the TV is locked");   // ...except call out the intruder
                    return true;
                }
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
                    lock.digit(k - KeyEvent.KEYCODE_0);
                    return true;
                }
                if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DEL) {
                    lock.back();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back does nothing.
    }

    /** The remote's Search key: Google TV's home used to handle it, so hand it to Google Assistant. */
    @Override
    public boolean onSearchRequested() {
        if (isLockedNow(prefs)) return true;
        try {
            startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { startActivity(new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
        return true;
    }

    /** Wallpaper slideshow: a new photo every N minutes while the home screen is showing. */
    private final Runnable slideshow = new Runnable() {
        @Override public void run() {
            int m = prefs.slideshow();
            if (m <= 0) return;
            if (moving < 0 && !isLockedNow(prefs)) loadWallpaper(true);
            h.postDelayed(this, m * 60_000L);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(slideshow);
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        h.removeCallbacks(slideshow);
        int m = prefs.slideshow();
        if (m > 0) h.postDelayed(slideshow, m * 60_000L);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Locked from the phone but something else got opened (e.g. by voice): bring the lock straight back.
        if (isLockedNow(prefs)) h.postDelayed(() -> {
            if (!isLockedNow(prefs)) return;
            intruderPending = true;
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }, 400);
    }

    private boolean intruderPending;

    /** Someone is trying to use the locked TV: the pointing man + dialogue on screen, and an alert to the phone. */
    void intruder(String what) {
        Intruder.show(root, theme, prefs);
        prefs.addEvent(what);
        Notify.send(this, "Someone is trying to use the TV", what, "warning");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(system);
        unregisterReceiver(packages);
    }
}
