package lab.franziska.pixelbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.TelecomManager;

/** Cellular call adapter for an authenticated Glass-confirmation command.
 * No Activity launch, contact read, implicit dialer selection, or automatic retry.
 */
public final class PhoneCallLauncher {
    public enum Result { PERMISSION_REQUIRED, NOT_CONFIRMED, NO_TELECOM, REQUESTED, PLATFORM_REJECTED }
    private PhoneCallLauncher() { }

    /** The peer ID must come from the authenticated Glass transport, never the
     * OpenClaw/broker message body. REQUESTED does not mean ringing or connected.
     */
    public static Result placeConfirmed(Context context,PhoneCallGate gate,String requestId,String authenticatedGlassPeerId) {
        if(context==null||gate==null)throw new IllegalArgumentException("context/gate");
        // Consume first: every authenticated confirmation is one attempt, including when
        // permission or Telecom disappears between preparation and confirmation.
        String number=gate.consumeConfirmed(requestId,authenticatedGlassPeerId,SystemClock.elapsedRealtime());
        if(number==null)return Result.NOT_CONFIRMED;
        if(context.checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)
            return Result.PERMISSION_REQUIRED;
        TelecomManager telecom=(TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
        if(telecom==null)return Result.NO_TELECOM;
        try{
            telecom.placeCall(Uri.fromParts("tel",number,null),new Bundle());
            return Result.REQUESTED;
        }catch(SecurityException denied){return Result.PLATFORM_REJECTED;}
        catch(RuntimeException rejected){return Result.PLATFORM_REJECTED;}
    }
}
