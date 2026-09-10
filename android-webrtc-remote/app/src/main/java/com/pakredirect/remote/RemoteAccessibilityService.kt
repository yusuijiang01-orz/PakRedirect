package com.pakredirect.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService: AccessibilityService() {
    override fun onServiceConnected(){ instance=this }
    override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}
    override fun onAccessibilityEvent(e: AccessibilityEvent?){}
    override fun onInterrupt(){}
    fun command(text:String){
        val p=text.split('|'); when(p.firstOrNull()){
            "tap"->{val x=(p.getOrNull(1)?.toFloatOrNull()?:0f)*resources.displayMetrics.widthPixels;val y=(p.getOrNull(2)?.toFloatOrNull()?:0f)*resources.displayMetrics.heightPixels;val path=Path().apply{moveTo(x,y)};dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,55)).build(),null,null)}
            "swipe"->{val x1=(p.getOrNull(1)?.toFloatOrNull()?:0f)*resources.displayMetrics.widthPixels;val y1=(p.getOrNull(2)?.toFloatOrNull()?:0f)*resources.displayMetrics.heightPixels;val x2=(p.getOrNull(3)?.toFloatOrNull()?:0f)*resources.displayMetrics.widthPixels;val y2=(p.getOrNull(4)?.toFloatOrNull()?:0f)*resources.displayMetrics.heightPixels;val path=Path().apply{moveTo(x1,y1);lineTo(x2,y2)};dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,180)).build(),null,null)}
            "back"->performGlobalAction(GLOBAL_ACTION_BACK);"home"->performGlobalAction(GLOBAL_ACTION_HOME);"recent"->performGlobalAction(GLOBAL_ACTION_RECENTS);"notifications"->performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }
    companion object { @Volatile var instance:RemoteAccessibilityService?=null }
}
