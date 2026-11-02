package com.example.pakredirect;

import java.util.Locale;

/** Identifies an Android crash/ANR dialog that names the selected game. */
final class ScriptCrashDialogDetector {
    private ScriptCrashDialogDetector() { }

    static boolean isPotentialSystemDialog(String packageName, String className) {
        String pkg = lower(packageName);
        String cls = lower(className);
        return pkg.equals("android") || pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.android.permissioncontroller")
                || pkg.startsWith("com.google.android.permissioncontroller")
                || cls.contains("apperrordialog") || cls.contains("anrdialog");
    }

    static boolean isTargetCrashDialog(String windowPackage, String windowClass, String content,
                                       String targetPackage, String targetLabel) {
        if (!isPotentialSystemDialog(windowPackage, windowClass)) return false;
        String text = normalize(content);
        if (text.isEmpty() || !hasCrashSignal(text)) return false;

        String label = normalize(targetLabel);
        String pkg = normalize(targetPackage);
        return (!label.isEmpty() && text.contains(label))
                || (!pkg.isEmpty() && text.contains(pkg));
    }

    static boolean isCloseAppButton(String text) {
        String value = normalize(text);
        return value.equals(normalize("关闭应用"))
                || value.equals(normalize("关闭"))
                || value.equals(normalize("Close app"))
                || value.equals(normalize("Close"))
                || value.equals(normalize("Đóng ứng dụng"))
                || value.equals(normalize("Đóng"));
    }

    private static boolean hasCrashSignal(String text) {
        return containsAny(text,
                "已停止运行", "停止运行", "一直停止", "无响应", "没有响应",
                "keepsstopping", "hasstopped", "isntresponding", "notresponding",
                "liêntụcdừng", "khôngphảnhồi", "đãdừng");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(normalize(needle))) return true;
        return false;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder normalized = new StringBuilder(value.length());
        String lower = value.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) normalized.append(c);
        }
        return normalized.toString();
    }
}
