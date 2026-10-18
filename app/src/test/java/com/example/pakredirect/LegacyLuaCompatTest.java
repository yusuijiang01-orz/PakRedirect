package com.example.pakredirect;

import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LegacyLuaCompatTest {
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
}
