package com.example.pakredirect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SemanticLoginFlowTest {
    private static final Path FLOW = Paths.get("app", "src", "main", "assets", "script_modules",
            "脚本", "登录无障碍.lua");

    @Test public void retriesStuckConnectingDialogWithoutImageApis() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<String> state = new AtomicReference<>("login");
        List<String> actions = new ArrayList<>();
        List<Long> actionTimes = new ArrayList<>();
        int[] confirms = {0};
        Globals globals = createGlobals(clock, state, actions, actionTimes, confirms);
        loadFlow(globals);

        Varargs result = globals.get("无图登录运行").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(60000), LuaValue.valueOf(2)}));

        assertTrue(result.arg(2).tojstring(), result.arg1().toboolean());
        assertEquals("world", result.arg(2).tojstring());
        assertEquals(Arrays.asList("login", "confirm", "back", "confirm"), actions);
        for (int i = 1; i < actionTimes.size(); i++) {
            if ("confirm".equals(actions.get(i - 1)) && "back".equals(actions.get(i))) continue;
            long delay = actionTimes.get(i) - actionTimes.get(i - 1);
            assertTrue("action delay outside 0.5-1.5 seconds: " + delay, delay >= 500 && delay <= 1500);
        }
    }

    @Test public void failsClosedWhenGameDoesNotPublishSemanticState() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<String> state = new AtomicReference<>("unknown");
        List<String> actions = new ArrayList<>();
        Globals globals = createGlobals(clock, state, actions, new ArrayList<>(), new int[]{0});
        loadFlow(globals);

        Varargs result = globals.get("无图登录运行").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(30000), LuaValue.valueOf(2)}));

        assertFalse(result.arg1().toboolean());
        assertTrue(result.arg(2).tojstring().contains("无障碍状态未知"));
        assertTrue(actions.isEmpty());
        assertEquals(15000L, clock.get());
    }

    @Test public void confirmsSelectedServerBeforeLogin() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<String> state = new AtomicReference<>("server");
        List<String> actions = new ArrayList<>();
        List<Long> actionTimes = new ArrayList<>();
        Globals globals = createGlobals(clock, state, actions, actionTimes, new int[]{0});
        loadFlow(globals);

        Varargs result = globals.get("无图登录运行").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(60000), LuaValue.valueOf(2)}));

        assertTrue(result.arg1().toboolean());
        assertEquals("world", result.arg(2).tojstring());
        assertEquals(Arrays.asList("server_confirm", "login", "confirm", "back", "confirm"), actions);
    }

    @Test public void waitsThroughLoadingLongerThanUnknownStateTimeout() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicReference<String> state = new AtomicReference<>("loading");
        List<String> actions = new ArrayList<>();
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                clock.addAndGet(Math.max(0L, millis.tolong()));
                return LuaValue.NONE;
            }
        });
        globals.set("gameUiState", new ZeroArgFunction() {
            @Override public LuaValue call() {
                if ("loading".equals(state.get()) && clock.get() >= 20000L) state.set("server");
                return LuaValue.valueOf(state.get());
            }
        });
        globals.set("gameUiClick", new OneArgFunction() {
            @Override public LuaValue call(LuaValue action) {
                actions.add(action.tojstring());
                state.set("world");
                return LuaValue.TRUE;
            }
        });
        loadFlow(globals);

        Varargs result = globals.get("无图登录运行").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(60000), LuaValue.valueOf(2)}));

        assertTrue(result.arg(2).tojstring(), result.arg1().toboolean());
        assertEquals("world", result.arg(2).tojstring());
        assertTrue(clock.get() >= 20000L);
        assertEquals(Arrays.asList("server_confirm"), actions);
    }

    private static Globals createGlobals(AtomicLong clock, AtomicReference<String> state,
            List<String> actions, List<Long> actionTimes, int[] confirms) {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(clock.get()); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                clock.addAndGet(Math.max(0L, millis.tolong()));
                return LuaValue.NONE;
            }
        });
        globals.set("gameUiState", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(state.get()); }
        });
        globals.set("gameUiClick", new OneArgFunction() {
            @Override public LuaValue call(LuaValue actionValue) {
                String action = actionValue.tojstring();
                actions.add(action);
                actionTimes.add(clock.get());
                if ("server_confirm".equals(action)) state.set("login");
                else if ("login".equals(action)) state.set("role");
                else if ("confirm".equals(action) && confirms[0]++ == 0) state.set("connecting");
                else if ("confirm".equals(action)) state.set("world");
                else if ("back".equals(action)) state.set("role");
                return LuaValue.TRUE;
            }
        });
        return globals;
    }

    private static void loadFlow(Globals globals) throws Exception {
        String source = Files.readString(FLOW, StandardCharsets.UTF_8);
        globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), FLOW.toString(), globals).call();
    }
}
