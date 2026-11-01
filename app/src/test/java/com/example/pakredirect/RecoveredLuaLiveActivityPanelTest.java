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
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Opt-in live check of the current source Lua activity-panel OCR and safe close path. */
public final class RecoveredLuaLiveActivityPanelTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage4", "activity-live").toAbsolutePath().normalize();

    @Test public void sourceLuaOcrRecognizesAndClosesLiveActivityPanel() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python and evidence env vars to opt in to the live activity test",
                present(adb) && present(serial) && present(python) && present(evidence));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertEquals("Point RYLUX_LIVE_EVIDENCE_DIR at the required activity-live evidence folder",
                EXPECTED_EVIDENCE, evidencePath);

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            installLiveApis(globals, device, callbackFailure);
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                Path sourcePath = SCRIPT_ROOT.resolve(module);
                loadCurrentSourceModule(globals, sourcePath, module);
            }
            installTapCompatibility(globals);

            LuaFunction isActivityOpen = globals.get("活动_页面已打开").checkfunction();
            LuaFunction closeActivity = globals.get("活动_关闭页面").checkfunction();
            LiveLuaDeviceHost.Frame initial = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(initial);
            writeFrameSummary(device, initial, initialRows, "initial");
            boolean alreadyOpen = isActivityOpen.call().toboolean();
            checkCallbackFailure(callbackFailure);

            if (!alreadyOpen) {
                int hudLabels = countHudLabels(initial, initialRows);
                ActivityShortcut shortcut = findActivityShortcut(initial, initialRows);
                assertTrue("Refusing to tap: main HUD markers are not visible in OCR",
                        hudLabels >= 2);
                assertTrue("Refusing to tap: no high-confidence main-screen 活动 shortcut was found "
                                + "in its expected toolbar cell; inspect capture-001.ocr.tsv",
                        shortcut != null);
                double tapReferenceY = 123;
                int tapRawX = initial.mapper.toScreenX((int) Math.round(shortcut.referenceX));
                int tapRawY = initial.mapper.toScreenY((int) Math.round(tapReferenceY));
                device.event("activity_shortcut", "capture=" + initial.number
                        + " label=活动 label_ref="
                        + Math.round(shortcut.referenceX) + "," + Math.round(shortcut.referenceY)
                        + " label_raw=" + Math.round(shortcut.rawX) + "," + Math.round(shortcut.rawY)
                        + " tap_ref=" + Math.round(shortcut.referenceX) + "," + Math.round(tapReferenceY)
                        + " tap_raw=" + tapRawX + "," + tapRawY
                        + " confidence=" + shortcut.confidence + " icon=left-calendar");
                device.tap(shortcut.referenceX, tapReferenceY, "OCR-gated left calendar-style 活动 shortcut");
            } else {
                device.event("initial_state", "Lua predicate confirmed activity panel already open");
            }

            boolean panelConfirmed = alreadyOpen;
            long waitUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            while (!panelConfirmed && System.nanoTime() < waitUntil) {
                Thread.sleep(700);
                LiveLuaDeviceHost.Frame frame = device.capture();
                panelConfirmed = isActivityOpen.call().toboolean();
                checkCallbackFailure(callbackFailure);
                device.event("activity_predicate", "capture=" + frame.number + " confirmed=" + panelConfirmed);
            }
            assertTrue("Current source Lua did not confirm the activity title plus menu OCR after opening; "
                    + "no close touch was sent", panelConfirmed);

            boolean closeDispatched = closeActivity.call().toboolean();
            checkCallbackFailure(callbackFailure);
            assertTrue("Lua activity close returned false after the panel predicate was true", closeDispatched);
            device.event("close", "source Lua activity predicate authorized close");

            boolean restored = false;
            long restoreUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            while (!restored && System.nanoTime() < restoreUntil) {
                Thread.sleep(700);
                LiveLuaDeviceHost.Frame frame = device.capture();
                List<LiveLuaDeviceHost.Row> rows = device.rows(frame);
                boolean panelStillOpen = isActivityOpen.call().toboolean();
                int hudLabels = countHudLabels(frame, rows);
                restored = !panelStillOpen && hudLabels >= 2;
                device.event("restore_check", "capture=" + frame.number + " panelOpen=" + panelStillOpen
                        + " hudLabels=" + hudLabels + " restored=" + restored);
            }
            checkCallbackFailure(callbackFailure);
            assertTrue("Activity panel did not close or main HUD OCR did not return; inspect evidence captures",
                    restored);
            assertEquals("The activity test should send exactly one close touch",
                    alreadyOpen ? 1 : 2, device.actionCount());
            device.event("result", "PASS source_lua_activity_open_predicate_and_close_verified actions="
                    + device.actionCount());
        } catch (Exception | Error failure) {
            try {
                Files.createDirectories(EXPECTED_EVIDENCE);
                Files.writeString(EXPECTED_EVIDENCE.resolve("activity-test-failure.txt"),
                        failure.getClass().getSimpleName() + ": " + safe(failure.getMessage()) + "\n",
                        StandardCharsets.UTF_8);
            } catch (IOException ignored) { }
            throw failure;
        }
    }

    static void installLiveApis(Globals globals, LiveLuaDeviceHost device,
                                       AtomicReference<Throwable> callbackFailure) {
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try {
                    device.tap(args.arg(1).checkdouble(), args.arg(2).checkdouble(), "source Lua tap");
                    return LuaValue.TRUE;
                } catch (Exception error) { return fail(callbackFailure, error); }
            }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                try {
                    long duration = Math.max(0, Math.min(5_000, millis.checklong()));
                    device.checkDeadline();
                    Thread.sleep(duration);
                    if (duration >= 100) device.invalidate();
                    return LuaValue.NONE;
                } catch (Exception error) { return fail(callbackFailure, error); }
            }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { device.invalidate(); return LuaValue.NONE; }
        });
        VarArgFunction quiet = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("print", quiet);
        globals.set("toast", quiet);
        globals.set("hideToast", quiet);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return ocrRowsInReferenceCrop(device, args); }
                catch (Exception error) { return fail(callbackFailure, error); }
            }
        });
    }

    static void installTapCompatibility(Globals globals) {
        globals.set("scxx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        globals.set("运行记录_增加", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        globals.set("取msg主要信息", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) { return value; }
        });
        if (globals.get("释放").isnil()) {
            globals.set("释放", new ZeroArgFunction() {
                @Override public LuaValue call() { return LuaValue.NONE; }
            });
        }
    }

    static void loadCurrentSourceModule(Globals globals, Path path, String name) throws IOException {
        if ("挂机模式.lua".equals(name)) {
            LuaTable preload = globals.get("package").get("preload").checktable();
            if (preload.get("网页路线").isnil()) {
                Path routePath = path.resolveSibling("网页路线.lua");
                String routeSource = Files.readString(routePath, StandardCharsets.UTF_8);
                preload.set("网页路线", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        globals.load(routeSource, "网页路线.lua", globals).call();
                        return LuaValue.TRUE;
                    }
                });
            }
        }
        String source = Files.readString(path, StandardCharsets.UTF_8);
        LuaValue module = globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), name, globals).call();
        if ("w.lua".equals(name)) globals.set("w", module);
    }

    static Varargs ocrRowsInReferenceCrop(LiveLuaDeviceHost device, Varargs args) throws Exception {
        LiveLuaDeviceHost.Frame frame = device.frame();
        double left = args.arg(1).checkdouble(), top = args.arg(2).checkdouble();
        double right = args.arg(3).checkdouble(), bottom = args.arg(4).checkdouble();
        if (right < left || bottom < top) throw new IOException("Invalid Lua OCR crop");
        LuaTable result = new LuaTable();
        int index = 1;
        // Match the runtime API: recognize only the requested crop. Doing a
        // full-frame OCR for every small Lua query made one route test take
        // six minutes and starved the device before pathfinding could be judged.
        for (LiveLuaDeviceHost.Row row : device.rowsInReferenceRegion(frame, left, top, right, bottom)) {
            LuaTable line = new LuaTable();
            line.set("words", LuaValue.valueOf(row.text));
            line.set("text", LuaValue.valueOf(row.text));
            line.set("x", LuaValue.valueOf(row.x));
            line.set("y", LuaValue.valueOf(row.y));
            line.set("score", LuaValue.valueOf(row.confidence));
            line.set("confidence", LuaValue.valueOf(row.confidence));
            line.set("success", LuaValue.TRUE);
            result.set(index++, line);
        }
        return result;
    }

    private static ActivityShortcut findActivityShortcut(LiveLuaDeviceHost.Frame frame,
            List<LiveLuaDeviceHost.Row> rows) {
        ActivityShortcut best = null;
        for (LiveLuaDeviceHost.Row row : rows) {
            int x = frame.mapper.toReferenceX(row.x), y = frame.mapper.toReferenceY(row.y);
            if (!"活动".equals(row.text.trim()) || row.confidence < 0.65
                    || x < 840 || x > 900 || y < 145 || y > 175) continue;
            if (best == null || row.confidence > best.confidence)
                best = new ActivityShortcut(x, y, row.x, row.y, row.confidence);
        }
        return best;
    }

    static int countHudLabels(LiveLuaDeviceHost.Frame frame, List<LiveLuaDeviceHost.Row> rows) {
        boolean inventory = false, character = false, skill = false;
        for (LiveLuaDeviceHost.Row row : rows) {
            int x = frame.mapper.toReferenceX(row.x), y = frame.mapper.toReferenceY(row.y);
            if (y < 580 || y > 710) continue;
            String text = row.text.replaceAll("\\s+", "");
            if (text.contains("背包")) inventory = true;
            if (text.contains("角色")) character = true;
            if (text.contains("技能")) skill = true;
        }
        return (inventory ? 1 : 0) + (character ? 1 : 0) + (skill ? 1 : 0);
    }

    static void writeFrameSummary(LiveLuaDeviceHost device, LiveLuaDeviceHost.Frame frame,
                                          List<LiveLuaDeviceHost.Row> rows, String stage) throws IOException {
        StringBuilder summary = new StringBuilder(stage).append(" capture=").append(frame.number)
                .append(" screenshot=").append(frame.image.getWidth()).append('x').append(frame.image.getHeight())
                .append(" rows=").append(rows.size()).append('\n');
        for (LiveLuaDeviceHost.Row row : rows) {
            summary.append(frame.mapper.toReferenceX(row.x)).append(',')
                    .append(frame.mapper.toReferenceY(row.y)).append('\t')
                    .append(String.format(java.util.Locale.ROOT, "%.4f", row.confidence)).append('\t')
                    .append(row.text.replace('\n', ' ')).append('\n');
        }
        Files.writeString(device.evidence().resolve(stage + "-ocr-reference.tsv"), summary,
                StandardCharsets.UTF_8);
    }

    private static LuaValue fail(AtomicReference<Throwable> failure, Throwable error) {
        failure.compareAndSet(null, error);
        throw new LuaError(error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
    }

    static void checkCallbackFailure(AtomicReference<Throwable> failure) throws IOException {
        Throwable cause = failure.get();
        if (cause != null) throw new IOException("Live activity Lua callback failed: " + safe(cause.getMessage()), cause);
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[A-Za-z0-9]{5,}", "[redacted]");
    }

    private static final class ActivityShortcut {
        final double referenceX, referenceY, rawX, rawY, confidence;
        ActivityShortcut(double referenceX, double referenceY, double rawX, double rawY, double confidence) {
            this.referenceX = referenceX; this.referenceY = referenceY;
            this.rawX = rawX; this.rawY = rawY; this.confidence = confidence;
        }
    }
}
