package lab.franziska.pixelbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lab.franziska.protocol.Json;
import lab.franziska.protocol.CallWire;
import lab.franziska.protocol.ProtocolException;

/** Strict typed broker command parser; never turns server strings into arbitrary Android actions. */
final class MyGlassWire {
    static final String SHOW_CARD = "show_card";
    static final String NAVIGATE = "navigate_destination";
    static final String CALL_PREPARE = "call_prepare";
    static final class Command {
        final String id, kind, leaseToken, title, body, label, e164;
        final long expiresAt;
        final double latitude, longitude;
        Command(String id, String kind, String leaseToken, long expiresAt,
                String title, String body, String label, String e164, double latitude, double longitude) {
            this.id=id; this.kind=kind; this.leaseToken=leaseToken; this.expiresAt=expiresAt;
            this.title=title; this.body=body; this.label=label; this.e164=e164;
            this.latitude=latitude; this.longitude=longitude;
        }
        Map<String,Object> cardPayload() {
            if (!SHOW_CARD.equals(kind)) throw new IllegalStateException("not a card");
            Map<String,Object> result=new LinkedHashMap<String,Object>();
            result.put("title",title);
            result.put("lines",Arrays.<Object>asList(body,"Частная карточка · Pixel"));
            result.put("myglass_private",Boolean.TRUE);
            return result;
        }
    }
    private MyGlassWire() { }

    static List<Command> claim(String encoded) throws ProtocolException {
        if (encoded==null || encoded.length()>16384) throw new ProtocolException("MYGLASS_RESPONSE_SIZE");
        Object value=Json.parse(encoded);
        if (!(value instanceof Map)) throw new ProtocolException("MYGLASS_RESPONSE_SHAPE");
        Map<?,?> root=(Map<?,?>)value;
        if (root.size()!=1 || !(root.get("commands") instanceof List)) throw new ProtocolException("MYGLASS_RESPONSE_SHAPE");
        List<?> rows=(List<?>)root.get("commands");
        if (rows.size()>8) throw new ProtocolException("MYGLASS_RESPONSE_COUNT");
        ArrayList<Command> result=new ArrayList<Command>();
        for (Object row:rows) result.add(command(row));
        return result;
    }

    private static Command command(Object value) throws ProtocolException {
        if (!(value instanceof Map)) throw new ProtocolException("MYGLASS_COMMAND_SHAPE");
        Map<?,?> row=(Map<?,?>)value;
        if (row.size()!=5 || !(row.get("payload") instanceof Map)) throw new ProtocolException("MYGLASS_COMMAND_SHAPE");
        String id=text(row.get("id"),36,36,false);
        if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}"))
            throw new ProtocolException("MYGLASS_COMMAND_ID");
        String kind=text(row.get("kind"),1,64,false);
        String lease=text(row.get("lease_token"),32,128,false);
        if (!lease.matches("[A-Za-z0-9_-]+")) throw new ProtocolException("MYGLASS_LEASE");
        Object expires=row.get("expires_at");
        if (!(expires instanceof Number) || ((Number)expires).longValue()<=0)
            throw new ProtocolException("MYGLASS_EXPIRY");
        Map<?,?> payload=(Map<?,?>)row.get("payload");
        if (SHOW_CARD.equals(kind)) {
            if (payload.size()!=3) throw new ProtocolException("MYGLASS_CARD_SHAPE");
            String cardId=text(payload.get("card_id"),36,36,false);
            if (!cardId.matches("[a-f0-9-]{36}")) throw new ProtocolException("MYGLASS_CARD_ID");
            String title=text(payload.get("title"),1,80,false);
            String body=text(payload.get("body"),1,240,true);
            return new Command(id,kind,lease,((Number)expires).longValue(),title,body,null,null,0,0);
        }
        if (NAVIGATE.equals(kind)) {
            if (payload.size()!=3) throw new ProtocolException("MYGLASS_ROUTE_SHAPE");
            String label=text(payload.get("label"),1,80,false);
            double lat=coordinate(payload.get("latitude"),90);
            double lon=coordinate(payload.get("longitude"),180);
            return new Command(id,kind,lease,((Number)expires).longValue(),null,null,label,null,lat,lon);
        }
        if (CALL_PREPARE.equals(kind)) {
            if (payload.size()!=2) throw new ProtocolException("MYGLASS_CALL_SHAPE");
            String label=text(payload.get("label"),1,40,false);
            String e164=text(payload.get("e164"),8,16,false);
            CallWire.validate("call.prepare",CallWire.prepare(id,label,e164));
            return new Command(id,kind,lease,((Number)expires).longValue(),null,null,label,e164,0,0);
        }
        // Future commands are acknowledged as unsupported by the worker, never interpreted.
        return new Command(id,kind,lease,((Number)expires).longValue(),null,null,null,null,0,0);
    }

    private static double coordinate(Object value,double maximum) throws ProtocolException {
        if (!(value instanceof Number)) throw new ProtocolException("MYGLASS_COORDINATE");
        double number=((Number)value).doubleValue();
        if (Double.isNaN(number)||Double.isInfinite(number)||number < -maximum || number > maximum)
            throw new ProtocolException("MYGLASS_COORDINATE");
        return number;
    }

    static String text(Object value,int minimum,int maximum,boolean multiline) throws ProtocolException {
        if (!(value instanceof String)) throw new ProtocolException("MYGLASS_TEXT");
        String text=(String)value;
        if (text.length()<minimum||text.length()>maximum||text.trim().length()==0)
            throw new ProtocolException("MYGLASS_TEXT");
        for (int at=0;at<text.length();at++) {
            char c=text.charAt(at);
            if (multiline && (c=='\n'||c=='\t')) continue;
            int type=Character.getType(c);
            if (type==Character.CONTROL || type==Character.FORMAT && c!='\u200d'
                    || type==Character.SURROGATE && !Character.isSurrogatePair(c,
                        at+1<text.length()?text.charAt(at+1):0)) {
                // A low surrogate from a valid pair is allowed below.
                if (!(Character.isLowSurrogate(c) && at>0 && Character.isHighSurrogate(text.charAt(at-1))))
                    throw new ProtocolException("MYGLASS_TEXT_CONTROL");
            }
        }
        return text;
    }
}
