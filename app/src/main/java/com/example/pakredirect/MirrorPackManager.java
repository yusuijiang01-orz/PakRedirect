package com.example.pakredirect;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a user-selected RYLUX mirror pack into app-private storage.
 *
 * Mirror packs contain only unmodified official game PAKs. Modified localization
 * PAKs remain managed by ContentUpdateManager and can never be overridden by a
 * mirror pack. Every imported file is verified against mirror.json before the
 * staged directory is committed.
 */
public final class MirrorPackManager {
    public static final String MODULE_CODE = "sg_localization";
    private static final String META_NAME = "mirror.json";
    private static final int MAX_META_BYTES = 512 * 1024;

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "resource.pak",
            "spr.pak",
            "script.pak",
            "fs2008.pak",
            "maps.pak",
            "update.pak",
            "blaze.pak"
    )));

    private MirrorPackManager() {}

    public interface ProgressListener {
        void onProgress(String message, int percent);
    }

    public static File mirrorDir(Context context) {
        return new File(context.getFilesDir(), "rylux-mirror/" + MODULE_CODE);
    }

    public static ImportResult importPack(Context context, Uri uri, ProgressListener listener) throws Exception {
        if (uri == null) throw new IllegalArgumentException("未选择镜像包");

        File base = new File(context.getFilesDir(), "rylux-mirror");
        if (!base.exists() && !base.mkdirs()) throw new IllegalStateException("无法创建镜像目录");

        File target = mirrorDir(context);
        File stage = new File(base, MODULE_CODE + ".stage");
        File backup = new File(base, MODULE_CODE + ".rollback");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IllegalStateException("无法创建镜像临时目录");

        long archiveSize = querySize(context, uri);
        byte[] metaBytes = null;
        List<String> extracted = new ArrayList<>();

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IllegalStateException("无法读取镜像包");
            CountingInputStream counted = new CountingInputStream(new BufferedInputStream(raw, 256 * 1024));
            try (ZipInputStream zip = new ZipInputStream(counted)) {
                ZipEntry entry;
                byte[] buffer = new byte[256 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }
                    String safe = safeRootName(entry.getName());
                    if (safe == null) {
                        zip.closeEntry();
                        continue;
                    }
                    String lower = safe.toLowerCase(Locale.US);
                    if (META_NAME.equals(lower)) {
                        metaBytes = readLimited(zip, MAX_META_BYTES);
                    } else if (ALLOWED.contains(lower)) {
                        File out = new File(stage, lower);
                        long copied = 0;
                        try (FileOutputStream fos = new FileOutputStream(out);
                             BufferedOutputStream bos = new BufferedOutputStream(fos, 256 * 1024)) {
                            int n;
                            while ((n = zip.read(buffer)) >= 0) {
                                if (n == 0) continue;
                                bos.write(buffer, 0, n);
                                copied += n;
                                emit(listener, "正在导入 " + lower + " · " + humanBytes(copied), percent(counted.count, archiveSize));
                            }
                            bos.flush();
                            fos.getFD().sync();
                        }
                        extracted.add(lower);
                    }
                    zip.closeEntry();
                }
            }
        } catch (Throwable t) {
            deleteTree(stage);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("镜像包导入失败", t);
        }

        if (metaBytes == null || metaBytes.length == 0) {
            deleteTree(stage);
            throw new IllegalStateException("镜像包缺少 mirror.json");
        }

        emit(listener, "正在校验镜像包…", 96);
        MirrorMeta meta = parseAndVerify(stage, metaBytes, extracted);
        try (FileOutputStream out = new FileOutputStream(new File(stage, META_NAME))) {
            out.write(metaBytes);
            out.getFD().sync();
        }

        deleteTree(backup);
        boolean hadTarget = target.exists();
        try {
            if (hadTarget && !target.renameTo(backup)) {
                throw new IllegalStateException("无法备份旧镜像包");
            }
            if (!stage.renameTo(target)) {
                if (hadTarget && backup.exists()) backup.renameTo(target);
                throw new IllegalStateException("无法安装镜像包");
            }
            deleteTree(backup);
        } catch (Throwable t) {
            deleteTree(stage);
            if (!target.exists() && hadTarget && backup.exists()) backup.renameTo(target);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("镜像包安装失败", t);
        }

        emit(listener, "镜像包已就绪", 100);
        return new ImportResult(true, meta.packName, meta.files.size(), meta.totalBytes);
    }

    public static MirrorStatus status(Context context) {
        try {
            File dir = mirrorDir(context);
            File metaFile = new File(dir, META_NAME);
            if (!metaFile.isFile()) return MirrorStatus.none();
            byte[] bytes = readFileLimited(metaFile, MAX_META_BYTES);
            MirrorMeta meta = parseMeta(bytes);
            int validCount = 0;
            long total = 0;
            for (MirrorEntry entry : meta.files.values()) {
                if (entry.file.isFile() && entry.file.length() == entry.size) {
                    validCount++;
                    total += entry.size;
                }
            }
            if (validCount == 0) return MirrorStatus.none();
            return new MirrorStatus(true, meta.packName, validCount, total);
        } catch (Throwable ignored) {
            return MirrorStatus.none();
        }
    }

    /** Returns only entries that still match their recorded size. */
    public static Map<String, MirrorEntry> entries(Context context) {
        try {
            File dir = mirrorDir(context);
            File metaFile = new File(dir, META_NAME);
            if (!metaFile.isFile()) return Collections.emptyMap();
            MirrorMeta meta = parseMeta(readFileLimited(metaFile, MAX_META_BYTES));
            Map<String, MirrorEntry> out = new HashMap<>();
            for (MirrorEntry entry : meta.files.values()) {
                if (entry.file.isFile() && entry.file.length() == entry.size) {
                    out.put(entry.name, entry);
                }
            }
            return out;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    public static void clear(Context context) {
        deleteTree(mirrorDir(context));
    }

    private static MirrorMeta parseAndVerify(File stage, byte[] metaBytes, List<String> extracted) throws Exception {
        JSONObject root = new JSONObject(new String(metaBytes, "UTF-8"));
        if (root.optInt("schema", 0) != 1) throw new IllegalStateException("镜像包格式版本不支持");
        if (!MODULE_CODE.equals(root.optString("module", ""))) throw new IllegalStateException("镜像包模块不匹配");

        JSONArray files = root.optJSONArray("files");
        if (files == null || files.length() == 0) throw new IllegalStateException("镜像包没有 PAK 文件");

        String packName = root.optString("name", "RYLUX 镜像包").trim();
        if (packName.isEmpty()) packName = "RYLUX 镜像包";
        Map<String, MirrorEntry> parsed = new HashMap<>();
        long totalBytes = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) throw new IllegalStateException("镜像包清单项目异常");
            String name = item.optString("name", "").trim().toLowerCase(Locale.US);
            long size = item.optLong("size", -1L);
            long revision = item.optLong("revision", 0L);
            String sha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
            if (!ALLOWED.contains(name)) throw new IllegalStateException("镜像包包含不允许的 PAK：" + name);
            if (size <= 0 || revision <= 0 || sha.length() != 64) throw new IllegalStateException("镜像包清单无效：" + name);
            if (parsed.containsKey(name)) throw new IllegalStateException("镜像包重复文件：" + name);
            File file = new File(stage, name);
            if (!file.isFile()) throw new IllegalStateException("镜像包缺少文件：" + name);
            if (file.length() != size) throw new IllegalStateException("镜像包文件大小不匹配：" + name);
            if (!sha.equals(sha256(file))) throw new IllegalStateException("镜像包校验失败：" + name);
            parsed.put(name, new MirrorEntry(name, file, size, revision));
            totalBytes += size;
        }

        for (String name : extracted) {
            if (!parsed.containsKey(name)) throw new IllegalStateException("mirror.json 未登记文件：" + name);
        }
        return new MirrorMeta(packName, parsed, totalBytes);
    }

    private static MirrorMeta parseMeta(byte[] metaBytes) throws Exception {
        JSONObject root = new JSONObject(new String(metaBytes, "UTF-8"));
        if (root.optInt("schema", 0) != 1 || !MODULE_CODE.equals(root.optString("module", ""))) {
            throw new IllegalStateException("镜像包元数据无效");
        }
        String packName = root.optString("name", "RYLUX 镜像包").trim();
        if (packName.isEmpty()) packName = "RYLUX 镜像包";
        File dir = null;
        JSONArray files = root.optJSONArray("files");
        if (files == null) throw new IllegalStateException("镜像包元数据为空");
        Map<String, MirrorEntry> parsed = new HashMap<>();
        long total = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim().toLowerCase(Locale.US);
            long size = item.optLong("size", -1L);
            long revision = item.optLong("revision", 0L);
            if (!ALLOWED.contains(name) || size <= 0 || revision <= 0) continue;
            if (dir == null) {
                // mirror.json always lives directly inside the mirror directory.
                // The caller rewrites the placeholder File below to the current directory.
            }
            parsed.put(name, new MirrorEntry(name, null, size, revision));
            total += size;
        }
        return new MirrorMeta(packName, parsed, total);
    }

    private static MirrorMeta parseMeta(Context context, byte[] metaBytes) throws Exception {
        MirrorMeta raw = parseMeta(metaBytes);
        File dir = mirrorDir(context);
        Map<String, MirrorEntry> fixed = new HashMap<>();
        for (MirrorEntry entry : raw.files.values()) {
            fixed.put(entry.name, new MirrorEntry(entry.name, new File(dir, entry.name), entry.size, entry.revision));
        }
        return new MirrorMeta(raw.packName, fixed, raw.totalBytes);
    }

    private static long querySize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    private static int percent(long read, long total) {
        if (total <= 0) return -1;
        return Math.max(0, Math.min(95, (int) ((read * 95L) / total)));
    }

    private static void emit(ProgressListener listener, String message, int percent) {
        if (listener != null) listener.onProgress(message, percent);
    }

    private static String safeRootName(String value) {
        if (value == null) return null;
        String name = value.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        return name;
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            if (out.size() + n > max) throw new IllegalStateException("mirror.json 过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] readFileLimited(File file, int max) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return readLimited(in, max);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 256 * 1024)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        try { file.delete(); } catch (Throwable ignored) {}
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;
        CountingInputStream(InputStream in) { super(in); }
        @Override public int read() throws java.io.IOException {
            int value = super.read();
            if (value >= 0) count++;
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    private static final class MirrorMeta {
        final String packName;
        final Map<String, MirrorEntry> files;
        final long totalBytes;
        MirrorMeta(String packName, Map<String, MirrorEntry> files, long totalBytes) {
            this.packName = packName;
            this.files = files;
            this.totalBytes = totalBytes;
        }
    }

    public static final class MirrorEntry {
        public final String name;
        public final File file;
        public final long size;
        public final long revision;
        MirrorEntry(String name, File file, long size, long revision) {
            this.name = name;
            this.file = file;
            this.size = size;
            this.revision = revision;
        }
    }

    public static final class MirrorStatus {
        public final boolean ready;
        public final String name;
        public final int fileCount;
        public final long totalBytes;
        MirrorStatus(boolean ready, String name, int fileCount, long totalBytes) {
            this.ready = ready;
            this.name = name;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
        static MirrorStatus none() { return new MirrorStatus(false, "", 0, 0L); }
    }

    public static final class ImportResult {
        public final boolean success;
        public final String name;
        public final int fileCount;
        public final long totalBytes;
        ImportResult(boolean success, String name, int fileCount, long totalBytes) {
            this.success = success;
            this.name = name;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }
}
