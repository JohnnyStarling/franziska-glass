package lab.franziska.glass;

import java.io.IOException;

/** Ordered media-copy decisions, independent of Android and of the transport. */
final class PhotoSyncPolicy {
    interface Access<T> {
        long id(T item);
        boolean automatic(T item, long lastId);
        boolean ready(T item);
        boolean oversized(T item);
    }
    interface Guard { void check() throws Exception; }
    interface Sender<T> { void send(T item) throws Exception; }
    interface Ledger {
        long lastId();
        int skippedCount();
        /** Persist last_id and oversize_count in one durable transaction. */
        boolean commit(long lastId, int skippedCount);
    }
    static final class Result {
        final int transferred, oversizedSkipped;
        final boolean complete, waitingForReady;
        Result(int transferred, int oversizedSkipped, boolean complete, boolean waitingForReady) {
            this.transferred=transferred;this.oversizedSkipped=oversizedSkipped;
            this.complete=complete;this.waitingForReady=waitingForReady;
        }
    }
    static <T> Result process(Iterable<T> items, boolean copyAll, long lastId,
                              Access<T> access, Guard guard, Sender<T> sender,
                              Ledger ledger) throws Exception {
        int transferred=0, skipped=0;
        // A prior durable skip remains incomplete on every later automatic
        // DONE, including an empty scan, until an acknowledged full copy clears it.
        boolean complete=copyAll||ledger.skippedCount()==0,waitingForReady=false;
        for(T item:items) {
            guard.check();
            boolean automatic=access.automatic(item,lastId);
            if(!copyAll&&!automatic)continue;
            // A camera file still being written is retried before any later automatic file.
            if(!access.ready(item)) {
                complete=false;
                if(copyAll)continue;
                waitingForReady=true;
                break;
            }
            // Only a stable, currently oversized automatic photo may advance
            // the ledger without a file ACK. The durable skip stays visible to
            // the owner, and subsequent eligible photos can proceed.
            if(access.oversized(item)) {
                complete=false;
                if(copyAll)continue;
                int previous=ledger.skippedCount();
                int count=previous==Integer.MAX_VALUE?previous:previous+1;
                if(!ledger.commit(access.id(item),count))throw new IOException("MEDIA_LEDGER_WRITE");
                lastId=access.id(item);skipped++;
                continue;
            }
            sender.send(item);transferred++;
            if(automatic&&!copyAll) {
                if(!ledger.commit(access.id(item),ledger.skippedCount()))
                    throw new IOException("MEDIA_LEDGER_WRITE");
                lastId=access.id(item);
            }
        }
        return new Result(transferred,skipped,complete,waitingForReady);
    }
    /** Call only after Pixel's DONE_ACK for a fully successful copy-all. */
    static void confirmedCopyAll(boolean copyAll, Result result, Ledger ledger) throws IOException {
        if(!copyAll||!result.complete||ledger.skippedCount()==0)return;
        if(!ledger.commit(ledger.lastId(),0))throw new IOException("MEDIA_LEDGER_WRITE");
    }
    private PhotoSyncPolicy() {}
}
