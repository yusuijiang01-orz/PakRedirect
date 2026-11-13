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
        if (!isValid(config)) return null;
        return migrateImplicitDesktopDefault(context, config);
    }

    /**
     * Older RYLUX builds opened the workbench from MainActivity with desktop
     * preselected. If the selected profile never explicitly saved a target,
     * that persisted desktop mode was only an entry-point default, not a user
     * choice. Migrate that legacy config to the RYLUX sandbox default.
     */
    private static String migrateImplicitDesktopDefault(Context context, String config) {
        try {
            JSONObject run = new JSONObject(config);
            if (!"desktop".equals(run.optString("目标运行环境", ""))) return config;

            android.content.SharedPreferences profiles = context.getSharedPreferences(
                    "script_workbench_profiles_v1", Context.MODE_PRIVATE);
            String selected = profiles.getString("selected", "默认方案");
            JSONObject profile = new JSONObject(profiles.getString("profile:" + selected, "{}"));
            if (profile.has("目标运行环境")) return config;

            run.put("目标运行环境", "sandbox");
            String migrated = run.toString();
            save(context, migrated);
            return migrated;
        } catch (Exception invalidProfile) {
            return config;
        }
    }

    private static boolean isValid(String config) {
        if (config == null || config.isEmpty()) return false;
        try { new JSONObject(config); return true; }
        catch (JSONException invalid) { return false; }
    }
}
