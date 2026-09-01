package com.example.pakredirect;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Non-invasive visual layer for the programmatic MainActivity UI.
 *
 * MainActivity keeps all authentication, membership, mirror and launch logic.
 * This class only restyles/recomposes views after they are attached, so the
 * security and game-launch path remains unchanged.
 */
public final class RyluxUiPolish {
    private static final String BANNER_URL =
            "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/banner/fengshenbang.png";

    private static final int BG = Color.rgb(11, 15, 21);
    private static final int CARD = Color.rgb(23, 29, 39);
    private static final int CARD_SOFT = Color.rgb(18, 24, 33);
    private static final int SURFACE = Color.rgb(32, 39, 52);
    private static final int BORDER = Color.rgb(48, 58, 73);
    private static final int TEXT = Color.rgb(244, 246, 251);
    private static final int MUTED = Color.rgb(139, 151, 168);
    private static final int PRIMARY = Color.rgb(70, 111, 241);
    private static final int PRIMARY_BORDER = Color.rgb(94, 132, 255);
    private static final int GREEN = Color.rgb(93, 217, 140);
    private static final int GREEN_BG = Color.rgb(22, 58, 43);
    private static final int GREEN_BORDER = Color.rgb(43, 96, 71);
    private static final int VIP_BG = Color.rgb(111, 35, 32);
    private static final int VIP_TEXT = Color.rgb(255, 216, 210);
    private static final int GOLD = Color.rgb(201, 173, 112);
    private static final int DANGER_BG = Color.rgb(48, 24, 24);
    private static final int DANGER_BORDER = Color.rgb(107, 48, 46);
    private static final int DANGER_TEXT = Color.rgb(229, 161, 158);

    private static final Set<View> HOME_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> HERO_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> PANEL_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();

    private static volatile Bitmap bannerBitmap;

    private RyluxUiPolish() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> decor.post(() -> polish(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> polish(activity));
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

    private static void polish(Activity activity) {
        activity.getWindow().setStatusBarColor(BG);
        activity.getWindow().setNavigationBarColor(BG);
        View decor = activity.getWindow().getDecorView();
        styleRecursive(activity, decor);
    }

    private static void styleRecursive(Activity activity, View view) {
        if (view instanceof GlowFrameLayout) {
            styleHome(activity, (GlowFrameLayout) view);
        }
        if (view instanceof TextView) {
            String value = ((TextView) view).getText() == null
                    ? ""
                    : ((TextView) view).getText().toString().trim();
            if ("账号中心".equals(value)) stylePanel(activity, (TextView) view, false);
            else if ("游戏详情".equals(value)) stylePanel(activity, (TextView) view, true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleRecursive(activity, group.getChildAt(i));
            }
        }
    }

    private static void styleHome(Activity activity, GlowFrameLayout hero) {
        if (!(hero.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) hero.getParent();
        root.setBackgroundColor(BG);

        if (HOME_STYLED.add(root)) {
            root.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 28));
            buildHomeHeader(activity, root, hero);
        }
        if (HERO_STYLED.add(hero)) buildHero(activity, hero);
    }

    private static void buildHomeHeader(Activity activity, LinearLayout root, View hero) {
        if (root.getChildCount() == 0) return;
        View avatarShell = root.getChildAt(0);
        if (!(avatarShell instanceof FrameLayout) || avatarShell == hero) return;

        root.removeView(avatarShell);
        if (root.getChildCount() > 0 && root.getChildAt(0) instanceof Space) {
            root.removeViewAt(0);
        }

        styleAvatar(activity, (FrameLayout) avatarShell);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(activity, 3), 0, dp(activity, 18));
        header.setClickable(true);
        header.setFocusable(true);
        header.setOnClickListener(v -> avatarShell.performClick());

        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 72));
        avatarLp.rightMargin = dp(activity, 14);
        header.addView(avatarShell, avatarLp);

        LinearLayout identity = new LinearLayout(activity);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        TextView brand = label(activity, "RYLUX", 12, MUTED, true);
        if (Build.VERSION.SDK_INT >= 21) brand.setLetterSpacing(0.16f);
        identity.addView(brand);

        String username = new AuthStorage(activity).loadUsername();
        if (username == null || username.trim().isEmpty()) username = "RYLUX 用户";
        TextView usernameView = label(activity, username, 18, TEXT, true);
        usernameView.setPadding(0, dp(activity, 3), 0, 0);
        identity.addView(usernameView);

        TextView hint = label(activity, "点击打开账号中心", 12, MUTED, false);
        hint.setPadding(0, dp(activity, 3), 0, 0);
        identity.addView(hint);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView secure = label(activity, "安全授权", 12, GOLD, true);
        secure.setGravity(Gravity.CENTER);
        secure.setPadding(dp(activity, 11), 0, dp(activity, 11), 0);
        secure.setBackground(round(activity, Color.rgb(31, 29, 24), 14, Color.rgb(76, 67, 48), 1));
        header.addView(secure, new LinearLayout.LayoutParams(-2, dp(activity, 32)));

        root.addView(header, 0, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void styleAvatar(Activity activity, FrameLayout shell) {
        for (int i = 0; i < shell.getChildCount(); i++) {
            View child = shell.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView text = (TextView) child;
            String value = text.getText() == null ? "" : text.getText().toString().trim();
            if ("VIP".equals(value) || "体验".equals(value)) {
                int fill = "体验".equals(value) ? Color.rgb(95, 75, 24) : VIP_BG;
                int stroke = "体验".equals(value) ? Color.rgb(145, 111, 34) : Color.rgb(145, 55, 50);
                int color = "体验".equals(value) ? Color.rgb(255, 226, 148) : VIP_TEXT;
                text.setTextColor(color);
                text.setTextSize(10);
                text.setBackground(round(activity, fill, 10, stroke, 1));
            } else {
                text.setTextColor(TEXT);
                text.setTextSize(22);
                text.setBackground(round(activity, Color.rgb(53, 65, 91), 28, Color.rgb(74, 88, 121), 1));
            }
        }
    }

    private static void buildHero(Activity activity, GlowFrameLayout hero) {
        hero.removeAllViews();
        hero.setPadding(0, 0, 0, 0);
        hero.setClipChildren(true);
        hero.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 21) {
            hero.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, 26));
                }
            });
            hero.setClipToOutline(true);
        }

        ViewGroup.LayoutParams rawLp = hero.getLayoutParams();
        if (rawLp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rawLp;
            lp.height = dp(activity, 390);
            lp.leftMargin = 0;
            lp.rightMargin = 0;
            lp.bottomMargin = dp(activity, 18);
            hero.setLayoutParams(lp);
        }

        ImageView poster = new ImageView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(Color.rgb(22, 28, 38));
        hero.addView(poster, new FrameLayout.LayoutParams(-1, -1));
        loadBanner(activity, poster);

        View shade = new View(activity);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(10, 4, 7, 11), Color.argb(75, 4, 7, 11), Color.argb(245, 4, 7, 11)}
        );
        shade.setBackground(gradient);
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        TextView supported = pill(activity, "已支持汉化", GREEN_BG, GREEN, GREEN_BORDER);
        FrameLayout.LayoutParams supportedLp = new FrameLayout.LayoutParams(-2, dp(activity, 32));
        supportedLp.gravity = Gravity.TOP | Gravity.START;
        supportedLp.leftMargin = dp(activity, 20);
        supportedLp.topMargin = dp(activity, 18);
        hero.addView(supported, supportedLp);

        try {
            MirrorPackManager.MirrorStatus mirror = MirrorPackManager.status(activity);
            if (mirror.ready) {
                TextView mirrorBadge = pill(
                        activity,
                        "镜像已就绪",
                        Color.rgb(28, 42, 72),
                        Color.rgb(143, 170, 255),
                        Color.rgb(55, 78, 125)
                );
                FrameLayout.LayoutParams mirrorLp = new FrameLayout.LayoutParams(-2, dp(activity, 32));
                mirrorLp.gravity = Gravity.TOP | Gravity.END;
                mirrorLp.rightMargin = dp(activity, 20);
                mirrorLp.topMargin = dp(activity, 18);
                hero.addView(mirrorBadge, mirrorLp);
            }
        } catch (Throwable ignored) {
        }

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(activity, 22), 0, dp(activity, 22), dp(activity, 22));
        TextView title = label(activity, "封神榜（越南版）", 26, TEXT, true);
        copy.addView(title);
        TextView sub = label(activity, "本地汉化 · 安全资源校验 · 加密内容", 13, Color.rgb(180, 190, 203), false);
        sub.setPadding(0, dp(activity, 7), 0, 0);
        copy.addView(sub);
        TextView hint = label(activity, "点击查看游戏详情", 12, MUTED, false);
        hint.setPadding(0, dp(activity, 11), 0, 0);
        copy.addView(hint);

        FrameLayout.LayoutParams copyLp = new FrameLayout.LayoutParams(-1, -2);
        copyLp.gravity = Gravity.BOTTOM;
        hero.addView(copy, copyLp);
    }

    private static void stylePanel(Activity activity, TextView heading, boolean gamePanel) {
        LinearLayout panel = findPanel(heading);
        if (panel == null || !PANEL_STYLED.add(panel)) return;

        panel.setPadding(dp(activity, 22), dp(activity, 20), dp(activity, 22), dp(activity, 22));
        panel.setBackground(round(activity, CARD, 28, BORDER, 1));
        if (Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(activity, 20));

        ViewGroup.LayoutParams raw = panel.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            lp.gravity = Gravity.BOTTOM;
            lp.leftMargin = dp(activity, 14);
            lp.rightMargin = dp(activity, 14);
            lp.bottomMargin = dp(activity, 14);
            lp.topMargin = 0;
            panel.setLayoutParams(lp);
        }

        heading.setTextSize(22);
        heading.setTextColor(TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        stylePanelChildren(activity, panel, gamePanel);
    }

    private static LinearLayout findPanel(TextView heading) {
        View cursor = heading;
        for (int i = 0; i < 6; i++) {
            if (!(cursor.getParent() instanceof View)) return null;
            cursor = (View) cursor.getParent();
            if (cursor instanceof LinearLayout && cursor.getParent() instanceof FrameLayout) {
                return (LinearLayout) cursor;
            }
        }
        return null;
    }

    private static void stylePanelChildren(Activity activity, LinearLayout panel, boolean gamePanel) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof LinearLayout && looksLikeInfoRow((LinearLayout) child)) {
                styleInfoRow(activity, (LinearLayout) child);
            }
        }

        java.util.ArrayList<View> all = new java.util.ArrayList<>();
        collect(panel, all);
        for (View view : all) {
            if (view instanceof Button) {
                styleButton(activity, (Button) view);
            } else if (view instanceof EditText) {
                EditText edit = (EditText) view;
                edit.setTextColor(TEXT);
                edit.setHintTextColor(Color.rgb(101, 113, 132));
                edit.setTextSize(15);
                edit.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
                edit.setBackground(round(activity, Color.rgb(16, 22, 30), 16, Color.rgb(48, 58, 73), 1));
            } else if (view instanceof TextView) {
                stylePanelText(activity, (TextView) view);
            }
        }

        if (gamePanel) {
            ImageView firstImage = findFirstImage(panel);
            if (firstImage != null) {
                ViewGroup.LayoutParams p = firstImage.getLayoutParams();
                p.width = dp(activity, 118);
                p.height = dp(activity, 84);
                firstImage.setLayoutParams(p);
                firstImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (Build.VERSION.SDK_INT >= 21) {
                    firstImage.setOutlineProvider(new ViewOutlineProvider() {
                        @Override public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, 18));
                        }
                    });
                    firstImage.setClipToOutline(true);
                }
                loadBanner(activity, firstImage);
            }
        }
    }

    private static void stylePanelText(Activity activity, TextView text) {
        String value = text.getText() == null ? "" : text.getText().toString().trim();
        if (value.isEmpty()) return;
        if ("×".equals(value)) {
            text.setTextColor(Color.rgb(214, 222, 233));
            text.setTextSize(22);
            text.setGravity(Gravity.CENTER);
            text.setBackground(round(activity, SURFACE, 16, Color.rgb(45, 55, 70), 1));
        } else if ("已支持汉化".equals(value)) {
            text.setTextColor(GREEN);
            text.setBackground(round(activity, GREEN_BG, 14, GREEN_BORDER, 1));
        } else if (value.contains("VIP 会员") || value.contains("体验会员")) {
            text.setTextColor(GREEN);
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        } else if (value.contains("镜像包")) {
            text.setTextColor(value.contains("已就绪") ? GREEN : MUTED);
        } else if (value.equals("封神榜(越南版)")) {
            text.setText("封神榜（越南版）");
            text.setTextColor(TEXT);
            text.setTextSize(19);
            text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    private static void styleButton(Activity activity, Button button) {
        String value = button.getText() == null ? "" : button.getText().toString().trim();
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setPadding(dp(activity, 16), 0, dp(activity, 16), 0);

        if (value.contains("启动游戏") || value.contains("兑换到当前账号")) {
            button.setTextColor(Color.WHITE);
            button.setBackground(round(activity, PRIMARY, 17, PRIMARY_BORDER, 1));
        } else if (value.contains("退出登录")) {
            button.setTextColor(DANGER_TEXT);
            button.setBackground(round(activity, DANGER_BG, 17, DANGER_BORDER, 1));
        } else {
            button.setTextColor(Color.rgb(215, 222, 232));
            button.setBackground(round(activity, SURFACE, 16, Color.rgb(48, 58, 73), 1));
        }
    }

    private static boolean looksLikeInfoRow(LinearLayout row) {
        if (row.getOrientation() != LinearLayout.HORIZONTAL || row.getChildCount() != 2) return false;
        if (!(row.getChildAt(0) instanceof TextView) || !(row.getChildAt(1) instanceof TextView)) return false;
        String left = ((TextView) row.getChildAt(0)).getText() == null
                ? ""
                : ((TextView) row.getChildAt(0)).getText().toString().trim();
        String right = ((TextView) row.getChildAt(1)).getText() == null
                ? ""
                : ((TextView) row.getChildAt(1)).getText().toString().trim();
        if ("×".equals(left)) return false;
        return !"账号中心".equals(right) && !"游戏详情".equals(right);
    }

    private static void styleInfoRow(Activity activity, LinearLayout row) {
        row.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13));
        row.setBackground(round(activity, CARD_SOFT, 16, Color.rgb(39, 48, 61), 1));
        ViewGroup.LayoutParams raw = row.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.topMargin = dp(activity, 8);
            row.setLayoutParams(lp);
        }
        TextView left = (TextView) row.getChildAt(0);
        TextView right = (TextView) row.getChildAt(1);
        left.setTextColor(MUTED);
        left.setTextSize(13);
        right.setTextColor(TEXT);
        right.setTextSize(14);
        right.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }

    private static ImageView findFirstImage(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ImageView) return (ImageView) child;
            if (child instanceof ViewGroup) {
                ImageView nested = findFirstImage((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static void collect(View view, java.util.List<View> out) {
        out.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
    }

    private static TextView label(Activity activity, String value, int sp, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private static TextView pill(Activity activity, String value, int fill, int color, int stroke) {
        TextView text = label(activity, value, 12, color, true);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        text.setBackground(round(activity, fill, 16, stroke, 1));
        return text;
    }

    private static GradientDrawable round(Activity activity, int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(activity, radiusDp));
        if (strokeDp > 0) bg.setStroke(dp(activity, strokeDp), stroke);
        return bg;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void loadBanner(Activity activity, ImageView target) {
        Bitmap ready = bannerBitmap;
        if (ready != null && !ready.isRecycled()) {
            target.setImageBitmap(ready);
            return;
        }

        new Thread(() -> {
            Bitmap bitmap = null;
            File cache = new File(activity.getCacheDir(), "rylux-fengshenbang-banner.png");
            try {
                long age = System.currentTimeMillis() - cache.lastModified();
                if (cache.isFile() && age >= 0 && age < 24L * 60L * 60L * 1000L) {
                    bitmap = BitmapFactory.decodeFile(cache.getAbsolutePath());
                }
                if (bitmap == null) {
                    bitmap = downloadBanner();
                    if (bitmap != null) {
                        try (FileOutputStream out = new FileOutputStream(cache)) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 95, out);
                            out.flush();
                        }
                    }
                }
            } catch (Throwable ignored) {
                if (cache.isFile()) bitmap = BitmapFactory.decodeFile(cache.getAbsolutePath());
            }
            if (bitmap == null) return;
            bannerBitmap = bitmap;
            Bitmap finalBitmap = bitmap;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) target.setImageBitmap(finalBitmap);
            });
        }, "RYLUX-Banner").start();
    }

    private static Bitmap downloadBanner() throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BANNER_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(true);
            connection.setRequestProperty("User-Agent", "RYLUX/2.3.0");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
