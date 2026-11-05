package lab.franziska.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small, exact cellular-call control messages on the pinned Glass/Pixel link. */
public final class CallWire {
    private CallWire() { }

    public static void validate(String type, Map<String,Object> payload) throws ProtocolException {
        if (payload == null) fail("FIELDS");
        if ("call.prepare".equals(type)) {
            fields(payload, "request_id", "label", "e164");
            String label = string(payload.get("label"), 1, 40, "LABEL");
            if (!label.equals(label.trim()) || unsafe(label)) fail("LABEL");
            String number = string(payload.get("e164"), 8, 16, "NUMBER");
            if (!number.matches("\\+[1-9][0-9]{6,14}")) fail("NUMBER");
        } else if ("call.confirm".equals(type) || "call.cancel".equals(type)) {
            fields(payload, "request_id");
        } else if ("call.result".equals(type)) {
            fields(payload, "request_id", "status");
            String status = string(payload.get("status"), 1, 32, "STATUS");
            if (!("REQUESTED".equals(status) || "PERMISSION_REQUIRED".equals(status)
                    || "NOT_CONFIRMED".equals(status) || "NO_TELECOM".equals(status)
                    || "PLATFORM_REJECTED".equals(status) || "CANCELLED".equals(status))) fail("STATUS");
        } else fail("TYPE");
        String id = string(payload.get("request_id"), 36, 36, "ID");
        if (!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) fail("ID");
    }

    public static Map<String,Object> payload(String requestId) {
        LinkedHashMap<String,Object> p = new LinkedHashMap<String,Object>();
        p.put("request_id", requestId); return p;
    }

    public static Map<String,Object> result(String requestId, String status) {
        Map<String,Object> p = payload(requestId); p.put("status", status); return p;
    }

    public static Map<String,Object> prepare(String requestId, String label, String e164) {
        Map<String,Object> p = payload(requestId); p.put("label", label); p.put("e164", e164); return p;
    }

    private static void fields(Map<String,Object> p, String... names) throws ProtocolException {
        if (p.size() != names.length) fail("FIELDS");
        for (String name : names) if (!p.containsKey(name)) fail("FIELDS");
    }

    private static String string(Object value, int min, int max, String reason) throws ProtocolException {
        if (!(value instanceof String) || ((String)value).length() < min || ((String)value).length() > max) fail(reason);
        return (String)value;
    }

    private static boolean unsafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == 127 || (c >= 0x202a && c <= 0x202e)
                    || (c >= 0x2066 && c <= 0x2069)) return true;
        }
        return false;
    }

    private static void fail(String code) throws ProtocolException { throw new ProtocolException("CALL_" + code); }
}
