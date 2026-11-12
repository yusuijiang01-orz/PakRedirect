package com.example.pakredirect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Fetches, verifies, decrypts and loads the signed CoraFlow workflow release. */
public final class CoraFlowWorkflowUpdateManager {
    private static final String ROOT = "rylux-coraflow";
    private static final String MANIFEST = "manifest.json";
    private static final String BUNDLE = "workflow.rpe";
    private static final long MAX_MANIFEST_BYTES = 256 * 1024L;
    private static final long MAX_ENCRYPTED_BYTES = 24L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 12L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 128;

    private CoraFlowWorkflowUpdateManager() { }

    public static synchronized CoraFlowWorkflowRuntime.WorkflowPackage load(
            Context context, JSONObject panelValues, Consumer<String> status) throws Exception {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), ROOT);
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建 CoraFlow 工作流缓存目录");
        File cachedManifest = new File(root, MANIFEST);
        File cachedBundle = new File(root, BUNDLE);
        String token = new AuthStorage(app).loadToken();
        JSONObject payload = null;
        byte[] encrypted = null;

        notify(status, "正在检查加密工作流更新");
        try {
            String manifestText = new String(readRemote(MANIFEST, MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
            payload = ProtectedContentManager.verifyWorkflowManifest(app, token, manifestText);
            String bundleName = payload.optString("bundle", "").trim();
            if (!BUNDLE.equals(bundleName)) throw new IllegalStateException("CoraFlow 清单指定了未知工作流文件");
            encrypted = downloadVerified(bundleName, payload.optLong("size", -1L),
                    payload.optString("sha256", ""));
            writeAtomic(cachedBundle, encrypted);
            writeAtomic(cachedManifest, manifestText.getBytes(StandardCharsets.UTF_8));
        } catch (Exception remoteFailure) {
            if (!cachedManifest.isFile() || !cachedBundle.isFile()) {
                throw new IllegalStateException("CoraFlow 加密工作流下载失败，且没有已验证的离线缓存", remoteFailure);
            }
            notify(status, "网络不可用，尝试使用已缓存的加密工作流");
            String manifestText = readText(cachedManifest, MAX_MANIFEST_BYTES);
            payload = ProtectedContentManager.verifyWorkflowManifest(app, token, manifestText);
            long size = payload.optLong("size", -1L);
            String sha = payload.optString("sha256", "").trim().toLowerCase(Locale.US);
            if (size <= 0 || size > MAX_ENCRYPTED_BYTES || cachedBundle.length() != size
                    || !sha256(cachedBundle).equals(sha)) {
                throw new IllegalStateException("CoraFlow 离线工作流缓存校验失败", remoteFailure);
            }
            encrypted = readBytes(cachedBundle, MAX_ENCRYPTED_BYTES);
        }

        if (payload == null || encrypted == null) throw new IllegalStateException("CoraFlow 工作流未就绪");
        notify(status, "正在验证授权并解密 CoraFlow 工作流");
        byte[] plain = ProtectedContentManager.decryptWorkflowBundle(app, token, cachedBundle, payload);
        try {
            notify(status, "正在载入工作流节点与素材");
            return unpack(app, plain, panelValues);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static CoraFlowWorkflowRuntime.WorkflowPackage unpack(Context context, byte[] plain, JSONObject panelValues) throws Exception {
        JSONObject workflow = null;
        JSONObject bindings = null;
        Map<String, Bitmap> images = new HashMap<>();
        Set<String> names = new HashSet<>();
        long expanded = 0L;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(plain))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalStateException("CoraFlow 工作流素材数量超过限制");
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) continue;
                if (!safePath(name) || !names.add(name)) throw new IllegalStateException("CoraFlow 工作流包含非法或重复路径");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int count;
                while ((count = input.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IllegalStateException("CoraFlow 工作流解压后超过大小限制");
                    bytes.write(buffer, 0, count);
                }
                byte[] content = bytes.toByteArray();
                if ("workflow.json".equals(name)) {
                    workflow = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if ("workflow_bindings.json".equals(name)) {
                    bindings = new JSONObject(new String(content, StandardCharsets.UTF_8));
                } else if (isImage(name)) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(content, 0, content.length);
                    if (bitmap == null || bitmap.isRecycled()) throw new IllegalStateException("无法解码工作流图片素材：" + name);
                    images.put(name, bitmap);
                }
                java.util.Arrays.fill(content, (byte) 0);
                input.closeEntry();
            }
        }
        if (workflow == null || bindings == null) throw new IllegalStateException("CoraFlow 工作流包缺少节点 JSON 或面板绑定表");
        if (workflow.optJSONArray("nodes") == null || workflow.optJSONArray("edges") == null
                || bindings.optInt("schema", 0) != 1) {
            throw new IllegalStateException("CoraFlow 工作流图或面板绑定版本不支持");
        }
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_大夫商店标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_背包标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_医生出售按钮.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_出售确认标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_出售确认按钮.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_背包_物品详情左上角_1920.png");
        return new CoraFlowWorkflowRuntime.WorkflowPackage(workflow, bindings, images,
                panelValues == null ? new JSONObject() : new JSONObject(panelValues.toString()));
    }

    private static void addBuiltinTemplate(Context context, Map<String, Bitmap> images, String workflowPath) throws Exception {
        if (images.containsKey(workflowPath)) return;
        String assetPath = "coraflow/" + workflowPath;
        try (InputStream input = context.getAssets().open(assetPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            byte[] bytes = output.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            java.util.Arrays.fill(bytes, (byte) 0);
            if (bitmap == null || bitmap.isRecycled()) throw new IllegalStateException("RYLUX 内置工作流素材无法解码：" + workflowPath);
            images.put(workflowPath, bitmap);
        }
    }

    private static boolean safePath(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains(":")) return false;
        for (String segment : name.split("/")) if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        return true;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.startsWith("captures/") && (lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp"));
    }

    private static byte[] readRemote(String name, long maximum) throws Exception {
        Exception last = null;
        for (String candidate : CnDownloadRouter.workflowRepoFileUrls(name)) {
            HttpURLConnection connection = null;
            try {
                connection = open(candidate);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("工作流服务器返回 HTTP " + connection.getResponseCode());
                }
                long declared = connection.getContentLengthLong();
                if (declared > maximum) throw new IllegalStateException("工作流下载超过大小限制");
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if ((long) output.size() + count > maximum) throw new IllegalStateException("工作流下载超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } catch (Exception failure) {
                last = failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("工作流清单下载失败") : last;
    }

    private static byte[] downloadVerified(String name, long expectedSize, String expectedSha) throws Exception {
        if (expectedSize <= 0 || expectedSize > MAX_ENCRYPTED_BYTES
                || expectedSha == null || !expectedSha.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalStateException("CoraFlow 工作流包校验信息无效");
        }
        Exception last = null;
        for (String candidate : CnDownloadRouter.workflowRepoFileUrls(name)) {
            HttpURLConnection connection = null;
            try {
                connection = open(candidate);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("工作流包服务器返回 HTTP " + connection.getResponseCode());
                }
                long declared = connection.getContentLengthLong();
                if (declared >= 0 && declared != expectedSize) throw new IllegalStateException("工作流包大小与清单不一致");
                ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedSize);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > expectedSize || total > MAX_ENCRYPTED_BYTES) throw new IllegalStateException("工作流包超过清单大小");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                    if (total != expectedSize) throw new IllegalStateException("工作流包下载不完整");
                    if (!expectedSha.equalsIgnoreCase(hex(digest.digest()))) throw new IllegalStateException("工作流包 SHA-256 校验失败");
                    return output.toByteArray();
                }
            } catch (Exception failure) {
                last = failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("工作流包下载失败") : last;
    }

    private static HttpURLConnection open(String source) throws Exception {
        String separator = source.contains("?") ? "&" : "?";
        HttpURLConnection connection = (HttpURLConnection) new URL(source + separator + "rylux=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20_000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "RYLUX-CoraFlow/1");
        connection.setRequestProperty("Accept", "application/octet-stream, application/json");
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        return connection;
    }

    private static void writeAtomic(File destination, byte[] bytes) throws Exception {
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) throw new IllegalStateException("无法创建工作流缓存目录");
        File temporary = new File(parent, destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) throw new IllegalStateException("无法更新工作流缓存");
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("无法保存工作流缓存");
        }
    }

    private static String readText(File file, long maximum) throws Exception {
        return new String(readBytes(file, maximum), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(File file, long maximum) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > maximum) throw new IllegalStateException("工作流缓存文件无效");
        try (InputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if ((long) output.size() + count > maximum) throw new IllegalStateException("工作流缓存超过大小限制");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value.toString();
    }

    private static void notify(Consumer<String> status, String message) {
        if (status != null) try { status.accept(message); } catch (RuntimeException ignored) { }
    }
}
