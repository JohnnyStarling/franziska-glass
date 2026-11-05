package lab.franziska.pixelbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** One reviewed, owner-visible route proposal; never launches an Activity from background. */
final class MyGlassRouteStore {
    private static final String PREFS="myglass-route";
    private static final long MAX_AGE_MS=24L*60*60*1000;
    static final class Route {
        final String commandId,label;
        final double latitude,longitude;
        final long expiresAt;
        Route(String id,String label,double lat,double lon,long expiry) {
            this.commandId=id; this.label=label; this.latitude=lat; this.longitude=lon; this.expiresAt=expiry;
        }
    }
    private MyGlassRouteStore() { }

    static synchronized Route pending(Context context) {
        SharedPreferences prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String id=prefs.getString("id",null), label=prefs.getString("label",null);
        long at=prefs.getLong("at",0);
        long expiresAt=prefs.getLong("expires_at",0);
        long now=System.currentTimeMillis();
        double lat=Double.longBitsToDouble(prefs.getLong("lat",0));
        double lon=Double.longBitsToDouble(prefs.getLong("lon",0));
        if(id==null||label==null||at<=0||now<at||now-at>MAX_AGE_MS
                ||expiresAt<=now/1000L
                || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
                || label.length()<1||label.length()>80||Double.isNaN(lat)||Double.isNaN(lon)
                || lat < -90||lat >90||lon < -180||lon>180) return null;
        return new Route(id,label,lat,lon,expiresAt);
    }

    static synchronized boolean save(Context context,MyGlassWire.Command command) {
        if(!MyGlassWire.NAVIGATE.equals(command.kind)
                ||command.expiresAt<=System.currentTimeMillis()/1000L) return false;
        Route prior=pending(context);
        if(prior!=null) return prior.commandId.equals(command.id);
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString("id",command.id).putString("label",command.label)
                .putLong("lat",Double.doubleToLongBits(command.latitude))
                .putLong("lon",Double.doubleToLongBits(command.longitude))
                .putLong("expires_at",command.expiresAt)
                .putLong("at",System.currentTimeMillis()).commit();
    }

    static synchronized boolean clear(Context context,String id) {
        Route prior=pending(context);
        if(prior==null||!prior.commandId.equals(id)) return false;
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().commit();
    }
}
