package lab.franziska.glass;

/** Local display only. No admission, transport, progress, ACK or screen-hold authority. */
final class InputFeedback {
    static final long DURATION_MS = 1200L;
    enum Stage {
        NONE(""), RECOGNIZED("Касание распознано"), NOT_SENT("Касание · не отправлено"),
        QUEUED("Допущено · ждём Pixel"), SENT("Отправлено · ждём Pixel"),
        UNCERTAIN("Отправка не подтверждена"), CARD_APPLIED("Карточка с Pixel");
        final String text;
        Stage(String text) { this.text = text; }
    }
    private Stage stage = Stage.NONE;
    private ConnectionInputGate.Ticket ticket;
    private long startedAt, deadline, revision;

    synchronized void clear() {
        stage = Stage.NONE; ticket = null; deadline = 0; revision++;
    }
    private void begin(Stage next, long now) {
        clear();
        if (now < 0 || now > Long.MAX_VALUE - DURATION_MS) return;
        stage = next; startedAt = now; deadline = now + DURATION_MS;
    }
    synchronized void recognized(long now) { begin(Stage.RECOGNIZED, now); }
    synchronized void notSent(long now) {
        if (stage(now) != Stage.RECOGNIZED) return;
        stage = Stage.NOT_SENT; revision++;
    }
    synchronized void queued(ConnectionInputGate.Ticket admitted, long now) {
        if (admitted == null || stage(now) != Stage.RECOGNIZED) return;
        ticket = admitted; stage = Stage.QUEUED; revision++;
    }
    synchronized boolean written(ConnectionInputGate.Ticket attempted, boolean sent, long now) {
        return result(attempted, sent ? Stage.SENT : Stage.NOT_SENT, now);
    }
    synchronized boolean uncertain(ConnectionInputGate.Ticket attempted, long now) {
        // A throwing/deadline-limited write can have delivered bytes; do not claim non-delivery.
        return result(attempted, Stage.UNCERTAIN, now);
    }
    private boolean result(ConnectionInputGate.Ticket attempted, Stage next, long now) {
        // A superseded/expired attempt cannot repaint a newer tap, card, Stop or connection.
        if (attempted == null || ticket != attempted || stage(now) != Stage.QUEUED) return false;
        stage = next; ticket = null; revision++;
        return true;
    }
    synchronized void cardApplied(boolean contentChanged, long now) {
        // Applied data, not tap-caused completion or proof of changed visible glyphs.
        // Called only after guarded current ui.card application, never from input ACK/write.
        clear();
        if (contentChanged) begin(Stage.CARD_APPLIED, now);
    }
    synchronized long revision() { return revision; }
    synchronized Stage stage(long now) {
        if (stage != Stage.NONE && (now < startedAt || now >= deadline)) clear();
        return stage;
    }
    synchronized long remaining(long now, long callbackRevision) {
        return callbackRevision == revision && stage(now) != Stage.NONE ? deadline - now : 0;
    }
}
