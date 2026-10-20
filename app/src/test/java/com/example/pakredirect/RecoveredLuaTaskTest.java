package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.concurrent.TimeUnit;

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
    @Test public void recoveredLuaClickOpensAndClosesTheLiveSandboxBackpackOverAdb() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        assumeTrue("Set RYLUX_ADB_EXECUTABLE and RYLUX_ADB_DEVICE for an opt-in emulator touch check",
                adb != null && serial != null);

        String windows = runDeviceCommand(adb, serial, "shell", "dumpsys", "window");
        assertTrue("The active app must be the BlackBox game proxy",
                windows.contains("mFocusedApp=") && windows.contains("com.example.pakredirect")
                        && windows.contains("ProxyActivity"));
        assertTrue("The focused guest window must be the localized game",
                windows.contains("mCurrentFocus=")
                        && windows.contains("com.tepaylink.tamgioiphantranhmobile"));

        BufferedImage before = readDeviceScreenshot(adb, serial);
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                before.getWidth(), before.getHeight());
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        AtomicLong ticks = new AtomicLong();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(ticks.addAndGet(1_000)); }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int x = mapper.toScreenX(args.arg(1).toint());
                int y = mapper.toScreenY(args.arg(2).toint());
                try { runDeviceCommand(adb, serial, "shell", "input", "tap", String.valueOf(x), String.valueOf(y)); }
                catch (Exception error) { throw new org.luaj.vm2.LuaError("ADB touch failed: " + error.getMessage()); }
                return LuaValue.TRUE;
            }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("print", noOpFunction());
        globals.set("toast", noOpFunction());
        globals.set("hideToast", noOpFunction());
        loadModule(globals, scriptRoot().resolve("w.lua"), "w.lua");
        LuaFunction click = globals.get("点击").checkfunction();

        boolean opened = false;
        try {
            click.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(902),
                    LuaValue.valueOf(655), LuaValue.valueOf(1000)}));
            Thread.sleep(900);
            opened = true;
            BufferedImage backpack = readDeviceScreenshot(adb, serial);
            long openedDifference = sampledDifference(before, backpack);
            assertTrue("The recovered Lua click must open the inventory panel on the live game",
                    openedDifference > 500);

            click.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1244),
                    LuaValue.valueOf(109), LuaValue.valueOf(1000)}));
            Thread.sleep(900);
            opened = false;
            BufferedImage after = readDeviceScreenshot(adb, serial);
            long closedDifference = sampledDifference(before, after);
            assertTrue("The recovered Lua click must close the inventory and restore gameplay",
                    closedDifference < openedDifference / 4);
        } finally {
            if (opened) {
                click.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1244),
                        LuaValue.valueOf(109), LuaValue.valueOf(1000)}));
            }
        }
    }

    @Test public void recoveredLuaParsesQuantityFromLiveSandboxBackpackOcr() throws Exception {
        String screenshotPath = System.getenv("RYLUX_LIVE_DEVICE_SCREENSHOT");
        String ocrPath = System.getenv("RYLUX_LIVE_DEVICE_OCR_CSV");
        assumeTrue("Set RYLUX_LIVE_DEVICE_SCREENSHOT and RYLUX_LIVE_DEVICE_OCR_CSV for an opt-in emulator check",
                screenshotPath != null && ocrPath != null);

        BufferedImage screenshot = ImageIO.read(Paths.get(screenshotPath).toFile());
        assertTrue("The screenshot must come from the live 1600x900 sandbox display",
                screenshot != null && screenshot.getWidth() == 1600 && screenshot.getHeight() == 900);
        List<String[]> ocrRows = new ArrayList<>();
        List<String> csv = Files.readAllLines(Paths.get(ocrPath), StandardCharsets.UTF_8);
        assertEquals("text,raw_center_x,raw_center_y,confidence", csv.get(0));
        for (int i = 1; i < csv.size(); i++) ocrRows.add(csv.get(i).split(",", 4));

        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                screenshot.getWidth(), screenshot.getHeight());
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                double left = args.arg(1).todouble(), top = args.arg(2).todouble();
                double right = args.arg(3).todouble(), bottom = args.arg(4).todouble();
                LuaTable rows = new LuaTable();
                int index = 1;
                for (String[] values : ocrRows) {
                    double x = mapper.toReferenceX(Double.parseDouble(values[1]));
                    double y = mapper.toReferenceY(Double.parseDouble(values[2]));
                    if (x < left || x > right || y < top || y > bottom) continue;
                    LuaTable row = new LuaTable();
                    row.set("words", LuaValue.valueOf(values[0]));
                    row.set("x", LuaValue.valueOf(x));
                    row.set("y", LuaValue.valueOf(y));
                    row.set("score", LuaValue.valueOf(Double.parseDouble(values[3])));
                    rows.set(index++, row);
                }
                return rows;
            }
        });
        globals.set("print", noOpFunction());
        globals.set("toast", noOpFunction());
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        // RapidOCR observed the blue-potion stack's literal count at screen pixel (1214, 250).
        // The legacy 1280x720 Lua reference maps that OCR box to approximately (971, 200).
        LuaValue quantity = globals.get("去卖货_识别物品数量").checkfunction().invoke(
                LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(950), LuaValue.valueOf(180),
                        LuaValue.valueOf(995), LuaValue.valueOf(220)})).arg1();
        assertEquals("Recovered Lua should accept the high-confidence count from the live sandbox screen",
                100, quantity.toint());
    }

    @Test public void recoveredInventoryImageTemplateIsPresentForTheLuaFindPicCalls() throws Exception {
        Path imageTemplate = scriptRoot().resolve("空格子0.png");
        String inventoryScript = Files.readString(scriptRoot().resolve("挂机模式.lua"), StandardCharsets.UTF_8);
        assertTrue("The recovered inventory routines reference the empty-slot image template",
                inventoryScript.contains("空格子0.png"));
        assertTrue("The required image template must ship beside the recovered Lua modules",
                Files.isRegularFile(imageTemplate));
    }

    @Test public void workbenchStartupConsumesSuppliedConfigWithoutWaitingForHiddenLegacyUi() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        LegacyLuaConfig.installWorkbenchConfigFlag(globals);
        AtomicInteger legacyUiLayouts = new AtomicInteger();
        AtomicInteger monitorRuns = new AtomicInteger();
        AtomicLong sleptMillis = new AtomicLong();
        globals.set("print", noOpFunction());
        globals.set("toast", noOpFunction());
        globals.set("hideToast", noOpFunction());
        globals.set("setControlBarPosNew", noOpFunction());
        globals.set("beginThread", new OneArgFunction() {
            @Override public LuaValue call(LuaValue function) { return LuaValue.NONE; }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                sleptMillis.addAndGet(millis.optlong(0));
                return LuaValue.NONE;
            }
        });
        globals.set("getPackageName", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("com.example.rylux"); }
        });
        globals.set("httpGet", new OneArgFunction() {
            @Override public LuaValue call(LuaValue url) { return LuaValue.valueOf(""); }
        });
        globals.set("取开机秒数", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(1); }
        });
        globals.set("splitStr", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue source, LuaValue separator) {
                return LegacyLuaCompat.splitStr(source, separator);
            }
        });

        LuaTable suppliedConfig = new LuaTable();
        for (String[] entry : new String[][]{
                {"手机编号", "test"}, {"队长名", ""}, {"队员名", ""}, {"是否队长", "2"},
                {"开启vpn", "false"}, {"检测修理装备", "true"}, {"背包保留重量", "3"},
                {"背包保留格子", "2"}, {"背包检测间隔秒", "60"}, {"挂机点x", "120"},
                {"挂机点y", "220"}, {"卖货地图", "0"}, {"挂机点地图", "0"},
                {"保留蓝条数量", "4"}
        }) suppliedConfig.set(entry[0], LuaValue.valueOf(entry[1]));

        LuaTable ui = new LuaTable();
        for (String method : new String[]{"newLayout", "addTabView", "addTab", "newRow", "addTextView",
                "addEditText", "addRadioGroup", "addCheckBox", "addButton", "setOnClick", "show",
                "setOnClose", "setText", "dismiss", "loadProfile", "saveProfile"}) {
            ui.set(method, new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    if ("newLayout".equals(method)) legacyUiLayouts.incrementAndGet();
                    return LuaValue.NONE;
                }
            });
        }
        ui.set("getData", new ZeroArgFunction() {
            @Override public LuaValue call() { return suppliedConfig; }
        });
        globals.set("ui", ui);

        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue module) {
                String name = module.tojstring();
                if ("TURING_VIP".equals(name)) return new LuaTable();
                if ("w".equals(name)) return new LuaTable();
                if ("中控".equals(name)) {
                    globals.set("监控命令", new ZeroArgFunction() {
                        @Override public LuaValue call() {
                            monitorRuns.incrementAndGet();
                            return LuaValue.NONE;
                        }
                    });
                }
                return LuaValue.TRUE;
            }
        });

        String source = Files.readString(scriptRoot().resolve("封神榜.lua"), StandardCharsets.UTF_8);
        globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), "封神榜.lua", globals).call();

        assertEquals("The headless workbench must not try to create the hidden legacy form", 0,
                legacyUiLayouts.get());
        assertEquals("Startup must reach the script monitor after loading the supplied config", 1,
                monitorRuns.get());
        assertEquals("The workbench's configured hunt coordinate must reach the Lua main module", "120",
                globals.get("界面数据").get("挂机点x").tojstring());
        assertEquals("The localized workbench must pass the Chinese map name to the game search field",
                "崇城野外", globals.get("挂机点地图").tojstring());
        assertEquals("The localized workbench must use Chinese for the merchant route too",
                "崇城野外", globals.get("卖货地图").tojstring());
        assertTrue("Startup must not incur the legacy 60-second form timeout", sleptMillis.get() < 60_000);
    }

    @Test public void recoveredTuringOcrPassesRegionThresholdFilterAndDictionaryToTheRecognizer() {
        AtomicReference<LegacyTuringCompat.Request> observed = new AtomicReference<>();
        LuaTable turing = LegacyTuringCompat.createApi(request -> {
            observed.set(request);
            return "  12\n";
        });
        Globals globals = JsePlatform.standardGlobals();
        globals.set("TURING", turing);
        String recoveredCallSequence =
                "TURING['来源_获取屏幕像素'](940, 299, 995, 321) " +
                "TURING['滤镜_彩色_二值化']('FF6563-202020|DE5552-202020') " +
                "TURING['切割_范围投影切割'](1, 1) " +
                "local count = TURING['字库_加载六十四进制识别字库']({'1|4,15|abc', '2|4,15|def'}) " +
                "local text = TURING['识别'](85) return count, text";
        Varargs luaResult = globals.load(recoveredCallSequence, "turing-compat.lua", globals).invoke();

        assertEquals(2, luaResult.arg(1).toint());
        assertEquals("12", luaResult.arg(2).tojstring());
        assertEquals(940, observed.get().left);
        assertEquals(299, observed.get().top);
        assertEquals(995, observed.get().right);
        assertEquals(321, observed.get().bottom);
        assertEquals(85, observed.get().confidenceThreshold);
        assertEquals("FF6563-202020|DE5552-202020", observed.get().colorFilter);
        assertEquals(1, observed.get().projectionMode);
        assertEquals(1, observed.get().projectionThreshold);
        assertEquals(2, observed.get().dictionaryEntries.size());
    }

    @Test public void recoveredTuringColorFilterProducesTheConfiguredBinaryMask() {
        int[] pixels = {0xffff6563, 0xffff8563, 0xffff8663, 0xff202020};
        int[] normal = LegacyTuringCompat.applyColorPickFilter(pixels, "FF6563-202020");
        assertEquals(0xffffffff, normal[0]);
        assertEquals("Tolerance includes the boundary", 0xffffffff, normal[1]);
        assertEquals("A one-channel difference beyond tolerance is rejected", 0xff000000, normal[2]);
        assertEquals(0xff000000, normal[3]);

        int[] inverted = LegacyTuringCompat.applyColorPickFilter(pixels, "@FF6563-202020");
        assertEquals(0xff000000, inverted[0]);
        assertEquals(0xffffffff, inverted[2]);
    }

    @Test public void recoveredTuringConfidenceThresholdRejectsWeakAndUnknownOcrLines() {
        assertTrue(LegacyTuringCompat.acceptsConfidence(0.85d, 85));
        assertTrue(!LegacyTuringCompat.acceptsConfidence(0.849d, 85));
        assertTrue(!LegacyTuringCompat.acceptsConfidence(-1d, 85));
        assertTrue(LegacyTuringCompat.acceptsConfidence(0.01d, -10));
        assertTrue(!LegacyTuringCompat.acceptsConfidence(0.99d, 101));
    }

    @Test public void recoveredTuringNumericFilterUsesGrayscaleBoundsInsteadOfColorMatching() {
        int[] pixels = {0xff101010, 0xff555555, 0xfff0f0f0};
        int[] threshold = LegacyTuringCompat.applyFilter(pixels, "85");
        assertEquals(0xff000000, threshold[0]);
        assertEquals(0xff000000, threshold[1]);
        assertEquals(0xffffffff, threshold[2]);
        int[] range = LegacyTuringCompat.applyFilter(pixels, "60-100");
        assertEquals(0xffffffff, range[0]);
        assertEquals(0xff000000, range[1]);
        assertEquals(0xffffffff, range[2]);
    }

    @Test public void recoveredPartyLeaderCheckNeverDisbandsOnMissingOrUnreadableNames() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        Path scriptRoot = scriptRoot();
        String compatibilitySource = Files.readString(scriptRoot.resolve("w.lua"), StandardCharsets.UTF_8);
        globals.set("w", globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(compatibilitySource),
                "w.lua", globals).call());
        for (String module : Arrays.asList("中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }

        LuaFunction judge = globals.get("组队_判断队长名称").checkfunction();
        assertEquals("未配置", judge.call(LuaValue.valueOf("player"), LuaValue.valueOf("player"),
                LuaValue.valueOf("  ")).tojstring());
        assertEquals("无法识别", judge.call(LuaValue.valueOf(""), LuaValue.valueOf("  "),
                LuaValue.valueOf("leader")).tojstring());
        assertEquals("匹配", judge.call(LuaValue.valueOf("leader"), LuaValue.valueOf(""),
                LuaValue.valueOf("leader")).tojstring());
        assertEquals("不匹配", judge.call(LuaValue.valueOf("wrong"), LuaValue.valueOf("other"),
                LuaValue.valueOf("leader")).tojstring());

        LuaTable expectedLeaders = new LuaTable();
        expectedLeaders.set(1, LuaValue.valueOf("first-leader"));
        expectedLeaders.set(2, LuaValue.valueOf("second-leader"));
        assertEquals("A configured alternate leader must be accepted, not only the first line",
                "匹配", judge.call(LuaValue.valueOf("second-leader"), LuaValue.valueOf(""),
                        expectedLeaders).tojstring());
        assertEquals("A newline-delimited legacy value must preserve all configured leaders",
                "匹配", judge.call(LuaValue.valueOf("second-leader"), LuaValue.valueOf(""),
                        LuaValue.valueOf("first-leader\nsecond-leader")).tojstring());

        LuaFunction inspectionDue = globals.get("组队_检查间隔已到").checkfunction();
        assertTrue("A missing baseline must not run the leader-name check on every loop",
                !inspectionDue.call(LuaValue.NIL, LuaValue.valueOf(1000), LuaValue.valueOf("180")).toboolean());
        assertTrue("The configured 180-second period must not fire early",
                !inspectionDue.call(LuaValue.valueOf(1000), LuaValue.valueOf(181000), LuaValue.valueOf("180")).toboolean());
        assertTrue("The leader-name check should fire once the full period has elapsed",
                inspectionDue.call(LuaValue.valueOf(1000), LuaValue.valueOf(181001), LuaValue.valueOf("180")).toboolean());
        assertTrue("The next check must wait after its timestamp is reset",
                !inspectionDue.call(LuaValue.valueOf(181001), LuaValue.valueOf(181501), LuaValue.valueOf("180")).toboolean());
    }

    @Test public void localizedPartyPanelIsRecognizedByOcrAndClosedByRecoveryHandler() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<String> title = new AtomicReference<>("队伍");
        AtomicReference<String> tab = new AtomicReference<>("附近玩家");
        List<String> clicks = new ArrayList<>();
        List<String> ocrRegions = new ArrayList<>();
        List<String> recoveryOrder = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ignored) { return LuaValue.FALSE; }
        });
        globals.set("tomocrtext", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                recoveryOrder.add("ocr");
                String region = args.arg(1).toint() + "|" + args.arg(2).toint() + "|"
                        + args.arg(3).toint() + "|" + args.arg(4).toint();
                ocrRegions.add(region);
                if ("330|35|410|100".equals(region)) return LuaValue.valueOf(title.get());
                if ("1035|185|1190|260".equals(region)) return LuaValue.valueOf(tab.get());
                return LuaValue.valueOf("");
            }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return new LuaTable(); }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg(1).tojstring() + "|" + args.arg(2).tojstring()
                        + "|" + args.arg(3).tojstring());
                return LuaValue.TRUE;
            }
        });
        globals.set("找到点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.FALSE; }
        });
        globals.set("取上次点击时间", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("检测卡死", new ZeroArgFunction() {
            @Override public LuaValue call() {
                recoveryOrder.add("卡死检测");
                return LuaValue.FALSE;
            }
        });
        globals.set("appIsRunning", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ignored) { return LuaValue.TRUE; }
        });
        globals.set("frontAppName", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf("com.tepaylink.tamgioiphantranhmobile"); }
        });
        globals.set("游戏包名", LuaValue.valueOf("com.tepaylink.tamgioiphantranhmobile"));
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });

        assertTrue("OCR should recognize the localized party page when legacy image templates miss",
                globals.get("组队_页面已打开").checkfunction().call().toboolean());
        recoveryOrder.clear();
        globals.get("通用处理").checkfunction().call();
        assertTrue("The main loop's generic handler should close the recognized party modal",
                !clicks.isEmpty());
        assertEquals("@@关闭队伍页面|1203|130", clicks.get(0));
        assertTrue("The OCR crops should match the actual title and nearby-player tab positions",
                ocrRegions.contains("330|35|410|100") && ocrRegions.contains("1035|185|1190|260"));
        assertTrue("Party recovery should run before screenshot-heavy card detection",
                recoveryOrder.indexOf("ocr") >= 0
                        && recoveryOrder.indexOf("ocr") < recoveryOrder.indexOf("卡死检测"));

        clicks.clear();
        ocrRegions.clear();
        title.set("背包");
        globals.get("通用处理").checkfunction().call();
        assertTrue("Unrelated screens must not receive the party-page close tap", clicks.isEmpty());
    }

    @Test public void localizedServerClusterRequiresChineseTitleAndConfirmBeforeTap() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<String> title = new AtomicReference<>("服务器集群");
        AtomicReference<String> confirm = new AtomicReference<>("确认");
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ignored) { return LuaValue.FALSE; }
        });
        globals.set("tomocrtext", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(args.arg(1).toint() < 500 ? title.get() : confirm.get());
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg(1).tojstring() + "|" + args.arg(2).tojstring()
                        + "|" + args.arg(3).tojstring());
                return LuaValue.TRUE;
            }
        });

        assertTrue("The Chinese server page should be recognized by its title and confirm button",
                globals.get("登录_汉化选区确认").checkfunction().call().toboolean());
        assertEquals("@@汉化服务器集群确认|749|664", clicks.get(0));

        clicks.clear();
        title.set("角色选择");
        assertTrue("A non-server screen must not trigger the server confirmation",
                !globals.get("登录_汉化选区确认").checkfunction().call().toboolean());
        assertTrue("The helper must never tap on an unrelated screen", clicks.isEmpty());

        title.set("服务器集群");
        confirm.set("取消");
        assertTrue("A server title without a recognized confirmation control must not be tapped",
                !globals.get("登录_汉化选区确认").checkfunction().call().toboolean());
        assertTrue("The helper requires both OCR signatures", clicks.isEmpty());
    }

    @Test public void localizedLoginRequiresKnownChinesePageAndSavedAccount() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<LuaTable> rows = new AtomicReference<>(new LuaTable());
        List<String> clicks = new ArrayList<>();
        List<String> regions = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                regions.add(args.arg(1).toint() + "|" + args.arg(2).toint() + "|"
                        + args.arg(3).toint() + "|" + args.arg(4).toint());
                return rows.get();
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg(1).tojstring() + "|" + args.arg(2).tojstring()
                        + "|" + args.arg(3).tojstring());
                return LuaValue.TRUE;
            }
        });

        LuaTable shuffledRows = new LuaTable();
        shuffledRows.set(1, ocrRow("登录", 663, 525));
        shuffledRows.set(2, ocrRow(" sbs139502 ", 660, 300));
        rows.set(shuffledRows);
        assertTrue("A saved account and login button should submit even if side-link OCR is missed",
                globals.get("登录_汉化账号确认").checkfunction().call().toboolean());
        assertEquals("The OCR-recognized button center should be tapped",
                "@@汉化账号密码登录|663|525", clicks.get(0));
        assertEquals("The helper should read one broad localized-login region",
                Arrays.asList("400|70|1270|590"), regions);

        clicks.clear();
        LuaTable noAccountRows = new LuaTable();
        noAccountRows.set(1, ocrRow("忘记密码?", 1160, 125));
        noAccountRows.set(2, ocrRow("注册账号", 1160, 250));
        noAccountRows.set(3, ocrRow("登录", 663, 525));
        rows.set(noAccountRows);
        assertTrue("An empty account field must never trigger login",
                !globals.get("登录_汉化账号确认").checkfunction().call().toboolean());
        assertTrue("No tap is allowed when the saved account is empty", clicks.isEmpty());

        LuaTable misplacedLoginRows = new LuaTable();
        misplacedLoginRows.set(1, ocrRow("忘记密码", 1160, 125));
        misplacedLoginRows.set(2, ocrRow("注册账号", 1160, 250));
        misplacedLoginRows.set(3, ocrRow("sbs139502", 660, 300));
        misplacedLoginRows.set(4, ocrRow("登录", 200, 525));
        rows.set(misplacedLoginRows);
        assertTrue("A login-like label outside the login button zone must not submit",
                !globals.get("登录_汉化账号确认").checkfunction().call().toboolean());
        assertTrue("The localized login helper must require all positioned anchors", clicks.isEmpty());
    }

    @Test public void localizedLoginMatchesObservedRapidOcrRowsFromCurrentLoginScreenshot() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        LuaTable rows = new LuaTable();
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        // Transcript and centers from RapidOCR on the 1600x900 emulator login
        // screenshot, converted into the script's 1280x720 reference coordinates.
        rows.set(1, ocrRow("退出", 1169, 672));
        rows.set(2, ocrRow("登录", 648, 533));
        rows.set(3, ocrRow("sbs139502", 569, 298));
        rows.set(4, ocrRow("注册账号", 1169, 241));
        rows.set(5, ocrRow("忘记密码？", 1164, 132));
        rows.set(6, ocrRow("保存账号", 604, 448));
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return rows; }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg(1).tojstring() + "|" + args.arg(2).tojstring()
                        + "|" + args.arg(3).tojstring());
                return LuaValue.TRUE;
            }
        });

        assertTrue("Observed localized login OCR should pass the Lua page classifier",
                globals.get("登录_汉化账号确认").checkfunction().call().toboolean());
        assertEquals(Arrays.asList("@@汉化账号密码登录|648|533"), clicks);
    }

    @Test public void localizedMapPickerIsRecognizedByObservedChineseOcrAnchors() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<LuaTable> rows = new AtomicReference<>(new LuaTable());
        AtomicInteger clock = new AtomicInteger(1000);
        List<String> regions = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                regions.add(args.arg(1).toint() + "|" + args.arg(2).toint() + "|"
                        + args.arg(3).toint() + "|" + args.arg(4).toint());
                return rows.get();
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ignored) { return LuaValue.FALSE; }
        });

        LuaTable mapPickerRows = new LuaTable();
        mapPickerRows.set(1, ocrRow("NPC", 86, 44));
        mapPickerRows.set(2, ocrRow("输入NPC名", 66, 98));
        mapPickerRows.set(3, ocrRow("朝歌", 802, 43));
        rows.set(mapPickerRows);
        assertTrue("The localized NPC/map picker should be recognized from its real OCR anchors",
                globals.get("大地图页面已打开").checkfunction().call().toboolean());
        assertEquals(Arrays.asList("0|0|420|155"), regions);

        clock.addAndGet(400);
        LuaTable mapTabRows = new LuaTable();
        mapTabRows.set(1, ocrRow("NPC", 86, 44));
        mapTabRows.set(2, ocrRow("输入地图名", 66, 98));
        rows.set(mapTabRows);
        assertTrue("The same panel remains open after switching to the map-name tab",
                globals.get("大地图页面已打开").checkfunction().call().toboolean());
        assertTrue("The map-name tab should be recorded as the localized Chinese picker",
                globals.get("大地图页面中文").toboolean());

        clock.addAndGet(400);
        LuaTable unrelatedRows = new LuaTable();
        unrelatedRows.set(1, ocrRow("NPC", 86, 44));
        unrelatedRows.set(2, ocrRow("角色名称", 66, 98));
        rows.set(unrelatedRows);
        assertTrue("An NPC tab without the map-picker search placeholder is not enough",
                !globals.get("大地图页面已打开").checkfunction().call().toboolean());
        assertEquals(3, regions.size());
    }

    @Test public void localizedMapSelectionUsesExactOcrRowAndVerifiesSelectedMap() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<LuaTable> listRows = new AtomicReference<>(new LuaTable());
        AtomicReference<LuaTable> headerRows = new AtomicReference<>(new LuaTable());
        List<String> taps = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(1_000L); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return args.arg(1).toint() == 0 ? listRows.get() : headerRows.get();
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                taps.add(args.arg1().tojstring() + "|" + args.arg(2).tojstring()
                        + "|" + args.arg(3).tojstring());
                return LuaValue.TRUE;
            }
        });

        LuaTable list = new LuaTable();
        list.set(1, ocrRow("朝歌", 48, 185));
        list.set(2, ocrRow("崇城野外", 56, 198));
        listRows.set(list);
        LuaTable header = new LuaTable();
        header.set(1, ocrRow("崇城野外", 802, 43));
        headerRows.set(header);
        assertTrue("The localized selector should confirm the exact OCR-matched Chinese map",
                globals.get("大地图_按OCR选择").checkfunction()
                        .call(LuaValue.valueOf("崇城野外")).toboolean());
        assertEquals(Arrays.asList("56|198|1200"), taps);

        taps.clear();
        headerRows.set(new LuaTable());
        assertTrue("A tap without a matching selected-map title must not be reported as success",
                !globals.get("大地图_按OCR选择").checkfunction()
                        .call(LuaValue.valueOf("崇城野外")).toboolean());
        assertEquals("The exact map row may be tapped once, but a wrong title must fail closed",
                Arrays.asList("56|198|1200"), taps);
    }

    @Test public void localizedRouteKeepsMapTabButShopRouteSwitchesToNpcTab() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        List<String> taps = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(1_000L); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable rows = new LuaTable();
                if (args.arg(1).toint() == 0) rows.set(1, ocrRow("崇城野外", 56, 198));
                else rows.set(1, ocrRow("崇城野外", 802, 43));
                return rows;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("大地图页面已打开", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        globals.set("大地图页面中文", LuaValue.TRUE);
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                StringBuilder call = new StringBuilder(args.arg1().tojstring());
                for (int i = 2; i <= args.narg(); i++) call.append('|').append(args.arg(i).tojstring());
                taps.add(call.toString());
                return LuaValue.TRUE;
            }
        });
        LuaTable ime = new LuaTable();
        ime.set("lock", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        ime.set("unlock", ime.get("lock"));
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue text) { return LuaValue.TRUE; }
        });
        globals.set("imeLib", ime);

        assertTrue("Hunting navigation should select and verify the Chinese map",
                globals.get("大地图_输入大地图").checkfunction()
                        .call(LuaValue.valueOf("崇城野外"), LuaValue.FALSE).toboolean());
        assertTrue("The hunting route must not leave the map tab before typing X/Y",
                taps.stream().noneMatch(tap -> tap.startsWith("@@npc选择")));

        taps.clear();
        assertTrue("Shop navigation should select the same Chinese map",
                globals.get("大地图_输入大地图").checkfunction()
                        .call(LuaValue.valueOf("崇城野外"), LuaValue.TRUE).toboolean());
        assertEquals("The shop route should switch to NPC selection after map verification",
                2, taps.stream().filter(tap -> tap.startsWith("@@npc选择")).count());
    }

    @Test public void recoveredPartyNameUsesOnlyConfidentOcrRows() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<LuaTable> rows = new AtomicReference<>(new LuaTable());
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return rows.get(); }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaTable candidates = new LuaTable();
        LuaTable weak = new LuaTable();
        weak.set("words", LuaValue.valueOf("wrong-name"));
        weak.set("score", LuaValue.valueOf(0.42));
        LuaTable strong = new LuaTable();
        strong.set("words", LuaValue.valueOf("liusanjie"));
        strong.set("score", LuaValue.valueOf(0.91));
        candidates.set(1, weak);
        candidates.set(2, strong);
        rows.set(candidates);
        assertEquals("The team checker should use the strongest OCR line above its confidence floor",
                "liusanjie", globals.get("组队_识别名字").checkfunction().invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(147), LuaValue.valueOf(212), LuaValue.valueOf(327), LuaValue.valueOf(263)})).tojstring());

        LuaTable uncertain = new LuaTable();
        uncertain.set("words", LuaValue.valueOf("wrong-name"));
        uncertain.set("score", LuaValue.valueOf(0.79));
        LuaTable lowConfidenceRows = new LuaTable();
        lowConfidenceRows.set(1, uncertain);
        rows.set(lowConfidenceRows);
        assertEquals("An uncertain OCR result must become unreadable so the caller cannot disband on it",
                "", globals.get("组队_识别名字").checkfunction().invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf(147), LuaValue.valueOf(212), LuaValue.valueOf(327), LuaValue.valueOf(263)})).tojstring());
    }

    @Test public void combatSupplyUsesOnlyRecognizedLowResourceAndMatchingQuickslot() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicLong clock = new AtomicLong(10_000L);
        AtomicReference<String> health = new AtomicReference<>("6 / 24");
        AtomicReference<String> mana = new AtomicReference<>("70/70");
        AtomicReference<Double> healthScore = new AtomicReference<>(0.95d);
        AtomicReference<Double> manaScore = new AtomicReference<>(0.95d);
        AtomicReference<int[]> tapped = new AtomicReference<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable row = new LuaTable();
                boolean isHealth = args.arg(2).toint() < 65;
                row.set("words", LuaValue.valueOf(isHealth ? health.get() : mana.get()));
                row.set("score", LuaValue.valueOf(isHealth ? healthScore.get() : manaScore.get()));
                LuaTable rows = new LuaTable();
                rows.set(1, row);
                return rows;
            }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                tapped.set(new int[]{args.arg(1).toint(), args.arg(2).toint()});
                return LuaValue.TRUE;
            }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("sleep", noOp);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaTable config = new LuaTable();
        config.set("自动使用红药", LuaValue.valueOf("true"));
        config.set("生命药触发阈值", LuaValue.valueOf("30"));
        config.set("自动使用蓝药", LuaValue.valueOf("false"));
        config.set("法力药触发阈值", LuaValue.valueOf("20"));
        globals.set("界面数据", config);
        globals.set("战斗_查找快捷栏药品", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return args.arg1().tojstring().equals("红")
                        ? LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1051), LuaValue.valueOf(310)})
                        : LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaValue.NIL});
            }
        });

        LuaFunction parse = globals.get("战斗_解析资源百分比").checkfunction();
        assertEquals(25, parse.call(LuaValue.valueOf("6 / 24")).toint());
        assertEquals(50, parse.call(LuaValue.valueOf(" 5／10 ")).toint());
        assertTrue("Zero maximum, over-maximum, and unreadable OCR must be rejected",
                parse.call(LuaValue.valueOf("0/0")).isnil()
                        && parse.call(LuaValue.valueOf("25/24")).isnil()
                        && parse.call(LuaValue.valueOf("?/24")).isnil());

        LuaFunction useSupplies = globals.get("执行自动补给").checkfunction();
        assertTrue("A recognized low-health reading and matching red quickslot should trigger one tap",
                useSupplies.call().toboolean());
        assertEquals(1051, tapped.get()[0]);
        assertEquals(310, tapped.get()[1]);
        assertTrue("The supply cooldown must suppress repeated taps in the same low-health interval",
                !useSupplies.call().toboolean());
        healthScore.set(0.79d);
        clock.addAndGet(3_001L);
        assertTrue("Low-confidence health OCR must not trigger a potion tap", !useSupplies.call().toboolean());

        config.set("自动使用红药", LuaValue.valueOf("false"));
        config.set("自动使用蓝药", LuaValue.valueOf("true"));
        mana.set("10/70");
        manaScore.set(0.95d);
        clock.addAndGet(3_001L);
        globals.set("战斗_查找快捷栏药品", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return args.arg1().tojstring().equals("蓝")
                        ? LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1131), LuaValue.valueOf(310)})
                        : LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaValue.NIL});
            }
        });
        assertTrue("A recognized low-mana reading should use the matching blue quickslot",
                useSupplies.call().toboolean());
        assertEquals(1131, tapped.get()[0]);

        mana.set("unreadable");
        clock.addAndGet(3_001L);
        assertTrue("Unreadable OCR must never trigger a potion tap", !useSupplies.call().toboolean());
    }

    @Test public void combatSupplyDoesNotMistakeEmptyQuickslotsForPotionsInRealGameScreenshot() throws Exception {
        Path scriptRoot = scriptRoot();
        BufferedImage frame = readFixture("/game/blackbox-live.png");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                frame.getWidth(), frame.getHeight());
        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findMultiColor(frame, mapper, args); }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }

        assertTrue("The real game frame has empty quickslots, so no potion coordinate may be returned",
                globals.get("战斗_查找快捷栏药品").invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf("红")})).arg1().isnil());
        assertTrue("An empty hotbar must not return a blue potion coordinate either",
                globals.get("战斗_查找快捷栏药品").invoke(LuaValue.varargsOf(new LuaValue[]{
                        LuaValue.valueOf("蓝")})).arg1().isnil());
    }

    @Test public void combatSupplyLocatesPotionQuickslotsUsingRecoveredPotionPixels() throws Exception {
        Path scriptRoot = scriptRoot();
        BufferedImage liveFrame = readFixture("/game/blackbox-live.png");
        BufferedImage backpackFrame = readFixture("/game/inventory-backpack.png");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                liveFrame.getWidth(), liveFrame.getHeight());
        Graphics2D graphics = liveFrame.createGraphics();
        try {
            // Copy real red and blue potion sprites from the captured backpack frame into
            // two captured empty hotbar slots; the Lua color matcher still reads pixels.
            graphics.drawImage(backpackFrame, 1196, 366, 1232, 408,
                    1040, 205, 1076, 247, null);
            graphics.drawImage(backpackFrame, 1296, 366, 1332, 408,
                    1130, 205, 1166, 247, null);
        } finally { graphics.dispose(); }

        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findMultiColor(liveFrame, mapper, args); }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }

        Varargs redSlot = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("红")}));
        assertEquals("Red potion detection must return the assigned hotbar slot center", 971, redSlot.arg1().toint());
        assertEquals(310, redSlot.arg(2).toint());
        Varargs blueSlot = globals.get("战斗_查找快捷栏药品").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("蓝")}));
        assertEquals("Blue potion detection must return its separate hotbar slot center", 1051, blueSlot.arg1().toint());
        assertEquals(310, blueSlot.arg(2).toint());
    }

    @Test public void recoveredBackpackWeightParserAcceptsOcrSpacingAndFullWidthSlash() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaFunction parser = globals.get("检测背包_解析重量").checkfunction();
        Varargs exact = parser.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("2/200")}));
        assertEquals(2, exact.arg(1).toint());
        assertEquals(200, exact.arg(2).toint());
        Varargs spaced = parser.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(" 12 / 200 ")}));
        assertEquals(12, spaced.arg(1).toint());
        assertEquals(200, spaced.arg(2).toint());
        Varargs fullWidthSlash = parser.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("12／200")}));
        assertEquals(12, fullWidthSlash.arg(1).toint());
        assertEquals(200, fullWidthSlash.arg(2).toint());
        Varargs impossible = parser.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("201/200")}));
        assertTrue("Impossible or unreadable weights must be rejected instead of influencing the shop decision",
                impossible.arg1().isnil() && impossible.arg(2).isnil());
    }

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
        globals.set("挂机点地图", LuaValue.valueOf("崇城野外"));
        globals.set("挂机点x", LuaValue.valueOf("321"));
        globals.set("挂机点y", LuaValue.valueOf("654"));
        globals.set("需要去挂机点一次", LuaValue.TRUE);

        assertTrue("The route should report success only after pathfinding completes",
                globals.get("执行返回挂机点").checkfunction().call().toboolean());
        assertTrue("A successful route should clear the pending return flag",
                !globals.get("需要去挂机点一次").toboolean());

        assertEquals("The route must search the selected Chinese map, then enter X/Y.",
                Arrays.asList("崇城野外", "321", "654"), enteredText);
        assertTrue("The selected map should choose the correct map row",
                tappedControls.contains("@@选择地图2"));
        assertTrue("Returning to the hunting destination must leave the map tab selected for X/Y input",
                !tappedControls.contains("@@npc选择"));
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

    @Test public void recoveredPathfinderReturnsFailureAfterTimeout() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger checks = new AtomicInteger();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
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
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("检测寻路完成", new ZeroArgFunction() {
            @Override public LuaValue call() { checks.incrementAndGet(); return LuaValue.FALSE; }
        });

        assertTrue("Exhausting the route wait must return explicit failure",
                !globals.get("寻路_等待完成").checkfunction().call().toboolean());
        assertEquals("The legacy route wait should retain its bounded 300 checks", 300, checks.get());
    }

    @Test public void recoveredShopRouteStopsWhenNpcPathfindingTimesOut() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
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
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("_ENV", globals);
        LuaTable mapPage = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("大地图页面", mapPage);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) { return LuaValue.valueOf(template.raweq(mapPage)); }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        globals.set("通用处理", noOp);
        globals.set("登录_判断一轮", noOp);
        globals.set("大地图_输入大地图", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.TRUE; }
        });
        globals.set("寻路_等待完成", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        globals.set("卖货地图", LuaValue.valueOf("sùng thành"));

        assertTrue("Shop navigation must report failure when its route times out",
                !globals.get("去卖货").checkfunction().call().toboolean());
        assertEquals(Arrays.asList("@@前往卖货npc"), clicks);
        assertEquals("去卖货寻路失败", globals.get("当前任务2").tojstring());
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
        List<int[]> scriptTaps = new ArrayList<>();
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
                int referenceX = (int) Math.round(args.arg(1).todouble());
                int referenceY = (int) Math.round(args.arg(2).todouble());
                int x = mapper.toScreenX(referenceX);
                int y = mapper.toScreenY(referenceY);
                scriptTaps.add(new int[]{referenceX, referenceY});
                if (Math.abs(x - mapper.toScreenX(1159)) <= mapper.toScreenDeltaX(2)
                        && Math.abs(y - mapper.toScreenY(110)) <= mapper.toScreenDeltaY(2)) {
                    mapOpenTap.set(new int[]{x, y});
                    mapPageOpen.set(true);
                }
                if (Math.abs(x - mapper.toScreenX(857)) <= mapper.toScreenDeltaX(2)
                        && Math.abs(y - mapper.toScreenY(651)) <= mapper.toScreenDeltaY(2)) {
                    mapPageOpen.set(false);
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
        globals.set("挂机点地图", LuaValue.valueOf("崇城野外"));
        globals.set("挂机点x", LuaValue.valueOf("321"));
        globals.set("挂机点y", LuaValue.valueOf("654"));
        globals.set("需要去挂机点一次", LuaValue.TRUE);

        assertTrue("Lua should reach the configured destination from the real home screenshot",
                globals.get("执行返回挂机点").checkfunction().call().toboolean());
        assertTrue("The game home signature should lead Lua to tap the map-open control",
                mapOpenTap.get() != null);
        assertEquals("The route should enter the chosen map followed by its configured X/Y",
                Arrays.asList("崇城野外", "321", "654"), enteredText);
        assertTrue("The Lua route should submit the destination",
                !globals.get("需要去挂机点一次").toboolean());
        assertTrue("Submitting the destination should return the game to its home screen",
                !mapPageOpen.get());

        int tapsBeforeAutoBattle = scriptTaps.size();
        LuaTable config = new LuaTable();
        config.set("自动战斗", LuaValue.valueOf("true"));
        globals.set("界面数据", config);
        assertTrue("After reaching the destination, enabled auto-battle should start",
                globals.get("执行自动战斗").checkfunction().call().toboolean());
        assertTrue("Auto-battle should issue a game touch after the route completes",
                scriptTaps.size() > tapsBeforeAutoBattle);
        int[] autoBattleTap = scriptTaps.get(scriptTaps.size() - 1);
        assertTrue("The final touch should target the detected auto-battle button",
                Math.abs(autoBattleTap[0] - 757) <= 2 && Math.abs(autoBattleTap[1] - 566) <= 2);
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

    @Test public void recoveredSellerKeepsSpecialItemsWhenOrdinarySaleIsNotEnabled() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        List<String> clicks = new ArrayList<>();
        AtomicBoolean selectedItemOpen = new AtomicBoolean(true);
        AtomicBoolean staleSaleConfirmationOpen = new AtomicBoolean(true);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        VarArgFunction noOp = noOpFunction();
        globals.set("toast", noOp);
        globals.set("print", noOp);
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaTable shopPage = new LuaTable();
        LuaTable nonEquipmentPage = new LuaTable();
        LuaTable specialItemPage = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        templates.set("商店选中非装备属性页面", nonEquipmentPage);
        templates.set("商店选中特殊物品属性页面", specialItemPage);
        LuaTable saleConfirmationPage = new LuaTable();
        templates.set("商店卖货确定页面", saleConfirmationPage);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (target.raweq(shopPage)) return LuaValue.TRUE;
                if (target.raweq(saleConfirmationPage)) {
                    return LuaValue.valueOf(staleSaleConfirmationOpen.get());
                }
                if (target.raweq(nonEquipmentPage) || target.raweq(specialItemPage)) {
                    return LuaValue.valueOf(selectedItemOpen.get());
                }
                return LuaValue.FALSE;
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String control = args.arg1().tojstring();
                clicks.add(control);
                if ("@@关掉非装备物品".equals(control)) selectedItemOpen.set(false);
                if ("@@关闭商店卖货确定页面".equals(control)) staleSaleConfirmationOpen.set(false);
                return LuaValue.NONE;
            }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
            }
        });
        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        // Mirror a legacy profile missing this new setting: the host default is false.
        config.set("允许出售物品", LuaValue.valueOf(LegacyLuaConfig.defaults().get("允许出售物品")));
        globals.set("界面数据", config);
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);

        globals.get("去卖货_卖货").checkfunction().call();

        assertTrue("A default or missing trade opt-in must never sell a special item",
                !clicks.contains("@@出售特殊物品") && !clicks.contains("@@确定卖掉"));
        assertTrue("An orphaned sale dialog must be canceled when no allowlisted sale was initiated",
                clicks.contains("@@关闭商店卖货确定页面") && !clicks.contains("@@确定卖掉"));
        assertTrue("With all sale modes disabled, the merchant workflow must not scan or open inventory items",
                clicks.stream().noneMatch(action -> action.startsWith("@@点装备"))
                        && !clicks.contains("@@关掉非装备物品"));
    }

    @Test public void ordinaryItemSaleRequiresConfidentOcrExactAllowlistAndQuantityAboveThreshold() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        AtomicReference<String> quantityText = new AtomicReference<>("11");
        AtomicReference<Double> quantityScore = new AtomicReference<>(0.95d);
        AtomicReference<String> nameText = new AtomicReference<>("冰肌");
        AtomicReference<Double> nameScore = new AtomicReference<>(0.93d);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable row = new LuaTable();
                boolean isQuantity = args.arg(1).toint() != 620;
                row.set("words", LuaValue.valueOf(isQuantity ? quantityText.get() : nameText.get()));
                row.set("score", LuaValue.valueOf(isQuantity ? quantityScore.get() : nameScore.get()));
                LuaTable rows = new LuaTable();
                rows.set(1, row);
                return rows;
            }
        });
        globals.set("print", noOpFunction());
        globals.set("toast", noOpFunction());
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaFunction readQuantity = globals.get("去卖货_识别物品数量").checkfunction();
        assertEquals(11, readQuantity.invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(662), LuaValue.valueOf(187), LuaValue.valueOf(694), LuaValue.valueOf(212)})).arg1().toint());
        quantityScore.set(0.79d);
        assertTrue("Low-confidence quantity must become unknown", readQuantity.invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(662), LuaValue.valueOf(187), LuaValue.valueOf(694), LuaValue.valueOf(212)})).arg1().isnil());
        quantityScore.set(0.95d);

        LuaFunction readName = globals.get("去卖货_识别物品名称").checkfunction();
        assertEquals("冰肌", readName.call().tojstring());
        nameScore.set(0.7481599d);
        assertEquals("Low-confidence item names must not be used for a destructive sale", "", readName.call().tojstring());
        assertEquals("The captured shop's RapidOCR result scores 0.748; allow the exact match only in an explicit opt-in purchase path",
                "冰肌", readName.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.NIL, LuaValue.NIL,
                        LuaValue.NIL, LuaValue.NIL, LuaValue.valueOf(0.7)})).arg1().tojstring());
        nameScore.set(0.93d);

        LuaFunction eligible = globals.get("去卖货_判断普通物品出售资格").checkfunction();
        LuaTable config = new LuaTable();
        config.set("允许出售物品", LuaValue.valueOf("false"));
        config.set("出售物品清单", LuaValue.valueOf("冰肌"));
        config.set("出售物品数量阈值", LuaValue.valueOf("10"));
        globals.set("界面数据", config);
        assertTrue("Ordinary item sale must remain opt-in", !eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.valueOf(11)).arg1().toboolean());

        config.set("允许出售物品", LuaValue.valueOf("true"));
        assertTrue("The exact allowlist item at the threshold must be retained", !eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.valueOf(10)).arg1().toboolean());
        assertTrue("A similar but unlisted name must be retained", !eligible.call(
                LuaValue.valueOf("冰肌碎片"), LuaValue.valueOf(11)).arg1().toboolean());
        assertTrue("Unknown OCR quantity must be retained", !eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.NIL).arg1().toboolean());
        assertTrue("The exact allowlisted item above threshold may be sold", eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.valueOf(11)).arg1().toboolean());
        config.set("出售物品清单", LuaValue.valueOf("其他物品\n冰肌"));
        assertTrue("Line-separated allowlist entries must match exactly", eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.valueOf(11)).arg1().toboolean());
        config.set("出售物品清单", LuaValue.valueOf("其他物品，冰肌"));
        assertTrue("Comma-separated allowlist entries must match exactly", eligible.call(
                LuaValue.valueOf("冰肌"), LuaValue.valueOf(11)).arg1().toboolean());
    }

    @Test public void ordinaryItemPurchaseRequiresExactAllowlistAndCountBelowThreshold() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("toast", noOpFunction());
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        LuaFunction eligible = globals.get("去卖货_判断普通物品购买资格").checkfunction();
        LuaTable config = new LuaTable();
        config.set("允许购买物品", LuaValue.valueOf("false"));
        config.set("购买物品清单", LuaValue.valueOf("回城符"));
        config.set("购买物品数量阈值", LuaValue.valueOf("10"));
        config.set("每次购买数量", LuaValue.valueOf("8"));
        globals.set("界面数据", config);

        assertTrue("Generic purchases must remain disabled unless explicitly opted in",
                !eligible.call(LuaValue.valueOf("回城符"), LuaValue.valueOf(2)).arg1().toboolean());
        config.set("允许购买物品", LuaValue.valueOf("true"));
        assertTrue("A similar or unlisted item must never be purchased",
                !eligible.call(LuaValue.valueOf("回城符碎片"), LuaValue.valueOf(2)).arg1().toboolean());
        assertTrue("Unknown OCR inventory counts must skip the purchase",
                !eligible.call(LuaValue.valueOf("回城符"), LuaValue.NIL).arg1().toboolean());
        assertTrue("A non-integer OCR count must not drive a purchase",
                !eligible.call(LuaValue.valueOf("回城符"), LuaValue.valueOf(2.5)).arg1().toboolean());
        assertTrue("An item at the threshold must not be purchased",
                !eligible.call(LuaValue.valueOf("回城符"), LuaValue.valueOf(10)).arg1().toboolean());

        Varargs eligiblePurchase = eligible.invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("回城符"), LuaValue.valueOf(2)}));
        assertTrue("An allowlisted item below threshold should be eligible",
                eligiblePurchase.arg1().toboolean());
        assertEquals("The requested batch should be capped so it cannot exceed the configured stock threshold",
                8, eligiblePurchase.arg(2).toint());
        Varargs cappedPurchase = eligible.invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("回城符"), LuaValue.valueOf(9)}));
        assertEquals("The final purchase batch must be capped to the remaining threshold gap",
                1, cappedPurchase.arg(2).toint());
        config.set("购买物品清单", LuaValue.valueOf("其他物品，回城符"));
        assertTrue("Comma-separated item allowlists should use exact matches",
                eligible.call(LuaValue.valueOf("回城符"), LuaValue.valueOf(2)).arg1().toboolean());
        config.set("每次购买数量", LuaValue.valueOf("0.5"));
        assertTrue("A fractional batch size must not become a zero-count purchase action",
                !eligible.call(LuaValue.valueOf("回城符"), LuaValue.valueOf(2)).arg1().toboolean());
    }

    @Test public void localizedChineseVendorCatalogFindsAllTenVisibleChineseAllowlistItems() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage shopScreenshot = readFixture("/game/merchant-purchase-list.png");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                shopScreenshot.getWidth(), shopScreenshot.getHeight());
        List<String[]> catalogRows = new ArrayList<>();
        try (InputStream input = RecoveredLuaTaskTest.class.getResourceAsStream(
                "/game/merchant-purchase-catalog-zh-live-20261018-rapidocr.csv")) {
            if (input == null) throw new IOException("Missing Chinese merchant catalog OCR fixture");
            String[] lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim().split("\\R");
            assertEquals("Expected the header and ten visible Chinese catalog items", 11, lines.length);
            assertEquals("text,raw_center_x,raw_center_y,confidence,tap_x,tap_y", lines[0]);
            for (int i = 1; i < lines.length; i++) catalogRows.add(lines[i].split(",", -1));
        }

        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable rows = new LuaTable();
                int index = 1;
                for (String[] values : catalogRows) {
                    LuaTable row = new LuaTable();
                    row.set("words", LuaValue.valueOf(values[0]));
                    row.set("x", LuaValue.valueOf(mapper.toReferenceX(Double.parseDouble(values[1]))));
                    row.set("y", LuaValue.valueOf(mapper.toReferenceY(Double.parseDouble(values[2]))));
                    row.set("score", LuaValue.valueOf(Double.parseDouble(values[3])));
                    rows.set(index++, row);
                }
                return rows;
            }
        });
        List<String> clicks = new ArrayList<>();
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                clicks.add(args.arg(1).tojstring());
                return LuaValue.NONE;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        for (String[] values : catalogRows) {
            Varargs actual = globals.get("去卖货_定位商店商品").checkfunction()
                    .invoke(LuaValue.valueOf(values[0]));
            int actualX = actual.arg1().toint();
            assertEquals("Chinese catalog OCR must find " + values[0],
                    mapper.toReferenceX(Double.parseDouble(values[1])), actualX);
            assertEquals("The OCR row must map to the correct item icon for " + values[0],
                    Integer.parseInt(values[4]), actual.arg(1).toint() < 350 ? 144 : 396);
            int row = actual.arg(2).toint() < 190 ? 1 : actual.arg(2).toint() < 287 ? 2
                    : actual.arg(2).toint() < 383 ? 3 : actual.arg(2).toint() < 480 ? 4 : 5;
            assertEquals("The OCR row must map to the correct item row for " + values[0],
                    Integer.parseInt(values[5]), new int[] {0,147,244,340,436,530}[row]);
        }
        assertTrue("A visible Chinese catalog result must not trigger page navigation or game input",
                clicks.isEmpty());
    }

    @Test public void localizedVendorPageTwoMatchesChineseReturnTalismanAllowlist() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        Path scriptRoot = scriptRoot();
        BufferedImage pageOne = readFixture("/game/localized-vendor-list-live-20261019.png");
        BufferedImage pageTwo = readFixture("/game/localized-vendor-page2-live-20261019.png");
        List<OcrLine> pageOneOcr = readRapidOcrFixture(
                "/game/localized-vendor-list-live-20261019-rapidocr.csv");
        List<OcrLine> pageTwoOcr = readRapidOcrFixture(
                "/game/localized-vendor-page2-live-20261019-rapidocr.csv");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                pageOne.getWidth(), pageOne.getHeight());
        assertEquals(pageOne.getWidth(), pageTwo.getWidth());
        assertEquals(pageOne.getHeight(), pageTwo.getHeight());
        AtomicInteger currentPage = new AtomicInteger(1);
        List<String> clicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("toast", noOpFunction());
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                List<OcrLine> source = currentPage.get() == 1 ? pageOneOcr : pageTwoOcr;
                LuaTable rows = new LuaTable();
                int index = 1;
                for (OcrLine line : source) {
                    int x = mapper.toReferenceX(line.centerX);
                    int y = mapper.toReferenceY(line.centerY);
                    if (line.confidence < 0.7d || x < args.arg(1).toint() || x > args.arg(3).toint()
                            || y < args.arg(2).toint() || y > args.arg(4).toint()) continue;
                    LuaTable row = new LuaTable();
                    row.set("words", LuaValue.valueOf(line.text));
                    row.set("x", LuaValue.valueOf(x));
                    row.set("y", LuaValue.valueOf(y));
                    row.set("score", LuaValue.valueOf(line.confidence));
                    rows.set(index++, row);
                }
                return rows;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@商店下一页".equals(action)) currentPage.set(2);
                return LuaValue.NONE;
            }
        });

        Varargs location = globals.get("去卖货_定位商店商品").checkfunction().invoke(
                LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf("回城符")}));
        assertEquals("The second live page's OCR alias should locate the Chinese allowlist item",
                498, location.arg1().toint());
        assertEquals("The OCR row center should remain in its actual second-page row",
                315, location.arg(2).toint());
        assertEquals("The recovered Lua must inspect page one, then turn exactly once to page two",
                Arrays.asList("@@商店下一页"), clicks);
    }

    @Test public void localizedVendorOcrCompletesChineseAllowlistedPurchaseSequence() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        Path scriptRoot = scriptRoot();
        BufferedImage screenshot = readFixture("/game/localized-vendor-list-live-20261019.png");
        List<OcrLine> catalog = readRapidOcrFixture(
                "/game/localized-vendor-list-live-20261019-rapidocr.csv");
        List<OcrLine> detailTitle = readRapidOcrFixture(
                "/game/localized-item-detail-live-20261019-rapidocr.csv");
        assertEquals("The real item-detail sample should contain exactly one OCR title", 1, detailTitle.size());
        assertEquals("RapidOCR's observed title must remain the source for detail verification",
                "Thion Quyn Quen", detailTitle.get(0).text);
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                screenshot.getWidth(), screenshot.getHeight());
        List<String> clicks = new ArrayList<>();
        int[] catalogTap = {-1, -1};
        AtomicBoolean dialogOpen = new AtomicBoolean(false);
        AtomicInteger quantity = new AtomicInteger(0);
        LuaTable shopPage = new LuaTable();
        LuaTable buyDialog = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        templates.set("商店买货确定页面", buyDialog);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("toast", noOpFunction());
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int regionX = args.arg(1).toint();
                LuaTable rows = new LuaTable();
                if (regionX == 90) {
                    int index = 1;
                    for (OcrLine line : catalog) {
                        int x = mapper.toReferenceX(line.centerX);
                        int y = mapper.toReferenceY(line.centerY);
                        if (line.confidence < 0.7d || x < args.arg(1).toint() || x > args.arg(3).toint()
                                || y < args.arg(2).toint() || y > args.arg(4).toint()) continue;
                        LuaTable row = new LuaTable();
                        row.set("words", LuaValue.valueOf(line.text));
                        row.set("x", LuaValue.valueOf(x));
                        row.set("y", LuaValue.valueOf(y));
                        row.set("score", LuaValue.valueOf(line.confidence));
                        rows.set(index++, row);
                    }
                } else {
                    LuaTable row = new LuaTable();
                    if (regionX == 665) row.set("words", LuaValue.valueOf(Integer.toString(quantity.get())));
                    else row.set("words", LuaValue.valueOf(detailTitle.get(0).text));
                    row.set("score", LuaValue.valueOf(0.999d));
                    rows.set(1, row);
                }
                return rows;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                return LuaValue.valueOf(target.raweq(shopPage)
                        || target.raweq(buyDialog) && dialogOpen.get());
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@选择购买物品".equals(action)) {
                    catalogTap[0] = args.arg(2).toint();
                    catalogTap[1] = args.arg(3).toint();
                } else if ("@@购买".equals(action)) {
                    dialogOpen.set(true);
                    quantity.set(0);
                } else if ("@@购买+1".equals(action)) {
                    quantity.incrementAndGet();
                } else if ("@@确定购买数量".equals(action)) {
                    dialogOpen.set(false);
                }
                return LuaValue.NONE;
            }
        });
        LuaTable config = new LuaTable();
        config.set("允许购买物品", LuaValue.valueOf("true"));
        config.set("购买物品清单", LuaValue.valueOf("天权冠"));
        config.set("购买物品数量阈值", LuaValue.valueOf("5"));
        config.set("每次购买数量", LuaValue.valueOf("8"));
        globals.set("界面数据", config);

        String[] chineseCatalog = {"天权冠", "云中冠", "广成冠", "天权道袍", "云中道袍",
                "广成道袍", "天权令", "云中令", "广成令", "天权巾"};
        assertEquals("The live catalog sample should contain ten OCR item names", 10, catalog.size());
        for (int i = 0; i < chineseCatalog.length; i++) {
            assertTrue("Chinese catalog entry " + chineseCatalog[i] + " must match its observed Vietnamese OCR row "
                            + catalog.get(i).text,
                    globals.get("去卖货_商品名称匹配").checkfunction().call(
                            LuaValue.valueOf(chineseCatalog[i]), LuaValue.valueOf(catalog.get(i).text)).toboolean());
        }
        assertTrue("A different product name must not match the Chinese allowlist entry",
                !globals.get("去卖货_商品名称匹配").checkfunction().call(
                        LuaValue.valueOf("天权冠"), LuaValue.valueOf("Vân Trung Quán")).toboolean());
        assertTrue("The separately captured Vietnamese item-detail OCR title must match the same product",
                globals.get("去卖货_商品名称匹配").checkfunction().call(
                        LuaValue.valueOf("天权冠"), LuaValue.valueOf(detailTitle.get(0).text)).toboolean());

        assertTrue("A Chinese allowlist name must match the exact RapidOCR alias on the live vendor screen",
                globals.get("去卖货_购买普通物品").checkfunction().call(
                        LuaValue.valueOf("天权冠"), LuaValue.valueOf(1)).toboolean());
        assertEquals("The Vietnamese OCR row must map to the corresponding first product cell",
                144, catalogTap[0]);
        assertEquals(147, catalogTap[1]);
        assertEquals("The stock threshold must cap the purchase batch at four",
                4, quantity.get());
        assertTrue("The simulated transaction should close only after the exact verified quantity is submitted",
                !dialogOpen.get() && clicks.contains("@@确定购买数量"));
    }

    @Test public void genericPurchaseSelectsOcrMatchedCatalogCellAndCapsTheConfirmedBatch() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage shopScreenshot = readFixture("/game/merchant-purchase-list.png");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                shopScreenshot.getWidth(), shopScreenshot.getHeight());
        String[] catalogOcr;
        try (InputStream input = RecoveredLuaTaskTest.class.getResourceAsStream(
                "/game/merchant-purchase-catalog-rapidocr.csv")) {
            if (input == null) throw new IOException("Missing merchant catalog OCR fixture");
            String[] lines = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim().split("\\R");
            assertEquals("Expected the OCR CSV header and one catalog row", 2, lines.length);
            assertEquals("text,raw_center_x,raw_center_y,confidence", lines[0]);
            catalogOcr = lines[1].split(",", -1);
            assertEquals("Expected text, source center, and confidence", 4, catalogOcr.length);
        }
        assertEquals("The probe row must refer to the captured 1600x900 screen", 1600,
                shopScreenshot.getWidth());
        assertEquals(900, shopScreenshot.getHeight());
        assertEquals("The source OCR x must map to the Lua reference frame", 223,
                mapper.toReferenceX(Double.parseDouble(catalogOcr[1])));
        assertEquals("The source OCR y must map to the Lua reference frame", 128,
                mapper.toReferenceY(Double.parseDouble(catalogOcr[2])));
        int[] catalogTap = { -1, -1 };
        List<String> clicks = new ArrayList<>();
        List<String> toasts = new ArrayList<>();
        AtomicReference<String> confirmationName = new AtomicReference<>("天权冠");
        java.util.concurrent.atomic.AtomicInteger selectedQuantity = new java.util.concurrent.atomic.AtomicInteger();
        AtomicBoolean staleQuantityOcr = new AtomicBoolean(false);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        });
        globals.set("sleep2", globals.get("sleep"));
        globals.set("toast", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                toasts.add(args.arg1().tojstring());
                return LuaValue.NONE;
            }
        });
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable row = new LuaTable();
                if (args.arg(1).toint() == 90) {
                    row.set("words", LuaValue.valueOf(catalogOcr[0]));
                    row.set("x", LuaValue.valueOf(mapper.toReferenceX(Double.parseDouble(catalogOcr[1]))));
                    row.set("y", LuaValue.valueOf(mapper.toReferenceY(Double.parseDouble(catalogOcr[2]))));
                    row.set("score", LuaValue.valueOf(Double.parseDouble(catalogOcr[3])));
                } else if (args.arg(1).toint() == 550) {
                    row.set("words", LuaValue.valueOf(confirmationName.get()));
                    row.set("score", LuaValue.valueOf(0.93));
                } else if (args.arg(1).toint() == 665) {
                    row.set("words", LuaValue.valueOf(Integer.toString(
                            staleQuantityOcr.get() ? 0 : selectedQuantity.get())));
                    row.set("score", LuaValue.valueOf(0.96));
                } else {
                    row.set("words", LuaValue.valueOf("天权冠"));
                    row.set("score", LuaValue.valueOf(0.93));
                }
                LuaTable rows = new LuaTable();
                rows.set(1, row);
                return rows;
            }
        });
        LuaTable shopPage = new LuaTable();
        LuaTable buyDialog = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        templates.set("商店买货确定页面", buyDialog);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                return LuaValue.valueOf(target.raweq(shopPage) || target.raweq(buyDialog));
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@选择购买物品".equals(action)) {
                    catalogTap[0] = args.arg(2).toint();
                    catalogTap[1] = args.arg(3).toint();
                }
                if ("@@购买".equals(action)) selectedQuantity.set(0);
                if ("@@购买+1".equals(action)) selectedQuantity.incrementAndGet();
                return LuaValue.NONE;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                return LuaValue.valueOf(target.raweq(shopPage) || target.raweq(buyDialog));
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@选择购买物品".equals(action)) {
                    catalogTap[0] = args.arg(2).toint();
                    catalogTap[1] = args.arg(3).toint();
                }
                if ("@@购买".equals(action)) selectedQuantity.set(0);
                if ("@@购买+1".equals(action)) selectedQuantity.incrementAndGet();
                return LuaValue.NONE;
            }
        });
        LuaTable config = new LuaTable();
        config.set("允许购买物品", LuaValue.valueOf("true"));
        config.set("购买物品清单", LuaValue.valueOf("天权冠"));
        config.set("购买物品数量阈值", LuaValue.valueOf("5"));
        config.set("每次购买数量", LuaValue.valueOf("8"));
        globals.set("界面数据", config);

        LuaValue purchaseResult = globals.get("去卖货_购买普通物品").checkfunction().call(
                LuaValue.valueOf("天权冠"), LuaValue.valueOf(1));
        assertTrue("A real shop catalog screenshot should drive the recovered generic buy flow; toasts=" + toasts + ", clicks=" + clicks,
                purchaseResult.toboolean());
        assertEquals("The OCR catalog row must map to the first product icon horizontally", 144, catalogTap[0]);
        assertEquals("The OCR catalog row must map to the first product icon vertically", 147, catalogTap[1]);
        assertTrue("Shop icon tap must map to a non-empty pixel in the captured screenshot",
                (shopScreenshot.getRGB(mapper.toScreenX(catalogTap[0]), mapper.toScreenY(catalogTap[1]))
                        & 0x00ffffff) != 0);
        assertEquals("The remaining threshold gap caps the requested batch from 8 to 4",
                4, java.util.Collections.frequency(clicks, "@@购买+1"));
        assertTrue("The selected item should use the recovered purchase and confirmation controls",
                clicks.contains("@@购买") && clicks.contains("@@确定购买数量"));

        clicks.clear();
        staleQuantityOcr.set(true);
        assertTrue("A stale final quantity must abort before purchase confirmation",
                !globals.get("去卖货_购买普通物品").checkfunction().call(
                        LuaValue.valueOf("天权冠"), LuaValue.valueOf(1)).toboolean());
        assertTrue("Unverified quantity must never be submitted", !clicks.contains("@@确定购买数量"));

        clicks.clear();
        staleQuantityOcr.set(false);
        confirmationName.set("云中冠");
        assertTrue("A mismatched confirmation title must abort before quantity or purchase confirmation",
                !globals.get("去卖货_购买普通物品").checkfunction().call(
                        LuaValue.valueOf("天权冠"), LuaValue.valueOf(1)).toboolean());
        assertTrue("The wrong item must never reach the irreversible confirmation action",
                !clicks.contains("@@确定购买数量"));
    }

    @Test public void recoveredSellerRunsGenericPurchaseFromCapturedMerchantScreenAndInventoryCount() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage shopScreenshot = readFixture("/game/merchant-purchase-list.png");
        List<OcrLine> observedCatalog = readMerchantCatalogOcrFixture("/game/merchant-purchase-catalog-rapidocr.csv");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                shopScreenshot.getWidth(), shopScreenshot.getHeight());
        List<String> clicks = new ArrayList<>();
        int[] catalogTap = {-1, -1};
        AtomicBoolean bagPopup = new AtomicBoolean(false);
        AtomicBoolean buyDialog = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger selectedQuantity = new java.util.concurrent.atomic.AtomicInteger();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("hideToast", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("toast", noOpFunction());
        globals.set("print", noOpFunction());
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable row = new LuaTable();
                int regionX = args.arg(1).toint();
                if (regionX == 90) {
                    LuaTable rows = new LuaTable();
                    int index = 1;
                    for (OcrLine line : observedCatalog) {
                        if (line.confidence < 0.7d) continue;
                        int x = mapper.toReferenceX(line.centerX);
                        int y = mapper.toReferenceY(line.centerY);
                        if (x < args.arg(1).toint() || x > args.arg(3).toint()
                                || y < args.arg(2).toint() || y > args.arg(4).toint()) continue;
                        LuaTable catalogRow = new LuaTable();
                        catalogRow.set("words", LuaValue.valueOf(line.text));
                        catalogRow.set("x", LuaValue.valueOf(x));
                        catalogRow.set("y", LuaValue.valueOf(y));
                        catalogRow.set("score", LuaValue.valueOf(line.confidence));
                        rows.set(index++, catalogRow);
                    }
                    return rows;
                } else if (regionX == 662) {
                    row.set("words", LuaValue.valueOf("1"));
                    row.set("score", LuaValue.valueOf(0.96));
                } else if (regionX == 550) {
                    row.set("words", LuaValue.valueOf("天权冠"));
                    row.set("score", LuaValue.valueOf(0.93));
                } else if (regionX == 665) {
                    row.set("words", LuaValue.valueOf(Integer.toString(selectedQuantity.get())));
                    row.set("score", LuaValue.valueOf(0.96));
                } else {
                    row.set("words", LuaValue.valueOf("天权冠"));
                    row.set("score", LuaValue.valueOf(0.93));
                }
                LuaTable rows = new LuaTable();
                rows.set(1, row);
                return rows;
            }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                boolean firstInventorySlot = args.arg(1).toint() == 666 && args.arg(2).toint() == 147;
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(!firstInventorySlot),
                        LuaValue.valueOf(firstInventorySlot ? -1 : 1), LuaValue.valueOf(firstInventorySlot ? -1 : 1)});
            }
        });
        LuaTable shopPage = new LuaTable();
        LuaTable inventoryPopup = new LuaTable();
        LuaTable specialPopup = new LuaTable();
        LuaTable buyConfirmation = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        templates.set("商店选中装备属性页面", new LuaTable());
        templates.set("商店选中非装备属性页面", inventoryPopup);
        templates.set("商店选中特殊物品属性页面", specialPopup);
        templates.set("商店买货确定页面", buyConfirmation);
        templates.set("商店卖货确定页面", new LuaTable());
        templates.set("对话页面", new LuaTable());
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (target.raweq(shopPage)) return LuaValue.TRUE;
                if (target.raweq(inventoryPopup)) return LuaValue.valueOf(bagPopup.get());
                if (target.raweq(specialPopup)) return LuaValue.FALSE;
                if (target.raweq(buyConfirmation)) return LuaValue.valueOf(buyDialog.get());
                return LuaValue.FALSE;
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@点装备1".equals(action)) bagPopup.set(true);
                if ("@@关掉非装备物品".equals(action)) bagPopup.set(false);
                if ("@@选择购买物品".equals(action)) {
                    catalogTap[0] = args.arg(2).toint();
                    catalogTap[1] = args.arg(3).toint();
                }
                if ("@@购买".equals(action)) buyDialog.set(true);
                if ("@@购买".equals(action)) selectedQuantity.set(0);
                if ("@@购买+1".equals(action)) selectedQuantity.incrementAndGet();
                if ("@@确定购买数量".equals(action)) buyDialog.set(false);
                return LuaValue.NONE;
            }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (target.raweq(shopPage)) return LuaValue.TRUE;
                if (target.raweq(inventoryPopup)) return LuaValue.valueOf(bagPopup.get());
                if (target.raweq(specialPopup)) return LuaValue.FALSE;
                if (target.raweq(buyConfirmation)) return LuaValue.valueOf(buyDialog.get());
                return LuaValue.FALSE;
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg(1).tojstring();
                clicks.add(action);
                if ("@@点装备1".equals(action)) bagPopup.set(true);
                if ("@@关掉非装备物品".equals(action)) bagPopup.set(false);
                if ("@@选择购买物品".equals(action)) {
                    catalogTap[0] = args.arg(2).toint();
                    catalogTap[1] = args.arg(3).toint();
                }
                if ("@@购买".equals(action)) buyDialog.set(true);
                if ("@@购买".equals(action)) selectedQuantity.set(0);
                if ("@@购买+1".equals(action)) selectedQuantity.incrementAndGet();
                if ("@@确定购买数量".equals(action)) buyDialog.set(false);
                return LuaValue.NONE;
            }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                boolean firstInventorySlot = args.arg(1).toint() == 666 && args.arg(2).toint() == 147;
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(!firstInventorySlot),
                        LuaValue.valueOf(firstInventorySlot ? -1 : 1),
                        LuaValue.valueOf(firstInventorySlot ? -1 : 1)});
            }
        });
        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("允许出售物品", LuaValue.valueOf("false"));
        config.set("出售物品清单", LuaValue.valueOf(""));
        config.set("出售物品数量阈值", LuaValue.valueOf("10"));
        config.set("允许购买物品", LuaValue.valueOf("true"));
        config.set("购买物品清单", LuaValue.valueOf("天权冠"));
        config.set("购买物品数量阈值", LuaValue.valueOf("5"));
        config.set("每次购买数量", LuaValue.valueOf("3"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        globals.set("界面数据", config);
        globals.set("小红数量", LuaValue.ZERO);
        globals.set("大红数量", LuaValue.ZERO);
        globals.set("小蓝数量", LuaValue.ZERO);
        globals.set("大蓝数量", LuaValue.ZERO);
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);

        globals.get("去卖货_卖货").checkfunction().call();

        assertTrue("The bag count from the real shop screen should trigger an allowlisted generic purchase",
                clicks.contains("@@购买") && clicks.contains("@@确定购买数量"));
        assertEquals("The OCR product name should map back to its captured catalog icon", 144, catalogTap[0]);
        assertEquals("The OCR product row should map back to the first catalog row", 147, catalogTap[1]);
        assertEquals("The configured batch should be capped from 3 to the remaining threshold gap of 4", 3,
                java.util.Collections.frequency(clicks, "@@购买+1"));
        assertTrue("The generic transaction must never sell bag contents",
                !clicks.contains("@@卖1") && !clicks.contains("@@出售特殊物品") && !clicks.contains("@@确定卖掉"));
        assertTrue("The catalog target coordinates must land inside the actual merchant screenshot",
                (shopScreenshot.getRGB(mapper.toScreenX(catalogTap[0]), mapper.toScreenY(catalogTap[1]))
                        & 0x00ffffff) != 0);
    }

    @Test public void recoveredSellerConfirmsOnlyAnOcrVerifiedAllowlistedSpecialItem() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        List<String> clicks = new ArrayList<>();
        AtomicBoolean shopVisible = new AtomicBoolean(true);
        AtomicBoolean itemPopupVisible = new AtomicBoolean(false);
        AtomicBoolean confirmationVisible = new AtomicBoolean(false);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("print", noOpFunction());
        globals.set("toast", noOpFunction());
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable row = new LuaTable();
                if (args.arg(1).toint() == 662) {
                    row.set("words", LuaValue.valueOf("11"));
                    row.set("score", LuaValue.valueOf(0.95));
                } else {
                    row.set("words", LuaValue.valueOf("冰肌"));
                    row.set("score", LuaValue.valueOf(0.93));
                }
                LuaTable rows = new LuaTable();
                rows.set(1, row);
                return rows;
            }
        });
        globals.set("_ENV", globals);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaTable templates = new LuaTable();
        for (String name : Arrays.asList("商店页面", "商店选中装备属性页面", "商店选中非装备属性页面",
                "商店选中特殊物品属性页面", "商店卖货确定页面", "商店买货确定页面", "对话页面")) {
            templates.set(name, new LuaTable());
        }
        globals.set("tz", templates);
        LuaValue shopPage = templates.get("商店页面");
        LuaValue equipmentPage = templates.get("商店选中装备属性页面");
        LuaValue nonEquipmentPage = templates.get("商店选中非装备属性页面");
        LuaValue specialPage = templates.get("商店选中特殊物品属性页面");
        LuaValue confirmationPage = templates.get("商店卖货确定页面");
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue target) {
                if (target.raweq(shopPage)) return LuaValue.valueOf(shopVisible.get());
                if (target.raweq(nonEquipmentPage) || target.raweq(specialPage)) {
                    return LuaValue.valueOf(itemPopupVisible.get());
                }
                if (target.raweq(confirmationPage)) return LuaValue.valueOf(confirmationVisible.get());
                if (target.raweq(equipmentPage)) return LuaValue.FALSE;
                return LuaValue.FALSE;
            }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg1().tojstring();
                clicks.add(action);
                if (action.startsWith("@@点装备")) {
                    shopVisible.set(false);
                    itemPopupVisible.set(true);
                } else if ("@@出售特殊物品".equals(action)) {
                    confirmationVisible.set(true);
                } else if ("@@确定卖掉".equals(action)) {
                    confirmationVisible.set(false);
                    itemPopupVisible.set(false);
                } else if ("@@关掉非装备物品".equals(action)) {
                    itemPopupVisible.set(false);
                }
                return LuaValue.NONE;
            }
        });

        LuaTable config = new LuaTable();
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("保留蓝条数量", LuaValue.valueOf("4"));
        config.set("回蓝>=", LuaValue.valueOf("false"));
        config.set("魔法出招>=", LuaValue.valueOf("false"));
        config.set("武器出招>=", LuaValue.valueOf("false"));
        config.set("允许出售物品", LuaValue.valueOf("true"));
        config.set("出售物品清单", LuaValue.valueOf("冰肌"));
        config.set("出售物品数量阈值", LuaValue.valueOf("10"));
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("false"));
        globals.set("界面数据", config);
        globals.set("买了一次蓝", LuaValue.FALSE);
        globals.set("买了一次红", LuaValue.FALSE);
        globals.set("初始化选中", noOpFunction());

        globals.get("去卖货_卖货").checkfunction().call();

        assertTrue("An OCR-verified exact allowlist match above the count threshold should enter sale",
                clicks.contains("@@出售特殊物品"));
        assertTrue("The eligible sale must be submitted through the existing confirmation flow",
                clicks.contains("@@确定卖掉"));
        assertTrue("Ordinary item policy must never route a special item through the equipment sale control",
                !clicks.contains("@@卖1"));

        clicks.clear();
        shopVisible.set(true);
        itemPopupVisible.set(false);
        confirmationVisible.set(false);
        config.set("出售物品清单", LuaValue.valueOf("未列入的其他物品"));
        globals.get("去卖货_卖货").checkfunction().call();
        assertTrue("An opted-in but unlisted item must never reach the destructive sale action",
                !clicks.contains("@@出售特殊物品") && !clicks.contains("@@确定卖掉"));
        assertTrue("An unlisted selection should be closed and preserved", clicks.contains("@@关掉非装备物品"));
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
        assertEquals("The observed confirmation starts at zero, so quantity 3 requires three increments", 3,
                java.util.Collections.frequency(clicks, "@@购买+1"));
        assertTrue("The recovered purchase flow must submit the selected quantity",
                clicks.contains("@@确定购买数量"));
        assertTrue("A blue purchase must never click the red supply tab", !clicks.contains("@@点红"));

        clicks.clear();
        config.set("购买红的数量", LuaValue.valueOf("0"));
        globals.get("去卖货_卖货_执行红蓝处理").checkfunction().call(LuaValue.valueOf("买红"));
        assertTrue("A configured quantity of zero must never reach the game's default one-item purchase",
                clicks.isEmpty());

        config.set("购买红的数量", LuaValue.valueOf("2"));
        globals.get("去卖货_卖货_执行红蓝处理").checkfunction().call(LuaValue.valueOf("买红"));
        assertTrue("A positive red purchase should choose the red supply tab", clicks.contains("@@点红"));
        assertEquals("The observed confirmation starts at zero, so quantity 2 requires two increments", 2,
                java.util.Collections.frequency(clicks, "@@购买+1"));
        assertTrue("A valid red purchase should submit its selected quantity", clicks.contains("@@确定购买数量"));
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
                int mask = Integer.parseInt(args.arg(6).tojstring(), 16) & 0x00ffffff;
                LegacyImageMatcher.Match match = LegacyImageMatcher.find(imagePixels(screen),
                        imagePixels(template), left, top, right, bottom, mask,
                        args.arg(8).optdouble(0.95), () -> { });
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(match != null),
                        LuaValue.valueOf(match == null ? -1 : mapper.toReferenceX(match.x)),
                        LuaValue.valueOf(match == null ? -1 : mapper.toReferenceY(match.y))});
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

    @Test public void localizedLiveBackpackPixelsAndRapidOcrFeedRecoveredBlueSupplyScanner() throws Exception {
        Path scriptRoot = scriptRoot();
        BufferedImage screen = readFixture("/game/localized-backpack-live-20261019.png");
        List<OcrLine> counts = readRapidOcrFixture(
                "/game/localized-backpack-live-20261019-counts-rapidocr.csv");
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                screen.getWidth(), screen.getHeight());
        Globals globals = JsePlatform.standardGlobals();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findMultiColor(screen, mapper, args); }
        });
        globals.set("tomocrtextall", LegacyLuaCompat.createTomocrTextAll(args ->
                readObservedOcrLines(counts, mapper, args)));
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("sleep", noOp);
        globals.set("sleep2", noOp);
        globals.set("hideToast", noOp);
        globals.set("toast", noOp);
        globals.set("print", noOp);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot.resolve(module), module);
        }

        LuaFunction identify = globals.get("检测背包_识别红蓝数量_识别红蓝").checkfunction();
        assertEquals("The localized live screenshot's blue potion must match the recovered pixel signature",
                "小蓝", identify.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(812),
                        LuaValue.valueOf(149), LuaValue.valueOf(880), LuaValue.valueOf(221)})).tojstring());
        Varargs inventoryRead = globals.get("检测背包_识别红蓝数量").checkfunction().invoke();
        assertEquals("RapidOCR on the live localized screenshot observes one blue potion",
                "1", inventoryRead.arg1().get("小蓝").tojstring());
        assertTrue("The observed count is numeric and must be accepted by the supply scanner",
                inventoryRead.arg(2).toboolean());
        LuaTable config = new LuaTable();
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("true"));
        config.set("蓝的数量低于", LuaValue.valueOf("3"));
        config.set("红的数量低于", LuaValue.valueOf("3"));
        globals.set("界面数据", config);
        globals.set("买了一次红", LuaValue.FALSE);
        globals.set("买了一次蓝", LuaValue.FALSE);
        Varargs supplyDecision = globals.get("检测背包_判断是否去卖或者买药").checkfunction().invoke();
        assertEquals("The real localized screenshot must select blue replenishment at stock 1/3",
                "买蓝", supplyDecision.arg1().tojstring());
        assertEquals("The supply decision must retain the observed blue count", "1",
                supplyDecision.arg(2).tojstring());
    }

    @Test public void recoveredBackpackScreenshotDrivesBlueSupplyPurchaseWithoutSelling() throws Exception {
        Globals globals = JsePlatform.standardGlobals();
        BufferedImage screen = readFixture("/game/inventory-backpack.png");
        List<OcrLine> recognizedCounts = readRapidOcrFixture("/game/inventory-backpack-counts-rapidocr.csv");
        // False low-confidence digits in the same ROIs must not inflate the observed stock counts.
        recognizedCounts.add(new OcrLine(1029.5d, 250.92d, "9", 0.849d));
        recognizedCounts.add(new OcrLine(1118.08d, 250.58d, "9", 0.849d));
        // The captured localized client has Vietnamese bag-tab labels outside these numeric ROIs.
        // A full-screen OCR row must not leak that UI text into the stock count.
        recognizedCounts.add(new OcrLine(900d, 151d, "Túi 1", 0.999d));
        recognizedCounts.add(new OcrLine(1055d, 151d, "Túi 2", 0.999d));
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280,
                screen.getWidth(), screen.getHeight());
        AtomicBoolean ocrReadable = new AtomicBoolean(true);
        AtomicBoolean purchaseDialogOpen = new AtomicBoolean(false);
        AtomicBoolean blueSupplyTabSelected = new AtomicBoolean(false);
        AtomicInteger selectedPurchaseQuantity = new AtomicInteger(-1);
        AtomicInteger confirmedPurchaseQuantity = new AtomicInteger(-1);
        List<String> purchaseClicks = new ArrayList<>();
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.ZERO; }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return findMultiColor(screen, mapper, args);
            }
        });
        globals.set("tomocrtextall", LegacyLuaCompat.createTomocrTextAll(args ->
                ocrReadable.get() ? readObservedOcrLines(recognizedCounts, mapper, args) : new LuaTable()));
        OneArgFunction noSleep = new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) { return LuaValue.NONE; }
        };
        globals.set("sleep", noSleep);
        globals.set("sleep2", noSleep);
        globals.set("hideToast", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        VarArgFunction noOp = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("toast", noOp);
        globals.set("print", noOp);
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            loadModule(globals, scriptRoot().resolve(module), module);
        }

        LuaFunction identify = globals.get("检测背包_识别红蓝数量_识别红蓝").checkfunction();
        assertEquals("", identify.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(670),
                LuaValue.valueOf(149), LuaValue.valueOf(738), LuaValue.valueOf(221)})).tojstring());
        assertEquals("", identify.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(741),
                LuaValue.valueOf(149), LuaValue.valueOf(809), LuaValue.valueOf(221)})).tojstring());
        assertEquals("小红", identify.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(812),
                LuaValue.valueOf(149), LuaValue.valueOf(880), LuaValue.valueOf(221)})).tojstring());
        assertEquals("小蓝", identify.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(883),
                LuaValue.valueOf(149), LuaValue.valueOf(951), LuaValue.valueOf(221)})).tojstring());

        Varargs counts = globals.get("检测背包_识别红蓝数量").checkfunction().invoke();
        LuaTable inventory = counts.arg1().checktable();
        assertEquals("1", inventory.get("小红").tojstring());
        assertEquals("1", inventory.get("小蓝").tojstring());
        assertTrue("Missing large-potion icons are valid zero stock, not OCR failures", counts.arg(2).toboolean());
        LuaTable config = new LuaTable();
        config.set("出售全部红", LuaValue.valueOf("false"));
        config.set("出售全部蓝", LuaValue.valueOf("false"));
        config.set("出售装备", LuaValue.valueOf("false"));
        config.set("保护装备不出售", LuaValue.valueOf("true"));
        config.set("出售装备品质上限", LuaValue.valueOf("0"));
        config.set("购买小红", LuaValue.valueOf("false"));
        config.set("购买小蓝", LuaValue.valueOf("true"));
        config.set("红的数量高于", LuaValue.valueOf("10"));
        config.set("蓝的数量高于", LuaValue.valueOf("10"));
        config.set("红的数量低于", LuaValue.valueOf("5"));
        config.set("蓝的数量低于", LuaValue.valueOf("3"));
        globals.set("界面数据", config);
        globals.set("大红数量", LuaValue.valueOf(inventory.get("大红").toint()));
        globals.set("小红数量", LuaValue.valueOf(inventory.get("小红").toint()));
        globals.set("大蓝数量", LuaValue.valueOf(inventory.get("大蓝").toint()));
        globals.set("小蓝数量", LuaValue.valueOf(inventory.get("小蓝").toint()));
        globals.set("买了一次红", LuaValue.FALSE);
        globals.set("买了一次蓝", LuaValue.FALSE);
        config.set("购买蓝的数量", LuaValue.valueOf("3"));
        LuaTable shopPage = new LuaTable();
        LuaTable buyConfirmation = new LuaTable();
        LuaTable templates = new LuaTable();
        templates.set("商店页面", shopPage);
        templates.set("商店买货确定页面", buyConfirmation);
        globals.set("tz", templates);
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) {
                if (template.raweq(shopPage)) return LuaValue.TRUE;
                if (template.raweq(buyConfirmation)) return LuaValue.valueOf(purchaseDialogOpen.get());
                return LuaValue.FALSE;
            }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String action = args.arg1().tojstring();
                purchaseClicks.add(action);
                if ("@@点蓝".equals(action)) blueSupplyTabSelected.set(true);
                if ("@@点红".equals(action)) blueSupplyTabSelected.set(false);
                if ("@@购买".equals(action) && blueSupplyTabSelected.get()) {
                    selectedPurchaseQuantity.set(0);
                    purchaseDialogOpen.set(true);
                }
                if ("@@购买+1".equals(action) && purchaseDialogOpen.get()) {
                    selectedPurchaseQuantity.incrementAndGet();
                }
                if ("@@确定购买数量".equals(action) && purchaseDialogOpen.get()) {
                    confirmedPurchaseQuantity.set(selectedPurchaseQuantity.get());
                    purchaseDialogOpen.set(false);
                }
                return LuaValue.NONE;
            }
        });
        Varargs decision = globals.get("检测背包_判断是否去卖或者买药").checkfunction().invoke();
        assertEquals("买蓝", decision.arg1().tojstring());
        assertEquals("1", decision.arg(2).tojstring());
        globals.get("去卖货_卖货").checkfunction().call();
        assertTrue("The screenshot-driven decision should select the blue supply tab",
                purchaseClicks.contains("@@点蓝"));
        assertEquals("The observed confirmation starts at zero, so quantity 3 requires three increments",
                3, java.util.Collections.frequency(purchaseClicks, "@@购买+1"));
        assertTrue("The purchase flow must submit the configured quantity",
                purchaseClicks.contains("@@确定购买数量"));
        assertEquals("The test merchant should start at zero and confirm the exact configured quantity",
                3, confirmedPurchaseQuantity.get());
        assertTrue("The confirmation should close the dialog before the seller loop continues",
                !purchaseDialogOpen.get());
        assertTrue("The merchant workflow should finish the routine repair step after the purchase",
                purchaseClicks.contains("@@修理1") && purchaseClicks.contains("@@修理2"));
        assertTrue("The default-protected run must never sell equipment or ordinary items",
                !purchaseClicks.contains("@@卖1") && !purchaseClicks.contains("@@出售特殊物品")
                        && !purchaseClicks.contains("@@确定卖掉"));

        ocrReadable.set(false);
        Varargs unreadableDecision = globals.get("检测背包_判断是否去卖或者买药").checkfunction().invoke();
        assertEquals("", unreadableDecision.arg1().tojstring());
        int clicksAfterSuccessfulPurchase = purchaseClicks.size();
        if (!unreadableDecision.arg1().tojstring().isEmpty()) {
            globals.get("去卖货_卖货_执行红蓝处理").checkfunction().call(unreadableDecision.arg1());
        }
        assertEquals("Unreadable OCR must not produce additional purchase clicks",
                clicksAfterSuccessfulPurchase, purchaseClicks.size());
    }

    private static Varargs findMultiColor(BufferedImage image, LegacyCoordinateMapper mapper, Varargs args) {
        int left = Math.max(0, mapper.left(args.arg(1).todouble()));
        int top = Math.max(0, mapper.top(args.arg(2).todouble()));
        int right = Math.min(image.getWidth() - 1, mapper.right(args.arg(3).todouble()));
        int bottom = Math.min(image.getHeight() - 1, mapper.bottom(args.arg(4).todouble()));
        int[] primary = parseColorTolerance(args.arg(5).tojstring());
        String[] fields = args.arg(6).tojstring().split("\\|");
        List<int[]> offsets = new ArrayList<>();
        for (int i = 0; i + 2 < fields.length; i += 3) {
            int[] color = parseColorTolerance(fields[i + 2]);
            offsets.add(new int[]{mapper.toScreenDeltaX(Integer.parseInt(fields[i].trim())),
                    mapper.toScreenDeltaY(Integer.parseInt(fields[i + 1].trim())), color[0], color[1]});
        }
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            if (!colorMatches(image.getRGB(x, y), primary[0], primary[1])) continue;
            boolean matched = true;
            for (int[] offset : offsets) {
                int px = x + offset[0], py = y + offset[1];
                if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()
                        || !colorMatches(image.getRGB(px, py), offset[2], offset[3])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(mapper.toReferenceX(x)),
                        LuaValue.valueOf(mapper.toReferenceY(y))});
            }
        }
        return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
    }

    private static int[] parseColorTolerance(String encoded) {
        String[] parts = encoded.split("-", 2);
        return new int[]{Integer.parseInt(parts[0].trim(), 16),
                parts.length == 2 ? Integer.parseInt(parts[1].trim(), 16) : 0};
    }

    private static List<OcrLine> readRapidOcrFixture(String resourceName) throws IOException {
        InputStream input = RecoveredLuaTaskTest.class.getResourceAsStream(resourceName);
        if (input == null) throw new IOException("Missing observed OCR fixture " + resourceName);
        List<OcrLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // Header.
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length < 4) throw new IOException("Malformed RapidOCR fixture row: " + line);
                lines.add(new OcrLine(Double.parseDouble(fields[0]), Double.parseDouble(fields[1]),
                        fields[2], Double.parseDouble(fields[3])));
            }
        }
        return lines;
    }

    private static List<OcrLine> readMerchantCatalogOcrFixture(String resourceName) throws IOException {
        InputStream input = RecoveredLuaTaskTest.class.getResourceAsStream(resourceName);
        if (input == null) throw new IOException("Missing observed merchant OCR fixture " + resourceName);
        List<OcrLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // Header: text,raw_center_x,raw_center_y,confidence.
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length < 4) throw new IOException("Malformed merchant OCR fixture row: " + line);
                lines.add(new OcrLine(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                        fields[0], Double.parseDouble(fields[3])));
            }
        }
        return lines;
    }

    private static LuaTable readObservedOcrLines(List<OcrLine> lines, LegacyCoordinateMapper mapper, Varargs args) {
        int left = Math.max(0, mapper.left(args.arg(1).todouble()));
        int top = Math.max(0, mapper.top(args.arg(2).todouble()));
        int right = mapper.edgeRight(args.arg(3).todouble());
        int bottom = mapper.edgeBottom(args.arg(4).todouble());
        LuaTable observed = new LuaTable();
        int index = 1;
        for (OcrLine line : lines) {
            if (line.centerX < left || line.centerX > right || line.centerY < top || line.centerY > bottom) continue;
            LuaTable row = new LuaTable();
            row.set("words", LuaValue.valueOf(line.text));
            row.set("text", LuaValue.valueOf(line.text));
            row.set("x", LuaValue.valueOf(line.centerX));
            row.set("y", LuaValue.valueOf(line.centerY));
            row.set("score", LuaValue.valueOf(line.confidence));
            row.set("success", LuaValue.TRUE);
            observed.set(index++, row);
        }
        return observed;
    }

    private static final class OcrLine {
        final double centerX, centerY, confidence;
        final String text;
        OcrLine(double centerX, double centerY, String text, double confidence) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.text = text;
            this.confidence = confidence;
        }
    }

    private static boolean colorMatches(int actual, int expected, int tolerance) {
        return Math.abs(((actual >> 16) & 255) - ((expected >> 16) & 255)) <= ((tolerance >> 16) & 255)
                && Math.abs(((actual >> 8) & 255) - ((expected >> 8) & 255)) <= ((tolerance >> 8) & 255)
                && Math.abs((actual & 255) - (expected & 255)) <= (tolerance & 255);
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

    private static LegacyImageMatcher.Pixels imagePixels(BufferedImage image) {
        return new LegacyImageMatcher.Pixels() {
            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int getPixel(int x, int y) { return image.getRGB(x, y); }
        };
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

    private static byte[] runDeviceBytes(String adb, String serial, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(adb);
        command.add("-s");
        command.add(serial);
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("ADB command timed out: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("ADB command failed (" + process.exitValue() + "): "
                    + new String(output, StandardCharsets.UTF_8));
        }
        return output;
    }

    private static String runDeviceCommand(String adb, String serial, String... args) throws Exception {
        return new String(runDeviceBytes(adb, serial, args), StandardCharsets.UTF_8);
    }

    private static BufferedImage readDeviceScreenshot(String adb, String serial) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(
                runDeviceBytes(adb, serial, "exec-out", "screencap", "-p")));
        if (image == null) throw new IOException("ADB returned an unreadable screenshot");
        return image;
    }

    private static long sampledDifference(BufferedImage left, BufferedImage right) {
        if (left.getWidth() != right.getWidth() || left.getHeight() != right.getHeight()) return Long.MAX_VALUE;
        long changed = 0;
        for (int y = 40; y < left.getHeight() - 40; y += 16) {
            for (int x = 40; x < left.getWidth() - 40; x += 16) {
                int a = left.getRGB(x, y), b = right.getRGB(x, y);
                int difference = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))
                        + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255))
                        + Math.abs((a & 255) - (b & 255));
                if (difference > 30) changed++;
            }
        }
        return changed;
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

    private static LuaTable ocrRow(String words, int x, int y) {
        LuaTable row = new LuaTable();
        row.set("words", LuaValue.valueOf(words));
        row.set("x", LuaValue.valueOf(x));
        row.set("y", LuaValue.valueOf(y));
        row.set("score", LuaValue.valueOf(0.96));
        return row;
    }

    private static VarArgFunction noOpFunction() {
        return new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
    }
}
