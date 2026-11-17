package com.example.pakredirect;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Runs CoraFlow's bundled Tesseract WASM in an offscreen WebView, without network models. */
public final class CoraFlowOcr implements AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch ready = new CountDownLatch(1);
    private final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private final ExecutorService http = Executors.newFixedThreadPool(4);
    private final String token = UUID.randomUUID().toString();
    private volatile ServerSocket server;
    private volatile WebView view;
    private volatile boolean closed;
    private volatile Throwable startupError;
    private volatile String origin;

    public CoraFlowOcr(Context context) { this.context=context.getApplicationContext(); }

    private synchronized void start() throws Exception {
        if(closed) throw new IllegalStateException("OCR 已关闭");
        if(server!=null) return;
        server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
        origin="http://127.0.0.1:"+server.getLocalPort()+"/"+token+"/";
        Thread accept=new Thread(()->{
            while(!closed) try { Socket socket=server.accept();http.execute(()->serve(socket)); }
            catch(Exception error){ if(!closed) android.util.Log.e("CoraFlowOCR","Asset server failed",error); }
        },"coraflow-ocr-assets");
        accept.setDaemon(true);accept.start();
        main.post(()->{
            if(closed) return;
            try {
                view=new WebView(context);
                view.getSettings().setJavaScriptEnabled(true);
                view.getSettings().setAllowFileAccess(false);
                view.getSettings().setAllowContentAccess(false);
                view.addJavascriptInterface(new Bridge(),"RyluxOcr");
                view.setWebViewClient(new WebViewClient(){
                    @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return !url.startsWith(origin);}
                    @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail){
                        startupError=new IllegalStateException("Tesseract 渲染进程已退出");
                        close();return true;
                    }
                });
                view.loadUrl(origin+"index.html");
            }catch(Throwable error){startupError=error;ready.countDown();}
        });
    }

    private void serve(Socket socket) {
        try(Socket s=socket){
            s.setSoTimeout(10000);
            BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));
            String line=reader.readLine();
            if(line==null) return;
            String[] request=line.split(" ");
            String prefix="/"+token+"/";
            if(request.length<2||!"GET".equals(request[0])||!request[1].startsWith(prefix)) return;
            String file=request[1].substring(prefix.length()).split("\\?",2)[0];
            if(!file.matches("[A-Za-z0-9_.-]+")||file.contains("..")) return;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream input=context.getAssets().open("coraflow-ocr/"+file)){
                byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            }
            String mime=file.endsWith(".js")?"application/javascript":file.endsWith(".html")?"text/html":file.endsWith(".wasm")?"application/wasm":"application/octet-stream";
            OutputStream output=s.getOutputStream();
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: "+mime+"\r\nContent-Length: "+bytes.size()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            bytes.writeTo(output);output.flush();
        }catch(Exception error){if(!closed)android.util.Log.e("CoraFlowOCR","Asset request failed",error);}
    }

    public final class Bridge {
        @JavascriptInterface public void ready(){ready.countDown();}
        @JavascriptInterface public void complete(String id,String json){
            CompletableFuture<JSONObject> task=pending.get(id);
            if(task!=null)try{task.complete(new JSONObject(json));}catch(Exception e){task.completeExceptionally(e);}
        }
    }

    public synchronized JSONObject recognize(Bitmap crop,String language,String kind,int scale) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("OCR 必须在后台线程运行");
        start();
        if(!ready.await(15,TimeUnit.SECONDS)) { close();throw new IllegalStateException("Tesseract 页面启动超时"); }
        if(startupError!=null)throw new IllegalStateException("Tesseract 页面启动失败",startupError);
        String id=UUID.randomUUID().toString();
        ByteArrayOutputStream png=new ByteArrayOutputStream();crop.compress(Bitmap.CompressFormat.PNG,100,png);
        JSONObject request=new JSONObject().put("id",id).put("language",language).put("kind",kind).put("scale",scale)
                .put("image","data:image/png;base64,"+Base64.encodeToString(png.toByteArray(),Base64.NO_WRAP));
        CompletableFuture<JSONObject> task=new CompletableFuture<>();pending.put(id,task);
        main.post(()->{if(!closed&&view!=null)view.evaluateJavascript("runOcr("+request+")",null);});
        try {
            JSONObject result=task.get(60,TimeUnit.SECONDS);
            if(result.has("error"))throw new IllegalStateException("Tesseract: "+result.optString("error"));
            return result;
        }catch(TimeoutException error){close();throw new IllegalStateException("Tesseract OCR 超时，已释放识别器",error);}
        finally{pending.remove(id);}
    }
    @Override public void close(){
        closed=true;ready.countDown();
        for(CompletableFuture<JSONObject> task:pending.values())task.completeExceptionally(new IllegalStateException("OCR 已关闭"));
        try{if(server!=null)server.close();}catch(IOException ignored){}
        http.shutdownNow();
        main.post(()->{if(view!=null){view.removeJavascriptInterface("RyluxOcr");view.destroy();view=null;}});
    }
}
