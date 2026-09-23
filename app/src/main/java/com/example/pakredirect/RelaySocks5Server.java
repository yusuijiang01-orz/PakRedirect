package com.example.pakredirect;

import android.net.VpnService;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal loopback SOCKS5 server restricted to explicitly approved game endpoints. */
public final class RelaySocks5Server implements Closeable {
    public static final int PORT = 18481;
    private static final String LEGACY_GAME_HOST = "103.206.217.41";
    private static final int LEGACY_GAME_PORT = 6664;
    private static final String CURRENT_GAME_HOST = "103.206.217.28";
    private static final int CURRENT_GAME_PORT = 6662;
    private static final int ALTERNATE_GAME_PORT = 5622;

    public interface Listener {
        void onSessionOpened();
        void onSessionClosed();
        void onRelayConnected();
        void onRelayClosed();
        void onRelayUnavailable(String userMessage);
    }

    private final VpnService vpnService;
    private final String relayToken;
    private final Listener listener;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "RYLUX-Relay-SOCKS");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile ServerSocket serverSocket;

    public RelaySocks5Server(VpnService vpnService, String relayToken, Listener listener) {
        this.vpnService = vpnService;
        this.relayToken = relayToken;
        this.listener = listener;
    }

    public void start() throws IOException {
        ServerSocket next = new ServerSocket();
        next.setReuseAddress(true);
        // hev is configured with 127.0.0.1. Android may resolve
        // getLoopbackAddress() to ::1, which leaves the IPv4 SOCKS endpoint
        // unreachable and silently blackholes every packet routed through TUN.
        next.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        serverSocket = next;
        Log.i("RYLUX-Relay", "SOCKS5 listener bound to 127.0.0.1:" + PORT);
        workers.execute(() -> acceptLoop(next));
    }

    private void acceptLoop(ServerSocket socket) {
        while (!closed) {
            try {
                Socket client = socket.accept();
                clients.add(client);
                workers.execute(() -> handle(client));
            } catch (IOException e) {
                if (!closed) close();
                return;
            }
        }
    }

    private void handle(Socket client) {
        listener.onSessionOpened();
        RelayWebSocketBridge bridge = null;
        try (Socket ignored = client;
             InputStream input = new BufferedInputStream(client.getInputStream());
             OutputStream output = new BufferedOutputStream(client.getOutputStream())) {
            client.setTcpNoDelay(true);
            client.setSoTimeout(1000);

            if (readByte(input) != 5) return;
            int methodCount = readByte(input);
            for (int i = 0; i < methodCount; i++) readByte(input);
            output.write(new byte[]{5, 0});
            output.flush();

            if (readByte(input) != 5) return;
            int command = readByte(input);
            readByte(input); // RSV
            int addressType = readByte(input);
            String destination = readDestination(input, addressType);
            int destinationPort = (readByte(input) << 8) | readByte(input);
            String relayPath = command == 1 ? relayPathFor(destination, destinationPort) : null;
            if (relayPath == null) {
                Log.w("RYLUX-Relay", "Rejected SOCKS request command=" + command
                        + " destination=" + destination + ":" + destinationPort);
                sendReply(output, 2);
                return;
            }
            Log.i("RYLUX-Relay", "Opening game TCP relay for " + destination + ":" + destinationPort);

            AtomicBoolean relayClosed = new AtomicBoolean(false);
            AtomicBoolean localReady = new AtomicBoolean(false);
            CountDownLatch relayOpened = new CountDownLatch(1);
            AtomicReference<String> relayFailure = new AtomicReference<>();
            Object outputLock = new Object();
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            final RelayWebSocketBridge relay = new RelayWebSocketBridge(vpnService);
            bridge = relay;
            relay.connect(relayToken, relayPath, new RelayWebSocketBridge.Listener() {
                @Override public void onOpen() {
                    listener.onRelayConnected();
                    relayOpened.countDown();
                }

                @Override public void onBinary(byte[] data) {
                    if (data == null || data.length == 0) return;
                    synchronized (outputLock) {
                        try {
                            if (!localReady.get()) {
                                if (pending.size() + data.length > 1024 * 1024) {
                                    relayClosed.set(true);
                                    relay.close();
                                    return;
                                }
                                pending.write(data);
                            } else {
                                output.write(data);
                                output.flush();
                            }
                        } catch (IOException e) {
                            relayClosed.set(true);
                            relay.close();
                        }
                    }
                }

                @Override public void onClosed() {
                    relayClosed.set(true);
                    listener.onRelayClosed();
                    relayOpened.countDown();
                }

                @Override public void onFailure(String userMessage) {
                    relayFailure.compareAndSet(null, userMessage);
                    relayClosed.set(true);
                    listener.onRelayUnavailable(userMessage);
                    relayOpened.countDown();
                }
            });

            if (!relayOpened.await(18, TimeUnit.SECONDS) || relayFailure.get() != null || relayClosed.get()) {
                Log.e("RYLUX-Relay", "Game relay did not open for " + destination + ":" + destinationPort
                        + (relayFailure.get() == null ? " (timeout/closed)" : ": " + relayFailure.get()));
                sendReply(output, 1);
                return;
            }

            Log.i("RYLUX-Relay", "Game relay WebSocket opened for " + destination + ":" + destinationPort);
            sendReply(output, 0);
            synchronized (outputLock) {
                localReady.set(true);
                pending.writeTo(output);
                pending.reset();
                output.flush();
            }

            byte[] buffer = new byte[16 * 1024];
            while (!closed && !relayClosed.get()) {
                int count;
                try {
                    count = input.read(buffer);
                } catch (SocketTimeoutException ignoredTimeout) {
                    continue;
                }
                if (count < 0) break;
                if (count == 0) continue;
                if (!relay.send(Arrays.copyOf(buffer, count))) break;
            }
        } catch (EOFException ignored) {
            // The game closed the local SOCKS stream.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // A disconnect is normal when the game exits or the relay is stopped.
        } finally {
            if (bridge != null) bridge.close();
            clients.remove(client);
            listener.onSessionClosed();
        }
    }

    private static String relayPathFor(String host, int port) {
        if (LEGACY_GAME_HOST.equals(host) && LEGACY_GAME_PORT == port) return RelayWebSocketBridge.LEGACY_GAME_PATH;
        if (CURRENT_GAME_HOST.equals(host) && CURRENT_GAME_PORT == port) return RelayWebSocketBridge.CURRENT_GAME_PATH;
        if (CURRENT_GAME_HOST.equals(host) && ALTERNATE_GAME_PORT == port) return RelayWebSocketBridge.ALTERNATE_GAME_PATH;
        return null;
    }

    private static String readDestination(InputStream input, int addressType) throws IOException {
        if (addressType == 1) {
            byte[] address = readFully(input, 4);
            return InetAddress.getByAddress(address).getHostAddress();
        }
        if (addressType == 3) {
            int length = readByte(input);
            return new String(readFully(input, length), StandardCharsets.US_ASCII);
        }
        if (addressType == 4) {
            readFully(input, 16);
            return "";
        }
        throw new IOException("unsupported SOCKS5 address type");
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) throw new EOFException("unexpected end of SOCKS5 request");
            offset += count;
        }
        return result;
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) throw new EOFException("unexpected end of SOCKS5 request");
        return value & 0xff;
    }

    private static void sendReply(OutputStream output, int status) throws IOException {
        output.write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        ServerSocket socket = serverSocket;
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        for (Socket client : clients) try { client.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
