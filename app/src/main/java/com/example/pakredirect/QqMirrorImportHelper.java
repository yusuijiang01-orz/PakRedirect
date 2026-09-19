package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.WeakHashMap;

/**
 * Android 11+ blocks SAF access to another app's Android/data directory.
 * This helper avoids sending users into that blocked picker path:
 * 1) use normal file access when the ROM still allows it;
 * 2) otherwise use existing root access to locate/copy the QQ file into
 *    RYLUX-owned storage, then feed it into MainActivity's existing import flow;
 * 3) without root, guide the user to move/share the file to Download first.
 */
public final class QqMirrorImportHelper {
    private static final int REQUEST_MIRROR_PACK = 4107;
    private static final String MIRROR_FILE_NAME = "RYLUX-Official-v1.rmp";
    private static final String QQ_DIR =
            "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv";

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();

    private QqMirrorImportHelper() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> hook(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decor.post(() -> hook(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> hook(activity));
        }
    }

    public static void detach(Activity activity) {
        synchronized (LISTENERS) {
            ViewTreeObserver.OnGlobalLayoutListener listener = LISTENERS.remove(activity);
            if (listener == null) return;
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        }
    }

    private static void hook(Activity activity) {
        Button mirror = findButton(activity.getWindow().getDecorView(), "选择镜像包");
        if (mirror == null) return;
        // Intentionally override the legacy QQ->SAF listener. SAF cannot access
        // Android/data/com.tencent.mobileqq on Android 11+.
        mirror.setOnClickListener(v -> start(activity, mirror));
    }

    private static void start(Activity activity, Button button) {
        button.setEnabled(false);
        button.setText("正在搜索 QQ 镜像包…");
        new Thread(() -> {
            try {
                SearchResult result = findMirror();
                if (result.path != null) {
                    File local = copyIntoRylux(activity, result.path, result.directReadable);
                    Uri uri = FileProvider.getUriForFile(
                            activity,
                            activity.getPackageName() + ".fileprovider",
                            local
                    );
                    activity.runOnUiThread(() -> {
                        resetButton(button);
                        if (activity.isFinishing()) return;
                        Toast.makeText(activity, "已找到 QQ 镜像包，正在自动导入…", Toast.LENGTH_LONG).show();
                        Intent data = new Intent().setData(uri);
                        ((MainActivity) activity).onActivityResult(
                                REQUEST_MIRROR_PACK,
                                Activity.RESULT_OK,
                                data
                        );
                    });
                    return;
                }

                activity.runOnUiThread(() -> {
                    resetButton(button);
                    if (!activity.isFinishing()) showManualFallback(activity, result.rootAvailable);
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    resetButton(button);
                    if (!activity.isFinishing()) {
                        Toast.makeText(
                                activity,
                                "QQ 镜像包自动导入失败：" + safeMessage(t),
                                Toast.LENGTH_LONG
                        ).show();
                        showManualFallback(activity, false);
                    }
                });
            }
        }, "RYLUX-QQ-Mirror-AutoImport").start();
    }

    private static SearchResult findMirror() {
        File direct = findRecursively(new File(QQ_DIR));
        if (direct != null && direct.canRead()) {
            return new SearchResult(direct.getAbsolutePath(), true, false);
        }

        RootShell.Result rootCheck = RootShell.run("id -u 2>/dev/null");
        boolean rootAvailable = rootCheck.ok() && "0".equals(rootCheck.output == null ? "" : rootCheck.output.trim());
        if (!rootAvailable) return new SearchResult(null, false, false);

        RootShell.Result find = RootShell.run(
                "find " + shellQuote(QQ_DIR)
                        + " -type f -name " + shellQuote(MIRROR_FILE_NAME)
                        + " -print 2>/dev/null | head -n 1"
        );
        if (!find.ok() || find.output == null || find.output.trim().isEmpty()) {
            return new SearchResult(null, false, true);
        }
        String path = firstLine(find.output);
        if (!path.endsWith("/" + MIRROR_FILE_NAME)) {
            return new SearchResult(null, false, true);
        }
        return new SearchResult(path, false, true);
    }

    private static File copyIntoRylux(Activity activity, String sourcePath, boolean directReadable)
            throws Exception {
        File base = new File(activity.getFilesDir(), "downloads/mirror-import");
        if (!base.exists() && !base.mkdirs() && !base.exists()) {
            throw new IllegalStateException("无法创建镜像包导入目录");
        }
        File target = new File(base, MIRROR_FILE_NAME);
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("无法清理旧镜像包缓存");
        }

        // Create the destination as the app UID first. Root then writes into
        // the existing inode, avoiding ownership/SELinux surprises in app data.
        try (FileOutputStream ignored = new FileOutputStream(target, false)) {
            // create empty app-owned file
        }

        if (directReadable) {
            copyJava(new File(sourcePath), target);
        } else {
            RootShell.Result copy = RootShell.run(
                    "cat " + shellQuote(sourcePath) + " > " + shellQuote(target.getAbsolutePath())
            );
            if (!copy.ok()) {
                target.delete();
                throw new IllegalStateException("Root 复制失败" + outputSuffix(copy.output));
            }
        }

        if (!target.isFile() || target.length() <= 0L) {
            target.delete();
            throw new IllegalStateException("复制后的镜像包为空");
        }
        return target;
    }

    private static void copyJava(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    private static void showManualFallback(Activity activity, boolean rootAvailable) {
        String rootNote = rootAvailable
                ? "已获得 Root 权限，但在 QQ 接收目录中没有找到该文件。"
                : "当前设备未提供可用 Root 权限。";
        new AlertDialog.Builder(activity)
                .setTitle("需要先把镜像包移到下载目录")
                .setMessage(rootNote
                        + "\n\nAndroid 11 及以上禁止其他应用直接通过系统文件选择器访问 QQ 的 Android/data 目录。"
                        + "\n\n请在 QQ 或文件管理器中，将 “" + MIRROR_FILE_NAME
                        + "” 复制、移动或分享到手机的 Download/下载 目录，然后返回 RYLUX 选择该文件。")
                .setNegativeButton("取消", null)
                .setNeutralButton("重新搜索", (dialog, which) -> {
                    Button button = findButton(activity.getWindow().getDecorView(), "选择镜像包");
                    if (button != null) start(activity, button);
                })
                .setPositiveButton("从下载目录选择", (dialog, which) -> openDownloadPicker(activity))
                .show();
    }

    private static void openDownloadPicker(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            Uri initial = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Download"
            );
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        } catch (Throwable ignored) {
        }
        try {
            activity.startActivityForResult(intent, REQUEST_MIRROR_PACK);
        } catch (Throwable first) {
            try {
                intent.removeExtra(DocumentsContract.EXTRA_INITIAL_URI);
                activity.startActivityForResult(intent, REQUEST_MIRROR_PACK);
            } catch (Throwable second) {
                Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static File findRecursively(File base) {
        if (base == null || !base.exists() || !base.isDirectory()) return null;
        Deque<File> pending = new ArrayDeque<>();
        pending.add(base);
        int visited = 0;
        while (!pending.isEmpty() && visited < 20_000) {
            File dir = pending.removeFirst();
            File[] children;
            try {
                children = dir.listFiles();
            } catch (Throwable ignored) {
                children = null;
            }
            if (children == null) continue;
            for (File child : children) {
                visited++;
                if (child == null) continue;
                if (child.isFile() && MIRROR_FILE_NAME.equals(child.getName())) return child;
                if (child.isDirectory()) pending.addLast(child);
                if (visited >= 20_000) break;
            }
        }
        return null;
    }

    private static Button findButton(View root, String exactText) {
        if (root instanceof Button) {
            Button button = (Button) root;
            CharSequence text = button.getText();
            if (text != null && exactText.equals(text.toString().trim())) return button;
        }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            Button found = findButton(group.getChildAt(i), exactText);
            if (found != null) return found;
        }
        return null;
    }

    private static void resetButton(Button button) {
        if (button == null) return;
        button.setEnabled(true);
        button.setText("选择镜像包");
    }

    private static String firstLine(String value) {
        int newline = value.indexOf('\n');
        return (newline >= 0 ? value.substring(0, newline) : value).trim();
    }

    private static String outputSuffix(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        return "：" + output.trim();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) return t.getClass().getSimpleName();
        return message.trim();
    }

    private static final class SearchResult {
        final String path;
        final boolean directReadable;
        final boolean rootAvailable;

        SearchResult(String path, boolean directReadable, boolean rootAvailable) {
            this.path = path;
            this.directReadable = directReadable;
            this.rootAvailable = rootAvailable;
        }
    }
}
