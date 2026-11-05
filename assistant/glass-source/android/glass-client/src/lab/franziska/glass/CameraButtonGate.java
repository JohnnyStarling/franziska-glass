package lab.franziska.glass;

/** One claimed local camera-key press. No Android APIs, sensors, transport or new authority.
 * Only the foreground owner decides whether to claim a DOWN. Cancellation retains the
 * claimed release so an invalidated gesture cannot fall through to the stock camera.
 */
final class CameraButtonGate {
    static final long MAX_PRESS_MS = 500L;
    static final long MAX_EVENT_AGE_MS = 1000L;
    private boolean claimed, valid;
    private Object owner;
    private String epoch, request;
    private long revision, downAt, lastDown = -1L;

    synchronized boolean claimed() { return claimed; }

    synchronized void press(Object peer, String session, String talkRequest, long currentRevision,
                            long down, long eventAt, long now, int repeat, boolean cancelled,
                            boolean eligible) {
        if (repeat != 0) { claimed = true; valid = false; return; }
        boolean clockValid = down >= 0 && eventAt >= down && now >= eventAt
                && eventAt - down < MAX_PRESS_MS && now - eventAt <= MAX_EVENT_AGE_MS;
        boolean fresh = clockValid && down > lastDown;
        if (fresh) lastDown = down;
        claimed = true;
        owner = peer; epoch = session; request = talkRequest; revision = currentRevision; downAt = down;
        valid = fresh && !cancelled && eligible && peer != null && session != null
                && talkRequest != null && currentRevision >= 0 && down >= 0
                && eventAt >= down && now >= eventAt && eventAt - down < MAX_PRESS_MS
                && now - eventAt <= MAX_EVENT_AGE_MS;
    }

    synchronized boolean release(Object peer, String session, String talkRequest, long currentRevision,
                                 long down, long eventAt, long now, boolean cancelled, boolean eligible) {
        boolean admit = claimed && valid && !cancelled && eligible && owner == peer
                && epoch != null && epoch.equals(session) && request != null && request.equals(talkRequest)
                && revision == currentRevision && downAt == down && eventAt >= downAt
                && eventAt - downAt < MAX_PRESS_MS && now >= eventAt
                && now - eventAt <= MAX_EVENT_AGE_MS;
        claimed = valid = false;
        owner = null; epoch = request = null;
        return admit;
    }

    synchronized void cancel() { valid = false; }
}
