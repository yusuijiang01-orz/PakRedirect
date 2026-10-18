package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LuaInstructionGateTest {
    @Test public void pauseAndStopInterruptPureLuaBusyLoop() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger ticks = addPulse(globals);
        LuaInstructionGate gate = new LuaInstructionGate(globals, control);
        LuaFunction loop = (LuaFunction) globals.load("while true do pulse() end", "busy-loop");
        CountDownLatch started = new CountDownLatch(1);
        control.start();

        Thread runner = new Thread(() -> {
            control.attachCurrentThread();
            started.countDown();
            try {
                gate.invoke(loop, LuaValue.NONE);
                control.complete();
            } catch (ScriptExecutionControl.StopRequestedException stopped) {
                control.complete();
            } finally {
                control.detachCurrentThread();
            }
        }, "lua-busy-loop-test");
        runner.start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        awaitTicks(ticks, 100, 2_000);
        assertTrue(control.pause());
        Thread.sleep(150);
        int pausedAt = ticks.get();
        Thread.sleep(150);
        assertEquals("pure Lua instructions must stop while paused", pausedAt, ticks.get());
        assertTrue(control.resume());
        awaitTicks(ticks, pausedAt + 100, 2_000);

        assertTrue(control.requestStop());
        runner.join(1_000);
        assertFalse("stop must end the pure Lua loop", runner.isAlive());
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }

    @Test public void stopThreadInterruptsPureLuaWorkerWithoutStoppingMainRuntime() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger ticks = addPulse(globals);
        LuaInstructionGate gate = new LuaInstructionGate(globals, control);
        LuaFunction luaWorker = (LuaFunction) globals.load("while true do pulse() end", "worker-loop");
        CountDownLatch started = new CountDownLatch(1);
        control.start();

        Thread worker = new Thread(() -> {
            control.attachCurrentThread();
            started.countDown();
            try { gate.invoke(luaWorker, LuaValue.NONE); }
            catch (ScriptExecutionControl.StopRequestedException expected) { }
            finally { control.detachCurrentThread(); }
        }, "lua-worker-loop-test");
        worker.start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        awaitTicks(ticks, 100, 2_000);
        control.cancelWorker(worker);
        worker.join(1_000);
        assertFalse("stopThread must end the pure Lua worker", worker.isAlive());
        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        assertTrue(control.requestStop());
        control.complete();
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }

    @Test public void queuedLuaWorkersAndActiveLoopBothStop() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        Globals globals = JsePlatform.standardGlobals();
        AtomicInteger firstTicks = new AtomicInteger();
        AtomicInteger secondTicks = new AtomicInteger();
        globals.set("firstPulse", pulse(firstTicks));
        globals.set("secondPulse", pulse(secondTicks));
        LuaInstructionGate gate = new LuaInstructionGate(globals, control);
        LuaFunction firstLoop = (LuaFunction) globals.load("while true do firstPulse() end", "first-worker");
        LuaFunction secondLoop = (LuaFunction) globals.load("while true do secondPulse() end", "second-worker");
        CountDownLatch started = new CountDownLatch(2);
        control.start();
        Thread first = runner(control, gate, firstLoop, started);
        Thread second = runner(control, gate, secondLoop, started);
        first.start();
        second.start();

        assertTrue(started.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (firstTicks.get() + secondTicks.get() < 100 && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("at least one Lua worker must start", firstTicks.get() + secondTicks.get() >= 100);
        assertTrue(control.requestStop());
        first.join(1_000);
        second.join(1_000);
        assertFalse("first Lua worker must stop after global stop", first.isAlive());
        assertFalse("second Lua worker must stop after global stop", second.isAlive());
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }

    private static AtomicInteger addPulse(Globals globals) {
        AtomicInteger ticks = new AtomicInteger();
        globals.set("pulse", pulse(ticks));
        return ticks;
    }

    private static ZeroArgFunction pulse(AtomicInteger ticks) {
        return new ZeroArgFunction() {
            @Override public LuaValue call() { ticks.incrementAndGet(); return LuaValue.NONE; }
        };
    }

    private static Thread runner(ScriptExecutionControl control, LuaInstructionGate gate,
                                 LuaFunction function, CountDownLatch started) {
        return new Thread(() -> {
            control.attachCurrentThread();
            started.countDown();
            try { gate.invoke(function, LuaValue.NONE); }
            catch (ScriptExecutionControl.StopRequestedException expected) { }
            finally { control.detachCurrentThread(); }
        });
    }

    private static void awaitTicks(AtomicInteger ticks, int expected, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (ticks.get() < expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Lua loop did not start", ticks.get() >= expected);
    }
}
