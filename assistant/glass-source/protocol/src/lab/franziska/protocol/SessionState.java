package lab.franziska.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded receiver state. Stop is terminal until explicit start in a new epoch. */
public final class SessionState {
    public enum Result {
        ACCEPTED, DUPLICATE, STALE_SESSION, STALE_SEQUENCE, EXPIRED, STOPPED
    }

    private String sessionId;
    private String stoppedSessionId;
    private long lastSequence = -1L;
    private boolean stopped;
    private boolean restartHelloAccepted;
    private Protocol.Envelope latestUi;
    private final LinkedHashMap<String, Boolean> seen =
            new LinkedHashMap<String, Boolean>(129, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 128;
        }
    };

    public synchronized void beginEpoch(String id) {
        sessionId = id;
        stoppedSessionId = null;
        lastSequence = -1L;
        stopped = false;
        restartHelloAccepted = false;
        latestUi = null;
        seen.clear();
    }

    /** Reconnect resets sequence/dedupe, but intentionally preserves terminal Stop. */
    public synchronized void reconnectEpoch(String id) {
        sessionId = id;
        lastSequence = -1L;
        restartHelloAccepted = false;
        latestUi = null;
        seen.clear();
    }

    /**
     * TTL is a local receive-to-process budget. The sender's monotonic_timestamp is
     * metadata only: monotonic clock origins on separate devices are unrelated.
     */
    public synchronized Result accept(Protocol.Envelope envelope,
                                      long receivedMonotonic,
                                      long processedMonotonic) {
        if (sessionId == null || !sessionId.equals(envelope.sessionId)) {
            return Result.STALE_SESSION;
        }
        long localAge = processedMonotonic - receivedMonotonic;
        if (localAge < 0L || localAge > envelope.ttlMs) return Result.EXPIRED;

        String dedupeKey = envelope.idempotencyKey == null
                ? envelope.messageId : envelope.idempotencyKey;
        if (seen.containsKey(dedupeKey)) return Result.DUPLICATE;
        if (envelope.sequence <= lastSequence) return Result.STALE_SEQUENCE;

        if (stopped) {
            boolean newEpoch = !sessionId.equals(stoppedSessionId);
            if ("hello".equals(envelope.type) && newEpoch && lastSequence < 0L) {
                // Establish transport ordering only; hello never clears terminal Stop.
                restartHelloAccepted = true;
            } else if ("mode.start".equals(envelope.type)
                    && newEpoch && restartHelloAccepted) {
                stopped = false;
                stoppedSessionId = null;
                restartHelloAccepted = false;
            } else {
                // mode.start must be the immediately following increasing frame.
                restartHelloAccepted = false;
                return Result.STOPPED;
            }
        }

        seen.put(dedupeKey, Boolean.TRUE);
        lastSequence = envelope.sequence;
        if ("mode.stop".equals(envelope.type)) {
            stopped = true;
            stoppedSessionId = sessionId;
            restartHelloAccepted = false;
            latestUi = null;
        } else if (isUiState(envelope.type)) {
            latestUi = envelope;
        }
        return Result.ACCEPTED;
    }

    private static boolean isUiState(String type) {
        return "ui.card".equals(type) || "ui.vector_scene".equals(type)
                || "state".equals(type);
    }

    public synchronized void stopLocal() {
        stopped = true;
        stoppedSessionId = sessionId;
        restartHelloAccepted = false;
        latestUi = null;
    }

    /** True only while this message is still the current, non-stopped UI state. */
    public synchronized boolean shouldPresent(String messageId) {
        return !stopped && latestUi != null && latestUi.messageId.equals(messageId);
    }

    public synchronized boolean isStopped() { return stopped; }
    public synchronized String sessionId() { return sessionId; }
    public synchronized Protocol.Envelope latestUi() { return latestUi; }
}
