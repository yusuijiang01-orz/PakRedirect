package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public final class ScriptMapCatalogParityTest {
    @Test public void chineseMapNamesMatchAcrossAllFourCatalogs() throws Exception {
        List<String> javaMaps = Arrays.asList(ScriptMapCatalog.MAPS);
        assertEquals(38, javaMaps.size());
        assertEquals("Java catalog contains duplicate names", 38, new HashSet<>(javaMaps).size());

        String lua = read("app/src/main/assets/script_modules/脚本/封神榜.lua");
        String python = read("tools/adb_game_test_panel.py");
        String html = read("tools/adb-game-test-panel.html");

        String luaRows = capture(lua,
                "L0_1 = splitStr\\s+L1_1 = \"([^\"]+)\"\\s+L2_1 = \"\\\\n\"\\s+"
                        + "L0_1 = L0_1\\(L1_1, L2_1\\)\\s+_ENV\\[\"中越大地图数组\"\\] = L0_1",
                "Lua map array");
        List<String> luaMaps = new ArrayList<>();
        for (String row : luaRows.split(Pattern.quote("\\n"), -1)) {
            int separator = row.indexOf(',');
            assertTrue("Lua map row lacks a Chinese/Vietnamese separator: " + row,
                    separator > 0 && separator < row.length() - 1);
            luaMaps.add(row.substring(0, separator));
        }

        List<String> pythonMaps = quotedNames(capture(python,
                "ROUTE_MAP_NAMES\\s*=\\s*\\(([^)]*)\\)", "Python route maps"), "\"");
        List<String> htmlMaps = quotedNames(capture(html,
                "const\\s+maps\\s*=\\s*\\[([^]]*)]", "HTML route maps"), "'");

        assertEquals("Lua map count", 38, luaMaps.size());
        assertEquals("Python map count", 38, pythonMaps.size());
        assertEquals("HTML map count", 38, htmlMaps.size());
        assertEquals("Lua map names and order", javaMaps, luaMaps);
        assertEquals("Python map names and order", javaMaps, pythonMaps);
        assertEquals("HTML map names and order", javaMaps, htmlMaps);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static String capture(String source, String expression, String label) {
        Matcher matcher = Pattern.compile(expression).matcher(source);
        assertTrue(label + " was not found", matcher.find());
        String value = matcher.group(1);
        assertFalse(label + " has multiple definitions", matcher.find());
        return value;
    }

    private static List<String> quotedNames(String source, String quote) {
        String body = source.trim();
        Pattern item = Pattern.compile("\\s*" + Pattern.quote(quote) + "([^" + quote + "]+)"
                + Pattern.quote(quote) + "\\s*(?:,|$)");
        Matcher matcher = item.matcher(body);
        List<String> names = new ArrayList<>();
        int position = 0;
        while (position < body.length()) {
            matcher.region(position, body.length());
            assertTrue("Unexpected text in map catalog: " + body.substring(position), matcher.lookingAt());
            names.add(matcher.group(1));
            position = matcher.end();
        }
        return names;
    }
}
