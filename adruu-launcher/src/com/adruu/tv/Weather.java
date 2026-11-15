package com.adruu.tv;

import android.os.Handler;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Current weather from Open-Meteo (free, no account or key). */
final class Weather {
    interface Callback { void done(String text); }

    private Weather() {}

    static void fetch(Prefs p, Handler main, Callback cb) {
        new Thread(() -> {
            try {
                if (!p.hasCoords()) {
                    String q = URLEncoder.encode(p.city(), "UTF-8");
                    JSONObject g = get("https://geocoding-api.open-meteo.com/v1/search?count=10&name=" + q);
                    JSONArray r = g.optJSONArray("results");
                    if (r == null || r.length() == 0) return;
                    // Prefer a match in India (e.g. Kochi, Kerala rather than Kōchi, Japan).
                    JSONObject best = r.getJSONObject(0);
                    for (int i = 0; i < r.length(); i++) {
                        if ("IN".equals(r.getJSONObject(i).optString("country_code"))) { best = r.getJSONObject(i); break; }
                    }
                    p.coords(best.getDouble("latitude"), best.getDouble("longitude"));
                }
                JSONObject w = get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                + "&current=temperature_2m,weather_code"
                                + "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code"
                                + "&forecast_hours=12&timezone=auto", p.lat(), p.lon()));
                JSONObject cur = w.getJSONObject("current");
                String text = Math.round(cur.getDouble("temperature_2m")) + "°  "
                        + describe(cur.getInt("weather_code")) + "  ·  " + p.city();
                // Next 12 hours: time, temperature, chance of rain, wind
                JSONObject h = w.optJSONObject("hourly");
                if (h != null) {
                    JSONArray time = h.getJSONArray("time"), temp = h.getJSONArray("temperature_2m"),
                            rain = h.getJSONArray("precipitation_probability"), wind = h.getJSONArray("wind_speed_10m"),
                            code = h.getJSONArray("weather_code");
                    JSONArray hours = new JSONArray();
                    for (int i = 0; i < time.length() && i < 12; i++) {
                        int hr = Integer.parseInt(time.getString(i).substring(11, 13));
                        String label = i == 0 ? "Now" : (hr % 12 == 0 ? 12 : hr % 12) + (hr < 12 ? " AM" : " PM");
                        hours.put(new JSONObject().put("h", label).put("t", Math.round(temp.optDouble(i)))
                                .put("r", rain.optInt(i)).put("w", Math.round(wind.optDouble(i)))
                                .put("c", describe(code.optInt(i))));
                    }
                    p.hourly(hours.toString());
                }
                p.lastWeather(text);
                main.post(() -> cb.done(text));
            } catch (Exception ignored) {
            }
        }, "adruu-weather").start();
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder s = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) s.append(line);
            return new JSONObject(s.toString());
        } finally {
            c.disconnect();
        }
    }

    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Partly cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Showers";
        return "Thunderstorm";
    }
}
