package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Exercises the recovered entry point and require chain without touching a game or device. */
public final class RecoveredLuaStartupTest {
    private static final String REACHED_MONITOR = "TEST_REACHED_MONITOR_LOOP";
    private static final String REACHED_WORKER_SLEEP = "TEST_REACHED_WORKER_SLEEP";
    private static final long WORKER_SLEEP_PROBE_LIMIT = 1024;

    @Test public void recoveredEntryAndGameWorkerPauseResumeAndStop() throws Exception {
        exerciseRecoveredLifecycle(false);
    }

    @Test public void globalStopWakesPausedRecoveredEntryAndGameWorker() throws Exception {
        exerciseRecoveredLifecycle(true);
    }

    private static void exerciseRecoveredLifecycle(boolean stopWhilePaused) throws Exception {
        Path assets = scriptAssets();
        Globals globals = JsePlatform.standardGlobals();
        AtomicBoolean monitorStarted = new AtomicBoolean();
        AtomicReference<LuaFunction> capturedWorker = new AtomicReference<>();
        installHostStubs(globals, monitorStarted, new AtomicBoolean(), capturedWorker,
                new AtomicLong(), new AtomicLong());
        installRequire(globals, assets, new ArrayList<>(), monitorStarted);
        ScriptExecutionControl control = new ScriptExecutionControl();
        LuaInstructionGate gate = new LuaInstructionGate(globals, control);
        AtomicLong mainProgress = new AtomicLong();
        AtomicLong workerProgress = new AtomicLong();
        AtomicLong hudProgress = new AtomicLong();
        AtomicReference<Thread> gameWorker = new AtomicReference<>();
        AtomicReference<Thread> hudWorker = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Thread> entry = new AtomicReference<>();

        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                Thread current = Thread.currentThread();
                if (current == entry.get() && monitorStarted.get()) mainProgress.incrementAndGet();
                if (current == gameWorker.get()) workerProgress.incrementAndGet();
                gate.sleep(1);
                return LuaValue.NONE;
            }
        });
        globals.set("showHUD", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                hudProgress.incrementAndGet();
                return LuaValue.NONE;
            }
        });
        globals.set("beginThread", new OneArgFunction() {
            @Override public LuaValue call(LuaValue function) {
                Thread worker = recoveredRunner("recovered-hud", control, gate,
                        function.checkfunction(), failure);
                hudWorker.set(worker);
                worker.start();
                return LuaValue.ONE;
            }
        });
        LuaTable threadApi = new LuaTable();
        threadApi.set("newThread", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                monitorStarted.set(true);
                Thread worker = recoveredRunner("recovered-game-worker", control, gate,
                        args.arg1().checkfunction(), failure);
                gameWorker.set(worker);
                worker.start();
                LuaTable handle = new LuaTable();
                handle.set("stopThread", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        control.cancelWorker(worker);
                        return LuaValue.TRUE;
                    }
                });
                return handle;
            }
        });
        globals.set("Thread", threadApi);
        LuaFunction recoveredEntry = compileLua(globals, assets, "脚本/封神榜.lua");
        control.start();
        Thread runner = recoveredRunner("recovered-entry", control, gate, recoveredEntry, failure);
        entry.set(runner);
        runner.start();
        try {
            awaitProgress(mainProgress, 2, "recovered monitor", failure);
            assertTrue("monitor was never entered; entry " + runner.getState()
                    + ", HUD callbacks " + hudProgress.get() + ", worker launched "
                    + (gameWorker.get() != null), monitorStarted.get());
            awaitProgress(workerProgress, 2, "recovered game worker", failure);
            awaitProgress(hudProgress, 1, "recovered HUD worker", failure);
            assertTrue("The recovered monitor must launch the game worker", gameWorker.get() != null);
            assertTrue("pause must accept an active recovered entry", control.pause());
            Thread.sleep(100);
            long pausedMain = mainProgress.get();
            long pausedWorker = workerProgress.get();
            long pausedHud = hudProgress.get();
            Thread.sleep(150);
            assertEquals("monitor progress must freeze", pausedMain, mainProgress.get());
            assertEquals("game worker progress must freeze", pausedWorker, workerProgress.get());
            assertEquals("HUD progress must freeze", pausedHud, hudProgress.get());
            if (!stopWhilePaused) {
                assertTrue("resume must accept a paused recovered entry", control.resume());
                awaitProgress(mainProgress, pausedMain + 2, "resumed monitor", failure);
                awaitProgress(workerProgress, pausedWorker + 2, "resumed game worker", failure);
                awaitProgress(hudProgress, pausedHud + 1, "resumed HUD worker", failure);
            }
            assertTrue("global stop must accept the recovered runtime", control.requestStop());
            runner.join(2_000);
            gameWorker.get().join(2_000);
            hudWorker.get().join(2_000);
            assertFalse("recovered monitor must terminate", runner.isAlive());
            assertFalse("recovered game worker must terminate", gameWorker.get().isAlive());
            assertFalse("recovered HUD worker must terminate", hudWorker.get().isAlive());
            assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
            assertTrue("recovered Lua must stop without a host failure: " + failure.get(), failure.get() == null);
        } finally {
            control.requestStop();
            runner.join(2_000);
            if (gameWorker.get() != null) gameWorker.get().join(2_000);
            if (hudWorker.get() != null) hudWorker.get().join(2_000);
        }
    }

    private static Thread recoveredRunner(String name, ScriptExecutionControl control,
                                          LuaInstructionGate gate, LuaFunction function,
                                          AtomicReference<Throwable> failure) {
        Thread runner = new Thread(() -> {
            try {
                control.attachCurrentThread();
                gate.invoke(function, LuaValue.NONE);
                if (!control.isStopRequested()) failure.compareAndSet(null,
                        new AssertionError(name + " returned before stop"));
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                // Global stop or a cancelled Lua worker.
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                control.fail(error);
            } finally {
                control.detachCurrentThread();
            }
        }, name);
        runner.setDaemon(true);
        return runner;
    }

    private static void awaitProgress(AtomicLong progress, long minimum, String activity,
                                      AtomicReference<Throwable> failure) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (progress.get() < minimum && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(activity + " did not progress; observed " + progress.get()
                + ", runtime failure: " + failure.get(), progress.get() >= minimum);
    }

    @Test public void recoveredEntryPointLoadsDependenciesAndReachesMainMonitor() throws Exception {
        Path assets = scriptAssets();
        Globals globals = JsePlatform.standardGlobals();
        AtomicBoolean monitorStarted = new AtomicBoolean();
        AtomicBoolean executeWorker = new AtomicBoolean();
        AtomicReference<org.luaj.vm2.LuaFunction> workerFunction = new AtomicReference<>();
        AtomicLong ticks = new AtomicLong();
        AtomicLong workerSleeps = new AtomicLong();
        List<String> loadedModules = new ArrayList<>();
        installHostStubs(globals, monitorStarted, executeWorker, workerFunction, ticks, workerSleeps);
        installRequire(globals, assets, loadedModules, monitorStarted);

        try {
            loadLua(globals, assets, "脚本/封神榜.lua");
            fail("The probe should stop only after entering the main monitor loop");
        } catch (LuaError expected) {
            assertTrue("The entry point should reach the monitor loop, got: " + expected.getMessage(),
                    expected.getMessage().contains(REACHED_MONITOR));
        }

        assertTrue("The entry point should start the recovered worker", workerFunction.get() != null);
        assertTrue("OCR finder bridge must be installed before running the worker", globals.get("tomocr").isfunction());
        assertTrue("Lua table.unpack must be available to recovered code", globals.get("table").get("unpack").isfunction());
        executeWorker.set(true);
        try {
            workerFunction.get().invoke();
            fail("The worker probe should stop only after the safe iteration budget");
        } catch (LuaError expected) {
            assertTrue("The worker should complete a safe multi-step probe without a host API error, got: "
                            + expected.getMessage(),
                    expected.getMessage().contains(REACHED_WORKER_SLEEP));
        }
        assertTrue("The worker should survive many screen-check branches before the probe stops",
                workerSleeps.get() >= WORKER_SLEEP_PROBE_LIMIT);

        assertTrue("Recovered helper module w.lua must load", loadedModules.contains("w"));
        assertTrue("Recovered control module must load", loadedModules.contains("中控"));
        assertTrue("Recovered data module must load", loadedModules.contains("通用数据"));
        assertTrue("Recovered game logic module must load", loadedModules.contains("挂机模式"));
        assertTrue("Main loop must start after all script configuration is initialized", monitorStarted.get());
        assertTrue("The legacy script form must keep its Vietnamese map name compatibility",
                "sùng thành".equals(globals.get("挂机点地图").tojstring()));
    }

    @Test public void ryluxWorkbenchKeepsChineseMapNamesForLocalizedGameSearch() throws Exception {
        Path assets = scriptAssets();
        Globals globals = JsePlatform.standardGlobals();
        AtomicBoolean monitorStarted = new AtomicBoolean();
        AtomicBoolean executeWorker = new AtomicBoolean();
        AtomicReference<org.luaj.vm2.LuaFunction> workerFunction = new AtomicReference<>();
        AtomicLong ticks = new AtomicLong();
        AtomicLong workerSleeps = new AtomicLong();
        List<String> loadedModules = new ArrayList<>();
        installHostStubs(globals, monitorStarted, executeWorker, workerFunction, ticks, workerSleeps);
        installRequire(globals, assets, loadedModules, monitorStarted);

        LuaTable config = new LuaTable();
        LegacyLuaConfig.defaults().forEach((key, value) -> config.set(key, LuaValue.valueOf(value)));
        globals.set("界面数据", config);
        LegacyLuaConfig.installWorkbenchConfigFlag(globals);

        try {
            loadLua(globals, assets, "脚本/封神榜.lua");
            fail("The probe should stop only after entering the main monitor loop");
        } catch (LuaError expected) {
            assertTrue("The localized workbench entry should reach the monitor loop, got: "
                            + expected.getMessage(),
                    expected.getMessage().contains(REACHED_MONITOR));
        }

        assertTrue("The RYLUX workbench must pass the Chinese localized map name to route logic",
                "崇城野外".equals(globals.get("挂机点地图").tojstring()));
        assertTrue("The RYLUX workbench must pass the Chinese localized map name to seller logic",
                "崇城野外".equals(globals.get("卖货地图").tojstring()));
    }

    private static void installHostStubs(Globals globals, AtomicBoolean monitorStarted,
                                         AtomicBoolean executeWorker,
                                         AtomicReference<org.luaj.vm2.LuaFunction> workerFunction,
                                         AtomicLong ticks, AtomicLong workerSleeps) {
        VarArgFunction noop = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                if (executeWorker.get()) {
                    if (workerSleeps.incrementAndGet() >= WORKER_SLEEP_PROBE_LIMIT) {
                        throw new LuaError(REACHED_WORKER_SLEEP);
                    }
                    return LuaValue.NONE;
                }
                if (monitorStarted.get()) throw new LuaError(REACHED_MONITOR);
                return LuaValue.NONE;
            }
        });
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(ticks.incrementAndGet()); }
        });
        globals.set("setControlBarPosNew", noop);
        globals.set("hideToast", noop);
        globals.set("showHUD", noop);
        globals.set("print", noop);
        globals.set("toast", noop);
        globals.set("httpGet", new OneArgFunction() {
            @Override public LuaValue call(LuaValue url) { return LuaValue.valueOf(""); }
        });
        globals.set("httpPost", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue url, LuaValue body) { return LuaValue.valueOf(""); }
        });
        globals.set("getPackageName", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("com.example.pakredirect"); }
        });
        globals.set("exec", new OneArgFunction() {
            @Override public LuaValue call(LuaValue command) { return LuaValue.valueOf("123.4 0.00"); }
        });
        globals.set("createHUD", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(1); }
        });
        globals.set("beginThread", new OneArgFunction() {
            @Override public LuaValue call(LuaValue function) { return LuaValue.valueOf(1); }
        });
        globals.set("splitStr", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue source, LuaValue separator) {
                return LegacyLuaCompat.splitStr(source, separator);
            }
        });
        globals.set("keepCapture", noop);
        globals.set("releaseCapture", noop);
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.valueOf(0); }
        });
        globals.set("findColor", noMatch());
        globals.set("findMultiColor", noCoordinateMatch());
        globals.set("findMultiColorAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.tableOf(); }
        });
        globals.set("findPic", noMatch());
        globals.set("findPicFast", noMatch());
        globals.set("cmpColorEx", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue patterns, LuaValue threshold) { return LuaValue.valueOf(0); }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.TRUE; }
        });
        globals.set("swipe", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.TRUE; }
        });
        globals.set("appIsRunning", new OneArgFunction() {
            @Override public LuaValue call(LuaValue packageName) { return LuaValue.TRUE; }
        });
        globals.set("frontAppName", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("com.tepaylink.tamgioiphantranhmobile"); }
        });
        globals.set("tomocr", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, new LuaTable()});
            }
        });
        globals.set("tomocrtextall", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.valueOf(""); }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.tableOf(); }
        });

        LuaTable thread = new LuaTable();
        thread.set("newThread", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                monitorStarted.set(true);
                workerFunction.set(args.arg1().checkfunction());
                return new LuaTable();
            }
        });
        globals.set("Thread", thread);

        LuaTable ui = new LuaTable();
        for (String method : Arrays.asList("newLayout", "addTabView", "addTab", "newRow", "addTextView",
                "addEditText", "addRadioGroup", "addCheckBox", "addButton", "setOnClick", "show",
                "setOnClose", "setText", "dismiss", "loadProfile", "saveProfile")) {
            ui.set(method, noop);
        }
        ui.set("getData", new ZeroArgFunction() {
            @Override public LuaValue call() { return defaultUiData(); }
        });
        globals.set("ui", ui);
    }

    private static void installRequire(Globals globals, Path assets, List<String> loadedModules,
                                       AtomicBoolean monitorStarted) {
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue moduleValue) {
                String module = moduleValue.checkjstring();
                if ("luacurl".equals(module)) return new LuaTable();
                if ("TURING_VIP".equals(module)) return new LuaTable();
                if ("tomocr".equals(module)) {
                    globals.set("tomocrtextall", new LuaTable());
                    globals.set("tomocrtext", new LuaTable());
                    globals.set("tomocrnumber", new LuaTable());
                    globals.set("tomocrtexttable", new LuaTable());
                    return LuaValue.TRUE;
                }

                String path = "脚本/" + module + (module.endsWith(".lua") ? "" : ".lua");
                try {
                    LuaValue result = loadLua(globals, assets, path);
                    loadedModules.add(module);
                    if ("挂机模式".equals(module)) {
                        // The next newThread call is the entry point's main monitor.
                        monitorStarted.set(false);
                    }
                    return result.isnil() ? LuaValue.TRUE : result;
                } catch (IOException error) {
                    throw new LuaError("Missing recovered Lua module " + path + ": " + error.getMessage());
                }
            }
        });
    }

    private static LuaTable defaultUiData() {
        LuaTable table = new LuaTable();
        LegacyLuaConfig.defaults().forEach((key, value) -> table.set(key, LuaValue.valueOf(value)));
        return table;
    }

    private static VarArgFunction noMatch() {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
            }
        };
    }

    private static VarArgFunction noCoordinateMatch() {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
            }
        };
    }

    private static LuaValue loadLua(Globals globals, Path assets, String relativePath) throws IOException {
        return compileLua(globals, assets, relativePath).invoke().arg1();
    }

    private static LuaFunction compileLua(Globals globals, Path assets, String relativePath) throws IOException {
        Path file = assets.resolve(relativePath);
        StringBuilder source = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file.toFile()),
                StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) source.append(buffer, 0, read);
        }
        return globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source.toString()), relativePath, globals)
                .checkfunction();
    }

    private static Path scriptAssets() throws IOException {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"));
        for (Path candidate : Arrays.asList(
                workingDirectory.resolve("src/main/assets/script_modules"),
                workingDirectory.resolve("app/src/main/assets/script_modules"))) {
            if (candidate.toFile().isDirectory()) return candidate;
        }
        throw new IOException("Could not locate app/src/main/assets/script_modules from " + workingDirectory);
    }
}
