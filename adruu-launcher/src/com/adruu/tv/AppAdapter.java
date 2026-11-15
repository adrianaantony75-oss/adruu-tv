package com.adruu.tv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tablet-style app tiles: wide banner cards with the app name underneath. */
final class AppAdapter extends BaseAdapter {
    static final class App {
        final String label, pkg;
        final Intent launch;
        final Drawable banner, icon;

        App(String label, String pkg, Intent launch, Drawable banner, Drawable icon) {
            this.label = label; this.pkg = pkg; this.launch = launch; this.banner = banner; this.icon = icon;
        }
    }

    /** Shown first, in this order, when installed. */
    private static final List<String> FAVOURITES = Arrays.asList(
            "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
            "com.google.android.youtube.tv", "com.sonyliv", "com.fancode.tv",
            "com.google.android.youtube.tvmusic", "com.spotify.tv.android",
            "com.mxtech.videoplayer.television", "com.jio.media.jiotvplus", "com.graymatrix.did",
            "com.suntv.sunnxt", "com.mmtv.manoramamax.android", "com.android.vending");

    /** Setup/system screens that shouldn't clutter the home screen. */
    private static final List<String> HIDDEN = Arrays.asList(
            "com.adruu.tv", "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
            "com.tcl.initsetup", "com.tcl.factory.view", "com.tcl.useragreement", "com.tcl.overseasappshow",
            "com.tcl.ocean.instructions", "com.tcl.usercenter", "com.tcl.messagebox");

    private final Context c;
    private final Theme t;
    private List<App> apps = new ArrayList<>();

    AppAdapter(Context c, Theme t) {
        this.c = c;
        this.t = t;
    }

    void setApps(List<App> list) {
        apps = list;
        notifyDataSetChanged();
    }

    /** Slow (reads icons), so call off the main thread. homeScreen = apply hidden/guest filters. */
    static List<App> load(Context c, boolean homeScreen) {
        PackageManager pm = c.getPackageManager();
        Prefs prefs = new Prefs(c);
        List<String> userHidden = homeScreen ? prefs.hidden() : new ArrayList<>();
        List<String> guestHidden = homeScreen && prefs.guest() ? prefs.personal() : new ArrayList<>();
        List<String> favs = prefs.favourites().isEmpty() ? FAVOURITES : prefs.favourites();
        Map<String, App> found = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (found.containsKey(pkg) || HIDDEN.contains(pkg) || userHidden.contains(pkg) || guestHidden.contains(pkg)) continue;
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                Drawable banner = null;
                try {
                    banner = ri.activityInfo.loadBanner(pm);
                    if (banner == null) {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        banner = ai.loadBanner(pm);
                    }
                } catch (Exception ignored) {
                }
                String label = String.valueOf(ri.loadLabel(pm));
                found.put(pkg, new App(label, pkg, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        banner, banner == null ? ri.loadIcon(pm) : null));
            }
        }
        List<App> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> {
            int fa = favs.indexOf(a.pkg), fb = favs.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return fa - fb;
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        if (!guestHidden.contains("adruu:settings")) {
            list.add(new App("ADRUU settings", "adruu:settings",
                    new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null,
                    c.getDrawable(R.drawable.ic_launcher)));
        }
        if (!guestHidden.contains("adruu:tvsettings")) {
            list.add(new App("TV settings", "adruu:tvsettings",
                    new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    null, c.getDrawable(android.R.drawable.ic_menu_preferences)));
        }
        return list;
    }

    @Override public int getCount() { return apps.size(); }
    @Override public App getItem(int i) { return apps.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int i, View convert, ViewGroup parent) {
        Holder hd;
        if (convert == null) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 122)));
            box.setClipChildren(false);

            FrameLayout card = new FrameLayout(c);
            card.setBackground(UI.round(t.surface, UI.dp(c, 14)));
            card.setClipToOutline(true);
            box.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UI.dp(c, 94)));

            ImageView img = new ImageView(c);
            card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView name = UI.text(c, "", 13, t.text, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setPadding(0, UI.dp(c, 9), 0, 0);
            box.addView(name);

            hd = new Holder(card, img, name);
            box.setTag(hd);
            convert = box;
        } else {
            hd = (Holder) convert.getTag();
        }
        App a = apps.get(i);
        hd.name.setText(a.label);
        if (a.banner != null) {
            hd.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            hd.img.setPadding(0, 0, 0, 0);
            hd.img.setImageDrawable(a.banner);
        } else {
            int pad = UI.dp(c, 22);
            hd.img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            hd.img.setPadding(pad, pad, pad, pad);
            hd.img.setImageDrawable(a.icon);
        }
        return convert;
    }

    static final class Holder {
        final FrameLayout card;
        final ImageView img;
        final TextView name;

        Holder(FrameLayout card, ImageView img, TextView name) {
            this.card = card; this.img = img; this.name = name;
        }
    }
}
