package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.ScrollView;
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
    private static final String PINNED_HEADER_TAG = "rylux_home_pinned_header_v1";
    private static final String HOME_BODY_SCROLL_TAG = "rylux_home_body_scroll_v1";
    private static final String ACCOUNT_REGION_TAG = "rylux_account_region_v3";
    private static final String IDENTITY_ROW_TAG = "rylux_account_identity_row_v1";
    private static final String ACCOUNT_BUTTON_TAG = "rylux_account_button_v1";
    private static final int ACCOUNT_BUTTON_BELOW_BREAKPOINT_DP = 420;

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final Set<View> STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<View, Boolean> LAST_NARROW_MODE = new WeakHashMap<>();

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
        LinearLayout homeRoot = (LinearLayout) findTagged(
                activity.getWindow().getDecorView(), ROOT_TAG);
        if (homeRoot != null) {
            adaptAccountRow(activity, homeRoot);
            syncPinnedHeaderPosition(activity);
            return;
        }

        GlowFrameLayout hero = findHero(activity.getWindow().getDecorView());
        if (hero == null || hero.getChildCount() < 3) return;
        if (!(hero.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) hero.getParent();
        if (STYLED.contains(root)) {
            adaptAccountRow(activity, root);
            return;
        }

        FrameLayout avatar = findHomeAvatar(root, hero);
        if (avatar == null) return;

        STYLED.add(root);
        root.setTag(ROOT_TAG);
        rebuild(activity, root, avatar, hero);
        LAST_NARROW_MODE.put(root, RyluxViewport.widthDp(activity) <= ACCOUNT_BUTTON_BELOW_BREAKPOINT_DP);
        restoreHomeScrollPosition(root);
    }

    private static void rebuild(
            Activity activity,
            LinearLayout root,
            FrameLayout avatar,
            GlowFrameLayout hero
    ) {
        View focused = root.findFocus();
        if (focused != null) focused.clearFocus();
        avatar.clearFocus();
        hero.clearFocus();
        // Keep the poster clickable without letting it win initial keyboard focus.
        hero.setFocusable(false);
        hero.setFocusableInTouchMode(false);
        // The content root can be taller than the viewport. Focusing it makes
        // ScrollView try to reveal the whole root and align its bottom, hiding
        // the account row above. Let the ScrollView itself hold initial focus.
        root.setFocusable(false);
        root.setFocusableInTouchMode(false);
        root.clearFocus();

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

        int widthDp = RyluxViewport.widthDp(activity);
        boolean narrow = widthDp <= ACCOUNT_BUTTON_BELOW_BREAKPOINT_DP;

        root.setPadding(
                dp(activity, widthDp <= 360 ? 10 : 16),
                dp(activity, 14),
                dp(activity, widthDp <= 360 ? 10 : 16),
                dp(activity, 30)
        );

        TextView title = label(activity, "RYLUX 控制台", widthDp <= 360 ? 20 : 23, TEXT, true);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = label(activity, "选择下方功能继续", 13, MUTED, false);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(activity, 4);
        subtitleLp.bottomMargin = dp(activity, 14);
        root.addView(subtitle, subtitleLp);

        LinearLayout accountRegion = new LinearLayout(activity);
        accountRegion.setTag(ACCOUNT_REGION_TAG);
        accountRegion.setOrientation(LinearLayout.VERTICAL);
        accountRegion.setGravity(Gravity.CENTER_VERTICAL);
        accountRegion.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
        accountRegion.setBackground(round(activity, SURFACE_2, 16, BORDER, 1));
        accountRegion.setMinimumHeight(dp(activity, widthDp <= 360 ? 88 : 96));
        accountRegion.setClickable(true);
        accountRegion.setFocusable(true);
        accountRegion.setOnClickListener(v -> avatar.performClick());

        LinearLayout identityRow = new LinearLayout(activity);
        identityRow.setTag(IDENTITY_ROW_TAG);
        identityRow.setOrientation(LinearLayout.HORIZONTAL);
        identityRow.setGravity(Gravity.CENTER_VERTICAL);
        identityRow.setBaselineAligned(false);
        identityRow.setMinimumHeight(dp(activity, widthDp <= 360 ? 64 : 72));

        int avatarSize = dp(activity, widthDp <= 360 ? 64 : 72);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.rightMargin = dp(activity, widthDp <= 360 ? 10 : 12);
        identityRow.addView(avatar, avatarLp);

        LinearLayout accountText = new LinearLayout(activity);
        accountText.setOrientation(LinearLayout.VERTICAL);
        accountText.setGravity(Gravity.CENTER_VERTICAL);

        TextView accountTitle = label(activity, "账号中心", widthDp <= 360 ? 16 : 17, TEXT, true);
        accountTitle.setSingleLine(true);
        accountText.addView(accountTitle, new LinearLayout.LayoutParams(-1, -2));
        identityRow.addView(accountText, new LinearLayout.LayoutParams(0, -2, 1f));

        Button accountButton = smallButton(activity, narrow ? "查看账号" : "查看");
        accountButton.setTag(ACCOUNT_BUTTON_TAG);
        accountButton.setOnClickListener(v -> avatar.performClick());
        if (!narrow) identityRow.addView(accountButton, accountButtonParams(activity, widthDp));

        accountRegion.addView(identityRow, new LinearLayout.LayoutParams(-1, -2));

        // Give the description the full card width and its own natural-height
        // row so it does not compete with the avatar and button for space.
        TextView accountHint = label(activity, "会员状态 · 兑换码 · 退出登录", widthDp <= 360 ? 12 : 13, MUTED, false);
        accountHint.setIncludeFontPadding(true);
        accountHint.setMaxLines(2);
        accountHint.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(activity, 6);
        accountRegion.addView(accountHint, hintLp);

        if (narrow) {
            accountRegion.addView(accountButton, accountButtonBelowParams(activity));
        }

        LinearLayout.LayoutParams accountLp = new LinearLayout.LayoutParams(-1, -2);
        accountLp.bottomMargin = dp(activity, 18);
        root.addView(accountRegion, accountLp);

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
        TextView flow = label(activity, "① 安装游戏   →   ② 启动游戏", widthDp <= 360 ? 11.5f : 12.5f, MUTED, false);
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

        TextView enterHint = label(activity, "安装和启动入口都在游戏详情中", 11.5f, MUTED, false);
        enterHint.setGravity(Gravity.CENTER);
        root.addView(enterHint, new LinearLayout.LayoutParams(-1, -2));

        Button sandbox = smallButton(activity, "BlackBox 游戏沙箱 · 测试版");
        sandbox.setOnClickListener(v -> activity.startActivity(
                new Intent(activity, BlackBoxSandboxActivity.class)
        ));
        LinearLayout.LayoutParams sandboxLp = new LinearLayout.LayoutParams(-1, dp(activity, 48));
        sandboxLp.topMargin = dp(activity, 16);
        root.addView(sandbox, sandboxLp);

        TextView sandboxHint = label(activity, "导入 APK，在虚拟环境中尝试运行", 11.5f, MUTED, false);
        sandboxHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sandboxHintLp = new LinearLayout.LayoutParams(-1, -2);
        sandboxHintLp.topMargin = dp(activity, 6);
        root.addView(sandboxHint, sandboxHintLp);

        for (View child : preserved) root.addView(child);
        pinHomeAccountHeader(activity, root, title, subtitle, accountRegion);
    }

    private static void adaptAccountRow(Activity activity, LinearLayout root) {
        Boolean wasNarrow = LAST_NARROW_MODE.get(root);
        if (wasNarrow == null) return;
        boolean narrow = RyluxViewport.widthDp(activity) <= ACCOUNT_BUTTON_BELOW_BREAKPOINT_DP;
        if (wasNarrow == narrow) return;

        View decor = activity.getWindow().getDecorView();
        View regionRaw = findTagged(decor, ACCOUNT_REGION_TAG);
        View rowRaw = findTagged(decor, IDENTITY_ROW_TAG);
        View buttonRaw = findTagged(decor, ACCOUNT_BUTTON_TAG);
        if (!(regionRaw instanceof LinearLayout)
                || !(rowRaw instanceof LinearLayout)
                || !(buttonRaw instanceof Button)) return;

        LinearLayout region = (LinearLayout) regionRaw;
        LinearLayout row = (LinearLayout) rowRaw;
        Button button = (Button) buttonRaw;
        if (button.getParent() instanceof ViewGroup) {
            ((ViewGroup) button.getParent()).removeView(button);
        }

        if (narrow) {
            button.setText("查看账号");
            region.addView(button, accountButtonBelowParams(activity));
        } else {
            button.setText("查看");
            row.addView(button, accountButtonParams(activity, RyluxViewport.widthDp(activity)));
        }
        LAST_NARROW_MODE.put(root, narrow);
    }

    private static LinearLayout.LayoutParams accountButtonParams(Activity activity, int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(activity, widthDp >= 600 ? 78 : 68), dp(activity, 42));
        params.leftMargin = dp(activity, 10);
        return params;
    }

    private static LinearLayout.LayoutParams accountButtonBelowParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 40));
        params.topMargin = dp(activity, 8);
        return params;
    }

    static int homeHeroHeight(Activity activity) {
        int widthDp = RyluxViewport.widthDp(activity);
        int heightDp = RyluxViewport.heightDp(activity);
        boolean landscape = widthDp > heightDp;
        // Match the 1529x2048 poster's natural portrait ratio. A fixed 340dp
        // card was wider than the artwork at phone widths, so CENTER_CROP
        // removed the character's head and other important content.
        int sidePaddingDp = widthDp <= 360 ? 10 : 16;
        int posterWidthDp = Math.max(1, widthDp - (sidePaddingDp * 2) - 2);
        int posterHeightDp = Math.round(posterWidthDp * (2048f / 1529f));
        int heroHeightDp = landscape
                ? Math.max(220, Math.min(300, Math.round(heightDp * 0.50f)))
                : Math.max(340, Math.min(680, posterHeightDp));
        return dp(activity, heroHeightDp);
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

    private static View findTagged(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTagged(group.getChildAt(i), tag);
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
        button.setPadding(dp(activity, 10), 0, dp(activity, 10), 0);
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

    private static void pinHomeAccountHeader(
            Activity activity,
            LinearLayout homeRoot,
            TextView title,
            TextView subtitle,
            LinearLayout accountRegion
    ) {
        if (!(homeRoot.getParent() instanceof ScrollView)) return;
        ScrollView bodyScroll = (ScrollView) homeRoot.getParent();
        if (!(bodyScroll.getParent() instanceof FrameLayout)) return;
        FrameLayout host = (FrameLayout) bodyScroll.getParent();

        homeRoot.removeView(title);
        homeRoot.removeView(subtitle);
        homeRoot.removeView(accountRegion);

        LinearLayout header = new LinearLayout(activity);
        header.setTag(PINNED_HEADER_TAG);
        header.setOrientation(LinearLayout.VERTICAL);
        int side = RyluxViewport.widthDp(activity) <= 360 ? 10 : 16;
        header.setPadding(dp(activity, side), dp(activity, 14), dp(activity, side), 0);
        header.setBackgroundColor(Color.rgb(8, 13, 21));
        header.setElevation(dp(activity, 6));
        header.addView(title, title.getLayoutParams());
        header.addView(subtitle, subtitle.getLayoutParams());
        header.addView(accountRegion, accountRegion.getLayoutParams());

        bodyScroll.setTag(HOME_BODY_SCROLL_TAG);
        bodyScroll.setFocusable(false);
        bodyScroll.setFocusableInTouchMode(false);
        bodyScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        FrameLayout.LayoutParams bodyParams = bodyScroll.getLayoutParams()
                instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) bodyScroll.getLayoutParams()
                : new FrameLayout.LayoutParams(-1, -1);
        bodyParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        bodyParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        bodyScroll.setLayoutParams(bodyParams);

        host.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        ));
        syncPinnedHeaderPosition(activity);
        homeRoot.post(() -> bodyScroll.scrollTo(0, 0));
        bodyScroll.postDelayed(() -> bodyScroll.scrollTo(0, 0), 180L);
        bodyScroll.postDelayed(() -> bodyScroll.scrollTo(0, 0), 520L);
    }

    private static void syncPinnedHeaderPosition(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        View header = findTagged(decor, PINNED_HEADER_TAG);
        View scrollRaw = findTagged(decor, HOME_BODY_SCROLL_TAG);
        if (header == null || !(scrollRaw instanceof ScrollView)
                || !(header.getParent() instanceof FrameLayout)) return;

        FrameLayout host = (FrameLayout) header.getParent();
        int width = host.getWidth() > 0
                ? host.getWidth()
                : dp(activity, RyluxViewport.widthDp(activity));
        header.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int headerHeight = header.getMeasuredHeight();
        ScrollView bodyScroll = (ScrollView) scrollRaw;
        ViewGroup.LayoutParams raw = bodyScroll.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
        if (params.topMargin == headerHeight) return;
        params.topMargin = headerHeight;
        bodyScroll.setLayoutParams(params);
    }
}
