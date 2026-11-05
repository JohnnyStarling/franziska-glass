package lab.franziska.pixelbridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Exact-record, temporary-grant-only, read-only provider for original document bytes. */
public final class OriginalShareProvider extends ContentProvider {
    @Override public boolean onCreate() { return getContext() != null; }
    private OriginalShareFiles.Entry entry(Uri uri) throws IOException {
        // Android's provider dispatch enforces exported=false; this additionally requires
        // the exact read grant for cross-UID query/type/open operations.
        getContext().enforceCallingOrSelfUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION, "Original-file read grant required");
        return OriginalShareStore.read(getContext(), OriginalShareStore.token(uri));
    }
    @Override public String getType(Uri uri) {
        try { entry(uri); return OriginalShareStore.MIME; }
        catch (IOException invalid) { throw new IllegalArgumentException("Unknown original-file URI", invalid); }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (selection != null || selectionArgs != null || sortOrder != null)
            throw new IllegalArgumentException("No original-file selection API");
        try {
            OriginalShareFiles.Entry record = entry(uri);
            String[] columns = projection == null ?
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = record.name;
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = record.size;
                else throw new IllegalArgumentException("Unsupported original-file column");
            }
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            cursor.addRow(values); return cursor;
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Unknown original-file URI", invalid);
        }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Original files are read-only");
        try { return ParcelFileDescriptor.open(entry(uri).file, ParcelFileDescriptor.MODE_READ_ONLY); }
        catch (IOException invalid) {
            FileNotFoundException missing = new FileNotFoundException("Original file unavailable");
            missing.initCause(invalid); throw missing;
        }
    }
    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only original-file provider");
    }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only original-file provider");
    }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only original-file provider");
    }
}
