package com.example.pakredirect;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Receives read-only bag snapshots over an authenticated app-private local socket. */
final class SandboxBagSnapshotRelay implements AutoCloseable {
    private static final String TAG = "RYLUX-BagRelay";
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String ENDPOINT_FILE = "sandbox-bag-relay.endpoint";
    private static final long SNAPSHOT_MAX_AGE_MS = 5_000L;
    private final File endpointFile;
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile SandboxBagSnapshot latest;
    private volatile LocalServerSocket server;
    private volatile LocalSocket activeClient;
    private volatile Thread acceptThread;
    private volatile int generation;
    private String lastLoggedStatus = "";
    private String socketName;
    private String token;

    SandboxBagSnapshotRelay(Context context) {
        endpointFile = new File(context.getApplicationContext().getFilesDir(), ENDPOINT_FILE);
    }

    void start() throws IOException {
        synchronized (lock) {
            stopLocked();
            socketName = "rylux_bag_" + UUID.randomUUID().toString().replace("-", "");
            token = UUID.randomUUID().toString().replace("-", "");
            LocalServerSocket opened = new LocalServerSocket(socketName);
            try {
                writeEndpoint(socketName, token);
            } catch (IOException failure) {
                opened.close();
                socketName = null;
                token = null;
                throw failure;
            }
            server = opened;
            running = true;
            latest = null;
            int runGeneration = ++generation;
            acceptThread = new Thread(() -> acceptLoop(opened, runGeneration),
                    "rylux-bag-snapshot-relay");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    SandboxBagSnapshot snapshot() {
        SandboxBagSnapshot current = latest;
        return current != null && current.isFresh(SystemClock.elapsedRealtime(), SNAPSHOT_MAX_AGE_MS)
                ? current : null;
    }

    private void acceptLoop(LocalServerSocket listening, int runGeneration) {
        while (running && generation == runGeneration) {
            LocalSocket client = null;
            try {
                client = listening.accept();
                activeClient = client;
                client.setSoTimeout(3_000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        client.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line != null) acceptPacket(line);
            } catch (IOException failure) {
                if (running && generation == runGeneration)
                    Log.d(TAG, "Snapshot client disconnected: " + failure.getMessage());
            } finally {
                activeClient = null;
                if (client != null) {
                    try { client.close(); }
                    catch (IOException ignored) { }
                }
            }
        }
    }

    private void acceptPacket(String line) {
        String expectedToken = token;
        if (expectedToken == null) return;
        String[] packet = line.split("\\|", 3);
        if (packet.length != 3 || !expectedToken.equals(packet[0])
                || !GAME_PACKAGE.equals(packet[1])) return;
        String[] values = packet[2].split("\\|", -1);
        if (values.length == 5 && "OK".equals(values[0])) {
            String buildId = values[1];
            int freeSlots;
            int currentWeight;
            int maxWeight;
            try {
                freeSlots = Integer.parseInt(values[2]);
                currentWeight = Integer.parseInt(values[3]);
                maxWeight = Integer.parseInt(values[4]);
            } catch (NumberFormatException invalid) {
                latest = new SandboxBagSnapshot(false, "INVALID_VALUES", buildId,
                        -1, -1, -1, SystemClock.elapsedRealtime());
                return;
            }
            boolean safeValues = isSupportedBuildId(buildId) && freeSlots >= 0 && freeSlots <= 200
                    && currentWeight >= 0 && currentWeight <= 1_000_000
                    && maxWeight > 0 && maxWeight <= 1_000_000;
            latest = new SandboxBagSnapshot(safeValues, safeValues ? "OK" : "INVALID_VALUES",
                    buildId, safeValues ? freeSlots : -1, safeValues ? currentWeight : -1,
                    safeValues ? maxWeight : -1, SystemClock.elapsedRealtime());
            logStatus(safeValues ? "OK buildId=" + buildId : "INVALID_VALUES buildId=" + buildId);
            return;
        }
        String status = values.length > 0 ? values[0] : "INVALID_PACKET";
        String buildId = values.length > 1 ? values[1] : "";
        latest = new SandboxBagSnapshot(false, status, buildId, -1, -1, -1,
                SystemClock.elapsedRealtime());
        logStatus(status + (buildId.isEmpty() ? "" : " buildId=" + buildId));
    }

    private void logStatus(String status) {
        if (status.equals(lastLoggedStatus)) return;
        lastLoggedStatus = status;
        Log.i(TAG, "Sandbox game bag reader status: " + status);
    }

    private static boolean isSupportedBuildId(String buildId) {
        return SandboxBagMemoryProfile.isSupported(buildId);
    }

    private void writeEndpoint(String name, String secret) throws IOException {
        AtomicFile atomicFile = new AtomicFile(endpointFile);
        java.io.FileOutputStream output = null;
        try {
            output = atomicFile.startWrite();
            output.write((name + "\n" + secret + "\n").getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(output);
        } catch (IOException failure) {
            if (output != null) atomicFile.failWrite(output);
            throw failure;
        }
    }

    private void stopLocked() {
        running = false;
        generation++;
        LocalSocket client = activeClient;
        activeClient = null;
        if (client != null) {
            try { client.close(); }
            catch (IOException ignored) { }
        }
        LocalServerSocket listening = server;
        server = null;
        if (listening != null) {
            try { listening.close(); }
            catch (IOException ignored) { }
        }
        Thread worker = acceptThread;
        acceptThread = null;
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        socketName = null;
        token = null;
        latest = null;
        new AtomicFile(endpointFile).delete();
    }

    @Override public void close() {
        synchronized (lock) { stopLocked(); }
    }
}
