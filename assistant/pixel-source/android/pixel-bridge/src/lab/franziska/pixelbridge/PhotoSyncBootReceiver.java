package lab.franziska.pixelbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Reopen only an owner-enabled receiver; no boot-time camera, microphone or cloud action. */
public final class PhotoSyncBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if(!Provisioning.isComplete(context))return;
        if(context.getSharedPreferences("route-mode",Context.MODE_PRIVATE).getBoolean("enabled",false)) {
            Intent route=new Intent(context,LinkService.class).setAction(LinkService.ROUTE_RESTORE);
            try {if(Build.VERSION.SDK_INT>=26)context.startForegroundService(route);else context.startService(route);}
            catch(RuntimeException unavailable){ /* Owner can use Prepare after OS restrictions. */ }
        }
        if(!context.getSharedPreferences("photo-sync",Context.MODE_PRIVATE).getBoolean("enabled",false))return;
        Intent start=new Intent(context,PhotoSyncService.class).setAction(PhotoSyncService.START);
        try {if(Build.VERSION.SDK_INT>=26)context.startForegroundService(start);else context.startService(start);}
        catch(RuntimeException unavailable) { }
    }
}
