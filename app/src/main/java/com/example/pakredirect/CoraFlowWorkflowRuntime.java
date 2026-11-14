package com.example.pakredirect;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes a decrypted CoraFlow graph through RYLUX's native Android input and OCR APIs. */
public final class CoraFlowWorkflowRuntime {
    private static final int MAX_STEPS = 100_000;
    private static final long CAPTURE_TIMEOUT_MS = 8_000L;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    public static final class WorkflowPackage implements AutoCloseable {
        final JSONObject workflow;
        final JSONObject bindings;
        final Map<String, Bitmap> images;
        final JSONObject panelValues;

        WorkflowPackage(JSONObject workflow, JSONObject bindings, Map<String, Bitmap> images,
                        JSONObject panelValues) {
            this.workflow = workflow;
            this.bindings = bindings;
            this.images = images;
            this.panelValues = panelValues;
        }

        @Override public void close() {
            Set<Bitmap> recycled = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Bitmap bitmap : images.values()) {
                if (bitmap != null && recycled.add(bitmap) && !bitmap.isRecycled()) bitmap.recycle();
            }
            images.clear();
        }
    }

    private static final class LoopFrame {
        final String nodeId;
        final String bodyStart;
        final int count;
        int iteration = 1;
        LoopFrame(String nodeId, String bodyStart, int count) {
            this.nodeId = nodeId;
            this.bodyStart = bodyStart;
            this.count = count;
        }
    }

    private static final class Match {
        final float x;
        final float y;
        final float score;
        Match(float x, float y, float score) { this.x = x; this.y = y; this.score = score; }
    }

    private static final class OcrRead {
        final String text;
        final float confidence;
        OcrRead(String text, float confidence) { this.text = text; this.confidence = confidence; }
    }

    private static final class NumberSample {
        final int value;
        final long time;
        NumberSample(int value, long time) { this.value = value; this.time = time; }
    }

    private static final class InventoryMetric {
        final int value;
        final String text;
        final float confidence;
        InventoryMetric(int value, String text, float confidence) {
            this.value = value;
            this.text = text;
            this.confidence = confidence;
        }
    }

    private static final class YellowTextCandidate {
        final Rect region;
        final int pixels;
        final int y;
        YellowTextCandidate(Rect region, int pixels, int y) {
            this.region = region;
            this.pixels = pixels;
            this.y = y;
        }
    }

    private final WorkflowPackage workflowPackage;
    private final ScriptExecutionControl control;
    private final Consumer<String> listener;
    private final ScriptGameAdapter game;
    private final ScriptOcr ocr;
    private final BooleanSupplier gameLauncher;
    private final Map<String, Bitmap> scaledTemplates = new HashMap<>();
    private final Map<String, int[]> previousRegions = new HashMap<>();
    private final Map<String, Integer> visits = new HashMap<>();
    private final Map<String, ArrayDeque<NumberSample>> numberHistory = new HashMap<>();
    private final ArrayDeque<LoopFrame> loops = new ArrayDeque<>();
    private JSONArray currentNodes;
    private int screenWidth;
    private int screenHeight;
    private int coordinateWidth = 1920;
    private int coordinateHeight = 1080;
    private int steps;

    public CoraFlowWorkflowRuntime(WorkflowPackage workflowPackage, ScriptExecutionControl control,
                                   Consumer<String> listener, BooleanSupplier gameLauncher) {
        if (workflowPackage == null || control == null) throw new IllegalArgumentException("CoraFlow runtime arguments are required");
        this.workflowPackage = workflowPackage;
        this.control = control;
        this.listener = listener;
        this.game = ScriptGameAdapter.requireConnected(control);
        this.ocr = new ScriptOcr();
        this.gameLauncher = gameLauncher;
    }

    static boolean startsWithStartupGate(WorkflowPackage workflowPackage) {
        if (workflowPackage == null) return false;
        JSONArray nodes = workflowPackage.workflow.optJSONArray("nodes");
        JSONArray edges = workflowPackage.workflow.optJSONArray("edges");
        if (nodes == null || edges == null) return false;
        String startId = null;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && "start".equals(nodeType(node))) {
                startId = node.optString("id", "");
                break;
            }
        }
        if (startId == null || startId.isEmpty()) return false;
        String firstId = null;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge != null && startId.equals(edge.optString("source", ""))
                    && !"condition".equals(edge.optString("type", ""))) {
                firstId = edge.optString("target", "");
                break;
            }
        }
        if (firstId == null || firstId.isEmpty()) return false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !firstId.equals(node.optString("id", ""))) continue;
            String type = nodeType(node);
            return "is_app_running".equals(type) || "launch_game".equals(type);
        }
        return false;
    }

    public void run() throws Exception {
        try {
            JSONObject graph = new JSONObject(workflowPackage.workflow.toString());
            applyPanelBindings(graph, workflowPackage.bindings, workflowPackage.panelValues);
            JSONArray nodes = graph.optJSONArray("nodes");
            JSONArray edges = graph.optJSONArray("edges");
            if (nodes == null || edges == null) throw new IllegalStateException("CoraFlow 工作流缺少节点或连线");
            currentNodes = nodes;
            Map<String, JSONObject> nodeMap = new HashMap<>();
            JSONObject start = null;
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node == null) continue;
                String id = node.optString("id", "").trim();
                if (id.isEmpty() || nodeMap.put(id, node) != null) throw new IllegalStateException("CoraFlow 节点 ID 缺失或重复");
                String type = nodeType(node);
                if ("start".equals(type) && start == null) start = node;
            }
            if (start == null) throw new IllegalStateException("画布中找不到“开始”节点");
            JSONObject sizeConfig = firstConfig(nodes, "set_window_size");
            if (sizeConfig != null) {
                coordinateWidth = positiveInt(sizeConfig.optInt("width", 1920), 1920);
                coordinateHeight = positiveInt(sizeConfig.optInt("height", 1080), 1080);
            }
            if (startsWithStartupGate(workflowPackage)) {
                note("工作流先检查并启动目标游戏；确认游戏前台后才截图，避免启动前因无画面而失败。");
            } else {
                Bitmap initial = capture();
                try {
                    screenWidth = initial.getWidth();
                    screenHeight = initial.getHeight();
                    note("RYLUX JSON 执行器已载入 “" + graph.optString("name", "CoraFlow 工作流")
                            + "” · " + nodes.length() + " 节点 / " + edges.length() + " 连线 · 画面 "
                            + initial.getWidth() + "×" + initial.getHeight());
                } finally { initial.recycle(); }
            }
            note("工作流坐标基准：" + coordinateWidth + "×" + coordinateHeight);
            String currentId = nextEdge(edges, start.optString("id", ""), null);
            while (currentId != null && !currentId.isEmpty()) {
                checkpoint();
                if (++steps > MAX_STEPS) throw new IllegalStateException("已达到 100,000 步安全上限；检查是否有无法退出的循环。");
                JSONObject node = nodeMap.get(currentId);
                if (node == null) throw new IllegalStateException("连线指向不存在的节点：" + currentId);
                JSONObject data = node.optJSONObject("data");
                String type = nodeType(node);
                JSONObject config = data == null ? new JSONObject() : data.optJSONObject("config");
                if (config == null) config = new JSONObject();
                String name = data == null ? type : data.optString("name", type);
                note("运行：" + name);
                int visitsForNode = visits.merge(currentId, 1, Integer::sum);
                int maxCount = config.optInt("count", -1);
                if (maxCount >= 0 && visitsForNode > maxCount) {
                    note("达到节点次数限制，跳过本次操作。");
                    currentId = nextEdge(edges, currentId, null);
                    continue;
                }

                overrideNext = null;
                Boolean condition = execute(type, currentId, name, config, edges, nodeMap);
                if (isDual(type)) {
                    if (condition == null) throw new IllegalStateException("“" + name + "”没有返回条件结果");
                    String next = nextEdge(edges, currentId, condition);
                    if (next == null) throw new IllegalStateException("“" + name + "”缺少 " + (condition ? "true" : "false") + " 分支连线");
                    currentId = next;
                } else if (overrideNext != null) {
                    currentId = overrideNext;
                } else if ("end_loop".equals(type) && loops.isEmpty()) {
                    currentId = null;
                } else {
                    currentId = nextEdge(edges, currentId, null);
                }
            }
            checkpoint();
            note("CoraFlow JSON 工作流执行完成。");
        } finally {
            for (Bitmap bitmap : scaledTemplates.values()) if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            scaledTemplates.clear();
            ocr.close();
        }
    }

    private Boolean execute(String type, String id, String name, JSONObject config,
                            JSONArray edges, Map<String, JSONObject> nodes) throws Exception {
        switch (type) {
            case "start":
            case "group":
                return null;
            case "print":
                note(config.optString("message", ""));
                return null;
            case "abort":
                throw new IllegalStateException(config.optString("message", "“" + name + "”中止了工作流"));
            case "delay":
                waitNode(config);
                return null;
            case "set_window_size":
                coordinateWidth = positiveInt(config.optInt("width", 0), 0);
                coordinateHeight = positiveInt(config.optInt("height", 0), 0);
                if (coordinateWidth <= 0 || coordinateHeight <= 0) throw new IllegalStateException("“" + name + "”坐标基准无效");
                note("坐标基准设为 " + coordinateWidth + "×" + coordinateHeight + "（不修改设备分辨率）。");
                return null;
            case "click":
            case "right_click":
                click(config.optDouble("x", 0), config.optDouble("y", 0));
                return null;
            case "left_click":
                click(config.optDouble("x", 0), config.optDouble("y", 0));
                return null;
            case "drag":
                drag(config);
                return null;
            case "input_text":
                inputText(config, name);
                return null;
            case "key_press":
                if (!game.inputKey(config.optString("key", ""))) throw new IllegalStateException("“" + name + "”无法向当前游戏输入框发送按键");
                return null;
            default:
                return executeGraphNode(type, id, name, config, edges, nodes);
        }
    }

    private Boolean executeGraphNode(String type, String id, String name, JSONObject config,
                                     JSONArray edges, Map<String, JSONObject> nodes) throws Exception {
        if ("is_app_running".equals(type)) {
            requireTargetGamePackage(config, name);
            boolean foreground = ScriptGameAdapter.isTargetGameForeground();
            note(foreground ? "目标游戏已在前台 → 是。" : "目标游戏未在前台 → 否；后续可启动或切回游戏。");
            return foreground;
        }
        if ("launch_game".equals(type)) {
            launchTargetGame(config, name);
            return null;
        }
        if ("loop".equals(type)) {
            LoopFrame active = loops.peekLast();
            if (active == null || !active.nodeId.equals(id)) {
                String body = nextEdge(edges, id, null);
                if (body == null) throw new IllegalStateException("“" + name + "”没有连接循环体");
                int count = config.optInt("count", -1);
                if (count == 0) {
                    note("循环次数为 0，按工作流定义结束执行。");
                    overrideNext = "";
                    return null;
                }
                loops.addLast(new LoopFrame(id, body, count));
                note(count < 0 ? "进入循环；可随时停止。" : "进入循环（共 " + count + " 次）。");
                overrideNext = body;
            }
            return null;
        }
        if ("end_loop".equals(type)) {
            LoopFrame frame = loops.peekLast();
            if (frame == null) throw new IllegalStateException("“" + name + "”没有对应的循环节点");
            if (config.optBoolean("break_loop", false)) {
                String expectedLoopId = config.optString("loop_node_id", "").trim();
                if (!expectedLoopId.isEmpty() && !expectedLoopId.equals(frame.nodeId)) {
                    throw new IllegalStateException("“" + name + "”要求退出循环 " + expectedLoopId
                            + "，但当前循环是 " + frame.nodeId + "；已停止以防破坏外层循环。");
                }
                loops.removeLast();
                String next = nextEdge(edges, id, null);
                if (next != null) overrideNext = next;
                else if (!loops.isEmpty()) overrideNext = loops.peekLast().bodyStart;
                else overrideNext = null;
                note("按条件提前退出循环“" + frame.nodeId + "”（已执行 " + frame.iteration + " 轮）。");
                return null;
            }
            if (frame.count < 0 || frame.iteration < frame.count) {
                frame.iteration++;
                overrideNext = frame.bodyStart;
            } else {
                loops.removeLast();
                String next = nextEdge(edges, id, null);
                if (next != null && !next.equals(frame.nodeId)) overrideNext = next;
                else if (!loops.isEmpty()) overrideNext = loops.peekLast().bodyStart;
                else overrideNext = null;
            }
            return null;
        }
        if ("is_image_exist".equals(type) || "click_if_image_exist".equals(type)) {
            String asset = config.optString("image", "").trim();
            if (asset.isEmpty()) throw new IllegalStateException("“" + name + "”没有配置图片素材");
            if (asset.endsWith("/RYLUX_登录_账号按钮_完整.png")) {
                int accountButton = game.accountLoginButton("click_if_image_exist".equals(type));
                if (accountButton < 0) throw new IllegalStateException("账号登录按钮已识别，但系统拒绝点击；已停止。");
                if (accountButton > 0) {
                    note("is_image_exist".equals(type) ? "已识别账号登录按钮控件 → 是。" : "已通过账号登录按钮控件执行点击。");
                    return "is_image_exist".equals(type) ? Boolean.TRUE : null;
                }
            }
            Bitmap frame = capture();
            try {
                Rect searchRegion = region(config.optJSONArray("region"));
                float threshold = (float) config.optDouble("threshold", 0.8);
                float fallbackThreshold = (float) config.optDouble("fallback_threshold", threshold);
                Match match = findTemplate(frame, asset, searchRegion, threshold);
                boolean fallbackMatch = false;
                if (match == null && fallbackThreshold < threshold) {
                    match = findTemplate(frame, asset, searchRegion, fallbackThreshold);
                    fallbackMatch = match != null;
                }
                if ("is_image_exist".equals(type)) {
                    note(match == null ? "未命中" + (fallbackThreshold < threshold
                                    ? "（主阈值 " + percent(threshold * 100) + "，备用阈值 "
                                            + percent(fallbackThreshold * 100) + "）" : "") + " → 否。"
                            : (fallbackMatch ? "备用阈值命中 " : "识别命中 ")
                                    + percent(match.score * 100) + " → 是。");
                    return match != null;
                }
                if (match != null) {
                    double y = match.y + config.optDouble("offset", 0) * frame.getHeight() / coordinateHeight;
                    tapPixels(match.x, y, frame.getWidth(), frame.getHeight());
                    note((fallbackMatch ? "素材备用阈值命中 " : "素材命中 ")
                            + percent(match.score * 100) + "；已点击匹配中心。");
                } else note("当前画面未找到素材" + (fallbackThreshold < threshold
                        ? "（主阈值 " + percent(threshold * 100) + "，备用阈值 "
                                + percent(fallbackThreshold * 100) + "）" : "")
                        + "，继续下一节点。");
                return null;
            } finally { frame.recycle(); }
        }
        if ("is_color_exist".equals(type) || "click_if_color_exist".equals(type)) {
            Bitmap frame = capture();
            try {
                String color = config.optString("color", "#FFFFFF");
                Match found = findColor(frame, color, region(config.optJSONArray("region")),
                        config.optInt("color_threshold", 20));
                if ("is_color_exist".equals(type)) {
                    note(found != null ? "区域中找到颜色 " + color + " → 是。"
                            : "区域中没有颜色 " + color + " → 否。");
                    return found != null;
                }
                if (found != null) {
                    double x = found.x + config.optDouble("offset_x", 0) * frame.getWidth() / coordinateWidth;
                    double y = found.y + config.optDouble("offset_y", 0) * frame.getHeight() / coordinateHeight;
                    tapPixels(x, y, frame.getWidth(), frame.getHeight());
                    note("区域找到颜色 " + color + " 并已点击。");
                } else note("区域中没有颜色 " + color + "，继续下一节点。");
                return null;
            } finally { frame.recycle(); }
        }
        if ("check_region_change".equals(type)) {
            Bitmap frame = capture();
            try {
                int[] current = regionSample(frame, region(config.optJSONArray("region")));
                int[] previous = previousRegions.put(id, current);
                if (previous == null) {
                    note("区域变化基准已记录 → 否。");
                    return false;
                }
                double changed = changedFraction(previous, current);
                boolean result = changed >= Math.max(0, config.optDouble("diff_threshold", 0.1));
                note("区域画面变化 " + String.format(Locale.US, "%.1f%%", changed * 100)
                        + " → " + (result ? "是" : "否") + "。");
                return result;
            } finally { frame.recycle(); }
        }
        if ("recognize_number".equals(type) || "recognize_text".equals(type)) {
            return recognizeNode(type, id, name, config);
        }
        if ("sell_inventory_equipment".equals(type)) {
            executeSellNode(name, config);
            return null;
        }
        throw new IllegalStateException("不支持的 CoraFlow 节点类型：“" + type + "”");
    }

    private void requireTargetGamePackage(JSONObject config, String name) {
        String packageName = config.optString("package_name", "").trim();
        if (!ScriptGameAdapter.TARGET_GAME_PACKAGE.equals(packageName)) {
            throw new IllegalStateException("“" + name + "”只能检查或启动已配置的封神榜游戏包名");
        }
    }

    private void launchTargetGame(JSONObject config, String name) throws Exception {
        requireTargetGamePackage(config, name);
        if (gameLauncher == null || !gameLauncher.getAsBoolean()) {
            throw new IllegalStateException("“" + name + "”未能启动目标游戏");
        }
        long waitMs = Math.max(10_000L, Math.min(120_000L,
                config.optLong("launch_wait_ms", 60_000L)));
        long deadline = SystemClock.elapsedRealtime() + waitMs;
        while (!ScriptGameAdapter.isTargetGameForeground()
                && SystemClock.elapsedRealtime() < deadline) {
            checkpoint();
            control.sleep(250L);
        }
        if (!ScriptGameAdapter.isTargetGameForeground()) {
            throw new IllegalStateException("“" + name + "”启动后 " + (waitMs / 1000)
                    + " 秒仍未进入前台；已停止，未继续点击。");
        }
        Bitmap frame = capture();
        try {
            screenWidth = frame.getWidth();
            screenHeight = frame.getHeight();
            note("目标游戏已启动并进入前台；显示分辨率 " + screenWidth + "×" + screenHeight + "。");
        } finally { frame.recycle(); }
    }

    private String overrideNext;

    private Boolean recognizeNode(String type, String id, String name, JSONObject config) throws Exception {
        int retries = Math.max(0, Math.min(3, config.optInt("ocr_retry_count", 0)));
        float minimum = (float) Math.max(0, Math.min(100, config.optDouble("min_confidence", 30)));
        OcrRead result = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            checkpoint();
            if (attempt > 0) {
                note("OCR 结果不确定，重试截图 " + attempt + "/" + retries + "；尚未执行后续操作。");
                control.sleep(Math.max(0, Math.min(5000, config.optLong("ocr_retry_delay_ms", 350))));
            }
            Bitmap frame = capture();
            try {
                if ("recognize_number".equals(type) && !config.optString("item_image", "").trim().isEmpty()) {
                    Number total = countInventoryIcon(frame, config);
                    double target = config.optDouble("number_value", config.optDouble("threshold", 0));
                    String op = config.optString("number_operator", config.optString("operator", ">="));
                    boolean matched = compare(total.doubleValue(), op, target);
                    note("全背包图标定位数量：" + total + "；" + total + " " + op + " " + target + " → " + (matched ? "是" : "否") + "。");
                    addNumberSample(id, total.intValue());
                    return matched;
                }
                boolean chinese = config.optString("ocr_language", "").toLowerCase(Locale.US).contains("chi");
                result = readOcr(frame, region(config.optJSONArray("region")), chinese,
                        config.optInt("ocr_scale", 2));
            } finally { frame.recycle(); }
            if (result.confidence >= minimum && ("recognize_text".equals(type) || parseNumber(result.text) != null)) break;
        }
        if (result == null) throw new IllegalStateException("“" + name + "”OCR 没有返回结果");
        String raw = result.text.replaceAll("\\s+", " ").trim();
        Integer value = "recognize_number".equals(type) ? parseNumber(raw) : null;
        if (result.confidence < minimum || ("recognize_number".equals(type) && value == null)) {
            if (config.optBoolean("low_confidence_as_false", false)) {
                note("OCR 结果不确定（" + raw + "，" + percent(result.confidence) + "），转入 false 分支，不将其当作零。");
                return false;
            }
            throw new IllegalStateException("OCR 置信度 " + percent(result.confidence) + "；原文“" + raw + "”；无法可靠判断。");
        }
        if ("recognize_text".equals(type)) {
            String expected = config.optString("text_to_find", "");
            String haystack = normalizeText(raw, config.optBoolean("text_case_sensitive", false));
            String needle = normalizeText(expected, config.optBoolean("text_case_sensitive", false));
            boolean matched = "equals".equals(config.optString("text_match_mode", "contains"))
                    ? haystack.equals(needle) : haystack.contains(needle);
            note("OCR 文字“" + raw + "”，匹配“" + expected + "”（" + percent(result.confidence)
                    + "）→ " + (matched ? "是" : "否") + "。");
            return matched;
        }
        addNumberSample(id, value);
        String op = config.optString("number_operator", config.optString("operator", ">="));
        double target = config.optDouble("number_value", config.optDouble("threshold", 0));
        boolean matched = compare(value, op, target);
        note("OCR 数字：" + value + "（原文“" + raw + "”，" + percent(result.confidence)
                + "）；" + value + " " + op + " " + target + " → " + (matched ? "是" : "否") + "。");
        return matched;
    }

    private void applyPanelBindings(JSONObject graph, JSONObject bindings, JSONObject panelValues) throws Exception {
        JSONArray variables = bindings.optJSONArray("variables");
        JSONArray nodes = graph.optJSONArray("nodes");
        if (variables == null || nodes == null) throw new IllegalStateException("CoraFlow 面板绑定表无效");
        Map<String, Object> resolved = new HashMap<>();
        Map<String, JSONObject> variableById = new HashMap<>();
        for (int i = 0; i < variables.length(); i++) {
            JSONObject item = variables.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String key = "coraflow." + id;
            Object fallback = item.opt("default");
            Object value = panelValues.has(key) ? panelValues.opt(key) : fallback;
            if ("integer".equals(item.optString("type", ""))) {
                int number = value instanceof Number ? ((Number) value).intValue() : parseInt(String.valueOf(value),
                        fallback instanceof Number ? ((Number) fallback).intValue() : 0);
                number = Math.max(item.optInt("min", Integer.MIN_VALUE), Math.min(item.optInt("max", Integer.MAX_VALUE), number));
                value = number;
            } else if ("boolean".equals(item.optString("type", ""))) {
                value = value instanceof Boolean ? value : Boolean.parseBoolean(String.valueOf(value));
            }
            resolved.put(id, value);
            variableById.put(id, item);
        }
        Map<String, JSONObject> nodeById = new HashMap<>();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null) nodeById.put(node.optString("id", ""), node);
        }
        for (Map.Entry<String, JSONObject> variable : variableById.entrySet()) {
            Object value = resolved.get(variable.getKey());
            JSONArray targets = variable.getValue().optJSONArray("targets");
            if (targets == null) continue;
            for (int i = 0; i < targets.length(); i++) {
                JSONObject binding = targets.optJSONObject(i);
                if (binding == null) continue;
                JSONObject node = nodeById.get(binding.optString("node_id", ""));
                if (node == null) throw new IllegalStateException("面板变量引用了不存在的工作流节点：" + binding.optString("node_id"));
                String path = binding.optString("path", "");
                if (!path.startsWith("data.")) throw new IllegalStateException("CoraFlow 绑定路径必须从 data 开始");
                Object transformed = applyTransform(value, binding.optJSONObject("transform"), resolved);
                setPath(node, path, transformed);
            }
        }
        note("面板变量已绑定到工作流节点：" + variables.length() + " 项。");
    }

    private static Object applyTransform(Object value, JSONObject transform, Map<String, Object> resolved) {
        if (transform == null) return value;
        String op = transform.optString("op", "");
        int number = value instanceof Number ? ((Number) value).intValue() : 0;
        if ("subtract_from".equals(op)) return transform.optInt("base") - number;
        if ("subtract".equals(op)) return number - transform.optInt("value");
        if ("max_minus_batch_plus_one".equals(op)) {
            Object batch = resolved.get(transform.optString("batch_variable", ""));
            return number - (batch instanceof Number ? ((Number) batch).intValue() : 0) + 1;
        }
        return value;
    }

    private static void setPath(JSONObject root, String dottedPath, Object value) throws JSONException {
        String[] parts = dottedPath.split("\\.");
        JSONObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.optJSONObject(parts[i]);
            if (current == null) throw new IllegalStateException("CoraFlow 绑定目标路径无效：" + dottedPath);
        }
        String key = parts[parts.length - 1];
        if (!current.has(key)) throw new IllegalStateException("CoraFlow 绑定目标字段不存在：" + dottedPath);
        current.put(key, value);
    }

    private void inputText(JSONObject config, String name) throws Exception {
        String text = config.optString("text", "");
        if (text.isEmpty()) throw new IllegalStateException("“" + name + "”没有配置要输入的文本");
        if (config.optBoolean("focus_before_input", false)) {
            click(config.optDouble("x", 0), config.optDouble("y", 0));
            control.sleep(Math.max(0, Math.min(5000, config.optLong("input_wait_before_ms", 350))));
        }
        if (config.optBoolean("clear_before_input", true)) {
            if (!game.setFocusedText("")) throw new IllegalStateException("“" + name + "”无法清空当前游戏输入框");
        }
        if (!game.setFocusedText(text)) throw new IllegalStateException("“" + name + "”无法向当前游戏输入框写入文字");
        if (config.optBoolean("click_after_input", false)) {
            control.sleep(Math.max(0, Math.min(5000, config.optLong("input_wait_after_ms", 600))));
            click(config.optDouble("submit_x", 0), config.optDouble("submit_y", 0));
        }
    }

    private void waitNode(JSONObject config) throws Exception {
        if (!config.optBoolean("adaptive_forecast_enabled", false)) {
            control.sleep(Math.max(0, Math.min(86_400_000L, config.optLong("ms", 0))));
            return;
        }
        double min = clamp(config.optDouble("adaptive_min_wait_s", 10), 0, 86_400) * 1000;
        double max = Math.max(min, clamp(config.optDouble("adaptive_max_wait_s", 300), 0, 86_400) * 1000);
        double fallback = clamp(config.optDouble("adaptive_fallback_wait_s", 180), min / 1000, max / 1000) * 1000;
        double lead = clamp(config.optDouble("adaptive_lead_ratio", 0.65), 0.1, 0.95);
        double safety = clamp(config.optDouble("adaptive_safety_margin_s", 15), 0, 86_400) * 1000;
        long forecast = Long.MAX_VALUE;
        String[] sources = {config.optString("adaptive_load_node_id", ""), config.optString("adaptive_free_slots_node_id", "")};
        boolean increasing[] = {true, false};
        for (int i = 0; i < sources.length; i++) {
            ArrayDeque<NumberSample> samples = numberHistory.get(sources[i]);
            if (samples == null || samples.size() < 2) continue;
            NumberSample first = samples.peekFirst(), last = samples.peekLast();
            long elapsed = last.time - first.time;
            if (elapsed < 10_000) continue;
            JSONObject sourceNode = null;
            for (int nodeIndex = 0; nodeIndex < currentNodes.length(); nodeIndex++) {
                JSONObject candidate = currentNodes.optJSONObject(nodeIndex);
                if (candidate != null && sources[i].equals(candidate.optString("id", ""))) {
                    sourceNode = candidate;
                    break;
                }
            }
            JSONObject sourceData = sourceNode == null ? null : sourceNode.optJSONObject("data");
            JSONObject sourceConfig = sourceData == null ? null : sourceData.optJSONObject("config");
            if (sourceConfig == null) continue;
            double threshold = sourceConfig.optDouble("number_value", sourceConfig.optDouble("threshold", Double.NaN));
            double progress = increasing[i] ? last.value - first.value : first.value - last.value;
            double remaining = increasing[i] ? threshold - last.value : last.value - threshold;
            if (!Double.isFinite(threshold) || progress <= 0 || remaining <= 0) continue;
            forecast = Math.min(forecast, (long) (remaining * elapsed / progress));
        }
        long sleep = forecast == Long.MAX_VALUE ? (long) fallback
                : Math.round(Math.max(min, Math.min(max, forecast * lead - safety)));
        note(forecast == Long.MAX_VALUE ? "没有足够的背包变化样本；按安全回退间隔检查。"
                : "按近期变化估算，在 " + Math.max(1, sleep / 1000) + " 秒后提前复查背包。");
        control.sleep(sleep);
    }

    private Number countInventoryIcon(Bitmap frame, JSONObject config) throws Exception {
        String path = config.optString("item_image", "").trim();
        Bitmap icon = workflowPackage.images.get(path);
        if (icon == null) throw new IllegalStateException("工作流缺少物品图标素材：" + path);
        Rect grid = region(config.optJSONArray("region"));
        if (grid.width() < 200 || grid.height() < 300) throw new IllegalStateException("物品数量节点的范围没有覆盖完整背包");
        int minConfidence = Math.max(0, Math.min(100, config.optInt("min_confidence", 30)));
        int count = 0;
        int hits = 0;
        int cellWidth = grid.width() / 5;
        int cellHeight = grid.height() / 7;
        float threshold = (float) config.optDouble("threshold", 0.9);
        for (int slot = 1; slot <= 35; slot++) {
            checkpoint();
            int row = (slot - 1) / 5, column = (slot - 1) % 5;
            Rect cell = new Rect(grid.left + column * cellWidth + 3, grid.top + row * cellHeight + 3,
                    column == 4 ? grid.right : grid.left + (column + 1) * cellWidth - 3,
                    row == 6 ? grid.bottom : grid.top + (row + 1) * cellHeight - 3);
            // Match the desktop CoraFlow inventory matcher: the saved potion
            // crops contain the dark slot background, so only sample colored
            // bottle pixels. Otherwise an empty dark slot can look like a
            // low-confidence potion candidate and stop the workflow.
            Match match = findTemplate(frame, icon, cell, threshold, true);
            if (match == null) {
                if (findTemplate(frame, icon, cell, Math.max(0.7f, threshold - 0.03f), true) != null) {
                    throw new IllegalStateException("第 " + slot + "/35 格发现药瓶图标候选，但未达到严格阈值；不按 0 处理。");
                }
                continue;
            }
            hits++;
            // Match CoraFlow's inventoryCountRegion: stack counts are rendered
            // at the lower-left of each slot (the old lower-right crop missed
            // them entirely on the last-row potion stacks).
            Rect countRegion = new Rect(cell.left + Math.round(cell.width() * 0.03f),
                    cell.top + Math.round(cell.height() * 0.58f),
                    cell.left + Math.round(cell.width() * 0.60f),
                    cell.top + Math.round(cell.height() * 0.95f));
            OcrRead bestRead = null;
            Integer value = null;
            for (int scale : new int[]{4, 3, 2}) {
                OcrRead candidate = readOcr(frame, countRegion, false, scale);
                Integer candidateValue = parseNumber(candidate.text);
                if (candidateValue != null && candidateValue > 0
                        && (bestRead == null || candidate.confidence > bestRead.confidence)) {
                    bestRead = candidate;
                    value = candidateValue;
                }
                if (candidateValue != null && candidateValue > 0 && candidate.confidence >= minConfidence) {
                    bestRead = candidate;
                    value = candidateValue;
                    break;
                }
            }
            if (bestRead == null || value == null || bestRead.confidence < minConfidence) {
                String text = bestRead == null ? "" : bestRead.text;
                float confidence = bestRead == null ? 0 : bestRead.confidence;
                throw new IllegalStateException("第 " + (row + 1) + " 行第 " + (column + 1)
                        + " 列命中药瓶图标但数量 OCR 不可靠（" + text + "，" + percent(confidence) + "）；停止统计。");
            }
            if (bestRead.confidence < 80) note("药瓶数量 OCR 经过多尺度复读，采用置信度最高结果。");
            count += value;
            note("药瓶图标定位：第 " + (row + 1) + " 行第 " + (column + 1) + " 列，数量 " + value + "。");
        }
        note("背包图标扫描 35 格，定位到 " + hits + " 个“" + path.substring(path.lastIndexOf('/') + 1) + "”堆栈，共 " + count + " 个。");
        return count;
    }

    private OcrRead readOcr(Bitmap source, Rect baseRegion, boolean chinese, int scale) throws Exception {
        Rect rect = pixelRegion(baseRegion, source.getWidth(), source.getHeight());
        if (rect.width() <= 0 || rect.height() <= 0) return new OcrRead("", 0);
        Bitmap crop = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height());
        Bitmap input = crop;
        try {
            int factor = Math.max(1, Math.min(4, scale));
            if (factor > 1) {
                input = Bitmap.createScaledBitmap(crop, Math.max(1, crop.getWidth() * factor),
                        Math.max(1, crop.getHeight() * factor), true);
            }
            ScriptOcr.Result result = ocr.recognize(input, chinese, ScriptOcr.LUA_TIMEOUT_MS,
                    Math.max(input.getWidth(), input.getHeight()));
            float confidence = 0;
            int scores = 0;
            try {
                JSONArray lines = new JSONArray(result.json);
                for (int i = 0; i < lines.length(); i++) {
                    JSONObject line = lines.optJSONObject(i);
                    if (line != null && line.has("score")) { confidence += (float) line.optDouble("score", 0); scores++; }
                }
            } catch (JSONException ignored) { }
            return new OcrRead(result.text, scores == 0 ? 0 : confidence / scores * 100f);
        } finally {
            if (input != crop && !input.isRecycled()) input.recycle();
            if (!crop.isRecycled()) crop.recycle();
        }
    }

    private Match findTemplate(Bitmap frame, String path, Rect region, float threshold) throws Exception {
        Bitmap source = workflowPackage.images.get(path);
        if (source == null) throw new IllegalStateException("工作流素材不可读取：" + path);
        return findTemplate(frame, source, region, threshold);
    }

    private Match findTemplate(Bitmap frame, Bitmap original, Rect region, float threshold) {
        return findTemplate(frame, original, region, threshold, false);
    }

    private Match findTemplate(Bitmap frame, Bitmap original, Rect region, float threshold,
                               boolean coloredInventoryPixelsOnly) {
        if (original == null || original.isRecycled()) return null;
        Rect bounds = pixelRegion(region, frame.getWidth(), frame.getHeight());
        int targetWidth = Math.max(1, Math.round(original.getWidth() * frame.getWidth() / (float) coordinateWidth));
        int targetHeight = Math.max(1, Math.round(original.getHeight() * frame.getHeight() / (float) coordinateHeight));
        if (targetWidth > frame.getWidth() || targetHeight > frame.getHeight()
                || bounds.width() < targetWidth || bounds.height() < targetHeight) return null;
        String cacheKey = System.identityHashCode(original) + ":" + targetWidth + "x" + targetHeight;
        Bitmap template = scaledTemplates.get(cacheKey);
        if (template == null || template.isRecycled()) {
            template = targetWidth == original.getWidth() && targetHeight == original.getHeight()
                    ? original : Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true);
            scaledTemplates.put(cacheKey, template);
        }
        int[] screen = pixels(frame), target = pixels(template);
        int sampleStep = Math.max(1, Math.min(targetWidth, targetHeight) / 20);
        int positionStep = Math.max(1, Math.min(targetWidth, targetHeight) / 28);
        int sampleCount = 0;
        for (int y = 0; y < targetHeight; y += sampleStep) for (int x = 0; x < targetWidth; x += sampleStep) {
            int pixel = target[y * targetWidth + x];
            if ((pixel >>> 24) >= 32
                    && (!coloredInventoryPixelsOnly || isColoredInventoryPixel(pixel))) sampleCount++;
        }
        if (sampleCount == 0) return null;
        threshold = Math.max(0, Math.min(1, threshold));
        // A coarse-grid position can be a few pixels away from the real match.
        // Use it only to find a candidate; the full-resolution pass decides.
        double coarseThreshold = Math.max(0d, threshold - 0.08d);
        double maxError = (1d - coarseThreshold) * 255d * sampleCount;
        int bestX = -1, bestY = -1;
        double bestScore = -1;
        for (int y = bounds.top; y <= bounds.bottom - targetHeight; y += positionStep) {
            checkpoint();
            for (int x = bounds.left; x <= bounds.right - targetWidth; x += positionStep) {
                double error = 0;
                int seen = 0;
                boolean over = false;
                for (int ty = 0; ty < targetHeight; ty += sampleStep) {
                    for (int tx = 0; tx < targetWidth; tx += sampleStep) {
                        int expected = target[ty * targetWidth + tx];
                        if ((expected >>> 24) < 32
                                || (coloredInventoryPixelsOnly && !isColoredInventoryPixel(expected))) continue;
                        int actual = screen[(y + ty) * frame.getWidth() + x + tx];
                        error += (Math.abs(Color.red(expected) - Color.red(actual))
                                + Math.abs(Color.green(expected) - Color.green(actual))
                                + Math.abs(Color.blue(expected) - Color.blue(actual))) / 3d;
                        seen++;
                        // Remaining samples can reduce the average error. Reject
                        // only when even perfect remaining pixels cannot pass.
                        if (error > maxError) { over = true; break; }
                    }
                    if (over) break;
                }
                if (over || seen == 0) continue;
                double score = 1d - error / seen / 255d;
                if (score > bestScore) { bestScore = score; bestX = x; bestY = y; }
            }
        }
        if (bestX < 0 || bestScore < coarseThreshold) return null;
        int fine = Math.max(1, positionStep);
        int x1 = Math.max(bounds.left, bestX - fine + 1), x2 = Math.min(bounds.right - targetWidth, bestX + fine - 1);
        int y1 = Math.max(bounds.top, bestY - fine + 1), y2 = Math.min(bounds.bottom - targetHeight, bestY + fine - 1);
        int verifiedX = -1, verifiedY = -1;
        double verifiedScore = threshold;
        for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) {
            double score = scoreAt(screen, frame.getWidth(), target, targetWidth, targetHeight,
                    x, y, 1, coloredInventoryPixelsOnly);
            if (score >= verifiedScore) { verifiedScore = score; verifiedX = x; verifiedY = y; }
        }
        return verifiedX < 0 ? null : new Match(verifiedX + targetWidth / 2f,
                verifiedY + targetHeight / 2f, (float) verifiedScore);
    }

    private static double scoreAt(int[] screen, int screenWidth, int[] target, int targetWidth,
                                  int targetHeight, int x, int y, int step,
                                  boolean coloredInventoryPixelsOnly) {
        int seen = 0;
        double error = 0;
        for (int ty = 0; ty < targetHeight; ty += step) for (int tx = 0; tx < targetWidth; tx += step) {
            int expected = target[ty * targetWidth + tx];
            if ((expected >>> 24) < 32
                    || (coloredInventoryPixelsOnly && !isColoredInventoryPixel(expected))) continue;
            int actual = screen[(y + ty) * screenWidth + x + tx];
            error += (Math.abs(Color.red(expected) - Color.red(actual))
                    + Math.abs(Color.green(expected) - Color.green(actual))
                    + Math.abs(Color.blue(expected) - Color.blue(actual))) / 3d;
            seen++;
        }
        return seen == 0 ? 0 : 1d - error / seen / 255d;
    }

    private static boolean isColoredInventoryPixel(int color) {
        int red = Color.red(color), green = Color.green(color), blue = Color.blue(color);
        int brightest = Math.max(red, Math.max(green, blue));
        int darkest = Math.min(red, Math.min(green, blue));
        // Keep the same colored-pixel filter used by CoraFlow's desktop
        // inventory matcher. It removes dark slot/background pixels while
        // retaining the red/blue bottle and its highlights.
        return brightest >= 60 && brightest - darkest >= 24;
    }

    private Match findColor(Bitmap frame, String value, Rect region, int tolerance) {
        String colorText = value == null ? "" : value.trim();
        if (!colorText.matches("#?[0-9a-fA-F]{6}")) throw new IllegalStateException("颜色必须是 #RRGGBB 格式");
        int target = Color.parseColor(colorText.startsWith("#") ? colorText : "#" + colorText);
        Rect bounds = pixelRegion(region, frame.getWidth(), frame.getHeight());
        int step = Math.max(1, Math.min(bounds.width(), bounds.height()) / 80);
        int[] pixels = pixels(frame);
        int bestX = -1, bestY = -1, best = Integer.MAX_VALUE;
        for (int y = bounds.top; y < bounds.bottom; y += step) for (int x = bounds.left; x < bounds.right; x += step) {
            int pixel = pixels[y * frame.getWidth() + x];
            int distance = Math.max(Math.abs(Color.red(target) - Color.red(pixel)),
                    Math.max(Math.abs(Color.green(target) - Color.green(pixel)), Math.abs(Color.blue(target) - Color.blue(pixel))));
            if (distance <= tolerance && distance < best) { best = distance; bestX = x; bestY = y; }
        }
        return bestX < 0 ? null : new Match(bestX, bestY, 1f - best / 255f);
    }

    private int[] regionSample(Bitmap frame, Rect region) {
        Rect bounds = pixelRegion(region, frame.getWidth(), frame.getHeight());
        int[] source = pixels(frame);
        int[] result = new int[32 * 18];
        int index = 0;
        for (int row = 0; row < 18; row++) for (int column = 0; column < 32; column++) {
            int x = bounds.left + Math.min(Math.max(0, bounds.width() - 1), column * Math.max(1, bounds.width()) / 32);
            int y = bounds.top + Math.min(Math.max(0, bounds.height() - 1), row * Math.max(1, bounds.height()) / 18);
            result[index++] = source[y * frame.getWidth() + x] & 0x00ffffff;
        }
        return result;
    }

    private static double changedFraction(int[] before, int[] after) {
        if (before.length != after.length || before.length == 0) return 1d;
        int changed = 0;
        for (int i = 0; i < before.length; i++) {
            int a = before[i], b = after[i];
            if (Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                    + Math.abs(Color.blue(a) - Color.blue(b)) > 45) changed++;
        }
        return changed / (double) before.length;
    }

    private void addNumberSample(String id, int value) {
        ArrayDeque<NumberSample> history = numberHistory.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        history.addLast(new NumberSample(value, SystemClock.elapsedRealtime()));
        while (history.size() > 4) history.removeFirst();
    }

    private void click(double x, double y) throws Exception {
        checkpoint();
        if (screenWidth <= 0 || screenHeight <= 0) throw new IllegalStateException("目标截图尺寸尚未确定");
        double deviceX = x * screenWidth / coordinateWidth;
        double deviceY = y * screenHeight / coordinateHeight;
        if (!game.tap((float) deviceX, (float) deviceY, 60, CAPTURE_TIMEOUT_MS)) {
            throw new IllegalStateException("Android 无障碍触控未完成：工作流 (" + Math.round(x) + "," + Math.round(y)
                    + ") → 设备 (" + Math.round(deviceX) + "," + Math.round(deviceY) + ")");
        }
    }

    private void drag(JSONObject config) throws Exception {
        checkpoint();
        double scaleX = screenWidth / (double) coordinateWidth;
        double scaleY = screenHeight / (double) coordinateHeight;
        long duration = Math.max(30L, Math.min(5000L,
                Math.round(config.optDouble("duration", 0.5) * 1000)));
        boolean completed = game.swipe((float) (config.optDouble("x1", 0) * scaleX),
                (float) (config.optDouble("y1", 0) * scaleY),
                (float) (config.optDouble("x2", 0) * scaleX),
                (float) (config.optDouble("y2", 0) * scaleY), duration, CAPTURE_TIMEOUT_MS);
        if (!completed) throw new IllegalStateException("Android 无障碍拖动未完成");
        long releaseDelay = Math.max(0L, Math.min(5000L, config.optLong("release_delay", 0L)));
        if (releaseDelay > 0) control.sleep(releaseDelay);
    }

    private void tapPixels(double x, double y, int frameWidth, int frameHeight) throws Exception {
        click(x * coordinateWidth / frameWidth, y * coordinateHeight / frameHeight);
    }

    private Bitmap capture() throws Exception {
        checkpoint();
        Bitmap frame = game.captureDisplay(CAPTURE_TIMEOUT_MS);
        screenWidth = frame.getWidth();
        screenHeight = frame.getHeight();
        return frame;
    }

    private void checkpoint() { control.checkpoint(); }

    private void note(String message) {
        if (listener != null && message != null && !message.isEmpty()) {
            try { listener.accept(message); } catch (RuntimeException ignored) { }
        }
    }

    private void executeSellNode(String name, JSONObject config) {
        try {
            if (config.optBoolean("sell_medicine_surplus", false)) {
                throw new IllegalStateException("安全停止：装备出售节点不允许出售药品；药品只由独立补药节点处理。");
            }
            if (config.optBoolean("emergency_discard_white", false)) {
                executeEmergencyDiscard(name, config);
            } else {
                executeEquipmentSale(name, config);
            }
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("“" + name + "”执行失败：" + rootMessage(failure), failure);
        }
    }

    private void executeEquipmentSale(String name, JSONObject config) throws Exception {
        final int[] columns = {1058, 1160, 1265, 1375, 1480};
        final int[] rows = {270, 375, 483, 589, 696, 800, 910};
        String shopTitlePath = "captures/templates/RYLUX_挂机_大夫商店标题.png";
        String inventoryTitlePath = "captures/templates/RYLUX_挂机_背包标题.png";
        String sellButtonPath = "captures/templates/RYLUX_挂机_医生出售按钮.png";
        String saleTitlePath = "captures/templates/RYLUX_挂机_出售确认标题.png";
        String saleConfirmPath = "captures/templates/RYLUX_挂机_出售确认按钮.png";
        String detailCornerPath = "captures/templates/RYLUX_背包_物品详情左上角_1920.png";
        requireImage(shopTitlePath);
        requireImage(inventoryTitlePath);
        requireImage(sellButtonPath);
        requireImage(saleTitlePath);
        requireImage(saleConfirmPath);
        requireImage(detailCornerPath);

        Bitmap shopStart = capture();
        try {
            Match shop = findTemplate(shopStart, shopTitlePath, new Rect(610, 45, 840, 118), 0.86f);
            if (shop == null) throw new IllegalStateException("当前画面没有匹配到大夫商店“商号”标题；背包单独页面不能出售。");
            note("确认处于大夫商店（标题命中 " + percent(shop.score * 100) + "）；开始装备扫描。");
        } finally { shopStart.recycle(); }
        click(1075, 170);
        control.sleep(250);

        Bitmap initial = capture();
        Set<Integer> medicineSlots;
        try { medicineSlots = locateMedicineSlots(initial, config); }
        finally { initial.recycle(); }
        if (medicineSlots.isEmpty()) note("药瓶图标没有命中；每件物品仍须通过功力行和品质双重确认后才可出售。");
        else note("药瓶格已定位并加入出售保护：" + medicineSlots + "。");

        int scanned = 0, sold = 0, candidates = 0, skipped = 0, recognized = 0;
        int white = 0, blue = 0, unknownQuality = 0, protectedBlue = 0, nonGear = 0;
        boolean sellWhite = config.optBoolean("sell_white", true);
        boolean sellBlue = config.optBoolean("sell_blue", true);
        boolean preserveBlueRecovery = config.optBoolean("preserve_blue_recovery", true);
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < columns.length; column++) {
                checkpoint();
                int slot = row * 5 + column + 1;
                scanned++;
                if (scanned == 1 || scanned % 5 == 0) note("背包装备扫描进度：" + scanned + "/35 格。");
                if (medicineSlots.contains(slot)) { skipped++; continue; }

                Bitmap item = selectInventorySlot(name, columns[column], rows[row], slot);
                if (item == null) { skipped++; nonGear++; continue; }
                try {
                    OcrRead detail = readOcr(item, new Rect(960, 120, 1605, 1010), true, 2);
                    YellowTextCandidate powerLine = null;
                    OcrRead powerText = new OcrRead("", 0);
                    for (YellowTextCandidate candidate : yellowTextCandidates(item, new Rect(960, 120, 1605, 1010))) {
                        OcrRead recognizedLine = readOcr(item, candidate.region, true, 4);
                        String normalized = recognizedLine.text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
                        if (normalized.matches(".*(?:功[力为為刀]|裝備功|装备功).*")) {
                            powerLine = candidate;
                            powerText = recognizedLine;
                            break;
                        }
                    }
                    String allText = (detail.text + " " + powerText.text).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                    if (powerLine == null || powerLine.pixels < 12
                            || allText.matches(".*(?:回城|返城|传送符|传送卷|townscroll|returnscroll|teleport|phùvềthành|phuvethanh).*")) {
                        nonGear++; skipped++;
                        note("第 " + slot + "/35 格未通过装备功力行确认，保留。");
                        continue;
                    }
                    recognized++;
                    note("第 " + slot + "/35 格通过装备功力行确认：“" + powerText.text.trim() + "”。");

                    Match sellButton = findTemplate(item, sellButtonPath, new Rect(1450, 300, 1900, 850), 0.84f);
                    if (sellButton == null) {
                        note("第 " + slot + "/35 格未识别到当前详情面板的 Bán 按钮；停止，不会点猜测坐标。");
                        throw new IllegalStateException("识别到装备，但没有在当前详情面板找到 Bán（出售）按钮；没有点击出售。");
                    }
                    double buttonX = sellButton.x * coordinateWidth / item.getWidth();
                    double buttonY = sellButton.y * coordinateHeight / item.getHeight();
                    Rect title = new Rect((int) buttonX - 390 - 170, (int) buttonY - 370 - 55,
                            (int) buttonX - 390 + 170, (int) buttonY - 370 + 55);
                    int whitePixels = countColorPixels(item, "#FFFFFF", title, 24);
                    int bluePixels = countColorPixels(item, "#6364FF", title, 28);
                    boolean whiteTitle = whitePixels >= 8;
                    boolean blueTitle = bluePixels >= 8;
                    boolean isWhite = whiteTitle && (!blueTitle || whitePixels >= bluePixels * 2);
                    boolean isBlue = blueTitle && (!whiteTitle || bluePixels >= whitePixels * 2);
                    if (!isWhite && !isBlue) {
                        unknownQuality++; skipped++;
                        note("第 " + slot + "/35 格品质颜色不明确（白 " + whitePixels + "、蓝 " + bluePixels + " 像素），保留。");
                        continue;
                    }
                    if (isWhite) white++; else blue++;
                    if ((isWhite && !sellWhite) || (isBlue && !sellBlue)) { skipped++; continue; }
                    candidates++;
                    if (isBlue && preserveBlueRecovery) {
                        OcrRead stats = readOcr(item, new Rect(1000, 420, 1600, 850), true, 4);
                        String combined = (detail.text + stats.text).replaceAll("\\s+", "");
                        if (combined.matches(".*(?:内力回复|内力恢复|生命回复|生命恢复|气血回复|气血恢复|回血|回蓝|回内|回红).*")) {
                            protectedBlue++; skipped++;
                            note("第 " + slot + "/35 格蓝装检测到回血/回内词条，保留。");
                            continue;
                        }
                        if (stats.confidence < 45) {
                            skipped++;
                            note("第 " + slot + "/35 格蓝装属性 OCR 不确定（" + percent(stats.confidence) + "），保护不出售。");
                            continue;
                        }
                    }
                    InventoryMetric beforeLoad = readInventoryMetric(item, new Rect(1675, 343, 1800, 384));
                    InventoryMetric beforeSlots = readInventoryMetric(item, new Rect(1682, 927, 1775, 963));
                    if (beforeLoad == null && beforeSlots == null) throw new IllegalStateException("无法可靠读取出售前负重和空格；没有点击出售。");
                    note("第 " + slot + "/35 格确认是" + (isWhite ? "白" : "蓝") + "色装备，Bán 命中 "
                            + percent(sellButton.score * 100) + "；出售前负重 " + metricText(beforeLoad) + "，空格 " + metricText(beforeSlots) + "。");
                    tapPixels(sellButton.x, sellButton.y, item.getWidth(), item.getHeight());
                    control.sleep(350);
                    Bitmap confirm = capture();
                    try {
                        Match titleMatch = findTemplate(confirm, saleTitlePath, new Rect(860, 280, 1050, 355), 0.84f);
                        Match confirmButton = findTemplate(confirm, saleConfirmPath, new Rect(700, 710, 940, 805), 0.84f);
                        if (titleMatch == null || confirmButton == null) {
                            throw new IllegalStateException("出售确认弹窗标题和按钮未同时匹配；已停止，未确认出售。");
                        }
                        tapPixels(confirmButton.x, confirmButton.y, confirm.getWidth(), confirm.getHeight());
                    } finally { confirm.recycle(); }
                    control.sleep(1000);
                    Bitmap after = capture();
                    try {
                        Match stillOpen = findTemplate(after, saleTitlePath, new Rect(860, 280, 1050, 355), 0.84f);
                        if (stillOpen != null) {
                            Match again = findTemplate(after, saleConfirmPath, new Rect(700, 710, 940, 805), 0.84f);
                            if (again == null) throw new IllegalStateException("出售确认弹窗仍在，但确认按钮未复核；停止，避免重复出售。");
                            note("确认弹窗仍可见，标题和按钮复核通过；仅重试确认一次。");
                            tapPixels(again.x, again.y, after.getWidth(), after.getHeight());
                            control.sleep(1000);
                            after.recycle();
                            after = capture();
                            stillOpen = findTemplate(after, saleTitlePath, new Rect(860, 280, 1050, 355), 0.84f);
                            if (stillOpen != null) throw new IllegalStateException("确认出售重试后弹窗仍然存在；停止。");
                        }
                        InventoryMetric afterLoad = readInventoryMetric(after, new Rect(1675, 343, 1800, 384));
                        InventoryMetric afterSlots = readInventoryMetric(after, new Rect(1682, 927, 1775, 963));
                        if (beforeSlots != null && afterSlots != null && afterSlots.value < beforeSlots.value) {
                            InventoryMetric expanded = readInventoryMetric(after, new Rect(1665, 922, 1805, 975));
                            if (expanded != null && expanded.value >= beforeSlots.value) afterSlots = expanded;
                            else afterSlots = null;
                        }
                        boolean loadChanged = beforeLoad != null && afterLoad != null;
                        boolean slotsChanged = beforeSlots != null && afterSlots != null;
                        boolean loadDown = loadChanged && afterLoad.value < beforeLoad.value;
                        boolean slotsUp = slotsChanged && afterSlots.value > beforeSlots.value;
                        if ((!loadChanged && !slotsChanged) || (!loadDown && !slotsUp)
                                || (loadChanged && afterLoad.value > beforeLoad.value)) {
                            throw new IllegalStateException("点击出售确认后未证实物品移除：负重 " + metricValue(beforeLoad) + "→"
                                    + metricValue(afterLoad) + "，空格 " + metricValue(beforeSlots) + "→" + metricValue(afterSlots) + "。");
                        }
                        sold++;
                        note("出售已验证：" + (isWhite ? "白" : "蓝") + "色装备；负重 " + metricValue(beforeLoad)
                                + "→" + metricValue(afterLoad) + "，空格 " + metricValue(beforeSlots) + "→" + metricValue(afterSlots) + "。");
                    } finally { after.recycle(); }
                    column--; // Revisit this cell in case the game compacts the inventory grid.
                } finally { item.recycle(); }
            }
        }
        String summary = "扫描 " + scanned + "/35 格，确认装备 " + recognized + " 件（白 " + white + "、蓝 " + blue
                + "、品质不明 " + unknownQuality + "），出售候选 " + candidates + " 件，验证出售 " + sold
                + " 件，保护回复蓝装 " + protectedBlue + " 件，跳过 " + skipped + " 格。";
        note((sold > 0 ? "装备扫描完成：" : "装备扫描未出售任何物品：") + summary);
        if (config.optBoolean("require_verified_sale", false) && sold == 0) {
            throw new IllegalStateException("清包触发后没有任何装备通过出售验证；" + summary);
        }
    }

    private void executeEmergencyDiscard(String name, JSONObject config) throws Exception {
        InventoryMetric load;
        InventoryMetric slots;
        Bitmap start = capture();
        Set<Integer> medicineSlots;
        try {
            load = readInventoryMetric(start, new Rect(1675, 343, 1800, 384));
            slots = readInventoryMetric(start, new Rect(1682, 927, 1775, 963));
            if (slots == null) slots = readInventoryMetric(start, new Rect(1665, 922, 1805, 975));
            medicineSlots = locateMedicineSlots(start, config);
        } finally { start.recycle(); }
        int loadLimit = Math.max(1, config.optInt("discard_load_limit", 200));
        int minimumFree = Math.max(1, Math.min(34, config.optInt("discard_min_free_slots", 1)));
        if (load == null || slots == null) throw new IllegalStateException("满包应急拒绝执行：无法可靠读取负重和空格；没有丢弃物品。");
        if (load.value < loadLimit && slots.value >= minimumFree) {
            note("满包应急无需丢弃：负重 " + load.text + "，空格 " + slots.text + ".");
            return;
        }
        note("满包应急开始：仅核验前排白色装备；药瓶、回城/传送物品和未知物品保持保护。");
        int end = Math.max(1, Math.min(35, config.optInt("discard_front_slot_end", 35)));
        int maxItems = Math.max(1, Math.min(34, config.optInt("discard_max_items", 34)));
        int discarded = 0, failedWhite = 0;
        int[] columns = {1058, 1160, 1265, 1375, 1480};
        int[] rows = {270, 375, 483, 589, 696, 800, 910};
        boolean allowBlue = config.optBoolean("emergency_discard_blue_fallback", false);
        for (int pass = 0; pass <= (allowBlue ? 1 : 0) && discarded < maxItems; pass++) {
            boolean inspectBlue = pass == 1;
            if (inspectBlue) {
                if (failedWhite > 0) {
                    note("发现白装丢弃未通过按钮复核；按先白后蓝规则，不执行蓝装兜底。");
                    break;
                }
                if (load != null && load.value < loadLimit && slots != null && slots.value >= minimumFree) break;
                note("前排白装扫描后仍达到满包应急条件；开始严格核验无回血/回内词条的蓝装兜底。");
            }
            for (int slot = 1; slot <= end && discarded < maxItems; slot++) {
                checkpoint();
                if (slot == 1 || medicineSlots.contains(slot)) continue;
                Bitmap item = selectInventorySlot(name, columns[(slot - 1) % 5], rows[(slot - 1) / 5], slot);
                if (item == null) continue;
                try {
                    ItemProof proof = inspectEquipment(item);
                    if (!proof.isGear || (inspectBlue ? !proof.isBlue : !proof.isWhite)) continue;
                    if (inspectBlue) {
                        OcrRead stats = readOcr(item, new Rect(1000, 380, 1600, 850), true, 4);
                        String statText = (stats.text + " ").replaceAll("\\s+", "");
                        if (stats.confidence < 45 || statText.matches(".*(?:内力回复|内力恢复|生命回复|生命恢复|气血回复|气血恢复|回血|回蓝|回内|回红).*")) {
                            note("第 " + slot + "/35 格蓝装属性有回复词条或 OCR 不确定，保护不丢。");
                            continue;
                        }
                    }
                    InventoryMetric beforeLoad = readInventoryMetric(item, new Rect(1675, 343, 1800, 384));
                    InventoryMetric beforeSlots = readInventoryMetric(item, new Rect(1682, 927, 1775, 963));
                    int redButtonPixels = countColorPixels(item, "#FF9A85", new Rect(1600, 960, 1835, 1035), 40);
                    if (redButtonPixels < 1000 || beforeLoad == null || beforeSlots == null) {
                        if (!inspectBlue) failedWhite++;
                        continue;
                    }
                    note("满包应急准备丢弃已确认" + (inspectBlue ? "蓝" : "白") + "装：第 " + slot
                            + "/35 格，功力行命中；负重 " + beforeLoad.text + "，空格 " + beforeSlots.text + "。");
                    click(1715, 992);
                    control.sleep(650);
                    Bitmap after = capture();
                    try {
                        InventoryMetric afterLoad = readInventoryMetric(after, new Rect(1675, 343, 1800, 384));
                        InventoryMetric afterSlots = readInventoryMetric(after, new Rect(1682, 927, 1775, 963));
                        if (afterSlots == null) afterSlots = readInventoryMetric(after, new Rect(1665, 922, 1805, 975));
                        boolean loadDown = afterLoad != null && afterLoad.value < beforeLoad.value;
                        boolean slotsUp = afterSlots != null && afterSlots.value > beforeSlots.value;
                        if (!loadDown && !slotsUp) throw new IllegalStateException("点丢弃后负重和空格均无可验证变化；停止，不会继续丢弃。");
                        discarded++;
                        load = afterLoad;
                        slots = afterSlots;
                        note("应急丢弃已验证：负重 " + metricValue(beforeLoad) + "→" + metricValue(afterLoad)
                                + "，空格 " + metricValue(beforeSlots) + "→" + metricValue(afterSlots) + "。");
                    } finally { after.recycle(); }
                    if (load != null && load.value < loadLimit && slots != null && slots.value >= minimumFree) break;
                    slot--; // Re-check this cell after the game may have compacted the grid.
                } finally { item.recycle(); }
            }
            if (load != null && load.value < loadLimit && slots != null && slots.value >= minimumFree) break;
        }
        if (load == null || slots == null || load.value >= loadLimit || slots.value < minimumFree) {
            String blue = allowBlue ? "已按白装优先规则扫描允许丢弃的蓝装兜底。" : "蓝装兜底未启用。";
            throw new IllegalStateException("满包应急未恢复空间：已验证丢弃 " + discarded + " 件白装，跳过 " + failedWhite
                    + " 格；当前负重 " + metricValue(load) + "，空格 " + metricValue(slots) + "。" + blue);
        }
        note("满包应急完成：已验证丢弃 " + discarded + " 件白装；负重 " + load.text + "，空格 " + slots.text + "。");
    }

    private static final class ItemProof {
        final boolean isGear;
        final boolean isWhite;
        final boolean isBlue;
        final String description;
        ItemProof(boolean isGear, boolean isWhite, boolean isBlue, String description) {
            this.isGear = isGear; this.isWhite = isWhite; this.isBlue = isBlue; this.description = description;
        }
    }

    private ItemProof inspectEquipment(Bitmap item) throws Exception {
        OcrRead detail = readOcr(item, new Rect(960, 120, 1605, 1010), true, 2);
        boolean gear = false;
        int yellowPixels = 0;
        String power = "";
        for (YellowTextCandidate candidate : yellowTextCandidates(item, new Rect(960, 120, 1605, 1010))) {
            OcrRead line = readOcr(item, candidate.region, true, 4);
            String normalized = line.text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (normalized.matches(".*(?:功[力为為刀]|裝備功|装备功).*")) {
                gear = candidate.pixels >= 12;
                yellowPixels = candidate.pixels;
                power = line.text;
                break;
            }
        }
        String normalized = (detail.text + power).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        boolean knownNonGear = normalized.matches(".*(?:回城|返城|传送符|传送卷|townscroll|returnscroll|teleport|phùvềthành|phuvethanh).*");
        gear &= !knownNonGear;
        int whitePixels = countColorPixels(item, "#FFFFFF", new Rect(1200, 135, 1530, 190), 24);
        int bluePixels = countColorPixels(item, "#6364FF", new Rect(1200, 135, 1530, 190), 28);
        boolean white = whitePixels >= 8 && (bluePixels < 8 || whitePixels >= bluePixels * 2);
        boolean blue = bluePixels >= 8 && (whitePixels < 8 || bluePixels >= whitePixels * 2);
        return new ItemProof(gear, white, blue, "功力行像素 " + yellowPixels + "，OCR“" + power + "”；标题白 " + whitePixels + " / 蓝 " + bluePixels);
    }

    private Bitmap selectInventorySlot(String name, int x, int y, int slot) throws Exception {
        Bitmap before = capture();
        try {
            Match detail = findTemplate(before, "captures/templates/RYLUX_背包_物品详情左上角_1920.png",
                    new Rect(400, 70, 1550, 300), 0.97f);
            if (detail != null) {
                click(1200, 80);
                control.sleep(250);
                before.recycle();
                before = capture();
                detail = findTemplate(before, "captures/templates/RYLUX_背包_物品详情左上角_1920.png",
                        new Rect(400, 70, 1550, 300), 0.97f);
                if (detail != null) {
                    if (!game.inputKey("escape")) throw new IllegalStateException("无法关闭遮挡背包的物品详情。");
                    control.sleep(250);
                    before.recycle();
                    before = capture();
                    detail = findTemplate(before, "captures/templates/RYLUX_背包_物品详情左上角_1920.png",
                            new Rect(400, 70, 1550, 300), 0.97f);
                }
                if (detail != null) throw new IllegalStateException("物品详情仍遮挡背包格子；已停止扫描。");
            }
            if (findTemplate(before, "captures/templates/RYLUX_挂机_背包标题.png", new Rect(1080, 20, 1420, 140), 0.88f) == null) {
                throw new IllegalStateException("选格前未确认背包标题；没有继续选择物品。");
            }
            click(x, y);
            control.sleep(350);
            Bitmap after = capture();
            boolean changed = regionChanged(before, after, new Rect(960, 120, 1605, 1010), 0.02)
                    || regionChanged(before, after, new Rect(x - 48, y - 48, x + 48, y + 48), 0.005);
            before.recycle();
            if (!changed) {
                after.recycle();
                note("第 " + slot + "/35 格详情未发生可见变化，跳过，未读取或操作。");
                return null;
            }
            return after;
        } finally { if (before != null && !before.isRecycled()) before.recycle(); }
    }

    private Set<Integer> locateMedicineSlots(Bitmap frame, JSONObject config) throws Exception {
        Set<Integer> found = new HashSet<>();
        Rect grid = region(config.optJSONArray("medicine_grid_region"));
        if (grid.width() < 200 || grid.height() < 300) grid = new Rect(1000, 214, 1537, 965);
        float threshold = (float) Math.max(0.7, Math.min(0.99, config.optDouble("medicine_icon_threshold", 0.92)));
        String[] paths = {config.optString("medicine_red_image", ""), config.optString("medicine_blue_image", "")};
        for (String path : paths) {
            if (path == null || path.trim().isEmpty() || found.size() >= 35) continue;
            Bitmap icon = workflowPackage.images.get(path.trim());
            if (icon == null) throw new IllegalStateException("缺少药瓶保护图标素材：" + path);
            for (int slot = 1; slot <= 35; slot++) {
                int row = (slot - 1) / 5, column = (slot - 1) % 5;
                int cellW = grid.width() / 5, cellH = grid.height() / 7;
                Rect cell = new Rect(grid.left + column * cellW + 3, grid.top + row * cellH + 3,
                        column == 4 ? grid.right : grid.left + (column + 1) * cellW - 3,
                        row == 6 ? grid.bottom : grid.top + (row + 1) * cellH - 3);
                if (findTemplate(frame, icon, cell, threshold) != null) found.add(slot);
                else if (threshold > 0.85f && findTemplate(frame, icon, cell, Math.max(0.85f, threshold - 0.03f)) != null) {
                    throw new IllegalStateException("第 " + slot + "/35 格可能是药瓶，但图标置信度不足；停止出售，避免误卖。");
                }
            }
        }
        return found;
    }

    private void requireImage(String path) {
        if (!workflowPackage.images.containsKey(path)) throw new IllegalStateException("工作流包缺少必要素材：“" + path + "”");
    }

    private InventoryMetric readInventoryMetric(Bitmap frame, Rect region) throws Exception {
        InventoryMetric best = null;
        for (int scale : new int[]{4, 3, 2}) {
            OcrRead result = readOcr(frame, region, false, scale);
            Integer value = parseNumber(result.text);
            if (value != null && result.confidence >= 35
                    && (best == null || result.confidence > best.confidence)) {
                best = new InventoryMetric(value, result.text.trim(), result.confidence);
            }
            if (best != null && best.confidence >= 80) break;
        }
        return best;
    }

    private String metricText(InventoryMetric value) {
        return value == null ? "未可靠识别" : value.text + "（" + percent(value.confidence) + "）";
    }
    private static String metricValue(InventoryMetric value) { return value == null ? "未知" : String.valueOf(value.value); }

    private int countColorPixels(Bitmap frame, String colorText, Rect baseRegion, int tolerance) {
        int target = Color.parseColor(colorText);
        Rect bounds = pixelRegion(baseRegion, frame.getWidth(), frame.getHeight());
        int[] data = pixels(frame);
        int count = 0;
        for (int y = bounds.top; y < bounds.bottom; y++) for (int x = bounds.left; x < bounds.right; x++) {
            int pixel = data[y * frame.getWidth() + x];
            int distance = Math.max(Math.abs(Color.red(target) - Color.red(pixel)),
                    Math.max(Math.abs(Color.green(target) - Color.green(pixel)), Math.abs(Color.blue(target) - Color.blue(pixel))));
            if (distance <= tolerance) count++;
        }
        return count;
    }

    private java.util.List<YellowTextCandidate> yellowTextCandidates(Bitmap frame, Rect baseRegion) {
        Rect bounds = pixelRegion(baseRegion, frame.getWidth(), frame.getHeight());
        int[] data = pixels(frame);
        java.util.List<YellowTextCandidate> result = new java.util.ArrayList<>();
        int groupTop = -1, groupBottom = -1, groupPixels = 0, groupLeft = frame.getWidth(), groupRight = -1;
        for (int y = bounds.top; y < bounds.bottom; y++) {
            int rowCount = 0, rowLeft = frame.getWidth(), rowRight = -1;
            for (int x = bounds.left; x < bounds.right; x++) {
                int pixel = data[y * frame.getWidth() + x];
                int red = Color.red(pixel), green = Color.green(pixel), blue = Color.blue(pixel);
                if (red >= 190 && green >= 135 && blue <= 110 && red - blue >= 90 && green - blue >= 35) {
                    rowCount++; rowLeft = Math.min(rowLeft, x); rowRight = Math.max(rowRight, x);
                }
            }
            if (rowCount >= 2) {
                if (groupTop >= 0 && y - groupBottom <= 3) {
                    groupBottom = y; groupPixels += rowCount; groupLeft = Math.min(groupLeft, rowLeft); groupRight = Math.max(groupRight, rowRight);
                } else {
                    addYellowCandidate(result, frame, groupTop, groupBottom, groupPixels, groupLeft, groupRight);
                    groupTop = y; groupBottom = y; groupPixels = rowCount; groupLeft = rowLeft; groupRight = rowRight;
                }
            } else if (groupTop >= 0 && y - groupBottom > 3) {
                addYellowCandidate(result, frame, groupTop, groupBottom, groupPixels, groupLeft, groupRight);
                groupTop = -1; groupBottom = -1; groupPixels = 0; groupLeft = frame.getWidth(); groupRight = -1;
            }
        }
        addYellowCandidate(result, frame, groupTop, groupBottom, groupPixels, groupLeft, groupRight);
        result.sort((a, b) -> Integer.compare(b.y, a.y));
        return result;
    }

    private void addYellowCandidate(java.util.List<YellowTextCandidate> result, Bitmap frame, int top, int bottom,
                                    int count, int left, int right) {
        if (top < 0 || count < 12 || right - left < 24) return;
        int x1 = Math.max(0, left - 12), x2 = Math.min(frame.getWidth(), right + 13);
        int y1 = Math.max(0, top - 5), y2 = Math.min(frame.getHeight(), bottom + 7);
        Rect base = new Rect(x1 * coordinateWidth / frame.getWidth(), y1 * coordinateHeight / frame.getHeight(),
                x2 * coordinateWidth / frame.getWidth(), y2 * coordinateHeight / frame.getHeight());
        result.add(new YellowTextCandidate(base, count, bottom));
    }

    private boolean regionChanged(Bitmap before, Bitmap after, Rect region, double threshold) {
        int[] a = regionSample(before, region), b = regionSample(after, region);
        return changedFraction(a, b) >= threshold;
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.trim().isEmpty() ? root.getClass().getSimpleName() : message.trim();
    }

    private String nextEdge(JSONArray edges, String id, Boolean condition) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !id.equals(edge.optString("source", ""))) continue;
            if (condition == null) {
                if (!"condition".equals(edge.optString("type", ""))) return edge.optString("target", null);
            } else {
                String expected = condition ? "true" : "false";
                if (expected.equals(edge.optString("condition", "")) || expected.equals(edge.optString("sourceHandle", ""))) {
                    return edge.optString("target", null);
                }
            }
        }
        if (condition == null) {
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                if (edge != null && id.equals(edge.optString("source", ""))) return edge.optString("target", null);
            }
        }
        return null;
    }

    private static boolean isDual(String type) {
        return "is_image_exist".equals(type) || "is_app_running".equals(type) || "is_color_exist".equals(type)
                || "recognize_number".equals(type) || "recognize_text".equals(type)
                || "check_region_change".equals(type);
    }

    private static JSONObject firstConfig(JSONArray nodes, String type) {
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            JSONObject data = node == null ? null : node.optJSONObject("data");
            if (data != null && type.equals(nodeType(node))) return data.optJSONObject("config");
        }
        return null;
    }

    private static String nodeType(JSONObject node) {
        if (node == null) return "";
        String type = node.optString("type", "").trim();
        if (!type.isEmpty()) return type;
        JSONObject data = node.optJSONObject("data");
        return data == null ? "" : data.optString("type", "").trim();
    }

    private Rect region(JSONArray values) {
        if (values == null || values.length() < 4) return new Rect(0, 0, coordinateWidth, coordinateHeight);
        return new Rect((int) values.optDouble(0), (int) values.optDouble(1),
                (int) values.optDouble(2), (int) values.optDouble(3));
    }

    private Rect pixelRegion(Rect base, int width, int height) {
        if (base == null) base = new Rect(0, 0, coordinateWidth, coordinateHeight);
        int left = clamp((int) Math.floor(base.left * width / (double) coordinateWidth), 0, width);
        int top = clamp((int) Math.floor(base.top * height / (double) coordinateHeight), 0, height);
        int right = clamp((int) Math.ceil(base.right * width / (double) coordinateWidth), 0, width);
        int bottom = clamp((int) Math.ceil(base.bottom * height / (double) coordinateHeight), 0, height);
        return new Rect(Math.min(left, right), Math.min(top, bottom), Math.max(left, right), Math.max(top, bottom));
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] result = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(result, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return result;
    }

    private static Integer parseNumber(String text) {
        Matcher matcher = NUMBER.matcher(text == null ? "" : text.replace(" ", ""));
        if (!matcher.find()) return null;
        String raw = matcher.group().replace(',', '.');
        try { return (int) Math.round(Double.parseDouble(raw)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw.trim()); } catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean compare(double value, String op, double target) {
        switch (op) {
            case "<": return value < target;
            case "<=": return value <= target;
            case ">": return value > target;
            case "==": return value == target;
            case "!=": return value != target;
            case ">=":
            default: return value >= target;
        }
    }

    private static String normalizeText(String text, boolean caseSensitive) {
        String value = text == null ? "" : text.replaceAll("\\s+", "");
        return caseSensitive ? value : value.toLowerCase(Locale.ROOT);
    }

    private static String percent(double value) { return String.format(Locale.US, "%.1f%%", value); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static int positiveInt(int value, int fallback) { return value > 0 ? value : fallback; }
}
