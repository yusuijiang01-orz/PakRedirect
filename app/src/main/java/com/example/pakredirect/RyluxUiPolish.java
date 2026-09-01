package com.example.pakredirect;

import android.app.Activity;
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
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Presentation-only layer for the programmatic MainActivity.
 *
 * MainActivity still owns authentication, membership, mirror import,
 * protected-content preparation and game launch. This class only rearranges
 * and styles existing views so those behaviors keep their original listeners
 * and references.
 */
public final class RyluxUiPolish {
    private static final int BG_TOP = Color.rgb(8, 14, 23);
    private static final int BG_BOTTOM = Color.rgb(5, 8, 13);
    private static final int PANEL_TOP = Color.rgb(20, 29, 43);
    private static final int PANEL_BOTTOM = Color.rgb(13, 20, 31);
    private static final int SURFACE = Color.rgb(16, 24, 36);
    private static final int SURFACE_2 = Color.rgb(20, 31, 47);
    private static final int BORDER = Color.rgb(49, 70, 99);
    private static final int BORDER_SOFT = Color.rgb(35, 50, 72);
    private static final int TEXT = Color.rgb(241, 246, 252);
    private static final int MUTED = Color.rgb(142, 157, 177);
    private static final int MUTED_2 = Color.rgb(102, 119, 142);
    private static final int BLUE = Color.rgb(58, 129, 255);
    private static final int BLUE_LIGHT = Color.rgb(92, 161, 255);
    private static final int BLUE_DARK = Color.rgb(24, 79, 188);
    private static final int RED = Color.rgb(235, 70, 74);
    private static final int RED_DARK = Color.rgb(68, 26, 31);
    private static final int RED_BORDER = Color.rgb(133, 49, 57);
    private static final int RED_TEXT = Color.rgb(255, 150, 153);
    private static final int YELLOW = Color.rgb(246, 192, 78);

    private static final Set<View> HOME_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> HERO_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> AVATAR_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<View> PANEL_STYLED =
            Collections.newSetFromMap(new WeakHashMap<>());
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();

    private RyluxUiPolish() {}

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) return;
            final View decor = activity.getWindow().getDecorView();
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
        activity.getWindow().setStatusBarColor(BG_TOP);
        activity.getWindow().setNavigationBarColor(BG_BOTTOM);
        if (Build.VERSION.SDK_INT >= 23) activity.getWindow().getDecorView().setSystemUiVisibility(0);
        styleRecursive(activity, activity.getWindow().getDecorView());
    }

    private static void styleRecursive(Activity activity, View view) {
        if (view instanceof GlowFrameLayout) {
            styleHome(activity, (GlowFrameLayout) view);
        }

        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            String value = value(textView);
            if ("账号中心".equals(value)) {
                stylePanel(activity, textView, false);
            } else if ("游戏详情".equals(value)) {
                stylePanel(activity, textView, true);
            }
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

        if (HOME_STYLED.add(root)) {
            root.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 26));
            root.setBackground(verticalGradient(BG_TOP, BG_BOTTOM, 0));

            if (root.getChildCount() > 0 && root.getChildAt(0) instanceof FrameLayout) {
                FrameLayout avatarShell = (FrameLayout) root.getChildAt(0);
                styleAvatar(activity, avatarShell);
                ViewGroup.LayoutParams raw = avatarShell.getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
                    lp.width = dp(activity, 72);
                    lp.height = dp(activity, 72);
                    lp.leftMargin = dp(activity, 4);
                    lp.bottomMargin = dp(activity, 6);
                    avatarShell.setLayoutParams(lp);
                }
            }
            if (root.getChildCount() > 1 && root.getChildAt(1) instanceof Space) {
                ViewGroup.LayoutParams raw = root.getChildAt(1).getLayoutParams();
                if (raw instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) raw).height = dp(activity, 8);
                    root.getChildAt(1).setLayoutParams(raw);
                }
            }
        }

        if (HERO_STYLED.add(hero)) buildHero(activity, hero);
    }

    private static void styleAvatar(Activity activity, FrameLayout shell) {
        if (!AVATAR_STYLED.add(shell)) return;

        String membership = "VIP";
        for (int i = 0; i < shell.getChildCount(); i++) {
            View child = shell.getChildAt(i);
            if (child instanceof TextView) {
                String value = value((TextView) child);
                if ("体验".equals(value) || "VIP".equals(value)) membership = value;
            }
        }

        shell.removeAllViews();
        shell.setBackgroundColor(Color.TRANSPARENT);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);

        FrameLayout portraitFrame = new FrameLayout(activity);
        portraitFrame.setPadding(dp(activity, 2), dp(activity, 2), dp(activity, 2), dp(activity, 2));
        portraitFrame.setBackground(oval(
                Color.rgb(21, 31, 45),
                Color.rgb(170, 135, 77),
                dp(activity, 1)
        ));
        FrameLayout.LayoutParams portraitFrameLp =
                new FrameLayout.LayoutParams(dp(activity, 58), dp(activity, 58));
        portraitFrameLp.gravity = Gravity.BOTTOM | Gravity.START;
        portraitFrameLp.leftMargin = dp(activity, 1);
        portraitFrameLp.bottomMargin = dp(activity, 1);
        shell.addView(portraitFrame, portraitFrameLp);

        ImageView portrait = new ImageView(activity);
        portrait.setImageResource(R.drawable.default_avatar);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (Build.VERSION.SDK_INT >= 21) {
            portrait.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            portrait.setClipToOutline(true);
        }
        portraitFrame.addView(portrait, new FrameLayout.LayoutParams(-1, -1));

        boolean trial = "体验".equals(membership);
        TextView badge = label(activity, membership, 10, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        badge.setBackground(round(
                activity,
                trial ? Color.rgb(109, 77, 22) : RED,
                9,
                trial ? Color.rgb(173, 126, 36) : Color.rgb(255, 104, 106),
                1
        ));
        FrameLayout.LayoutParams badgeLp =
                new FrameLayout.LayoutParams(-2, dp(activity, 22));
        badgeLp.gravity = Gravity.TOP | Gravity.END;
        badgeLp.topMargin = dp(activity, 2);
        badgeLp.rightMargin = dp(activity, 1);
        shell.addView(badge, badgeLp);
    }

    private static void buildHero(Activity activity, GlowFrameLayout hero) {
        hero.removeAllViews();
        hero.setPadding(0, 0, 0, 0);
        hero.setClipChildren(true);
        hero.setClipToPadding(false);

        if (Build.VERSION.SDK_INT >= 21) {
            hero.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, 25));
                }
            });
            hero.setClipToOutline(true);
        }

        ViewGroup.LayoutParams raw = hero.getLayoutParams();
        if (raw instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) raw;
            lp.leftMargin = dp(activity, 3);
            lp.rightMargin = dp(activity, 3);
            lp.bottomMargin = dp(activity, 16);
            hero.setLayoutParams(lp);
        }

        hero.post(() -> {
            ViewGroup.LayoutParams params = hero.getLayoutParams();
            if (!(params instanceof LinearLayout.LayoutParams) || hero.getWidth() <= 0) return;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
            int desired = Math.round(hero.getWidth() * 1.46f);
            int min = dp(activity, 430);
            int max = dp(activity, 620);
            int height = Math.max(min, Math.min(max, desired));
            if (lp.height != height) {
                lp.height = height;
                hero.setLayoutParams(lp);
            }
        });

        ImageView poster = new ImageView(activity);
        poster.setImageResource(R.drawable.fengshenbang_hero);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setAdjustViewBounds(false);
        hero.addView(poster, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(activity);
        shade.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        Color.argb(15, 3, 7, 13),
                        Color.argb(18, 3, 7, 13),
                        Color.argb(72, 3, 7, 13),
                        Color.argb(228, 3, 7, 13)
                }
        ));
        hero.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout caption = new LinearLayout(activity);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(dp(activity, 22), 0, dp(activity, 22), dp(activity, 23));

        TextView title = label(activity, "封神榜（越南版）", 29, Color.rgb(246, 224, 186), true);
        title.setShadowLayer(dp(activity, 8), 0, dp(activity, 2), Color.argb(190, 0, 0, 0));
        caption.addView(title);

        TextView supported = label(activity, "已支持汉化", 12, Color.rgb(155, 205, 255), true);
        supported.setGravity(Gravity.CENTER);
        supported.setPadding(dp(activity, 9), 0, dp(activity, 9), 0);
        supported.setBackground(round(
                activity,
                Color.argb(190, 19, 55, 99),
                6,
                Color.rgb(49, 119, 203),
                1
        ));
        LinearLayout.LayoutParams supportedLp =
                new LinearLayout.LayoutParams(-2, dp(activity, 27));
        supportedLp.topMargin = dp(activity, 8);
        caption.addView(supported, supportedLp);

        FrameLayout.LayoutParams captionLp =
                new FrameLayout.LayoutParams(-1, -2);
        captionLp.gravity = Gravity.BOTTOM;
        hero.addView(caption, captionLp);
    }

    private static void stylePanel(Activity activity, TextView heading, boolean gamePanel) {
        LinearLayout panel = findPanel(heading);
        if (panel == null || !PANEL_STYLED.add(panel)) return;

        panel.setBackground(verticalGradient(PANEL_TOP, PANEL_BOTTOM, dp(activity, 22)));
        panel.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 20));
        if (Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(activity, 24));

        ViewGroup.LayoutParams raw = panel.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
            lp.leftMargin = dp(activity, gamePanel ? 14 : 34);
            lp.rightMargin = dp(activity, gamePanel ? 14 : 34);
            lp.bottomMargin = dp(activity, gamePanel ? 14 : 0);
            lp.topMargin = 0;
            lp.gravity = gamePanel ? Gravity.BOTTOM : Gravity.CENTER;
            panel.setLayoutParams(lp);
        }

        if (gamePanel) rebuildGamePanel(activity, panel);
        else rebuildAccountPanel(activity, panel);
    }

    private static void rebuildAccountPanel(Activity activity, LinearLayout panel) {
        if (panel.getChildCount() < 9) {
            styleFallbackPanel(activity, panel);
            return;
        }

        ArrayList<View> children = snapshot(panel);
        View header = children.get(0);
        TextView username = asText(children.get(1));
        TextView state = asText(children.get(2));
        View expiry = children.get(3);
        Button refresh = asButton(children.get(4));
        TextView redeemTitle = asText(children.get(5));
        EditText code = asEdit(children.get(6));
        Button redeem = asButton(children.get(7));
        Button logout = asButton(children.get(8));

        panel.removeAllViews();

        styleHeader(activity, header, false);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        LinearLayout profileCard = new LinearLayout(activity);
        profileCard.setOrientation(LinearLayout.VERTICAL);
        profileCard.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 14));
        profileCard.setBackground(round(activity, SURFACE, 15, BORDER_SOFT, 1));

        LinearLayout identity = new LinearLayout(activity);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(activity);
        avatar.setImageResource(R.drawable.default_avatar);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (Build.VERSION.SDK_INT >= 21) {
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            avatar.setClipToOutline(true);
        }
        LinearLayout.LayoutParams avatarLp =
                new LinearLayout.LayoutParams(dp(activity, 58), dp(activity, 58));
        avatarLp.rightMargin = dp(activity, 13);
        identity.addView(avatar, avatarLp);

        LinearLayout userText = new LinearLayout(activity);
        userText.setOrientation(LinearLayout.VERTICAL);
        username.setTextSize(17);
        username.setTextColor(TEXT);
        username.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        userText.addView(username, new LinearLayout.LayoutParams(-1, -2));

        String stateText = value(state);
        state.setTextSize(14);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setTextColor(stateText.contains("体验") ? YELLOW
                : stateText.contains("VIP") ? Color.rgb(255, 102, 112)
                : MUTED);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(-1, -2);
        stateLp.topMargin = dp(activity, 4);
        userText.addView(state, stateLp);
        identity.addView(userText, new LinearLayout.LayoutParams(0, -2, 1f));
        profileCard.addView(identity);

        styleInfoRow(activity, expiry, false);
        LinearLayout.LayoutParams expiryLp = new LinearLayout.LayoutParams(-1, dp(activity, 42));
        expiryLp.topMargin = dp(activity, 10);
        profileCard.addView(expiry, expiryLp);

        styleOutlineButton(activity, refresh, false);
        refresh.setText("⟳  刷新会员状态");
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-1, dp(activity, 44));
        refreshLp.topMargin = dp(activity, 8);
        profileCard.addView(refresh, refreshLp);

        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(-1, -2);
        profileLp.topMargin = dp(activity, 12);
        panel.addView(profileCard, profileLp);

        redeemTitle.setText("✦  兑换码充值  ✦");
        redeemTitle.setGravity(Gravity.CENTER);
        redeemTitle.setTextSize(16);
        redeemTitle.setTextColor(TEXT);
        redeemTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams redeemTitleLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 52));
        redeemTitleLp.topMargin = dp(activity, 6);
        panel.addView(redeemTitle, redeemTitleLp);

        styleInput(activity, code);
        panel.addView(code, new LinearLayout.LayoutParams(-1, dp(activity, 50)));

        styleOutlineButton(activity, redeem, true);
        LinearLayout.LayoutParams redeemLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 48));
        redeemLp.topMargin = dp(activity, 10);
        panel.addView(redeem, redeemLp);

        styleDangerButton(activity, logout);
        logout.setText("退出登录");
        LinearLayout.LayoutParams logoutLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 46));
        logoutLp.topMargin = dp(activity, 14);
        panel.addView(logout, logoutLp);
    }

    private static void rebuildGamePanel(Activity activity, LinearLayout panel) {
        if (panel.getChildCount() < 10) {
            styleFallbackPanel(activity, panel);
            return;
        }

        ArrayList<View> children = snapshot(panel);
        View header = children.get(0);
        View gameHeadRaw = children.get(1);
        TextView description = asText(children.get(2));
        View updateRow = children.get(3);
        View progressRow = children.get(4);
        TextView mirrorStatus = asText(children.get(5));
        Button mirrorButton = asButton(children.get(6));
        Button startButton = asButton(children.get(7));
        TextView moduleProgressText = asText(children.get(8));
        View moduleProgress = children.get(9);

        panel.removeAllViews();

        styleHeader(activity, header, true);
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(activity, 48)));

        if (gameHeadRaw instanceof LinearLayout) {
            LinearLayout gameHead = (LinearLayout) gameHeadRaw;
            gameHead.setGravity(Gravity.TOP);
            gameHead.setPadding(0, dp(activity, 10), 0, dp(activity, 11));

            ImageView icon = null;
            LinearLayout gameText = null;
            if (gameHead.getChildCount() >= 2) {
                if (gameHead.getChildAt(0) instanceof ImageView) icon = (ImageView) gameHead.getChildAt(0);
                if (gameHead.getChildAt(1) instanceof LinearLayout) gameText = (LinearLayout) gameHead.getChildAt(1);
            }

            if (icon != null) {
                icon.setImageResource(R.drawable.fengshenbang_thumb);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                if (Build.VERSION.SDK_INT >= 21) {
                    icon.setOutlineProvider(new ViewOutlineProvider() {
                        @Override
                        public void getOutline(View view, Outline outline) {
                            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, 13));
                        }
                    });
                    icon.setClipToOutline(true);
                }
                ViewGroup.LayoutParams p = icon.getLayoutParams();
                if (p instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) p;
                    lp.width = dp(activity, 118);
                    lp.height = dp(activity, 146);
                    lp.rightMargin = dp(activity, 15);
                    icon.setLayoutParams(lp);
                }
            }

            if (gameText != null) {
                if (gameText.getChildCount() > 0 && gameText.getChildAt(0) instanceof TextView) {
                    TextView title = (TextView) gameText.getChildAt(0);
                    title.setTextSize(20);
                    title.setTextColor(TEXT);
                    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                }
                if (gameText.getChildCount() > 1 && gameText.getChildAt(1) instanceof TextView) {
                    TextView badge = (TextView) gameText.getChildAt(1);
                    badge.setTextColor(Color.rgb(155, 205, 255));
                    badge.setTextSize(11);
                    badge.setGravity(Gravity.CENTER);
                    badge.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
                    badge.setBackground(round(
                            activity,
                            Color.argb(190, 18, 55, 101),
                            5,
                            Color.rgb(47, 111, 190),
                            1
                    ));
                    ViewGroup.LayoutParams badgeRaw = badge.getLayoutParams();
                    if (badgeRaw instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams badgeLp = (LinearLayout.LayoutParams) badgeRaw;
                        badgeLp.height = dp(activity, 26);
                        badgeLp.topMargin = dp(activity, 7);
                        badge.setLayoutParams(badgeLp);
                    }
                }

                removeFromParent(description);
                description.setTextSize(12.5f);
                description.setTextColor(Color.rgb(173, 184, 199));
                description.setLineSpacing(0f, 1.18f);
                description.setPadding(0, dp(activity, 10), 0, 0);
                gameText.addView(description, new LinearLayout.LayoutParams(-1, -2));
            }

            panel.addView(gameHead, new LinearLayout.LayoutParams(-1, -2));
        } else {
            panel.addView(gameHeadRaw);
            panel.addView(description);
        }

        LinearLayout infoCard = new LinearLayout(activity);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(activity, 13), dp(activity, 5), dp(activity, 13), dp(activity, 5));
        infoCard.setBackground(round(activity, SURFACE, 14, BORDER_SOFT, 1));

        styleInfoRow(activity, updateRow, true);
        styleInfoRow(activity, progressRow, true);
        infoCard.addView(updateRow, new LinearLayout.LayoutParams(-1, dp(activity, 43)));
        infoCard.addView(separator(activity), new LinearLayout.LayoutParams(-1, dp(activity, 1)));
        infoCard.addView(progressRow, new LinearLayout.LayoutParams(-1, dp(activity, 43)));
        infoCard.addView(separator(activity), new LinearLayout.LayoutParams(-1, dp(activity, 1)));

        mirrorStatus.setTextSize(13);
        mirrorStatus.setTextColor(MUTED);
        mirrorStatus.setGravity(Gravity.CENTER_VERTICAL);
        mirrorStatus.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        infoCard.addView(mirrorStatus, new LinearLayout.LayoutParams(-1, dp(activity, 43)));

        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(-1, -2);
        infoLp.topMargin = dp(activity, 2);
        panel.addView(infoCard, infoLp);

        styleOutlineButton(activity, mirrorButton, false);
        mirrorButton.setText("选择镜像包");
        LinearLayout.LayoutParams mirrorLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 46));
        mirrorLp.topMargin = dp(activity, 12);
        panel.addView(mirrorButton, mirrorLp);

        stylePrimaryButton(activity, startButton);
        startButton.setText(startButton.isEnabled() ? "▶  启动游戏" : startButton.getText());
        LinearLayout.LayoutParams startLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 56));
        startLp.topMargin = dp(activity, 12);
        panel.addView(startButton, startLp);

        moduleProgressText.setTextColor(MUTED);
        moduleProgressText.setTextSize(12);
        moduleProgressText.setGravity(Gravity.CENTER);
        moduleProgressText.setPadding(0, dp(activity, 8), 0, dp(activity, 5));
        panel.addView(moduleProgressText, new LinearLayout.LayoutParams(-1, -2));

        if (moduleProgress instanceof ProgressBar) {
            ProgressBar progressBar = (ProgressBar) moduleProgress;
            if (Build.VERSION.SDK_INT >= 21) {
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE_LIGHT));
                progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(BLUE_LIGHT));
            }
        }
        LinearLayout.LayoutParams progressLp =
                new LinearLayout.LayoutParams(-1, dp(activity, 5));
        progressLp.topMargin = dp(activity, 1);
        panel.addView(moduleProgress, progressLp);
    }

    private static void styleHeader(Activity activity, View headerRaw, boolean gamePanel) {
        if (!(headerRaw instanceof LinearLayout)) return;
        LinearLayout header = (LinearLayout) headerRaw;
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView close = null;
        TextView heading = null;
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView t = (TextView) child;
            if ("×".equals(value(t))) close = t;
            else heading = t;
        }

        if (close != null) {
            close.setText("×");
            close.setTextSize(25);
            close.setTextColor(Color.rgb(205, 217, 232));
            close.setGravity(Gravity.CENTER);
            close.setBackground(round(
                    activity,
                    Color.rgb(18, 28, 41),
                    20,
                    Color.rgb(78, 98, 123),
                    1
            ));
            ViewGroup.LayoutParams p = close.getLayoutParams();
            if (p instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) p;
                lp.width = dp(activity, 38);
                lp.height = dp(activity, 38);
                close.setLayoutParams(lp);
            }
        }

        if (heading != null) {
            heading.setText(gamePanel ? "✦  游戏详情  ✦" : "账号中心");
            heading.setTextSize(gamePanel ? 18 : 20);
            heading.setTextColor(TEXT);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setGravity(Gravity.CENTER);
            if (gamePanel) heading.setTextColor(Color.rgb(240, 229, 207));
        }
    }

    private static void styleFallbackPanel(Activity activity, LinearLayout panel) {
        ArrayList<View> all = new ArrayList<>();
        collect(panel, all);
        for (View v : all) {
            if (v instanceof EditText) styleInput(activity, (EditText) v);
            else if (v instanceof Button) styleOutlineButton(activity, (Button) v, false);
            else if (v instanceof TextView) {
                TextView t = (TextView) v;
                if (!"×".equals(value(t))) t.setTextColor(TEXT);
            }
        }
    }

    private static void styleInfoRow(Activity activity, View rowRaw, boolean compact) {
        if (!(rowRaw instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) rowRaw;
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        row.setBackgroundColor(Color.TRANSPARENT);

        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView t = (TextView) child;
            t.setTextSize(compact ? 12.5f : 12.5f);
            t.setTextColor(i == 0 ? MUTED : TEXT);
            if (i > 0) t.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        }
    }

    private static void styleInput(Activity activity, EditText edit) {
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED_2);
        edit.setTextSize(14.5f);
        edit.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        edit.setBackground(round(
                activity,
                Color.rgb(10, 16, 25),
                10,
                Color.rgb(42, 61, 88),
                1
        ));
    }

    private static void styleOutlineButton(Activity activity, Button button, boolean emphasized) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        button.setTextColor(emphasized ? Color.rgb(218, 232, 255) : Color.rgb(221, 229, 240));
        button.setBackground(round(
                activity,
                emphasized ? Color.rgb(24, 47, 87) : Color.rgb(17, 26, 39),
                8,
                emphasized ? Color.rgb(64, 123, 219) : Color.rgb(55, 83, 121),
                1
        ));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(0);
        }
    }

    private static void stylePrimaryButton(Activity activity, Button button) {
        button.setAllCaps(false);
        button.setTextSize(18);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(button.isEnabled() ? Color.WHITE : Color.rgb(142, 157, 177));

        if (button.isEnabled()) {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{BLUE_LIGHT, BLUE, BLUE_DARK}
            );
            bg.setCornerRadius(dp(activity, 9));
            bg.setStroke(dp(activity, 1), Color.rgb(104, 173, 255));
            button.setBackground(bg);
        } else {
            button.setBackground(round(
                    activity,
                    Color.rgb(38, 48, 62),
                    9,
                    Color.rgb(62, 75, 92),
                    1
            ));
        }

        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(dp(activity, 2));
        }
    }

    private static void styleDangerButton(Activity activity, Button button) {
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(RED_TEXT);
        button.setBackground(round(
                activity,
                RED_DARK,
                8,
                RED_BORDER,
                1
        ));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(0);
        }
    }

    private static LinearLayout findPanel(TextView heading) {
        View cursor = heading;
        for (int i = 0; i < 7; i++) {
            if (!(cursor.getParent() instanceof View)) return null;
            cursor = (View) cursor.getParent();
            if (cursor instanceof LinearLayout && cursor.getParent() instanceof FrameLayout) {
                return (LinearLayout) cursor;
            }
        }
        return null;
    }

    private static ArrayList<View> snapshot(ViewGroup group) {
        ArrayList<View> result = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) result.add(group.getChildAt(i));
        return result;
    }

    private static void collect(View view, ArrayList<View> out) {
        out.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
    }

    private static void removeFromParent(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static TextView asText(View view) {
        return view instanceof TextView ? (TextView) view : new TextView(view.getContext());
    }

    private static EditText asEdit(View view) {
        return view instanceof EditText ? (EditText) view : new EditText(view.getContext());
    }

    private static Button asButton(View view) {
        return view instanceof Button ? (Button) view : new Button(view.getContext());
    }

    private static View separator(Activity activity) {
        View line = new View(activity);
        line.setBackgroundColor(Color.rgb(37, 51, 71));
        return line;
    }

    private static String value(TextView view) {
        CharSequence text = view.getText();
        return text == null ? "" : text.toString().trim();
    }

    private static TextView label(
            Activity activity,
            String text,
            float size,
            int color,
            boolean bold
    ) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private static GradientDrawable verticalGradient(int top, int bottom, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom}
        );
        drawable.setCornerRadius(radius);
        return drawable;
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

    private static GradientDrawable oval(int fill, int stroke, int strokePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fill);
        drawable.setStroke(strokePx, stroke);
        return drawable;
    }

    private static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
