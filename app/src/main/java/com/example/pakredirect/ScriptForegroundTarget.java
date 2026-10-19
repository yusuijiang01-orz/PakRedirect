package com.example.pakredirect;

/** Pure decision rule shared by startup and every screen input operation. */
final class ScriptForegroundTarget {
    private static final String HOST_PACKAGE = "com.example.pakredirect";
    private static final String PROXY = "top.niunaijun.blackbox.proxy.ProxyActivity$P";
    private static final String TRANSPARENT_PROXY =
            "top.niunaijun.blackbox.proxy.TransparentProxyActivity$P";

    private ScriptForegroundTarget() { }

    static boolean isTarget(String androidPackage, String androidClass,
                            boolean currentActiveWindow, String virtualTopPackage,
                            String targetPackage) {
        if (!currentActiveWindow || targetPackage == null || targetPackage.isEmpty()) return false;
        if (targetPackage.equals(androidPackage)) return true;
        return HOST_PACKAGE.equals(androidPackage)
                && isProxyActivity(androidClass)
                && targetPackage.equals(virtualTopPackage);
    }

    /**
     * Checks the live active accessibility window instead of relying on a potentially
     * stale window-state event. BlackBox renders its guest activity in a host window.
     */
    static boolean isActiveSandboxGameWindow(String activeWindowPackage,
                                             String eventPackage,
                                             String eventClass,
                                             String virtualTopPackage,
                                             String targetPackage) {
        if (activeWindowPackage == null || targetPackage == null || targetPackage.isEmpty()) return false;
        if (targetPackage.equals(activeWindowPackage)) return true;
        if (!HOST_PACKAGE.equals(activeWindowPackage) || !targetPackage.equals(virtualTopPackage)) {
            return false;
        }
        // Accessibility events and live windows can use different ids in BlackBox on
        // some Android builds. Correlate the current host application window with the
        // guest activity identity and BlackBox's current virtual top activity instead.
        return targetPackage.equals(eventPackage) || isProxyActivity(eventClass);
    }

    static boolean isProxyActivity(String className) {
        if (className == null) return false;
        String suffix;
        if (className.startsWith(PROXY)) suffix = className.substring(PROXY.length());
        else if (className.startsWith(TRANSPARENT_PROXY)) {
            suffix = className.substring(TRANSPARENT_PROXY.length());
        } else return false;
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (suffix.charAt(i) < '0' || suffix.charAt(i) > '9') return false;
        }
        return true;
    }

}
