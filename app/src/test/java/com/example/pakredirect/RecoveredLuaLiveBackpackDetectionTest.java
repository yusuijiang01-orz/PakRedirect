package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Read-only ADB/OCR verification of the live backpack detector on the desktop game. */
public final class RecoveredLuaLiveBackpackDetectionTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261102",
            "backpack-detection-live").toAbsolutePath().normalize();

    @Test public void sourceLuaReadsLiveBackpackAndAppliesEitherThresholdWithoutTouchingItems()
            throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        String targetMode = System.getenv("RYLUX_LIVE_TARGET_MODE");
        assumeTrue("Set ADB, RapidOCR, evidence, and RYLUX_LIVE_TARGET_MODE=desktop",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && "desktop".equalsIgnoreCase(targetMode));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertEquals("Use the dedicated live backpack evidence folder", EXPECTED_EVIDENCE, evidencePath);

        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            LiveLuaDeviceHost.Frame frame = device.capture();
            device.event("initial_state", "read-only desktop game capture=" + frame.number);

            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            LegacyLuaConfig.installWorkbenchConfigFlag(globals);
            RecoveredLuaLiveActivityPanelTest.installLiveApis(globals, device, callbackFailure);
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }

            LuaValue backpackTemplate = globals.get("tz").get("背包页面");
            assertFalse("The source template table must define the localized backpack page", backpackTemplate.isnil());
            globals.set("找到", new OneArgFunction() {
                @Override public LuaValue call(LuaValue template) {
                    return LuaValue.valueOf(template.raweq(backpackTemplate));
                }
            });
            globals.set("在主页面", new ZeroArgFunction() {
                @Override public LuaValue call() { return LuaValue.FALSE; }
            });
            globals.set("sleep", new OneArgFunction() {
                @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
            });
            globals.set("修改数据库", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
            });
            globals.set("tomocrtextall", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try {
                        LiveLuaDeviceHost.Frame current = device.frame();
                        List<LiveLuaDeviceHost.Row> rows = device.rowsInReferenceRegion(current,
                                args.arg(1).checkdouble(), args.arg(2).checkdouble(),
                                args.arg(3).checkdouble(), args.arg(4).checkdouble());
                        StringBuilder text = new StringBuilder();
                        for (LiveLuaDeviceHost.Row row : rows) {
                            if (text.length() > 0) text.append(' ');
                            text.append(row.text);
                        }
                        return LuaValue.valueOf(text.toString());
                    } catch (Exception error) {
                        callbackFailure.compareAndSet(null, error);
                        throw new LuaError(error.getClass().getSimpleName() + ": " + error.getMessage());
                    }
                }
            });

            LuaTable config = new LuaTable();
            for (String key : Arrays.asList("购买小红", "购买小蓝", "出售全部红", "出售全部蓝"))
                config.set(key, LuaValue.valueOf("false"));
            globals.set("界面数据", config);
            globals.set("背包检测间隔秒", LuaValue.valueOf(60));
            globals.set("背包保留重量", LuaValue.valueOf(3));
            globals.set("背包保留格子", LuaValue.valueOf(2));
            long now = globals.get("tickCount").checkfunction().call().checklong();
            globals.set("上次检测元宝", LuaValue.valueOf(now));
            globals.set("上次检测背包", LuaValue.NIL);

            LuaValue slotParser = globals.get("检测背包_解析空格子");
            LuaValue weightParser = globals.get("检测背包_解析重量");
            String slotText = ocrText(device, frame, 1045, 604, 1215, 655);
            String weightText = ocrText(device, frame, 1100, 225, 1210, 260);
            Varargs slots = slotParser.checkfunction().invoke(LuaValue.valueOf(slotText));
            Varargs weight = weightParser.checkfunction().invoke(LuaValue.valueOf(weightText));
            assertEquals("The real footer OCR must read 27 free slots", 27, slots.arg(1).toint());
            assertEquals(35, slots.arg(2).toint());
            assertEquals("The real weight OCR must read 146/200", 146, weight.arg(1).toint());
            assertEquals(200, weight.arg(2).toint());
            device.event("inventory_counts", "OCR slots=" + slotText + "; weight=" + weightText);

            LuaValue detector = globals.get("检测背包").checkfunction();
            assertTrue("With 27/35 slots and 146/200 weight, default thresholds must continue farming",
                    detector.call().toboolean());
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);

            globals.set("上次检测背包", LuaValue.NIL);
            globals.set("背包保留重量", LuaValue.valueOf(55));
            assertFalse("The weight threshold must trigger when only 54 capacity remains",
                    detector.call().toboolean());
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);

            globals.set("上次检测背包", LuaValue.NIL);
            globals.set("背包保留重量", LuaValue.valueOf(3));
            globals.set("背包保留格子", LuaValue.valueOf(28));
            assertFalse("The independent free-slot threshold must trigger at 27/35",
                    detector.call().toboolean());
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertEquals("The test must never tap, sell, sort, or move the live character", 0,
                    device.actionCount());
            device.event("result", "PASS live OCR and both independent trigger decisions; no game actions");
        } catch (Exception | Error failure) {
            Files.createDirectories(EXPECTED_EVIDENCE);
            Files.writeString(EXPECTED_EVIDENCE.resolve("backpack-detection-test-failure.txt"),
                    failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()) + "\n",
                    StandardCharsets.UTF_8);
            throw failure;
        }
    }

    private static String ocrText(LiveLuaDeviceHost device, LiveLuaDeviceHost.Frame frame,
                                  int left, int top, int right, int bottom) throws Exception {
        StringBuilder text = new StringBuilder();
        for (LiveLuaDeviceHost.Row row : device.rowsInReferenceRegion(frame, left, top, right, bottom)) {
            if (text.length() > 0) text.append(' ');
            text.append(row.text);
        }
        return text.toString();
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[A-Za-z0-9]{5,}", "[redacted]");
    }
}
