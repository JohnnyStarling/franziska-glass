package lab.franziska.protocol;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;

import com.franziska.glass.compat.tls15.PinnedTlsTransport;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsServerProtocol;

/** Explicit BC-lightweight TLS seam over an app-owned ordinary Socket. */
public final class SecureTransport implements Closeable {
    private final Socket socket;
    private final TlsClientProtocol clientProtocol;
    private final TlsServerProtocol serverProtocol;
    private final InputStream input;
    private final DataOutputStream output;
    private final Closeable rawCloser;

    private SecureTransport(Socket socket, TlsClientProtocol clientProtocol,
            TlsServerProtocol serverProtocol, InputStream input, DataOutputStream output) {
        this.socket = socket;
        this.clientProtocol = clientProtocol;
        this.serverProtocol = serverProtocol;
        this.input = input;
        this.output = output;
        this.rawCloser = new Closeable() {
            public void close() throws IOException { SecureTransport.this.socket.close(); }
        };
    }

    public static Socket newClientSocket() { return new Socket(); }

    public static ServerSocket listen(String bind, int port) throws IOException {
        return new ServerSocket(port, 1, Tls.ipv4Literal(bind));
    }

    public static SecureTransport connectOwned(Socket socket, String host, int port,
            int connectTimeoutMs, AppIdentityStore.LoadedIdentity identity, byte[] peerPin)
            throws IOException {
        // Framed realtime audio uses small TLS records. Nagle can defer the payload
        // behind the separately written length prefix until the peer's delayed ACK.
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(Tls.ipv4Literal(host), port), connectTimeoutMs);
        TlsClientProtocol protocol = PinnedTlsTransport.connectClient(socket.getInputStream(),
                socket.getOutputStream(), identity.tls, peerPin, new SecureRandom());
        return new SecureTransport(socket, protocol, null, protocol.getInputStream(),
                new DataOutputStream(protocol.getOutputStream()));
    }

    public static SecureTransport acceptOwned(Socket socket,
            AppIdentityStore.LoadedIdentity identity, byte[] peerPin) throws IOException {
        socket.setTcpNoDelay(true);
        TlsServerProtocol protocol = PinnedTlsTransport.acceptServer(socket.getInputStream(),
                socket.getOutputStream(), identity.tls, peerPin, new SecureRandom());
        return new SecureTransport(socket, null, protocol, protocol.getInputStream(),
                new DataOutputStream(protocol.getOutputStream()));
    }

    public Socket socket() { return socket; }
    public InputStream input() { return input; }
    public DataOutputStream output() { return output; }
    /** Raw-only terminal target: never emits close_notify or waits on a TLS lock/peer. */
    public Closeable rawCloser() { return rawCloser; }

    public void close() throws IOException {
        rawCloser.close();
    }
}
