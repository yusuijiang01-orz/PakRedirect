package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** One membership gate shared by localization and the script workbench. */
public final class MembershipAccess {
    private static final String PREFS = "rylux_membership_access_v1";
    private static final String ACTIVE = "active";
    private static final String EXPIRES_AT = "expires_at";

    private MembershipAccess() {}

    public interface Callback { void onResult(CheckResult result); }

    public static final class CheckResult {
        public final boolean requestOk;
        public final boolean success;
        public final boolean allowed;
        public final AuthClient.ProfileResult profile;
        public final String message;

        private CheckResult(boolean requestOk, boolean success, boolean allowed,
                            AuthClient.ProfileResult profile, String message) {
            this.requestOk = requestOk;
            this.success = success;
            this.allowed = allowed;
            this.profile = profile;
            this.message = message;
        }
    }

    /** Membership is valid only when the server marks it active and its expiry is still future. */
    public static boolean isAllowed(AuthClient.ProfileResult profile) {
        String kind = profile == null || profile.membershipKind == null
                ? "" : profile.membershipKind.trim();
        boolean vipOrTrial = "vip".equalsIgnoreCase(kind) || "trial".equalsIgnoreCase(kind);
        return profile != null && profile.requestOk && profile.success
                && profile.membershipActive && vipOrTrial
                && expiryMillis(profile.expiresAt) > System.currentTimeMillis();
    }

    /** Checks the last server-confirmed entitlement and enforces its recorded expiry locally. */
    public static boolean hasCachedAccess(Context context) {
        SharedPreferences prefs = preferences(context);
        return prefs.getBoolean(ACTIVE, false)
                && expiryMillis(prefs.getString(EXPIRES_AT, null)) > System.currentTimeMillis();
    }

    public static void remember(Context context, AuthClient.ProfileResult profile) {
        if (profile == null || !profile.requestOk || !profile.success) return;
        preferences(context).edit()
                .putBoolean(ACTIVE, isAllowed(profile))
                .putString(EXPIRES_AT, profile.expiresAt == null ? "" : profile.expiresAt)
                .apply();
    }

    public static void clear(Context context) { preferences(context).edit().clear().apply(); }

    /** Rechecks the authoritative /me response off the UI thread. Failures deny the current action. */
    public static void refresh(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            CheckResult result = refreshNow(app);
            if (callback != null) callback.onResult(result);
        }, "RYLUX-Membership-Check").start();
    }

    /** Call only from a worker thread. */
    public static CheckResult refreshNow(Context context) {
        String token = new AuthStorage(context).loadToken();
        if (token == null || token.trim().isEmpty()) {
            return new CheckResult(false, false, false, null, "请先登录 RYLUX 账号");
        }
        AuthClient.ProfileResult profile = AuthClient.me(token);
        if (!profile.requestOk) {
            return new CheckResult(false, false, false, profile, "无法验证会员状态，请检查网络后重试");
        }
        if (!profile.success) {
            clear(context);
            return new CheckResult(true, false, false, profile,
                    profile.message == null || profile.message.isEmpty() ? "账号状态无效，请重新登录" : profile.message);
        }
        remember(context, profile);
        boolean allowed = isAllowed(profile);
        return new CheckResult(true, true, allowed, profile,
                allowed ? "会员权限有效" : "此功能需要有效的 VIP 会员或体验会员，当前会员已到期");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long expiryMillis(String text) {
        if (text == null || text.trim().isEmpty()) return 0L;
        String value = text.trim();
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
        catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) { return 0L; }
    }
}
