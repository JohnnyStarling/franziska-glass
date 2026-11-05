package lab.franziska.glass;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import lab.franziska.protocol.MediaDeleteGuard;
import lab.franziska.protocol.MediaWire;

/** Exact MediaStore row only. Never uses a path supplied by Pixel. */
final class GlassDeleteIndex implements MediaDeleteGuard.Index {
    private final Context context;
    GlassDeleteIndex(Context c) {context=c;}
    public MediaDeleteGuard.Source query(MediaWire.Frame request) throws Exception {
        Cursor c=context.getContentResolver().query(
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"),request.id),
                new String[]{"_id","_data","_size","date_modified","_display_name","media_type"},
                null,null,null);
        try {
            if(c==null||!c.moveToFirst())throw new IOException("MEDIA_DELETE_ROW_MISSING");
            long id=c.getLong(0),size=c.getLong(2),modified=c.getLong(3);
            String path=c.getString(1),name=c.getString(4);int type=c.getInt(5);
            byte kind=type==MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE?MediaWire.PHOTO:
                    type==MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO?MediaWire.VIDEO:0;
            if(path==null||name==null||c.moveToNext())throw new IOException("MEDIA_DELETE_ROW_INVALID");
            MediaWire.Frame item=MediaWire.parse(MediaWire.deleteItem(id,kind,size,modified,name,request.hash));
            if(!MediaDeleteGuard.same(item,request))throw new IOException("MEDIA_DELETE_METADATA_CHANGED");
            return new MediaDeleteGuard.Source(item,Environment.getExternalStorageDirectory().getCanonicalFile(),new File(path));
        }finally {if(c!=null)c.close();}
    }
    public boolean remove(MediaDeleteGuard.Source source) throws Exception {
        MediaWire.Frame r=source.item;
        // File and row are separate steps: failure after unlink is deliberately reported incomplete.
        if(!source.file.delete())return false;
        int rows=context.getContentResolver().delete(
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"),r.id),
                "_id=? AND _data=? AND _size=? AND date_modified=? AND _display_name=? AND media_type=?",
                new String[]{Long.toString(r.id),source.file.getPath(),Long.toString(r.size),
                        Long.toString(r.modified),r.name,Integer.toString(r.kind==MediaWire.PHOTO?
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE:MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)});
        return rows==1;
    }
}
