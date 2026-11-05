package lab.franziska.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Independent bulk-media lane; never multiplex photo bytes into Talk/Stop frames. */
public final class PhotoWire {
    public static final int MAX_JPEG = 8 * 1024 * 1024;
    public static final int CHUNK = 48 * 1024;
    public static final int HASH_BYTES = 32;
    public static final byte BEGIN = 1, DATA = 2, COMMIT = 3, ACK = 4;
    private PhotoWire() {}

    public static final class Frame {
        public final byte type;
        public final long photoId, modified;
        public final int totalBytes, offset;
        public final byte[] hash, data;
        private Frame(byte type, long photoId, long modified, int totalBytes, int offset,
                      byte[] hash, byte[] data) {
            this.type = type; this.photoId = photoId; this.modified = modified;
            this.totalBytes = totalBytes; this.offset = offset; this.hash = hash; this.data = data;
        }
    }

    public static byte[] begin(long id, long modified, int bytes, byte[] hash) throws IOException {
        if (id <= 0 || modified < 0 || bytes < 4 || bytes > MAX_JPEG) throw invalid();
        checkHash(hash);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(53);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(BEGIN); out.writeLong(id); out.writeLong(modified); out.writeInt(bytes);
        out.write(hash); return buffer.toByteArray();
    }

    public static byte[] data(long id, int offset, byte[] bytes, int count) throws IOException {
        if (id <= 0 || offset < 0 || offset >= MAX_JPEG || bytes == null || count < 1
                || count > CHUNK || count > bytes.length || (long)offset + count > MAX_JPEG)
            throw invalid();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(13 + count);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(DATA); out.writeLong(id); out.writeInt(offset); out.write(bytes, 0, count);
        return buffer.toByteArray();
    }

    public static byte[] commit(long id, byte[] hash) throws IOException { return terminal(COMMIT, id, hash); }
    public static byte[] ack(long id, byte[] hash) throws IOException { return terminal(ACK, id, hash); }
    private static byte[] terminal(byte type, long id, byte[] hash) throws IOException {
        if (id <= 0) throw invalid(); checkHash(hash);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(41);
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(type); out.writeLong(id); out.write(hash); return buffer.toByteArray();
    }

    public static Frame parse(byte[] raw) throws IOException {
        if (raw == null || raw.length < 9 || raw.length > CHUNK + 13) throw invalid();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        byte type = in.readByte(); long id = in.readLong();
        if (id <= 0) throw invalid();
        long modified = 0; int total = 0, offset = 0;
        byte[] hash = null, data = null;
        if (type == BEGIN) {
            if (raw.length != 53) throw invalid();
            modified = in.readLong(); total = in.readInt();
            if (modified < 0 || total < 4 || total > MAX_JPEG) throw invalid();
            hash = new byte[HASH_BYTES]; in.readFully(hash);
        } else if (type == DATA) {
            if (raw.length < 14) throw invalid();
            offset = in.readInt(); int count = raw.length - 13;
            if (offset < 0 || count > CHUNK || (long)offset + count > MAX_JPEG) throw invalid();
            data = new byte[count]; in.readFully(data);
        } else if (type == COMMIT || type == ACK) {
            if (raw.length != 41) throw invalid();
            hash = new byte[HASH_BYTES]; in.readFully(hash);
        } else throw invalid();
        if (in.available() != 0) throw invalid();
        return new Frame(type, id, modified, total, offset, hash, data);
    }

    public static boolean sameHash(byte[] a, byte[] b) {
        return a != null && b != null && a.length == HASH_BYTES && b.length == HASH_BYTES
                && java.security.MessageDigest.isEqual(a, b);
    }

    private static void checkHash(byte[] value) throws IOException {
        if (value == null || value.length != HASH_BYTES) throw invalid();
    }
    private static IOException invalid() { return new IOException("PHOTO_FRAME_INVALID"); }
}
