package lab.franziska.protocol;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;

/** Pull exactly one frame per existing TLS writer iteration. Never enqueue or retry media.
 * Takes ownership of arrays. Cancel wipes unsent bytes. A failed write cancels the whole pump. */
public final class AssistPump {
    public static final class Frame {
        public final String type;
        public final Map<String,Object> payload;
        Frame(String t,Map<String,Object> p){type=t;payload=p;}
    }
    public final String requestId, epoch;
    private byte[] image,audio;
    private final String imageHash,audioHash;
    private int stage,offset,index;
    private final long started;
    public AssistPump(String id,String epoch,byte[] image,byte[] audio,long now) throws ProtocolException {
        this.requestId=AssistWire.id(id);this.epoch=epoch;
        if(image.length>AssistWire.MAX_IMAGE||audio.length>AssistWire.MAX_AUDIO||image.length+audio.length==0)AssistWire.fail("SIZE");
        if(image.length>0)AssistWire.validateJpeg(image);if(audio.length>0)AssistWire.validateWav(audio);
        this.image=image;this.audio=audio;imageHash=AssistWire.sha256(image);audioHash=AssistWire.sha256(audio);started=now;
    }
    public synchronized Frame next(String currentEpoch,long now) throws ProtocolException {
        if(stage==4)return null;
        if(!epoch.equals(currentEpoch)||now<started||now-started>=25000L){cancel();AssistWire.fail("SEND_DEADLINE_OR_EPOCH");}
        if(stage==0){stage=1;return new Frame("assist.begin",AssistWire.payload("request_id",requestId,"image_bytes",image.length,"audio_bytes",audio.length));}
        while(stage==1||stage==2){
            byte[] bytes=stage==1?image:audio;
            if(offset==bytes.length){stage++;offset=0;index=0;continue;}
            ArrayList<String> parts=new ArrayList<String>(AssistWire.MAX_CHUNK_PARTS);
            for(int i=0;i<AssistWire.MAX_CHUNK_PARTS&&offset<bytes.length;i++){
                int count=Math.min(AssistWire.CHUNK,bytes.length-offset);
                parts.add(AssistWire.encode(bytes,offset,count));offset+=count;
            }
            return new Frame("assist.chunk",AssistWire.payload("request_id",requestId,"kind",stage==1?"image":"audio","index",index++,"parts",parts));
        }
        Frame commit=new Frame("assist.commit",AssistWire.payload("request_id",requestId,"image_sha256",imageHash,"audio_sha256",audioHash));
        cancel();return commit;
    }
    public synchronized boolean done(){return stage==4;}
    public synchronized void cancel(){if(image!=null)Arrays.fill(image,(byte)0);if(audio!=null)Arrays.fill(audio,(byte)0);image=null;audio=null;stage=4;}
}
