package lab.franziska.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.os.Build;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Re-select an already saved, owner-approved peer Wi-Fi; never handles credentials.
 * The caller must invoke learned only AFTER the unchanged exact peer pin authenticated.
 * Recovery is transport readiness, never permission to resume recording or replay a question.
 */
final class SavedPeerWifi {
    private static final long RETRY_MS=15000L, SCAN_MAX_US=30000000L;
    private static long lastScan=-1L,lastSelection=-1L;
    private SavedPeerWifi() {}

    static final class Snapshot {
        private final String ssid;
        private final int networkId,ip,gateway;
        private Snapshot(String ssid,int id,int ip,int gateway){this.ssid=ssid;networkId=id;this.ip=ip;this.gateway=gateway;}
        private boolean same(Snapshot other){return other!=null&&networkId==other.networkId&&ip==other.ip
                &&gateway==other.gateway&&ssid.equals(other.ssid);}
    }
    /** Capture immediately before this exact socket attempt, never after TLS succeeded. */
    static Snapshot captured(Context context,PeerRoute.Owner owner) throws IOException {
        owner.check();
        try {
            WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            owner.check();
            if(wifi==null)return null;
            boolean enabled=wifi.isWifiEnabled();owner.check();if(!enabled)return null;
            WifiInfo info=wifi.getConnectionInfo();owner.check();
            if(info==null||info.getSupplicantState()!=SupplicantState.COMPLETED)return null;
            String ssid=unquote(info.getSSID());int id=info.getNetworkId();
            if(!valid(ssid)||id<0)return null;
            DhcpInfo dhcp=wifi.getDhcpInfo();owner.check();
            if(dhcp==null||dhcp.ipAddress==0||dhcp.gateway==0)return null;
            int ip=dhcp.ipAddress,gateway=dhcp.gateway;
            List<WifiConfiguration> networks=wifi.getConfiguredNetworks();owner.check();
            if(!configured(networks,ssid,id))return null;
            return new Snapshot(ssid,id,ip,gateway);
        }catch(RuntimeException unavailable){return null;}
    }
    static void learned(Context context,String authenticatedAddress,Snapshot before,PeerRoute.Owner owner) throws IOException {
        owner.check();
        if(before==null||!literal(before.gateway).equals(authenticatedAddress))return;
        try {
            // Bind the authenticated socket to its pre-connect Wi-Fi, not whichever network
            // happens to be current when an old handshake callback finally runs.
            Snapshot current=captured(context,owner);
            if(!before.same(current))return;
            Snapshot confirmed=captured(context,owner);
            if(!before.same(confirmed))return;
            SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("peer-route",Context.MODE_PRIVATE);
            owner.check();
            if(!before.ssid.equals(prefs.getString("preferred_ssid",""))) {
                owner.check();
                prefs.edit().putString("preferred_ssid",before.ssid).commit();
            }
        }catch(RuntimeException unavailable){ /* Framework/permission unavailable: do not alter Wi-Fi. */ }
    }

    static void recover(Context context,PeerRoute.Owner owner) throws IOException {
        owner.check();
        // ScanResult.timestamp was added in API17. Older supported builds keep their
        // existing network and manual Settings path rather than trusting undated scans.
        if(Build.VERSION.SDK_INT<17)return;
        try {
            Context app=context.getApplicationContext();
            String target=app.getSharedPreferences("peer-route",Context.MODE_PRIVATE).getString("preferred_ssid","");
            if(!valid(target))return;
            WifiManager wifi=(WifiManager)app.getSystemService(Context.WIFI_SERVICE);
            if(wifi==null||!wifi.isWifiEnabled())return;
            WifiInfo current=wifi.getConnectionInfo();owner.check();
            // A failed photo/listener request must not tear down an already healthy
            // voice Wi-Fi connection merely to reconnect it to the same preferred AP.
            if(current!=null&&current.getSupplicantState()==SupplicantState.COMPLETED
                    &&target.equals(unquote(current.getSSID())))return;
            int networkId=configuredId(wifi.getConfiguredNetworks(),target);
            if(networkId<0)return;
            long now=SystemClock.elapsedRealtime();
            if(reserve(now,true)) {
                owner.check();
                try {wifi.startScan();}catch(RuntimeException unavailable){ /* Fresh cached results remain usable. */ }
                owner.check();
            }
            List<ScanResult> scans=wifi.getScanResults();
            owner.check();
            long nowUs=SystemClock.elapsedRealtime()*1000L;
            boolean visible=false;
            if(scans!=null)for(ScanResult scan:scans) {
                if(scan!=null&&target.equals(scan.SSID)&&scan.timestamp>0L
                        &&scan.timestamp<=nowUs&&nowUs-scan.timestamp<=SCAN_MAX_US){visible=true;break;}
            }
            if(!visible)return;
            // Fresh scan is not a credential/trust source: target must remain a saved network.
            owner.check();
            if(!wifi.isWifiEnabled()||configuredId(wifi.getConfiguredNetworks(),target)!=networkId)return;
            if(!reserve(SystemClock.elapsedRealtime(),false))return;
            select(wifi,networkId,owner);
        }catch(RuntimeException unavailable){ /* No root, enabling Wi-Fi or credential repair fallback. */ }
    }

    private static synchronized boolean reserve(long now,boolean scan) {
        long prior=scan?lastScan:lastSelection;
        if(prior>=0L&&now>=prior&&now-prior<RETRY_MS)return false;
        if(scan)lastScan=now;else lastSelection=now;
        return true;
    }

    private static void select(WifiManager wifi,int networkId,PeerRoute.Owner owner) throws IOException {
        final Method connect;
        try {
            Class<?> listener=Class.forName("android.net.wifi.WifiManager$ActionListener");
            connect=WifiManager.class.getMethod("connect",Integer.TYPE,listener);
        }catch(ClassNotFoundException missing){fallback(wifi,networkId,owner);return;}
        catch(NoSuchMethodException missing){fallback(wifi,networkId,owner);return;}
        catch(SecurityException unavailable){fallback(wifi,networkId,owner);return;}
        owner.check();
        try {connect.invoke(wifi,Integer.valueOf(networkId),null);}
        catch(IllegalAccessException unavailable){fallback(wifi,networkId,owner);}
        catch(InvocationTargetException rejected){ /* Existing method rejected: no second mutation/retry. */ }
        owner.check();
    }

    private static void fallback(WifiManager wifi,int networkId,PeerRoute.Owner owner) throws IOException {
        owner.check();
        boolean enabled=wifi.enableNetwork(networkId,false); // Never disable other saved/home networks.
        owner.check();if(!enabled)return;
        wifi.reconnect();owner.check();
    }
    private static boolean configured(List<WifiConfiguration> networks,String target,int id) {
        if(networks==null||id<0)return false;
        for(WifiConfiguration n:networks)if(n!=null&&n.networkId==id&&quoted(target).equals(n.SSID))return true;
        return false;
    }
    private static int configuredId(List<WifiConfiguration> networks,String target) {
        if(networks==null)return -1;
        int found=-1;
        for(WifiConfiguration n:networks)if(n!=null&&n.networkId>=0&&quoted(target).equals(n.SSID)) {
            if(found>=0&&found!=n.networkId)return -1; // Ambiguous records: leave selection to owner.
            found=n.networkId;
        }
        return found;
    }
    private static boolean valid(String value) {
        if(value==null||value.length()==0||value.length()>32||"<unknown ssid>".equals(value))return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)return false;
        try{return value.getBytes("UTF-8").length<=32;}catch(java.io.UnsupportedEncodingException impossible){return false;}
    }
    private static String quoted(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private static String unquote(String value) {
        if(value==null)return null;
        if(value.length()<2||value.charAt(0)!='"'||value.charAt(value.length()-1)!='"')return value;
        StringBuilder out=new StringBuilder();
        for(int i=1;i<value.length()-1;i++){
            char c=value.charAt(i);
            if(c=='\\'){
                if(++i>=value.length()-1)return null;
                c=value.charAt(i);if(c!='\\'&&c!='"')return null;
            }
            out.append(c);
        }
        return out.toString();
    }
    private static String literal(int value) {
        return (value&255)+"."+((value>>>8)&255)+"."+((value>>>16)&255)+"."+((value>>>24)&255);
    }
}
