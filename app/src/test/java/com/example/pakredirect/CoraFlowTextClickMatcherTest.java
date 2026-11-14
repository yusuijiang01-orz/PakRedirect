package com.example.pakredirect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CoraFlowTextClickMatcherTest {
    @Test public void matchesVietnameseButtonTextIgnoringSpacesAndCase() {
        CoraFlowTextClickMatcher.Line line = new CoraFlowTextClickMatcher.Line(
                " THỬ   LẠI! ", 96, 440, 720);
        CoraFlowTextClickMatcher.Result result = CoraFlowTextClickMatcher.find(
                Collections.singletonList(line), "Thử lại", false, false);

        assertEquals(line, result.line);
        assertFalse(result.ambiguous);
    }

    @Test public void rejectsDistantNearEqualCandidates() {
        CoraFlowTextClickMatcher.Line first = new CoraFlowTextClickMatcher.Line(
                "重新连接", 92, 800, 400);
        CoraFlowTextClickMatcher.Line second = new CoraFlowTextClickMatcher.Line(
                "重新连接", 89, 800, 700);
        CoraFlowTextClickMatcher.Result result = CoraFlowTextClickMatcher.find(
                Arrays.asList(first, second), "重新连接", true, false);

        assertTrue(result.ambiguous);
        assertEquals(first, result.line);
    }

    @Test public void doesNotSelectUnrelatedText() {
        CoraFlowTextClickMatcher.Result result = CoraFlowTextClickMatcher.find(
                Collections.singletonList(new CoraFlowTextClickMatcher.Line(
                        "取消", 99, 420, 700)), "Thử lại", false, false);

        assertNull(result.line);
        assertFalse(result.ambiguous);
    }
}
