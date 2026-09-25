package com.example.pakredirect;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.WeakHashMap;

/** Runtime fixes for emulator resolutions, landscape panels and short viewports. */
public final class RyluxAdaptiveLayoutFix {
    private static final String HOME_TAG = "rylux_home_navigation_v3";
    static final String GAME_SCROLL_TAG = "rylux_game_panel_scroll_v1";

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final WeakHashMap<View, String> LAST_HOME_KEY = new WeakHashMap<>();
    private static final WeakHashMap<View, String> LAST_PANEL_KEY = new WeakHashMap<>();

    private RyluxAdaptiveLayoutFix() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> tune(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> decor.post(() -> tune(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> tune(activity));
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

    private static void tune(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        int widthDp = c.screenWidthDp;
        int heightDp = c.screenHeightDp;
        boolean landscape = widthDp > heightDp;
        String key = widthDp + "x" + heightDp + ":" + landscape;

        View home = findTagged(activity.getWindow().getDecorView(), HOME_TAG);
        if (home instanceof LinearLayout && !key.equals(LAST_HOME_KEY.get(home))) {
            LAST_HOME_KEY.put(home, key);
            tuneHome(activity, (LinearLayout) home, widthDp, heightDp, landscape);
        }

        TextView gameHeading = findGameDetailHeading(activity.getWindow().getDecorView());
        LinearLayout gamePanel = gameHeading == null ? null : findPanel(gameHeading);
        if (gamePanel != null && !key.equals(LAST_PANEL_KEY.get(gamePanel))) {
            LAST_PANEL_KEY.put(gamePanel, key);
            tuneGamePanel(activity, gamePanel, widthDp, heightDp, landscape);
        }
    }

    private static void tuneHome(
            Activity activity,
            LinearLayout root,
            int widthDp,
            int heightDp,
            boolean landscape
    ) {
        float scale = RyluxHomeNavigationPolish.homeScale(activity);
        int side = widthDp <= 360 ? 10 : (landscape ? 20 : 16);
        root.setPadding(
                dp(activity, Math.round(side * scale)),
                dp(activity, Math.round(14 * scale)),
                dp(activity, Math.round(side * scale)),
                dp(activity, Math.round(28 * scale))
        );

        FrameLayout heroShell = findHeroShell(root);
        if (heroShell == null) return;

        ViewGroup.LayoutParams raw = heroShell.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;

        lp.height = RyluxHomeNavigationPolish.homeHeroHeight(activity);
        heroShell.setLayoutParams(lp);
    }

    private static void tuneGamePanel(
            Activity activity,
            LinearLayout panel,
            int widthDp,
            int heightDp,
            boolean landscape
    ) {
        boolean needsScroll = shouldScrollGamePanel(activity);
        View parent = panel.getParent() instanceof View ? (View) panel.getParent() : null;

        if (needsScroll && parent instanceof FrameLayout) {
            FrameLayout overlay = (FrameLayout) parent;
            overlay.removeView(panel);

            ScrollView scroll = new ScrollView(activity);
            scroll.setTag(GAME_SCROLL_TAG);
            scroll.setFillViewport(false);
            scroll.setClipToPadding(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.setPadding(0, dp(activity, 8), 0, dp(activity, 8));

            panel.setLayoutParams(new ScrollView.LayoutParams(-1, -2));
            scroll.addView(panel);
            overlay.addView(scroll, scrollParams(activity, widthDp, landscape));
            parent = scroll;
        }

        if (parent instanceof ScrollView && GAME_SCROLL_TAG.equals(parent.getTag())) {
            ScrollView scroll = (ScrollView) parent;
            scroll.setLayoutParams(scrollParams(activity, widthDp, landscape));
            ViewGroup.LayoutParams raw = panel.getLayoutParams();
            if (!(raw instanceof ScrollView.LayoutParams)) {
                panel.setLayoutParams(new ScrollView.LayoutParams(-1, -2));
            } else {
                raw.width = ViewGroup.LayoutParams.MATCH_PARENT;
                raw.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.setLayoutParams(raw);
            }
            panel.setPadding(
                    dp(activity, Math.round((landscape ? 16 : 14) * gameDetailScale(activity))),
                    dp(activity, Math.round(12 * gameDetailScale(activity))),
                    dp(activity, Math.round((landscape ? 16 : 14) * gameDetailScale(activity))),
                    dp(activity, Math.round(16 * gameDetailScale(activity)))
            );
        }
    }

    private static FrameLayout.LayoutParams scrollParams(Activity activity, int widthDp, boolean landscape) {
        int margin = dp(activity, 10);
        int maxWidthDp = landscape ? Math.min(680, Math.max(420, widthDp - 40)) : widthDp;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                landscape ? dp(activity, maxWidthDp) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        lp.topMargin = margin;
        lp.bottomMargin = margin;
        return lp;
    }

    static boolean shouldScrollGamePanel(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        return c.screenWidthDp <= 380 || c.screenHeightDp <= 760 || c.screenWidthDp > c.screenHeightDp;
    }

    static float gameDetailScale(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        float width = c.screenWidthDp / 380f;
        float height = c.screenHeightDp / 760f;
        return Math.max(0.72f, Math.min(1f, Math.min(width, height)));
    }

    static ScrollView makeGamePanelScrollable(Activity activity, LinearLayout panel) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setTag(GAME_SCROLL_TAG);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        panel.setLayoutParams(new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scroll.addView(panel);
        scroll.post(() -> {
            scroll.requestFocus();
            scroll.scrollTo(0, 0);
        });
        return scroll;
    }

    static FrameLayout.LayoutParams gamePanelScrollParams(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        return scrollParams(activity, c.screenWidthDp, c.screenWidthDp > c.screenHeightDp);
    }

    private static FrameLayout findHeroShell(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof FrameLayout && findFirst(child, GlowFrameLayout.class) != null) {
                return (FrameLayout) child;
            }
        }
        return null;
    }

    private static LinearLayout findPanel(TextView heading) {
        View cursor = heading;
        for (int i = 0; i < 8; i++) {
            if (!(cursor.getParent() instanceof View)) return null;
            cursor = (View) cursor.getParent();
            if (cursor instanceof LinearLayout) {
                Object parent = cursor.getParent();
                if (parent instanceof FrameLayout || parent instanceof ScrollView) return (LinearLayout) cursor;
            }
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

    private static TextView findGameDetailHeading(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                String value = text.toString().trim();
                if ("游戏详情".equals(value)
                        || (value.contains("游戏详情") && !"进入游戏详情".equals(value))) {
                    return (TextView) view;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findGameDetailHeading(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> T findFirst(View view, Class<T> type) {
        if (type.isInstance(view)) return (T) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            T found = findFirst(group.getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
