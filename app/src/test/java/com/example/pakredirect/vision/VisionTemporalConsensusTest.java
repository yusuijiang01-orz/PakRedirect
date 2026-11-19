package com.example.pakredirect.vision;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VisionTemporalConsensusTest {
    @Test public void consecutiveAgreementConfirmsVisibleElement() {
        assertEquals(VisionTemporalConsensus.State.CONFIRMED,
                VisionTemporalConsensus.decide(true, 0.98, true, 0.97, 0.95));
        assertEquals(VisionTemporalConsensus.State.ABSENT,
                VisionTemporalConsensus.decide(false, 0.99, false, 0.97, 0.95));
    }

    @Test public void uncertaintyCannotBeTreatedAsEmptyOrPermissionToTrade() {
        assertEquals(VisionTemporalConsensus.State.UNKNOWN,
                VisionTemporalConsensus.decide(true, 0.98, false, 0.99, 0.95));
        assertEquals(VisionTemporalConsensus.State.UNKNOWN,
                VisionTemporalConsensus.decide(null, 0.99, false, 0.99, 0.95));
        assertEquals(VisionTemporalConsensus.State.UNKNOWN,
                VisionTemporalConsensus.decide(false, 0.70, false, 0.99, 0.95));
        assertEquals(VisionTemporalConsensus.State.UNKNOWN,
                VisionTemporalConsensus.decide(true, Double.NaN, true, 0.99, 0.95));
    }
}
