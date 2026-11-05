package lab.franziska.pixelbridge;

/** Pure lifecycle authority. All queued work carries this revision AND peer identity. */
public final class AssistAuthority {
    private long revision;
    private boolean enabled, capable;
    private Object peer;
    private String epoch;
    private String request, completed;
    private String state = "disabled";
    public synchronized long revision() { return revision; }
    public synchronized String state() { return state; }
    public synchronized boolean capable() { return capable && peer != null; }
    public synchronized void enable(boolean started, boolean configured) {
        invalidate(); enabled = started && configured; state = enabled ? "ready" : "disabled";
    }
    public synchronized void disable() { invalidate(); enabled = false; state = "disabled"; }
    public synchronized void peer(Object value, String epoch) { invalidate(); peer = value; this.epoch = epoch; capable = false; state = enabled ? "ready" : "disabled"; }
    public synchronized boolean hello(Object value, String epoch, String source, String type, boolean emptyPayload) {
        if (peer == null || peer != value || capable || this.epoch == null || !this.epoch.equals(epoch)
                || !"glass".equals(source) || !"assist.hello".equals(type) || !emptyPayload) return false;
        capable = true; return true;
    }
    public synchronized long begin(String id) {
        // A failed request stays terminal until its exact cancellation acknowledgement.
        // Otherwise a new begin could coalesce away the error before Glass sees it.
        if (!enabled || !capable() || request != null || "error".equals(state)) return -1;
        request = id; completed = null; state = "busy"; return ++revision;
    }
    public synchronized boolean current(long ticket, Object value, String id) {
        return enabled && capable() && peer == value && ticket == revision && id != null && id.equals(request);
    }
    public synchronized boolean writable(long ticket, Object value) {
        return capable() && peer == value && ticket == revision;
    }
    public synchronized void cancel(String id) { if (id != null && (id.equals(request) || id.equals(completed))) { invalidate(); state = enabled ? "ready" : "disabled"; } }
    public synchronized void finish(boolean success) { completed = request; request = null; state = enabled ? (success ? "ready" : "error") : "disabled"; }
    private void invalidate() { revision++; request = null; completed = null; }
}
