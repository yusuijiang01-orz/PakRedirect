package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Presents the existing account, game and sandbox actions on the home screen. */
public final class RyluxHomeNavigationPolish {
    private static final String ROOT_TAG = "rylux_home_navigation_v3";
    private static final int TEXT = Color.rgb(244, 248, 255);
    private static final int GOLD = Color.rgb(255, 216, 132);
    private static final int GREEN = Color.rgb(86, 226, 189);
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final Set<View> STYLED = Collections.newSetFromMap(new WeakHashMap<>());

    private RyluxHomeNavigationPolish() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> polish(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
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
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        }
    }

    private static void polish(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (findTagged(decor, ROOT_TAG) != null) return;
        GlowFrameLayout hero = findHero(decor);
        if (hero == null || !(hero.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) hero.getParent();
        if (STYLED.contains(root)) return;
        FrameLayout avatar = findHomeAvatar(root, hero);
        if (avatar == null) return;
        STYLED.add(root);
        root.setTag(ROOT_TAG);
        rebuild(activity, root, avatar, hero);
    }

    private static void rebuild(Activity activity, LinearLayout root, FrameLayout avatar,
                                GlowFrameLayout hero) {
        ArrayList<View> preserved = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != avatar && child != hero && !(child instanceof Space)) preserved.add(child);
        }
        root.removeAllViews();
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setFocusable(false);
        root.setFocusableInTouchMode(false);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 28));
        root.setBackgroundColor(Color.TRANSPARENT);
        installBackground(activity, root);

        int widthDp = RyluxViewport.widthDp(activity);
        int contentWidth = widthDp >= 600 ? dp(activity, 560) : ViewGroup.LayoutParams.MATCH_PARENT;
        LinearLayout header = header(activity, avatar, widthDp < 310);
        root.addView(header, new LinearLayout.LayoutParams(contentWidth, -2));

        LinearLayout section = sectionHeading(activity);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(contentWidth, -2);
        sectionLp.topMargin = dp(activity, widthDp < 310 ? 18 : 34);
        sectionLp.bottomMargin = dp(activity, 16);
        root.addView(section, sectionLp);

        FrameLayout card = gameCard(activity, hero, widthDp);
        int initialCardWidthDp = widthDp >= 600 ? 560 : Math.max(1, widthDp - 32);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                contentWidth, dp(activity, gameCardHeightDp(initialCardWidthDp)));
        root.addView(card, cardLp);
        // The backdrop fills the card, so the card itself must have a bounded
        // height before the first measurement. Recompute from its real width
        // when the window changes size, without allowing a MATCH_PARENT child
        // to determine the height of a WRAP_CONTENT parent.
        card.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            int widthPx = right - left;
            if (widthPx <= 0) return;
            float density = activity.getResources().getDisplayMetrics().density;
            int measuredWidthDp = Math.round(widthPx / Math.max(0.1f, density));
            int desiredHeight = dp(activity, gameCardHeightDp(measuredWidthDp));
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.height != desiredHeight) {
                params.height = desiredHeight;
                view.setLayoutParams(params);
            }
        });
        for (View child : preserved) root.addView(child);

        // fillViewport expands this spacer on tall phones; on short screens it
        // collapses to zero and the page scrolls rather than hiding controls.
        root.addView(new Space(activity), new LinearLayout.LayoutParams(1, 0, 1f));
        View sandbox = sandboxButton(activity);
        int sandboxWidth = widthDp >= 600
                ? dp(activity, 470) : dp(activity, Math.max(1, Math.round(widthDp * 0.84f)));
        LinearLayout.LayoutParams sandboxLp = new LinearLayout.LayoutParams(sandboxWidth, -2);
        sandboxLp.topMargin = dp(activity, 28);
        root.addView(sandbox, sandboxLp);
    }

    private static int gameCardHeightDp(int cardWidthDp) {
        return Math.max(104, Math.min(136, Math.round(cardWidthDp * 0.30f)));
    }

    private static void installBackground(Activity activity, LinearLayout root) {
        if (!(root.getParent() instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) root.getParent();
        scroll.setBackgroundColor(Color.TRANSPARENT);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setSaveEnabled(false);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        if (scroll.getParent() instanceof FrameLayout) {
            FrameLayout host = (FrameLayout) scroll.getParent();
            host.setBackgroundColor(Color.rgb(3, 14, 33));
            ImageView background = new ImageView(activity);
            background.setImageResource(R.drawable.rylux_home_background);
            // Preserve the designed light-ribbon positions across phone ratios.
            // CENTER_CROP clips the top and bottom artwork on narrow devices.
            background.setScaleType(ImageView.ScaleType.FIT_XY);
            background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            host.addView(background, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        scroll.post(() -> {
            scroll.requestFocus();
            scroll.scrollTo(0, 0);
        });
    }

    private static LinearLayout header(Activity activity, FrameLayout originalAvatar, boolean narrow) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        int avatarSize = dp(activity, narrow ? 50 : 58);
        View portrait = portrait(activity, originalAvatar);
        LinearLayout.LayoutParams portraitLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        portraitLp.rightMargin = dp(activity, 8);
        row.addView(portrait, portraitLp);

        View vip = membership(activity, originalAvatar);
        LinearLayout.LayoutParams vipLp = new LinearLayout.LayoutParams(
                dp(activity, narrow ? 96 : 106), dp(activity, 32));
        vipLp.leftMargin = dp(activity, 2);
        row.addView(vip, vipLp);
        if (!narrow) {
            row.addView(new Space(activity), new LinearLayout.LayoutParams(0, 1, 1f));
            row.addView(headerIcon(activity, R.drawable.ic_rylux_bell, "通知（暂不可用）"),
                    iconParams(activity, 0));
            row.addView(headerIcon(activity, R.drawable.ic_rylux_settings, "设置（暂不可用）"),
                    iconParams(activity, 0));
        }
        container.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (narrow) {
            LinearLayout actions = new LinearLayout(activity);
            actions.setGravity(Gravity.END);
            actions.addView(headerIcon(activity, R.drawable.ic_rylux_bell, "通知（暂不可用）"), iconParams(activity, 0));
            actions.addView(headerIcon(activity, R.drawable.ic_rylux_settings, "设置（暂不可用）"), iconParams(activity, 2));
            container.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        }
        return container;
    }

    private static View portrait(Activity activity, FrameLayout originalAvatar) {
        FrameLayout rim = new FrameLayout(activity);
        GradientDrawable aura = radialGlow(activity, Color.argb(145, 43, 167, 255),
                Color.argb(38, 70, 134, 255), 38, 28);
        rim.setBackground(aura);
        FrameLayout ring = new FrameLayout(activity);
        GradientDrawable ringGradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(137, 246, 255), Color.rgb(64, 167, 255), Color.rgb(211, 143, 255)});
        ringGradient.setShape(GradientDrawable.OVAL);
        ring.setBackground(ringGradient);
        ring.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(-1, -1);
        ringLp.setMargins(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3));
        rim.addView(ring, ringLp);
        ImageView image = new ImageView(activity);
        image.setImageResource(R.drawable.default_avatar);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        image.setClipToOutline(true);
        ring.addView(image, new FrameLayout.LayoutParams(-1, -1));
        rim.setClickable(true);
        rim.setFocusable(true);
        rim.setContentDescription("打开账号中心");
        rim.setOnClickListener(v -> originalAvatar.performClick());
        return rim;
    }

    private static FrameLayout membership(Activity activity, FrameLayout originalAvatar) {
        MainActivity main = (MainActivity) activity;
        boolean admin = "admin".equalsIgnoreCase(MainActivity.currentRole);
        boolean active = main.homeMembershipActive();
        String kind = main.homeMembershipKind();
        boolean trial = active && "trial".equalsIgnoreCase(kind);
        String text = admin ? "Admin" : (!active ? "已过期" : (trial ? "24小时体验" : "VIP"));
        FrameLayout shell = new FrameLayout(activity);
        shell.setBackground(radialGlow(activity, Color.argb(126, 255, 178, 61),
                Color.argb(28, 225, 125, 43), 170, 24));
        LinearLayout pill = new LinearLayout(activity);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(activity, 7), dp(activity, 2), dp(activity, 7), dp(activity, 2));
        pill.setMinimumHeight(dp(activity, 30));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(103, 70, 30), Color.rgb(48, 39, 31), Color.rgb(34, 34, 43)});
        bg.setCornerRadius(dp(activity, 18));
        bg.setStroke(dp(activity, 1), Color.rgb(255, 210, 119));
        pill.setBackground(bg);
        pill.setElevation(dp(activity, 2));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setContentDescription(text + "，打开账号中心");
        pill.setOnClickListener(v -> originalAvatar.performClick());
        ImageView crown = image(activity, R.drawable.ic_rylux_crown, 20);
        pill.addView(crown, new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));
        TextView label = label(activity, text, trial ? 10 : 14, admin ? GREEN : GOLD, true);
        label.setMaxLines(1);
        label.setAutoSizeTextTypeUniformWithConfiguration(
                8, trial ? 10 : 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textLp.leftMargin = dp(activity, 4);
        pill.addView(label, textLp);
        ImageView arrow = image(activity, R.drawable.ic_rylux_chevron_gold, 13);
        arrow.setColorFilter(Color.rgb(255, 226, 164));
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(activity, 13), dp(activity, 13));
        arrowLp.leftMargin = dp(activity, 2);
        pill.addView(arrow, arrowLp);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-1, dp(activity, 28), Gravity.CENTER);
        pillLp.setMargins(dp(activity, 3), dp(activity, 2), dp(activity, 3), dp(activity, 2));
        shell.addView(pill, pillLp);
        View glint = new View(activity);
        GradientDrawable glintBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, Color.argb(210, 255, 243, 205),
                        Color.argb(105, 255, 202, 108), Color.TRANSPARENT});
        glintBg.setCornerRadius(dp(activity, 2));
        glint.setBackground(glintBg);
        FrameLayout.LayoutParams glintLp = new FrameLayout.LayoutParams(-1, dp(activity, 1),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        glintLp.setMargins(dp(activity, 30), dp(activity, 4), dp(activity, 30), 0);
        shell.addView(glint, glintLp);
        shell.addView(new GoldDustView(activity), new FrameLayout.LayoutParams(-1, -1));
        shell.setClickable(true);
        shell.setFocusable(true);
        shell.setContentDescription(text + "，打开账号中心");
        shell.setOnClickListener(v -> originalAvatar.performClick());
        return shell;
    }

    private static LinearLayout sectionHeading(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView gamepad = image(activity, R.drawable.ic_rylux_gamepad, 28);
        row.addView(gamepad, new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 28)));
        TextView title = label(activity, "游戏服务", 18, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.leftMargin = dp(activity, 8);
        row.addView(title, titleLp);
        column.addView(row, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(activity);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(26, 156, 255), Color.rgb(125, 233, 255), Color.TRANSPARENT});
        glow.setCornerRadius(dp(activity, 3));
        line.setBackground(glow);
        line.setElevation(dp(activity, 4));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 2));
        lineLp.leftMargin = dp(activity, 40);
        lineLp.topMargin = dp(activity, 9);
        column.addView(line, lineLp);
        return column;
    }

    private static FrameLayout gameCard(Activity activity, GlowFrameLayout hero, int widthDp) {
        FrameLayout card = new FrameLayout(activity);
        card.setClipToOutline(true);
        card.setOutlineProvider(roundOutline(activity, 16));
        card.setBackgroundColor(Color.rgb(14, 36, 64));
        card.setElevation(dp(activity, 6));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("封神榜汉化版，已支持汉化，打开游戏详情");
        card.setOnClickListener(v -> hero.performClick());

        ImageView backdrop = new ImageView(activity);
        backdrop.setImageResource(R.drawable.rylux_game_card_background);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(224, 7, 24, 48), Color.argb(203, 7, 24, 48), Color.argb(170, 7, 24, 48)}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(activity);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        int imageSize = dp(activity, widthDp < 340 ? 72 : 80);
        FrameLayout thumbnail = new FrameLayout(activity);
        thumbnail.setClipToOutline(true);
        thumbnail.setOutlineProvider(roundOutline(activity, 13));
        ImageView image = new ImageView(activity);
        image.setImageResource(R.drawable.fengshenbang_thumb);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.addView(image, new FrameLayout.LayoutParams(-1, -1));
        thumbnail.setForeground(round(activity, Color.TRANSPARENT, 13, Color.rgb(215, 193, 143), 1));
        content.addView(thumbnail, new LinearLayout.LayoutParams(imageSize, imageSize));

        LinearLayout description = new LinearLayout(activity);
        description.setOrientation(LinearLayout.VERTICAL);
        description.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(activity, "封神榜（汉化版）", widthDp < 340 ? 15 : 17, TEXT, true);
        title.setMaxLines(2);
        title.setAutoSizeTextTypeUniformWithConfiguration(
                11, widthDp < 340 ? 15 : 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        description.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout status = new LinearLayout(activity);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(activity, 7), dp(activity, 4), dp(activity, 9), dp(activity, 4));
        GradientDrawable statusBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(23, 100, 99), Color.rgb(17, 68, 82), Color.rgb(20, 47, 72)});
        statusBg.setCornerRadius(dp(activity, 20));
        statusBg.setStroke(dp(activity, 1), Color.rgb(79, 220, 193));
        status.setBackground(statusBg);
        status.setElevation(dp(activity, 2));
        ImageView check = image(activity, R.drawable.ic_rylux_check, 15);
        status.addView(check, new LinearLayout.LayoutParams(dp(activity, 15), dp(activity, 15)));
        TextView supported = label(activity, "已支持汉化", 12, TEXT, true);
        supported.setShadowLayer(dp(activity, 5), 0, 0, Color.argb(150, 69, 225, 201));
        LinearLayout.LayoutParams supportedLp = new LinearLayout.LayoutParams(-2, -2);
        supportedLp.leftMargin = dp(activity, 5);
        status.addView(supported, supportedLp);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = dp(activity, 6);
        description.addView(status, statusLp);
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(0, -2, 1f);
        descriptionLp.leftMargin = dp(activity, 12);
        descriptionLp.rightMargin = dp(activity, 6);
        content.addView(description, descriptionLp);

        FrameLayout arrowGlow = new FrameLayout(activity);
        arrowGlow.setBackground(radialGlow(activity, Color.argb(115, 36, 161, 255),
                Color.argb(28, 36, 161, 255), 28, 16));
        FrameLayout arrowCircle = new FrameLayout(activity);
        GradientDrawable arrowBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(225, 16, 65, 118), Color.argb(225, 8, 37, 78)});
        arrowBg.setShape(GradientDrawable.OVAL);
        arrowBg.setStroke(dp(activity, 1), Color.rgb(83, 184, 255));
        arrowCircle.setBackground(arrowBg);
        ImageView arrow = image(activity, R.drawable.ic_rylux_chevron, 18);
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(dp(activity, 18),
                dp(activity, 18), Gravity.CENTER);
        arrowCircle.addView(arrow, arrowLp);
        arrowGlow.addView(arrowCircle, new FrameLayout.LayoutParams(dp(activity, 26),
                dp(activity, 26), Gravity.CENTER));
        content.addView(arrowGlow, new LinearLayout.LayoutParams(dp(activity, 32), dp(activity, 32)));
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));
        card.setForeground(round(activity, Color.TRANSPARENT, 16, Color.rgb(65, 145, 224), 1));
        return card;
    }

    private static View sandboxButton(Activity activity) {
        FrameLayout shell = new FrameLayout(activity);
        shell.setBackground(radialGlow(activity, Color.argb(143, 38, 157, 255),
                Color.argb(33, 37, 112, 214), 300, 24));
        LinearLayout button = new LinearLayout(activity);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        button.setMinimumHeight(dp(activity, 44));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(29, 100, 176), Color.rgb(14, 54, 109), Color.rgb(9, 34, 76)});
        bg.setCornerRadius(dp(activity, 22));
        bg.setStroke(dp(activity, 1.5f), Color.rgb(105, 210, 255));
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription("打开 BlackBox 游戏沙箱测试版");
        button.setOnClickListener(v -> activity.startActivity(new Intent(activity, BlackBoxSandboxActivity.class)));
        ImageView icon = image(activity, R.drawable.ic_rylux_cube, 18);
        button.addView(icon, new LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)));
        TextView title = label(activity, "BlackBox 游戏沙箱 · 测试版", 14, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.leftMargin = dp(activity, 8);
        button.addView(title, titleLp);
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        buttonLp.setMargins(dp(activity, 4), dp(activity, 3), dp(activity, 4), dp(activity, 3));
        shell.addView(button, buttonLp);
        View glint = new View(activity);
        GradientDrawable glintBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.TRANSPARENT, Color.argb(190, 181, 235, 255),
                        Color.argb(95, 89, 191, 255), Color.TRANSPARENT});
        glintBg.setCornerRadius(dp(activity, 2));
        glint.setBackground(glintBg);
        FrameLayout.LayoutParams glintLp = new FrameLayout.LayoutParams(-1, dp(activity, 1),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        glintLp.setMargins(dp(activity, 38), dp(activity, 5), dp(activity, 38), 0);
        shell.addView(glint, glintLp);
        shell.setClickable(true);
        shell.setFocusable(true);
        shell.setContentDescription("打开 BlackBox 游戏沙箱测试版");
        shell.setOnClickListener(v -> activity.startActivity(new Intent(activity, BlackBoxSandboxActivity.class)));
        return shell;
    }

    private static FrameLayout headerIcon(Activity activity, int drawable, String description) {
        FrameLayout shell = new FrameLayout(activity);
        shell.setBackground(radialGlow(activity, Color.argb(34, 97, 195, 255),
                Color.argb(10, 97, 195, 255), 34, 22));
        ImageView icon = image(activity, drawable, 22);
        shell.addView(icon, new FrameLayout.LayoutParams(dp(activity, 20), dp(activity, 20), Gravity.CENTER));
        shell.setContentDescription(description);
        shell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return shell;
    }

    private static GradientDrawable radialGlow(Activity activity, int center, int middle,
                                              float radiusDp, float cornerDp) {
        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientCenter(0.5f, 0.5f);
        glow.setGradientRadius(dp(activity, radiusDp));
        glow.setColors(new int[]{center, middle, Color.TRANSPARENT});
        glow.setCornerRadius(dp(activity, cornerDp));
        glow.setDither(true);
        return glow;
    }

    private static final class GoldDustView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        GoldDustView(Activity activity) {
            super(activity);
            density = activity.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float[][] particles = {
                    {0.035f, 0.22f, 0.9f}, {0.075f, 0.78f, 0.75f},
                    {0.925f, 0.17f, 0.8f}, {0.965f, 0.42f, 1.15f},
                    {0.945f, 0.83f, 0.7f}, {0.885f, 0.91f, 0.55f}
            };
            for (int i = 0; i < particles.length; i++) {
                float radius = particles[i][2] * density;
                int color = i % 2 == 0 ? Color.rgb(255, 238, 188) : Color.rgb(255, 194, 94);
                paint.setColor(color);
                paint.setShadowLayer(radius * 2.4f, 0, 0, Color.argb(175, 255, 177, 66));
                canvas.drawCircle(w * particles[i][0], h * particles[i][1], radius, paint);
            }
            paint.clearShadowLayer();
        }
    }

    private static LinearLayout.LayoutParams iconParams(Activity activity, int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 34), dp(activity, 40));
        lp.leftMargin = dp(activity, leftMargin);
        return lp;
    }

    private static ImageView image(Activity activity, int resource, int sizeDp) {
        ImageView view = new ImageView(activity);
        view.setImageResource(resource);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static TextView label(Activity activity, String text, float size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private static GradientDrawable round(Activity activity, int color, float radius, int stroke, float strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(activity, radius));
        drawable.setStroke(dp(activity, strokeWidth), stroke);
        return drawable;
    }

    private static ViewOutlineProvider roundOutline(Activity activity, float radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, radius));
            }
        };
    }

    private static FrameLayout findHomeAvatar(LinearLayout root, GlowFrameLayout hero) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != hero && child instanceof FrameLayout && !(child instanceof GlowFrameLayout)) {
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

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
