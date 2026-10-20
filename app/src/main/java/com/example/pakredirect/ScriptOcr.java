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
import java.util.concurrent.TimeUnit;

/** On-device OCR bridge for the legacy Chinese and Vietnamese game UI. */
public final class ScriptOcr implements AutoCloseable {
    private final TextRecognizer chinese = TextRecognition.getClient(
            new ChineseTextRecognizerOptions.Builder().build());
    private final TextRecognizer latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    public Result recognize(Bitmap bitmap, boolean chineseText, long timeoutMs) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Text[] output = new Text[1];
        Exception[] failure = new Exception[1];
        TextRecognizer recognizer = chineseText ? chinese : latin;
        int[] size = LegacyTuringCompat.ocrInputDimensions(bitmap.getWidth(), bitmap.getHeight());
        Bitmap scaled = size[0] == bitmap.getWidth() && size[1] == bitmap.getHeight()
                ? null : Bitmap.createScaledBitmap(bitmap, size[0], size[1], true);
        Bitmap input = scaled == null ? bitmap : scaled;
        Task<Text> task;
        try {
            task = recognizer.process(InputImage.fromBitmap(input, 0));
        } catch (RuntimeException error) {
            if (scaled != null && !scaled.isRecycled()) scaled.recycle();
            throw error;
        }
        task.addOnCompleteListener(result -> {
            try {
                if (result.isSuccessful()) output[0] = result.getResult();
                else {
                    Exception error = result.getException();
                    failure[0] = error == null ? new IllegalStateException("OCR 识别失败") : error;
                }
            } finally {
                if (scaled != null && !scaled.isRecycled()) scaled.recycle();
                done.countDown();
            }
        });
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("OCR 识别超时");
        if (failure[0] != null) throw failure[0];
        return Result.from(output[0], bitmap.getWidth() / (double) input.getWidth(),
                bitmap.getHeight() / (double) input.getHeight());
    }

    @Override public void close() {
        chinese.close();
        latin.close();
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
