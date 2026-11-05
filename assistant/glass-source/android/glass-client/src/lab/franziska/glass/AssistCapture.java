package lab.franziska.glass;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import lab.franziska.protocol.AssistWire;

/** One deliberately started capture, memory only. No intents, recognizer or public files. */
@SuppressWarnings("deprecation")
final class AssistCapture {
    private static final int MAX_CAMERA_JPEG_PADDING = 16;
    interface Listener { void complete(AssistCapture source,byte[] jpeg,byte[] wav); void failed(AssistCapture source); }
    private final Handler ui;
    private final Listener listener;
    private final boolean photo;
    private final boolean recordAudio;
    private volatile boolean cancelled, finishing;
    private boolean delivered,audioDone;
    private volatile boolean photoDone;
    enum Failure { NONE, UNSUPPORTED, MIC_INIT, MIC_START, MIC_READ, AUDIO_EMPTY, WAV_INVALID, CAMERA_OPEN, CAMERA_SETUP, CAMERA_SHOT, JPEG_EMPTY, JPEG_INVALID, PHOTO_TIMEOUT, AUDIO_TIMEOUT }
    private volatile Failure failureCode = Failure.NONE;
    Failure failureCode(){return failureCode;}
    private volatile String failureDetail = "";
    String failureDetail(){return failureDetail;}
    private volatile String failureSubcode = "";
    String failureSubcode(){return failureSubcode;}
    private byte[] jpeg,wav;
    private final AssistCaptureLifecycle audio = new AssistCaptureLifecycle();
    private Camera camera;
    private SurfaceTexture surface;
    private boolean ownsSurface;
    private volatile SurfaceTexture previewSurface;
    private volatile int previewWidth,previewHeight;
    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;
    private final Runnable maximum=new Runnable(){public void run(){finish();}};
    private final Runnable timeout=new Runnable(){public void run(){fail(photoDone ? Failure.AUDIO_TIMEOUT : Failure.PHOTO_TIMEOUT);}};
    AssistCapture(Handler ui,boolean photo,Listener listener){this(ui,photo,true,listener);}
    AssistCapture(Handler ui,boolean photo,boolean recordAudio,Listener listener){
        this.ui=ui;this.photo=photo;this.recordAudio=recordAudio;this.listener=listener;photoDone=!photo;
        audioDone=!recordAudio;if(!recordAudio)wav=new byte[0];
    }
    void setPreview(SurfaceTexture target){
        if(target==null)throw new IllegalArgumentException("visible preview required");
        previewSurface=target;
    }
    void start(){
        if(android.os.Build.VERSION.SDK_INT<19){fail(Failure.UNSUPPORTED);return;}
        Failure stage=Failure.MIC_INIT;
        try {
            ui.postDelayed(maximum,recordAudio?15000L:4500L);
            if(recordAudio)new Thread(new Runnable(){public void run(){record();}},"assist-mic").start();
            if(photo){
                stage=Failure.CAMERA_SETUP;
                cameraThread=new HandlerThread("assist-camera");cameraThread.start();
                Handler handler=new Handler(cameraThread.getLooper());cameraHandler=handler;
                handler.post(new Runnable(){public void run(){if(cancelled)closeCamera();else openPhoto();}});
            }
        } catch(Exception unavailable) { fail(stage); }
    }
    private void record(){
        byte[] pcm=new byte[480000];byte[] chunk=new byte[2048];int count=0;AudioRecord local=null;Failure stage=Failure.MIC_INIT;
        final Object nativeLease=new Object();boolean admitted=false,constructing=false;
        try{
            if(cancelled||finishing)return;
            admitted=NativeAudioAdmission.acquire(nativeLease);
            if(!admitted)throw new IllegalStateException("NATIVE_AUDIO_BUSY");
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0||min>65536)throw new IllegalStateException();
            if(cancelled||finishing)return;
            constructing=true;
            local=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(min,4096));
            constructing=false;
            stage=Failure.MIC_START;
            final AudioRecord owned=local;
            if(!audio.start(new AssistCaptureLifecycle.Device(){
                public void start(){
                    if(owned.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();
                    owned.startRecording();
                    if(owned.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException();
                }
                public void stop(){owned.stop();}
                public void release(){owned.release();}
            }))return;
            stage=Failure.MIC_READ;
            long start=SystemClock.elapsedRealtime();
            while(!cancelled&&!finishing&&count<pcm.length&&SystemClock.elapsedRealtime()-start<15000L){
                int n=local.read(chunk,0,Math.min(chunk.length,pcm.length-count));
                if(n<=0){if(cancelled||finishing)break;throw new IllegalStateException();}
                if(!cancelled){System.arraycopy(chunk,0,pcm,count,n);count+=n;}
            }
            synchronized(this){
                if(!cancelled){
                    stage=count < 2 ? Failure.AUDIO_EMPTY : Failure.WAV_INVALID;
                    if(count < 2)throw new IllegalStateException();
                    wav=AssistWire.wav(pcm,count&~1);audioDone=true;
                }
            }
        }catch(Exception failure){if(!cancelled)fail(stage);}
        finally{
            // Only the read owner releases; stop may concurrently unblock read.
            if(admitted){
                // The same process lease covers Talk and legacy photo-with-mic.
                // Retain it through blocking stop/release; uncertain cleanup is quarantined.
                boolean clean=audio.release();
                NativeAudioAdmission.settle(nativeLease,clean&&!constructing);
            }
            Arrays.fill(pcm,(byte)0);Arrays.fill(chunk,(byte)0);maybeComplete();
        }
    }
    // Camera fields belong exclusively to this worker, including stale callbacks.
    private void openPhoto(){
        Failure stage=Failure.CAMERA_OPEN;
        try{
            if(cancelled)return;
            camera=Camera.open();if(cancelled)return;
            stage=Failure.CAMERA_SETUP;
            Camera.Parameters p=camera.getParameters();if(cancelled)return;
            List<Camera.Size> sizes=p.getSupportedPreviewSizes();Camera.Size selected=null;
            for(Camera.Size size:sizes)if(size.width<=640&&size.height<=480
                    &&(size.width&1)==0&&(size.height&1)==0
                    &&(selected==null||size.width*size.height>selected.width*selected.height))selected=size;
            if(selected==null)throw new IllegalStateException();
            List<Integer> formats=p.getSupportedPreviewFormats();
            if(formats==null||!formats.contains(Integer.valueOf(ImageFormat.NV21)))throw new IllegalStateException();
            previewWidth=selected.width;previewHeight=selected.height;
            p.setPreviewSize(selected.width,selected.height);p.setPreviewFormat(ImageFormat.NV21);
            if(cancelled)return;
            camera.setParameters(p);if(cancelled)return;
            surface=previewSurface;
            if(surface==null){surface=new SurfaceTexture(0);ownsSurface=true;} // host/legacy capture seam
            if(cancelled)return;
            camera.setPreviewTexture(surface);if(cancelled)return;
            camera.startPreview();if(cancelled)return;
            cameraHandler.postDelayed(new Runnable(){public void run(){capturePreviewFrame();}},1000L);
        }catch(Exception failure){if(!cancelled)fail(stage);}
        finally{if(cancelled)closeCamera();}
    }
    private void capturePreviewFrame(){
        try{
            if(cancelled||camera==null)return;
            camera.setOneShotPreviewCallback(new Camera.PreviewCallback(){public void onPreviewFrame(byte[] data,Camera ignored){
                try{
                    if(cancelled){if(data!=null)Arrays.fill(data,(byte)0);return;}
                    int expected=previewWidth*previewHeight*3/2;
                    if(data==null||data.length<expected){fail(Failure.CAMERA_SHOT);return;}
                    ByteArrayOutputStream encoded=new ByteArrayOutputStream(65536);
                    YuvImage frame=new YuvImage(data,ImageFormat.NV21,previewWidth,previewHeight,null);
                    if(!frame.compressToJpeg(new Rect(0,0,previewWidth,previewHeight),70,encoded)){
                        fail(Failure.CAMERA_SHOT);return;
                    }
                    byte[] candidate=stripCameraPadding(encoded.toByteArray());
                    if(cancelled){Arrays.fill(candidate,(byte)0);return;}
                    try{AssistWire.validateJpeg(candidate);}catch(Exception invalid){
                        String detail=invalid.getMessage();
                        if(detail==null||!detail.matches("ASSIST_JPEG(_[A-Z]+)?"))detail="ASSIST_JPEG";
                        // Fixed validator category and length only; never retain image/audio bytes.
                        failureSubcode=candidate.length>AssistWire.MAX_IMAGE ? "JPEG_SIZE" : detail.substring(7);
                        failureDetail=detail+" bytes="+candidate.length+" preview="+previewWidth+"x"+previewHeight;
                        Arrays.fill(candidate,(byte)0);fail(Failure.JPEG_INVALID);return;
                    }
                    synchronized(AssistCapture.this){
                        if(cancelled||photoDone){Arrays.fill(candidate,(byte)0);return;}
                        jpeg=candidate;photoDone=true;
                    }
                    closeCamera(); // Release driver before UI may hide its TextureView.
                    maybeComplete();
                }catch(Exception failure){if(!cancelled)fail(Failure.CAMERA_SHOT);}
                finally{if(data!=null)Arrays.fill(data,(byte)0);closeCamera();}
            }});
        }catch(Exception failure){if(!cancelled)fail(Failure.CAMERA_SHOT);}
        finally{if(cancelled)closeCamera();}
    }
    static byte[] stripCameraPadding(byte[] raw){
        if(raw.length<18||raw.length>AssistWire.MAX_IMAGE+MAX_CAMERA_JPEG_PADDING)return raw;
        int end=raw.length;
        while(end>raw.length-MAX_CAMERA_JPEG_PADDING&&end>2&&raw[end-1]==0)end--;
        if(end==raw.length||end<2||(raw[end-2]&255)!=255||(raw[end-1]&255)!=217)return raw;
        byte[] canonical=Arrays.copyOf(raw,end);
        Arrays.fill(raw,(byte)0);
        return canonical;
    }
    private void stopAudio(){
        audio.requestStop();
    }
    private void scheduleCameraClose(){
        Handler handler=cameraHandler;
        if(handler!=null)handler.post(new Runnable(){public void run(){closeCamera();}});
    }
    boolean isFinishing(){return finishing;}
    void finish(){
        synchronized(this){
            if(cancelled||delivered||finishing)return;
            finishing=true;
        }
        stopAudio();ui.removeCallbacks(maximum);ui.postDelayed(timeout,3000L);maybeComplete();
    }
    private void maybeComplete(){
        synchronized(this){
            if(cancelled||delivered||!audioDone||!photoDone)return;
            delivered=true;ui.removeCallbacks(maximum);ui.removeCallbacks(timeout);scheduleCameraClose();
            final byte[] image=jpeg==null?new byte[0]:jpeg, audio=wav;
            // Keep owned references until delivery so cancel also wipes a queued callback
            // that Activity Stop removes from the main Handler without running it.
            jpeg=image;
            ui.post(new Runnable(){public void run(){
                synchronized(AssistCapture.this){
                    if(cancelled){Arrays.fill(image,(byte)0);Arrays.fill(audio,(byte)0);return;}
                    jpeg=null;wav=null;
                }
                listener.complete(AssistCapture.this,image,audio);
            }});
        }
    }
    private void fail(Failure code){
        synchronized(this){if(cancelled||delivered||failureCode!=Failure.NONE)return;failureCode=code;}
        cancel();ui.post(new Runnable(){public void run(){listener.failed(AssistCapture.this);}});
    }
    void cancel(){
        // Publish revocation before any lock or hardware operation.
        cancelled=true;finishing=true;
        stopAudio();scheduleCameraClose();
        ui.removeCallbacks(maximum);ui.removeCallbacks(timeout);
        synchronized(this){
            if(jpeg!=null)Arrays.fill(jpeg,(byte)0);if(wav!=null)Arrays.fill(wav,(byte)0);jpeg=null;wav=null;
        }
    }
    private void closeCamera(){
        if(cameraHandler!=null)cameraHandler.removeCallbacksAndMessages(null);
        if(camera!=null){try{camera.stopPreview();}catch(Exception ignored){}try{camera.release();}catch(Exception ignored){}camera=null;}
        if(surface!=null&&ownsSurface){try{surface.release();}catch(Exception ignored){}}
        surface=null;ownsSurface=false;
        if(cameraThread!=null){cameraThread.quit();cameraThread=null;cameraHandler=null;}
    }
}


/** Audio-only hardware serialization. Never acquire this monitor from cancellation. */
final class AssistCaptureLifecycle {
    interface Device { void start(); void stop(); void release(); }
    private volatile boolean stopping;
    private Device device;
    private boolean stopped,startAttempted;
    private boolean cleanupClean=true;
    synchronized boolean start(Device value) {
        device=value;
        if(stopping)return false;
        startAttempted=true;
        value.start();
        return !stopping;
    }
    void requestStop() {
        stopping=true;
        // Independent of camera worker and of a possibly blocked audio start/read.
        synchronizedScheduler();
    }
    private final Object scheduling=new Object();
    private boolean scheduled;
    private void synchronizedScheduler() {
        synchronized(scheduling){
            if(scheduled)return;
            scheduled=true;
            new Thread(new Runnable(){public void run(){stop();}},"assist-mic-stop").start();
        }
    }
    private synchronized void stop() {
        if(device==null||stopped)return;
        stopped=true;
        if(startAttempted){
            boolean completed=false;
            try{device.stop();completed=true;}catch(Exception ignored){}
            finally{if(!completed)cleanupClean=false;}
        }
    }
    synchronized boolean release() {
        stop();
        Device owned=device;device=null;
        if(owned!=null){
            boolean completed=false;
            try{owned.release();completed=true;}catch(Exception ignored){}
            finally{if(!completed)cleanupClean=false;}
        }
        return cleanupClean;
    }
}
