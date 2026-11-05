package lab.franziska.pixelbridge;

/** Process-local pairing generation, shared by native setup and service Stop/disable/revoke. */
public final class AssistPairing {
    public interface Cancellation { void cancel(); }
    public static final AssistPairing INSTANCE = new AssistPairing();
    private long revision, owner;
    private boolean busy;
    private Cancellation cancellation;
    public synchronized long begin() {
        if (busy) return -1;
        busy=true; owner=++revision; return owner;
    }
    public synchronized boolean current(long ticket) { return busy && owner==ticket && revision==ticket; }
    public synchronized boolean install(long ticket, Cancellation value) {
        if (!current(ticket)) { value.cancel(); return false; }
        cancellation=value; return true;
    }
    public synchronized void invalidate() {
        revision++;
        if(cancellation!=null)cancellation.cancel();
    }
    /** Linearization point: cancel before settle makes cleanup mandatory; after settle pairing is done. */
    public synchronized boolean settle(long ticket) {
        boolean valid=current(ticket);
        if(busy && owner==ticket)cancellation=null;
        return valid;
    }
    public synchronized boolean revisionMatches(long ticket) { return revision==ticket; }
    public synchronized void finish(long ticket) {
        if(busy && owner==ticket){busy=false;cancellation=null;}
    }
}
