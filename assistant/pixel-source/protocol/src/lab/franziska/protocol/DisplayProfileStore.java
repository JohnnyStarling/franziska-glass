package lab.franziska.protocol;
import java.io.*;
import java.util.*;
/** Single atomic private-file commit. Failed writes never replace live or in-memory profiles. */
public final class DisplayProfileStore implements DisplayCalibration.Store {
    private final File directory;
    public DisplayProfileStore(File directory){this.directory=directory;}
    private Map<String,Object> read(){
        synchronized(DisplayProfileStore.class){
            File file=new File(directory,"display-profiles-v1.json");
            try{
                if(file.length()>4096)throw new IOException("size");
                FileInputStream in=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try{byte[] buffer=new byte[512];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>4096)throw new IOException("size");bytes.write(buffer,0,n);}}
                finally{in.close();}
                Object value=Json.parse(new String(bytes.toByteArray(),Protocol.UTF8));
                if(!(value instanceof Map))throw new IOException("object");
                Map<String,Object> result=(Map<String,Object>)value;
                DisplayCalibration.fields(result,"active","indoor","outdoor");
                for(Object profile:result.values())DisplayCalibration.Profile.parse(profile);
                return result;
            }catch(Exception invalid){return AssistWire.payload("active",DisplayCalibration.Profile.identity().map(),
                "indoor",DisplayCalibration.Profile.identity().map(),"outdoor",DisplayCalibration.Profile.identity().map());}
        }
    }
    public DisplayCalibration.Profile active(){return DisplayCalibration.Profile.parse(read().get("active"));}
    public DisplayCalibration.Profile load(String slot){checkSlot(slot);return DisplayCalibration.Profile.parse(read().get(slot));}
    private void checkSlot(String slot){if(!"indoor".equals(slot)&&!"outdoor".equals(slot))throw new IllegalArgumentException("slot");}
    public boolean save(String slot,DisplayCalibration.Profile profile){
        checkSlot(slot);
        synchronized(DisplayProfileStore.class){
            File temporary=new File(directory,"display-profiles-v1.tmp"),target=new File(directory,"display-profiles-v1.json");
            try{
                Map<String,Object> values=read();values.put(slot,profile.map());values.put("active",profile.map());
                byte[] bytes=Json.stringify(values).getBytes(Protocol.UTF8);
                FileOutputStream out=new FileOutputStream(temporary);
                try{out.write(bytes);out.flush();out.getFD().sync();}finally{out.close();}
                if(!temporary.renameTo(target))throw new IOException("rename");
                return true;
            }catch(Exception failure){temporary.delete();return false;}
        }
    }
}
