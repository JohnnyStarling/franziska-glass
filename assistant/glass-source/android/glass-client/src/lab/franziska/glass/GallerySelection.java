package lab.franziska.glass;

import android.content.Context;
import lab.franziska.protocol.MediaWire;

/** The unified MediaStore Files index is the only source of selectable originals. */
final class GallerySelection {
    static boolean photo(GlassMediaIndex.Entry item) {
        return item!=null && item.kind==MediaWire.PHOTO;
    }
    static boolean video(GlassMediaIndex.Entry item) {
        return item!=null && item.kind==MediaWire.VIDEO;
    }
    static boolean same(GlassMediaIndex.Entry a,GlassMediaIndex.Entry b) {
        return a!=null && b!=null && a.id==b.id && a.kind==b.kind
                && a.size==b.size && a.modified==b.modified && a.file.equals(b.file);
    }
    static boolean shareable(GlassMediaIndex.Entry item) {
        return item!=null && item.id>0 && item.size>=4 && item.modified>=0;
    }
    static GlassMediaIndex.Entry fresh(Context context,GlassMediaIndex.Entry selected) throws Exception {
        if (!shareable(selected)) return null;
        GlassMediaIndex.Entry match=GlassMediaIndex.readEntry(context,selected.id);
        return same(selected,match) && match.ready() ? match : null;
    }
    private GallerySelection() {}
}
