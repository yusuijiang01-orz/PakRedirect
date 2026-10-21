package com.example.pakredirect;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Opt-in, device-backed execution of only the recovered Lua login decision. */
public final class RecoveredLuaLiveLoginTest {
    private static final long RUN_LIMIT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final long UNKNOWN_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(35);

    @Test public void recoveredLuaLoginReachesObservedGameWorld() throws Exception {
        long deadline = System.nanoTime() + RUN_LIMIT_NANOS;
        try (LiveLuaDeviceHost device = new LiveLuaDeviceHost(deadline)) {
            Globals globals = JsePlatform.standardGlobals();
            globals.set("_ENV", globals);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            installDeviceApis(globals, device, callbackFailure);
            Path scripts = Paths.get("app", "src", "main", "assets", "script_modules", "脚本");
            for (String name : Arrays.asList("w.lua", "中控.lua", "通用数据.lua", "挂机模式.lua")) {
                String source = Files.readString(scripts.resolve(name), StandardCharsets.UTF_8);
                globals.load(LuaSourceCompat.normalizeUnicodeIdentifiers(source), name, globals).call();
            }
            LuaFunction loginRound = globals.get("登录_判断一轮").checkfunction();
            LuaFunction inMainPage = globals.get("在主页面").checkfunction();
            LuaTable templates = globals.get("tz").checktable();
            long unknownSince = 0;
            int rounds = 0;
            try {
                while (true) {
                    device.checkDeadline();
                    LiveLuaDeviceHost.Frame before = device.capture();
                    checkCallbackFailure(callbackFailure);
                    if (worldVisible(globals, inMainPage, templates, device)) {
                        checkCallbackFailure(callbackFailure);
                        device.event("result", "WORLD_RECOGNIZED rounds=" + rounds + " actions=" + device.actionCount());
                        return;
                    }
                    int actionsBeforeProbe = device.actionCount();
                    if (knownLoginOrTransition(before, globals, templates, device)) unknownSince = 0;
                    else if (unknownSince == 0) unknownSince = System.nanoTime();
                    else if (System.nanoTime() - unknownSince > UNKNOWN_LIMIT_NANOS)
                        throw new IOException("Unrecognized game screen persisted for 35 seconds; last capture="
                                + before.number + "; see evidence screenshots and OCR rows");
                    if (device.actionCount() != actionsBeforeProbe) {
                        Thread.sleep(850);
                        continue;
                    }

                    device.event("round", Integer.toString(++rounds));
                    loginRound.call();
                    checkCallbackFailure(callbackFailure);
                    device.invalidate();
                    Thread.sleep(850);
                }
            } catch (Exception | Error failure) {
                try { device.event("result", "FAILED " + failure.getClass().getSimpleName() + " "
                        + safeDiagnostic(failure.getMessage())); } catch (IOException ignored) { }
                throw failure;
            }
        }
    }

    private static String safeDiagnostic(String value) {
        // Device OCR and saved credentials stay in evidence files, never JUnit output.
        if (value == null) return "";
        return value.replaceAll("[A-Za-z0-9]{5,}", "[redacted]");
    }

    private static void checkCallbackFailure(AtomicReference<Throwable> failure) throws IOException {
        Throwable cause = failure.get();
        if (cause != null) throw new IOException("Live Lua callback failed: " + safeDiagnostic(cause.getMessage()), cause);
    }

    private static void installDeviceApis(Globals globals, LiveLuaDeviceHost device,
                                          AtomicReference<Throwable> failure) {
        globals.set("utf8", LegacyLuaCompat.createUtf8Compat());
        globals.set("tickCount", new ZeroArgFunction() {
            @Override public LuaValue call() { return LuaValue.valueOf(TimeUnit.NANOSECONDS.toMillis(System.nanoTime())); }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try {
                    device.tap(args.arg(1).checkdouble(), args.arg(2).checkdouble(), "Lua tap");
                    return LuaValue.TRUE;
                } catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                try {
                    long duration = Math.max(0, millis.checklong());
                    device.checkDeadline();
                    Thread.sleep(duration);
                    if (duration >= 100) device.invalidate();
                    return LuaValue.NONE;
                } catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("releaseCapture", new ZeroArgFunction() {
            @Override public LuaValue call() { device.invalidate(); return LuaValue.NONE; }
        });
        // Recovered scripts may print saved account data. The durable OCR transcript
        // and action journal are recorded by the device host instead.
        VarArgFunction quiet = new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; }
        };
        globals.set("print", quiet); globals.set("toast", quiet); globals.set("hideToast", quiet);
        globals.set("cmpColorEx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try {
                    LiveLuaDeviceHost.Frame frame = device.frame();
                    String points = frame.mapper.mapColorPoints(args.arg1().checkjstring());
                    boolean match = LegacyLuaCompat.matchesColorPoints(points, args.arg(2).optdouble(0.9),
                            frame.image.getWidth(), frame.image.getHeight(), frame.image::getRGB);
                    device.event("color", "capture=" + frame.number + " matched=" + match);
                    return LuaValue.valueOf(match ? 1 : 0);
                } catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return findMultiColor(device, args); }
                catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return ocrRows(device, args); }
                catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("tomocrtext", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                try { return LuaValue.valueOf(ocrText(ocrRows(device, args), 0)); }
                catch (Exception error) { return fail(failure, error); }
            }
        });
        globals.set("tomocrtextall", LegacyLuaCompat.createTomocrTextAll(args -> {
            try { return ocrRows(device, args); }
            catch (Exception error) { return fail(failure, error).checktable(); }
        }));
        for (String name : Arrays.asList("keepCapture", "findPic", "findStr", "findImage", "tomocr", "useDict",
                "findMultiColorAll", "touchDown", "touchMove", "touchUp", "touchMoveEx", "keyPress")) {
            globals.set(name, new VarArgFunction() {
                @Override public Varargs invoke(Varargs args) {
                    return fail(failure, new UnsupportedOperationException("Unsupported live Lua API invoked: " + name));
                }
            });
        }
    }

    private static LuaValue fail(AtomicReference<Throwable> failure, Throwable error) {
        failure.compareAndSet(null, error);
        throw new LuaError(error.getClass().getSimpleName() + ": " + safeDiagnostic(error.getMessage()));
    }

    private static LuaTable ocrRows(LiveLuaDeviceHost device, Varargs args) throws Exception {
        LiveLuaDeviceHost.Frame frame = device.frame();
        double left = args.arg(1).checkdouble(), top = args.arg(2).checkdouble();
        double right = args.arg(3).checkdouble(), bottom = args.arg(4).checkdouble();
        if (right < left || bottom < top) throw new IOException("Invalid Lua OCR region");
        LuaTable rows = new LuaTable();
        int count = 0;
        for (LiveLuaDeviceHost.Row line : frame.rows) {
            int x = frame.mapper.toReferenceX(line.x), y = frame.mapper.toReferenceY(line.y);
            if (x < left || x > right || y < top || y > bottom) continue;
            LuaTable row = new LuaTable();
            row.set("words", LuaValue.valueOf(line.text));
            row.set("text", LuaValue.valueOf(line.text));
            row.set("x", LuaValue.valueOf(x)); row.set("y", LuaValue.valueOf(y));
            row.set("score", LuaValue.valueOf(line.confidence)); row.set("success", LuaValue.TRUE);
            rows.set(++count, row);
        }
        device.event("ocr_query", "capture=" + frame.number + " region=" + left + "," + top + ","
                + right + "," + bottom + " rows=" + count);
        return rows;
    }

    private static String ocrText(LuaTable rows, double minimumScore) {
        StringBuilder result = new StringBuilder();
        for (int i = 1; i <= rows.length(); i++) {
            LuaTable row = rows.get(i).checktable();
            if (row.get("score").todouble() >= minimumScore) result.append(row.get("words").tojstring());
        }
        return result.toString();
    }

    private static Varargs findMultiColor(LiveLuaDeviceHost device, Varargs args) throws Exception {
        LiveLuaDeviceHost.Frame frame = device.frame();
        BufferedImage image = frame.image;
        LegacyCoordinateMapper mapper = frame.mapper;
        int left = mapper.left(args.arg(1).checkdouble()), top = mapper.top(args.arg(2).checkdouble());
        int right = mapper.right(args.arg(3).checkdouble()), bottom = mapper.bottom(args.arg(4).checkdouble());
        int[] primary = color(args.arg(5).checkjstring().split("\\|", 2)[0]);
        String[] fields = args.arg(6).checkjstring().split("\\|", -1);
        if (fields.length == 0 || fields.length % 3 != 0) throw new IOException("Invalid findMultiColor offsets");
        List<int[]> offsets = new ArrayList<>();
        for (int i = 0; i < fields.length; i += 3) {
            int[] target = color(fields[i + 2]);
            offsets.add(new int[]{mapper.toScreenDeltaX(Integer.parseInt(fields[i].trim())),
                    mapper.toScreenDeltaY(Integer.parseInt(fields[i + 1].trim())), target[0], target[1]});
        }
        for (int y = top; y <= bottom; y++) {
            device.checkDeadline();
            for (int x = left; x <= right; x++) {
            if (!matches(image.getRGB(x, y), primary[0], primary[1])) continue;
            boolean matched = true;
            for (int[] offset : offsets) {
                int px = x + offset[0], py = y + offset[1];
                if (px < 0 || py < 0 || px >= image.getWidth() || py >= image.getHeight()
                        || !matches(image.getRGB(px, py), offset[2], offset[3])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                device.event("multi_color", "capture=" + frame.number + " matched=true");
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(mapper.toReferenceX(x)),
                        LuaValue.valueOf(mapper.toReferenceY(y))});
            }
            }
        }
        device.event("multi_color", "capture=" + frame.number + " matched=false");
        return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
    }

    private static int[] color(String encoded) {
        String[] parts = encoded.split("-", 2);
        return new int[]{Integer.parseInt(parts[0], 16), parts.length == 2 ? Integer.parseInt(parts[1], 16) : 0};
    }

    private static boolean matches(int actual, int expected, int tolerance) {
        return Math.abs(((actual >> 16) & 255) - ((expected >> 16) & 255)) <= ((tolerance >> 16) & 255)
                && Math.abs(((actual >> 8) & 255) - ((expected >> 8) & 255)) <= ((tolerance >> 8) & 255)
                && Math.abs((actual & 255) - (expected & 255)) <= (tolerance & 255);
    }

    private static boolean template(Globals globals, LuaTable templates, String name) {
        LuaValue target = templates.get(name);
        if (target.isnil()) throw new LuaError("Missing recovered HUD template: " + name);
        return globals.get("找到").checkfunction().call(target).toboolean();
    }

    private static boolean worldVisible(Globals globals, LuaFunction inMainPage, LuaTable templates,
                                        LiveLuaDeviceHost device) throws Exception {
        boolean main = inMainPage.call().toboolean();
        boolean settings = template(globals, templates, "设置按钮");
        boolean skills = template(globals, templates, "切换技能按钮");
        LiveLuaDeviceHost.Frame frame = device.frame();
        int hudRatios = 0;
        double lastY = -100;
        for (LiveLuaDeviceHost.Row row : frame.rows) {
            if (row.confidence < 0.50 || row.x >= frame.image.getWidth() * 0.32
                    || row.y >= frame.image.getHeight() * 0.16
                    || !row.text.matches(".*\\d{1,5}\\s*[/／]\\s*\\d{1,5}.*")) continue;
            if (Math.abs(row.y - lastY) >= 10) { hudRatios++; lastY = row.y; }
        }
        device.event("world_probe", "main=" + main + " settings=" + settings + " skills=" + skills
                + " ocr_hud_ratios=" + hudRatios);
        return main && settings && skills && hudRatios >= 2;
    }

    private static boolean knownLoginOrTransition(LiveLuaDeviceHost.Frame frame, Globals globals,
                                                   LuaTable templates, LiveLuaDeviceHost device) throws Exception {
        List<ScriptStartupTerms.Line> lines = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (LiveLuaDeviceHost.Row row : frame.rows) {
            lines.add(new ScriptStartupTerms.Line(row.text, (int) Math.round(row.x), (int) Math.round(row.y)));
            all.append(row.text.replaceAll("\\s+", ""));
        }
        ScriptStartupTerms.Tap agree = ScriptStartupTerms.findAgreeTap(lines,
                frame.image.getWidth(), frame.image.getHeight());
        if (agree != null) {
            device.tap(frame.mapper.toReferenceX(agree.x), frame.mapper.toReferenceY(agree.y), "terms agreement");
            return true;
        }
        if (ScriptStartupTerms.isLocalizedLoginPage(lines)
                || all.toString().contains("正在登录") || all.toString().contains("服务器集群")
                || all.toString().contains("创建角色")) return true;
        for (String name : Arrays.asList("选区页面", "账号密码页面", "选角色页面")) {
            if (template(globals, templates, name)) return true;
        }
        return false;
    }
}
