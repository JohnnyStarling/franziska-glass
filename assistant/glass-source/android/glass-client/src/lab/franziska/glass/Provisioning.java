package lab.franziska.glass;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import lab.franziska.protocol.AppIdentityStore;

/** App-private pairing material. No external/shared-storage import is supported. */
final class Provisioning {
    private Provisioning() {}

    // One entry for the process, never a Context, parsed key, or file contents. Every lookup
    // still reads bounded current bytes: metadata alone cannot prove revocation/corruption.
    private static final Object CACHE_LOCK = new Object();
    private static Readiness cached;
    private static final class Readiness {
        final String directory, packageName;
        final byte[] fingerprint;
        final boolean ready;
        Readiness(String directory, String packageName, byte[] fingerprint, boolean ready) {
            this.directory=directory;this.packageName=packageName;
            this.fingerprint=fingerprint;this.ready=ready;
        }
        boolean matches(String directory, String packageName, byte[] fingerprint) {
            return this.directory.equals(directory) && this.packageName.equals(packageName)
                    && MessageDigest.isEqual(this.fingerprint,fingerprint);
        }
    }

    static boolean isComplete(Context context) {
        try {
            File directory=context.getFilesDir();
            String path=directory.getCanonicalPath(),packageName=context.getPackageName();
            byte[] before=fingerprint(directory);
            synchronized(CACHE_LOCK) {
                if(cached!=null && cached.matches(path,packageName,before))return cached.ready;
                cached=null;
            }
            boolean ready=AppIdentityStore.identityState(directory,"glass",packageName)
                    == AppIdentityStore.IdentityState.READY;
            // identityState reads the files itself. Never attribute its result to a
            // different snapshot if a writer/revoke crossed that validation.
            byte[] after=fingerprint(directory);
            if(!MessageDigest.isEqual(before,after))return false;
            // Cache only successful proof: a temporary parser/read failure must be retried.
            synchronized(CACHE_LOCK) { cached=ready ? new Readiness(path,packageName,after,true) : null; }
            return ready;
        } catch(Exception unavailable) {
            synchronized(CACHE_LOCK) { cached=null; }
            return false;
        }
    }

    private static byte[] fingerprint(File directory) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[2048];
        try {
            hash(digest,new File(directory,AppIdentityStore.PRIVATE_KEY),16384,buffer);
            hash(digest,new File(directory,AppIdentityStore.CERTIFICATE),16384,buffer);
            hash(digest,new File(directory,AppIdentityStore.PUBLIC_IDENTITY),65536,buffer);
            // Keep the trust-file requirement fresh too. Parsing/pin verification remains
            // with loadTrust at the authenticated connection and commit boundaries.
            hash(digest,new File(directory,AppIdentityStore.TRUST),4096,buffer);
            return digest.digest();
        } finally { Arrays.fill(buffer,(byte)0); }
    }

    private static void hash(MessageDigest digest,File file,int limit,byte[] buffer)
            throws IOException {
        long expected=file.length();
        if(!file.isFile() || expected>limit)throw new IOException("provisioning unavailable");
        // Fixed file order plus length framing makes boundaries unambiguous.
        for(int shift=24;shift>=0;shift-=8)digest.update((byte)(expected>>>shift));
        FileInputStream input=new FileInputStream(file);
        try {
            int total=0,n;
            while((n=input.read(buffer))!=-1) {
                total+=n;
                if(total>limit)throw new IOException("provisioning exceeds bound");
                digest.update(buffer,0,n);
            }
            if(total!=expected || !file.isFile() || file.length()!=expected)
                throw new IOException("provisioning changed");
        } finally { input.close(); }
    }
}
