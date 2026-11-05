package lab.franziska.pixelbridge;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.franziska.protocol.Json;
import lab.franziska.protocol.Protocol;

/** Durable at-most-once local admission ledger; ambiguous crash state fails closed. */
final class MyGlassLedger {
    // Workers can briefly overlap across disable/re-enable. They must share
    // one in-memory view of the durable file until the app process exits.
    private static MyGlassLedger shared;
    static synchronized MyGlassLedger shared(Context context) throws Exception {
        if(shared==null) shared=new MyGlassLedger(context.getApplicationContext());
        return shared;
    }
    static final String RESERVED="reserved", ACCEPTED="pixel_accepted", FAILED="failed";
    private static final long RETAIN_MS=10L*24*60*60*1000;
    private static final int MAX_ENTRIES=1024, MAX_BYTES=131072;
    static final class Record {
        final String state, code;
        final long at;
        Record(String state,String code,long at) { this.state=state; this.code=code; this.at=at; }
    }
    static final class Reservation {
        final Record record;
        final boolean fresh;
        Reservation(Record record,boolean fresh) { this.record=record; this.fresh=fresh; }
    }
    private final AtomicFile file;
    private final LinkedHashMap<String,Record> entries=new LinkedHashMap<String,Record>();

    MyGlassLedger(Context context) throws Exception {
        File base=new File(context.getFilesDir(),"myglass-command-ledger.json");
        file=new AtomicFile(base);
        load();
    }

    synchronized Reservation reserve(String id) throws Exception {
        checkedId(id);
        Record prior=entries.get(id);
        if(prior!=null) return new Reservation(prior,false);
        prune();
        if(entries.size()>=MAX_ENTRIES) throw new IOException("MyGlass ledger full");
        Record next=new Record(RESERVED,null,System.currentTimeMillis());
        entries.put(id,next);
        try { persist(); }
        catch(Exception failed) { entries.remove(id); throw failed; }
        return new Reservation(next,true);
    }

    synchronized Record finish(String id,String state,String code) throws Exception {
        checkedId(id);
        if(!ACCEPTED.equals(state) && !FAILED.equals(state)) throw new IOException("MyGlass ledger status invalid");
        if(FAILED.equals(state) && !validCode(code) || ACCEPTED.equals(state) && code!=null)
            throw new IOException("MyGlass ledger code invalid");
        Record before=entries.get(id);
        if(before==null || !RESERVED.equals(before.state)) throw new IOException("MyGlass ledger transition invalid");
        Record next=new Record(state,code,before.at);
        entries.put(id,next);
        try { persist(); }
        catch(Exception failed) { entries.put(id,before); throw failed; }
        return next;
    }

    /** Undo only a fresh reservation when the Glass host positively rejected before admission. */
    synchronized void releaseUnadmitted(String id) throws Exception {
        checkedId(id);
        Record before=entries.get(id);
        if(before==null || !RESERVED.equals(before.state)) throw new IOException("MyGlass ledger transition invalid");
        entries.remove(id);
        try { persist(); }
        catch(Exception failed) { entries.put(id,before); throw failed; }
    }

    private void prune() {
        long now=System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String,Record>> it=entries.entrySet().iterator();
        while(it.hasNext()) if(now-it.next().getValue().at>RETAIN_MS) it.remove();
    }

    private void load() throws Exception {
        FileInputStream in;
        try { in=file.openRead(); }
        catch(FileNotFoundException absent) { return; }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try {
            byte[] chunk=new byte[4096]; int n;
            while((n=in.read(chunk))!=-1) {
                if(bytes.size()+n>MAX_BYTES) throw new IOException("MyGlass ledger size invalid");
                bytes.write(chunk,0,n);
            }
        } finally { in.close(); }
        Object decoded=Json.parse(new String(bytes.toByteArray(),Protocol.UTF8));
        if(!(decoded instanceof Map)) throw new IOException("MyGlass ledger format invalid");
        Map<?,?> root=(Map<?,?>)decoded;
        if(root.size()!=2 || !(root.get("version") instanceof Number)
                || ((Number)root.get("version")).intValue()!=1 || !(root.get("entries") instanceof List))
            throw new IOException("MyGlass ledger format invalid");
        List<?> rows=(List<?>)root.get("entries");
        if(rows.size()>MAX_ENTRIES) throw new IOException("MyGlass ledger capacity invalid");
        for(Object item:rows) {
            if(!(item instanceof List)||((List<?>)item).size()!=4) throw new IOException("MyGlass ledger row invalid");
            List<?> row=(List<?>)item;
            if(!(row.get(0) instanceof String)||!(row.get(1) instanceof String)||!(row.get(3) instanceof Number))
                throw new IOException("MyGlass ledger row invalid");
            String id=(String)row.get(0), state=(String)row.get(1), code=(String)row.get(2);
            checkedId(id);
            if(!RESERVED.equals(state)&&!ACCEPTED.equals(state)&&!FAILED.equals(state)
                    || FAILED.equals(state)&&!validCode(code)
                    || !FAILED.equals(state)&&code!=null || entries.containsKey(id))
                throw new IOException("MyGlass ledger row invalid");
            entries.put(id,new Record(state,code,((Number)row.get(3)).longValue()));
        }
    }

    private void persist() throws Exception {
        Map<String,Object> root=new LinkedHashMap<String,Object>();
        root.put("version",Long.valueOf(1));
        List<Object> rows=new ArrayList<Object>();
        for(Map.Entry<String,Record> item:entries.entrySet()) {
            Record record=item.getValue();
            rows.add(Arrays.<Object>asList(item.getKey(),record.state,record.code,Long.valueOf(record.at)));
        }
        root.put("entries",rows);
        byte[] data=Json.stringify(root).getBytes(Protocol.UTF8);
        if(data.length>MAX_BYTES) throw new IOException("MyGlass ledger size invalid");
        FileOutputStream out=null;
        try {
            out=file.startWrite();
            out.write(data);
            file.finishWrite(out);
            out=null;
        } catch(Exception failure) {
            if(out!=null) file.failWrite(out);
            throw failure;
        } finally { Arrays.fill(data,(byte)0); }
    }

    private static void checkedId(String id) throws IOException {
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))
            throw new IOException("MyGlass command ID invalid");
    }
    private static boolean validCode(String code) {
        return "unsupported".equals(code)||"glass_offline".equals(code)
                ||"permission_denied".equals(code)||"invalid_payload".equals(code)||"local_error".equals(code);
    }
}
