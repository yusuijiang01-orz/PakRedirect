package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Directly executes one recovered Lua task without building or launching the APK. */
public final class RecoveredLuaTaskTest {
    @Test public void stuckDetectorUsesRealGameScreenshotsAndTripsAfterTenUnchangedFrames() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage baseline = readFixture("/game/blackbox-current.png");
        BufferedImage changed = readFixture("/game/blackbox-live.png");
        assertEquals(1600, baseline.getWidth());
        assertEquals(900, baseline.getHeight());
        assertEquals(baseline.getWidth(), changed.getWidth());
        assertEquals(baseline.getHeight(), changed.getHeight());
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                baseline.getWidth(), baseline.getHeight());
        assertNotEquals("The captured game frames must differ in the Lua detector's sample area",
                sampledChecksum(baseline, mapper), sampledChecksum(changed, mapper));
        AtomicLong clock = new AtomicLong();
        AtomicReference<BufferedImage> currentFrame = new AtomicReference<>(baseline);
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger pixelReads = new AtomicInteger();

        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("keepCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { captures.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { releases.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                pixelReads.incrementAndGet();
                int x = mapper.toScreenX(args.arg(1).checkint());
                int y = mapper.toScreenY(args.arg(2).checkint());
                BufferedImage frame = currentFrame.get();
                if (x < 0 || x >= frame.getWidth() || y < 0 || y >= frame.getHeight()) {
                    throw new AssertionError("Lua requested pixel outside captured screenshot: " + x + "," + y);
                }
                return LuaValue.valueOf(frame.getRGB(x, y) & 0x00ffffff);
            }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);

        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        LuaFunction detectStuck = globals.get("检测卡死").checkfunction();

        detectStuck.call(); // First sample becomes the baseline.
        assertEquals(0, globals.get("没有变化次数").toint());

        currentFrame.set(changed);
        clock.addAndGet(5_001L);
        detectStuck.call(); // A changed frame resets the unchanged counter.
        assertEquals(0, globals.get("没有变化次数").toint());

        boolean detected = false;
        for (int i = 0; i < 10; i++) {
            clock.addAndGet(5_001L);
            detected = detectStuck.call().toboolean();
            if (detected) break;
        }

        assertTrue("Ten identical real screenshot samples should report a stuck screen", detected);
        assertEquals("The detector must reset after reporting", 0, globals.get("没有变化次数").toint());
        assertEquals("Every acquired capture must be released", captures.get(), releases.get());
        assertEquals("The detector should sample the original 84 by 41 pixel grid",
                84 * 41 * captures.get(), pixelReads.get());
    }

    @Test public void stuckDetectorReleasesCaptureWhenPixelReadFails() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(10_000L); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("keepCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { captures.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { releases.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { throw new IllegalStateException("fixture read failure"); }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }

        try {
            globals.get("检测卡死").checkfunction().call();
            throw new AssertionError("pixel read failure should escape after cleanup");
        } catch (org.luaj.vm2.LuaError expected) {
            assertTrue(expected.getMessage().contains("fixture read failure"));
        }
        assertEquals("Capture must be released exactly once on the error path", 1, captures.get());
        assertEquals("Capture must be released exactly once on the error path", 1, releases.get());
    }

    @Test public void configuredNoMovementThresholdTripsFromAStableRealGameFrame() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage frame = readFixture("/game/blackbox-live.png");
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger pixelReads = new AtomicInteger();
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                frame.getWidth(), frame.getHeight());
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("keepCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { captures.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { releases.incrementAndGet(); return LuaValue.NONE; }
        });
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                pixelReads.incrementAndGet();
                int x = mapper.toScreenX(args.arg(1).checkint());
                int y = mapper.toScreenY(args.arg(2).checkint());
                if (x < 0 || x >= frame.getWidth() || y < 0 || y >= frame.getHeight()) {
                    throw new AssertionError("Lua requested pixel outside captured screenshot: " + x + "," + y);
                }
                return LuaValue.valueOf(frame.getRGB(x, y) & 0x00ffffff);
            }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        LuaTable config = new LuaTable();
        config.set("挂机不移动秒", LuaValue.valueOf("3"));
        globals.set("_ENV", globals);
        globals.set("界面数据", config);
        LuaFunction detector = globals.get("检测挂机长时间不移动").checkfunction();

        assertTrue("First captured frame initializes movement baseline", detector.call().isnil());
        for (int i = 0; i < 2; i++) {
            clock.addAndGet(1_001L);
            assertTrue("Configured threshold must not fire early", detector.call().isnil());
        }
        clock.addAndGet(1_001L);
        assertTrue("Three unchanged seconds should report no movement", detector.call().toboolean());
        assertEquals(0, globals.get("检测挂机长时间不移动_寻路没有变化次数").toint());
        assertEquals(captures.get(), releases.get());
        assertEquals(21 * captures.get(), pixelReads.get());
    }

    @Test public void recoveredAutoHuntPatternMatchesTheRealGameScreenshot() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage liveFrame = readFixture("/game/blackbox-live.png");
        BufferedImage currentFrame = readFixture("/game/blackbox-current.png");
        AtomicReference<BufferedImage> frame = new AtomicReference<>(liveFrame);
        AtomicReference<int[]> tappedPoint = new AtomicReference<>();
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                liveFrame.getWidth(), liveFrame.getHeight());
        AtomicLong ticks = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(ticks.incrementAndGet()); }
        });
        globals.set("识别异常用时", LuaValue.valueOf(10_000));
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("print", noOp);
        globals.set("toast", noOp);
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                tappedPoint.set(new int[]{mapper.toScreenX(args.arg(1).todouble()),
                        mapper.toScreenY(args.arg(2).todouble())});
                return LuaValue.TRUE;
            }
        });
        globals.set("cmpColorEx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String screenPoints = mapper.mapColorPoints(args.arg(1).checkjstring());
                boolean matches = LegacyLuaCompat.matchesColorPoints(screenPoints,
                        args.arg(2).optdouble(0.9d), frame.get().getWidth(), frame.get().getHeight(),
                        (x, y) -> frame.get().getRGB(x, y) & 0x00ffffff);
                return LuaValue.valueOf(matches ? 1 : 0);
            }
        });
        loadModule(globals, scriptRoot.resolve("w.lua"), "w.lua");
        loadModule(globals, scriptRoot.resolve("通用数据.lua"), "通用数据.lua");
        loadModule(globals, scriptRoot.resolve("挂机模式.lua"), "挂机模式.lua");

        LuaFunction finder = globals.get("找到").checkfunction();
        LuaTable data = globals.get("tz").checktable();
        globals.set("scxx", noOp);
        globals.set("运行记录_增加", noOp);
        globals.set("取msg主要信息", new OneArgFunction() {
            @Override public LuaValue call(LuaValue message) { return message; }
        });
        for (BufferedImage sample : Arrays.asList(liveFrame, currentFrame)) {
            frame.set(sample);
            Varargs offState = finder.invoke(data.get("自动挂机按钮_未开启"));
            Varargs onState = finder.invoke(data.get("自动挂机按钮_已开启"));
            assertTrue("Recovered off-state signature should match both real game screenshots",
                    offState.arg(1).toboolean());
            assertEquals("Lua finder returns the reference X", 757, offState.arg(2).toint());
            assertEquals("Lua finder returns the reference Y", 566, offState.arg(3).toint());
            assertTrue("The opposite auto-hunt state must not match the off-state screenshot",
                    !onState.arg(1).toboolean());
            assertTrue("Recovered main-page signatures should recognize the actual game screenshot",
                    globals.get("在主页面").checkfunction().call().toboolean());
        }
        LuaTable config = new LuaTable();
        config.set("自动战斗", LuaValue.valueOf("true"));
        globals.set("界面数据", config);
        globals.get("执行自动战斗").checkfunction().call();
        int[] tap = tappedPoint.get();
        assertTrue("Enabled auto-battle should click the detected off-state control", tap != null);
        assertTrue("Mapped X should target the button, allowing the legacy 2-pixel jitter",
                Math.abs(tap[0] - mapper.toScreenX(757)) <= mapper.toScreenDeltaX(2));
        assertTrue("Mapped Y should target the button, allowing the legacy 2-pixel jitter",
                Math.abs(tap[1] - mapper.toScreenY(566)) <= mapper.toScreenDeltaY(2));
        tappedPoint.set(null);
        config.set("自动战斗", LuaValue.valueOf("false"));
        globals.get("执行自动战斗").checkfunction().call();
        assertTrue("Disabling auto-battle must suppress clicks even when the off state is detected",
                tappedPoint.get() == null);
    }

    @Test public void recoveredRouteEntersSelectedMapAndSubmitsConfiguredCoordinates() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        List<String> enteredText = new ArrayList<>();
        List<String> tappedControls = new ArrayList<>();
        List<String> tapArguments = new ArrayList<>();
        AtomicBoolean pathfindingCompletes = new AtomicBoolean(true);
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(1_000L); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("通用处理", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("登录_判断一轮", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                return LuaValue.valueOf(target == globals.get("TEST_MAP_PAGE"));
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                tappedControls.add(args.arg(1).tojstring());
                StringBuilder call = new StringBuilder(args.arg(1).tojstring());
                for (int i = 2; i <= args.narg(); i++) call.append('|').append(args.arg(i).tojstring());
                tapArguments.add(call.toString());
                return LuaValue.TRUE;
            }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) { return LuaValue.TRUE; }
        });
        globals.set("寻路_等待完成", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(pathfindingCompletes.get()); }
        });
        LuaTable ime = new LuaTable();
        ime.set("lock", noOp);
        ime.set("unlock", noOp);
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                enteredText.add(value.tojstring());
                return LuaValue.TRUE;
            }
        });
        globals.set("imeLib", ime);
        LuaTable tz = new LuaTable();
        LuaTable mapPage = new LuaTable();
        globals.set("TEST_MAP_PAGE", mapPage);
        tz.set("大地图页面", mapPage);
        globals.set("tz", tz);

        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        // The recovered control modules define their own wrappers; replace them
        // only after loading so this test isolates route progression from OCR.
        globals.set("通用处理", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("登录_判断一轮", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                return LuaValue.valueOf(target == globals.get("TEST_MAP_PAGE"));
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                tappedControls.add(args.arg(1).tojstring());
                StringBuilder call = new StringBuilder(args.arg(1).tojstring());
                for (int i = 2; i <= args.narg(); i++) call.append('|').append(args.arg(i).tojstring());
                tapArguments.add(call.toString());
                return LuaValue.TRUE;
            }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) { return LuaValue.TRUE; }
        });
        globals.set("寻路_等待完成", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(pathfindingCompletes.get()); }
        });
        globals.set("imeLib", ime);
        globals.set("tz", tz);
        globals.set("挂机点地图", LuaValue.valueOf("sùng thành"));
        globals.set("挂机点x", LuaValue.valueOf("321"));
        globals.set("挂机点y", LuaValue.valueOf("654"));
        globals.set("需要去挂机点一次", LuaValue.TRUE);

        assertTrue("The route should report success only after pathfinding completes",
                globals.get("执行返回挂机点").checkfunction().call().toboolean());
        assertTrue("A successful route should clear the pending return flag",
                !globals.get("需要去挂机点一次").toboolean());

        assertEquals("The route must search the selected Vietnamese map, then enter X/Y.",
                Arrays.asList("sùng thành", "321", "654"), enteredText);
        assertTrue("The selected map should choose the correct map row",
                tappedControls.contains("@@选择地图2"));
        assertTrue("The route should submit the destination to the game map",
                tappedControls.contains("@@去目的地"));
        assertTrue("The Lua route should use the recovered map-submit control coordinates",
                tapArguments.contains("@@去目的地|857|651|1000"));
        assertTrue("The route should enable auto-hunt after path finding",
                tapArguments.contains("809|673|2000"));

        enteredText.clear();
        tappedControls.clear();
        tapArguments.clear();
        pathfindingCompletes.set(false);
        globals.set("需要去挂机点一次", LuaValue.TRUE);
        assertTrue("The route must report a pathfinding timeout as failure",
                !globals.get("执行返回挂机点").checkfunction().call().toboolean());
        assertTrue("A failed route must retain the pending return flag for retry",
                globals.get("需要去挂机点一次").toboolean());
        assertEquals("回挂机点失败，等待重试", globals.get("当前任务2").tojstring());
        assertTrue("A failed route must not re-enable auto-hunt at the destination",
                !tapArguments.contains("809|673|2000"));
    }

    @Test public void recoveredRouteStartsFromTheRealGameHomeScreenshot() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage screenshot = readFixture("/game/blackbox-live.png");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                screenshot.getWidth(), screenshot.getHeight());
        AtomicLong ticks = new AtomicLong();
        AtomicBoolean mapPageOpen = new AtomicBoolean();
        AtomicReference<int[]> mapOpenTap = new AtomicReference<>();
        List<String> enteredText = new ArrayList<>();
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(ticks.addAndGet(1_000)); }
        });
        globals.set("识别异常用时", LuaValue.valueOf(10_000));
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("splitStr", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue source, LuaValue separator) {
                return LegacyLuaCompat.splitStr(source, separator);
            }
        });
        globals.set("print", noOp);
        globals.set("toast", noOp);
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int x = mapper.toScreenX(args.arg(1).todouble());
                int y = mapper.toScreenY(args.arg(2).todouble());
                if (Math.abs(x - mapper.toScreenX(1159)) <= mapper.toScreenDeltaX(2)
                        && Math.abs(y - mapper.toScreenY(110)) <= mapper.toScreenDeltaY(2)) {
                    mapOpenTap.set(new int[]{x, y});
                    mapPageOpen.set(true);
                }
                return LuaValue.TRUE;
            }
        });
        globals.set("cmpColorEx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String points = mapper.mapColorPoints(args.arg(1).checkjstring());
                boolean matches = LegacyLuaCompat.matchesColorPoints(points,
                        args.arg(2).optdouble(0.9d), screenshot.getWidth(), screenshot.getHeight(),
                        (x, y) -> screenshot.getRGB(x, y) & 0x00ffffff);
                return LuaValue.valueOf(matches ? 1 : 0);
            }
        });
        globals.set("通用处理", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("登录_判断一轮", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        LuaTable ime = new LuaTable();
        ime.set("lock", noOp);
        ime.set("unlock", noOp);
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue text) {
                enteredText.add(text.tojstring());
                return LuaValue.TRUE;
            }
        });
        globals.set("imeLib", ime);

        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        globals.set("通用处理", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("登录_判断一轮", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) { return LuaValue.TRUE; }
        });
        LuaTable tz = globals.get("tz").checktable();
        LuaValue mapPage = tz.get("大地图页面");
        LuaFunction oldFind = globals.get("找到").checkfunction();
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (target == mapPage) return LuaValue.valueOf(mapPageOpen.get());
                return oldFind.invoke(target).arg1();
            }
        });
        LuaFunction oldMainPage = globals.get("在主页面").checkfunction();
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(!mapPageOpen.get() && oldMainPage.call().toboolean());
            }
        });
        globals.set("scxx", noOp);
        globals.set("运行记录_增加", noOp);
        globals.set("取msg主要信息", new OneArgFunction() {
            @Override public LuaValue call(LuaValue message) { return message; }
        });
        globals.set("寻路_等待完成", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        globals.set("挂机点地图", LuaValue.valueOf("sùng thành"));
        globals.set("挂机点x", LuaValue.valueOf("321"));
        globals.set("挂机点y", LuaValue.valueOf("654"));
        globals.set("需要去挂机点一次", LuaValue.TRUE);

        assertTrue("Lua should reach the configured destination from the real home screenshot",
                globals.get("执行返回挂机点").checkfunction().call().toboolean());
        assertTrue("The game home signature should lead Lua to tap the map-open control",
                mapOpenTap.get() != null);
        assertEquals("The route should enter the chosen map followed by its configured X/Y",
                Arrays.asList("sùng thành", "321", "654"), enteredText);
        assertTrue("The Lua route should submit the destination",
                !globals.get("需要去挂机点一次").toboolean());
    }

    @Test public void recoveredSupplyRoutineResetsBluePurchaseGuardToFalse() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("点击", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ignored) { return LuaValue.FALSE; }
        });
        globals.set("tz", new LuaTable());
        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        globals.set("界面数据", config);
        globals.set("买了一次蓝", LuaValue.TRUE);
        globals.set("买了一次红", LuaValue.TRUE);

        globals.get("去卖货_卖货").checkfunction().call();

        assertEquals("false", globals.get("买了一次蓝").tojstring());
        assertEquals("false", globals.get("买了一次红").tojstring());
    }

    @Test public void recoveredEquipmentQualityHonorsWhiteAndGreenSaleCeilings() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<String> visibleQuality = new AtomicReference<>("白色");
        AtomicLong clock = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        globals.set("toast", globals.get("print"));
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        globals.set("findColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String signature = args.arg(5).tojstring();
                boolean match = ("黄色".equals(visibleQuality.get()) && signature.contains("ffff00"))
                        || ("绿色".equals(visibleQuality.get()) && signature.contains("00ff00"))
                        || ("橙色".equals(visibleQuality.get()) && signature.contains("efa23a"));
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(match ? 1 : -1),
                        LuaValue.valueOf(match ? 100 : -1), LuaValue.valueOf(match ? 100 : -1)});
            }
        });
        LuaFunction identify = globals.get("去卖货_卖货_识别装备品质").checkfunction();
        assertEquals("白色", identify.call().tojstring());
        visibleQuality.set("绿色");
        assertEquals("绿色", identify.call().tojstring());
        visibleQuality.set("黄色");
        assertEquals("黄色", identify.call().tojstring());
        visibleQuality.set("橙色");
        assertEquals("橙色", identify.call().tojstring());

        LuaFunction salePolicy = globals.get("去卖货_卖货_品质是否允许出售").checkfunction();
        assertTrue("Quality ceiling 0 must prohibit white equipment sales",
                !salePolicy.call(LuaValue.valueOf("白色"), LuaValue.valueOf(0)).toboolean());
        assertTrue("Quality ceiling 1 may sell white equipment",
                salePolicy.call(LuaValue.valueOf("白色"), LuaValue.valueOf(1)).toboolean());
        assertTrue("Quality ceiling 1 must preserve green equipment",
                !salePolicy.call(LuaValue.valueOf("绿色"), LuaValue.valueOf(1)).toboolean());
        assertTrue("Quality ceiling 2 may sell green equipment",
                salePolicy.call(LuaValue.valueOf("绿色"), LuaValue.valueOf(2)).toboolean());
        assertTrue("Yellow and orange equipment remain protected at every ceiling",
                !salePolicy.call(LuaValue.valueOf("黄色"), LuaValue.valueOf(2)).toboolean()
                        && !salePolicy.call(LuaValue.valueOf("橙色"), LuaValue.valueOf(2)).toboolean());
    }

    @Test public void recoveredEquipmentSaleConfirmationWaitsForUserAndCancelsOnTimeout() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong();
        AtomicInteger confirmationChecks = new AtomicInteger();
        AtomicReference<Integer> visibleChecksBeforeDismissal = new AtomicReference<>(2);
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("print", noOp);
        globals.set("toast", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        LuaTable confirmationPage = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店卖货确定页面", confirmationPage);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) {
                if (!template.raweq(confirmationPage)) return LuaValue.FALSE;
                int check = confirmationChecks.incrementAndGet();
                Integer dismissAfter = visibleChecksBeforeDismissal.get();
                return LuaValue.valueOf(dismissAfter == null || check <= dismissAfter);
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        LuaTable config = new LuaTable();
        config.set("出售装备二次确认", LuaValue.valueOf("true"));
        globals.set("界面数据", config);
        LuaFunction handler = globals.get("去卖货_处理装备出售确认").checkfunction();

        assertTrue("Dismissing the in-game prompt should allow the workflow to continue",
                handler.call().toboolean());
        assertTrue("The script must never press the game's destructive confirm button",
                !clicks.contains("@@确定卖掉"));
        assertEquals(0, clicks.size());

        confirmationChecks.set(0);
        visibleChecksBeforeDismissal.set(null);
        assertTrue("An unresolved game prompt should stop the workflow after timeout",
                !handler.call().toboolean());
        // The mock still reports the prompt as visible when dismissAfter is null.
        assertTrue("Timeout must explicitly cancel the game prompt", clicks.contains("@@关闭商店卖货确定页面"));

        confirmationChecks.set(0);
        clicks.clear();
        visibleChecksBeforeDismissal.set(10);
        config.set("出售装备二次确认", LuaValue.valueOf("false"));
        assertTrue("With the extra confirmation disabled, the legacy flow may confirm automatically",
                handler.call().toboolean());
        assertEquals(Arrays.asList("@@确定卖掉"), clicks);
    }

    @Test public void recoveredSellerNeverScansEquipmentWhenQualityCeilingIsNoSale() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger equipmentScans = new AtomicInteger();
        AtomicReference<String> clicked = new AtomicReference<>("");
        AtomicLong clock = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        globals.set("toast", globals.get("print"));
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicked.set(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicked.set(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        LuaTable shopPage = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) {
                return LuaValue.valueOf(template.raweq(shopPage));
            }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                equipmentScans.incrementAndGet();
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
            }
        });
        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("true"));
        config.set("保护装备不出售", LuaValue.valueOf("false"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        globals.set("界面数据", config);
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);

        globals.get("去卖货_卖货").checkfunction().call();

        assertEquals("Equipment sale quality ceiling 0 must bypass inventory scanning", 0, equipmentScans.get());
        assertTrue("No equipment selection or sale action may run under the no-sale ceiling",
                !clicked.get().contains("点装备") && !clicked.get().contains("卖1"));
    }

    @Test public void recoveredInventoryDecisionUsesConfiguredRedAndBlueThresholds() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        LuaTable counts = new LuaTable();
        counts.set("大红", LuaValue.valueOf("1"));
        counts.set("小红", LuaValue.valueOf("1"));
        counts.set("大蓝", LuaValue.valueOf("1"));
        counts.set("小蓝", LuaValue.valueOf("2"));
        globals.set("检测背包_识别红蓝数量", new ZeroArgFunction() {
            @Override public LuaValue call() { return counts; }
        });
        LuaTable config = new LuaTable();
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("true"));
        config.set("红的数量高于", LuaValue.valueOf("10"));
        config.set("蓝的数量高于", LuaValue.valueOf("10"));
        config.set("红的数量低于", LuaValue.valueOf("10"));
        config.set("蓝的数量低于", LuaValue.valueOf("5"));
        globals.set("界面数据", config);
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);

        Varargs purchaseBlue = globals.get("检测背包_判断是否去卖或者买药").checkfunction().invoke();
        assertEquals("买蓝", purchaseBlue.arg(1).tojstring());
        assertEquals(3, purchaseBlue.arg(2).toint());

        globals.set("买了一次蓝", LuaValue.TRUE);
        assertEquals("", globals.get("检测背包_判断是否去卖或者买药").checkfunction().call().tojstring());

        config.set("出售全部红", LuaValue.valueOf("true"));
        config.set("红的数量高于", LuaValue.valueOf("1"));
        globals.set("小红数量", LuaValue.valueOf(1));
        globals.set("大红数量", LuaValue.valueOf(1));
        globals.set("小蓝数量", LuaValue.valueOf(1));
        globals.set("大蓝数量", LuaValue.valueOf(1));
        Varargs sellRed = globals.get("检测背包_判断是否去卖或者买药").checkfunction().invoke();
        assertEquals("卖红", sellRed.arg(1).tojstring());
        assertEquals(2, sellRed.arg(2).toint());
    }

    @Test public void recoveredShopExecutesBluePurchaseWithConfiguredQuantity() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong();
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);

        LuaTable shop = new LuaTable();
        LuaTable buyQuantity = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shop);
        templates.set("商店买货确定页面", buyQuantity);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) {
                return LuaValue.valueOf(template.raweq(shop) || template.raweq(buyQuantity));
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("true"));
        config.set("蓝的数量低于", LuaValue.valueOf("5"));
        config.set("购买蓝的数量", LuaValue.valueOf("3"));
        config.set("红的数量低于", LuaValue.valueOf("5"));
        config.set("购买红的数量", LuaValue.valueOf("3"));
        config.set("红的数量高于", LuaValue.valueOf("20"));
        config.set("蓝的数量高于", LuaValue.valueOf("20"));
        globals.set("界面数据", config);
        globals.set("小红数量", LuaValue.valueOf(1));
        globals.set("大红数量", LuaValue.valueOf(0));
        globals.set("小蓝数量", LuaValue.valueOf(1));
        globals.set("大蓝数量", LuaValue.valueOf(0));
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);

        globals.get("去卖货_卖货").checkfunction().call();

        assertTrue("Short blue stock should select blue supplies", clicks.contains("@@点蓝"));
        assertTrue("Blue purchase should open quantity confirmation", clicks.contains("@@购买"));
        assertEquals("Configured quantity 3 must add two units after the first", 2,
                java.util.Collections.frequency(clicks, "@@购买+1"));
        assertTrue("The recovered purchase flow must submit the selected quantity",
                clicks.contains("@@确定购买数量"));
        assertTrue("A blue purchase must never click the red supply tab", !clicks.contains("@@点红"));
    }

    @Test public void recoveredInventorySkipsTransactionsWhenQuickslotOcrIsInvalid() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong();
        AtomicReference<String[]> recognizedCounts = new AtomicReference<>(
                new String[]{"", "1", "2", "3"});
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        globals.set("检测背包_主页面识别红蓝数量_识别子函数", new OneArgFunction() {
            private int index;
            @Override public LuaValue call(LuaValue left) {
                String[] values = recognizedCounts.get();
                int slot = index++ % values.length;
                return LuaValue.valueOf(values[slot]);
            }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        globals.set("检测修理装备", LuaValue.FALSE);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) { return LuaValue.FALSE; }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        LuaTable config = new LuaTable();
        config.set("购买小红", LuaValue.valueOf("true"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("红的数量低于", LuaValue.valueOf("5"));
        config.set("蓝的数量低于", LuaValue.valueOf("5"));
        config.set("红的数量高于", LuaValue.valueOf("20"));
        config.set("蓝的数量高于", LuaValue.valueOf("20"));
        config.set("背包检测间隔秒", LuaValue.valueOf("60"));
        globals.set("界面数据", config);
        globals.set("背包检测间隔秒", LuaValue.valueOf(60));

        assertTrue("Unreadable slot OCR must not force the player to a shop",
                globals.get("检测背包").checkfunction().call().toboolean());
        assertTrue("An empty OCR result must be marked invalid rather than as zero stock",
                !globals.get("药品数量识别有效").toboolean());
        assertTrue("Invalid OCR must skip the inventory and purchase flow",
                !clicks.contains("@@打开背包"));

        recognizedCounts.set(new String[]{"0", "1", "2", "3"});
        assertTrue("Literal zero is valid OCR and may proceed to the normal inventory scan",
                globals.get("检测背包_主页面识别红蓝数量").checkfunction().call().toboolean());
        assertTrue("Literal zero stock must remain distinguishable from an unreadable slot",
                globals.get("药品数量识别有效").toboolean());
    }

    @Test public void recoveredInventoryCountsEmptySlotsOnTheRealBackpackScreenshot() throws Exception {
        Path scriptRoot = scriptRoot();
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage screen = readFixture("/game/inventory-backpack.png");
        BufferedImage sourceTemplate = ImageIO.read(scriptRoot.resolve("空格子0.png").toFile());
        assertEquals(1600, screen.getWidth());
        assertEquals(900, screen.getHeight());
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                screen.getWidth(), screen.getHeight());
        BufferedImage template = scaleTemplate(sourceTemplate, mapper.toScreenDeltaX(sourceTemplate.getWidth()),
                mapper.toScreenDeltaY(sourceTemplate.getHeight()));
        AtomicLong clock = new AtomicLong();
        AtomicInteger slotSearches = new AtomicInteger();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.incrementAndGet()); }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("splitStr", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue source, LuaValue separator) {
                return LegacyLuaCompat.splitStr(source, separator);
            }
        });
        globals.set("print", noOp);
        globals.set("toast", noOp);
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                slotSearches.incrementAndGet();
                int left = Math.max(0, mapper.left(args.arg(1).todouble()));
                int top = Math.max(0, mapper.top(args.arg(2).todouble()));
                int right = mapper.edgeRight(args.arg(3).todouble());
                int bottom = mapper.edgeBottom(args.arg(4).todouble());
                boolean match = imageMatches(screen, template, left, top, right, bottom,
                        args.arg(6).tojstring(), args.arg(8).optdouble(0.95));
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(match),
                        LuaValue.valueOf(match ? left : -1), LuaValue.valueOf(match ? top : -1)});
            }
        });
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        globals.set("_ENV", globals);

        int emptySlots = globals.get("检测背包_识别空格子").checkfunction().call().toint();

        assertEquals("The seven-row bag grid has five columns and four occupied cells", 31, emptySlots);
        assertEquals("The Lua scan must check each of the 35 visible bag slots exactly once", 35,
                slotSearches.get());

        globals.set("检测修理装备", LuaValue.FALSE);
        globals.set("背包检测间隔秒", LuaValue.valueOf(60));
        globals.set("背包保留重量", LuaValue.valueOf(3));
        globals.set("背包保留格子", LuaValue.valueOf(2));
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        LuaValue bagPage = globals.get("tz").get("背包页面");
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue candidate) {
                return LuaValue.valueOf(candidate.raweq(bagPage));
            }
        });
        globals.set("tomocrtextall", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                // The real screenshot visibly shows 2/200 in the backpack weight field.
                return LuaValue.valueOf("2/200");
            }
        });
        globals.set("修改数据库", noOp);
        LuaTable config = new LuaTable();
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("背包检测间隔秒", LuaValue.valueOf("60"));
        globals.set("界面数据", config);

        assertTrue("The real backpack screenshot has enough weight and empty slots to continue",
                globals.get("检测背包").checkfunction().call().toboolean());
        assertEquals("31", globals.get("当前背包格子").tojstring());
        assertEquals("2/200", globals.get("当前背包重量").tojstring());
    }

    private static BufferedImage scaleTemplate(BufferedImage source, int width, int height) {
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return scaled;
    }

    private static boolean imageMatches(BufferedImage screen, BufferedImage template,
                                        int left, int top, int right, int bottom,
                                        String maskHex, double threshold) {
        int tw = template.getWidth(), th = template.getHeight();
        if (right - left < tw || bottom - top < th) return false;
        int mask = Integer.parseInt(maskHex, 16) & 0x00ffffff;
        int sampleStep = Math.max(1, Math.min(3, Math.max(tw, th) / 18));
        int valid = 0;
        for (int y = 0; y < th; y += sampleStep) for (int x = 0; x < tw; x += sampleStep) {
            if ((template.getRGB(x, y) & 0x00ffffff) != mask) valid++;
        }
        int maxX = right - tw, maxY = bottom - th;
        for (int y = top; y <= maxY; y++) for (int x = left; x <= maxX; x++) {
            int hits = 0;
            for (int ty = 0; ty < th; ty += sampleStep) for (int tx = 0; tx < tw; tx += sampleStep) {
                int expected = template.getRGB(tx, ty) & 0x00ffffff;
                if (expected == mask) continue;
                int actual = screen.getRGB(x + tx, y + ty) & 0x00ffffff;
                int delta = Math.abs(((expected >> 16) & 255) - ((actual >> 16) & 255))
                        + Math.abs(((expected >> 8) & 255) - ((actual >> 8) & 255))
                        + Math.abs((expected & 255) - (actual & 255));
                if (delta <= 42) hits++;
            }
            if (valid > 0 && (double) hits / valid >= threshold) return true;
        }
        return false;
    }

    private static Path scriptRoot() throws IOException {
        Path workingDirectory = Paths.get(System.getProperty("user.dir"));
        for (Path candidate : Arrays.asList(
                workingDirectory.resolve("src/main/assets/script_modules/脚本"),
                workingDirectory.resolve("app/src/main/assets/script_modules/脚本"))) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IOException("Could not locate recovered Lua scripts from " + workingDirectory);
    }

    private static BufferedImage readFixture(String resourceName) throws IOException {
        try (InputStream input = RecoveredLuaTaskTest.class.getResourceAsStream(resourceName)) {
            if (input == null) throw new IOException("Missing screenshot fixture " + resourceName);
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IOException("Unsupported screenshot fixture " + resourceName);
            return image;
        }
    }

    private static long sampledChecksum(BufferedImage image, LegacyCoordinateMapper mapper) {
        long checksum = 0;
        for (int x = 387; x <= 1218; x += 10) {
            for (int y = 60; y <= 465; y += 10) {
                checksum += image.getRGB(mapper.toScreenX(x), mapper.toScreenY(y)) & 0x00ffffff;
            }
        }
        return checksum;
    }

    private static void loadModule(Globals globals, Path path, String name) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), name, globals).call();
    }
}
