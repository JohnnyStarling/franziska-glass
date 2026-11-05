package com.franziska.glass.compat.tls15;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Vector;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.CipherSuite;
import org.bouncycastle.tls.ClientCertificateType;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.DefaultTlsServer;
import org.bouncycastle.tls.HashAlgorithm;
import org.bouncycastle.tls.ProtocolVersion;
import org.bouncycastle.tls.SignatureAlgorithm;
import org.bouncycastle.tls.SignatureAndHashAlgorithm;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentialedSigner;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.TlsServerProtocol;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.util.Arrays;

/**
 * Minimal TLS 1.2-only, exact-leaf-pin mTLS seam using BC's lightweight API.
 * The entire output containing this class and BC MUST be relocated as described
 * in relocation.rules before inclusion in an Android application.
 */
public final class PinnedTlsTransport {
    private static final ProtocolVersion[] TLS12_ONLY = { ProtocolVersion.TLSv12 };
    private static final int[] CIPHERS = { CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 };
    private static final SignatureAndHashAlgorithm RSA_SHA256 =
            new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa);

    private PinnedTlsTransport() {}

    public static TlsClientProtocol connectClient(InputStream in, OutputStream out,
            Identity clientIdentity, byte[] expectedServerLeafSha256, SecureRandom random)
            throws IOException {
        requirePin(expectedServerLeafSha256);
        final BcTlsCrypto crypto = new BcTlsCrypto(random);
        TlsClientProtocol protocol = new TlsClientProtocol(in, out);
        protocol.connect(new PinnedClient(crypto, clientIdentity, expectedServerLeafSha256));
        return protocol;
    }

    public static TlsServerProtocol acceptServer(InputStream in, OutputStream out,
            Identity serverIdentity, byte[] expectedClientLeafSha256, SecureRandom random)
            throws IOException {
        requirePin(expectedClientLeafSha256);
        final BcTlsCrypto crypto = new BcTlsCrypto(random);
        TlsServerProtocol protocol = new TlsServerProtocol(in, out);
        protocol.accept(new PinnedServer(crypto, serverIdentity, expectedClientLeafSha256));
        return protocol;
    }

    public static byte[] leafSha256(Certificate chain) throws IOException {
        if (chain == null || chain.isEmpty()) {
            throw new TlsFatalAlert(AlertDescription.bad_certificate);
        }
        byte[] encoded = chain.getCertificateAt(0).getEncoded();
        SHA256Digest digest = new SHA256Digest();
        digest.update(encoded, 0, encoded.length);
        byte[] pin = new byte[digest.getDigestSize()];
        digest.doFinal(pin, 0);
        return pin;
    }

    private static void requireExactLeaf(Certificate chain, byte[] expected) throws IOException {
        if (!Arrays.constantTimeAreEqual(expected, leafSha256(chain))) {
            throw new TlsFatalAlert(AlertDescription.bad_certificate);
        }
    }

    private static void requirePin(byte[] pin) {
        if (pin == null || pin.length != 32) {
            throw new IllegalArgumentException("SHA-256 certificate pin must be exactly 32 bytes");
        }
    }

    private static TlsCredentialedSigner signer(org.bouncycastle.tls.TlsContext context,
            BcTlsCrypto crypto, Identity identity) {
        return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto,
                identity.privateKey, identity.chain, RSA_SHA256);
    }

    public static final class Identity {
        final AsymmetricKeyParameter privateKey;
        final Certificate chain;

        public Identity(AsymmetricKeyParameter privateKey, Certificate chain) {
            if (privateKey == null || !privateKey.isPrivate() || chain == null || chain.isEmpty()) {
                throw new IllegalArgumentException("private key and non-empty certificate chain required");
            }
            this.privateKey = privateKey;
            this.chain = chain;
        }
    }

    private static final class PinnedClient extends DefaultTlsClient {
        private final BcTlsCrypto crypto;
        private final Identity identity;
        private final byte[] expectedServer;

        PinnedClient(BcTlsCrypto crypto, Identity identity, byte[] expectedServer) {
            super(crypto);
            this.crypto = crypto;
            this.identity = identity;
            this.expectedServer = Arrays.clone(expectedServer);
        }

        public ProtocolVersion[] getProtocolVersions() { return TLS12_ONLY; }
        protected int[] getSupportedCipherSuites() { return CIPHERS; }

        public TlsAuthentication getAuthentication() {
            return new TlsAuthentication() {
                public void notifyServerCertificate(TlsServerCertificate certificate) throws IOException {
                    requireExactLeaf(certificate.getCertificate(), expectedServer);
                }

                public TlsCredentials getClientCredentials(CertificateRequest request) {
                    return signer(context, crypto, identity);
                }
            };
        }
    }

    private static final class PinnedServer extends DefaultTlsServer {
        private final BcTlsCrypto crypto;
        private final Identity identity;
        private final byte[] expectedClient;

        PinnedServer(BcTlsCrypto crypto, Identity identity, byte[] expectedClient) {
            super(crypto);
            this.crypto = crypto;
            this.identity = identity;
            this.expectedClient = Arrays.clone(expectedClient);
        }

        public ProtocolVersion[] getProtocolVersions() { return TLS12_ONLY; }
        protected int[] getSupportedCipherSuites() { return CIPHERS; }

        public TlsCredentials getCredentials() {
            return signer(context, crypto, identity);
        }

        public CertificateRequest getCertificateRequest() {
            Vector algorithms = new Vector();
            algorithms.addElement(RSA_SHA256);
            return new CertificateRequest(new short[] { ClientCertificateType.rsa_sign }, algorithms, null);
        }

        public void notifyClientCertificate(Certificate clientCertificate) throws IOException {
            requireExactLeaf(clientCertificate, expectedClient);
        }
    }
}

