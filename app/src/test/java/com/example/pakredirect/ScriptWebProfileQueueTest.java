package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

public final class ScriptWebProfileQueueTest {
    @Test public void everyWebWorkbenchRuntimeFieldAcceptsItsDisplayedDefaultAndNumericBounds() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path page = cwd.resolve("../../tools/adb-game-test-panel.html");
        if (!Files.isRegularFile(page)) page = cwd.resolve("tools/adb-game-test-panel.html");
        String html = Files.readString(page, StandardCharsets.UTF_8);
        assertTrue("runtime collection must include the settings page", html.contains("for(const page of pages.slice(1))"));
        String specs = html.substring(html.indexOf("const pages=["), html.indexOf("const defaultProfile="));
        Set<String> localOnly = new HashSet<>(Arrays.asList("战斗技能阈值", "装备耐久阈值",
                "引擎模式", "远程调试主机", "远程调试端口"));
        String localDeclaration = html.substring(html.indexOf("const localOnlySettings="),
                html.indexOf("function collectRuntimeProfile()"));
        Set<String> declaredLocalOnly = new HashSet<>();
        Matcher localNames = Pattern.compile("'([^']+)'").matcher(localDeclaration);
        while (localNames.find()) declaredLocalOnly.add(localNames.group(1));
        assertEquals("web runtime exclusion list", localOnly, declaredLocalOnly);
        Map<String, String> values = new LinkedHashMap<>();
        Matcher fields = Pattern.compile("\\['(?:map|switch|number|choice|text)','([^']+)'").matcher(specs);
        while (fields.find()) {
            String key = fields.group(1);
            if (localOnly.contains(key)) continue;
            String fallback = LegacyLuaConfig.defaults().get(key);
            if ("虚拟屏幕宽度".equals(key)) fallback = "720";
            if ("虚拟屏幕高度".equals(key)) fallback = "1280";
            if (ScriptRuntimeLog.AUTO_SAVE.equals(key) || ScriptRuntimeLog.SOURCE_CONTEXT.equals(key)) {
                fallback = "true";
            }
            assertNotNull("missing Lua default for workbench field " + key, fallback);
            values.put(key, fallback);
        }
        assertEquals("workbench runtime field count", 53, values.size());
        ScriptWebProfileQueue.validate("all-workbench-fields", values);

        Matcher numbers = Pattern.compile("\\['number','([^']+)','[^']*','[^']*',(\\d+),(\\d+)\\]").matcher(specs);
        while (numbers.find()) {
            String key = numbers.group(1);
            if (localOnly.contains(key)) continue;
            String original = values.get(key);
            values.put(key, numbers.group(2));
            ScriptWebProfileQueue.validate("minimum-bound", values);
            values.put(key, numbers.group(3));
            ScriptWebProfileQueue.validate("maximum-bound", values);
            values.put(key, original);
        }
        for (String key : localOnly) {
            try {
                ScriptWebProfileQueue.validate("local-only", java.util.Collections.singletonMap(key, "0"));
                throw new AssertionError("local-only field accepted by Lua queue: " + key);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("不允许"));
            }
        }
    }

    @Test public void virtualDpiAcceptsOnlyIntegersWithinTheWidgetBounds() {
        for (String valid : Arrays.asList("1", "320", "2000")) {
            ScriptWebProfileQueue.validate("dpi-valid", java.util.Collections.singletonMap("虚拟屏幕DPI", valid));
        }
        for (String invalid : Arrays.asList("0", "2001", "99999", "-1", "320.5", "true", "", " 320")) {
            try {
                ScriptWebProfileQueue.validate("dpi-invalid", java.util.Collections.singletonMap("虚拟屏幕DPI", invalid));
                throw new AssertionError("invalid DPI accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("虚拟屏幕DPI"));
            }
        }
    }

    @Test public void logSwitchesAcceptOnlyBooleans() {
        for (String key : Arrays.asList(ScriptRuntimeLog.AUTO_SAVE, ScriptRuntimeLog.SOURCE_CONTEXT)) {
            ScriptWebProfileQueue.validate("enabled", java.util.Collections.singletonMap(key, "true"));
            ScriptWebProfileQueue.validate("disabled", java.util.Collections.singletonMap(key, "false"));
            for (String invalid : Arrays.asList("1", "0", "yes", "")) {
                try {
                    ScriptWebProfileQueue.validate("invalid", java.util.Collections.singletonMap(key, invalid));
                    throw new AssertionError("invalid log switch accepted: " + invalid);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains("开关值无效"));
                }
            }
        }
    }

    @Test public void acceptsCompleteRuntimeSettingsAndReportsApplication() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("挂机点地图", "37");
        values.put("卖货地图", "0");
        values.put("自动战斗", "true");
        values.put("虚拟屏幕宽度", "720");
        values.put("虚拟屏幕高度", "1280");
        values.put("虚拟屏幕DPI", "480");
        values.put("开启vpn", "false");
        values.put("出售物品清单", "普通材料\n药品");
        ScriptWebProfileQueue queue = new ScriptWebProfileQueue();
        ScriptWebProfileQueue.Request request = queue.submit("profile-1", values);
        assertEquals(ScriptWebProfileQueue.QUEUED, request.state);
        assertEquals(request, queue.poll());
        assertEquals("480", request.values.get("虚拟屏幕DPI"));
        assertEquals(ScriptWebProfileQueue.APPLYING, request.state);
        queue.update("profile-1", ScriptWebProfileQueue.APPLIED, "已应用");
        assertEquals(ScriptWebProfileQueue.APPLIED, queue.get("profile-1").state);
    }

    @Test public void rejectsUnsupportedSettingsInvalidMapAndConcurrentProfile() {
        ScriptWebProfileQueue queue = new ScriptWebProfileQueue();
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("未知开关", "true");
        try { queue.submit("unknown", invalid); throw new AssertionError("unknown config accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("不允许")); }

        invalid.clear();
        invalid.put("挂机点地图", "38");
        try { queue.submit("bad-map", invalid); throw new AssertionError("invalid map accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("范围")); }

        invalid.clear();
        invalid.put("虚拟屏幕宽度", "20000");
        try { queue.submit("bad-screen", invalid); throw new AssertionError("invalid display size accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("范围")); }

        Map<String, String> valid = new LinkedHashMap<>();
        valid.put("自动战斗", "false");
        ScriptWebProfileQueue.Request active = queue.submit("active", valid);
        assertNotNull(queue.poll());
        try { queue.submit("second", valid); throw new AssertionError("concurrent profile accepted"); }
        catch (IllegalStateException expected) { assertEquals(ScriptWebProfileQueue.APPLYING, active.state); }
    }
}
