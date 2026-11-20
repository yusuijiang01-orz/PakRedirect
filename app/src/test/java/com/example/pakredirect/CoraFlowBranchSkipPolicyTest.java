package com.example.pakredirect;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class CoraFlowBranchSkipPolicyTest {
    private static Set<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test public void findsNearestSharedContinuationAndSkipsBothTradeBranches() throws Exception {
        JSONArray edges = new JSONArray("["
                + "{\"source\":\"count\",\"target\":\"buy\",\"type\":\"condition\",\"condition\":\"false\"},"
                + "{\"source\":\"count\",\"target\":\"enough\",\"type\":\"condition\",\"condition\":\"true\"},"
                + "{\"source\":\"buy\",\"target\":\"close\"},"
                + "{\"source\":\"enough\",\"target\":\"close\"},"
                + "{\"source\":\"close\",\"target\":\"repair\"}]");
        assertEquals("close", CoraFlowBranchSkipPolicy.findJoin(edges, "count",
                ids("count", "buy", "enough", "close", "repair")));
    }

    @Test public void acceptsSourceHandleBranchEncoding() throws Exception {
        JSONArray edges = new JSONArray("["
                + "{\"source\":\"count\",\"target\":\"a\",\"sourceHandle\":\"true\"},"
                + "{\"source\":\"count\",\"target\":\"b\",\"sourceHandle\":\"false\"},"
                + "{\"source\":\"a\",\"target\":\"join\"},"
                + "{\"source\":\"b\",\"target\":\"join\"}]");
        assertEquals("join", CoraFlowBranchSkipPolicy.findJoin(edges, "count",
                ids("count", "a", "b", "join")));
    }

    @Test public void sameTrueAndFalseTargetIsAlreadyAJoin() throws Exception {
        JSONArray edges = new JSONArray("["
                + "{\"source\":\"count\",\"target\":\"continue\",\"condition\":\"true\"},"
                + "{\"source\":\"count\",\"target\":\"continue\",\"condition\":\"false\"}]");
        assertEquals("continue", CoraFlowBranchSkipPolicy.findJoin(edges, "count",
                ids("count", "continue")));
    }

    @Test public void doesNotTreatTheOppositeBranchHeadAsAJoin() throws Exception {
        JSONArray edges = new JSONArray("["
                + "{\"source\":\"count\",\"target\":\"a\",\"condition\":\"true\"},"
                + "{\"source\":\"count\",\"target\":\"b\",\"condition\":\"false\"},"
                + "{\"source\":\"a\",\"target\":\"b\"},"
                + "{\"source\":\"b\",\"target\":\"join\"}]");
        assertEquals("join", CoraFlowBranchSkipPolicy.findJoin(edges, "count",
                ids("count", "a", "b", "join")));
    }

    @Test public void missingCommonContinuationFailsClosed() throws Exception {
        JSONArray edges = new JSONArray("["
                + "{\"source\":\"count\",\"target\":\"buy\",\"condition\":\"true\"},"
                + "{\"source\":\"count\",\"target\":\"skip\",\"condition\":\"false\"},"
                + "{\"source\":\"buy\",\"target\":\"endBuy\"},"
                + "{\"source\":\"skip\",\"target\":\"endSkip\"}]");
        assertNull(CoraFlowBranchSkipPolicy.findJoin(edges, "count",
                ids("count", "buy", "skip", "endBuy", "endSkip")));
    }
}
