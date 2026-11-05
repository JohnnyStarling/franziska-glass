package lab.franziska.protocol;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;

/** Wire v1 bounds; no Android, java.util.Base64 or external dependencies. */
public final class AssistWire {
    public static final int MAX_IMAGE = 262144, MAX_AUDIO = 512044, CHUNK = 3072;
    public static final int MAX_CHUNK_PARTS = 6, MAX_BUNDLE = CHUNK * MAX_CHUNK_PARTS;
    public static final long RECEIVE_MS = 30000L;
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final java.util.regex.Pattern NOT_BASE64 = java.util.regex.Pattern.compile("[^A-Za-z0-9+/]");
    private AssistWire() {}
    public static Map<String,Object> payload(Object... pairs) {
        Map<String,Object> p = new LinkedHashMap<String,Object>();
        for (int i=0;i<pairs.length;i+=2) p.put((String)pairs[i], pairs[i+1]);
        return p;
    }
    public static void validate(String type, Map<String,Object> p) throws ProtocolException {
        if (p == null) fail("FIELDS");
        if ("assist.hello".equals(type)) { fields(p); return; }
        if ("assist.status".equals(type)) {
            fields(p,"state","detail"); String s = text(p,"state",1,8);
            if (!Arrays.asList("ready","disabled","busy","error").contains(s)) fail("STATE");
            text(p,"detail",0,160); return;
        }
        id(p.get("request_id"));
        if ("assist.begin".equals(type)) {
            fields(p,"request_id","image_bytes","audio_bytes");
            int i = number(p,"image_bytes",0,MAX_IMAGE), a = number(p,"audio_bytes",0,MAX_AUDIO);
            if (i+a==0) fail("EMPTY");
        } else if ("assist.chunk".equals(type)) {
            if(p.containsKey("parts"))fields(p,"request_id","kind","index","parts");
            else fields(p,"request_id","kind","index","data");
            String k=text(p,"kind",1,5); if (!"image".equals(k)&&!"audio".equals(k)) fail("KIND");
            number(p,"index",0,Integer.MAX_VALUE);
            chunkLength(p);
        } else if ("assist.commit".equals(type)) {
            fields(p,"request_id","image_sha256","audio_sha256");
            hashField(p,"image_sha256"); hashField(p,"audio_sha256");
        } else if ("assist.cancel".equals(type)) fields(p,"request_id");
        else if ("assist.reply".equals(type)) {
            if (p.containsKey("photo_receipt") || p.containsKey("image_sha256")) {
                fields(p,"request_id","text","photo_receipt","image_sha256");
                if (!"conversation_image".equals(text(p,"photo_receipt",1,32))) fail("RECEIPT");
                if (!text(p,"image_sha256",64,64).matches("[0-9a-f]{64}")) fail("HASH");
            } else fields(p,"request_id","text");
            text(p,"text",1,4000);
        }
        else fail("TYPE");
    }
    static byte[] chunkBytes(Map<String,Object> p) throws ProtocolException {
        if(!p.containsKey("parts")){
            byte[] one=decode(text(p,"data",4,4096));
            if(one.length<1||one.length>CHUNK){Arrays.fill(one,(byte)0);fail("CHUNK_SIZE");}
            return one;
        }
        Object raw=p.get("parts");
        if(!(raw instanceof java.util.List))fail("PARTS");
        java.util.List<?> parts=(java.util.List<?>)raw;
        if(parts.size()<1||parts.size()>MAX_CHUNK_PARTS)fail("PARTS");
        byte[] bundle=new byte[MAX_BUNDLE];int at=0;
        try {
            for(Object item:parts){
                if(!(item instanceof String))fail("PARTS");
                String encoded=(String)item;if(encoded.length()<4||encoded.length()>4096)fail("PARTS");
                byte[] part=decode(encoded);
                try{
                    if(part.length<1||part.length>CHUNK||part.length>bundle.length-at)fail("CHUNK_SIZE");
                    System.arraycopy(part,0,bundle,at,part.length);at+=part.length;
                }finally{Arrays.fill(part,(byte)0);}
            }
            return Arrays.copyOf(bundle,at);
        }finally{Arrays.fill(bundle,(byte)0);}
    }
    /** Validate an assist chunk without allocating PCM/JPEG bytes that the receiver decodes later. */
    private static int chunkLength(Map<String,Object> p) throws ProtocolException {
        if(!p.containsKey("parts")){
            int length=decodedLength(text(p,"data",4,4096));
            if(length<1||length>CHUNK)fail("CHUNK_SIZE");
            return length;
        }
        Object raw=p.get("parts");
        if(!(raw instanceof java.util.List))fail("PARTS");
        java.util.List<?> parts=(java.util.List<?>)raw;
        if(parts.size()<1||parts.size()>MAX_CHUNK_PARTS)fail("PARTS");
        int total=0;
        for(Object item:parts){
            if(!(item instanceof String))fail("PARTS");
            String encoded=(String)item;
            if(encoded.length()<4||encoded.length()>4096)fail("PARTS");
            int length=decodedLength(encoded);
            if(length<1||length>CHUNK||length>MAX_BUNDLE-total)fail("CHUNK_SIZE");
            total+=length;
        }
        return total;
    }
    public static String id(Object o) throws ProtocolException {
        if (!(o instanceof String)) { fail("ID"); }
        String s=(String)o;
        if(s.length()!=36)fail("ID");
        for(int i=0;i<36;i++){
            char c=s.charAt(i);
            if(i==8||i==13||i==18||i==23){if(c!='-')fail("ID");}
            else if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))fail("ID");
        }
        return s;
    }
    static void fields(Map<String,Object> p,String... names) throws ProtocolException {
        if (p.size()!=names.length || !p.keySet().containsAll(Arrays.asList(names))) fail("FIELDS");
    }
    static String text(Map<String,Object> p,String key,int min,int max) throws ProtocolException {
        return boundedText(p.get(key),min,max);
    }
    static String boundedText(Object o,int min,int max) throws ProtocolException {
        if (!(o instanceof String)) fail("TEXT");
        String s=(String)o; if(s.length()<min||s.length()>max) fail("TEXT"); return s;
    }
    static int number(Map<String,Object> p,String key,int min,int max) throws ProtocolException {
        Object o=p.get(key); if (!(o instanceof Number)) fail("NUMBER");
        double d=((Number)o).doubleValue(); long n=((Number)o).longValue();
        if (Double.isNaN(d)||Double.isInfinite(d)||d!=(double)n||n<min||n>max) fail("NUMBER");
        return (int)n;
    }
    static void hashField(Map<String,Object> p,String key) throws ProtocolException {
        String s=text(p,key,0,64); if (!s.isEmpty()&&!s.matches("[0-9a-f]{64}")) fail("HASH");
    }
    public static String encode(byte[] bytes,int offset,int count) {
        if (count<1||count>CHUNK||offset<0||offset>bytes.length-count) throw new IllegalArgumentException("chunk");
        char[] encoded=new char[(count+2)/3*4];
        int end=offset+count,at=0;
        for(int i=offset;i<end;i+=3) {
            int left=end-i, n=(bytes[i]&255)<<16;
            if(left>1)n|=(bytes[i+1]&255)<<8; if(left>2)n|=bytes[i+2]&255;
            encoded[at++]=B64[n>>>18];encoded[at++]=B64[(n>>>12)&63];
            encoded[at++]=left>1?B64[(n>>>6)&63]:'=';
            encoded[at++]=left>2?B64[n&63]:'=';
        }
        return new String(encoded);
    }
    public static byte[] decode(String s) throws ProtocolException {
        int length=base64Size(s);
        byte[] out=new byte[length];
        try { scanBase64(s,out); return out; }
        catch(ProtocolException failure){Arrays.fill(out,(byte)0);throw failure;}
    }
    /** Same canonical RFC 4648 check as decode, without creating or re-encoding bytes. */
    static int decodedLength(String s) throws ProtocolException {
        int length=base64Size(s);
        int end=s.length();
        int padding=s.charAt(end-1)=='='?(s.charAt(end-2)=='='?2:1):0;
        // Immutable input; validate every non-padding character in one linear scan.
        if(NOT_BASE64.matcher(s).region(0,end-padding).find())fail("BASE64");
        if(padding!=0){
            int last=base64Digit(s.charAt(end-padding-1));
            if((last&(padding==2?15:3))!=0)fail("BASE64");
        }
        return length;
    }
    private static int base64Size(String s) throws ProtocolException {
        if(s==null||s.length()<4||s.length()>4096||(s.length()&3)!=0)fail("BASE64");
        int last=s.length()-1;
        int padding=s.charAt(last)=='='?(s.charAt(last-1)=='='?2:1):0;
        return s.length()/4*3-padding;
    }
    private static void scanBase64(String s,byte[] out) throws ProtocolException {
        int at=0;
        for(int i=0;i<s.length();i+=4){
            int a=base64Digit(s.charAt(i)),b=base64Digit(s.charAt(i+1));
            char third=s.charAt(i+2),fourth=s.charAt(i+3);
            if(third=='='){
                if(i+4!=s.length()||fourth!='='||(b&15)!=0)fail("BASE64");
                if(out!=null)out[at]=(byte)((a<<2)|(b>>>4));
                return;
            }
            int c=base64Digit(third);
            if(fourth=='='){
                if(i+4!=s.length()||(c&3)!=0)fail("BASE64");
                if(out!=null){out[at++]=(byte)((a<<2)|(b>>>4));out[at]=(byte)((b<<4)|(c>>>2));}
                return;
            }
            int d=base64Digit(fourth);
            if(out!=null){
                out[at++]=(byte)((a<<2)|(b>>>4));
                out[at++]=(byte)((b<<4)|(c>>>2));
                out[at++]=(byte)((c<<6)|d);
            }
        }
    }
    private static int base64Digit(char c) throws ProtocolException {
        if(c>='A'&&c<='Z')return c-'A';
        if(c>='a'&&c<='z')return c-'a'+26;
        if(c>='0'&&c<='9')return c-'0'+52;
        if(c=='+')return 62;
        if(c=='/')return 63;
        fail("BASE64");return 0;
    }
    public static String sha256(byte[] bytes) {
        if(bytes.length==0)return "";
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder b=new StringBuilder(64);
            for(byte v:hash){b.append("0123456789abcdef".charAt((v&255)>>>4));b.append("0123456789abcdef".charAt(v&15));}
            return b.toString();
        } catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public static byte[] wav(byte[] pcm,int count) throws ProtocolException {
        if(count<2||count>480000||(count&1)!=0||count>pcm.length)fail("PCM_SIZE");
        byte[] w=new byte[count+44];
        tag(w,0,"RIFF");put(w,4,count+36,4);tag(w,8,"WAVE");tag(w,12,"fmt ");put(w,16,16,4);
        put(w,20,1,2);put(w,22,1,2);put(w,24,16000,4);put(w,28,32000,4);put(w,32,2,2);put(w,34,16,2);
        tag(w,36,"data");put(w,40,count,4);System.arraycopy(pcm,0,w,44,count);return w;
    }
    private static void tag(byte[] b,int o,String s){for(int i=0;i<s.length();i++)b[o+i]=(byte)s.charAt(i);}
    private static void put(byte[] b,int o,int n,int len){for(int i=0;i<len;i++)b[o+i]=(byte)(n>>>(8*i));}
    private static long le(byte[] b,int o,int n){long v=0;for(int i=0;i<n;i++)v|=(long)(b[o+i]&255)<<(8*i);return v;}
    private static boolean tagIs(byte[] b,int o,String s){for(int i=0;i<s.length();i++)if(b[o+i]!=(byte)s.charAt(i))return false;return true;}
    public static void validateWav(byte[] b) throws ProtocolException {
        if(b.length<46||b.length>MAX_AUDIO||!tagIs(b,0,"RIFF")||le(b,4,4)!=b.length-8
            ||!tagIs(b,8,"WAVE")||!tagIs(b,12,"fmt ")||le(b,16,4)!=16||le(b,20,2)!=1
            ||le(b,22,2)!=1||le(b,24,4)!=16000||le(b,28,4)!=32000||le(b,32,2)!=2
            ||le(b,34,2)!=16||!tagIs(b,36,"data")||le(b,40,4)!=b.length-44||((b.length-44)&1)!=0)fail("WAV");
    }
    /** Structural JPEG parser: bounded dimensions, SOF + SOS, entropy escaping and final EOI. */
    public static void validateJpeg(byte[] b) throws ProtocolException {
        if(b.length<16||b.length>MAX_IMAGE||(b[0]&255)!=255||(b[1]&255)!=216)fail("JPEG");
        int p=2;boolean sof=false,scan=false,quant=false,huffman=false,entropy=false;
        while(p<b.length){
            if((b[p++]&255)!=255)fail("JPEG_MARKER");
            while(p<b.length&&(b[p]&255)==255)p++;
            if(p>=b.length)fail("JPEG_MARKER"); int m=b[p++]&255;
            if(m==217){
                if(!sof||!scan||!entropy)fail("JPEG_END_STATE");
                if(p!=b.length)fail("JPEG_END_TRAILING");
                return;
            }
            if(m==0||m==216||m==1||(m>=208&&m<=215))fail("JPEG_MARKER");
            if(p+2>b.length)fail("JPEG_LENGTH");int len=((b[p]&255)<<8)|(b[p+1]&255);
            if(len<2||p+len>b.length)fail("JPEG_LENGTH");
            if(m==219){
                int q=p+2;while(q<p+len){int spec=b[q++]&255;
                    if((spec&15)>3||(spec>>>4)>1)fail("JPEG_DQT");
                    q+=64*((spec>>>4)+1);if(q>p+len)fail("JPEG_DQT");}
                if(len<67)fail("JPEG_DQT");quant=true;
            }
            if(m==196){
                int q=p+2;while(q<p+len){int spec=b[q++]&255;
                    if((spec&15)>3||(spec>>>4)>1||q+16>p+len)fail("JPEG_DHT");
                    int symbols=0;for(int n=0;n<16;n++)symbols+=b[q++]&255;
                    if(symbols<1||symbols>256||q+symbols>p+len)fail("JPEG_DHT");q+=symbols;}
                if(len<20)fail("JPEG_DHT");huffman=true;
            }
            if(m==192||m==193||m==194){
                if(sof||len<11)fail("JPEG_SOF");int h=((b[p+3]&255)<<8)|(b[p+4]&255),w=((b[p+5]&255)<<8)|(b[p+6]&255);
                int c=b[p+7]&255;if((b[p+2]&255)!=8||w<1||h<1||w>4096||h>4096||c<1||c>4||len!=8+3*c)fail("JPEG_SOF");sof=true;
            }
            if(m==218){if(!sof||!quant||!huffman||len<8||(b[p+2]&255)>4||len!=6+2*(b[p+2]&255))fail("JPEG_SOS");scan=true;p+=len;
                while(p<b.length){if((b[p]&255)!=255){entropy=true;p++;continue;}
                    if(p+1>=b.length)fail("JPEG_SCAN");int next=b[p+1]&255;
                    if(next==0||(next>=208&&next<=215)){if(next==0)entropy=true;p+=2;continue;}break;}
            }else p+=len;
        }
        fail("JPEG_END_MISSING");
    }
    static void fail(String code) throws ProtocolException {throw new ProtocolException("ASSIST_"+code);}
}
