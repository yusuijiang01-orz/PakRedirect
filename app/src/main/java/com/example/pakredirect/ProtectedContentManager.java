package com.example.pakredirect;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads authenticated encrypted localization resources and keeps them
 * encrypted at rest. The localhost PAK server decrypts only requested ranges.
 */
public final class ProtectedContentManager {
    public static final String MODULE_CODE = "sg_localization";
    private static final String API = "https://verify.lovenom.eu.org/api/v1";
    private static final String MAGIC = "RYLUXE01";
    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);
    private static final int PREFIX_BYTES = 12;
    private static final int MAX_HEADER_BYTES = 128 * 1024;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final String AUTH_MANIFEST = "manifest.auth.json";
    private static final String LINKSPAK = "linkspak.txt";
    private static final Set<String> PROTECTED_NAMES = new HashSet<>();

    static {
        PROTECTED_NAMES.add("settings.pak");
        PROTECTED_NAMES.add("ui.pak");
        PROTECTED_NAMES.add("updatefs.pak");
    }

    private ProtectedContentManager() {}

    public interface ProgressListener {
        void onProgress(String message, int percent, boolean indeterminate);
    }

    private interface DownloadProgress {
        void onBytes(long bytesWritten);
    }

    public static File moduleDir(Context context) {
        return new File(context.getFilesDir(), "rylux-protected/" + MODULE_CODE);
    }

    public static File linkspakFile(Context context) {
        return new File(moduleDir(context), LINKSPAK);
    }

    public static UpdateResult checkAndApply(
            Context context,
            String token,
            ProgressListener listener
    ) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("登录状态无效");
        }
        notifyProgress(listener, "正在验证加密汉化资源…", -1, true);

        ManifestBundle remote;
        try {
            remote = fetchManifest(token);
        } catch (NetworkException network) {
            ManifestBundle installed = loadInstalledManifest(context);
            if (installed != null && installedFilesPresent(context, installed)) {
                notifyProgress(listener, "内容服务暂不可用，继续使用已验证资源", 100, false);
                return UpdateResult.softFailure(installed.version, "继续使用已验证资源");
            }
            throw new IllegalStateException("无法连接汉化内容服务，请检查网络后重试");
        }

        File dir = moduleDir(context);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建加密资源目录");

        List<ContentSlot> slots = new ArrayList<>();
        long totalDownload = 0L;

        File linkTarget = new File(dir, LINKSPAK);
        if (!matches(linkTarget, remote.linkspak.size, remote.linkspak.sha256)) {
            ContentSlot slot = new ContentSlot(linkTarget);
            slot.downloadName = remote.linkspak.storedName;
            slot.expectedSize = remote.linkspak.size;
            slot.expectedSha = remote.linkspak.sha256;
            slots.add(slot);
            totalDownload += Math.max(0L, slot.expectedSize);
        }

        for (ProtectedEntry entry : remote.entries.values()) {
            File target = new File(dir, entry.encryptedName);
            if (!matches(target, entry.encryptedSize, entry.encryptedSha256)) {
                ContentSlot slot = new ContentSlot(target);
                slot.downloadName = entry.encryptedName;
                slot.expectedSize = entry.encryptedSize;
                slot.expectedSha = entry.encryptedSha256;
                slot.protectedEntry = entry;
                slots.add(slot);
                totalDownload += Math.max(0L, slot.expectedSize);
            }
        }

        final long totalBytes = Math.max(1L, totalDownload);
        long completed = 0L;
        List<ContentSlot> staged = new ArrayList<>();
        try {
            for (ContentSlot slot : slots) {
                deleteIfExists(slot.stage);
                final long base = completed;
                final String label = slot.protectedEntry == null
                        ? LINKSPAK
                        : slot.protectedEntry.name;
                download(
                        token,
                        slot.downloadName,
                        slot.stage,
                        slot.expectedSize,
                        written -> {
                            long overall = Math.min(totalBytes, base + written);
                            int percent = (int) Math.min(96L, (overall * 96L) / totalBytes);
                            notifyProgress(listener, "正在下载 " + label + " · " + percent + "%", percent, false);
                        }
                );
                if (!matches(slot.stage, slot.expectedSize, slot.expectedSha)) {
                    throw new IllegalStateException("加密资源传输校验失败：" + label);
                }
                completed += Math.max(0L, slot.expectedSize);
                if (slot.protectedEntry != null) {
                    notifyProgress(listener, "正在验证并解密校验 " + label + "…", 97, false);
                    verifyEncryptedFile(slot.stage, slot.protectedEntry, remote.contentKey);
                }
                staged.add(slot);
            }

            notifyProgress(listener, "正在安全写入加密资源…", 98, false);
            commitSlots(staged);

            File manifestTarget = new File(dir, AUTH_MANIFEST);
            writeAtomic(manifestTarget, remote.serverResponse.getBytes(StandardCharsets.UTF_8));

            ManifestBundle installed = loadInstalledManifest(context);
            if (installed == null || !installedFilesPresent(context, installed)) {
                throw new IllegalStateException("加密资源写入后校验失败");
            }

            for (ContentSlot slot : staged) {
                slot.committed = false;
                quietDelete(slot.backup);
            }
            removeLegacyPlaintext(context);
        } catch (Throwable t) {
            rollback(staged);
            for (ContentSlot slot : slots) quietDelete(slot.stage);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("加密资源更新失败", t);
        }

        notifyProgress(listener, "加密汉化资源已就绪", 100, false);
        return slots.isEmpty()
                ? UpdateResult.noUpdate(remote.version)
                : UpdateResult.updated(slots.size(), remote.version);
    }

    public static Map<String, ProtectedEntry> entries(Context context) {
        try {
            ManifestBundle installed = loadInstalledManifest(context);
            if (installed == null) return new HashMap<>();
            File dir = moduleDir(context);
            Map<String, ProtectedEntry> result = new HashMap<>();
            for (ProtectedEntry entry : installed.entries.values()) {
                File file = new File(dir, entry.encryptedName);
                if (!file.isFile() || file.length() != entry.encryptedSize) continue;
                EncryptedHeader header = readHeader(file);
                validateHeader(header, entry, installed.contentKey);
                entry.file = file;
                entry.contentKey = installed.contentKey.clone();
                entry.header = header;
                result.put(entry.name.toLowerCase(Locale.US), entry);
            }
            return result;
        } catch (Throwable ignored) {
            return new HashMap<>();
        }
    }

    public static InputStream openEntry(ProtectedEntry entry, long relativeStart) throws Exception {
        if (entry == null || entry.file == null || entry.header == null || entry.contentKey == null) {
            throw new IllegalStateException("加密 PAK 未就绪");
        }
        if (relativeStart < 0 || relativeStart >= entry.plainSize) {
            throw new IllegalArgumentException("加密 PAK 读取范围无效");
        }
        return new DecryptingInputStream(entry, relativeStart);
    }

    private static ManifestBundle fetchManifest(String token) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(API + "/content/" + MODULE_CODE + "/manifest");
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
            c.setRequestProperty("User-Agent", "RYLUX/2.3.0");

            JSONObject body = new JSONObject();
            body.put("device_public_key", DeviceKeyManager.publicKeyBase64());
            byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(data);
            }

            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = readUtf8(stream, MAX_MANIFEST_BYTES);
            if (code < 200 || code >= 300) {
                String message = "内容授权失败 HTTP " + code;
                try {
                    JSONObject error = new JSONObject(text);
                    message = error.optString("detail", message);
                } catch (Throwable ignored) {
                }
                if (code == 401 || code == 403 || code == 404 || code == 503) {
                    throw new IllegalStateException(message);
                }
                throw new NetworkException(message);
            }
            return parseServerManifest(text);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new NetworkException("内容服务连接失败", t);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ManifestBundle loadInstalledManifest(Context context) {
        try {
            File file = new File(moduleDir(context), AUTH_MANIFEST);
            if (!file.isFile()) return null;
            String text = readUtf8(new FileInputStream(file), MAX_MANIFEST_BYTES);
            return parseServerManifest(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ManifestBundle parseServerManifest(String text) throws Exception {
        JSONObject outer = new JSONObject(text);
        if (outer.optInt("schema", 0) != 1 || !MODULE_CODE.equals(outer.optString("module", ""))) {
            throw new IllegalStateException("受保护资源清单无效");
        }
        String keyId = outer.optString("key_id", "").trim().toLowerCase(Locale.US);
        String payloadB64 = outer.optString("payload_b64", "").trim();
        String manifestHmac = outer.optString("manifest_hmac", "").trim().toLowerCase(Locale.US);
        String wrappedKey = outer.optString("wrapped_key", "").trim();
        if (keyId.length() != 16 || payloadB64.isEmpty() || manifestHmac.length() != 64 || wrappedKey.isEmpty()) {
            throw new IllegalStateException("受保护资源授权数据不完整");
        }

        byte[] key = DeviceKeyManager.unwrapKey(wrappedKey);
        String actualKeyId = sha256Bytes(key).substring(0, 16);
        if (!actualKeyId.equals(keyId)) throw new IllegalStateException("设备内容密钥校验失败");

        byte[] payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT);
        if (payloadBytes.length == 0 || payloadBytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalStateException("受保护资源清单长度无效");
        }
        String actualHmac = hmacSha256(key, payloadBytes);
        if (!constantHexEquals(actualHmac, manifestHmac)) {
            throw new IllegalStateException("受保护资源清单完整性校验失败");
        }

        JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        if (payload.optInt("schema", 0) != 1 || !MODULE_CODE.equals(payload.optString("module", ""))) {
            throw new IllegalStateException("受保护资源载荷无效");
        }
        if (!keyId.equals(payload.optString("key_id", "").trim().toLowerCase(Locale.US))) {
            throw new IllegalStateException("受保护资源密钥版本不匹配");
        }
        String version = payload.optString("version", "").trim();
        if (version.isEmpty()) throw new IllegalStateException("受保护资源缺少版本号");

        JSONObject link = payload.optJSONObject("linkspak");
        if (link == null) throw new IllegalStateException("受保护资源缺少 linkspak");
        LinkspakEntry linkspak = new LinkspakEntry(
                link.optString("stored_name", LINKSPAK),
                link.optLong("size", -1L),
                link.optString("sha256", "").trim().toLowerCase(Locale.US)
        );
        if (!LINKSPAK.equals(linkspak.storedName)
                || linkspak.size <= 0
                || linkspak.sha256.length() != 64) {
            throw new IllegalStateException("linkspak 清单无效");
        }

        JSONArray files = payload.optJSONArray("files");
        if (files == null || files.length() == 0) throw new IllegalStateException("受保护资源列表为空");
        Map<String, ProtectedEntry> entries = new HashMap<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) throw new IllegalStateException("受保护资源项目异常");
            String name = item.optString("name", "").trim().toLowerCase(Locale.US);
            if (!PROTECTED_NAMES.contains(name) || entries.containsKey(name)) {
                throw new IllegalStateException("不允许的受保护资源：" + name);
            }
            ProtectedEntry entry = new ProtectedEntry(
                    name,
                    item.optString("encrypted_name", "").trim(),
                    item.optLong("revision", 0L),
                    item.optLong("plain_size", -1L),
                    item.optString("plain_sha256", "").trim().toLowerCase(Locale.US),
                    item.optLong("encrypted_size", -1L),
                    item.optString("encrypted_sha256", "").trim().toLowerCase(Locale.US),
                    keyId
            );
            if (!safeEncryptedName(entry.encryptedName)
                    || entry.revision <= 0
                    || entry.plainSize <= 0
                    || entry.plainSha256.length() != 64
                    || entry.encryptedSize <= 0
                    || entry.encryptedSha256.length() != 64) {
                throw new IllegalStateException("受保护资源清单无效：" + name);
            }
            entries.put(name, entry);
        }
        return new ManifestBundle(text, version, keyId, key, linkspak, entries);
    }

    private static boolean installedFilesPresent(Context context, ManifestBundle bundle) {
        try {
            File dir = moduleDir(context);
            File link = new File(dir, LINKSPAK);
            if (!matches(link, bundle.linkspak.size, bundle.linkspak.sha256)) return false;
            for (ProtectedEntry entry : bundle.entries.values()) {
                File file = new File(dir, entry.encryptedName);
                if (!file.isFile() || file.length() != entry.encryptedSize) return false;
                EncryptedHeader header = readHeader(file);
                validateHeader(header, entry, bundle.contentKey);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void verifyEncryptedFile(File file, ProtectedEntry entry, byte[] key) throws Exception {
        if (!entry.encryptedSha256.equals(sha256(file))) {
            throw new IllegalStateException("加密文件 SHA-256 不匹配：" + entry.name);
        }
        EncryptedHeader header = readHeader(file);
        validateHeader(header, entry, key);

        ProtectedEntry verify = entry.copy();
        verify.file = file;
        verify.header = header;
        verify.contentKey = key.clone();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (InputStream in = new DecryptingInputStream(verify, 0L)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n == 0) continue;
                digest.update(buffer, 0, n);
                total += n;
            }
        }
        if (total != entry.plainSize || !entry.plainSha256.equals(hex(digest.digest()))) {
            throw new IllegalStateException("加密 PAK 明文校验失败：" + entry.name);
        }
    }

    private static EncryptedHeader readHeader(File file) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[MAGIC_BYTES.length];
            raf.readFully(magic);
            if (!MessageDigest.isEqual(magic, MAGIC_BYTES)) throw new IllegalStateException("加密 PAK 格式无效");
            int headerLength = raf.readInt();
            if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
                throw new IllegalStateException("加密 PAK 头部长度无效");
            }
            byte[] headerBytes = new byte[headerLength];
            raf.readFully(headerBytes);
            JSONObject json = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
            byte[] noncePrefix = Base64.decode(json.optString("nonce_prefix", ""), Base64.DEFAULT);
            if (noncePrefix.length != 8) throw new IllegalStateException("加密 PAK nonce 无效");
            return new EncryptedHeader(
                    headerLength,
                    json.optString("module", ""),
                    json.optString("name", "").trim().toLowerCase(Locale.US),
                    json.optLong("plain_size", -1L),
                    json.optString("plain_sha256", "").trim().toLowerCase(Locale.US),
                    json.optLong("revision", 0L),
                    json.optInt("chunk_size", 0),
                    noncePrefix,
                    json.optString("key_id", "").trim().toLowerCase(Locale.US)
            );
        }
    }

    private static void validateHeader(EncryptedHeader header, ProtectedEntry entry, byte[] key) throws Exception {
        if (!MODULE_CODE.equals(header.module)
                || !entry.name.equals(header.name)
                || header.plainSize != entry.plainSize
                || !entry.plainSha256.equals(header.plainSha256)
                || header.revision != entry.revision
                || header.chunkSize < 64 * 1024
                || header.chunkSize > 4 * 1024 * 1024
                || !entry.keyId.equals(header.keyId)
                || !entry.keyId.equals(sha256Bytes(key).substring(0, 16))) {
            throw new IllegalStateException("加密 PAK 元数据不匹配：" + entry.name);
        }
        long chunks = (entry.plainSize + header.chunkSize - 1L) / header.chunkSize;
        long expected = PREFIX_BYTES + header.headerLength + entry.plainSize + chunks * 16L;
        File file = entry.file;
        if (file != null && file.length() != expected) {
            throw new IllegalStateException("加密 PAK 长度无效：" + entry.name);
        }
    }

    private static void download(
            String token,
            String remoteName,
            File target,
            long expectedSize,
            DownloadProgress progress
    ) throws Exception {
        HttpURLConnection c = null;
        try {
            URL url = new URL(API + "/content/" + MODULE_CODE + "/files/" + remoteName);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
            c.setRequestProperty("Cache-Control", "no-store");
            c.setRequestProperty("User-Agent", "RYLUX/2.3.0");
            int code = c.getResponseCode();
            if (code != 200) {
                String message = "资源下载失败 HTTP " + code;
                try {
                    String text = readUtf8(c.getErrorStream(), 128 * 1024);
                    JSONObject error = new JSONObject(text);
                    message = error.optString("detail", message);
                } catch (Throwable ignored) {
                }
                throw new IllegalStateException(message);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 256 * 1024);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[256 * 1024];
                long total = 0L;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    total += n;
                    if (total > expectedSize) throw new IllegalStateException("资源长度超过清单");
                    if (progress != null) progress.onBytes(total);
                }
                out.getFD().sync();
                if (total != expectedSize) throw new IllegalStateException("资源长度与清单不一致");
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void commitSlots(List<ContentSlot> slots) throws Exception {
        List<ContentSlot> committed = new ArrayList<>();
        try {
            for (ContentSlot slot : slots) {
                deleteIfExists(slot.backup);
                slot.hadTarget = slot.target.exists();
                if (slot.hadTarget && !slot.target.renameTo(slot.backup)) {
                    throw new IllegalStateException("无法备份旧加密资源：" + slot.target.getName());
                }
                if (!slot.stage.renameTo(slot.target)) {
                    if (slot.hadTarget && slot.backup.exists()) slot.backup.renameTo(slot.target);
                    throw new IllegalStateException("无法替换加密资源：" + slot.target.getName());
                }
                slot.committed = true;
                committed.add(slot);
            }
        } catch (Throwable t) {
            rollback(committed);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("加密资源提交失败", t);
        }
    }

    private static void rollback(List<ContentSlot> slots) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            ContentSlot slot = slots.get(i);
            if (!slot.committed) continue;
            try {
                if (slot.target.exists()) slot.target.delete();
                if (slot.hadTarget && slot.backup.exists()) slot.backup.renameTo(slot.target);
                slot.committed = false;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws Exception {
        File stage = new File(target.getParentFile(), target.getName() + ".stage");
        File backup = new File(target.getParentFile(), target.getName() + ".rollback");
        deleteIfExists(stage);
        try (FileOutputStream out = new FileOutputStream(stage)) {
            out.write(bytes);
            out.getFD().sync();
        }
        deleteIfExists(backup);
        boolean had = target.exists();
        if (had && !target.renameTo(backup)) throw new IllegalStateException("无法备份资源清单");
        if (!stage.renameTo(target)) {
            if (had && backup.exists()) backup.renameTo(target);
            throw new IllegalStateException("无法写入资源清单");
        }
        quietDelete(backup);
    }

    private static void removeLegacyPlaintext(Context context) {
        File legacy = ContentUpdateManager.moduleDir(context);
        for (String name : PROTECTED_NAMES) quietDelete(new File(legacy, name));
    }

    private static boolean matches(File file, long size, String sha) throws Exception {
        return file.isFile() && file.length() == size && sha.equals(sha256(file));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String sha256Bytes(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(bytes));
    }

    private static String hmacSha256(byte[] key, byte[] bytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(bytes));
    }

    private static boolean constantHexEquals(String a, String b) {
        try {
            return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String readUtf8(InputStream in, int maxBytes) throws Exception {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) {
                if (n == 0) continue;
                if (out.size() + n > maxBytes) throw new IllegalStateException("响应过大");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static void deleteIfExists(File file) throws Exception {
        if (file.exists() && !file.delete()) throw new IllegalStateException("无法清理临时文件：" + file.getName());
    }

    private static void quietDelete(File file) {
        try { if (file != null && file.exists()) file.delete(); } catch (Throwable ignored) {}
    }

    private static boolean safeEncryptedName(String name) {
        return name != null
                && name.toLowerCase(Locale.US).endsWith(".rpe")
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains("..");
    }

    private static void notifyProgress(ProgressListener listener, String message, int percent, boolean indeterminate) {
        if (listener == null) return;
        try { listener.onProgress(message, percent, indeterminate); } catch (Throwable ignored) {}
    }

    private static byte[] nonce(byte[] prefix, long index) {
        if (index < 0 || index > 0xffffffffL) throw new IllegalArgumentException("加密块索引越界");
        ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        buffer.put(prefix);
        buffer.putInt((int) index);
        return buffer.array();
    }

    private static byte[] aad(ProtectedEntry entry, long index, int chunkSize) {
        String value = MAGIC + "|" + MODULE_CODE + "|" + entry.name + "|"
                + entry.revision + "|" + index + "|" + entry.plainSize + "|"
                + chunkSize + "|" + entry.keyId;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class DecryptingInputStream extends InputStream {
        private final ProtectedEntry entry;
        private final RandomAccessFile raf;
        private final Cipher cipher;
        private long position;
        private long chunkIndex;
        private byte[] plainChunk;
        private int chunkPos;
        private boolean firstChunk = true;

        DecryptingInputStream(ProtectedEntry entry, long start) throws Exception {
            this.entry = entry;
            this.position = start;
            this.chunkIndex = start / entry.header.chunkSize;
            this.raf = new RandomAccessFile(entry.file, "r");
            this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
        }

        private boolean loadChunk() throws Exception {
            if (position >= entry.plainSize) return false;
            int chunkSize = entry.header.chunkSize;
            long plainStart = chunkIndex * (long) chunkSize;
            int plainLength = (int) Math.min((long) chunkSize, entry.plainSize - plainStart);
            long cipherOffset = PREFIX_BYTES + entry.header.headerLength
                    + chunkIndex * ((long) chunkSize + 16L);
            byte[] cipherText = new byte[plainLength + 16];
            raf.seek(cipherOffset);
            raf.readFully(cipherText);

            GCMParameterSpec spec = new GCMParameterSpec(128, nonce(entry.header.noncePrefix, chunkIndex));
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(entry.contentKey, "AES"), spec);
            cipher.updateAAD(aad(entry, chunkIndex, chunkSize));
            plainChunk = cipher.doFinal(cipherText);
            if (plainChunk.length != plainLength) throw new IllegalStateException("解密块长度异常");
            chunkPos = firstChunk ? (int) (position - plainStart) : 0;
            firstChunk = false;
            return true;
        }

        @Override
        public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (b == null) throw new NullPointerException();
            if (off < 0 || len < 0 || off + len > b.length) throw new IndexOutOfBoundsException();
            if (len == 0) return 0;
            if (position >= entry.plainSize) return -1;

            int written = 0;
            try {
                while (written < len && position < entry.plainSize) {
                    if (plainChunk == null || chunkPos >= plainChunk.length) {
                        if (plainChunk != null) chunkIndex++;
                        if (!loadChunk()) break;
                    }
                    int available = plainChunk.length - chunkPos;
                    int take = Math.min(len - written, available);
                    long remain = entry.plainSize - position;
                    take = (int) Math.min((long) take, remain);
                    System.arraycopy(plainChunk, chunkPos, b, off + written, take);
                    chunkPos += take;
                    written += take;
                    position += take;
                }
            } catch (Throwable t) {
                throw new java.io.IOException("加密 PAK 解密失败：" + entry.name, t);
            }
            return written == 0 ? -1 : written;
        }

        @Override
        public void close() throws java.io.IOException {
            raf.close();
            plainChunk = null;
        }
    }

    private static final class NetworkException extends Exception {
        NetworkException(String message) { super(message); }
        NetworkException(String message, Throwable cause) { super(message, cause); }
    }

    private static final class LinkspakEntry {
        final String storedName;
        final long size;
        final String sha256;
        LinkspakEntry(String storedName, long size, String sha256) {
            this.storedName = storedName;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private static final class ManifestBundle {
        final String serverResponse;
        final String version;
        final String keyId;
        final byte[] contentKey;
        final LinkspakEntry linkspak;
        final Map<String, ProtectedEntry> entries;
        ManifestBundle(
                String serverResponse,
                String version,
                String keyId,
                byte[] contentKey,
                LinkspakEntry linkspak,
                Map<String, ProtectedEntry> entries
        ) {
            this.serverResponse = serverResponse;
            this.version = version;
            this.keyId = keyId;
            this.contentKey = contentKey;
            this.linkspak = linkspak;
            this.entries = entries;
        }
    }

    private static final class EncryptedHeader {
        final int headerLength;
        final String module;
        final String name;
        final long plainSize;
        final String plainSha256;
        final long revision;
        final int chunkSize;
        final byte[] noncePrefix;
        final String keyId;
        EncryptedHeader(
                int headerLength,
                String module,
                String name,
                long plainSize,
                String plainSha256,
                long revision,
                int chunkSize,
                byte[] noncePrefix,
                String keyId
        ) {
            this.headerLength = headerLength;
            this.module = module;
            this.name = name;
            this.plainSize = plainSize;
            this.plainSha256 = plainSha256;
            this.revision = revision;
            this.chunkSize = chunkSize;
            this.noncePrefix = noncePrefix;
            this.keyId = keyId;
        }
    }

    private static final class ContentSlot {
        final File target;
        final File stage;
        final File backup;
        String downloadName;
        long expectedSize;
        String expectedSha;
        ProtectedEntry protectedEntry;
        boolean hadTarget;
        boolean committed;
        ContentSlot(File target) {
            this.target = target;
            this.stage = new File(target.getParentFile(), target.getName() + ".stage");
            this.backup = new File(target.getParentFile(), target.getName() + ".rollback");
        }
    }

    public static final class ProtectedEntry {
        public final String name;
        public final String encryptedName;
        public final long revision;
        public final long plainSize;
        public final String plainSha256;
        public final long encryptedSize;
        public final String encryptedSha256;
        public final String keyId;
        File file;
        byte[] contentKey;
        EncryptedHeader header;

        ProtectedEntry(
                String name,
                String encryptedName,
                long revision,
                long plainSize,
                String plainSha256,
                long encryptedSize,
                String encryptedSha256,
                String keyId
        ) {
            this.name = name;
            this.encryptedName = encryptedName;
            this.revision = revision;
            this.plainSize = plainSize;
            this.plainSha256 = plainSha256;
            this.encryptedSize = encryptedSize;
            this.encryptedSha256 = encryptedSha256;
            this.keyId = keyId;
        }

        ProtectedEntry copy() {
            return new ProtectedEntry(
                    name,
                    encryptedName,
                    revision,
                    plainSize,
                    plainSha256,
                    encryptedSize,
                    encryptedSha256,
                    keyId
            );
        }
    }

    public static final class UpdateResult {
        public final boolean updated;
        public final boolean softFailure;
        public final int changedFiles;
        public final String version;
        public final String message;

        private UpdateResult(boolean updated, boolean softFailure, int changedFiles, String version, String message) {
            this.updated = updated;
            this.softFailure = softFailure;
            this.changedFiles = changedFiles;
            this.version = version;
            this.message = message;
        }

        static UpdateResult updated(int count, String version) {
            return new UpdateResult(true, false, count, version, "加密资源已更新");
        }

        static UpdateResult noUpdate(String version) {
            return new UpdateResult(false, false, 0, version, "加密资源已是最新");
        }

        static UpdateResult softFailure(String version, String message) {
            return new UpdateResult(false, true, 0, version, message);
        }
    }
}
