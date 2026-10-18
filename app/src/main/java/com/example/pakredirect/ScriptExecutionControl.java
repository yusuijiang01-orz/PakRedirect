package com.example.pakredirect;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Cooperative lifecycle gate shared by the Lua runner and every host API call. */
public final class ScriptExecutionControl {
    public enum State { IDLE, RUNNING, PAUSED, STOP_REQUESTED, STOPPED, COMPLETED, FAILED }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private volatile State state = State.IDLE;
    private volatile Throwable failure;
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final Set<Thread> cancelledWorkers = ConcurrentHashMap.newKeySet();

    public State getState() { return state; }
    public Throwable getFailure() { return failure; }

    public boolean isCurrentThreadCancellationRequested() {
        State currentState = state;
        return currentState == State.STOP_REQUESTED || currentState == State.STOPPED
                || currentState == State.FAILED
                || cancelledWorkers.contains(Thread.currentThread());
    }

    public boolean isStopRequested() {
        return state == State.STOP_REQUESTED || state == State.STOPPED;
    }

    public void start() {
        lock.lock();
        try {
            if (state != State.IDLE && state != State.STOPPED
                    && state != State.COMPLETED && state != State.FAILED) {
                throw new IllegalStateException("Script is already active: " + state);
            }
            state = State.RUNNING;
            failure = null;
            workers.clear();
            cancelledWorkers.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean pause() {
        lock.lock();
        try {
            if (state != State.RUNNING) return false;
            state = State.PAUSED;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean resume() {
        lock.lock();
        try {
            if (state != State.PAUSED) return false;
            state = State.RUNNING;
            changed.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean requestStop() {
        lock.lock();
        try {
            if (state == State.IDLE || isTerminal(state)) return false;
            state = State.STOP_REQUESTED;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        for (Thread worker : workers) {
            if (worker != Thread.currentThread()) worker.interrupt();
        }
        return true;
    }

    public void attachCurrentThread() {
        workers.add(Thread.currentThread());
        checkpoint();
    }

    public void detachCurrentThread() {
        workers.remove(Thread.currentThread());
        cancelledWorkers.remove(Thread.currentThread());
    }

    /** Stop one legacy Lua worker without stopping the whole runtime. */
    public void cancelWorker(Thread worker) {
        if (worker == null || !worker.isAlive()) return;
        cancelledWorkers.add(worker);
        worker.interrupt();
    }

    /** Called before and after every host operation and during interruptible sleeps. */
    public void checkpoint() {
        lock.lock();
        try {
            while (state == State.PAUSED) changed.await();
            Thread current = Thread.currentThread();
            boolean workerCancelled = cancelledWorkers.contains(current);
            if (state == State.STOP_REQUESTED || state == State.STOPPED || state == State.FAILED
                    || workerCancelled) {
                if (state == State.STOP_REQUESTED || state == State.STOPPED) {
                    state = State.STOPPED;
                    changed.signalAll();
                }
                throw new StopRequestedException();
            }
            if (current.isInterrupted()) throw new IllegalStateException("Script thread was interrupted unexpectedly");
            if (state != State.RUNNING) throw new IllegalStateException("Script is not running: " + state);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            boolean workerCancelled = cancelledWorkers.contains(Thread.currentThread());
            if (state == State.STOP_REQUESTED || state == State.STOPPED
                    || state == State.FAILED || workerCancelled) {
                if (state == State.STOP_REQUESTED) state = State.STOPPED;
                throw new StopRequestedException();
            }
            throw new IllegalStateException("Script wait was interrupted", interrupted);
        } finally {
            lock.unlock();
        }
    }

    /** Sleeps in short slices so pause and stop take effect promptly. */
    public void sleep(long millis) {
        long remainingNanos = Math.max(0L, millis) * 1_000_000L;
        long deadline = System.nanoTime() + remainingNanos;
        do {
            checkpoint();
            remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) break;
            long sliceMillis = Math.max(1L, Math.min(100L, remainingNanos / 1_000_000L));
            try {
                Thread.sleep(sliceMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                checkpoint();
            }
        } while (true);
        checkpoint();
    }

    public void complete() { finish(State.COMPLETED); }

    /** Record the first failure and stop all other runtime threads. */
    public boolean fail(Throwable cause) {
        if (cause == null) throw new NullPointerException("cause");
        lock.lock();
        try {
            if (state != State.RUNNING && state != State.PAUSED) return false;
            failure = cause;
            state = State.FAILED;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        for (Thread worker : workers) {
            if (worker != Thread.currentThread()) worker.interrupt();
        }
        return true;
    }

    private void finish(State terminal) {
        lock.lock();
        try {
            if (state != State.FAILED) {
                state = state == State.STOP_REQUESTED || state == State.STOPPED
                        ? State.STOPPED : terminal;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static boolean isTerminal(State value) {
        return value == State.STOPPED || value == State.COMPLETED || value == State.FAILED;
    }

    public static final class StopRequestedException extends RuntimeException {
        public StopRequestedException() { super("Script stop requested", null, false, false); }
    }
}
