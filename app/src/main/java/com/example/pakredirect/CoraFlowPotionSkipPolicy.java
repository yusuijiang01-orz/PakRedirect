package com.example.pakredirect;

/**
 * Skip the remainder of the active outer workflow cycle on UNKNOWN potion
 * inventory, instead of turning UNKNOWN into a yes/no condition or a zero.
 * This never chooses either transaction branch.
 */
final class CoraFlowPotionSkipPolicy {
    private CoraFlowPotionSkipPolicy() { }

    static final class Decision {
        final boolean canContinue;
        final String nextBody;
        final int nextIteration;

        Decision(boolean canContinue, String nextBody, int nextIteration) {
            this.canContinue = canContinue;
            this.nextBody = nextBody;
            this.nextIteration = nextIteration;
        }
    }

    static Decision nextCycle(String outerBody, int iteration, int count) {
        if (outerBody == null || outerBody.trim().isEmpty() || iteration < 1 || count == 0)
            return new Decision(false, null, iteration);
        if (count > 0 && iteration >= count)
            return new Decision(false, null, iteration);
        if (iteration == Integer.MAX_VALUE)
            return new Decision(false, null, iteration);
        return new Decision(true, outerBody, iteration + 1);
    }
}
