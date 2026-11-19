package com.example.pakredirect;

import java.text.Normalizer;

/** Strict numeric confirmation for safety-critical game purchase dialogs. */
final class CoraFlowNumberConsensus {
    private CoraFlowNumberConsensus() { }

    static boolean matchesReadings(String first, float firstConfidence,
                                   String second, float secondConfidence,
                                   int expected, float minimumConfidence) {
        if (expected < 0 || expected > 999 || !Float.isFinite(minimumConfidence)) return false;
        return matchesOne(first, firstConfidence, expected, minimumConfidence)
                && matchesOne(second, secondConfidence, expected, minimumConfidence);
    }

    private static boolean matchesOne(String text, float confidence, int expected, float minimum) {
        if (text == null || !Float.isFinite(confidence) || confidence < minimum) return false;
        String cleaned = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
        // Do not accept a partially read number, suffix, multiple values or a decimal.
        if (!cleaned.matches("[0-9]{1,3}")) return false;
        try {
            return Integer.parseInt(cleaned) == expected;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
