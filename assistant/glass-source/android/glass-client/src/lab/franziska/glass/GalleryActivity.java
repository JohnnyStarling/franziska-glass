package lab.franziska.glass;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.VideoView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Foreground-only Glass gallery. All media sources are fresh local MediaStore rows. */
public final class GalleryActivity extends Activity {
    static final String SHARE_ORIGINAL="lab.franziska.glass.MEDIA_SHARE_ORIGINAL";
    private final Handler ui=new Handler();
    // A rapid swipe may replace the queued preview, but never creates another native decoder.
    private final ThreadPoolExecutor previews=new ThreadPoolExecutor(0,1,5L,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1),new ThreadFactory() {
                public Thread newThread(Runnable task) {return new Thread(task,"glass-gallery-preview");}
            },new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor indexes=new ThreadPoolExecutor(0,1,5L,TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(1),new ThreadFactory() {
                public Thread newThread(Runnable task) {return new Thread(task,"glass-gallery-index");}
            },new ThreadPoolExecutor.DiscardOldestPolicy());
    private final GalleryFlow flow=new GalleryFlow();
    private final ChecklistInput keyAndDedupe=new ChecklistInput();
    private final List<GlassMediaIndex.Entry> entries=new ArrayList<GlassMediaIndex.Entry>();
    private GalleryView galleryView;
    private VideoView videoView;
    private TapGesture touch, generic;
    private volatile boolean resumed, focused, destroyed;
    private boolean loading, shareBusy, videoVisible;
    private int requestedIndexRevision, shareRevision, videoRevision;
    private volatile int indexRevision, previewRevision;
    private Bitmap preview;
    private String note="";
    private SharedPreferences sharePrefs;
    private String shareStatus="";
    private long shareMediaId, shareUpdatedAt;
    private long awaitingStatusAfter;
    private boolean sharePending;
    private final SharedPreferences.OnSharedPreferenceChangeListener shareListener=
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences prefs,String key) {
            if (key!=null && key.startsWith("share_")) ui.post(new Runnable() {
                public void run() {if (resumed && !destroyed) readShareStatus();}
            });
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Deliberately never consume getIntent().getData()/extras as a media source.
        touch=new TapGesture(ViewConfiguration.get(this).getScaledTouchSlop(),true);
        generic=new TapGesture(ViewConfiguration.get(this).getScaledTouchSlop(),true);
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        galleryView=new GalleryView();
        root.addView(galleryView,new FrameLayout.LayoutParams(-1,-1));
        videoView=new VideoView(this);
        videoView.setVisibility(View.GONE);
        root.addView(videoView,new FrameLayout.LayoutParams(1,1));
        root.setFocusable(true);root.setFocusableInTouchMode(true);
        setContentView(root);
        root.requestFocus();
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            public void onPrepared(MediaPlayer player) {
                if (!foreground() || flow.mode()!=GalleryFlow.PLAYING || !videoVisible)
                    stopVideo();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            public void onCompletion(MediaPlayer player) {
                if (!videoVisible) return;
                stopVideo();flow.stop();note="Видео завершено";render();
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer player,int what,int extra) {
                if (!videoVisible) return true;
                stopVideo();flow.stop();note="Видео недоступно";render();return true;
            }
        });
    }
    private boolean foreground() { return resumed && focused && !destroyed; }
    @Override protected void onResume() {
        super.onResume();resumed=true;
        sharePrefs=getSharedPreferences("photo-sync",MODE_PRIVATE);
        sharePrefs.registerOnSharedPreferenceChangeListener(shareListener);
        readShareStatus();reload();
    }
    @Override protected void onPause() {
        if (sharePrefs!=null) sharePrefs.unregisterOnSharedPreferenceChangeListener(shareListener);
        resumed=false;revokeInputAndWork();stopVideo();super.onPause();
    }
    @Override protected void onStop() {
        resumed=false;revokeInputAndWork();stopVideo();super.onStop();
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);focused=hasFocus;
        if (!hasFocus) { revokeInputAndWork();stopVideo(); }
        else if (resumed) {
            readShareStatus();
            if (loading && requestedIndexRevision!=indexRevision) reload();
            else if (flow.mode()==GalleryFlow.DETAIL && preview==null
                    && GallerySelection.photo(selected())) loadPhoto();
        }
    }
    @Override protected void onDestroy() {
        destroyed=true;revokeInputAndWork();previews.shutdownNow();indexes.shutdownNow();
        stopVideo();clearPreview();super.onDestroy();
    }
    private void revokeInputAndWork() {
        keyAndDedupe.reset();touch.cancel();generic.cancel();
        flow.dismissTransient(); // focus loss cannot leave a one-tap share confirmation armed
        if (shareBusy) note="Проверка отменена";
        indexRevision++;previewRevision++;shareRevision++;videoRevision++;shareBusy=false;
        previews.getQueue().clear();indexes.getQueue().clear();clearPreview();
    }
    private void readShareStatus() {
        if (sharePrefs==null) return;
        shareStatus=sharePrefs.getString("share_status","");
        shareMediaId=sharePrefs.getLong("share_media_id",0);
        sharePending=sharePrefs.getBoolean("share_pending",false);
        shareUpdatedAt=sharePrefs.getLong("share_updated_at",0);
        if (awaitingStatusAfter>0 && shareUpdatedAt<awaitingStatusAfter) {
            shareStatus="";sharePending=false;
        } else awaitingStatusAfter=0;
        render();
    }
    private boolean recentShare(GlassMediaIndex.Entry item) {
        return item!=null && GalleryShareStatus.recent(item.id,shareMediaId,
                shareUpdatedAt,System.currentTimeMillis());
    }
    private boolean pendingShare(GlassMediaIndex.Entry item) {
        return item!=null && GalleryShareStatus.canCancel(item.id,shareMediaId,
                shareUpdatedAt,System.currentTimeMillis(),sharePending);
    }
    private void reload() {
        final int revision=++indexRevision;requestedIndexRevision=revision;
        indexes.getQueue().clear();
        ++previewRevision;++shareRevision;++videoRevision;
        shareBusy=false;loading=true;stopVideo();clearPreview();
        entries.clear();flow.reset(0,0);note="Загрузка…";render();
        final android.content.Context app=getApplicationContext();
        indexes.execute(new Runnable() { public void run() {
            if (revision!=indexRevision || !resumed || destroyed) return;
            List<GlassMediaIndex.Entry> found=null;
            try { found=GlassMediaIndex.readEntries(app); }
            catch (Exception unavailable) { /* show bounded error */ }
            final List<GlassMediaIndex.Entry> result=found;
            if (revision!=indexRevision || !resumed || destroyed) return;
            ui.post(new Runnable() { public void run() {
                if (revision!=indexRevision || !resumed || destroyed) return;
                loading=false;entries.clear();
                if (result!=null) entries.addAll(result);
                flow.reset(entries.size(),0);
                note=result==null ? "MediaStore недоступен" : "";
                render();
            }});
        }});
    }
    private GlassMediaIndex.Entry selected() {
        return flow.count()>0 && flow.index()<entries.size() ? entries.get(flow.index()) : null;
    }
    private void clearPreview() {
        Bitmap old=preview;preview=null;
        if (old!=null && !old.isRecycled()) old.recycle();
    }
    private void loadPhoto() {
        ++previewRevision;previews.getQueue().clear();clearPreview();
        final GlassMediaIndex.Entry item=selected();
        if (!GallerySelection.photo(item)) {render();return;}
        final int revision=previewRevision;
        previews.execute(new Runnable() { public void run() {
            if (revision!=previewRevision || !foreground()) return;
            Bitmap image=null;
            try {
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(item.file.getAbsolutePath(),bounds);
                if (bounds.outWidth<=0 || bounds.outHeight<=0) throw new IllegalStateException();
                BitmapFactory.Options scaled=new BitmapFactory.Options();
                scaled.inSampleSize=1;
                while (bounds.outWidth/scaled.inSampleSize>1120
                        || bounds.outHeight/scaled.inSampleSize>440) scaled.inSampleSize*=2;
                scaled.inPreferredConfig=Bitmap.Config.RGB_565;
                if (revision!=previewRevision || !foreground()) return;
                image=BitmapFactory.decodeFile(item.file.getAbsolutePath(),scaled);
            } catch (Exception unavailable) { /* no path or private media in diagnostics */ }
            final Bitmap ready=image;
            if (revision!=previewRevision || !foreground()) {
                if (ready!=null) ready.recycle();return;
            }
            ui.post(new Runnable() { public void run() {
                if (revision!=previewRevision || !foreground()
                        || !GallerySelection.same(item,selected())) {
                    if (ready!=null) ready.recycle();return;
                }
                clearPreview();preview=ready;
                if (ready==null) note="Фото недоступно";
                render();
            }});
        }});
    }
    private void stopVideo() {
        ++videoRevision;
        if (videoVisible) {
            videoVisible=false;
            videoView.stopPlayback();videoView.setVisibility(View.GONE);
        }
        flow.stop();
        render();
    }
    private void startVideo() {
        final GlassMediaIndex.Entry item=selected();
        if (!GallerySelection.video(item)) {flow.stop();return;}
        final int revision=++videoRevision;
        final android.content.Context app=getApplicationContext();
        new Thread(new Runnable() { public void run() {
            GlassMediaIndex.Entry found=null;
            try {found=GallerySelection.fresh(app,item);}catch(Exception unavailable){}
            final GlassMediaIndex.Entry verified=found;
            ui.post(new Runnable() { public void run() {
                if (revision!=videoRevision || !foreground() || flow.mode()!=GalleryFlow.PLAYING
                        || !GallerySelection.same(item,selected())) return;
                if (verified==null) {flow.stop();note="Файл изменён или удалён";render();return;}
                videoVisible=true;videoView.setVisibility(View.VISIBLE);
                videoView.setVideoPath(verified.file.getAbsolutePath());
                videoView.start();render();
            }});
        }},"glass-gallery-video-check").start();
    }
    private void shareSelected() {
        if (shareBusy || !foreground()) return;
        final GlassMediaIndex.Entry item=selected();
        if (!GallerySelection.shareable(item)) {note="Нет надёжных данных файла";render();return;}
        shareBusy=true;note="Проверка оригинала…";render();
        final int revision=++shareRevision;
        final android.content.Context app=getApplicationContext();
        new Thread(new Runnable() { public void run() {
            GlassMediaIndex.Entry found=null;
            try { found=GallerySelection.fresh(app,item); } catch(Exception unavailable){}
            final GlassMediaIndex.Entry verified=found;
            ui.post(new Runnable() { public void run() {
                if (revision!=shareRevision || !foreground()
                        || !GallerySelection.same(item,selected())) return;
                shareBusy=false;
                if (verified==null) {note="Файл изменён или удалён";render();return;}
                try {
                    Intent request=new Intent(GalleryActivity.this,PhotoSyncService.class)
                            .setAction(SHARE_ORIGINAL)
                            .putExtra("media_id",verified.id)
                            .putExtra("media_size",verified.size)
                            .putExtra("media_modified",verified.modified);
                    if (startService(request)==null) note="Передача недоступна";
                    else {note="Запрос отправлен · ждём состояние";
                        awaitingStatusAfter=System.currentTimeMillis();shareStatus="";sharePending=false;}
                } catch (RuntimeException unavailable) {note="Передача недоступна";}
                render();
            }});
        }},"glass-gallery-share-check").start();
    }
    private void dispatch(String action,String route,long down,long at) {
        if (!foreground() || loading) return;
        if (!keyAndDedupe.route(route,action,down,at)) return;
        if ("menu".equals(action) && flow.mode()==GalleryFlow.LIST) {reload();return;}
        GlassMediaIndex.Entry item=selected();
        if ("tap".equals(action) && flow.mode()==GalleryFlow.MENU && pendingShare(item)) {
            cancelSelected(item);flow.action("back",GallerySelection.video(item));render();return;
        }
        int effect=flow.action(action,GallerySelection.video(item));
        if (effect==GalleryFlow.PREVIEW || effect==GalleryFlow.START
                || "next".equals(action) || "previous".equals(action)) note="";
        if (effect==GalleryFlow.FINISH) {finish();return;}
        if (effect==GalleryFlow.STOP) {
            // Flow has already moved to its destination; stopPlayback must be immediate.
            if (videoVisible) {videoVisible=false;videoView.stopPlayback();videoView.setVisibility(View.GONE);}
            ++videoRevision;
            if ("next".equals(action) || "previous".equals(action)) loadPhoto();
        } else if (effect==GalleryFlow.PREVIEW) loadPhoto();
        else if (effect==GalleryFlow.START) startVideo();
        else if (effect==GalleryFlow.PAUSE) {if (videoVisible) videoView.pause();}
        else if (effect==GalleryFlow.RESUME) {if (videoVisible) videoView.start();}
        else if (effect==GalleryFlow.SHARE) shareSelected();
        render();
    }
    private void cancelSelected(GlassMediaIndex.Entry item) {
        if (!foreground() || !pendingShare(item)) return;
        try {
            Intent cancel=new Intent(this,PhotoSyncService.class)
                    .setAction("lab.franziska.glass.MEDIA_SHARE_CANCEL")
                    .putExtra("media_id",item.id);
            if (startService(cancel)==null) note="Отмена недоступна";
            else {note="Запрос отмены отправлен";
                awaitingStatusAfter=System.currentTimeMillis();shareStatus="";sharePending=false;}
        } catch (RuntimeException unavailable) {note="Отмена недоступна";}
    }
    private String keyAction(int code) {
        if (code==KeyEvent.KEYCODE_DPAD_CENTER) return "tap";
        if (code==KeyEvent.KEYCODE_DPAD_RIGHT) return "next";
        if (code==KeyEvent.KEYCODE_DPAD_LEFT) return "previous";
        if (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_DOWN) return "back";
        if (code==KeyEvent.KEYCODE_MENU || code==KeyEvent.KEYCODE_DPAD_UP) return "menu";
        return null;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        String action=keyAction(event.getKeyCode());
        if (action==null) return super.dispatchKeyEvent(event);
        if (!foreground()) {keyAndDedupe.reset();return true;}
        if (event.getAction()==KeyEvent.ACTION_DOWN) {
            keyAndDedupe.down(event.getKeyCode(),event.getRepeatCount(),event.getDownTime(),event.isLongPress());
            return true;
        }
        if (event.getAction()==KeyEvent.ACTION_UP) {
            long down=keyAndDedupe.downAt();
            if (keyAndDedupe.up(event.getKeyCode(),event.getEventTime(),event.isCanceled()
                    || event.getDownTime()!=down || event.getRepeatCount()!=0 || event.isLongPress()))
                dispatch(action,"key",down,event.getEventTime());
            return true;
        }
        keyAndDedupe.reset();return true;
    }
    private boolean accepted(MotionEvent event) {
        int source=event.getSource();
        return (source & InputDevice.SOURCE_TOUCHPAD)==InputDevice.SOURCE_TOUCHPAD
                || (source & InputDevice.SOURCE_CLASS_POINTER)!=0;
    }
    private String feed(TapGesture helper,MotionEvent event) {
        int count=event.getPointerCount();
        for (int i=0;count==1 && i<event.getHistorySize();i++)
            helper.event(MotionEvent.ACTION_MOVE,count,event.getPointerId(0),
                    event.getHistoricalX(0,i),event.getHistoricalY(0,i),
                    event.getDownTime(),event.getHistoricalEventTime(i));
        return helper.event(event.getActionMasked(),count,count==1?event.getPointerId(0):-1,
                count>0?event.getX():Float.NaN,count>0?event.getY():Float.NaN,
                event.getDownTime(),event.getEventTime());
    }
    private boolean motion(MotionEvent event,TapGesture helper,String route) {
        if (!accepted(event)) {helper.cancel();return false;}
        if (!foreground()) {helper.cancel();return true;}
        String action=feed(helper,event);
        if (action!=null) dispatch("exit".equals(action)?"back":action,
                route,helper.downAt(),event.getEventTime());
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return motion(event,touch,"touch") || super.dispatchTouchEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return motion(event,generic,"motion") || super.onGenericMotionEvent(event);
    }
    private void render() { if (galleryView!=null) galleryView.invalidate(); }
    private final class GalleryView extends View {
        private final GalleryRenderer renderer=new GalleryRenderer();
        GalleryView() {super(GalleryActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
                    Math.max(1,w*560/640),Math.max(1,h*196/360));
            p.leftMargin=w*40/640;p.topMargin=h*82/360;videoView.setLayoutParams(p);
        }
        @Override protected void onDraw(Canvas canvas) {
            GalleryRenderer.Frame frame=new GalleryRenderer.Frame();
            GlassMediaIndex.Entry item=selected();
            if (loading) {
                frame.primary="Загрузка…";frame.footer="Вниз — выйти";
            } else if (item==null) {
                frame.primary=note.length()>0?note:"Нет фото и видео";
                frame.footer="Вниз — выйти";
            } else {
                String kind=GallerySelection.video(item)?"Видео":"Фото";
                int mode=flow.mode();
                if (mode==GalleryFlow.LIST) {
                    frame.title="Галерея · "+(flow.index()+1)+" / "+flow.count();
                    frame.primary=kind+" · "+item.name;
                    frame.secondary=note.length()>0?note:"Свайп — выбрать";
                    frame.footer="Тап — открыть · вверх — обновить";
                } else if (mode==GalleryFlow.MENU) {
                    frame.title="Меню · "+kind;
                    frame.primary=pendingShare(item)?"Отменить передачу на Pixel"
                            :"Отправить оригинал на Pixel";
                    frame.secondary=pendingShare(item)?"Тап — отменить":"Тап — далее";
                    frame.footer="Вниз — к просмотру";
                } else if (mode==GalleryFlow.CONFIRM) {
                    frame.title="Отправить оригинал?";
                    frame.primary="Оригинал скопируется на Pixel";
                    frame.secondary="Получателя выбирают на Pixel";
                    frame.footer="Тап — подтвердить · вниз — отмена";
                } else {
                    frame.title=kind+" · "+item.name;
                    frame.media=true;frame.video=GallerySelection.video(item);
                    frame.photo=frame.video?null:preview;
                    frame.primary=note.length()>0?note:"Тап — воспроизвести";
                    frame.footer=mode==GalleryFlow.PLAYING ? "Тап — пауза · вниз — стоп" :
                            mode==GalleryFlow.PAUSED ? "Тап — продолжить · вниз — стоп" :
                            frame.video ? "Тап — играть · вверх — отправить" :
                            "Тап — меню · вниз — список";
                    if (mode==GalleryFlow.DETAIL && recentShare(item) && shareStatus.length()>0)
                        frame.footer=shareStatus;
                    else if (note.length()>0 && mode==GalleryFlow.DETAIL) frame.footer=note;
                }
            }
            renderer.draw(canvas,getWidth(),getHeight(),frame);
        }
    }
}
