package lab.franziska.glass;

/** Process-wide native audio lease shared by Talk and one-shot capture.
 * Metadata only: never call native code or a listener while holding this gate.
 * Uncertain cleanup remains quarantined until process death, not a new Activity.
 */
final class NativeAudioAdmission {
    private static Object owner;
    private static boolean quarantined;
    private NativeAudioAdmission(){}
    static synchronized boolean acquire(Object token){
        if(token==null||owner!=null||quarantined)return false;
        owner=token;return true;
    }
    static synchronized void settle(Object token,boolean clean){
        if(owner!=token||token==null)return;
        if(!clean)quarantined=true;
        if(clean&&!quarantined)owner=null;
    }
}
