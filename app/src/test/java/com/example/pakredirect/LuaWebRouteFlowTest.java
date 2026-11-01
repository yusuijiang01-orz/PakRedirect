package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

public final class LuaWebRouteFlowTest {
    @Test public void exactSearchAndHeaderApprovalPrecedeCoordinatesAndSubmit() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(321));
        globals.set("routeTargetY", LuaValue.valueOf(654));
        loadRouteModule(globals);
        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(321), LuaValue.valueOf(654),
                LuaValue.valueOf("test-route")
        }));

        assertTrue(result.arg1().toboolean());
        assertTrue(events.contains("map:崇城野外:false:true"));
        assertTrue(events.indexOf("status:selected") < events.indexOf("text:321"));
        assertTrue(events.indexOf("status:selected") < events.indexOf("text:654"));
        assertTrue(events.indexOf("tap:@@去目的地") < events.indexOf("status:submitted"));
        assertTrue(events.indexOf("status:submitted") < events.indexOf("status:moving"));
        assertEquals("status:arrived", events.get(events.size() - 1));
    }

    @Test public void everyCatalogMapUsesStrictLocalizedSearchBeforeSubmitting() throws Exception {
        for (String map : ScriptMapCatalog.MAPS) {
            List<String> events = new ArrayList<>();
            Globals globals = globals(events, true);
            globals.set("routeExpectedMap", LuaValue.valueOf(map));
            globals.set("routeTargetX", LuaValue.valueOf(100));
            globals.set("routeTargetY", LuaValue.valueOf(200));
            loadRouteModule(globals);
            Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                    LuaValue.valueOf(map), LuaValue.valueOf(100), LuaValue.valueOf(200),
                    LuaValue.valueOf("route-" + map)
            }));

            assertTrue("route should submit for " + map, result.arg1().toboolean());
            assertTrue("strict Chinese exact search must be used for " + map,
                    events.contains("map:" + map + ":false:true"));
            assertTrue("route should submit only after selecting " + map,
                    events.indexOf("status:selected") < events.indexOf("tap:@@去目的地"));
        }
    }

    @Test public void stationaryAfterSubmissionFailsWithoutClaimingArrival() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(100));
        globals.set("routeTargetY", LuaValue.valueOf(200));
        globals.set("routeStationary", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(100), LuaValue.valueOf(200),
                LuaValue.valueOf("stationary-route")
        }));

        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring().contains("未观察到小地图坐标移动"));
        assertTrue(events.contains("status:submitted"));
        assertEquals("status:failed", events.get(events.size() - 1));
        assertFalse(events.contains("status:moving"));
        assertFalse(events.contains("status:arrived"));
    }

    @Test public void successToastAcceptsStationaryNearbyHudOnRequestedMapAlias() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(196));
        globals.set("routeTargetY", LuaValue.valueOf(204));
        globals.set("routeStationary", LuaValue.TRUE);
        globals.set("routeSuccessToast", LuaValue.TRUE);
        globals.set("routeHudMap", LuaValue.valueOf("Sùng thành"));
        LuaTable maps = new LuaTable();
        maps.set(1, LuaValue.valueOf("崇城野外,sùng thành"));
        globals.set("中越大地图数组", maps);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(196), LuaValue.valueOf(204),
                LuaValue.valueOf("stationary-success")
        }));

        assertTrue(result.arg1().toboolean());
        assertTrue(events.contains("status:submitted"));
        assertEquals("status:arrived", events.get(events.size() - 1));
        assertFalse(events.contains("status:moving"));
    }

    @Test public void successToastAppearingAfterFirstReadStillAcceptsNearbyHud() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(196));
        globals.set("routeTargetY", LuaValue.valueOf(204));
        globals.set("routeStationary", LuaValue.TRUE);
        globals.set("routeSuccessToast", LuaValue.TRUE);
        globals.set("routeDelayedSuccessToast", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(196), LuaValue.valueOf(204),
                LuaValue.valueOf("delayed-success")
        }));

        assertTrue(result.arg1().toboolean());
        assertEquals("status:arrived", events.get(events.size() - 1));
        assertFalse(events.contains("status:moving"));
    }

    @Test public void successToastOnWrongHudMapDoesNotClaimArrival() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(196));
        globals.set("routeTargetY", LuaValue.valueOf(204));
        globals.set("routeStationary", LuaValue.TRUE);
        globals.set("routeSuccessToast", LuaValue.TRUE);
        globals.set("routeWrongMap", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(196), LuaValue.valueOf(204),
                LuaValue.valueOf("wrong-map-success")
        }));

        assertFalse(result.arg1().toboolean());
        assertEquals("status:failed", events.get(events.size() - 1));
        assertFalse(events.contains("status:arrived"));
    }

    @Test public void successToastOutsideCoordinateToleranceDoesNotClaimArrival() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(200));
        globals.set("routeTargetY", LuaValue.valueOf(204));
        globals.set("routeStationary", LuaValue.TRUE);
        globals.set("routeSuccessToast", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(200), LuaValue.valueOf(204),
                LuaValue.valueOf("far-success")
        }));

        assertFalse(result.arg1().toboolean());
        assertEquals("status:failed", events.get(events.size() - 1));
        assertFalse(events.contains("status:arrived"));
    }

    @Test public void gameCannotReachToastFailsImmediatelyAfterCheckmark() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(100));
        globals.set("routeTargetY", LuaValue.valueOf(200));
        globals.set("routeRejected", LuaValue.TRUE);
        globals.set("routeSuccessToast", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(100), LuaValue.valueOf(200),
                LuaValue.valueOf("rejected-route")
        }));

        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring().contains("此处无法到达"));
        assertTrue(events.contains("tap:@@去目的地"));
        assertTrue(events.contains("status:submitted"));
        assertEquals("status:failed", events.get(events.size() - 1));
        assertFalse(events.contains("status:moving"));
        assertFalse(events.contains("status:arrived"));
    }

    @Test public void reachingCoordinatesOnWrongMapDoesNotClaimArrival() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        globals.set("routeExpectedMap", LuaValue.valueOf("崇城野外"));
        globals.set("routeTargetX", LuaValue.valueOf(100));
        globals.set("routeTargetY", LuaValue.valueOf(200));
        globals.set("routeWrongMap", LuaValue.TRUE);
        loadRouteModule(globals);

        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("崇城野外"), LuaValue.valueOf(100), LuaValue.valueOf(200),
                LuaValue.valueOf("wrong-map-route")
        }));

        assertFalse(result.arg1().toboolean());
        assertTrue(events.contains("status:moving"));
        assertEquals("status:failed", events.get(events.size() - 1));
        assertFalse(events.contains("status:arrived"));
    }

    @Test public void failedExactMapVerificationNeverEntersCoordinatesOrSubmits() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, false);
        loadRouteModule(globals);
        Varargs result = globals.get("执行网页路线").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf("北海"), LuaValue.valueOf(12), LuaValue.valueOf(34),
                LuaValue.valueOf("unverified")
        }));

        assertFalse(result.arg1().toboolean());
        assertTrue(events.contains("status:failed"));
        assertFalse(events.contains("text:12"));
        assertFalse(events.contains("text:34"));
        assertFalse(events.contains("tap:@@去目的地"));
    }

    @Test public void webProfileUpdatesLiveSettingsAndDerivedLocalizedMaps() throws Exception {
        List<String> events = new ArrayList<>();
        Globals globals = globals(events, true);
        LuaTable data = new LuaTable();
        data.set("挂机点地图", "0");
        data.set("卖货地图", "0");
        data.set("队长名", "");
        data.set("队员名", "");
        data.set("是否队长", "0");
        data.set("开启vpn", "false");
        data.set("检测修理装备", "true");
        data.set("保留蓝条数量", "4");
        data.set("虚拟屏幕DPI", "320");
        globals.set("界面数据", data);
        globals.set("保留蓝条数量", LuaValue.valueOf(4));
        LuaTable maps = new LuaTable();
        maps.set(1, LuaValue.valueOf("崇城野外,sùng thành"));
        maps.set(2, LuaValue.valueOf("北海,bắc hải"));
        globals.set("中越大地图数组", maps);
        globals.set("splitStr", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                String[] parts = args.arg(1).tojstring().split(java.util.regex.Pattern.quote(args.arg(2).tojstring()), -1);
                LuaTable result = new LuaTable();
                for (int i = 0; i < parts.length; i++) result.set(i + 1, LuaValue.valueOf(parts[i]));
                return result;
            }
        });
        LuaTable request = new LuaTable();
        request.set("requestId", LuaValue.valueOf("profile-test"));
        LuaTable fields = new LuaTable();
        fields.set("挂机点地图", LuaValue.valueOf("1"));
        fields.set("卖货地图", LuaValue.valueOf("0"));
        fields.set("挂机点x", LuaValue.valueOf("456"));
        fields.set("挂机点y", LuaValue.valueOf("789"));
        fields.set("自动战斗", LuaValue.valueOf("true"));
        fields.set("是否队长", LuaValue.valueOf("1"));
        fields.set("队长名", LuaValue.valueOf("甲\n乙"));
        fields.set("开启vpn", LuaValue.valueOf("true"));
        fields.set("保留蓝条数量", LuaValue.valueOf("9"));
        fields.set("虚拟屏幕宽度", LuaValue.valueOf("1080"));
        fields.set("虚拟屏幕DPI", LuaValue.valueOf("480"));
        request.set("values", fields);
        globals.set("pollWebProfileRequest", new ZeroArgFunction() {
            private boolean sent;
            @Override public LuaValue call() { if (sent) return LuaValue.NIL; sent = true; return request; }
        });
        globals.set("updateWebProfileRequest", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                events.add("profile-status:" + args.arg(2).tojstring());
                return LuaValue.NONE;
            }
        });
        loadRouteModule(globals);

        assertTrue(globals.get("处理网页方案队列").call().toboolean());
        assertEquals("北海", globals.get("挂机点地图").tojstring());
        assertEquals("崇城野外", globals.get("卖货地图").tojstring());
        assertEquals("456", globals.get("挂机点x").tojstring());
        assertEquals("true", data.get("自动战斗").tojstring());
        assertEquals(false, globals.get("是队长").toboolean());
        assertEquals("true", data.get("开启vpn").tojstring());
        assertEquals("1080", data.get("虚拟屏幕宽度").tojstring());
        assertEquals("480", data.get("虚拟屏幕DPI").tojstring());
        assertEquals(true, globals.get("开启vpn").toboolean());
        assertEquals(9, globals.get("保留蓝条数量").toint());
        assertEquals("9", data.get("保留蓝条数量").tojstring());
        assertTrue(events.contains("profile-status:applied"));
    }

    private static Globals globals(List<String> events, boolean mapVerified) {
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger positionReads = new AtomicInteger();
        AtomicInteger fakeClock = new AtomicInteger();
        AtomicInteger pixelReads = new AtomicInteger();
        AtomicInteger toastReads = new AtomicInteger();
        globals.set("_ENV", globals);
        globals.set("requireWebRouteDisplay", new ZeroArgFunction() {
            @Override public LuaValue call() { events.add("display-ok"); return LuaValue.TRUE; }
        });
        globals.set("大地图页面已打开", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.TRUE; }
        });
        globals.set("大地图页面中文", LuaValue.TRUE);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable rows = new LuaTable();
                LuaTable row = new LuaTable();
                if (args.arg(1).toint() == 350) {
                    boolean delayed = globals.get("routeDelayedSuccessToast").toboolean()
                            && toastReads.incrementAndGet() == 1;
                    row.set("words", globals.get("routeRejected").toboolean()
                            ? LuaValue.valueOf("此处无法到达! 已到目的地")
                            : globals.get("routeSuccessToast").toboolean() && !delayed
                                    ? LuaValue.valueOf("已到目的地") : LuaValue.valueOf(""));
                    row.set("score", LuaValue.valueOf(0.95));
                } else if (args.arg(1).toint() >= 1110) {
                    int x = positionReads.incrementAndGet() == 1 || globals.get("routeStationary").toboolean()
                            ? 195 : globals.get("routeTargetX").toint();
                    int y = positionReads.get() == 1 || globals.get("routeStationary").toboolean()
                            ? 204 : globals.get("routeTargetY").toint();
                    row.set("words", LuaValue.valueOf(x + "/" + y));
                    row.set("score", LuaValue.valueOf(0.95));
                } else if (args.arg(1).toint() >= 1040) {
                    row.set("words", globals.get("routeWrongMap").toboolean()
                            ? LuaValue.valueOf("别的地图")
                            : globals.get("routeHudMap").isnil()
                                    ? globals.get("routeExpectedMap") : globals.get("routeHudMap"));
                    row.set("score", LuaValue.valueOf(0.95));
                } else if (args.arg(1).toint() >= 600) {
                    row.set("words", globals.get("routeExpectedMap"));
                    row.set("score", LuaValue.valueOf(0.95));
                    row.set("x", LuaValue.valueOf(800));
                    row.set("y", LuaValue.valueOf(43));
                } else {
                    row.set("words", LuaValue.valueOf("NPC"));
                    row.set("x", LuaValue.valueOf(80));
                    row.set("y", LuaValue.valueOf(38));
                }
                rows.set(1, row);
                return rows;
            }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.FALSE; }
        });
        globals.set("大地图_输入大地图", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                events.add("map:" + args.arg(1).tojstring() + ":" + args.arg(2).tojstring()
                        + ":" + args.arg(3).tojstring());
                return LuaValue.valueOf(mapVerified);
            }
        });
        globals.set("找到", new OneArgFunction() {
            @Override public LuaValue call(LuaValue template) { return LuaValue.FALSE; }
        });
        globals.set("点击", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                events.add("tap:" + args.arg(1).tojstring());
                return LuaValue.TRUE;
            }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) { return LuaValue.NONE; }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue ms) {
                fakeClock.addAndGet(Math.max(0, ms.toint()));
                return LuaValue.NONE;
            }
        });
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(fakeClock.get()); }
        });
        globals.set("keepCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int read = pixelReads.incrementAndGet();
                boolean stationary = globals.get("routeStationary").toboolean();
                return LuaValue.valueOf(stationary || read <= 130 ? 0x123456 : 0x654321);
            }
        });
        LuaTable tz = new LuaTable();
        tz.set("自动挂机按钮_已开启", new LuaTable());
        tz.set("自动挂机按钮_未开启", new LuaTable());
        globals.set("tz", tz);
        LuaTable ime = new LuaTable();
        ime.set("lock", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        ime.set("unlock", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.NONE; }
        });
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                events.add("text:" + value.tojstring());
                return LuaValue.TRUE;
            }
        });
        globals.set("imeLib", ime);
        globals.set("updateWebRouteRequest", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                events.add("status:" + args.arg(2).tojstring());
                return LuaValue.NONE;
            }
        });
        return globals;
    }

    private static void loadRouteModule(Globals globals) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path module = cwd.resolve("src/main/assets/script_modules/脚本/网页路线.lua");
        if (!Files.isRegularFile(module)) {
            module = cwd.resolve("app/src/main/assets/script_modules/脚本/网页路线.lua");
        }
        String source = Files.readString(module, StandardCharsets.UTF_8);
        globals.load(source, "网页路线.lua").call();
    }
}
