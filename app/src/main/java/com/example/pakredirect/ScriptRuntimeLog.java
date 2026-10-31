package com.example.pakredirect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Private, bounded UTF-8 runtime log. Has no Android dependency. */
final class ScriptRuntimeLog {
    static final String AUTO_SAVE = "自动保存调试日志";
    static final String SOURCE_CONTEXT = "日志行号文件名";
    static final String FILE_NAME = "script-runtime.log";
    static final int MAX_FILE_BYTES = 512 * 1024;
    static final int FILE_COUNT = 3;
    private static final int MAX_EVENT_BYTES = 64 * 1024;
    private static final String TRUNCATED = "\n…(日志已截断)\n";

    private final File directory;
    private final int maxFileBytes;
    private final int fileCount;
    private final LongSupplier clock;
    private boolean enabled = true;
    private boolean includeContext = true;

    ScriptRuntimeLog(File directory) {
        this(directory, MAX_FILE_BYTES, FILE_COUNT, System::currentTimeMillis);
    }

    ScriptRuntimeLog(File directory, int maxFileBytes, int fileCount, LongSupplier clock) {
        if (maxFileBytes < 128 || fileCount < 1) throw new IllegalArgumentException("Invalid log limits");
        this.directory = directory;
        this.maxFileBytes = maxFileBytes;
        this.fileCount = fileCount;
        this.clock = clock;
    }

    synchronized void configure(boolean enabled, boolean includeContext) {
        this.enabled = enabled;
        this.includeContext = includeContext;
    }

    /** Apply only supplied host settings after the web profile has succeeded. */
    synchronized void applySettings(Map<String, String> values) {
        if (values.containsKey(AUTO_SAVE)) enabled = Boolean.parseBoolean(values.get(AUTO_SAVE));
        if (values.containsKey(SOURCE_CONTEXT)) includeContext = Boolean.parseBoolean(values.get(SOURCE_CONTEXT));
    }

    synchronized void status(String state, String message) throws IOException {
        if (!enabled) return;
        append(prefix(state) + (message == null ? "" : message) + "\n");
    }

    synchronized void failure(String state, Throwable failure) throws IOException {
        if (!enabled) return;
        LimitedWriter detail = new LimitedWriter(MAX_EVENT_BYTES);
        if (includeContext) {
            // LuaJ's original message/trace can carry real Lua locations. Keep it;
            // a Java host frame must never be presented as a Lua source line.
            failure.printStackTrace(new PrintWriter(detail));
        } else {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
                if (cause != failure) detail.append("Caused by: ");
                detail.append(cause.toString()).append('\n');
                if (detail.full()) break;
            }
        }
        append(prefix(state) + detail + (detail.full() ? TRUNCATED : ""));
    }

    private String prefix(String state) {
        StringBuilder result = new StringBuilder();
        if (includeContext) {
            result.append('[').append(Instant.ofEpochMilli(clock.getAsLong())).append(']')
                    .append("[thread=").append(Thread.currentThread().getName()).append(']');
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                if (frame.getClassName().equals(ScriptRuntimeLog.class.getName())) continue;
                result.append("[host=").append(frame).append(']');
                break;
            }
        }
        return result.append('[').append(state).append("] ").toString();
    }

    private void append(String event) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create runtime log directory");
        }
        byte[] bytes = boundedUtf8(event, Math.min(MAX_EVENT_BYTES, maxFileBytes));
        File active = file(0);
        if (active.length() + bytes.length > maxFileBytes) {
            Files.deleteIfExists(file(fileCount - 1).toPath());
            for (int index = fileCount - 2; index >= 0; index--) {
                File source = file(index);
                if (source.exists()) Files.move(source.toPath(), file(index + 1).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (FileOutputStream output = new FileOutputStream(active, true)) {
            output.write(bytes);
        }
    }

    private File file(int index) {
        return new File(directory, FILE_NAME + (index == 0 ? "" : "." + index));
    }

    private static byte[] boundedUtf8(String text, int limit) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return bytes;
        byte[] suffix = TRUNCATED.getBytes(StandardCharsets.UTF_8);
        int end = limit - suffix.length;
        // Move to a code point boundary before copying the truncation suffix.
        while (end > 0 && (bytes[end] & 0xc0) == 0x80) end--;
        byte[] bounded = new byte[end + suffix.length];
        System.arraycopy(bytes, 0, bounded, 0, end);
        System.arraycopy(suffix, 0, bounded, end, suffix.length);
        return bounded;
    }

    /** Prevent exception formatting itself from accumulating an unbounded trace. */
    private static final class LimitedWriter extends Writer {
        private final StringBuilder buffer = new StringBuilder();
        private final int limit;
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) {
            int count = Math.min(length, limit - buffer.length());
            if (count > 0) buffer.append(chars, offset, count);
        }
        @Override public void flush() { }
        @Override public void close() { }
        boolean full() { return buffer.length() >= limit; }
        @Override public String toString() { return buffer.toString(); }
    }
}
