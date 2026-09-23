package com.example.pakredirect;

import android.net.VpnService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

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
        void onFailure(String userMessage);
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
            listener.onFailure("relay 凭据为空，请重新登录");
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
                listener.onFailure(failureMessage(error, response, token));
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

    /** Formats a relay transport/handshake failure without exposing the short-lived token. */
    private static String failureMessage(Throwable error, Response response, String token) {
        if (response != null) {
            int code = response.code();
            if (code == 401) return "relay 凭据被拒绝（HTTP 401），请重新登录后重试";
            if (code == 502) return "relay Worker 无法连接游戏服务器（HTTP 502）";
            return "relay 握手失败（HTTP " + code + "）";
        }
        if (findCause(error, java.net.UnknownHostException.class) != null) {
            return "relay DNS 解析失败，请检查手机 DNS 或网络";
        }
        if (findCause(error, javax.net.ssl.SSLException.class) != null) {
            return "relay TLS 安全连接失败，请检查手机时间和网络";
        }
        if (findCause(error, java.net.SocketTimeoutException.class) != null) {
            return "relay TCP/443 连接超时，请检查手机网络后重试";
        }
        if (error instanceof IOException) {
            String detail = safeIoFailureDetail(error, token);
            if (hasCauseMessage(error, "unable to protect relay socket from VPN")) {
                return "Android 未能保护 relay 连接免受 VPN 路由影响（protect=false）";
            }
            if (findCause(error, java.net.ConnectException.class) != null
                    || findCause(error, java.net.NoRouteToHostException.class) != null) {
                return "relay TCP/443 连接失败：" + detail;
            }
            return "relay 网络连接失败：" + detail;
        }
        return "relay 连接失败（" + error.getClass().getSimpleName() + "）";
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable cause = error;
        while (cause != null) {
            if (type.isInstance(cause)) return type.cast(cause);
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return null;
    }

    private static boolean hasCauseMessage(Throwable error, String expected) {
        Throwable cause = error;
        while (cause != null) {
            if (expected.equalsIgnoreCase(cause.getMessage())) return true;
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return false;
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
