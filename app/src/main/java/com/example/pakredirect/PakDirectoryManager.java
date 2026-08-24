package com.example.pakredirect;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

public class PakDirectoryManager {
    public static PakIndex scan(Context context, Uri treeUri) {
        PakIndex index = new PakIndex();
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null) return index;

        for (DocumentFile file : root.listFiles()) {
            if (!file.isFile()) continue;
            String name = file.getName();
            if (name == null || !name.toLowerCase().endsWith(".pak")) continue;
            index.put(new PakEntry(name, file.length(), file.getUri().toString()));
        }
        return index;
    }
}
