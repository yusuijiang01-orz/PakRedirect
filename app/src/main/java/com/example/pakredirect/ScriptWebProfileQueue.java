package com.example.pakredirect;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded handoff for applying a web workbench profile to the active Lua loop. */
final class ScriptWebProfileQueue {
    static final String QUEUED = "queued";
    static final String APPLYING = "applying";
    static final String APPLIED = "applied";
    static final String FAILED = "failed";
    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "自动战斗", "自动使用红药", "生命药触发阈值",
            "自动使用蓝药", "法力药触发阈值", "手机编号", "队长名", "队员名", "是否队长",
            "检测队长名字频率秒", "检测是否有队伍频率秒", "红的数量高于", "出售全部红",
            "蓝的数量高于", "出售全部蓝", "红的数量低于", "购买小红", "购买红的数量",
            "蓝的数量低于", "购买小蓝", "购买蓝的数量", "挂机点x", "挂机点y", "挂机点地图",
            "卖货地图", "背包保留重量", "背包保留格子", "检测修理装备", "背包检测间隔秒",
            "保留蓝条数量", "回蓝>=", "保留回蓝数值", "魔法出招>=", "保留魔法出招数值",
            "武器出招>=", "保留武器出招数值", "蓝条筛选模式", "开启vpn", "挂机不移动秒",
            "队员找不到队长组队就回家次数", "保护装备不出售", "出售装备", "出售装备品质上限",
            "出售装备品质_白色", "出售装备品质_蓝色", "出售装备品质_绿色",
            "出售装备品质_黄色", "出售装备品质_橙色",
            "出售装备二次确认", "允许出售物品", "出售物品清单", "出售物品数量阈值",
            "允许购买物品", "购买物品清单", "购买物品数量阈值", "每次购买数量",
            "虚拟屏幕宽度", "虚拟屏幕高度", "虚拟屏幕DPI", "自动保存调试日志", "日志行号文件名")));
    private static final Set<String> SWITCHES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "自动战斗", "自动使用红药", "自动使用蓝药", "出售全部红", "出售全部蓝", "购买小红",
            "购买小蓝", "检测修理装备", "回蓝>=", "魔法出招>=", "武器出招>=", "开启vpn",
            "保护装备不出售", "出售装备", "出售装备品质_白色", "出售装备品质_蓝色",
            "出售装备品质_绿色", "出售装备品质_黄色", "出售装备品质_橙色",
            "出售装备二次确认", "允许出售物品", "允许购买物品",
            "自动保存调试日志", "日志行号文件名")));
    private static final int MAX_ACTIVE = 1;
    private static final long RETAIN_COMPLETED_MS = 10 * 60_000L;

    static final class Request {
        final String requestId;
        final Map<String, String> values;
        volatile String state = QUEUED;
        volatile String message = "网页方案已排队，等待 Lua 工作线程应用";
        volatile long updatedAtMs = System.currentTimeMillis();

        Request(String requestId, Map<String, String> values) {
            this.requestId = requestId;
            this.values = Collections.unmodifiableMap(values);
        }

        boolean isFinal() { return APPLIED.equals(state) || FAILED.equals(state); }
        synchronized void update(String next, String detail) {
            if (isFinal()) return;
            if (!(APPLYING.equals(next) || APPLIED.equals(next) || FAILED.equals(next))) return;
            state = next;
            message = detail == null ? "" : detail;
            updatedAtMs = System.currentTimeMillis();
        }
        boolean stale() { return isFinal() && System.currentTimeMillis() - updatedAtMs > RETAIN_COMPLETED_MS; }
    }

    private final ArrayBlockingQueue<Request> pending = new ArrayBlockingQueue<>(MAX_ACTIVE);
    private final ConcurrentHashMap<String, Request> requests = new ConcurrentHashMap<>();

    synchronized Request submit(String requestId, Map<String, String> values) {
        validate(requestId, values);
        prune();
        Request old = requests.get(requestId);
        if (old != null) return old;
        for (Request request : requests.values()) {
            if (!request.isFinal()) throw new IllegalStateException("已有网页方案正在应用");
        }
        Request request = new Request(requestId, values);
        if (!pending.offer(request)) throw new IllegalStateException("Lua 方案队列已满");
        requests.put(requestId, request);
        return request;
    }

    synchronized Request poll() {
        prune();
        Request request = pending.poll();
        if (request != null) request.update(APPLYING, "正在更新 Lua 当前运行方案");
        return request;
    }

    Request get(String requestId) { return requestId == null ? null : requests.get(requestId); }

    void update(String requestId, String state, String message) {
        Request request = get(requestId);
        if (request != null) request.update(state, message);
    }

    synchronized void close(String message) {
        Request request;
        while ((request = pending.poll()) != null) request.update(FAILED, message);
        for (Request item : requests.values()) if (!item.isFinal()) item.update(FAILED, message);
    }

    static void validate(String requestId, Map<String, String> values) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,96}")) {
            throw new IllegalArgumentException("requestId 格式无效");
        }
        if (values == null || values.isEmpty() || values.size() > ALLOWED.size()) {
            throw new IllegalArgumentException("网页方案字段为空或数量无效");
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!ALLOWED.contains(key)) throw new IllegalArgumentException("不允许的 Lua 方案字段：" + key);
            if (value == null || value.length() > 300) throw new IllegalArgumentException("方案字段长度无效：" + key);
            if ("出售物品清单".equals(key) || "购买物品清单".equals(key)
                    || "队长名".equals(key) || "队员名".equals(key) || "手机编号".equals(key)) continue;
            if (SWITCHES.contains(key)) {
                if (!("true".equals(value) || "false".equals(value))) {
                    throw new IllegalArgumentException("开关值无效：" + key);
                }
            } else {
                if (!value.matches("[0-9]{1,5}")) throw new IllegalArgumentException("数值字段无效：" + key);
                int number = Integer.parseInt(value);
                int minimum = minimum(key);
                int maximum = maximum(key);
                if (number < minimum || number > maximum) throw new IllegalArgumentException(key + " 超出允许范围");
            }
        }
    }

    private static int minimum(String key) {
        if ("挂机不移动秒".equals(key) || "背包检测间隔秒".equals(key)
                || "检测队长名字频率秒".equals(key) || "检测是否有队伍频率秒".equals(key)
                || "队员找不到队长组队就回家次数".equals(key)
                || "出售物品数量阈值".equals(key) || "每次购买数量".equals(key)
                || "购买红的数量".equals(key) || "购买蓝的数量".equals(key)
                || "虚拟屏幕宽度".equals(key) || "虚拟屏幕高度".equals(key)
                || "虚拟屏幕DPI".equals(key)) return 1;
        return 0;
    }

    private static int maximum(String key) {
        if ("挂机点地图".equals(key) || "卖货地图".equals(key)) return ScriptMapCatalog.MAPS.length - 1;
        if ("生命药触发阈值".equals(key) || "法力药触发阈值".equals(key)) return 100;
        if ("是否队长".equals(key)) return 2;
        if ("出售装备品质上限".equals(key)) return 2;
        if ("蓝条筛选模式".equals(key)) return 3;
        if ("虚拟屏幕DPI".equals(key)) return 2000;
        if ("虚拟屏幕宽度".equals(key) || "虚拟屏幕高度".equals(key)) return 16384;
        if ("挂机不移动秒".equals(key) || "背包检测间隔秒".equals(key)
                || "检测队长名字频率秒".equals(key) || "检测是否有队伍频率秒".equals(key)) return 86400;
        if ("队员找不到队长组队就回家次数".equals(key)) return 999;
        return 99999;
    }

    private void prune() {
        for (Request request : requests.values()) if (request.stale()) requests.remove(request.requestId, request);
    }
}
