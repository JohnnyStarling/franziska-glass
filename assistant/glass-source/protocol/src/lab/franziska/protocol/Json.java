package lab.franziska.protocol;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Small strict JSON codec: duplicate keys and non-finite numbers are rejected. */
public final class Json {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    // Fixed linear character classes; native ICU scans on legacy Android avoid
    // interpreting every byte of the long, ordinary PCM/base64 strings.
    private static final Pattern STRING_SPECIAL = Pattern.compile("[\\x00-\\x1F\\x22\\x5C]");
    private static final Pattern NOT_PLAIN_ASCII = Pattern.compile("[^\\x20-\\x21\\x23-\\x5B\\x5D-\\x7F]");
    private Json() {}

    public static Object parse(String source) throws ProtocolException {
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != source.length()) {
            throw new ProtocolException("TRAILING_JSON");
        }
        return value;
    }

    public static String stringify(Object value) throws ProtocolException {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    /** Envelope payload occupies depth one in the complete wire object. */
    static String stringifyEnvelopePayload(Object value) throws ProtocolException {
        StringBuilder output = new StringBuilder();
        write(value, output, 1);
        return output.toString();
    }

    /** Byte count of stringify(value) in UTF-8, without materializing its large PCM payload. */
    static int utf8Length(Object value) throws ProtocolException {
        return utf8Length(value, 0);
    }

    /** The payload was already validated and serialized; do not serialize it a second time. */
    static String stringifyEnvelope(Map<String, Object> root, String payloadJson)
            throws ProtocolException {
        StringBuilder output = new StringBuilder(payloadJson.length() + 256);
        output.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            if (!first) output.append(',');
            first = false;
            quote(entry.getKey(), output);
            output.append(':');
            if ("payload".equals(entry.getKey())) output.append(payloadJson);
            else write(entry.getValue(), output, 1);
        }
        output.append('}');
        return output.toString();
    }

    private static int utf8Length(Object value, int depth) throws ProtocolException {
        if (depth > 8) throw new ProtocolException("NESTING");
        if (value == null) return 4;
        if (value instanceof String) return quotedUtf8Length((String) value);
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? 4 : 5;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new ProtocolException("NON_FINITE");
            }
            return plainUtf8Length(value.toString());
        }
        if (value instanceof Map) {
            int length = 2;
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new ProtocolException("KEY_TYPE");
                if (!first) length++;
                first = false;
                length += quotedUtf8Length((String) entry.getKey()) + 1;
                length += utf8Length(entry.getValue(), depth + 1);
            }
            return length;
        }
        if (value instanceof List) {
            int length = 2;
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) length++;
                first = false;
                length += utf8Length(item, depth + 1);
            }
            return length;
        }
        throw new ProtocolException("VALUE_TYPE");
    }

    private static int quotedUtf8Length(String value) {
        if (value.length() >= 128 && !NOT_PLAIN_ASCII.matcher(value).find()) return value.length() + 2;
        int length = 2;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character == '\b'
                    || character == '\f' || character == '\n' || character == '\r'
                    || character == '\t') length += 2;
            else if (character < 0x20) length += 6;
            else if (character < 0x80) length++;
            else if (character < 0x800) length += 2;
            else if (highSurrogate(character)
                    && index + 1 < value.length()
                    && lowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (surrogate(character)) length += String.valueOf(character).getBytes(UTF8).length;
            else length += 3;
        }
        return length;
    }

    private static int plainUtf8Length(String value) {
        int length = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) length++;
            else if (character < 0x800) length += 2;
            else if (highSurrogate(character)
                    && index + 1 < value.length()
                    && lowSurrogate(value.charAt(index + 1))) {
                length += 4;
                index++;
            } else if (surrogate(character)) length += String.valueOf(character).getBytes(UTF8).length;
            else length += 3;
        }
        return length;
    }

    private static boolean highSurrogate(char value) {
        return value >= 0xd800 && value <= 0xdbff;
    }

    private static boolean lowSurrogate(char value) {
        return value >= 0xdc00 && value <= 0xdfff;
    }

    private static boolean surrogate(char value) {
        return value >= 0xd800 && value <= 0xdfff;
    }

    private static void write(Object value, StringBuilder output, int depth)
            throws ProtocolException {
        if (depth > 8) throw new ProtocolException("NESTING");
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            quote((String) value, output);
        } else if (value instanceof Boolean) {
            output.append(value.toString());
        } else if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new ProtocolException("NON_FINITE");
            }
            output.append(value.toString());
        } else if (value instanceof Map) {
            writeObject((Map<?, ?>) value, output, depth);
        } else if (value instanceof List) {
            writeArray((List<?>) value, output, depth);
        } else {
            throw new ProtocolException("VALUE_TYPE");
        }
    }

    private static void writeObject(Map<?, ?> value, StringBuilder output, int depth)
            throws ProtocolException {
        output.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new ProtocolException("KEY_TYPE");
            }
            if (!first) output.append(',');
            first = false;
            quote((String) entry.getKey(), output);
            output.append(':');
            write(entry.getValue(), output, depth + 1);
        }
        output.append('}');
    }

    private static void writeArray(List<?> value, StringBuilder output, int depth)
            throws ProtocolException {
        output.append('[');
        boolean first = true;
        for (Object item : value) {
            if (!first) output.append(',');
            first = false;
            write(item, output, depth + 1);
        }
        output.append(']');
    }

    private static void quote(String value, StringBuilder output) {
        if (value.length() >= 128 && !STRING_SPECIAL.matcher(value).find()) {
            output.append('"').append(value).append('"');
            return;
        }
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        String hex = Integer.toHexString(character);
                        output.append("\\u0000", 0, 6 - hex.length()).append(hex);
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String source;
        private Matcher stringSpecial;
        private int index;

        Parser(String source) {
            this.source = source;
        }

        void whitespace() {
            while (index < source.length()
                    && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        Object value(int depth) throws ProtocolException {
            if (depth > 8) bad("NESTING");
            whitespace();
            if (index >= source.length()) bad("EOF");
            char character = source.charAt(index);
            if (character == '{') return object(depth);
            if (character == '[') return array(depth);
            if (character == '"') return string();
            if (character == 't' && take("true")) return Boolean.TRUE;
            if (character == 'f' && take("false")) return Boolean.FALSE;
            if (character == 'n' && take("null")) return null;
            if (character == '-' || (character >= '0' && character <= '9')) {
                return number();
            }
            bad("JSON_TOKEN");
            return null;
        }

        Map<String, Object> object(int depth) throws ProtocolException {
            LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
            index++;
            whitespace();
            if (peek('}')) {
                index++;
                return result;
            }
            while (true) {
                whitespace();
                if (!peek('"')) bad("OBJECT_KEY");
                String key = string();
                if (result.containsKey(key)) bad("DUPLICATE_KEY");
                whitespace();
                expect(':');
                result.put(key, value(depth + 1));
                whitespace();
                if (peek('}')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        List<Object> array(int depth) throws ProtocolException {
            ArrayList<Object> result = new ArrayList<Object>();
            index++;
            whitespace();
            if (peek(']')) {
                index++;
                return result;
            }
            while (true) {
                result.add(value(depth + 1));
                whitespace();
                if (peek(']')) {
                    index++;
                    return result;
                }
                expect(',');
            }
        }

        String string() throws ProtocolException {
            expect('"');
            int start = index;
            // Most wire strings (including base64 PCM) contain no escapes. The old
            // path copied every character into a growing StringBuilder on Dalvik.
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return source.substring(start, index - 1);
                if (character == '\\') {
                    index--;
                    break;
                }
                if (character < 0x20) bad("CONTROL_CHAR");
                if (index - start == 128 && index < source.length()) {
                    if (stringSpecial == null) stringSpecial = STRING_SPECIAL.matcher(source);
                    if (!stringSpecial.find(index)) { index = source.length(); break; }
                    index = stringSpecial.start();
                    character = source.charAt(index++);
                    if (character == '"') return source.substring(start, index - 1);
                    if (character == '\\') { index--; break; }
                    bad("CONTROL_CHAR");
                }
            }
            StringBuilder result = new StringBuilder();
            result.append(source, start, index);
            while (index < source.length()) {
                char character = source.charAt(index++);
                if (character == '"') return result.toString();
                if (character == '\\') {
                    appendEscape(result);
                } else {
                    if (character < 0x20) bad("CONTROL_CHAR");
                    result.append(character);
                }
            }
            bad("STRING_EOF");
            return null;
        }

        private void appendEscape(StringBuilder result) throws ProtocolException {
            if (index >= source.length()) bad("ESCAPE");
            char escaped = source.charAt(index++);
            if (escaped == '"' || escaped == '\\' || escaped == '/') {
                result.append(escaped);
            } else if (escaped == 'b') {
                result.append('\b');
            } else if (escaped == 'f') {
                result.append('\f');
            } else if (escaped == 'n') {
                result.append('\n');
            } else if (escaped == 'r') {
                result.append('\r');
            } else if (escaped == 't') {
                result.append('\t');
            } else if (escaped == 'u') {
                appendUnicode(result);
            } else {
                bad("ESCAPE");
            }
        }

        private void appendUnicode(StringBuilder result) throws ProtocolException {
            if (index + 4 > source.length()) bad("UNICODE");
            try {
                result.append((char) Integer.parseInt(
                        source.substring(index, index + 4), 16));
            } catch (NumberFormatException failure) {
                bad("UNICODE");
            }
            index += 4;
        }

        Number number() throws ProtocolException {
            int start = index;
            if (peek('-')) index++;
            if (index >= source.length()) bad("NUMBER");
            if (peek('0')) {
                index++;
            } else {
                if (!digit()) bad("NUMBER");
                while (digit()) index++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                index++;
                if (!digit()) bad("NUMBER");
                while (digit()) index++;
            }
            if (peek('e') || peek('E')) {
                decimal = true;
                index++;
                if (peek('+') || peek('-')) index++;
                if (!digit()) bad("NUMBER");
                while (digit()) index++;
            }
            String token = source.substring(start, index);
            try {
                // Separate returns avoid boxed numeric conditional promotion to Double.
                if (decimal) return Double.valueOf(token);
                return Long.valueOf(token);
            } catch (NumberFormatException failure) {
                bad("NUMBER");
                return null;
            }
        }

        boolean digit() {
            return index < source.length()
                    && source.charAt(index) >= '0' && source.charAt(index) <= '9';
        }

        boolean peek(char character) {
            return index < source.length() && source.charAt(index) == character;
        }

        void expect(char character) throws ProtocolException {
            whitespace();
            if (!peek(character)) bad("EXPECTED_" + character);
            index++;
        }

        boolean take(String token) {
            if (source.regionMatches(index, token, 0, token.length())) {
                index += token.length();
                return true;
            }
            return false;
        }

        void bad(String code) throws ProtocolException {
            throw new ProtocolException(code + "@" + index);
        }
    }
}
