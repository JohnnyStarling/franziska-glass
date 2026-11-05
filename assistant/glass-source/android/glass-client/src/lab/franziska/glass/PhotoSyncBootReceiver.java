package lab.franziska.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reopen only a previously owner-enabled local photo channel after boot. */
public final class PhotoSyncBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!context.getSharedPreferences("photo-sync", Context.MODE_PRIVATE).getBoolean("enabled", false)
                || !Provisioning.isComplete(context)) return;
        Intent start = new Intent(context, PhotoSyncService.class);
        start.setAction(PhotoSyncService.START);
        try { context.startService(start); } catch (RuntimeException unavailable) { }
    }
}
