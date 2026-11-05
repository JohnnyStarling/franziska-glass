package lab.franziska.glass;

/** Caller owns synchronization. Elapsed realtime includes sleep; timers never renew. */
final class ReadingScreenPolicy {
    static final long GRACE_MS = 120000L;
    private Object connection;
    private String session, card;
    private long deadline, revision;
    void clear() { connection = null; session = null; card = null; release(); }
    void release() { deadline = 0; revision++; }
    void applied(Object connection, String session, String card) {
        clear();
        this.connection = connection; this.session = session; this.card = card;
    }
    boolean eligible(Object connection, String session, String card, boolean live,
                     boolean stopped, boolean resumed, boolean focused) {
        return live && !stopped && resumed && focused && this.connection != null
                && this.connection == connection && this.session != null
                && this.session.equals(session) && this.card != null && this.card.equals(card);
    }
    void renew(long now, boolean eligible) {
        release();
        if (eligible) deadline = now + GRACE_MS;
    }
    long revision() { return revision; }
    long remaining(long now, boolean eligible, long callbackRevision) {
        if (callbackRevision != revision) return 0;
        if (!eligible || deadline == 0 || now >= deadline) { release(); return 0; }
        return deadline - now;
    }
}
