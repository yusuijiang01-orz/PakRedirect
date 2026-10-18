package com.example.pakredirect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LuaSourceCompatTest {
    @Test public void normalizesCodeIdentifiersButLeavesStringsAndCommentsAlone() {
        String source = "local 本地变量 = '保留中文' -- 注释中文\n"
                + "_ENV[\"界面数据\"] = [[长字符串中的中文]]\n"
                + "local mixed_变量2 = 本地变量";
        String normalized = LuaSourceCompat.normalizeUnicodeIdentifiers(source);

        assertFalse(normalized.contains("local 本地变量"));
        assertFalse(normalized.contains("mixed_变量2"));
        assertTrue(normalized.contains("'保留中文' -- 注释中文"));
        assertTrue(normalized.contains("_ENV[\"界面数据\"]"));
        assertTrue(normalized.contains("[[长字符串中的中文]]"));
    }
}
