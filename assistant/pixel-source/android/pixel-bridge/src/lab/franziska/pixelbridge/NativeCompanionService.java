package lab.franziska.pixelbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

/** Pixel-owned stock XE24 connection. It never opens Glass sensors or an Internet endpoint. */
public final class NativeCompanionService extends Service {
    static final String CONNECT = "lab.franziska.pixelbridge.native.CONNECT";
    static final String DISCONNECT = "lab.franziska.pixelbridge.native.DISCONNECT";
    static final String UPDATE = "lab.franziska.pixelbridge.native.UPDATE";
    static final String DEVICE = "device";
    private final LocalBinder binder = new LocalBinder();
    private NativeCompanionClient client;
    private String state = "Не подключено";
    private String details = "Батарея и версия: нет данных";
    private byte[] latestScreen;
    private boolean connected, casting;
    private boolean locationAllowed;
    private String requestedProvider;
    private LocationManager locationManager;
    private final LocationListener locations = new LocationListener() {
        public void onLocationChanged(Location location) {
            if (!connected || !locationAllowed || requestedProvider == null || location == null) return;
            try { sendCommand(Xe24Wire.locationUpdate(requestedProvider, location.getLatitude(),
                    location.getLongitude(), Math.max(0f, location.getAccuracy()), location.getTime())); }
            catch (IllegalArgumentException ignored) { }
        }
        public void onStatusChanged(String provider, int status, Bundle extras) { }
        public void onProviderEnabled(String provider) { }
        public void onProviderDisabled(String provider) { }
    };
    public final class LocalBinder extends Binder { NativeCompanionService service() { return NativeCompanionService.this; } }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (DISCONNECT.equals(intent.getAction())) { stopCompanion(); return START_NOT_STICKY; }
        if (!CONNECT.equals(intent.getAction())) return START_NOT_STICKY;
        final String address = intent.getStringExtra(DEVICE);
        if (address == null) { state = "Glass не выбраны"; publish(); stopSelf(); return START_NOT_STICKY; }
        if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(11, notification());
        if (client != null) client.close();
        locationAllowed = false; requestedProvider = null; stopLocationUpdates();
        connected = false; casting = false; latestScreen = null;
        state = "Подключаюсь к выбранным Glass…"; publish();
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth off");
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IllegalArgumentException("not bonded");
            final NativeCompanionClient[] owner = new NativeCompanionClient[1];
            final NativeCompanionClient next = new NativeCompanionClient(new NativeCompanionClient.Listener() {
                public void ready(Xe24Wire.Reply hello) {
                    if (client != owner[0]) return;
                    connected = true; state = "Стоковый канал XE24 подключён"; publish();
                }
                public void received(Xe24Wire.Reply reply) {
                    if (client != owner[0]) return;
                    if (reply.battery != null || reply.software != null || reply.name != null)
                        details = (reply.name == null ? "Glass" : reply.name)
                                + " · батарея " + (reply.battery == null ? "?" : reply.battery + "%")
                                + " · " + (reply.software == null ? "версия ?" : reply.software);
                    if (casting && reply.screen != null) latestScreen = reply.screen;
                    if (reply.locationRequestType != null) {
                        if (reply.locationRequestType == 1) { requestedProvider = null; stopLocationUpdates(); }
                        else if (reply.locationProvider != null && !reply.locationProvider.isEmpty()) {
                            requestedProvider = reply.locationProvider;
                            if (locationAllowed) startLocationUpdates();
                        }
                    }
                    publish();
                }
                public void ended(String category) {
                    if (client != owner[0]) return;
                    connected = false; casting = false; latestScreen = null;
                    state = "Стоковый канал закрыт: " + category; publish(); stopForeground(true); stopSelf();
                }
            });
            owner[0] = next;
            client = next;
            next.connect(device);
        } catch (Exception failure) {
            stopNative("Не удалось подключиться к выбранным Glass");
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel("franziska-native", "Glass companion", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, NativeCompanionActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent action = PendingIntent.getActivity(this, 11, open, PendingIntent.FLAG_UPDATE_CURRENT | flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "franziska-native") : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("Франциска · Glass")
                .setContentText("Штатная связь Glass активна").setContentIntent(action).setOngoing(true).build();
    }
    boolean connected() { return connected; }
    boolean locationAllowed() { return locationAllowed; }
    String state() { return state; }
    String details() { return details; }
    byte[] takeScreen() { byte[] frame = latestScreen; latestScreen = null; return frame; }
    boolean info() { return send(Xe24Wire.infoRequest()); }
    boolean wifi(String ssid, String security, String key) { return send(Xe24Wire.wifiRequest(ssid, security, key)); }
    boolean navigation(double latitude, double longitude) { return send(Xe24Wire.navigationRequest(latitude, longitude)); }
    boolean screen(boolean start) {
        if (!send(Xe24Wire.screenRequest(start))) return false;
        casting = start; if (!start) latestScreen = null;
        return true;
    }
    boolean sendCommand(byte[] command) { return connected && client != null && client.send(command); }
    private boolean send(byte[] command) { return sendCommand(command); }
    boolean enableLocation() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return false;
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(11, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            locationAllowed = true;
            if (requestedProvider != null) startLocationUpdates();
            publish(); return true;
        } catch (RuntimeException denied) { locationAllowed = false; return false; }
    }
    void disableLocation() {
        locationAllowed = false; stopLocationUpdates();
        if (connected && Build.VERSION.SDK_INT >= 29) startForeground(11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        publish();
    }
    private void startLocationUpdates() {
        if (!connected || !locationAllowed || requestedProvider == null) return;
        stopLocationUpdates();
        try {
            if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            String provider = locationManager.getProvider("fused") != null ? "fused"
                    : locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null ? LocationManager.NETWORK_PROVIDER
                    : LocationManager.GPS_PROVIDER;
            locationManager.requestLocationUpdates(provider, 1000L, 0f, locations);
        } catch (RuntimeException denied) { state = "Геолокация Pixel недоступна"; publish(); }
    }
    private void stopLocationUpdates() {
        if (locationManager != null) try { locationManager.removeUpdates(locations); } catch (RuntimeException ignored) { }
    }
    void stopCompanion() {
        stopNative("Отключено владельцем");
    }
    private void stopNative(String reason) {
        disableLocation(); requestedProvider = null;
        connected = false; casting = false; latestScreen = null;
        if (client != null) { client.close(); client = null; }
        state = reason; publish(); stopForeground(true); stopSelf();
    }
    private void publish() { Intent update = new Intent(UPDATE).setPackage(getPackageName()); sendBroadcast(update); }
    @Override public void onDestroy() {
        locationAllowed = false; stopLocationUpdates(); requestedProvider = null;
        if (client != null) { client.close(); client = null; }
        connected = false; casting = false; latestScreen = null;
        super.onDestroy();
    }
}
