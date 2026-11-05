package lab.franziska.glass;
/** Pure authority/generation gate. Remote status cannot call begin or activate sensors. */
final class AssistGate {
    private Object peer;
    private String epoch,id;
    // 15s capture + 30s transfer + 175s Gateway + 10s delivery, from initial tap.
    static final long REQUEST_TIMEOUT_MS = 230000L;
    private long revision, startedAt;
    private static long now(){return System.nanoTime()/1000000L;}
    private boolean committed;
    synchronized long begin(Object peer,String epoch,String id,boolean eligible) {
        return begin(peer,epoch,id,eligible,now());
    }
    synchronized long begin(Object peer,String epoch,String id,boolean eligible,long now) {
        if(!eligible||peer==null||epoch==null||id==null||this.id!=null)return -1;
        this.peer=peer;this.epoch=epoch;this.id=id;startedAt=now;committed=false;return ++revision;
    }
    synchronized boolean owns(long token,Object peer,String epoch,String id) {
        return token==revision&&this.peer==peer&&this.id!=null&&this.id.equals(id)&&this.epoch.equals(epoch);
    }
    synchronized boolean written(long token,Object peer,String epoch,String id) {
        if(!owns(token,peer,epoch,id))return false;committed=true;return true;
    }
    synchronized boolean reply(long token,Object peer,String epoch,String id,boolean foreground) {
        return reply(token,peer,epoch,id,foreground,now());
    }
    synchronized boolean reply(long token,Object peer,String epoch,String id,boolean foreground,long now) {
        long elapsed=now-startedAt;
        return foreground&&committed&&owns(token,peer,epoch,id)
                &&elapsed>=0&&elapsed<REQUEST_TIMEOUT_MS;
    }
    synchronized void revoke(){revision++;peer=null;epoch=null;id=null;committed=false;}
}
