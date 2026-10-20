package com.example.pakredirect;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

/** The exact scheme accepted in the last successful sandbox launch. */
final class ScriptLastRunConfig {
    private static final String PREFERENCES = "script_last_confirmed_run_v1";
    private static final String CONFIG = "config";

    private ScriptLastRunConfig() { }

    static void save(Context context, String config) {
        if (!isValid(config)) return;
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(CONFIG, config).apply();
    }

    static String load(Context context) {
        String config = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(CONFIG, null);
        return isValid(config) ? config : null;
    }

    private static boolean isValid(String config) {
        if (config == null || config.isEmpty()) return false;
        try { new JSONObject(config); return true; }
        catch (JSONException invalid) { return false; }
    }
}
