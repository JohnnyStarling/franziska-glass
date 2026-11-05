package lab.franziska.glass;

/** Bounded preference display/admission; old status cannot authorize cancel. */
final class GalleryShareStatus {
    static final long MAX_STATUS_AGE_MS=3720000L;
    static boolean recent(long selectedId,long storedId,long updatedAt,long now) {
        return selectedId>0 && selectedId==storedId && updatedAt>0
                && now>=updatedAt && now-updatedAt<MAX_STATUS_AGE_MS;
    }
    static boolean canCancel(long selectedId,long storedId,long updatedAt,long now,boolean pending) {
        return pending && recent(selectedId,storedId,updatedAt,now);
    }
    private GalleryShareStatus() {}
}
