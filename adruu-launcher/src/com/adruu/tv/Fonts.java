package com.adruu.tv;

import android.content.Context;
import android.graphics.Typeface;

/**
 * ADRUU's typefaces (both SIL Open Font License, bundled in assets):
 * Manrope for the interface, Noto Sans Malayalam for Malayalam text such as the lock-screen dialogue.
 */
final class Fonts {
    private static Typeface regular, semibold, bold, malayalam;

    private Fonts() {}

    private static Typeface load(Context c, String file, int weight) {
        try {
            return new Typeface.Builder(c.getAssets(), file)
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (Exception e) {
            return Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    static synchronized Typeface ui(Context c, boolean strong) {
        Context a = c.getApplicationContext();
        if (regular == null) {
            regular = load(a, "font_manrope.ttf", 500);
            bold = load(a, "font_manrope.ttf", 700);
        }
        return strong ? bold : regular;
    }

    static synchronized Typeface semibold(Context c) {
        if (semibold == null) semibold = load(c.getApplicationContext(), "font_manrope.ttf", 600);
        return semibold;
    }

    static synchronized Typeface malayalam(Context c) {
        if (malayalam == null) malayalam = load(c.getApplicationContext(), "font_malayalam.ttf", 700);
        return malayalam;
    }
}
