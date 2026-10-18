package com.example.pakredirect;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable defaults for settings read by the recovered Lua modules. */
final class LegacyLuaConfig {
    private LegacyLuaConfig() { }

    static void installWorkbenchConfigFlag(Globals globals) {
        globals.set("RYLUX_WORKBENCH_CONFIG", LuaValue.TRUE);
    }

    /** Converts the workbench's exclusive blue-stat filter into the legacy flags. */
    static String[] blueFilterFlags(String selectedMode) {
        if (selectedMode == null || selectedMode.trim().isEmpty()) return null;
        final int selected;
        try { selected = Integer.parseInt(selectedMode.trim()); }
        catch (NumberFormatException invalid) { return null; }
        if (selected < 0 || selected > 3) return null;
        return new String[]{Boolean.toString(selected == 1), Boolean.toString(selected == 2),
                Boolean.toString(selected == 3)};
    }

    static Map<String, String> defaults() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("自动战斗", "false");
        values.put("战斗技能阈值", "15");
        values.put("装备耐久阈值", "15");
        values.put("自动使用红药", "false");
        values.put("生命药触发阈值", "30");
        values.put("自动使用蓝药", "false");
        values.put("法力药触发阈值", "20");
        values.put("手机编号", "");
        values.put("队长名", "");
        values.put("队员名", "");
        values.put("是否队长", "0");
        values.put("检测队长名字频率秒", "180");
        values.put("检测是否有队伍频率秒", "180");
        values.put("红的数量高于", "10");
        values.put("出售全部红", "false");
        values.put("蓝的数量高于", "10");
        values.put("出售全部蓝", "false");
        values.put("红的数量低于", "10");
        values.put("购买小红", "false");
        values.put("购买红的数量", "10");
        values.put("蓝的数量低于", "10");
        values.put("购买小蓝", "false");
        values.put("购买蓝的数量", "10");
        values.put("挂机点x", "100");
        values.put("挂机点y", "200");
        values.put("挂机点地图", "0");
        values.put("卖货地图", "0");
        values.put("背包保留重量", "3");
        values.put("背包保留格子", "2");
        values.put("检测修理装备", "true");
        values.put("背包检测间隔秒", "60");
        values.put("保留蓝条数量", "4");
        values.put("回蓝>=", "true");
        values.put("保留回蓝数值", "10");
        values.put("魔法出招>=", "true");
        values.put("保留魔法出招数值", "15");
        values.put("武器出招>=", "true");
        values.put("保留武器出招数值", "15");
        values.put("蓝条筛选模式", "1");
        values.put("开启vpn", "false");

        // These fields are read directly by recovered loops. Their workbench
        // widget fallback is not necessarily persisted until the user edits it.
        values.put("挂机不移动秒", "60");
        values.put("队员找不到队长组队就回家次数", "2");
        values.put("保护装备不出售", "true");
        values.put("出售装备", "false");
        values.put("出售装备品质上限", "0");
        values.put("出售装备二次确认", "true");
        values.put("允许出售物品", "false");
        values.put("出售物品清单", "");
        values.put("出售物品数量阈值", "10");
        values.put("允许购买物品", "false");
        values.put("购买物品清单", "");
        values.put("购买物品数量阈值", "10");
        values.put("每次购买数量", "10");
        return Collections.unmodifiableMap(values);
    }
}
