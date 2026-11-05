package lab.franziska.pixelbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.MediaWire;
import lab.franziska.protocol.PhotoWire;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** Explicit Pixel receiver; pinned Glass can advertise/copy media, never send cloud requests. */
public final class PhotoSyncService extends Service {
    static final String START = "lab.franziska.pixelbridge.PHOTO_START";
    static final String STOP = "lab.franziska.pixelbridge.PHOTO_STOP";
    static final String CANCEL_ALL = "lab.franziska.pixelbridge.MEDIA_CANCEL_ALL";
    static final String RUNTIME_CHANGED = "lab.franziska.pixelbridge.PHOTO_RUNTIME_CHANGED";
    private volatile boolean running;
    private volatile ServerSocket listener;
    private volatile long mediaEpoch;
    private volatile long receiverGeneration;
    /** One background inventory/copy and one explicit selected-original transfer. */
    private static final int MAX_MEDIA_SESSIONS = 2;
    private final Map<Socket, Boolean> sessions = new HashMap<Socket, Boolean>();
    private Thread worker;
    private java.util.ArrayList<GlassMediaCatalog.Item> galleryRequest;
    private long galleryDeadline;
    private GalleryDeleteRequest deleteRequest;
    private boolean deleteInFlight;
    /** Caller must present explicit final confirmation; never called from resume/reconnect. */
    synchronized boolean requestGlassDeletion(java.util.ArrayList<GlassMediaCatalog.Item> items) {
        if(!running||!Provisioning.isComplete(this)||hasDeletion())return false;
        try {
            GalleryDeleteRequest request=new GalleryDeleteRequest(this,items,SystemClock.elapsedRealtime()+120000L);
            mediaEpoch++;cancelDeletion();closeSessions();galleryRequest=null;deleteRequest=request;
            getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putBoolean("import_all",false)
                    .putString("delete_results","").commit();
            deleteStatus("Ожидаем Glass · удаление однократное");return true;
        }catch(Exception invalid) {deleteStatus("Нужен проверенный оригинал каждого файла на Pixel");return false;}
    }
    synchronized GalleryDeleteRequest takeDeletion(long epoch,byte hello) {
        if(!isCurrent(epoch)||!MediaWire.canDelete(hello))return null;
        GalleryDeleteRequest request=deleteRequest;deleteRequest=null; // consume BEFORE any PLAN write
        if(request!=null&&SystemClock.elapsedRealtime()>=request.expires) {
            deleteStatus("Удаление не отправлено: истекли 2 минуты");return null;
        }
        if(request!=null)deleteInFlight=true;
        return request;
    }
    synchronized boolean hasDeletion() {
        if(deleteRequest!=null&&SystemClock.elapsedRealtime()>=deleteRequest.expires){deleteRequest=null;deleteStatus("Удаление не отправлено: истекли 2 минуты");}
        return deleteInFlight||deleteRequest!=null;
    }
    synchronized void deletionFinished(long epoch) {if(isCurrent(epoch)){deleteInFlight=false;runtimeChanged();}}
    synchronized void deleteStatusCurrent(long epoch,String value) {if(isCurrent(epoch))deleteStatus(value);}
    void deleteStatus(String value) {
        getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putString("delete_status",value)
                .putLong("delete_updated_at",System.currentTimeMillis()).apply();runtimeChanged();
    }
    private void cancelDeletion() {
        boolean had=deleteRequest!=null||deleteInFlight;deleteRequest=null;deleteInFlight=false;
        if(had)deleteStatus("Удаление отменено; уже отправленные действия могли завершиться · обновите каталог");
    }
    synchronized boolean requestCopies(java.util.ArrayList<GlassMediaCatalog.Item> items) {
        if(!running||items==null||items.isEmpty()||items.size()>128)return false;
        mediaEpoch++;cancelDeletion();closeSessions();
        galleryRequest=new java.util.ArrayList<GlassMediaCatalog.Item>(items);
        galleryDeadline=SystemClock.elapsedRealtime()+3600000L;
        getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putBoolean("import_all",false).commit();
        status("Выбрано: "+items.size()+" · ожидаем Glass");runtimeChanged();return true;
    }
    synchronized java.util.ArrayList<GlassMediaCatalog.Item> selected(long epoch) {
        if(!isCurrent(epoch))return null;
        if(SystemClock.elapsedRealtime()>=galleryDeadline) {galleryRequest=null;return null;}
        return galleryRequest;
    }
    synchronized void selectedDone(long epoch) {if(isCurrent(epoch)){galleryRequest=null;runtimeChanged();}}
    synchronized boolean hasSelection() {return galleryRequest!=null&&SystemClock.elapsedRealtime()<galleryDeadline;}


    final class StateBinder extends Binder {
        PhotoSyncService service() { return PhotoSyncService.this; }
    }
    @Override public IBinder onBind(Intent intent) { return new StateBinder(); }
    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopSync(); return START_NOT_STICKY; }
        if (intent != null && CANCEL_ALL.equals(intent.getAction())) {
            mediaEpoch++;galleryRequest=null;cancelDeletion();
            getSharedPreferences("photo-sync", MODE_PRIVATE).edit().putBoolean("import_all", false).commit();
            closeSessions(); status("Перенос отменён"); runtimeChanged(); return START_STICKY;
        }
        boolean restart = intent == null && getSharedPreferences("photo-sync", MODE_PRIVATE)
                .getBoolean("enabled", false);
        if (!restart && (intent == null || !START.equals(intent.getAction()))) {
            runtimeChanged(); return START_NOT_STICKY;
        }
        if (running || !Provisioning.isComplete(this)) {
            runtimeChanged();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT < 29) { runtimeChanged(); return START_NOT_STICKY; }
        clearAbandonedParts();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(12, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(12, notification());
        running = true;
        getSharedPreferences("photo-sync", MODE_PRIVATE).edit().putBoolean("enabled", true).commit();
        status("Ожидает Glass");
        final long token=++receiverGeneration;
        worker = new Thread(new Runnable() { public void run() { listen(token); } }, "pixel-photo-receiver");
        worker.start();
        runtimeChanged();
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            manager.createNotificationChannel(new NotificationChannel("glass-photo-sync",
                    "Glass photos", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, BridgeActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent target = PendingIntent.getActivity(this, 12, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "glass-photo-sync") : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Glass → Pixel · медиа")
                .setContentText("Каталог и перенос фото/видео")
                .setContentIntent(target).setOngoing(true).build();
    }

    private void clearAbandonedParts() {
        File[] files = getCacheDir().listFiles();
        if (files == null) return;
        for (File file : files)
            if (file.isFile() && file.getName().startsWith("glass-media-")
                    && file.getName().endsWith(".part")) file.delete();
    }

    private synchronized boolean receiverActive(long token){return running&&receiverGeneration==token;}
    private synchronized boolean ownListener(long token,ServerSocket owned){
        if(!receiverActive(token))return false;
        listener=owned;return true;
    }
    private void listen(long token) {
        while (receiverActive(token) && Provisioning.isComplete(this)) {
            ServerSocket owned = null;
            try {
                AppIdentityStore.LoadedIdentity identity = AppIdentityStore.loadIdentity(getFilesDir());
                AppIdentityStore.TrustConfig trust = AppIdentityStore.loadTrust(getFilesDir(), true);
                if (trust.port >= 65535) throw new IOException("PHOTO_PORT_INVALID");
                owned = SecureTransport.listen("0.0.0.0", trust.port + 1);
                if(!ownListener(token,owned))break;
                synchronized(this){if(receiverActive(token))status("Ожидает Glass");}
                while (receiverActive(token) && Provisioning.isComplete(this)) {
                    try {
                        final Socket connected = owned.accept();
                        final long epoch = admitListener(token,connected);
                        if (epoch < 0L) { close(connected); continue; }
                        try {
                            new Thread(new Runnable() { public void run() {
                                try {
                                    new MediaReceiver(PhotoSyncService.this, epoch).receive(
                                            connected, identity, trust.peerPin);
                                } catch (Exception failure) {
                                    if (isCurrent(epoch) && !"MEDIA_SESSION_BUSY".equals(failure.getMessage())) {
                                        boolean lowSpace="MEDIA_STORAGE_LOW".equals(failure.getMessage());
                                        if (Boolean.TRUE.equals(sessionRole(connected)))
                                            shareStatus(lowSpace ? "Для оригинала не хватает места на Pixel" :
                                                    "Передача выбранного оригинала не завершена");
                                        else status(lowSpace ? "На Pixel не хватает места" : "Ожидает повтор медиа Glass");
                                    }
                                } finally { release(connected); close(connected); }
                            }}, "pixel-media-session").start();
                        } catch (RuntimeException unavailable) { release(connected); close(connected); throw unavailable; }
                    } catch (Exception listenerFailure) { break; } // Rebind after delay.
                }
            } catch (Exception unavailable) {
                synchronized(this){if(receiverActive(token))status("Сеть Pixel недоступна");}
            } finally {
                synchronized(this){if(listener==owned)listener=null;}
                if (owned != null) try { owned.close(); } catch (IOException ignored) {}
            }
            if (receiverActive(token)) try { Thread.sleep(10000L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        }
        synchronized(this){if(receiverActive(token)&&!Provisioning.isComplete(this))stopSync();}
    }

    private void receive(Socket socket, AppIdentityStore.LoadedIdentity identity, byte[] pin)
            throws Exception {
        socket.setSoTimeout(1000);
        Tls.DeadlineWatchdog handshake = Tls.closeAtDeadline(socket, 8000L, "photo-pixel-handshake");
        SecureTransport secure;
        try { secure = SecureTransport.acceptOwned(socket, identity, pin); }
        finally { handshake.cancel(); }
        Tls.FrameReader reader = Tls.frameReader(secure.input(), secure.rawCloser(), 15000L, 5000L);
        PhotoWire.Frame begin = null;
        File temp = null;
        FileOutputStream output = null;
        MessageDigest digest = null;
        int written = 0;
        long expires = 0;
        try {
            while (running && Provisioning.isComplete(this)) {
                PhotoWire.Frame frame;
                try { frame = PhotoWire.parse(reader.read()); }
                catch (Tls.IdleTimeoutException idle) {
                    if (begin != null && SystemClock.elapsedRealtime() > expires)
                        throw new IOException("PHOTO_TRANSFER_TIMEOUT");
                    continue;
                }
                if (frame.type == PhotoWire.BEGIN && begin == null) {
                    begin = frame;
                    expires = SystemClock.elapsedRealtime() + 60000L;
                    temp = File.createTempFile("glass-photo-", ".part", getCacheDir());
                    output = new FileOutputStream(temp);
                    digest = MessageDigest.getInstance("SHA-256"); written = 0;
                } else if (frame.type == PhotoWire.DATA && begin != null
                        && frame.photoId == begin.photoId && frame.offset == written
                        && (long)written + frame.data.length <= begin.totalBytes
                        && SystemClock.elapsedRealtime() <= expires) {
                    output.write(frame.data);
                    digest.update(frame.data);
                    written += frame.data.length;
                } else if (frame.type == PhotoWire.COMMIT && begin != null
                        && frame.photoId == begin.photoId && written == begin.totalBytes
                        && PhotoWire.sameHash(frame.hash, begin.hash)
                        && SystemClock.elapsedRealtime() <= expires) {
                    output.getFD().sync(); output.close(); output = null;
                    if (!PhotoWire.sameHash(digest.digest(), begin.hash))
                        throw new IOException("PHOTO_HASH_MISMATCH");
                    if (!running || !Provisioning.isComplete(this)
                            || !MessageDigest.isEqual(pin,
                                AppIdentityStore.loadTrust(getFilesDir(), true).peerPin))
                        throw new IOException("PHOTO_TRUST_REVOKED");
                    new PhotoAlbum(this).publish(begin.photoId, begin.hash, temp, written);
                    Tls.write(secure.output(), PhotoWire.ack(begin.photoId, begin.hash),
                            socket, 5000L, "photo-pixel-ack");
                    temp.delete();
                    temp = null; begin = null; digest = null; written = 0;
                } else throw new IOException("PHOTO_SEQUENCE_INVALID");
            }
        } finally {
            if (output != null) try { output.close(); } catch (IOException ignored) {}
            if (temp != null) temp.delete();
            close(socket);
        }
    }

    private synchronized void stopSync() {
        getSharedPreferences("photo-sync", MODE_PRIVATE).edit().putBoolean("enabled", false).commit();
        cleanup();
        status("Выключено"); stopForeground(true); stopSelf();
        runtimeChanged();
    }
    private synchronized void cleanup() {
        receiverGeneration++;
        Thread oldWorker=worker;worker=null;if(oldWorker!=null)oldWorker.interrupt();
        mediaEpoch++;galleryRequest=null;cancelDeletion();
        running = false;
        closeSessions();
        ServerSocket server = listener; listener = null;
        if (server != null) try { server.close(); } catch (IOException ignored) {}
    }
    boolean isRunning() { return running; }
    private void runtimeChanged() {
        sendBroadcast(new Intent(RUNTIME_CHANGED).setPackage(getPackageName()));
    }
    boolean isCurrent(long epoch) {return running&&mediaEpoch==epoch;}
    private synchronized long admitListener(long token,Socket connected){return receiverActive(token)?admit(connected):-1L;}
    private synchronized long admit(Socket connected) {
        if (!running || sessions.size() >= MAX_MEDIA_SESSIONS) return -1L;
        sessions.put(connected, null); // Role becomes known only after pinned TLS and HELLO.
        return mediaEpoch;
    }
    synchronized boolean claimSession(long epoch, Socket connected, boolean sharing) {
        if (!isCurrent(epoch) || !sessions.containsKey(connected)) return false;
        for (Boolean role : sessions.values()) if (role != null && role.booleanValue() == sharing) return false;
        sessions.put(connected, Boolean.valueOf(sharing));
        return true;
    }
    private synchronized Boolean sessionRole(Socket connected) { return sessions.get(connected); }
    private synchronized void release(Socket connected) { sessions.remove(connected); }
    private synchronized void closeSessions() {
        for (Socket connected : sessions.keySet()) close(connected);
        sessions.clear();
    }
    void status(String value) {
        getSharedPreferences("photo-sync", MODE_PRIVATE).edit()
                .putString("status", value).apply();
    }
    void shareStatus(String value) {
        getSharedPreferences("photo-sync", MODE_PRIVATE).edit()
                .putString("share_status", value)
                .putLong("share_updated_at", System.currentTimeMillis()).apply();
    }
    private static void close(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
    @Override public void onDestroy() { cleanup(); runtimeChanged(); super.onDestroy(); }
}
