package com.example.pakredirect;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ScriptForegroundGateTest {
    @Test public void readyTargetPassesOnlyAfterCheckpoint() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ScriptForegroundGate.await(() -> true, checks::incrementAndGet, 100,
                "target did not become foreground");
        assertEquals(1, checks.get());
    }

    @Test public void waitsUntilTheTargetBecomesForeground() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ScriptForegroundGate.await(() -> checks.get() >= 3,
                checks::incrementAndGet, 5_000, "target did not become foreground");
        assertEquals(3, checks.get());
    }

    @Test public void recoveryInvalidatesOldFrameOnlyWhenForegroundWasLost() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        AtomicInteger waiting = new AtomicInteger();
        AtomicInteger resumed = new AtomicInteger();
        boolean recovered = ScriptForegroundGate.awaitRecovery(
                () -> checks.incrementAndGet() >= 3, () -> { },
                waiting::incrementAndGet, resumed::incrementAndGet,
                5_000, "target did not return");
        assertTrue(recovered);
        assertEquals(1, waiting.get());
        assertEquals(1, resumed.get());

        waiting.set(0);
        resumed.set(0);
        recovered = ScriptForegroundGate.awaitRecovery(() -> true, () -> { },
                waiting::incrementAndGet, resumed::incrementAndGet,
                5_000, "target did not return");
        assertTrue(!recovered);
        assertEquals(0, waiting.get());
        assertEquals(0, resumed.get());
    }

    @Test public void screenshotRetriesAfterForegroundReturns() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger foregroundChecks = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger waiting = new AtomicInteger();
        AtomicInteger resumed = new AtomicInteger();
        String image = ScriptForegroundGate.captureWithRecovery(timeoutMs -> {
            if (captures.incrementAndGet() == 1) {
                throw new TargetGameNotForegroundException("foreground changed");
            }
            return "image";
        }, () -> foregroundChecks.incrementAndGet() >= 3, () -> { },
                rejected::incrementAndGet, waiting::incrementAndGet, resumed::incrementAndGet,
                5_000, "target did not return");
        assertEquals("image", image);
        assertEquals(2, captures.get());
        assertEquals(1, rejected.get());
        assertEquals(1, waiting.get());
        assertEquals(1, resumed.get());
    }

    @Test public void screenshotRepeatedForegroundRejectionStopsAfterThreeAttempts() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        try {
            ScriptForegroundGate.captureWithRecovery(timeoutMs -> {
                captures.incrementAndGet();
                throw new TargetGameNotForegroundException("foreground changed");
            }, () -> true, () -> { }, rejected::incrementAndGet,
                    () -> fail("ready target should not wait"), () -> { },
                    5_000, "target did not return");
            fail("Expected repeated foreground rejection to fail");
        } catch (TargetGameNotForegroundException expected) {
            assertEquals("foreground changed", expected.getMessage());
        }
        assertEquals(3, captures.get());
        assertEquals(3, rejected.get());
    }

    @Test public void screenshotRecoveryTimeoutNeverRetriesWithoutForeground() throws Exception {
        AtomicInteger captures = new AtomicInteger();
        try {
            ScriptForegroundGate.captureWithRecovery(timeoutMs -> {
                captures.incrementAndGet();
                throw new TargetGameNotForegroundException("foreground changed");
            }, () -> false, () -> { }, () -> { }, () -> { }, () -> { },
                    20, "target did not return");
            fail("Expected screenshot recovery to time out");
        } catch (TimeoutException expected) {
            assertEquals("target did not return", expected.getMessage());
        }
        assertEquals(1, captures.get());
    }

    @Test public void stopInterruptsScreenshotForegroundRecovery() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();
        AtomicInteger captures = new AtomicInteger();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            control.attachCurrentThread();
            try {
                ScriptForegroundGate.captureWithRecovery(timeoutMs -> {
                    captures.incrementAndGet();
                    throw new TargetGameNotForegroundException("foreground changed");
                }, () -> false, control::checkpoint, () -> { }, waiting::countDown,
                        () -> fail("stopped screenshot should not resume"),
                        5_000, "target did not return");
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                stopped.set(true);
            } catch (InterruptedException interrupted) {
                try {
                    control.checkpoint();
                } catch (ScriptExecutionControl.StopRequestedException expected) {
                    stopped.set(true);
                } catch (Throwable error) {
                    failure.set(error);
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                control.detachCurrentThread();
            }
        }, "screenshot-foreground-stop-test");
        worker.start();
        assertTrue("worker did not enter screenshot recovery", waiting.await(1, TimeUnit.SECONDS));
        assertTrue(control.requestStop());
        worker.join(1_000);
        assertTrue("stop should interrupt screenshot recovery promptly", !worker.isAlive());
        assertTrue(stopped.get());
        assertEquals(1, captures.get());
        assertEquals(null, failure.get());
    }

    @Test public void recoveryTimeoutDoesNotRunResumeHook() throws Exception {
        AtomicInteger resumed = new AtomicInteger();
        try {
            ScriptForegroundGate.awaitRecovery(() -> false, () -> { }, () -> { },
                    resumed::incrementAndGet, 20, "target did not return");
            fail("Expected recovery to time out");
        } catch (TimeoutException expected) {
            assertEquals("target did not return", expected.getMessage());
        }
        assertEquals(0, resumed.get());
    }

    @Test public void recoveryWaitHonorsPauseThenResumesOnForegroundReturn() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();
        AtomicBoolean ready = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch resumed = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            control.attachCurrentThread();
            try {
                ScriptForegroundGate.awaitRecovery(ready::get, control::checkpoint,
                        waiting::countDown, resumed::countDown, 5_000, "target did not return");
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                control.detachCurrentThread();
            }
        }, "foreground-recovery-pause-test");
        worker.start();
        assertTrue("worker did not enter the foreground wait", waiting.await(1, TimeUnit.SECONDS));
        assertTrue(control.pause());
        ready.set(true);
        Thread.sleep(100);
        assertTrue("paused recovery must not continue", worker.isAlive());
        assertEquals(1L, resumed.getCount());
        assertTrue(control.resume());
        assertTrue("recovery did not resume when the game returned", resumed.await(1, TimeUnit.SECONDS));
        worker.join(1_000);
        assertTrue("worker should finish after recovery", !worker.isAlive());
        assertEquals(null, failure.get());
    }

    @Test public void stopInterruptsForegroundRecoveryWait() throws Exception {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicInteger resumed = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            control.attachCurrentThread();
            try {
                ScriptForegroundGate.awaitRecovery(() -> false, control::checkpoint,
                        waiting::countDown, resumed::incrementAndGet, 5_000, "target did not return");
            } catch (ScriptExecutionControl.StopRequestedException expected) {
                stopped.set(true);
            } catch (InterruptedException interrupted) {
                try {
                    control.checkpoint();
                } catch (ScriptExecutionControl.StopRequestedException expected) {
                    stopped.set(true);
                } catch (Throwable error) {
                    failure.set(error);
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                control.detachCurrentThread();
            }
        }, "foreground-recovery-stop-test");
        worker.start();
        assertTrue("worker did not enter the foreground wait", waiting.await(1, TimeUnit.SECONDS));
        assertTrue(control.requestStop());
        worker.join(1_000);
        assertTrue("stop should interrupt recovery promptly", !worker.isAlive());
        assertTrue(stopped.get());
        assertEquals(0, resumed.get());
        assertEquals(null, failure.get());
    }

    @Test public void timeoutPreventsWorkFromPassingTheGate() throws Exception {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        try {
            ScriptForegroundGate.await(() -> false, () -> { }, 20,
                    "sandbox game is not foreground");
            actionRan.set(true);
            fail("Expected the foreground gate to time out");
        } catch (TimeoutException expected) {
            assertEquals("sandbox game is not foreground", expected.getMessage());
        }
        assertTrue("Automation must remain blocked if the game never becomes foreground", !actionRan.get());
    }

    @Test public void cancellationCheckpointInterruptsTheWait() {
        try {
            ScriptForegroundGate.await(() -> false,
                    () -> { throw new IllegalStateException("stopped"); }, 1_000,
                    "target did not become foreground");
            fail("Expected cancellation to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("stopped", expected.getMessage());
        } catch (Exception unexpected) {
            fail("Unexpected checked exception: " + unexpected);
        }
    }

    @Test public void rejectsInputWhenTheTargetIsNoLongerForeground() {
        try {
            ScriptForegroundGate.requireReady(() -> false, "game is not foreground");
            fail("Expected the foreground input gate to reject the operation");
        } catch (IllegalStateException expected) {
            assertEquals("game is not foreground", expected.getMessage());
        }
    }
}
