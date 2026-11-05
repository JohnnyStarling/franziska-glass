package lab.franziska.glass;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Arrays;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.MediaSelectionPolicy;
import lab.franziska.protocol.MediaWire;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** Owner-selected single original. Never toggles background sync or sends to a Telegram recipient. */
public final class GalleryShareService extends Service {
    static final String SEND="lab.franziska.glass.MEDIA_SHARE_ORIGINAL";
    static final String CANCEL="lab.franziska.glass.MEDIA_SHARE_CANCEL";
    private long generation,mediaId,expires;
    private boolean active;
    private int latestStartId;
    private Socket socket;
    private static final long MAX_OPERATION_MS=3600000L;
    // Cancellation revokes authority immediately, but a catalog/provider/file call can
    // still be blocked. Retain the process-wide slot until that worker actually exits,
    // including across Service recreation. There is deliberately no pending queue.
    private static final Object WORKER_LOCK=new Object();
    private static Object workerOwner;
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public synchronized int onStartCommand(Intent intent,int flags,final int startId){
        latestStartId=startId;
        if(intent==null)return START_NOT_STICKY;
        long id=intent.getLongExtra("media_id",0);
        if(CANCEL.equals(intent.getAction())){
            if(active&&id==mediaId)revoke("Передача отменена");
            if(!active){stopForeground(true);stopSelf(startId);}return START_NOT_STICKY;
        }
        if(!SEND.equals(intent.getAction()))return START_NOT_STICKY;
        if(active){status("Передача уже идёт · отмените её перед новой",true);return START_NOT_STICKY;}
        final long size=intent.getLongExtra("media_size",-1),modified=intent.getLongExtra("media_modified",-1);
        if(id<=0||size<4||modified<0){
            mediaId=id;status("Не удалось выбрать оригинал",false);stopSelf(startId);return START_NOT_STICKY;
        }
        final Object worker=claimWorker();
        if(worker==null){
            // A rejected start must not acquire pending ownership or change the last
            // accepted request's media id/pending state, even in a new Service instance.
            getSharedPreferences("photo-sync",MODE_PRIVATE).edit()
                    .putString("share_status","Предыдущая передача ещё завершается · повторите отправку")
                    .putLong("share_updated_at",System.currentTimeMillis()).apply();
            stopSelf(startId);return START_NOT_STICKY;
        }
        mediaId=id;active=true;expires=SystemClock.elapsedRealtime()+MAX_OPERATION_MS;
        final long token=++generation,selected=id;
        boolean started=false;
        try{
        PendingIntent open=PendingIntent.getActivity(this,13,new Intent(this,GalleryActivity.class),PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(13,new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle("Оригинал → Pixel").setContentText("Получатель выбирается на Pixel")
                .setContentIntent(open).setOngoing(true).getNotification());
        status("Передаю оригинал на Pixel…",true);
        new Thread(new Runnable(){public void run(){
            String result="Передача не завершена · включите медиа на Pixel";
            try{
                try{transfer(token,selected,size,modified);result="Оригинал на Pixel · выберите чат в Telegram";}catch(Exception failure){
                    if("MEDIA_SELECTION_STALE".equals(failure.getMessage()))result="Файл изменился · выберите его заново";
                    else if("MEDIA_SELECTION_MISSING".equals(failure.getMessage()))result="Файл больше недоступен";
                }
            }finally{try{finish(token,startId,result);}finally{releaseWorker(worker);}}
        }},"glass-selected-original").start();
        started=true;
        }finally{if(!started){try{finish(token,startId,"Передача не завершена");}finally{releaseWorker(worker);}}}
        return START_NOT_STICKY;
    }
    private static Object claimWorker(){synchronized(WORKER_LOCK){
        if(workerOwner!=null)return null;workerOwner=new Object();return workerOwner;
    }}
    private static void releaseWorker(Object owner){synchronized(WORKER_LOCK){
        if(workerOwner==owner)workerOwner=null;
    }}
    private synchronized void own(long token,Socket raw) throws IOException{
        guard(token);socket=raw;
    }
    private synchronized void guard(long token) throws IOException{
        if(!active||generation!=token||SystemClock.elapsedRealtime()>=expires)
            throw new IOException("MEDIA_CANCELLED");
    }
    private void check(long token) throws IOException{
        guard(token);
        boolean complete=Provisioning.isComplete(this);
        guard(token); // Stop can revoke while private-file validation is blocked.
        if(!complete)throw new IOException("MEDIA_CANCELLED");
    }
    private void transfer(final long token,long selected,long size,long modified) throws Exception{
        check(token);
        GlassMediaIndex index=GlassMediaIndex.read(this);GlassMediaIndex.Entry chosen=null;
        check(token);
        for(GlassMediaIndex.Entry e:index.entries)if(e.id==selected){chosen=e;break;}
        if(chosen==null)throw new IOException("MEDIA_SELECTION_MISSING");
        MediaSelectionPolicy.snapshot(selected,size,modified,chosen.id,chosen.size,chosen.modified);
        if(!chosen.ready()||chosen.file.lastModified()/1000L!=chosen.modified||chosen.size>(chosen.kind==MediaWire.PHOTO?MediaWire.MAX_PHOTO:MediaWire.MAX_VIDEO))
            throw new IOException("MEDIA_SELECTION_STALE");
        check(token);
        AppIdentityStore.LoadedIdentity identity=AppIdentityStore.loadIdentity(getFilesDir());
        guard(token);
        AppIdentityStore.TrustConfig trust=AppIdentityStore.loadTrust(getFilesDir(),false);
        final byte[] pin=trust.peerPin;
        if(trust.port>=65535)throw new IOException("MEDIA_TRUST_INVALID");
        check(token);
        Socket raw=null;
        try{
            SecureTransport secure=PeerRoute.connect(this,trust.address,trust.port+1,identity,pin,
                    5000,8000L,new PeerRoute.Owner() {
                        public void check() throws IOException {GalleryShareService.this.check(token);}
                        public void own(Socket owned) throws IOException {GalleryShareService.this.own(token,owned);}
                    });
            raw=secure.socket();
            Tls.FrameReader reader=Tls.frameReader(secure.input(),secure.rawCloser(),15000L,5000L);
            write(token,secure,raw,MediaWire.shareHello(index.digest,chosen.id));
            MediaWire.Frame plan=next(token,reader,10000L);
            if(plan.type!=MediaWire.PLAN)throw new IOException("MEDIA_SHARE_PLAN_INVALID");
            MediaSelectionPolicy.singlePlan(plan.flags); // A selected-file request cannot become copy-all/catalog.
            sendFile(token,chosen,secure,raw,reader,pin);
            write(token,secure,raw,MediaWire.done(1,true));
            if(next(token,reader,10000L).type!=MediaWire.DONE_ACK)throw new IOException("MEDIA_DONE_ACK");
        }finally{close(raw);synchronized(this){if(socket==raw)socket=null;}}
    }
    private void sendFile(long token,GlassMediaIndex.Entry e,SecureTransport secure,Socket raw,
                          Tls.FrameReader reader,byte[] pin) throws Exception{
        byte[] buffer=new byte[MediaWire.CHUNK];
        final long fileModified=e.file.lastModified();
        if(fileModified/1000L!=e.modified)throw new IOException("MEDIA_SELECTION_STALE");
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            FileInputStream input=new FileInputStream(e.file);
            try{int n;while((n=input.read(buffer))!=-1){check(token);count+=n;if(count>e.size)throw new IOException("MEDIA_SELECTION_STALE");digest.update(buffer,0,n);}}
            finally{input.close();}
            if(count!=e.size||!e.ready()||e.file.lastModified()!=fileModified)throw new IOException("MEDIA_SELECTION_STALE");
            byte[] hash=digest.digest();
            write(token,secure,raw,MediaWire.fileBegin(e.id,e.kind,e.size,e.modified,e.name,hash));
            input=new FileInputStream(e.file);
            try{long offset=0;int n;while((n=input.read(buffer))!=-1){
                check(token);if(offset+n>e.size)throw new IOException("MEDIA_SELECTION_STALE");
                write(token,secure,raw,MediaWire.data(e.id,offset,buffer,n));offset+=n;
            }if(offset!=e.size||!e.ready()||e.file.lastModified()!=fileModified)throw new IOException("MEDIA_SELECTION_STALE");}
            finally{input.close();}
            check(token);
            if(!MessageDigest.isEqual(pin,AppIdentityStore.loadTrust(getFilesDir(),false).peerPin))
                throw new IOException("MEDIA_TRUST_REVOKED");
            write(token,secure,raw,MediaWire.commit(e.id,hash));
            // Pixel streams the verified original into its album and verifies private staging.
            // Finalization stays inside the same absolute one-hour operation, not a 15-s chunk budget.
            MediaWire.Frame ack=next(token,reader,MAX_OPERATION_MS);
            if(ack.type!=MediaWire.ACK||ack.id!=e.id||!MediaWire.sameHash(ack.hash,hash))throw new IOException("MEDIA_FILE_ACK");
        }finally{Arrays.fill(buffer,(byte)0);}
    }
    private void write(long token,SecureTransport secure,Socket raw,byte[] bytes) throws Exception{
        check(token);long remaining; synchronized(this){remaining=expires-SystemClock.elapsedRealtime();}
        if(remaining<=0)throw new IOException("MEDIA_CANCELLED");
        try{Tls.write(secure.output(),bytes,raw,Math.min(5000L,remaining),"share-glass-write");check(token);}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    private MediaWire.Frame next(long token,Tls.FrameReader reader,long limit) throws Exception{
        long until; synchronized(this){until=Math.min(expires,SystemClock.elapsedRealtime()+limit);}
        while(SystemClock.elapsedRealtime()<until){check(token);try{byte[] frame=reader.read();check(token);
                if(SystemClock.elapsedRealtime()>=until)throw new IOException("MEDIA_RESPONSE_TIMEOUT");
                return MediaWire.parse(frame);}
            catch(Tls.IdleTimeoutException idle){}}
        throw new IOException("MEDIA_RESPONSE_TIMEOUT");
    }
    private synchronized void finish(long token,int startId,String result){
        if(generation!=token||!active)return;active=false;generation++;close(socket);socket=null;
        status(result,false);stopForeground(true);stopSelf(latestStartId);
    }
    private void status(String value,boolean pending){getSharedPreferences("photo-sync",MODE_PRIVATE).edit()
            .putString("share_status",value).putLong("share_media_id",mediaId)
            .putBoolean("share_pending",pending).putLong("share_updated_at",System.currentTimeMillis()).apply();}
    private synchronized void revoke(String message){
        boolean was=active;active=false;generation++;close(socket);socket=null;if(was)status(message,false);
    }
    private static void close(Socket raw){if(raw!=null)try{raw.close();}catch(IOException ignored){}}
    @Override public synchronized void onDestroy(){revoke("Передача остановлена");super.onDestroy();}
}
