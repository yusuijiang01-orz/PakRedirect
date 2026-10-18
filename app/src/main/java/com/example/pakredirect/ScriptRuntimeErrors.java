package com.example.pakredirect;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Small, Android-independent error formatter for messages surfaced by the Lua host. */
final class ScriptRuntimeErrors {
    private ScriptRuntimeErrors() { }

    static String summarize(Throwable error) {
        if (error == null) return "未知错误";
        StringBuilder message = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        String previousDetail = null;
        while (current != null && seen.add(current)) {
            String detail = current.getMessage();
            if (detail == null || detail.trim().isEmpty()) detail = current.getClass().getSimpleName();
            if (!detail.equals(previousDetail)) {
                if (message.length() > 0) message.append("；底层原因：");
                message.append(detail);
                previousDetail = detail;
            }
            current = current.getCause();
        }
        return message.toString();
    }
}
