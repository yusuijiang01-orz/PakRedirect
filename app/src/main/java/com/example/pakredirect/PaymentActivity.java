package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.alipay.sdk.app.PayTask;
import com.tencent.mm.opensdk.modelpay.PayReq;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.example.pakredirect.RyluxUiPolish.BG_TOP;
import static com.example.pakredirect.RyluxUiPolish.BG_BOTTOM;
import static com.example.pakredirect.RyluxUiPolish.PANEL_TOP;
import static com.example.pakredirect.RyluxUiPolish.PANEL_BOTTOM;
import static com.example.pakredirect.RyluxUiPolish.SURFACE;
import static com.example.pakredirect.RyluxUiPolish.BORDER;
import static com.example.pakredirect.RyluxUiPolish.TEXT;
import static com.example.pakredirect.RyluxUiPolish.MUTED;
import static com.example.pakredirect.RyluxUiPolish.BLUE;

/** SDK responses are UI hints only. Entitlement always comes from our server. */
public final class PaymentActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> purchaseButtons = new ArrayList<>();
    private SharedPreferences prefs;
    private String token, accountKey, orderId;
    private LinearLayout content, plansBox;
    private TextView status, intro;
    private boolean busy, querying, resumed;
    private int polls;
    private final Runnable poll = () -> queryOrder(false);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AuthStorage auth = new AuthStorage(this);
        token = auth.loadToken();
        if (token == null || token.isEmpty()) { finish(); return; }
        prefs = getSharedPreferences("rylux_payments", MODE_PRIVATE);
        accountKey = auth.loadUsername() + ":";
        orderId = prefs.getString(accountKey + "order", "");
        getWindow().setStatusBarColor(BG_TOP);
        getWindow().setNavigationBarColor(BG_BOTTOM);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(RyluxUiPolish.verticalGradient(BG_TOP, BG_BOTTOM, 0));
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(24), dp(22), dp(32));
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        TextView brand = label("RYLUX", 12);
        brand.setTextColor(BLUE);
        brand.setLetterSpacing(0.18f);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView title = label("VIP 充值", 26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        intro = label("选好套餐和支付方式，付款后会员会自动开通。已有会员从当前到期时间继续续期。", 14);
        intro.setTextColor(MUTED);
        status = label("正在加载套餐…", 14);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setLineSpacing(dp(4), 1f);
        status.setBackground(RyluxUiPolish.round(this, PANEL_TOP, 16, BORDER, 1));
        plansBox = new LinearLayout(this);
        plansBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams plansLp = new LinearLayout.LayoutParams(-1, -2);
        plansLp.topMargin = dp(6);
        content.addView(plansBox, plansLp);
        TextView ordersTitle = label("订单与记录", 12);
        ordersTitle.setTextColor(MUTED);
        ordersTitle.setPadding(0, dp(24), 0, dp(4));
        button(content, "查询当前订单", () -> { polls = 0; queryOrder(true); });
        button(content, "充值记录", this::loadHistory);
        button(content, "返回账号中心", this::finish);
        loadCatalog();
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(TEXT); view.setTextSize(size);
        view.setIncludeFontPadding(false);
        view.setPadding(0, dp(4), 0, dp(12)); content.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        RyluxUiPolish.styleOutlineButton(this, button, false);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.topMargin = dp(10);
        parent.addView(button, lp);
        return button;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private AlertDialog.Builder dialog(String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(18);
        heading.setTextColor(TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(dp(22), dp(22), dp(22), dp(12));
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setCustomTitle(heading);
    }

    private void showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    RyluxUiPolish.verticalGradient(PANEL_TOP, PANEL_BOTTOM, dp(18)));
        }
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) message.setTextColor(TEXT);
        if (dialog.getListView() != null) dialog.getListView().setBackgroundColor(SURFACE);
        Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (primary != null) {
            RyluxUiPolish.stylePrimaryButton(this, primary);
            primary.setTextSize(14);
            primary.setPadding(dp(16), 0, dp(16), 0);
        }
        Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (cancel != null) {
            RyluxUiPolish.styleOutlineButton(this, cancel, false);
            cancel.setPadding(dp(16), 0, dp(16), 0);
        }
    }

    private void ui(Runnable action) {
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); });
    }

    private void loadCatalog() {
        worker.execute(() -> {
            try {
                JSONObject catalog = AuthClient.paymentCatalog();
                ui(() -> {
                    plansBox.removeAllViews(); purchaseButtons.clear();
                    JSONArray channels = catalog.optJSONArray("channels");
                    JSONArray plans = catalog.optJSONArray("plans");
                    if (!catalog.optBoolean("purchase_enabled") || channels == null || channels.length() == 0) {
                        intro.setVisibility(View.GONE);
                        status.setText("在线支付还没开通，可以先用兑换码开通会员。"); return;
                    }
                    intro.setVisibility(View.VISIBLE);
                    status.setText("选择套餐后即可支付");
                    if (plans == null) return;
                    for (int i = 0; i < plans.length(); i++) {
                        JSONObject plan = plans.optJSONObject(i);
                        if (plan == null) continue;
                        String title = plan.optString("name") + "\n" + plan.optInt("days") + " 天 · ¥"
                                + String.format(Locale.CHINA, "%.2f", plan.optInt("amount_fen") / 100.0);
                        Button purchase = button(plansBox, title, () -> chooseChannel(plan, channels, title));
                        RyluxUiPolish.stylePrimaryButton(this, purchase);
                        purchase.setTextSize(17);
                        purchase.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                        purchase.setPadding(dp(18), dp(10), dp(18), dp(10));
                        LinearLayout.LayoutParams purchaseLp =
                                (LinearLayout.LayoutParams) purchase.getLayoutParams();
                        purchaseLp.height = dp(80);
                        purchase.setLayoutParams(purchaseLp);
                        purchaseButtons.add(purchase);
                    }
                });
            } catch (Exception error) { ui(() -> status.setText("套餐加载失败：" + error.getMessage())); }
        });
    }

    private void chooseChannel(JSONObject plan, JSONArray channels, String title) {
        if (busy) return;
        List<String> codes = new ArrayList<>(), labels = new ArrayList<>();
        for (int i = 0; i < channels.length(); i++) {
            String code = channels.optString(i);
            if ("alipay".equals(code) || "wechat".equals(code)) {
                codes.add(code); labels.add("alipay".equals(code) ? "支付宝支付" : "微信支付");
            }
        }
        showDialog(dialog(title)
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        showDialog(dialog("确认充值")
                                .setMessage(title + "\n" + labels.get(index) + "\n如有未完成订单，请先查询充值记录。")
                                .setPositiveButton("去支付", (d, which) -> create(plan.optString("code"), codes.get(index)))
                                .setNegativeButton("取消", null)))
                .setNegativeButton("取消", null));
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Button button : purchaseButtons) {
            button.setEnabled(!value);
            RyluxUiPolish.stylePrimaryButton(this, button);
            button.setTextSize(17);
        }
    }

    private void create(String plan, String channel) {
        if (busy) return;
        setBusy(true);
        handler.removeCallbacks(poll);
        status.setText("正在创建订单…");
        String requestKey = accountKey + "request:" + channel + ":" + plan;
        String existing = prefs.getString(requestKey, "");
        final String requestId = existing.isEmpty() ? UUID.randomUUID().toString() : existing;
        // Persist BEFORE the network request so a timeout/process death cannot double-create.
        if (!prefs.edit().putString(requestKey, requestId).commit()) {
            setBusy(false); status.setText("无法保存订单，请稍后重试"); return;
        }
        worker.execute(() -> {
            try {
                JSONObject result = AuthClient.createPayment(token, channel, plan, requestId);
                JSONObject order = result.getJSONObject("order");
                String id = order.getString("order_id");
                prefs.edit().putString(accountKey + "order", id).remove(requestKey).commit();
                ui(() -> {
                    orderId = id; polls = 0;
                    if ("paid".equals(order.optString("status"))) {
                        setBusy(false); showOrder(order); return;
                    }
                    JSONObject payment = result.optJSONObject("payment");
                    if (payment == null) { setBusy(false); showOrder(order); return; }
                    launchPayment(channel, payment);
                });
            } catch (Exception error) {
                if (error instanceof AuthClient.PaymentException && ((AuthClient.PaymentException) error).statusCode == 409) {
                    prefs.edit().remove(requestKey).commit();
                }
                ui(() -> {
                    setBusy(false);
                    status.setText("订单创建未确认：" + error.getMessage() + "\n请选择同一套餐和支付方式重试，或查看充值记录。");
                });
            }
        });
    }

    private void launchPayment(String channel, JSONObject payment) {
        status.setText("订单 " + orderId + "\n等待支付，返回后会自动查询结果。");
        if ("alipay".equals(channel)) {
            worker.execute(() -> {
                String hint;
                try {
                    String result = new PayTask(this).payV2(payment.optString("order_string"), true).get("resultStatus");
                    hint = "6001".equals(result) ? "已取消支付，正在确认订单状态。" : "正在确认支付结果…";
                } catch (Exception error) { hint = "未能打开支付宝，请查询订单结果。"; }
                final String message = hint;
                ui(() -> { setBusy(false); status.setText(message); polls = 0; queryOrder(false); });
            });
        } else {
            try {
                String appId = payment.getString("appid");
                prefs.edit().putString("wechat_app_id", appId).commit();
                IWXAPI api = WXAPIFactory.createWXAPI(this, appId, true);
                api.registerApp(appId);
                if (!api.isWXAppInstalled()) throw new Exception("请先安装微信");
                PayReq req = new PayReq();
                req.appId = appId; req.partnerId = payment.getString("partnerid");
                req.prepayId = payment.getString("prepayid"); req.packageValue = payment.getString("package");
                req.nonceStr = payment.getString("noncestr"); req.timeStamp = payment.getString("timestamp");
                req.sign = payment.getString("sign");
                if (!api.sendReq(req)) throw new Exception("无法打开微信支付");
            } catch (Exception error) { status.setText(error.getMessage() + "，可查询当前订单。"); }
            setBusy(false);
            handler.postDelayed(poll, 5000);
        }
    }

    private void queryOrder(boolean manual) {
        if (orderId == null || orderId.isEmpty()) {
            if (manual) status.setText("暂无当前订单，可查看充值记录。");
            return;
        }
        if (querying || busy || !resumed) return;
        handler.removeCallbacks(poll);
        querying = true;
        final String id = orderId;
        worker.execute(() -> {
            try {
                JSONObject result = AuthClient.paymentOrder(token, id);
                JSONObject order = result.getJSONObject("order");
                ui(() -> {
                    querying = false;
                    if (!id.equals(orderId)) { queryOrder(false); return; }
                    showOrder(order);
                    if ("pending".equals(order.optString("status")) && ++polls < 12 && resumed) {
                        handler.postDelayed(poll, 5000);
                    }
                    if (!result.optBoolean("sync_ok", true)) status.append("\n暂时无法确认支付结果，请稍后查询。");
                });
            } catch (Exception error) {
                ui(() -> { querying = false; status.setText("查询失败：" + error.getMessage() + "\n如已扣款，请勿重复支付。"); });
            }
        });
    }

    private void showOrder(JSONObject order) {
        String state = order.optString("status");
        String message = "paid".equals(state) ? "支付成功，VIP 已开通\n到期时间：" + order.optString("vip_expires_at")
                : "closed".equals(state) ? "订单已关闭，未支付" : "等待支付确认。如已扣款，请稍后查询。";
        status.setText(order.optString("plan_name") + " · 订单 " + order.optString("order_id") + "\n" + message);
        if ("paid".equals(state)) { setResult(RESULT_OK); handler.removeCallbacks(poll); }
    }

    private void loadHistory() {
        worker.execute(() -> {
            try {
                JSONArray orders = AuthClient.paymentHistory(token).getJSONArray("orders");
                String[] labels = new String[orders.length()];
                for (int i = 0; i < orders.length(); i++) {
                    JSONObject order = orders.getJSONObject(i);
                    String state = order.optString("status");
                    labels[i] = order.optString("plan_name") + " ¥" + String.format(Locale.CHINA, "%.2f", order.optInt("amount_fen") / 100.0)
                            + " · " + ("paid".equals(state) ? "已支付" : "closed".equals(state) ? "已关闭" : "待确认")
                            + "\n" + order.optString("created_at");
                }
                ui(() -> {
                    if (labels.length == 0) { Toast.makeText(this, "暂无充值记录", Toast.LENGTH_SHORT).show(); return; }
                    showDialog(dialog("充值记录 · 点击查询")
                            .setItems(labels, (d, index) -> {
                                orderId = orders.optJSONObject(index).optString("order_id"); polls = 0;
                                prefs.edit().putString(accountKey + "order", orderId).apply();
                                queryOrder(true);
                            }).setNegativeButton("关闭", null));
                });
            } catch (Exception error) { ui(() -> status.setText("充值记录加载失败：" + error.getMessage())); }
        });
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; polls = 0;
        if (token != null) handler.postDelayed(poll, 800);
    }
    @Override protected void onPause() {
        resumed = false; handler.removeCallbacks(poll); super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy();
    }
}
