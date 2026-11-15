package com.adruu.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Minimal ADRUU settings: a short list anyone can understand. */
public class SettingsActivity extends Activity {
    private Prefs p;
    private Theme t;
    private LinearLayout list;

    interface PinCallback { void done(String pin); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = new Prefs(this);
        render();
    }

    private void render() {
        t = Theme.current(p);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(UI.backdrop(t));
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = UI.dp(this, 48);
        page.setPadding(pad, UI.dp(this, 36), pad, pad);
        scroll.addView(page);

        TextView title = UI.text(this, "Settings", 28, t.text, true);
        title.setPadding(0, 0, 0, UI.dp(this, 24));
        page.addView(title);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list, new LinearLayout.LayoutParams(UI.dp(this, 560), ViewGroup.LayoutParams.WRAP_CONTENT));

        row("Wallpaper", Wallpaper.label(p.wallpaper()), () -> { p.wallpaper(Wallpaper.next(p.wallpaper())); render(); });
        row("Theme", Theme.byId(p.theme()).name, this::pickTheme);
        row("Festival themes", onOff(p.festival()), () -> { p.festival(!p.festival()); render(); });
        row("TV lock", onOff(p.lockOn()), this::toggleLock);
        if (p.hasPin()) row("Change PIN", "", this::changePin);
        row("Mode", Modes.label(p.mode()), () -> { p.mode(Modes.next(p.mode())); render(); });
        row("Guest mode", onOff(p.guest()), this::toggleGuest);
        row("Click sounds", onOff(p.clicks()), () -> { p.clicks(!p.clicks()); render(); });
        row("Startup animation", onOff(p.intro()), () -> { p.intro(!p.intro()); render(); });
        row("Subtitles in video player", onOff(p.subtitles()), () -> { p.subtitles(!p.subtitles()); render(); });
        row("CPU / RAM display", onOff(p.perfOverlay()), () -> { p.perfOverlay(!p.perfOverlay()); render(); });
        row("Weather city", p.city(), this::pickCity);
        row("Screen time today", "", this::showScreenTime);
        row("Phone remote", "", () -> new AlertDialog.Builder(this).setTitle("Phone remote")
                .setMessage("On your phone (same Wi-Fi), open:\n\n" + RemoteServer.phoneUrl(this)
                        + "\n\nKeep this link private: anyone with it can unlock the TV.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Make a new link", (d, w) -> {
                    p.newToken();
                    ControlService.rewriteControlFile(this);
                    toast("New link made; the old one stops working");
                })
                .show());

        row("Wallpaper photo credits", "", () -> {
            TextView tv = UI.text(this, Wallpaper.credits(this), 12, 0xFF222222, false);
            int pd = UI.dp(this, 16);
            tv.setPadding(pd, pd, pd, pd);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this).setTitle("Wallpaper credits").setView(sv).setPositiveButton("OK", null).show();
        });

        TextView ver = UI.text(this, "ADRUU " + BuildInfo.VERSION, 12, t.dim, false);
        ver.setPadding(0, UI.dp(this, 20), 0, 0);
        page.addView(ver);

        setContentView(scroll);
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private static String onOff(boolean b) { return b ? "On" : "Off"; }

    private void row(String label, String value, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setFocusable(true);
        r.setClickable(true);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(UI.button(t, UI.dp(this, 14)));
        int ph = UI.dp(this, 24), pv = UI.dp(this, 16);
        r.setPadding(ph, pv, ph, pv);
        TextView l = UI.text(this, label, 17, t.text, true);
        l.setTextColor(UI.buttonText(t));
        l.setDuplicateParentStateEnabled(true);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = UI.text(this, value, 16, t.dim, false);
        v.setTextColor(UI.buttonText(t));
        v.setDuplicateParentStateEnabled(true);
        r.addView(v);
        r.setOnClickListener(x -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UI.dp(this, 10);
        list.addView(r, lp);
    }

    private void pickTheme() {
        String[] names = new String[Theme.BASE.length];
        for (int i = 0; i < names.length; i++) names[i] = Theme.BASE[i].name;
        new AlertDialog.Builder(this).setTitle("Theme").setItems(names, (d, i) -> {
            p.theme(Theme.BASE[i].id);
            render();
        }).show();
    }

    private void pickCity() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(p.city());
        new AlertDialog.Builder(this).setTitle("Weather city").setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String c = e.getText().toString().trim();
                    if (!c.isEmpty()) p.city(c);
                    render();
                }).setNegativeButton("Cancel", null).show();
    }

    private void toggleLock() {
        if (p.lockOn()) {
            askPin("Enter your PIN to turn the lock off", pin -> {
                if (p.checkPin(pin)) {
                    p.lockOn(false);
                    MainActivity.sLocked = false;
                    render();
                } else {
                    toast("Wrong PIN");
                }
            });
        } else if (p.hasPin()) {
            p.lockOn(true);
            render();
        } else {
            newPin(() -> { p.lockOn(true); render(); });
        }
    }

    private void toggleGuest() {
        if (p.guest() && p.hasPin()) {
            askPin("Enter your PIN to leave guest mode", pin -> {
                if (p.checkPin(pin)) { p.guest(false); render(); } else toast("Wrong PIN");
            });
        } else {
            p.guest(!p.guest());
            render();
        }
    }

    private void showScreenTime() {
        StringBuilder s = new StringBuilder();
        long total = 0;
        java.util.List<Modes.Usage> list = Modes.today(this);
        for (Modes.Usage u : list) total += u.ms;
        s.append("Total: ").append(Modes.duration(total)).append("\n\n");
        for (int i = 0; i < list.size() && i < 10; i++) {
            s.append(list.get(i).label).append(" â€” ").append(Modes.duration(list.get(i).ms)).append("\n");
        }
        if (list.isEmpty()) s.append("No data yet (usage access may not be switched on).");
        new AlertDialog.Builder(this).setTitle("Screen time today").setMessage(s).setPositiveButton("OK", null).show();
    }

    private void changePin() {
        askPin("Enter your current PIN", pin -> {
            if (p.checkPin(pin)) newPin(this::render);
            else toast("Wrong PIN");
        });
    }

    private void newPin(Runnable after) {
        askPin("Choose a new 6-digit PIN", first -> {
            if (first.length() != LockView.LEN) { toast("The PIN needs 6 digits"); return; }
            askPin("Enter it again", second -> {
                if (!first.equals(second)) { toast("PINs didn't match. Try again."); return; }
                p.setPin(first);
                toast("PIN saved");
                after.run();
            });
        });
    }

    private void askPin(String title, PinCallback cb) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(LockView.LEN)});
        e.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this).setTitle(title).setView(e)
                .setPositiveButton("OK", (d, w) -> cb.done(e.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && p.clicks()) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_CENTER)
                Synth.click();
        }
        return super.dispatchKeyEvent(e);
    }
}
