package com.adruu.tv;

import android.content.Context;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Phone alerts (opt-in): TV switched on, wrong PIN, someone trying to use the TV while it's locked.
 * Sent to a private, randomly named topic on ntfy.sh (free push service); the owner's phone subscribes
 * with the ntfy app. Works over mobile data, anywhere. Same alert at most once a minute.
 */
final class Notify {
    private static final Map<String, Long> last = new HashMap<>();

    private Notify() {}

    static void send(Context c, String title, String message, String tag) {
        Prefs p = new Prefs(c);
        if (!p.notifyOn()) return;
        final String topic = p.notifyTopic();
        synchronized (last) {
            Long t = last.get(title);
            if (t != null && System.currentTimeMillis() - t < 60_000) return;
            last.put(title, System.currentTimeMillis());
        }
        new Thread(() -> {
            HttpURLConnection h = null;
            try {
                h = (HttpURLConnection) new URL("https://ntfy.sh/" + topic).openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                h.setDoOutput(true);
                h.setRequestProperty("Title", "ADRUU TV: " + title);
                h.setRequestProperty("Tags", tag);
                h.setRequestProperty("Priority", tag.equals("warning") ? "high" : "default");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(message.getBytes(StandardCharsets.UTF_8));
                }
                h.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (h != null) h.disconnect();
            }
        }, "adruu-notify").start();
    }
}
