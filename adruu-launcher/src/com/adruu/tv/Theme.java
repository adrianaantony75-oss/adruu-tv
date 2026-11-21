package com.adruu.tv;

import java.util.Calendar;

/** Colour themes, including festival themes picked by date. */
final class Theme {
    final String id, name, greeting;
    final int bg, bg2, surface, accent, onAccent, text, dim;

    Theme(String id, String name, int bg, int bg2, int surface, int accent, int onAccent,
          int text, int dim, String greeting) {
        this.id = id; this.name = name; this.bg = bg; this.bg2 = bg2; this.surface = surface;
        this.accent = accent; this.onAccent = onAccent; this.text = text; this.dim = dim;
        this.greeting = greeting;
    }

    static final Theme[] BASE = {
        new Theme("midnight", "Midnight", 0xFF0B0F1A, 0xFF141B2D, 0xFF1B2438, 0xFF4F8CFF, 0xFFFFFFFF, 0xFFF2F5FA, 0xFF8A96AD, null),
        new Theme("neon", "Neon", 0xFF07050F, 0xFF170A2B, 0xFF1E1236, 0xFFFF2BD6, 0xFFFFFFFF, 0xFFF7EFFF, 0xFF9C8BB8, null),
        new Theme("racing", "Racing red", 0xFF0E0B0B, 0xFF1F1313, 0xFF2A1A1A, 0xFFFF3B30, 0xFFFFFFFF, 0xFFFFF4F2, 0xFFA89290, null),
        new Theme("ocean", "Ocean", 0xFF04161A, 0xFF0A2C33, 0xFF0F3740, 0xFF2EE6C5, 0xFF03201B, 0xFFEFFFFC, 0xFF86A9A5, null),
        new Theme("minimal", "Minimal light", 0xFFF4F4F2, 0xFFE6E6E1, 0xFFFFFFFF, 0xFF1F6FEB, 0xFFFFFFFF, 0xFF15171A, 0xFF6B7078, null),
    };

    static final Theme ONAM = new Theme("onam", "Onam", 0xFF0F1A07, 0xFF2A2405, 0xFF26320F, 0xFFFFC233, 0xFF2A1C00, 0xFFFFF8E6, 0xFFB5AE8C, "Happy Onam");
    static final Theme VISHU = new Theme("vishu", "Vishu", 0xFF14110A, 0xFF2B2208, 0xFF2E2810, 0xFFFFD60A, 0xFF2A2200, 0xFFFFFBEA, 0xFFB9AF86, "Happy Vishu");
    static final Theme DIWALI = new Theme("diwali", "Diwali", 0xFF140A1F, 0xFF2A0F2E, 0xFF2A1838, 0xFFFF9F1C, 0xFF2A1400, 0xFFFFF3E6, 0xFFB79FB0, "Happy Diwali");
    static final Theme CHRISTMAS = new Theme("christmas", "Christmas", 0xFF0B1A12, 0xFF240C0C, 0xFF1F2E24, 0xFFE63946, 0xFFFFFFFF, 0xFFF4FFF7, 0xFF95AD9E, "Merry Christmas");
    static final Theme NEW_YEAR = new Theme("newyear", "New Year", 0xFF0A0A0A, 0xFF1C1708, 0xFF201C10, 0xFFFFD166, 0xFF2A1F00, 0xFFFFFAEB, 0xFFADA58C, "Happy New Year");

    // National days: tricolour (saffron accent on deep navy, white text)
    static final Theme REPUBLIC = new Theme("republic", "Republic Day", 0xFF0A1330, 0xFF0F2A1C, 0xFF16224A, 0xFFFF9933, 0xFF2A1400, 0xFFFFFFFF, 0xFFB8C2D9, "Happy Republic Day");
    static final Theme INDEPENDENCE = new Theme("independence", "Independence Day", 0xFF0A1330, 0xFF0F2A1C, 0xFF16224A, 0xFFFF9933, 0xFF2A1400, 0xFFFFFFFF, 0xFFB8C2D9, "Happy Independence Day");
    static final Theme GANDHI = new Theme("gandhi", "Gandhi Jayanti", 0xFF1A1712, 0xFF26221A, 0xFF2C281F, 0xFFF4EBD6, 0xFF2A2418, 0xFFFFFBF2, 0xFFB7AE9A, "Remembering Gandhiji · Gandhi Jayanti");
    static final Theme PONGAL = new Theme("pongal", "Pongal", 0xFF1A1205, 0xFF2E1F08, 0xFF33240C, 0xFFFFB627, 0xFF2A1A00, 0xFFFFF7E6, 0xFFBDAE8E, "Happy Pongal");
    static final Theme HOLI = new Theme("holi", "Holi", 0xFF1A0A24, 0xFF0A1E2A, 0xFF2A1438, 0xFFFF4FA3, 0xFFFFFFFF, 0xFFFFF0FA, 0xFFC0A8C8, "Happy Holi");
    static final Theme EID = new Theme("eid", "Eid", 0xFF06201A, 0xFF0A2E25, 0xFF0F3A30, 0xFFE8C468, 0xFF2A2000, 0xFFF2FFF9, 0xFF9FC2B6, "Eid Mubarak");

    /**
     * Festivals whose date moves each year: {year, month, day}. Approximate, from published calendars;
     * add new years as they're announced.
     */
    private static final int[][] ONAM_DAYS = {{2026, 8, 26}, {2027, 9, 12}};     // Thiruvonam
    private static final int[][] DIWALI_DAYS = {{2026, 11, 8}, {2027, 10, 29}};  // Lakshmi Puja
    private static final int[][] HOLI_DAYS = {{2026, 3, 4}, {2027, 3, 22}};
    private static final int[][] EID_DAYS = {{2026, 3, 20}, {2027, 3, 10}};      // Eid al-Fitr (depends on the moon)

    /** Every theme the TV switches to by itself, for the phone app's list. */
    static final String AUTO_FESTIVALS = "Republic Day, Pongal, Holi, Eid, Vishu, Independence Day, Onam, "
            + "Gandhi Jayanti, Diwali, Christmas, New Year";

    static boolean isValid(String id) {
        if ("photo".equals(id)) return true;
        for (Theme t : BASE) if (t.id.equals(id)) return true;
        return false;
    }

    static Theme byId(String id) {
        for (Theme t : BASE) if (t.id.equals(id)) return t;
        return BASE[0];
    }

    static Theme current(Prefs p) {
        if (p.festival()) {
            Theme f = festival(Calendar.getInstance());
            if (f != null) return f;
        }
        if ("photo".equals(p.theme())) {
            Theme t = p.photoTheme();
            if (t != null) return t;
        }
        return byId(p.theme());
    }

    static Theme festival(Calendar c) {
        int m = c.get(Calendar.MONTH) + 1, d = c.get(Calendar.DAY_OF_MONTH);
        if (m == 12 && d >= 20 && d <= 27) return CHRISTMAS;
        if ((m == 12 && d >= 30) || (m == 1 && d <= 2)) return NEW_YEAR;
        if (m == 4 && d >= 12 && d <= 16) return VISHU;
        if (m == 1 && d >= 25 && d <= 26) return REPUBLIC;
        if (m == 8 && d >= 14 && d <= 15) return INDEPENDENCE;
        if (m == 10 && d >= 1 && d <= 2) return GANDHI;
        if (m == 1 && d >= 13 && d <= 16) return PONGAL;
        if (near(c, ONAM_DAYS, 9, 1)) return ONAM;      // Onam season: ~10 days up to Thiruvonam
        if (near(c, DIWALI_DAYS, 2, 2)) return DIWALI;
        if (near(c, HOLI_DAYS, 1, 0)) return HOLI;
        if (near(c, EID_DAYS, 1, 1)) return EID;
        return null;
    }

    private static boolean near(Calendar now, int[][] days, int before, int after) {
        for (int[] x : days) {
            Calendar f = Calendar.getInstance();
            f.clear();
            f.set(x[0], x[1] - 1, x[2]);
            long diff = (now.getTimeInMillis() - f.getTimeInMillis()) / 86_400_000L;
            if (now.getTimeInMillis() < f.getTimeInMillis()) diff -= 1;
            if (diff >= -before && diff <= after) return true;
        }
        return false;
    }
}
