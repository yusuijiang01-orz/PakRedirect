package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Keeps fallback backpack-page recognition on small, explicit screen regions. */
public final class ScriptBackpackPageOcrRegionsTest {
    private static final Path LUA = Paths.get("app", "src", "main", "assets",
            "script_modules", "脚本", "挂机模式.lua");

    @Test public void backpackProbeUsesTitleAndRightPanelCropsInsteadOfFullScreen() throws Exception {
        List<int[]> calls = new ArrayList<>();
        Globals globals = createProbeGlobals(calls, true);

        assertTrue(globals.get("药品_识别背包页面").call().toboolean());
        assertEquals(4, calls.size());
        for (int[] region : calls) {
            assertFalse("Full-screen OCR is prohibited in the backpack page fallback",
                    region[0] == 0 && region[1] == 0 && region[2] == 1280 && region[3] == 720);
            assertTrue("Each OCR request must remain a bounded UI crop",
                    (region[2] - region[0]) * (region[3] - region[1]) <= 350_000);
        }
    }

    @Test public void missingBackpackTitleStopsBeforeScanningOtherRegions() throws Exception {
        List<int[]> calls = new ArrayList<>();
        Globals globals = createProbeGlobals(calls, false);

        assertFalse(globals.get("药品_识别背包页面").call().toboolean());
        assertEquals("A missing title should not trigger additional OCR work", 1, calls.size());
    }

    private static Globals createProbeGlobals(List<int[]> calls, boolean backpackTitle) throws IOException {
        String source = Files.readString(LUA, StandardCharsets.UTF_8);
        String startMarker = "function L0_1(标题, markers, requiredMarkers)";
        String endMarker = "_ENV[\"药品_识别自动设置页面\"] = function()";
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        if (start < 0 || end < 0) throw new IOException("Could not find the backpack OCR helper in source Lua");

        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                int left = args.arg(1).toint();
                int top = args.arg(2).toint();
                int right = args.arg(3).toint();
                int bottom = args.arg(4).toint();
                calls.add(new int[]{left, top, right, bottom});
                LuaTable rows = new LuaTable();
                String text = "";
                if (left == 620 && top == 0) text = backpackTitle ? "背包" : "其他页面";
                else if (left == 650) text = "Túi1";
                else if (top == 130) text = "禁止丢弃";
                else if (top == 285) text = "整理";
                if (!text.isEmpty()) {
                    LuaTable row = new LuaTable();
                    row.set("words", LuaValue.valueOf(text));
                    row.set("score", LuaValue.valueOf(0.99));
                    rows.set(1, row);
                }
                return rows;
            }
        });

        String helper = source.substring(start, end);
        globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(helper), "backpack_page_probe.lua", globals).call();
        return globals;
    }
}
