package com.example.pakredirect;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Bounded handoff from the local socket thread to the single Lua worker. */
final class ScriptWebRouteQueue {
    static final String QUEUED = "queued";
    static final String SELECTED = "selected";
    static final String SUBMITTED = "submitted";
    static final String MOVING = "moving";
    static final String ARRIVED = "arrived";
    static final String FAILED = "failed";
    private static final int MAX_ACTIVE = 1;
    private static final long REQUEST_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(6);
    private static final long RETAIN_COMPLETED_MS = TimeUnit.MINUTES.toMillis(10);

    static final class Request {
        final String requestId;
        final String mapName;
        final int x;
        final int y;
        final long createdAtMs;
        volatile String state = QUEUED;
        volatile String message = "已排队，等待 Lua 工作线程处理";
        volatile long updatedAtMs;

        Request(String requestId, String mapName, int x, int y, long now) {
            this.requestId = requestId;
            this.mapName = mapName;
            this.x = x;
            this.y = y;
            createdAtMs = now;
            updatedAtMs = now;
        }

        boolean isFinal() { return ARRIVED.equals(state) || FAILED.equals(state); }

        synchronized void update(String nextState, String nextMessage, long now) {
            if (isFinal()) return;
            if (!FAILED.equals(nextState)
                    && !(QUEUED.equals(state) && SELECTED.equals(nextState))
                    && !(SELECTED.equals(state) && SUBMITTED.equals(nextState))
                    && !(SUBMITTED.equals(state) && MOVING.equals(nextState))
                    && !(MOVING.equals(state) && (MOVING.equals(nextState) || ARRIVED.equals(nextState)))) return;
            state = nextState;
            message = nextMessage == null ? "" : nextMessage;
            updatedAtMs = now;
        }

        boolean expired(long now) { return now - createdAtMs > REQUEST_TIMEOUT_MS; }
        boolean staleCompleted(long now) { return isFinal() && now - updatedAtMs > RETAIN_COMPLETED_MS; }
    }

    private final ArrayBlockingQueue<Request> pending = new ArrayBlockingQueue<>(MAX_ACTIVE);
    private final ConcurrentHashMap<String, Request> requests = new ConcurrentHashMap<>();

    synchronized Request submit(String requestId, String mapName, int x, int y) {
        validate(requestId, mapName, x, y);
        prune();
        Request previous = requests.get(requestId);
        if (previous != null) {
            if (!previous.mapName.equals(mapName) || previous.x != x || previous.y != y) {
                throw new IllegalArgumentException("requestId 已用于其他路线请求");
            }
            return previous;
        }
        for (Request request : requests.values()) {
            if (!request.isFinal()) throw new IllegalStateException("已有路线请求正在处理");
        }
        Request request = new Request(requestId, mapName, x, y, System.currentTimeMillis());
        if (!pending.offer(request)) throw new IllegalStateException("Lua 路线队列已满");
        requests.put(requestId, request);
        return request;
    }

    synchronized Request poll() {
        prune();
        Request request = pending.poll();
        if (request != null && request.expired(System.currentTimeMillis())) {
            request.update(FAILED, "Lua 工作线程未及时接收路线请求", System.currentTimeMillis());
            return null;
        }
        return request;
    }

    Request get(String requestId) { return requestId == null ? null : requests.get(requestId); }

    void update(String requestId, String state, String message) {
        Request request = get(requestId);
        if (request != null) request.update(state, message, System.currentTimeMillis());
    }

    synchronized void close(String message) {
        Request request;
        while ((request = pending.poll()) != null) {
            request.update(FAILED, message, System.currentTimeMillis());
        }
        for (Request item : requests.values()) {
            if (!item.isFinal()) item.update(FAILED, message, System.currentTimeMillis());
        }
    }

    private void prune() {
        long now = System.currentTimeMillis();
        for (Request request : requests.values()) {
            if (!request.isFinal() && request.expired(now)) {
                pending.remove(request);
                request.update(FAILED, "Lua 路线请求超时", now);
            }
            if (request.staleCompleted(now)) requests.remove(request.requestId, request);
        }
    }

    static void validate(String requestId, String mapName, int x, int y) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("requestId 格式无效");
        }
        if (!ScriptMapCatalog.contains(mapName)) throw new IllegalArgumentException("地图名不在工作台地图清单中");
        if (x < 0 || x > 99999 || y < 0 || y > 99999) {
            throw new IllegalArgumentException("坐标必须为 0–99999 的整数");
        }
    }
}
