package com.example.pakredirect;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CoraFlowInventoryCountConsensusTest {
    @Test public void acceptsTwoIdenticalCompleteSnapshots() {
        Map<Integer,Integer> a = new HashMap<>(), b = new HashMap<>();
        a.put(2, 7); a.put(19, 3);
        b.put(19, 3); b.put(2, 7);
        assertTrue(CoraFlowInventoryCountConsensus.agrees(a, b));
        assertEquals(10, CoraFlowInventoryCountConsensus.total(b));
    }

    @Test public void cannotConfirmOnlyMatchingTotalsWithDifferentSlots() {
        Map<Integer,Integer> a = new HashMap<>(), b = new HashMap<>();
        a.put(2, 7); b.put(3, 7);
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, b));
        b.clear(); b.put(2, 6);
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, b));
    }

    @Test public void rejectsMissingInvalidOrPartialSnapshot() {
        Map<Integer,Integer> a = new HashMap<>();
        a.put(2, 7);
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, null));
        a.put(36, 1);
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, a));
        a.remove(36); a.put(2, 0);
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, a));
    }

    @Test public void twoEmptyInventoriesCanBeVerifiedButOneCannot() {
        Map<Integer,Integer> a = new HashMap<>();
        assertTrue(CoraFlowInventoryCountConsensus.agrees(a, new HashMap<>()));
        assertEquals(0, CoraFlowInventoryCountConsensus.total(a));
        assertFalse(CoraFlowInventoryCountConsensus.agrees(a, null));
    }
}
