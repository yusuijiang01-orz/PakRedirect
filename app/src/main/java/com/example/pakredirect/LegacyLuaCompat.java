package com.example.pakredirect;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/** Small compatibility helpers shared by the recovered Lua host and regression tests. */
final class LegacyLuaCompat {
    private LegacyLuaCompat() { }

    static LuaTable splitStr(LuaValue source, LuaValue separator) {
        String text = source.isnil() ? "" : source.tojstring();
        String delimiter = separator.isnil() ? "" : separator.tojstring();
        LuaTable result = new LuaTable();
        if (delimiter.isEmpty()) {
            if (!text.isEmpty()) result.set(1, LuaValue.valueOf(text));
            return result;
        }

        int index = 1;
        int start = 0;
        int next;
        while ((next = text.indexOf(delimiter, start)) >= 0) {
            result.set(index++, LuaValue.valueOf(text.substring(start, next)));
            start = next + delimiter.length();
        }
        if (start < text.length()) result.set(index, LuaValue.valueOf(text.substring(start)));
        return result;
    }
}
