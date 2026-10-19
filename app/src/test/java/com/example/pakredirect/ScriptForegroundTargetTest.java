package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ScriptForegroundTargetTest {
    private static final String TARGET = "com.tepaylink.tamgioiphantranhmobile";
    private static final String HOST = "com.example.pakredirect";
    private static final String PROXY = "top.niunaijun.blackbox.proxy.ProxyActivity$P0";

    @Test public void directAndroidPackageIsAccepted() {
        assertTrue(ScriptForegroundTarget.isTarget(TARGET, "com.game.MainActivity",
                true, null, TARGET));
    }

    @Test public void proxyWithTopVirtualTargetIsAccepted() {
        assertTrue(ScriptForegroundTarget.isTarget(HOST, PROXY, true, TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isTarget(HOST,
                "top.niunaijun.blackbox.proxy.TransparentProxyActivity$P1",
                true, TARGET, TARGET));
    }

    @Test public void proxyWithAnotherTopVirtualGuestIsRejected() {
        assertFalse(ScriptForegroundTarget.isTarget(HOST, PROXY, true,
                "com.example.othergame", TARGET));
    }

    @Test public void hostWorkbenchIsRejectedEvenWhenVirtualTargetStillRuns() {
        assertFalse(ScriptForegroundTarget.isTarget(HOST,
                "com.example.pakredirect.ScriptToolsActivity", true, TARGET, TARGET));
    }

    @Test public void staleOrEmptyWindowStateIsRejected() {
        assertFalse(ScriptForegroundTarget.isTarget(HOST, PROXY, false, TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTarget(TARGET, "com.game.MainActivity",
                false, null, TARGET));
        assertFalse(ScriptForegroundTarget.isTarget("", "", true, null, TARGET));
        assertFalse(ScriptForegroundTarget.isTarget(HOST, PROXY, true, null, TARGET));
    }

    @Test public void merelyNamingProxyPrefixIsRejected() {
        assertFalse(ScriptForegroundTarget.isTarget(HOST,
                "top.niunaijun.blackbox.proxy.ProxyActivity$P0Fake", true, TARGET, TARGET));
    }

    @Test public void acceptsLiveBlackBoxHostWindowWithTargetVirtualTop() {
        assertTrue(ScriptForegroundTarget.isActiveWindowTarget(HOST, TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveWindowTarget(HOST, "com.example.othergame", TARGET));
        assertFalse(ScriptForegroundTarget.isActiveWindowTarget("com.other.host", TARGET, TARGET));
    }

    @Test public void acceptsDirectGameActiveWindow() {
        assertTrue(ScriptForegroundTarget.isActiveWindowTarget(TARGET, null, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveWindowTarget("com.example.othergame", null, TARGET));
    }
}
