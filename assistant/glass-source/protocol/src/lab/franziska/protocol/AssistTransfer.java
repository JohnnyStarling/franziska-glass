package lab.franziska.protocol;

import java.util.Arrays;
import java.util.Map;

/** Pixel-side receiver. Caller first authenticates TLS and accepts through SessionState.
 * All methods synchronized. One request (receiving OR upstream) at a time. */
public final class AssistTransfer {
    public static final class Result {
        public enum Kind { NONE, COMPLETE, CANCELLED }
        public final Kind kind;
        public final String requestId;
        /** Private ephemeral ownership passes to caller on COMPLETE; wipe after use/cancel. */
        public final byte[] image, audio;
        private Result(Kind k,String id,byte[] i,byte[] a){kind=k;requestId=id;image=i;audio=a;}
        public void wipe(){if(image!=null)Arrays.fill(image,(byte)0);if(audio!=null)Arrays.fill(audio,(byte)0);}
    }
    private final AssistRequestIds.Ledger seen=new AssistRequestIds.Ledger();
    private String epoch, active;
    private byte[] image,audio;
    private int imageAt,audioAt,imageIndex,audioIndex;
    private long started;
    private boolean committed;
    /** Revoke first; never resume bytes across epochs. Dedupe deliberately survives resets. */
    public synchronized void reset(String epoch){clear();this.epoch=epoch;}
    public synchronized String activeRequestId(){return active;}
    /** Finish upstream/reply or revoke. Seen IDs remain burned; no replay after release. */
    public synchronized void release(String id){if(id!=null&&id.equals(active))clear();}
    /** Call from a monotonic timer even when no frames arrive; returns cancelled receive ID. */
    public synchronized String expire(long now){
        if(active!=null&&!committed&&(now<started||now-started>=AssistWire.RECEIVE_MS)){
            String id=active;clear();return id;
        }return null;
    }
    public synchronized Result accept(Protocol.Envelope e,long now,boolean locallyEnabled)
            throws ProtocolException {
        try {
            if(e==null||now<0||!locallyEnabled||epoch==null||!epoch.equals(e.sessionId)||!"glass".equals(e.sourceId))AssistWire.fail("AUTHORITY");
            if(expire(now)!=null)AssistWire.fail("DEADLINE");
            Map<String,Object> p=e.payload;
            if(!("assist.begin".equals(e.type)||"assist.chunk".equals(e.type)
                ||"assist.commit".equals(e.type)||"assist.cancel".equals(e.type)))AssistWire.fail("DIRECTION");
            AssistWire.validate(e.type,p);String id=AssistWire.id(p.get("request_id"));
            if("assist.begin".equals(e.type)){
                if(active!=null)AssistWire.fail("BUSY_OR_DUPLICATE");
                seen.reserve(id);active=id;started=now;
                image=new byte[AssistWire.number(p,"image_bytes",0,AssistWire.MAX_IMAGE)];
                audio=new byte[AssistWire.number(p,"audio_bytes",0,AssistWire.MAX_AUDIO)];
            }else {
                if(!id.equals(active))AssistWire.fail("REQUEST");
                if("assist.cancel".equals(e.type)){clear();return new Result(Result.Kind.CANCELLED,id,null,null);}
                if(committed)AssistWire.fail("DUPLICATE_COMMIT");
                if("assist.chunk".equals(e.type)){
                    boolean isImage="image".equals(p.get("kind"));byte[] dest=isImage?image:audio;
                    int at=isImage?imageAt:audioAt,index=isImage?imageIndex:audioIndex;
                    byte[] chunk=AssistWire.chunkBytes(p);
                    try {
                        if(AssistWire.number(p,"index",0,Integer.MAX_VALUE)!=index||chunk.length>dest.length-at)AssistWire.fail("ORDER_OR_BUDGET");
                        System.arraycopy(chunk,0,dest,at,chunk.length);
                        if(isImage){imageAt+=chunk.length;imageIndex++;}else{audioAt+=chunk.length;audioIndex++;}
                    }finally{Arrays.fill(chunk,(byte)0);}
                }else {
                    if(imageAt!=image.length||audioAt!=audio.length)AssistWire.fail("TOTAL");
                    if(!AssistWire.sha256(image).equals(p.get("image_sha256"))||!AssistWire.sha256(audio).equals(p.get("audio_sha256")))AssistWire.fail("INTEGRITY");
                    if(image.length>0)AssistWire.validateJpeg(image);if(audio.length>0)AssistWire.validateWav(audio);
                    committed=true;Result result=new Result(Result.Kind.COMPLETE,id,image,audio);image=null;audio=null;return result;
                }
            }
            return new Result(Result.Kind.NONE,id,null,null);
        }catch(ProtocolException failure){clear();throw failure;}
    }
    private void clear(){
        if(image!=null)Arrays.fill(image,(byte)0);if(audio!=null)Arrays.fill(audio,(byte)0);
        image=null;audio=null;active=null;imageAt=audioAt=imageIndex=audioIndex=0;committed=false;started=0;
    }
}
