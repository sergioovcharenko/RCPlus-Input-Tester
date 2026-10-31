package com.serhii.rcplustester;

import android.content.Context;
import android.content.SharedPreferences;

/** Simulator-only settings. No MAVLink, radio or aircraft-control outputs. */
public final class BridgeConfig {
    public static final String TARGET_DEFAULT = "com.dji.virtual_flight";
    public static final String PREFS = "virtual_flight_bridge";
    private BridgeConfig() {}
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static boolean armed(Context c) { return prefs(c).getBoolean("armed", false); }
    public static void setArmed(Context c, boolean armed) { prefs(c).edit().putBoolean("armed", armed).apply(); }
    public static String target(Context c) {
        // Never pair the Android launcher or settings as a simulator.
        // The installed DJI Virtual Flight package was confirmed as com.dji.virtual_flight.
        String configured = prefs(c).getString("target", TARGET_DEFAULT).trim();
        return TARGET_DEFAULT.equals(configured) ? configured : TARGET_DEFAULT;
    }
    public static int number(Context c, String key, int fallback) {
        return prefs(c).getInt(key, fallback);
    }
    public static float scale(Context c) { return Math.max(1f, number(c, "raw_max", 660)); }
    public static boolean invert(Context c, String axis) {
        return prefs(c).getBoolean("invert_" + axis, false);
    }
    public static float norm(float input, float max, boolean invert) {
        float n = input / max;
        return Math.max(-1f, Math.min(1f, invert ? -n : n));
    }
}
