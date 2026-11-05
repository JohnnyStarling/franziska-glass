package lab.franziska.pixelbridge;

import android.content.Context;
import java.io.IOException;
import java.util.Arrays;

/** No broker credential enrollment/import/storage in this community edition. */
final class MyGlassCredentials {
    private MyGlassCredentials() { }
    static boolean present(Context context) { return false; }
    static void save(Context context,char[] supplied) throws Exception {
        if(supplied!=null) Arrays.fill(supplied,'\0');
        throw new IOException("MYGLASS_UNCONFIGURED");
    }
    static char[] load(Context context) throws Exception { throw new IOException("MYGLASS_UNCONFIGURED"); }
    static void forget(Context context) throws Exception { throw new IOException("MYGLASS_UNCONFIGURED"); }
    static void importPending(Context context) throws Exception { throw new IOException("MYGLASS_UNCONFIGURED"); }
}
