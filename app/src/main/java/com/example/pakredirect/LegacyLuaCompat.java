package com.example.pakredirect;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/** Small compatibility helpers shared by the recovered Lua host and regression tests. */
final class LegacyLuaCompat {
    private static final long DEFAULT_TOUCH_DURATION_MS = 350L;
    private static final long MAX_TOUCH_DURATION_MS = 10_000L;

    private LegacyLuaCompat() { }

    interface PixelReader { int getPixel(int x, int y); }
    interface OcrRegionRecognizer { LuaTable recognize(Varargs args); }

    static long normalizeTouchDuration(long durationMs) {
        return Math.max(1L, Math.min(MAX_TOUCH_DURATION_MS, durationMs));
    }

    static long defaultTouchDurationMs() { return DEFAULT_TOUCH_DURATION_MS; }

    /** Implements the recovered cmpColorEx coordinate/color-list contract. */
    static boolean matchesColorPoints(String encodedPoints, double threshold, int width, int height,
                                      PixelReader pixels) {
        if (encodedPoints == null || encodedPoints.trim().isEmpty() || pixels == null) return false;
        String[] points = encodedPoints.split(",", -1);
        if (points.length == 0) return false;
        double required = Double.isNaN(threshold) ? 0.9d : Math.max(0d, Math.min(1d, threshold));
        int matches = 0;
        int valid = 0;
        for (String point : points) {
            String[] fields = point.trim().split("\\|", 3);
            if (fields.length != 3) continue;
            int x = parseInt(fields[0]), y = parseInt(fields[1]);
            if (x < 0 || y < 0) continue;
            String[] colorFields = fields[2].split("-", 2);
            if (colorFields[0].trim().isEmpty()) continue;
            valid++;
            if (x >= width || y >= height) continue;
            int expected = parseColor(colorFields[0]);
            int tolerance = colorFields.length > 1 ? parseColor(colorFields[1]) : 0;
            if (matchesColor(pixels.getPixel(x, y), expected, tolerance)) matches++;
        }
        return valid > 0 && matches > 0 && (double) matches / points.length >= required;
    }

    /** Finds the first OCR line matching one of the recovered script's literal text candidates. */
    static LuaTable findOcrMatch(LuaTable lines, LuaTable candidates, double minimumScore) {
        if (lines == null || candidates == null) return null;
        double required = Double.isNaN(minimumScore) ? 0.8d : Math.max(0d, Math.min(1d, minimumScore));
        for (int i = 1; i <= lines.length(); i++) {
            LuaValue value = lines.get(i);
            if (!value.istable()) continue;
            LuaTable line = value.checktable();
            String words = line.get("words").isnil() ? line.get("text").tojstring() : line.get("words").tojstring();
            if (line.get("score").optdouble(0d) < required) continue;
            for (int j = 1; j <= candidates.length(); j++) {
                LuaValue candidate = candidates.get(j);
                if (!candidate.isnil() && !candidate.tojstring().isEmpty()
                        && words.contains(candidate.tojstring())) return line;
            }
        }
        return null;
    }

    /** Implements the recovered findStr text/score behavior on ML Kit OCR lines. */
    static LuaTable findOcrTextMatch(LuaTable lines, String target, double minimumScore) {
        if (target == null || target.isEmpty()) return null;
        LuaTable candidates = new LuaTable();
        candidates.set(1, LuaValue.valueOf(target));
        return findOcrMatch(lines, candidates, minimumScore);
    }

    /** Joins only OCR rows at or above the confidence required by the caller. */
    static String concatenateConfidentOcrText(LuaTable lines, double minimumScore) {
        if (lines == null) return "";
        double required = Double.isNaN(minimumScore) ? 0.85d
                : Math.max(0d, Math.min(1d, minimumScore));
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= lines.length(); i++) {
            LuaValue value = lines.get(i);
            if (!value.istable()) continue;
            LuaTable line = value.checktable();
            double score = line.get("score").isnumber()
                    ? line.get("score").todouble() : line.get("confidence").optdouble(0d);
            if (score < required) continue;
            LuaValue words = line.get("words").isnil() ? line.get("text") : line.get("words");
            if (!words.isnil()) text.append(words.tojstring());
        }
        return text.toString();
    }

    /** Creates the same confidence-filtering tomocrtextall API used by Android and LuaJ tests. */
    static VarArgFunction createTomocrTextAll(OcrRegionRecognizer recognizer) {
        if (recognizer == null) throw new IllegalArgumentException("OCR recognizer is required");
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable lines = recognizer.recognize(args);
                double minimumScore = args.arg(5).optdouble(0.85d);
                return LuaValue.valueOf(concatenateConfidentOcrText(lines, minimumScore));
            }
        };
    }

    static LuaTable splitStr(LuaValue source, LuaValue separator) {
        String text = source.isnil() ? "" : source.tojstring();
        String delimiter = separator.isnil() ? "" : separator.tojstring();
        LuaTable result = new LuaTable();
        if (delimiter.isEmpty()) {
            if (!text.isEmpty()) result.set(1, LuaValue.valueOf(text));
            return result;
        }

        int index = 1;
        int start = 0;
        int next;
        while ((next = text.indexOf(delimiter, start)) >= 0) {
            result.set(index++, LuaValue.valueOf(text.substring(start, next)));
            start = next + delimiter.length();
        }
        if (start < text.length()) result.set(index, LuaValue.valueOf(text.substring(start)));
        return result;
    }

    private static int parseColor(String value) {
        try { return (int) Long.parseLong(value.trim(), 16) & 0x00ffffff; }
        catch (RuntimeException ignored) { return 0; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static boolean matchesColor(int actual, int expected, int tolerance) {
        return Math.abs(((actual >> 16) & 255) - ((expected >> 16) & 255)) <= ((tolerance >> 16) & 255)
                && Math.abs(((actual >> 8) & 255) - ((expected >> 8) & 255)) <= ((tolerance >> 8) & 255)
                && Math.abs((actual & 255) - (expected & 255)) <= (tolerance & 255);
    }
}
