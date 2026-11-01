package com.example.pakredirect;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Device-backed Lua login flow with semantic controls and localized-template fallback. */
public final class AdbSemanticLoginTest {
    private static final String GAME = "com.tepaylink.tamgioiphantranhmobile";
    private static final Pattern NODE = Pattern.compile("<node\\b([^>]*)/?>");
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    private static final Pattern BOUNDS = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");
    private static final String SETTINGS_WORLD = "1213|662|c69e63-111111,1212|651|e7cb84-111111,1207|646|f7df8c-111111,1199|646|f7e794-111111,1196|650|f7df8c-111111,1195|663|dec37b-111111,1203|666|cea66b-111111,1227|670|b5ae9c-111111,1227|660|d6cfbd-111111,1225|659|e7e3d6-111111,1222|648|c6bead-111111,1223|641|d6cbbd-111111,1218|636|e7dfce-111111";
    private static final String SKILLS_WORLD = "1250|662|eff7ff-222222,1248|660|f7fbff-222222,1240|659|f7fbff-222222,1233|659|f7fbff-222222,1230|660|f7fbff-222222,1237|662|eff7ff-222222,1246|662|eff7ff-222222,1243|678|9cd7ef-222222,1239|675|addfef-222222,1231|674|b5e3ef-222222,1224|674|b5e3ef-222222,1222|675|addfef-222222,1234|676|a5dfef-222222,1240|677|a5dbef-222222,1237|250|bdb2ef-222222,1234|246|cecff7-222222,1215|249|bdbaef-222222,1218|246|cecff7-222222,1219|232|ffffff-222222,1216|228|ffffff-222222,1234|231|ffffff-222222,1238|228|ffffff-222222";
    private static final String LOADING_BAR = "160|616|08cfce-303030,320|616|08cfce-303030,480|616|08cfce-303030,640|616|00c7ce-303030,800|616|00c7ce-303030,960|616|00c7ce-303030,1120|616|00c7c6-303030";

    @Test public void runsBoundedLoginFlowAgainstAdbGuest() throws Exception {
        Device device = Device.connect();
        Path evidence = Paths.get("work", "adb-login-test").toAbsolutePath();
        Files.createDirectories(evidence);
        device.evidence = evidence;
        device.startGameFromSandbox();

        Globals globals = JsePlatform.standardGlobals();
        globals.set("_ENV", globals);
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(System.nanoTime() / 1_000_000L); }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue duration) {
                try { Thread.sleep(Math.max(0, duration.checklong())); return LuaValue.NONE; }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("ADB Lua run interrupted", interrupted);
                }
            }
        });
        globals.set("gameUiState", new ZeroArgFunction() {
            @Override public LuaValue call() {
                try { return LuaValue.valueOf(device.state()); }
                catch (Exception failure) { throw new IllegalStateException("ADB state probe failed: " + failure.getMessage(), failure); }
            }
        });
        globals.set("gameUiClick", new OneArgFunction() {
            @Override public LuaValue call(LuaValue action) {
                try { return LuaValue.valueOf(device.click(action.checkjstring())); }
                catch (Exception failure) { throw new IllegalStateException("ADB semantic click failed: " + failure.getMessage(), failure); }
            }
        });
        globals.set("log", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { device.log(args.tojstring()); return LuaValue.NONE; }
                catch (IOException failure) { throw new IllegalStateException(failure); }
            }
        });

        String sourcePath = "app/src/main/assets/script_modules/脚本/登录无障碍.lua";
        String source = Files.readString(Paths.get(sourcePath), StandardCharsets.UTF_8);
        globals.load(source, sourcePath).call();

        // The Lua state machine owns timing, retry decisions, and the 10-second
        // timeout. If accessibility nodes are absent, its callbacks use the
        // small localized templates rather than blind coordinate guesses.
        String initial = device.state();
        if ("unknown".equals(initial)) {
            device.log("semantic nodes absent; Lua callbacks will use localized button templates");
        }

        Varargs result = globals.get("无图登录运行").invoke(LuaValue.varargsOf(new LuaValue[]{
                LuaValue.valueOf(180_000), LuaValue.valueOf(3)}));
        if (!result.arg1().toboolean()) throw new AssertionError(result.arg(2).tojstring());
    }

    private static final class Device {
        final String adb, serial;
        String foreground;
        Path evidence;
        long lastTreeAt;
        String cachedTree;
        int dumpCount;
        int screenshotCount;
        boolean semanticNodesAbsent;
        final Map<String, BufferedImage> loginTemplates = new HashMap<>();

        private Device(String adb, String serial, String foreground) {
            this.adb = adb; this.serial = serial; this.foreground = foreground;
        }

        static Device connect() throws Exception {
            String adb = System.getenv("RYLUX_ADB_EXECUTABLE");
            if (adb == null || adb.trim().isEmpty()) adb = "E:\\leidian\\LDPlayer14\\adb.exe";
            if (!Files.isRegularFile(Paths.get(adb))) throw new AssertionError("找不到 ADB: " + adb);
            String serial = System.getenv("RYLUX_ADB_DEVICE");
            if (serial == null || serial.trim().isEmpty()) {
                String devices = run(adb, null, 8, "devices");
                List<String> online = new ArrayList<>();
                for (String line : devices.split("\\R")) {
                    String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 2 && "device".equals(fields[1])) online.add(fields[0]);
                }
                if (online.size() != 1) throw new AssertionError("需要恰好一个已连接 ADB 设备；当前在线设备=" + online
                        + "。多设备时设置环境变量 RYLUX_ADB_DEVICE。未执行触控。");
                serial = online.get(0);
            }
            return new Device(adb, serial, "ADB device=" + serial);
        }

        void startGameFromSandbox() throws Exception {
            // The BAT is one-click: restart RYLUX cleanly, navigate its accessible
            // controls, and launch the installed guest from the BlackBox screen.
            run(adb, serial, 8, "shell", "am", "force-stop", "com.example.pakredirect");
            run(adb, serial, 8, "shell", "am", "start", "-n", "com.example.pakredirect/.MainActivity");
            log("started RYLUX MainActivity");
            String tree = waitForText("BlackBox 游戏沙箱 · 测试版", 30, true);
            clickText(tree, "BlackBox 游戏沙箱 · 测试版");

            tree = waitForSandboxGame(30);
            if (tree == null) throw new AssertionError("沙箱没有导入目标游戏包 " + GAME + "。没有启动其他包；"
                    + "请检查 work/adb-login-test 中的 UI 树。");
            if (!checked(tree, "RYLUX 汉化")) {
                if (!clickDescription(tree, "RYLUX 汉化"))
                    throw new AssertionError("沙箱内找不到可操作的 RYLUX 汉化开关；停止测试。");
                tree = waitForSandboxGame(10);
            }
            if (tree == null || !checked(tree, "RYLUX 汉化")) throw new AssertionError("RYLUX 汉化开关未打开；为避免测试错版本，停止启动。");
            if (!clickText(tree, "启动游戏")) throw new AssertionError("沙箱中没有唯一可点击的“启动游戏”按钮。");
            log("requested localized guest launch through BlackBoxSandboxActivity");

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
            while (System.nanoTime() < deadline) {
                String focus = foregroundDump();
                if (isGameFocused(focus)) {
                    log("guest foreground " + summarizeFocus(focus));
                    foreground = summarizeFocus(focus);
                    Files.writeString(evidence.resolve("foreground.txt"), focus, StandardCharsets.UTF_8);
                    cachedTree = null;
                    return;
                }
                Thread.sleep(750);
            }
            throw new AssertionError("75 秒内未检测到沙箱游戏进入前台；未发送游戏触控。查看 " + evidence);
        }

        private String waitForText(String text, int seconds, boolean scroll) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            int swipes = 0;
            while (System.nanoTime() < deadline) {
                String tree = uiTree();
                if (tree.contains(text)) return tree;
                if (scroll && swipes++ < 10) {
                    run(adb, serial, 8, "shell", "input", "swipe", "800", "800", "800", "300", "350");
                    cachedTree = null;
                } else Thread.sleep(500);
            }
            throw new AssertionError("等待 RYLUX 控件超时：" + text + "; 页面树见 " + evidence);
        }

        private String waitForSandboxGame(int seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                String tree = uiTree();
                if (tree.contains(GAME) && tree.contains("启动游戏") && tree.contains("RYLUX 汉化")) return tree;
                Thread.sleep(500);
            }
            return null;
        }

        private boolean clickText(String tree, String text) throws Exception {
            List<int[]> bounds = matchingBounds(tree, "text", text, true);
            if (bounds.size() != 1) return false;
            tap(bounds.get(0));
            return true;
        }

        private boolean clickDescription(String tree, String description) throws Exception {
            List<int[]> bounds = matchingBounds(tree, "content-desc", description, true);
            if (bounds.size() != 1) return false;
            tap(bounds.get(0));
            return true;
        }

        private static boolean checked(String tree, String description) {
            Matcher nodes = NODE.matcher(tree);
            while (nodes.find()) {
                String raw = nodes.group(1);
                if (description.equals(attr(raw, "content-desc")) && "true".equals(attr(raw, "checked"))) return true;
            }
            return false;
        }

        private static List<int[]> matchingBounds(String tree, String attribute, String value, boolean clickable) {
            List<int[]> result = new ArrayList<>();
            Matcher nodes = NODE.matcher(tree);
            while (nodes.find()) {
                String raw = nodes.group(1);
                if (!value.equals(attr(raw, attribute))) continue;
                if (clickable && (!"true".equals(attr(raw, "clickable")) || !"true".equals(attr(raw, "enabled")))) continue;
                Matcher bounds = BOUNDS.matcher(attr(raw, "bounds"));
                if (!bounds.matches()) continue;
                result.add(new int[]{(Integer.parseInt(bounds.group(1)) + Integer.parseInt(bounds.group(3))) / 2,
                        (Integer.parseInt(bounds.group(2)) + Integer.parseInt(bounds.group(4))) / 2});
            }
            return result;
        }

        private void tap(int[] point) throws Exception {
            run(adb, serial, 8, "shell", "input", "tap", Integer.toString(point[0]), Integer.toString(point[1]));
            cachedTree = null;
            Thread.sleep(350);
        }

        void requireGameForeground() throws Exception {
            String focus = foregroundDump();
            if (!isGameFocused(focus)) throw new AssertionError("游戏已离开前台；拒绝控制其他应用。");
        }

        private String foregroundDump() throws Exception {
            return run(adb, serial, 8, "shell", "dumpsys", "window") + "\n"
                    + run(adb, serial, 8, "shell", "dumpsys", "activity", "activities");
        }

        String state() throws Exception {
            requireGameForeground();
            List<String> states = new ArrayList<>();
            if (!semanticNodesAbsent) {
                String tree = uiTree();
                Matcher nodes = NODE.matcher(tree);
                while (nodes.find()) {
                    String desc = attr(nodes.group(1), "content-desc");
                    if (desc.startsWith("rylux.game.state.")) states.add(desc.substring("rylux.game.state.".length()));
                }
                if (states.isEmpty()) semanticNodesAbsent = true;
            }
            if (states.size() == 1) {
                String state = states.get(0);
                log("state=" + state + " source=accessibility");
                return state;
            }
            if (states.size() > 1) return "unknown";
            Frame frame = captureFrame("state");
            List<String> matched = new ArrayList<>();
            for (String candidate : Arrays.asList("agree", "server-confirm", "login", "confirm", "return"))
                if (match(frame, candidate) != null) matched.add(candidate);
            log("template_candidates=" + matched);
            if (matched.size() == 1) {
                String state = "agree".equals(matched.get(0)) ? "terms"
                        : "server-confirm".equals(matched.get(0)) ? "server"
                        : "login".equals(matched.get(0)) ? "login"
                        : "confirm".equals(matched.get(0)) ? "role" : "connecting";
                log("state=" + state + " source=localized-template");
                return state;
            }
            if (matched.size() > 1) {
                log("state=unknown reason=ambiguous-templates " + matched);
                return "unknown";
            }
            if (isWorld(frame)) {
                log("state=world source=localized-main-ui-colors");
                return "world";
            }
            if (matchesWorldPoints(frame, LOADING_BAR, 0.80d)) {
                log("state=loading source=localized-loading-progress-bar");
                return "loading";
            }
            log("state=unknown source=no-accessibility-or-template-match");
            return "unknown";
        }

        boolean click(String action) throws Exception {
            requireGameForeground();
            String target = "rylux.game.action." + action;
            int matches = 0, x = -1, y = -1;
            if (!semanticNodesAbsent) {
                String tree = uiTree();
                Matcher nodes = NODE.matcher(tree);
                while (nodes.find()) {
                    String raw = nodes.group(1);
                    if (!target.equals(attr(raw, "content-desc"))) continue;
                    if (!"true".equals(attr(raw, "clickable")) || !"true".equals(attr(raw, "enabled"))) continue;
                    Matcher bounds = BOUNDS.matcher(attr(raw, "bounds"));
                    if (!bounds.matches()) continue;
                    x = (Integer.parseInt(bounds.group(1)) + Integer.parseInt(bounds.group(3))) / 2;
                    y = (Integer.parseInt(bounds.group(2)) + Integer.parseInt(bounds.group(4))) / 2;
                    matches++;
                }
            }
            if (matches > 1) { log("action=" + action + " rejected semanticMatches=" + matches); return false; }
            if (matches == 0) {
                String templateName = "agree".equals(action) ? "agree"
                        : "server_confirm".equals(action) ? "server-confirm"
                        : "login".equals(action) ? "login"
                        : "confirm".equals(action) ? "confirm" : "back".equals(action) ? "return" : null;
                if (templateName == null) { log("action=" + action + " rejected unknown"); return false; }
                ControlMatch control = match(captureFrame("action-" + action), templateName);
                if (control == null) { log("action=" + action + " rejected template-not-visible"); return false; }
                tapMatch(control);
                return true;
            }
            log("action=" + action + " x=" + x + " y=" + y + " source=accessibility");
            run(adb, serial, 8, "shell", "input", "tap", Integer.toString(x), Integer.toString(y));
            cachedTree = null;
            return true;
        }

        private Frame captureFrame(String label) throws Exception {
            requireGameForeground();
            byte[] png = runBytes(adb, serial, 8, "exec-out", "screencap", "-p");
            BufferedImage image = decodePng(png);
            int number = ++screenshotCount;
            log("screenshot_probe=" + number + " purpose=" + label + " dimensions=" + image.getWidth() + "x" + image.getHeight()
                    + " foreground=" + foreground);
            return new Frame(image);
        }

        private ControlMatch match(Frame frame, String name) throws Exception {
            return findTemplate(frame.image, name, loginTemplates);
        }

        private boolean isWorld(Frame frame) {
            return matchesWorldPoints(frame, SETTINGS_WORLD, 0.90d)
                    || matchesWorldPoints(frame, SKILLS_WORLD, 0.90d);
        }

        private boolean matchesWorldPoints(Frame frame, String points, double threshold) {
            LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                    frame.image.getWidth(), frame.image.getHeight());
            return LegacyLuaCompat.matchesColorPoints(mapper.mapColorPoints(points), threshold,
                    frame.image.getWidth(), frame.image.getHeight(), frame.image::getRGB);
        }

        private static LegacyImageMatcher.Pixels pixels(BufferedImage image) {
            return new LegacyImageMatcher.Pixels() {
                @Override public int width() { return image.getWidth(); }
                @Override public int height() { return image.getHeight(); }
                @Override public int getPixel(int x, int y) { return image.getRGB(x, y); }
            };
        }

        private String uiTree() throws Exception {
            if (cachedTree != null && System.nanoTime() - lastTreeAt < TimeUnit.MILLISECONDS.toNanos(700)) return cachedTree;
            String remote = "/sdcard/rylux-semantic-login-" + (++dumpCount) + ".xml";
            run(adb, serial, 12, "shell", "uiautomator", "dump", remote);
            byte[] data = runBytes(adb, serial, 8, "exec-out", "cat", remote);
            cachedTree = new String(data, StandardCharsets.UTF_8);
            Files.writeString(evidence.resolve("ui-" + dumpCount + ".xml"), redactInputText(cachedTree), StandardCharsets.UTF_8);
            lastTreeAt = System.nanoTime();
            return cachedTree;
        }

        int countPrefix(String prefix) throws Exception {
            int count = 0;
            Matcher nodes = NODE.matcher(uiTree());
            while (nodes.find()) if (attr(nodes.group(1), "content-desc").startsWith(prefix)) count++;
            return count;
        }

        void log(String message) throws IOException {
            Files.writeString(evidence.resolve("events.log"), Instant.now() + " " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }

        private static String attr(String attributes, String name) {
            Matcher matcher = ATTR.matcher(attributes);
            while (matcher.find()) if (name.equals(matcher.group(1))) return matcher.group(2);
            return "";
        }

        private static String redactInputText(String tree) {
            Matcher nodes = NODE.matcher(tree);
            StringBuffer safe = new StringBuffer();
            while (nodes.find()) {
                String tag = nodes.group();
                if (tag.contains("class=\"android.widget.EditText\"") || tag.contains("password=\"true\"")) {
                    tag = tag.replaceAll("text=\"[^\"]*\"", "text=\"[redacted]\"");
                    tag = tag.replaceAll("content-desc=\"[^\"]*\"", "content-desc=\"[redacted]\"");
                }
                nodes.appendReplacement(safe, Matcher.quoteReplacement(tag));
            }
            nodes.appendTail(safe);
            return safe.toString();
        }

        private static String summarizeFocus(String dump) {
            String fallback = null;
            for (String line : dump.split("\\R")) {
                if (!line.contains("mCurrentFocus=") && !line.contains("mFocusedApp=")) continue;
                if (fallback == null) fallback = line.trim();
                if (!line.contains("=null")) return line.trim();
            }
            return fallback == null ? "unknown" : fallback;
        }

        private static boolean isGameFocused(String dump) {
            boolean focusedGame = false, proxyFocused = false, resumedGuest = false;
            for (String line : dump.split("\\R")) {
                if ((line.contains("mCurrentFocus=") || line.contains("mFocusedApp=")) && line.contains(GAME)) focusedGame = true;
                if ((line.contains("mCurrentFocus=") || line.contains("mFocusedApp="))
                        && line.contains("top.niunaijun.blackbox.proxy.ProxyActivity$")) proxyFocused = true;
                if (line.contains("topResumedActivity=") && line.contains(GAME)) resumedGuest = true;
            }
            return focusedGame || (proxyFocused && resumedGuest);
        }

        private void tapMatch(ControlMatch match) throws Exception {
            requireGameForeground();
            ControlMatch current = match(captureFrame("verify-before-tap-" + match.name), match.name);
            if (current == null) throw outcomeUnknown("控件模板在触控前消失: " + match.name);
            requireGameForeground();
            log("mode=template-gated-coordinate action=" + current.name + " score=" + current.score
                    + " x=" + current.x + " y=" + current.y + " foreground=" + foreground);
            run(adb, serial, 8, "shell", "input", "tap", Integer.toString(current.x), Integer.toString(current.y));
            cachedTree = null;
            Thread.sleep(350);
        }

        private AssertionError outcomeUnknown(String reason) throws IOException {
            log("outcome=unobservable reason=" + reason);
            return new AssertionError(reason + "; 已停止触控。证据日志=" + evidence.resolve("events.log"));
        }
    }

    static boolean templateVisibleForTest(BufferedImage image, String name) throws Exception {
        return findTemplate(image, name, new HashMap<>()) != null;
    }

    private static ControlMatch findTemplate(BufferedImage image, String name,
            Map<String, BufferedImage> templates) throws Exception {
        Template spec = Template.forName(name);
        if (spec == null) throw new IllegalArgumentException("Unknown login template: " + name);
        BufferedImage source = templates.get(spec.file);
        if (source == null) {
            Path path = Paths.get("app", "src", "main", "assets", "script_modules", "脚本", "素材库", spec.file);
            source = ImageIO.read(path.toFile());
            if (source == null) throw new IOException("Unreadable login button template: " + path);
            templates.put(spec.file, source);
        }
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720, image.getWidth(), image.getHeight());
        // These button crops were saved from a slightly smaller 16:9 capture.
        // A 1.05 raster scale was measured against the live 1600x900 sandbox
        // terms screen; using the legacy 1280x720 scale (1.25) overshoots them.
        int width = Math.max(1, (int)Math.round(source.getWidth() * spec.scale));
        int height = Math.max(1, (int)Math.round(source.getHeight() * spec.scale));
        String key = spec.file + "@" + width + "x" + height;
        BufferedImage scaled = templates.get(key);
        if (scaled == null) {
            scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            templates.put(key, scaled);
        }
        LegacyImageMatcher.Match hit = LegacyImageMatcher.find(pixels(image), pixels(scaled),
                mapper.left(spec.left), mapper.top(spec.top), mapper.edgeRight(spec.right), mapper.edgeBottom(spec.bottom),
                0x00101010, 0.80d, () -> { });
        if (hit == null) return null;
        return new ControlMatch(name, hit.x + scaled.getWidth() / 2, hit.y + scaled.getHeight() / 2, hit.score);
    }

    private static LegacyImageMatcher.Pixels pixels(BufferedImage image) {
        return new LegacyImageMatcher.Pixels() {
            @Override public int width() { return image.getWidth(); }
            @Override public int height() { return image.getHeight(); }
            @Override public int getPixel(int x, int y) { return image.getRGB(x, y); }
        };
    }

    private static final class Frame {
        final BufferedImage image;
        Frame(BufferedImage image) { this.image = image; }
    }

    private static final class ControlMatch {
        final String name;
        final int x, y;
        final double score;
        ControlMatch(String name, int x, int y, double score) {
            this.name = name; this.x = x; this.y = y; this.score = score;
        }
    }

    private static final class Template {
        final String name, file;
        final int left, top, right, bottom;
        final double scale;
        private Template(String name, String file, int left, int top, int right, int bottom, double scale) {
            this.name = name; this.file = file; this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.scale = scale;
        }
        static Template forName(String name) {
            if ("agree".equals(name)) return new Template(name, "汉化条款同意按钮.png", 380, 550, 580, 650, 1.05d);
            if ("server-confirm".equals(name)) return new Template(name, "汉化服务器确认按钮.png", 650, 610, 850, 710, 1.05d);
            if ("login".equals(name)) return new Template(name, "汉化登录按钮.png", 480, 455, 820, 585, 1.25d);
            if ("confirm".equals(name)) return new Template(name, "汉化角色确认按钮.png", 950, 510, 1210, 625, 1.25d);
            if ("return".equals(name)) return new Template(name, "汉化登录返回按钮.png", 530, 420, 750, 520, 1.25d);
            return null;
        }
    }

    private static String run(String adb, String serial, int timeoutSeconds, String... args) throws Exception {
        return new String(runBytes(adb, serial, timeoutSeconds, args), StandardCharsets.UTF_8);
    }

    static BufferedImage decodePng(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null) throw new IOException("ADB screenshot is not a readable PNG");
        return image;
    }

    private static byte[] runBytes(String adb, String serial, int timeoutSeconds, String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add(adb);
        if (serial != null) { command.add("-s"); command.add(serial); }
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) { input.transferTo(output); }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IOException("ADB timeout: " + String.join(" ", args));
        }
        byte[] bytes = output.toByteArray();
        if (process.exitValue() != 0) throw new IOException("ADB failed: " + new String(bytes, StandardCharsets.UTF_8));
        return bytes;
    }
}
