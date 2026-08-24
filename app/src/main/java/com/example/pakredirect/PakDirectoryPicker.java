package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;

public class PakDirectoryPicker {
    public static final int REQUEST_CODE = 2001;

    public static void open(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        activity.startActivityForResult(intent, REQUEST_CODE);
    }
}
