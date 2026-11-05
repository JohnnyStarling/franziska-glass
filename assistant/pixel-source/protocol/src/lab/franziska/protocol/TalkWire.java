package lab.franziska.protocol;

import java.util.Map;
import java.util.List;

/** Bounded PCM relay frames on the already authenticated Glass-Pixel link. */
public final class TalkWire {
    public static final int MAX_PCM = 3072;
    public static final long MAX_SESSION_MS = 3600000L;
    /** Sender may have at most 1.5 seconds of output ahead of Glass's completed writes. */
    public static final int OUTPUT_WINDOW_BYTES = 72000;
    public static final int BATCH_BYTES_LIMIT = 23040;
    public static final int MAX_OUTPUT_CREDIT_BYTES = 180000000;
    private TalkWire() {}

    public static void validate(String type, Map<String,Object> p) throws ProtocolException {
        if (p == null) fail("FIELDS");
        if ("talk.start".equals(type)) {
            if(p.containsKey("text_version")){
                AssistWire.fields(p,"request_id","text_version");
                AssistWire.number(p,"text_version",2,2);
            }else AssistWire.fields(p,"request_id");
        } else if ("talk.ready".equals(type) || "talk.stop".equals(type) || "talk.clear".equals(type) || "talk.camera".equals(type)) {
            AssistWire.fields(p,"request_id");
        } else if ("talk.audio".equals(type)) {
            AssistWire.number(p,"index",0,Integer.MAX_VALUE);
            if(p.containsKey("parts")){
                AssistWire.fields(p,"request_id","index","parts");
                Object raw=p.get("parts");
                if(!(raw instanceof List)||((List<?>)raw).size()<1||((List<?>)raw).size()>8)fail("PARTS");
                int total=0;
                for(Object part:(List<?>)raw)total+=validatePcm(part);
                if(total>23040)fail("PCM");
            }else{
                AssistWire.fields(p,"request_id","index","data");
                validatePcm(p.get("data"));
            }
        } else if ("talk.flow".equals(type)) {
            AssistWire.fields(p,"request_id","released_bytes");
            if((AssistWire.number(p,"released_bytes",0,MAX_OUTPUT_CREDIT_BYTES)&1)!=0)fail("CREDIT");
        } else if ("talk.text".equals(type)) {
            if(p.containsKey("utterance_id")||p.containsKey("final")){
                AssistWire.fields(p,"request_id","role","text","utterance_id","final");
                AssistWire.text(p,"utterance_id",1,64);
                if(!(p.get("final") instanceof Boolean))fail("FINAL");
            }else AssistWire.fields(p,"request_id","role","text");
            String role=AssistWire.text(p,"role",4,9);
            if(!"user".equals(role)&&!"assistant".equals(role))fail("ROLE");
            AssistWire.text(p,"text",1,4000);
        } else if ("talk.error".equals(type)) {
            AssistWire.fields(p,"request_id","code");
            String code=AssistWire.text(p,"code",1,64);
            if(!code.matches("[A-Z][A-Z0-9_]*"))fail("CODE");
        } else fail("TYPE");
        AssistWire.id(p.get("request_id"));
    }

    /** Called after strict payload validation; opt-in belongs to this request only. */
    public static boolean textV2(Map<String,Object> start) {
        Object version=start.get("text_version");
        return version instanceof Number && ((Number)version).doubleValue()==2.0;
    }

    /** Coalesce snapshots only within one utterance; legacy peers keep role coalescing. */
    public static boolean sameTextSlot(Map<String,Object> a,Map<String,Object> b) {
        if(!a.get("request_id").equals(b.get("request_id"))||!a.get("role").equals(b.get("role")))return false;
        Object first=a.get("utterance_id"),second=b.get("utterance_id");
        return first==null ? second==null : first.equals(second);
    }

    private static int validatePcm(Object encoded) throws ProtocolException {
        int length=AssistWire.decodedLength(AssistWire.boundedText(encoded,4,4096));
        if(length<2||length>MAX_PCM||(length&1)!=0)fail("PCM");
        return length;
    }

    private static void fail(String code) throws ProtocolException { throw new ProtocolException("TALK_"+code); }
}
