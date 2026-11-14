package com.example.pakredirect;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Matches OCR lines to button labels and rejects ambiguous positions. */
final class CoraFlowTextClickMatcher {
    private CoraFlowTextClickMatcher() { }

    static final class Line {
        final String text;
        final double confidence;
        final double x;
        final double y;

        Line(String text, double confidence, double x, double y) {
            this.text = text == null ? "" : text;
            this.confidence = confidence;
            this.x = x;
            this.y = y;
        }
    }

    static final class Result {
        final Line line;
        final boolean ambiguous;

        Result(Line line, boolean ambiguous) {
            this.line = line;
            this.ambiguous = ambiguous;
        }
    }

    static Result find(List<Line> lines, String expected, boolean exact, boolean caseSensitive) {
        String target = normalize(expected, caseSensitive);
        if (target.isEmpty() || lines == null) return new Result(null, false);
        List<Line> candidates = new ArrayList<>();
        for (Line line : lines) {
            String text = normalize(line.text, caseSensitive);
            if (exact ? text.equals(target) : text.contains(target)) candidates.add(line);
        }
        candidates.sort(Comparator.comparingDouble((Line line) -> line.confidence).reversed());
        if (candidates.isEmpty()) return new Result(null, false);
        Line best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            Line other = candidates.get(i);
            if (other.confidence < best.confidence - 5d) break;
            if (Math.hypot(other.x - best.x, other.y - best.y) > 12d) {
                return new Result(best, true);
            }
        }
        return new Result(best, false);
    }

    private static String normalize(String value, boolean caseSensitive) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
        return caseSensitive ? normalized : normalized.toLowerCase(Locale.ROOT);
    }
}
