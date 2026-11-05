package lab.franziska.protocol;
import java.util.*;
/** App-only color values and strict, bounded preview transaction; no Android/sensor dependencies. */
public final class DisplayCalibration {
    public static final String CAPABILITY="display_calibration_v1";
    public static final long IDLE_MS=120000L, MAX_MS=900000L;
    public static final class Profile {
        public final int brightness, contrast, gamma, saturation, red, green, blue;
        private final double[] tone=new double[256];
        public Profile(int b,int c,int g,int s,int r,int gr,int bl) {
            if((b!=-1&&(b<10||b>100))||c<50||c>150||g<50||g>200||s<0||s>150
                    ||r<25||r>100||gr<25||gr>100||bl<25||bl>100)throw new IllegalArgumentException("PROFILE_RANGE");
            brightness=b;contrast=c;gamma=g;saturation=s;red=r;green=gr;blue=bl;
            for(int i=0;i<256;i++){
                double x=i/255.0, k=c/100.0;
                // Symmetric S-curve contrast: fixed black, midgray and white; slope k at midgray.
                // Gamma exponent convention is explicit in Pixel UI.
                double a=Math.pow(x,k),b2=Math.pow(1-x,k);
                tone[i]=Math.pow(a/(a+b2),g/100.0);
            }
        }
        public static Profile identity(){return new Profile(-1,100,100,100,100,100,100);}
        public int color(int argb){
            double r=tone[(argb>>>16)&255],g=tone[(argb>>>8)&255],b=tone[argb&255];
            double y=.2126*r+.7152*g+.0722*b,s=saturation/100.0;
            return (argb&0xff000000)|(channel((y+(r-y)*s)*red/100.0)<<16)
                |(channel((y+(g-y)*s)*green/100.0)<<8)|channel((y+(b-y)*s)*blue/100.0);
        }
        private static int channel(double v){return (int)Math.round(Math.max(0,Math.min(1,v))*255);}
        public Map<String,Object> map(){return AssistWire.payload("brightness",brightness,"contrast",contrast,
            "gamma",gamma,"saturation",saturation,"red",red,"green",green,"blue",blue);}
        public static Profile parse(Object value){
            if(!(value instanceof Map))throw new IllegalArgumentException("PROFILE_OBJECT");
            Map<?,?> m=(Map<?,?>)value;
            fields(m,"brightness","contrast","gamma","saturation","red","green","blue");
            return new Profile(n(m,"brightness",-1,100),n(m,"contrast",50,150),n(m,"gamma",50,200),
                n(m,"saturation",0,150),n(m,"red",25,100),n(m,"green",25,100),n(m,"blue",25,100));
        }
        public String encode(){try{return Json.stringify(map());}catch(Exception e){throw new IllegalStateException(e);}}
        public static Profile decode(String s){try{return parse(Json.parse(s));}catch(Exception e){return identity();}}
    }
    static void fields(Map<?,?> m,String... keys){
        if(!m.keySet().equals(new HashSet<String>(Arrays.asList(keys))))throw new IllegalArgumentException("DISPLAY_FIELDS");
    }
    public static int n(Map<?,?> m,String key,int lo,int hi){
        Object v=m.get(key);if(!(v instanceof Number))throw new IllegalArgumentException("DISPLAY_NUMBER");
        double d=((Number)v).doubleValue();int i=((Number)v).intValue();
        if(d!=i||i<lo||i>hi)throw new IllegalArgumentException("DISPLAY_RANGE");return i;
    }
    static String text(Map<?,?> m,String k){Object v=m.get(k);
        if(!(v instanceof String)||((String)v).length()<1||((String)v).length()>80)throw new IllegalArgumentException("DISPLAY_TEXT");
        return (String)v;
    }
    public static void validate(String type,Map<String,Object> m)throws ProtocolException{
        try{
            if("display.command".equals(type)){
                fields(m,"id","generation","revision","op","slot","pattern","original","profile");
                text(m,"id");n(m,"generation",1,Integer.MAX_VALUE);n(m,"revision",0,Integer.MAX_VALUE);
                String op=text(m,"op");if(!Arrays.asList("begin","update","save","cancel").contains(op))throw new IllegalArgumentException();
                if(!Arrays.asList("indoor","outdoor").contains(text(m,"slot")))throw new IllegalArgumentException();
                n(m,"pattern",0,2);if(!(m.get("original") instanceof Boolean))throw new IllegalArgumentException();
                Profile.parse(m.get("profile"));
            }else if("display.state".equals(type)){
                fields(m,"id","generation","revision","status","slot","pattern","original","profile");
                text(m,"id");n(m,"generation",1,Integer.MAX_VALUE);n(m,"revision",0,Integer.MAX_VALUE);
                if(!Arrays.asList("applied","saved","cancelled","timeout","busy","unavailable","stale","storage_error").contains(text(m,"status")))throw new IllegalArgumentException();
                if(!Arrays.asList("indoor","outdoor").contains(text(m,"slot")))throw new IllegalArgumentException();
                n(m,"pattern",0,2);if(!(m.get("original") instanceof Boolean))throw new IllegalArgumentException();
                Profile.parse(m.get("profile"));
            }else throw new IllegalArgumentException();
        }catch(IllegalArgumentException bad){throw new ProtocolException("DISPLAY_INVALID");}
    }
    public interface Store { Profile load(String slot); boolean save(String slot,Profile value); }
    public static final class Gate {
        private final Store store;
        private Object peer; private String epoch;
        private int highest, generation, revision; private String id,slot;
        private Profile saved,preview; private boolean original;private int pattern;
        private long began,touched;
        public Gate(Store store,Profile saved){this.store=store;this.saved=saved;}
        public synchronized void connect(Object p,String e){cancel();peer=p;epoch=e;highest=0;}
        public synchronized void disconnect(){cancel();peer=null;epoch=null;}
        public synchronized boolean active(){return id!=null;}
        public synchronized Profile applied(){return active()?(original?Profile.identity():preview):saved;}
        public synchronized int pattern(){return pattern;}
        public synchronized long remaining(long now){return active()?Math.max(0,Math.min(began+MAX_MS,touched+IDLE_MS)-now):0;}
        public synchronized Map<String,Object> expire(long now){
            if(!active()||remaining(now)>0)return null;
            Map<String,Object> r=state("timeout");cancel();r.put("profile",saved.map());r.put("original",false);return r;
        }
        public synchronized void cancel(){id=null;preview=null;original=false;}
        public synchronized Map<String,Object> command(Object p,String e,Map<String,Object> m,boolean eligible,long now){
            if(peer!=p||epoch==null||!epoch.equals(e))return null;
            String op=(String)m.get("op");int gen=n(m,"generation",1,Integer.MAX_VALUE),rev=n(m,"revision",0,Integer.MAX_VALUE);
            if("begin".equals(op)){
                if(gen<=highest||rev!=0)return reject(m,"stale");
                highest=gen;
                if(active()||!eligible)return reject(m,"busy");
                id=(String)m.get("id");slot=(String)m.get("slot");generation=gen;revision=0;
                preview=store.load(slot);original=false;pattern=0;began=touched=now;return state("applied");
            }
            if(!active()||remaining(now)==0||gen!=generation||!id.equals(m.get("id"))||!slot.equals(m.get("slot"))||("cancel".equals(op)?rev<=revision:rev!=revision+1))
                return reject(m,"stale");
            revision=rev;touched=now;
            if("cancel".equals(op)){Map<String,Object> r=state("cancelled");cancel();r.put("profile",saved.map());r.put("original",false);return r;}
            if("save".equals(op)){
                // Save the last ACKed preview, never a hidden/unapplied payload or A/B identity.
                if(!store.save(slot,preview))return state("storage_error");
                saved=preview;Map<String,Object> r=state("saved");cancel();r.put("profile",saved.map());r.put("original",false);return r;
            }
            preview=Profile.parse(m.get("profile"));pattern=n(m,"pattern",0,2);original=Boolean.TRUE.equals(m.get("original"));
            return state("applied");
        }
        private Map<String,Object> state(String status){return AssistWire.payload("id",id,"generation",generation,"revision",revision,
            "status",status,"slot",slot,"pattern",pattern,"original",original,"profile",applied().map());}
        private Map<String,Object> reject(Map<String,Object> m,String status){
            Map<String,Object> r=new LinkedHashMap<String,Object>(m);r.remove("op");r.put("status",status);r.put("profile",applied().map());return r;
        }
    }
}
