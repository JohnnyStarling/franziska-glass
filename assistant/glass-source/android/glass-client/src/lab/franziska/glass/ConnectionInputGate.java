package lab.franziska.glass;

/** Activity monitor owns all calls. Tickets use elapsed realtime, which includes device sleep. */
final class ConnectionInputGate {
    static final long MAX_EVENT_AGE_MS = 1500L;
    /** Fixed reasons; normalization and its diagnostic share the same clock predicate. */
    enum Rejection {
        NONE, CLOCK_NEGATIVE_EVENT, CLOCK_FUTURE_EVENT, CLOCK_ELAPSED_UNDERFLOW, CLOCK_STALE_EVENT,
        INACTIVE, NO_AUTH, WRONG_CONNECTION, WRONG_SESSION, NO_APPLIED_CARD, LATEST_NOT_CARD, CARD_CHANGED,
        BEFORE_CARD, FUTURE_EVENT, NEGATIVE_EVENT, DEADLINE_OVERFLOW, STALE_EVENT
    }
    static Rejection clockRejection(long eventUptime, long uptimeNow, long elapsedNow) {
        if (eventUptime < 0) return Rejection.CLOCK_NEGATIVE_EVENT;
        if (eventUptime > uptimeNow) return Rejection.CLOCK_FUTURE_EVENT;
        long age = uptimeNow - eventUptime;
        if (age >= MAX_EVENT_AGE_MS) return Rejection.CLOCK_STALE_EVENT;
        if (elapsedNow < age) return Rejection.CLOCK_ELAPSED_UNDERFLOW;
        return Rejection.NONE;
    }
    static long elapsedEventTime(long eventUptime, long uptimeNow, long elapsedNow) {
        // Only compare timestamps within their own domain: age is uptime-based.
        // Device clock origins may have either offset; never freshen or change the age.
        if (clockRejection(eventUptime, uptimeNow, elapsedNow) != Rejection.NONE) return -1;
        return elapsedNow - (uptimeNow - eventUptime);
    }
    private Object connection;
    private String session, card;
    private long appliedAt;
    static final class Ticket {
        final Object connection;
        final String session, card;
        final long eventAt, deadline;
        Ticket(Object connection, String session, String card, long eventAt) {
            this.connection = connection; this.session = session; this.card = card;
            this.eventAt = eventAt; this.deadline = eventAt + MAX_EVENT_AGE_MS;
        }
    }
    void clear() { connection = null; session = null; card = null; }
    void authenticated(Object ownedConnection, String acceptedSession) {
        clear();
        if (ownedConnection != null && acceptedSession != null) {
            connection = ownedConnection; session = acceptedSession;
        }
    }
    void clearCard() { card = null; }
    boolean matches(Object ownedConnection, String currentSession) {
        return connection != null && connection == ownedConnection
                && session.equals(currentSession);
    }
    void applied(Object ownedConnection, String currentSession, String messageId, long now) {
        if (matches(ownedConnection, currentSession)) { card = messageId; appliedAt = now; }
    }
    Ticket admit(Object ownedConnection, String currentSession, boolean active,
                 String latestCard, long eventAt, long now) {
        if (!active || !matches(ownedConnection, currentSession) || card == null
                || !card.equals(latestCard) || eventAt < appliedAt || eventAt > now
                || eventAt < 0 || eventAt > Long.MAX_VALUE - MAX_EVENT_AGE_MS
                || now - eventAt >= MAX_EVENT_AGE_MS) return null;
        return new Ticket(connection, session, card, eventAt);
    }
    Rejection rejection(Object ownedConnection, String currentSession, boolean active,
                        String latestCard, long eventAt, long now) {
        if (!active) return Rejection.INACTIVE;
        if (connection == null) return Rejection.NO_AUTH;
        if (connection != ownedConnection) return Rejection.WRONG_CONNECTION;
        if (!session.equals(currentSession)) return Rejection.WRONG_SESSION;
        if (card == null) return Rejection.NO_APPLIED_CARD;
        if (latestCard == null) return Rejection.LATEST_NOT_CARD;
        if (!card.equals(latestCard)) return Rejection.CARD_CHANGED;
        if (eventAt < appliedAt) return Rejection.BEFORE_CARD;
        if (eventAt > now) return Rejection.FUTURE_EVENT;
        if (eventAt < 0) return Rejection.NEGATIVE_EVENT;
        if (eventAt > Long.MAX_VALUE - MAX_EVENT_AGE_MS) return Rejection.DEADLINE_OVERFLOW;
        if (now - eventAt >= MAX_EVENT_AGE_MS) return Rejection.STALE_EVENT;
        return Rejection.NONE;
    }
    boolean writable(Ticket ticket, Object ownedConnection, String currentSession,
                     boolean active, String latestCard, long now) {
        return ticket != null && active && matches(ownedConnection, currentSession)
                && ticket.connection == connection && ticket.session.equals(session)
                && card != null && card.equals(ticket.card) && card.equals(latestCard)
                && now >= ticket.eventAt && now < ticket.deadline;
    }
}
