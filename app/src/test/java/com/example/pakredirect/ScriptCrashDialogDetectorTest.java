package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScriptCrashDialogDetectorTest {
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";

    @Test public void identifiesOnlySystemCrashDialogForTargetGame() {
        assertTrue(ScriptCrashDialogDetector.isTargetCrashDialog(
                "android", "com.android.server.am.AppErrorDialog",
                "封神榜（汉化版）已停止运行 关闭应用", GAME_PACKAGE, "封神榜（汉化版）"));
        assertTrue(ScriptCrashDialogDetector.isTargetCrashDialog(
                "com.android.systemui", "android.app.Dialog",
                "Tam Gioi Phan Tranh keeps stopping Close app", GAME_PACKAGE,
                "Tam Gioi Phan Tranh"));
        assertFalse(ScriptCrashDialogDetector.isTargetCrashDialog(
                "android", "com.android.server.am.AppErrorDialog",
                "浏览器已停止运行", GAME_PACKAGE, "封神榜（汉化版）"));
        assertFalse(ScriptCrashDialogDetector.isTargetCrashDialog(
                GAME_PACKAGE, "com.game.MainActivity", "封神榜已停止运行",
                GAME_PACKAGE, "封神榜"));
    }

    @Test public void recognizesCloseButtonLabels() {
        assertTrue(ScriptCrashDialogDetector.isCloseAppButton("关闭应用"));
        assertTrue(ScriptCrashDialogDetector.isCloseAppButton("Close app"));
        assertTrue(ScriptCrashDialogDetector.isCloseAppButton("Đóng ứng dụng"));
        assertFalse(ScriptCrashDialogDetector.isCloseAppButton("等待"));
    }
}
