package lab.franziska.glass;

import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import lab.franziska.protocol.MediaWire;

/** Current stock-camera inventory; no camera activation or source-file deletion. */
final class GlassMediaIndex {
    static final class Entry {
        final long id,size,modified; final byte kind; final String name; final File file;
        final boolean stockCamera;
        Entry(long id,byte kind,long size,long modified,String name,File file,boolean stockCamera) {
            this.id=id;this.kind=kind;this.size=size;this.modified=modified;
            this.name=name;this.file=file;this.stockCamera=stockCamera;
        }
        boolean ready() {return file.isFile()&&file.length()==size&&file.lastModified()/1000==modified
                &&System.currentTimeMillis()-file.lastModified()>=2000L;}
    }
    final ArrayList<Entry> entries;
    final byte[] digest;
    private GlassMediaIndex(ArrayList<Entry> entries,byte[] digest) {
        this.entries=entries;this.digest=digest;
    }

    static GlassMediaIndex read(Context context) throws Exception {
        ArrayList<Entry> entries=readEntries(context);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        for(Entry entry:entries)
            hash.update(MediaWire.item(entry.id,entry.kind,entry.size,entry.modified,entry.name,null));
        return new GlassMediaIndex(entries,hash.digest());
    }

    /** Foreground browsing needs fresh rows, not serialized transport frames or a catalog hash. */
    static ArrayList<Entry> readEntries(Context context) throws Exception {
        return readEntries(context,0);
    }

    /** One selected row uses the same validation as browsing, without scanning the archive. */
    static Entry readEntry(Context context,long id) throws Exception {
        if(id<=0)return null;
        ArrayList<Entry> rows=readEntries(context,id);
        return rows.size()==1?rows.get(0):null;
    }

    private static ArrayList<Entry> readEntries(Context context,long selectedId) throws Exception {
        ArrayList<Entry> entries=new ArrayList<Entry>();
        File root=Environment.getExternalStorageDirectory().getCanonicalFile();
        File camera=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera").getCanonicalFile();
        Cursor cursor=null;
        try {
            String[] columns={MediaStore.Files.FileColumns._ID,MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.SIZE,MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.MIME_TYPE,MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MEDIA_TYPE};
            cursor=context.getContentResolver().query(selectedId>0
                            ?ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"),selectedId)
                            :MediaStore.Files.getContentUri("external"),columns,
                    MediaStore.Files.FileColumns.MEDIA_TYPE+"=? OR "+MediaStore.Files.FileColumns.MEDIA_TYPE+"=?",
                    new String[]{Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                            Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)},
                    MediaStore.Files.FileColumns._ID+" ASC");
            if(cursor==null)throw new IOException("MEDIA_CATALOG_UNAVAILABLE");
            while(cursor.moveToNext()) {
                long id=cursor.getLong(0),size=cursor.getLong(2),modified=cursor.getLong(3);
                String path=cursor.getString(1),mime=cursor.getString(4),name=cursor.getString(5);
                int mediaType=cursor.getInt(6);
                if(selectedId>0&&id!=selectedId)throw new IOException("MEDIA_SELECTION_ROW_INVALID");
                if(path==null||mime==null||name==null||id<=0||modified<0||size<4)continue;
                byte kind;
                if(mediaType==MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE&&mime.startsWith("image/"))
                    kind=MediaWire.PHOTO;
                else if(mediaType==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        &&mime.startsWith("video/"))kind=MediaWire.VIDEO;
                else continue;
                File file=new File(path).getCanonicalFile();
                if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.isFile()||!file.canRead())continue;
                size=file.length(); modified=file.lastModified()/1000; if(size<4||size>MediaWire.MAX_CATALOG_SIZE)continue;
                if(entries.size()>=MediaWire.MAX_ITEMS)throw new IOException("MEDIA_CATALOG_LIMIT");
                // No UTF-8 name can fit more than 120 UTF-16 units in 120 bytes.
                // Bound hostile/stale DISPLAY_NAME metadata before the byte-accurate trim.
                if(name.length()>120)name=name.substring(0,120);
                while(name.getBytes("UTF-8").length>120&&name.length()>1)
                    name=name.substring(0,name.length()-1);
                Entry entry=new Entry(id,kind,size,modified,name,file,
                        camera.equals(file.getParentFile())&&"image/jpeg".equals(mime));
                entries.add(entry);
                if(selectedId>0&&entries.size()>1)return entries; // Ambiguous provider result rejects.
            }
        } finally {if(cursor!=null)cursor.close();}
        return entries;
    }

    static byte[] preview(Entry entry) {
        Bitmap bitmap=null;
        try {
            if(entry.kind==MediaWire.PHOTO) {
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(entry.file.getAbsolutePath(),options);
                if(options.outWidth<1||options.outHeight<1)return new byte[0];
                int sample=1;while(options.outWidth/sample>160||options.outHeight/sample>120)sample*=2;
                options.inJustDecodeBounds=false;options.inSampleSize=sample;
                bitmap=BitmapFactory.decodeFile(entry.file.getAbsolutePath(),options);
            } else bitmap=ThumbnailUtils.createVideoThumbnail(entry.file.getAbsolutePath(),
                    MediaStore.Video.Thumbnails.MICRO_KIND);
            if(bitmap==null)return new byte[0];
            ByteArrayOutputStream out=new ByteArrayOutputStream(8192);
            bitmap.compress(Bitmap.CompressFormat.JPEG,55,out);
            if(out.size()>MediaWire.MAX_THUMB) {
                out.reset();bitmap.compress(Bitmap.CompressFormat.JPEG,30,out);
            }
            return out.size()<=MediaWire.MAX_THUMB?out.toByteArray():new byte[0];
        } catch(Exception ignored) {return new byte[0];}
        finally {if(bitmap!=null)bitmap.recycle();}
    }
}
