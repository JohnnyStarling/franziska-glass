package lab.franziska.glass;

/** One short-lived call prompt. Only GlassActivity's physical input path can confirm it. */
final class GlassCallPrompt {
    static final long LOCAL_WINDOW_MS = 25000L; // Pixel's ticket lasts at most 30 seconds.
    enum Stage { NONE, READY, CONFIRM_QUEUED, WAITING_RESULT, RESULT }

    private Stage stage = Stage.NONE;
    private String requestId, label, e164, result;
    private Object peer;
    private String epoch;
    private long expiresAt;
    private long inputBoundary;

    // now is elapsedRealtime; inputBoundary and physical gesture down-times are uptimeMillis.
    void begin(String id, String name, String number, Object socket, String session,
               long now, long inputBoundary) {
        if (socket == null || session == null || now < 0 || inputBoundary < 0)
            throw new IllegalArgumentException("call link");
        requestId = id; label = name; e164 = number; result = null;
        peer = socket; epoch = session; expiresAt = now + LOCAL_WINDOW_MS;
        this.inputBoundary = inputBoundary;
        stage = Stage.READY;
    }

    String confirm(Object socket, String session, long now, long gestureDown, boolean foreground) {
        // A release from a gesture begun on the previous screen cannot confirm this call.
        if (stage != Stage.READY || !owns(socket, session) || !foreground || now >= expiresAt
                || gestureDown <= inputBoundary) return null;
        stage = Stage.CONFIRM_QUEUED;
        return requestId;
    }

    boolean writableConfirmation(String id, Object socket, String session, long now, boolean foreground) {
        return stage == Stage.CONFIRM_QUEUED && requestId.equals(id) && owns(socket, session)
                && foreground && now < expiresAt;
    }

    void confirmationWritten(String id, Object socket, String session) {
        if (stage == Stage.CONFIRM_QUEUED && requestId.equals(id) && owns(socket, session))
            stage = Stage.WAITING_RESULT;
    }

    String cancel(Object socket, String session) {
        if (stage != Stage.READY || !owns(socket, session)) return null;
        stage = Stage.RESULT; result = "CANCELLED";
        return requestId;
    }

    String expire(long now) {
        if (stage != Stage.READY || now < expiresAt) return null;
        stage = Stage.RESULT; result = "EXPIRED";
        return requestId;
    }

    boolean result(String id, String status, Object socket, String session) {
        if ((stage != Stage.CONFIRM_QUEUED && stage != Stage.WAITING_RESULT && stage != Stage.READY)
                || !requestId.equals(id) || !owns(socket, session)) return false;
        stage = Stage.RESULT; result = status;
        return true;
    }

    void failUncertain() {
        if (stage == Stage.CONFIRM_QUEUED || stage == Stage.WAITING_RESULT) {
            stage = Stage.RESULT; result = "UNKNOWN";
        } else if (stage == Stage.READY) {
            stage = Stage.RESULT; result = "CANCELLED";
        }
        peer = null; epoch = null;
    }

    void failedConfirmation(String id, Object socket, String session) {
        if (stage == Stage.CONFIRM_QUEUED && requestId.equals(id) && owns(socket, session))
            failUncertain();
    }

    void clear() {
        stage = Stage.NONE; requestId = label = e164 = result = epoch = null;
        peer = null; expiresAt = inputBoundary = 0;
    }

    private boolean owns(Object socket, String session) {
        return socket != null && peer == socket && epoch != null && epoch.equals(session);
    }

    boolean visible() { return stage != Stage.NONE; }
    boolean mayHaveStarted() {
        return stage == Stage.CONFIRM_QUEUED || stage == Stage.WAITING_RESULT
                || (stage == Stage.RESULT && ("REQUESTED".equals(result) || "UNKNOWN".equals(result)));
    }
    Stage stage() { return stage; }
    String id() { return requestId; }
    String label() { return label; }
    String e164() { return e164; }
    String outcome() { return result; }
}
