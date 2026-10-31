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
        return prefs(c).getString("target", TARGET_DEFAULT).trim();
    }
    public static int number(Context c, String key, int fallback) {
        return prefs(c).getInt(key, fallback);
    }
    public static float scale(Context c) { return Math.max(100f, number(c, "raw_max", 660)); }
    public static boolean invert(Context c, String axis) {
        return prefs(c).getBoolean("invert_" + axis, false);
    }
    public static float norm(float input, float max, boolean invert) {
        float n = Math.abs(input) <= 1.01f ? input : input / max;
        return Math.max(-1f, Math.min(1f, invert ? -n : n));
    }
}
