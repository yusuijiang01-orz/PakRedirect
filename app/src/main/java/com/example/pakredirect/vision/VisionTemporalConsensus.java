package com.example.pakredirect.vision;

/** Tri-state recognition. UNKNOWN is never treated as EMPTY or an authorization to trade. */
public final class VisionTemporalConsensus {
    public enum State { CONFIRMED, ABSENT, UNKNOWN }
    private VisionTemporalConsensus() { }

    /**
     * For high-risk UI transitions require two independent agreeing observations.
     * Missing observations, contradictions or low confidence => UNKNOWN.
     */
    public static State decide(Boolean first, double firstConfidence,
                               Boolean second, double secondConfidence, double minimum) {
        if (first == null || second == null
                || !Double.isFinite(firstConfidence) || !Double.isFinite(secondConfidence)
                || firstConfidence < minimum || secondConfidence < minimum
                || !first.equals(second)) return State.UNKNOWN;
        return first ? State.CONFIRMED : State.ABSENT;
    }
}
