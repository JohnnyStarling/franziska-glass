package lab.franziska.pixelbridge;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Talk speech and legacy Glass display have different, independently bounded budgets. */
final class GatewayTalkText {
    static final int MAX_TEXT_CHARS=32768;
    // JSON-quoted UTF-8 text, not Java chars: reserve space inside the unchanged
    // 65536-byte outgoing frame for two bounded IDs and the tool-result envelope.
    static final int MAX_TOOL_TEXT_JSON_BYTES=60000;
    static final int DISPLAY_CHARS=4000;
    private GatewayTalkText() {}

    static String finalText(Object message)throws Exception {
        if(!(message instanceof Map))return null;
        Map<?,?> m=(Map<?,?>)message;
        if(!"assistant".equals(m.get("role")))throw new GatewayException("ANSWER_INVALID");
        Object content=m.get("content");
        if(!(content instanceof List))return null;
        StringBuilder text=new StringBuilder();
        for(Object part:(List<?>)content){
            if(!(part instanceof Map))continue;
            Map<?,?> p=(Map<?,?>)part;Object type=p.get("type");
            if(!"text".equals(type)&&!"output_text".equals(type))continue;
            Object raw=p.get("text");
            if(!(raw instanceof String))throw new GatewayException("PROTOCOL_INVALID");
            String s=(String)raw;
            int separator=text.length()>0?1:0;
            if(s.length()>MAX_TEXT_CHARS-text.length()-separator)
                throw new GatewayException("TALK_ANSWER_TOO_LARGE");
            if(separator!=0)text.append('\n');
            text.append(s);
        }
        String answer=text.toString();
        byte[] encoded=GatewayJson.encode(answer);
        try{
            if(encoded.length>MAX_TOOL_TEXT_JSON_BYTES)
                throw new GatewayException("TALK_ANSWER_TOO_LARGE");
        }finally{Arrays.fill(encoded,(byte)0);}
        return answer;
    }

    /** Only the screen preview is clipped; the consult result for speech is never shortened. */
    static String displayText(String text){
        if(text.length()<=DISPLAY_CHARS)return text;
        int end=DISPLAY_CHARS-1;
        if(Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end)+"…";
    }
}
