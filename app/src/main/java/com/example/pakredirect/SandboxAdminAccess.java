package com.example.pakredirect;

import android.content.Context;

/**
 * Server-confirmed Admin gate for the experimental BlackBox sandbox.
 * Never trust the home badge, stored preferences, VIP status or intent extras.
 * Every privileged entry/action rechecks /api/v1/me; offline and stale sessions fail closed.
 */
public final class SandboxAdminAccess {
    private SandboxAdminAccess() { }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public final boolean allowed;
        public final String message;

        Result(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }
    }

    public static boolean isAdminRole(String role) {
        return role != null && "admin".equalsIgnoreCase(role.trim());
    }

    static boolean isAdminProfile(AuthClient.ProfileResult profile) {
        return profile != null && profile.requestOk && profile.success
                && isAdminRole(profile.role);
    }

    /** Makes a fresh network request. Do not invoke on the main/UI thread. */
    public static Result checkNow(Context context) {
        String token = new AuthStorage(context).loadToken();
        if (token == null || token.trim().isEmpty()) {
            return new Result(false, "请先登录 Admin 账号");
        }
        AuthClient.ProfileResult profile = AuthClient.me(token);
        if (!profile.requestOk) {
            return new Result(false, "无法在线验证 Admin 权限，沙箱已锁定");
        }
        if (!profile.success || !isAdminProfile(profile)) {
            return new Result(false, "BlackBox 沙箱仅限 Admin 管理员使用");
        }
        return new Result(true, "Admin 权限已验证");
    }

    public static void checkAsync(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Result result = checkNow(app);
            if (callback != null) callback.onResult(result);
        }, "RYLUX-Sandbox-Admin-Check").start();
    }

    public static void requireNow(Context context) {
        Result result = checkNow(context);
        if (!result.allowed) throw new SecurityException(result.message);
    }
}
