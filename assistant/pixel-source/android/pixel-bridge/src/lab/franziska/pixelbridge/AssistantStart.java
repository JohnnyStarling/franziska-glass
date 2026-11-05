package lab.franziska.pixelbridge;

/** Owner intent only, never persisted. No sensor, network, retry or media operations. */
public final class AssistantStart {
    public enum Phase { OFF, VALIDATING_PAIR, VALIDATING_ROUTE, ENABLED, FAILED }
    private long revision;
    private Phase phase = Phase.OFF;
    public synchronized Phase phase() { return phase; }
    public synchronized boolean pending() { return phase == Phase.VALIDATING_PAIR || phase == Phase.VALIDATING_ROUTE; }
    /** -1 coalesces repeated clicks, including while already enabled. */
    public synchronized long start(boolean endpointRunning) {
        if (pending() || phase == Phase.ENABLED) return -1;
        phase = endpointRunning ? Phase.VALIDATING_ROUTE : Phase.VALIDATING_PAIR;
        return ++revision;
    }
    public synchronized long ticket() { return revision; }
    public synchronized boolean pairValidated(long ticket) {
        if (ticket != revision || phase != Phase.VALIDATING_PAIR) return false;
        phase = Phase.VALIDATING_ROUTE; return true;
    }
    public synchronized boolean routeValidated(long ticket, boolean paired) {
        if (ticket != revision || phase != Phase.VALIDATING_ROUTE) return false;
        phase = paired ? Phase.ENABLED : Phase.FAILED; return true;
    }
    public synchronized void fail() { revision++; phase = Phase.FAILED; }
    public synchronized void invalidate() { revision++; phase = Phase.OFF; }
}
