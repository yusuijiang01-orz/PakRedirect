package com.example.pakredirect;

import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LegacyLuaCompatTest {
    @Test public void touchMoveDurationUsesLegacyDefaultAndStaysWithinGestureBounds() {
        assertEquals(350L, LegacyLuaCompat.defaultTouchDurationMs());
        assertEquals(1L, LegacyLuaCompat.normalizeTouchDuration(0L));
        assertEquals(300L, LegacyLuaCompat.normalizeTouchDuration(300L));
        assertEquals(10_000L, LegacyLuaCompat.normalizeTouchDuration(20_000L));
    }

    @Test public void splitStrPreservesMapOrderAndSeparatesLiteralNewlines() {
        LuaTable maps = LegacyLuaCompat.splitStr(
                LuaValue.valueOf("崇城野外,sùng thành\n北海,bắc hải"), LuaValue.valueOf("\n"));

        assertEquals(2, maps.length());
        assertEquals("崇城野外,sùng thành", maps.get(1).tojstring());
        assertEquals("北海,bắc hải", maps.get(2).tojstring());
        LuaTable pair = LegacyLuaCompat.splitStr(maps.get(1), LuaValue.valueOf(","));
        assertEquals("崇城野外", pair.get(1).tojstring());
        assertEquals("sùng thành", pair.get(2).tojstring());
    }

    @Test public void splitStrReturnsEmptyTableForEmptyInput() {
        assertTrue(LegacyLuaCompat.splitStr(LuaValue.valueOf(""), LuaValue.valueOf("\n")).get(1).isnil());
    }

    @Test public void utf8CompatibilityUsesCharacterIndexesForChineseText() {
        LuaTable utf8 = LegacyLuaCompat.createUtf8Compat();
        LuaValue source = LuaValue.valueOf("点击队伍[附近]");
        assertEquals(5, utf8.get("inStr").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.ONE, source, LuaValue.valueOf("[")})).arg1().toint());
        assertEquals("点击队伍", utf8.get("left").call(source, LuaValue.valueOf(4)).tojstring());
        assertEquals("点击", utf8.get("left").call(source, LuaValue.valueOf(2)).tojstring());
    }

    @Test public void cmpColorExUsesAbsolutePointsPerChannelToleranceAndSimilarityThreshold() {
        String points = "1|1|ff0000-101010,2|2|00ff00-000000";
        LegacyLuaCompat.PixelReader pixels = (x, y) -> x == 1 && y == 1 ? 0xf50000 : 0x000000;

        assertTrue(LegacyLuaCompat.matchesColorPoints(points, 0.5d, 4, 4, pixels));
        assertFalse(LegacyLuaCompat.matchesColorPoints(points, 0.75d, 4, 4, pixels));
        assertFalse(LegacyLuaCompat.matchesColorPoints("9|9|ffffff-000000", 0.1d, 4, 4, pixels));
        assertFalse(LegacyLuaCompat.matchesColorPoints("bad-point", 0d, 4, 4, pixels));
    }

    @Test public void legacyTomocrReturnsFirstLiteralCandidateMatchAboveConfidenceThreshold() {
        LuaTable lines = new LuaTable();
        LuaTable lowConfidence = new LuaTable();
        lowConfidence.set("words", LuaValue.valueOf("允许进入"));
        lowConfidence.set("score", LuaValue.valueOf(0.6d));
        lines.set(1, lowConfidence);
        LuaTable match = new LuaTable();
        match.set("words", LuaValue.valueOf("玩家申请允许进入队伍"));
        match.set("text", LuaValue.valueOf("玩家申请允许进入队伍"));
        match.set("x", LuaValue.valueOf(42));
        match.set("y", LuaValue.valueOf(73));
        match.set("score", LuaValue.valueOf(0.95d));
        lines.set(2, match);
        LuaTable candidates = new LuaTable();
        candidates.set(1, LuaValue.valueOf("允许进入"));

        assertSame(match, LegacyLuaCompat.findOcrMatch(lines, candidates, 0.8d));
        assertEquals("玩家申请允许进入队伍",
                LegacyLuaCompat.findOcrMatch(lines, candidates, 0.8d).get("words").tojstring());
        assertNull(LegacyLuaCompat.findOcrMatch(lines, candidates, 0.99d));
    }

    @Test public void legacyFindStrMatchesRequestedTextAndReturnsScreenPoint() {
        LuaTable lines = new LuaTable();
        LuaTable match = new LuaTable();
        match.set("words", LuaValue.valueOf("玩家申请允许进入队伍"));
        match.set("x", LuaValue.valueOf(42));
        match.set("y", LuaValue.valueOf(73));
        match.set("score", LuaValue.valueOf(0.95d));
        lines.set(1, match);

        LuaTable found = LegacyLuaCompat.findOcrTextMatch(lines, "允许进入", 0.8d);
        assertSame(match, found);
        assertEquals(42, found.get("x").toint());
        assertEquals(73, found.get("y").toint());
        assertNull(LegacyLuaCompat.findOcrTextMatch(lines, "完全不存在", 0.8d));
        assertNull(LegacyLuaCompat.findOcrTextMatch(lines, "允许进入", 0.99d));
    }

    @Test public void tomocrtextallOmitsLowConfidenceRowsAndKeepsBoundaryMatches() {
        LuaTable lines = new LuaTable();
        LuaTable weak = new LuaTable();
        weak.set("words", LuaValue.valueOf("8"));
        weak.set("score", LuaValue.valueOf(0.849d));
        lines.set(1, weak);
        LuaTable boundary = new LuaTable();
        boundary.set("text", LuaValue.valueOf("1"));
        boundary.set("score", LuaValue.valueOf(0.85d));
        lines.set(2, boundary);
        LuaTable accepted = new LuaTable();
        accepted.set("words", LuaValue.valueOf("9"));
        accepted.set("confidence", LuaValue.valueOf(0.95d));
        lines.set(3, accepted);
        LuaTable unknown = new LuaTable();
        unknown.set("words", LuaValue.valueOf("7"));
        lines.set(4, unknown);

        VarArgFunction api = LegacyLuaCompat.createTomocrTextAll(args -> lines);
        Varargs defaultThreshold = api.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.ZERO,
                LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO}));
        Varargs callerThreshold = api.invoke(LuaValue.varargsOf(new LuaValue[]{LuaValue.ZERO,
                LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO, LuaValue.valueOf(0.99d)}));
        assertEquals("19", defaultThreshold.tojstring());
        assertEquals("", callerThreshold.tojstring());
        assertEquals("", LegacyLuaCompat.concatenateConfidentOcrText(null, 0.85d));
    }
}
