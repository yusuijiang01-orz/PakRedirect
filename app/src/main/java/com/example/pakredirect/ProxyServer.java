package com.example.pakredirect;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

public final class ProxyServer {
    public interface Listener {
        void onLog(String line);
        void onHit(int count);
    }

    public static final String DEFAULT_MANIFEST_URL = "https://cdn.tamgioipt.vn/linkspak.txt";

    private final Context context;
    private final URI pakTarget;
    private final URI manifestTarget;
    private final Uri fileUri;
    private final Listener listener;
    private final AtomicInteger hits = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private SSLServerSocket server;
    private Thread acceptThread;
    private long pakSize = -1;
    private byte[] patchedManifest;

    public ProxyServer(Context context, String targetUrl, String manifestUrl, Uri fileUri, Listener listener) {
        this.context = context.getApplicationContext();
        this.pakTarget = URI.create(targetUrl);
        this.manifestTarget = URI.create(manifestUrl);
        this.fileUri = fileUri;
        this.listener = listener;
    }

    /** Must be called before hosts are redirected. */
    public void prepare() throws Exception {
        pakSize = determinePakSize();
        if (pakSize < 0) throw new IllegalStateException("无法读取所选 PAK 文件大小");

        String original;
        try {
            original = downloadText(manifestTarget.toString());
            log("已读取远端 linkspak.txt");
        } catch (Throwable t) {
            original = new String(readAsset("linkspak_fallback.txt"), StandardCharsets.UTF_8);
            log("远端 linkspak.txt 读取失败，使用内置模板: " + t.getClass().getSimpleName());
        }
        String patched = patchManifestSize(original, pakSize);
        if (patched.equals(original)) throw new IllegalStateException("linkspak.txt 中未找到目标 ui.pak 行");
        patchedManifest = patched.getBytes(StandardCharsets.UTF_8);
        log("ui.pak 实际大小: " + pakSize + " bytes；linkspak.txt 已动态修正");
    }

    public String[] interceptedHosts() {
        return new String[]{pakTarget.getHost(), manifestTarget.getHost()};
    }

    public void start(int port) throws Exception {
        String pakHost = pakTarget.getHost();
        String manifestHost = manifestTarget.getHost();
        if (pakHost == null || manifestHost == null) throw new IllegalArgumentException("URL 缺少 Host");
        if (patchedManifest == null || pakSize < 0) throw new IllegalStateException("尚未 prepare");

        byte[] caPem = readAsset("pakredirect_ca.pem");
        byte[] caKey = readAsset("pakredirect_ca_key.pem");
        CertUtil.GeneratedIdentity id = CertUtil.issueServerIdentity(new String[]{pakHost, manifestHost}, caPem, caKey);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", id.privateKey, new char[0], new java.security.cert.Certificate[]{id.leaf, id.ca});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "PakRedirect-Accept");
        acceptThread.start();
        log("HTTPS 本地服务已监听 127.0.0.1:" + port);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        workers.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                SSLSocket s = (SSLSocket) server.accept();
                s.setSoTimeout(15000);
                workers.execute(() -> handle(s));
            } catch (Throwable t) {
                if (running) log("accept 失败: " + t.getMessage());
            }
        }
    }

    private void handle(SSLSocket socket) {
        try (SSLSocket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             BufferedOutputStream out = new BufferedOutputStream(s.getOutputStream())) {
            String headerText = readHeaders(in, 64 * 1024);
            if (headerText == null || headerText.isEmpty()) return;
            String[] lines = headerText.split("\\r?\\n");
            String[] first = lines[0].split(" ", 3);
            if (first.length < 2) { sendText(out, 400, "Bad Request"); return; }
            String method = first[0].toUpperCase(Locale.US);
            String requestTarget = first[1];
            Map<String,String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int p = lines[i].indexOf(':');
                if (p > 0) h.put(lines[i].substring(0,p).trim().toLowerCase(Locale.US), lines[i].substring(p+1).trim());
            }
            if (!(method.equals("GET") || method.equals("HEAD"))) {
                sendText(out, 405, "Method Not Allowed");
                return;
            }

            String host = normalizeHost(h.get("host"));
            String path = requestPathOnly(requestTarget);

            if (host.equalsIgnoreCase(pakTarget.getHost()) && path.equals(rawPath(pakTarget))) {
                serveFile(out, method.equals("HEAD"), h.get("range"));
                int n = hits.incrementAndGet();
                if (listener != null) listener.onHit(n);
                log("PAK 命中 #" + n + ": " + method + " " + requestTarget + (h.get("range") == null ? "" : " | " + h.get("range")));
                return;
            }

            if (host.equalsIgnoreCase(manifestTarget.getHost()) && path.equals(rawPath(manifestTarget))) {
                serveManifest(out, method.equals("HEAD"));
                log("linkspak.txt 命中：已返回动态大小 " + pakSize);
                return;
            }

            log("未匹配: " + host + " " + method + " " + requestTarget);
            sendText(out, 404, "PakRedirect: URL not matched");
        } catch (Throwable t) {
            log("连接失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void serveManifest(OutputStream out, boolean headOnly) throws Exception {
        String h = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + patchedManifest.length + "\r\n" +
                "Cache-Control: no-store, no-cache, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        if (!headOnly) out.write(patchedManifest);
        out.flush();
    }

    private void serveFile(OutputStream out, boolean headOnly, String rangeHeader) throws Exception {
        ContentResolver cr = context.getContentResolver();
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(fileUri, "r")) {
            if (pfd == null) { sendText(out, 500, "Cannot open selected PAK"); return; }
            long total = pfd.getStatSize();
            if (total < 0) total = pakSize;
            if (total < 0) { sendText(out, 500, "Cannot determine PAK size"); return; }
            long start = 0, end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.toLowerCase(Locale.US).startsWith("bytes=")) {
                String r = rangeHeader.substring(6).trim();
                if (r.contains(",")) { send416(out, total); return; }
                int dash = r.indexOf('-');
                if (dash < 0) { send416(out, total); return; }
                String a = r.substring(0, dash).trim();
                String b = r.substring(dash + 1).trim();
                try {
                    if (a.isEmpty()) {
                        long suffix = Long.parseLong(b);
                        if (suffix <= 0) { send416(out, total); return; }
                        start = Math.max(0, total - suffix);
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Long.parseLong(b);
                    }
                } catch (NumberFormatException e) { send416(out, total); return; }
                if (start < 0 || start >= total || end < start) { send416(out, total); return; }
                end = Math.min(end, total - 1);
                partial = true;
            }
            long len = end - start + 1;
            StringBuilder resp = new StringBuilder();
            resp.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            resp.append("Content-Type: application/octet-stream\r\n");
            resp.append("Accept-Ranges: bytes\r\n");
            resp.append("Content-Length: ").append(len).append("\r\n");
            if (partial) resp.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n");
            resp.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(resp.toString().getBytes(StandardCharsets.US_ASCII));
            if (!headOnly) {
                try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                    fis.getChannel().position(start);
                    byte[] buf = new byte[64 * 1024];
                    long remain = len;
                    while (remain > 0) {
                        int n = fis.read(buf, 0, (int)Math.min(buf.length, remain));
                        if (n < 0) break;
                        out.write(buf, 0, n);
                        remain -= n;
                    }
                }
            }
            out.flush();
        }
    }

    private long determinePakSize() {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(fileUri, "r")) {
            if (pfd != null && pfd.getStatSize() >= 0) return pfd.getStatSize();
        } catch (Throwable ignored) {}
        android.database.Cursor c = null;
        try {
            c = context.getContentResolver().query(fileUri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) { } finally { if (c != null) c.close(); }
        return -1;
    }

    private String patchManifestSize(String original, long size) {
        String base = "https://" + pakTarget.getHost() + rawPath(pakTarget);
        Pattern p = Pattern.compile("(?m)^(\\s*" + Pattern.quote(base) + "(?:\\?[^,\\r\\n]*)?\\s*,\\s*data/\\s*,\\s*ui\\.pak\\s*,\\s*)\\d+(\\s*,[^\\r\\n]*)$");
        Matcher m = p.matcher(original);
        if (!m.find()) return original;
        String replacement = m.group(1) + size + m.group(2);
        return original.substring(0, m.start()) + replacement + original.substring(m.end());
    }

    private String downloadText(String url) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PakRedirect/1.1");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) {
                if (out.size() + n > 1024 * 1024) throw new IllegalStateException("manifest too large");
                out.write(b, 0, n);
            }
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static String normalizeHost(String hostHeader) {
        if (hostHeader == null) return "";
        String h = hostHeader.trim();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon > 0 ? h.substring(0, colon) : h;
    }

    private static String rawPath(URI u) {
        String p = u.getRawPath();
        return p == null || p.isEmpty() ? "/" : p;
    }

    private static String requestPathOnly(String target) {
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static String readHeaders(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < max) {
            int x = in.read();
            if (x < 0) break;
            b.write(x);
            if ((state == 0 || state == 2) && x == '\r') state++;
            else if ((state == 1 || state == 3) && x == '\n') state++;
            else state = (x == '\r') ? 1 : 0;
            if (state == 4) break;
        }
        return b.toString("ISO-8859-1");
    }

    private static void send416(OutputStream out, long total) throws Exception {
        String s = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + total + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(s.getBytes(StandardCharsets.US_ASCII)); out.flush();
    }

    private static void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String reason = code == 400 ? "Bad Request" : code == 404 ? "Not Found" : code == 405 ? "Method Not Allowed" : "Error";
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII)); out.write(body); out.flush();
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) >= 0) out.write(b,0,n);
            return out.toByteArray();
        }
    }

    private void log(String s) { if (listener != null) listener.onLog(s); }
}
