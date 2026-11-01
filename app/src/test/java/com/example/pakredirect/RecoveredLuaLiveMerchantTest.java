package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Opt-in device test of one configured red-potion purchase through the Lua merchant route. */
public final class RecoveredLuaLiveMerchantTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(6);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage11", "merchant-live").toAbsolutePath().normalize();

    @Test public void sourceLuaRoutesToNpcAndBuysExactlyOneRedPotion() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        String buyFlag = System.getenv("RYLUX_LIVE_MERCHANT_BUY");
        boolean buyOneRedPotion = "1".equals(buyFlag);
        assumeTrue("Set ADB, RapidOCR Python, evidence, and RYLUX_LIVE_MERCHANT_BUY=0 (route only) or 1 (buy one potion)",
                present(adb) && present(serial) && present(python) && present(evidence)
                        && ("0".equals(buyFlag) || buyOneRedPotion));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-11 merchant-live evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            LegacyLuaConfig.installWorkbenchConfigFlag(globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            // The guest game is already live under BlackBox's proxy Activity;
            // Android's host-package appIsRunning would falsely report it absent.
            globals.set("appIsRunning", new OneArgFunction() {
                @Override public LuaValue call(LuaValue packageName) {
                    try {
                        device.event("guest_process", "confirmed live sandbox guest "
                                + packageName.optjstring(""));
                        return LuaValue.TRUE;
                    } catch (Exception error) { return fail(callbackFailure, error); }
                }
            });
            globals.set("frontAppName", new ZeroArgFunction() {
                @Override public LuaValue call() { return LuaValue.valueOf("com.tepaylink.tamgioiphantranhmobile"); }
            });
            globals.set("游戏包名", LuaValue.valueOf("com.tepaylink.tamgioiphantranhmobile"));
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
            installIme(globals, device, callbackFailure);

            LuaTable config = new LuaTable();
            for (Map.Entry<String, String> entry : LegacyLuaConfig.defaults().entrySet())
                config.set(entry.getKey(), LuaValue.valueOf(entry.getValue()));
            // Temporary test profile: buy one red potion only. All selling and
            // generic purchase paths stay disabled, and the Lua route still
            // requires the live shop and quantity-confirmation UI.
            config.set("自动战斗", LuaValue.valueOf("false"));
            config.set("购买小红", LuaValue.valueOf(buyOneRedPotion ? "true" : "false"));
            config.set("购买红的数量", LuaValue.valueOf("1"));
            config.set("红的数量低于", LuaValue.valueOf("999"));
            config.set("购买小蓝", LuaValue.valueOf("false"));
            config.set("出售全部红", LuaValue.valueOf("false"));
            config.set("出售全部蓝", LuaValue.valueOf("false"));
            config.set("出售装备", LuaValue.valueOf("false"));
            config.set("允许出售物品", LuaValue.valueOf("false"));
            config.set("允许购买物品", LuaValue.valueOf("false"));
            globals.set("界面数据", config);

            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua"))
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);
            if (!buyOneRedPotion) {
                globals.set("去卖货_卖货", new ZeroArgFunction() {
                    @Override public LuaValue call() {
                        try {
                            device.event("merchant_shop", "NPC shop reached; transaction intentionally skipped");
                            return LuaValue.NONE;
                        } catch (Exception error) { return fail(callbackFailure, error); }
                    }
                });
            }
            globals.set("卖货地图", LuaValue.valueOf("崇城野外"));
            globals.set("挂机点地图", LuaValue.valueOf("崇城野外"));
            globals.set("挂机点x", LuaValue.valueOf("100"));
            globals.set("挂机点y", LuaValue.valueOf("200"));

            LiveLuaDeviceHost.Frame initial = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(initial);
            RecoveredLuaLiveActivityPanelTest.writeFrameSummary(device, initial, initialRows, "initial");
            boolean initialHud = RecoveredLuaLiveActivityPanelTest.countHudLabels(initial, initialRows) >= 2;
            boolean initialBackpack = globals.get("药品_识别背包页面").checkfunction().call().toboolean();
            boolean initialMap = !buyOneRedPotion
                    && globals.get("大地图页面已打开").checkfunction().call().toboolean();
            assertTrue("Refusing merchant navigation outside the game HUD, backpack preflight, or route picker",
                    initialHud || (!buyOneRedPotion && (initialBackpack || initialMap)));

            if (!buyOneRedPotion && initialMap) {
                boolean selectedDoctor = globals.get("大地图_点击大夫").checkfunction().call().toboolean();
                RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
                assertTrue("The exact OCR-recognized 大夫 row was not selected", selectedDoctor);
                boolean mapDismissed = false;
                for (int attempt = 0; attempt < 8; attempt++) {
                    Thread.sleep(500);
                    device.invalidate();
                    LiveLuaDeviceHost.Frame afterTap = device.capture();
                    if (!globals.get("大地图页面已打开").checkfunction().call().toboolean()) {
                        mapDismissed = true;
                        device.event("merchant_route", "doctor row tap dismissed the picker; capture="
                                + afterTap.number);
                        break;
                    }
                }
                assertTrue("Tapping 大夫 must start navigation and dismiss the picker", mapDismissed);
                String initialPosition = readMiniMapPosition(device);
                device.event("merchant_route", "position immediately after doctor tap=" + initialPosition);
                boolean moved = false;
                long movementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (initialPosition != null && System.nanoTime() < movementDeadline) {
                    Thread.sleep(2_000);
                    device.invalidate();
                    String position = readMiniMapPosition(device);
                    device.event("merchant_route", "position after doctor tap=" + position);
                    if (position != null && !position.equals(initialPosition)) {
                        moved = true;
                        break;
                    }
                }
                assertTrue("NPC row tap dismissed the picker, but no minimap-coordinate change confirmed that navigation started",
                        moved);
                device.event("result", "PASS source_lua_exact_doctor_row_tap_and_navigation_started; purchase/sale skipped");
                return;
            }

            int redBefore = -1;
            if (buyOneRedPotion) {
                LuaTable beforeCounts = scanPotionCounts(globals, device, callbackFailure, "before");
                redBefore = quantity(beforeCounts, "小红") + quantity(beforeCounts, "大红");
                assertTrue("Cannot test a quantity-triggered purchase without readable red-potion inventory counts",
                        redBefore >= 0);
                globals.set("小红数量", LuaValue.valueOf(quantity(beforeCounts, "小红")));
                globals.set("大红数量", LuaValue.valueOf(quantity(beforeCounts, "大红")));
                globals.set("小蓝数量", LuaValue.valueOf(quantity(beforeCounts, "小蓝")));
                globals.set("大蓝数量", LuaValue.valueOf(quantity(beforeCounts, "大蓝")));
                assertTrue("The one-potion test threshold must be above the actual red-potion stock",
                        redBefore < 999);
                device.event("merchant_stock", "redBefore=" + redBefore + "; purchase=1; sale=false");
            }

            device.event("merchant_config", "temporary allowlist: one red potion; sale and generic purchase disabled");

            globals.get("去卖货").checkfunction().call();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            LiveLuaDeviceHost.Frame shop = device.capture();
            boolean shopVisible = globals.get("找到").checkfunction()
                    .invoke(globals.get("tz").get("商店页面")).arg1().toboolean();
            device.event("merchant_shop", "shopVisible=" + shopVisible + " capture=" + shop.number);
            assertTrue("Lua returned without a positively recognized merchant shop; inspect route and dialog evidence",
                    shopVisible);

            globals.get("通用关闭").checkfunction().call();
            device.invalidate();
            LiveLuaDeviceHost.Frame afterShopClose = device.capture();
            assertTrue("The merchant flow must return to the localized world HUD",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(afterShopClose,
                            device.rows(afterShopClose)) >= 2);
            if (!buyOneRedPotion) {
                device.event("result", "PASS source_lua_backpack_sort_and_doctor_route; transactions skipped");
                return;
            }
            device.tap(902, 663, "open backpack after red-potion purchase");
            Thread.sleep(500);
            device.invalidate();
            LuaTable afterCounts = scanPotionCounts(globals, device, callbackFailure, "after");
            int redAfter = quantity(afterCounts, "小红") + quantity(afterCounts, "大红");
            assertTrue("Exactly one allowlisted red potion should be reflected in the localized inventory: "
                    + redBefore + " -> " + redAfter, redAfter == redBefore + 1);
            device.event("result", "PASS source_lua_npc_route_and_one_red_potion_purchase stock="
                    + redBefore + "->" + redAfter);
        }
    }

    private static LuaTable scanPotionCounts(Globals globals, LiveLuaDeviceHost device,
                                              AtomicReference<Throwable> callbackFailure, String phase)
            throws Exception {
        device.invalidate();
        LiveLuaDeviceHost.Frame before = device.capture();
        if (RecoveredLuaLiveActivityPanelTest.countHudLabels(before, device.rows(before)) >= 2) {
            device.tap(902, 663, "open backpack for " + phase + " red-potion count");
            Thread.sleep(500);
            device.invalidate();
        }
        boolean backpack = globals.get("药品_识别背包页面").checkfunction().call().toboolean();
        RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
        assertTrue("Localized backpack page was not confirmed for " + phase + " inventory count", backpack);
        Varargs scan = globals.get("检测背包_识别红蓝数量").checkfunction().invoke();
        RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
        LuaTable counts = scan.arg1().checktable();
        assertTrue("Potion stack OCR was not reliable for " + phase + " count scan", scan.arg(2).toboolean());
        device.event("merchant_stock", phase + " red=" + quantity(counts, "小红") + "+"
                + quantity(counts, "大红") + " blue=" + quantity(counts, "小蓝") + "+"
                + quantity(counts, "大蓝"));
        if ("before".equals(phase)) {
            device.tap(1244, 104, "close verified backpack before merchant route");
            Thread.sleep(500);
            device.invalidate();
            LiveLuaDeviceHost.Frame hud = device.capture();
            assertTrue("Localized world HUD did not return before merchant routing",
                    RecoveredLuaLiveActivityPanelTest.countHudLabels(hud, device.rows(hud)) >= 2);
        }
        return counts;
    }

    private static String readMiniMapPosition(LiveLuaDeviceHost device) throws Exception {
        LiveLuaDeviceHost.Frame frame = device.capture();
        for (LiveLuaDeviceHost.Row row : device.rowsInReferenceRegion(frame, 1100, 140, 1225, 195)) {
            java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
                    .matcher(row.text.replace('／', '/'));
            if (match.find() && row.confidence >= 0.7)
                return match.group(1) + "/" + match.group(2);
        }
        return null;
    }

    private static int quantity(LuaTable counts, String key) {
        String value = counts.get(key).optjstring("0").trim();
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static LuaValue fail(AtomicReference<Throwable> failure, Throwable error) {
        failure.compareAndSet(null, error);
        throw new org.luaj.vm2.LuaError(error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static void installIme(Globals globals, LiveLuaDeviceHost device,
                                   AtomicReference<Throwable> callbackFailure) {
        LuaTable ime = new LuaTable();
        ime.set("lock", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.NONE; } });
        ime.set("unlock", new ZeroArgFunction() { @Override public LuaValue call() { return LuaValue.NONE; } });
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                try { device.inputText(value.tojstring()); return LuaValue.TRUE; }
                catch (Exception error) {
                    callbackFailure.compareAndSet(null, error);
                    throw new org.luaj.vm2.LuaError(error.getClass().getSimpleName() + ": " + error.getMessage());
                }
            }
        });
        globals.set("imeLib", ime);
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
}
