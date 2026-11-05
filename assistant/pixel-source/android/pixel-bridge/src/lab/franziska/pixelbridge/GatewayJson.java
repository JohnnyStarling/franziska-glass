package lab.franziska.pixelbridge;

import java.util.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import lab.franziska.protocol.Json;

/** Bounded strict peer JSON; supports deep hello snapshots without changing lab protocol limits. */
final class GatewayJson {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static Map<String,Object> obj(Object... kv) {
        Map<String,Object> m = new LinkedHashMap<String,Object>();
        for (int i=0;i<kv.length;i+=2) m.put((String)kv[i],kv[i+1]);
        return m;
    }
    static byte[] encode(Object value) throws Exception { return Json.stringify(value).getBytes(UTF8); }
    static Map<?,?> map(Object o) throws GatewayException {
        if (!(o instanceof Map)) throw new GatewayException("PROTOCOL_INVALID");
        return (Map<?,?>)o;
    }
    static String string(Object o, int max) throws GatewayException {
        if (!(o instanceof String) || ((String)o).length()>max) throw new GatewayException("PROTOCOL_INVALID");
        return (String)o;
    }
    static long integer(Object o, long min, long max) throws GatewayException {
        if (!(o instanceof Long) && !(o instanceof Integer)) throw new GatewayException("PROTOCOL_INVALID");
        long n=((Number)o).longValue();
        if(n<min || n>max) throw new GatewayException("PROTOCOL_INVALID");
        return n;
    }
    static String utf8(byte[] b) throws GatewayException {
        try { return UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString(); }
        catch(Exception e) { throw new GatewayException("PROTOCOL_INVALID_UTF8"); }
    }
    static Object parse(byte[] b) throws GatewayException {
        if(b.length>2097152) throw new GatewayException("RESPONSE_TOO_LARGE");
        Parser p=new Parser(utf8(b)); Object v=p.value(0); p.ws();
        if(p.i!=p.s.length()) throw new GatewayException("PROTOCOL_INVALID"); return v;
    }
    private static final class Parser {
        final String s; int i, nodes;
        Parser(String s){this.s=s;}
        void bad() throws GatewayException {throw new GatewayException("PROTOCOL_INVALID");}
        void ws(){while(i<s.length() && (s.charAt(i)==' '||s.charAt(i)=='\n'||s.charAt(i)=='\r'||s.charAt(i)=='\t'))i++;}
        boolean take(char c){if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        Object value(int d) throws GatewayException {
            if(d>32 || ++nodes>65536) bad(); ws(); if(i>=s.length())bad(); char c=s.charAt(i);
            if(c=='"')return str();
            if(take('{')){Map<String,Object> m=new LinkedHashMap<String,Object>();ws();if(take('}'))return m;
                do{ws();String k=str();ws();if(!take(':')||m.containsKey(k))bad();m.put(k,value(d+1));ws();if(take('}'))return m;}while(take(','));bad();}
            if(take('[')){List<Object> a=new ArrayList<Object>();ws();if(take(']'))return a;
                do{a.add(value(d+1));ws();if(take(']'))return a;}while(take(','));bad();}
            for(String word:new String[]{"true","false","null"})if(s.startsWith(word,i)){i+=word.length();return word.equals("null")?null:Boolean.valueOf(word);}
            int start=i;take('-');if(!take('0')){if(i>=s.length()||s.charAt(i)<'1'||s.charAt(i)>'9')bad();while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}
            boolean decimal=false;
            if(take('.')){decimal=true;int n=i;while(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')i++;if(n==i)bad();}
            if(take('e')||take('E')){decimal=true;if(!take('+'))take('-');int n=i;while(i<s.length()&&s.charAt(i)>='0'&&s.charAt(i)<='9')i++;if(n==i)bad();}
            try {if(decimal){double n=Double.parseDouble(s.substring(start,i));if(Double.isInfinite(n)||Double.isNaN(n))bad();return Double.valueOf(n);}return Long.valueOf(s.substring(start,i));}
            catch(NumberFormatException e){bad();return null;}
        }
        String str() throws GatewayException {
            if(!take('"'))bad();StringBuilder b=new StringBuilder();boolean end=false;
            while(i<s.length()){char c=s.charAt(i++);if(c=='"'){end=true;break;}if(c<32)bad();
                if(c=='\\'){if(i>=s.length())bad();char e=s.charAt(i++);switch(e){
                    case '"':c='"';break;case '\\':c='\\';break;case '/':c='/';break;
                    case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                    case 'u':if(i+4>s.length())bad();int value=0;for(int h=0;h<4;h++){char digit=s.charAt(i++);int n=digit>='0'&&digit<='9'?digit-'0':digit>='a'&&digit<='f'?digit-'a'+10:digit>='A'&&digit<='F'?digit-'A'+10:-1;if(n<0)bad();value=(value<<4)|n;}c=(char)value;break;default:bad();}
                }b.append(c);
            }
            if(!end)bad();
            for(int n=0;n<b.length();n++){char c=b.charAt(n);if(Character.isHighSurrogate(c)){if(++n>=b.length()||!Character.isLowSurrogate(b.charAt(n)))bad();}else if(Character.isLowSurrogate(c))bad();}
            return b.toString();
        }
    }
}
