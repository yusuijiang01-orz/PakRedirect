package com.example.pakredirect;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class WorkflowVersionValidatorTest {
    @Test public void acceptsMatchingSignedAndDecryptedRelease() throws Exception {
        JSONObject graph = new JSONObject().put("name", "RYLUX 完整自动化 v3.82")
                .put("version", "1.0");
        WorkflowVersionValidator.Info info =
                WorkflowVersionValidator.validate("coraflow-3.82-medicine-sale", graph);
        assertEquals("3.82", info.manifestVersion);
        assertEquals("3.82", info.embeddedVersion);
    }

    @Test public void rejectsManifestClaimingNewerVersionThanDecryptedGraph() throws Exception {
        JSONObject graph = new JSONObject().put("name", "RYLUX 完整自动化 v3.79")
                .put("id", "rylux-v3.40").put("version", "1.0");
        try {
            WorkflowVersionValidator.validate("coraflow-3.82-medicine-sale", graph);
            fail("version mismatch must block launch");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("v3.82"));
            assertTrue(expected.getMessage().contains("v3.79"));
        }
    }

    @Test public void ignoresStaleNodeLabelsAndLegacyGraphId() throws Exception {
        JSONObject node = new JSONObject().put("data",
                new JSONObject().put("name", "v3.46 启动：等待背包动画"));
        JSONObject graph = new JSONObject().put("name", "RYLUX 完整自动化 v3.82")
                .put("id", "rylux-v3.40").put("version", "1.0")
                .put("nodes", new org.json.JSONArray().put(node));
        WorkflowVersionValidator.Info info =
                WorkflowVersionValidator.validate("coraflow-3.82-medicine-sale", graph);
        assertEquals("3.82", info.embeddedVersion);
    }

    @Test public void rejectsMissingVersionAndTooOldWorkflow() throws Exception {
        try {
            WorkflowVersionValidator.validate("coraflow-3.82", new JSONObject().put("name", "RYLUX"));
            fail("missing version must block launch");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("未声明"));
        }
        try {
            WorkflowVersionValidator.validate("coraflow-3.81", new JSONObject().put("name", "RYLUX v3.81"));
            fail("old workflow must block launch");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("至少 v3.82"));
        }
    }
}
