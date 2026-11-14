package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public final class CoraFlowWorkflowImagePreflightTest {
    @Test public void missingImageFailsBeforeWorkflowCanTouchGame() throws Exception {
        JSONObject workflow = new JSONObject("{\"nodes\":[{\"id\":\"check\",\"type\":\"is_image_exist\","
                + "\"data\":{\"name\":\"主界面识别\",\"config\":{\"image\":\"captures/main.png\"}}}]}");
        try {
            CoraFlowWorkflowUpdateManager.validateWorkflowImages(workflow, Collections.emptySet());
            fail("missing image must fail preflight");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("captures/main.png"));
            assertTrue(expected.getMessage().contains("尚未发送游戏触控"));
        }
    }

    @Test public void equipmentProcessingRequiresBothPotionProtectionTemplates() throws Exception {
        JSONObject workflow = new JSONObject("{\"nodes\":[{\"id\":\"sell\",\"type\":\"sell_inventory_equipment\","
                + "\"data\":{\"name\":\"商店清包\",\"config\":{\"sell_white\":true}}}]}");
        try {
            CoraFlowWorkflowUpdateManager.validateWorkflowImages(workflow, Collections.emptySet());
            fail("sale node without potion protection must fail preflight");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("红药保护图标"));
            assertTrue(expected.getMessage().contains("蓝药保护图标"));
        }
    }

    @Test public void completeImageReferencesPassPreflight() throws Exception {
        JSONObject workflow = new JSONObject("{\"nodes\":[{\"id\":\"check\",\"type\":\"is_image_exist\","
                + "\"data\":{\"config\":{\"image\":\"captures/main.png\"}}},{"
                + "\"id\":\"sell\",\"type\":\"sell_inventory_equipment\",\"data\":{\"config\":{"
                + "\"sell_white\":true,\"medicine_red_image\":\"captures/red.png\","
                + "\"medicine_blue_image\":\"captures/blue.png\"}}}]}");
        CoraFlowWorkflowUpdateManager.validateWorkflowImages(workflow,
                Set.of("captures/main.png", "captures/red.png", "captures/blue.png"));
    }
}
