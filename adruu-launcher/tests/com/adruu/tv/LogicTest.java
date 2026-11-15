package com.adruu.tv;

import java.util.Calendar;

/** Plain-Java checks for logic that doesn't need a TV: festival dates and mode cycling. */
public class LogicTest {
    static int pass, fail;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("PASS  " + name); }
        else { fail++; System.out.println("FAIL  " + name); }
    }

    static String fest(int y, int m, int d, int hour) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, hour, 0);
        Theme t = Theme.festival(c);
        return t == null ? "none" : t.id;
    }

    public static void main(String[] a) {
        check("today 15 Nov 2026 -> none", fest(2026, 11, 15, 18).equals("none"));
        check("Christmas Eve -> christmas", fest(2026, 12, 24, 20).equals("christmas"));
        check("19 Dec -> none", fest(2026, 12, 19, 20).equals("none"));
        check("28 Dec -> none", fest(2026, 12, 28, 10).equals("none"));
        check("31 Dec -> new year", fest(2026, 12, 31, 23).equals("newyear"));
        check("1 Jan -> new year", fest(2027, 1, 1, 0).equals("newyear"));
        check("3 Jan -> none", fest(2027, 1, 3, 9).equals("none"));
        check("Vishu 14 Apr -> vishu", fest(2027, 4, 14, 7).equals("vishu"));
        check("Thiruvonam day 2026 -> onam", fest(2026, 8, 26, 12).equals("onam"));
        check("9 days before Onam -> onam", fest(2026, 8, 17, 12).equals("onam"));
        check("14 days before Onam -> none", fest(2026, 8, 12, 12).equals("none"));
        check("15 Aug 2026 (also before Onam) -> independence", fest(2026, 8, 15, 12).equals("independence"));
        check("2 days after Onam -> none", fest(2026, 8, 28, 12).equals("none"));
        check("Diwali 2026 -> diwali", fest(2026, 11, 8, 19).equals("diwali"));
        check("Diwali eve 2026 (late night) -> diwali", fest(2026, 11, 7, 23).equals("diwali"));
        check("3 days after Diwali -> none", fest(2026, 11, 11, 10).equals("none"));
        check("Diwali 2027 -> diwali", fest(2027, 10, 29, 19).equals("diwali"));

        check("Gandhi Jayanti 2 Oct -> gandhi", fest(2026, 10, 2, 9).equals("gandhi"));
        check("Independence Day 15 Aug -> independence", fest(2027, 8, 15, 9).equals("independence"));
        check("Republic Day 26 Jan -> republic", fest(2027, 1, 26, 9).equals("republic"));
        check("Pongal 14 Jan -> pongal", fest(2027, 1, 14, 9).equals("pongal"));
        check("Holi 2027 -> holi", fest(2027, 3, 22, 9).equals("holi"));
        check("Eid 2027 -> eid", fest(2027, 3, 10, 9).equals("eid"));
        check("3 Oct -> none", fest(2026, 10, 3, 9).equals("none"));
        check("mode cycle auto->normal", Modes.next("auto").equals("normal"));
        check("mode cycle game->auto", Modes.next("game").equals("auto"));
        check("mode cycle unknown->auto", Modes.next("banana").equals("auto"));
        check("mode label movie", Modes.label("movie").equals("Movie"));
        check("duration 75 min", Modes.duration(75 * 60_000L).equals("1 h 15 min"));
        check("duration 5 min", Modes.duration(5 * 60_000L).equals("5 min"));
        check("theme fallback", Theme.byId("nope").id.equals("midnight"));
        check("valid theme ids", Theme.isValid("neon") && !Theme.isValid("nope"));
        check("valid modes", Modes.isValid("movie") && !Modes.isValid("banana"));

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
