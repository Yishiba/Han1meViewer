package com.yenaly.han1meviewer.ui.screen.settings.backup;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Test-only provider exercises the actual binder, URI grant and DocumentFile path. */
public class BackupTestDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };

    private File directory() {
        File folder = new File(getContext().getCacheDir(), "backup-provider-fixture");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException();
        return folder;
    }
    private File file(String id) {
        if (!id.startsWith("file:")) throw new IllegalArgumentException();
        String name = id.substring(5);
        if (!name.matches("[A-Za-z0-9._-]+") || name.equals(".") || name.equals("..")) throw new IllegalArgumentException();
        return new File(directory(), name);
    }
    private Map<String, Object> values(String id) {
        boolean root = id.equals("root");
        File entry = root ? directory() : file(id);
        if (!entry.exists()) throw new IllegalArgumentException();
        Map<String, Object> values = new HashMap<>();
        values.put(Document.COLUMN_DOCUMENT_ID, id);
        values.put(Document.COLUMN_DISPLAY_NAME, entry.getName());
        values.put(Document.COLUMN_MIME_TYPE, root ? Document.MIME_TYPE_DIR : "application/json");
        values.put(Document.COLUMN_FLAGS, root ? Document.FLAG_DIR_SUPPORTS_CREATE :
            Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_RENAME | Document.FLAG_SUPPORTS_DELETE);
        values.put(Document.COLUMN_SIZE, entry.length());
        values.put(Document.COLUMN_LAST_MODIFIED, entry.lastModified());
        return values;
    }
    private void addRow(MatrixCursor cursor, String[] columns, Map<String, Object> values) {
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = values.get(columns[i]);
        cursor.addRow(row);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS };
        Map<String, Object> values = new HashMap<>();
        values.put(Root.COLUMN_ROOT_ID, "fixture"); values.put(Root.COLUMN_DOCUMENT_ID, "root");
        values.put(Root.COLUMN_TITLE, "Backup fixture");
        values.put(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY);
        MatrixCursor cursor = new MatrixCursor(columns); addRow(cursor, columns, values); return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        String[] columns = projection != null ? projection : COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns); addRow(cursor, columns, values(id)); return cursor;
    }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder) {
        if (!parentId.equals("root")) throw new IllegalArgumentException();
        String[] columns = projection != null ? projection : COLUMNS;
        MatrixCursor cursor = new MatrixCursor(columns);
        File[] files = directory().listFiles();
        if (files != null) for (File entry : files) addRow(cursor, columns, values("file:" + entry.getName()));
        return cursor;
    }
    @Override public String createDocument(String parentId, String mime, String name) throws FileNotFoundException {
        if (!parentId.equals("root")) throw new IllegalArgumentException();
        String id = "file:" + name;
        try { if (!file(id).createNewFile()) throw new IOException(); }
        catch (IOException failure) { throw new FileNotFoundException(); }
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String renameDocument(String id, String name) {
        String renamed = "file:" + name;
        if (!file(id).renameTo(file(renamed))) throw new IllegalStateException();
        return renamed;
    }
    @Override public void deleteDocument(String id) {
        if (!file(id).delete()) throw new IllegalStateException();
    }
    @Override public boolean isChildDocument(String parentId, String id) {
        return parentId.equals("root") && id.startsWith("file:");
    }
}
