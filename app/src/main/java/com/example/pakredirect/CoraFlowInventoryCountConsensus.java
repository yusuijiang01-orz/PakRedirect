package com.example.pakredirect;

import java.util.Map;

/** Requires two complete independent screenshots with identical per-slot potion quantities. */
final class CoraFlowInventoryCountConsensus {
    private CoraFlowInventoryCountConsensus() { }

    static boolean agrees(Map<Integer, Integer> first, Map<Integer, Integer> second) {
        return valid(first) && valid(second) && first.equals(second);
    }

    static int total(Map<Integer, Integer> verified) {
        if (!valid(verified)) throw new IllegalArgumentException("Unverified potion inventory");
        int count = 0;
        for (int quantity : verified.values()) {
            count = Math.addExact(count, quantity);
        }
        return count;
    }

    private static boolean valid(Map<Integer, Integer> observed) {
        if (observed == null || observed.size() > 35) return false;
        for (Map.Entry<Integer, Integer> entry : observed.entrySet()) {
            Integer slot = entry.getKey(), quantity = entry.getValue();
            if (slot == null || slot < 1 || slot > 35
                    || quantity == null || quantity <= 0 || quantity > 999999) return false;
        }
        return true;
    }
}
