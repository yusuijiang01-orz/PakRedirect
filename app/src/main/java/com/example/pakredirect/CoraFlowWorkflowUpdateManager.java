package com.example.pakredirect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
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
    private static final String VERSION_PREFS = "rylux-coraflow-version";

    private static final class RemoteManifest {
        final String text;
        final String source;
        final JSONObject payload;
        final long revision;

        RemoteManifest(String text, String source, JSONObject payload, long revision) {
            this.text = text;
            this.source = source;
            this.payload = payload;
            this.revision = revision;
        }
    }

    private CoraFlowWorkflowUpdateManager() { }

    // The same verified/decrypted package is held briefly between the workbench
    // preflight and the foreground runtime service. This avoids downloading
    // and decrypting a second time after the game has already been launched.
    private static CoraFlowWorkflowRuntime.WorkflowPackage preparedPackage;
    private static String preparedPanelValues;
    private static long preparedAtMillis;
    private static final long PREPARED_TTL_MILLIS = 10L * 60L * 1000L;

    public static synchronized String prepareForLaunch(Context context, JSONObject panelValues,
                                                        Consumer<String> status) throws Exception {
        discardPrepared();
        CoraFlowWorkflowRuntime.WorkflowPackage loaded = loadFresh(context, panelValues, status);
        preparedPackage = loaded;
        preparedPanelValues = panelKey(panelValues);
        preparedAtMillis = System.currentTimeMillis();
        return loaded.versionSummary();
    }

    public static synchronized boolean isPreparedForLaunch(JSONObject panelValues) {
        if (preparedPackage == null) return false;
        if (System.currentTimeMillis() - preparedAtMillis > PREPARED_TTL_MILLIS) {
            discardPrepared();
            return false;
        }
        return preparedPanelValues != null && preparedPanelValues.equals(panelKey(panelValues));
    }

    public static synchronized void discardPrepared() {
        if (preparedPackage != null) {
            preparedPackage.close();
            preparedPackage = null;
        }
        preparedPanelValues = null;
        preparedAtMillis = 0L;
    }

    public static synchronized CoraFlowWorkflowRuntime.WorkflowPackage load(
            Context context, JSONObject panelValues, Consumer<String> status) throws Exception {
        if (isPreparedForLaunch(panelValues)) {
            CoraFlowWorkflowRuntime.WorkflowPackage ready = preparedPackage;
            preparedPackage = null;
            preparedPanelValues = null;
            preparedAtMillis = 0L;
            notify(status, "使用启动前已完成验签、解密和版本核验的工作流 " + ready.versionSummary());
            return ready;
        }
        discardPrepared();
        return loadFresh(context, panelValues, status);
    }

    private static String panelKey(JSONObject values) {
        return values == null ? "{}" : values.toString();
    }

    private static CoraFlowWorkflowRuntime.WorkflowPackage loadFresh(
            Context context, JSONObject panelValues, Consumer<String> status) throws Exception {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), ROOT);
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建 CoraFlow 工作流缓存目录");
        File cachedManifest = new File(root, MANIFEST);
        File cachedBundle = new File(root, BUNDLE);
        String token = new AuthStorage(app).loadToken();
        JSONObject payload = null;
        byte[] encrypted = null;
        RemoteManifest newestRemote = null;
        long highestRevision = app.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
                .getLong("highest_revision", 0L);

        notify(status, "正在检查加密工作流更新");
        try {
            newestRemote = readNewestManifest(app, token, status);
            payload = newestRemote.payload;
            String bundleName = payload.optString("bundle", "").trim();
            if (!BUNDLE.equals(bundleName)) throw new IllegalStateException("CoraFlow 清单指定了未知工作流文件");
            encrypted = downloadVerified(bundleName, payload.optLong("size", -1L),
                    payload.optString("sha256", ""));
            writeAtomic(cachedBundle, encrypted);
            writeAtomic(cachedManifest, newestRemote.text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception remoteFailure) {
            if (!cachedManifest.isFile() || !cachedBundle.isFile()) {
                throw new IllegalStateException("CoraFlow 加密工作流下载失败，且没有已验证的离线缓存", remoteFailure);
            }
            String manifestText = readText(cachedManifest, MAX_MANIFEST_BYTES);
            JSONObject cachedPayload = ProtectedContentManager.verifyWorkflowManifest(app, token, manifestText);
            long cachedRevision = workflowRevision(cachedPayload);
            long requiredRevision = Math.max(highestRevision,
                    newestRemote == null ? 0L : newestRemote.revision);
            if (cachedRevision < requiredRevision) {
                String requiredVersion = newestRemote == null
                        ? app.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE)
                                .getString("highest_version", "较新工作流")
                        : newestRemote.payload.optString("version", "较新工作流");
                throw new IllegalStateException("在线已发现工作流 " + requiredVersion
                        + "（修订 " + requiredRevision + "），但对应加密包暂不可用；缓存只有修订 "
                        + cachedRevision + "，已拒绝静默运行旧版。请重试更新。", remoteFailure);
            }
            String sha = cachedPayload.optString("sha256", "").trim().toLowerCase(Locale.US);
            payload = cachedPayload;
            long size = payload.optLong("size", -1L);
            if (size <= 0 || size > MAX_ENCRYPTED_BYTES || cachedBundle.length() != size
                    || !sha256(cachedBundle).equals(sha)) {
                throw new IllegalStateException("CoraFlow 离线工作流缓存校验失败", remoteFailure);
            }
            notify(status, "网络暂不可用；仅使用已验证缓存 "
                    + payload.optString("version", "未标注") + "（修订 " + cachedRevision + "）");
            encrypted = readBytes(cachedBundle, MAX_ENCRYPTED_BYTES);
        }

        if (payload == null || encrypted == null) throw new IllegalStateException("CoraFlow 工作流未就绪");
        notify(status, "正在验证授权并解密 CoraFlow 工作流");
        byte[] plain = ProtectedContentManager.decryptWorkflowBundle(app, token, cachedBundle, payload);
        try {
            notify(status, "正在载入工作流节点与素材");
            long loadedRevision = workflowRevision(payload);
            CoraFlowWorkflowRuntime.WorkflowPackage loaded = unpack(app, plain, panelValues,
                    payload.optString("version", "未标注"), loadedRevision);
            app.getSharedPreferences(VERSION_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("highest_revision", Math.max(highestRevision, loadedRevision))
                    .putString("highest_version", payload.optString("version", "未标注"))
                    .apply();
            notify(status, "已载入并核验工作流版本：" + loaded.versionSummary());
            return loaded;
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    private static CoraFlowWorkflowRuntime.WorkflowPackage unpack(Context context, byte[] plain,
                                                                  JSONObject panelValues,
                                                                  String releaseVersion,
                                                                  long revision) throws Exception {
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
            recycleImages(images);
            throw new IllegalStateException("CoraFlow 工作流图或面板绑定版本不支持");
        }
        final WorkflowVersionValidator.Info versionInfo;
        try {
            versionInfo = WorkflowVersionValidator.validate(releaseVersion, workflow);
        } catch (RuntimeException invalidVersion) {
            recycleImages(images);
            throw invalidVersion;
        }
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_大夫商店标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_背包标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_医生出售按钮.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_出售确认标题.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_挂机_出售确认按钮.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_背包_物品详情左上角_1920.png");
        addBuiltinTemplate(context, images, "captures/templates/RYLUX_背包_空格子.png");
        try {
            validateWorkflowImages(workflow, images.keySet());
        } catch (RuntimeException invalidAssets) {
            recycleImages(images);
            throw invalidAssets;
        }
        return new CoraFlowWorkflowRuntime.WorkflowPackage(workflow, bindings, images,
                panelValues == null ? new JSONObject() : new JSONObject(panelValues.toString()),
                releaseVersion, versionInfo.embeddedVersion, versionInfo.workflowName, revision);
    }

    private static void recycleImages(Map<String, Bitmap> images) {
        for (Bitmap bitmap : images.values()) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
        images.clear();
    }

    static void validateWorkflowImages(JSONObject workflow, Set<String> availableImages) {
        JSONArray nodes = workflow == null ? null : workflow.optJSONArray("nodes");
        if (nodes == null) throw new IllegalStateException("CoraFlow 工作流缺少节点，无法预检识别素材");
        Set<String> missingPaths = new java.util.TreeSet<>();
        java.util.List<String> missingConfig = new java.util.ArrayList<>();
        Set<String> available = availableImages == null ? java.util.Collections.emptySet() : availableImages;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String id = node.optString("id", "?");
            String type = node.optString("type", "");
            JSONObject data = node.optJSONObject("data");
            if (type.trim().isEmpty() && data != null) type = data.optString("type", "");
            String label = data == null ? type : data.optString("name", type);
            JSONObject config = data == null ? null : data.optJSONObject("config");
            if (config == null) config = new JSONObject();

            boolean directImageNode = "is_image_exist".equals(type) || "click_if_image_exist".equals(type);
            if (directImageNode && config.optString("image", "").trim().isEmpty()) {
                missingConfig.add(id + "（" + label + "）：未配置 image 素材路径");
            }
            if ("recognize_number".equals(type)
                    && !config.optString("item_count_mode", "").trim().isEmpty()
                    && config.optString("item_image", "").trim().isEmpty()) {
                missingConfig.add(id + "（" + label + "）：图标计数节点未配置 item_image 素材路径");
            }
            if ("sell_inventory_equipment".equals(type)
                    && (config.optBoolean("sell_white", false) || config.optBoolean("sell_blue", false)
                    || config.optBoolean("emergency_discard_white", false))) {
                if (config.optString("medicine_red_image", "").trim().isEmpty()) {
                    missingConfig.add(id + "（" + label + "）：装备处理节点未配置红药保护图标");
                }
                if (config.optString("medicine_blue_image", "").trim().isEmpty()) {
                    missingConfig.add(id + "（" + label + "）：装备处理节点未配置蓝药保护图标");
                }
            }

            for (String field : new String[]{"image", "item_image", "medicine_red_image", "medicine_blue_image"}) {
                String path = config.optString(field, "").trim();
                if (!path.isEmpty() && !available.contains(path)) missingPaths.add(path);
            }
        }
        if (!missingConfig.isEmpty() || !missingPaths.isEmpty()) {
            java.util.List<String> problems = new java.util.ArrayList<>(missingConfig);
            for (String path : missingPaths) problems.add("工作流包中缺少素材 " + path);
            throw new IllegalStateException("CoraFlow 工作流素材预检失败；尚未发送游戏触控："
                    + String.join("；", problems));
        }
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

    private static RemoteManifest readNewestManifest(Context context, String token,
                                                       Consumer<String> status) throws Exception {
        RemoteManifest newest = null;
        Exception last = null;
        String mirrorConflict = null;
        int valid = 0;
        for (String candidate : CnDownloadRouter.workflowRepoFileUrls(MANIFEST)) {
            HttpURLConnection connection = null;
            try {
                connection = open(candidate);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("工作流清单服务器返回 HTTP " + connection.getResponseCode());
                }
                long declared = connection.getContentLengthLong();
                if (declared > MAX_MANIFEST_BYTES) throw new IllegalStateException("工作流清单超过大小限制");
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if ((long) output.size() + count > MAX_MANIFEST_BYTES) throw new IllegalStateException("工作流清单超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    JSONObject payload = ProtectedContentManager.verifyWorkflowManifest(context, token, text);
                    long revision = workflowRevision(payload);
                    valid++;
                    if (newest == null || revision > newest.revision) {
                        newest = new RemoteManifest(text, candidate, payload, revision);
                    } else if (revision == newest.revision
                            && !payload.optString("sha256", "").equalsIgnoreCase(
                                    newest.payload.optString("sha256", ""))) {
                        mirrorConflict = "同一工作流修订号对应不同加密包；拒绝加载不一致镜像";
                    }
                }
            } catch (Exception failure) {
                last = failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        if (mirrorConflict != null) throw new IllegalStateException(mirrorConflict, last);
        if (newest == null) throw last == null ? new IllegalStateException("工作流清单下载失败") : last;
        notify(status, "已核对 " + valid + " 个签名工作流镜像，采用 "
                + newest.payload.optString("version", "未标注") + "（修订 " + newest.revision + "）");
        return newest;
    }

    private static long workflowRevision(JSONObject payload) {
        JSONObject encryption = payload == null ? null : payload.optJSONObject("encryption");
        long revision = encryption == null ? 0L : encryption.optLong("revision", 0L);
        if (revision <= 0L) throw new IllegalStateException("CoraFlow 工作流清单缺少有效修订号");
        return revision;
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
