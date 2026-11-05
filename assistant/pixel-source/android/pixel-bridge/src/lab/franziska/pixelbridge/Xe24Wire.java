package lab.franziska.pixelbridge;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Narrow, independently implemented XE24 companion subset. Never accepts remote commands. */
public final class Xe24Wire {
    public static final int VERSION = 655366; // XE24 minor 10, major 6.
    public static final int MAX_FRAME = 512 * 1024;
    public static final int MAX_SCREEN = 384 * 1024;
    private Xe24Wire() { }

    public static final class Reply {
        public int version = -1;
        public Integer battery;
        public String name;
        public String software;
        public Integer width, height;
        public byte[] screen;
        public Boolean screenOn;
        public Integer locationRequestType;
        public String locationProvider;
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~127L) != 0) { out.write(((int) value & 127) | 128); value >>>= 7; }
        out.write((int) value);
    }
    private static void fieldInt(ByteArrayOutputStream out, int field, long value) {
        varint(out, field << 3); varint(out, value);
    }
    private static void fieldBytes(ByteArrayOutputStream out, int field, byte[] value) {
        varint(out, (field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length);
    }
    private static byte[] envelope(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fieldInt(out, 1, VERSION);
        if (field > 0) fieldBytes(out, field, value);
        return out.toByteArray();
    }
    public static byte[] handshake() { return envelope(0, new byte[0]); }
    public static byte[] infoRequest() {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        fieldInt(info, 1, 1); // battery
        fieldInt(info, 3, 1); // name
        fieldInt(info, 4, 1); // software
        fieldInt(info, 6, 1); // screen dimensions
        return envelope(21, info.toByteArray());
    }
    public static byte[] screenRequest(boolean start) {
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        fieldInt(request, start ? 1 : 2, 1);
        return envelope(15, request.toByteArray());
    }
    public static byte[] wifiRequest(String ssid, String security, String key) {
        if (ssid == null || ssid.isEmpty() || ssid.length() > 32 || ssid.indexOf('\n') >= 0 || ssid.indexOf('\r') >= 0)
            throw new IllegalArgumentException("SSID должен содержать 1–32 символа без переноса строки");
        if (!"WPA".equals(security) && !"NONE".equals(security))
            throw new IllegalArgumentException("Поддерживаются только WPA/WPA2 и открытая сеть");
        if ("WPA".equals(security) && (key == null || key.length() < 8 || key.length() > 63 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0))
            throw new IllegalArgumentException("Пароль WPA должен содержать 8–63 символа без переноса строки");
        if ("NONE".equals(security) && key != null && !key.isEmpty())
            throw new IllegalArgumentException("Открытая сеть не принимает пароль");
        String setup = ssid + "\n" + security + ("WPA".equals(security) ? "\n" + key : "");
        return envelope(12, setup.getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] navigationRequest(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180)
            throw new IllegalArgumentException("Недопустимые координаты");
        String place = latitude + "," + longitude;
        ByteArrayOutputStream nav = new ByteArrayOutputStream();
        fieldBytes(nav, 1, ("geo:" + place + "?q=" + place).getBytes(StandardCharsets.UTF_8));
        return envelope(10, nav.toByteArray());
    }
    private static void fixed(ByteArrayOutputStream out, int field, long value, int bytes) {
        varint(out, (field << 3) | (bytes == 8 ? 1 : 5));
        for (int i = 0; i < bytes; i++) out.write((int) (value >>> (8 * i)) & 255);
    }
    public static byte[] locationUpdate(String provider, double latitude, double longitude, float accuracy, long timeMillis) {
        if (provider == null || provider.isEmpty() || provider.length() > 40 || !Double.isFinite(latitude)
                || !Double.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180
                || !Float.isFinite(accuracy) || accuracy < 0 || accuracy > 100000 || timeMillis <= 0)
            throw new IllegalArgumentException("Недопустимая позиция");
        ByteArrayOutputStream location = new ByteArrayOutputStream();
        fixed(location, 1, Double.doubleToRawLongBits(latitude), 8);
        fixed(location, 2, Double.doubleToRawLongBits(longitude), 8);
        fixed(location, 3, Float.floatToRawIntBits(accuracy), 4);
        fieldInt(location, 7, timeMillis);
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        fieldInt(message, 1, 0); // LOCATION_CHANGED
        fieldBytes(message, 2, provider.getBytes(StandardCharsets.UTF_8));
        fieldBytes(message, 3, location.toByteArray());
        return envelope(7, message.toByteArray());
    }
    public static void write(OutputStream output, byte[] message) throws IOException {
        if (message == null || message.length < 1 || message.length > MAX_FRAME) throw new IOException("companion frame size");
        ByteArrayOutputStream length = new ByteArrayOutputStream(3);
        varint(length, message.length);
        output.write(length.toByteArray()); output.write(message); output.flush();
    }
    public static byte[] read(InputStream input) throws IOException {
        int first = input.read(); if (first < 0) return null;
        long length = first & 127; int shift = 7; int b = first;
        for (int i = 1; (b & 128) != 0; i++) {
            if (i >= 5) throw new IOException("companion length varint");
            b = input.read(); if (b < 0) throw new EOFException("companion length truncated");
            length |= (long) (b & 127) << shift; shift += 7;
        }
        if (length < 1 || length > MAX_FRAME) throw new IOException("companion frame size");
        byte[] bytes = new byte[(int) length]; int offset = 0;
        while (offset < bytes.length) {
            int count = input.read(bytes, offset, bytes.length - offset);
            if (count < 0) throw new EOFException("companion frame truncated");
            if (count == 0) continue;
            offset += count;
        }
        return bytes;
    }
    private static final class Cursor {
        final byte[] data; int at; final int end;
        Cursor(byte[] data) { this(data, 0, data.length); }
        Cursor(byte[] data, int at, int end) { this.data = data; this.at = at; this.end = end; }
        long integer() throws IOException {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (at >= end) throw new EOFException("protobuf varint");
                int b = data[at++] & 255;
                if (i == 9 && (b & 254) != 0) throw new IOException("protobuf varint overflow");
                value |= (long) (b & 127) << (i * 7);
                if ((b & 128) == 0) return value;
            }
            throw new IOException("protobuf varint overflow");
        }
        Cursor nested() throws IOException {
            long size = integer(); if (size < 0 || size > end - at) throw new IOException("protobuf length");
            Cursor nested = new Cursor(data, at, at + (int) size); at += (int) size; return nested;
        }
        byte[] bytes(int maximum) throws IOException {
            Cursor nested = nested(); int size = nested.end - nested.at;
            if (size > maximum) throw new IOException("protobuf field too large");
            byte[] result = new byte[size]; System.arraycopy(data, nested.at, result, 0, size); return result;
        }
        void skip(int wire) throws IOException {
            if (wire == 0) integer();
            else if (wire == 2) nested();
            else if (wire == 1 || wire == 5) {
                int size = wire == 1 ? 8 : 4;
                if (end - at < size) throw new IOException("protobuf fixed field"); at += size;
            } else throw new IOException("protobuf wire type");
        }
    }
    public static Reply parse(byte[] frame) throws IOException {
        if (frame == null || frame.length < 1 || frame.length > MAX_FRAME) throw new IOException("companion frame size");
        Reply result = new Reply(); Cursor envelope = new Cursor(frame);
        while (envelope.at < envelope.end) {
            long tag = envelope.integer(); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (field == 0) throw new IOException("protobuf field zero");
            if (field == 1 && wire == 0) result.version = (int) envelope.integer();
            else if (field == 6 && wire == 2) parseLocationRequest(envelope.nested(), result);
            else if (field == 22 && wire == 2) parseInfo(envelope.nested(), result);
            else if (field == 15 && wire == 2) parseScreen(envelope.nested(), result);
            else envelope.skip(wire);
        }
        if (result.version < 0 || (result.version & 65535) != 6) throw new IOException("XE24 companion major version mismatch");
        return result;
    }
    private static void parseInfo(Cursor info, Reply result) throws IOException {
        while (info.at < info.end) {
            long tag = info.integer(); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (wire == 0 && field == 1) { long value = info.integer(); if (value >= 0 && value <= 100) result.battery = (int) value; }
            else if (wire == 2 && (field == 4 || field == 5)) {
                String value = new String(info.bytes(128), StandardCharsets.UTF_8);
                if (field == 4) result.name = value; else result.software = value;
            } else if (wire == 0 && (field == 7 || field == 8)) {
                long value = info.integer(); if (value > 0 && value <= 4096) {
                    if (field == 7) result.width = (int) value; else result.height = (int) value;
                }
            } else info.skip(wire);
        }
    }
    private static void parseScreen(Cursor screen, Reply result) throws IOException {
        while (screen.at < screen.end) {
            long tag = screen.integer(); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (field == 3 && wire == 2) result.screen = screen.bytes(MAX_SCREEN);
            else if (field == 4 && wire == 0) result.screenOn = screen.integer() != 0;
            else screen.skip(wire);
        }
    }
    private static void parseLocationRequest(Cursor request, Reply result) throws IOException {
        while (request.at < request.end) {
            long tag = request.integer(); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (field == 1 && wire == 0) {
                long type = request.integer(); if (type == 0 || type == 1) result.locationRequestType = (int) type;
            } else if (field == 2 && wire == 2) result.locationProvider = new String(request.bytes(40), StandardCharsets.UTF_8);
            else request.skip(wire);
        }
    }
}
