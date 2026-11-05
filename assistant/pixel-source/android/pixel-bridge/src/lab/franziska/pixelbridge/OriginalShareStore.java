package lab.franziska.pixelbridge;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** Internal admission API. Every draft is an app-private verified original, never MediaStore bytes. */
final class OriginalShareStore {
    static final String AUTHORITY = "lab.franziska.pixelbridge.originals";
    static final String MIME = "application/octet-stream";
    static final String TELEGRAM_PACKAGE = "org.telegram.messenger";
    private OriginalShareStore() {}

    static final class Prepared {
        private final OriginalShareFiles.Prepared handle;
        private Prepared(OriginalShareFiles.Prepared handle) { this.handle = handle; }
    }
    static Prepared prepare(Context context, File verifiedTemp, String originalName,
                            long size, byte[] sha256) throws IOException {
        return new Prepared(files(context).prepare(verifiedTemp, originalName, size, sha256));
    }
    /** Call only under PhotoSyncService's cancellation/trust authority lock. */
    static String commit(Context context, Prepared prepared) throws IOException {
        return files(context).commit(prepared == null ? null : prepared.handle);
    }
    static void discard(Context context, Prepared prepared) throws IOException {
        if (prepared != null) files(context).discard(prepared.handle);
    }
    static List<OriginalShareFiles.Entry> pending(Context context) throws IOException {
        return files(context).pending();
    }
    static OriginalShareFiles.Entry read(Context context, String token) throws IOException {
        return files(context).read(token);
    }
    static void offered(Context context, String token) throws IOException {
        files(context).offered(token);
    }
    static void delete(Context context,String token) throws IOException {files(context).delete(token);}
    static Uri uri(String token) throws IOException {
        if (!OriginalShareFiles.validToken(token)) throw new IOException("ORIGINAL_TOKEN_INVALID");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("o")
                .appendPath(token).build();
    }
    static String token(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority()) || uri.getPathSegments().size() != 2
                || !"o".equals(uri.getPathSegments().get(0)))
            throw new IOException("ORIGINAL_URI_INVALID");
        String token = uri.getPathSegments().get(1);
        if (!OriginalShareFiles.validToken(token) || !uri.toString().equals(uri(token).toString()))
            throw new IOException("ORIGINAL_URI_INVALID");
        return token;
    }
    /** Creates a document-class ACTION_SEND; only a foreground owner tap may launch it. */
    static Intent telegramIntent(Context context, String token) throws IOException {
        read(context, token);
        Uri content = uri(token); // opaque last path segment: no .jpg/.mp4 URI sniff
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(MIME);
        send.setPackage(TELEGRAM_PACKAGE);
        send.putExtra(Intent.EXTRA_STREAM, content);
        send.setClipData(ClipData.newRawUri("Original file", content));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }
    private static OriginalShareFiles files(Context context) throws IOException {
        if (context == null) throw new IOException("ORIGINAL_CONTEXT_INVALID");
        return new OriginalShareFiles(context.getCacheDir(), context.getFilesDir());
    }
}
