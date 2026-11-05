package lab.franziska.glass;

/** Media-free presentation only. Never grants capture, transport or reply authority. */
final class AssistPresentation {
    enum Stage { IDLE, RECORDING, PREPARING, SENDING, WAITING, ANSWER, ERROR, CANCELLED, STOPPED }
    private Stage stage = Stage.IDLE;
    private String message = "Откройте помощника на Pixel", code = "";
    private boolean ready;
    private long started, holdUntil;
    static final long TERMINAL_HOLD_MS = 60000L;
    static final long ENTRY_HOLD_MS = 30000L;
    /** Called only for a fresh foreground entry or deliberate home/restart tap, never readiness/reconnect. */
    synchronized void enter(long now) { if (stage == Stage.IDLE) holdUntil = now + ENTRY_HOLD_MS; }
    synchronized Stage stage() { return stage; }
    synchronized boolean terminal() { return stage == Stage.ANSWER || stage == Stage.ERROR || stage == Stage.CANCELLED || stage == Stage.STOPPED; }
    synchronized boolean active() { return stage == Stage.RECORDING || stage == Stage.PREPARING || stage == Stage.SENDING || stage == Stage.WAITING; }
    synchronized void ready(boolean value) {
        ready = value;
        if (stage == Stage.IDLE) message = value ? "Готово · тап — начать" : "Откройте помощника на Pixel";
    }
    synchronized void begin(long now) { stage = Stage.RECORDING; message = "Говорите"; code = ""; started = now; holdUntil = now + AssistGate.REQUEST_TIMEOUT_MS; }
    synchronized void advance(Stage next) {
        if (!active() || next.ordinal() <= stage.ordinal() || next.ordinal() > Stage.WAITING.ordinal()) return;
        stage = next;
        message = next == Stage.PREPARING ? "Готовлю запись…" : next == Stage.SENDING ? "Отправляю вопрос…" : "Жду ответ Франциски…";
    }
    synchronized void end(Stage next, String text, String safeCode, long now) {
        stage = next; message = text; code = safeCode; holdUntil = now + TERMINAL_HOLD_MS;
    }
    synchronized void home(long now) { stage = Stage.IDLE; code = ""; enter(now); ready(ready); }
    synchronized String message(long now) {
        if (stage != Stage.RECORDING) return message;
        long elapsed = Math.max(0, Math.min(15, (now - started) / 1000));
        return "Запись " + elapsed + " / 15 с · осталось " + (15-elapsed) + " с";
    }
    synchronized String code() { return code; }
    synchronized long remaining(long now, boolean foreground) { return foreground ? Math.max(0, holdUntil-now) : 0; }
    synchronized void page(long now) { if (stage == Stage.ANSWER) holdUntil = now + TERMINAL_HOLD_MS; }
}
