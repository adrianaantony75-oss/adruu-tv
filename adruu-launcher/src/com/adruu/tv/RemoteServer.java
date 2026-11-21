package com.adruu.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server on the TV (port 8686) for the phone page and the control API.
 * Every request needs the secret token (?t=... or header X-ADRUU-Token).
 * Reachable on the home Wi-Fi, or from anywhere through a private Tailscale network.
 */
final class RemoteServer {
    static final int PORT = 8686;
    private static final int MAX_BODY = 200 * 1024 * 1024; // APK uploads

    private final Context c;
    private final Prefs p;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private ServerSocket server;
    private volatile boolean running;

    RemoteServer(Context c) {
        this.c = c.getApplicationContext();
        p = new Prefs(c);
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (running) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            }
        }, "adruu-server").start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    static String address(Context c) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "TV-IP";
    }

    /** Every IPv4 address of the TV: home Wi-Fi and, with Tailscale, its 100.x "away" address. */
    static JSONArray addresses() {
        JSONArray a = new JSONArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses()))
                    if (ad instanceof Inet4Address) a.put(ad.getHostAddress());
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    static String phoneUrl(Context c) {
        return "http://" + address(c) + ":" + PORT + "/?t=" + new Prefs(c).token();
    }

    // ------------------------------------------------------------------

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(30_000);
            InputStream in = sock.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) headers.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
            }
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            int len = 0;
            try { len = Integer.parseInt(headers.getOrDefault("content-length", "0")); } catch (Exception ignored) { }
            if (len > MAX_BODY) { send(sock, 413, "text/plain", "Too big".getBytes()); return; }

            // --- open calls (no token): finding the TV and pairing the phone app ---
            if (path.equals("/api/hello")) {
                JSONObject hi = new JSONObject().put("ok", true).put("app", "ADRUU")
                        .put("name", android.os.Build.MODEL).put("version", BuildInfo.VERSION)
                        .put("addresses", addresses());   // includes the Tailscale 100.x address when installed
                send(sock, 200, "application/json", hi.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/pair/start") && method.equals("POST")) {
                Pairing.start(c);
                send(sock, 200, "application/json", "{\"ok\":true,\"message\":\"Look at the TV for the code\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/pair") && method.equals("POST")) {
                if (Pairing.check(q.get("code"))) {
                    p.addEvent("A phone was paired with ADRUU Remote");
                    send(sock, 200, "application/json", new JSONObject().put("ok", true).put("token", p.token()).toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    send(sock, 401, "application/json", "{\"ok\":false,\"error\":\"Wrong or expired code\"}".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }

            String token = q.containsKey("t") ? q.get("t") : headers.get("x-adruu-token");
            if (!same(token, p.token())) {
                send(sock, 401, "text/plain", "ADRUU: wrong or missing token".getBytes());
                return;
            }

            if (path.equals("/") && method.equals("GET")) {
                send(sock, 200, "text/html; charset=utf-8", asset("phone.html"));
                return;
            }
            if (path.equals("/api/apk") && method.equals("POST")) {
                if (len < 1024) {
                    send(sock, 400, "application/json", "{\"ok\":false,\"error\":\"That isn't an APK file\"}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                send(sock, 200, "application/json", installApk(in, len).toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/wallpaper/upload") && method.equals("POST")) {
                JSONObject r = new JSONObject();
                if (len < 1024 || len > Media.MAX_PHOTO_MB * 1024 * 1024) {
                    r.put("ok", false).put("error", "Photo must be under " + Media.MAX_PHOTO_MB + " MB");
                } else {
                    java.io.File tmp = Media.receive(c, in, len, "upload.img");
                    boolean theme = "1".equals(q.get("theme"));
                    try {
                        if (theme) Media.themeFromPhoto(c, tmp);
                        String album = Media.cleanAlbum(q.get("album"));
                        String name = Media.saveWallpaper(c, tmp, album);
                        p.wallpaper("album:" + album);
                        if (theme) p.theme("photo");
                        main.post(MainActivity::reloadWallpaper);
                        r.put("ok", true).put("name", name).put("message", theme ? "Wallpaper and matching theme set" : "Wallpaper added");
                    } catch (Exception e) {
                        r.put("ok", false).put("error", e.getMessage());
                    }
                }
                send(sock, r.optBoolean("ok") ? 200 : 400, "application/json", r.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/intro/upload") && method.equals("POST")) {
                JSONObject r = new JSONObject();
                if (len < 1024 || len > Media.MAX_INTRO_MB * 1024 * 1024) {
                    r.put("ok", false).put("error", "Video must be under " + Media.MAX_INTRO_MB + " MB");
                } else {
                    Media.saveIntro(c, Media.receive(c, in, len, "upload.mp4"));
                    p.intro(true);
                    r.put("ok", true).put("message", "Startup video saved (" + (len >> 20) + " MB). It plays next time the TV starts.");
                }
                send(sock, r.optBoolean("ok") ? 200 : 400, "application/json", r.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if ((path.equals("/api/intruder/image") || path.equals("/api/intruder/audio")) && method.equals("POST")) {
                JSONObject r = new JSONObject();
                if (len < 200 || len > 15 * 1024 * 1024) {
                    r.put("ok", false).put("error", "File must be under 15 MB");
                } else {
                    boolean image = path.endsWith("image");
                    java.io.File tmp = Media.receive(c, in, len, "intruder.tmp");
                    java.io.File dst = image ? Intruder.imageFile(c) : Intruder.audioFile(c);
                    //noinspection ResultOfMethodCallIgnored
                    dst.delete();
                    if (image && android.graphics.BitmapFactory.decodeFile(tmp.getPath()) == null) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        r.put("ok", false).put("error", "That isn't a picture the TV can read");
                    } else if (tmp.renameTo(dst)) {
                        r.put("ok", true).put("message", image ? "Lock-screen picture saved" : "Lock-screen voice saved");
                    } else {
                        r.put("ok", false).put("error", "Couldn't save the file");
                    }
                }
                send(sock, r.optBoolean("ok") ? 200 : 400, "application/json", r.toString().getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/font/manrope") || path.equals("/api/font/malayalam")) {   // fonts for the phone app
                send(sock, 200, "font/ttf", asset(path.endsWith("manrope") ? "font_manrope.ttf" : "font_malayalam.ttf"));
                return;
            }
            if (path.equals("/api/remote/apk")) {   // newest ADRUU Remote, bundled with the TV app
                send(sock, 200, "application/vnd.android.package-archive", asset("remote.apk"));
                return;
            }
            byte[] body = readBody(in, len);
            JSONObject res = api(path, q, new String(body, StandardCharsets.UTF_8));
            send(sock, res.optBoolean("ok", true) ? 200 : 400, "application/json", res.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private JSONObject api(String path, Map<String, String> q, String body) throws Exception {
        JSONObject r = new JSONObject().put("ok", true);
        switch (path) {
            case "/api/status":
                r.put("locked", MainActivity.isLockedNow(p))
                        .put("lockOn", p.lockOn())
                        .put("theme", p.theme()).put("festival", p.festival()).put("wallpaper", p.wallpaper())
                        .put("mode", p.mode()).put("modeNow", Modes.effective(c, p.mode()))
                        .put("guest", p.guest()).put("clicks", p.clicks()).put("intro", p.intro())
                        .put("perf", p.perfOverlay()).put("subtitles", p.subtitles()).put("city", p.city())
                        .put("mouse", MouseService.isActive()).put("mouseReady", MouseService.isReady())
                        .put("lockHelp", p.hasPin()
                                ? "Lock TV shows the PIN screen at once. Unlock with the PIN or from the phone."
                                : "Lock TV blocks the TV at once. Only the phone app can unlock it (or restarting the TV).")
                        .put("hasPin", p.hasPin())
                        .put("introVideoMB", Media.introFile(c).exists() ? Media.introFile(c).length() >> 20 : -1)
                        .put("maxIntroMB", Media.MAX_INTRO_MB)
                        .put("version", BuildInfo.VERSION)
                        .put("festivals", Theme.AUTO_FESTIVALS)
                        .put("notify", p.notifyOn()).put("notifyTopic", p.notifyOn() ? p.notifyTopic() : "")
                        .put("intruderText", p.intruderText())
                        .put("intruderImage", Intruder.imageFile(c).exists())
                        .put("intruderAudio", Intruder.audioFile(c).exists())
                        .put("malayalamVoice", Intruder.hasMalayalamVoice())
                        .put("accent", String.format("#%06X", Theme.current(p).accent & 0xFFFFFF))
                        .put("accentBg", String.format("#%06X", Theme.current(p).bg2 & 0xFFFFFF))
                        .put("festivalNow", Theme.festival(java.util.Calendar.getInstance()) == null ? "" : Theme.festival(java.util.Calendar.getInstance()).name)
                        .put("tvName", android.os.Build.MODEL)
                        .put("tiles", p.tileStyle())
                        .put("foreground", Modes.sForeground)
                        .put("foregroundLabel", appLabel(Modes.sForeground))
                        .put("events", new JSONArray(p.eventsJson()));
                JSONArray themes = new JSONArray();
                for (Theme t : Theme.BASE) themes.put(new JSONObject().put("id", t.id).put("name", t.name));
                if (p.photoTheme() != null) themes.put(new JSONObject().put("id", "photo").put("name", "From my photo"));
                r.put("themes", themes);
                return r;
            case "/api/unlock":
                MainActivity.sLocked = false;
                MainActivity.sPhoneLocked = false;
                p.addEvent("Unlocked from phone");
                main.post(MainActivity::refreshFromOutside);
                return r;
            case "/api/lock":
                // Immediate: the lock screen comes to the front over whatever app is open.
                if (p.lockOn()) MainActivity.sLocked = true; else MainActivity.sPhoneLocked = true;
                p.addEvent("Locked from phone");
                main.post(() -> {
                    c.startActivity(new Intent(c, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    MainActivity.refreshFromOutside();
                });
                return r;
            case "/api/mode": {
                String id = q.getOrDefault("id", "auto");
                if (!Modes.isValid(id)) return r.put("ok", false).put("error", "Unknown mode: " + id);
                p.mode(id);
                main.post(MainActivity::refreshFromOutside);
                return r;
            }
            case "/api/theme": {
                String id = q.getOrDefault("id", "midnight");
                if (!Theme.isValid(id)) return r.put("ok", false).put("error", "Unknown theme: " + id);
                p.theme(id);
                main.post(MainActivity::refreshFromOutside);
                return r;
            }
            case "/api/mouse":
                boolean on = "1".equals(q.get("on"));
                main.post(() -> MouseService.setActive(on));
                return r.put("ready", MouseService.isReady());
            case "/api/open": {
                String url = q.get("url");
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://")))
                    return r.put("ok", false).put("error", "Send a web link");
                main.post(() -> {
                    try {
                        c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                });
                return r;
            }
            case "/api/apps": {
                JSONArray a = new JSONArray();
                for (AppAdapter.App app : AppAdapter.load(c, false)) {
                    if (!app.pkg.startsWith("adruu:")) a.put(new JSONObject().put("pkg", app.pkg).put("label", app.label));
                }
                return r.put("apps", a);
            }
            case "/api/launch": {
                String pkg = q.get("pkg");
                Intent i = pkg == null ? null : c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (i == null && pkg != null) i = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return r.put("ok", false).put("error", "App not found");
                final Intent li = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                main.post(() -> c.startActivity(li));
                return r;
            }
            case "/api/home":
                main.post(() -> c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                return r;
            case "/api/key":
                return key(q.getOrDefault("k", ""), r);
            case "/api/mouse/move": {
                float dx = Float.parseFloat(q.getOrDefault("dx", "0")), dy = Float.parseFloat(q.getOrDefault("dy", "0"));
                if (!MouseService.isReady()) return r.put("ok", false).put("error", "Mouse service is off");
                main.post(() -> MouseService.move(dx, dy));
                return r;
            }
            case "/api/mouse/tap":
                main.post(MouseService::tapHere);
                return r;
            case "/api/mouse/scroll": {
                String dir = q.getOrDefault("dir", "");
                if (!dir.matches("up|down|left|right")) return r.put("ok", false).put("error", "Scroll up, down, left or right");
                main.post(() -> MouseService.scroll(dir));
                return r;
            }
            case "/api/power": {
                String st = q.getOrDefault("state", "");
                if (st.equals("off")) {
                    // Standby, same as the remote's power button.
                    if (!MouseService.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN))
                        return r.put("ok", false).put("error", "Needs the ADRUU accessibility service");
                    return r;
                }
                if (st.equals("on")) {
                    android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
                    @SuppressWarnings("deprecation")
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "adruu:wake");
                    wl.acquire(3000);
                    return r;
                }
                return r.put("ok", false).put("error", "state must be on or off");
            }
            case "/api/apps/uninstall": {
                String pkg = q.get("pkg");
                if (pkg == null || pkg.equals(c.getPackageName())) return r.put("ok", false).put("error", "Choose an app");
                main.post(() -> c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                return r.put("message", "Confirm on the TV to uninstall");
            }
            case "/api/apps/store": {
                String pkg = q.get("pkg"), s = q.get("q");
                String url = pkg != null ? "market://details?id=" + pkg : s != null ? "market://search?q=" + Uri.encode(s) + "&c=apps" : null;
                if (url == null) return r.put("ok", false).put("error", "Type an app name");
                main.post(() -> {
                    try {
                        c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) {
                    }
                });
                return r.put("message", "Play Store opened on the TV");
            }
            case "/api/wallpaper/list":
                return r.put("photos", Media.listWallpapers(c)).put("albums", Media.albums(c))
                        .put("slideshow", p.slideshow());
            case "/api/wallpaper/delete":
                if (!Media.deleteWallpaper(c, q.get("album"), q.get("name"))) return r.put("ok", false).put("error", "Not found");
                main.post(MainActivity::reloadWallpaper);
                return r;
            case "/api/order": {   // phone app: new home screen order (JSON array of package names)
                JSONArray a = new JSONArray(body.isEmpty() ? "[]" : body);
                p.favourites(strings(a));
                main.post(MainActivity::refreshFromOutside);
                return r.put("count", a.length());
            }
            case "/api/intro/clear":
                //noinspection ResultOfMethodCallIgnored
                Media.introFile(c).delete();
                return r.put("message", "Back to the plane intro");
            case "/api/remote/version":
                return r.put("versionCode", Integer.parseInt(new String(asset("remote-version.txt"), StandardCharsets.UTF_8).trim()));
            case "/api/reports/add": {
                JSONObject j = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String text = j.optString("text", "").trim();
                if (text.isEmpty()) return r.put("ok", false).put("error", "Write or say what went wrong");
                if (text.length() > 4000) text = text.substring(0, 4000);
                String who = j.optString("name", "Family").trim();
                Health.record("user", who + ": " + text, j.put("text", text).toString());
                return r.put("message", "Thanks! Report saved on the TV.");
            }
            case "/api/pin": {   // set or remove the TV PIN from the paired phone
                JSONObject j = body.isEmpty() ? new JSONObject() : new JSONObject(body);
                String pin = j.optString("pin", "");
                if (j.optBoolean("remove")) {
                    p.lockOn(false);
                    MainActivity.sLocked = false;
                    p.removePin();
                    main.post(MainActivity::refreshFromOutside);
                    return r.put("message", "PIN removed");
                }
                if (!pin.matches("\\d{6}")) return r.put("ok", false).put("error", "The PIN must be 6 digits");
                p.setPin(pin);
                p.fails(0);
                p.addEvent("PIN changed from phone");
                main.post(MainActivity::refreshFromOutside);
                return r.put("message", "PIN saved");
            }
            case "/api/intruder/clear": {
                String what = q.getOrDefault("what", "");
                if (what.equals("image")) //noinspection ResultOfMethodCallIgnored
                    Intruder.imageFile(c).delete();
                else if (what.equals("audio")) //noinspection ResultOfMethodCallIgnored
                    Intruder.audioFile(c).delete();
                else return r.put("ok", false).put("error", "what=image or audio");
                return r;
            }
            case "/api/intruder/test":
                main.post(() -> {
                    MainActivity a = MainActivity.current();
                    if (a != null) a.intruder("Test from the phone app");
                });
                return r.put("message", "Showing on the TV");
            case "/api/notify/test":
                if (!p.notifyOn()) return r.put("ok", false).put("error", "Turn on phone alerts first");
                Notify.send(c, "Test alert", "Alerts from your TV are working", "white_check_mark");
                return r.put("message", "Test alert sent");
            case "/api/reports":
                return r.put("reports", Health.reports(c));
            case "/api/reports/clear":
                return r.put("cleared", Health.clear(c));
            case "/api/reports/check":
                Health.selfCheck(c);
                return r.put("reports", Health.reports(c));
            case "/api/screentime": {
                JSONArray a = new JSONArray();
                long total = 0;
                for (Modes.Usage u : Modes.today(c)) {
                    total += u.ms;
                    a.put(new JSONObject().put("label", u.label).put("pkg", u.pkg).put("minutes", u.ms / 60_000));
                }
                return r.put("totalMinutes", total / 60_000).put("apps", a);
            }
            case "/api/config":
                return applyConfig(body.isEmpty() ? new JSONObject() : new JSONObject(body), r);
            default:
                return r.put("ok", false).put("error", "Unknown: " + path);
        }
    }

    /**
     * Live changes from the phone or the control API. Any subset of:
     * theme, festival, clicks, intro, perf, subtitles, city, mode, guest, lockOn (false only),
     * hidden[], favourites[], personal[].
     */
    private JSONObject applyConfig(JSONObject j, JSONObject r) throws Exception {
        JSONArray changed = new JSONArray();
        if (j.has("theme") && !Theme.isValid(j.getString("theme")))
            return r.put("ok", false).put("error", "Unknown theme: " + j.getString("theme"));
        if (j.has("mode") && !Modes.isValid(j.getString("mode")))
            return r.put("ok", false).put("error", "Unknown mode: " + j.getString("mode"));
        if (j.has("wallpaper") && !Wallpaper.isValid(j.getString("wallpaper")))
            return r.put("ok", false).put("error", "Unknown wallpaper: " + j.getString("wallpaper"));
        if (j.has("wallpaper")) { p.wallpaper(j.getString("wallpaper")); changed.put("wallpaper"); }
        if (j.has("tiles")) {
            String ts = j.getString("tiles");
            if (!ts.equals("rounded") && !ts.equals("square")) return r.put("ok", false).put("error", "tiles: rounded or square");
            p.tileStyle(ts);
            changed.put("tiles");
            main.post(MainActivity::rebuildFromOutside);
        }
        if (j.has("slideshow")) {
            int m = j.getInt("slideshow");
            if (m < 0 || m > 240) return r.put("ok", false).put("error", "Slideshow minutes must be 0-240");
            p.slideshow(m);
            changed.put("slideshow");
        }
        if (j.has("theme")) { p.theme(j.getString("theme")); changed.put("theme"); }
        if (j.has("festival")) { p.festival(j.getBoolean("festival")); changed.put("festival"); }
        if (j.has("clicks")) { p.clicks(j.getBoolean("clicks")); changed.put("clicks"); }
        if (j.has("intro")) { p.intro(j.getBoolean("intro")); changed.put("intro"); }
        if (j.has("perf")) { p.perfOverlay(j.getBoolean("perf")); changed.put("perf"); }
        if (j.has("subtitles")) { p.subtitles(j.getBoolean("subtitles")); changed.put("subtitles"); }
        if (j.has("city")) { p.city(j.getString("city")); changed.put("city"); }
        if (j.has("mode")) { p.mode(j.getString("mode")); changed.put("mode"); }
        if (j.has("guest")) { p.guest(j.getBoolean("guest")); changed.put("guest"); }
        if (j.has("lockOn")) {
            boolean on = j.getBoolean("lockOn");
            if (on && !p.hasPin()) return r.put("ok", false).put("error", "Set a PIN first");
            p.lockOn(on);
            if (!on) MainActivity.sLocked = false;
            changed.put("lockOn");
        }
        if (j.has("notify")) { p.notifyOn(j.getBoolean("notify")); changed.put("notify"); }
        if (j.has("intruderText")) {
            String s = j.getString("intruderText").trim();
            if (s.isEmpty() || s.length() > 120) return r.put("ok", false).put("error", "Dialogue: 1 to 120 characters");
            p.intruderText(s);
            changed.put("intruderText");
        }
        if (j.has("hidden")) { p.hidden(strings(j.getJSONArray("hidden"))); changed.put("hidden"); }
        if (j.has("favourites")) { p.favourites(strings(j.getJSONArray("favourites"))); changed.put("favourites"); }
        if (j.has("personal")) { p.personal(strings(j.getJSONArray("personal"))); changed.put("personal"); }
        main.post(MainActivity::refreshFromOutside);
        return r.put("changed", changed);
    }

    /** Friendly name of the app on screen, for the phone's status card. */
    private String appLabel(String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return "Home screen";
        if (pkg.contains("dream")) return "Screensaver";
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return "Home screen";
        }
    }

    /** Remote buttons: back, home, playpause, next, prev, volup, voldown, mute. Volume reaches the soundbar over HDMI-CEC. */
    private JSONObject key(String k, JSONObject r) throws Exception {
        android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
        int media = -1;
        switch (k) {
            case "back":
                if (!MouseService.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    return r.put("ok", false).put("error", "Back needs the ADRUU accessibility service");
                return r;
            case "home":
                main.post(() -> c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                return r;
            case "playpause": media = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "next": media = android.view.KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "prev": media = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "volup":
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI);
                return r;
            case "voldown":
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI);
                return r;
            case "mute":
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI);
                return r;
            default:
                return r.put("ok", false).put("error", "Unknown key: " + k);
        }
        long now = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, media, 0));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, media, 0));
        return r;
    }

    private static List<String> strings(JSONArray a) throws Exception {
        List<String> l = new java.util.ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
        return l;
    }

    /** Streams an uploaded APK into Android's installer; the TV then asks to confirm. */
    private JSONObject installApk(InputStream in, int len) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("upload.apk", 0, len)) {
                byte[] buf = new byte[64 * 1024];
                int left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, left));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    left -= n;
                }
                s.fsync(out);
            }
            s.commit(InstallReceiver.pending(c, id));
        }
        p.addEvent("App sent from phone - confirm the install on the TV");
        return new JSONObject().put("ok", true).put("message", "Check the TV and press Install");
    }

    // ------------------------------------------------------------------

    private byte[] asset(String name) throws Exception {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }

    private static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') sb.append((char) ch);
            if (sb.length() > 8192) return null;
        }
        return ch < 0 && sb.length() == 0 ? null : sb.toString();
    }

    private static byte[] readBody(InputStream in, int len) throws Exception {
        if (len <= 0) return new byte[0];
        if (len > 1024 * 1024) throw new IllegalArgumentException("body too big");
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return b;
    }

    private static void send(Socket s, int code, String type, byte[] body) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 413 ? "Too Large" : "Bad Request";
        if (type.equals("application/json")) type = "application/json; charset=utf-8";   // Malayalam text
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(body);
        o.flush();
    }
}
