package com.example.pakredirect;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class LicenseClient {
    private static final String VERIFY_URL =
            "https://38.47.107.59/api/v1/license/verify";

    private LicenseClient() {}

    public static VerifyResult verify(String licenseKey) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(VERIFY_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "PakRedirect/2");

            JSONObject request = new JSONObject();
            request.put("license_key", licenseKey == null ? "" : licenseKey.trim());

            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String responseText = readAll(stream);
            if (code < 200 || code >= 300) {
                return VerifyResult.networkError(
                        "验证服务器返回 HTTP " + code
                );
            }

            JSONObject response = new JSONObject(responseText);
            boolean valid = response.optBoolean("valid", false);
            String expiresAt = response.optString("expires_at", null);
            String message = response.optString(
                    "message",
                    valid ? "验证成功" : "卡密无效"
            );
            return new VerifyResult(true, valid, expiresAt, message);
        } catch (Throwable t) {
            return VerifyResult.networkError("网络验证失败");
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
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    public static final class VerifyResult {
        public final boolean requestOk;
        public final boolean valid;
        public final String expiresAt;
        public final String message;

        public VerifyResult(
                boolean requestOk,
                boolean valid,
                String expiresAt,
                String message
        ) {
            this.requestOk = requestOk;
            this.valid = valid;
            this.expiresAt = expiresAt;
            this.message = message;
        }

        public static VerifyResult networkError(String message) {
            return new VerifyResult(false, false, null, message);
        }
    }
}
