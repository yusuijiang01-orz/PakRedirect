package com.example.pakredirect;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

public class PakDirectoryManager {
    public static PakIndex scan(Context context, Uri treeUri) {
        PakIndex index = new PakIndex();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        try (Cursor c = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                null, null, null)) {
            if (c == null) return index;
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name == null || !name.toLowerCase().endsWith(".pak")) continue;
                long size = c.isNull(1) ? 0 : c.getLong(1);
                String id = c.getString(3);
                Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                index.put(new PakEntry(name, size, fileUri.toString()));
            }
        }
        return index;
    }
}
