package lab.franziska.glass;

/** Single pointer sequence validator. Android action constants are stable public API values. */
final class TapGesture {
    private final float slop;
    private final boolean upwardMenu;
    private boolean active;
    private int pointer;
    private float x, y;
    private double excursion;
    private long down, last;
    TapGesture(float slop) { this(slop,false); }
    TapGesture(float slop,boolean upwardMenu) {
        this.slop = finite(slop) && slop >= 0 ? slop : 0;this.upwardMenu=upwardMenu;
    }
    synchronized void cancel() { active = false; }
    synchronized long downAt() { return down; }
    synchronized boolean active() { return active; }
    static boolean finite(float v) { return !Float.isNaN(v) && !Float.isInfinite(v); }
    synchronized String event(int action, int count, int id, float px, float py,
                              long originalDown, long at) {
        if (action == 3 || count != 1 || id < 0 || !finite(px) || !finite(py)
                || originalDown < 0 || at < originalDown || (action != 0 && action != 1 && action != 2)) {
            cancel(); return null;
        }
        if (action == 0) {
            if (at != originalDown) { cancel(); return null; }
            active = true; pointer = id; x = px; y = py; down = last = at; excursion = 0;
            return null;
        }
        if (!active || id != pointer || originalDown != down || at < last) {
            cancel(); return null;
        }
        last = at;
        double dx = (double) px - x, dy = (double) py - y;
        excursion = Math.max(excursion, dx * dx + dy * dy);
        if (action != 1) return null;
        active = false;
        long duration = at - down;
        if (duration < 500 && excursion <= (double) slop * slop) return "tap";
        String gesture=ChecklistInput.swipe((float) dx, (float) dy, duration);
        if(gesture==null&&upwardMenu&&"exit".equals(ChecklistInput.swipe((float)dx,(float)-dy,duration)))return "menu";
        return gesture;
    }
}
