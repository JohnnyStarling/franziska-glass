package lab.franziska.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Structured UUID: positive monotonic counter in MSB, persistent install namespace in LSB. */
public final class AssistRequestIds {
    public static final int MAX_NAMESPACES=8;
    private AssistRequestIds(){}
    public static long counter(String id)throws ProtocolException {
        AssistWire.id(id);long n=UUID.fromString(id).getMostSignificantBits();
        if(n<=0)AssistWire.fail("REQUEST_COUNTER");return n;
    }
    public static long namespace(String id)throws ProtocolException {
        counter(id);return UUID.fromString(id).getLeastSignificantBits();
    }
    public static String format(long counter,long namespace)throws ProtocolException {
        if(counter<=0)AssistWire.fail("REQUEST_COUNTER");return new UUID(counter,namespace).toString();
    }
    /** Both fields absent is a fresh install; a partial pair is corruption, never reinitialize. */
    public interface Store {
        Long namespace(); Long counter(); boolean commit(long namespace,long counter);
    }
    /** Synchronous durable reservation BEFORE capture. Failure returns no usable ID. */
    public static synchronized String reserve(Store store,long freshNamespace)throws ProtocolException {
        try {
            Long ns=store.namespace(),n=store.counter();
            if((ns==null)!=(n==null))AssistWire.fail("REQUEST_STORE_PARTIAL");
            if(n!=null&&(n.longValue()<0||n.longValue()==Long.MAX_VALUE))AssistWire.fail("REQUEST_COUNTER_EXHAUSTED");
            long namespace=ns==null?freshNamespace:ns.longValue(),next=n==null?1:n.longValue()+1;
            if(!store.commit(namespace,next))AssistWire.fail("REQUEST_STORE_COMMIT");
            return format(next,namespace);
        }catch(ProtocolException failure){throw failure;}
        catch(RuntimeException failure){AssistWire.fail("REQUEST_STORE_INVALID");return null;}
    }
    /** No eviction. At most eight installations; unlimited increasing positive counters each. */
    public static final class Ledger {
        private final LinkedHashMap<Long,Long> high=new LinkedHashMap<Long,Long>();
        public Ledger(){}
        public Ledger(Map<Long,Long> persisted)throws ProtocolException {
            if(persisted==null||persisted.size()>MAX_NAMESPACES)AssistWire.fail("REQUEST_LEDGER_INVALID");
            for(Map.Entry<Long,Long> e:persisted.entrySet()){
                if(e.getKey()==null||e.getValue()==null||e.getValue().longValue()<=0)AssistWire.fail("REQUEST_LEDGER_INVALID");
                high.put(e.getKey(),e.getValue());
            }
        }
        /** Mutates before submission; persistent consumers MUST durably save snapshot before upload. */
        public synchronized void reserve(String id)throws ProtocolException {
            long n=counter(id),ns=namespace(id);Long old=high.get(ns);
            if(old!=null&&n<=old.longValue())AssistWire.fail("REQUEST_REPLAY");
            if(old==null&&high.size()>=MAX_NAMESPACES)AssistWire.fail("REQUEST_NAMESPACE_CAPACITY");
            high.put(ns,n);
        }
        public synchronized Map<Long,Long> snapshot(){return new LinkedHashMap<Long,Long>(high);}
    }
}
