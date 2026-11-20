package com.example.pakredirect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Finds the nearest common continuation of the true and false branches of a
 * conditional node. This lets an UNKNOWN inventory observation bypass both
 * sides of a potion-dependent decision without requiring an enclosing loop.
 */
final class CoraFlowBranchSkipPolicy {
    private CoraFlowBranchSkipPolicy() { }

    static String findJoin(JSONArray edges, String sourceId, Set<String> nodeIds) {
        if (edges == null || sourceId == null || sourceId.trim().isEmpty()
                || nodeIds == null || nodeIds.isEmpty()) return null;

        String trueHead = branchTarget(edges, sourceId, "true");
        String falseHead = branchTarget(edges, sourceId, "false");
        if (trueHead == null || falseHead == null
                || !nodeIds.contains(trueHead) || !nodeIds.contains(falseHead)) return null;
        if (trueHead.equals(falseHead)) return trueHead;

        Map<String, Integer> trueDistances = distances(edges, trueHead, sourceId, falseHead, nodeIds);
        Map<String, Integer> falseDistances = distances(edges, falseHead, sourceId, trueHead, nodeIds);
        String best = null;
        int bestMax = Integer.MAX_VALUE;
        int bestSum = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : trueDistances.entrySet()) {
            String candidate = entry.getKey();
            Integer other = falseDistances.get(candidate);
            if (other == null || candidate.equals(sourceId)
                    || candidate.equals(trueHead) || candidate.equals(falseHead)) continue;
            int max = Math.max(entry.getValue(), other);
            int sum = entry.getValue() + other;
            if (max < bestMax || (max == bestMax && sum < bestSum)
                    || (max == bestMax && sum == bestSum
                    && (best == null || candidate.compareTo(best) < 0))) {
                best = candidate;
                bestMax = max;
                bestSum = sum;
            }
        }
        return best;
    }

    private static String branchTarget(JSONArray edges, String sourceId, String branch) {
        for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i);
            if (edge == null || !sourceId.equals(edge.optString("source", ""))) continue;
            if (branch.equals(edge.optString("condition", ""))
                    || branch.equals(edge.optString("sourceHandle", ""))) {
                String target = edge.optString("target", "").trim();
                if (!target.isEmpty()) return target;
            }
        }
        return null;
    }

    private static Map<String, Integer> distances(JSONArray edges, String start,
                                                   String sourceId, String blocked,
                                                   Set<String> nodeIds) {
        Map<String, Integer> result = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        result.put(start, 0);
        queue.addLast(start);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int nextDistance = result.get(current) + 1;
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                if (edge == null || !current.equals(edge.optString("source", ""))) continue;
                String target = edge.optString("target", "").trim();
                if (target.isEmpty() || target.equals(sourceId) || target.equals(blocked)
                        || !nodeIds.contains(target) || result.containsKey(target)) continue;
                result.put(target, nextDistance);
                queue.addLast(target);
            }
        }
        return result;
    }
}
