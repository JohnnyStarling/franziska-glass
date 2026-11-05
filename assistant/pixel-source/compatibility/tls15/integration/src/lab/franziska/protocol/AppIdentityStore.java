package lab.franziska.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import com.franziska.glass.compat.tls15.PinnedTlsTransport;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder;
import org.bouncycastle.tls.Certificate;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

/** App-private, non-rotating RSA identity and public-only trust configuration. */
public final class AppIdentityStore {
    public static final String PRIVATE_KEY = "identity-private.der";
    public static final String CERTIFICATE = "identity-cert.der";
    public static final String PUBLIC_IDENTITY = "identity-public.json";
    public static final String TRUST = "trust.json";
    private static final int MAX_KEY_BYTES = 16384;
    private static final int MAX_CERT_BYTES = 16384;
    private static final int MAX_PUBLIC_BYTES = 65536;
    private static final int MAX_TRUST_BYTES = 4096;
    private static final long VALIDITY_MS = 10L * 365L * 24L * 60L * 60L * 1000L;

    public enum IdentityState { ABSENT, PARTIAL_OR_INVALID, READY }

    public interface Cancellation { boolean cancelled(); }

    public interface StageListener { void stage(String name); }

    public interface PrivatePermissions {
        void enforceDirectory(File directory) throws IOException;
        void enforceEmptyFile(File file) throws IOException;
    }

    private static final PrivatePermissions OWNER_ONLY = new PrivatePermissions() {
        public void enforceDirectory(File directory) throws IOException { enforcePrivateDirectory(directory); }
        public void enforceEmptyFile(File file) throws IOException { enforcePrivateFile(file); }
    };

    private static final Map<String, Coordination> COORDINATIONS =
            new HashMap<String, Coordination>();

    private static final class Coordination { long generation; }

    public static final class LifecycleToken {
        private final Coordination coordination;
        private final long generation;
        private LifecycleToken(Coordination coordination, long generation) {
            this.coordination = coordination;
            this.generation = generation;
        }
    }

    public static final class LoadedIdentity {
        public final PinnedTlsTransport.Identity tls;
        public final byte[] leafPin;
        private LoadedIdentity(PinnedTlsTransport.Identity tls, byte[] leafPin) {
            this.tls = tls;
            this.leafPin = leafPin;
        }
    }

    public static final class TrustConfig {
        public final byte[] peerPin;
        public final String address;
        public final int port;
        public final boolean server;
        private TrustConfig(byte[] peerPin, String address, int port, boolean server) {
            this.peerPin = peerPin;
            this.address = address;
            this.port = port;
            this.server = server;
        }
    }

    private AppIdentityStore() {}

    public static LifecycleToken beginIdentityCreation(File directory) {
        Coordination coordination = coordination(directory);
        synchronized (coordination) {
            return new LifecycleToken(coordination, ++coordination.generation);
        }
    }

    public static boolean isCurrent(LifecycleToken token) {
        synchronized (token.coordination) {
            return token.generation == token.coordination.generation;
        }
    }

    public static boolean runIfCurrent(LifecycleToken token, Runnable action) {
        synchronized (token.coordination) {
            if (token.generation != token.coordination.generation) return false;
            action.run();
            return true;
        }
    }

    /** Invalidates a worker and removes only this store's fixed staging names. */
    public static void cancelIdentityCreation(LifecycleToken token) {
        synchronized (token.coordination) {
            if (token.generation == token.coordination.generation) {
                token.coordination.generation++;
                deleteTemps(tokenDirectory(token));
            }
        }
    }

    public static IdentityState identityState(File directory, String role, String packageName) {
        File key = new File(directory, PRIVATE_KEY);
        File cert = new File(directory, CERTIFICATE);
        File pub = new File(directory, PUBLIC_IDENTITY);
        if (!key.exists() && !cert.exists() && !pub.exists()) return IdentityState.ABSENT;
        if (!key.isFile() || !cert.isFile() || !pub.isFile()) return IdentityState.PARTIAL_OR_INVALID;
        try {
            LoadedIdentity loaded = loadIdentity(directory);
            String expected = publicJson(role, packageName, readBounded(cert, MAX_CERT_BYTES), loaded.leafPin);
            return expected.equals(new String(readBounded(pub, MAX_PUBLIC_BYTES), "US-ASCII"))
                    ? IdentityState.READY : IdentityState.PARTIAL_OR_INVALID;
        } catch (Exception invalid) {
            return IdentityState.PARTIAL_OR_INVALID;
        }
    }

    /** Generates once. Existing or partial identity state is never overwritten or rotated. */
    public static void createIdentity(File directory, String role, String packageName,
            Cancellation cancellation, StageListener listener) throws Exception {
        createIdentity(directory, role, packageName, beginIdentityCreation(directory),
                cancellation, listener, OWNER_ONLY);
    }

    public static void createIdentity(File directory, String role, String packageName,
            LifecycleToken token, Cancellation cancellation, StageListener listener) throws Exception {
        createIdentity(directory, role, packageName, token, cancellation, listener, OWNER_ONLY);
    }

    public static void createIdentityForTest(File directory, String role, String packageName,
            LifecycleToken token, Cancellation cancellation, StageListener listener,
            PrivatePermissions permissions) throws Exception {
        createIdentity(directory, role, packageName, token, cancellation, listener, permissions);
    }

    private static void createIdentity(File directory, String role, String packageName,
            LifecycleToken token, Cancellation cancellation, StageListener listener,
            PrivatePermissions permissions) throws Exception {
        if (!safeToken(role) || !safePackage(packageName)) throw new IOException("invalid identity metadata");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("private directory unavailable");
        permissions.enforceDirectory(directory);
        Coordination coordination = coordination(directory);
        if (token.coordination != coordination) throw new IOException("identity token directory mismatch");
        File keyTmp = new File(directory, ".identity-private.der.tmp");
        File certTmp = new File(directory, ".identity-cert.der.tmp");
        File pubTmp = new File(directory, ".identity-public.json.tmp");
        synchronized (coordination) {
            checkToken(token, cancellation);
            if (identityState(directory, role, packageName) != IdentityState.ABSENT) {
                throw new IOException("identity already exists or is partial; explicit revoke required");
            }
            deleteTemps(directory);
        }
        try {
            stage(token, cancellation, listener, "GENERATING_RSA_2048");
            SecureRandom random = new SecureRandom();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, random);
            KeyPair pair = generator.generateKeyPair();
            stage(token, cancellation, listener, "CREATING_X509");
            AsymmetricKeyParameter privateKey = PrivateKeyFactory.createKey(pair.getPrivate().getEncoded());
            X500Name name = new X500Name("CN=" + role + ",O=Franziska Glass Lab Foundation");
            Date before = new Date(System.currentTimeMillis() - 24L * 60L * 60L * 1000L);
            Date after = new Date(System.currentTimeMillis() + VALIDITY_MS);
            AlgorithmIdentifier signature = new AlgorithmIdentifier(
                    PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE);
            AlgorithmIdentifier digest = new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256);
            ContentSigner signer = new BcRSAContentSignerBuilder(signature, digest).build(privateKey);
            X509CertificateHolder holder = new X509v3CertificateBuilder(name,
                    new BigInteger(160, random), before, after, name,
                    SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded())).build(signer);
            byte[] keyDer = pair.getPrivate().getEncoded();
            byte[] certDer = holder.getEncoded();
            Certificate chain = chain(certDer, random);
            byte[] pin = PinnedTlsTransport.leafSha256(chain);
            stage(token, cancellation, listener, "WRITING_PRIVATE_ATOMIC");
            synchronized (coordination) {
                checkToken(token, cancellation);
                writeSync(keyTmp, keyDer, permissions);
            }
            stage(token, cancellation, listener, "PRIVATE_TEMP_WRITTEN");
            synchronized (coordination) {
                checkToken(token, cancellation);
                writeSync(certTmp, certDer, permissions);
            }
            synchronized (coordination) {
                checkToken(token, cancellation);
                writeSync(pubTmp, publicJson(role, packageName, certDer, pin).getBytes("US-ASCII"),
                        permissions);
            }
            stage(token, cancellation, listener, "COMMITTING_IDENTITY");
            synchronized (coordination) {
                checkToken(token, cancellation);
                renameNew(keyTmp, new File(directory, PRIVATE_KEY));
                renameNew(certTmp, new File(directory, CERTIFICATE));
                renameNew(pubTmp, new File(directory, PUBLIC_IDENTITY));
                if (identityState(directory, role, packageName) != IdentityState.READY) {
                    throw new IOException("committed identity validation failed");
                }
            }
            stage(token, cancellation, listener, "IDENTITY_READY");
        } finally {
            synchronized (coordination) {
                // A stale worker must never delete a newer operation's staging files.
                if (token.generation == coordination.generation) deleteTemps(directory);
            }
        }
    }

    public static LoadedIdentity loadIdentity(File directory) throws Exception {
        byte[] keyDer = readBounded(new File(directory, PRIVATE_KEY), MAX_KEY_BYTES);
        byte[] certDer = readBounded(new File(directory, CERTIFICATE), MAX_CERT_BYTES);
        AsymmetricKeyParameter privateKey = PrivateKeyFactory.createKey(keyDer);
        X509CertificateHolder holder = new X509CertificateHolder(certDer);
        AsymmetricKeyParameter publicKey = PublicKeyFactory.createKey(holder.getSubjectPublicKeyInfo());
        if (!(privateKey instanceof RSAKeyParameters) || !(publicKey instanceof RSAKeyParameters)
                || !((RSAKeyParameters) privateKey).getModulus().equals(
                        ((RSAKeyParameters) publicKey).getModulus())) {
            throw new IOException("private key and certificate do not match");
        }
        Certificate chain = chain(certDer, new SecureRandom());
        return new LoadedIdentity(new PinnedTlsTransport.Identity(privateKey, chain),
                PinnedTlsTransport.leafSha256(chain));
    }

    public static TrustConfig loadTrust(File directory, boolean server) throws Exception {
        String json = new String(readBounded(new File(directory, TRUST), MAX_TRUST_BYTES), "US-ASCII");
        String pinHex = field(json, "peer_leaf_sha256");
        String address = field(json, server ? "bind" : "endpoint_ipv4");
        int port = parsePort(field(json, "port"));
        byte[] pin = fromHex(pinHex);
        Tls.ipv4Literal(address);
        String exact = trustJson(pinHex, address, port, server);
        if (!exact.equals(json)) throw new IOException("trust configuration is not canonical");
        return new TrustConfig(pin, address, port, server);
    }

    public static String trustJson(String pinHex, String address, int port, boolean server) throws IOException {
        if (fromHex(pinHex).length != 32) throw new IOException("invalid exact leaf pin");
        Tls.ipv4Literal(address);
        if (port < 1 || port > 65535) throw new IOException("invalid port");
        return "{\"schema\":1,\"peer_leaf_sha256\":\"" + pinHex.toLowerCase()
                + "\",\"" + (server ? "bind" : "endpoint_ipv4") + "\":\"" + address
                + "\",\"port\":" + port + "}\n";
    }

    public static boolean revokeOwn(File directory) {
        Coordination coordination = coordination(directory);
        synchronized (coordination) {
            coordination.generation++;
            String[] names = { PRIVATE_KEY, CERTIFICATE, PUBLIC_IDENTITY, TRUST,
                    ".identity-private.der.tmp", ".identity-cert.der.tmp",
                    ".identity-public.json.tmp" };
            boolean okay = true;
            for (int i = 0; i < names.length; i++) {
                File file = new File(directory, names[i]);
                if (file.exists() && !file.delete()) okay = false;
            }
            return okay;
        }
    }

    private static Certificate chain(byte[] certDer, SecureRandom random) throws IOException {
        BcTlsCrypto crypto = new BcTlsCrypto(random);
        return new Certificate(new TlsCertificate[] { crypto.createCertificate(certDer) });
    }

    private static String publicJson(String role, String packageName, byte[] cert, byte[] pin) {
        return "{\"schema\":1,\"role\":\"" + role + "\",\"package\":\"" + packageName
                + "\",\"leaf_sha256\":\"" + hex(pin) + "\",\"certificate_der_hex\":\""
                + hex(cert) + "\"}\n";
    }

    private static String field(String json, String name) throws IOException {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("missing trust field");
        start += marker.length();
        if (start < json.length() && json.charAt(start) == '\"') {
            int end = json.indexOf('\"', ++start);
            if (end < 0) throw new IOException("invalid trust field");
            return json.substring(start, end);
        }
        int end = start;
        while (end < json.length() && json.charAt(end) >= '0' && json.charAt(end) <= '9') end++;
        return json.substring(start, end);
    }

    private static int parsePort(String value) throws IOException {
        try { int port = Integer.parseInt(value); if (port > 0 && port <= 65535) return port; }
        catch (RuntimeException ignored) {}
        throw new IOException("invalid port");
    }

    private static byte[] readBounded(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > limit) throw new IOException("invalid private file");
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length());
            byte[] buffer = new byte[2048];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > limit) throw new IOException("private file exceeds bound");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static void writeSync(File file, byte[] bytes, PrivatePermissions permissions)
            throws IOException {
        if (!file.createNewFile()) throw new IOException("exclusive staging creation failed");
        permissions.enforceEmptyFile(file);
        FileOutputStream output = new FileOutputStream(file, false);
        try { output.write(bytes); output.flush(); output.getFD().sync(); }
        finally { output.close(); }
    }

    private static void renameNew(File from, File to) throws IOException {
        if (to.exists() || !from.renameTo(to)) throw new IOException("atomic identity commit failed");
    }

    private static void enforcePrivateDirectory(File directory) throws IOException {
        if (!directory.setReadable(false, false) || !directory.setWritable(false, false)
                || !directory.setExecutable(false, false) || !directory.setReadable(true, true)
                || !directory.setWritable(true, true) || !directory.setExecutable(true, true)) {
            throw new IOException("cannot enforce owner-only directory mode");
        }
    }
    private static void enforcePrivateFile(File file) throws IOException {
        if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setExecutable(false, false) || !file.setReadable(true, true)
                || !file.setWritable(true, true)) {
            throw new IOException("cannot enforce owner-only file mode");
        }
    }
    private static void deleteTemps(File directory) {
        new File(directory, ".identity-private.der.tmp").delete();
        new File(directory, ".identity-cert.der.tmp").delete();
        new File(directory, ".identity-public.json.tmp").delete();
    }
    private static void stage(LifecycleToken token, Cancellation cancellation,
            StageListener listener, String name) throws InterruptedException {
        checkToken(token, cancellation);
        listener.stage(name);
        checkToken(token, cancellation);
    }
    private static void checkToken(LifecycleToken token, Cancellation cancellation)
            throws InterruptedException {
        if (!isCurrent(token)) throw new InterruptedException("cancelled");
        checkCancelled(cancellation);
    }
    private static Coordination coordination(File directory) {
        String key;
        try { key = directory.getCanonicalPath(); }
        catch (IOException failure) { key = directory.getAbsolutePath(); }
        synchronized (COORDINATIONS) {
            Coordination value = COORDINATIONS.get(key);
            if (value == null) { value = new Coordination(); COORDINATIONS.put(key, value); }
            return value;
        }
    }
    private static File tokenDirectory(LifecycleToken token) {
        synchronized (COORDINATIONS) {
            for (Map.Entry<String, Coordination> entry : COORDINATIONS.entrySet()) {
                if (entry.getValue() == token.coordination) return new File(entry.getKey());
            }
        }
        throw new IllegalStateException("unknown identity token");
    }
    private static void checkCancelled(Cancellation c) throws InterruptedException {
        if (c.cancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("cancelled");
    }
    private static boolean safeToken(String value) { return value != null && value.matches("[a-z0-9-]{1,32}"); }
    private static boolean safePackage(String value) { return value != null && value.matches("[a-z0-9.]{3,96}"); }
    private static String hex(byte[] value) {
        char[] chars = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) { chars[i * 2] = alphabet[(value[i] >>> 4) & 15]; chars[i * 2 + 1] = alphabet[value[i] & 15]; }
        return new String(chars);
    }
    private static byte[] fromHex(String value) throws IOException {
        if (value == null || (value.length() & 1) != 0 || value.length() > MAX_PUBLIC_BYTES * 2) throw new IOException("invalid hex");
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16), lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IOException("invalid hex");
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
}
