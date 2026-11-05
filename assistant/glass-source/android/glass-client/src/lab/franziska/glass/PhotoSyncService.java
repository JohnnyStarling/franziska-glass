package lab.franziska.glass;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.MediaWire;
import lab.franziska.protocol.MediaDeleteGuard;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** Glass retains originals; the opt-in service advertises and copies stock-camera media. */
public final class PhotoSyncService extends Service {
    static final String START="lab.franziska.glass.PHOTO_START", STOP="lab.franziska.glass.PHOTO_STOP";
    private volatile boolean running;
    private volatile Socket socket;
    private HandlerThread thread;
    private Handler handler;
    private ContentObserver observer;
    private volatile long generation;
    private Runnable scan;
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public synchronized int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null&&(GalleryShareService.SEND.equals(intent.getAction())||GalleryShareService.CANCEL.equals(intent.getAction()))) {
            Intent selected=new Intent(this,GalleryShareService.class).setAction(intent.getAction());
            selected.putExtra("media_id",intent.getLongExtra("media_id",0));
            selected.putExtra("media_size",intent.getLongExtra("media_size",-1));
            selected.putExtra("media_modified",intent.getLongExtra("media_modified",-1));
            startService(selected);if(!running)stopSelf(startId);
            return running?START_STICKY:START_NOT_STICKY;
        }
        if(intent!=null&&STOP.equals(intent.getAction())) {stopSync();return START_NOT_STICKY;}
        boolean restart=intent==null&&getSharedPreferences("photo-sync",MODE_PRIVATE).getBoolean("enabled",false);
        if(!restart&&(intent==null||!START.equals(intent.getAction())))return START_NOT_STICKY;
        if(running||!Provisioning.isComplete(this))return START_NOT_STICKY;
        startForeground(12,notification());
        if(!getSharedPreferences("photo-sync",MODE_PRIVATE).contains("last_id")) {
            try {GlassMediaIndex index=GlassMediaIndex.read(this);
                long latest=index.entries.isEmpty()?0:index.entries.get(index.entries.size()-1).id;
                if(!getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putLong("last_id",latest).commit())
                    throw new IOException("MEDIA_LEDGER_WRITE");
            }catch(Exception unavailable) {stopForeground(true);stopSelf();return START_NOT_STICKY;}
        }
        final long token=++generation;
        running=true;
        getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putBoolean("enabled",true).commit();
        status("Каталог Glass активен");
        thread=new HandlerThread("glass-media-sync");thread.start();handler=new Handler(thread.getLooper());
        scan=new Runnable() {public void run() {
            if(!active(token))return;
            try {exchange(token);}catch(Exception failure) {
                String code=failure.getMessage();
                status(token,"MEDIA_CATALOG_LIMIT".equals(code)?"Каталог переполнен":
                        "MEDIA_LEDGER_WRITE".equals(code)?"Не удалось сохранить прогресс":
                        "Передача не завершена · повторю");
            }
            schedule(token,30000L);
        }};
        observer=new ContentObserver(handler) {@Override public void onChange(boolean selfChange) {schedule(token,1800L);}};
        getContentResolver().registerContentObserver(MediaStore.Files.getContentUri("external"),true,observer);
        schedule(token,500L);return START_STICKY;
    }
    private Notification notification() {
        PendingIntent target=PendingIntent.getActivity(this,12,new Intent(this,PhotoSyncActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Glass → Pixel · медиа").setContentText("Каталог и перенос фото/видео")
                .setContentIntent(target).setOngoing(true).getNotification();
    }
    private boolean active(long token) {return running&&generation==token;}
    private synchronized void schedule(long token,long delay) {
        Handler h=handler;
        if(active(token)&&h!=null&&scan!=null) {h.removeCallbacks(scan);h.postDelayed(scan,delay);}
    }
    private void check(long token) throws IOException {
        if(!active(token))throw new IOException("MEDIA_CANCELLED");
    }
    private synchronized void own(long token,Socket owned) throws IOException {
        if(!active(token))throw new IOException("MEDIA_CANCELLED");
        socket=owned;
    }
    private void exchange(final long token) throws Exception {
        try {exchange(token,2);} catch(LegacyGalleryPeer legacy) {
            check(token);try {exchange(token,1);} catch(LegacyGalleryPeer older) {check(token);exchange(token,0);}
        }
    }
    private static final class LegacyGalleryPeer extends IOException {}
    private void exchange(final long token,int capability) throws Exception {
        final boolean gallery=capability>0;
        if(!Provisioning.isComplete(this)) {synchronized(this) {if(active(token))stopSync();}return;}
        check(token);
        GlassMediaIndex index=GlassMediaIndex.read(this);
        check(token);
        AppIdentityStore.LoadedIdentity identity=AppIdentityStore.loadIdentity(getFilesDir());
        AppIdentityStore.TrustConfig trust=AppIdentityStore.loadTrust(getFilesDir(),false);
        if(trust.port>=65535)throw new IOException("MEDIA_TRUST_INVALID");
        Socket transport=null;
        try {
            SecureTransport secure=PeerRoute.connect(this,trust.address,trust.port+1,identity,trust.peerPin,
                    5000,8000L,new PeerRoute.Owner() {
                        public void check() throws IOException {PhotoSyncService.this.check(token);}
                        public void own(Socket raw) throws IOException {PhotoSyncService.this.own(token,raw);}
                    });
            final Socket owned=secure.socket();
            transport=owned;
            Tls.FrameReader reader=Tls.frameReader(secure.input(),secure.rawCloser(),15000L,5000L);
            write(token,secure,owned,capability==2?MediaWire.deleteHello(index.digest):gallery?MediaWire.galleryHello(index.digest):MediaWire.hello(index.digest));
            MediaWire.Frame plan;
            try {plan=next(token,reader,10000L);} catch(IOException rejected) {
                if(gallery)throw new LegacyGalleryPeer();throw rejected;
            }
            if(plan.type!=MediaWire.PLAN)throw new IOException("MEDIA_PLAN_INVALID");
            if(plan.flags==MediaWire.PLAN_DELETE) {
                if(capability!=2)throw new IOException("MEDIA_DELETE_UNNEGOTIATED");
                deleteSelected(token,secure,owned,reader,trust.peerPin);return;
            }
            if((plan.flags&MediaWire.PLAN_CATALOG)!=0)sendCatalog(token,index,secure,owned,reader);
            if((plan.flags&MediaWire.PLAN_SELECTED)!=0) {
                if(!gallery)throw new IOException("MEDIA_SELECTION_UNNEGOTIATED");
                java.util.ArrayList<GlassMediaIndex.Entry> chosen=new java.util.ArrayList<GlassMediaIndex.Entry>();
                java.util.HashSet<Long> ids=new java.util.HashSet<Long>();
                // Read the complete bounded selection before any source I/O or transfer.
                for(int i=0;i<=128;i++) {
                    MediaWire.Frame request=next(token,reader,10000L);
                    if(request.type==MediaWire.SELECT_END)break;
                    if(i==128||request.type!=MediaWire.ITEM||!ids.add(request.id))throw new IOException("MEDIA_SELECTION_INVALID");
                    GlassMediaIndex.Entry found=null;
                    for(GlassMediaIndex.Entry e:index.entries)if(e.id==request.id
                            &&e.size==request.size&&e.modified==request.modified&&e.kind==request.kind
                            &&e.name.equals(request.name)) {found=e;break;}
                    if(found==null||!found.ready())throw new IOException("MEDIA_SELECTION_CHANGED");
                    chosen.add(found);
                }
                if(chosen.isEmpty())throw new IOException("MEDIA_SELECTION_EMPTY");
                for(GlassMediaIndex.Entry entry:chosen) {
                    // Query again immediately before sending: no arbitrary path or stale ID admission.
                    GlassMediaIndex.Entry fresh=GlassMediaIndex.readEntry(this,entry.id);
                    if(fresh==null||fresh.kind!=entry.kind||fresh.size!=entry.size
                            ||fresh.modified!=entry.modified||!fresh.name.equals(entry.name)
                            ||!fresh.file.equals(entry.file)||!fresh.ready())
                        throw new IOException("MEDIA_SELECTION_CHANGED");
                    sendFile(token,fresh,secure,owned,reader);
                }
                write(token,secure,owned,MediaWire.done(chosen.size(),true));
                if(next(token,reader,10000L).type!=MediaWire.DONE_ACK)throw new IOException("MEDIA_DONE_ACK");
                status(token,"Выбранное скопировано на Pixel");return;
            }
            final boolean all=(plan.flags&MediaWire.PLAN_ALL)!=0;
            final android.content.SharedPreferences prefs=getSharedPreferences("photo-sync",MODE_PRIVATE);
            final PhotoSyncPolicy.Ledger ledger=new PhotoSyncPolicy.Ledger() {
                public long lastId() {return prefs.getLong("last_id",0);}
                public int skippedCount() {return prefs.getInt("oversize_count",0);}
                public boolean commit(long id,int count) {
                    synchronized(PhotoSyncService.this) {
                        return active(token)&&prefs.edit().putLong("last_id",id).putInt("oversize_count",count).commit();
                    }
                }
            };
            PhotoSyncPolicy.Result outcome=PhotoSyncPolicy.process(index.entries,all,
                    ledger.lastId(),new PhotoSyncPolicy.Access<GlassMediaIndex.Entry>() {
                public long id(GlassMediaIndex.Entry entry) {return entry.id;}
                public boolean automatic(GlassMediaIndex.Entry entry,long lastId) {
                    return entry.kind==MediaWire.PHOTO&&entry.stockCamera&&entry.id>lastId;
                }
                public boolean ready(GlassMediaIndex.Entry entry) {return entry.ready();}
                public boolean oversized(GlassMediaIndex.Entry entry) {
                    return entry.size>(entry.kind==MediaWire.PHOTO?MediaWire.MAX_PHOTO:MediaWire.MAX_VIDEO);
                }
            },new PhotoSyncPolicy.Guard() {
                public void check() throws Exception {
                    PhotoSyncService.this.check(token);
                    if(!Provisioning.isComplete(PhotoSyncService.this))throw new IOException("MEDIA_CANCELLED");
                }
            },new PhotoSyncPolicy.Sender<GlassMediaIndex.Entry>() {
                public void send(GlassMediaIndex.Entry entry) throws Exception {
                    status(token,entry.kind==MediaWire.PHOTO?"Передаёт фото на Pixel":"Передаёт видео на Pixel");
                    sendFile(token,entry,secure,owned,reader);
                }
            },ledger);
            write(token,secure,owned,MediaWire.done(outcome.transferred,outcome.complete));
            if(next(token,reader,10000L).type!=MediaWire.DONE_ACK)throw new IOException("MEDIA_DONE_ACK");
            check(token);
            PhotoSyncPolicy.confirmedCopyAll(all,outcome,ledger);
            status(token,all?(outcome.complete?"Архив скопирован на Pixel":"Часть архива не перенесена"):
                    prefs.getInt("oversize_count",0)>0?"Фото >32 МБ не перенесено":
                    outcome.waitingForReady?"Фото готовится · повторю":
                    outcome.transferred>0?"Новые фото на Pixel":"Каталог Glass доступен");
        }finally {close(transport);synchronized(this) {if(socket==transport)socket=null;}}
    }
    private void deleteSelected(final long token,final SecureTransport secure,final Socket raw,
                                final Tls.FrameReader reader,final byte[] pin) throws Exception {
        final long expires=SystemClock.elapsedRealtime()+120000L;
        final MediaDeleteGuard.Guard guard=new MediaDeleteGuard.Guard() {
            public void check() throws Exception {
                PhotoSyncService.this.check(token);
                if(raw.isClosed()||SystemClock.elapsedRealtime()>=expires||!Provisioning.isComplete(PhotoSyncService.this)
                        ||!MessageDigest.isEqual(pin,AppIdentityStore.loadTrust(getFilesDir(),false).peerPin))
                    throw new IOException("MEDIA_DELETE_CANCELLED");
            }
        };
        final java.util.ArrayList<MediaWire.Frame> requests=new java.util.ArrayList<MediaWire.Frame>();
        for(int i=0;i<=128;i++) {
            guard.check();MediaWire.Frame item=next(token,reader,Math.min(10000L,expires-SystemClock.elapsedRealtime()));
            if(item.type==MediaWire.SELECT_END)break;
            if(i==128||item.type!=MediaWire.DELETE_ITEM)throw new IOException("MEDIA_DELETE_SELECTION");
            requests.add(item);
        }
        final int[] confirmed={0};
        try {
            MediaDeleteGuard.execute(requests,new GlassDeleteIndex(this),guard,this,new MediaDeleteGuard.Results() {
                public void item(long id,boolean success) throws Exception {
                    guard.check();write(token,secure,raw,MediaWire.deleteResult(id,success));
                    if(success)confirmed[0]++;
                    // Require a live Pixel acknowledgement before admitting the next deletion.
                    MediaWire.Frame ack=next(token,reader,Math.min(10000L,expires-SystemClock.elapsedRealtime()));
                    byte[] expected=null;for(MediaWire.Frame r:requests)if(r.id==id)expected=r.hash;
                    if(ack.type!=MediaWire.ACK||ack.id!=id||!MediaWire.sameHash(ack.hash,expected))
                        throw new IOException("MEDIA_DELETE_ACK");
                    guard.check();
                }
            });
        }catch(Exception invalid) {
            guard.check(); // Cancellation/trust loss must not emit a success receipt.
        }
        guard.check();boolean complete=!requests.isEmpty()&&confirmed[0]==requests.size();
        write(token,secure,raw,MediaWire.done(confirmed[0],complete));
        if(next(token,reader,Math.min(10000L,expires-SystemClock.elapsedRealtime())).type!=MediaWire.DONE_ACK)
            throw new IOException("MEDIA_DONE_ACK");
        status(token,complete?"Выбранные оригиналы удалены":"Удаление не завершено · проверьте каталог");
    }
    private void sendCatalog(long token,GlassMediaIndex index,SecureTransport secure,Socket raw,Tls.FrameReader reader)
            throws Exception {
        long expires=SystemClock.elapsedRealtime()+600000L;
        write(token,secure,raw,MediaWire.catalogBegin(index.entries.size()));
        for(GlassMediaIndex.Entry entry:index.entries) {
            check(token);
            if(SystemClock.elapsedRealtime()>expires)throw new IOException("MEDIA_CATALOG_TIMEOUT");
            write(token,secure,raw,MediaWire.item(entry.id,entry.kind,entry.size,entry.modified,
                    entry.name,GlassMediaIndex.preview(entry)));
        }
        write(token,secure,raw,MediaWire.catalogEnd(index.digest));
        MediaWire.Frame ack=next(token,reader,15000L);
        if(ack.type!=MediaWire.CATALOG_ACK||!MediaWire.sameHash(ack.hash,index.digest))
            throw new IOException("MEDIA_CATALOG_ACK");
    }
    private void sendFile(long token,GlassMediaIndex.Entry entry,SecureTransport secure,Socket raw,
                          Tls.FrameReader reader) throws Exception {
        long expires=SystemClock.elapsedRealtime()+3600000L;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[MediaWire.CHUNK];long count=0;
        FileInputStream input=new FileInputStream(entry.file);
        try {int n;while((n=input.read(buffer))!=-1) {
            check(token);
            if(SystemClock.elapsedRealtime()>expires||count+n>entry.size)
                throw new IOException("MEDIA_HASH_TIMEOUT");
            digest.update(buffer,0,n);count+=n;
        }}finally {input.close();}
        if(count!=entry.size||!entry.ready())throw new IOException("MEDIA_CHANGED");
        byte[] hash=digest.digest();
        write(token,secure,raw,MediaWire.fileBegin(entry.id,entry.kind,entry.size,entry.modified,entry.name,hash));
        input=new FileInputStream(entry.file);
        try {long offset=0;int n;while((n=input.read(buffer))!=-1) {
            check(token);
            if(!Provisioning.isComplete(this)||SystemClock.elapsedRealtime()>expires
                    ||offset+n>entry.size)throw new IOException("MEDIA_TRANSFER_TIMEOUT");
            write(token,secure,raw,MediaWire.data(entry.id,offset,buffer,n));offset+=n;
        }
            if(offset!=entry.size||entry.file.length()!=entry.size)throw new IOException("MEDIA_CHANGED");
        }finally {input.close();}
        write(token,secure,raw,MediaWire.commit(entry.id,hash));
        MediaWire.Frame ack=next(token,reader,15000L);
        if(ack.type!=MediaWire.ACK||ack.id!=entry.id||!MediaWire.sameHash(ack.hash,hash))
            throw new IOException("MEDIA_FILE_ACK");
    }
    private MediaWire.Frame next(long token,Tls.FrameReader reader,long waitMs) throws Exception {
        long expires=SystemClock.elapsedRealtime()+waitMs;
        while(SystemClock.elapsedRealtime()<expires) {
            check(token);
            try {byte[] frame=reader.read();check(token);
                if(SystemClock.elapsedRealtime()>=expires)throw new IOException("MEDIA_RESPONSE_TIMEOUT");
                return MediaWire.parse(frame);
            }
            catch(Tls.IdleTimeoutException idle) {continue;}
        }
        throw new IOException("MEDIA_RESPONSE_TIMEOUT");
    }
    private void write(long token,SecureTransport secure,Socket raw,byte[] frame) throws Exception {
        check(token);
        Tls.write(secure.output(),frame,raw,5000L,"glass-media-write");
        check(token);
    }
    private synchronized void status(long token,String value) {if(active(token))status(value);}
    private void status(String value) {getSharedPreferences("photo-sync",MODE_PRIVATE).edit()
            .putString("status",value).apply();}
    private synchronized void stopSync() {
        getSharedPreferences("photo-sync",MODE_PRIVATE).edit().putBoolean("enabled",false).commit();
        cleanup();status("Выключено");stopForeground(true);stopSelf();
    }
    private synchronized void cleanup() {
        running=false;++generation;
        if(observer!=null) {getContentResolver().unregisterContentObserver(observer);observer=null;}
        Handler h=handler;handler=null;if(h!=null&&scan!=null)h.removeCallbacks(scan);scan=null;
        if(thread!=null) {thread.quit();thread=null;}
        Socket active=socket;socket=null;close(active);
    }
    private static void close(Socket s) {if(s!=null)try{s.close();}catch(IOException ignored){}}
    @Override public void onDestroy() {cleanup();super.onDestroy();}
}
