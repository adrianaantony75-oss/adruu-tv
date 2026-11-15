package com.adruu.remote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ADRUU Remote: finds the ADRUU TV on the home Wi-Fi, pairs with the 4-digit code the TV shows,
 * then opens the TV's full control panel. Links shared to this app open on the TV.
 */
public class MainActivity extends Activity {
    private static final int PORT = 8686, PICK_FILE = 7;
    // Same design tokens as the control panel (phone.html on the TV).
    private static final int BG = 0xFF08090E, CARD = 0xFF11131B, CARD2 = 0xFF181B26, TEXT = 0xFFF4F6FB, DIM = 0xFF9AA3B8,
            ACCENT = 0xFF6C8CFF, ACCENT2 = 0xFF9B6CFF, LINE = 0xFF262B3B;
    private Typeface font, fontBold;

    private Typeface font(boolean bold) {
        if (font == null) {
            try {
                font = new Typeface.Builder(getAssets(), "font_manrope.ttf").setFontVariationSettings("'wght' 500").build();
                fontBold = new Typeface.Builder(getAssets(), "font_manrope.ttf").setFontVariationSettings("'wght' 750").build();
            } catch (Exception e) {
                font = Typeface.DEFAULT;
                fontBold = Typeface.DEFAULT_BOLD;
            }
        }
        return bold ? fontBold : font;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private WebView web;
    private LinearLayout page;
    private ValueCallback<Uri[]> fileCb;
    private String sharedLink;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        sp = getSharedPreferences("remote", MODE_PRIVATE);
        if (quickAction(getIntent())) return;
        sharedLink = linkFrom(getIntent());
        start();
    }

    /** Home-screen shortcuts: lock / unlock / TV off in one tap, without opening the panel. */
    private boolean quickAction(Intent i) {
        String a = i == null ? null : i.getAction();
        final String path = "com.adruu.remote.LOCK".equals(a) ? "/api/lock"
                : "com.adruu.remote.UNLOCK".equals(a) ? "/api/unlock"
                : "com.adruu.remote.OFF".equals(a) ? "/api/power?state=off" : null;
        if (path == null) return false;
        final String label = path.contains("unlock") ? "unlocked" : path.contains("lock") ? "locked" : "turned off";
        String token = sp.getString("token", null);
        if (token == null) { toast("Open ADRUU Remote once to pair with the TV"); finish(); return true; }
        new Thread(() -> {
            boolean ok = false;
            for (String ip : new String[]{sp.getString("ip", null), sp.getString("away", null)}) {
                if (ip == null || ip.isEmpty()) continue;
                String r = request("GET", "http://" + ip + ":" + PORT + path + (path.contains("?") ? "&" : "?") + "t=" + token, 4000);
                if (r != null && r.contains("\"ok\":true")) { ok = true; break; }
            }
            final boolean done = ok;
            main.post(() -> { toast(done ? "TV " + label : "Couldn't reach the TV"); finish(); });
        }).start();
        return true;
    }

    /** When the TV has Tailscale, remember its 100.x address so the app also works away from home. */
    private void learnAwayAddress(String hello) {
        try {
            org.json.JSONArray a = new JSONObject(hello).optJSONArray("addresses");
            if (a == null) return;
            for (int k = 0; k < a.length(); k++) {
                String ip = a.getString(k);
                String[] p = ip.split("\\.");
                // Tailscale uses the 100.64.0.0/10 range
                if (p.length == 4 && p[0].equals("100") && Integer.parseInt(p[1]) >= 64 && Integer.parseInt(p[1]) <= 127) {
                    if (!ip.equals(sp.getString("away", ""))) sp.edit().putString("away", ip).apply();
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        sharedLink = linkFrom(i);
        if (sharedLink != null && web != null && sp.contains("ip")) sendLink();
    }

    private static String linkFrom(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return null;
        String s = i.getStringExtra(Intent.EXTRA_TEXT);
        if (s == null) return null;
        int at = s.indexOf("http");
        return at >= 0 ? s.substring(at).split("\\s")[0] : null;
    }

    /**
     * Saved TV that still answers -> control panel; otherwise search again.
     * Tries the home Wi-Fi address first, then the "away" address (Tailscale), so the same app
     * works at home and from anywhere with internet.
     */
    private void start() {
        String ip = sp.getString("ip", null), away = sp.getString("away", null), token = sp.getString("token", null);
        if (ip == null || token == null) { search(); return; }
        status("Connecting to your TV…", null);
        new Thread(() -> {
            String hi = hello(ip, 2500);
            if (hi != null) learnAwayAddress(hi);
            String use = hi != null ? ip : (away != null && !away.isEmpty() && hello(away, 4000) != null ? away : null);
            main.post(() -> { if (use != null) openPanel(use, token); else lost(ip); });
        }).start();
    }

    // ---------------------------------------------------------------- bridge for the control page

    /** Called by the TV's control page (window.ADRUU in JavaScript). */
    public class Bridge {
        @android.webkit.JavascriptInterface public String appVersion() { return BuildConfigLite.VERSION_NAME; }
        @android.webkit.JavascriptInterface public int appVersionCode() { return BuildConfigLite.VERSION_CODE; }
        @android.webkit.JavascriptInterface public String savedName() { return sp.getString("name", ""); }
        @android.webkit.JavascriptInterface public void saveName(String n) { sp.edit().putString("name", n).apply(); }
        @android.webkit.JavascriptInterface public void voice(String lang) { main.post(() -> startVoice(lang)); }
        @android.webkit.JavascriptInterface public void connection() { main.post(MainActivity.this::connectionSettings); }
        @android.webkit.JavascriptInterface public void update(String url) {
            main.post(() -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
        }
        /** Opens an app's Play Store page on this phone (Tailscale, ntfy…). */
        @android.webkit.JavascriptInterface public void openStore(String pkg) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
                } catch (Exception e) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
                }
            });
        }
        /** Opens a link in the app that handles it (e.g. ntfy:// subscription links). */
        @android.webkit.JavascriptInterface public void openUrl(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast("Install the app for this link first");
                }
            });
        }
        @android.webkit.JavascriptInterface public String awayAddress() { return sp.getString("away", ""); }
    }

    private static final int VOICE = 8;

    /** Speech-to-text with the phone's own recogniser (Malayalam "ml-IN" or English "en-IN"). */
    private void startVoice(String lang) {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, lang.startsWith("ml") ? "പ്രശ്നം പറയൂ" : "Say what went wrong");
        try {
            startActivityForResult(i, VOICE);
        } catch (Exception e) {
            toast("Voice typing isn't available on this phone. Use the keyboard's mic.");
        }
    }

    /** Home Wi-Fi address and "away" address (the TV's Tailscale address) for control from anywhere. */
    private void connectionSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(20);
        box.setPadding(pd, dp(8), pd, 0);
        EditText home = new EditText(this), away = new EditText(this);
        home.setHint("Home Wi-Fi address of the TV");
        home.setText(sp.getString("ip", ""));
        away.setHint("Away address (TV's Tailscale address, 100.x.x.x)");
        away.setText(sp.getString("away", ""));
        box.addView(home);
        box.addView(away);
        TextView help = new TextView(this);
        help.setText("To control the TV from anywhere: install Tailscale on the TV and on this phone, sign in to the same account on both, and type the TV's Tailscale address here.");
        help.setPadding(0, dp(8), 0, 0);
        box.addView(help);
        box.addView(button("Get Tailscale on this phone", true, v -> new Bridge().openStore("com.tailscale.ipn")));
        new android.app.AlertDialog.Builder(this).setTitle("Connection").setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    sp.edit().putString("ip", home.getText().toString().trim()).putString("away", away.getText().toString().trim()).apply();
                    start();
                })
                .setNeutralButton("Pair again", (d, w) -> { sp.edit().remove("token").apply(); search(); })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------------------------------------------------------- screens

    private void openPanel(String ip, String token) {
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "ADRUU");   // only the TV's own page is ever loaded here
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest req) {
                // Keep the bridge to the TV page only: anything else opens in the browser.
                String host = req.getUrl().getHost();
                if (host != null && (host.equals(sp.getString("ip", "")) || host.equals(sp.getString("away", "")))) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl()));
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                String[] types = params.getAcceptTypes();
                String type = types != null && types.length > 0 && !types[0].isEmpty() ? types[0] : "*/*";
                if (params.isCaptureEnabled() && type.startsWith("audio")) {
                    // "Record" on the lock-screen voice: the phone's own sound recorder.
                    try {
                        startActivityForResult(new Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION), PICK_FILE);
                        return true;
                    } catch (Exception noRecorder) {
                        toast("No voice recorder app found: choose a recording instead");
                    }
                }
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT).setType(type.contains("apk") ? "*/*" : type)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                startActivityForResult(Intent.createChooser(pick, "Choose a file"), PICK_FILE);
                return true;
            }
        });
        web.loadUrl("http://" + ip + ":" + PORT + "/?t=" + token);
        // Android 15 draws apps edge to edge: keep the panel clear of the status and navigation bars.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(web);
        setContentView(frame);
        if (sharedLink != null) sendLink();
    }

    private void sendLink() {
        final String link = sharedLink, ip = sp.getString("ip", ""), token = sp.getString("token", "");
        sharedLink = null;
        new Thread(() -> {
            boolean ok = request("GET", "http://" + ip + ":" + PORT + "/api/open?url=" + enc(link) + "&t=" + token) != null;
            main.post(() -> toast(ok ? "Opening on the TV" : "Couldn't send the link"));
        }).start();
    }

    private void search() {
        status("Looking for your ADRUU TV on this Wi-Fi…", null);
        new Thread(() -> {
            List<String> found = scan();
            main.post(() -> showFound(found));
        }).start();
    }

    private void showFound(List<String> tvs) {
        LinearLayout box = base("Find your TV");
        if (tvs.isEmpty()) {
            box.addView(text("No ADRUU TV found. Check the phone is on the same Wi-Fi as the TV and the TV is on.", 15, DIM, false));
        } else {
            box.addView(text("Tap your TV to pair:", 15, DIM, false));
            for (String ip : tvs) box.addView(button("TV at " + ip, true, v -> pair(ip)));
        }
        box.addView(button("Search again", false, v -> search()));
        EditText manual = new EditText(this);
        manual.setHint("Or type the TV address, e.g. 192.168.10.8");
        manual.setHintTextColor(DIM);
        manual.setTextColor(TEXT);
        manual.setInputType(InputType.TYPE_CLASS_PHONE);
        box.addView(manual);
        box.addView(button("Connect to this address", false, v -> {
            String ip = manual.getText().toString().trim();
            if (!ip.isEmpty()) pair(ip);
        }));
    }

    private void pair(String ip) {
        status("Asking the TV for a code…", null);
        new Thread(() -> {
            String r = request("POST", "http://" + ip + ":" + PORT + "/api/pair/start");
            main.post(() -> {
                if (r == null) { toast("The TV didn't answer"); search(); return; }
                LinearLayout box = base("Enter the code");
                box.addView(text("A 4-digit code is now on the TV screen. Type it here:", 15, DIM, false));
                EditText code = new EditText(this);
                code.setInputType(InputType.TYPE_CLASS_NUMBER);
                code.setTextColor(TEXT);
                code.setTextSize(32);
                code.setGravity(Gravity.CENTER);
                code.setLetterSpacing(0.3f);
                box.addView(code);
                box.addView(button("Pair", true, v -> finishPair(ip, code.getText().toString().trim())));
                box.addView(button("Back", false, v -> search()));
                code.requestFocus();
            });
        }).start();
    }

    private void finishPair(String ip, String code) {
        new Thread(() -> {
            String r = request("POST", "http://" + ip + ":" + PORT + "/api/pair?code=" + enc(code));
            String token = null;
            try { if (r != null) token = new JSONObject(r).optString("token", null); } catch (Exception ignored) { }
            final String t = token;
            main.post(() -> {
                if (t == null) { toast("Wrong or expired code. Try again."); pair(ip); return; }
                sp.edit().putString("ip", ip).putString("token", t).apply();
                new Thread(() -> { String hi = hello(ip, 3000); if (hi != null) learnAwayAddress(hi); }).start();
                toast("Paired with your TV");
                openPanel(ip, t);
            });
        }).start();
    }

    private void lost(String ip) {
        LinearLayout box = base("Can't reach the TV");
        box.addView(text("The TV at " + ip + " isn't answering. It may be off, on standby, or on a different Wi-Fi.", 15, DIM, false));
        box.addView(button("Try again", true, v -> start()));
        box.addView(button("Connection (home / away)", false, v -> connectionSettings()));
        box.addView(button("Find the TV again", false, v -> search()));
        box.addView(button("Forget this TV", false, v -> { sp.edit().clear().apply(); search(); }));
    }

    private void status(String msg, String sub) {
        LinearLayout box = base(null);
        box.addView(text(msg, 18, TEXT, false));
        if (sub != null) box.addView(text(sub, 14, DIM, false));
    }

    // ---------------------------------------------------------------- network

    /** Checks every address on this phone's Wi-Fi (/24) for an ADRUU TV, 48 at a time. */
    private List<String> scan() {
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        String mine = myIp();
        if (mine == null) return found;
        String prefix = mine.substring(0, mine.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            if (ip.equals(mine)) continue;
            pool.execute(() -> { if (hello(ip, 700) != null) found.add(ip); });
        }
        pool.shutdown();
        try { pool.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return new ArrayList<>(found);
    }

    private static String myIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static String hello(String ip, int timeout) {
        String r = request("GET", "http://" + ip + ":" + PORT + "/api/hello", timeout);
        return r != null && r.contains("\"ADRUU\"") ? r : null;
    }

    private static String request(String method, String url) { return request(method, url, 5000); }

    private static String request(String method, String url, int timeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout + 3000);
            if (method.equals("POST")) { c.setDoOutput(true); c.getOutputStream().close(); }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                return o.toString("UTF-8");
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- small UI kit

    private LinearLayout base(String title) {
        web = null;
        ScrollView sv = new ScrollView(this);
        // Soft violet/blue glow behind the screen, like the control panel's hero card.
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1A1636, BG, BG, 0xFF0F1830});
        sv.setBackground(glow);
        sv.setFillViewport(true);
        sv.setFitsSystemWindows(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pd = dp(24);
        page.setPadding(pd, dp(36), pd, pd);
        TextView logo = text("ADRUU", 24, ACCENT, true);
        logo.setLetterSpacing(0.32f);
        logo.getPaint().setShader(new android.graphics.LinearGradient(0, 0, dp(140), 0, ACCENT, ACCENT2, android.graphics.Shader.TileMode.CLAMP));
        page.addView(logo);
        page.addView(text("Remote for your TV", 14, DIM, false));
        if (title != null) {
            TextView t = text(title, 30, TEXT, true);
            t.setLetterSpacing(-0.02f);
            t.setPadding(0, dp(48), 0, dp(6));
            page.addView(t);
        } else {
            page.addView(new View(this), new LinearLayout.LayoutParams(1, dp(80)));
        }
        sv.addView(page);
        setContentView(sv);
        return page;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(font(bold));
        v.setLineSpacing(0, 1.15f);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        b.setTypeface(font(true));
        b.setStateListAnimator(null);
        GradientDrawable g = primary
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT2})
                : new GradientDrawable();
        if (!primary) {
            g.setColor(CARD);
            g.setStroke(dp(1), LINE);
        }
        g.setCornerRadius(dp(18));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF), g, null));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == VOICE && res == RESULT_OK && data != null && web != null) {
            java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (said != null && !said.isEmpty())
                web.evaluateJavascript("window.onVoice(" + JSONObject.quote(said.get(0)) + ")", null);
            return;
        }
        if (req == PICK_FILE && fileCb != null) {
            Uri[] picked = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {   // several photos at once
                    picked = new Uri[data.getClipData().getItemCount()];
                    for (int i = 0; i < picked.length; i++) picked[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    picked = new Uri[]{data.getData()};
                }
            }
            fileCb.onReceiveValue(picked);
            fileCb = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
