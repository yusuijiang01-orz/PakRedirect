package com.example.pakredirect;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Real ADB screenshots, OCR, and touches for one bounded recovered-Lua login run. */
final class LiveLuaDeviceHost implements AutoCloseable {
    static final class Row {
        final String text;
        final double x, y, confidence;
        Row(String text, double x, double y, double confidence) {
            this.text = text; this.x = x; this.y = y; this.confidence = confidence;
        }
    }
    static final class Frame {
        final BufferedImage image;
        final LegacyCoordinateMapper mapper;
        final List<Row> rows;
        final int number;
        final long capturedAt;
        Frame(BufferedImage image, List<Row> rows, int number) {
            this.image = image;
            this.mapper = new LegacyCoordinateMapper(720, 1280, image.getWidth(), image.getHeight());
            this.rows = rows;
            this.number = number;
            this.capturedAt = System.nanoTime();
        }
    }

    private final String adb, serial;
    private final Path evidence;
    private final long deadline;
    private final ExecutorService io = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "live-lua-device-io"); t.setDaemon(true); return t;
    });
    private final Process ocr;
    private final BufferedReader ocrOutput;
    private final BufferedWriter ocrInput;
    private Frame current;
    private int captures, actions;

    LiveLuaDeviceHost(long deadline) throws Exception {
        this.adb = requiredFile("RYLUX_ADB_EXECUTABLE");
        this.serial = requiredText("RYLUX_ADB_DEVICE");
        String python = requiredFile("RYLUX_OCR_PYTHON");
        this.evidence = Paths.get(requiredText("RYLUX_LIVE_EVIDENCE_DIR")).toAbsolutePath().normalize();
        Files.createDirectories(evidence);
        if (!Files.isDirectory(evidence) || !Files.isWritable(evidence))
            throw new IOException("Live evidence directory is not writable: " + evidence);
        this.deadline = deadline;
        Path worker = Paths.get("tools", "lua-regression", "live_ocr_worker.py").toAbsolutePath();
        if (!Files.isRegularFile(worker)) throw new IOException("Missing OCR worker: " + worker);
        ocr = new ProcessBuilder(python, "-u", worker.toString())
                .redirectError(evidence.resolve("ocr-stderr.log").toFile()).start();
        ocrInput = new BufferedWriter(new OutputStreamWriter(ocr.getOutputStream(), StandardCharsets.UTF_8));
        ocrOutput = new BufferedReader(new InputStreamReader(ocr.getInputStream(), StandardCharsets.UTF_8));
        try {
            String ready = timedLine(30_000);
            if (!"READY".equals(ready)) throw new IOException("RapidOCR worker did not initialize; see ocr-stderr.log");
            event("worker", "ready");
        } catch (Exception failure) {
            try { ocrInput.close(); } catch (IOException ignored) { }
            try { ocrOutput.close(); } catch (IOException ignored) { }
            if (ocr.isAlive()) ocr.destroyForcibly();
            io.shutdownNow();
            throw failure;
        }
    }

    private static String requiredText(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Required live test environment variable missing: " + name);
        return value.trim();
    }
    private static String requiredFile(String name) {
        String value = requiredText(name);
        if (!Files.isRegularFile(Paths.get(value))) throw new IllegalStateException(name + " is not a file: " + value);
        return value;
    }

    void checkDeadline() throws IOException {
        if (System.nanoTime() >= deadline) throw new IOException("Live login exceeded its 3-minute deadline");
    }

    private long remaining(long cap) throws IOException {
        checkDeadline();
        return Math.max(1, Math.min(cap, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    }

    private byte[] adb(long cap, String... args) throws Exception {
        checkDeadline();
        List<String> command = new ArrayList<>();
        command.add(adb); command.add("-s"); command.add(serial); command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        Future<byte[]> bytes = io.submit(() -> {
            try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384]; int length;
                while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
                return output.toByteArray();
            }
        });
        try {
            long wait = remaining(cap);
            byte[] output = bytes.get(wait, TimeUnit.MILLISECONDS);
            if (!process.waitFor(remaining(cap), TimeUnit.MILLISECONDS)) throw new TimeoutException();
            if (process.exitValue() != 0) throw new IOException("ADB failed (" + process.exitValue() + "): "
                    + new String(output, StandardCharsets.UTF_8));
            return output;
        } catch (TimeoutException timeout) {
            throw new IOException("ADB timed out: " + String.join(" ", args), timeout);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            bytes.cancel(true);
        }
    }

    void requireForeground() throws Exception {
        String windows = new String(adb(8_000, "shell", "dumpsys", "window"), StandardCharsets.UTF_8);
        int displaysStart = windows.indexOf("WINDOW MANAGER DISPLAY CONTENTS");
        String displays = displaysStart < 0 ? "" : windows.substring(displaysStart);
        Matcher blocks = Pattern.compile("(?ms)^\\s*Display: mDisplayId=(\\d+)(.*?)(?=^\\s*Display: mDisplayId=|^WINDOW MANAGER |\\z)")
                .matcher(displays);
        boolean foreground = false;
        while (blocks.find()) {
            if (!"0".equals(blocks.group(1))) continue;
            String block = blocks.group(2);
            boolean guest = Pattern.compile("(?m)^\\s*mCurrentFocus=Window\\{[^\\r\\n]*\\scom\\.tepaylink\\.tamgioiphantranhmobile/[^}\\r\\n]+\\}")
                    .matcher(block).find();
            boolean proxy = Pattern.compile("(?m)^\\s*mFocusedApp=ActivityRecord\\{[^\\r\\n]*\\scom\\.example\\.pakredirect/top\\.niunaijun\\.blackbox\\.proxy\\.ProxyActivity\\$P\\d+\\s[^}\\r\\n]*\\}")
                    .matcher(block).find();
            foreground = guest && proxy;
            break;
        }
        if (!foreground) {
            event("foreground", "display0 guest/proxy focus mismatch");
            throw new IOException("BlackBox proxy or localized game guest lost foreground; see events.tsv");
        }
    }

    Frame capture() throws Exception {
        requireForeground();
        byte[] png = adb(12_000, "exec-out", "screencap", "-p");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() < 1000 || image.getHeight() < 500)
            throw new IOException("ADB returned an unreadable or unexpected game screenshot");
        int number = ++captures;
        Path path = evidence.resolve(String.format("capture-%03d.png", number));
        Files.write(path, png);
        List<Row> rows = recognize(path);
        StringBuilder transcript = new StringBuilder("text_base64\traw_center_x\traw_center_y\tconfidence\n");
        for (Row row : rows) transcript.append(Base64.getEncoder().encodeToString(row.text.getBytes(StandardCharsets.UTF_8)))
                .append('\t').append(row.x).append('\t').append(row.y).append('\t').append(row.confidence).append('\n');
        Files.writeString(evidence.resolve(String.format("capture-%03d.ocr.tsv", number)), transcript,
                StandardCharsets.UTF_8);
        current = new Frame(image, rows, number);
        event("capture", number + " " + image.getWidth() + "x" + image.getHeight() + " rows=" + rows.size());
        return current;
    }

    Frame frame() throws Exception {
        if (current == null || System.nanoTime() - current.capturedAt > TimeUnit.MILLISECONDS.toNanos(700)) return capture();
        checkDeadline();
        return current;
    }

    void invalidate() { current = null; }

    private String timedLine(long cap) throws Exception {
        Future<String> line = io.submit((Callable<String>) ocrOutput::readLine);
        try { return line.get(remaining(cap), TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { throw new IOException("RapidOCR worker read timed out", timeout); }
        finally { line.cancel(true); }
    }

    private List<Row> recognize(Path path) throws Exception {
        String request = Base64.getEncoder().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
        ocrInput.write("IMAGE\t" + request + "\n"); ocrInput.flush();
        List<Row> rows = new ArrayList<>();
        while (true) {
            String line = timedLine(12_000);
            if (line == null) throw new IOException("RapidOCR worker exited; see ocr-stderr.log");
            if ("END".equals(line)) return rows;
            String[] fields = line.split("\t", -1);
            if (fields.length == 2 && "ERROR".equals(fields[0]))
                throw new IOException("RapidOCR failed: " + new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8));
            if (fields.length != 5 || !"ROW".equals(fields[0])) throw new IOException("Malformed RapidOCR response");
            rows.add(new Row(new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8),
                    Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
        }
    }

    void tap(double referenceX, double referenceY, String label) throws Exception {
        Frame before = frame();
        requireForeground();
        int x = before.mapper.toScreenX(referenceX), y = before.mapper.toScreenY(referenceY);
        if (x < 0 || y < 0 || x >= before.image.getWidth() || y >= before.image.getHeight())
            throw new IOException("Lua requested out-of-screen touch: " + referenceX + "," + referenceY);
        event("action", (++actions) + " " + label + " ref=" + referenceX + "," + referenceY
                + " raw=" + x + "," + y + " source=capture-" + before.number);
        adb(8_000, "shell", "input", "tap", Integer.toString(x), Integer.toString(y));
        invalidate();
    }

    void event(String type, String safeDetail) throws IOException {
        Files.writeString(evidence.resolve("events.tsv"), Instant.now() + "\t" + type + "\t" + safeDetail + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    int actionCount() { return actions; }
    Path evidence() { return evidence; }

    @Override public void close() {
        try { ocrInput.close(); } catch (IOException ignored) { }
        if (ocr.isAlive()) ocr.destroyForcibly();
        io.shutdownNow();
    }
}
