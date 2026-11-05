package lab.franziska.protocol;
import java.io.IOException;

/** Exact-file admission for the separate original-share session; no Telegram action occurs here. */
public final class MediaSelectionPolicy {
    private final long selected;
    private int completed;
    private boolean receiving;
    public MediaSelectionPolicy(long selectedId) throws IOException {
        if(selectedId<0)throw new IOException("MEDIA_SELECTION_INVALID");selected=selectedId;
    }
    public static void singlePlan(byte flags) throws IOException {
        if(flags!=0)throw new IOException("MEDIA_SHARE_PLAN_INVALID");
    }
    public static void snapshot(long expectedId,long expectedSize,long expectedModified,
                                long id,long size,long modified) throws IOException {
        if(expectedId<=0||expectedSize<4||expectedModified<0||expectedId!=id
                ||expectedSize!=size||expectedModified!=modified)throw new IOException("MEDIA_SELECTION_STALE");
    }
    public void begin(MediaWire.Frame frame) throws IOException {
        if(frame==null||frame.type!=MediaWire.FILE_BEGIN||receiving
                ||(selected>0&&(frame.id!=selected||completed!=0)))throw new IOException("MEDIA_SELECTION_MISMATCH");
        receiving=true;
    }
    public void committed() throws IOException {
        if(!receiving)throw new IOException("MEDIA_SELECTION_COMMIT");receiving=false;completed++;
    }
    public void done(MediaWire.Frame frame) throws IOException {
        if(frame==null||frame.type!=MediaWire.DONE||receiving||frame.count!=completed
                ||(selected>0&&(completed!=1||frame.flags!=1)))throw new IOException("MEDIA_SELECTION_INCOMPLETE");
    }
}
