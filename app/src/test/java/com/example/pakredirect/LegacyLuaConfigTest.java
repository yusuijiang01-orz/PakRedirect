package com.example.pakredirect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LegacyLuaConfigTest {
    @Test public void recoveredScriptFieldsHaveSafeDefaultsWhenWorkBenchValuesAreOmitted() {
        assertEquals("60", LegacyLuaConfig.defaults().get("挂机不移动秒"));
        assertEquals("2", LegacyLuaConfig.defaults().get("队员找不到队长组队就回家次数"));
        assertEquals("true", LegacyLuaConfig.defaults().get("保护装备不出售"));
        assertEquals("false", LegacyLuaConfig.defaults().get("出售装备"));
    }
}
