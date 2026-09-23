package com.example.pakredirect;

import android.net.VpnService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Bridges one local SOCKS5 TCP stream to the fixed RYLUX WebSocket relay. */
public final class RelayWebSocketBridge {
    public static final String RELAY_URL = "wss://relay.lovenom.eu.org/rylux-game";

    public interface Listener {
        void onOpen();
        void onBinary(byte[] data);
        void onClosed();
        void onFailure(Throwable error);
    }

    private final OkHttpClient client;
    private volatile WebSocket webSocket;

    public RelayWebSocketBridge(VpnService vpnService) {
        client = new OkHttpClient.Builder()
                .socketFactory(new ProtectedSocketFactory(vpnService))
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    }

    public void connect(String token, Listener listener) {
        if (token == null || token.trim().isEmpty()) {
            listener.onFailure(new IllegalArgumentException("relay token is empty"));
            return;
        }
        Request request = new Request.Builder()
                .url(RELAY_URL)
                .header("Authorization", "Bearer " + token.trim())
                .build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                listener.onOpen();
            }

            @Override public void onMessage(WebSocket socket, ByteString bytes) {
                listener.onBinary(bytes.toByteArray());
            }

            @Override public void onClosing(WebSocket socket, int code, String reason) {
                listener.onClosed();
            }

            @Override public void onClosed(WebSocket socket, int code, String reason) {
                listener.onClosed();
            }

            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                listener.onFailure(error);
            }
        });
    }

    public boolean send(byte[] data) {
        WebSocket socket = webSocket;
        return socket != null && data != null && socket.send(ByteString.of(data));
    }

    public void close() {
        WebSocket socket = webSocket;
        if (socket != null) socket.close(1000, "relay session closed");
    }

    /** Returns null on success, otherwise a user-safe explanation without exposing the token. */
    public static String probeFailure(VpnService vpnService, String token) {
        if (token == null || token.trim().isEmpty()) return "relay 凭据为空，请重新登录";

        OkHttpClient client = new OkHttpClient.Builder()
                .socketFactory(new ProtectedSocketFactory(vpnService))
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean opened = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();
        Request request = new Request.Builder()
                .url(RELAY_URL)
                .header("Authorization", "Bearer " + token.trim())
                .build();

        WebSocket socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                opened.set(true);
                completed.countDown();
                webSocket.close(1000, "relay probe");
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (response != null) {
                    int code = response.code();
                    if (code == 401) {
                        failure.set("relay 凭据被拒绝（HTTP 401），请检查服务端密钥配置");
                    } else if (code == 502) {
                        failure.set("relay Worker 无法连接游戏服务器（HTTP 502）");
                    } else {
                        failure.set("relay 握手失败（HTTP " + code + "）");
                    }
                } else if (t instanceof java.net.UnknownHostException) {
                    failure.set("无法解析 relay 域名，请检查手机 DNS 或网络");
                } else if (t instanceof javax.net.ssl.SSLException) {
                    failure.set("relay TLS 安全连接失败，请检查手机时间和网络");
                } else if (t instanceof java.net.SocketTimeoutException) {
                    failure.set("连接 relay 超时，请检查手机网络后重试");
                } else if (t instanceof IOException) {
                    String detail = safeIoFailureDetail(t, token);
                    if ("unable to protect relay socket from VPN".equalsIgnoreCase(detail)) {
                        failure.set("Android 未能保护 relay 连接免受 VPN 路由影响（protect=false）");
                    } else {
                        failure.set("relay 网络连接失败：" + detail);
                    }
                } else {
                    failure.set("relay 连接失败（" + t.getClass().getSimpleName() + "）");
                }
                completed.countDown();
            }
        });

        try {
            boolean finished = completed.await(18, TimeUnit.SECONDS);
            if (opened.get()) return null;
            if (!finished) return "连接 relay 超时（18 秒），请检查手机网络";
            String message = failure.get();
            return message == null ? "relay 握手失败，请重试" : message;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "relay 检查已中断，请重试";
        } finally {
            if (!opened.get()) socket.cancel();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    private static String safeIoFailureDetail(Throwable error, String token) {
        Throwable detailError = error;
        while (detailError.getCause() != null && detailError.getCause() != detailError) {
            detailError = detailError.getCause();
        }
        String detail = detailError.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getMessage();
        }
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }
        if (token != null && !token.isEmpty()) {
            detail = detail.replace(token, "[已隐藏]");
        }
        detail = detail.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [已隐藏]")
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        if (detail.length() > 110) detail = detail.substring(0, 107) + "…";
        return detail;
    }

    private static final class ProtectedSocketFactory extends SocketFactory {
        private final VpnService vpnService;

        ProtectedSocketFactory(VpnService vpnService) {
            this.vpnService = vpnService;
        }

        private Socket prepare(Socket socket) throws IOException {
            if (!vpnService.protect(socket)) {
                try { socket.close(); } catch (IOException ignored) {}
                throw new IOException("unable to protect relay socket from VPN");
            }
            return socket;
        }

        private Socket connect(Socket socket, SocketAddress address) throws IOException {
            prepare(socket);
            socket.connect(address, 15000);
            return socket;
        }

        @Override public Socket createSocket() throws IOException {
            return prepare(new Socket());
        }

        @Override public Socket createSocket(String host, int port) throws IOException {
            return connect(new Socket(), new InetSocketAddress(host, port));
        }

        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            return connect(socket, new InetSocketAddress(host, port));
        }

        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return connect(new Socket(), new InetSocketAddress(host, port));
        }

        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            return connect(socket, new InetSocketAddress(host, port));
        }
    }
}
