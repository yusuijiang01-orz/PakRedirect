package com.example.pakredirect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Minimal, fail-closed bridge for the recovered LuaJ-compatible scripts. */
final class LegacyLuaRuntime {
    interface Listener { void onStatus(String text); }

    private static final String ASSET_ROOT = "script_modules/";
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String VPN_PACKAGE = "com.github.kr328.clash";
    private static final int MAX_COLOR_MATCHES = 1024;
    private final Context context;
    private final ScriptExecutionControl control;
    private final JSONObject config;
    private final Listener listener;
    private final Consumer<Throwable> failureListener;
    private final AtomicBoolean captureHeld = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
    private volatile Bitmap latestFrame;
    private volatile int[] ocrRegion = new int[]{0, 0, 0, 0};
    private ScriptOcr ocr;
    private LuaInstructionGate instructionGate;
    private volatile float touchStartX, touchStartY, touchEndX, touchEndY;
    private Globals globals;

    LegacyLuaRuntime(Context context, ScriptExecutionControl control, JSONObject config,
                     Listener listener, Consumer<Throwable> failureListener) {
        this.context = context.getApplicationContext();
        this.control = control;
        this.config = config;
        this.listener = listener;
        this.failureListener = failureListener;
    }

    static String[] compatibilityBlockers(Context context) {
        // OCR is provided by the local ML Kit adapter as a documented substitute.
        // It is not pixel-identical to the lost TURING glyph dictionary, so surface
        // that limitation in diagnostics instead of making the host unlaunchable.
        return new String[0];
    }

    void run() throws Exception {
        globals = JsePlatform.standardGlobals();
        instructionGate = new LuaInstructionGate(globals, control);
        restrictDangerousLibraries();
        installHostApi();
        try {
            control.attachCurrentThread();
            loadModule("脚本/封神榜.lua", true);
        } finally {
            for (Thread thread : threads) control.cancelWorker(thread);
            long deadline = SystemClock.uptimeMillis() + 2000;
            for (Thread thread : threads) {
                if (thread == Thread.currentThread()) continue;
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;
                try { thread.join(remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            releaseFrame();
            if (ocr != null) ocr.close();
            control.detachCurrentThread();
        }
    }

    private void restrictDangerousLibraries() {
        LuaValue os = globals.get("os");
        if (os.istable()) os.set("execute", LuaValue.NIL);
        LuaValue io = globals.get("io");
        if (io.istable()) io.set("popen", LuaValue.NIL);
        LuaValue pkg = globals.get("package");
        if (pkg.istable()) pkg.set("loadlib", LuaValue.NIL);
        globals.set("dofile", LuaValue.NIL);
    }

    private void installHostApi() {
        globals.set("sleep", new OneArgFunction() {
            @Override public LuaValue call(LuaValue millis) {
                instructionGate.sleep(Math.max(0, millis.optlong(0)));
                return LuaValue.NONE;
            }
        });
        globals.set("tickCount", new ZeroFunction() {
            @Override public LuaValue invoke() { control.checkpoint(); return LuaValue.valueOf(SystemClock.uptimeMillis()); }
        });
        globals.set("keepCapture", new ZeroFunction() {
            @Override public LuaValue invoke() { control.checkpoint(); captureHeld.set(true); refreshFrame(); return LuaValue.NONE; }
        });
        globals.set("releaseCapture", new ZeroFunction() {
            @Override public LuaValue invoke() { captureHeld.set(false); releaseFrame(); return LuaValue.NONE; }
        });
        globals.set("getPixelColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                Bitmap frame = frame();
                int x = args.arg(1).toint(), y = args.arg(2).toint();
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) return LuaValue.valueOf(0);
                int color = frame.getPixel(x, y);
                return LuaValue.valueOf(color & 0x00ffffff);
            }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                try {
                    boolean ok = ScriptGameAdapter.requireConnected().tap(args.arg(1).tofloat(), args.arg(2).tofloat(),
                            args.narg() >= 3 ? args.arg(3).optlong(80) : 80, 5000);
                    if (!ok) throw new LuaError("系统未接受点击手势");
                    return LuaValue.TRUE;
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("触控失败：" + error.getMessage()); }
            }
        });
        globals.set("swipe", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                try {
                    boolean ok = ScriptGameAdapter.requireConnected().swipe(args.arg(1).tofloat(), args.arg(2).tofloat(),
                            args.arg(3).tofloat(), args.arg(4).tofloat(), args.arg(5).optlong(300), 8000);
                    if (!ok) throw new LuaError("系统未接受滑动手势");
                    return LuaValue.TRUE;
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("触控失败：" + error.getMessage()); }
            }
        });
        globals.set("touchDown", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                touchStartX = touchEndX = args.arg(2).tofloat();
                touchStartY = touchEndY = args.arg(3).tofloat();
                return LuaValue.TRUE;
            }
        });
        globals.set("touchMove", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                touchEndX = args.arg(2).tofloat(); touchEndY = args.arg(3).tofloat();
                return LuaValue.TRUE;
            }
        });
        globals.set("touchUp", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                try {
                    boolean ok = ScriptGameAdapter.requireConnected().swipe(touchStartX, touchStartY,
                            touchEndX, touchEndY, 350, 8000);
                    if (!ok) throw new LuaError("系统未接受触控手势");
                    return LuaValue.TRUE;
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("触控失败：" + error.getMessage()); }
            }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue keyValue) {
                control.checkpoint();
                String key = keyValue.checkjstring().toLowerCase(Locale.ROOT);
                if ("home".equals(key)) {
                    try { return LuaValue.valueOf(ScriptGameAdapter.requireConnected().pressHome()); }
                    catch (Exception error) { throw new LuaError("返回桌面失败：" + error.getMessage()); }
                }
                if ("del".equals(key) || "delete".equals(key)) {
                    try { return LuaValue.valueOf(ScriptGameAdapter.requireConnected().clearFocusedText()); }
                    catch (Exception error) { throw new LuaError("清除输入内容失败：" + error.getMessage()); }
                }
                throw new LuaError("当前输入适配器仅支持 home 按键，未执行：" + key);
            }
        });
        globals.set("runApp", new OneArgFunction() {
            @Override public LuaValue call(LuaValue packageName) {
                control.checkpoint();
                String target = packageName.checkjstring();
                if (!isAllowedPackage(target)) {
                    throw new LuaError("安全限制：脚本只能启动封神榜游戏或已配置的 Clash VPN");
                }
                boolean launched = top.niunaijun.blackbox.BlackBoxCore.get().launchApk(target, 0);
                return LuaValue.valueOf(launched);
            }
        });
        globals.set("stopApp", new OneArgFunction() {
            @Override public LuaValue call(LuaValue packageName) {
                control.checkpoint();
                String target = packageName.checkjstring();
                if (!isAllowedPackage(target)) throw new LuaError("安全限制：脚本只能停止封神榜游戏或已配置的 Clash VPN");
                top.niunaijun.blackbox.BlackBoxCore.get().stopPackage(target, 0);
                return LuaValue.TRUE;
            }
        });
        globals.set("appIsRunning", new OneArgFunction() {
            @Override public LuaValue call(LuaValue packageName) {
                control.checkpoint();
                String target = packageName.checkjstring();
                if (!isAllowedPackage(target)) {
                    throw new LuaError("安全限制：脚本只能查询封神榜游戏或已配置的 Clash VPN");
                }
                try {
                    top.niunaijun.blackbox.entity.am.RunningAppProcessInfo processes =
                            top.niunaijun.blackbox.fake.frameworks.BActivityManager.get()
                                    .getRunningAppProcesses(target, 0);
                    return LuaValue.valueOf(processes != null && processes.mAppProcessInfoList != null
                            && !processes.mAppProcessInfoList.isEmpty());
                } catch (android.os.RemoteException error) {
                    return LuaValue.FALSE;
                }
            }
        });
        globals.set("frontAppName", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.valueOf(ScriptGameAdapter.foregroundPackage()); }
        });
        globals.set("cmpColorEx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                double threshold = Math.max(0d, Math.min(1d, args.arg(2).optdouble(0.9d)));
                Bitmap bitmap = frame();
                boolean matches = LegacyLuaCompat.matchesColorPoints(args.arg(1).checkjstring(), threshold,
                        bitmap.getWidth(), bitmap.getHeight(), bitmap::getPixel);
                return LuaValue.valueOf(matches ? 1 : 0);
            }
        });
        globals.set("findColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findPattern(args, false); }
        });
        globals.set("findMultiColor", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findPattern(args, true); }
        });
        globals.set("findMultiColorAll", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findAllPatterns(args); }
        });
        globals.set("findPic", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findImage(args, false); }
        });
        globals.set("findPicFast", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findImage(args, true); }
        });
        globals.set("snapShot", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return saveSnapshot(args); }
        });
        globals.set("delfile", new OneArgFunction() {
            @Override public LuaValue call(LuaValue path) {
                File file = snapshotFile(path.checkjstring());
                return LuaValue.valueOf(file.isFile() && file.delete());
            }
        });
        globals.set("toast", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                final String message = args.arg1().tojstring();
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
                return LuaValue.NONE;
            }
        });
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (listener != null && args.narg() > 0) listener.onStatus(args.tojstring());
                return LuaValue.NONE;
            }
        });
        globals.set("hideToast", noOp());
        globals.set("setControlBarPosNew", noOp());
        globals.set("createHUD", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.valueOf(1); }
        });
        globals.set("showHUD", noOp());
        LuaTable threadApi = new LuaTable();
        threadApi.set("newThread", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaFunction function = args.arg1().checkfunction();
                Thread worker = spawnLuaWorker(function, args.subargs(2), "rylux-lua-thread");
                LuaTable handle = new LuaTable();
                handle.set("stopThread", new ZeroFunction() {
                    @Override public LuaValue invoke() {
                        control.cancelWorker(worker);
                        return LuaValue.TRUE;
                    }
                });
                return handle;
            }
        });
        globals.set("Thread", threadApi);
        globals.set("beginThread", new OneArgFunction() {
            @Override public LuaValue call(LuaValue function) {
                if (!function.isfunction()) return LuaValue.NIL;
                spawnLuaWorker(function.checkfunction(), LuaValue.NONE, "rylux-lua-worker");
                return LuaValue.valueOf(threads.size());
            }
        });
        globals.set("getPackageName", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.valueOf(context.getPackageName()); }
        });
        globals.set("exec", new OneArgFunction() {
            @Override public LuaValue call(LuaValue commandValue) {
                control.checkpoint();
                String command = commandValue.checkjstring().trim();
                if ("cat /proc/uptime".equals(command)) {
                    return LuaValue.valueOf((SystemClock.elapsedRealtime() / 1000.0) + " 0.00");
                }
                // Recovered modules contain shell, reboot and remote-update commands.
                // Never pass script-controlled text to a shell from the host app.
                if (listener != null) listener.onStatus("已拦截未授权系统命令：" + command);
                return LuaValue.valueOf("");
            }
        });
        LuaTable ime = new LuaTable();
        ime.set("lock", noOp());
        ime.set("unlock", noOp());
        ime.set("setText", new OneArgFunction() {
            @Override public LuaValue call(LuaValue textValue) {
                control.checkpoint();
                try {
                    boolean accepted = ScriptGameAdapter.requireConnected().setFocusedText(textValue.tojstring());
                    if (!accepted) throw new LuaError("当前页面没有可由无障碍服务编辑的输入框");
                    return LuaValue.TRUE;
                } catch (LuaError error) { throw error; }
                catch (Exception error) { throw new LuaError("输入文本失败：" + error.getMessage()); }
            }
        });
        globals.set("imeLib", ime);
        globals.set("httpGet", new OneArgFunction() {
            @Override public LuaValue call(LuaValue url) { return LuaValue.valueOf(""); }
        });
        globals.set("httpPost", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue url, LuaValue body) { return LuaValue.valueOf(""); }
        });
        globals.set("splitStr", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue source, LuaValue separator) {
                return LegacyLuaCompat.splitStr(source, separator);
            }
        });
        globals.set("require", new OneArgFunction() {
            @Override public LuaValue call(LuaValue nameValue) {
                String name = nameValue.checkjstring();
                if ("tomocr".equals(name)) { installOcrApi(); return LuaValue.TRUE; }
                if ("luacurl".equals(name)) return new LuaTable();
                if ("TURING_VIP".equals(name)) return installTuringStub();
                String module = name.endsWith(".lua") ? name : name + ".lua";
                return loadModule("脚本/" + module, false);
            }
        });
        installUiApi();
        // Native Lua 5.3 convenience names used by the recovered helper module.
        globals.set("unpack", globals.get("table").get("unpack"));
    }

    private Thread spawnLuaWorker(LuaFunction function, Varargs args, String name) {
        Thread worker = new Thread(() -> {
            try {
                control.attachCurrentThread();
                instructionGate.invoke(function, args);
            }
            catch (ScriptExecutionControl.StopRequestedException ignored) { }
            catch (Throwable error) {
                if (!control.isCurrentThreadCancellationRequested() && control.fail(error)) {
                    try { failureListener.accept(error); }
                    catch (RuntimeException diagnosticFailure) {
                        Log.e("RYLUX-Lua", "保存后台线程诊断失败", diagnosticFailure);
                    }
                }
            } finally {
                control.detachCurrentThread();
                threads.remove(Thread.currentThread());
            }
        }, name);
        worker.setDaemon(true);
        threads.add(worker);
        worker.start();
        return worker;
    }

    private static boolean isAllowedPackage(String packageName) {
        return GAME_PACKAGE.equals(packageName) || VPN_PACKAGE.equals(packageName);
    }

    private LuaValue installTuringStub() {
        LuaTable turing = new LuaTable();
        turing.set("来源_获取屏幕像素", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                ocrRegion = new int[]{args.arg(1).toint(), args.arg(2).toint(), args.arg(3).toint(), args.arg(4).toint()};
                return LuaValue.NONE;
            }
        });
        turing.set("滤镜_彩色_二值化", noOp());
        turing.set("切割_范围投影切割", noOp());
        turing.set("字库_加载六十四进制识别字库", new OneArgFunction() {
            @Override public LuaValue call(LuaValue data) { return LuaValue.TRUE; }
        });
        turing.set("切割_获取切割字符数据", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.tableOf(); }
        });
        turing.set("识别", new OneArgFunction() {
            @Override public LuaValue call(LuaValue threshold) { return recognizeLegacyRegion(); }
        });
        return turing;
    }

    private LuaValue recognizeLegacyRegion() {
        control.checkpoint();
        int[] r = ocrRegion;
        Bitmap screen = frame();
        int left = Math.max(0, r[0]), top = Math.max(0, r[1]);
        int right = Math.min(screen.getWidth(), r[2]), bottom = Math.min(screen.getHeight(), r[3]);
        if (right <= left || bottom <= top) return LuaValue.valueOf("");
        Bitmap crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top);
        try {
            if (ocr == null) ocr = new ScriptOcr();
            ScriptOcr.Result result = ocr.recognize(crop, true, 10000);
            return LuaValue.valueOf(result.text.replace("\n", "").replace(" ", ""));
        } catch (Exception error) { rethrowIfStopped(); throw new LuaError("TURING OCR 替代识别失败：" + error.getMessage()); }
        finally { crop.recycle(); }
    }

    private void installOcrApi() {
        globals.set("tomocrtextall", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable lines = recognizeTextRegion(args, "OCR 失败");
                StringBuilder text = new StringBuilder();
                for (int i = 1; i <= lines.length(); i++) text.append(lines.get(i).get("words").tojstring());
                return LuaValue.valueOf(text.toString());
            }
        });
        globals.set("tomocrtext", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable lines = recognizeTextRegion(args, "OCR 失败");
                return LuaValue.valueOf(lines.length() == 0 ? "" : lines.get(1).get("words").tojstring());
            }
        });
        globals.set("tomocrnumber", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable lines = recognizeTextRegion(args, "OCR 数字识别失败");
                for (int i = 1; i <= lines.length(); i++) {
                    String digits = lines.get(i).get("words").tojstring().replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) {
                        try { return LuaValue.valueOf(Long.parseLong(digits)); }
                        catch (NumberFormatException ignored) { return LuaValue.valueOf(0); }
                    }
                }
                return LuaValue.valueOf(0);
            }
        });
        globals.set("tomocrtexttable", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                return recognizeTextRegion(args, "OCR 表格识别失败");
            }
        });
        globals.set("tomocr", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaTable lines = recognizeTextRegion(args, "OCR 文本匹配失败");
                LuaTable candidates = args.arg(5).checktable();
                double minimumScore = Math.max(0d, Math.min(1d, args.arg(6).optdouble(0.8d)));
                LuaTable match = LegacyLuaCompat.findOcrMatch(lines, candidates, minimumScore);
                if (match != null) return LuaValue.varargsOf(new LuaValue[]{LuaValue.TRUE, match});
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, new LuaTable()});
            }
        });
        globals.set("tomocrclick", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                Varargs result = ((LuaFunction) globals.get("tomocr")).invoke(args);
                if (!result.arg1().toboolean()) return LuaValue.FALSE;
                LuaValue match = result.arg(2);
                try {
                    boolean clicked = ScriptGameAdapter.requireConnected().tap(
                            (float) match.get("x").todouble(), (float) match.get("y").todouble(), 1000, 5000);
                    return LuaValue.valueOf(clicked);
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("OCR 点击失败：" + error.getMessage()); }
            }
        });
    }

    private LuaTable recognizeTextRegion(Varargs args, String errorLabel) {
        Bitmap screen = frame();
        int left = Math.max(0, args.arg(1).toint()), top = Math.max(0, args.arg(2).toint());
        int right = Math.min(screen.getWidth(), args.arg(3).toint());
        int bottom = Math.min(screen.getHeight(), args.arg(4).toint());
        LuaTable table = new LuaTable();
        if (right <= left || bottom <= top) return table;
        Bitmap crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top);
        try {
            if (ocr == null) ocr = new ScriptOcr();
            ScriptOcr.Result result = ocr.recognize(crop, true, 10000);
            org.json.JSONArray lines = new org.json.JSONArray(result.json);
            for (int i = 0; i < lines.length(); i++) {
                org.json.JSONObject line = lines.getJSONObject(i);
                String words = line.optString("words", line.optString("text", ""));
                LuaTable item = new LuaTable();
                item.set("words", LuaValue.valueOf(words));
                item.set("text", LuaValue.valueOf(words));
                item.set("x", LuaValue.valueOf(line.optInt("x") + left));
                item.set("y", LuaValue.valueOf(line.optInt("y") + top));
                item.set("score", LuaValue.valueOf(line.optDouble("score", 0d)));
                item.set("success", LuaValue.TRUE);
                table.set(i + 1, item);
            }
            return table;
        } catch (Exception error) {
            rethrowIfStopped();
            throw new LuaError(errorLabel + "：" + error.getMessage());
        } finally { crop.recycle(); }
    }

    private void installUiApi() {
        LuaTable ui = new LuaTable();
        for (String method : new String[]{"newLayout", "addTabView", "addTab", "newRow", "addTextView",
                "addEditText", "addRadioGroup", "addCheckBox", "addButton", "setOnClick", "show",
                "setOnClose", "setText", "dismiss", "loadProfile", "saveProfile"}) ui.set(method, noOp());
        ui.set("getData", new ZeroFunction() {
            @Override public LuaValue invoke() {
                LuaTable values = new LuaTable();
                for (java.util.Map.Entry<String, String> entry : LegacyLuaConfig.defaults().entrySet()) {
                    values.set(entry.getKey(), LuaValue.valueOf(entry.getValue()));
                }
                java.util.Iterator<String> keys = config.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = config.opt(key);
                    values.set(key, LuaValue.valueOf(value == null || value == JSONObject.NULL ? "" : String.valueOf(value)));
                }
                if (values.get("是否队长").isnil()) values.set("是否队长", LuaValue.valueOf("0"));
                return values;
            }
        });
        globals.set("ui", ui);
    }

    private Varargs findPattern(Varargs args, boolean multi) {
        control.checkpoint();
        Bitmap frame = frame();
        int left = Math.max(0, args.arg(1).toint()), top = Math.max(0, args.arg(2).toint());
        int right = Math.min(frame.getWidth() - 1, args.arg(3).toint()), bottom = Math.min(frame.getHeight() - 1, args.arg(4).toint());
        String primary = args.arg(5).tojstring().split("\\|", 2)[0];
        String[] primaryParts = primary.split("-", 2);
        int color = parseColor(primaryParts[0]);
        int tolerance = primaryParts.length > 1 ? parseColor(primaryParts[1]) : 0;
        String points = multi && args.narg() >= 6 ? args.arg(6).tojstring() : "";
        java.util.ArrayList<int[]> offsets = new java.util.ArrayList<>();
        if (!points.isEmpty()) {
            String[] parts = points.split("\\|");
            for (int i = 0; i + 2 < parts.length; i += 3) {
                String[] colorParts = parts[i + 2].split("-", 2);
                offsets.add(new int[]{parseInt(parts[i]), parseInt(parts[i + 1]), parseColor(colorParts[0]),
                        colorParts.length > 1 ? parseColor(colorParts[1]) : 0});
            }
        }
        String[] colors = !multi ? args.arg(5).tojstring().split("\\|") : new String[0];
        for (int y = top; y <= bottom; y++) {
            control.checkpoint();
            for (int x = left; x <= right; x++) {
                if (!colorMatches(frame.getPixel(x, y), color, tolerance)) continue;
                boolean match = true;
                if (!multi) {
                    for (int i = 1; i < colors.length; i++) {
                        String[] expectedParts = colors[i].split("-", 2);
                        int expected = parseColor(expectedParts[0]);
                        int colorTolerance = expectedParts.length > 1 ? parseColor(expectedParts[1]) : 0;
                        if (x + i >= frame.getWidth() || !colorMatches(frame.getPixel(x + i, y), expected, colorTolerance)) {
                            match = false; break;
                        }
                    }
                }
                if (!match) continue;
                for (int[] p : offsets) {
                    int px = x + p[0], py = y + p[1];
                    if (px < 0 || py < 0 || px >= frame.getWidth() || py >= frame.getHeight()
                            || !colorMatches(frame.getPixel(px, py), p[2], p[3])) { match = false; break; }
                }
                if (match) {
                    LuaValue xVal = LuaValue.valueOf(x), yVal = LuaValue.valueOf(y);
                    return multi ? LuaValue.varargsOf(new LuaValue[]{xVal, yVal})
                            : LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1), xVal, yVal});
                }
            }
        }
        return multi ? LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1)})
                : LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
    }

    private LuaTable findAllPatterns(Varargs args) {
        control.checkpoint();
        Bitmap frame = frame();
        int left = Math.max(0, args.arg(1).toint()), top = Math.max(0, args.arg(2).toint());
        int right = Math.min(frame.getWidth() - 1, args.arg(3).toint());
        int bottom = Math.min(frame.getHeight() - 1, args.arg(4).toint());
        String[] primaryParts = args.arg(5).tojstring().split("\\|", 2)[0].split("-", 2);
        int color = parseColor(primaryParts[0]);
        int tolerance = primaryParts.length > 1 ? parseColor(primaryParts[1]) : 0;
        String points = args.narg() >= 6 ? args.arg(6).tojstring() : "";
        java.util.ArrayList<int[]> offsets = new java.util.ArrayList<>();
        if (!points.isEmpty()) {
            String[] parts = points.split("\\|");
            for (int i = 0; i + 2 < parts.length; i += 3) {
                String[] colorParts = parts[i + 2].split("-", 2);
                offsets.add(new int[]{parseInt(parts[i]), parseInt(parts[i + 1]), parseColor(colorParts[0]),
                        colorParts.length > 1 ? parseColor(colorParts[1]) : 0});
            }
        }
        LuaTable matches = new LuaTable();
        int count = 0;
        for (int y = top; y <= bottom; y++) {
            control.checkpoint();
            for (int x = left; x <= right; x++) {
                if (!colorMatches(frame.getPixel(x, y), color, tolerance)) continue;
                boolean match = true;
                for (int[] p : offsets) {
                    long px = (long) x + p[0], py = (long) y + p[1];
                    if (px < 0 || py < 0 || px >= frame.getWidth() || py >= frame.getHeight()
                            || !colorMatches(frame.getPixel((int) px, (int) py), p[2], p[3])) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    LuaTable point = new LuaTable();
                    point.set("x", LuaValue.valueOf(x));
                    point.set("y", LuaValue.valueOf(y));
                    matches.set(++count, point);
                    if (count >= MAX_COLOR_MATCHES) return matches;
                }
            }
        }
        return matches;
    }

    /** Legacy image-match API: (left, top, right, bottom, template, mask, method, threshold). */
    private Varargs findImage(Varargs args, boolean fast) {
        control.checkpoint();
        Bitmap screen = frame();
        Bitmap template = loadTemplate(args.arg(5).tojstring());
        if (template == null) throw new LuaError("找图模板不存在：" + args.arg(5).tojstring());
        try {
            int left = Math.max(0, args.arg(1).toint());
            int top = Math.max(0, args.arg(2).toint());
            int right = Math.min(screen.getWidth(), args.arg(3).toint());
            int bottom = Math.min(screen.getHeight(), args.arg(4).toint());
            int tw = template.getWidth(), th = template.getHeight();
            if (right - left < tw || bottom - top < th) return imageNotFound(fast);
            int mask = args.narg() >= 6 ? parseColor(args.arg(6).tojstring()) : -1;
            double threshold = args.narg() >= 8 ? args.arg(8).optdouble(0.95) : 0.95;
            threshold = Math.max(0.50, Math.min(1.0, threshold));
            // Legacy matching uses a color-key mask. Skip keyed pixels and sample a
            // coarse grid first, then verify the best nearby origin at full resolution.
            int sampleStep = Math.max(1, Math.min(3, Math.max(tw, th) / 18));
            int templateSamples = countTemplateSamples(template, mask, sampleStep);
            if (templateSamples == 0) return imageNotFound(fast);
            int bestX = -1, bestY = -1;
            double bestScore = -1;
            int maxX = right - tw, maxY = bottom - th;
            long positions = (long) (maxX - left + 1) * (maxY - top + 1);
            int scanStep = (int) Math.max(1, Math.min(8, Math.ceil(Math.sqrt((double) positions * templateSamples / 5_000_000d))));
            for (int y = top; y <= maxY; y += scanStep) {
                for (int x = left; x <= maxX; x += scanStep) {
                    control.checkpoint();
                    double score = templateScore(screen, template, x, y, mask, sampleStep);
                    if (score > bestScore) { bestScore = score; bestX = x; bestY = y; }
                    if (scanStep == 1 && score >= threshold) return imageMatch(fast, x, y);
                }
            }
            // Verify the neighborhood around the coarse best candidate using every
            // non-masked pixel; this prevents a coarse sample from driving a click.
            if (bestX >= 0) {
                int fromX = Math.max(left, bestX - scanStep + 1), toX = Math.min(maxX, bestX + scanStep - 1);
                int fromY = Math.max(top, bestY - scanStep + 1), toY = Math.min(maxY, bestY + scanStep - 1);
                for (int y = fromY; y <= toY; y++) for (int x = fromX; x <= toX; x++) {
                    control.checkpoint();
                    double score = templateScore(screen, template, x, y, mask, 1);
                    if (score >= threshold) return imageMatch(fast, x, y);
                }
            }
            return imageNotFound(fast);
        } finally { template.recycle(); }
    }

    private int countTemplateSamples(Bitmap template, int mask, int step) {
        int count = 0;
        for (int y = 0; y < template.getHeight(); y += step)
            for (int x = 0; x < template.getWidth(); x += step)
                if ((template.getPixel(x, y) & 0x00ffffff) != mask) count++;
        return count;
    }

    private double templateScore(Bitmap screen, Bitmap template, int x, int y, int mask, int step) {
        int hit = 0, count = 0;
        for (int ty = 0; ty < template.getHeight(); ty += step) for (int tx = 0; tx < template.getWidth(); tx += step) {
            int expected = template.getPixel(tx, ty) & 0x00ffffff;
            if (expected == mask) continue;
            int actual = screen.getPixel(x + tx, y + ty) & 0x00ffffff;
            int dr = Math.abs(((expected >> 16) & 255) - ((actual >> 16) & 255));
            int dg = Math.abs(((expected >> 8) & 255) - ((actual >> 8) & 255));
            int db = Math.abs((expected & 255) - (actual & 255));
            if (dr + dg + db <= 42) hit++;
            count++;
        }
        return count == 0 ? 0 : (double) hit / count;
    }

    private Bitmap loadTemplate(String path) {
        File local = snapshotFile(path);
        if (local.isFile()) {
            Bitmap bitmap = BitmapFactory.decodeFile(local.getAbsolutePath());
            if (bitmap != null) return bitmap;
        }
        String name = new File(path.replace('\\', '/')).getName();
        if (name.isEmpty() || name.contains("..")) return null;
        try (InputStream input = context.getAssets().open(ASSET_ROOT + "脚本/" + name)) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ignored) { return null; }
    }

    private Varargs saveSnapshot(Varargs args) {
        control.checkpoint();
        Bitmap source = frame();
        Bitmap crop = source;
        boolean ownsCrop = false;
        try {
            if (args.narg() >= 5) {
                int left = Math.max(0, args.arg(2).toint()), top = Math.max(0, args.arg(3).toint());
                int right = Math.min(source.getWidth(), args.arg(4).toint()), bottom = Math.min(source.getHeight(), args.arg(5).toint());
                if (right <= left || bottom <= top) throw new LuaError("截图区域无效");
                crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top);
                ownsCrop = true;
            }
            if (args.narg() == 0) return LuaValue.TRUE;
            File output = snapshotFile(args.arg(1).checkjstring());
            File parent = output.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) throw new LuaError("无法创建截图缓存目录");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!crop.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new LuaError("截图编码失败");
            }
            return LuaValue.TRUE;
        } catch (java.io.IOException error) { throw new LuaError("保存截图失败：" + error.getMessage()); }
        finally { if (ownsCrop && !crop.isRecycled()) crop.recycle(); }
    }

    private File snapshotFile(String legacyPath) {
        String safe = legacyPath == null ? "snapshot" : legacyPath.replace('\\', '/');
        // Never honor arbitrary filesystem paths from restored scripts. Map their
        // legacy /sdcard names to this app's private cache directory.
        String name = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "snapshot.png";
        return new File(new File(context.getCacheDir(), "legacy-script-images"), name);
    }

    private Varargs imageMatch(boolean fast, int x, int y) {
        if (!fast) return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1), LuaValue.valueOf(x), LuaValue.valueOf(y)});
        LuaTable matches = new LuaTable();
        LuaTable point = new LuaTable(); point.set(1, LuaValue.valueOf(x)); point.set(2, LuaValue.valueOf(y));
        point.set("x", LuaValue.valueOf(x)); point.set("y", LuaValue.valueOf(y));
        matches.set(1, point);
        return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(1), matches});
    }

    private Varargs imageNotFound(boolean fast) {
        return fast ? LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), new LuaTable()})
                : LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(-1), LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
    }

    private void refreshFrame() {
        control.checkpoint();
        try {
            Bitmap capture = ScriptGameAdapter.requireConnected().captureDisplay(10000);
            Bitmap old = latestFrame;
            latestFrame = capture;
            if (old != null && !old.isRecycled()) old.recycle();
        } catch (Exception error) { rethrowIfStopped(); throw new LuaError("截图失败：" + error.getMessage()); }
    }

    private Bitmap frame() {
        Bitmap current = latestFrame;
        if (current == null || current.isRecycled() || !captureHeld.get()) {
            refreshFrame();
            current = latestFrame;
        }
        return current;
    }

    private void releaseFrame() {
        Bitmap old = latestFrame;
        latestFrame = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    private LuaValue loadModule(String assetPath, boolean main) {
        control.checkpoint();
        try (InputStream stream = context.getAssets().open(ASSET_ROOT + assetPath);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            listener.onStatus("加载 " + (main ? "主脚本" : "依赖模块"));
            StringBuilder source = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) source.append(buffer, 0, read);
            String compatibleSource = LuaSourceCompat.normalizeUnicodeIdentifiers(source.toString());
            LuaFunction chunk = globals.load(compatibleSource, assetPath, globals).checkfunction();
            LuaValue value = instructionGate.invoke(chunk, LuaValue.NONE);
            return value.isnil() ? LuaValue.TRUE : value;
        } catch (ScriptExecutionControl.StopRequestedException stopped) { throw stopped; }
        catch (LuaError error) {
            // Keep LuaJ's original stack and source line. Rebuilding the error from
            // getMessage() discards the failing module's traceback and made the
            // floating status impossible to diagnose.
            error.addSuppressed(new IllegalStateException("加载 Lua 模块：" + assetPath));
            throw error;
        }
        catch (Exception error) { throw new LuaError("Lua 模块加载失败：" + assetPath + "：" + error.getMessage()); }
    }

    private static LuaValue unsupported(String message) {
        return new VarArgFunction() { @Override public Varargs invoke(Varargs args) { throw new LuaError(message); } };
    }

    private void rethrowIfStopped() {
        if (Thread.currentThread().isInterrupted()) control.checkpoint();
    }

    private static LuaValue noOp() { return new VarArgFunction() { @Override public Varargs invoke(Varargs args) { return LuaValue.NONE; } }; }

    private abstract static class ZeroFunction extends VarArgFunction {
        public abstract LuaValue invoke();
        @Override public Varargs invoke(Varargs args) { return invoke(); }
    }

    private static int parseColor(String value) {
        try { return (int) Long.parseLong(value.trim(), 16) & 0x00ffffff; }
        catch (RuntimeException ignored) { return 0; }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value.trim()); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static boolean colorMatches(int actual, int expected) {
        return (actual & 0x00ffffff) == expected;
    }

    private static boolean colorMatches(int actual, int expected, int tolerance) {
        return Math.abs(((actual >> 16) & 255) - ((expected >> 16) & 255)) <= ((tolerance >> 16) & 255)
                && Math.abs(((actual >> 8) & 255) - ((expected >> 8) & 255)) <= ((tolerance >> 8) & 255)
                && Math.abs((actual & 255) - (expected & 255)) <= (tolerance & 255);
    }
}
