package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user choice for the RYLUX per-game VPN relay. */
public final class AccelerationSettings {
    private static final String PREFS = "rylux_acceleration_settings";
    private static final String KEY_ENABLED = "acceleration_enabled";

    private AccelerationSettings() {}

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
