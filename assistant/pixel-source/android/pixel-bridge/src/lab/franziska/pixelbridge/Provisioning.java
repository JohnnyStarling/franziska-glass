package lab.franziska.pixelbridge;

import android.content.Context;
import java.io.File;

/** App-private pairing material. No external/shared-storage import is supported. */
final class Provisioning {
    /** Short local publication/revoke gate; never held for file copy, hashing or network I/O. */
    static final Object MEDIA_PUBLICATION_LOCK = new Object();
    private Provisioning() {}

    static boolean isComplete(Context context) {
        return lab.franziska.protocol.AppIdentityStore.identityState(context.getFilesDir(),
                "pixel", context.getPackageName())
                == lab.franziska.protocol.AppIdentityStore.IdentityState.READY
                && new File(context.getFilesDir(),
                        lab.franziska.protocol.AppIdentityStore.TRUST).isFile();
    }

    static boolean revoke(Context context) {
        synchronized(MEDIA_PUBLICATION_LOCK) {
            return lab.franziska.protocol.AppIdentityStore.revokeOwn(context.getFilesDir())
                    && !isComplete(context);
        }
    }
}
