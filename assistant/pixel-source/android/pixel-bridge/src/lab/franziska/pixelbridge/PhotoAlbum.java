package lab.franziska.pixelbridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import lab.franziska.protocol.PhotoWire;

/** Pixel-local MediaStore destination; no Google Photos/OpenClaw upload. */
final class PhotoAlbum {
    private static final String FOLDER = "Pictures/Franziska Glass/";
    private final Context context;
    PhotoAlbum(Context context) { this.context = context; }

    boolean publish(long id, byte[] hash, File source, int bytes) throws IOException {
        if (Build.VERSION.SDK_INT < 29 || id <= 0 || hash == null || hash.length != 32
                || source == null || bytes < 4 || bytes > PhotoWire.MAX_JPEG
                || source.length() != bytes) throw new IOException("PHOTO_IMPORT_INVALID");
        validateJpeg(source);
        String name = "GLASS_" + id + "_" + hex(hash) + ".jpg";
        String key = "import_" + id + "_" + hex(hash);
        if (context.getSharedPreferences("glass-photo-receipts", Context.MODE_PRIVATE)
                .getBoolean(key, false)) return true;
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Cursor cursor = null;
        try {
            cursor = resolver.query(collection, new String[] { MediaStore.Images.Media._ID,
                    MediaStore.MediaColumns.IS_PENDING },
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[] { name, FOLDER }, null);
            if (cursor != null) while (cursor.moveToNext()) {
                Uri existing = Uri.withAppendedPath(collection, Long.toString(cursor.getLong(0)));
                if (cursor.getInt(1) == 0) return receipt(key);
                resolver.delete(existing, null, null); // only our deterministic pending row
            }
        } finally { if (cursor != null) cursor.close(); }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(1));
        Uri inserted = resolver.insert(collection, values);
        if (inserted == null) throw new IOException("PHOTO_IMPORT_CREATE");
        boolean finished = false;
        try {
            InputStream in = new FileInputStream(source);
            try {
                OutputStream out = resolver.openOutputStream(inserted, "w");
                if (out == null) throw new IOException("PHOTO_IMPORT_OPEN");
                try {
                    byte[] buffer = new byte[16384]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.flush();
                } finally { out.close(); }
            } finally { in.close(); }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, Integer.valueOf(0));
            if (resolver.update(inserted, values, null, null) != 1)
                throw new IOException("PHOTO_IMPORT_PUBLISH");
            finished = true;
            return receipt(key);
        } finally {
            if (!finished) resolver.delete(inserted, null, null);
        }
    }

    private boolean receipt(String key) throws IOException {
        if (!context.getSharedPreferences("glass-photo-receipts", Context.MODE_PRIVATE)
                .edit().putBoolean(key, true).commit()) throw new IOException("PHOTO_IMPORT_RECEIPT");
        return true;
    }

    private static void validateJpeg(File file) throws IOException {
        RandomAccessFile input = new RandomAccessFile(file, "r");
        try {
            if (input.length() < 4 || input.readUnsignedByte() != 0xff || input.readUnsignedByte() != 0xd8)
                throw new IOException("PHOTO_JPEG_HEADER");
            input.seek(input.length() - 2);
            if (input.readUnsignedByte() != 0xff || input.readUnsignedByte() != 0xd9)
                throw new IOException("PHOTO_JPEG_END");
        } finally { input.close(); }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > 8192 || bounds.outHeight > 8192)
            throw new IOException("PHOTO_JPEG_DIMENSIONS");
    }

    static String hex(byte[] value) {
        final char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            result[i * 2] = digits[(value[i] >>> 4) & 15];
            result[i * 2 + 1] = digits[value[i] & 15];
        }
        return new String(result);
    }
}
