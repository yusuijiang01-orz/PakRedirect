package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/** SDK responses are UI hints only. Entitlement always comes from our server. */
public final class PaymentActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> purchaseButtons = new ArrayList<>();
    private SharedPreferences prefs;
    private String token, accountKey, orderId;
    private LinearLayout content, plansBox;
    private TextView status;
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
        getWindow().setStatusBarColor(Color.rgb(16, 20, 29));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(36, 36, 36, 48);
        content.setBackgroundColor(Color.rgb(16, 20, 29));
        scroll.setFillViewport(true);
        scroll.addView(content);
        setContentView(scroll);
        label("VIP 在线充值", 24);
        label("支付后自动开通；有效会员续费会在原到期时间上顺延。", 15);
        status = label("正在加载套餐…", 15);
        plansBox = new LinearLayout(this);
        plansBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(plansBox);
        button(content, "查询当前订单", () -> { polls = 0; queryOrder(true); });
        button(content, "充值记录", this::loadHistory);
        button(content, "返回账号中心", this::finish);
        loadCatalog();
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextColor(Color.WHITE); view.setTextSize(size);
        view.setPadding(0, 12, 0, 20); content.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
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
                        status.setText(catalog.optString("message", "在线支付暂未开放")); return;
                    }
                    status.setText("选择套餐和支付方式");
                    if (plans == null) return;
                    for (int i = 0; i < plans.length(); i++) {
                        JSONObject plan = plans.optJSONObject(i);
                        if (plan == null) continue;
                        String title = plan.optString("name") + " · " + plan.optInt("days") + " 天 · ¥"
                                + String.format(Locale.CHINA, "%.2f", plan.optInt("amount_fen") / 100.0);
                        purchaseButtons.add(button(plansBox, title, () -> chooseChannel(plan, channels, title)));
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
        new AlertDialog.Builder(this).setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, index) ->
                        new AlertDialog.Builder(this).setTitle("确认充值")
                                .setMessage(title + "\n" + labels.get(index) + "\n请先在充值记录确认上一笔订单，避免重复购买。")
                                .setPositiveButton("去支付", (d, which) -> create(plan.optString("code"), codes.get(index)))
                                .setNegativeButton("取消", null).show())
                .setNegativeButton("取消", null).show();
    }

    private void setBusy(boolean value) {
        busy = value;
        for (Button button : purchaseButtons) button.setEnabled(!value);
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
                    status.setText("下单未确认：" + error.getMessage() + "\n重新选择同一套餐和渠道会重试原订单；也可查看充值记录。");
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
                    hint = "6001".equals(result) ? "已取消支付，正在确认订单状态。" : "已返回，正在向服务器确认支付结果。";
                } catch (Exception error) { hint = "支付宝未能完成调起，请查询订单结果。"; }
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
                if (!api.sendReq(req)) throw new Exception("微信支付调起失败");
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
                    if (!result.optBoolean("sync_ok", true)) status.append("\n支付平台查询暂不可用，请稍后重试。");
                });
            } catch (Exception error) {
                ui(() -> { querying = false; status.setText("查询失败：" + error.getMessage() + "\n请稍后查询，切勿重复支付。"); });
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
                    new AlertDialog.Builder(this).setTitle("最近 30 笔充值记录（点击查询）")
                            .setItems(labels, (d, index) -> {
                                orderId = orders.optJSONObject(index).optString("order_id"); polls = 0;
                                prefs.edit().putString(accountKey + "order", orderId).apply();
                                queryOrder(true);
                            }).setNegativeButton("关闭", null).show();
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
