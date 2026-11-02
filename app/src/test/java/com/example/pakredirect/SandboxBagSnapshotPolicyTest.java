package com.example.pakredirect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Deterministic source-Lua checks for the exact 610 memory profile and doctor triggers. */
public final class SandboxBagSnapshotPolicyTest {
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();

    @Test public void onlyValidated610BuildIsAllowed() {
        assertTrue(SandboxBagMemoryProfile.isSupported(SandboxBagMemoryProfile.GAME_610_BUILD_ID));
        assertFalse(SandboxBagMemoryProfile.isSupported("71f7482f5ebf0ea60b50e0f69a3edf04b4bc192f"));
        assertFalse(SandboxBagMemoryProfile.isSupported("unknown-build"));
    }

    @Test public void lowFreeSlotsTriggersDoctorBeforeOpeningBackpack() throws Exception {
        Globals globals = runtimeWithSnapshot(2, 120, 200);
        LuaValue result = globals.get("检测背包").call();
        assertTrue("the Lua state machine must route to the doctor when <= 2 slots remain",
                result.isboolean() && !result.toboolean());
        assertTrue("memory trigger must not open the bag UI", globals.get("testMainPageCalls").toint() == 0);
    }

    @Test public void lowRemainingWeightTriggersDoctor() throws Exception {
        Globals globals = runtimeWithSnapshot(10, 197, 200);
        LuaValue result = globals.get("检测背包").call();
        assertTrue("3 weight remaining meets the configured threshold", result.isboolean() && !result.toboolean());
    }

    @Test public void safeSnapshotKeepsFarmingWithoutOpeningBagOnThisInterval() throws Exception {
        Globals globals = runtimeWithSnapshot(3, 100, 200);
        LuaValue result = globals.get("检测背包").call();
        assertTrue("safe memory snapshot must not dispatch the doctor trip", result.isboolean() && result.toboolean());
        assertTrue("the routine should not open the bag before its next configured interval",
                globals.get("testMainPageCalls").toint() == 0);
    }

    @Test public void unsupported587SnapshotCannotTriggerDoctor() throws Exception {
        Globals globals = runtimeWithUnsupportedSnapshot();
        LuaValue result = globals.get("检测背包").call();
        assertTrue("an unsupported older build must not be treated as bag data",
                result.isboolean() && result.toboolean());
        assertTrue("unsupported memory data must not cause an input action",
                globals.get("testMainPageCalls").toint() == 0);
    }

    private static Globals runtimeWithSnapshot(int freeSlots, int currentWeight, int maxWeight)
            throws IOException {
        return runtimeWithSnapshot(true, SandboxBagMemoryProfile.GAME_610_BUILD_ID,
                freeSlots, currentWeight, maxWeight);
    }

    private static Globals runtimeWithUnsupportedSnapshot() throws IOException {
        return runtimeWithSnapshot(false, "71f7482f5ebf0ea60b50e0f69a3edf04b4bc192f",
                0, 999, 1);
    }

    private static Globals runtimeWithSnapshot(boolean supported, String buildId, int freeSlots,
                                               int currentWeight, int maxWeight) throws IOException {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("utf8", LegacyLuaCompat.createUtf8Compat());
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(10_000); }
        });
        for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
            RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                    globals, SCRIPT_ROOT.resolve(module), module);
        }
        globals.set("背包保留格子", LuaValue.valueOf(2));
        globals.set("背包保留重量", LuaValue.valueOf(3));
        globals.set("上次检测背包", LuaValue.valueOf(9_999));
        globals.set("testMainPageCalls", LuaValue.valueOf(0));
        globals.set("toast", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        globals.set("getSandboxBagSnapshot", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                SandboxBagSnapshot snapshot = new SandboxBagSnapshot(supported, "OK",
                        buildId, freeSlots, currentWeight,
                        maxWeight, 100);
                LuaTable value = new LuaTable();
                value.set("supported", LuaValue.valueOf(supported));
                value.set("freeSlots", LuaValue.valueOf(freeSlots));
                value.set("currentWeight", LuaValue.valueOf(currentWeight));
                value.set("maxWeight", LuaValue.valueOf(maxWeight));
                value.set("ageMs", LuaValue.valueOf(0));
                value.set("buildId", LuaValue.valueOf(snapshot.buildId));
                value.set("shouldVisitDoctor", LuaValue.valueOf(supported && snapshot.shouldVisitDoctor(
                        args.arg(1).optint(2), args.arg(2).optint(3))));
                return value;
            }
        });
        globals.set("在主页面", new ZeroArgFunction() {
            @Override public LuaValue call() {
                globals.set("testMainPageCalls", LuaValue.valueOf(
                        globals.get("testMainPageCalls").toint() + 1));
                return LuaValue.TRUE;
            }
        });
        globals.set("sleep", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        });
        return globals;
    }
}
