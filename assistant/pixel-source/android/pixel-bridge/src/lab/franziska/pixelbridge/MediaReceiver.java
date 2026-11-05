package lab.franziska.pixelbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.MessageDigest;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.MediaWire;
import lab.franziska.protocol.MediaDeleteGuard;
import lab.franziska.protocol.MediaSelectionPolicy;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** One mutually authenticated catalog/copy session. Publication precedes every file ACK. */
final class MediaReceiver {
    private final PhotoSyncService service;
    private final long epoch;
    private long operationEnd;
    MediaReceiver(PhotoSyncService service,long epoch) {this.service=service;this.epoch=epoch;}
    void receive(Socket raw,AppIdentityStore.LoadedIdentity identity,byte[] pin) throws Exception {
        checkCurrent();
        operationEnd=SystemClock.elapsedRealtime()+24L*3600000L;
        raw.setSoTimeout(1000);
        Tls.DeadlineWatchdog watchdog=Tls.closeAtDeadline(raw,8000L,"media-pixel-handshake");
        SecureTransport secure;
        try {secure=SecureTransport.acceptOwned(raw,identity,pin);}finally {watchdog.cancel();}
        Tls.FrameReader reader=Tls.frameReader(secure.input(),secure.rawCloser(),15000L,5000L);
        GlassMediaCatalog catalog=new GlassMediaCatalog(service);
        SharedPreferences prefs=service.getSharedPreferences("photo-sync",Context.MODE_PRIVATE);
        File temp=null;FileOutputStream output=null;
        try {
            MediaWire.Frame hello=read(reader,SystemClock.elapsedRealtime()+10000L);
            if(hello.type!=MediaWire.DELETE_HELLO&&hello.type!=MediaWire.HELLO&&hello.type!=MediaWire.GALLERY_HELLO&&hello.type!=MediaWire.SHARE_HELLO)throw new IOException("MEDIA_HELLO_INVALID");
            final boolean sharing=hello.type==MediaWire.SHARE_HELLO;
            if(!service.claimSession(epoch,raw,sharing))throw new IOException("MEDIA_SESSION_BUSY");
            final GalleryDeleteRequest deletion=service.takeDeletion(epoch,hello.type);
            if(deletion!=null) {deleteSelected(deletion,secure,raw,reader,pin,prefs);return;}
            if(!sharing&&service.hasDeletion()&&!MediaWire.canDelete(hello.type))
                service.deleteStatusCurrent(epoch,"Для удаления обновите приложение Glass; запрос не отправлен");
            if(sharing)service.shareStatus("Готовим выбранный оригинал");
            else service.status("Glass на связи · проверяем медиа");
            if(sharing)operationEnd=SystemClock.elapsedRealtime()+3600000L;
            final MediaSelectionPolicy selection=new MediaSelectionPolicy(sharing?hello.id:0);
            String revision=PhotoAlbum.hex(hello.hash);
            boolean refresh=prefs.getBoolean("refresh",false);
            boolean catalogNeeded=!sharing&&(refresh||!revision.equals(prefs.getString("catalog_digest","")));
            final java.util.ArrayList<GlassMediaCatalog.Item> chosen=!sharing&&(hello.type==MediaWire.GALLERY_HELLO||hello.type==MediaWire.DELETE_HELLO)?service.selected(epoch):null;
            if(chosen!=null)operationEnd=Math.min(operationEnd,SystemClock.elapsedRealtime()+3600000L);
            if(!sharing&&hello.type==MediaWire.HELLO&&service.hasSelection())service.status("Для выбора на Pixel обновите приложение Glass");
            boolean all=!sharing&&chosen==null&&prefs.getBoolean("import_all",false);
            byte flags=(byte)((catalogNeeded?MediaWire.PLAN_CATALOG:0)|(all?MediaWire.PLAN_ALL:0)|(chosen!=null?MediaWire.PLAN_SELECTED:0));
            write(secure,raw,MediaWire.plan(flags));
            if(catalogNeeded) {
                MediaWire.Frame start=read(reader,SystemClock.elapsedRealtime()+15000L);
                if(start.type!=MediaWire.CATALOG_BEGIN)throw new IOException("MEDIA_CATALOG_BEGIN");
                catalog.begin(start.count);
                long expires=SystemClock.elapsedRealtime()+600000L;
                for(int i=0;i<start.count;i++) {
                    MediaWire.Frame item=read(reader,expires);
                    if(item.type!=MediaWire.ITEM)throw new IOException("MEDIA_CATALOG_ITEM");
                    catalog.add(item);
                }
                MediaWire.Frame end=read(reader,expires);
                if(end.type!=MediaWire.CATALOG_END||!MediaWire.sameHash(end.hash,hello.hash))
                    throw new IOException("MEDIA_CATALOG_DIGEST");
                catalog.finish();
                if(!prefs.edit().putString("catalog_digest",revision)
                        .putLong("catalog_at",System.currentTimeMillis())
                        .putBoolean("refresh",false).commit())throw new IOException("MEDIA_CATALOG_RECEIPT");
                write(secure,raw,MediaWire.catalogAck(hello.hash));
                service.status("Каталог Glass обновлён");
            }
            if(chosen!=null) {
                for(GlassMediaCatalog.Item item:chosen)write(secure,raw,MediaWire.item(item.id,item.kind,item.size,item.modified,item.name,null));
                write(secure,raw,MediaWire.selectEnd());
            }
            int received=0;
            long sessionEnd=operationEnd;
            while(service.isCurrent(epoch)&&Provisioning.isComplete(service)) {
                MediaWire.Frame begin=read(reader,sessionEnd);
                if(begin.type==MediaWire.DONE) {
                    selection.done(begin);
                    if(chosen!=null&&(begin.flags!=1||received!=chosen.size()))throw new IOException("MEDIA_SELECTION_INCOMPLETE");
                    if(begin.count!=received)throw new IOException("MEDIA_DONE_COUNT");
                    if(!sharing) {
                        SharedPreferences.Editor receipt=prefs.edit();
                        receipt.putLong("checked_at",System.currentTimeMillis());
                        if(all&&begin.flags==1&&prefs.getBoolean("import_all",false))
                            receipt.putBoolean("import_all",false);
                        if(!all||begin.flags==1)receipt.putBoolean("auto_incomplete",begin.flags!=1);
                        if(!receipt.commit())throw new IOException("MEDIA_CHECK_RECEIPT");
                    }
                    write(secure,raw,MediaWire.doneAck());
                    if(chosen!=null){service.selectedDone(epoch);service.status("Выбранное скопировано на Pixel");}
                    else if(sharing)service.shareStatus("Оригинал скопирован на Pixel · проверьте список");
                    else service.status(all?(begin.flags==1?"Архив скопирован":"Часть архива не перенесена"):
                            begin.flags==1?"Каталог Glass доступен":"Не все новые фото перенесены");
                    return;
                }
                if(begin.type!=MediaWire.FILE_BEGIN)throw new IOException("MEDIA_FILE_BEGIN");
                selection.begin(begin);
                if(chosen!=null&&(received>=chosen.size()||!GalleryMediaTypes.matches(chosen.get(received),begin)))
                    throw new IOException("MEDIA_SELECTION_MISMATCH");
                if(begin.size>service.getCacheDir().getUsableSpace()-64L*1024*1024)
                    throw new IOException("MEDIA_STORAGE_LOW");
                temp=File.createTempFile("glass-media-",".part",service.getCacheDir());
                output=new FileOutputStream(temp);MessageDigest digest=MessageDigest.getInstance("SHA-256");
                long written=0;long expires=SystemClock.elapsedRealtime()+3600000L;
                while(true) {
                    MediaWire.Frame frame=read(reader,expires);
                    if(!service.isCurrent(epoch))throw new IOException("MEDIA_CANCELLED");
                    if(frame.type==MediaWire.DATA&&frame.id==begin.id&&frame.offset==written
                            &&written+frame.data.length<=begin.size) {
                        output.write(frame.data);digest.update(frame.data);written+=frame.data.length;
                    } else if(frame.type==MediaWire.COMMIT&&frame.id==begin.id
                            &&written==begin.size&&MediaWire.sameHash(frame.hash,begin.hash))break;
                    else throw new IOException("MEDIA_FILE_SEQUENCE");
                }
                output.getFD().sync();output.close();output=null;
                if(!MediaWire.sameHash(digest.digest(),begin.hash))throw new IOException("MEDIA_HASH_MISMATCH");
                if(!service.isCurrent(epoch)||!Provisioning.isComplete(service)||!MessageDigest.isEqual(pin,
                        AppIdentityStore.loadTrust(service.getFilesDir(),true).peerPin))
                    throw new IOException("MEDIA_TRUST_REVOKED");
                new MediaAlbum(service).publish(begin,temp,new MediaAlbum.Cancellation() {
                    public boolean current() {return service.isCurrent(epoch)&&SystemClock.elapsedRealtime()<operationEnd;}
                    public Object publicationLock() {return service;}
                    public void beforePublish() throws IOException {
                        try {
                            checkCurrent();
                            if(!service.isCurrent(epoch)||!Provisioning.isComplete(service)
                                    ||!MessageDigest.isEqual(pin,
                                            AppIdentityStore.loadTrust(service.getFilesDir(),true).peerPin))
                                throw new IOException("MEDIA_TRUST_REVOKED");
                        } catch(IOException invalid) {throw invalid;}
                        catch(Exception invalid) {throw new IOException("MEDIA_TRUST_REVOKED",invalid);}
                    }
                });
                if(sharing||chosen!=null){
                    OriginalShareStore.Prepared prepared=OriginalShareStore.prepare(service,temp,begin.name,begin.size,begin.hash);
                    boolean published=false;
                    try{
                        // STOP/CANCEL/revoke and draft publication share the service monitor.
                        synchronized(service){synchronized(Provisioning.MEDIA_PUBLICATION_LOCK){
                            checkCurrent();
                            if(!service.isCurrent(epoch)||!Provisioning.isComplete(service)
                                    ||!MessageDigest.isEqual(pin,AppIdentityStore.loadTrust(service.getFilesDir(),true).peerPin))
                                throw new IOException("MEDIA_TRUST_REVOKED");
                            String originalToken=OriginalShareStore.commit(service,prepared);published=true;
                            GalleryLocalStore.remember(service,begin,originalToken);
                        }}
                    }finally{if(!published)OriginalShareStore.discard(service,prepared);}
                }
                selection.committed();
                write(secure,raw,MediaWire.ack(begin.id,begin.hash));
                temp.delete();temp=null;received++;
                if(sharing)service.shareStatus("Оригинал получен · проверяем сохранение");
                else service.status("Скопировано медиа: "+received);
            }
            throw new IOException("MEDIA_CANCELLED");
        } finally {
            catalog.abort();catalog.close();
            if(output!=null)try{output.close();}catch(IOException ignored){}
            if(temp!=null)temp.delete();
            try{raw.close();}catch(IOException ignored){}
        }
    }
    private void deleteSelected(final GalleryDeleteRequest request,SecureTransport secure,Socket raw,
                                Tls.FrameReader reader,final byte[] pin,SharedPreferences prefs) throws Exception {
        operationEnd=request.expires;
        final MediaDeleteGuard.Guard guard=new MediaDeleteGuard.Guard() {
            public void check() throws Exception {
                checkCurrent();
                if(!MessageDigest.isEqual(pin,AppIdentityStore.loadTrust(service.getFilesDir(),true).peerPin))
                    throw new IOException("MEDIA_TRUST_REVOKED");
            }
        };
        int confirmed=0,results=0;StringBuilder receipts=new StringBuilder();boolean complete=false;
        try {
            request.verify(service,guard);guard.check();
            service.deleteStatusCurrent(epoch,"Удаление отправляется · без автоматического повтора");
            write(secure,raw,MediaWire.plan(MediaWire.PLAN_DELETE));
            for(MediaWire.Frame frame:request.frames) {
                guard.check();write(secure,raw,MediaWire.deleteItem(frame.id,frame.kind,frame.size,frame.modified,frame.name,frame.hash));
            }
            guard.check();write(secure,raw,MediaWire.selectEnd());
            while(true) {
                guard.check();MediaWire.Frame result=read(reader,operationEnd);
                if(result.type==MediaWire.DONE) {
                    if(result.count!=confirmed||(result.flags==1&&confirmed!=request.frames.size()))
                        throw new IOException("MEDIA_DELETE_RECEIPT_INVALID");
                    complete=result.flags==1;
                    write(secure,raw,MediaWire.doneAck());break;
                }
                if(result.type!=MediaWire.DELETE_RESULT||results>=request.frames.size()
                        ||result.id!=request.frames.get(results).id)throw new IOException("MEDIA_DELETE_RECEIPT_INVALID");
                receipts.append(result.id).append(result.flags==1?":deleted\n":":incomplete\n");
                results++;if(result.flags==1)confirmed++;
                synchronized(service) {if(service.isCurrent(epoch))prefs.edit().putString("delete_results",receipts.toString()).commit();}
                guard.check();write(secure,raw,MediaWire.ack(result.id,request.frames.get(results-1).hash));
            }
        }finally {
            synchronized(service) {if(service.isCurrent(epoch)) {
                prefs.edit().putBoolean("refresh",true).putString("delete_results",receipts.toString()).commit();
                service.deletionFinished(epoch);
                service.deleteStatus(complete?"Удалено на Glass: "+confirmed+" · копии Pixel сохранены":
                        "Удаление не завершено; подтверждено: "+confirmed+" из "+request.frames.size()+" · обновите каталог, повтора нет");
            }}
        }
    }
    private void checkCurrent() throws IOException {
        if(!service.isCurrent(epoch)||!Provisioning.isComplete(service))throw new IOException("MEDIA_CANCELLED");
        if(operationEnd>0&&SystemClock.elapsedRealtime()>=operationEnd)throw new IOException("MEDIA_READ_TIMEOUT");
    }
    private MediaWire.Frame read(Tls.FrameReader reader,long expires) throws Exception {
        expires=Math.min(expires,operationEnd);
        while(SystemClock.elapsedRealtime()<expires) {
            checkCurrent();
            try{
                byte[] bytes=reader.read();checkCurrent();
                if(SystemClock.elapsedRealtime()>=expires)throw new IOException("MEDIA_READ_TIMEOUT");
                return MediaWire.parse(bytes);
            }catch(Tls.IdleTimeoutException idle){continue;}
        }
        throw new IOException("MEDIA_READ_TIMEOUT");
    }
    private void write(SecureTransport secure,Socket raw,byte[] frame) throws Exception {
        checkCurrent();long remaining=operationEnd-SystemClock.elapsedRealtime();
        if(remaining<=0)throw new IOException("MEDIA_WRITE_TIMEOUT");
        Tls.write(secure.output(),frame,raw,Math.min(5000L,remaining),"pixel-media-write");checkCurrent();
    }
}
