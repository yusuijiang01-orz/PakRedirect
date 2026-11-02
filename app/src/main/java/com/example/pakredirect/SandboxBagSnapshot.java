package com.example.pakredirect;

/** Immutable, validated snapshot received from the isolated sandbox game process. */
final class SandboxBagSnapshot {
    final boolean supported;
    final String status;
    final String buildId;
    final int freeSlots;
    final int currentWeight;
    final int maxWeight;
    final long receivedAtElapsedMs;

    SandboxBagSnapshot(boolean supported, String status, String buildId, int freeSlots,
                       int currentWeight, int maxWeight, long receivedAtElapsedMs) {
        this.supported = supported;
        this.status = status == null ? "UNKNOWN" : status;
        this.buildId = buildId == null ? "" : buildId;
        this.freeSlots = freeSlots;
        this.currentWeight = currentWeight;
        this.maxWeight = maxWeight;
        this.receivedAtElapsedMs = receivedAtElapsedMs;
    }

    boolean isFresh(long nowElapsedMs, long maxAgeMs) {
        return receivedAtElapsedMs > 0 && nowElapsedMs >= receivedAtElapsedMs
                && nowElapsedMs - receivedAtElapsedMs <= maxAgeMs;
    }

    boolean shouldVisitDoctor(int reserveSlots, int reserveWeight) {
        return supported && (freeSlots <= Math.max(0, reserveSlots)
                || maxWeight - currentWeight <= Math.max(0, reserveWeight));
    }
}
