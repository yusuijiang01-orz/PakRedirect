package com.example.pakredirect;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;

/** Local ADB-forwardable JSON-lines bridge to the active Lua route worker. */
final class ScriptWebRouteBridge implements AutoCloseable {
    static final String SOCKET_NAME = "rylux_script_route_v1";
    private static final int MAX_LINE_BYTES = 16 * 1024;
    private final ScriptWebRouteQueue queue = new ScriptWebRouteQueue();
    private final ScriptWebProfileQueue profileQueue = new ScriptWebProfileQueue();
    private volatile LocalServerSocket server;
    private volatile Thread thread;

    synchronized void start() throws IOException {
        if (server != null) return;
        LocalServerSocket opened = new LocalServerSocket(SOCKET_NAME);
        server = opened;
        Thread worker = new Thread(() -> serve(opened), "rylux-route-local-socket");
        worker.setDaemon(true);
        thread = worker;
        worker.start();
    }

    ScriptWebRouteQueue.Request poll() { return queue.poll(); }
    void update(String requestId, String state, String message) { queue.update(requestId, state, message); }
    ScriptWebRouteQueue.Request get(String requestId) { return queue.get(requestId); }
    ScriptWebProfileQueue.Request pollProfile() { return profileQueue.poll(); }
    void updateProfile(String requestId, String state, String message) {
        profileQueue.update(requestId, state, message);
    }
    ScriptWebProfileQueue.Request getProfile(String requestId) { return profileQueue.get(requestId); }

    private void serve(LocalServerSocket active) {
        while (server == active) {
            LocalSocket client = null;
            try {
                client = active.accept();
                client.setSoTimeout(5_000);
                handle(client);
            } catch (IOException closed) {
                if (server == active) android.util.Log.w("ScriptWebRoute", "Route socket request failed", closed);
            } finally {
                if (client != null) try { client.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void handle(LocalSocket client) throws IOException {
        String line = readBoundedLine(client.getInputStream());
        JSONObject response;
        try {
            JSONObject input = new JSONObject(line);
            String action = input.optString("action", "submit");
            if ("submit".equals(action)) response = submit(input);
            else if ("status".equals(action)) response = status(input.optString("requestId", ""));
            else if ("apply_profile".equals(action)) response = applyProfile(input);
            else if ("profile_status".equals(action)) response = profileStatus(input.optString("requestId", ""));
            else response = error("不支持的路线桥接操作");
        } catch (Exception invalid) {
            response = error(invalid.getMessage() == null ? "路线请求格式无效" : invalid.getMessage());
        }
        OutputStream output = client.getOutputStream();
        output.write((response.toString() + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private JSONObject submit(JSONObject input) throws Exception {
        String requestId = input.optString("requestId", "");
        String mapName = input.optString("mapName", "");
        int x = exactInteger(input, "x");
        int y = exactInteger(input, "y");
        ScriptWebRouteQueue.Request request = queue.submit(requestId, mapName, x, y);
        return serialize(request);
    }

    private JSONObject status(String requestId) throws Exception {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")) {
            return error("requestId 格式无效");
        }
        ScriptWebRouteQueue.Request request = queue.get(requestId);
        return request == null ? error("未找到该路线请求") : serialize(request);
    }

    private JSONObject applyProfile(JSONObject input) throws Exception {
        String requestId = input.optString("requestId", "");
        JSONObject source = input.optJSONObject("values");
        if (source == null) throw new IllegalArgumentException("网页方案格式无效");
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = source.get(key);
            if (value == JSONObject.NULL) throw new IllegalArgumentException("方案字段不能为空：" + key);
            if (value instanceof JSONObject || value instanceof org.json.JSONArray) {
                throw new IllegalArgumentException("方案字段类型无效：" + key);
            }
            values.put(key, String.valueOf(value));
        }
        return serializeProfile(profileQueue.submit(requestId, values));
    }

    private JSONObject profileStatus(String requestId) throws Exception {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")) {
            return error("requestId 格式无效");
        }
        ScriptWebProfileQueue.Request request = profileQueue.get(requestId);
        return request == null ? error("未找到该网页方案请求") : serializeProfile(request);
    }

    private static JSONObject serializeProfile(ScriptWebProfileQueue.Request request) throws Exception {
        return new JSONObject().put("requestId", request.requestId).put("state", request.state)
                .put("message", request.message);
    }

    private static int exactInteger(JSONObject input, String key) throws Exception {
        Object value = input.get(key);
        if (!(value instanceof Number)) throw new IllegalArgumentException(key + " 必须是整数");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        return (int) number;
    }

    private static JSONObject serialize(ScriptWebRouteQueue.Request request) throws Exception {
        return new JSONObject().put("requestId", request.requestId).put("mapName", request.mapName)
                .put("x", request.x).put("y", request.y).put("state", request.state)
                .put("message", request.message);
    }

    private static JSONObject error(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("state", ScriptWebRouteQueue.FAILED);
            result.put("message", message == null ? "路线桥接失败" : message);
        } catch (org.json.JSONException ignored) { }
        return result;
    }

    private static String readBoundedLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i <= MAX_LINE_BYTES; i++) {
            int value = input.read();
            if (value < 0) break;
            if (value == '\n') return bytes.toString(StandardCharsets.UTF_8.name()).trim();
            if (value != '\r') bytes.write(value);
            if (bytes.size() > MAX_LINE_BYTES) throw new IOException("路线请求超过大小限制");
        }
        throw new IOException("路线请求未以换行符结束");
    }

    private void serveErrorToQueued(String message) { queue.close(message); }

    @Override public synchronized void close() {
        LocalServerSocket closing = server;
        server = null;
        if (closing != null) try { closing.close(); } catch (IOException ignored) { }
        serveErrorToQueued("Lua 脚本已停止，路线请求未完成");
        profileQueue.close("Lua 脚本已停止，方案未应用");
        thread = null;
    }
}
