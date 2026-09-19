package com.example.pakredirect;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.WeakHashMap;

/** Keeps the game-detail patch date synchronized with the current GitHub patch. */
public final class PatchUpdateInfo {
    private static final String PREFS = "rylux_patch_update_info";
    private static final String KEY_DATE = "latest_patch_date";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long REFRESH_MS = 10L * 60L * 1000L;
    private static final String COMMITS_API =
            "https://api.github.com/repos/yusuijiang01-orz/PakRedirect/commits"
                    + "?path=pak/manifest.json&per_page=1";

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> FETCHING = new WeakHashMap<>();

    private PatchUpdateInfo() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> decorate(activity));
                maybeRefresh(activity);
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> decor.post(() -> decorate(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> decorate(activity));
        }
        maybeRefresh(activity);
    }

    public static void detach(Activity activity) {
        synchronized (LISTENERS) {
            ViewTreeObserver.OnGlobalLayoutListener listener = LISTENERS.remove(activity);
            FETCHING.remove(activity);
            if (listener == null) return;
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        }
    }

    /** Records the newest protected-content revision as an offline fallback. */
    public static void rememberRevision(Context context, long revision) {
        String date = dateFromRevision(revision);
        if (date == null) return;
        saveDate(context, date, System.currentTimeMillis());
    }

    private static void maybeRefresh(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L);
        if (System.currentTimeMillis() - fetchedAt < REFRESH_MS) return;
        synchronized (FETCHING) {
            if (Boolean.TRUE.equals(FETCHING.get(activity))) return;
            FETCHING.put(activity, true);
        }
        new Thread(() -> {
            String date = null;
            try { date = fetchCommitDate(); } catch (Throwable ignored) {}
            if (date == null) {
                try { date = fetchManifestRevisionDate(); } catch (Throwable ignored) {}
            }
            final String result = date;
            if (result != null) saveDate(activity, result, System.currentTimeMillis());
            else activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_FETCHED_AT, System.currentTimeMillis()).apply();
            synchronized (FETCHING) { FETCHING.remove(activity); }
            activity.runOnUiThread(() -> decorate(activity));
        }, "RYLUX-Patch-Date").start();
    }

    private static void decorate(Activity activity) {
        if (activity.isFinishing()) return;
        String date = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATE, "");
        if (date == null || date.trim().isEmpty()) return;
        LinearLayout row = findInfoRow(activity.getWindow().getDecorView());
        if (row == null) return;
        TextView value = secondText(row);
        if (value != null) value.setText(date);
    }

    private static LinearLayout findInfoRow(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout row = (LinearLayout) view;
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (child instanceof TextView) {
                    CharSequence text = ((TextView) child).getText();
                    if (text != null && "最近更新时间".equals(text.toString().trim())) return row;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                LinearLayout found = findInfoRow(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView secondText(LinearLayout row) {
        int seen = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            seen++;
            if (seen == 2) return (TextView) child;
        }
        return null;
    }

    private static String fetchCommitDate() throws Exception {
        for (String url : CnDownloadRouter.githubApiUrls(COMMITS_API)) {
            try {
                String body = getText(url, "application/vnd.github+json", 1024 * 1024);
                JSONArray array = new JSONArray(body);
                if (array.length() == 0) continue;
                JSONObject commit = array.getJSONObject(0).optJSONObject("commit");
                if (commit == null) continue;
                JSONObject committer = commit.optJSONObject("committer");
                String iso = committer == null ? "" : committer.optString("date", "");
                if (iso.length() >= 10) return iso.substring(0, 10);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String fetchManifestRevisionDate() throws Exception {
        for (String url : CnDownloadRouter.publicRepoFileUrls("pak/manifest.json")) {
            try {
                String text = getText(url, "application/json,text/plain,*/*", 1024 * 1024);
                JSONObject outer = new JSONObject(text);
                String payloadB64 = outer.optString("payload_b64", "").trim();
                if (payloadB64.isEmpty()) continue;
                byte[] payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT);
                JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
                JSONArray files = payload.optJSONArray("files");
                if (files == null) continue;
                long latest = 0L;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject item = files.optJSONObject(i);
                    if (item != null) latest = Math.max(latest, item.optLong("revision", 0L));
                }
                String date = dateFromRevision(latest);
                if (date != null) return date;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String getText(String url, String accept, int maxBytes) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "RYLUX/2.3 patch-date");
            connection.setRequestProperty("Cache-Control", "no-cache");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > maxBytes) throw new IllegalStateException("response too large");
                    out.write(buffer, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String dateFromRevision(long revision) {
        if (revision <= 0L) return null;
        String value = String.format(Locale.US, "%d", revision);
        if (value.length() < 8) return null;
        String y = value.substring(0, 4);
        String m = value.substring(4, 6);
        String d = value.substring(6, 8);
        try {
            int year = Integer.parseInt(y);
            int month = Integer.parseInt(m);
            int day = Integer.parseInt(d);
            if (year < 2020 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) return null;
        } catch (Throwable ignored) {
            return null;
        }
        return y + "-" + m + "-" + d;
    }

    private static void saveDate(Context context, String date, long fetchedAt) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATE, date)
                .putLong(KEY_FETCHED_AT, fetchedAt)
                .apply();
    }
}
