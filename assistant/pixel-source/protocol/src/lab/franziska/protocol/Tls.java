package lab.franziska.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

/** Shared framing/watchdogs plus a diagnostic-only native TLS capability probe. */
public final class Tls {
    private Tls() {}

    /** Native-provider diagnostic/test context. Production app paths do not call this. */
    public static SSLContext context(File identity, char[] identityPassword,
                                     File peers, char[] peersPassword) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        FileInputStream identityInput = new FileInputStream(identity);
        try { keys.load(identityInput, identityPassword); } finally { identityInput.close(); }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        FileInputStream peerInput = new FileInputStream(peers);
        try { trust.load(peerInput, peersPassword); } finally { peerInput.close(); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, identityPassword);
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());
        return context;
    }

    public static void harden(SSLSocket socket) throws IOException {
        socket.setEnabledProtocols(requireStrong(socket.getSupportedProtocols()));
    }

    public static void harden(SSLServerSocket socket) throws IOException {
        socket.setEnabledProtocols(requireStrong(socket.getSupportedProtocols()));
        socket.setNeedClientAuth(true);
    }

    /** Runtime provider probe; false means the secure link must remain disabled. */
    public static boolean platformSupportsStrongTls() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, null, new SecureRandom());
            SSLSocket probe = (SSLSocket) context.getSocketFactory().createSocket();
            try {
                return strong(probe.getSupportedProtocols()).length > 0;
            } finally {
                probe.close();
            }
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static String[] requireStrong(String[] supported) throws IOException {
        String[] protocols = strong(supported);
        if (protocols.length == 0) {
            throw new IOException("SECURE_LINK_BLOCKED: TLS 1.2+ unavailable");
        }
        return protocols;
    }

    private static String[] strong(String[] supported) {
        List<String> enabled = new ArrayList<String>();
        for (String protocol : supported) {
            if ("TLSv1.2".equals(protocol) || "TLSv1.3".equals(protocol)) {
                enabled.add(protocol);
            }
        }
        return enabled.toArray(new String[enabled.size()]);
    }

    public static void write(DataOutputStream output, byte[] frame) throws IOException {
        if (frame.length > Protocol.MAX_WIRE_BYTES) {
            throw new IOException("frame too large");
        }
        writeFrame(output, frame);
    }

    /** One bounded frame copy avoids separate TLS records for the length and body. */
    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        byte[] packet = new byte[frame.length + 4];
        packet[0] = (byte) (frame.length >>> 24);
        packet[1] = (byte) (frame.length >>> 16);
        packet[2] = (byte) (frame.length >>> 8);
        packet[3] = (byte) frame.length;
        System.arraycopy(frame, 0, packet, 4, frame.length);
        output.write(packet);
        output.flush();
    }

    /** A complete frame was not written inside its absolute monotonic budget. */
    public static final class WriteDeadlineException extends SocketTimeoutException {
        public WriteDeadlineException() { super("absolute frame write deadline exceeded"); }
    }

    /** API15 Socket is not Closeable; capture this exact raw socket, not TLS state. */
    private static Closeable rawCloser(final Socket socket) {
        if (socket == null) throw new IllegalArgumentException("target");
        return new Closeable() {
            @Override public void close() throws IOException { socket.close(); }
        };
    }

    /** Socket overload prevents the API19-only Socket-to-Closeable conversion. */
    public static void write(DataOutputStream output, byte[] frame, Socket rawTarget,
                             long timeoutMillis, String watchdogName) throws IOException {
        write(output, frame, rawCloser(rawTarget), timeoutMillis, watchdogName);
    }

    /** Writes one frame, closing the exact owned raw transport at the absolute deadline. */
    public static void write(DataOutputStream output, byte[] frame, Closeable rawTarget,
                             long timeoutMillis, String watchdogName) throws IOException {
        if (rawTarget == null) throw new IllegalArgumentException("rawTarget");
        if (frame.length > Protocol.MAX_WIRE_BYTES) throw new IOException("frame too large");
        Deadline deadline = new Deadline(timeoutMillis);
        DeadlineWatchdog watchdog = closeAtDeadline(rawTarget, deadline, watchdogName);
        IOException failure = null;
        try {
            // Packing is inside the same absolute deadline as writing and flushing.
            writeFrame(output, frame);
        } catch (IOException writeFailure) {
            failure = writeFailure;
        } finally {
            watchdog.cancel();
        }
        // Expiry is failure even when raw close caused a blocked write to return normally/late.
        if (deadline.expired()) throw new WriteDeadlineException();
        if (failure != null) throw failure;
    }

    /** No frame byte arrived during the socket's ordinary idle timeout. */
    public static final class IdleTimeoutException extends SocketTimeoutException {
        public IdleTimeoutException() { super("idle between frames"); }
    }

    /** A frame started but did not complete inside its absolute budget. */
    public static final class FrameDeadlineException extends SocketTimeoutException {
        public FrameDeadlineException() { super("absolute frame deadline exceeded"); }
    }

    /** Monotonic absolute deadline shared by framing and socket watchdogs. */
    public static final class Deadline {
        private final long expiresAtNanos;

        public Deadline(long timeoutMillis) {
            if (timeoutMillis <= 0L || timeoutMillis > Long.MAX_VALUE / 1000000L) {
                throw new IllegalArgumentException("timeout");
            }
            // Signed subtraction below is wrap-safe for practical (< 292 year) budgets.
            expiresAtNanos = System.nanoTime() + timeoutMillis * 1000000L;
        }

        public boolean expired() { return System.nanoTime() - expiresAtNanos >= 0L; }

        public long remainingMillis() {
            long remaining = expiresAtNanos - System.nanoTime();
            if (remaining <= 0L) return 0L;
            return Math.max(1L, (remaining + 999999L) / 1000000L);
        }
    }

    /** Cancellable absolute watchdog. Closing the socket interrupts connect/handshake. */
    public static final class DeadlineWatchdog {
        private volatile boolean cancelled;
        private final Thread thread;

        private DeadlineWatchdog(final Closeable target, final Deadline deadline, String name) {
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    while (!cancelled && !deadline.expired()) {
                        try {
                            Thread.sleep(deadline.remainingMillis());
                        } catch (InterruptedException interrupted) {
                            // Recheck cancellation and the absolute deadline.
                        }
                    }
                    if (!cancelled) {
                        try { target.close(); } catch (IOException ignored) {}
                    }
                }
            }, name);
            thread.setDaemon(true);
            thread.start();
        }

        public void cancel() {
            cancelled = true;
            thread.interrupt();
        }
    }

    /** Keep raw ownership and the existing cancellation/deadline algorithm unchanged. */
    public static DeadlineWatchdog closeAtDeadline(Socket target,
                                                    long timeoutMillis,
                                                    String threadName) {
        return closeAtDeadline(rawCloser(target), timeoutMillis, threadName);
    }

    public static DeadlineWatchdog closeAtDeadline(Closeable target,
                                                    long timeoutMillis,
                                                    String threadName) {
        if (target == null) throw new IllegalArgumentException("target");
        return new DeadlineWatchdog(target, new Deadline(timeoutMillis), threadName);
    }

    private static DeadlineWatchdog closeAtDeadline(Closeable target, Deadline deadline,
                                                     String threadName) {
        return new DeadlineWatchdog(target, deadline, threadName);
    }

    /**
     * Incremental frame reader. Ordinary idle timeouts escape only before a frame
     * starts. Once any header/body byte arrives, timeout retries retain progress and
     * the absolute deadline makes a partial or authenticated slow-drip frame fatal.
     */
    public static final class FrameReader {
        private final InputStream input;
        private final long frameTimeoutMillis;
        private final Closeable operationTarget;
        private final long operationTimeoutMillis;
        private final byte[] header = new byte[4];
        private int headerBytes;
        private byte[] body;
        private int bodyBytes;
        private Deadline deadline;

        public FrameReader(InputStream input, long frameTimeoutMillis) {
            this(input, frameTimeoutMillis, null, 0L);
        }

        public FrameReader(InputStream input, long frameTimeoutMillis,
                Closeable operationTarget, long operationTimeoutMillis) {
            if (input == null) throw new IllegalArgumentException("input");
            if (frameTimeoutMillis <= 0L) throw new IllegalArgumentException("timeout");
            if ((operationTarget == null) != (operationTimeoutMillis == 0L)
                    || operationTimeoutMillis < 0L) throw new IllegalArgumentException("operation watchdog");
            this.input = input;
            this.frameTimeoutMillis = frameTimeoutMillis;
            this.operationTarget = operationTarget;
            this.operationTimeoutMillis = operationTimeoutMillis;
        }

        public byte[] read() throws IOException {
            while (true) {
                if (deadline != null && deadline.expired()) {
                    throw new FrameDeadlineException();
                }
                try {
                    if (headerBytes < 4) {
                        int count = boundedRead(header, headerBytes, 4 - headerBytes);
                        if (count < 0) throw new EOFException(
                                headerBytes == 0 ? "end of stream" : "partial frame header");
                        if (count == 0) continue;
                        if (deadline == null) deadline = new Deadline(frameTimeoutMillis);
                        headerBytes += count;
                        if (headerBytes < 4) continue;
                        int length = ((header[0] & 255) << 24)
                                | ((header[1] & 255) << 16)
                                | ((header[2] & 255) << 8)
                                | (header[3] & 255);
                        if (length <= 0 || length > Protocol.MAX_WIRE_BYTES) {
                            throw new IOException("invalid frame size");
                        }
                        body = new byte[length];
                    }
                    int count = boundedRead(body, bodyBytes, body.length - bodyBytes);
                    if (count < 0) throw new EOFException("partial frame body");
                    if (count == 0) continue;
                    bodyBytes += count;
                    if (bodyBytes == body.length) {
                        if (deadline.expired()) throw new FrameDeadlineException();
                        byte[] complete = body;
                        reset();
                        return complete;
                    }
                } catch (SocketTimeoutException timeout) {
                    if (deadline == null) throw new IdleTimeoutException();
                    if (deadline.expired()) throw new FrameDeadlineException();
                    // Preserve header/body offsets and continue within one absolute frame budget.
                }
            }
        }

        private int boundedRead(byte[] target, int offset, int length) throws IOException {
            if (operationTarget == null) return input.read(target, offset, length);
            DeadlineWatchdog watchdog = closeAtDeadline(operationTarget,
                    operationTimeoutMillis, "tls-read-operation-deadline");
            try { return input.read(target, offset, length); }
            finally { watchdog.cancel(); }
        }

        private void reset() {
            headerBytes = 0;
            body = null;
            bodyBytes = 0;
            deadline = null;
        }
    }

    public static FrameReader frameReader(SSLSocket socket,
                                          long frameTimeoutMillis) throws IOException {
        return new FrameReader(new BufferedInputStream(socket.getInputStream()),
                frameTimeoutMillis);
    }

    public static FrameReader frameReader(InputStream input, Closeable operationTarget,
            long frameTimeoutMillis, long operationTimeoutMillis) {
        return new FrameReader(input, frameTimeoutMillis, operationTarget, operationTimeoutMillis);
    }

    /** Strict dotted-decimal literal parser: endpoint configuration never invokes DNS. */
    public static InetAddress ipv4Literal(String value) throws IOException {
        if (value == null) throw new IOException("missing IPv4 literal");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IOException("endpoint must be an IPv4 literal");
        byte[] address = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.length() == 0 || part.length() > 3) {
                throw new IOException("invalid IPv4 literal");
            }
            int number = 0;
            for (int digit = 0; digit < part.length(); digit++) {
                char character = part.charAt(digit);
                if (character < '0' || character > '9') {
                    throw new IOException("invalid IPv4 literal");
                }
                number = number * 10 + character - '0';
            }
            if (number > 255) throw new IOException("invalid IPv4 literal");
            address[index] = (byte) number;
        }
        return InetAddress.getByAddress(address);
    }

    public static DataOutputStream output(SSLSocket socket) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }
}
