package com.pakredirect.remote

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import java.net.NetworkInterface

class MainActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var status: TextView
    private val projection = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && r.data != null) {
            CaptureService.start(this, r.resultCode, r.data!!)
            refresh()
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); setContentView(R.layout.activity_main); status=findViewById(R.id.status)
        findViewById<Button>(R.id.accessibility).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.start).setOnClickListener {
            val ip=tailscaleIp(); if(ip==null){status.text="未检测到模拟器内的 Tailscale 100.x 地址。请先在雷电内安装并连接 Tailscale。";return@setOnClickListener}
            projection.launch((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.stop).setOnClickListener { stopService(Intent(this,CaptureService::class.java)); refresh() }
        refresh()
    }
    override fun onResume(){super.onResume();refresh()}
    private fun refresh(){val ip=tailscaleIp(); val info=CaptureService.info;if(info!=null)status.text="已启动\nSafari: http://${info.first}:17920\nPIN: ${info.second}" else status.text=if(ip==null)"未检测到模拟器内的 Tailscale 连接" else "Tailscale: $ip\n请依次开启辅助服务并授权录屏"}
    companion object { fun tailscaleIp():String?=NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap{it.inetAddresses.toList()}?.map{it.hostAddress?.substringBefore('%')}?.firstOrNull{it?.matches(Regex("100\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))==true} }
}
