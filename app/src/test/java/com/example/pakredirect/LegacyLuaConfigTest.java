package com.example.pakredirect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.junit.Test;

public final class LegacyLuaConfigTest {
    @Test public void workbenchHostMarksSettingsAsAvailableBeforeLuaStarts() {
        Globals globals = JsePlatform.standardGlobals();
        LegacyLuaConfig.installWorkbenchConfigFlag(globals);
        assertTrue(globals.get("RYLUX_WORKBENCH_CONFIG").toboolean());
    }

    @Test public void blueFilterSelectionEnablesOnlyTheSelectedLegacyRule() {
        assertArrayEquals(new String[]{"false", "false", "false"}, LegacyLuaConfig.blueFilterFlags("0"));
        assertArrayEquals(new String[]{"true", "false", "false"}, LegacyLuaConfig.blueFilterFlags("1"));
        assertArrayEquals(new String[]{"false", "true", "false"}, LegacyLuaConfig.blueFilterFlags("2"));
        assertArrayEquals(new String[]{"false", "false", "true"}, LegacyLuaConfig.blueFilterFlags("3"));
    }

    @Test public void missingOrInvalidSelectionLeavesLegacyValuesUntouched() {
        assertNull(LegacyLuaConfig.blueFilterFlags(null));
        assertNull(LegacyLuaConfig.blueFilterFlags(""));
        assertNull(LegacyLuaConfig.blueFilterFlags("not-an-index"));
        assertNull(LegacyLuaConfig.blueFilterFlags("4"));
    }

    @Test public void recoveredScriptFieldsHaveSafeDefaultsWhenWorkbenchValuesAreOmitted() {
        assertEquals("320", LegacyLuaConfig.defaults().get("虚拟屏幕DPI"));
        assertEquals("60", LegacyLuaConfig.defaults().get("挂机不移动秒"));
        assertEquals("2", LegacyLuaConfig.defaults().get("队员找不到队长组队就回家次数"));
        assertEquals("true", LegacyLuaConfig.defaults().get("保护装备不出售"));
        assertEquals("false", LegacyLuaConfig.defaults().get("出售装备"));
        assertEquals("0", LegacyLuaConfig.defaults().get("出售装备品质上限"));
        assertEquals("true", LegacyLuaConfig.defaults().get("出售装备二次确认"));
        assertEquals("false", LegacyLuaConfig.defaults().get("允许出售物品"));
        assertEquals("", LegacyLuaConfig.defaults().get("出售物品清单"));
        assertEquals("10", LegacyLuaConfig.defaults().get("出售物品数量阈值"));
        assertEquals("false", LegacyLuaConfig.defaults().get("允许购买物品"));
        assertEquals("", LegacyLuaConfig.defaults().get("购买物品清单"));
        assertEquals("10", LegacyLuaConfig.defaults().get("购买物品数量阈值"));
        assertEquals("10", LegacyLuaConfig.defaults().get("每次购买数量"));
        assertEquals("false", LegacyLuaConfig.defaults().get("自动使用红药"));
        assertEquals("false", LegacyLuaConfig.defaults().get("自动使用蓝药"));
        assertArrayEquals(new String[]{"true", "false", "false"}, LegacyLuaConfig.blueFilterFlags(
                LegacyLuaConfig.defaults().get("蓝条筛选模式")));
    }

    @Test public void virtualDpiIsSeededAndReadFromTheLiveHostConfig() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        if (!Files.isDirectory(root.resolve("app/src/main/java"))) root = root.resolve("..");
        Path sources = root.resolve("app/src/main/java/com/example/pakredirect");
        String activity = new String(Files.readAllBytes(sources.resolve("ScriptToolsActivity.java")), StandardCharsets.UTF_8);
        String loadProfile = activity.substring(activity.indexOf("private void loadProfile()"),
                activity.indexOf("private void put("));
        assertTrue(loadProfile.contains("if (!values.has(\"虚拟屏幕DPI\")) put(\"虚拟屏幕DPI\", LegacyLuaConfig.defaults().get(\"虚拟屏幕DPI\"))"));

        String runtime = new String(Files.readAllBytes(sources.resolve("LegacyLuaRuntime.java")), StandardCharsets.UTF_8);
        String getDpi = runtime.substring(runtime.indexOf("globals.set(\"getDisplayDpi\""),
                runtime.indexOf("globals.set(\"exitScript\""));
        assertTrue(getDpi.contains("config.optInt(\"虚拟屏幕DPI\","));
        assertTrue(getDpi.contains("context.getResources().getDisplayMetrics().densityDpi"));
        String pollProfile = runtime.substring(runtime.indexOf("globals.set(\"pollWebProfileRequest\""),
                runtime.indexOf("globals.set(\"updateWebRouteRequest\""));
        String updateDpi = pollProfile.substring(pollProfile.indexOf("if (\"虚拟屏幕DPI\".equals(entry.getKey()))"));
        assertTrue(updateDpi.contains("config.put(entry.getKey(), Integer.parseInt(entry.getValue()))"));
    }
}
