package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Live source-Lua test of the configured auto-combat toggle on the real game HUD. */
public final class RecoveredLuaLiveCombatTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage7", "combat-live").toAbsolutePath().normalize();

    @Test public void sourceLuaStartsAutoCombatFromTheLocalizedHud() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python and evidence env vars to opt in to live combat",
                present(adb) && present(serial) && present(python) && present(evidence));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-7 combat evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

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
            LuaTable config = new LuaTable();
            config.set("自动战斗", LuaValue.valueOf("true"));
            globals.set("界面数据", config);

            LiveLuaDeviceHost.Frame initial = device.capture();
            assertTrue("Refusing to start combat outside the recognized world HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(initial, device.rows(initial)) >= 2);
            LuaTable patterns = globals.get("tz").checktable();
            LuaFunction find = globals.get("找到").checkfunction();
            boolean wasOn = find.invoke(patterns.get("自动挂机按钮_已开启")).arg1().toboolean();
            if (wasOn) {
                device.event("combat_state", "auto-combat was already visibly enabled; no duplicate tap");
            } else {
                boolean offState = find.invoke(patterns.get("自动挂机按钮_未开启")).arg1().toboolean();
                assertTrue("Neither calibrated auto-combat state matched; no tap was sent", offState);
                int actionsBefore = device.actionCount();
                globals.get("执行自动战斗").checkfunction().call();
                RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
                assertTrue("Configured auto-combat did not send its Lua-authorized toggle tap",
                        device.actionCount() == actionsBefore + 1);
                device.event("combat_toggle", "source Lua invoked the detected not-enabled control once");
            }

            boolean started = wasOn;
            long verifyUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            while (!started && System.nanoTime() < verifyUntil) {
                Thread.sleep(800);
                LiveLuaDeviceHost.Frame frame = device.capture();
                started = find.invoke(patterns.get("自动挂机按钮_已开启")).arg1().toboolean();
                device.event("combat_state", "capture=" + frame.number + " enabled=" + started);
            }
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("The localized auto-combat enabled signature did not appear after the Lua tap", started);
            device.event("result", "PASS source_lua_auto_combat_toggle_verified");
        }
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
}
