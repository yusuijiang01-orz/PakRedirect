package com.example.pakredirect;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Canonical localized map names shared by the workbench and the Lua route bridge. */
final class ScriptMapCatalog {
    static final String[] MAPS = {
            "崇城野外", "北海", "燕山", "西昆仑", "昆仑山", "首阳山", "游魂关", "苗疆", "巨鹿",
            "潼关", "孟津", "城塘关", "荒漠", "荒漠2", "荒漠3", "荒漠4", "荒漠5",
            "海1", "海2", "海3", "海4", "海5", "火1", "火2", "火3", "火4", "火5",
            "冰1", "冰2", "冰3", "冰4", "冰5", "捆仙宫1", "捆仙宫2", "捆仙宫3",
            "捆仙宫4", "捆仙宫5", "碧游宫1"
    };
    private static final Set<String> LOOKUP = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(MAPS)));

    private ScriptMapCatalog() {}

    static boolean contains(String mapName) {
        return mapName != null && LOOKUP.contains(mapName);
    }
}
