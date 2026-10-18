package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ScriptExecutionControlTest {
    @Test public void pauseBlocksHostProgressUntilResume() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        AtomicInteger steps = new AtomicInteger();
        CountDownLatch firstStep = new CountDownLatch(1);
        control.start();
        Thread runner = new Thread(() -> {
            try {
                control.attachCurrentThread();
                steps.incrementAndGet();
                firstStep.countDown();
                control.sleep(80);
                steps.incrementAndGet();
                control.complete();
            } catch (ScriptExecutionControl.StopRequestedException ignored) {
                control.complete();
            }
        });
        runner.start();
        assertTrue(firstStep.await(1, TimeUnit.SECONDS));
        assertTrue(control.pause());
        Thread.sleep(150);
        assertEquals(1, steps.get());
        assertTrue(control.resume());
        runner.join(1000);
        assertFalse(runner.isAlive());
        assertEquals(2, steps.get());
        assertEquals(ScriptExecutionControl.State.COMPLETED, control.getState());
    }

    @Test public void stopInterruptsLongHostSleepPromptly() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        CountDownLatch enteredSleep = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        control.start();
        Thread runner = new Thread(() -> {
            try {
                control.attachCurrentThread();
                enteredSleep.countDown();
                control.sleep(30_000);
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                stopped.countDown();
                control.complete();
            }
        });
        runner.start();
        assertTrue(enteredSleep.await(1, TimeUnit.SECONDS));
        assertTrue(control.requestStop());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        runner.join(1000);
        assertFalse(runner.isAlive());
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }

    @Test public void cancellingOneLuaWorkerKeepsRuntimeRunning() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        CountDownLatch enteredSleep = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        control.start();
        Thread worker = new Thread(() -> {
            control.attachCurrentThread();
            try {
                enteredSleep.countDown();
                control.sleep(30_000);
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                stopped.countDown();
            } finally {
                control.detachCurrentThread();
            }
        });
        worker.start();
        assertTrue(enteredSleep.await(1, TimeUnit.SECONDS));
        control.cancelWorker(worker);
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        assertTrue(control.requestStop());
        control.complete();
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }
}
