package com.example.pakredirect;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Runtime responsive adjustments layered on top of RyluxUiPolish.
 *
 * The visual mock-up was tuned on a roomy 450x800dp phone profile
 * (900x1600 @ 320dpi). Many common Android emulator/phone profiles such as
 * 1080x1920 @ 480dpi and 1440x2560 @ 640dpi resolve to only 360x640dp, while
 * landscape tablets are much wider but not necessarily taller. This class
 * keeps the approved visual direction while applying safe size constraints to
 * compact phones and wide/landscape screens.
 */
public final class RyluxResponsiveTuner {
    private static final String HOME_NAVIGATION_TAG = "rylux_home_navigation_v3";
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final WeakHashMap<TextView, Float> ORIGINAL_TEXT_SIZES = new WeakHashMap<>();

    private RyluxResponsiveTuner() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) return;
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
        Configuration configuration = activity.getResources().getConfiguration();
        int widthDp = configuration.screenWidthDp;
        int heightDp = configuration.screenHeightDp;
        boolean compact = widthDp <= 380 || heightDp <= 680;
        boolean wide = widthDp >= 600;
        tuneRecursive(activity, activity.getWindow().getDecorView(), widthDp, heightDp, compact, wide);
    }

    private static void tuneRecursive(
            Activity activity,
            View view,
            int widthDp,
            int heightDp,
            boolean compact,
            boolean wide
    ) {
        if (view instanceof GlowFrameLayout) {
            tuneHero(activity, (GlowFrameLayout) view, compact, wide);
        }

        if (view instanceof TextView) {
            String text = value((TextView) view);
            if ("账号中心".equals(text)) {
                if (!isInsideHomeNavigation(view)) {
                    LinearLayout panel = findPanel((TextView) view);
                    if (panel != null) tunePanel(activity, panel, false, compact, wide);
                }
            } else if (text.contains("游戏详情")) {
                LinearLayout panel = findPanel((TextView) view);
                if (panel != null) tunePanel(activity, panel, true, compact, wide);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tuneRecursive(activity, group.getChildAt(i), widthDp, heightDp, compact, wide);
            }
        }
    }

    private static boolean isInsideHomeNavigation(View view) {
        View current = view;
        while (current != null) {
            if (HOME_NAVIGATION_TAG.equals(current.getTag())) return true;
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return false;
    }

    private static void tuneHero(Activity activity, GlowFrameLayout hero, boolean compact, boolean wide) {
        if (!(hero.getParent() instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) hero.getParent();
        ViewGroup.LayoutParams raw = hero.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;

        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        int available = root.getWidth() > 0
                ? Math.max(dp(activity, 280), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight())
                : dp(activity, wide ? 420 : 360);

        if (wide) {
            lp.width = Math.min(available, dp(activity, 420));
            lp.leftMargin = 0;
            lp.rightMargin = 0;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }

        int heroWidth = wide ? lp.width : hero.getWidth();
        if (heroWidth <= 0 && available > 0) heroWidth = available;
        if (heroWidth > 0) {
            float ratio = compact ? 1.36f : 1.46f;
            int desired = Math.round(heroWidth * ratio);
            int min = dp(activity, compact ? 390 : 430);
            int max = dp(activity, wide ? (compact ? 500 : 560) : (compact ? 500 : 620));
            lp.height = Math.max(min, Math.min(max, desired));
        }
        hero.setLayoutParams(lp);

        if (wide && root.getChildCount() > 0 && root.getChildAt(0) instanceof FrameLayout) {
            View avatar = root.getChildAt(0);
            ViewGroup.LayoutParams avatarRaw = avatar.getLayoutParams();
            if (avatarRaw instanceof LinearLayout.LayoutParams && root.getWidth() > 0) {
                LinearLayout.LayoutParams avatarLp = (LinearLayout.LayoutParams) avatarRaw;
                int heroTarget = lp.width > 0 ? lp.width : dp(activity, 420);
                avatarLp.leftMargin = Math.max(dp(activity, 4), (root.getWidth() - heroTarget) / 2);
                avatar.setLayoutParams(avatarLp);
            }
        }
    }

    private static void tunePanel(
            Activity activity,
            LinearLayout panel,
            boolean gamePanel,
            boolean compact,
            boolean wide
    ) {
        ViewGroup.LayoutParams raw = panel.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            if (wide) {
                lp.width = dp(activity, gamePanel ? 520 : 430);
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                lp.gravity = gamePanel ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
                lp.bottomMargin = gamePanel ? dp(activity, 12) : 0;
            } else {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                int margin = dp(activity, compact ? (gamePanel ? 8 : 10) : (gamePanel ? 14 : 34));
                lp.leftMargin = margin;
                lp.rightMargin = margin;
                lp.gravity = gamePanel ? Gravity.BOTTOM : Gravity.CENTER;
                lp.bottomMargin = gamePanel ? dp(activity, compact ? 8 : 14) : 0;
            }
            panel.setLayoutParams(lp);
        } else if (gamePanel && raw instanceof android.widget.ScrollView.LayoutParams) {
            raw.width = ViewGroup.LayoutParams.MATCH_PARENT;
            raw.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            panel.setLayoutParams(raw);
        } else {
            return;
        }

        if (compact) {
            float scale = gamePanel ? RyluxAdaptiveLayoutFix.gameDetailScale(activity) : 1f;
            panel.setPadding(
                    dp(activity, Math.round(14 * scale)),
                    dp(activity, Math.round(10 * scale)),
                    dp(activity, Math.round(14 * scale)),
                    dp(activity, Math.round(14 * scale))
            );
            if (gamePanel) compactGamePanel(activity, panel);
            else compactAccountPanel(activity, panel);
        }
    }

    private static void compactAccountPanel(Activity activity, LinearLayout panel) {
        if (panel.getChildCount() < 5) return;

        setHeight(panel.getChildAt(0), dp(activity, 42));

        View profileRaw = panel.getChildAt(1);
        if (profileRaw instanceof LinearLayout) {
            LinearLayout profile = (LinearLayout) profileRaw;
            profile.setPadding(dp(activity, 11), dp(activity, 9), dp(activity, 11), dp(activity, 9));
            if (profile.getChildCount() > 0) {
                View identityRaw = profile.getChildAt(0);
                if (identityRaw instanceof LinearLayout) {
                    ImageView avatar = firstImage((ViewGroup) identityRaw);
                    if (avatar != null) setSize(avatar, dp(activity, 50), dp(activity, 50));
                }
            }
            if (profile.getChildCount() > 1) setHeight(profile.getChildAt(1), dp(activity, 36));
            if (profile.getChildCount() > 2) setHeight(profile.getChildAt(2), dp(activity, 40));
        }

        if (panel.getChildCount() > 2) setHeight(panel.getChildAt(2), dp(activity, 44));
        if (panel.getChildCount() > 3 && panel.getChildAt(3) instanceof EditText) {
            setHeight(panel.getChildAt(3), dp(activity, 44));
        }
        if (panel.getChildCount() > 4 && panel.getChildAt(4) instanceof Button) {
            setHeight(panel.getChildAt(4), dp(activity, 44));
        }
        if (panel.getChildCount() > 5 && panel.getChildAt(5) instanceof Button) {
            setHeight(panel.getChildAt(5), dp(activity, 42));
        }

        shrinkText(panel, 0.92f);
    }

    private static void compactGamePanel(Activity activity, LinearLayout panel) {
        if (panel.getChildCount() < 5) return;
        float scale = RyluxAdaptiveLayoutFix.gameDetailScale(activity);

        setHeight(panel.getChildAt(0), dp(activity, Math.round(42 * scale)));

        View gameHeadRaw = panel.getChildAt(1);
        if (gameHeadRaw instanceof ViewGroup) {
            ImageView thumbnail = firstImage((ViewGroup) gameHeadRaw);
            if (thumbnail != null) setSize(
                    thumbnail,
                    dp(activity, Math.round(96 * scale)),
                    dp(activity, Math.round(118 * scale))
            );
        }

        View infoRaw = panel.getChildAt(2);
        if (infoRaw instanceof LinearLayout) {
            LinearLayout info = (LinearLayout) infoRaw;
            info.setPadding(
                    dp(activity, Math.round(11 * scale)),
                    dp(activity, Math.round(4 * scale)),
                    dp(activity, Math.round(11 * scale)),
                    dp(activity, Math.round(4 * scale))
            );
            for (int i = 0; i < info.getChildCount(); i++) {
                View child = info.getChildAt(i);
                if (child instanceof LinearLayout || child instanceof TextView) {
                    setHeight(child, dp(activity, Math.round(37 * scale)));
                }
            }
        }

        View install = findTagged(panel, "rylux_install_game_button");
        if (install != null) setHeight(install, dp(activity, Math.round(50 * scale)));
        View localization = findTagged(panel, "rylux_localization_control");
        if (localization != null) setHeight(localization, dp(activity, Math.round(68 * scale)));
        View toggle = findContentDescription(localization, "汉化开关");
        if (toggle != null) setHeight(toggle, dp(activity, Math.round(38 * scale)));

        View start = findTagged(panel, "rylux_start_game_button");
        if (start != null) setHeight(start, dp(activity, Math.round(50 * scale)));
        View progressRaw = findTagged(panel, "rylux_module_progress_text");
        if (progressRaw instanceof TextView) {
            TextView progress = (TextView) progressRaw;
            progress.setTextSize(11);
            progress.setPadding(0, dp(activity, Math.round(5 * scale)), 0, dp(activity, Math.round(3 * scale)));
        }
        View progressBar = findTagged(panel, "rylux_module_progress");
        if (progressBar != null) setHeight(progressBar, dp(activity, Math.round(4 * scale)));

        shrinkText(panel, scale);
    }

    private static LinearLayout findPanel(TextView heading) {
        View cursor = heading;
        for (int i = 0; i < 7; i++) {
            if (!(cursor.getParent() instanceof View)) return null;
            cursor = (View) cursor.getParent();
            if (cursor instanceof LinearLayout
                    && (cursor.getParent() instanceof FrameLayout
                    || cursor.getParent() instanceof android.widget.ScrollView)) {
                return (LinearLayout) cursor;
            }
        }
        return null;
    }

    private static ImageView firstImage(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) return (ImageView) child;
            if (child instanceof ViewGroup) {
                ImageView nested = firstImage((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static View findTagged(View view, String tag) {
        if (view == null) return null;
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findContentDescription(View view, String description) {
        if (view == null) return null;
        CharSequence actualDescription = view.getContentDescription();
        if (actualDescription != null && description.contentEquals(actualDescription)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findContentDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void shrinkText(View view, float factor) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Float original = ORIGINAL_TEXT_SIZES.get(text);
            if (original == null) {
                original = text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity;
                ORIGINAL_TEXT_SIZES.put(text, original);
            }
            if (original > 12f) text.setTextSize(Math.max(11.5f, original * factor));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) shrinkText(group.getChildAt(i), factor);
        }
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null || lp.height == height) return;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    private static void setSize(View view, int width, int height) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null) return;
        lp.width = width;
        lp.height = height;
        view.setLayoutParams(lp);
    }

    private static String value(TextView text) {
        CharSequence value = text.getText();
        return value == null ? "" : value.toString().trim();
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
