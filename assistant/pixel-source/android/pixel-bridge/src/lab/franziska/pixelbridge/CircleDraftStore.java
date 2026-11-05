package lab.franziska.pixelbridge;

import java.io.File;
import java.io.IOException;

/** One app-private draft; no MediaStore entry, provider, share URI, or network path. */
final class CircleDraftStore {
    private static final String READY_NAME = "telegram-circle-draft.mp4";
    private static final String BACKUP_NAME = "telegram-circle-draft.previous.mp4";
    private static final String PENDING_PREFIX = "circle-pending-";
    private final File directory;

    CircleDraftStore(File privateFilesDirectory) throws IOException {
        directory = new File(privateFilesDirectory, "circle_drafts");
        if ((!directory.exists() && !directory.mkdir()) || !directory.isDirectory())
            throw new IOException("Private draft directory unavailable");
        recoverPrevious();
    }

    File ready() { return new File(directory, READY_NAME); }

    File createPending() throws IOException {
        clearPending();
        return File.createTempFile(PENDING_PREFIX, ".mp4", directory);
    }

    File promote(File pending) throws IOException {
        if (!isPending(pending) || !pending.isFile()) throw new IOException("Invalid pending draft");
        File ready = ready();
        File previous = previous();
        if (previous.exists() && !previous.delete()) throw new IOException("Old draft backup cannot be removed");
        boolean hadReady = ready.exists();
        if (hadReady && !ready.renameTo(previous)) throw new IOException("Existing draft cannot be preserved");
        if (!pending.renameTo(ready)) {
            if (hadReady) previous.renameTo(ready); // retain backup for next startup if rollback fails
            throw new IOException("Draft could not be finalized");
        }
        if (hadReady) previous.delete(); // any stale backup is recovered/removed on next startup
        return ready;
    }

    boolean discard() throws IOException {
        File ready = ready();
        boolean existed = ready.exists();
        if (existed && !ready.delete()) throw new IOException("Draft could not be discarded");
        File previous = previous();
        if (previous.exists() && !previous.delete()) throw new IOException("Draft backup could not be discarded");
        clearPending();
        return existed;
    }

    private File previous() { return new File(directory, BACKUP_NAME); }

    private void recoverPrevious() throws IOException {
        File previous = previous();
        if (!previous.exists()) return;
        if (ready().exists()) {
            if (!previous.delete()) throw new IOException("Stale draft backup cannot be removed");
        } else if (!previous.renameTo(ready())) {
            throw new IOException("Draft backup cannot be restored");
        }
    }

    void discardPending(File pending) throws IOException {
        if (!isPending(pending)) throw new IOException("Invalid pending draft");
        if (pending.exists() && !pending.delete()) throw new IOException("Pending draft could not be removed");
    }

    void clearPending() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Private draft directory unavailable");
        for (File file : files) {
            if (isPending(file) && file.exists() && !file.delete())
                throw new IOException("Stale pending draft could not be removed");
        }
    }

    private boolean isPending(File file) {
        try {
            return file != null && directory.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())
                    && file.getName().startsWith(PENDING_PREFIX) && file.getName().endsWith(".mp4");
        } catch (IOException invalid) {
            return false;
        }
    }
}
