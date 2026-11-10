package com.example.pakredirect;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.TextView;

import java.util.Locale;

/** One-shot read-only diagnostic. Never loads or runs the Lua automation workflow. */
public final class BagMemoryProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = this::pollSnapshot;
    private SandboxBagSnapshotRelay relay;
    private TextView result;
    private long deadlineElapsedMs;
    private boolean finished;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        result = new TextView(this);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        result.setPadding(padding, padding, padding, padding);
        result.setTextSize(18);
        result.setText("只读读取背包中……\n不会执行游戏点击或 Lua 流程。");
        setContentView(result);
        try {
            relay = new SandboxBagSnapshotRelay(this);
            relay.start();
            deadlineElapsedMs = SystemClock.elapsedRealtime() + 15_000L;
            handler.postDelayed(poller, 250L);
        } catch (Exception failure) {
            finishWith("中继启动失败：" + failure.getClass().getSimpleName()
                    + "：" + String.valueOf(failure.getMessage()));
        }
    }

    private void pollSnapshot() {
        if (finished || relay == null) return;
        SandboxBagSnapshot snapshot = relay.snapshot();
        if (snapshot != null) {
            if (snapshot.supported) {
                long ageMs = Math.max(0L, SystemClock.elapsedRealtime() - snapshot.receivedAtElapsedMs);
                finishWith(String.format(Locale.ROOT,
                        "读取成功（只读）\n背包剩余格子：%d\n负重：%d / %d\n快照延迟：%d ms\nBuild ID：%s",
                        snapshot.freeSlots, snapshot.currentWeight, snapshot.maxWeight, ageMs,
                        snapshot.buildId));
            } else {
                finishWith("读取器拒绝此游戏版本或数值\n状态：" + snapshot.status
                        + "\nBuild ID：" + snapshot.buildId);
            }
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) {
            finishWith("15 秒内没有收到内存快照。请确认游戏在 RYLUX 沙箱前台，并检查读取器日志。");
            return;
        }
        handler.postDelayed(poller, 250L);
    }

    private void finishWith(String message) {
        if (finished) return;
        finished = true;
        if (relay != null) {
            relay.close();
            relay = null;
        }
        result.setText(message);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(poller);
        if (relay != null) {
            relay.close();
            relay = null;
        }
        super.onDestroy();
    }
}
