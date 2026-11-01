package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ScriptRuntimeLogTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void defaultsAppendUtf8WithRealHostContext() throws Exception {
        File directory = new File(temporary.getRoot(), "logs");
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory, 4096, 3, () -> 0L);
        log.status("RUNNING", "Lua 已启动");
        log.status("PAUSED", "用户暂停");

        String text = read(new File(directory, ScriptRuntimeLog.FILE_NAME));
        assertTrue(text.contains("[1970-01-01T00:00:00Z]"));
        assertTrue(text.contains("[thread=" + Thread.currentThread().getName() + "]"));
        assertTrue(text.contains("[host=com.example.pakredirect.ScriptRuntimeLogTest."));
        assertTrue(text.contains("ScriptRuntimeLogTest.java:"));
        assertTrue(text.contains("[RUNNING] Lua 已启动\n"));
        assertTrue(text.endsWith("[PAUSED] 用户暂停\n"));
        assertFalse(text.contains(".lua:"));
    }

    @Test public void disabledLoggingCreatesNothingAndCanResumeWithPartialWebSettings() throws Exception {
        File directory = new File(temporary.getRoot(), "logs");
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory);
        log.configure(false, false);
        log.status("RUNNING", "不保存");
        log.failure("FAILED", new IllegalStateException("不保存异常"));
        assertFalse(directory.exists());

        log.applySettings(Collections.singletonMap(ScriptRuntimeLog.AUTO_SAVE, "true"));
        log.status("RUNNING", "继续保存");
        assertEquals("[RUNNING] 继续保存\n", read(new File(directory, ScriptRuntimeLog.FILE_NAME)));

        log.applySettings(Collections.singletonMap(ScriptRuntimeLog.SOURCE_CONTEXT, "true"));
        log.status("RUNNING", "带上下文");
        assertTrue(read(new File(directory, ScriptRuntimeLog.FILE_NAME)).contains("[thread="));
        log.applySettings(Collections.singletonMap(ScriptRuntimeLog.AUTO_SAVE, "false"));
        long before = new File(directory, ScriptRuntimeLog.FILE_NAME).length();
        log.status("STOPPED", "不再保存");
        assertEquals(before, new File(directory, ScriptRuntimeLog.FILE_NAME).length());
    }

    @Test public void failureContextUsesRealExceptionFramesAndLuaMessage() throws Exception {
        File directory = temporary.newFolder();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory);
        IllegalStateException failure = new IllegalStateException("脚本/挂机模式.lua:37: 原始错误");
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("LuaHost", "run", "ActualHost.java", 71)});
        log.failure("FAILED", failure);

        String text = read(new File(directory, ScriptRuntimeLog.FILE_NAME));
        assertTrue(text.contains("脚本/挂机模式.lua:37: 原始错误"));
        assertTrue(text.contains("LuaHost.run(ActualHost.java:71)"));
    }

    @Test public void contextOffKeepsFailureMessagesAndCausesWithoutAddingFrames() throws Exception {
        File directory = temporary.newFolder();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory);
        log.configure(true, false);
        IllegalArgumentException cause = new IllegalArgumentException("实际底层原因");
        log.failure("FAILED", new IllegalStateException("脚本/真实.lua:12: 失败", cause));

        String text = read(new File(directory, ScriptRuntimeLog.FILE_NAME));
        assertEquals("[FAILED] java.lang.IllegalStateException: 脚本/真实.lua:12: 失败\n"
                + "Caused by: java.lang.IllegalArgumentException: 实际底层原因\n", text);
        assertFalse(text.contains("[thread="));
        assertFalse(text.contains(".java:"));
    }

    @Test public void rotationsBoundTotalFilesAndRetainNewestEvents() throws Exception {
        File directory = temporary.newFolder();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory, 128, 3, () -> 0L);
        log.configure(true, false);
        for (int index = 0; index < 50; index++) {
            log.status("RUNNING", "event-" + index + " " + "x".repeat(75));
        }
        File[] files = directory.listFiles();
        assertEquals(3, files.length);
        for (File file : files) assertTrue(file.length() <= 128);
        assertTrue(read(new File(directory, ScriptRuntimeLog.FILE_NAME)).contains("event-49 "));
        assertTrue(read(new File(directory, ScriptRuntimeLog.FILE_NAME + ".1")).contains("event-48 "));
        assertTrue(read(new File(directory, ScriptRuntimeLog.FILE_NAME + ".2")).contains("event-47 "));
    }

    @Test public void oversizedStatusAndFailureAreUtf8SafeAndBounded() throws Exception {
        File directory = temporary.newFolder();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory, 128, 2, () -> 0L);
        log.configure(true, false);
        log.status("RUNNING", "汉字😀".repeat(100));
        log.failure("FAILED", new IllegalStateException("😀中文".repeat(100)));

        for (File file : directory.listFiles()) {
            byte[] bytes = Files.readAllBytes(file.toPath());
            assertTrue(bytes.length <= 128);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            assertTrue(text.endsWith("…(日志已截断)\n"));
        }
    }

    @Test public void simultaneousProducersAppendWholeEvents() throws Exception {
        File directory = temporary.newFolder();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory, 16 * 1024, 1, () -> 0L);
        log.configure(true, false);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            @SuppressWarnings("unchecked") Future<Void>[] results = new Future[4];
            for (int worker = 0; worker < results.length; worker++) {
                int id = worker;
                results[worker] = workers.submit(() -> {
                    for (int event = 0; event < 30; event++) log.status("RUNNING", id + ":" + event);
                    return null;
                });
            }
            for (Future<Void> result : results) result.get();
        } finally {
            workers.shutdownNow();
        }
        List<String> lines = Files.readAllLines(new File(directory, ScriptRuntimeLog.FILE_NAME).toPath(),
                StandardCharsets.UTF_8);
        assertEquals(120, lines.size());
        assertEquals(120, new java.util.HashSet<>(lines).size());
        for (String line : lines) assertTrue(line.matches("\\[RUNNING] [0-3]:[0-9]+"));
    }

    @Test public void unwritableDirectoryReportsIoFailureToHost() throws Exception {
        File directory = temporary.newFile();
        ScriptRuntimeLog log = new ScriptRuntimeLog(directory);
        try {
            log.status("RUNNING", "无法写入");
            fail("a file cannot serve as log directory");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("directory"));
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
