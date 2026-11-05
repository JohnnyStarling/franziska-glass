package lab.franziska.glass;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** DHCP is discovery, never trust. Every candidate must pass the unchanged mutual TLS pin. */
final class PeerRoute {
    interface Owner {
        void check() throws IOException;
        void own(Socket socket) throws IOException;
    }
    private static String lastNetwork, lastAddress;
    private PeerRoute() {}

    static SecureTransport connect(Context context, String saved, int port,
            AppIdentityStore.LoadedIdentity identity, byte[] pin, int connectMs,
            long handshakeMs, Owner owner) throws Exception {
        owner.check();
        int ip=0,gateway=0,mask=0;
        try {
            WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp=wifi==null?null:wifi.getDhcpInfo();
            if(dhcp!=null) {ip=dhcp.ipAddress;gateway=dhcp.gateway;mask=dhcp.netmask;}
        } catch(RuntimeException unavailable) { /* Saved endpoint remains available. */ }
        final String network=ip+":"+gateway+":"+mask+":"+saved;
        final String[] addresses=candidates(saved,ip,gateway,mask,remembered(network));
        Exception failure=null;
        for(String address:addresses) {
            owner.check();
            final SavedPeerWifi.Snapshot observedNetwork=SavedPeerWifi.captured(context,owner);
            owner.check();
            Socket raw=SecureTransport.newClientSocket();
            boolean accepted=false;
            try {
                owner.own(raw);owner.check();raw.setSoTimeout(1000);
                Tls.DeadlineWatchdog watchdog=Tls.closeAtDeadline(raw,handshakeMs,"glass-route-handshake");
                SecureTransport secure;
                try {secure=SecureTransport.connectOwned(raw,address,port,connectMs,identity,pin);}
                finally {watchdog.cancel();}
                owner.check();
                SavedPeerWifi.learned(context,address,observedNetwork,owner);
                owner.check();
                remember(network,address);accepted=true;return secure;
            } catch(Exception rejected) {failure=rejected;}
            finally {if(!accepted)try {raw.close();}catch(IOException ignored) {}}
            // No second attempt after Stop, pause, revocation or replacement.
            owner.check();
        }
        owner.check();
        SavedPeerWifi.recover(context,owner);
        if(failure!=null)throw failure;
        throw new IOException("PEER_ROUTE_UNAVAILABLE");
    }
    private static synchronized String remembered(String network) {
        return network.equals(lastNetwork)?lastAddress:null;
    }
    private static synchronized void remember(String network,String address) {
        lastNetwork=network;lastAddress=address;
    }
    // DHCP IPv4 integers are little-endian on Android, including the netmask.
    static String[] candidates(String saved,int ip,int gateway,int mask,String last) {
        ArrayList<String> result=new ArrayList<String>(2);
        String discovered=privateAddress(gateway)&&gateway!=ip?literal(gateway):null;
        if(last!=null&&(last.equals(saved)||last.equals(discovered)))add(result,last);
        int savedIp=parse(saved);
        boolean savedLocal=ip!=0&&mask!=0&&(savedIp&mask)==(ip&mask);
        if(!savedLocal)add(result,discovered);
        add(result,saved);add(result,discovered);
        return result.toArray(new String[result.size()]);
    }
    private static void add(ArrayList<String> result,String value) {
        if(value!=null&&!result.contains(value))result.add(value);
    }
    private static int parse(String value) {
        try {String[] p=value.split("\\.");if(p.length!=4)return 0;
            int n=0;for(int i=0;i<4;i++){int b=Integer.parseInt(p[i]);if(b<0||b>255)return 0;n|=b<<(8*i);}return n;
        }catch(RuntimeException invalid){return 0;}
    }
    private static boolean privateAddress(int value) {
        int a=value&255,b=(value>>>8)&255;
        return a==10||(a==172&&b>=16&&b<=31)||(a==192&&b==168);
    }
    private static String literal(int value) {
        return (value&255)+"."+((value>>>8)&255)+"."+((value>>>16)&255)+"."+((value>>>24)&255);
    }
}
