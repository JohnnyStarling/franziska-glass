package lab.franziska.glass;

/** Local, payload-free authority for one voiced-photo handoff and its acknowledgment.
 * The caller holds the Activity owner lock. This never opens a sensor or grants readiness.
 */
final class VoicePhotoGate {
    static final long HANDOFF_TIMEOUT_MS = 5000L;
    static final long ACK_TIMEOUT_MS = 30000L;
    private static final int NONE = 0, HANDOFF = 1, ACK = 2;
    private int phase;
    private Object owner;
    private String epoch;
    private long revision, admittedAt, deadline;
    private boolean spoken;

    boolean handoff(Object peer, String session, long afterStopRevision,
                    long receivedAt, long now, long frameTtl, long talkDeadline) {
        if (phase != NONE || peer == null || session == null || afterStopRevision < 0
                || receivedAt < 0 || now < receivedAt || frameTtl <= 0) return false;
        long budget = Math.min(HANDOFF_TIMEOUT_MS, frameTtl);
        if (receivedAt > Long.MAX_VALUE - budget) return false;
        long until = Math.min(receivedAt + budget, talkDeadline);
        if (now >= until) return false;
        set(HANDOFF, peer, session, afterStopRevision, receivedAt, until);
        return true;
    }

    boolean consumeHandoff(Object peer, String session, long afterStopRevision, long now) {
        if (!matches(HANDOFF, peer, session, afterStopRevision, now)) return false;
        clear(); return true;
    }

    boolean acknowledgment(Object peer, String session, long answerRevision, long now) {
        if (phase != NONE || peer == null || session == null || answerRevision < 0
                || now < 0 || now > Long.MAX_VALUE - ACK_TIMEOUT_MS) return false;
        set(ACK, peer, session, answerRevision, now, now + ACK_TIMEOUT_MS);
        return true;
    }

    boolean finished(Object peer, String session, long answerRevision, long now) {
        if (!matches(ACK, peer, session, answerRevision, now)) return false;
        spoken = true; return true;
    }

    boolean consumeResume(Object peer, String session, long answerRevision, long now) {
        if (!spoken || !matches(ACK, peer, session, answerRevision, now)) return false;
        clear(); return true;
    }

    boolean handoffPending() { return phase == HANDOFF; }
    boolean acknowledgmentPending() { return phase == ACK; }
    boolean expired(long now) { return phase != NONE && (now < admittedAt || now >= deadline); }

    void clear() {
        phase = NONE; owner = null; epoch = null; revision = admittedAt = deadline = 0;
        spoken = false;
    }

    private boolean matches(int expected, Object peer, String session, long currentRevision, long now) {
        return phase == expected && owner == peer && epoch != null && epoch.equals(session)
                && revision == currentRevision && !expired(now);
    }

    private void set(int next, Object peer, String session, long currentRevision, long start, long until) {
        phase = next; owner = peer; epoch = session; revision = currentRevision;
        admittedAt = start; deadline = until; spoken = false;
    }
}
