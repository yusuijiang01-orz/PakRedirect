package com.example.pakredirect;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaThread;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.concurrent.locks.ReentrantLock;

/** Adds cooperative pause/stop checkpoints inside Lua bytecode, including tight loops. */
final class LuaInstructionGate {
    private static final int INSTRUCTIONS_PER_CHECKPOINT = 256;

    private final ScriptExecutionControl control;
    private final LuaValue setHook;
    private final ReentrantLock vmLock = new ReentrantLock(true);
    private final Thread primaryJavaThread;
    private final LuaThread primaryLuaThread;
    private final ThreadLocal<LuaThread> workerLuaThread = new ThreadLocal<LuaThread>() {
        @Override protected LuaThread initialValue() {
            LuaThread thread = new LuaThread(globals);
            install(thread);
            return thread;
        }
    };
    private final Globals globals;
    private final LuaValue hook = new ZeroArgFunction() {
        @Override public LuaValue call() {
            try { control.checkpoint(); }
            catch (ScriptExecutionControl.StopRequestedException stopped) { throw new StopSignal(); }
            return LuaValue.NONE;
        }
    };

    LuaInstructionGate(Globals globals, ScriptExecutionControl control) {
        this.globals = globals;
        this.control = control;
        this.primaryJavaThread = Thread.currentThread();
        globals.load(new DebugLib());
        LuaValue debug = globals.get("debug");
        if (!debug.istable() || !debug.get("sethook").isfunction()) {
            throw new IllegalStateException("LuaJ instruction hook is unavailable");
        }
        setHook = debug.get("sethook");
        // Keep the hook private so recovered scripts cannot disable cancellation.
        globals.set("debug", LuaValue.NIL);
        primaryLuaThread = globals.running;
        install(primaryLuaThread);
    }

    LuaValue invoke(LuaFunction function, Varargs args) {
        try { vmLock.lockInterruptibly(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            control.checkpoint();
            throw new IllegalStateException("Interrupted while waiting for the Lua runtime", interrupted);
        }
        LuaThread previous = globals.running;
        try {
            globals.running = Thread.currentThread() == primaryJavaThread
                    ? primaryLuaThread : workerLuaThread.get();
            try { return function.invoke(args).arg1(); }
            catch (StopSignal stop) {
                control.checkpoint();
                throw stop;
            }
        } finally {
            globals.running = previous;
            vmLock.unlock();
        }
    }

    void sleep(long millis) {
        int holds = releaseVmLock();
        try { control.sleep(millis); }
        catch (ScriptExecutionControl.StopRequestedException stopped) { throw new StopSignal(); }
        finally { reacquireVmLock(holds); }
    }

    private void install(LuaThread thread) {
        setHook.invoke(LuaValue.varargsOf(new LuaValue[]{thread, hook, LuaValue.EMPTYSTRING,
                LuaValue.valueOf(INSTRUCTIONS_PER_CHECKPOINT)}));
    }

    private int releaseVmLock() {
        int holds = vmLock.getHoldCount();
        for (int i = 0; i < holds; i++) vmLock.unlock();
        return holds;
    }

    private void reacquireVmLock(int holds) {
        for (int i = 0; i < holds; i++) vmLock.lock();
    }

    /** LuaJ wraps RuntimeException from Java callbacks, but lets Error escape Lua pcall. */
    private static final class StopSignal extends Error {
        StopSignal() { super("Lua cancellation requested", null, false, false); }
    }
}
