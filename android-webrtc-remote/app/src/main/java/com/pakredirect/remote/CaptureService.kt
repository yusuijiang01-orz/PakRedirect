package com.pakredirect.remote

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.webrtc.*
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureService: Service() {
    private var server:RemoteServer?=null; private var projection:MediaProjection?=null; private var capturer:ScreenCapturerAndroid?=null
    private var factory:PeerConnectionFactory?=null; private var source:VideoSource?=null; private var helper:SurfaceTextureHelper?=null; private var pc:PeerConnection?=null
    override fun onBind(i:Intent?):IBinder?=null
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int{
        if(i?.action=="start") startCapture(i.getIntExtra("code",0),i.getParcelableExtra("data")!!)
        return START_NOT_STICKY
    }
    private fun startCapture(code:Int,data:Intent){
        if(server!=null)return; val ip=MainActivity.tailscaleIp()?:run{stopSelf();return}; val pin=(100000+SecureRandom().nextInt(900000)).toString()
        val ch="remote_capture"; (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(ch,"局域网远控",NotificationManager.IMPORTANCE_LOW))
        startForeground(20,NotificationCompat.Builder(this,ch).setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("局域网远控运行中").setContentText("仅可通过 Tailscale 访问").build())
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());val egl=EglBase.create()
        factory=PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext,true,true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
        capturer=ScreenCapturerAndroid(data,object:MediaProjection.Callback(){override fun onStop(){stopSelf()}});source=factory!!.createVideoSource(true);helper=SurfaceTextureHelper.create("capture",egl.eglBaseContext);capturer!!.initialize(helper,this,source!!.capturerObserver);capturer!!.startCapture(720,1280,30)
        server=RemoteServer(ip,17920,pin,assets.open("remote.html").bufferedReader().readText()){offer->answer(offer)}.also{it.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false)};info=ip to pin
    }
    private fun answer(offer:String):String{
        pc?.close(); val latch=CountDownLatch(1);var result:String?=null;var failure:String?=null
        val observer=object:PeerConnection.Observer{
            override fun onSignalingChange(s:PeerConnection.SignalingState?){};override fun onIceConnectionChange(s:PeerConnection.IceConnectionState?){};override fun onIceConnectionReceivingChange(v:Boolean){};override fun onIceGatheringChange(s:PeerConnection.IceGatheringState?){if(s==PeerConnection.IceGatheringState.COMPLETE){result=pc?.localDescription?.description;latch.countDown()}};override fun onIceCandidate(c:IceCandidate?){};override fun onIceCandidatesRemoved(c:Array<out IceCandidate>?){};override fun onAddStream(s:MediaStream?){};override fun onRemoveStream(s:MediaStream?){};override fun onDataChannel(d:DataChannel?){d?.registerObserver(object:DataChannel.Observer{override fun onBufferedAmountChange(v:Long){};override fun onStateChange(){};override fun onMessage(b:DataChannel.Buffer){val x=ByteArray(b.data.remaining());b.data.get(x);RemoteAccessibilityService.instance?.command(String(x,StandardCharsets.UTF_8))}})};override fun onRenegotiationNeeded(){};override fun onAddTrack(r:RtpReceiver?,m:Array<out MediaStream>?){}}
        // We return a non-trickle SDP answer, so ICE must be gathered once and
        // reach COMPLETE. GATHER_CONTINUALLY never reliably completes on some
        // Android WebRTC builds and caused Safari to see "ICE gathering timeout".
        pc=factory!!.createPeerConnection(PeerConnection.RTCConfiguration(emptyList()).apply{
            sdpSemantics=PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy=PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        },observer)
        val track=factory!!.createVideoTrack("screen",source);pc!!.addTrack(track,listOf("screen"));pc!!.setRemoteDescription(object:SdpObserver{override fun onSetSuccess(){pc!!.createAnswer(object:SdpObserver{override fun onCreateSuccess(s:SessionDescription?){pc!!.setLocalDescription(object:SdpObserver{override fun onSetSuccess(){};override fun onSetFailure(e:String?){failure=e;latch.countDown()};override fun onCreateSuccess(s:SessionDescription?){};override fun onCreateFailure(e:String?){}},s)};override fun onCreateFailure(e:String?){failure=e;latch.countDown()};override fun onSetSuccess(){};override fun onSetFailure(e:String?){}},MediaConstraints())};override fun onSetFailure(e:String?){failure=e;latch.countDown()};override fun onCreateSuccess(s:SessionDescription?){};override fun onCreateFailure(e:String?){}},SessionDescription(SessionDescription.Type.OFFER,offer))
        latch.await(10,TimeUnit.SECONDS);return result?:throw IllegalStateException(failure?:"ICE gathering timeout")
    }
    override fun onDestroy(){server?.stop();capturer?.stopCapture();capturer?.dispose();helper?.dispose();source?.dispose();pc?.close();factory?.dispose();info=null;super.onDestroy()}
    companion object { @Volatile var info:Pair<String,String>?=null;fun start(c:Context,code:Int,data:Intent){c.startForegroundService(Intent(c,CaptureService::class.java).setAction("start").putExtra("code",code).putExtra("data",data))} }
}

private class RemoteServer(host:String,port:Int,private val pin:String,private val page:String,private val createAnswer:(String)->String):NanoHTTPD(host,port){
    override fun serve(s:IHTTPSession):Response{if(s.uri=="/")return newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8",page);if(s.uri=="/health")return newFixedLengthResponse("ok");if(s.uri=="/offer"&&s.method==Method.POST){if(s.parameters["pin"]?.firstOrNull()!=pin)return newFixedLengthResponse(Response.Status.UNAUTHORIZED,"text/plain","bad pin");return try{val files=HashMap<String,String>();s.parseBody(files);val body=files["postData"]?:"";val offer=JSONObject(body).getString("sdp");newFixedLengthResponse(Response.Status.OK,"application/json",JSONObject().put("type","answer").put("sdp",createAnswer(offer)).toString())}catch(e:Exception){newFixedLengthResponse(Response.Status.INTERNAL_ERROR,"text/plain",e.message?:"error")}};return newFixedLengthResponse(Response.Status.NOT_FOUND,"text/plain","not found")}
}

