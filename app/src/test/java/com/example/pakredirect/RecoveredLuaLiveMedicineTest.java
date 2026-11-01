package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Live check that source Lua recognizes the medicine assigned in the localized game quickbar. */
public final class RecoveredLuaLiveMedicineTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(4);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage7", "medicine-live").toAbsolutePath().normalize();

    @Test public void sourceLuaRecognizesAssignedBlueMedicineOnLocalizedHud() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python and evidence env vars to opt in to live medicine",
                present(adb) && present(serial) && present(python) && present(evidence));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);

            LiveLuaDeviceHost.Frame frame = device.capture();
            int hudLabels = RecoveredLuaLiveActivityPanelTest.countHudLabels(frame, device.rows(frame));
            assertTrue("Refusing medicine matching outside the recognized game HUD", hudLabels >= 2);
            Varargs slot = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.valueOf("蓝"));
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Source Lua did not detect the blue medicine visibly assigned to the quickbar",
                    !slot.arg1().isnil() && !slot.arg(2).isnil());
            device.event("medicine_quickslot", "blue slot=" + slot.arg1().toint() + "," + slot.arg(2).toint());
            device.event("result", "PASS source_lua_blue_medicine_hud_match");
        }
    }

    /** Checks the red medicine icon matcher against the actual localized bag cell. */
    @Test public void sourceLuaRecognizesRedMedicineInLocalizedBackpack() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python, evidence and RYLUX_LIVE_MEDICINE_SCAN=1; leave the localized backpack open",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "1".equals(System.getenv("RYLUX_LIVE_MEDICINE_SCAN")));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            LiveLuaDeviceHost.Frame frame = device.capture();
            Varargs result = globals.get("检测背包_识别红蓝数量_识别红蓝").checkfunction().invoke(
                    LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(954), LuaValue.valueOf(291),
                            LuaValue.valueOf(1022), LuaValue.valueOf(363)}));
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            device.event("medicine_scan", "localized red medicine cell classification=" + result.arg1().tojstring());
            assertTrue("Source Lua did not recognize the visible red medicine in backpack cell (954,291)",
                    "小红".equals(result.arg1().tojstring()) || "大红".equals(result.arg1().tojstring()));
            device.event("result", "PASS source_lua_red_medicine_backpack_match capture=" + frame.number);
        }
    }

    /** Consumes exactly one blue potion only when explicitly opted into a live test run. */
    @Test public void sourceLuaUsesBlueMedicineWhenConfiguredManaThresholdIsMet() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python, evidence, and RYLUX_LIVE_MEDICINE_USE=1 to consume one test potion",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "1".equals(System.getenv("RYLUX_LIVE_MEDICINE_USE")));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);
            LiveLuaDeviceHost.Frame before = device.capture();
            assertTrue("Refusing medicine input outside the recognized game HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(before, device.rows(before)) >= 2);
            int manaPercent = globals.get("战斗_识别资源百分比").checkfunction().invoke(
                    LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(230), LuaValue.valueOf(65),
                            LuaValue.valueOf(280), LuaValue.valueOf(84)})).arg1().toint();
            assertTrue("Mana OCR must return a valid percentage", manaPercent >= 0 && manaPercent <= 100);
            int countBefore = quickbarCount(device.rows(before));
            assertTrue("The current screen must expose the blue potion stack count before testing its use",
                    countBefore > 0);

            LuaTable config = new LuaTable();
            config.set("自动使用红药", LuaValue.valueOf("false"));
            config.set("自动使用蓝药", LuaValue.valueOf("true"));
            config.set("法力药触发阈值", LuaValue.valueOf("100"));
            config.set("蓝药冷却秒", LuaValue.valueOf("0"));
            globals.set("界面数据", config);
            int actionsBefore = device.actionCount();
            boolean used = globals.get("执行自动补给").checkfunction().call().toboolean();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Lua should use blue medicine when observed mana is at/below the configured threshold",
                    used && device.actionCount() == actionsBefore + 1);
            device.event("medicine_use", "source Lua used one blue potion; mana=" + manaPercent
                    + "% threshold=100 stackBefore=" + countBefore);

            Thread.sleep(1800);
            device.invalidate();
            LiveLuaDeviceHost.Frame after = device.capture();
            assertTrue("The game HUD should remain visible after the potion tap",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(after, device.rows(after)) >= 2);
            int countAfter = quickbarCount(device.rows(after));
            assertEquals("Exactly one quickbar medicine should be consumed", countBefore - 1, countAfter);
            device.event("result", "PASS source_lua_threshold_used_one_blue_potion stack="
                    + countBefore + "->" + countAfter);
        }
    }

    /** Consumes one assigned red potion when the observed health is below its configured threshold. */
    @Test public void sourceLuaUsesRedMedicineWhenConfiguredHealthThresholdIsMet() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python, evidence, and RYLUX_LIVE_RED_MEDICINE_USE=1 to consume one test potion",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "1".equals(System.getenv("RYLUX_LIVE_RED_MEDICINE_USE")));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);

            LiveLuaDeviceHost.Frame before = device.capture();
            assertTrue("Refusing medicine input outside the recognized game HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(before, device.rows(before)) >= 2);
            int healthPercent = globals.get("战斗_识别资源百分比").checkfunction().invoke(
                    LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(230), LuaValue.valueOf(45),
                            LuaValue.valueOf(275), LuaValue.valueOf(64)})).arg1().toint();
            assertTrue("Health OCR must return a valid percentage", healthPercent >= 0 && healthPercent <= 100);
            int countBefore = redQuickbarCount(device, before);
            assertTrue("The current screen must expose the red potion stack count before testing its use",
                    countBefore > 0);
            Varargs redSlot = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.valueOf("红"));
            assertTrue("Source Lua must positively match the assigned red HUD quickslot",
                    !redSlot.arg1().isnil() && !redSlot.arg(2).isnil());

            LuaTable config = new LuaTable();
            config.set("自动使用红药", LuaValue.valueOf("true"));
            config.set("自动使用蓝药", LuaValue.valueOf("false"));
            config.set("生命药触发阈值", LuaValue.valueOf("100"));
            globals.set("界面数据", config);
            int actionsBefore = device.actionCount();
            boolean used = globals.get("执行自动补给").checkfunction().call().toboolean();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Lua should tap the matched red medicine when observed health is under threshold",
                    used && device.actionCount() == actionsBefore + 1);
            device.event("medicine_use", "source Lua used one red potion; health=" + healthPercent
                    + "% threshold=100 stackBefore=" + countBefore);

            Thread.sleep(1800);
            device.invalidate();
            LiveLuaDeviceHost.Frame after = device.capture();
            assertTrue("The game HUD should remain visible after the red potion tap",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(after, device.rows(after)) >= 2);
            int countAfter = redQuickbarCount(device, after);
            assertEquals("Exactly one red quickbar medicine should be consumed", countBefore - 1, countAfter);
            device.event("result", "PASS source_lua_health_threshold_used_one_red_potion stack="
                    + countBefore + "->" + countAfter);
        }
    }

    /** Adds a second blue stack through the actual localized item-menu flow; no medicine is consumed. */
    @Test public void sourceLuaAddsMedicineThroughItemQuickMenuAndEmptyQuickslot() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python, evidence, and RYLUX_LIVE_MEDICINE_SETUP=1 to assign another quickslot",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "1".equals(System.getenv("RYLUX_LIVE_MEDICINE_SETUP")));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);
            LiveLuaDeviceHost.Frame initial = device.capture();
            assertTrue("Refusing quickslot setup outside the recognized game HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(initial, device.rows(initial)) >= 2);

            LuaValue realMatcher = globals.get("战斗_查找快捷栏药品");
            AtomicInteger firstProbe = new AtomicInteger();
            globals.set("战斗_查找快捷栏药品", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if ("蓝".equals(args.arg1().tojstring()) && firstProbe.getAndIncrement() == 0) {
                        return LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaValue.NIL});
                    }
                    return realMatcher.invoke(args);
                }
            });
            int actionsBefore = device.actionCount();
            boolean assigned = globals.get("战斗_配置快捷栏药品").checkfunction()
                    .call(LuaValue.valueOf("蓝")).toboolean();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Source Lua did not complete the localized click → 快捷 → empty slot → medicine flow",
                    assigned && device.actionCount() >= actionsBefore + 7);

            int blueSlots = countBlueQuickslots(globals);
            assertTrue("The newly configured blue potion must be visible in at least two quickslots", blueSlots >= 2);
            LiveLuaDeviceHost.Frame after = device.capture();
            assertTrue("Quickslot setup must close its panels and return to the game HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(after, device.rows(after)) >= 2);
            device.event("medicine_setup", "source Lua double-tapped blue medicine through 快捷 to assign an empty slot; detectedBlueSlots="
                    + blueSlots + " actions=" + (device.actionCount() - actionsBefore));
            device.event("result", "PASS source_lua_localized_medicine_quickslot_setup");
        }
    }

    /** Assigns the visible localized red medicine to an empty quickslot without using it. */
    @Test public void sourceLuaConfiguresRedMedicineThroughQuickbarPage() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python, evidence and RYLUX_LIVE_RED_SETUP=1 to run red quickslot setup",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "1".equals(System.getenv("RYLUX_LIVE_RED_SETUP")));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 medicine evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);

            LiveLuaDeviceHost.Frame start = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(start);
            boolean backpack = false;
            for (LiveLuaDeviceHost.Row row : initialRows) {
                if (row.confidence >= 0.7 && row.text.contains("背包")) backpack = true;
            }
            // The HUD itself contains the label “背包”; do not classify it as
            // an open backpack panel when several HUD anchors are also visible.
            boolean alreadyHud = RecoveredLuaLiveActivityPanelTest.countHudLabels(start, initialRows) >= 2;
            if (!alreadyHud) {
                assertTrue("Red setup may start only from the HUD or recognized backpack", backpack);
                device.tap(1244, 104, "close recognized backpack before red setup");
                Thread.sleep(500);
                device.invalidate();
                LiveLuaDeviceHost.Frame hud = device.capture();
                assertTrue("The localized game HUD should be restored before red setup",
                        RecoveredLuaLiveActivityPanelTest.countHudLabels(hud, device.rows(hud)) >= 2);
            }

            Varargs existing = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.valueOf("红"));
            if (!existing.arg1().isnil()) {
                device.event("medicine_setup", "red potion already assigned at "
                        + existing.arg1().toint() + "," + existing.arg(2).toint() + "; skipped duplicate assignment");
                device.event("result", "PASS source_lua_red_quickslot_already_configured");
                return;
            }

            int actionsBefore = device.actionCount();
            boolean assigned = globals.get("战斗_配置快捷栏药品").checkfunction()
                    .call(LuaValue.valueOf("红")).toboolean();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Source Lua did not locate and assign the red potion through its localized quickbar page",
                    assigned && device.actionCount() >= actionsBefore + 7);
            Varargs redSlot = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.valueOf("红"));
            assertTrue("The red medicine must be detectable in the HUD quickbar after assignment",
                    !redSlot.arg1().isnil() && !redSlot.arg(2).isnil());
            device.event("medicine_setup", "red medicine assigned to " + redSlot.arg1().toint() + "," + redSlot.arg(2).toint());
            device.event("result", "PASS source_lua_localized_red_quickslot_setup");
        }
    }

    private static int countBlueQuickslots(Globals globals) {
        int count = 0;
        int[] xs = {971, 1051, 1131, 1211};
        int[] ys = {310, 375};
        for (int y : ys) for (int x : xs) {
            Varargs found = globals.get("检测背包_识别红蓝数量_识别红蓝").checkfunction().invoke(
                    LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(x - 24), LuaValue.valueOf(y - 24),
                            LuaValue.valueOf(x + 24), LuaValue.valueOf(y + 24)}));
            if ("小蓝".equals(found.arg1().tojstring()) || "大蓝".equals(found.arg1().tojstring())) count++;
        }
        return count;
    }

    private static int quickbarCount(java.util.List<LiveLuaDeviceHost.Row> rows) {
        for (LiveLuaDeviceHost.Row row : rows) {
            if (row.x >= 1170 && row.x <= 1250 && row.y >= 355 && row.y <= 425
                    && row.confidence >= 0.8 && row.text.matches("\\d+")) {
                try { return Integer.parseInt(row.text); }
                catch (NumberFormatException ignored) { return -1; }
            }
        }
        return -1;
    }

    private static int redQuickbarCount(LiveLuaDeviceHost device, LiveLuaDeviceHost.Frame frame)
            throws Exception {
        // OCR just the red stack digits, excluding the adjacent red bottle that
        // RapidOCR can merge into a false trailing digit on a full HUD frame.
        for (LiveLuaDeviceHost.Row row : device.rowsInReferenceRegion(frame, 1098, 292, 1132, 330)) {
            if (row.confidence >= 0.75 && row.text.matches("\\d+")) {
                try { return Integer.parseInt(row.text); }
                catch (NumberFormatException ignored) { return -1; }
            }
        }
        return -1;
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
}
