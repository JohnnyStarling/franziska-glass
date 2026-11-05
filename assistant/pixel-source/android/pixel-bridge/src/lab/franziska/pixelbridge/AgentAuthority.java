package lab.franziska.pixelbridge;

/** In-memory authority. All methods require the supplied service monitor; no IO here.
 * Admission linearizes at check/writeAllowed, not at eventual network completion.
 */
public final class AgentAuthority {
    public interface Work { Request agentRequest(); }
    public static final class Request {
        final long listener, service, deadline, peerRevision;
        final Object peer;
        Request(long l, long s, long d, Object p, long r) { listener=l; service=s; deadline=d; peer=p; peerRevision=r; }
    }
    private final Object monitor;
    private long listener, service, peerRevision;
    private Object peer, readyPeer;
    private long readyService = -1;
    private boolean running, enabled, stopped = true, paired;
    public AgentAuthority(Object monitor) { this.monitor=monitor; }
    private void locked() { if (!Thread.holdsLock(monitor)) throw new IllegalStateException("authority monitor required"); }
    public void start(long generation, boolean validatedPair) {
        locked(); disable(); service=generation; running=true; stopped=false; paired=validatedPair; peer=null; peerRevision++; clearReady();
    }
    public long beginEnable() { locked(); disable(); return listener; }
    public boolean install(long ticket) { locked(); if(ticket!=listener || !running || stopped) return false; enabled=true; return true; }
    public void disable() { locked(); enabled=false; listener++; }
    public void purge(java.util.Collection<? extends Work> queue) {
        locked(); java.util.Iterator<? extends Work> it = queue.iterator();
        while (it.hasNext()) if (it.next().agentRequest()!=null) it.remove();
    }
    public boolean listenerFailed(long ticket) {
        locked(); if (!currentEnable(ticket)) return false; disable(); return true;
    }
    public void stop() { locked(); disable(); stopped=true; clearReady(); }
    public void shutdown() { locked(); stop(); running=false; peer=null; }
    public void revoke() { locked(); shutdown(); paired=false; }
    public boolean currentEnable(long ticket) { locked(); return ticket==listener && running && !stopped; }
    public boolean enabled() { locked(); return enabled; }
    public boolean paired() { locked(); return paired; }
    public void ownPeer(long generation, Object socket) { locked(); if(generation!=service || !running) return; peer=socket; peerRevision++; clearReady(); }
    public void losePeer(Object socket) { locked(); if(peer==socket) { peer=null; peerRevision++; clearReady(); } }
    private void clearReady() { readyPeer=null; readyService=-1; }
    public boolean ready(long generation, Object socket) {
        locked(); if(!running || stopped || generation!=service || socket==null || socket!=peer) return false;
        readyPeer=socket; readyService=generation; return true;
    }
    public boolean connected() { locked(); return running && !stopped && peer!=null && readyPeer==peer && readyService==service; }
    public Request capture(long ticket, long deadline) { locked(); return new Request(ticket,service,deadline,peer,peerRevision); }
    public boolean check(Request request, long now) {
        locked(); return request!=null && enabled && running && !stopped && request.listener==listener
            && request.service==service && request.peer==peer && request.peerRevision==peerRevision && now-request.deadline<0;
    }
    public boolean writeAllowed(Request request, Object socket) {
        locked(); return request==null || (enabled && connected() && request.listener==listener
            && request.service==service && request.peerRevision==peerRevision && request.peer==socket && peer==socket);
    }
}
