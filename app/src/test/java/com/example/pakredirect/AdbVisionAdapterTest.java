package com.example.pakredirect;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Read-only live smoke test for screenshot, OCR, image matching, and coordinate mapping. */
public final class AdbVisionAdapterTest {
    @Test public void readsLocalizedGameFrameAndValidatesVisionCoordinates() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            device.requireForeground();
            LiveLuaDeviceHost.Frame frame = device.capture();
            BufferedImage screen = frame.image;
            assertTrue("Expected a landscape game frame, got " + screen.getWidth() + "x" + screen.getHeight(),
                    screen.getWidth() >= 1000 && screen.getWidth() > screen.getHeight());

            List<LiveLuaDeviceHost.Row> rows = device.rows(frame);
            assertTrue("RapidOCR returned no visible game text; inspect capture and OCR evidence", !rows.isEmpty());
            boolean chineseText = rows.stream().anyMatch(row -> row.confidence >= 0.50
                    && row.text.matches(".*[\\u4e00-\\u9fff].*"));
            assertTrue("OCR did not detect confident Chinese UI text; inspect capture-001.ocr.tsv", chineseText);
            String mainMenuAnchor = rows.stream()
                    .filter(row -> row.confidence >= 0.80)
                    .map(row -> row.text)
                    .filter(text -> text.contains("背包") || text.contains("角色")
                            || text.contains("技能") || text.contains("设置"))
                    .findFirst()
                    .orElse(null);
            assertNotNull("OCR did not find a main-game menu anchor; leave the guest at its world screen",
                    mainMenuAnchor);

            // Match a crop from this exact live frame through the production image matcher.
            // This exercises real captured pixels and coordinate mapping without touching the game.
            int left = screen.getWidth() / 2 - 24;
            int top = screen.getHeight() / 2 - 24;
            BufferedImage crop = screen.getSubimage(left, top, 48, 48);
            LegacyImageMatcher.Match match = LegacyImageMatcher.find(
                    pixels(screen), pixels(crop), 0, 0, screen.getWidth(), screen.getHeight(),
                    0x111111, 0.98d, () -> { });
            assertNotNull("Live screenshot self-match failed", match);
            assertTrue("Matcher returned an out-of-frame coordinate",
                    match.x >= 0 && match.y >= 0
                            && match.x + crop.getWidth() <= screen.getWidth()
                            && match.y + crop.getHeight() <= screen.getHeight());

            int refX = frame.mapper.toReferenceX(match.x + crop.getWidth() / 2.0);
            int refY = frame.mapper.toReferenceY(match.y + crop.getHeight() / 2.0);
            int roundTripX = frame.mapper.toScreenX(refX);
            int roundTripY = frame.mapper.toScreenY(refY);
            assertTrue("X coordinate conversion drifted by more than one pixel", Math.abs(roundTripX - (match.x + 24)) <= 1);
            assertTrue("Y coordinate conversion drifted by more than one pixel", Math.abs(roundTripY - (match.y + 24)) <= 1);

            // Exercise the recovered Lua color-anchor matcher against this actual
            // device frame and the calibrated settings control visible on the HUD.
            Globals lua = JsePlatform.standardGlobals();
            lua.set("_ENV", lua);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            RecoveredLuaLiveLoginTest.installDeviceApis(lua, device, callbackFailure, new HashMap<>());
            Path scripts = Paths.get("app", "src", "main", "assets", "script_modules", "脚本");
            for (String script : List.of("w.lua", "通用数据.lua")) {
                String source = Files.readString(scripts.resolve(script), StandardCharsets.UTF_8);
                lua.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), script, lua).call();
            }
            LuaTable templates = lua.get("tz").checktable();
            LuaValue settings = templates.get("设置按钮");
            assertTrue("Recovered settings anchor is missing", !settings.isnil());
            Varargs foundSettings = lua.get("找到").checkfunction()
                    .invoke(LuaValue.varargsOf(new LuaValue[]{settings}));
            assertTrue("Recovered Lua did not match the visible settings control", foundSettings.arg1().toboolean());
            int settingsX = foundSettings.arg(2).toint();
            int settingsY = foundSettings.arg(3).toint();
            assertTrue("Settings match was outside its calibrated Lua reference region: " + settingsX + "," + settingsY,
                    settingsX >= 1180 && settingsX <= 1260 && settingsY >= 610 && settingsY <= 700);
            Throwable callbackError = callbackFailure.get();
            if (callbackError != null) throw new IOException("Recovered Lua vision callback failed", callbackError);

            device.tap(settingsX, settingsY, "tap recovered Lua settings match");
            Thread.sleep(1_000);
            LiveLuaDeviceHost.Frame settingsPage = device.capture();
            List<LiveLuaDeviceHost.Row> settingsRows = device.rows(settingsPage);
            String settingsText = settingsRows.stream().map(row -> row.text).reduce("", String::concat);
            boolean settingsTitle = settingsText.contains("系统目录");
            boolean settingsOptions = settingsText.contains("选项") && settingsText.contains("离线托管");
            // Restore the game world even when OCR layout differs; this tap only
            // closes the modal opened by this test and never confirms a setting.
            device.tap(806, 178, "close settings modal");
            Thread.sleep(500);
            LiveLuaDeviceHost.Frame restored = device.capture();
            List<LiveLuaDeviceHost.Row> restoredRows = device.rows(restored);
            boolean worldAnchor = restoredRows.stream().anyMatch(row -> row.confidence >= 0.80
                    && row.text.matches(".*(背包|角色|技能|设置).*"));
            assertTrue("Matched coordinate did not open the localized system menu: " + settingsText,
                    settingsTitle && settingsOptions);
            assertTrue("Closing the test modal did not return to the game HUD", worldAnchor);

            device.event("vision", "PASS screenshot=" + screen.getWidth() + "x" + screen.getHeight()
                    + " ocrRows=" + rows.size() + " chinese=true mainMenu=" + mainMenuAnchor
                    + " matcherScore=" + match.score
                    + " match=" + match.x + "," + match.y + " ref=" + refX + "," + refY
                    + " luaSettings=" + settingsX + "," + settingsY
                    + " settingsPage=true worldRestored=true touches=2");
        }
    }

    private static LegacyImageMatcher.Pixels pixels(BufferedImage image) {
        return new LegacyImageMatcher.Pixels() {
            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int getPixel(int x, int y) { return image.getRGB(x, y); }
        };
    }
}
