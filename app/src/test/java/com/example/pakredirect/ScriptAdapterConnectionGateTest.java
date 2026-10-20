package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class ScriptAdapterConnectionGateTest {
    @Test public void returnsWhenAdapterReconnects() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        AtomicReference<String> adapter = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        control.start();
        Thread reconnect = new Thread(() -> {
            try {
                control.attachCurrentThread();
                waiting.countDown();
                control.sleep(120);
                adapter.set("connected");
            } finally {
                control.detachCurrentThread();
            }
        });
        reconnect.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        String connected = ScriptAdapterConnectionGate.await(adapter::get, control,
                1_000, "reconnect timed out");
        reconnect.join(1_000);
        assertEquals("connected", connected);
        assertTrue(!reconnect.isAlive());
    }

    @Test public void timesOutInsteadOfWaitingForever() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ScriptAdapterConnectionGate.await(() -> null, control,
                        120, "reconnect timed out"));
        assertEquals("reconnect timed out", error.getMessage());
    }

    @Test public void stopInterruptsReconnectWaitPromptly() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        control.start();
        Thread worker = new Thread(() -> {
            try {
                control.attachCurrentThread();
                entered.countDown();
                ScriptAdapterConnectionGate.await(() -> null, control,
                        30_000, "reconnect timed out");
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                stopped.countDown();
                control.complete();
            } finally {
                control.detachCurrentThread();
            }
        });
        worker.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertTrue(control.requestStop());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        worker.join(1_000);
        assertTrue(!worker.isAlive());
        assertEquals(ScriptExecutionControl.State.STOPPED, control.getState());
    }

    @Test public void pauseFreezesReconnectPollingUntilResume() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        AtomicReference<String> adapter = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        control.start();
        Thread worker = new Thread(() -> {
            try {
                control.attachCurrentThread();
                waiting.countDown();
                connected.countDown();
                ScriptAdapterConnectionGate.await(adapter::get, control,
                        5_000, "reconnect timed out");
            } finally {
                control.detachCurrentThread();
            }
        });
        worker.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        assertTrue(connected.await(1, TimeUnit.SECONDS));
        assertTrue(control.pause());
        adapter.set("connected");
        Thread.sleep(150);
        assertTrue("A paused script must not advance through the reconnect gate", worker.isAlive());
        assertTrue(control.resume());
        worker.join(1_000);
        assertTrue(!worker.isAlive());
        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        control.complete();
    }
}
