package com.example.pakredirect;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regression checks: a failed or ambiguous digit recognition never authorizes a purchase. */
public class CoraFlowNumberConsensusTest {
    @Test public void acceptsTwoIndependentMatchingNumbers() {
        assertTrue(CoraFlowNumberConsensus.matchesReadings("5", 96, " ５\n", 94, 5, 90));
        assertTrue(CoraFlowNumberConsensus.matchesReadings("1", 92, "1", 93, 1, 90));
    }

    @Test public void rejectsMismatchesAndLowConfidence() {
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "1", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 89, "5", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "5", 80, 5, 90));
    }

    @Test public void rejectsPartialAmbiguousAndMissingNumbers() {
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "55", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "5.0", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "5x", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "5 1", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings(null, 99, "5", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", Float.NaN, "5", 99, 5, 90));
        assertFalse(CoraFlowNumberConsensus.matchesReadings("5", 99, "5", 99, 1000, 90));
    }
}
