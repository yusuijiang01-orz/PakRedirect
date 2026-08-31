package com.example.pakredirect;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AuthClient {
    private static final String API = "https://verify.lovenom.eu.org/api/v1";

    private AuthClient() {}

    public static AuthResult register(String username, String password, String deviceId) {
        try {
            JSONObject body = new JSONObject();
            body.put("username", username == null ? "" : username.trim());
            body.put("password", password == null ? "" : password);
            body.put("device_id", deviceId == null ? "" : deviceId);
            HttpResult http = request("POST", "/auth/register", null, body);
            if (!http.requestOk) return AuthResult.networkError(http.message);
            if (!http.success) return AuthResult.failure(http.message);
            return parseAuth(http.json, "注册成功");
        } catch (Throwable t) {
            return AuthResult.networkError("网络请求失败");
        }
    }

    public static AuthResult login(String username, String password, String deviceId) {
        try {
            JSONObject body = new JSONObject();
            body.put("username", username == null ? "" : username.trim());
            body.put("password", password == null ? "" : password);
            body.put("device_id", deviceId == null ? "" : deviceId);
            HttpResult http = request("POST", "/auth/login", null, body);
            if (!http.requestOk) return AuthResult.networkError(http.message);
            if (!http.success) return AuthResult.failure(http.message);
            return parseAuth(http.json, "登录成功");
        } catch (Throwable t) {
            return AuthResult.networkError("网络请求失败");
        }
    }

    public static ProfileResult me(String token) {
        HttpResult http = request("GET", "/me", token, null);
        if (!http.requestOk) return ProfileResult.networkError(http.message);
        if (!http.success) return ProfileResult.failure(http.message);
        try {
            JSONObject user = http.json.optJSONObject("user");
            if (user == null) return ProfileResult.failure("用户数据异常");
            JSONObject membership = user.optJSONObject("membership");
            return new ProfileResult(
                    true,
                    true,
                    user.optString("username", ""),
                    membership != null && membership.optBoolean("active", false),
                    membership == null ? "expired" : membership.optString("kind", "expired"),
                    membership == null ? null : nullable(membership.optString("expires_at", null)),
                    ""
            );
        } catch (Throwable t) {
            return ProfileResult.failure("用户数据解析失败");
        }
    }

    public static ActionResult redeem(String token, String code) {
        try {
            JSONObject body = new JSONObject();
            body.put("code", code == null ? "" : code.trim());
            HttpResult http = request("POST", "/redeem", token, body);
            if (!http.requestOk) return ActionResult.networkError(http.message);
            if (!http.success) return ActionResult.failure(http.message);
            return new ActionResult(
                    true,
                    true,
                    nullable(http.json.optString("expires_at", null)),
                    http.json.optString("message", "兑换成功")
            );
        } catch (Throwable t) {
            return ActionResult.networkError("兑换请求失败");
        }
    }

    public static ActionResult authorize(String token, String moduleCode) {
        HttpResult http = request("POST", "/modules/" + moduleCode + "/authorize", token, new JSONObject());
        if (!http.requestOk) return ActionResult.networkError(http.message);
        if (!http.success) return ActionResult.failure(http.message);
        return new ActionResult(
                true,
                http.json.optBoolean("allowed", false),
                nullable(http.json.optString("expires_at", null)),
                http.json.optBoolean("allowed", false) ? "授权成功" : "当前账号无权限"
        );
    }

    public static void logout(String token) {
        request("POST", "/auth/logout", token, new JSONObject());
    }

    private static AuthResult parseAuth(JSONObject json, String fallbackMessage) {
        String token = nullable(json.optString("token", null));
        JSONObject user = json.optJSONObject("user");
        if (token == null || user == null) return AuthResult.failure("登录数据异常");
        JSONObject membership = user.optJSONObject("membership");
        boolean active = membership != null && membership.optBoolean("active", false);
        String kind = membership == null ? "expired" : membership.optString("kind", "expired");
        String expiresAt = membership == null ? null : nullable(membership.optString("expires_at", null));
        return new AuthResult(
                true,
                true,
                token,
                user.optString("username", ""),
                active,
                kind,
                expiresAt,
                json.optString("message", fallbackMessage)
        );
    }

    private static HttpResult request(String method, String path, String token, JSONObject body) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(API + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RYLUX/2.0");
            if (token != null && !token.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }

            if (body != null && !"GET".equals(method)) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(data);
                }
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = readAll(stream);
            JSONObject json;
            try {
                json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (Throwable ignored) {
                json = new JSONObject();
            }

            if (code < 200 || code >= 300) {
                String message = json.optString("detail", "请求失败 HTTP " + code);
                return new HttpResult(true, false, json, message, code);
            }
            return new HttpResult(true, true, json, "", code);
        } catch (Throwable t) {
            return new HttpResult(false, false, new JSONObject(), "网络连接失败", 0);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String nullable(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value)) return null;
        return value;
    }

    private static final class HttpResult {
        final boolean requestOk;
        final boolean success;
        final JSONObject json;
        final String message;
        final int statusCode;

        HttpResult(boolean requestOk, boolean success, JSONObject json, String message, int statusCode) {
            this.requestOk = requestOk;
            this.success = success;
            this.json = json;
            this.message = message;
            this.statusCode = statusCode;
        }
    }

    public static final class AuthResult {
        public final boolean requestOk;
        public final boolean success;
        public final String token;
        public final String username;
        public final boolean membershipActive;
        public final String membershipKind;
        public final String expiresAt;
        public final String message;

        AuthResult(boolean requestOk, boolean success, String token, String username,
                   boolean membershipActive, String membershipKind, String expiresAt, String message) {
            this.requestOk = requestOk;
            this.success = success;
            this.token = token;
            this.username = username;
            this.membershipActive = membershipActive;
            this.membershipKind = membershipKind;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        static AuthResult networkError(String message) {
            return new AuthResult(false, false, null, "", false, "expired", null, message);
        }

        static AuthResult failure(String message) {
            return new AuthResult(true, false, null, "", false, "expired", null, message);
        }
    }

    public static final class ProfileResult {
        public final boolean requestOk;
        public final boolean success;
        public final String username;
        public final boolean membershipActive;
        public final String membershipKind;
        public final String expiresAt;
        public final String message;

        ProfileResult(boolean requestOk, boolean success, String username, boolean membershipActive,
                      String membershipKind, String expiresAt, String message) {
            this.requestOk = requestOk;
            this.success = success;
            this.username = username;
            this.membershipActive = membershipActive;
            this.membershipKind = membershipKind;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        static ProfileResult networkError(String message) {
            return new ProfileResult(false, false, "", false, "expired", null, message);
        }

        static ProfileResult failure(String message) {
            return new ProfileResult(true, false, "", false, "expired", null, message);
        }
    }

    public static final class ActionResult {
        public final boolean requestOk;
        public final boolean success;
        public final String expiresAt;
        public final String message;

        ActionResult(boolean requestOk, boolean success, String expiresAt, String message) {
            this.requestOk = requestOk;
            this.success = success;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        static ActionResult networkError(String message) {
            return new ActionResult(false, false, null, message);
        }

        static ActionResult failure(String message) {
            return new ActionResult(true, false, null, message);
        }
    }
}
