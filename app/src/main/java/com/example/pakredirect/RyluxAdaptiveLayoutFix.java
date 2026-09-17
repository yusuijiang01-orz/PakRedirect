package com.example.pakredirect;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.WeakHashMap;

/** Runtime fixes for compact phones, emulator resolutions and landscape panels. */
public final class RyluxAdaptiveLayoutFix {
    private static final String HOME_TAG = "rylux_home_navigation_v2";
    private static final String GAME_SCROLL_TAG = "rylux_game_panel_scroll_v1";

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

        TextView gameHeading = findTextContaining(activity.getWindow().getDecorView(), "游戏详情");
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
        boolean compact = widthDp <= 420;
        root.setPadding(
                dp(activity, compact ? 10 : (landscape ? 20 : 16)),
                dp(activity, compact ? 10 : 14),
                dp(activity, compact ? 10 : (landscape ? 20 : 16)),
                dp(activity, 28)
        );

        LinearLayout accountCard = findAccountCard(root);
        if (accountCard != null) rebuildAccountCard(activity, accountCard, widthDp, compact);

        FrameLayout heroShell = findHeroShell(root);
        if (heroShell != null) {
            ViewGroup.LayoutParams raw = heroShell.getLayoutParams();
            if (raw instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                int height;
                if (landscape) {
                    height = dp(activity, clamp(Math.round(heightDp * 0.52f), 220, 300));
                } else if (widthDp <= 380 || heightDp <= 680) {
                    height = dp(activity, 300);
                } else if (widthDp >= 600) {
                    height = dp(activity, 360);
                } else {
                    height = dp(activity, 340);
                }
                lp.height = height;
                heroShell.setLayoutParams(lp);
            }
        }
    }

    private static void rebuildAccountCard(
            Activity activity,
            LinearLayout card,
            int widthDp,
            boolean compact
    ) {
        FrameLayout avatar = findFirst(card, FrameLayout.class);
        Button button = findButton(card, "查看");
        LinearLayout accountText = findLinearWithExactText(card, "账号中心");
        if (avatar == null || button == null || accountText == null) return;

        removeFromParent(avatar);
        removeFromParent(button);
        removeFromParent(accountText);
        card.removeAllViews();
        card.setPadding(
                dp(activity, compact ? 10 : 13),
                dp(activity, compact ? 10 : 11),
                dp(activity, compact ? 10 : 11),
                dp(activity, compact ? 10 : 11)
        );

        TextView title = findExactText(accountText, "账号中心");
        TextView hint = findHintText(accountText);
        if (title != null) title.setTextSize(compact ? 15f : 16.5f);
        if (hint != null) {
            hint.setTextSize(compact ? 11f : 12f);
            hint.setSingleLine(true);
            if (widthDp <= 340) hint.setText("会员状态 · 兑换码 · 退出");
            else hint.setText("会员状态 · 兑换码 · 退出登录");
        }

        int avatarSize = dp(activity, compact ? 64 : 72);
        if (compact) {
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
            avatarLp.rightMargin = dp(activity, 11);
            top.addView(avatar, avatarLp);
            top.addView(accountText, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(top, new LinearLayout.LayoutParams(-1, -2));

            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(activity, 40));
            buttonLp.topMargin = dp(activity, 9);
            card.addView(button, buttonLp);
        } else {
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
            avatarLp.rightMargin = dp(activity, 12);
            card.addView(avatar, avatarLp);
            card.addView(accountText, new LinearLayout.LayoutParams(0, -2, 1f));

            int buttonWidth = dp(activity, widthDp >= 600 ? 78 : 66);
            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(buttonWidth, dp(activity, 38));
            buttonLp.leftMargin = dp(activity, 8);
            card.addView(button, buttonLp);
        }
    }

    private static void tuneGamePanel(
            Activity activity,
            LinearLayout panel,
            int widthDp,
            int heightDp,
            boolean landscape
    ) {
        boolean needsScroll = landscape || heightDp <= 700;
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
                    dp(activity, landscape ? 16 : 14),
                    dp(activity, 12),
                    dp(activity, landscape ? 16 : 14),
                    dp(activity, 16)
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

    private static LinearLayout findAccountCard(LinearLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof LinearLayout
                    && findExactText(child, "账号中心") != null
                    && findButton(child, "查看") != null) {
                return (LinearLayout) child;
            }
        }
        return null;
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

    private static TextView findTextContaining(View view, String needle) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().contains(needle)) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextContaining(group.getChildAt(i), needle);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findExactText(View view, String exact) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && exact.equals(text.toString().trim())) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findExactText(group.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findHintText(LinearLayout accountText) {
        for (int i = 0; i < accountText.getChildCount(); i++) {
            View child = accountText.getChildAt(i);
            if (child instanceof TextView) {
                String value = ((TextView) child).getText() == null ? "" : ((TextView) child).getText().toString();
                if (!"账号中心".equals(value.trim())) return (TextView) child;
            }
        }
        return null;
    }

    private static LinearLayout findLinearWithExactText(View view, String exact) {
        if (view instanceof LinearLayout && findExactText(view, exact) != null) return (LinearLayout) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            LinearLayout found = findLinearWithExactText(group.getChildAt(i), exact);
            if (found != null) return found;
        }
        return null;
    }

    private static Button findButton(View view, String exact) {
        if (view instanceof Button) {
            CharSequence text = ((Button) view).getText();
            if (text != null && exact.equals(text.toString().trim())) return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), exact);
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

    private static void removeFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
