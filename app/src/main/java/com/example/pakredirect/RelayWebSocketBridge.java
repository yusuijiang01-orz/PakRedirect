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

    public static boolean probe(VpnService vpnService, String token) {
        if (token == null || token.trim().isEmpty()) return false;

        OkHttpClient client = new OkHttpClient.Builder()
                .socketFactory(new ProtectedSocketFactory(vpnService))
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean opened = new AtomicBoolean(false);
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
                completed.countDown();
            }
        });

        try {
            completed.await(18, TimeUnit.SECONDS);
            return opened.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (!opened.get()) socket.cancel();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
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
