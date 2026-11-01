package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Opt-in live test of the recovered source Lua party-panel detection and recovery. */
public final class RecoveredLuaLivePartyPanelTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage6", "party-live").toAbsolutePath().normalize();

    @Test public void sourceLuaOpensAndClosesLivePartyPanel() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python and evidence env vars to opt in to the live party test",
                present(adb) && present(serial) && present(python) && present(evidence));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-6 party evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(globals, device, callbackFailure, new HashMap<>());
            globals.set("tomocrtext", new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    try {
                        Varargs rows = RecoveredLuaLiveActivityPanelTest.ocrRowsInReferenceCrop(device, args);
                        StringBuilder text = new StringBuilder();
                        LuaValue value = rows.arg1();
                        if (value.istable()) {
                            org.luaj.vm2.LuaTable table = value.checktable();
                            for (int i = 1; i <= table.length(); i++) {
                                LuaValue row = table.get(i);
                                if (row.istable()) text.append(row.get("words").optjstring(""));
                            }
                        }
                        return LuaValue.valueOf(text.toString());
                    } catch (Exception error) {
                        callbackFailure.compareAndSet(null, error);
                        throw new org.luaj.vm2.LuaError(error.getMessage());
                    }
                }
            });
            for (String module : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                RecoveredLuaLiveActivityPanelTest.loadCurrentSourceModule(
                        globals, SCRIPT_ROOT.resolve(module), module);
            }
            RecoveredLuaLiveActivityPanelTest.installTapCompatibility(globals);
            LuaFunction partyOpen = globals.get("组队_页面已打开").checkfunction();
            LuaFunction closeParty = globals.get("组队_关闭页面").checkfunction();

            LiveLuaDeviceHost.Frame initial = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(initial);
            RecoveredLuaLiveActivityPanelTest.writeFrameSummary(device, initial, initialRows, "initial");
            boolean alreadyOpen = partyOpen.call().toboolean();
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            if (!alreadyOpen) {
                int hudLabels = RecoveredLuaLiveActivityPanelTest.countHudLabels(initial, initialRows);
                assertTrue("Refusing to open the team panel unless the world HUD is visible; inspect initial.png",
                        hudLabels >= 2);
                LiveLuaDeviceHost.Row teamLabel = initialRows.stream()
                        .filter(row -> row.confidence >= 0.8 && "组队".equals(row.text.trim()))
                        .filter(row -> row.x >= 1300 && row.x <= 1500 && row.y >= 690 && row.y <= 800)
                        .findFirst().orElse(null);
                assertTrue("Refusing to tap without the OCR-confirmed bottom-right 组队 label", teamLabel != null);
                device.event("open_party", "main HUD and visible 组队 label confirmed; tap production Lua coordinate"
                        + " reference=1114,556 confidence=" + teamLabel.confidence);
                device.tap(1114, 556, "OCR-gated production Lua team-button coordinate");
            } else {
                device.event("initial_state", "Lua predicate confirmed party panel already open");
            }

            boolean panelConfirmed = alreadyOpen;
            long waitUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            while (!panelConfirmed && System.nanoTime() < waitUntil) {
                Thread.sleep(700);
                LiveLuaDeviceHost.Frame frame = device.capture();
                panelConfirmed = partyOpen.call().toboolean();
                RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
                device.event("party_predicate", "capture=" + frame.number + " confirmed=" + panelConfirmed);
            }
            assertTrue("Current source Lua did not confirm team title and nearby-player tab; no close touch was sent",
                    panelConfirmed);

            assertTrue("Lua party recovery refused to close its confirmed panel", closeParty.call().toboolean());
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            device.event("close_party", "source Lua party predicate authorized close");

            boolean restored = false;
            long restoreUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            while (!restored && System.nanoTime() < restoreUntil) {
                Thread.sleep(700);
                LiveLuaDeviceHost.Frame frame = device.capture();
                List<LiveLuaDeviceHost.Row> rows = device.rows(frame);
                boolean partyStillOpen = partyOpen.call().toboolean();
                int hudLabels = RecoveredLuaLiveActivityPanelTest.countHudLabels(frame, rows);
                restored = !partyStillOpen && hudLabels >= 2;
                device.event("restore_check", "capture=" + frame.number + " partyOpen=" + partyStillOpen
                        + " hudLabels=" + hudLabels + " restored=" + restored);
            }
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Party panel did not close or main HUD OCR did not return; inspect evidence frames", restored);
            assertTrue("The test should open then close the panel exactly once", device.actionCount() == (alreadyOpen ? 1 : 2));
            device.event("result", "PASS source_lua_party_open_predicate_and_close_verified");
        }
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
}
