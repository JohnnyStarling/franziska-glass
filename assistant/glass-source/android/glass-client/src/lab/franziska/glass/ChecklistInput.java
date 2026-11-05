package lab.franziska.glass;

/** No Android/GDK dependency. Cross-route duplicates suppressed, same-route taps retained. */
final class ChecklistInput {
    private int key = -1;
    private long downAt;
    private boolean longPress;
    private final String[] actions = new String[32];
    private final int[] routeMasks = new int[32];
    private final long[] starts = new long[32], ends = new long[32];
    private int cursor;
    // Pending cancellation deliberately retains bounded companion history across cards/sessions.
    synchronized void reset() { key = -1; longPress = false; }
    synchronized long downAt() { return downAt; }
    synchronized void down(int code, int repeat, long time, boolean isLong) {
        if (repeat == 0) { key = code; downAt = time; longPress = isLong; }
        else if (key == code) longPress = true;
    }
    synchronized boolean up(int code, long time, boolean cancelled) {
        boolean accept = key == code && !cancelled && !longPress
                && downAt >= 0 && time >= downAt && time - downAt < 500;
        key = -1;
        return accept;
    }
    synchronized boolean route(String route, String action, long start, long end) {
        if (start < 0 || end < start) return false;
        int mask = "key".equals(route) ? 1 : "touch".equals(route) ? 2 : 4;
        for (int n = 1; n <= actions.length; n++) {
            int i = (cursor - n + actions.length) % actions.length;
            if (actions[i] != null && actions[i].equals(action)
                    && distance(start, starts[i]) <= 250 && distance(end, ends[i]) <= 250) {
                if ((routeMasks[i] & mask) != 0) break; // intentional repeat on a seen route
                routeMasks[i] |= mask;
                return false;
            }
        }
        actions[cursor] = action; routeMasks[cursor] = mask;
        starts[cursor] = start; ends[cursor] = end;
        cursor = (cursor + 1) % actions.length;
        return true;
    }
    private static long distance(long a, long b) { return a >= b ? a - b : b - a; }
    static String swipe(float dx, float dy, long duration) {
        if (!TapGesture.finite(dx) || !TapGesture.finite(dy) || duration < 0 || duration > 1200) return null;
        if (dy > 80 && dy > Math.abs(dx) * 1.5f) return "exit";
        if (Math.abs(dx) < 80 || Math.abs(dx) < Math.abs(dy) * 1.5f) return null;
        return dx > 0 ? "next" : "previous";
    }
}
