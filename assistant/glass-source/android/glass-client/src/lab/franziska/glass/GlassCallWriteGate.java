package lab.franziska.glass;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes the final call confirmation write with local Stop, without holding Activity's lock. */
final class GlassCallWriteGate {
    interface Writer { void write() throws IOException; }

    private final ReentrantLock sending = new ReentrantLock();
    private volatile boolean revoked;

    boolean send(Writer writer, long waitMs) throws IOException {
        boolean acquired = false;
        try {
            acquired = sending.tryLock(waitMs, TimeUnit.MILLISECONDS);
            if (!acquired) return false;
            if (revoked) return false;
            writer.write();
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired) sending.unlock();
        }
    }

    /** Returns false only if an in-flight write did not quiesce within the bounded wait. */
    boolean revoke(long waitMs) {
        revoked = true;
        boolean acquired = false;
        try {
            acquired = sending.tryLock(waitMs, TimeUnit.MILLISECONDS);
            return acquired;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (acquired) sending.unlock();
        }
    }
}
