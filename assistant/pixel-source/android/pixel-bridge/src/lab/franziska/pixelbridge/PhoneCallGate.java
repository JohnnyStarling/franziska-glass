package lab.franziska.pixelbridge;

import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

/** One pending cellular call at a time. A trusted transport must supply glassPeerId,
 * not accept it from a model-generated payload. This class never places a call.
 */
public final class PhoneCallGate {
    public static final long CONFIRM_WINDOW_MS = 30000L;

    public static final class Ticket {
        public final String requestId, glassPeerId, label, e164;
        public final long expiresAtElapsedMs;
        private Ticket(String requestId, String glassPeerId, String label, String e164, long expiresAtElapsedMs) {
            this.requestId=requestId;this.glassPeerId=glassPeerId;this.label=label;this.e164=e164;
            this.expiresAtElapsedMs=expiresAtElapsedMs;
        }
    }

    private Ticket pending;
    private final Set<String> retired=new HashSet<String>();

    /** Use SystemClock.elapsedRealtime() for nowElapsedMs. Rejects MMI/USSD, short codes,
     * extensions, and unnormalized local numbers; contact resolution must precede prepare.
     */
    public synchronized Ticket prepare(String requestId,String glassPeerId,String label,String e164,long nowElapsedMs) {
        if(requestId==null||!UUID.fromString(requestId).toString().equals(requestId))throw new IllegalArgumentException("request id");
        if(glassPeerId==null||glassPeerId.isEmpty()||glassPeerId.length()>128)throw new IllegalArgumentException("glass peer");
        if(label==null||label.trim().isEmpty()||label.length()>120||hasUnsafeText(label))throw new IllegalArgumentException("label");
        if(e164==null||!e164.matches("\\+[1-9][0-9]{6,14}"))throw new IllegalArgumentException("E.164 number");
        if(nowElapsedMs<0||nowElapsedMs>Long.MAX_VALUE-CONFIRM_WINDOW_MS)throw new IllegalArgumentException("time");
        if(pending!=null&&nowElapsedMs>=pending.expiresAtElapsedMs){retired.add(pending.requestId);pending=null;}
        if(retired.contains(requestId))throw new IllegalArgumentException("call request already retired");
        if(pending!=null&&pending.requestId.equals(requestId)){
            if(!pending.glassPeerId.equals(glassPeerId)||!pending.label.equals(label.trim())||!pending.e164.equals(e164))
                throw new IllegalArgumentException("request id reused for different call");
            return pending; // Retransmission cannot change the number or extend the expiry.
        }
        if(retired.size()>=4096)throw new IllegalStateException("call replay capacity; durable broker ledger required");
        if(pending!=null)retired.add(pending.requestId);
        pending=new Ticket(requestId,glassPeerId,label.trim(),e164,nowElapsedMs+CONFIRM_WINDOW_MS);
        return pending;
    }

    /** Only call after an explicit Glass gesture/voice confirmation was authenticated by
     * the Glass transport. A matching ticket is consumed before the caller invokes Telecom.
     */
    public synchronized String consumeConfirmed(String requestId,String authenticatedGlassPeerId,long nowElapsedMs) {
        if(nowElapsedMs<0)return null;
        if(pending==null)return null;
        if(nowElapsedMs>=pending.expiresAtElapsedMs){retired.add(pending.requestId);pending=null;return null;}
        if(!pending.requestId.equals(requestId)||!pending.glassPeerId.equals(authenticatedGlassPeerId))return null;
        String number=pending.e164;retired.add(pending.requestId);pending=null;return number;
    }

    public synchronized boolean cancel(String requestId,String authenticatedGlassPeerId) {
        if(pending==null||!pending.requestId.equals(requestId)||!pending.glassPeerId.equals(authenticatedGlassPeerId))return false;
        retired.add(pending.requestId);pending=null;return true;
    }

    public synchronized Ticket pending(long nowElapsedMs) {
        if(pending!=null&&nowElapsedMs>=pending.expiresAtElapsedMs){retired.add(pending.requestId);pending=null;}
        return pending;
    }

    private static boolean hasUnsafeText(String text) {
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(c<32||c==127||(c>=0x202a&&c<=0x202e)||(c>=0x2066&&c<=0x2069))return true;
        }
        return false;
    }
}
