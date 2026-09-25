package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's localization choice; changing it never performs network or file work. */
public final class LocalizationSettings {
    private static final String PREFS = "rylux_localization_settings";
    private static final String KEY_ENABLED = "localization_enabled";

    private LocalizationSettings() {}

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
