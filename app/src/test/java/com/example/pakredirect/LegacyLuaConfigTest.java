package com.example.pakredirect;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class LegacyLuaConfigTest {
    @Test public void blueFilterSelectionEnablesOnlyTheSelectedLegacyRule() {
        assertArrayEquals(new String[]{"false", "false", "false"}, LegacyLuaConfig.blueFilterFlags("0"));
        assertArrayEquals(new String[]{"true", "false", "false"}, LegacyLuaConfig.blueFilterFlags("1"));
        assertArrayEquals(new String[]{"false", "true", "false"}, LegacyLuaConfig.blueFilterFlags("2"));
        assertArrayEquals(new String[]{"false", "false", "true"}, LegacyLuaConfig.blueFilterFlags("3"));
    }

    @Test public void missingOrInvalidSelectionLeavesLegacyValuesUntouched() {
        assertNull(LegacyLuaConfig.blueFilterFlags(null));
        assertNull(LegacyLuaConfig.blueFilterFlags(""));
        assertNull(LegacyLuaConfig.blueFilterFlags("not-an-index"));
        assertNull(LegacyLuaConfig.blueFilterFlags("4"));
    }

    @Test public void recoveredScriptFieldsHaveSafeDefaultsWhenWorkbenchValuesAreOmitted() {
        assertEquals("60", LegacyLuaConfig.defaults().get("挂机不移动秒"));
        assertEquals("2", LegacyLuaConfig.defaults().get("队员找不到队长组队就回家次数"));
        assertEquals("true", LegacyLuaConfig.defaults().get("保护装备不出售"));
        assertEquals("false", LegacyLuaConfig.defaults().get("出售装备"));
        assertEquals("0", LegacyLuaConfig.defaults().get("出售装备品质上限"));
        assertEquals("true", LegacyLuaConfig.defaults().get("出售装备二次确认"));
    }
}
