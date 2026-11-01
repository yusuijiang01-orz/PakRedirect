package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Opt-in device test of the real Lua map search, coordinate entry, and route submit path. */
public final class RecoveredLuaLiveRouteTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(4);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage5", "route-live-rerun-07").toAbsolutePath().normalize();

    @Test public void sourceLuaReportsBoundedLiveRouteOutcome() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String textProbe = System.getenv("RYLUX_UIAUTOMATOR_TEXT_JAR");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, device, RapidOCR, UIAutomator probe, and evidence paths before the live route test",
                present(adb) && present(serial) && present(python) && present(textProbe) && present(evidence));
        validateTextProbe(Paths.get(textProbe));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-5 route evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            AtomicReference<LiveLuaDeviceHost.Frame> pixelCapture = new AtomicReference<>();
            globals.set("keepCapture", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try { pixelCapture.set(device.capture()); return LuaValue.NONE; }
                    catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("releaseCapture", new ZeroArgFunction() {
                @Override public LuaValue call() { pixelCapture.set(null); return LuaValue.NONE; }
            });
            globals.set("getPixelColor", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try {
                        LiveLuaDeviceHost.Frame frame = pixelCapture.get();
                        if (frame == null) frame = device.frame();
                        int x = frame.mapper.toScreenX(args.arg(1).checkdouble());
                        int y = frame.mapper.toScreenY(args.arg(2).checkdouble());
                        if (x < 0 || y < 0 || x >= frame.image.getWidth() || y >= frame.image.getHeight())
                            return LuaValue.valueOf(0);
                        return LuaValue.valueOf(frame.image.getRGB(x, y) & 0x00ffffff);
                    } catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            // The Android adapter OCRs the requested crop. Keep the ADB live
            // test representative and fast instead of OCRing 1600x900 for each
            // of the route monitor's one-second coordinate checks.
            globals.set("tomocrtexttable", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try {
                        LiveLuaDeviceHost.Frame frame = device.frame();
                        List<LiveLuaDeviceHost.Row> rows = device.rowsInReferenceRegion(frame,
                                args.arg(1).checkdouble(), args.arg(2).checkdouble(),
                                args.arg(3).checkdouble(), args.arg(4).checkdouble());
                        LuaTable result = new LuaTable();
                        for (int i = 0; i < rows.size(); i++) {
                            LiveLuaDeviceHost.Row source = rows.get(i);
                            LuaTable row = new LuaTable();
                            row.set("words", LuaValue.valueOf(source.text));
                            row.set("text", LuaValue.valueOf(source.text));
                            row.set("x", LuaValue.valueOf(source.x));
                            row.set("y", LuaValue.valueOf(source.y));
                            row.set("score", LuaValue.valueOf(source.confidence));
                            result.set(i + 1, row);
                        }
                        return result;
                    } catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            LuaTable ime = new LuaTable();
            ime.set("lock", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.NONE; } });
            ime.set("unlock", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.NONE; } });
            ime.set("setText", new OneArgFunction() {
                @Override public LuaValue call(LuaValue value) {
                    try { device.inputText(value.tojstring()); return LuaValue.TRUE; }
                    catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("imeLib", ime);
            globals.set("keyPress", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try { device.deleteKey(); return LuaValue.NONE; }
                    catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("requireWebRouteDisplay", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try {
                        LiveLuaDeviceHost.Frame frame = device.capture();
                        if (frame.image.getWidth() != 1600 || frame.image.getHeight() != 900)
                            throw new IOException("Expected calibrated 1600x900 landscape, got "
                                    + frame.image.getWidth() + "x" + frame.image.getHeight());
                        return LuaValue.TRUE;
                    } catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("updateWebRouteRequest", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    String state = args.arg(2).optjstring("failed");
                    String message = args.arg(3).optjstring("");
                    try { device.event("route_state", state + " " + safe(message)); }
                    catch (IOException error) { return fail(callbackFailure, error); }
                    return LuaValue.NONE;
                }
            });
            globals.set("找到", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) { return LuaValue.FALSE; }
            });
            globals.set("找到点击", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) { return LuaValue.FALSE; }
            });
            globals.set("在主页面", new ZeroArgFunction() {
                @Override public LuaValue call() {
                    try {
                        LiveLuaDeviceHost.Frame frame = device.frame();
                        List<LiveLuaDeviceHost.Row> rows = device.rows(frame);
                        boolean hud = RecoveredLuaLiveActivityPanelTest.countHudLabels(frame, rows) >= 2;
                        device.event("main_hud", "ocr-confirmed=" + hud);
                        return LuaValue.valueOf(hud);
                    } catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("updateWebProfileRequest", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
            });

            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            globals.set("中越大地图数组", mapAliasesFromSource());
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);

            LiveLuaDeviceHost.Frame initial = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(initial);
            RecoveredLuaLiveActivityPanelTest.writeFrameSummary(device, initial, initialRows, "initial");
            assertTrue("Refusing to route unless localized main-world HUD is OCR-visible; inspect initial capture",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(initial, initialRows) >= 2);

            LuaFunction execute = globals.get("执行网页路线").checkfunction();
            Varargs result = execute.invoke(LuaValue.varargsOf(new LuaValue[]{
                    // The prior 100/200 destination was explicitly rejected by the game.
                    // Probe a nearby point to distinguish an unreachable target from a
                    // broken map-search/coordinate-submit path.
                    LuaValue.valueOf("崇城野外"), LuaValue.valueOf(196), LuaValue.valueOf(204),
                    LuaValue.valueOf("live-route-near-origin-20261022")
            }));
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            String outcome = result.arg(2).optjstring("no Lua diagnostic");
            if (!result.arg1().toboolean()) {
                device.event("result", "BLOCKED pathfinding_not_verified " + safe(outcome));
            }
            assertTrue("Live route did not reach the destination: " + outcome,
                    result.arg1().toboolean() && outcome.contains("抵达"));
            device.event("result", "ARRIVED source_lua_exact_localized_map_search_and_route");
        } catch (Exception | Error failure) {
            try {
                Files.createDirectories(EXPECTED_EVIDENCE);
                Files.writeString(EXPECTED_EVIDENCE.resolve("route-test-failure.txt"),
                        failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()) + "\n",
                        StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
            throw failure;
        }
    }

    private static LuaValue fail(AtomicReference<Throwable> failure, Exception error) {
        failure.compareAndSet(null, error);
        throw new org.luaj.vm2.LuaError(error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }

    private static LuaTable mapAliasesFromSource() throws IOException {
        String source = Files.readString(SCRIPT_ROOT.resolve("封神榜.lua"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                "L0_1 = splitStr\\s+L1_1 = \"([^\"]+)\"\\s+L2_1 = \"\\\\n\"\\s+"
                        + "L0_1 = L0_1\\(L1_1, L2_1\\)\\s+_ENV\\[\"中越大地图数组\"\\] = L0_1")
                .matcher(source);
        assertTrue("Source Lua map alias array was not found", matcher.find());
        String body = matcher.group(1);
        assertTrue("Source Lua map alias array has multiple definitions", !matcher.find());
        String[] rows = body.split(Pattern.quote("\\n"), -1);
        LuaTable aliases = new LuaTable();
        for (int i = 0; i < rows.length; i++) {
            int separator = rows[i].indexOf(',');
            assertTrue("Invalid Chinese/Vietnamese map alias: " + rows[i],
                    separator > 0 && separator < rows[i].length() - 1);
            aliases.set(i + 1, LuaValue.valueOf(rows[i]));
        }
        return aliases;
    }

    private static void validateTextProbe(Path path) throws IOException {
        Path probe = path.toAbsolutePath().normalize();
        assertTrue("UIAutomator text probe does not exist: " + probe, Files.isRegularFile(probe));
        try (JarFile jar = new JarFile(probe.toFile())) {
            ZipEntry dex = jar.getEntry("classes.dex");
            assertTrue("UIAutomator probe JAR is missing classes.dex: " + probe, dex != null);
            byte[] bytes;
            try (java.io.InputStream input = jar.getInputStream(dex)) { bytes = input.readAllBytes(); }
            String dexText = new String(bytes, StandardCharsets.ISO_8859_1);
            assertTrue("JAR is not the RYLUX AccessibilityTextProbe: " + probe,
                    dexText.contains("testhelper/AccessibilityTextProbe"));
        }
    }
}
