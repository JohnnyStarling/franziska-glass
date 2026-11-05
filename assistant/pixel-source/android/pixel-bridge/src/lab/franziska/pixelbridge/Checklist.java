package lab.franziska.pixelbridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure Java state; all publication and receipt transitions share this monitor. */
public final class Checklist {
    public static final String DEFAULT_TITLE = "Сборы";
    public static final String DEFAULT_STEPS = "Взять ключи\nПроверить телефон и заряд\nВзять документы\nВзять воду\nВыключить свет и закрыть дверь";
    private String title = DEFAULT_TITLE;
    private String[] steps = DEFAULT_STEPS.split("\n");
    private boolean[] done = new boolean[steps.length];
    private int index;
    private long revision;
    private boolean published, queued, received;
    private final LinkedHashMap<String, Long> cards = new LinkedHashMap<String, Long>();

    public static void validate(String title, String text) {
        validLine(title, 40);
        if (text == null || text.length() > 20 * 241) throw new IllegalArgumentException("От 1 до 20 шагов, каждый до 120 символов");
        String[] lines = text.split("\n", -1);
        if (lines.length < 1 || lines.length > 20) throw new IllegalArgumentException("От 1 до 20 шагов");
        for (String line : lines) validLine(line, 120);
    }
    private static void validLine(String text, int max) {
        if (text == null || text.trim().length() == 0 || text.codePointCount(0, text.length()) > max)
            throw new IllegalArgumentException("Непустой заголовок до 40 символов; шаг до 120 символов");
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            int cp = text.codePointAt(i);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029
                    || (Character.isSurrogate(c) && Character.charCount(cp) == 1))
                throw new IllegalArgumentException("Недопустимый символ");
            i += Character.charCount(cp);
        }
    }
    public synchronized void edit(String title, String text) {
        validate(title, text); // Atomic rejection: never truncate or partially edit.
        if (this.title.equals(title) && joined().equals(text)) return;
        this.title = title;
        steps = text.split("\n", -1);
        done = new boolean[steps.length];
        index = 0;
        invalidate();
    }
    private String joined() {
        StringBuilder out = new StringBuilder();
        for (String step : steps) { if (out.length() > 0) out.append('\n'); out.append(step); }
        return out.toString();
    }
    private void invalidate() { revision++; cards.clear(); queued = false; received = false; }
    public synchronized void start() { published = true; invalidate(); }
    public synchronized void stop() { published = false; invalidate(); }
    /** Remote gestures require application receipt of this exact revision. */
    public synchronized boolean remoteInput(String action) {
        return published && received && input(action);
    }
    public synchronized boolean input(String action) {
        if (!published) return false;
        if ("tap".equals(action)) { done[index] = true; if (index < steps.length - 1) index++; }
        else if ("next".equals(action)) { if (index < steps.length - 1) index++; }
        else if ("previous".equals(action)) { if (index > 0) index--; }
        else return false;
        invalidate();
        return true;
    }
    public synchronized void clearProgress() { done = new boolean[steps.length]; index = 0; invalidate(); }
    public synchronized Snapshot snapshot() {
        int count = 0; for (boolean value : done) if (value) count++;
        return new Snapshot(title, steps[index], index, steps.length, count, done[index], revision, published, queued, received);
    }
    public synchronized void queued(long revision) { if (published && this.revision == revision) { queued = true; received = false; } }
    public synchronized boolean current(long revision) { return published && this.revision == revision; }
    public synchronized void written(String id, long revision) {
        if (!current(revision)) return;
        if (cards.size() >= 16) cards.remove(cards.keySet().iterator().next());
        cards.put(id, Long.valueOf(revision));
    }
    public synchronized boolean acknowledge(String id) {
        Long value = cards.remove(id);
        if (value == null || !current(value.longValue())) return false;
        received = true; queued = false; return true;
    }
    public synchronized void disconnected() { invalidate(); }
    public static final class Snapshot {
        public final String title, text;
        public final int index, total, completed;
        public final long revision;
        public final boolean done, published, queued, received;
        Snapshot(String title, String text, int index, int total, int completed, boolean done,
                long revision, boolean published, boolean queued, boolean received) {
            this.title = title; this.text = text; this.index = index; this.total = total;
            this.completed = completed; this.done = done; this.revision = revision;
            this.published = published; this.queued = queued; this.received = received;
        }
        public Map<String, Object> payload() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("title", title + " " + (index + 1) + "/" + total);
            ArrayList<Object> lines = new ArrayList<Object>();
            lines.add(text); // 120 Unicode code points <= existing 240 UTF-16 wire units.
            lines.add("Выполнено: " + completed + "/" + total);
            lines.add(done ? "Готово ✓" : "Касание: отметить и дальше");
            result.put("lines", lines); return result;
        }
    }
}
