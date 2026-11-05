package lab.franziska.pixelbridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded display-only projection of Gateway deltas, snapshots and finalized utterances.
 * No audio, sensor, transport or logging side effects. One instance per relay request.
 */
final class GatewayTalkTranscript {
    static final class Snapshot {
        final String id,text;
        final boolean isFinal;
        Snapshot(String id,String text,boolean isFinal){this.id=id;this.text=text;this.isFinal=isFinal;}
    }
    private static final class Role {
        String id,prefix="",lastFinal,lastFinalId;
    }
    private final Role user=new Role(),assistant=new Role();
    private final LinkedHashMap<String,Boolean> finalIds=new LinkedHashMap<String,Boolean>();
    private long sequence;

    Snapshot accept(String role,String text,boolean isFinal,boolean snapshot,Object rawFinalId){
        Role state="user".equals(role)?user:"assistant".equals(role)?assistant:null;
        if(state==null)return null;
        // Gateway IDs are not forwarded: generated wire IDs have a small fixed bound.
        String finalId=rawFinalId instanceof String&&((String)rawFinalId).length()<=512
                &&!((String)rawFinalId).isEmpty() ? role+":"+rawFinalId : null;
        if(isFinal&&finalId!=null&&finalIds.containsKey(finalId))return null;
        if(text.isEmpty()&&state.id==null)return null;
        // Old Gateways have no replay identity. Adjacent identical finals without a
        // new partial cannot be distinguished from duplicate delivery. A new final
        // ID or any intervening partial establishes a distinct utterance.
        if(isFinal&&state.id==null&&finalId==null&&state.lastFinalId==null&&text.equals(state.lastFinal))return null;
        // Opposite-role activity is also evidence of a new conversation turn on
        // old Gateways; do not collapse a repeated answer across that boundary.
        Role other=state==user?assistant:user;other.lastFinal=null;other.lastFinalId=null;
        if(state.id==null)state.id="u"+(++sequence);
        if(isFinal||snapshot){
            if(!text.isEmpty()||!isFinal)state.prefix=prefix(text);
        }else if(state.prefix.length()<=GatewayTalkText.DISPLAY_CHARS){
            int remaining=GatewayTalkText.DISPLAY_CHARS+1-state.prefix.length();
            state.prefix+=text.substring(0,Math.min(remaining,text.length()));
        }
        String preview=GatewayTalkText.displayText(state.prefix);
        if(!preview.isEmpty()&&Character.isHighSurrogate(preview.charAt(preview.length()-1)))
            preview=preview.substring(0,preview.length()-1);
        Snapshot result=preview.isEmpty()?null:new Snapshot(state.id,preview,isFinal);
        if(isFinal){
            state.lastFinal=text; // bounded by the unchanged Gateway parser's 32768-char limit
            state.lastFinalId=finalId;
            state.id=null;state.prefix="";
            if(finalId!=null){
                finalIds.put(finalId,Boolean.TRUE);
                if(finalIds.size()>32)finalIds.remove(finalIds.keySet().iterator().next());
            }
        }
        return result;
    }
    private static String prefix(String text){
        return text.substring(0,Math.min(text.length(),GatewayTalkText.DISPLAY_CHARS+1));
    }
}
