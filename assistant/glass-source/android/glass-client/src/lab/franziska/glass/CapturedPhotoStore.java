package lab.franziska.glass;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lab.franziska.protocol.AssistWire;
import lab.franziska.protocol.ProtocolException;

/** Stage exact captured JPEG bytes, fsync, then publish one original with an owner gate. */
final class CapturedPhotoStore {
    interface Commit { void run() throws IOException; }
    interface Gate {
        boolean active();
        /** Check the current capture/session generation under the owner's lock, then run commit. */
        boolean commit(Commit operation) throws IOException;
    }
    enum Result { CANCELLED, SAVED_INDEXED, SAVED_SCAN_FAILED }
    interface Published { void published(); }

    static Result save(Context context, byte[] jpeg, Gate gate)
            throws IOException, ProtocolException {
        return save(context, jpeg, gate, null);
    }
    /** Publication is reported before the separately bounded MediaStore scan. */
    static Result save(Context context, byte[] jpeg, Gate gate, Published listener)
            throws IOException, ProtocolException {
        if (context==null || jpeg==null || gate==null) throw new IllegalArgumentException();
        AssistWire.validateJpeg(jpeg);
        if (!gate.active()) return Result.CANCELLED;
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()))
            throw new IOException("photo storage unavailable");
        File dcim=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        File dir=new File(dcim,"Franziska");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("photo directory unavailable");
        String expected=new File(dcim.getCanonicalFile(),"Franziska").getPath();
        if (!dir.isDirectory() || !dir.getCanonicalPath().equals(expected))
            throw new IOException("photo directory not owned");
        File stage=File.createTempFile(".franziska-",".part",dir);
        boolean committed=false;
        try {
            FileOutputStream output=new FileOutputStream(stage);
            try {
                for (int offset=0;offset<jpeg.length;) {
                    if (!gate.active()) return Result.CANCELLED;
                    int count=Math.min(8192,jpeg.length-offset);
                    output.write(jpeg,offset,count);offset+=count;
                }
                output.getFD().sync();
            } finally { output.close(); }
            if (!gate.active()) return Result.CANCELLED;
            final File finalFile=new File(dir,"FRZ_"+System.currentTimeMillis()+"_"
                    +UUID.randomUUID().toString()+".jpg");
            committed=gate.commit(new Commit() { public void run() throws IOException {
                if (finalFile.exists() || !stage.renameTo(finalFile))
                    throw new IOException("photo publication failed");
            }});
            if (!committed) return Result.CANCELLED;
            if (listener != null) listener.published();
            try {
                final CountDownLatch indexed=new CountDownLatch(1);
                final Uri[] scanned=new Uri[1];
                MediaScannerConnection.scanFile(context,
                        new String[]{finalFile.getAbsolutePath()},new String[]{"image/jpeg"},
                        new MediaScannerConnection.OnScanCompletedListener() {
                            public void onScanCompleted(String path, Uri uri) {
                                if (finalFile.getAbsolutePath().equals(path)) scanned[0]=uri;
                                indexed.countDown();
                            }
                        });
                if (!indexed.await(8000L,TimeUnit.MILLISECONDS) || scanned[0]==null
                        || !scanned[0].toString().startsWith("content://media/"))
                    return Result.SAVED_SCAN_FAILED;
                return Result.SAVED_INDEXED;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();return Result.SAVED_SCAN_FAILED;
            } catch (RuntimeException scannerUnavailable) {
                // Keep the committed original; caller may retry MediaStore registration later.
                return Result.SAVED_SCAN_FAILED;
            }
        } finally {
            if (!committed && stage.exists()) stage.delete(); // Only this helper's staging file.
        }
    }
    private CapturedPhotoStore() {}
}
