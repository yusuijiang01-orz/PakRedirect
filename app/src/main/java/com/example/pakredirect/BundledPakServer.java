package com.example.pakredirect;

import android.content.Context;
import android.content.res.AssetFileDescriptor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves verified hot-updated localization content and optional mounted mirror
 * PAKs over localhost. The game downloads from 127.0.0.1 and writes files with
 * its own UID, so no root or cross-app sandbox write is required.
 */
public final class BundledPakServer {
    public interface Listener {
        void onLog(String line);
        void onHit(int count);
    }

    public static final int PORT = 18480;
    public static final String MANIFEST_PATH = "/linkspak.txt";
    public static final String PAK_PREFIX = "/pak/";

    private final Context context;
    private final Listener listener;
    private final Map<String, PakSource> pakAssets = new HashMap<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(4);

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private byte[] manifestBytes;

    public BundledPakServer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void prepare() throws Exception {
        pakAssets.clear();
        String[] names = context.getAssets().list("");
        if (names == null) throw new IllegalStateException("无法读取 APK 内置资源");

        for (String name : names) {
            if (name == null || !name.toLowerCase(Locale.US).endsWith(".pak")) continue;
            try (AssetFileDescriptor afd = context.getAssets().openFd(name)) {
                long length = afd.getLength();
                if (length < 0) throw new IllegalStateException("无法读取 PAK 大小: " + name);
                pakAssets.put(name.toLowerCase(Locale.US), PakSource.asset(name, length));
            }
        }

        File hotDir = ContentUpdateManager.moduleDir(context);
        File[] hotFiles = hotDir.listFiles();
        int hotCount = 0;
        if (hotFiles != null) {
            for (File file : hotFiles) {
                if (!file.isFile()) continue;
                String name = file.getName();
                if (!name.toLowerCase(Locale.US).endsWith(".pak")) continue;
                if (file.length() <= 0) continue;
                pakAssets.put(name.toLowerCase(Locale.US), PakSource.hot(name, file));
                hotCount++;
            }
        }

        int mirrorCount = 0;
        Map<String, MirrorPackManager.MirrorEntry> mirrorEntries = MirrorPackManager.entries(context);
        for (MirrorPackManager.MirrorEntry entry : mirrorEntries.values()) {
            if (entry == null || entry.uri == null || entry.size <= 0 || entry.revision <= 0) continue;
            String key = entry.name.toLowerCase(Locale.US);
            // Mirror packs are restricted to official PAK names, but never let a
            // mirror override a localization PAK if the allow-list changes later.
            if ("settings.pak".equals(key) || "ui.pak".equals(key) || "updatefs.pak".equals(key)) continue;
            pakAssets.put(key, PakSource.mirror(entry));
            mirrorCount++;
        }

        if (pakAssets.isEmpty()) throw new IllegalStateException("未发现可用 PAK 文件");

        String manifest = readManifestText(hotDir);
        PatchResult patched = patchManifest(manifest);
        if (patched.changed == 0) {
            throw new IllegalStateException("linkspak.txt 未匹配到任何 PAK");
        }
        manifestBytes = patched.text.getBytes(StandardCharsets.UTF_8);
        log("已加载 PAK " + pakAssets.size() + " 个，其中汉化热更新 " + hotCount
                + " 个、镜像 " + mirrorCount + " 个；清单已改写 " + patched.changed + " 项");
    }

    public void start() throws Exception {
        if (manifestBytes == null || pakAssets.isEmpty()) throw new IllegalStateException("尚未 prepare");
        serverSocket = new ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "RYLUX-Pak-Accept");
        acceptThread.start();
        log("本地 PAK 服务已监听 http://127.0.0.1:" + PORT);
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable ignored) {}
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(15000);
                workers.execute(() -> handle(socket));
            } catch (Throwable t) {
                if (running) log("本地服务连接异常: " + safeMessage(t));
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String headers = readHeaders(in, 64 * 1024);
            if (headers == null || headers.isEmpty()) return;

            String[] lines = headers.split("\\r?\\n");
            String[] first = lines[0].split(" ", 3);
            if (first.length < 2) {
                sendText(out, 400, "Bad Request");
                return;
            }

            String method = first[0].toUpperCase(Locale.US);
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                sendText(out, 405, "Method Not Allowed");
                return;
            }
            boolean headOnly = "HEAD".equals(method);
            String path = pathOnly(first[1]);

            String range = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) continue;
                String key = lines[i].substring(0, colon).trim();
                if ("range".equalsIgnoreCase(key)) range = lines[i].substring(colon + 1).trim();
            }

            if ("/health".equals(path)) {
                sendText(out, 200, "RYLUX OK");
                return;
            }
            if (MANIFEST_PATH.equals(path)) {
                sendManifest(out, headOnly);
                log("linkspak.txt 已发送给游戏");
                return;
            }
            if (path.startsWith(PAK_PREFIX)) {
                String name = path.substring(PAK_PREFIX.length());
                PakSource asset = pakAssets.get(name.toLowerCase(Locale.US));
                if (asset == null) {
                    sendText(out, 404, "PAK not found");
                    return;
                }
                servePak(out, asset, headOnly, range);
                int count = hits.incrementAndGet();
                if (listener != null) listener.onHit(count);
                log("本地 PAK 命中 #" + count + ": " + asset.name + " [" + asset.label + "]");
                return;
            }
            sendText(out, 404, "Not Found");
        } catch (Throwable t) {
            log("请求处理失败: " + safeMessage(t));
        }
    }

    private void sendManifest(OutputStream out, boolean headOnly) throws Exception {
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + manifestBytes.length + "\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) out.write(manifestBytes);
        out.flush();
    }

    private void servePak(OutputStream out, PakSource asset, boolean headOnly, String rangeHeader) throws Exception {
        long total = asset.size;
        long start = 0;
        long end = total - 1;
        boolean partial = false;

        if (rangeHeader != null && rangeHeader.toLowerCase(Locale.US).startsWith("bytes=")) {
            String spec = rangeHeader.substring(6).trim();
            if (spec.contains(",")) { send416(out, total); return; }
            int dash = spec.indexOf('-');
            if (dash < 0) { send416(out, total); return; }
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            try {
                if (left.isEmpty()) {
                    long suffix = Long.parseLong(right);
                    if (suffix <= 0) { send416(out, total); return; }
                    start = Math.max(0, total - suffix);
                } else {
                    start = Long.parseLong(left);
                    if (!right.isEmpty()) end = Long.parseLong(right);
                }
            } catch (NumberFormatException e) {
                send416(out, total);
                return;
            }
            if (start < 0 || start >= total || end < start) { send416(out, total); return; }
            end = Math.min(end, total - 1);
            partial = true;
        }

        long length = end - start + 1;
        StringBuilder response = new StringBuilder();
        response.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        response.append("Content-Type: application/octet-stream\r\n");
        response.append("Accept-Ranges: bytes\r\n");
        response.append("Content-Length: ").append(length).append("\r\n");
        if (partial) response.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
        response.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(response.toString().getBytes(StandardCharsets.US_ASCII));

        if (!headOnly) {
            try (InputStream assetIn = asset.open(context, start)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int n = assetIn.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) break;
                    out.write(buffer, 0, n);
                    remaining -= n;
                }
                if (remaining != 0) throw new IllegalStateException("PAK 读取长度不足: " + asset.name);
            }
        }
        out.flush();
    }

    private PatchResult patchManifest(String original) {
        String[] lines = original.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(original.length() + 512);
        int changed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String[] fields = line.split(",", -1);
            if (fields.length >= 4) {
                String pakName = fields[2].trim();
                PakSource asset = pakAssets.get(pakName.toLowerCase(Locale.US));
                if (asset != null && mirrorRevisionMatches(asset, fields)) {
                    fields[0] = leadingSpaces(fields[0]) + "http://127.0.0.1:" + PORT + PAK_PREFIX + asset.name + trailingSpaces(fields[0]);
                    fields[3] = leadingSpaces(fields[3]) + asset.size + trailingSpaces(fields[3]);
                    StringBuilder rebuilt = new StringBuilder();
                    for (int f = 0; f < fields.length; f++) {
                        if (f > 0) rebuilt.append(',');
                        rebuilt.append(fields[f]);
                    }
                    line = rebuilt.toString();
                    changed++;
                }
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return new PatchResult(out.toString(), changed);
    }

    private boolean mirrorRevisionMatches(PakSource asset, String[] fields) {
        if (!asset.mirror) return true;
        if (fields.length < 6) return false;
        try {
            long manifestRevision = Long.parseLong(fields[5].trim());
            if (manifestRevision == asset.revision) return true;
            log("镜像版本不匹配，保持官方源: " + asset.name
                    + " (镜像 " + asset.revision + " / 清单 " + manifestRevision + ")");
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String readManifestText(File hotDir) throws Exception {
        File hot = new File(hotDir, "linkspak.txt");
        if (hot.isFile() && hot.length() > 0) {
            try (InputStream in = new FileInputStream(hot)) { return readText(in); }
        }
        try (InputStream in = context.getAssets().open("linkspak.txt")) { return readText(in); }
    }

    private static String readText(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    private static void skipFully(InputStream in, long bytes) throws Exception {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) { remaining -= skipped; continue; }
            if (in.read() < 0) throw new IllegalStateException("Unexpected EOF while seeking");
            remaining--;
        }
    }

    private static String readHeaders(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (out.size() < max) {
            int value = in.read();
            if (value < 0) break;
            out.write(value);
            if ((state == 0 || state == 2) && value == '\r') state++;
            else if ((state == 1 || state == 3) && value == '\n') state++;
            else state = value == '\r' ? 1 : 0;
            if (state == 4) break;
        }
        return out.toString("ISO-8859-1");
    }

    private static String pathOnly(String target) {
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static void send416(OutputStream out, long total) throws Exception {
        String response = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Error";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static String leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String trailingSpaces(String s) {
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return s.substring(i + 1);
    }

    private void log(String line) { if (listener != null) listener.onLog(line); }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }

    private static final class PakSource {
        final String name;
        final long size;
        final File file;
        final MirrorPackManager.MirrorEntry mirrorEntry;
        final boolean mirror;
        final long revision;
        final String label;

        private PakSource(
                String name,
                long size,
                File file,
                MirrorPackManager.MirrorEntry mirrorEntry,
                boolean mirror,
                long revision,
                String label
        ) {
            this.name = name;
            this.size = size;
            this.file = file;
            this.mirrorEntry = mirrorEntry;
            this.mirror = mirror;
            this.revision = revision;
            this.label = label;
        }

        static PakSource asset(String name, long size) {
            return new PakSource(name, size, null, null, false, 0L, "内置");
        }

        static PakSource hot(String name, File file) {
            return new PakSource(name, file.length(), file, null, false, 0L, "汉化热更新");
        }

        static PakSource mirror(MirrorPackManager.MirrorEntry entry) {
            return new PakSource(entry.name, entry.size, null, entry, true, entry.revision, "镜像");
        }

        InputStream open(Context context, long start) throws Exception {
            if (mirrorEntry != null) {
                return MirrorPackManager.openEntry(context, mirrorEntry, start);
            }
            InputStream in = file == null ? context.getAssets().open(name) : new FileInputStream(file);
            try {
                skipFully(in, start);
                return in;
            } catch (Throwable t) {
                try { in.close(); } catch (Throwable ignored) {}
                if (t instanceof Exception) throw (Exception) t;
                throw new IllegalStateException("PAK 定位失败: " + name, t);
            }
        }
    }

    private static final class PatchResult {
        final String text;
        final int changed;
        PatchResult(String text, int changed) { this.text = text; this.changed = changed; }
    }
}
