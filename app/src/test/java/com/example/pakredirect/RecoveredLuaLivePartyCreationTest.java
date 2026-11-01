package com.example.pakredirect;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

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

/** Live source-Lua test for OCR-gated party creation and its post-action marker. */
public final class RecoveredLuaLivePartyCreationTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final Path SCRIPT_ROOT = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本").toAbsolutePath().normalize();
    private static final Path EXPECTED_EVIDENCE = Paths.get("work", "lua-acceptance-20261022",
            "stage6", "party-create-live-rerun-04").toAbsolutePath().normalize();

    @Test public void sourceLuaCreatesAndConfirmsLivePartyOnce() throws Exception {
        String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
        String serial = System.getenv("RYLUX_ADB_DEVICE");
        String python = System.getenv("RYLUX_OCR_PYTHON");
        String evidence = System.getenv("RYLUX_LIVE_EVIDENCE_DIR");
        assumeTrue("Set ADB, RapidOCR Python and evidence env vars to opt in to live party creation",
                present(adb) && present(serial) && present(python) && present(evidence));
        Path evidencePath = Paths.get(evidence).toAbsolutePath().normalize();
        assertTrue("Use the stage-6 party creation evidence folder", EXPECTED_EVIDENCE.equals(evidencePath));

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

            LuaFunction onMain = globals.get("在主页面").checkfunction();
            LuaFunction panelOpen = globals.get("组队_页面已打开").checkfunction();
            LuaFunction createButton = globals.get("组队_识别创建队伍按钮").checkfunction();
            LuaFunction created = globals.get("组队_已确认创建成功").checkfunction();
            LuaFunction closeParty = globals.get("组队_关闭页面").checkfunction();

            LiveLuaDeviceHost.Frame initial = device.capture();
            List<LiveLuaDeviceHost.Row> initialRows = device.rows(initial);
            RecoveredLuaLiveActivityPanelTest.writeFrameSummary(device, initial, initialRows, "initial");
            boolean alreadyOpen = panelOpen.call().toboolean();
            assertTrue("Refusing to create a team unless the game HUD or verified team panel is visible",
                    alreadyOpen || onMain.call().toboolean());
            boolean alreadyCreated = created.call().toboolean();
            boolean panelConfirmed = alreadyOpen;
            if (!panelConfirmed) {
                LiveLuaDeviceHost.Row teamLabel = initialRows.stream()
                        .filter(row -> row.confidence >= 0.8 && "组队".equals(row.text.trim()))
                        .filter(row -> row.x >= 1300 && row.x <= 1500 && row.y >= 690 && row.y <= 800)
                        .findFirst().orElse(null);
                assertTrue("Refusing to tap without OCR confirmation of the bottom-right 组队 button", teamLabel != null);
                device.event("open_party", "OCR confirmed 组队; tap production Lua coordinate 1114,556");
                device.tap(1114, 556, "OCR-gated production Lua team-button coordinate");
                long panelUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
                while (!panelConfirmed && System.nanoTime() < panelUntil) {
                    Thread.sleep(700);
                    LiveLuaDeviceHost.Frame frame = device.capture();
                    panelConfirmed = panelOpen.call().toboolean();
                    device.event("party_predicate", "capture=" + frame.number + " confirmed=" + panelConfirmed);
                }
            }
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("The localized 队伍/附近玩家 panel was not confirmed; no create touch was sent", panelConfirmed);
            // Recheck inside the modal: the Chinese "解散" action is only
            // visible here, so a HUD-only marker check can miss an existing team.
            alreadyCreated = alreadyCreated || created.call().toboolean();

            if (alreadyCreated) {
                device.event("existing_party", "localized 解散 action or team flag confirms success; skipped duplicate create tap");
            } else {
                Varargs button = createButton.invoke(LuaValue.NONE);
                int x = button.arg1().optint(-1);
                int y = button.arg(2).optint(-1);
                assertTrue("No exact OCR match for the localized team action; no touch was sent",
                        x >= 430 && x <= 1070 && y >= 570 && y <= 685);
                device.event("create_party", "exact localized OCR match for 组队; Lua tap=" + x + "," + y);
                device.tap(x, y, "OCR-confirmed localized 组队 button");
            }

            boolean confirmed = false;
            long createUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            while (!confirmed && System.nanoTime() < createUntil) {
                Thread.sleep(900);
                LiveLuaDeviceHost.Frame frame = device.capture();
                confirmed = created.call().toboolean();
                device.event("create_result", "capture=" + frame.number + " teamMarker=" + confirmed);
            }
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Creation tap was sent but the live team marker never confirmed success", confirmed);

            if (panelOpen.call().toboolean()) {
                assertTrue("Lua close helper refused to close the verified party panel", closeParty.call().toboolean());
            }
            boolean restored = false;
            long restoreUntil = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(25));
            while (!restored && System.nanoTime() < restoreUntil) {
                Thread.sleep(700);
                LiveLuaDeviceHost.Frame frame = device.capture();
                List<LiveLuaDeviceHost.Row> rows = device.rows(frame);
                boolean panelStillOpen = panelOpen.call().toboolean();
                int hudLabels = RecoveredLuaLiveActivityPanelTest.countHudLabels(frame, rows);
                restored = !panelStillOpen && hudLabels >= 2;
                device.event("restore_check", "capture=" + frame.number + " panelOpen=" + panelStillOpen
                        + " hudLabels=" + hudLabels + " restored=" + restored);
            }
            RecoveredLuaLiveActivityPanelTest.checkCallbackFailure(callbackFailure);
            assertTrue("Party panel did not close or main HUD did not return", restored);
            device.event("result", "PASS source_lua_live_party_created_and_team_marker_confirmed");
        }
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
}
