package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;

/** Configuration workbench only. There is no script runtime or game input path here. */
public final class ScriptToolsActivity extends Activity {
    static final String EXTRA_SHOW_RUNTIME_DIAGNOSTIC = "show_runtime_diagnostic";
    static final String EXTRA_REQUEST_START = "request_confirmed_runtime_start";
    private static final String RUNTIME_DIAGNOSTICS = "script_runtime_diagnostics";
    private static final String GAME = "com.tepaylink.tamgioiphantranhmobile";
    private static final String SCRIPT = "脚本/封神榜.lua";
    private static final String[] REQUIRED_ASSETS = {
            SCRIPT, "脚本/挂机模式.lua", "脚本/通用数据.lua", "脚本/中控.lua",
            "脚本/tomocr.lua", "脚本/TURING_VIP.lua", "脚本/w.lua",
            "插件/opPlugin.lua", "插件/自定义库.lua", "脚本/空格子0.png"
    };
    // Recovered map order, shown using the Chinese names used by the localized client.
    private static final String[] MAPS = {
            "崇城野外", "北海", "燕山", "西昆仑", "昆仑山", "首阳山", "游魂关", "苗疆", "巨鹿",
            "潼关", "孟津", "城塘关", "荒漠", "荒漠2", "荒漠3", "荒漠4", "荒漠5",
            "海1", "海2", "海3", "海4", "海5", "火1", "火2", "火3", "火4", "火5",
            "冰1", "冰2", "冰3", "冰4", "冰5", "捆仙宫1", "捆仙宫2", "捆仙宫3",
            "捆仙宫4", "捆仙宫5", "碧游宫1"
    };
    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int SOFT = Color.rgb(34, 39, 48);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final int BLUE = Color.rgb(72, 113, 255);
    private static final int BORDER = Color.rgb(47, 53, 64);

    private SharedPreferences preferences;
    private JSONObject values;
    private String profileName;
    private LinearLayout root;
    private TextView environment;
    private TextView accessibilityState;
    private int selectedTab;
    private final ArrayList<ScrollView> tabPages = new ArrayList<>();
    private final ArrayList<Button> tabButtons = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("script_workbench_profiles_v1", MODE_PRIVATE);
        profileName = preferences.getString("selected", "默认方案");
        loadProfile();
        buildUi();
        if (getIntent().getBooleanExtra(EXTRA_SHOW_RUNTIME_DIAGNOSTIC, false)) showRuntimeDiagnostic();
        else if (getIntent().getBooleanExtra(EXTRA_REQUEST_START, false)) confirmAndStart();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SHOW_RUNTIME_DIAGNOSTIC, false)) showRuntimeDiagnostic();
        else if (intent.getBooleanExtra(EXTRA_REQUEST_START, false)) {
            selectTab(0);
            confirmAndStart();
        }
    }

    private void showRuntimeDiagnostic() {
        String detail = getSharedPreferences(RUNTIME_DIAGNOSTICS, MODE_PRIVATE)
                .getString("last_error", "尚无脚本失败诊断记录。下次失败后，点击悬浮窗顶部状态查看完整错误。" );
        TextView content = label(detail, 12, TEXT, false);
        content.setTextIsSelectable(true);
        content.setTypeface(Typeface.MONOSPACE);
        content.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("脚本运行诊断").setView(scroll)
                .setPositiveButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("RYLUX Lua diagnostic", detail));
                    Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (environment != null) environment.setText(environmentText());
        if (accessibilityState != null) accessibilityState.setText(accessibilityStatus());
    }

    private void buildUi() {
        tabPages.clear();
        tabButtons.clear();
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(12), dp(18), dp(8));
        screen.addView(header, new LinearLayout.LayoutParams(-1, -2));
        Button back = button("‹  返回沙箱", SOFT);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(112), dp(42)));
        TextView title = label("脚本工作台", 21, TEXT, true);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button settings = button("⚙", SOFT);
        settings.setTextSize(24);
        settings.setContentDescription("设置");
        settings.setOnClickListener(v -> showSettings());
        header.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(42)));

        TextView subtitle = label("封神榜配置 · 打开页面不会启动游戏或脚本", 12, MUTED, false);
        subtitle.setPadding(dp(18), 0, dp(18), dp(8));
        screen.addView(subtitle);

        HorizontalScrollView tabs = new HorizontalScrollView(this);
        tabs.setHorizontalScrollBarEnabled(false);
        tabs.setFillViewport(false);
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(dp(14), dp(4), dp(14), dp(8));
        tabs.addView(tabRow);
        screen.addView(tabs, new LinearLayout.LayoutParams(-1, dp(54)));

        FrameLayout content = new FrameLayout(this);
        screen.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        String[] names = {"方案", "路线", "战斗", "背包", "队伍", "交易"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button tab = button(names[i], SOFT);
            tab.setOnClickListener(v -> selectTab(index));
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(-2, dp(40));
            tabLp.leftMargin = dp(4);
            tabLp.rightMargin = dp(4);
            tabRow.addView(tab, tabLp);
            tabButtons.add(tab);

            ScrollView page = new ScrollView(this);
            page.setFillViewport(true);
            page.setVisibility(View.GONE);
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(18), dp(4), dp(18), dp(28));
            page.addView(body, new ScrollView.LayoutParams(-1, -2));
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
            tabPages.add(page);
        }

        root = (LinearLayout) tabPages.get(0).getChildAt(0);

        LinearLayout profiles = section("配置方案", "本机自动保存当前方案，支持另存和切换。");
        profiles.addView(label("当前方案  " + profileName, 15, TEXT, true));
        Button choose = button("切换方案", SOFT);
        choose.setOnClickListener(v -> chooseProfile());
        addButton(profiles, choose);
        Button saveAs = button("另存为命名方案", BLUE);
        saveAs.setOnClickListener(v -> saveAsProfile());
        addButton(profiles, saveAs);

        LinearLayout run = section("运行与状态", "先检查运行条件，再确认本次方案。开始后会启动沙箱中的封神榜并显示 RYLUX 悬浮控制面板。");
        Button preflight = button("查看配置与运行前检查", BLUE);
        preflight.setOnClickListener(v -> showPreflight());
        addButton(run, preflight);
        Button previewOverlay = button("预览 RYLUX 脚本悬浮窗", SOFT);
        previewOverlay.setOnClickListener(v -> showOverlayPreview());
        addButton(run, previewOverlay);
        run.addView(label("运行宿主使用 LuaJ、系统截图与触控；旧脚本的不兼容接口会在运行前检查。", 12, MUTED, false));
        Button start = button("配置方案并启动", BLUE);
        start.setOnClickListener(v -> confirmAndStart());
        addButton(run, start);

        root = (LinearLayout) tabPages.get(1).getChildAt(0);
        LinearLayout route = section("挂机与路线", "面向汉化版输入中文地图名，也支持搜索越南语旧名。");
        addMap(route, "挂机点地图");
        addNumber(route, "挂机点x", "挂机点 X 坐标", "100", "坐标", 0, 99999);
        addNumber(route, "挂机点y", "挂机点 Y 坐标", "200", "坐标", 0, 99999);
        addNumber(route, "挂机不移动秒", "无移动后返回挂机点", "60", "秒", 1, 86400);
        addNumber(route, "队员找不到队长组队就回家次数", "找不到队长后返回", "2", "次", 1, 999);

        root = (LinearLayout) tabPages.get(2).getChildAt(0);
        LinearLayout supplies = section("战斗与补给", "开关和阈值仅写入本机方案；运行宿主接入前不会触发游戏操作。");
        addSwitch(supplies, "自动战斗", "自动战斗", false);
        addNumber(supplies, "战斗技能阈值", "技能触发阈值", "15", "%", 0, 100);
        addNumber(supplies, "装备耐久阈值", "装备耐久低于此值时处理", "15", "%", 0, 100);
        addSwitch(supplies, "自动使用红药", "生命低于阈值时使用红药", false);
        addNumber(supplies, "生命药触发阈值", "红药触发阈值", "30", "%", 0, 100);
        addSwitch(supplies, "自动使用蓝药", "法力低于阈值时使用蓝药", false);
        addNumber(supplies, "法力药触发阈值", "蓝药触发阈值", "20", "%", 0, 100);
        addSwitch(supplies, "出售全部红", "红药超过阈值时出售", false);
        addNumber(supplies, "红的数量高于", "红药出售阈值", "10", "个", 0, 99999);
        addSwitch(supplies, "出售全部蓝", "蓝药超过阈值时出售", false);
        addNumber(supplies, "蓝的数量高于", "蓝药出售阈值", "10", "个", 0, 99999);
        addSwitch(supplies, "购买小红", "红药不足时购买小红", false);
        addNumber(supplies, "红的数量低于", "红药购买阈值", "10", "个", 0, 99999);
        addNumber(supplies, "购买红的数量", "每次购买红药", "10", "个", 1, 99999);
        addSwitch(supplies, "购买小蓝", "蓝药不足时购买小蓝", false);
        addNumber(supplies, "蓝的数量低于", "蓝药购买阈值", "10", "个", 0, 99999);
        addNumber(supplies, "购买蓝的数量", "每次购买蓝药", "10", "个", 1, 99999);

        root = (LinearLayout) tabPages.get(3).getChildAt(0);
        LinearLayout inventory = section("背包与装备", "装备出售默认关闭；只有显式启用并设置出售品质后才进入旧脚本出售流程，设备端仍待校准。");
        addSwitch(inventory, "保护装备不出售", "保护装备不出售（建议开启）", true);
        addSwitch(inventory, "检测修理装备", "检测并修理装备", true);
        addNumber(inventory, "背包保留重量", "保留背包重量", "3", "重量", 0, 99999);
        addNumber(inventory, "背包保留格子", "保留空格", "2", "格", 0, 99999);
        addNumber(inventory, "背包检测间隔秒", "背包检测间隔", "60", "秒", 1, 86400);
        addSwitch(inventory, "出售装备", "允许出售装备", false);
        addChoice(inventory, "出售装备品质上限", "装备出售保护等级", new String[]{"不出售装备", "仅白色", "白色与绿色"}, 0);
        addSwitch(inventory, "出售装备二次确认", "每次出售装备前再次确认", true);
        addNumber(inventory, "保留蓝条数量", "保留蓝条数量", "4", "条", 0, 99999);
        addChoice(inventory, "蓝条筛选模式", "蓝条筛选条件（单选）",
                new String[]{"不筛选", "回蓝 ≥", "魔法出招 ≥", "武器出招 ≥"}, 1);
        addNumber(inventory, "保留回蓝数值", "回蓝最低值", "10", "点", 0, 99999);
        addNumber(inventory, "保留魔法出招数值", "魔法出招最低值", "15", "点", 0, 99999);
        addNumber(inventory, "保留武器出招数值", "武器出招最低值", "15", "点", 0, 99999);

        root = (LinearLayout) tabPages.get(4).getChildAt(0);
        LinearLayout party = section("队伍", "队长、队员和不组队互斥；名称可按行填写多个。");
        addChoice(party, "是否队长", "组队角色", new String[]{"队长", "队员", "不组队"}, 0);
        addText(party, "队长名", "队长名（每行一个）", true);
        addText(party, "队员名", "队员名（每行一个）", true);
        addNumber(party, "检测队长名字频率秒", "检测队长名字间隔", "180", "秒", 1, 86400);
        addNumber(party, "检测是否有队伍频率秒", "检测队伍间隔", "180", "秒", 1, 86400);

        root = (LinearLayout) tabPages.get(5).getChildAt(0);
        LinearLayout trade = section("交易", "普通物品须同时命中出售开关、名称清单和数量阈值；OCR 不确定时保留物品。商店页仍需设备校准。");
        addMap(trade, "卖货地图");
        addSwitch(trade, "允许出售物品", "允许出售普通物品", false);
        addText(trade, "出售物品清单", "出售物品名称（每行一个）", true);
        addNumber(trade, "出售物品数量阈值", "物品数量超过阈值才出售", "10", "个", 1, 99999);
        addSwitch(trade, "允许购买物品", "允许购买补给物品", false);
        addText(trade, "购买物品清单", "购买物品名称（每行一个）", true);
        addNumber(trade, "购买物品数量阈值", "物品数量低于阈值才购买", "10", "个", 0, 99999);
        addNumber(trade, "每次购买数量", "每次购买数量", "10", "个", 1, 99999);

        setContentView(screen);
        selectTab(Math.max(0, Math.min(tabPages.size() - 1, selectedTab)));
    }

    private void selectTab(int index) {
        selectedTab = index;
        for (int i = 0; i < tabPages.size(); i++) {
            boolean active = i == index;
            tabPages.get(i).setVisibility(active ? View.VISIBLE : View.GONE);
            Button tab = tabButtons.get(i);
            tab.setBackground(round(active ? BLUE : SOFT, 12));
            tab.setTextColor(active ? Color.WHITE : MUTED);
            tab.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            tab.setSelected(active);
        }
    }

    private void showSettings() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(BG);
        LinearLayout previousRoot = root;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(4), dp(18), dp(28));
        page.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout selection = section("目标游戏", "目前只有一个已识别的目标游戏。");
        selection.addView(label("封神榜 · " + GAME, 13, TEXT, true));
        selection.addView(label(gameStatus(), 12, MUTED, false));
        Button gamePicker = button("选择目标游戏", SOFT);
        gamePicker.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("目标游戏")
                .setSingleChoiceItems(new String[]{"封神榜 · " + GAME}, 0, (dialog, which) -> dialog.dismiss())
                .setNegativeButton("关闭", null).show());
        addButton(selection, gamePicker);

        LinearLayout advanced = section("运行环境与诊断", "权限仍由你在系统页面手动开启；进入工作台不会启动游戏或脚本。");
        environment = label(environmentText(), 12, MUTED, false);
        advanced.addView(environment);
        accessibilityState = label(accessibilityStatus(), 13, TEXT, true);
        accessibilityState.setPadding(0, dp(10), 0, 0);
        advanced.addView(accessibilityState);
        Button enableAccessibility = button("打开无障碍服务设置", SOFT);
        enableAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        addButton(advanced, enableAccessibility);
        addChoice(advanced, "引擎模式", "引擎模式（首次运行选择）",
                new String[]{"手动截图与无障碍", "Root", "无障碍服务"}, 0);
        advanced.addView(label("Android 11 及以上通过无障碍服务读取画面；此权限必须由你在系统设置中启用。", 12, MUTED, false));

        LinearLayout options = section("高级设置", "调试与虚拟屏幕偏好仅供记录。");
        addText(options, "远程调试主机", "远程调试主机", false);
        addNumber(options, "远程调试端口", "远程调试端口", "10192", "端口", 1, 65535);
        addSwitch(options, "自动保存调试日志", "自动保存调试日志", true);
        addSwitch(options, "日志行号文件名", "日志包含行号和文件名", true);
        addNumber(options, "虚拟屏幕宽度", "虚拟屏幕宽度", "720", "像素", 1, 16384);
        addNumber(options, "虚拟屏幕高度", "虚拟屏幕高度", "1280", "像素", 1, 16384);
        addNumber(options, "虚拟屏幕DPI", "虚拟屏幕 DPI", "320", "DPI", 1, 2000);
        addText(options, "手机编号", "手机编号", false);
        addSwitch(options, "开启vpn", "原脚本 VPN 偏好", false);

        root = previousRoot;
        new AlertDialog.Builder(this).setTitle("设置").setView(page)
                .setPositiveButton("完成", null).show();
    }

    private void showOverlayPreview() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("需要悬浮窗权限")
                    .setMessage("启用权限后才能显示脚本控制悬浮窗。预览只显示界面，不会启动游戏或脚本。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, 417);
                    }).show();
            return;
        }
        startOverlayPreviewService();
    }

    private void startOverlayPreviewService() {
        Intent intent = new Intent(this, ScriptControlOverlayService.class)
                .setAction(ScriptControlOverlayService.ACTION_PREVIEW);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "悬浮窗预览已打开；预览不会启动游戏或脚本", Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 417 && Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
            startOverlayPreviewService();
        }
    }

    private LinearLayout section(String title, String description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(CARD, 14));
        card.addView(label(title, 16, TEXT, true));
        TextView note = label(description, 12, MUTED, false);
        note.setPadding(0, dp(5), 0, dp(12));
        card.addView(note);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        root.addView(card, lp);
        return card;
    }

    private void addMap(LinearLayout parent, String key) {
        parent.addView(fieldLabel(key));
        Button picker = button(mapName(values.optInt(key, 0)), SOFT);
        picker.setOnClickListener(v -> chooseMap(key, picker));
        addButton(parent, picker);
    }

    private void chooseMap(String key, Button picker) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(10), dp(16), 0);
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索中文地图名或越南语旧名");
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        panel.addView(search);
        ListView list = new ListView(this);
        ArrayList<String> visible = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, visible);
        list.setAdapter(adapter);
        panel.addView(list, new LinearLayout.LayoutParams(-1, dp(380)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(key).setView(panel)
                .setNegativeButton("取消", null).create();
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for (String map : MAPS) if (map.toLowerCase(Locale.ROOT).contains(query)) visible.add(map);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(watcher(filter));
        filter.run();
        list.setOnItemClickListener((listView, view, position, id) -> {
            int index = Arrays.asList(MAPS).indexOf(visible.get(position));
            put(key, index);
            picker.setText(mapName(index));
            dialog.dismiss();
        });
        dialog.show();
    }

    private void addNumber(LinearLayout parent, String key, String title, String fallback,
                           String unit, int min, int max) {
        parent.addView(fieldLabel(title + " · " + unit));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("−", SOFT);
        row.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(44)));
        EditText input = field(values.optString(key, fallback), false);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        row.addView(input, lp);
        Button plus = button("+", SOFT);
        row.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(44)));
        minus.setOnClickListener(v -> step(input, -1, min, max));
        plus.setOnClickListener(v -> step(input, 1, min, max));
        input.addTextChangedListener(watcher(() -> put(key, input.getText().toString())));
        input.setOnFocusChangeListener((v, focused) -> {
            if (!focused) input.setText(String.valueOf(Math.max(min,
                    Math.min(max, parseNumber(input.getText().toString(), min)))));
        });
        parent.addView(row);
    }

    private void step(EditText input, int delta, int min, int max) {
        int value = parseNumber(input.getText().toString(), min);
        input.setText(String.valueOf(Math.max(min, Math.min(max, value + delta))));
    }

    private void addText(LinearLayout parent, String key, String title, boolean multiline) {
        parent.addView(fieldLabel(title));
        EditText input = field(values.optString(key, ""), multiline);
        input.setHint(title);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(300)});
        input.addTextChangedListener(watcher(() -> put(key, input.getText().toString())));
        parent.addView(input, new LinearLayout.LayoutParams(-1, multiline ? dp(86) : dp(46)));
    }

    private void addSwitch(LinearLayout parent, String key, String title, boolean fallback) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(TEXT);
        control.setTextSize(13);
        control.setPadding(0, dp(8), 0, dp(8));
        control.setChecked(values.optBoolean(key, fallback));
        control.setOnCheckedChangeListener((button, checked) -> {
            if ("保护装备不出售".equals(key) && !checked) {
                new AlertDialog.Builder(this).setTitle("关闭装备保护？")
                        .setMessage("这只会记录偏好。回收脚本和未来适配器尚未验证，关闭后可能增加误售风险。")
                        .setNegativeButton("保持保护", (dialog, which) -> control.setChecked(true))
                        .setPositiveButton("仍要关闭", (dialog, which) -> put(key, false))
                        .setOnCancelListener(dialog -> control.setChecked(true)).show();
            } else put(key, checked);
        });
        parent.addView(control);
    }

    private void addChoice(LinearLayout parent, String key, String title, String[] options, int fallback) {
        parent.addView(fieldLabel(title));
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        int selected = values.optInt(key, fallback);
        for (int i = 0; i < options.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(android.view.View.generateViewId());
            choice.setText(options[i]);
            choice.setTextColor(TEXT);
            choice.setTextSize(13);
            group.addView(choice);
            if (i == selected) choice.setChecked(true);
            final int index = i;
            choice.setOnClickListener(v -> put(key, index));
        }
        parent.addView(group);
    }

    private String environmentText() {
        return "游戏环境：" + gameStatus()
                + "\nLua 运行宿主：LuaJ 已接入，旧 API 兼容检查中\n截图/OCR/触控：" + accessibilityStatus()
                + "\nOCR 使用本地 ML Kit 替代，尚未达到旧 TURING 字库的识别一致性";
    }

    private String accessibilityStatus() {
        if (Build.VERSION.SDK_INT < 30) return "系统版本不支持无障碍截图（需要 Android 11+）";
        return isAccessibilityServiceEnabled() ? "游戏输入服务：已启用" : "游戏输入服务：未启用";
    }

    private boolean isAccessibilityServiceEnabled() {
        if (Build.VERSION.SDK_INT < 30) return false;
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String expected = new ComponentName(this, ScriptGameAdapter.class).flattenToString();
        return enabled != null && enabled.toLowerCase(Locale.ROOT)
                .contains(expected.toLowerCase(Locale.ROOT));
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开系统无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    private String gameStatus() {
        RyluxApplication app = (RyluxApplication) getApplication();
        if (!app.isBlackBoxReady()) return "沙箱未就绪";
        try {
            for (ApplicationInfo info : BlackBoxCore.get().getInstalledApplications(0, 0)) {
                if (info != null && GAME.equals(info.packageName)) return "目标游戏已导入沙箱";
            }
            return "目标游戏尚未导入沙箱";
        } catch (Throwable error) {
            return "无法读取沙箱游戏状态";
        }
    }

    private void showPreflight() {
        String[] roles = {"队长", "队员", "不组队"};
        String[] modes = {"不筛选", "回蓝 ≥", "魔法出招 ≥", "武器出招 ≥"};
        String summary = "方案：" + profileName + "\n游戏：" + GAME + "\n脚本：封神榜"
                + "\n挂机点：" + mapName(values.optInt("挂机点地图", 0))
                + " (" + values.optString("挂机点x", "100") + ", " + values.optString("挂机点y", "200") + ")"
                + "\n卖货地图：" + mapName(values.optInt("卖货地图", 0))
                + "\n自动战斗：" + onOff("自动战斗")
                + " · 装备耐久阈值：" + values.optString("装备耐久阈值", "15") + "%"
                + "\n队伍：" + roles[Math.max(0, Math.min(2, values.optInt("是否队长", 0)))]
                + "\n蓝条筛选：" + modes[Math.max(0, Math.min(3, values.optInt("蓝条筛选模式", 1)))]
                + "\n补给：买小红 " + (values.optBoolean("购买小红", false) ? "开" : "关")
                + " / 买小蓝 " + (values.optBoolean("购买小蓝", false) ? "开" : "关")
                + "\n背包：保留 " + values.optString("背包保留格子", "2") + " 格、"
                + values.optString("背包保留重量", "3") + " 重量"
                + "\n装备保护：" + (values.optBoolean("保护装备不出售", true) ? "开启" : "关闭")
                + "\n出售红药 / 蓝药：" + (values.optBoolean("出售全部红", false) ? "开" : "关")
                + " / " + (values.optBoolean("出售全部蓝", false) ? "开" : "关")
                + "\n出售装备：" + onOff("出售装备")
                + " · 二次确认：" + onOff("出售装备二次确认")
                + "\n交易物品清单：出售 " + values.optString("出售物品清单", "（未设置）").replace("\n", "、")
                + "；购买 " + values.optString("购买物品清单", "（未设置）").replace("\n", "、")
                + "\n\n游戏检查：" + gameStatus()
                + "\n脚本与模板资源：" + (assetsComplete()
                        ? REQUIRED_ASSETS.length + "/" + REQUIRED_ASSETS.length + " 已打包" : "资源不完整")
                + "\n配置检查：" + configurationIssues()
                + "\nLua 运行宿主：LuaJ 已接入\n游戏输入适配器：" + accessibilityStatus()
                + "\n兼容说明：OCR 使用 ML Kit 替代旧字库；系统命令仅开放 /proc/uptime；文本输入依赖游戏提供可访问输入框"
                + "\n脚本兼容阻塞项：" + scriptCompatibilityBlockers()
                + "\n悬浮窗权限：" + (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this) ? "已就绪" : "未开启")
                + "\n\n检查通过后才会开放开始操作。";
        boolean canOpenAccessibilitySettings = Build.VERSION.SDK_INT >= 30 && !isAccessibilityServiceEnabled();
        showRyluxDialog("运行前检查", summary, null,
                canOpenAccessibilitySettings ? "打开无障碍设置" : "知道了",
                canOpenAccessibilitySettings ? this::openAccessibilitySettings : null);
    }

    private void confirmAndStart() {
        String blockers = scriptCompatibilityBlockers();
        if (!blockers.isEmpty() || !ScriptGameAdapter.isConnected()
                || gameStatus().contains("尚未导入") || gameStatus().contains("未就绪")
                || (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this))) {
            showPreflight();
            return;
        }
        String summary = "方案：" + profileName + "\n目标：封神榜（汉化版）\n运行模式：无障碍截图与触控"
                + "\n路线：" + mapName(values.optInt("挂机点地图", 0))
                + "（" + values.optString("挂机点x", "100") + ", " + values.optString("挂机点y", "200") + "）"
                + "\n自动战斗：" + onOff("自动战斗")
                + "\n装备出售：" + onOff("出售装备") + "；装备保护：" + onOff("保护装备不出售")
                + "\n启动目标：RYLUX 沙箱内的封神榜"
                + "\n\n点击开始后会打开沙箱游戏并运行脚本，随后显示可暂停/停止的 RYLUX 悬浮控制面板。";
        showRyluxDialog("确认本次运行", summary, "返回检查", "开始", this::startRuntime);
    }

    private void startRuntime() {
        Toast.makeText(this, "正在准备汉化并启动沙箱游戏…", Toast.LENGTH_SHORT).show();
        // Use the same localization readiness gate as the sandbox launch flow
        // before starting the script host or routing this package to BlackBoxCore.
        new Thread(this::prepareLocalizationThenStartRuntime, "RYLUX-Script-Launch").start();
    }

    private void prepareLocalizationThenStartRuntime() {
        try {
            LocalizationLaunchPreparation.prepare(this, message -> runOnUiThread(
                    () -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()));
            runOnUiThread(this::startRuntimeServicesAndLaunchGame);
        } catch (Exception error) {
            failRuntimeLaunch(error.getMessage());
        }
    }

    private void startRuntimeServicesAndLaunchGame() {
        // Start the host and overlay while this activity is still foreground. The
        // sandbox game must be launched last: finishing this activity after
        // launch returns Android to the launcher and moves BlackBox's proxy task
        // behind it, which makes both the game and the foreground gate fail.
        try {
            if (Build.VERSION.SDK_INT >= 23 && Settings.canDrawOverlays(this)) {
                Intent overlay = new Intent(this, ScriptControlOverlayService.class)
                        .setAction(ScriptControlOverlayService.ACTION_RUNTIME);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlay);
                else startService(overlay);
            }
            Intent command = new Intent(this, ScriptRuntimeService.class)
                    .setAction(ScriptRuntimeService.ACTION_START)
                    .putExtra(ScriptRuntimeService.EXTRA_CONFIG, values.toString());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(command);
            else startService(command);

            // BlackBoxCore routes this launch to the virtualized package.
            if (!BlackBoxCore.get().launchApk(GAME, 0)) {
                failRuntimeLaunch("RYLUX 无法打开沙箱中的封神榜。请返回沙箱页检查应用状态。");
                return;
            }
            Toast.makeText(this,
                    "汉化已就绪，沙箱游戏和脚本已启动；可用 RYLUX 悬浮面板控制",
                    Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            failRuntimeLaunch(error.getMessage());
        }
    }

    private void failRuntimeLaunch(String message) {
        runOnUiThread(() -> {
            stopPendingRuntime();
            showRyluxDialog("沙箱游戏启动失败", message == null ? "RYLUX 无法启动沙箱游戏。" : message,
                    null, "知道了", null);
        });
    }

    private void stopPendingRuntime() {
        Intent stop = new Intent(this, ScriptRuntimeService.class)
                .setAction(ScriptRuntimeService.ACTION_STOP);
        try { startService(stop); } catch (RuntimeException ignored) { }
        stopService(new Intent(this, ScriptControlOverlayService.class));
    }

    private void showRyluxDialog(String title, String message, String secondaryLabel,
                                 String primaryLabel, Runnable primaryAction) {
        Dialog dialog = new Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        card.setBackground(round(CARD, 18));
        TextView heading = label(title, 18, TEXT, true);
        heading.setPadding(0, 0, 0, dp(10));
        card.addView(heading);
        ScrollView scroll = new ScrollView(this);
        TextView details = label(message, 14, TEXT, false);
        details.setLineSpacing(dp(3), 1f);
        scroll.addView(details, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        scrollLp.bottomMargin = dp(16);
        card.addView(scroll, scrollLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (secondaryLabel != null) {
            Button secondary = button(secondaryLabel, SOFT);
            actions.addView(secondary, new LinearLayout.LayoutParams(0, dp(44), 1));
            LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(0, dp(44), 1);
            primaryLp.leftMargin = dp(8);
            Button primary = button(primaryLabel, BLUE);
            primary.setOnClickListener(v -> {
                dialog.dismiss();
                if (primaryAction != null) primaryAction.run();
            });
            secondary.setOnClickListener(v -> dialog.dismiss());
            actions.addView(primary, primaryLp);
        } else {
            Button primary = button(primaryLabel, BLUE);
            primary.setOnClickListener(v -> {
                dialog.dismiss();
                if (primaryAction != null) primaryAction.run();
            });
            actions.addView(primary, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        card.addView(actions);
        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int maxWidth = dp(520);
            int screenWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
            window.setLayout(Math.min(maxWidth, screenWidth), -2);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
        if (window != null) {
            int maxWidth = dp(520);
            int screenWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
            window.setLayout(Math.min(maxWidth, screenWidth), -2);
        }
    }

    private String scriptCompatibilityBlockers() {
        return String.join("；", LegacyLuaRuntime.compatibilityBlockers(this));
    }

    private String onOff(String key) {
        return values.optBoolean(key, false) ? "开启" : "关闭";
    }

    private String configurationIssues() {
        String[][] numbers = {
                {"挂机点x", "100", "0", "99999"}, {"挂机点y", "200", "0", "99999"},
                {"挂机不移动秒", "60", "1", "86400"}, {"背包检测间隔秒", "60", "1", "86400"},
                {"背包保留格子", "2", "0", "99999"}, {"背包保留重量", "3", "0", "99999"}
        };
        StringBuilder invalid = new StringBuilder();
        for (String[] item : numbers) {
            int value = parseNumber(values.optString(item[0], item[1]), -1);
            if (value < Integer.parseInt(item[2]) || value > Integer.parseInt(item[3])) {
                if (invalid.length() > 0) invalid.append("、");
                invalid.append(item[0]);
            }
        }
        return invalid.length() == 0 ? "基本数值有效" : "请检查 " + invalid;
    }

    private boolean assetsComplete() {
        for (String path : REQUIRED_ASSETS) {
            try (InputStream ignored = getAssets().open("script_modules/" + path)) { }
            catch (IOException error) { return false; }
        }
        return true;
    }

    private void chooseProfile() {
        JSONArray names = profileNames();
        String[] items = new String[names.length()];
        for (int i = 0; i < items.length; i++) items[i] = names.optString(i);
        new AlertDialog.Builder(this).setTitle("切换配置方案")
                .setItems(items, (dialog, which) -> {
                    profileName = items[which];
                    preferences.edit().putString("selected", profileName).apply();
                    loadProfile();
                    buildUi();
                }).setNegativeButton("取消", null).show();
    }

    private void saveAsProfile() {
        EditText input = field("", false);
        input.setHint("输入方案名称");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp(20), 0, dp(20), 0);
        container.addView(input, new LinearLayout.LayoutParams(-1, dp(54)));
        new AlertDialog.Builder(this).setTitle("另存为命名方案").setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "方案名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    JSONArray names = profileNames();
                    for (int i = 0; i < names.length(); i++) {
                        if (name.equals(names.optString(i))) {
                            Toast.makeText(this, "同名方案已存在", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    names.put(name);
                    profileName = name;
                    preferences.edit().putString("names", names.toString())
                            .putString("selected", name).putString("profile:" + name, values.toString()).apply();
                    buildUi();
                }).show();
    }

    private JSONArray profileNames() {
        try {
            JSONArray names = new JSONArray(preferences.getString("names", "[\"默认方案\"]"));
            if (names.length() > 0) return names;
        } catch (JSONException ignored) { }
        return new JSONArray().put("默认方案");
    }

    private void loadProfile() {
        try { values = new JSONObject(preferences.getString("profile:" + profileName, "{}")); }
        catch (JSONException ignored) { values = new JSONObject(); }
    }

    private void put(String key, Object value) {
        try {
            values.put(key, value);
            preferences.edit().putString("profile:" + profileName, values.toString()).apply();
        } catch (JSONException ignored) {
            Toast.makeText(this, "方案保存失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String mapName(int index) {
        return MAPS[Math.max(0, Math.min(MAPS.length - 1, index))];
    }

    private static int parseNumber(String raw, int fallback) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable s) { }
        };
    }

    private EditText field(String value, boolean multiline) {
        EditText view = new EditText(this);
        view.setText(value);
        view.setTextColor(TEXT);
        view.setHintTextColor(MUTED);
        view.setTextSize(14);
        view.setSingleLine(!multiline);
        view.setBackground(round(SOFT, 10));
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        return view;
    }

    private TextView fieldLabel(String title) {
        TextView view = label(title, 13, TEXT, true);
        view.setPadding(0, dp(10), 0, dp(5));
        return view;
    }

    private void addButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(8);
        parent.addView(button, lp);
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text, int color) {
        Button view = new Button(this);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(13);
        view.setAllCaps(false);
        view.setBackground(round(color, 12));
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
