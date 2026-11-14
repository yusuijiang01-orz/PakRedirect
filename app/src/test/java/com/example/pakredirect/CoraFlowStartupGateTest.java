package com.example.pakredirect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public final class CoraFlowStartupGateTest {
    @Test public void runningCheckAtEntryUsesWorkflowOwnedStartup() throws Exception {
        assertTrue(CoraFlowWorkflowRuntime.startsWithStartupGate(workflow(
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"check\",\"type\":\"is_app_running\"}],"
                        + "\"edges\":[{\"source\":\"start\",\"target\":\"check\",\"type\":\"default\"}]}")));
    }

    @Test public void launchNodeAtEntryUsesWorkflowOwnedStartup() throws Exception {
        assertTrue(CoraFlowWorkflowRuntime.startsWithStartupGate(workflow(
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"launch\",\"type\":\"launch_game\"}],"
                        + "\"edges\":[{\"source\":\"start\",\"target\":\"launch\",\"type\":\"default\"}]}")));
    }

    @Test public void startupGateIgnoresConditionEdgesFromStart() throws Exception {
        assertTrue(CoraFlowWorkflowRuntime.startsWithStartupGate(workflow(
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"branch\",\"type\":\"condition\"},"
                        + "{\"id\":\"check\",\"type\":\"is_app_running\"}],\"edges\":["
                        + "{\"source\":\"start\",\"target\":\"branch\",\"type\":\"condition\"},"
                        + "{\"source\":\"start\",\"target\":\"check\",\"type\":\"default\"}]}")));
    }

    @Test public void legacyWorkflowDoesNotBypassInitialScreenshot() throws Exception {
        assertFalse(CoraFlowWorkflowRuntime.startsWithStartupGate(workflow(
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"wait\",\"type\":\"delay\"}],"
                        + "\"edges\":[{\"source\":\"start\",\"target\":\"wait\",\"type\":\"default\"}]}")));
    }

    @Test public void missingEntryEdgeDoesNotClaimStartupGate() throws Exception {
        assertFalse(CoraFlowWorkflowRuntime.startsWithStartupGate(workflow(
                "{\"nodes\":[{\"id\":\"start\",\"type\":\"start\"},{\"id\":\"check\",\"type\":\"is_app_running\"}],\"edges\":[]}")));
    }

    private static CoraFlowWorkflowRuntime.WorkflowPackage workflow(String graph) throws Exception {
        return new CoraFlowWorkflowRuntime.WorkflowPackage(
                new JSONObject(graph), new JSONObject(), Collections.emptyMap(), new JSONObject());
    }
}
