package lab.franziska.pixelbridge;

import java.io.Closeable;

/** Slow loading/binding is never inside admission. Commit alone holds the monitor.
 * Cancellation is logical: a blocked provider is not forcibly interrupted. Its returned
 * resource is closed and can never be installed after cancellation.
 */
public final class AgentResourceLoader {
    public interface Job<T extends Closeable> {
        boolean current(); // monitor held
        T open() throws Exception; // monitor NOT held
        void install(T resource) throws Exception; // monitor held; no IO
        void failed(); // monitor held
        void finished(); // monitor held
    }
    public static <T extends Closeable> void run(Object monitor, Job<T> job) {
        T candidate = null;
        try {
            synchronized (monitor) { if (!job.current()) return; }
            candidate = job.open();
            synchronized (monitor) {
                if (job.current()) { job.install(candidate); candidate = null; }
            }
        } catch (Exception failure) {
            synchronized (monitor) { if (job.current()) job.failed(); }
        } finally {
            if (candidate != null) try { candidate.close(); } catch (Exception ignored) {}
            synchronized (monitor) { job.finished(); }
        }
    }
    private AgentResourceLoader() {}
}
