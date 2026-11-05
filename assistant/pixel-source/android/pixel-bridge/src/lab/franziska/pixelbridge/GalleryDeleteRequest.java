package lab.franziska.pixelbridge;

import android.content.Context;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import lab.franziska.protocol.MediaDeleteGuard;
import lab.franziska.protocol.MediaWire;

/** RAM-only confirmed request. No persisted command or reconnect reconstruction. */
final class GalleryDeleteRequest {
    final long expires;
    final ArrayList<GlassMediaCatalog.Item> items=new ArrayList<GlassMediaCatalog.Item>();
    final ArrayList<String> tokens=new ArrayList<String>();
    final ArrayList<MediaWire.Frame> frames=new ArrayList<MediaWire.Frame>();
    GalleryDeleteRequest(Context c,ArrayList<GlassMediaCatalog.Item> selected,long deadline) throws Exception {
        expires=deadline;
        if(selected==null||selected.isEmpty()||selected.size()>128)throw new IOException("MEDIA_DELETE_SELECTION");
        HashSet<Long> ids=new HashSet<Long>();
        for(GlassMediaCatalog.Item item:selected) {
            if(item==null||!ids.add(item.id))throw new IOException("MEDIA_DELETE_DUPLICATE");
            OriginalShareFiles.Entry local=GalleryLocalStore.local(c,item);
            if(local==null)throw new IOException("MEDIA_DELETE_NEEDS_ORIGINAL");
            items.add(item);tokens.add(local.token);
            frames.add(MediaWire.parse(MediaWire.deleteItem(item.id,item.kind,item.size,item.modified,item.name,local.hash)));
            // Protect from normal age pruning during the short confirmed request.
            OriginalShareStore.offered(c,local.token);
        }
    }
    void verify(Context c,MediaDeleteGuard.Guard guard) throws Exception {
        for(int i=0;i<items.size();i++) {
            guard.check();OriginalShareFiles.Entry local=GalleryLocalStore.local(c,items.get(i));
            MediaWire.Frame frame=frames.get(i);
            if(local==null||!local.token.equals(tokens.get(i))||!MediaWire.sameHash(local.hash,frame.hash)
                    ||!local.file.getCanonicalFile().equals(local.file.getAbsoluteFile()))
                throw new IOException("MEDIA_DELETE_LOCAL_CHANGED");
            MediaDeleteGuard.hash(local.file,frame.size,frame.hash,guard);
        }
    }
}
