package com.example.pakredirect;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import android.util.Log;

/** On-device OCR bridge for the legacy Chinese and Vietnamese game UI. */
public final class ScriptOcr implements AutoCloseable {
    static final long LUA_TIMEOUT_MS = 30_000L;
    private static final String TAG = "RYLUX-ScriptOcr";
    private static final Executor CALLBACK_EXECUTOR = Runnable::run;
    private static final ScriptOcrGate PROCESS_GATE = new ScriptOcrGate();
    private static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();
    private final TextRecognizer chinese = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());
    private final TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean recognizersClosed = new AtomicBoolean();

    public Result recognize(Bitmap bitmap, boolean chineseText, long timeoutMs) throws Exception {
        return recognize(bitmap, chineseText, timeoutMs, 960);
    }

    public Result recognize(Bitmap bitmap, boolean chineseText, long timeoutMs,
                            int maxInputEdge) throws Exception {
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("OCR 图片不可用");
        if (closeRequested.get()) throw new IllegalStateException("OCR 识别器已关闭");
        int requestId = REQUEST_SEQUENCE.incrementAndGet();
        long timeout = Math.max(1L, timeoutMs);
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        ScriptOcrGate.Lease gateLease = PROCESS_GATE.acquire(timeout);
        if (gateLease == null) {
            Log.w(TAG, "OCR #" + requestId + " semaphore timeout; source="
                    + bitmap.getWidth() + "x" + bitmap.getHeight());
            if (PROCESS_GATE.isCircuitOpen()) {
                throw new IllegalStateException("OCR 连续超时，识别已暂停；为避免脚本卡住，已停止后续识别");
            }
            throw new IllegalStateException("OCR 识别超时：上一张图片仍在识别");
        }
        CountDownLatch done = new CountDownLatch(1);
        Text[] output = new Text[1];
        Exception[] failure = new Exception[1];
        Bitmap input = null;
        boolean listenerAttached = false;
        AtomicBoolean taskFinished = new AtomicBoolean();
        try {
            if (closeRequested.get()) throw new IllegalStateException("OCR 识别器已关闭");
            TextRecognizer recognizer = chineseText ? chinese : latin;
            int[] size = LegacyTuringCompat.ocrInputDimensions(
                    bitmap.getWidth(), bitmap.getHeight(), maxInputEdge);
            input = size[0] == bitmap.getWidth() && size[1] == bitmap.getHeight()
                    ? bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    : Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
            if (input == null) throw new IllegalStateException("无法复制 OCR 图片");
            final Bitmap taskInput = input;
            final int inputWidth = taskInput.getWidth();
            final int inputHeight = taskInput.getHeight();
            long taskStart = android.os.SystemClock.elapsedRealtime();
            Log.d(TAG, "OCR #" + requestId + " started; source=" + bitmap.getWidth() + "x"
                    + bitmap.getHeight() + ", input=" + inputWidth + "x" + inputHeight
                    + ", maxEdge=" + maxInputEdge);
            markTaskStarted();
            Runnable finishTask = () -> {
                if (!taskFinished.compareAndSet(false, true)) return;
                long elapsed = android.os.SystemClock.elapsedRealtime() - taskStart;
                if (elapsed >= 5_000L) {
                    Log.w(TAG, "OCR #" + requestId + " completed in " + elapsed + "ms; input="
                            + inputWidth + "x" + inputHeight);
                }
                if (!taskInput.isRecycled()) taskInput.recycle();
                markTaskFinished();
                done.countDown();
                PROCESS_GATE.complete(gateLease);
            };
            try {
                Task<Text> task = recognizer.process(InputImage.fromBitmap(taskInput, 0));
                task.addOnCompleteListener(CALLBACK_EXECUTOR, result -> {
                    try {
                        if (result.isSuccessful()) output[0] = result.getResult();
                        else {
                            Exception error = result.getException();
                            failure[0] = error == null ? new IllegalStateException("OCR 识别失败") : error;
                        }
                    } catch (RuntimeException error) {
                        failure[0] = error;
                    } finally {
                        finishTask.run();
                    }
                });
                listenerAttached = true;
            } catch (RuntimeException error) {
                finishTask.run();
                throw error;
            }
            long remaining = deadline - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0 || !done.await(remaining, TimeUnit.MILLISECONDS)) {
                PROCESS_GATE.markTimedOut(gateLease);
                Log.e(TAG, "OCR #" + requestId + " inference timeout; source="
                        + bitmap.getWidth() + "x" + bitmap.getHeight() + ", input="
                        + inputWidth + "x" + inputHeight + ", timeout=" + timeout + "ms");
                if (PROCESS_GATE.isCircuitOpen()) {
                    throw new IllegalStateException("OCR 连续超时，识别已暂停；为避免脚本卡住，已停止后续识别");
                }
                throw new IllegalStateException("OCR 识别超时（等待 " + timeout + " 毫秒）");
            }
            if (failure[0] != null) throw failure[0];
            return Result.from(output[0], bitmap.getWidth() / (double) inputWidth,
                    bitmap.getHeight() / (double) inputHeight);
        } finally {
            if (!listenerAttached) {
                if (input != null && !input.isRecycled()) input.recycle();
                PROCESS_GATE.complete(gateLease);
            }
        }
    }

    @Override public synchronized void close() {
        closeRequested.set(true);
        closeRecognizersIfReady();
    }

    static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith("OCR 识别超时")) return true;
        }
        return false;
    }

    static boolean isCircuitOpen(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith("OCR 连续超时")) return true;
        }
        return false;
    }

    static boolean isUnavailable(Throwable error) {
        return isTimeout(error) || isCircuitOpen(error);
    }

    private synchronized void markTaskStarted() {
        if (closeRequested.get()) throw new IllegalStateException("OCR 识别器已关闭");
        pendingTasks.incrementAndGet();
    }

    private synchronized void markTaskFinished() {
        pendingTasks.decrementAndGet();
        closeRecognizersIfReady();
    }

    private synchronized void closeRecognizersIfReady() {
        if (!closeRequested.get() || pendingTasks.get() != 0
                || !recognizersClosed.compareAndSet(false, true)) return;
        try { chinese.close(); } catch (RuntimeException ignored) { }
        try { latin.close(); } catch (RuntimeException ignored) { }
    }


    public static final class Result {
        public final String text;
        public final String json;
        private Result(String text, String json) { this.text = text; this.json = json; }

        private static Result from(Text recognized, double scaleX, double scaleY) {
            StringBuilder text = new StringBuilder();
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (Text.TextBlock block : recognized.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(line.getText());
                    android.graphics.Rect box = line.getBoundingBox();
                    if (box == null) continue;
                    int centerX = (int) Math.round(box.centerX() * scaleX);
                    int centerY = (int) Math.round(box.centerY() * scaleY);
                    if (!first) json.append(',');
                    first = false;
                    json.append("{\"text\":\"").append(escape(line.getText()))
                            .append("\",\"words\":\"").append(escape(line.getText()))
                            .append("\",\"x\":").append(centerX)
                            .append(",\"y\":").append(centerY)
                            .append(",\"score\":").append(line.getConfidence()).append('}');
                }
            }
            return new Result(text.toString(), json.append(']').toString());
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r");
        }
    }
}
