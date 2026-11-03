package com.example.pakredirect;

import android.app.ActivityManager;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScriptGameProcessMonitorTest {
    @Test public void recognizesUnresponsiveBlackBoxProxyProcessesForThisUid() {
        assertTrue(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect:p0", 10093,
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING));
        assertTrue(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect:p12", 10093,
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING));
    }

    @Test public void ignoresOtherProcessesAndOtherUids() {
        int notResponding = ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING;
        assertFalse(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect", 10093,
                notResponding));
        assertFalse(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect:black", 10093,
                notResponding));
        assertFalse(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect:p0", 10111,
                notResponding));
        assertFalse(ScriptGameProcessMonitor.isBlackBoxProxyAnr(
                "com.example.pakredirect", 10093, "com.example.pakredirect:p0", 10093,
                ActivityManager.ProcessErrorStateInfo.CRASHED));
    }
}
