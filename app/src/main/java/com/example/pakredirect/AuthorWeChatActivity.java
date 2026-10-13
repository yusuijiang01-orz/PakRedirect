package com.example.pakredirect;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Displays the author's supplied WeChat contact QR without changing payment behavior. */
public final class AuthorWeChatActivity extends Activity {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final Uri CONTACT_URI = Uri.parse(
            "https://u.wechat.com/EEeplu6T83ZNN3UjU-iiWkI?s=2");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(RyluxUiPolish.BG_TOP);
        getWindow().setNavigationBarColor(RyluxUiPolish.BG_BOTTOM);
        getWindow().getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(RyluxUiPolish.verticalGradient(
                RyluxUiPolish.BG_TOP, RyluxUiPolish.BG_BOTTOM, 0));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(20), dp(22), dp(32));
        scroll.addView(content);
        setContentView(scroll);

        TextView back = label(content, "←  返回充值页", 14, RyluxUiPolish.MUTED, false);
        back.setPadding(0, dp(4), 0, dp(20));
        back.setOnClickListener(v -> finish());
        TextView brand = label(content, "RYLUX  /  微信联系", 12, RyluxUiPolish.BLUE, true);
        brand.setLetterSpacing(0.12f);
        TextView title = label(content, "添加作者微信", 27, RyluxUiPolish.TEXT, true);
        LinearLayout.LayoutParams titleLp = (LinearLayout.LayoutParams) title.getLayoutParams();
        titleLp.topMargin = dp(12);
        title.setLayoutParams(titleLp);
        TextView intro = label(content,
                "打开微信添加作者，或扫描下方二维码。添加好友不会自动开通 VIP。",
                14, RyluxUiPolish.MUTED, false);
        intro.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams introLp = (LinearLayout.LayoutParams) intro.getLayoutParams();
        introLp.topMargin = dp(10);
        intro.setLayoutParams(introLp);

        Button openWeChat = new Button(this);
        openWeChat.setText("在微信中添加作者  ›");
        openWeChat.setGravity(Gravity.CENTER);
        openWeChat.setMinimumHeight(0);
        openWeChat.setMinHeight(0);
        RyluxUiPolish.stylePrimaryButton(this, openWeChat);
        openWeChat.setTextSize(16);
        openWeChat.setOnClickListener(v -> openContact());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(-1, dp(54));
        buttonLp.topMargin = dp(24);
        content.addView(openWeChat, buttonLp);

        TextView qrTitle = label(content, "微信二维码", 16, RyluxUiPolish.TEXT, true);
        LinearLayout.LayoutParams qrTitleLp = (LinearLayout.LayoutParams) qrTitle.getLayoutParams();
        qrTitleLp.topMargin = dp(30);
        qrTitle.setLayoutParams(qrTitleLp);
        TextView qrHint = label(content,
                "用另一台设备扫码；也可截图后在微信扫一扫中从相册识别。",
                13, RyluxUiPolish.MUTED, false);
        qrHint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams qrHintLp = (LinearLayout.LayoutParams) qrHint.getLayoutParams();
        qrHintLp.topMargin = dp(7);
        qrHint.setLayoutParams(qrHintLp);

        LinearLayout qrCard = new LinearLayout(this);
        qrCard.setOrientation(LinearLayout.VERTICAL);
        qrCard.setPadding(dp(10), dp(10), dp(10), dp(10));
        qrCard.setBackground(RyluxUiPolish.round(this, Color.WHITE, 16,
                RyluxUiPolish.BORDER, 1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.topMargin = dp(16);
        content.addView(qrCard, cardLp);
        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.author_wechat_qr);
        qr.setAdjustViewBounds(true);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription("作者微信二维码，可使用微信扫一扫添加好友");
        qrCard.addView(qr, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView label(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void openContact() {
        PackageManager packages = getPackageManager();
        try {
            packages.getPackageInfo(WECHAT_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException error) {
            Toast.makeText(this, "未安装微信，请安装后使用二维码添加作者", Toast.LENGTH_LONG).show();
            return;
        }

        Intent link = new Intent(Intent.ACTION_VIEW, CONTACT_URI);
        link.addCategory(Intent.CATEGORY_BROWSABLE);
        link.setPackage(WECHAT_PACKAGE);
        try {
            startActivity(link);
            return;
        } catch (ActivityNotFoundException | SecurityException error) {
            // Some WeChat versions do not register the contact HTTPS link.
        }

        Intent launch = packages.getLaunchIntentForPackage(WECHAT_PACKAGE);
        if (launch != null) {
            try {
                startActivity(launch);
                Toast.makeText(this, "微信无法直接打开此名片，请截图二维码后在扫一扫中识别",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException | SecurityException error) {
                // Keep the QR visible when the installed app cannot be launched.
            }
        }
        Toast.makeText(this, "无法打开微信，请使用二维码添加作者", Toast.LENGTH_LONG).show();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
