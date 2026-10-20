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

    @Test public void acceptsLiveBlackBoxHostWindowWhenAccessibilityWindowIdsDiffer() {
        assertTrue(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, TARGET, "com.jxclient.release.jxclient", "封神榜手游", "封神榜手游",
                TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, HOST, PROXY, "封神榜手游", "封神榜手游", TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, HOST, "com.example.pakredirect.ScriptToolsActivity",
                "封神榜手游", "封神榜手游", TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, HOST, "com.example.pakredirect.ScriptToolsActivity",
                "RYLUX 脚本工作台", "封神榜手游", TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, HOST, PROXY, "RYLUX 脚本工作台", "封神榜手游", TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, HOST, PROXY, null, "封神榜手游", TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveSandboxGameWindow(
                HOST, TARGET, "com.jxclient.release.jxclient", "封神榜手游", "封神榜手游",
                "com.example.othergame", TARGET));
        assertFalse(ScriptForegroundTarget.isActiveSandboxGameWindow(
                "com.other.host", TARGET, PROXY, "封神榜手游", "封神榜手游", TARGET, TARGET));
    }

    @Test public void acceptsDirectGameActiveWindow() {
        assertTrue(ScriptForegroundTarget.isActiveSandboxGameWindow(
                TARGET, HOST, "com.example.pakredirect.ScriptToolsActivity", null, null, null, TARGET));
        assertFalse(ScriptForegroundTarget.isActiveSandboxGameWindow(
                "com.example.othergame", HOST, PROXY, null, null, null, TARGET));
    }

    @Test public void liveWorkbenchWindowCannotBeOverriddenByStaleProxyEvent() {
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "RYLUX 脚本工作台", "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "封神榜手游", "封神榜手游", HOST,
                "com.example.pakredirect.ScriptToolsActivity", TARGET, TARGET));
    }

    @Test public void genericHostTitleRequiresTheActualForegroundBlackBoxProxy() {
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "RYLUX", "封神榜手游", HOST,
                "com.example.pakredirect.ScriptToolsActivity",
                PROXY, TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "RYLUX", "封神榜手游", HOST, PROXY,
                "com.example.pakredirect.ScriptToolsActivity", TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "RYLUX", "封神榜手游", HOST, PROXY,
                PROXY, "com.example.othergame", TARGET));
    }

    @Test public void nullRootRequiresLiveGameTitleAndVirtualTopTarget() {
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, null, "封神榜手游", "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                false, null, "封神榜手游", "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, null, "RYLUX 脚本工作台", "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, null, null, "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, null, "封神榜手游", "", HOST, PROXY,
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, null, "封神榜手游", "封神榜手游", HOST, PROXY,
                "com.example.othergame", TARGET));
    }

    @Test public void slashPlaceholderRootAcceptsOnlyForegroundBlackBoxGuest() {
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, "/", "", "封神榜手游", HOST,
                "com.jxclient.release.jxclient#1469", PROXY, TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, "/", "", "封神榜手游", HOST,
                "com.jxclient.release.jxclient#1469", "com.example.pakredirect.ScriptToolsActivity",
                TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, "/", "", "封神榜手游", HOST,
                "com.jxclient.release.jxclient#1469", PROXY, "com.example.othergame", TARGET));
    }

    @Test public void knownRootPackageTakesPriorityOverMatchingTitleAndVirtualTop() {
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, "com.example.otherapp", "封神榜手游", "封神榜手游",
                HOST, PROXY, TARGET, TARGET));
    }

    @Test public void directAndHostActiveWindowsStillPass() {
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, TARGET, null, null, HOST, PROXY, null, TARGET));
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, HOST, "封神榜手游", "封神榜手游", HOST, PROXY,
                TARGET, TARGET));
    }

    @Test public void diagnosticHostSuffixAcceptsItsLiveGuestWindow() {
        String diagnosticHost = HOST + ".hookdiag";
        assertTrue(ScriptForegroundTarget.isTarget(diagnosticHost, PROXY,
                true, TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTarget(HOST + "hookdiag", PROXY,
                true, TARGET, TARGET));
        assertTrue(ScriptForegroundTarget.isTargetForeground(
                true, diagnosticHost, "Tam Giới Phân Tranh Mobile",
                "Tam Giới Phân Tranh Mobile", diagnosticHost,
                "com.jxclient.release.jxclient#109", TARGET, TARGET));
        assertFalse(ScriptForegroundTarget.isTargetForeground(
                true, diagnosticHost, "RYLUX 脚本工作台",
                "Tam Giới Phân Tranh Mobile", diagnosticHost,
                PROXY, TARGET, TARGET));
    }
}
