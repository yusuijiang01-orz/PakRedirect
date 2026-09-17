package com.example.pakredirect;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds an explicit navigation hierarchy to the logged-in home screen without
 * moving any account/game behavior out of MainActivity. Existing avatar and
 * hero click listeners remain the source of truth; this class only provides
 * clearer cards, labels and buttons that forward to those existing actions.
 */
public final class RyluxHomeNavigationPolish {
    private static final int TEXT = Color.rgb(241, 246, 252);
    private static final int MUTED = Color.rgb(142, 157, 177);
    private static final int SURFACE = Color.rgb(16, 24, 36);
    private static final int SURFACE_2 = Color.rgb(20, 31, 47);
    private static final int BORDER = Color.rgb(49, 70, 99);
    private static final int BLUE = Color.rgb(58, 129, 255);
    private static final int BLUE_LIGHT = Color.rgb(92, 161, 255);
    private static final int BLUE_DARK = Color.rgb(24, 79, 188);

    private static final String ROOT_TAG = "rylux_home_navigation_v2";

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final Set<View> STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private RyluxHomeNavigationPolish() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> polish(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decor.post(() -> polish(activity));
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
        GlowFrameLayout hero = findHero(activity.getWindow().getDecorView());
        if (hero == null || hero.getChildCount() < 3) return;
        if (!(hero.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) hero.getParent();
        if (ROOT_TAG.equals(root.getTag()) || STYLED.contains(root)) return;

        FrameLayout avatar = findHomeAvatar(root, hero);
        if (avatar == null) return;

        STYLED.add(root);
        root.setTag(ROOT_TAG);
        rebuild(activity, root, avatar, hero);
    }

    private static void rebuild(
            Activity activity,
            LinearLayout root,
            FrameLayout avatar,
            GlowFrameLayout hero
    ) {
        ArrayList<View> preserved = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != avatar && child != hero && !(child instanceof Space)) preserved.add(child);
        }

        root.removeView(avatar);
        root.removeView(hero);
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (root.getChildAt(i) instanceof Space) root.removeViewAt(i);
        }

        // The current home has only avatar + gap + hero. Keep any future direct
        // children rather than silently deleting them.
        for (View child : preserved) root.removeView(child);

        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 30));

        TextView title = label(activity, "RYLUX 控制台", 23, TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = label(activity, "选择下方功能继续", 13, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(activity, 3);
        subtitleLp.bottomMargin = dp(activity, 14);
        root.addView(subtitle, subtitleLp);

        LinearLayout accountCard = new LinearLayout(activity);
        accountCard.setOrientation(LinearLayout.HORIZONTAL);
        accountCard.setGravity(Gravity.CENTER_VERTICAL);
        accountCard.setPadding(dp(activity, 13), dp(activity, 11), dp(activity, 11), dp(activity, 11));
        accountCard.setBackground(round(activity, SURFACE, 18, BORDER, 1));
        accountCard.setClickable(true);
        accountCard.setFocusable(true);
        if (Build.VERSION.SDK_INT >= 21) accountCard.setElevation(dp(activity, 2));

        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(activity, 72), dp(activity, 72));
        avatarLp.rightMargin = dp(activity, 12);
        accountCard.addView(avatar, avatarLp);

        LinearLayout accountText = new LinearLayout(activity);
        accountText.setOrientation(LinearLayout.VERTICAL);
        TextView accountTitle = label(activity, "账号中心", 16.5f, TEXT, true);
        accountText.addView(accountTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView accountHint = label(activity, "会员状态 · 兑换码 · 退出登录", 12, MUTED, false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(activity, 4);
        accountText.addView(accountHint, hintLp);
        accountCard.addView(accountText, new LinearLayout.LayoutParams(0, -2, 1f));

        Button accountButton = smallButton(activity, "查看");
        accountButton.setOnClickListener(v -> avatar.performClick());
        LinearLayout.LayoutParams accountButtonLp =
                new LinearLayout.LayoutParams(dp(activity, 66), dp(activity, 38));
        accountButtonLp.leftMargin = dp(activity, 8);
        accountCard.addView(accountButton, accountButtonLp);
        accountCard.setOnClickListener(v -> avatar.performClick());

        LinearLayout.LayoutParams accountLp = new LinearLayout.LayoutParams(-1, -2);
        accountLp.bottomMargin = dp(activity, 18);
        root.addView(accountCard, accountLp);

        TextView gameSection = label(activity, "游戏服务", 16, TEXT, true);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = dp(activity, 9);
        root.addView(gameSection, sectionLp);

        FrameLayout heroShell = new FrameLayout(activity);
        heroShell.setPadding(dp(activity, 1), dp(activity, 1), dp(activity, 1), dp(activity, 1));
        heroShell.setBackground(round(activity, SURFACE_2, 22, BORDER, 1));
        if (Build.VERSION.SDK_INT >= 21) heroShell.setElevation(dp(activity, 2));

        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(-1, -1);
        hero.setLayoutParams(heroLp);
        heroShell.addView(hero);

        int heroHeight = homeHeroHeight(activity);
        LinearLayout.LayoutParams heroShellLp = new LinearLayout.LayoutParams(-1, heroHeight);
        heroShellLp.bottomMargin = dp(activity, 12);
        root.addView(heroShell, heroShellLp);

        LinearLayout flowCard = new LinearLayout(activity);
        flowCard.setOrientation(LinearLayout.VERTICAL);
        flowCard.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
        flowCard.setBackground(round(activity, SURFACE, 15, BORDER, 1));

        TextView flowTitle = label(activity, "操作流程", 14, TEXT, true);
        flowCard.addView(flowTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView flow = label(activity, "① 安装游戏   →   ② 选择镜像包   →   ③ 启动游戏", 12.5f, MUTED, false);
        flow.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(-1, -2);
        flowLp.topMargin = dp(activity, 6);
        flowCard.addView(flow, flowLp);

        LinearLayout.LayoutParams flowCardLp = new LinearLayout.LayoutParams(-1, -2);
        flowCardLp.bottomMargin = dp(activity, 12);
        root.addView(flowCard, flowCardLp);

        Button enter = primaryButton(activity, "进入游戏详情");
        enter.setOnClickListener(v -> hero.performClick());
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(-1, dp(activity, 52));
        enterLp.bottomMargin = dp(activity, 8);
        root.addView(enter, enterLp);

        TextView enterHint = label(activity, "安装、镜像包和启动入口都在游戏详情中", 11.5f, MUTED, false);
        enterHint.setGravity(Gravity.CENTER);
        root.addView(enterHint, new LinearLayout.LayoutParams(-1, -2));

        // Preserve future direct children after the redesigned primary content.
        for (View child : preserved) root.addView(child);
    }

    private static int homeHeroHeight(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        int widthDp = c.screenWidthDp;
        int heightDp = c.screenHeightDp;
        if (widthDp >= 600) return dp(activity, 390);
        if (widthDp <= 380 || heightDp <= 680) return dp(activity, 320);
        return dp(activity, 360);
    }

    private static FrameLayout findHomeAvatar(LinearLayout root, GlowFrameLayout hero) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == hero) continue;
            if (child instanceof FrameLayout && !(child instanceof GlowFrameLayout)) {
                return (FrameLayout) child;
            }
        }
        return null;
    }

    private static GlowFrameLayout findHero(View view) {
        if (view instanceof GlowFrameLayout) return (GlowFrameLayout) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            GlowFrameLayout found = findHero(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static Button smallButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(Color.rgb(218, 232, 255));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(round(activity, Color.rgb(24, 47, 87), 10, Color.rgb(64, 123, 219), 1));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(0);
        }
        return button;
    }

    private static Button primaryButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BLUE_LIGHT, BLUE, BLUE_DARK}
        );
        bg.setCornerRadius(dp(activity, 13));
        bg.setStroke(dp(activity, 1), Color.rgb(104, 173, 255));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(dp(activity, 2));
        }
        return button;
    }

    private static TextView label(Activity activity, String text, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private static GradientDrawable round(
            Activity activity,
            int fill,
            float radiusDp,
            int stroke,
            int strokeDp
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(activity, radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(activity, strokeDp), stroke);
        return drawable;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}