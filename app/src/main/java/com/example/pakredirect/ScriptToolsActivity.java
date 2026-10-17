package com.example.pakredirect;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Read-only catalog of reconstructed Lua modules. */
public final class ScriptToolsActivity extends Activity {
    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int SOFT = Color.rgb(34, 39, 48);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final int BORDER = Color.rgb(47, 53, 64);
    private static final String[] MODULES = {
            "脚本/封神榜.lua",
            "脚本/挂机模式.lua",
            "脚本/通用数据.lua",
            "脚本/中控.lua",
            "脚本/tomocr.lua",
            "脚本/TURING_VIP.lua",
            "脚本/w.lua",
            "插件/opPlugin.lua",
            "插件/自定义库.lua"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(24));
        page.addView(root, new ScrollView.LayoutParams(-1, -2));

        Button back = new Button(this);
        back.setText("‹  返回沙箱");
        back.setTextColor(TEXT);
        back.setTextSize(14);
        back.setAllCaps(false);
        back.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        back.setBackground(round(SOFT, 13));
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView title = label("脚本工具", 23, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(18);
        root.addView(title, titleLp);

        TextView description = label(
                "以下为反编译重建的 Lua 模块（共 9 个）。源码文件尚未打包进本 APK，且 Lua 宿主兼容性尚未验证；此页仅展示模块清单，不提供运行功能。",
                13, MUTED, false);
        description.setLineSpacing(dp(3), 1.08f);
        LinearLayout.LayoutParams descriptionLp = new LinearLayout.LayoutParams(-1, -2);
        descriptionLp.topMargin = dp(8);
        descriptionLp.bottomMargin = dp(18);
        root.addView(description, descriptionLp);

        for (String module : MODULES) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(round(CARD, 14));
            card.addView(label(module, 14, TEXT, true));
            TextView kind = label("反编译重建 · 清单展示", 11, MUTED, false);
            kind.setPadding(0, dp(4), 0, 0);
            card.addView(kind);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(8);
            root.addView(card, cardLp);
        }
        setContentView(page);
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), BORDER);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
