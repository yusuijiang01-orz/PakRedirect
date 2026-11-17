package com.example.pakredirect;
import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Separate test APK: runs the production bridge with the desktop replay fixtures. */
public class OcrParityActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(()->{
            JSONArray results=new JSONArray();
            try(CoraFlowOcr ocr=new CoraFlowOcr(this)) {
                File root=getExternalFilesDir(null);
                JSONArray cases=new JSONArray(new String(Files.readAllBytes(new File(root,"cases.json").toPath()),StandardCharsets.UTF_8));
                for(int i=0;i<cases.length();i++) {
                    JSONObject c=cases.getJSONObject(i);
                    Bitmap image=BitmapFactory.decodeFile(new File(root,c.getString("file")).getPath());
                    try {JSONObject r=c.optBoolean("yellow")?ocr.recognizeYellow(image):ocr.recognize(image,c.getString("language"),c.getString("kind"),c.getInt("scale"));r.put("id",c.getString("id"));results.put(r);Log.i("OCR-PARITY",r.toString());}
                    finally {image.recycle();}
                }
            }catch(Throwable e){try{results.put(new JSONObject().put("error",e.toString()));}catch(Exception ignored){}Log.e("OCR-PARITY","Failed",e);}
            try{Files.write(new File(getExternalFilesDir(null),"results.json").toPath(),results.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){Log.e("OCR-PARITY","Cannot save",e);}
            runOnUiThread(this::finish);
        },"ocr-parity-check").start();
    }
}
