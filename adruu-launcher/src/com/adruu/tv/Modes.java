package com.adruu.tv;

import android.app.NotificationManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Viewing modes and screen time.
 * Movie / Game: do-not-disturb on (no pop-ups). Music / Normal: pop-ups allowed.
 * Auto: picks Movie for streaming apps, Music for music apps, Normal for everything else.
 * (TCL keeps picture presets private, so modes can't switch the panel's picture mode.)
 */
final class Modes {
    static final String[] ALL = {"auto", "normal", "movie", "music", "game"};

    private static final List<String> MOVIE_APPS = Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.sonyliv",
            "com.graymatrix.did", "com.suntv.sunnxt", "com.jio.media.jiotvplus", "com.mmtv.manoramamax.android",
            "com.mxtech.videoplayer.television", "org.videolan.vlc", "com.google.android.videos", "com.fancode.tv",
            "com.google.android.youtube.tv", "com.teamsmart.videomanager.tv");
    private static final List<String> MUSIC_APPS = Arrays.asList(
            "com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.radio.pocketfm");

    private Modes() {}

    static String label(String m) {
        switch (m) {
            case "movie": return "Movie";
            case "music": return "Music";
            case "game": return "Game";
            case "normal": return "Normal";
            default: return "Auto";
        }
    }

    static boolean isValid(String m) {
        for (String x : ALL) if (x.equals(m)) return true;
        return false;
    }

    static String next(String m) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(m)) return ALL[(i + 1) % ALL.length];
        return "auto";
    }

    /** Last app seen in front; refreshed in the background by ControlService, so the UI never queries. */
    static volatile String sForeground;

    /** The mode actually in effect right now (cheap: uses the cached foreground app). */
    static String effective(Context c, String m) {
        if (!m.equals("auto")) return m;
        String fg = sForeground;
        if (fg == null) return "normal";
        if (MOVIE_APPS.contains(fg)) return "movie";
        if (MUSIC_APPS.contains(fg)) return "music";
        return "normal";
    }

    static void apply(Context c, String effective) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.isNotificationPolicyAccessGranted()) return;
        boolean quiet = effective.equals("movie") || effective.equals("game");
        int want = quiet ? NotificationManager.INTERRUPTION_FILTER_PRIORITY : NotificationManager.INTERRUPTION_FILTER_ALL;
        if (nm.getCurrentInterruptionFilter() != want) nm.setInterruptionFilter(want);
    }

    /** Last app opened since {@code since} (null if none), from usage events. Cheap for short windows. */
    static String foreground(Context c, long since) {
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents ev = um.queryEvents(since, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String last = null;
        while (ev != null && ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
        }
        return last;
    }

    static final class Usage {
        final String label, pkg;
        final long ms;
        Usage(String label, String pkg, long ms) { this.label = label; this.pkg = pkg; this.ms = ms; }
    }

    /** Screen time per app since midnight, biggest first. */
    static List<Usage> today(Context c) {
        List<Usage> out = new ArrayList<>();
        UsageStatsManager um = c.getSystemService(UsageStatsManager.class);
        if (um == null) return out;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        Map<String, UsageStats> m = um.queryAndAggregateUsageStats(cal.getTimeInMillis(), System.currentTimeMillis());
        PackageManager pm = c.getPackageManager();
        for (UsageStats s : m.values()) {
            long ms = s.getTotalTimeInForeground();
            if (ms < 60_000 || s.getPackageName().equals(c.getPackageName())) continue;
            String label = s.getPackageName();
            try {
                label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(label, 0)));
            } catch (Exception ignored) {
            }
            out.add(new Usage(label, s.getPackageName(), ms));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.ms, a.ms));
        return out;
    }

    static String duration(long ms) {
        long min = ms / 60_000;
        return min >= 60 ? (min / 60) + " h " + (min % 60) + " min" : min + " min";
    }
}
