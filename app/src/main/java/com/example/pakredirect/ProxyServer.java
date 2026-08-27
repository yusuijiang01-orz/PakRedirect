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
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class ProxyServer {
    public interface Listener {
        void onLog(String line);
        void onHit(int count);
    }

    public static final String DEFAULT_MANIFEST_URL = "https://cdn.tamgioipt.vn/linkspak.txt";

    private final Context context;
    private final URI pakTarget;
    private final URI manifestTarget;
    private final Uri directoryUri;
    private final Listener listener;
    private final AtomicInteger hits = new AtomicInteger();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, InetAddress[]> originAddresses = new HashMap<>();
    private final Map<String, PakEntry> pakEntries = new HashMap<>();
    private final Set<String> pakHosts = new LinkedHashSet<>();

    private volatile boolean running;
    private SSLServerSocket server;
    private Thread acceptThread;
    private byte[] patchedManifest;

    public ProxyServer(Context context, String targetUrl, String manifestUrl, Uri directoryUri, Listener listener) {
        this.context = context.getApplicationContext();
        this.pakTarget = URI.create(targetUrl);
        this.manifestTarget = URI.create(manifestUrl);
        this.directoryUri = directoryUri;
        this.listener = listener;
    }

    /** Must run before /system/etc/hosts is redirected. */
    public void prepare() throws Exception {
        PakIndex index = PakDirectoryManager.scan(context, directoryUri);
        pakEntries.clear();
        pakEntries.putAll(index.all());
        if (pakEntries.isEmpty()) throw new IllegalStateException("所选目录内未发现 .pak 文件");

        rememberOrigin(manifestTarget.getHost());

        String original;
        try {
            original = downloadText(manifestTarget.toString());
            log("已读取远端 linkspak.txt");
        } catch (Throwable t) {
            original = new String(readAsset("linkspak_fallback.txt"), StandardCharsets.UTF_8);
            log("远端 linkspak.txt 读取失败，使用内置模板: " + t.getClass().getSimpleName());
        }
        PatchResult patched = patchManifest(original);
        if (patched.changed == 0) throw new IllegalStateException("linkspak.txt 中未匹配到本地 PAK 文件");
        if (pakHosts.isEmpty() && pakTarget.getHost() != null) pakHosts.add(pakTarget.getHost().toLowerCase(Locale.US));
        for (String host : pakHosts) rememberOrigin(host);
        patchedManifest = patched.text.getBytes(StandardCharsets.UTF_8);
        log("linkspak.txt 已动态修正 " + patched.changed + " 个 PAK 大小；本地索引 " + pakEntries.size() + " 个");
    }

    private void rememberOrigin(String host) throws Exception {
        if (host == null || host.isEmpty() || originAddresses.containsKey(host.toLowerCase(Locale.US))) return;
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) throw new IllegalStateException("无法解析真实地址: " + host);
        originAddresses.put(host.toLowerCase(Locale.US), addresses);
        StringBuilder s = new StringBuilder("已缓存真实地址 ").append(host).append(": ");
        for (int i = 0; i < addresses.length; i++) {
            if (i > 0) s.append(", ");
            s.append(addresses[i].getHostAddress());
        }
        log(s.toString());
    }

    public String[] interceptedHosts() {
        ArrayList<String> hosts = new ArrayList<>();
        if (manifestTarget.getHost() != null) hosts.add(manifestTarget.getHost());
        for (String host : pakHosts) {
            if (host != null && !hosts.contains(host)) hosts.add(host);
        }
        return hosts.toArray(new String[0]);
    }

    public void start(int port) throws Exception {
        String manifestHost = manifestTarget.getHost();
        if (manifestHost == null || pakHosts.isEmpty()) throw new IllegalArgumentException("URL 缺少 Host");
        if (patchedManifest == null || pakEntries.isEmpty()) throw new IllegalStateException("尚未 prepare");

        byte[] caPem = readAsset("pakredirect_ca.pem");
        byte[] caKey = readAsset("pakredirect_ca_key.pem");
        CertUtil.GeneratedIdentity id = CertUtil.issueServerIdentity(interceptedHosts(), caPem, caKey);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", id.privateKey, new char[0], new java.security.cert.Certificate[]{id.leaf, id.ca});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
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
                s.setSoTimeout(20000);
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
            String pakName = pakNameFromPath(path);
            PakEntry entry = pakEntries.get(pakName.toLowerCase(Locale.US));

            if (pakHosts.contains(host.toLowerCase(Locale.US)) && entry != null) {
                serveFile(out, entry, method.equals("HEAD"), h.get("range"));
                int n = hits.incrementAndGet();
                if (listener != null) listener.onHit(n);
                log("PAK 命中 #" + n + ": " + entry.name + " | " + method + " " + requestTarget + (h.get("range") == null ? "" : " | " + h.get("range")));
                return;
            }

            if (host.equalsIgnoreCase(manifestTarget.getHost()) && path.equals(rawPath(manifestTarget))) {
                serveManifest(out, method.equals("HEAD"));
                log("linkspak.txt 命中：已返回动态 PAK 索引");
                return;
            }

            if (originAddresses.containsKey(host.toLowerCase(Locale.US))) {
                log("透传: " + host + " " + method + " " + requestTarget);
                forwardHttps(out, host, headerText);
                return;
            }

            log("未管理 Host: " + host + " " + method + " " + requestTarget);
            sendText(out, 404, "PakRedirect: host not managed");
        } catch (Throwable t) {
            log("连接失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Forward unmatched requests to the real origin IP cached before hosts redirection.
     * This avoids the 127.0.0.1 DNS loop while preserving normal CDN endpoints.
     */
    private void forwardHttps(OutputStream clientOut, String host, String originalHeader) throws Exception {
        InetAddress[] addresses = originAddresses.get(host.toLowerCase(Locale.US));
        if (addresses == null || addresses.length == 0) throw new IllegalStateException("没有真实地址缓存: " + host);
        Throwable last = null;
        for (InetAddress address : addresses) {
            try {
                forwardViaAddress(clientOut, host, originalHeader, address);
                return;
            } catch (Throwable t) {
                last = t;
            }
        }
        throw new IllegalStateException("真实服务器连接失败: " + host + (last == null ? "" : " / " + last.getMessage()), last);
    }

    private void forwardViaAddress(OutputStream clientOut, String host, String originalHeader, InetAddress address) throws Exception {
        Socket raw = new Socket();
        raw.connect(new java.net.InetSocketAddress(address, 443), 8000);
        raw.setSoTimeout(20000);
        SSLSocketFactory sf = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket upstream = (SSLSocket) sf.createSocket(raw, host, 443, true)) {
            upstream.setUseClientMode(true);
            upstream.startHandshake();
            HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
            if (!verifier.verify(host, upstream.getSession())) throw new javax.net.ssl.SSLHandshakeException("Hostname verification failed: " + host);

            try (BufferedOutputStream uout = new BufferedOutputStream(upstream.getOutputStream());
                 BufferedInputStream uin = new BufferedInputStream(upstream.getInputStream())) {
                String request = makeForwardHeader(originalHeader, host);
                uout.write(request.getBytes(StandardCharsets.ISO_8859_1));
                uout.flush();

                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = uin.read(buf)) >= 0) clientOut.write(buf, 0, n);
                clientOut.flush();
            }
        }
    }

    private static String makeForwardHeader(String originalHeader, String host) {
        String[] lines = originalHeader.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        if (lines.length > 0) out.append(lines[0]).append("\r\n");
        boolean hasHost = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isEmpty()) continue;
            int p = line.indexOf(':');
            if (p <= 0) continue;
            String name = line.substring(0, p).trim();
            if (name.equalsIgnoreCase("connection") || name.equalsIgnoreCase("proxy-connection")) continue;
            if (name.equalsIgnoreCase("host")) hasHost = true;
            out.append(line).append("\r\n");
        }
        if (!hasHost) out.append("Host: ").append(host).append("\r\n");
        out.append("Connection: close\r\n\r\n");
        return out.toString();
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

    private void serveFile(OutputStream out, PakEntry entry, boolean headOnly, String rangeHeader) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Uri fileUri = Uri.parse(entry.uri);
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(fileUri, "r")) {
            if (pfd == null) { sendText(out, 500, "Cannot open local PAK"); return; }
            long total = pfd.getStatSize();
            if (total < 0) total = entry.size;
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

    private PatchResult patchManifest(String original) {
        String[] lines = original.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder(original.length());
        int changed = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String patched = patchManifestLine(line);
            if (!patched.equals(line)) changed++;
            out.append(patched);
            if (i < lines.length - 1) out.append('\n');
        }
        return new PatchResult(out.toString(), changed);
    }

    private String patchManifestLine(String line) {
        String[] fields = line.split(",", -1);
        if (fields.length < 4) return line;
        String name = fields[2].trim();
        PakEntry entry = pakEntries.get(name.toLowerCase(Locale.US));
        if (entry == null) return line;
        try {
            URI uri = URI.create(fields[0].trim());
            if (uri.getHost() != null) pakHosts.add(uri.getHost().toLowerCase(Locale.US));
        } catch (Throwable ignored) {}
        fields[3] = leadingSpaces(fields[3]) + entry.size + trailingSpaces(fields[3]);
        StringBuilder out = new StringBuilder(line.length() + 16);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append(',');
            out.append(fields[i]);
        }
        return out.toString();
    }

    private String downloadText(String url) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PakRedirect/1.2");
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

    private static String pakNameFromPath(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
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

    private static final class PatchResult {
        final String text;
        final int changed;

        PatchResult(String text, int changed) {
            this.text = text;
            this.changed = changed;
        }
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
