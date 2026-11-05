package lab.franziska.protocol;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** Dependency-free protocol shared verbatim by both APKs and the host simulator. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int MAX_WIRE_BYTES = 65536;
    public static final int MAX_PAYLOAD_BYTES = 49152;
    public static final int MAX_STRING = 4096;
    public static final int MAX_POINTS = 256;
    public static final int MAX_UI_LINES = 3;
    public static final long MAX_TTL_MS = 30000L;
    public static final Charset UTF8 = Charset.forName("UTF-8");

    public static final Set<String> TYPES;
    private static final Set<String> ENVELOPE_FIELDS;
    static {
        HashSet<String> s = new HashSet<String>();
        Collections.addAll(s, "hello", "capabilities", "ping", "ack", "ui.card",
                "ui.vector_scene", "input.event", "mode.start", "mode.stop", "frame",
                "audio.chunk", "error", "state", "assist.hello", "assist.status", "assist.begin",
                "assist.chunk", "assist.commit", "assist.cancel", "assist.reply",
                "talk.start", "talk.ready", "talk.audio", "talk.flow", "talk.clear", "talk.text", "talk.camera", "talk.stop", "talk.error",
                "call.prepare", "call.confirm", "call.cancel", "call.result", "display.command", "display.state");
        TYPES = Collections.unmodifiableSet(s);
        HashSet<String> f = new HashSet<String>();
        Collections.addAll(f, "protocol_version", "session_id", "message_id", "type",
                "source_id", "mode_id", "sequence", "monotonic_timestamp",
                "payload_length", "ttl_ms", "idempotency_key", "payload");
        ENVELOPE_FIELDS = Collections.unmodifiableSet(f);
    }

    private Protocol() {}

    public static final class Envelope {
        public int protocolVersion;
        public String sessionId;
        public String messageId;
        public String type;
        public String sourceId;
        public String modeId;
        public long sequence;
        public long monotonicTimestamp;
        public int payloadLength;
        public long ttlMs;
        public String idempotencyKey;
        public Map<String, Object> payload;
    }

    public static Envelope decode(byte[] wire) throws ProtocolException {
        if (wire == null || wire.length == 0 || wire.length > MAX_WIRE_BYTES) fail("WIRE_SIZE");
        Object parsed = Json.parse(new String(wire, UTF8));
        Map<String, Object> root = object(parsed, "envelope");
        if (!root.keySet().equals(ENVELOPE_FIELDS)) fail("ENVELOPE_FIELDS");
        Envelope e = new Envelope();
        e.protocolVersion = integer(root.get("protocol_version"), "protocol_version");
        if (e.protocolVersion != VERSION) fail("VERSION_MISMATCH");
        e.sessionId = text(root.get("session_id"), "session_id", 128);
        e.messageId = text(root.get("message_id"), "message_id", 128);
        e.type = text(root.get("type"), "type", 64);
        if (!TYPES.contains(e.type)) fail("UNKNOWN_TYPE");
        e.sourceId = text(root.get("source_id"), "source_id", 128);
        e.modeId = optionalText(root.get("mode_id"), "mode_id", 128);
        e.sequence = whole(root.get("sequence"), "sequence", 0, Long.MAX_VALUE);
        e.monotonicTimestamp = whole(root.get("monotonic_timestamp"), "monotonic_timestamp", 0, Long.MAX_VALUE);
        e.payloadLength = integer(root.get("payload_length"), "payload_length");
        if (e.payloadLength < 0 || e.payloadLength > MAX_PAYLOAD_BYTES) fail("PAYLOAD_SIZE");
        e.ttlMs = whole(root.get("ttl_ms"), "ttl_ms", 1, MAX_TTL_MS);
        e.idempotencyKey = optionalText(root.get("idempotency_key"), "idempotency_key", 128);
        e.payload = object(root.get("payload"), "payload");
        int actual = Json.utf8Length(e.payload);
        if (actual != e.payloadLength) fail("PAYLOAD_LENGTH_MISMATCH");
        validatePayload(e.type, e.payload);
        return e;
    }

    public static byte[] encode(Envelope e) throws ProtocolException {
        if (e == null || e.payload == null) fail("MISSING_ENVELOPE");
        validatePayload(e.type, e.payload);
        String payloadJson = Json.stringifyEnvelopePayload(e.payload);
        e.payloadLength = payloadJson.getBytes(UTF8).length;
        LinkedHashMap<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("protocol_version", Long.valueOf(e.protocolVersion));
        root.put("session_id", e.sessionId);
        root.put("message_id", e.messageId);
        root.put("type", e.type);
        root.put("source_id", e.sourceId);
        root.put("mode_id", e.modeId);
        root.put("sequence", Long.valueOf(e.sequence));
        root.put("monotonic_timestamp", Long.valueOf(e.monotonicTimestamp));
        root.put("payload_length", Long.valueOf(e.payloadLength));
        root.put("ttl_ms", Long.valueOf(e.ttlMs));
        root.put("idempotency_key", e.idempotencyKey);
        root.put("payload", e.payload);
        byte[] out = Json.stringifyEnvelope(root, payloadJson).getBytes(UTF8);
        if (out.length > MAX_WIRE_BYTES) fail("WIRE_SIZE");
        return out;
    }

    public static void validatePayload(String type, Map<String, Object> p)
            throws ProtocolException {
        if (!TYPES.contains(type)) fail("UNKNOWN_TYPE");
        validateValue(p, 0);
        if (type.startsWith("display.")) { DisplayCalibration.validate(type, p); return; }
        if (type.startsWith("assist.")) { AssistWire.validate(type, p); return; }
        if (type.startsWith("talk.")) { TalkWire.validate(type, p); return; }
        if (type.startsWith("call.")) { CallWire.validate(type, p); return; }
        if ("hello".equals(type) || "ping".equals(type)
                || "mode.start".equals(type) || "mode.stop".equals(type)) {
            if (!p.isEmpty()) fail("PAYLOAD_FIELDS:" + type);
        } else if ("ack".equals(type)) {
            exactFields(p, "reply_to");
            text(p.get("reply_to"), "reply_to", 128);
        } else if ("input.event".equals(type)) {
            exactFields(p, "action");
            String action = text(p.get("action"), "action", 32);
            if (!("tap".equals(action) || "next".equals(action)
                    || "previous".equals(action))) fail("INPUT_ACTION");
        } else if ("ui.card".equals(type)) {
            text(p.get("title"), "title", 120);
            if (p.containsKey("local_mirror") && !Boolean.TRUE.equals(p.get("local_mirror"))) fail("LOCAL_MIRROR_FLAG");
            Object lines = p.get("lines");
            if (!(lines instanceof List) || ((List<?>) lines).size() > MAX_UI_LINES) {
                fail("CARD_LINES");
            }
            for (Object line : (List<?>) lines) text(line, "line", 240);
        } else if ("ui.vector_scene".equals(type)) {
            Object shapes = p.get("shapes");
            if (!(shapes instanceof List) || ((List<?>) shapes).size() > 64) fail("SHAPES");
            int points = 0;
            for (Object shape : (List<?>) shapes) {
                Map<String, Object> sm = object(shape, "shape");
                String kind = text(sm.get("kind"), "kind", 24);
                if (!("line".equals(kind) || "point".equals(kind) || "polyline".equals(kind) || "arrow".equals(kind))) fail("SHAPE_KIND");
                Object ps = sm.get("points");
                if (!(ps instanceof List)) fail("POINTS");
                points += ((List<?>)ps).size();
                for (Object point : (List<?>)ps) {
                    List<?> xy = list(point, "point");
                    if (xy.size() != 2) fail("POINT_ARITY");
                    finite(xy.get(0), "x", 0, 640); finite(xy.get(1), "y", 0, 360);
                }
            }
            if (points > MAX_POINTS) fail("TOO_MANY_POINTS");
        } else if ("frame".equals(type)) {
            text(p.get("frame_id"), "frame_id", 128);
            whole(p.get("width"), "width", 1, 4096);
            whole(p.get("height"), "height", 1, 4096);
            finite(p.get("orientation"), "orientation", 0, 359);
            whole(p.get("source_timestamp"), "source_timestamp", 0, Long.MAX_VALUE);
        } else if ("audio.chunk".equals(type)) {
            whole(p.get("sample_count"), "sample_count", 1, 48000);
        }
    }

    private static void exactFields(Map<String, Object> value, String only)
            throws ProtocolException {
        if (value.size() != 1 || !value.containsKey(only)) fail("PAYLOAD_FIELDS");
    }

    private static void validateValue(Object value, int depth) throws ProtocolException {
        if (depth > 8) fail("NESTING");
        if (value == null || value instanceof Boolean || value instanceof String) {
            if (value instanceof String && ((String) value).length() > MAX_STRING) {
                fail("STRING_SIZE");
            }
            return;
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) fail("NON_FINITE");
            return;
        }
        if (value instanceof Map) {
            if (((Map<?, ?>) value).size() > 128) fail("OBJECT_SIZE");
            for (Object item : ((Map<?, ?>) value).values()) {
                validateValue(item, depth + 1);
            }
            return;
        }
        if (value instanceof List) {
            if (((List<?>) value).size() > 512) fail("ARRAY_SIZE");
            for (Object item : (List<?>) value) validateValue(item, depth + 1);
            return;
        }
        fail("VALUE_TYPE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name)
            throws ProtocolException {
        if (!(value instanceof Map)) fail("EXPECTED_OBJECT:" + name);
        return (Map<String, Object>) value;
    }

    private static List<?> list(Object value, String name) throws ProtocolException {
        if (!(value instanceof List)) fail("EXPECTED_ARRAY:" + name);
        return (List<?>) value;
    }

    private static String text(Object value, String name, int maximum)
            throws ProtocolException {
        if (!(value instanceof String) || ((String) value).length() == 0
                || ((String) value).length() > maximum) {
            fail("STRING:" + name);
        }
        return (String) value;
    }

    private static String optionalText(Object value, String name, int maximum)
            throws ProtocolException {
        if (value == null) return null;
        return text(value, name, maximum);
    }

    private static int integer(Object value, String name) throws ProtocolException {
        return (int) whole(value, name, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static long whole(Object value, String name, long minimum, long maximum)
            throws ProtocolException {
        if (!(value instanceof Number)) fail("NUMBER:" + name);
        double decimal = ((Number) value).doubleValue();
        long whole = ((Number) value).longValue();
        if (Double.isNaN(decimal) || Double.isInfinite(decimal)
                || decimal != (double) whole || whole < minimum || whole > maximum) {
            fail("RANGE:" + name);
        }
        return whole;
    }

    private static double finite(Object value, String name,
                                 double minimum, double maximum)
            throws ProtocolException {
        if (!(value instanceof Number)) fail("NUMBER:" + name);
        double number = ((Number) value).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number)
                || number < minimum || number > maximum) {
            fail("RANGE:" + name);
        }
        return number;
    }

    private static void fail(String code) throws ProtocolException {
        throw new ProtocolException(code);
    }
}
