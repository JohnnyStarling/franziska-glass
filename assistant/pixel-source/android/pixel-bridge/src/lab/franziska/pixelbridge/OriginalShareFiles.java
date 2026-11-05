package lab.franziska.pixelbridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App-private, process-death-safe bytes and draft index. No Android or network dependency. */
final class OriginalShareFiles {
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;
    static final int MAX_RECORDS = 128;
    static final long LOW_SPACE_FLOOR = 64L * 1024 * 1024;
    private static final long DRAFT_AGE = 90L * 24 * 3600 * 1000;
    private static final long OFFER_GRACE = 30L * 24 * 3600 * 1000;
    private static final long ORPHAN_GRACE = 2L * 24 * 3600 * 1000;
    private static final int MAGIC = 0x4f534831; // OSH1
    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DIRECTORY = "telegram-originals-v1";

    interface Clock { long now(); }
    interface Space { long usable(File directory); }
    static final class Prepared {
        final File root;
        final String token, name;
        final long size;
        final byte[] hash;
        String committedToken;
        boolean discarded;
        Prepared(File root, String token, String name, long size, byte[] hash) {
            this.root = root; this.token = token; this.name = name; this.size = size;
            this.hash = hash.clone();
        }
    }
    static final class Entry {
        final String token, name;
        final long size, created, lastOffered;
        final byte[] hash;
        final File file;
        Entry(String token, String name, long size, byte[] hash, long created,
              long lastOffered, File file) {
            this.token = token; this.name = name; this.size = size;
            this.hash = hash.clone(); this.created = created;
            this.lastOffered = lastOffered; this.file = file;
        }
    }

    private final File cache, root;
    private final Clock clock;
    private final Space space;
    OriginalShareFiles(File cacheDir, File filesDir) throws IOException {
        this(cacheDir, filesDir, new Clock() { public long now() { return System.currentTimeMillis(); } },
                new Space() { public long usable(File dir) { return dir.getUsableSpace(); } });
    }
    OriginalShareFiles(File cacheDir, File filesDir, Clock clock, Space space) throws IOException {
        if (cacheDir == null || filesDir == null || clock == null || space == null)
            throw new IOException("ORIGINAL_DIRECTORY_INVALID");
        cache = cacheDir.getCanonicalFile();
        File privateDir = filesDir.getCanonicalFile();
        root = new File(filesDir, DIRECTORY).getCanonicalFile();
        if (!root.getParentFile().equals(privateDir)) throw new IOException("ORIGINAL_DIRECTORY_INVALID");
        this.clock = clock; this.space = space;
    }
    Prepared prepare(File source, String name, long size, byte[] hash) throws IOException {
        validName(name);
        if (source == null || hash == null || hash.length != 32 || size < 4 || size > MAX_FILE_BYTES)
            throw new IOException("ORIGINAL_INPUT_INVALID");
        File canonical = source.getCanonicalFile();
        if (!canonical.getParentFile().equals(cache) || !source.isFile()
                || !canonical.getName().equals(source.getName()))
            throw new IOException("ORIGINAL_SOURCE_NOT_OWNED_TEMP");
        if (source.length() != size) throw new IOException("ORIGINAL_SIZE_MISMATCH");
        requireSpace();
        MessageDigest digest = sha256();
        long read = 0;
        FileInputStream input = new FileInputStream(source);
        try {
            byte[] buffer = new byte[32768]; int n;
            while ((n = input.read(buffer)) != -1) {
                if (n > size - read) throw new IOException("ORIGINAL_SIZE_MISMATCH");
                digest.update(buffer, 0, n); read += n;
            }
        } finally { input.close(); }
        if (read != size || source.length() != size) throw new IOException("ORIGINAL_SIZE_MISMATCH");
        if (!MessageDigest.isEqual(digest.digest(), hash)) throw new IOException("ORIGINAL_HASH_MISMATCH");
        synchronized (LOCK) {
            ensureRoot(); prune(); requireSpace();
            String token = randomToken();
            File stage = path(token, ".stage");
            if (stage.exists() || recordPath(token).exists() || blobPath(token).exists())
                throw new IOException("ORIGINAL_TOKEN_COLLISION");
            // Same-volume cache -> private files rename; never copy another multi-GB original.
            if (!source.renameTo(stage)) throw new IOException("ORIGINAL_STAGE_RENAME");
            if (!stage.setLastModified(clock.now()) || !stage.setReadOnly()) {
                stage.delete(); throw new IOException("ORIGINAL_STAGE_READ_ONLY");
            }
            return new Prepared(root, token, name, size, hash);
        }
    }
    String commit(Prepared prepared) throws IOException {
        synchronized (LOCK) {
            owned(prepared);
            if (prepared.discarded) throw new IOException("ORIGINAL_ALREADY_DISCARDED");
            if (prepared.committedToken != null) return prepared.committedToken;
            ensureRoot(); prune(); requireSpace();
            File stage = path(prepared.token, ".stage");
            if (!stage.isFile() || stage.length() != prepared.size)
                throw new IOException("ORIGINAL_STAGING_MISSING");
            List<Entry> entries = records();
            for (Entry existing : entries) {
                if (existing.size == prepared.size && existing.name.equals(prepared.name)
                        && MessageDigest.isEqual(existing.hash, prepared.hash)) {
                    if (!stage.delete()) throw new IOException("ORIGINAL_DUPLICATE_CLEANUP");
                    prepared.committedToken = existing.token;
                    return existing.token;
                }
            }
            long used = 0;
            for (Entry entry : entries) {
                if (entry.size > MAX_TOTAL_BYTES - used) throw new IOException("ORIGINAL_QUOTA_FULL");
                used += entry.size;
            }
            if (entries.size() >= MAX_RECORDS || prepared.size > MAX_TOTAL_BYTES - used)
                throw new IOException("ORIGINAL_QUOTA_FULL");
            File blob = blobPath(prepared.token);
            if (blob.exists() || recordPath(prepared.token).exists()
                    || !stage.renameTo(blob)) throw new IOException("ORIGINAL_COMMIT_RENAME");
            Entry entry = new Entry(prepared.token, prepared.name, prepared.size,
                    prepared.hash, clock.now(), 0, blob);
            writeRecord(entry);
            prepared.committedToken = prepared.token;
            return prepared.token;
        }
    }
    void discard(Prepared prepared) throws IOException {
        synchronized (LOCK) {
            owned(prepared);
            if (prepared.committedToken != null || prepared.discarded) return;
            File stage = path(prepared.token, ".stage");
            File blob = blobPath(prepared.token);
            if (stage.exists() && !stage.delete()) throw new IOException("ORIGINAL_DISCARD_FAILED");
            if (!recordPath(prepared.token).exists() && blob.exists() && !blob.delete())
                throw new IOException("ORIGINAL_DISCARD_FAILED");
            prepared.discarded = true;
        }
    }
    List<Entry> pending() throws IOException {
        synchronized (LOCK) {
            ensureRoot(); prune();
            List<Entry> entries = records();
            Collections.sort(entries, new Comparator<Entry>() {
                public int compare(Entry a, Entry b) { return Long.compare(b.created, a.created); }
            });
            return entries;
        }
    }
    Entry read(String token) throws IOException {
        synchronized (LOCK) {
            ensureRoot(); return readEntry(token);
        }
    }
    void offered(String token) throws IOException {
        synchronized (LOCK) {
            ensureRoot();
            Entry entry = readEntry(token);
            long now = clock.now();
            writeRecord(new Entry(token, entry.name, entry.size, entry.hash, entry.created,
                    Math.max(entry.lastOffered, now), entry.file));
        }
    }
    void delete(String token) throws IOException {
        synchronized(LOCK) {
            Entry entry=readEntry(token);
            if(!entry.file.delete())throw new IOException("ORIGINAL_DELETE_FAILED");
            if(!recordPath(token).delete())throw new IOException("ORIGINAL_DELETE_RECORD_FAILED");
        }
    }
    static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-f]{64}");
    }
    private void owned(Prepared prepared) throws IOException {
        if (prepared == null || !root.equals(prepared.root) || !validToken(prepared.token))
            throw new IOException("ORIGINAL_PREPARED_FOREIGN");
    }
    private void requireSpace() throws IOException {
        if (space.usable(cache) < LOW_SPACE_FLOOR || space.usable(root.getParentFile()) < LOW_SPACE_FLOOR)
            throw new IOException("ORIGINAL_STORAGE_LOW");
    }
    private void ensureRoot() throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("ORIGINAL_DIRECTORY_CREATE");
        if (!root.isDirectory() || !root.getCanonicalFile().equals(root))
            throw new IOException("ORIGINAL_DIRECTORY_INVALID");
    }
    private File path(String token, String suffix) throws IOException {
        if (!validToken(token)) throw new IOException("ORIGINAL_TOKEN_INVALID");
        return new File(root, token + suffix);
    }
    private File recordPath(String token) throws IOException { return path(token, ".record"); }
    private File blobPath(String token) throws IOException { return path(token, ".blob"); }
    private Entry readEntry(String token) throws IOException {
        File record = recordPath(token);
        if (!record.isFile()) throw new IOException("ORIGINAL_RECORD_MISSING");
        DataInputStream input = new DataInputStream(new FileInputStream(record));
        try {
            if (input.readInt() != MAGIC || input.readInt() != 1)
                throw new IOException("ORIGINAL_RECORD_INVALID");
            long size = input.readLong(), created = input.readLong(), offered = input.readLong();
            byte[] hash = new byte[32]; input.readFully(hash);
            int length = input.readUnsignedShort();
            if (length < 1 || length > 120) throw new IOException("ORIGINAL_RECORD_INVALID");
            byte[] nameBytes = new byte[length]; input.readFully(nameBytes);
            if (input.read() != -1 || size < 4 || size > MAX_FILE_BYTES || created <= 0 || offered < 0)
                throw new IOException("ORIGINAL_RECORD_INVALID");
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            validName(name);
            if (!Arrays.equals(name.getBytes(StandardCharsets.UTF_8), nameBytes))
                throw new IOException("ORIGINAL_RECORD_INVALID");
            File blob = blobPath(token);
            if (!blob.isFile() || blob.length() != size || !blob.getCanonicalFile().getParentFile().equals(root))
                throw new IOException("ORIGINAL_BLOB_INVALID");
            return new Entry(token, name, size, hash, created, offered, blob);
        } finally { input.close(); }
    }
    private List<Entry> records() throws IOException {
        File[] all = root.listFiles();
        if (all == null) throw new IOException("ORIGINAL_DIRECTORY_READ");
        List<Entry> entries = new ArrayList<>();
        for (File file : all) {
            String filename = file.getName();
            if (filename.endsWith(".record")) {
                String token = filename.substring(0, filename.length() - 7);
                if (validToken(token)) entries.add(readEntry(token));
            }
        }
        return entries;
    }
    private void writeRecord(Entry entry) throws IOException {
        File temp = File.createTempFile("record-", ".tmp", root);
        boolean published = false;
        try {
            FileOutputStream raw = new FileOutputStream(temp);
            try {
                DataOutputStream output = new DataOutputStream(raw);
                byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
                output.writeInt(MAGIC); output.writeInt(1); output.writeLong(entry.size);
                output.writeLong(entry.created); output.writeLong(entry.lastOffered);
                output.write(entry.hash); output.writeShort(name.length); output.write(name);
                output.flush(); raw.getFD().sync();
            } finally { raw.close(); }
            if (!temp.renameTo(recordPath(entry.token)))
                throw new IOException("ORIGINAL_RECORD_PUBLISH");
            published = true;
        } finally { if (!published) temp.delete(); }
    }
    private void prune() throws IOException {
        File[] all = root.listFiles();
        if (all == null) throw new IOException("ORIGINAL_DIRECTORY_READ");
        Set<String> active = new HashSet<>();
        long now = clock.now();
        for (File file : all) {
            String filename = file.getName();
            if (!filename.endsWith(".record")) continue;
            String token = filename.substring(0, filename.length() - 7);
            if (!validToken(token)) continue;
            Entry entry = readEntry(token);
            long draftElapsed = Math.max(0, now - entry.created);
            long offerElapsed = entry.lastOffered == 0 ? Long.MAX_VALUE :
                    Math.max(0, now - entry.lastOffered);
            if (draftElapsed > DRAFT_AGE && offerElapsed > OFFER_GRACE) {
                if (!file.delete()) throw new IOException("ORIGINAL_EXPIRY_FAILED");
                entry.file.delete(); // orphan sweep retries later if deletion fails
            } else active.add(token);
        }
        for (File file : all) {
            String filename = file.getName();
            String suffix = filename.endsWith(".stage") ? ".stage" :
                    filename.endsWith(".blob") ? ".blob" : null;
            if (suffix == null) continue;
            String token = filename.substring(0, filename.length() - suffix.length());
            if (validToken(token) && !active.contains(token)
                    && now - file.lastModified() > ORPHAN_GRACE) file.delete();
        }
    }
    private static void validName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals(".."))
            throw new IOException("ORIGINAL_NAME_INVALID");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == '/' || ch == '\\' || Character.isISOControl(ch))
                throw new IOException("ORIGINAL_NAME_INVALID");
        }
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        if (utf8.length < 1 || utf8.length > 120 ||
                !name.equals(new String(utf8, StandardCharsets.UTF_8)))
            throw new IOException("ORIGINAL_NAME_INVALID");
    }
    private static MessageDigest sha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException missing) { throw new IOException("SHA_256_UNAVAILABLE", missing); }
    }
    private static String randomToken() {
        byte[] random = new byte[32]; RANDOM.nextBytes(random);
        char[] chars = new char[64];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < random.length; i++) {
            chars[i * 2] = digits[(random[i] >>> 4) & 15];
            chars[i * 2 + 1] = digits[random[i] & 15];
        }
        return new String(chars);
    }
}
