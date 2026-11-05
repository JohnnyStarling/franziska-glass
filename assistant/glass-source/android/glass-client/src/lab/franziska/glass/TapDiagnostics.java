package lab.franziska.glass;

/** TEMP: fixed vocabulary, no event data; one lifetime budget per Activity. */
final class TapDiagnostics {
    enum Category {
        KEY_CONFIRM, KEY_NAV, KEY_OTHER,
        TOUCH_DOWN_ACCEPTED_SINGLE, TOUCH_UP_ACCEPTED_SINGLE, TOUCH_CANCEL_ACCEPTED_SINGLE,
        TOUCH_DOWN_OTHER_SINGLE, TOUCH_UP_OTHER_SINGLE, TOUCH_CANCEL_OTHER_SINGLE,
        TOUCH_DOWN_ACCEPTED_MULTI, TOUCH_UP_ACCEPTED_MULTI, TOUCH_CANCEL_ACCEPTED_MULTI,
        TOUCH_DOWN_OTHER_MULTI, TOUCH_UP_OTHER_MULTI, TOUCH_CANCEL_OTHER_MULTI,
        GENERIC_DOWN_ACCEPTED_SINGLE, GENERIC_UP_ACCEPTED_SINGLE, GENERIC_CANCEL_ACCEPTED_SINGLE,
        GENERIC_DOWN_OTHER_SINGLE, GENERIC_UP_OTHER_SINGLE, GENERIC_CANCEL_OTHER_SINGLE,
        GENERIC_DOWN_ACCEPTED_MULTI, GENERIC_UP_ACCEPTED_MULTI, GENERIC_CANCEL_ACCEPTED_MULTI,
        GENERIC_DOWN_OTHER_MULTI, GENERIC_UP_OTHER_MULTI, GENERIC_CANCEL_OTHER_MULTI,
        RECOGNIZED, REJECTED, DEDUPED, GATE_ADMITTED, GATE_REJECTED,
        WRITER_VALIDATED, WRITE_SUCCESS, WRITER_REJECT,
        GATE_NONE, GATE_CLOCK_NEGATIVE_EVENT, GATE_CLOCK_FUTURE_EVENT,
        GATE_CLOCK_INVALID_ORDER, GATE_CLOCK_ELAPSED_UNDERFLOW, GATE_CLOCK_STALE_EVENT, GATE_INACTIVE,
        GATE_NO_AUTH, GATE_WRONG_CONNECTION, GATE_WRONG_SESSION,
        GATE_NO_APPLIED_CARD, GATE_LATEST_NOT_CARD, GATE_CARD_CHANGED,
        GATE_BEFORE_CARD, GATE_FUTURE_EVENT, GATE_NEGATIVE_EVENT,
        GATE_DEADLINE_OVERFLOW, GATE_STALE_EVENT,
        RAW_EVENT_RECENT_ELAPSED, RAW_EVENT_NOT_RECENT_ELAPSED
    }
    interface Sink { void log(Category category); }
    static Category gate(ConnectionInputGate.Rejection reason) {
        return Category.valueOf("GATE_" + reason.name());
    }
    /** Diagnostic comparison only, never a fallback conversion or admission path. */
    static Category rawElapsedRecency(long event, long elapsed) {
        return event >= 0 && elapsed >= event
                && elapsed - event < ConnectionInputGate.MAX_EVENT_AGE_MS
                ? Category.RAW_EVENT_RECENT_ELAPSED : Category.RAW_EVENT_NOT_RECENT_ELAPSED;
    }
    private long first = -1;
    private int entries;
    private boolean resumed, focused;
    synchronized void lifecycle(boolean resume, boolean focus, long now) {
        if (resume && first < 0 && now >= 0) first = now;
        resumed = resume; focused = focus;
    }
    synchronized void record(Category category, long now, Sink sink) {
        if (!resumed || !focused || first < 0 || now < first || now - first >= 120000
                || entries >= 64 || category == null) return;
        entries++;
        sink.log(category);
    }
    static Category motion(boolean generic, int action, boolean accepted, int count) {
        int offset = action == 0 ? 0 : action == 1 ? 1 : action == 3 ? 2 : -1;
        if (offset < 0) return null;
        return Category.values()[3 + (generic ? 12 : 0) + (count == 1 ? 0 : 6)
                + (accepted ? 0 : 3) + offset];
    }
}
