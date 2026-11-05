package lab.franziska.pixelbridge;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import lab.franziska.protocol.MediaWire;

/** Exact catalog identity -> immutable verified original; never accepts file paths from intents. */
final class GalleryLocalStore {
    private static String key(long id,long size,long modified,byte kind,String name) {
        return id+":"+size+":"+modified+":"+kind+":"+name;
    }
    static void remember(Context c,MediaWire.Frame item,String token) throws IOException {
        if(!prefs(c).edit().putString(key(item.id,item.size,item.modified,item.kind,item.name),token).commit())
            throw new IOException("GALLERY_LOCAL_RECEIPT");
        c.getSharedPreferences("photo-sync",0).edit().putLong("gallery_local_at",System.currentTimeMillis()).apply();
    }
    static OriginalShareFiles.Entry local(Context c,GlassMediaCatalog.Item item) {
        String token=prefs(c).getString(key(item.id,item.size,item.modified,item.kind,item.name),null);
        if(token==null)return null;
        try {OriginalShareFiles.Entry e=OriginalShareStore.read(c,token);
            return e.size==item.size&&e.name.equals(item.name)?e:null;
        }catch(IOException missing){return null;}
    }
    static void delete(Context c,GlassMediaCatalog.Item item,String expectedToken) throws IOException {
        OriginalShareFiles.Entry e=local(c,item);
        if(e==null||!e.token.equals(expectedToken))throw new IOException("GALLERY_LOCAL_CHANGED");
        c.revokeUriPermission(OriginalShareStore.uri(e.token),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        OriginalShareStore.delete(c,e.token);
        prefs(c).edit().remove(key(item.id,item.size,item.modified,item.kind,item.name)).commit();
        c.getSharedPreferences("photo-sync",0).edit().putLong("gallery_local_at",System.currentTimeMillis()).apply();
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("gallery-local-originals",0);}
}
