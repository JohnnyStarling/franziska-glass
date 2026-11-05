package lab.franziska.pixelbridge;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import lab.franziska.protocol.MediaWire;

/** Local-only copy to Pixel albums, idempotent after a lost ACK. Never deletes Glass originals. */
final class MediaAlbum {
    interface Cancellation {boolean current();Object publicationLock();void beforePublish() throws IOException;}
    private final Context context;
    MediaAlbum(Context context) {this.context=context;}

    void publish(MediaWire.Frame begin,File source,Cancellation cancellation) throws IOException {
        if(Build.VERSION.SDK_INT<29||begin==null||begin.type!=MediaWire.FILE_BEGIN
                ||source==null||source.length()!=begin.size)throw new IOException("MEDIA_IMPORT_INVALID");
        if(!cancellation.current())throw new IOException("MEDIA_CANCELLED");
        if(begin.kind==MediaWire.PHOTO)validateImage(source);
        else if(begin.kind==MediaWire.VIDEO)validateVideo(source);
        else throw new IOException("MEDIA_IMPORT_KIND");
        String hash=PhotoAlbum.hex(begin.hash);
        String extension=GalleryMediaTypes.extension(begin.name,begin.kind);
        String mime=GalleryMediaTypes.mime(begin.name,begin.kind);
        String filename="GLASS_"+begin.id+"_"+hash+"."+extension;
        String folder=begin.kind==MediaWire.PHOTO?"Pictures/Franziska Glass/":"Movies/Franziska Glass/";
        String key="media_"+begin.kind+"_"+begin.id+"_"+hash;
        ContentResolver resolver=context.getContentResolver();
        Uri collection=begin.kind==MediaWire.PHOTO?MediaStore.Images.Media.EXTERNAL_CONTENT_URI:
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Cursor cursor=null;
        try {
            cursor=resolver.query(collection,new String[]{MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.SIZE},
                    MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?",
                    new String[]{filename,folder},null);
            if(cursor!=null)while(cursor.moveToNext()) {
                Uri existing=Uri.withAppendedPath(collection,Long.toString(cursor.getLong(0)));
                if(cursor.getInt(1)==0) {
                    if(cursor.getLong(2)!=begin.size)throw new IOException("MEDIA_EXISTING_SIZE");
                    receipt(key);return;
                }
                resolver.delete(existing,null,null);
            }
        } finally {if(cursor!=null)cursor.close();}
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,folder);
        values.put(MediaStore.MediaColumns.IS_PENDING,Integer.valueOf(1));
        Uri inserted=resolver.insert(collection,values);
        if(inserted==null)throw new IOException("MEDIA_IMPORT_CREATE");
        boolean published=false;
        try {
            InputStream input=new FileInputStream(source);
            try {
                OutputStream output=resolver.openOutputStream(inserted,"w");
                if(output==null)throw new IOException("MEDIA_IMPORT_OPEN");
                try {byte[] buffer=new byte[32768];int n;
                    while((n=input.read(buffer))!=-1) {
                        if(!cancellation.current())throw new IOException("MEDIA_CANCELLED");
                        output.write(buffer,0,n);
                    }
                    output.flush();
                } finally {output.close();}
            } finally {input.close();}
            synchronized(cancellation.publicationLock()) {synchronized(Provisioning.MEDIA_PUBLICATION_LOCK) {
                cancellation.beforePublish();
                values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,Integer.valueOf(0));
                if(resolver.update(inserted,values,null,null)!=1)throw new IOException("MEDIA_IMPORT_PUBLISH");
                published=true;receipt(key);
            }}
        } finally {if(!published)resolver.delete(inserted,null,null);}
    }
    private void receipt(String key) throws IOException {
        if(!context.getSharedPreferences("glass-media-receipts",Context.MODE_PRIVATE)
                .edit().putBoolean(key,true).commit())throw new IOException("MEDIA_IMPORT_RECEIPT");
    }
    private static void validateImage(File file) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(options.outWidth<1||options.outHeight<1||options.outWidth>8192||options.outHeight>8192)
            throw new IOException("MEDIA_JPEG_DIMENSIONS");
    }
    private static void validateVideo(File file) throws IOException {
        MediaMetadataRetriever metadata=new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            if(!"yes".equalsIgnoreCase(metadata.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)))
                throw new IOException("MEDIA_VIDEO_TRACK");
        } catch(RuntimeException invalid) {throw new IOException("MEDIA_VIDEO_PARSE",invalid);}
        finally {metadata.release();}
    }
}
