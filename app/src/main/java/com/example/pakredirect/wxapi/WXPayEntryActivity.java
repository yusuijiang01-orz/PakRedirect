package com.example.pakredirect.wxapi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

/** Mandatory WeChat SDK callback component. Never grants VIP from an intent. */
public final class WXPayEntryActivity extends Activity implements IWXAPIEventHandler {
    private IWXAPI api;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String appId = getSharedPreferences("rylux_payments", MODE_PRIVATE).getString("wechat_app_id", "");
        if (appId.isEmpty()) { finish(); return; }
        api = WXAPIFactory.createWXAPI(this, appId, true);
        handle(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handle(intent);
    }
    private void handle(Intent intent) {
        try { if (api == null || !api.handleIntent(intent, this)) finish(); }
        catch (Exception ignored) { finish(); }
    }
    @Override public void onReq(BaseReq req) { finish(); }
    @Override public void onResp(BaseResp resp) {
        // PaymentActivity.onResume queries the server; forged/cancelled callbacks cannot grant access.
        finish();
    }
}
