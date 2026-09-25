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
 * clearer labels and controls that forward to those existing actions.
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

    private static final String ROOT_TAG = "rylux_home_navigation_v3";
    private static final String ACCOUNT_REGION_TAG = "rylux_account_region_v3";

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
        for (View child : preserved) root.removeView(child);

        Configuration c = activity.getResources().getConfiguration();
        int widthDp = c.screenWidthDp;
        float scale = homeScale(activity);
        // Preserve the reference phone's single-row account header on compact
        // high-density screens; proportional scaling keeps its contents fitting.
        boolean narrow = widthDp < 280;

        root.setPadding(
                dp(activity, (widthDp <= 360 ? 10 : 16) * scale),
                dp(activity, 14 * scale),
                dp(activity, (widthDp <= 360 ? 10 : 16) * scale),
                dp(activity, 30 * scale)
        );

        TextView title = label(activity, "RYLUX 控制台", (widthDp <= 360 ? 20 : 23) * scale, TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = label(activity, "选择下方功能继续", 13 * scale, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(activity, 3 * scale);
        subtitleLp.bottomMargin = dp(activity, 10 * scale);
        root.addView(subtitle, subtitleLp);

        // Account header deliberately has no card/background. Keeping it visually
        // open prevents the avatar/status/button from being squeezed inside a
        // rounded rectangle on narrow phones and emulator windows.
        LinearLayout accountRegion = new LinearLayout(activity);
        accountRegion.setTag(ACCOUNT_REGION_TAG);
        accountRegion.setOrientation(LinearLayout.VERTICAL);
        accountRegion.setGravity(Gravity.CENTER_VERTICAL);
        accountRegion.setPadding(0, dp(activity, 2 * scale), 0, dp(activity, 4 * scale));
        accountRegion.setBackgroundColor(Color.TRANSPARENT);
        accountRegion.setClickable(true);
        accountRegion.setFocusable(true);
        accountRegion.setOnClickListener(v -> avatar.performClick());

        LinearLayout identityRow = new LinearLayout(activity);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        identityRow.setBaselineAligned(false);

        int avatarSize = dp(activity, (widthDp <= 360 ? 60 : (narrow ? 66 : 72)) * scale);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.rightMargin = dp(activity, (widthDp <= 360 ? 9 : 12) * scale);
        identityRow.addView(avatar, avatarLp);

        LinearLayout accountText = new LinearLayout(activity);
        accountText.setOrientation(LinearLayout.VERTICAL);
        accountText.setGravity(Gravity.CENTER_VERTICAL);

        TextView accountTitle = label(activity, "账号中心", (widthDp <= 360 ? 14.5f : 16.5f) * scale, TEXT, true);
        accountTitle.setSingleLine(true);
        accountText.addView(accountTitle, new LinearLayout.LayoutParams(-1, -2));

        String hintText = widthDp <= 340
                ? "会员状态 · 兑换码 · 退出"
                : "会员状态 · 兑换码 · 退出登录";
        TextView accountHint = label(activity, hintText, (widthDp <= 360 ? 10.5f : 12) * scale, MUTED, false);
        accountHint.setSingleLine(true);
        accountHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(activity, 4 * scale);
        accountText.addView(accountHint, hintLp);
        identityRow.addView(accountText, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!narrow) {
            Button accountButton = smallButton(activity, "查看");
            accountButton.setOnClickListener(v -> avatar.performClick());
            LinearLayout.LayoutParams accountButtonLp =
                    new LinearLayout.LayoutParams(dp(activity, (widthDp >= 600 ? 78 : 68) * scale), dp(activity, 38 * scale));
            accountButtonLp.leftMargin = dp(activity, 10 * scale);
            identityRow.addView(accountButton, accountButtonLp);
        }

        accountRegion.addView(identityRow, new LinearLayout.LayoutParams(-1, avatarSize));

        if (narrow) {
            Button accountButton = smallButton(activity, "查看账号");
            accountButton.setOnClickListener(v -> avatar.performClick());
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(activity, 40 * scale));
            buttonLp.topMargin = dp(activity, 8 * scale);
            accountRegion.addView(accountButton, buttonLp);
        }

        LinearLayout.LayoutParams accountLp = new LinearLayout.LayoutParams(-1, -2);
        accountLp.bottomMargin = dp(activity, 16 * scale);
        root.addView(accountRegion, accountLp);

        TextView gameSection = label(activity, "游戏服务", 16 * scale, TEXT, true);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.bottomMargin = dp(activity, 9 * scale);
        root.addView(gameSection, sectionLp);

        FrameLayout heroShell = new FrameLayout(activity);
        heroShell.setPadding(dp(activity, scale), dp(activity, scale), dp(activity, scale), dp(activity, scale));
        heroShell.setBackground(round(activity, SURFACE_2, 22 * scale, BORDER, 1));
        if (Build.VERSION.SDK_INT >= 21) heroShell.setElevation(dp(activity, 2 * scale));

        FrameLayout.LayoutParams heroLp = new FrameLayout.LayoutParams(-1, -1);
        hero.setLayoutParams(heroLp);
        heroShell.addView(hero);

        int heroHeight = homeHeroHeight(activity);
        LinearLayout.LayoutParams heroShellLp = new LinearLayout.LayoutParams(-1, heroHeight);
        heroShellLp.bottomMargin = dp(activity, 12 * scale);
        root.addView(heroShell, heroShellLp);

        LinearLayout flowCard = new LinearLayout(activity);
        flowCard.setOrientation(LinearLayout.VERTICAL);
        flowCard.setPadding(dp(activity, 14 * scale), dp(activity, 12 * scale), dp(activity, 14 * scale), dp(activity, 12 * scale));
        flowCard.setBackground(round(activity, SURFACE, 15 * scale, BORDER, 1));

        TextView flowTitle = label(activity, "操作流程", 14 * scale, TEXT, true);
        flowCard.addView(flowTitle, new LinearLayout.LayoutParams(-1, -2));
        TextView flow = label(activity, "① 安装游戏   →   ② 启动游戏", (widthDp <= 360 ? 11.5f : 12.5f) * scale, MUTED, false);
        flow.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams flowLp = new LinearLayout.LayoutParams(-1, -2);
        flowLp.topMargin = dp(activity, 6 * scale);
        flowCard.addView(flow, flowLp);

        LinearLayout.LayoutParams flowCardLp = new LinearLayout.LayoutParams(-1, -2);
        flowCardLp.bottomMargin = dp(activity, 12 * scale);
        root.addView(flowCard, flowCardLp);

        Button enter = primaryButton(activity, "进入游戏详情");
        enter.setOnClickListener(v -> hero.performClick());
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(-1, dp(activity, 52 * scale));
        enterLp.bottomMargin = dp(activity, 8 * scale);
        root.addView(enter, enterLp);

        TextView enterHint = label(activity, "安装和启动入口都在游戏详情中", 11.5f * scale, MUTED, false);
        enterHint.setGravity(Gravity.CENTER);
        root.addView(enterHint, new LinearLayout.LayoutParams(-1, -2));

        for (View child : preserved) root.addView(child);
    }

    static int homeHeroHeight(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        int heightDp = c.screenHeightDp;
        boolean landscape = c.screenWidthDp > heightDp;
        float baseHeight = landscape
                ? Math.max(220, Math.min(300, heightDp * 0.50f))
                : 340f;
        return dp(activity, Math.max(180, Math.min(340, baseHeight * homeScale(activity))));
    }

    static float homeScale(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        float widthScale = c.screenWidthDp / 450f;
        float heightScale = c.screenHeightDp / 800f;
        return Math.max(0.58f, Math.min(1f, Math.min(widthScale, heightScale)));
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
        float scale = homeScale(activity);
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13 * scale);
        button.setTextColor(Color.rgb(218, 232, 255));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 10 * scale), 0, dp(activity, 10 * scale), 0);
        button.setBackground(round(activity, Color.rgb(24, 47, 87), 10 * scale, Color.rgb(64, 123, 219), 1));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(0);
        }
        return button;
    }

    private static Button primaryButton(Activity activity, String text) {
        float scale = homeScale(activity);
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16 * scale);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 14 * scale), 0, dp(activity, 14 * scale), 0);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{BLUE_LIGHT, BLUE, BLUE_DARK}
        );
        bg.setCornerRadius(dp(activity, 13 * scale));
        bg.setStroke(dp(activity, 1), Color.rgb(104, 173, 255));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(dp(activity, 2 * scale));
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
