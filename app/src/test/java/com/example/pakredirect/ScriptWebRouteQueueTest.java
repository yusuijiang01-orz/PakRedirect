package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ScriptWebRouteQueueTest {
    @Test public void everyWorkbenchMapCanBeQueuedAndReported() {
        assertEquals(38, ScriptMapCatalog.MAPS.length);
        ScriptWebRouteQueue queue = new ScriptWebRouteQueue();
        int index = 0;
        for (String map : ScriptMapCatalog.MAPS) {
            String id = "route-" + index++;
            ScriptWebRouteQueue.Request request = queue.submit(id, map, 100, 200);
            assertEquals(ScriptWebRouteQueue.QUEUED, request.state);
            assertEquals(request, queue.poll());
            queue.update(id, ScriptWebRouteQueue.SELECTED, "精确地图标题已确认");
            assertEquals(ScriptWebRouteQueue.SELECTED, queue.get(id).state);
            queue.update(id, ScriptWebRouteQueue.SUBMITTED, "路线已提交");
            assertEquals(ScriptWebRouteQueue.SUBMITTED, queue.get(id).state);
            queue.update(id, ScriptWebRouteQueue.MOVING, "坐标已经变化");
            assertEquals(ScriptWebRouteQueue.MOVING, queue.get(id).state);
            queue.update(id, ScriptWebRouteQueue.ARRIVED, "目标地图和坐标已确认");
            assertEquals(ScriptWebRouteQueue.ARRIVED, queue.get(id).state);
        }
    }

    @Test public void submissionIsNotTerminalAndCannotSkipMovement() {
        ScriptWebRouteQueue queue = new ScriptWebRouteQueue();
        ScriptWebRouteQueue.Request request = queue.submit("route-monitor", ScriptMapCatalog.MAPS[0], 100, 200);
        assertNotNull(queue.poll());
        queue.update(request.requestId, ScriptWebRouteQueue.SELECTED, "地图已确认");
        queue.update(request.requestId, ScriptWebRouteQueue.SUBMITTED, "已提交");
        assertTrue(!request.isFinal());
        queue.update(request.requestId, ScriptWebRouteQueue.ARRIVED, "没有移动证据");
        assertEquals(ScriptWebRouteQueue.SUBMITTED, request.state);
        try {
            queue.submit("another-route", ScriptMapCatalog.MAPS[0], 100, 200);
            throw new AssertionError("submitted route should still occupy the worker");
        } catch (IllegalStateException expected) { }
        queue.update(request.requestId, ScriptWebRouteQueue.FAILED, "未观察到移动");
        assertEquals(ScriptWebRouteQueue.FAILED, request.state);
        assertTrue(request.isFinal());
    }

    @Test public void invalidMapAndConcurrentRequestAreRejected() {
        ScriptWebRouteQueue queue = new ScriptWebRouteQueue();
        try {
            queue.submit("bad-map", "not a map", 1, 2);
            throw new AssertionError("unsupported map accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("地图名"));
        }
        ScriptWebRouteQueue.Request first = queue.submit("first", ScriptMapCatalog.MAPS[0], 0, 0);
        assertNotNull(queue.poll());
        try {
            queue.submit("second", ScriptMapCatalog.MAPS[1], 0, 0);
            throw new AssertionError("parallel route accepted");
        } catch (IllegalStateException expected) {
            assertEquals(ScriptWebRouteQueue.QUEUED, first.state);
        }
    }
}
