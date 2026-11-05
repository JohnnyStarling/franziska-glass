package lab.franziska.pixelbridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.IOException;
import java.util.ArrayList;
import lab.franziska.protocol.MediaWire;

/** Private last-complete snapshot of what was observed on Glass, including small previews. */
final class GlassMediaCatalog extends SQLiteOpenHelper {
    static final class Item {
        final long id,size,modified;final byte kind;final String name;final byte[] preview;
        Item(long id,long size,long modified,byte kind,String name,byte[] preview) {
            this.id=id;this.size=size;this.modified=modified;this.kind=kind;
            this.name=name;this.preview=preview;
        }
    }
    private SQLiteDatabase write;
    private int expected,received;
    GlassMediaCatalog(Context context) {
        super(context,"glass-media-catalog.db",null,1);
        setWriteAheadLoggingEnabled(true);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE media (id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, "
                +"bytes INTEGER NOT NULL, modified INTEGER NOT NULL, preview BLOB)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
        throw new IllegalStateException("MEDIA_CATALOG_SCHEMA");
    }
    void begin(int count) throws IOException {
        if(write!=null||count<0||count>MediaWire.MAX_ITEMS)throw new IOException("CATALOG_BEGIN_INVALID");
        write=getWritableDatabase();write.beginTransaction();write.delete("media",null,null);
        expected=count;received=0;
    }
    void add(MediaWire.Frame item) throws IOException {
        if(write==null||item.type!=MediaWire.ITEM||received>=expected)throw new IOException("CATALOG_ITEM_INVALID");
        ContentValues values=new ContentValues();values.put("id",Long.valueOf(item.id));
        values.put("kind",Integer.valueOf(item.kind));values.put("name",item.name);
        values.put("bytes",Long.valueOf(item.size));values.put("modified",Long.valueOf(item.modified));
        values.put("preview",item.data);
        if(write.insertWithOnConflict("media",null,values,SQLiteDatabase.CONFLICT_ABORT)<0)
            throw new IOException("CATALOG_DUPLICATE_ID");
        received++;
    }
    void finish() throws IOException {
        if(write==null||received!=expected)throw new IOException("CATALOG_INCOMPLETE");
        try {write.setTransactionSuccessful();}finally {write.endTransaction();write=null;}
    }
    void abort() {if(write!=null) {write.endTransaction();write=null;}}
    int[] counts() {
        Cursor c=getReadableDatabase().rawQuery("SELECT kind,COUNT(*) FROM media GROUP BY kind",null);
        int[] result=new int[2];try {while(c.moveToNext()) {
            if(c.getInt(0)==MediaWire.PHOTO)result[0]=c.getInt(1);
            else if(c.getInt(0)==MediaWire.VIDEO)result[1]=c.getInt(1);
        }}finally {c.close();}return result;
    }
    ArrayList<Item> page(int offset,int limit) {return page(offset,limit,0);}
    ArrayList<Item> page(int offset,int limit,int kind) {
        ArrayList<Item> result=new ArrayList<Item>();
        Cursor c=getReadableDatabase().query("media",new String[]{"id","bytes","modified","kind","name","preview"},
                kind==0?null:"kind=?",kind==0?null:new String[]{Integer.toString(kind)},null,null,"modified DESC,id DESC",
                Integer.toString(Math.max(0,offset))+","+Integer.toString(Math.min(200,Math.max(1,limit))));
        try {while(c.moveToNext())result.add(new Item(c.getLong(0),c.getLong(1),c.getLong(2),
                (byte)c.getInt(3),c.getString(4),c.getBlob(5)));}finally {c.close();}
        return result;
    }
}
