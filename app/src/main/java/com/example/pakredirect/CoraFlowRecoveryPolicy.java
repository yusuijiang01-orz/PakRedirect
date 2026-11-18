package com.example.pakredirect;

import java.util.Map;
import java.util.Set;

/** Resolves only explicitly configured JSON recovery jumps and bounds repeated failures. */
final class CoraFlowRecoveryPolicy {
    static final class Decision {
        final String target;
        final int attempt;
        final int maximum;
        final long delayMs;

        private Decision(String target, int attempt, int maximum, long delayMs) {
            this.target = target;
            this.attempt = attempt;
            this.maximum = maximum;
            this.delayMs = delayMs;
        }

        boolean shouldRecover() { return target != null && attempt <= maximum; }
    }

    private CoraFlowRecoveryPolicy() { }

    static Decision plan(String nodeId, String message, String configuredTarget,
                         int configuredMaximum, long configuredDelayMs,
                         Set<String> nodeIds, Map<String, Integer> attempts) {
        String target = configuredTarget == null ? "" : configuredTarget.trim();
        if (target.isEmpty()) return new Decision(null, 0, 0, 0);
        if (!nodeIds.contains(target)) {
            throw new IllegalStateException(message + "；恢复目标不存在：" + target);
        }
        int maximum = Math.max(1, Math.min(10, configuredMaximum));
        int attempt = attempts.merge(nodeId, 1, Integer::sum);
        long delayMs = Math.max(0L, Math.min(30_000L, configuredDelayMs));
        return new Decision(target, attempt, maximum, delayMs);
    }
}
