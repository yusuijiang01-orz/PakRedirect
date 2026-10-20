package com.example.pakredirect;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.os.Build;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Minimal, fail-closed bridge for the recovered LuaJ-compatible scripts. */
final class LegacyLuaRuntime {
    interface Listener { void onStatus(String text); }

    private static final String ASSET_ROOT = "script_modules/";
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String VPN_PACKAGE = "com.github.kr328.clash";
    private static final int MAX_COLOR_MATCHES = 1024;
    private static final long FOREGROUND_RETURN_TIMEOUT_MS = 30_000L;
    private static final long SCREENSHOT_TIMEOUT_MS = 10_000L;
    private final Context context;
    private final ScriptExecutionControl control;
    private final JSONObject config;
    private final Listener listener;
    private final Consumer<Throwable> failureListener;
    private final AtomicBoolean captureHeld = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Thread> threads = new CopyOnWriteArrayList<>();
    private final ScriptHudStatus hudStatus = new ScriptHudStatus();
    private volatile Bitmap latestFrame;
    private volatile LegacyCoordinateMapper coordinateMapper;
    private ScriptOcr ocr;
    private LuaInstructionGate instructionGate;
    private volatile float touchStartX, touchStartY, touchEndX, touchEndY;
    private volatile long touchDurationMs = LegacyLuaCompat.defaultTouchDurationMs();
    private Globals globals;
    private volatile Toast currentToast;

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
        globals.set("utf8", LegacyLuaCompat.createUtf8Compat());
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
                LegacyCoordinateMapper mapper = mapperFor(frame);
                int x = mapper.toScreenX(args.arg(1).todouble());
                int y = mapper.toScreenY(args.arg(2).todouble());
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) return LuaValue.valueOf(0);
                int color = frame.getPixel(x, y);
                return LuaValue.valueOf(color & 0x00ffffff);
            }
        });
        globals.set("tap", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                try {
                    Bitmap screen = frame();
                    LegacyCoordinateMapper mapper = mapperFor(screen);
                    int x = mapper.toScreenX(args.arg(1).todouble());
                    int y = mapper.toScreenY(args.arg(2).todouble());
                    requireDisplayPoint(x, y, screen);
                    awaitGameForeground();
                    boolean ok = ScriptGameAdapter.requireConnected(control).tap(x, y,
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
                    Bitmap screen = frame();
                    LegacyCoordinateMapper mapper = mapperFor(screen);
                    int x1 = mapper.toScreenX(args.arg(1).todouble());
                    int y1 = mapper.toScreenY(args.arg(2).todouble());
                    int x2 = mapper.toScreenX(args.arg(3).todouble());
                    int y2 = mapper.toScreenY(args.arg(4).todouble());
                    requireDisplayPoint(x1, y1, screen);
                    requireDisplayPoint(x2, y2, screen);
                    awaitGameForeground();
                    boolean ok = ScriptGameAdapter.requireConnected(control).swipe(x1, y1, x2, y2,
                            args.arg(5).optlong(300), 8000);
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
                touchDurationMs = LegacyLuaCompat.defaultTouchDurationMs();
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
        globals.set("touchMoveEx", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                touchEndX = args.arg(2).tofloat();
                touchEndY = args.arg(3).tofloat();
                touchDurationMs = LegacyLuaCompat.normalizeTouchDuration(
                        args.arg(4).optlong(LegacyLuaCompat.defaultTouchDurationMs()));
                return LuaValue.TRUE;
            }
        });
        globals.set("touchUp", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                try {
                    Bitmap screen = frame();
                    LegacyCoordinateMapper mapper = mapperFor(screen);
                    int x1 = mapper.toScreenX(touchStartX), y1 = mapper.toScreenY(touchStartY);
                    int x2 = mapper.toScreenX(touchEndX), y2 = mapper.toScreenY(touchEndY);
                    requireDisplayPoint(x1, y1, screen);
                    requireDisplayPoint(x2, y2, screen);
                    awaitGameForeground();
                    boolean ok = ScriptGameAdapter.requireConnected(control).swipe(
                            x1, y1, x2, y2, touchDurationMs, 8000);
                    if (!ok) throw new LuaError("系统未接受触控手势");
                    return LuaValue.TRUE;
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("触控失败：" + error.getMessage()); }
            }
        });
        globals.set("getDevice", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.valueOf(Build.DEVICE); }
        });
        globals.set("getDisplaySize", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                // DisplayMetrics describes the host activity's orientation, which can
                // differ from the game display inside BlackBox. Use the same captured
                // frame that every pixel/OCR/touch API is mapped against.
                LegacyCoordinateMapper mapper = mapperFor(frame());
                return LuaValue.varargsOf(new LuaValue[]{LuaValue.valueOf(mapper.referenceWidth()),
                        LuaValue.valueOf(mapper.referenceHeight())});
            }
        });
        globals.set("getDisplayDpi", new ZeroFunction() {
            @Override public LuaValue invoke() {
                return LuaValue.valueOf(context.getResources().getDisplayMetrics().densityDpi);
            }
        });
        globals.set("exitScript", new ZeroFunction() {
            @Override public LuaValue invoke() {
                control.requestStop();
                control.checkpoint();
                return LuaValue.NONE;
            }
        });
        globals.set("keyPress", new OneArgFunction() {
            @Override public LuaValue call(LuaValue keyValue) {
                control.checkpoint();
                String key = keyValue.checkjstring().toLowerCase(Locale.ROOT);
                if ("home".equals(key)) {
                    try { return LuaValue.valueOf(ScriptGameAdapter.requireConnected(control).pressHome()); }
                    catch (Exception error) { rethrowIfStopped(); throw new LuaError("返回桌面失败：" + error.getMessage()); }
                }
                if ("del".equals(key) || "delete".equals(key)) {
                    try { return LuaValue.valueOf(ScriptGameAdapter.requireConnected(control).clearFocusedText()); }
                    catch (Exception error) { rethrowIfStopped(); throw new LuaError("清除输入内容失败：" + error.getMessage()); }
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
                LegacyCoordinateMapper mapper = mapperFor(bitmap);
                boolean matches = LegacyLuaCompat.matchesColorPoints(mapper.mapColorPoints(args.arg(1).checkjstring()), threshold,
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
                main.post(() -> {
                    Toast previous = currentToast;
                    if (previous != null) previous.cancel();
                    Toast next = Toast.makeText(context, message, Toast.LENGTH_SHORT);
                    currentToast = next;
                    next.show();
                });
                return LuaValue.NONE;
            }
        });
        globals.set("print", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                if (listener != null && args.narg() > 0) listener.onStatus(args.tojstring());
                return LuaValue.NONE;
            }
        });
        globals.set("hideToast", new ZeroArgFunction() {
            @Override public LuaValue call() {
                android.os.Handler main = new android.os.Handler(context.getMainLooper());
                main.post(() -> {
                    Toast active = currentToast;
                    currentToast = null;
                    if (active != null) active.cancel();
                });
                return LuaValue.NONE;
            }
        });
        globals.set("setControlBarPosNew", noOp());
        globals.set("createHUD", new ZeroFunction() {
            @Override public LuaValue invoke() { return LuaValue.valueOf(1); }
        });
        globals.set("showHUD", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                control.checkpoint();
                String message = hudStatus.next(args.arg(2).optjstring(""), SystemClock.uptimeMillis());
                if (message != null && listener != null) listener.onStatus("脚本状态 · " + message);
                return LuaValue.NONE;
            }
        });
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
                    awaitGameForeground();
                    boolean accepted = ScriptGameAdapter.requireConnected(control).setFocusedText(textValue.tojstring());
                    if (!accepted) throw new LuaError("当前页面没有可由无障碍服务编辑的输入框");
                    return LuaValue.TRUE;
                } catch (LuaError error) { throw error; }
                catch (Exception error) { rethrowIfStopped(); throw new LuaError("输入文本失败：" + error.getMessage()); }
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
                if ("TURING_VIP".equals(name)) return installTuringCompat();
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

    private LuaValue installTuringCompat() {
        return LegacyTuringCompat.createApi(request -> {
            control.checkpoint();
            Bitmap screen = frame();
            LegacyCoordinateMapper mapper = mapperFor(screen);
            int left = Math.max(0, mapper.left(request.left));
            int top = Math.max(0, mapper.top(request.top));
            int right = mapper.edgeRight(request.right);
            int bottom = mapper.edgeBottom(request.bottom);
            if (right <= left || bottom <= top) return "";
            Bitmap crop = Bitmap.createBitmap(screen, left, top, right - left, bottom - top);
            Bitmap filtered = crop;
            try {
                if (!request.colorFilter.isEmpty()) {
                    int[] pixels = new int[crop.getWidth() * crop.getHeight()];
                    crop.getPixels(pixels, 0, crop.getWidth(), 0, 0, crop.getWidth(), crop.getHeight());
                    int[] binary = LegacyTuringCompat.applyFilter(pixels, request.colorFilter);
                    filtered = Bitmap.createBitmap(binary, crop.getWidth(), crop.getHeight(), Bitmap.Config.ARGB_8888);
                }
                if (ocr == null) ocr = new ScriptOcr();
                ScriptOcr.Result result = ocr.recognize(filtered, true, 10000);
                org.json.JSONArray lines = new org.json.JSONArray(result.json);
                StringBuilder acceptedText = new StringBuilder();
                for (int index = 0; index < lines.length(); index++) {
                    org.json.JSONObject line = lines.getJSONObject(index);
                    if (!LegacyTuringCompat.acceptsConfidence(
                            line.optDouble("score", -1d), request.confidenceThreshold)) continue;
                    acceptedText.append(line.optString("text", ""));
                }
                return acceptedText.toString();
            } catch (Exception error) {
                rethrowIfStopped();
                throw error;
            } finally {
                if (filtered != crop) filtered.recycle();
                crop.recycle();
            }
        });
    }

    private void installOcrApi() {
        // The replacement OCR uses ML Kit's on-device language model instead of the
        // lost glyph dictionary. Accept the legacy selector so the helper can continue.
        globals.set("useDict", new OneArgFunction() {
            @Override public LuaValue call(LuaValue dictionary) { return LuaValue.TRUE; }
        });
        globals.set("findStr", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) { return findText(args); }
        });
        globals.set("tomocrtextall", LegacyLuaCompat.createTomocrTextAll(
                args -> recognizeTextRegion(args, "OCR 失败")));
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
                    Bitmap screen = frame();
                    LegacyCoordinateMapper mapper = mapperFor(screen);
                    awaitGameForeground();
                    boolean clicked = ScriptGameAdapter.requireConnected(control).tap(
                            mapper.toScreenX(match.get("x").todouble()),
                            mapper.toScreenY(match.get("y").todouble()), 1000, 5000);
                    return LuaValue.valueOf(clicked);
                } catch (Exception error) { rethrowIfStopped(); throw new LuaError("OCR 点击失败：" + error.getMessage()); }
            }
        });
    }

    /** Legacy seven-argument text finder backed by the local ML Kit OCR adapter. */
    private Varargs findText(Varargs args) {
        control.checkpoint();
        LuaTable lines = recognizeTextRegion(args, "文字查找失败");
        String target = args.arg(5).tojstring();
        double minimumScore = Math.max(0d, Math.min(1d, args.arg(7).optdouble(0.8d)));
        LuaTable match = LegacyLuaCompat.findOcrTextMatch(lines, target, minimumScore);
        if (match == null) {
            return LuaValue.varargsOf(new LuaValue[]{LuaValue.FALSE, LuaValue.valueOf(-1), LuaValue.valueOf(-1)});
        }
        return LuaValue.varargsOf(new LuaValue[]{LuaValue.TRUE, match.get("x"), match.get("y")});
    }

    private LuaTable recognizeTextRegion(Varargs args, String errorLabel) {
        Bitmap screen = frame();
        LegacyCoordinateMapper mapper = mapperFor(screen);
        int left = Math.max(0, mapper.left(args.arg(1).todouble()));
        int top = Math.max(0, mapper.top(args.arg(2).todouble()));
        int right = mapper.edgeRight(args.arg(3).todouble());
        int bottom = mapper.edgeBottom(args.arg(4).todouble());
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
                item.set("x", LuaValue.valueOf(mapper.toReferenceX(line.optInt("x") + left)));
                item.set("y", LuaValue.valueOf(mapper.toReferenceY(line.optInt("y") + top)));
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
                String[] blueFilterFlags = LegacyLuaConfig.blueFilterFlags(
                        values.get("蓝条筛选模式").isnil() ? null : values.get("蓝条筛选模式").tojstring());
                if (blueFilterFlags != null) {
                    values.set("回蓝>=", LuaValue.valueOf(blueFilterFlags[0]));
                    values.set("魔法出招>=", LuaValue.valueOf(blueFilterFlags[1]));
                    values.set("武器出招>=", LuaValue.valueOf(blueFilterFlags[2]));
                }
                if (values.get("是否队长").isnil()) values.set("是否队长", LuaValue.valueOf("0"));
                return values;
            }
        });
        globals.set("ui", ui);
        // The RYLUX workbench supplies settings before the Lua runtime starts.
        // The recovered script's original UI is not rendered by this host; tell
        // it to consume the supplied profile instead of waiting for that window.
        LegacyLuaConfig.installWorkbenchConfigFlag(globals);
    }

    private Varargs findPattern(Varargs args, boolean multi) {
        control.checkpoint();
        Bitmap frame = frame();
        LegacyCoordinateMapper mapper = mapperFor(frame);
        int left = Math.max(0, mapper.left(args.arg(1).todouble()));
        int top = Math.max(0, mapper.top(args.arg(2).todouble()));
        int right = Math.min(frame.getWidth() - 1, mapper.right(args.arg(3).todouble()));
        int bottom = Math.min(frame.getHeight() - 1, mapper.bottom(args.arg(4).todouble()));
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
                offsets.add(new int[]{mapper.toScreenDeltaX(parseInt(parts[i])),
                        mapper.toScreenDeltaY(parseInt(parts[i + 1])), parseColor(colorParts[0]),
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
                        int px = x + mapper.toScreenDeltaX(i);
                        if (px < 0 || px >= frame.getWidth() || !colorMatches(frame.getPixel(px, y), expected, colorTolerance)) {
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
                    LuaValue xVal = LuaValue.valueOf(mapper.toReferenceX(x));
                    LuaValue yVal = LuaValue.valueOf(mapper.toReferenceY(y));
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
        LegacyCoordinateMapper mapper = mapperFor(frame);
        int left = Math.max(0, mapper.left(args.arg(1).todouble()));
        int top = Math.max(0, mapper.top(args.arg(2).todouble()));
        int right = Math.min(frame.getWidth() - 1, mapper.right(args.arg(3).todouble()));
        int bottom = Math.min(frame.getHeight() - 1, mapper.bottom(args.arg(4).todouble()));
        String[] primaryParts = args.arg(5).tojstring().split("\\|", 2)[0].split("-", 2);
        int color = parseColor(primaryParts[0]);
        int tolerance = primaryParts.length > 1 ? parseColor(primaryParts[1]) : 0;
        String points = args.narg() >= 6 ? args.arg(6).tojstring() : "";
        java.util.ArrayList<int[]> offsets = new java.util.ArrayList<>();
        if (!points.isEmpty()) {
            String[] parts = points.split("\\|");
            for (int i = 0; i + 2 < parts.length; i += 3) {
                String[] colorParts = parts[i + 2].split("-", 2);
                offsets.add(new int[]{mapper.toScreenDeltaX(parseInt(parts[i])),
                        mapper.toScreenDeltaY(parseInt(parts[i + 1])), parseColor(colorParts[0]),
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
                    point.set("x", LuaValue.valueOf(mapper.toReferenceX(x)));
                    point.set("y", LuaValue.valueOf(mapper.toReferenceY(y)));
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
        LegacyCoordinateMapper mapper = mapperFor(screen);
        String templatePath = args.arg(5).tojstring();
        boolean capturedFromCurrentDisplay = snapshotFile(templatePath).isFile();
        Bitmap sourceTemplate = loadTemplate(templatePath);
        if (sourceTemplate == null) throw new LuaError("找图模板不存在：" + templatePath);
        Bitmap template = sourceTemplate;
        try {
            if (mapper.shouldScaleTemplate(capturedFromCurrentDisplay)) {
                int scaledWidth = Math.max(1, mapper.toScreenDeltaX(sourceTemplate.getWidth()));
                int scaledHeight = Math.max(1, mapper.toScreenDeltaY(sourceTemplate.getHeight()));
                template = Bitmap.createScaledBitmap(sourceTemplate, scaledWidth, scaledHeight, true);
            }
            int left = Math.max(0, mapper.left(args.arg(1).todouble()));
            int top = Math.max(0, mapper.top(args.arg(2).todouble()));
            int right = mapper.edgeRight(args.arg(3).todouble());
            int bottom = mapper.edgeBottom(args.arg(4).todouble());
            int mask = args.narg() >= 6 ? parseColor(args.arg(6).tojstring()) : -1;
            double threshold = args.narg() >= 8 ? args.arg(8).optdouble(0.95) : 0.95;
            Bitmap matchedTemplate = template;
            LegacyImageMatcher.Pixels screenPixels = new LegacyImageMatcher.Pixels() {
                @Override public int width() { return screen.getWidth(); }
                @Override public int height() { return screen.getHeight(); }
                @Override public int getPixel(int x, int y) { return screen.getPixel(x, y); }
            };
            LegacyImageMatcher.Pixels templatePixels = new LegacyImageMatcher.Pixels() {
                @Override public int width() { return matchedTemplate.getWidth(); }
                @Override public int height() { return matchedTemplate.getHeight(); }
                @Override public int getPixel(int x, int y) { return matchedTemplate.getPixel(x, y); }
            };
            LegacyImageMatcher.Match match = LegacyImageMatcher.find(screenPixels, templatePixels,
                    left, top, right, bottom, mask, threshold, control::checkpoint);
            return match == null ? imageNotFound(fast)
                    : imageMatch(fast, mapper.toReferenceX(match.x), mapper.toReferenceY(match.y));
        } finally {
            if (template != sourceTemplate && !template.isRecycled()) template.recycle();
            if (!sourceTemplate.isRecycled()) sourceTemplate.recycle();
        }
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
                LegacyCoordinateMapper mapper = mapperFor(source);
                int left = Math.max(0, mapper.left(args.arg(2).todouble()));
                int top = Math.max(0, mapper.top(args.arg(3).todouble()));
                int right = mapper.edgeRight(args.arg(4).todouble());
                int bottom = mapper.edgeBottom(args.arg(5).todouble());
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
            awaitGameForeground();
            Bitmap capture = ScriptForegroundGate.captureWithRecovery(
                    remainingMs -> ScriptGameAdapter.requireConnected(control)
                            .captureDisplay(Math.min(SCREENSHOT_TIMEOUT_MS, remainingMs)),
                    ScriptGameAdapter::isTargetGameForeground, control::checkpoint,
                    this::releaseFrame, this::onGameForegroundLost, this::onGameForegroundResumed,
                    FOREGROUND_RETURN_TIMEOUT_MS, "沙箱游戏连续 30 秒不在前台，截图已停止等待");
            Bitmap old = latestFrame;
            latestFrame = capture;
            coordinateMapper = mapperFor(capture.getWidth(), capture.getHeight());
            if (old != null && !old.isRecycled()) old.recycle();
        } catch (InterruptedException interrupted) {
            control.checkpoint();
            Thread.currentThread().interrupt();
            throw new LuaError("等待沙箱游戏返回前台时被中断");
        } catch (TimeoutException timeout) {
            rethrowIfStopped();
            throw new LuaError("截图失败：" + timeout.getMessage() + "（"
                    + ScriptGameAdapter.foregroundDiagnostic() + "）");
        } catch (Exception error) {
            rethrowIfStopped();
            throw new LuaError("截图失败：" + ScriptRuntimeErrors.summarize(error));
        }
    }

    private Bitmap frame() {
        awaitGameForeground();
        Bitmap current = latestFrame;
        if (current == null || current.isRecycled() || !captureHeld.get()) {
            refreshFrame();
            current = latestFrame;
        }
        return current;
    }

    /**
     * A workbench or Android dialog can briefly take focus while the Lua worker is
     * running. Keep the script cancellable while waiting, then discard cached
     * pixels so the next operation uses a fresh frame from the game.
     */
    private void awaitGameForeground() {
        try {
            ScriptForegroundGate.awaitRecovery(ScriptGameAdapter::isTargetGameForeground,
                    control::checkpoint, this::onGameForegroundLost,
                    this::onGameForegroundResumed,
                    FOREGROUND_RETURN_TIMEOUT_MS,
                    "沙箱游戏连续 30 秒不在前台");
        } catch (InterruptedException interrupted) {
            control.checkpoint();
            Thread.currentThread().interrupt();
            throw new LuaError("等待沙箱游戏返回前台时被中断");
        } catch (TimeoutException timeout) {
            throw new LuaError(timeout.getMessage() + "，脚本已停止等待（"
                    + ScriptGameAdapter.foregroundDiagnostic() + "）");
        }
    }

    private void onGameForegroundLost() {
        releaseFrame();
        listener.onStatus("游戏暂时不在前台，等待游戏返回（可暂停或停止）");
    }

    private void onGameForegroundResumed() {
        listener.onStatus("游戏已回到前台，继续执行脚本");
    }

    private LegacyCoordinateMapper mapperFor(Bitmap frame) {
        LegacyCoordinateMapper mapper = coordinateMapper;
        if (mapper == null || latestFrame == null || frame.getWidth() != latestFrame.getWidth()
                || frame.getHeight() != latestFrame.getHeight()) {
            mapper = mapperFor(frame.getWidth(), frame.getHeight());
            coordinateMapper = mapper;
        }
        return mapper;
    }

    private LegacyCoordinateMapper mapperFor(int screenWidth, int screenHeight) {
        return new LegacyCoordinateMapper(config.optInt("虚拟屏幕宽度", 720),
                config.optInt("虚拟屏幕高度", 1280), screenWidth, screenHeight);
    }

    private static void requireDisplayPoint(int x, int y, Bitmap screen) {
        if (x < 0 || y < 0 || x >= screen.getWidth() || y >= screen.getHeight()) {
            throw new LuaError("触控坐标越界：" + x + "," + y + "；显示尺寸 "
                    + screen.getWidth() + "x" + screen.getHeight());
        }
    }

    private void releaseFrame() {
        Bitmap old = latestFrame;
        latestFrame = null;
        coordinateMapper = null;
        if (old != null && !old.isRecycled()) old.recycle();
    }

    private LuaValue loadModule(String assetPath, boolean main) {
        control.checkpoint();
        try (InputStream stream = context.getAssets().open(ASSET_ROOT + assetPath);
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            listener.onStatus("正在加载 " + (main ? "主脚本" : "依赖模块") + "：" + assetPath);
            StringBuilder source = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) source.append(buffer, 0, read);
            String compatibleSource = LuaSourceCompat.normalizeUnicodeIdentifiers(source.toString());
            LuaFunction chunk = globals.load(compatibleSource, assetPath, globals).checkfunction();
            LuaValue value = instructionGate.invoke(chunk, LuaValue.NONE);
            listener.onStatus("已加载 " + assetPath);
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
