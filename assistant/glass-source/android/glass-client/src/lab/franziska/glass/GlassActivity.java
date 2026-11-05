package lab.franziska.glass;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import lab.franziska.protocol.AppIdentityStore;
import lab.franziska.protocol.Protocol;
import lab.franziska.protocol.DisplayCalibration;
import lab.franziska.protocol.AssistWire;
import lab.franziska.protocol.TalkWire;
import lab.franziska.protocol.AssistPump;
import lab.franziska.protocol.CallWire;
import lab.franziska.protocol.ProtocolException;
import lab.franziska.protocol.SessionState;
import lab.franziska.protocol.SecureTransport;
import lab.franziska.protocol.Tls;

/** 640x360-safe native renderer. It never executes remote intents or code. */
public class GlassActivity extends Activity {
    protected boolean assistantEntry() { return false; }
    protected boolean photoEntry() { return false; }
    private boolean assistMode, assistPhoto = true;
    private boolean localMirrorVisible;
    private int localMirrorPage;
    private final GlassCallPrompt callPrompt = new GlassCallPrompt();
    private GlassCallWriteGate callWriteGate = new GlassCallWriteGate();
    private final Runnable callTimeout = new Runnable() { public void run() {
        synchronized (GlassActivity.this) {
            String id = callPrompt.expire(SystemClock.elapsedRealtime());
            if (id != null) sendCallCancel(id);
            else if (callPrompt.stage() == GlassCallPrompt.Stage.CONFIRM_QUEUED
                    || callPrompt.stage() == GlassCallPrompt.Stage.WAITING_RESULT)
                ui.postDelayed(callResultTimeout, 10000L);
            view.invalidate();
        }
    }};
    private final Runnable callResultTimeout = new Runnable() { public void run() {
        synchronized (GlassActivity.this) {
            if (callPrompt.stage() == GlassCallPrompt.Stage.CONFIRM_QUEUED
                    || callPrompt.stage() == GlassCallPrompt.Stage.WAITING_RESULT) {
                callPrompt.failUncertain(); view.invalidate();
            }
        }
    }};
    private int batteryPercent=-1,outputVolumePercent=-1;
    private static final String VOLUME_CHANGED="android.media.VOLUME_CHANGED_ACTION";
    private void refreshOutputVolume(){
        try{AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
            outputVolumePercent=TalkStatusBar.percent(manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }catch(RuntimeException unavailable){outputVolumePercent=-1;}
        if(view!=null)view.invalidate();
    }
    private boolean batteryCharging,batteryObserved;
    private final BroadcastReceiver batteryChanges=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!batteryObserved||intent==null)return;
            if(VOLUME_CHANGED.equals(intent.getAction())){refreshOutputVolume();return;}
            if(!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction()))return;
            batteryPercent=TalkStatusBar.percent(intent.getIntExtra("level",-1),intent.getIntExtra("scale",-1));
            int status=intent.getIntExtra("status",-1);
            batteryCharging=intent.getIntExtra("plugged",0)!=0
                    &&(status==android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    ||status==android.os.BatteryManager.BATTERY_STATUS_FULL);
            if(view!=null)view.invalidate();
        }
    };
    private void observeBattery(){
        if(batteryObserved)return;
        batteryObserved=true;
        refreshOutputVolume();
        try{IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);filter.addAction(VOLUME_CHANGED);
            Intent sticky=registerReceiver(batteryChanges,filter);
            if(sticky!=null)batteryChanges.onReceive(this,sticky);
        }catch(RuntimeException unavailable){batteryObserved=false;batteryPercent=-1;batteryCharging=false;}
    }
    private void stopObservingBattery(){
        if(!batteryObserved)return;
        batteryObserved=false;unregisterReceiver(batteryChanges);
    }
    private boolean talkSelected;
    private boolean pendingVision, visionRequest, visionResume, visionSpeechDone;
    private final CameraButtonGate cameraButtonGate = new CameraButtonGate();
    private final VoicePhotoGate voicePhotoGate = new VoicePhotoGate();
    private final Runnable visionHandoffClock = new Runnable() { public void run() {
        synchronized (GlassActivity.this) {
            if (pendingVision && voicePhotoGate.handoffPending()
                    && voicePhotoGate.expired(SystemClock.elapsedRealtime()))
                cancelVisionContinuation("Фото не начато · тап — к разговору", "PHOTO_HANDOFF_EXPIRED");
        }
    }};
    private final Runnable visionAckClock = new Runnable() { public void run() {
        synchronized (GlassActivity.this) {
            if (voicePhotoGate.acknowledgmentPending()
                    && voicePhotoGate.expired(SystemClock.elapsedRealtime()))
                cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_ACK_TIMEOUT");
        }
    }};
    private String talkId,talkEpoch,talkStatus="Чем помочь?";
    private boolean talkDraining, talkInputSeen, talkFailed, talkDeadlineEnded;
    // Repaint countdown only when its visible minute label/color changes.
    private final Runnable talkStatusClock=new Runnable(){public void run(){synchronized(GlassActivity.this){
        if(talkId==null||!resumed||!windowFocused)return;
        if(view!=null)view.invalidate();
        long delay=TalkStatusBar.nextCountdownDelay(talkDeadline-SystemClock.elapsedRealtime());
        if(delay>0L)ui.postDelayed(this,delay);
    }}};
    private boolean talkOutputPending(){
        if(talkPlayback==null)return false;
        TalkSendPolicy.Playback.State progress=talkPlayback.snapshot();
        return progress.active&&progress.accepted>progress.completed;
    }
    private String talkTranscript="";
    private final TalkTranscriptLog talkLog=new TalkTranscriptLog();
    private boolean talkTextV2, talkPhotoV1;
    private String photoTalkId, photoNotice="";
    private long photoTalkRevision;
    /** Only the physical button binds this photo to the still-owned microphone session. */
    private boolean photoTalkCurrent() {
        return photoTalkId!=null && photoTalkId.equals(talkId) && photoTalkRevision==talkRevision
                && talkSocket==socket && talkEpoch!=null && talkEpoch.equals(epoch)
                && !talkDraining && SystemClock.elapsedRealtime()<talkDeadline;
    }
    private void clearPhotoTalk() { photoTalkId=null; photoTalkRevision=0L; }

    private boolean talkTextRedrawPending;
    private final Runnable talkTextRedraw=new Runnable(){public void run(){synchronized(GlassActivity.this){talkTextRedrawPending=false;if(view!=null)view.invalidate();}}};
    private static final long TALK_DRAIN_GRACE_MS=6500L;
    private Socket talkSocket;
    private GlassTalkAudio talkAudio;
    private int talkInputIndex,talkOutputIndex;
    private int talkAcceptedBytes,talkReleasedBytes,talkFlowGeneration;
    private Outbound talkFlow;
    private TalkSendPolicy.Credits talkCredits;
    private TalkSendPolicy.Trace talkTrace;
    private TalkSendPolicy.Playback talkPlayback;
    private long talkRevision,talkDeadline;
    private long talkKeyFloorUptime = -1L;
    private Outbound talkStop;
    private final Runnable talkTimeout=new Runnable(){public void run(){synchronized(GlassActivity.this){
        if(talkId!=null&&SystemClock.elapsedRealtime()>=talkDeadline)talkAbort(true,"Сеанс завершён · тап — снова","SESSION_DEADLINE");
    }}};
    private boolean voiceStartPending;
    private long voiceStartDeadline;
    private long voiceCueGeneration;
    private TextureView cameraPreview;
    private AssistCapture startedCapture;
    private static final String GLASS_VOICE_TRIGGER = "com.google.android.glass.action.VOICE_TRIGGER";
    private Socket assistReadySocket, assistRequestSocket;
    private String assistReadyEpoch, assistRequestEpoch, assistRequest;
    private boolean assistReady, assistBegan;
    private final AssistPresentation assistUi = new AssistPresentation();
    private final Runnable assistClock = new Runnable() { public void run() {
        synchronized (GlassActivity.this) {
            if (assistUi.stage() == AssistPresentation.Stage.RECORDING && assistCapture != null && assistCapture.isFinishing())
                assistUi.advance(AssistPresentation.Stage.PREPARING);
            view.invalidate();
            if (assistUi.stage() == AssistPresentation.Stage.RECORDING && resumed && windowFocused) {
                ui.postDelayed(this, 250L);
            }
        }
    } };
    private AssistCapture assistCapture;
    private volatile AssistPump assistPump;
    private byte[] pendingPhotoImage, pendingPhotoAudio;
    private boolean photoPublished;
    private long photoSaveStartedAt;
    private String photoImageSha256;
    private static final String PHOTO_TRANSFER_ACK = "Фото добавлено в эту беседу. Продолжим разговор.";
    private final AssistGate assistGate = new AssistGate();
    private long assistGateToken;
    private Outbound assistCancel, assistHello;
    private String assistHelloEpoch;
    private java.util.List<String[]> assistPages;
    private int assistPage;
    private long assistRevision;
    private AssistSpeech assistSpeech;
    private boolean assistVoiceUnavailable;

    private String reserveAssistRequestId() throws ProtocolException {
        final android.content.SharedPreferences prefs = getSharedPreferences("assist_request_ids", MODE_PRIVATE);
        return lab.franziska.protocol.AssistRequestIds.reserve(new lab.franziska.protocol.AssistRequestIds.Store() {
            public Long namespace() { return prefs.contains("namespace") ? Long.valueOf(prefs.getLong("namespace", 0)) : null; }
            public Long counter() { return prefs.contains("counter") ? Long.valueOf(prefs.getLong("counter", 0)) : null; }
            public boolean commit(long namespace, long counter) {
                return prefs.edit().putLong("namespace", namespace).putLong("counter", counter).commit();
            }
        }, new java.security.SecureRandom().nextLong());
    }
    private synchronized void queueAssistHello() {
        if (assistMode && running && socket != null && epoch != null && !inbound.isStopped()
                && !localStopRequested && !terminalExitRequested && !epoch.equals(assistHelloEpoch)) {
            assistHelloEpoch = epoch;
            assistHello = new Outbound("assist.hello", emptyPayload(), false, false);
            notifyAll();
        }
    }
    /** Authenticated current link, independent of helper availability (busy during Talk).
     * Presentation only: new capture still requires assistEligible() and ready. */
    private synchronized boolean assistLinked() {
        return running && !localStopRequested && !terminalExitRequested && !inbound.isStopped()
                && socket != null && !socket.isClosed() && socket == assistReadySocket
                && epoch != null && epoch.equals(assistReadyEpoch);
    }
    private synchronized boolean assistEligible() {
        return assistMode && resumed && windowFocused && running && !localStopRequested
                && !terminalExitRequested && !inbound.isStopped() && (assistReady || photoTalkCurrent())
                && (photoTalkId==null || photoTalkCurrent())
                && socket != null && socket == assistReadySocket && epoch != null
                && epoch.equals(assistReadyEpoch);
    }
    private synchronized void assistAbort(boolean notifyPeer, String state) {
        assistAbort(notifyPeer, state, "CANCELLED", false);
    }
    private synchronized void assistAbort(boolean notifyPeer, String state, String code, boolean error) {
        cameraButtonGate.cancel();
        boolean livePhoto = photoTalkCurrent();
        boolean wasVision = pendingVision || visionRequest || voicePhotoGate.acknowledgmentPending();
        clearPhotoTalk();
        if(livePhoto)photoNotice=error ? "Фото не отправлено" : "Фото отменено";
        pendingVision=false;visionRequest=false;visionResume=false;visionSpeechDone=false;
        voicePhotoGate.clear();
        ui.removeCallbacks(visionHandoffClock); ui.removeCallbacks(visionAckClock);
        if (wasVision && !livePhoto) {
            talkSelected=true;
            talkStatus=error ? "Фото: " + code + " · тап — к разговору" : "Фото остановлено · тап — к разговору";
        }
        boolean active = assistRequest != null || assistCapture != null || assistPump != null;
        String id = assistRequest;
        if (notifyPeer && assistBegan && id != null && socket == assistRequestSocket && epoch != null
                && epoch.equals(assistRequestEpoch) && running && !inbound.isStopped())
            assistCancel = new Outbound("assist.cancel", AssistWire.payload("request_id", id), false, false);
        notifyAll(); // Publish cancellation to an idle writer.
        assistRevision++; assistGate.revoke(); assistBegan = false;
        discardPendingPhoto(); photoPublished = false; photoImageSha256 = null;
        ui.removeCallbacks(assistClock);
        assistRequest = null; assistRequestSocket = null; assistRequestEpoch = null;
        if (assistCapture != null) assistCapture.cancel();
        assistCapture = null;
        startedCapture = null;
        hidePreview();
        if (assistPump != null) assistPump.cancel();
        assistPump = null;
        if (active || error) {
            assistPages = null; assistPage = 0;
            assistUi.end(error ? AssistPresentation.Stage.ERROR : AssistPresentation.Stage.CANCELLED, state, code, SystemClock.elapsedRealtime());
        }
        // Fixed local categories only: no media, IDs, addresses or exception text.
        if (active || error) Log.i("GlassAssist", code);
        if (view != null) ui.post(new Runnable() { public void run() { updateReadingScreen(false); view.invalidate(); } });
    }
    private synchronized void assistDisconnected() {
        displayPeer=false;
        if(displayGate!=null){displayGate.disconnect();ui.post(new Runnable(){public void run(){displayRender();}});}
        if (callPrompt.visible()) { callPrompt.failUncertain(); if (view != null) view.invalidate(); }
        localMirrorVisible = false;
        talkAbort(false,"Связь потеряна · разговор остановлен","LINK_LOST");
        assistReady = false; assistReadySocket = null; assistReadyEpoch = null; assistCancel = null;talkTextV2=false;talkPhotoV1=false;
        assistHello = null; assistHelloEpoch = null;
        assistAbort(false, "Связь потеряна · вопрос отменён", "LINK_LOST", false);
        assistUi.ready(false);
        if (localStopRequested || inbound.isStopped()) {
            assistPages = null; assistPage = 0;
            if (assistUi.stage() != AssistPresentation.Stage.STOPPED)
                assistUi.end(AssistPresentation.Stage.STOPPED, "Связь остановлена", "STOPPED", SystemClock.elapsedRealtime());
        }
    }
    private synchronized void assistInput(String action) {
        if(displayActive())return;
        if ("tap".equals(action) && pendingVision) {
            cancelVisionContinuation("Фото отменено · тап — к разговору", "PHOTO_HANDOFF_CANCELLED");
            return;
        }
        if ("tap".equals(action) && voicePhotoGate.acknowledgmentPending())
            cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_ACK_OWNER_TAP");
        if ("tap".equals(action) && assistSpeech != null) {assistSpeech.stop();visionResume=false;}
        if ("tap".equals(action)) assistVoiceUnavailable = false;
        if ((talkSelected||talkId!=null)&&!pendingVision&&!visionRequest) {
            if ("next".equals(action)||"previous".equals(action)){view.talkCard.browse(talkLog,"next".equals(action)?1:-1);view.invalidate();return;}
            if ("tap".equals(action)&&talkId==null&&view.talkCard.reading()){view.talkCard.closeHistory();view.invalidate();return;}
        }
        if ("next".equals(action) || "previous".equals(action)) {
            if (assistPages != null) assistPage = Math.max(0, Math.min(assistPages.size()-1,
                    assistPage + ("next".equals(action) ? 1 : -1)));
            if (assistPages != null) {
                assistUi.page(SystemClock.elapsedRealtime()); updateReadingScreen(false);
            }
            view.invalidate(); return;
        }
        if (!"tap".equals(action)) return;
        if(photoTalkCurrent() && assistCapture!=null){
            assistAbort(true,"Фото отменено","OWNER_CANCEL",false);return;
        }
        if(talkId!=null){talkAbort(true,"Разговор завершён · тап — снова","OWNER_TAP");view.invalidate();return;}
        if(talkSelected){talkStart();view.invalidate();return;}
        if (assistCapture != null) {
            if(visionRequest){assistAbort(true,"Фото отменено", "OWNER_CANCEL",false);return;}
            assistUi.advance(AssistPresentation.Stage.PREPARING); assistCapture.finish(); view.invalidate(); return;
        }
        if (assistPages != null) {
            assistPages = null; assistUi.home(SystemClock.elapsedRealtime()); updateReadingScreen(false); view.invalidate(); return;
        }
        startAssistCapture(false);
    }
    private synchronized void startAssistCapture(boolean photoOnly) {
        if(displayActive())return;
        if (assistRequest != null) return;
        if (!assistEligible() || assistCancel != null) {
            if (!assistUi.terminal()) assistUi.ready(false); view.invalidate(); return;
        }
        if(photoOnly){
            voicePhotoGate.clear();
            ui.removeCallbacks(visionHandoffClock); ui.removeCallbacks(visionAckClock);
            if (assistSpeech != null) assistSpeech.stop();
            visionSpeechDone=false;talkSelected=photoTalkCurrent();assistPages=null;assistPage=0;
        }
        try { assistRequest = reserveAssistRequestId(); }
        catch (ProtocolException unavailable) {
            assistUi.end(AssistPresentation.Stage.ERROR, "Счётчик недоступен · запись запрещена", "REQUEST_ID", SystemClock.elapsedRealtime()); updateReadingScreen(false); view.invalidate(); return;
        }
        assistRequestSocket = socket; assistRequestEpoch = epoch;
        photoPublished = false; photoImageSha256 = null;
        visionRequest=photoOnly;
        final long assistStartedAt = SystemClock.elapsedRealtime();
        assistGateToken = assistGate.begin(socket, epoch, assistRequest, assistEligible(), assistStartedAt);
        if (assistGateToken < 0) { assistAbort(false, "Не готово"); return; }
        assistUi.begin(assistStartedAt);
        ui.removeCallbacks(assistClock); ui.post(assistClock);
        updateReadingScreen(false);
        final long revision = ++assistRevision;
        assistCapture = new AssistCapture(ui, assistPhoto||photoOnly, !photoOnly, new AssistCapture.Listener() {
            public void complete(AssistCapture source, byte[] jpeg, byte[] wav) {
                synchronized (GlassActivity.this) {
                    if (assistCapture != source || revision != assistRevision || !assistEligible()
                            || socket != assistRequestSocket || !epoch.equals(assistRequestEpoch)
                            || !assistGate.owns(assistGateToken, socket, epoch, assistRequest)) {
                        java.util.Arrays.fill(jpeg, (byte)0); java.util.Arrays.fill(wav, (byte)0);
                        if (assistCapture == source) assistAbort(true, "Запись отменена", "CAPTURE_REVOKED", false); return;
                    }
                    assistCapture = null;
                    startedCapture = null;
                    hidePreview();
                    if(photoTalkCurrent()){talkSelected=true;photoNotice="Отправляю фото";}
                    assistUi.advance(AssistPresentation.Stage.PREPARING);
                    photoImageSha256 = jpeg.length > 0 ? AssistWire.sha256(jpeg) : null;
                    if (jpeg.length > 0) retainCapturedPhoto(jpeg, wav, revision, assistStartedAt + AssistGate.REQUEST_TIMEOUT_MS);
                    else enqueueAssistMedia(jpeg, wav);
                    view.invalidate();
                }
            }
            public void failed(AssistCapture source) {
                synchronized (GlassActivity.this) {
                    if (assistCapture == source && revision == assistRevision) {
                        boolean invalidJpeg = source.failureCode() == AssistCapture.Failure.JPEG_INVALID;
                        if (invalidJpeg)
                            Log.i("GlassAssist", "JPEG_INVALID " + source.failureDetail());
                        String code = invalidJpeg ? source.failureSubcode() : source.failureCode().name();
                        String text = invalidJpeg ? ("JPEG_SIZE".equals(code)
                                ? "Снимок слишком большой" : "Фото: " + code)
                                : captureFailureText(source.failureCode());
                        assistAbort(true, text, code, true);
                    }
                }
            }
        });
        if (assistPhoto||photoOnly) {
            cameraPreview.setVisibility(View.VISIBLE);
            startCaptureWhenPreviewReady(assistCapture, revision);
            ui.postDelayed(new Runnable() { public void run() {
                synchronized (GlassActivity.this) {
                    if (revision == assistRevision && assistCapture != null && assistCapture != startedCapture)
                        assistAbort(true, "Превью камеры недоступно", "PREVIEW_TIMEOUT", true);
                }
            } }, 3000L);
        } else assistCapture.start();
        view.invalidate();
        ui.postDelayed(new Runnable() { public void run() {
            synchronized (GlassActivity.this) {
                if (assistRequest != null && revision == assistRevision)
                    assistAbort(true, "Ответ не получен · тап — снова", "REQUEST_TIMEOUT", true);
            }
        } }, Math.max(0L, assistStartedAt + AssistGate.REQUEST_TIMEOUT_MS - SystemClock.elapsedRealtime()));
    }
    /** Pending upload is independently owned until exact archive publication, never scanner completion. */
    private synchronized void discardPendingPhoto() {
        if (pendingPhotoImage != null) java.util.Arrays.fill(pendingPhotoImage, (byte)0);
        if (pendingPhotoAudio != null) java.util.Arrays.fill(pendingPhotoAudio, (byte)0);
        pendingPhotoImage = pendingPhotoAudio = null;
    }
    private synchronized boolean photoArchiveCurrent(long revision, long deadline) {
        return revision == assistRevision && pendingPhotoImage != null && resumed && windowFocused
                && running && !localStopRequested && !terminalExitRequested && !inbound.isStopped()
                && SystemClock.elapsedRealtime() >= photoSaveStartedAt
                && SystemClock.elapsedRealtime() < deadline && assistRequestSocket == socket
                && assistRequestEpoch != null && assistRequestEpoch.equals(epoch)
                && assistGate.owns(assistGateToken, socket, epoch, assistRequest)
                && (photoTalkId==null || photoTalkCurrent());
    }
    private synchronized void photoArchivePublished(long revision, long deadline) {
        if (!photoArchiveCurrent(revision, deadline)) return;
        byte[] jpeg = pendingPhotoImage, wav = pendingPhotoAudio;
        pendingPhotoImage = pendingPhotoAudio = null;
        photoPublished = true;
        enqueueAssistMedia(jpeg, wav);
    }
    private synchronized void enqueueAssistMedia(byte[] jpeg, byte[] wav) {
        try {
            assistPump = new AssistPump(assistRequest, epoch, jpeg, wav, SystemClock.elapsedRealtime());
            notifyAll();
            assistUi.advance(AssistPresentation.Stage.SENDING);
            final AssistPump sending = assistPump;
            ui.postDelayed(new Runnable() { public void run() {
                synchronized (GlassActivity.this) {
                    if (assistPump == sending) assistAbort(true, "Передача прервана · тап — снова", "SEND_TIMEOUT", true);
                }
            } }, 25000L);
        } catch (Exception invalid) {
            java.util.Arrays.fill(jpeg, (byte)0); java.util.Arrays.fill(wav, (byte)0);
            assistAbort(true, "Не удалось подготовить запись", "MEDIA_PREPARE", true);
        }
        view.invalidate();
    }
    /** Keep exactly the captured JPEG, without re-encoding or selecting any gallery item. */
    private void retainCapturedPhoto(byte[] jpeg, byte[] wav, final long revision, long requestDeadline) {
        final byte[] original = jpeg.clone();
        pendingPhotoImage = jpeg; pendingPhotoAudio = wav;
        photoSaveStartedAt = SystemClock.elapsedRealtime();
        final long deadline = Math.min(requestDeadline, photoSaveStartedAt + 5000L);
        final android.content.Context app = getApplicationContext();
        final CapturedPhotoStore.Gate gate = new CapturedPhotoStore.Gate() {
            public boolean active() { return photoArchiveCurrent(revision, deadline); }
            public boolean commit(CapturedPhotoStore.Commit operation) throws java.io.IOException {
                synchronized (GlassActivity.this) {
                    if (!active()) return false;
                    operation.run(); return true;
                }
            }
        };
        ui.postDelayed(new Runnable() { public void run() {
            synchronized (GlassActivity.this) {
                if (revision == assistRevision && pendingPhotoImage != null)
                    assistAbort(false, "Фото не сохранено · передача отменена", "PHOTO_SAVE_TIMEOUT", true);
            }
        } }, Math.max(0L, deadline - SystemClock.elapsedRealtime()));
        new Thread(new Runnable() { public void run() {
            String status = "FAILED";
            try {
                status = CapturedPhotoStore.save(app, original, gate, new CapturedPhotoStore.Published() {
                    public void published() { java.util.Arrays.fill(original, (byte)0);
                        ui.post(new Runnable() { public void run() {
                        photoArchivePublished(revision, deadline);
                    }}); }
                }).name();
            } catch (Exception unavailable) { /* Never log image, path, or exception payload. */ }
            finally { java.util.Arrays.fill(original, (byte)0); }
            final String result = status;
            ui.post(new Runnable() { public void run() {
                synchronized (GlassActivity.this) {
                    if (revision != assistRevision) return;
                    if (pendingPhotoImage != null)
                        assistAbort(false, "Фото не сохранено · передача отменена", "PHOTO_SAVE_FAILED", true);
                    else if (photoPublished && "SAVED_SCAN_FAILED".equals(result) && resumed && windowFocused)
                        android.widget.Toast.makeText(GlassActivity.this,
                                "Фото сохранено · галерея ещё обновляется", android.widget.Toast.LENGTH_LONG).show();
                }
            }});
        }}, "glass-captured-original").start();
    }
    private synchronized void talkStart(){
        if(displayActive())return;
        if(!assistEligible()||assistRequest!=null||assistCapture!=null||assistPump!=null){talkStatus="Pixel не готов";return;}
        photoNotice="";clearPhotoTalk();
        talkKeyFloorUptime=SystemClock.uptimeMillis();
        talkId=UUID.randomUUID().toString();talkSocket=socket;talkEpoch=epoch;
        talkInputIndex=0;talkOutputIndex=0;talkAcceptedBytes=0;talkReleasedBytes=0;
        talkFlowGeneration=0;talkFlow=null;talkRevision++;
        talkCredits=new TalkSendPolicy.Credits();talkTrace=TalkDiagnostics.ENABLED?new TalkSendPolicy.Trace():null;
        talkDraining=false;talkInputSeen=false;talkFailed=false;talkDeadlineEnded=false;talkTranscript="";
        talkLog.clear();view.talkCard.closeHistory();
        talkDeadline=SystemClock.elapsedRealtime()+TalkWire.MAX_SESSION_MS;
        talkStatus="Подключаюсь…";
        Log.i("GlassTalkDiag","START");
        Map<String,Object> startPayload=AssistWire.payload("request_id",talkId);
        if(talkTextV2)startPayload.put("text_version",Long.valueOf(2));
        if(!outbound.offerLast(new Outbound("talk.start",startPayload,false,false))){
            talkAbort(false,"Канал занят · тап — снова","START_QUEUE_FULL");return;
        }
        notifyAll(); // Wake the owned non-consuming writer wait.
        ui.removeCallbacks(talkTimeout);ui.postDelayed(talkTimeout,TalkWire.MAX_SESSION_MS);
        ui.removeCallbacks(talkStatusClock);ui.post(talkStatusClock);
        updateReadingScreen(false);
    }
    private synchronized void talkAbort(boolean notifyPeer,String status){
        talkAbort(notifyPeer,status,"LIFECYCLE_OR_REMOTE");
    }
    private synchronized void queueTalkFlow(){queueTalkFlow(true);}
    private synchronized void queueTalkFlow(boolean force){
        if(talkPlayback!=null){
            TalkSendPolicy.Playback.State progress=talkPlayback.snapshot();
            if(progress.failed||progress.accepted!=talkAcceptedBytes
                    ||progress.generation!=talkFlowGeneration){
                talkAbort(true,"Аудиовыход прерван","OUTPUT_FLOW_ACCOUNTING");return;
            }
            if(!progress.active)return;
            talkReleasedBytes=progress.completed;
        }
        if(talkId!=null&&talkAudio!=null&&talkCredits!=null&&!talkDraining
                &&talkReleasedBytes<=TalkWire.MAX_OUTPUT_CREDIT_BYTES
                &&talkCredits.publish(talkReleasedBytes,SystemClock.elapsedRealtime(),force)){
            talkFlow=new Outbound("talk.flow",AssistWire.payload("request_id",talkId,
                    "released_bytes",talkReleasedBytes),false,false);
            notifyAll();
        }
    }
    private synchronized void logTalkQueueFull(){
        long now=SystemClock.elapsedRealtime(),oldest=0L;int microphone=0,other=0;
        for(Outbound message:outbound){
            if("talk.audio".equals(message.type))microphone++;else other++;
            oldest=Math.max(oldest,now-message.queuedAt);
        }
        Log.i("GlassTalkDiag","INPUT_QUEUE_SNAPSHOT MIC_PENDING="+microphone+" OTHER_PENDING="+other
                +" OLDEST_MS="+oldest+(talkTrace==null?"":talkTrace.snapshot(now)));
    }
    private synchronized void talkAbort(boolean notifyPeer,String status,String cause){
        // Stop/focus/disconnect owns both branches; a late image can never restart speech.
        if(photoTalkId!=null)assistAbort(notifyPeer,"Фото отменено","TALK_ENDED",false);
        cameraButtonGate.cancel();
        talkKeyFloorUptime=-1L;
        String id=talkId;Socket owner=talkSocket;String ownedEpoch=talkEpoch;
        if(notifyPeer&&id!=null&&socket==owner&&epoch!=null&&epoch.equals(ownedEpoch)&&running&&!inbound.isStopped())
            talkStop=new Outbound("talk.stop",AssistWire.payload("request_id",id),false,false);
        if(talkPlayback!=null)talkPlayback.revoke();talkPlayback=null;
        talkId=null;talkSocket=null;talkEpoch=null;talkFlow=null;talkCredits=null;talkTrace=null;talkRevision++;talkDraining=false;talkInputSeen=false;
        if(id!=null)talkTranscript="";
        if(id!=null){
            talkStatus=status;talkDeadlineEnded="SESSION_DEADLINE".equals(cause);
            talkFailed=!("OWNER_TAP".equals(cause)||"OWNER_BACK".equals(cause)||"OWNER_STOP".equals(cause)
                    ||"PAUSED".equals(cause)||"FOCUS_LOST".equals(cause)||"SESSION_DEADLINE".equals(cause)
                    ||"LIFECYCLE_OR_REMOTE".equals(cause)||"DRAINED".equals(cause));
        } // Idle lifecycle callbacks must not invent a failed conversation.
        if(id!=null)Log.i("GlassTalkDiag","END_REASON="+cause);
        ui.removeCallbacks(talkTimeout);ui.removeCallbacks(talkStatusClock);
        GlassTalkAudio audio=talkAudio;talkAudio=null;if(audio!=null)audio.stop();
        for(Outbound message:outbound)if(message.type.startsWith("talk."))outbound.remove(message);
        notifyAll(); // Stop never waits for the idle polling interval.
        if(view!=null)ui.post(new Runnable(){public void run(){
            updateReadingScreen(false);view.invalidate();
        }});
    }
    /** The existing authenticated reader orders Talk controls and PCM. UI work never gates audio. */
    private void talkAccepted(final long token,final Socket connected,final Protocol.Envelope envelope){
        if(!"pixel".equals(envelope.sourceId))return;
        if("talk.audio".equals(envelope.type)){talkAudioAccepted(token,connected,envelope);return;}
        synchronized(this){
            if(!talkEnvelopeCurrent(token,connected,envelope))return;
            if("talk.ready".equals(envelope.type)){
                if(talkAudio!=null)return;
                Log.i("GlassTalkDiag","READY");
                final String id=talkId;final long revision=talkRevision;
                final TalkSendPolicy.Playback progress=new TalkSendPolicy.Playback();
                talkPlayback=progress;
                talkStatus="Разговор идёт";
                talkAudio=new GlassTalkAudio(new GlassTalkAudio.BorrowedInputListener(){
                    public void input(byte[] pcm){input(pcm,pcm.length);}
                    public void input(byte[] pcm,int length){
                        try{
                            // Base64 materialization must not occupy the Activity owner monitor:
                            // incoming audio and Stop need it independently of microphone encoding.
                            synchronized(GlassActivity.this){
                                if(revision!=talkRevision||talkDraining||!id.equals(talkId)||socket!=talkSocket||epoch==null||!epoch.equals(talkEpoch)
                                        ||!resumed||!windowFocused||!running||inbound.isStopped()||SystemClock.elapsedRealtime()>=talkDeadline)return;
                            }
                            final String encoded=AssistWire.encode(pcm,0,length);
                            final long lockAt=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():-1L;
                            synchronized(GlassActivity.this){
                            final long admissionWait=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime()-lockAt:-1L;
                            if(revision!=talkRevision||talkDraining||!id.equals(talkId)||socket!=talkSocket||epoch==null||!epoch.equals(talkEpoch)
                                    ||!resumed||!windowFocused||!running||inbound.isStopped()||SystemClock.elapsedRealtime()>=talkDeadline)return;
                            if(!talkInputSeen){
                                talkInputSeen=true;
                                ui.post(new Runnable(){public void run(){if(view!=null)view.invalidate();}});
                            }
                            Map<String,Object> p=AssistWire.payload("request_id",id,"index",talkInputIndex++,"data",encoded);
                            if(!outbound.offerLast(new Outbound("talk.audio",p,false,false))){
                                logTalkQueueFull();talkAbort(true,"Аудиоканал перегружен","INPUT_QUEUE_FULL");
                            }else{
                                if(talkTrace!=null)talkTrace.admitted(admissionWait);
                                GlassActivity.this.notifyAll();
                            }
                        }}finally{java.util.Arrays.fill(pcm,(byte)0);}
                    }
                    public void failed(){synchronized(GlassActivity.this){if(revision==talkRevision&&id.equals(talkId))talkAbort(true,"Ошибка аудио Glass","AUDIO_FAILURE");}}
                },new GlassTalkAudio.OutputProgress(){public void written(int bytes,int generation){
                    // No Activity/UI monitor on the real-time speaker. Old lifetime/turn
                    // completions cannot mutate a replacement's cumulative credits.
                    progress.completed(bytes,generation);
                }});
                queueTalkFlow(); // Initial grant: zero released bytes and a fixed 1.5-s window.
                final GlassTalkAudio starting=talkAudio;
                new Thread(new Runnable(){public void run(){starting.start();}},"glass-talk-start").start();
            }else if("talk.clear".equals(envelope.type)){
                Log.i("GlassTalkDiag","CLEAR");
                if(talkAudio!=null){
                    talkFlowGeneration++;talkReleasedBytes=talkAcceptedBytes;
                    if(talkPlayback==null||!talkPlayback.clear(talkFlowGeneration)){
                        talkAbort(true,"Аудиовыход прерван","OUTPUT_FLOW_ACCOUNTING");return;
                    }
                    talkAudio.clear();queueTalkFlow();
                }
            }else if("talk.camera".equals(envelope.type)){
                // Compatibility only: neither remote speech nor model output grants camera authority.
                Log.i("GlassTalkDiag","CAMERA_REMOTE_IGNORED");
            }else if("talk.text".equals(envelope.type)){
                if(TalkDiagnostics.ENABLED)Log.i("GlassTalkDiag","TRANSCRIPT_ROLE="+envelope.payload.get("role")
                        +" CHARS="+((String)envelope.payload.get("text")).length());
                talkTranscript=("assistant".equals(envelope.payload.get("role"))?"Франциска: ":"Вы: ")+(String)envelope.payload.get("text");
                String role=(String)envelope.payload.get("role"),text=(String)envelope.payload.get("text");
                Object utterance=envelope.payload.get("utterance_id");
                if(utterance instanceof String)talkLog.accept((String)utterance,role,text,Boolean.TRUE.equals(envelope.payload.get("final")));
                else talkLog.legacy(role,text);
                if(!talkTextRedrawPending){talkTextRedrawPending=true;ui.postDelayed(talkTextRedraw,100L);}
                return;
            }else if("talk.error".equals(envelope.type)){
                Log.i("GlassTalkDiag","ERROR_CODE="+envelope.payload.get("code"));
                if("TALK_CLOSED".equals(envelope.payload.get("code"))&&talkAudio!=null&&!talkDraining){
                    talkDraining=true;talkStatus="Дослушиваю ответ…";
                    talkDeadline=SystemClock.elapsedRealtime()+TALK_DRAIN_GRACE_MS;
                    ui.removeCallbacks(talkTimeout);ui.postDelayed(talkTimeout,TALK_DRAIN_GRACE_MS);
                    ui.removeCallbacks(talkStatusClock);ui.post(talkStatusClock);
                    final String id=talkId;final long revision=talkRevision;
                    talkAudio.finishOutput(new GlassTalkAudio.DrainListener(){public void complete(boolean drained){
                        synchronized(GlassActivity.this){
                            if(revision==talkRevision&&id.equals(talkId))
                                talkAbort(false,drained?"Разговор завершён · тап — снова":"Аудиовыход не завершился · тап — снова",drained?"DRAINED":"DRAIN_FAILED");
                        }
                    }});
                }else{
                    String code=(String)envelope.payload.get("code");
                    talkAbort(false,"TALK_TIMEOUT".equals(code)?"Время разговора вышло · тап — снова":
                            "TALK_CREDENTIALS".equals(code)?"Pixel не готов · открой Франциску":
                            "Связь прервалась · тап — снова","REMOTE_FAILURE");
                }
            }
            if(view!=null)view.postInvalidate();
        }
    }
    /** Caller holds the Activity owner lock; no deferred UI callback grants authority. */
    private synchronized boolean talkEnvelopeCurrent(long token,Socket connected,Protocol.Envelope envelope){
        return isOwned(token,connected)&&"pixel".equals(envelope.sourceId)&&talkId!=null
                &&talkId.equals(envelope.payload.get("request_id"))&&talkSocket==connected
                &&envelope.sessionId.equals(talkEpoch)&&resumed&&windowFocused
                &&!localStopRequested&&!terminalExitRequested&&!inbound.isStopped();
    }
    /** At most one wire-bounded batch on the existing reader, not another queue.
     * Decode outside owner lock; Stop/reconnect/clear must be rechecked before publication. */
    private void talkAudioAccepted(long token,Socket connected,Protocol.Envelope envelope){
        final long receivedAt=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():-1L;
        final long diagnosticOwner0At;
        final long revision;final int generation;
        final GlassTalkAudio audio;final TalkSendPolicy.Playback progress;
        synchronized(this){
            diagnosticOwner0At=TalkDiagnostics.monoMillis();
            if(!talkEnvelopeCurrent(token,connected,envelope))return;
            revision=talkRevision;generation=talkFlowGeneration;audio=talkAudio;progress=talkPlayback;
        }
        final long diagnosticDecodeAt=TalkDiagnostics.monoMillis(),diagnosticDecodeCpu=TalkDiagnostics.cpuNanos();
        int diagnosticIndex=-1,diagnosticActive=0;
        long diagnosticOwner1Ms=-1L,diagnosticAdmitMs=-1L,diagnosticAdmitCpu=-1L;
        long diagnosticAccepted=-1L,diagnosticCompleted=-1L;
        byte[][] decoded=null;
        try{
            Object raw=envelope.payload.get("parts");
            java.util.List<?> parts=raw instanceof java.util.List?(java.util.List<?>)raw:
                    java.util.Collections.singletonList(envelope.payload.get("data"));
            if(parts.size()<1||parts.size()>8)throw new ProtocolException("TALK_PARTS");
            decoded=new byte[parts.size()][];int total=0;
            for(int i=0;i<decoded.length;i++){
                decoded[i]=AssistWire.decode((String)parts.get(i));total+=decoded[i].length;
                if(total>TalkWire.BATCH_BYTES_LIMIT)throw new ProtocolException("TALK_PCM");
            }
            final long decodedAt=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():-1L;
            final long diagnosticDecodedCpu=TalkDiagnostics.cpuNanos();
            synchronized(this){
                final long diagnosticOwner1At=TalkDiagnostics.monoMillis();
                if(!talkEnvelopeCurrent(token,connected,envelope)||revision!=talkRevision
                        ||generation!=talkFlowGeneration||audio!=talkAudio||progress!=talkPlayback)return;
                if(SystemClock.elapsedRealtime()>=talkDeadline){
                    talkAbort(true,"Сеанс завершён · тап — снова","SESSION_DEADLINE");return;
                }
                int index=((Number)envelope.payload.get("index")).intValue();
                if(index!=talkOutputIndex++){talkAbort(true,"Аудиопоток прерван","OUTPUT_SEQUENCE");return;}
                long admittedAt=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():-1L;
                long diagnosticAdmitCpuAt=TalkDiagnostics.cpuNanos();
                for(byte[] pcm:decoded){
                    if(audio==null||progress==null){talkAbort(true,"Аудиовыход прерван","OUTPUT_FLOW_ACCOUNTING");break;}
                    // Publish accounting before the speaker can dequeue the cloned bytes.
                    if(!progress.admit(pcm.length)){talkAbort(true,"Сеанс завершён","OUTPUT_BYTE_BOUND");break;}
                    talkAcceptedBytes+=pcm.length;
                    if(!audio.play(pcm)){talkAbort(true,"Аудиовыход перегружен","OUTPUT_QUEUE_FULL");break;}
                }
                long diagnosticAdmittedAt=TalkDiagnostics.monoMillis();
                diagnosticAdmitCpu=TalkDiagnostics.delta(diagnosticAdmitCpuAt,TalkDiagnostics.cpuNanos());
                if(TalkDiagnostics.ENABLED&&(index==0||index%5==0)){
                    Log.i("GlassTalkDiag","OUTPUT_FRAME="+index
                        +" PCM_DECODE_MS="+(decodedAt-receivedAt)
                        +" OWNER_WAIT_MS="+(admittedAt-decodedAt)
                        +" ADMIT_MS="+(SystemClock.elapsedRealtime()-admittedAt));
                    diagnosticIndex=index;diagnosticOwner1Ms=TalkDiagnostics.delta(decodedAt,diagnosticOwner1At);
                    diagnosticAdmitMs=TalkDiagnostics.delta(admittedAt,diagnosticAdmittedAt);
                    if(progress!=null){
                        TalkSendPolicy.Playback.State diagnosticState=progress.snapshot();
                        diagnosticAccepted=diagnosticState.accepted;diagnosticCompleted=diagnosticState.completed;
                        diagnosticActive=diagnosticState.active&&!diagnosticState.failed?1:0;
                    }
                }
            }
            if(diagnosticIndex>=0)TalkDiagnostics.admission(diagnosticIndex,generation,diagnosticActive,
                    TalkDiagnostics.delta(receivedAt,diagnosticOwner0At),TalkDiagnostics.delta(diagnosticDecodeAt,decodedAt),
                    TalkDiagnostics.delta(diagnosticDecodeCpu,diagnosticDecodedCpu),diagnosticOwner1Ms,diagnosticAdmitMs,
                    diagnosticAdmitCpu,diagnosticAccepted,diagnosticCompleted);
        }catch(Exception invalid){
            synchronized(this){
                if(talkEnvelopeCurrent(token,connected,envelope)&&revision==talkRevision
                        &&generation==talkFlowGeneration&&audio==talkAudio&&progress==talkPlayback)
                    talkAbort(true,"Ошибка аудиоответа","OUTPUT_DECODE");
            }
        }finally{
            if(decoded!=null)for(byte[] pcm:decoded)if(pcm!=null)java.util.Arrays.fill(pcm,(byte)0);
        }
    }
    private void hidePreview() {
        if (cameraPreview != null) cameraPreview.setVisibility(View.INVISIBLE);
    }
    private void startCaptureWhenPreviewReady(final AssistCapture source, final long revision) {
        if (source != assistCapture || source == startedCapture || revision != assistRevision || !assistEligible()) return;
        if (!cameraPreview.isAvailable()) return;
        SurfaceTexture target = cameraPreview.getSurfaceTexture();
        if (target == null) return;
        source.setPreview(target);
        startedCapture = source;
        source.start();
    }
    private synchronized void maybeStartVoiceTurn() {
        if (!voiceStartPending || !assistMode) return;
        if (SystemClock.elapsedRealtime() > voiceStartDeadline) {
            voiceStartPending = false;
            assistUi.end(AssistPresentation.Stage.ERROR, "Pixel не готов · откройте Франциску", "VOICE_NOT_READY", SystemClock.elapsedRealtime());
            view.invalidate();
            return;
        }
        if (!assistEligible() || assistRequest != null || assistCapture != null || assistPump != null) return;
        voiceStartPending = false;
        final long ticket=++voiceCueGeneration;
        ToneGenerator audible=null;
        try { audible=new ToneGenerator(AudioManager.STREAM_MUSIC,70);audible.startTone(ToneGenerator.TONE_PROP_BEEP,120); }
        catch (RuntimeException unavailable) { if(audible!=null)audible.release();audible=null; }
        final ToneGenerator cue=audible;
        ui.postDelayed(new Runnable(){public void run(){
            if(cue!=null)cue.release();
            synchronized(GlassActivity.this){
                if(ticket==voiceCueGeneration && assistEligible()) assistInput("tap");
            }
        }},250L); // Speak after the cue; reconnect never starts a recording.
    }
    private void assistAccepted(final long token, final Socket connected, final Protocol.Envelope envelope) {
        if (!"pixel".equals(envelope.sourceId)) return;
        ui.post(new Runnable() { public void run() {
            synchronized (GlassActivity.this) {
                if (!isOwned(token, connected) || !envelope.sessionId.equals(epoch)
                        || localStopRequested || terminalExitRequested || inbound.isStopped()) return;
                if ("assist.status".equals(envelope.type)) {
                    String state = (String)envelope.payload.get("state");
                    assistReady = "ready".equals(state);
                    assistReadySocket = connected; assistReadyEpoch = epoch;
                    // Busy is expected while our committed request runs. Disabled/error revoke it.
                    if ("disabled".equals(state) || "error".equals(state)) {
                        voiceStartPending = false;
                        if ("error".equals(state) && photoTalkCurrent()) {
                            // A failed photo is not a failed voice session. Disabled still revokes both.
                            assistAbort(true, "Фото не отправлено", "PHOTO_FAILED", true);
                        } else {
                            talkAbort(true,"Помощник недоступен на Pixel","PEER_NOT_READY");
                            assistAbort(true, "Помощник недоступен на Pixel", "PIXEL_" + ("disabled".equals(state) ? "DISABLED" : "ERROR"), assistRequest != null);
                        }
                    }
                    assistUi.ready(assistReady);
                    maybeStartVoiceTurn();
                    if(pendingVision&&assistEligible()&&talkId==null){
                        if (voicePhotoGate.consumeHandoff(socket, epoch, talkRevision, SystemClock.elapsedRealtime())) {
                            pendingVision=false;ui.removeCallbacks(visionHandoffClock);
                            startAssistCapture(true);
                            if(!visionRequest)cancelVisionContinuation("Фото не начато · тап — к разговору", "PHOTO_HANDOFF_REJECTED");
                        } else cancelVisionContinuation("Фото не начато · тап — к разговору", "PHOTO_HANDOFF_EXPIRED");
                    }
                    maybeResumeVisionTalk();
                } else if ("assist.reply".equals(envelope.type) && assistRequest != null
                        && assistRequest.equals(envelope.payload.get("request_id"))
                        && assistRequestSocket == connected && epoch.equals(assistRequestEpoch)
                        && assistCapture == null && assistPump == null
                        && assistGate.reply(assistGateToken, connected, epoch, assistRequest, resumed && windowFocused,
                                SystemClock.elapsedRealtime())) {
                    // Paginate with the exact paint size used by the prism answer renderer.
                    view.paint.setTextSize(32);
                    boolean wasVision=visionRequest;
                    if (wasVision && !photoPublished) {
                        assistAbort(true, "Фото не сохранено · подтверждение отклонено", "PHOTO_NOT_PUBLISHED", true);
                        return;
                    }
                    if (wasVision && (!"conversation_image".equals(envelope.payload.get("photo_receipt"))
                            || photoImageSha256 == null || !photoImageSha256.equals(envelope.payload.get("image_sha256")))) {
                        assistAbort(true, "Получение фото не подтверждено · тап — к разговору", "PHOTO_RECEIPT_UNVERIFIED", true);
                        return;
                    }
                    // Authenticated companion verified this request's canonical managed-image claim.
                    // Hash binds the companion's received JPEG, not Gateway byte readback or model vision.
                    if(wasVision && photoTalkId!=null && !photoTalkCurrent()){
                        assistAbort(true,"Фото отменено","PHOTO_TALK_EXPIRED",false);return;
                    }
                    if(wasVision && photoTalkCurrent()){
                        // Pixel has received this exact image and submitted its agent answer to
                        // the existing voice. Local TTS and Talk recreation are deliberately absent.
                        assistRequest=null;assistRequestSocket=null;assistRequestEpoch=null;
                        assistBegan=false;visionRequest=false;visionResume=false;visionSpeechDone=false;
                        assistRevision++;assistGate.revoke();ui.removeCallbacks(assistClock);
                        clearPhotoTalk();photoNotice="Фото в беседе";talkSelected=true;
                        photoPublished=false;photoImageSha256=null;
                        assistUi.home(SystemClock.elapsedRealtime());
                        updateReadingScreen(false);view.invalidate();return;
                    }
                    String answer = wasVision ? PHOTO_TRANSFER_ACK : (String)envelope.payload.get("text");
                    assistPages = AssistPages.paginate(answer, view.measure);
                    assistPage = 0; assistRequest = null; assistRequestSocket = null; assistRequestEpoch = null;
                    visionRequest=false;
                    assistRevision++; assistGate.revoke();
                    assistUi.end(AssistPresentation.Stage.ANSWER, "Ответ Франциски", "", SystemClock.elapsedRealtime());
                    if (wasVision && visionResume) {
                        visionSpeechDone=false;
                        if (voicePhotoGate.acknowledgment(socket, epoch, assistRevision, SystemClock.elapsedRealtime()))
                            ui.postDelayed(visionAckClock, VoicePhotoGate.ACK_TIMEOUT_MS);
                        else cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_ACK_REJECTED");
                    }
                    if (assistSpeech != null) assistSpeech.say(answer);
                    else if(wasVision)voicePhotoUnavailable();
                    maybeResumeVisionTalk();
                    assistUi.page(SystemClock.elapsedRealtime()); updateReadingScreen(false);
                }
                view.invalidate();
            }
        } });
    }

    private synchronized boolean startVisionHandoff(long receivedAt, long frameTtl) {
        long now=SystemClock.elapsedRealtime();
        if(pendingVision || visionRequest || assistRequest!=null || talkDraining || talkId==null
                || talkAudio==null || !talkPhotoV1 || !resumed || !windowFocused
                || !running || localStopRequested || terminalExitRequested || inbound.isStopped()
                || socket!=talkSocket || epoch==null || !epoch.equals(talkEpoch)
                || receivedAt<0L || now<receivedAt || frameTtl<=0L
                || now-receivedAt>=Math.min(frameTtl,VoicePhotoGate.HANDOFF_TIMEOUT_MS)
                || now>=talkDeadline)return false;
        photoTalkId=talkId;photoTalkRevision=talkRevision;
        photoNotice="Снимаю фото";visionResume=false;visionSpeechDone=false;
        startAssistCapture(true);
        if(!visionRequest){clearPhotoTalk();photoNotice="Фото не начато";return false;}
        return true;
    }

    /** An error/timeout permits a new owner tap; it never opens the mic by itself. */
    private synchronized void cancelVisionContinuation(String text, String code) {
        cameraButtonGate.cancel();
        pendingVision=false;visionResume=false;visionSpeechDone=false;
        voicePhotoGate.clear();
        ui.removeCallbacks(visionHandoffClock); ui.removeCallbacks(visionAckClock);
        if (assistSpeech != null) assistSpeech.stop();
        talkSelected=true;talkStatus=text;
        Log.i("GlassAssist",code);
        if (view != null) view.invalidate();
    }

    private synchronized void voicePhotoUnavailable() {
        assistVoiceUnavailable=true;
        if (voicePhotoGate.acknowledgmentPending())
            cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_ACK_UNAVAILABLE");
        if (view != null) view.invalidate();
    }

    private synchronized void voicePhotoFinished() {
        if (voicePhotoGate.finished(socket, epoch, assistRevision, SystemClock.elapsedRealtime())) {
            visionSpeechDone=true;maybeResumeVisionTalk();
        } else if (voicePhotoGate.acknowledgmentPending() && voicePhotoGate.expired(SystemClock.elapsedRealtime()))
            cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_ACK_EXPIRED");
    }

    private synchronized void maybeResumeVisionTalk(){
        if(!visionResume||!visionSpeechDone||!assistEligible()||talkId!=null||assistRequest!=null
                ||assistCapture!=null||assistPump!=null)return;
        if (!voicePhotoGate.consumeResume(socket, epoch, assistRevision, SystemClock.elapsedRealtime())) {
            cancelVisionContinuation("Тап — обсудить снимок", "PHOTO_RESUME_REVOKED");return;
        }
        ui.removeCallbacks(visionAckClock);
        visionResume=false;visionSpeechDone=false;talkSelected=true;
        talkStart();
    }

    private static String captureFailureText(AssistCapture.Failure code) {
        switch (code) {
            case MIC_INIT: case MIC_START: case MIC_READ: return "Ошибка микрофона · тап — снова";
            case AUDIO_EMPTY: return "Голос не записан · тап — снова";
            case AUDIO_TIMEOUT: return "Микрофон не завершил запись";
            case WAV_INVALID: return "Ошибка аудиозаписи";
            case JPEG_EMPTY: case JPEG_INVALID: return "Фото не прошло проверку";
            case PHOTO_TIMEOUT: return "Камера не завершила фото";
            case UNSUPPORTED: return "Запись не поддерживается";
            default: return "Ошибка камеры · тап — снова";
        }
    }
    private synchronized void assistBack() {
        voiceStartPending = false;
        cancelPendingInput();
        if(talkId!=null){talkAbort(true,"Разговор отменён · тап — снова","OWNER_BACK");return;}
        if (assistRequest != null || assistCapture != null || assistPump != null) {
            assistAbort(true, "Вопрос отменён · тап — снова", "OWNER_CANCEL", false);
        } else {
            // Exit this foreground UI only. Whole-link mode.stop is the explicit hold action.
            terminalExitRequested = true; shutdownLink(); finish();
        }
    }

    private static final long PING_INTERVAL_MS = 5000L;
    private static final long FRAME_DEADLINE_MS = 3000L;
    private static final long HANDSHAKE_DEADLINE_MS = 3000L;
    private static final long READ_OPERATION_DEADLINE_MS = 10000L;
    private static final long WRITE_OPERATION_DEADLINE_MS = 3000L;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int OUTBOUND_CAPACITY = 16;

    private final Handler ui = new Handler();
    private final SessionState inbound = new SessionState();
    private final LinkedBlockingDeque<Outbound> outbound =
            new LinkedBlockingDeque<Outbound>(OUTBOUND_CAPACITY);
    private final Map<String, Long> pendingPings = new LinkedHashMap<String, Long>();

    private final ReadingScreenPolicy readingScreen = new ReadingScreenPolicy();
    private Runnable readingCallback;
    private final InputFeedback inputFeedback = new InputFeedback();
    private Runnable feedbackCallback;
    private final Runnable feedbackRefresh = new Runnable() {
        @Override public void run() { renderInputFeedback(); }
    };
    private synchronized void refreshInputFeedback() {
        // Coalesce link-thread results; UI callbacks only repaint current state.
        ui.removeCallbacks(feedbackRefresh);
        ui.post(feedbackRefresh);
    }
    private synchronized void renderInputFeedback() {
        if (feedbackCallback != null) ui.removeCallbacks(feedbackCallback);
        feedbackCallback = null;
        if (view == null) return;
        view.invalidate();
        final long revision = inputFeedback.revision();
        long remaining = inputFeedback.remaining(SystemClock.elapsedRealtime(), revision);
        if (remaining > 0 && resumed && windowFocused) {
            feedbackCallback = new Runnable() {
                @Override public void run() {
                    if (revision == inputFeedback.revision()) renderInputFeedback();
                }
            };
            ui.postDelayed(feedbackCallback, remaining); // No renewal or screen request.
        }
    }
    private void clearInputFeedback() {
        inputFeedback.clear();
        refreshInputFeedback();
    }
    private void inputWriteFeedback(ConnectionInputGate.Ticket ticket, boolean sent) {
        if (inputFeedback.written(ticket, sent, SystemClock.elapsedRealtime())) refreshInputFeedback();
    }
    private void inputWriteUncertain(ConnectionInputGate.Ticket ticket) {
        if (inputFeedback.uncertain(ticket, SystemClock.elapsedRealtime())) refreshInputFeedback();
    }
    private volatile boolean windowFocused;
    private final TapDiagnostics diagnostics = new TapDiagnostics();
    private final TapDiagnostics.Sink diagnosticSink = new TapDiagnostics.Sink() {
        public void log(TapDiagnostics.Category category) {
            Log.i("GlassChecklistInput", category.name());
        }
    };
    private void diagnostic(TapDiagnostics.Category category) {
        diagnostics.record(category, SystemClock.elapsedRealtime(), diagnosticSink);
    }
    private void cancelPendingInput() {
        cameraButtonGate.cancel();
        physicalInput.reset();
        if (view != null) view.cancelInput();
    }


    // Activity -> inbound lock order, identical to presentation/input. View writes are UI-only.
    private synchronized boolean readingEligible() {
        synchronized (inbound) {
            return readingScreen.eligible(socket, epoch, latestCardId(), running,
                    localStopRequested || terminalExitRequested || inbound.isStopped(),
                    resumed, windowFocused);
        }
    }

    private synchronized void updateReadingScreen(boolean renew) {
        if (readingCallback != null) ui.removeCallbacks(readingCallback);
        readingCallback = null;
        long now = SystemClock.elapsedRealtime();
        boolean eligible = readingEligible();
        if (renew) readingScreen.renew(now, eligible);
        final long revision = readingScreen.revision();
        long remaining = readingScreen.remaining(now, eligible, revision);
        if (assistMode && !localMirrorVisible) {
            remaining = assistUi.remaining(now, resumed && windowFocused && !localStopRequested && !terminalExitRequested);
            if(talkId!=null&&resumed&&windowFocused)remaining=Math.max(remaining,Math.max(0,talkDeadline-now));
        }
        view.setKeepScreenOn(displayActive() || remaining > 0);
        if (remaining > 0) {
            readingCallback = new Runnable() {
                @Override public void run() {
                    synchronized (GlassActivity.this) {
                        if (revision != readingScreen.revision()) return;
                        updateReadingScreen(false); // Only remaining absolute budget, never renewal.
                    }
                }
            };
            ui.postDelayed(readingCallback, remaining);
        }
    }

    private synchronized void releaseReadingScreen() {
        readingScreen.release();
        if (readingCallback != null) ui.removeCallbacks(readingCallback);
        readingCallback = null;
        view.setKeepScreenOn(false);
    }

    // Called under Activity monitor from link threads: revoke now, render release on main.
    private void invalidateReadingCard() {
        cancelPendingInput();
        clearInputFeedback();
        readingScreen.clear();
        if (readingCallback != null) ui.removeCallbacks(readingCallback);
        readingCallback = null;
        ui.post(new Runnable() {
            @Override public void run() { updateReadingScreen(false); }
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        boolean reacquired = hasFocus && !windowFocused;
        windowFocused = hasFocus;
        diagnostics.lifecycle(resumed, hasFocus, SystemClock.elapsedRealtime());
        if (!hasFocus) {
            displayCancel(true);
            voiceStartPending = false; voiceCueGeneration++;
            if (assistSpeech != null) assistSpeech.stop();
            synchronized (this) {
                // A queued server call must not remain confirmable after the
                // owner leaves this screen; report cancellation to Pixel.
                if (callPrompt.stage() == GlassCallPrompt.Stage.READY) {
                    String id = callPrompt.cancel(socket, epoch);
                    if (id != null) sendCallCancel(id);
                } else if (callPrompt.stage() == GlassCallPrompt.Stage.CONFIRM_QUEUED
                        || callPrompt.stage() == GlassCallPrompt.Stage.WAITING_RESULT) {
                    callPrompt.failUncertain();
                }
            }
        }
        if (!hasFocus){talkAbort(true,"Разговор остановлен · потерян фокус","FOCUS_LOST");assistAbort(true, "Вопрос отменён · потерян фокус", "FOCUS_LOST", false);}
        if (!hasFocus) cancelPendingInput();
        if (!hasFocus) clearInputFeedback();
        if (view == null) return;
        if (hasFocus) updateReadingScreen(reacquired);
        else releaseReadingScreen();
        if (hasFocus) maybeStartVoiceTurn();
    }


    private DisplayCalibration.Gate displayGate;
    private boolean displayPeer, displayBackUp;
    private final Runnable displayTimer=new Runnable(){public void run(){
        synchronized(GlassActivity.this){
            if(displayGate==null)return;
            Map<String,Object> expired=displayGate.expire(SystemClock.elapsedRealtime());
            if(expired!=null)displayReply(expired);
            displayRender();
        }
    }};
    private void displayInit(){
        lab.franziska.protocol.DisplayProfileStore store=new lab.franziska.protocol.DisplayProfileStore(getFilesDir());
        DisplayCalibration.Profile saved=store.active();
        displayGate=new DisplayCalibration.Gate(store,saved);
        DisplayPaint.profile=saved;
    }
    private boolean displayActive(){return displayGate!=null&&displayGate.active();}
    private synchronized void displayRender(){
        if(displayGate==null||view==null)return;
        DisplayPaint.profile=displayGate.applied();
        android.view.WindowManager.LayoutParams attrs=getWindow().getAttributes();
        attrs.screenBrightness=resumed?DisplayPaint.profile.brightness/100f:-1f;
        if(DisplayPaint.profile.brightness==-1)attrs.screenBrightness=-1f;
        getWindow().setAttributes(attrs);
        ui.removeCallbacks(displayTimer);
        if(displayGate.active()){
            view.setKeepScreenOn(true);
            ui.postDelayed(displayTimer,Math.max(1,displayGate.remaining(SystemClock.elapsedRealtime())));
        }else updateReadingScreen(false);
        view.invalidate();
    }
    private synchronized void displayCancel(boolean notify){
        if(displayGate==null)return;
        if(displayGate.active()){
            // Expiry produces the same complete applied-state receipt, then revokes the preview.
            Map<String,Object> result=displayGate.expire(Long.MAX_VALUE-1);
            if(notify&&result!=null){result.put("status","cancelled");displayReply(result);}
        }
        displayRender();
    }
    private void displayReply(Map<String,Object> state){
        if(displayPeer&&socket!=null&&running){
            outbound.offerFirst(new Outbound("display.state",state,false,false));notifyAll();
        }
    }
    private void displayAccepted(final long token,final Socket connected,final Protocol.Envelope envelope){
        final long received=SystemClock.elapsedRealtime();
        ui.post(new Runnable(){public void run(){synchronized(GlassActivity.this){
            if(!isOwned(token,connected)||!displayPeer||!envelope.sessionId.equals(epoch)
                ||!"pixel".equals(envelope.sourceId)||!resumed||!windowFocused||inbound.isStopped()
                ||SystemClock.elapsedRealtime()-received>envelope.ttlMs)return;
            Map<String,Object> expired=displayGate.expire(SystemClock.elapsedRealtime());
            if(expired!=null)displayReply(expired);
            boolean eligible=talkId==null&&assistRequest==null&&assistCapture==null&&assistPump==null
                &&(assistMode||inbound.latestUi()==null)&&assistPages==null&&!view.talkCard.reading()&&!pendingVision&&!visionResume&&!voiceStartPending&&!callPrompt.visible()
                &&!localMirrorVisible&&!localStopRequested&&!terminalExitRequested;
            Map<String,Object> result=displayGate.command(connected,epoch,envelope.payload,eligible,SystemClock.elapsedRealtime());
            if(result!=null&&"begin".equals(envelope.payload.get("op"))&&"applied".equals(result.get("status"))){
                cancelPendingInput();voiceCueGeneration++;
            }
            displayRender(); // Window and native color state applied before ACK.
            if(result!=null)displayReply(result);
        }}});
    }

    private GlassView view;
    private volatile boolean running;
    private volatile boolean localStopRequested;
    private volatile boolean terminalExitRequested;
    private volatile Socket socket;
    private volatile boolean identityCreating;
    private volatile AppIdentityStore.LifecycleToken identityToken;
    private volatile long generation;
    private volatile String epoch;
    private long sequence;
    private final ConnectionInputGate inputGate = new ConnectionInputGate();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        displayInit();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        assistMode = assistantEntry();
        if (assistMode) {
            assistPhoto = photoEntry();
            talkSelected=!assistPhoto;
            assistUi.enter(SystemClock.elapsedRealtime());
            assistSpeech = new AssistSpeech(this, ui, new AssistSpeech.Listener() {
                public void unavailable() { voicePhotoUnavailable(); }
                public void finished() { voicePhotoFinished(); }
            });
            voiceStartPending = GLASS_VOICE_TRIGGER.equals(getIntent().getAction());
            voiceStartDeadline = SystemClock.elapsedRealtime() + 15000L;
            if (voiceStartPending) ui.postDelayed(new Runnable() { public void run() { maybeStartVoiceTurn(); } }, 15000L);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        cameraPreview = new TextureView(this);
        cameraPreview.setVisibility(View.INVISIBLE);
        cameraPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                synchronized (GlassActivity.this) {
                    if (assistCapture != null && (assistPhoto||visionRequest)) startCaptureWhenPreviewReady(assistCapture, assistRevision);
                }
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                synchronized (GlassActivity.this) {
                    if (assistCapture != null && (assistPhoto||visionRequest)) assistAbort(true, "Камера закрыта", "PREVIEW_LOST", false);
                }
                return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
        });
        root.addView(cameraPreview, new FrameLayout.LayoutParams(-1,-1));
        view = new GlassView();
        root.addView(view, new FrameLayout.LayoutParams(-1,-1));
        setContentView(root);
        startLink();
    }

    @Override protected void onDestroy() {
        stopObservingBattery();
        if (assistSpeech != null) assistSpeech.stop();
        talkAbort(true,"Разговор остановлен");
        terminalExitRequested = true;
        cancelPendingInput();
        clearInputFeedback();
        diagnostics.lifecycle(false, false, SystemClock.elapsedRealtime());
        releaseReadingScreen();
        cancelIdentityCreation();
        shutdownLink();
        super.onDestroy();
    }

    private volatile boolean resumed;
    private final ChecklistInput physicalInput = new ChecklistInput();

    @Override protected void onResume() {
        super.onResume();
        observeBattery();
        resumed = true;
        displayRender();
        if (!running && !localStopRequested && !terminalExitRequested && !inbound.isStopped()) startLink();
        diagnostics.lifecycle(true, windowFocused, SystemClock.elapsedRealtime());
        view.requestFocus();
        updateReadingScreen(true);
        maybeStartVoiceTurn();
    }

    @Override protected void onPause() {
        stopObservingBattery();
        resumed = false;
        displayCancel(true);
        ui.removeCallbacks(callTimeout); ui.removeCallbacks(callResultTimeout);
        if (callPrompt.visible()) callPrompt.failUncertain();
        localMirrorVisible = false;
        voiceStartPending = false;
        voiceCueGeneration++;
        if (assistSpeech != null) assistSpeech.stop();
        talkAbort(true,"Разговор остановлен · приложение скрыто","PAUSED");
        assistAbort(true, "Вопрос отменён · приложение скрыто", "PAUSED", false);
        // Only the foreground native entry owns the shared transport implementation.
        shutdownLink();
        diagnostics.lifecycle(false, windowFocused, SystemClock.elapsedRealtime());
        cancelPendingInput();
        clearInputFeedback();
        releaseReadingScreen();
        super.onPause();
    }

    @Override protected void onStop() {
        resumed = false;
        diagnostics.lifecycle(false, windowFocused, SystemClock.elapsedRealtime());
        cancelPendingInput();
        clearInputFeedback();
        releaseReadingScreen();
        super.onStop();
    }

    /** Native key clocks are uptime; handoff clocks are elapsed realtime. Never mix them. */
    private synchronized boolean cameraKeyScope() {
        return cameraButtonGate.claimed() || (assistMode && (talkId != null || pendingVision
                || visionRequest || voicePhotoGate.acknowledgmentPending()));
    }
    private synchronized boolean cameraKeyEligible() {
        return assistMode && resumed && windowFocused && running && !localStopRequested
                && !terminalExitRequested && !inbound.isStopped() && !localMirrorVisible
                && !callPrompt.visible() && talkId != null && !talkDraining
                && socket != null && socket == talkSocket && epoch != null && epoch.equals(talkEpoch)
                && SystemClock.elapsedRealtime() < talkDeadline && !pendingVision && !visionRequest
                && !voicePhotoGate.acknowledgmentPending() && assistRequest == null
                && assistCapture == null && assistPump == null && assistCancel == null;
    }
    private synchronized boolean cameraKeyDown(KeyEvent event) {
        if (!cameraKeyScope()) return false;
        if (event == null) { cameraButtonGate.cancel(); return true; }
        cameraButtonGate.press(socket, epoch, talkId, talkRevision, event.getDownTime(),
                event.getEventTime(), SystemClock.uptimeMillis(), event.getRepeatCount(),
                event.isCanceled() || event.isLongPress(), cameraKeyEligible()
                        && talkKeyFloorUptime >= 0L && event.getDownTime() > talkKeyFloorUptime);
        return true;
    }
    private synchronized boolean cameraKeyUp(KeyEvent event) {
        if (!cameraKeyScope()) return false;
        if (event == null) { cameraButtonGate.cancel(); return true; }
        long uptime = SystemClock.uptimeMillis();
        boolean accepted = cameraButtonGate.release(socket, epoch, talkId, talkRevision,
                event.getDownTime(), event.getEventTime(), uptime,
                event.isCanceled() || event.isLongPress() || event.getRepeatCount() != 0,
                cameraKeyEligible());
        if (accepted) {
            long age = uptime - event.getEventTime();
            long elapsed = SystemClock.elapsedRealtime();
            if(!talkPhotoV1){photoNotice="Фото недоступно";view.invalidate();}
            else if (elapsed >= age && startVisionHandoff(elapsed - age, VoicePhotoGate.HANDOFF_TIMEOUT_MS))
                Log.i("GlassAssist", "CAMERA_BUTTON_ACCEPTED");
        }
        return true; // Rejected/revoked releases must not fall through to stock photo/video.
    }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA && cameraKeyScope()) {
            cameraButtonGate.cancel(); return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }
    @Override public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CAMERA && cameraKeyScope()) {
            cameraButtonGate.cancel(); return true;
        }
        return super.onKeyMultiple(keyCode, count, event);
    }

    private String keyAction(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) return "tap";
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return "next";
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) return "previous";
        return null;
    }

    private void keyEntry(int code) {
        String action = keyAction(code);
        diagnostic(action == null ? TapDiagnostics.Category.KEY_OTHER
                : "tap".equals(action) ? TapDiagnostics.Category.KEY_CONFIRM : TapDiagnostics.Category.KEY_NAV);
    }
    @Override public void onBackPressed(){if(displayActive()){displayCancel(true);return;}super.onBackPressed();}

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(displayActive()){if(keyCode==KeyEvent.KEYCODE_BACK){displayBackUp=true;displayCancel(true);}return true;}
        if (keyCode == KeyEvent.KEYCODE_CAMERA && cameraKeyDown(event)) return true;
        keyEntry(keyCode);
        if (!resumed || !windowFocused) { physicalInput.reset(); return super.onKeyDown(keyCode, event); }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (assistMode) {
                // Commit BACK only on a short, uncancelled release, like other keys.
                physicalInput.down(keyCode, event.getRepeatCount(), event.getDownTime(),
                        event.isLongPress() || event.isCanceled());
            } else requestLocalStop("back", true);
            return true;
        }
        if (keyAction(keyCode) != null) {
            physicalInput.down(keyCode, event.getRepeatCount(), event.getDownTime(),
                    event.isLongPress());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if(displayBackUp&&keyCode==KeyEvent.KEYCODE_BACK){displayBackUp=false;return true;}
        if(displayActive())return true;
        if (keyCode == KeyEvent.KEYCODE_CAMERA && cameraKeyUp(event)) return true;
        keyEntry(keyCode);
        String action = assistMode && keyCode == KeyEvent.KEYCODE_BACK ? "exit" : keyAction(keyCode);
        if (action != null) {
            long down = physicalInput.downAt();
            if (physicalInput.up(keyCode, event.getEventTime(), event.isCanceled()
                    || event.getDownTime() != down
                    || !(event.getRepeatCount() == 0 && !event.isLongPress())))
                dispatchInput(action, "key", down, event.getEventTime());
            else diagnostic(TapDiagnostics.Category.REJECTED);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void dispatchInput(String action, String route, long down, long time) {
        if(displayActive()){if("exit".equals(action))displayCancel(true);return;}
        diagnostic(TapDiagnostics.Category.RECOGNIZED);
        if (!resumed || !windowFocused || terminalExitRequested) {
            diagnostic(TapDiagnostics.Category.REJECTED); return;
        }
        if ("exit".equals(action) && localMirrorVisible) {
            localMirrorVisible = false; updateReadingScreen(false); view.invalidate(); return;
        }
        if ("exit".equals(action) && callPrompt.visible()) {
            if (physicalInput.route(route, action, down, time)) {
                String id = callPrompt.cancel(socket, epoch);
                if (id != null) sendCallCancel(id);
                else callPrompt.clear();
                view.invalidate();
            }
            return;
        }
        if ("exit".equals(action)) {
            if (assistMode) {
                if (physicalInput.route(route, action, down, time)) assistBack();
            } else requestLocalStop("down", true);
            return;
        }
        if (!physicalInput.route(route, action, down, time)) {
            diagnostic(TapDiagnostics.Category.DEDUPED); return;
        }
        inputFeedback.recognized(SystemClock.elapsedRealtime());
        refreshInputFeedback();
        if (callPrompt.visible()) {
            if (callPrompt.stage() == GlassCallPrompt.Stage.RESULT) callPrompt.clear();
            else if ("tap".equals(action)) {
                String id = callPrompt.confirm(socket, epoch, SystemClock.elapsedRealtime(),
                        down,
                        resumed && windowFocused && running && !localStopRequested
                                && !terminalExitRequested && !inbound.isStopped());
                if (id != null && !outbound.offerFirst(new Outbound("call.confirm", CallWire.payload(id), false, false)))
                    callPrompt.failUncertain();
                synchronized(this){notifyAll();}
            } else if ("next".equals(action) || "previous".equals(action)) {
                String id = callPrompt.cancel(socket, epoch);
                if (id != null) sendCallCancel(id);
            }
            view.invalidate(); return;
        }
        // Honor the displayed reconnect-only contract even after transport/ready recovered.
        // This tap changes presentation/restarts transport only; a later deliberate tap records.
        if ("tap".equals(action) && ((assistMode && assistUi.stage() == AssistPresentation.Stage.STOPPED)
                || (inbound.isStopped() && !running))) {
            if (assistMode) assistUi.home(SystemClock.elapsedRealtime());
            startLink(); // Preserve explicit local restart, never clear remote Stop here.
            if (assistMode) { updateReadingScreen(false); view.invalidate(); }
            return;
        }
        if (assistMode && localMirrorVisible) {
            if ("next".equals(action) || "previous".equals(action)) {
                view.moveMirrorPage("next".equals(action) ? 1 : -1); return;
            }
            if ("tap".equals(action) || "exit".equals(action)) {
                localMirrorVisible = false;
                updateReadingScreen(false);
                view.invalidate();
            }
            return;
        }
        if (assistMode) { assistInput(action); return; }
        enqueueInput(action, down); // Pixel owns progress; no optimistic local completion.
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (view != null && view.genericMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    private synchronized void startLink() {
        if (running || terminalExitRequested) return;
        AppIdentityStore.IdentityState identityState = AppIdentityStore.identityState(
                getFilesDir(), "glass", getPackageName());
        if (identityState == AppIdentityStore.IdentityState.ABSENT) {
            createIdentityAsync();
            return;
        }
        if (identityState != AppIdentityStore.IdentityState.READY) {
            view.status = "OFF / PARTIAL IDENTITY / REVOKE REQUIRED";
            view.invalidate();
            return;
        }
        if (!Provisioning.isComplete(this)) {
            view.status = "OFF / NOT PROVISIONED";
            return;
        }
        running = true;
        localStopRequested = false;
        // An old writer can only use its old, revoked gate and old socket.
        callWriteGate = new GlassCallWriteGate();
        final long token = ++generation;
        new Thread(new Runnable() {
            @Override public void run() { connectionLoop(token); }
        }, "glass-link-reader").start();
    }

    private synchronized void createIdentityAsync() {
        if (identityCreating || terminalExitRequested) return;
        identityCreating = true;
        final AppIdentityStore.LifecycleToken operation =
                AppIdentityStore.beginIdentityCreation(getFilesDir());
        identityToken = operation;
        view.status = "OFF / CREATING PRIVATE IDENTITY";
        view.invalidate();
        final long startedAt = SystemClock.elapsedRealtime();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AppIdentityStore.createIdentity(getFilesDir(), "glass", getPackageName(), operation,
                            new AppIdentityStore.Cancellation() {
                                public boolean cancelled() {
                                    return !AppIdentityStore.isCurrent(operation)
                                            || localStopRequested || terminalExitRequested
                                            || SystemClock.elapsedRealtime() - startedAt > 30000L;
                                }
                            }, new AppIdentityStore.StageListener() {
                                public void stage(String stage) {
                                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                                            && !localStopRequested && !terminalExitRequested) {
                                        postIdentityStatus(operation, "OFF / " + stage);
                                    }
                                }
                            });
                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                            && !localStopRequested && !terminalExitRequested) {
                        postIdentityStatus(operation,
                                "OFF / IDENTITY READY / PUBLIC BOOTSTRAP REQUIRED");
                    }
                } catch (Exception failure) {
                    if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                            && !localStopRequested && !terminalExitRequested) {
                        postIdentityStatus(operation,
                                "OFF / IDENTITY CREATION FAILED / NO ROTATION");
                    }
                } finally {
                    if (identityToken == operation) {
                        identityCreating = false;
                        // Retain the completed token until Stop/destroy/new creation.
                        // Posted READY/FAIL UI still needs it; cancellation invalidates it.
                    }
                }
            }
        }, "glass-identity-create").start();
    }

    private void connectionLoop(final long token) {
        while (isCurrent(token)) {
            Socket connected = null;
            SecureTransport secure = null;
            try {
                AppIdentityStore.LoadedIdentity identity = AppIdentityStore.loadIdentity(getFilesDir());
                AppIdentityStore.TrustConfig trust = AppIdentityStore.loadTrust(getFilesDir(), false);
                secure = PeerRoute.connect(this, trust.address, trust.port, identity, trust.peerPin,
                        CONNECT_TIMEOUT_MS, HANDSHAKE_DEADLINE_MS, new PeerRoute.Owner() {
                            public void check() throws IOException {
                                if (!isCurrent(token) || localStopRequested || terminalExitRequested)
                                    throw new IOException("link stopped");
                            }
                            public void own(Socket raw) throws IOException {
                                if (!ownSocket(token, raw)) throw new IOException("link stopped");
                            }
                        });
                connected = secure.socket();
                if (!isOwned(token, connected)) throw new IOException("link stopped");
                serveConnection(token, connected, secure);
            } catch (Exception failure) {
                if (isCurrent(token) && !localStopRequested && !terminalExitRequested)
                    Log.i("GlassTalkDiag",failure instanceof Tls.FrameDeadlineException ? "LINK_READ_DEADLINE"
                            : failure instanceof ProtocolException ? "LINK_PROTOCOL_REJECTED"
                            : failure instanceof java.io.EOFException ? "LINK_READ_EOF" : "LINK_READ_FAILED");
                closeSocket(connected);
                if (secure != null) closeSecure(secure);
                if (isCurrent(token)) {
                    postStatus("DEGRADED / RETRY");
                    sleepBeforeRetry();
                }
            }
        }
    }

    private void serveConnection(final long token, final Socket connected,
            final SecureTransport secure) throws Exception {
        final Tls.ConnectionWatchdog deadlineOwner = new Tls.ConnectionWatchdog(
                connected, "glass-connection-deadline");
        try {
            serveWatchedConnection(token, connected, secure, deadlineOwner);
        } finally {
            // Includes failed/missing peer hello: never leave a detached deadline owner.
            deadlineOwner.close();
        }
    }

    private void serveWatchedConnection(final long token, final Socket connected,
            final SecureTransport secure, final Tls.ConnectionWatchdog deadlineOwner) throws Exception {
        final Tls.FrameReader input = Tls.connectionFrameReader(secure.input(), deadlineOwner,
                FRAME_DEADLINE_MS, READ_OPERATION_DEADLINE_MS);
        final Tls.FrameWriter output = new Tls.FrameWriter(secure.output(), deadlineOwner,
                WRITE_OPERATION_DEADLINE_MS);

        byte[] helloFrame = input.read();
        long helloReceivedAt = SystemClock.elapsedRealtime();
        Protocol.Envelope hello = Protocol.decode(helloFrame);
        if (!"hello".equals(hello.type)) throw new ProtocolException("EXPECTED_HELLO");
        synchronized (this) {
            if (!isOwned(token, connected)) throw new IOException("link stopped");
            inputGate.clear();
            invalidateReadingCard();
            discardQueuedInputs();
            if (inbound.sessionId() == null) inbound.beginEpoch(hello.sessionId);
            else inbound.reconnectEpoch(hello.sessionId);
            epoch = hello.sessionId;
            talkTextV2=false;displayPeer=false;displayGate.connect(connected,epoch);
            sequence = 0L;
            SessionState.Result helloResult = inbound.accept(
                    hello, helloReceivedAt, SystemClock.elapsedRealtime());
            if (helloResult != SessionState.Result.ACCEPTED)
                throw new ProtocolException("HELLO_REJECTED:" + helloResult);
            // TLS connectOwned completed AND peer hello accepted; socket existence is not auth.
            inputGate.authenticated(connected, hello.sessionId);
            assistHelloEpoch = null;
            queueAssistHello();
        }

        postStatus(inbound.isStopped()
                ? "STOPPED / authenticated / awaiting explicit mode.start"
                : "SECURE LINK");
        Thread writer = new Thread(new Runnable() {
            @Override public void run() { writerLoop(token, connected, output); }
        }, "glass-link-writer");
        writer.start();

        try {
            while (isOwned(token, connected)) {
                try {
                    long diagnosticReadCpuAt=TalkDiagnostics.cpuNanos();
                    long readStartedAt = TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():-1L;
                    byte[] frame = input.read();
                    long receivedAt = SystemClock.elapsedRealtime();
                    long diagnosticReceivedCpu=TalkDiagnostics.cpuNanos();
                    Protocol.Envelope envelope = Protocol.decode(frame);
                    long processedAt = SystemClock.elapsedRealtime();
                    long diagnosticProcessedCpu=TalkDiagnostics.cpuNanos();
                    int diagnosticAudioIndex=-1;
                    if ("talk.audio".equals(envelope.type)) {
                        int index=((Number)envelope.payload.get("index")).intValue();
                        if(TalkDiagnostics.ENABLED&&(index==0||index%5==0))diagnosticAudioIndex=index;
                        if(TalkDiagnostics.ENABLED&&(index==0||index%5==0))Log.i("GlassTalkDiag","LINK_AUDIO_INDEX="+index
                                +" READ_MS="+(receivedAt-readStartedAt)
                                +" DECODE_MS="+(processedAt-receivedAt));
                    }
                    SessionState.Result result = inbound.accept(
                            envelope, receivedAt, processedAt);
                    if (result != SessionState.Result.ACCEPTED) continue;
                    synchronized (this) {
                        if (isOwned(token, connected) && ("state".equals(envelope.type)
                                || "ui.vector_scene".equals(envelope.type)
                                || "ui.card".equals(envelope.type)
                                || "mode.stop".equals(envelope.type))) invalidateReadingCard();
                    }
                    handleAccepted(token, envelope, connected);
                    // File/Log observation is after accepted delivery and outside owner monitors.
                    if(diagnosticAudioIndex>=0)TalkDiagnostics.link(diagnosticAudioIndex,frame.length,
                            TalkDiagnostics.delta(diagnosticReadCpuAt,diagnosticReceivedCpu),
                            TalkDiagnostics.delta(diagnosticReceivedCpu,diagnosticProcessedCpu));
                } catch (Tls.IdleTimeoutException timeout) {
                    // Timeout only lets us observe shutdown; periodic ping is in writer.
                }
            }
        } finally {
            closeSocket(connected);
            writer.interrupt();
            if (isCurrent(token)) clearPeerTransientState();
        }
    }

    private void handleAccepted(final long token, final Protocol.Envelope envelope, final Socket connected) {
        if("capabilities".equals(envelope.type)){
            synchronized(this){if(isOwned(token,connected)&&"pixel".equals(envelope.sourceId)&&envelope.sessionId.equals(epoch)){talkTextV2=Boolean.TRUE.equals(envelope.payload.get("talk_text_v2"));
                talkPhotoV1=Boolean.TRUE.equals(envelope.payload.get("talk_photo_v1"));}}
            synchronized(this){
                if(isOwned(token,connected)&&"pixel".equals(envelope.sourceId)&&envelope.sessionId.equals(epoch)){
                    displayPeer=Boolean.TRUE.equals(envelope.payload.get(DisplayCalibration.CAPABILITY));
                    if(displayPeer){outbound.offerFirst(new Outbound("capabilities",
                        AssistWire.payload(DisplayCalibration.CAPABILITY,Boolean.TRUE),false,false));notifyAll();}
                }
            }
            return;
        }
        if ("display.command".equals(envelope.type)){displayAccepted(token,connected,envelope);return;}
        if (envelope.type.startsWith("call.")) { callAccepted(token, connected, envelope); return; }
        if (envelope.type.startsWith("talk.")) {talkAccepted(token,connected,envelope);return;}
        if (envelope.type.startsWith("assist.")) {
            assistAccepted(token, connected, envelope); return;
        }
        if ("ping".equals(envelope.type)) {
            enqueueAck(envelope.messageId);
            return;
        }
        if ("ack".equals(envelope.type)) {
            recordRtt(envelope);
            return;
        }
        if ("mode.start".equals(envelope.type)) {
            queueAssistHello();
            postStatus("SECURE LINK / explicitly restarted");
            return;
        }
        if ("mode.stop".equals(envelope.type)) {
            ui.removeCallbacksAndMessages(null);
            ui.post(new Runnable() {
                @Override public void run() { view.stop("remote"); }
            });
            shutdownLink();
            return;
        }
        if ("ui.card".equals(envelope.type) || "ui.vector_scene".equals(envelope.type)) {
            ui.post(new Runnable() {
                @Override public void run() {
                    // Lock order Activity -> SessionState, matching start/input lifecycle.
                    synchronized (GlassActivity.this) {
                        synchronized (inbound) {
                            if (isOwned(token, connected) && !localStopRequested && !terminalExitRequested
                                    && envelope.sessionId.equals(inbound.sessionId())
                                    && inbound.shouldPresent(envelope.messageId)) {
                                if(displayActive())return;
                                boolean localMirror = "ui.card".equals(envelope.type)
                                        && Boolean.TRUE.equals(envelope.payload.get("local_mirror"));
                                if (assistMode && (!localMirror || assistRequest != null
                                        || assistCapture != null || assistPump != null || talkId != null)) return;
                                if (assistMode) { localMirrorVisible = true; localMirrorPage = 0; }
                                view.apply(envelope);
                                // New presentation invalidates queued actions before its ACK.
                                // Already-polled actions are independently checked by ticket.card.
                                discardQueuedInputs();
                                // Received/applied only, not optical PASS or owner confirmation.
                                if ("ui.card".equals(envelope.type)) {
                                    inputGate.applied(connected, envelope.sessionId,
                                            envelope.messageId, SystemClock.elapsedRealtime());
                                    enqueueAck(envelope.messageId);
                                    readingScreen.applied(connected, envelope.sessionId, envelope.messageId);
                                    updateReadingScreen(true);
                                } else inputGate.clearCard();
                            }
                        }
                    }
                }
            });
        }
    }

    private void callAccepted(final long token, final Socket connected, final Protocol.Envelope envelope) {
        if(displayActive())return;
        if (!"pixel".equals(envelope.sourceId)) return;
        ui.post(new Runnable() { @Override public void run() {
            synchronized (GlassActivity.this) {
                if (!isOwned(token, connected) || !envelope.sessionId.equals(epoch)
                        || localStopRequested || terminalExitRequested || inbound.isStopped()) return;
                String id = (String) envelope.payload.get("request_id");
                if (!resumed || !windowFocused) {
                    if ("call.prepare".equals(envelope.type)) sendCallCancel(id);
                    return;
                }
                if ("call.prepare".equals(envelope.type)) {
                    // A call cannot piggyback on the microphone or a checklist tap.
                    if (talkId != null) talkAbort(true, "Разговор остановлен · запрос звонка");
                    if (assistRequest != null) assistAbort(true, "Вопрос отменён · запрос звонка");
                    localMirrorVisible = false;
                    discardQueuedInputs(); inputGate.clearCard();
                    cancelPendingInput();
                    callPrompt.begin(id, (String)envelope.payload.get("label"),
                            (String)envelope.payload.get("e164"), connected, epoch,
                            SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
                    ui.removeCallbacks(callTimeout); ui.removeCallbacks(callResultTimeout);
                    ui.postDelayed(callTimeout, GlassCallPrompt.LOCAL_WINDOW_MS);
                    view.invalidate();
                } else if ("call.result".equals(envelope.type)
                        && callPrompt.result(id, (String)envelope.payload.get("status"), connected, epoch)) {
                    ui.removeCallbacks(callTimeout); ui.removeCallbacks(callResultTimeout);
                    view.invalidate();
                }
            }
        }});
    }

    private synchronized void sendCallCancel(String id) {
        if (id != null && socket != null && epoch != null && running
                && !localStopRequested && !terminalExitRequested && !inbound.isStopped())
            outbound.offerFirst(new Outbound("call.cancel", CallWire.payload(id), false, false));
        notifyAll();
    }

    /** Nonblocking selection under the owner lock; FIFO and flow each make progress. */
    private synchronized Outbound pollOutbound(TalkSendPolicy policy,long token,Socket connected) {
        if(!isOwned(token,connected))return null;
        queueTalkFlow(false); // Flush a small residual credit on the existing <=100-ms writer poll.
        Outbound message=talkStop;
        if(message!=null){talkStop=null;return message;}
        if(policy.takeFlow(talkFlow!=null,!outbound.isEmpty() || (photoTalkCurrent() && assistPump!=null))){
            message=talkFlow;talkFlow=null;return message;
        }
        message=assistCancel;
        if(message!=null){assistCancel=null;return message;}
        message=assistHello;
        if(message!=null){assistHello=null;return message;}
        if(photoTalkCurrent() && assistPump!=null && policy.takePhoto(!outbound.isEmpty())){
            try{
                AssistPump.Frame frame=assistPump.next(epoch,SystemClock.elapsedRealtime());
                if(frame!=null)return new Outbound(frame.type,frame.payload,false,false);
            }catch(ProtocolException expired){assistAbort(true,"Фото не отправлено","SEND_TIMEOUT",true);}
        }
        message=outbound.pollFirst();
        if(message!=null)policy.regularTaken();
        return message;
    }

    /** Waiting never consumes FIFO work; Object.wait releases the Activity monitor.
     * Every dequeue is re-admitted atomically by pollOutbound after this wait. */
    private synchronized void awaitOutbound(long token,Socket connected,long waitMs) throws InterruptedException {
        if(isOwned(token,connected)&&talkStop==null&&talkFlow==null&&assistCancel==null
                &&assistHello==null&&assistPump==null&&outbound.isEmpty()) {
            // Only active audio needs credit-tail sampling; idle work is producer-signalled.
            wait(talkId!=null ? Math.min(100L,waitMs) : waitMs);
        }
    }

    private void writerLoop(long token, Socket connected, final Tls.FrameWriter output) {
        final GlassCallWriteGate callGate = callWriteGate;
        final TalkSendPolicy sendPolicy = new TalkSendPolicy();
        long nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
        try {
            while (isOwned(token, connected)) {
                long wait = Math.max(1L, nextPingAt - SystemClock.elapsedRealtime());
                Outbound message=pollOutbound(sendPolicy,token,connected);
                if (message == null) {
                    awaitOutbound(token,connected,wait);
                    message=pollOutbound(sendPolicy,token,connected);
                }
                if(message!=null&&message.sendTrace!=null){
                    long selectedAt=SystemClock.elapsedRealtime();
                    message.sendTrace.selected("talk.audio".equals(message.type),"talk.flow".equals(message.type),
                            selectedAt,selectedAt-message.queuedAt);
                }
                if (message == null) {
                    synchronized (this) {
                        if (assistPump != null && isOwned(token, connected)) {
                            try {
                                AssistPump.Frame frame = assistPump.next(epoch, SystemClock.elapsedRealtime());
                                if (frame != null) message = new Outbound(frame.type, frame.payload, false, false);
                            } catch (ProtocolException expired) { assistAbort(true, "Передача прервана · тап — снова", "SEND_TIMEOUT", true); }
                        }
                    }
                }
                if (!isOwned(token, connected)) {
                    if (message != null && message.inputTicket != null) {
                        diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                        inputWriteFeedback(message.inputTicket, false);
                    }
                    if (message != null && message.inputTicket == null
                            && !message.type.startsWith("assist.") && !message.type.startsWith("talk.") && message.generation == generation) {
                        synchronized(this) {
                            if(message.generation==generation) {
                                outbound.offerFirst(message);
                                notifyAll(); // A replacement writer may already be idle.
                            }
                        }
                    }
                    return;
                }
                if (message == null) {
                    if (SystemClock.elapsedRealtime() < nextPingAt) continue;
                    message = new Outbound("ping", emptyPayload(), false, false);
                    nextPingAt = SystemClock.elapsedRealtime() + PING_INTERVAL_MS;
                }
                if (message.generation != token) {
                    if (message.inputTicket != null) {
                        diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                        inputWriteFeedback(message.inputTicket, false);
                    }
                    continue;
                }
                Protocol.Envelope envelope;
                synchronized (this) {
                    synchronized (inbound) {
                        if (!isOwned(token, connected)) {
                            if (message.inputTicket != null) {
                                diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                                inputWriteFeedback(message.inputTicket, false);
                            }
                            continue;
                        }
                        if ("input.event".equals(message.type)
                                && !inputGate.writable(message.inputTicket, connected, epoch,
                                    !localStopRequested && !terminalExitRequested && !inbound.isStopped(),
                                    latestCardId(), SystemClock.elapsedRealtime())) {
                            diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                            inputWriteFeedback(message.inputTicket, false); continue;
                        }
                        if (message.type.startsWith("assist.") && !"assist.cancel".equals(message.type)
                                && !"assist.hello".equals(message.type)
                                && (assistRequest == null || !assistRequest.equals(message.payload.get("request_id"))
                                    || socket != assistRequestSocket || !epoch.equals(assistRequestEpoch)
                                    || !resumed || !windowFocused || inbound.isStopped()
                                    || (photoTalkId!=null && !photoTalkCurrent())
                                    || !assistGate.owns(assistGateToken, connected, epoch, assistRequest))) continue;
                        if(message.type.startsWith("talk.") && !"talk.stop".equals(message.type)
                                && (talkId==null||!talkId.equals(message.payload.get("request_id"))
                                    ||talkSocket!=connected||!epoch.equals(talkEpoch)||!resumed||!windowFocused||inbound.isStopped()))continue;
                        if ((message.type.startsWith("display.")||"capabilities".equals(message.type))
                                &&(message.callPeer!=connected||message.callEpoch==null||!message.callEpoch.equals(epoch)
                                    ||SystemClock.elapsedRealtime()-message.queuedAt>5000||inbound.isStopped()))continue;
                        if (message.type.startsWith("call.") && (message.callPeer != connected
                                || !message.callEpoch.equals(epoch) || inbound.isStopped())) continue;
                        if ("call.confirm".equals(message.type)
                                && !callPrompt.writableConfirmation((String)message.payload.get("request_id"),
                                        connected, epoch, SystemClock.elapsedRealtime(),
                                        resumed && windowFocused && !localStopRequested && !terminalExitRequested)) continue;
                        if ("assist.hello".equals(message.type) && (!assistMode || inbound.isStopped()
                                || localStopRequested || terminalExitRequested)) continue;
                        envelope = createEnvelope(message.type, message.payload);
                        if ("assist.begin".equals(message.type)) assistBegan = true;
                        // Admit the reply before entering the write: a fast peer can reply while
                        // Tls.write is returning. A failed write closes the epoch and revokes it.
                        if ("assist.commit".equals(message.type)) {
                            assistGate.written(assistGateToken, connected, epoch, assistRequest);
                            assistPump = null;
                        }
                        if (message.inputTicket != null) {
                            envelope.monotonicTimestamp = message.inputTicket.eventAt;
                            envelope.ttlMs = Math.min(envelope.ttlMs,
                                    message.inputTicket.deadline - SystemClock.elapsedRealtime());
                            if (envelope.ttlMs <= 0) {
                                diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                                inputWriteFeedback(message.inputTicket, false); continue;
                            }
                            diagnostic(TapDiagnostics.Category.WRITER_VALIDATED);
                        }
                    }
                }
                if ("ping".equals(message.type)) rememberPing(envelope.messageId);
                try {
                    final boolean diagnosticCredit="talk.flow".equals(message.type);
                    final boolean diagnosticMicrophone="talk.audio".equals(message.type);
                    final long diagnosticCounter=TalkDiagnostics.ENABLED&&(diagnosticCredit||diagnosticMicrophone)?TalkDiagnostics.number(message.payload.get(
                            diagnosticCredit?"released_bytes":"index")):-1L;
                    final boolean diagnosticSend=TalkDiagnostics.ENABLED&&(diagnosticCredit||(diagnosticMicrophone
                            &&diagnosticCounter>=0L&&diagnosticCounter%10L==0L));
                    if(message.sendTrace!=null)message.sendTrace.encoding(SystemClock.elapsedRealtime());
                    final long diagnosticEncodeAt=diagnosticSend?TalkDiagnostics.monoMillis():-1L;
                    final long diagnosticEncodeCpu=diagnosticSend?TalkDiagnostics.cpuNanos():-1L;
                    final byte[] frame = Protocol.encode(envelope);
                    if(message.sendTrace!=null)message.sendTrace.encoded(SystemClock.elapsedRealtime());
                    final long diagnosticEncodedAt=diagnosticSend?TalkDiagnostics.monoMillis():-1L;
                    final long diagnosticEncodedCpu=diagnosticSend?TalkDiagnostics.cpuNanos():-1L;
                    final long diagnosticWriteAt=diagnosticSend?TalkDiagnostics.monoMillis():-1L;
                    final long diagnosticWriteCpu=diagnosticSend?TalkDiagnostics.cpuNanos():-1L;
                    if ("call.confirm".equals(message.type)) {
                        // Stop revokes this gate and waits for an admitted write to finish.
                        // No Activity/inbound monitor is held across the bounded TLS write.
                        if (!callGate.send(new GlassCallWriteGate.Writer() {
                            public void write() throws IOException {
                                output.write(frame);
                            }
                        }, WRITE_OPERATION_DEADLINE_MS + 250L)) {
                            synchronized (this) {
                                callPrompt.failedConfirmation((String)message.payload.get("request_id"),
                                        connected, epoch);
                            }
                            ui.post(new Runnable() { public void run() { view.invalidate(); } });
                            continue;
                        }
                    } else output.write(frame);
                    if(message.sendTrace!=null)message.sendTrace.written(SystemClock.elapsedRealtime());
                    final long diagnosticWrittenAt=diagnosticSend?TalkDiagnostics.monoMillis():-1L;
                    final long diagnosticWrittenCpu=diagnosticSend?TalkDiagnostics.cpuNanos():-1L;
                    if ("call.confirm".equals(message.type)) synchronized (this) {
                        callPrompt.confirmationWritten((String)message.payload.get("request_id"), connected, epoch);
                    }
                    if ("assist.commit".equals(message.type)) {
                        synchronized (this) {
                            if (assistRequest != null && assistRequest.equals(message.payload.get("request_id"))) {
                                assistUi.advance(AssistPresentation.Stage.WAITING);
                                ui.post(new Runnable() { public void run() { view.invalidate(); } });
                            }
                        }
                    }
                    if (message.inputTicket != null) {
                        diagnostic(TapDiagnostics.Category.WRITE_SUCCESS);
                        inputWriteFeedback(message.inputTicket, true);
                    }
                    // Numbers only, after the original write/feedback actions; no owner lock held.
                    if(diagnosticSend)TalkDiagnostics.sent(diagnosticCredit,diagnosticCounter,
                            TalkDiagnostics.delta(message.queuedAt,diagnosticEncodeAt),
                            TalkDiagnostics.delta(diagnosticEncodeAt,diagnosticEncodedAt),
                            TalkDiagnostics.delta(diagnosticEncodeCpu,diagnosticEncodedCpu),
                            TalkDiagnostics.delta(diagnosticWriteAt,diagnosticWrittenAt),
                            TalkDiagnostics.delta(diagnosticWriteCpu,diagnosticWrittenCpu),frame.length);
                } catch (Exception failure) {
                    Log.i("GlassTalkDiag",failure instanceof Tls.WriteDeadlineException ? "LINK_WRITE_DEADLINE" : "LINK_WRITE_FAILED");
                    if (message.inputTicket != null) {
                        diagnostic(TapDiagnostics.Category.WRITER_REJECT);
                        inputWriteUncertain(message.inputTicket);
                    }
                    throw failure;
                }
                if (message.closeAfterWrite) {
                    shutdownLink(token);
                    if (message.finishActivity) ui.post(new Runnable() {
                        @Override public void run() { finish(); }
                    });
                    return;
                }
            }
        } catch (Exception failure) {
            closeSocket(connected);
            if (localStopRequested && isCurrent(token)) {
                shutdownLink(token);
                if (terminalExitRequested) ui.post(new Runnable() {
                    @Override public void run() { finish(); }
                });
            }
        }
    }

    private synchronized Protocol.Envelope createEnvelope(String type, Map<String, Object> payload) {
        Protocol.Envelope envelope = new Protocol.Envelope();
        envelope.protocolVersion = Protocol.VERSION;
        envelope.sessionId = epoch;
        envelope.messageId = UUID.randomUUID().toString();
        envelope.type = type;
        envelope.sourceId = "glass";
        envelope.modeId = type.startsWith("assist.")||type.startsWith("talk.")?"assist":"demo";
        envelope.sequence = ++sequence;
        envelope.monotonicTimestamp = SystemClock.elapsedRealtime();
        envelope.ttlMs = 5000L;
        envelope.idempotencyKey = envelope.messageId;
        envelope.payload = payload;
        return envelope;
    }

    private String latestCardId() {
        Protocol.Envelope latest = inbound.latestUi();
        return latest != null && "ui.card".equals(latest.type) ? latest.messageId : null;
    }

    private synchronized void enqueueInput(String action, long eventAt) {
        synchronized (inbound) {
            // Native input uses uptime; normalize once so queued actions expire through sleep
            // and use the same monotonic clock as the existing wire timestamps.
            long uptimeNow = SystemClock.uptimeMillis();
            long elapsedNow = SystemClock.elapsedRealtime();
            long elapsedEvent = ConnectionInputGate.elapsedEventTime(eventAt, uptimeNow, elapsedNow);
            ConnectionInputGate.Ticket ticket = inputGate.admit(socket, epoch,
                    running && !localStopRequested && !terminalExitRequested && !inbound.isStopped(),
                    latestCardId(), elapsedEvent, elapsedNow);
            if (ticket == null) {
                inputFeedback.notSent(SystemClock.elapsedRealtime());
                refreshInputFeedback();
                diagnostic(TapDiagnostics.Category.GATE_REJECTED);
                // Same cached clock samples and locks; observation cannot admit or retry input.
                ConnectionInputGate.Rejection reason = ConnectionInputGate.clockRejection(
                        eventAt, uptimeNow, elapsedNow);
                if (reason == ConnectionInputGate.Rejection.NONE)
                    reason = inputGate.rejection(socket, epoch,
                            running && !localStopRequested && !terminalExitRequested && !inbound.isStopped(),
                            latestCardId(), elapsedEvent, elapsedNow);
                else diagnostic(TapDiagnostics.rawElapsedRecency(eventAt, elapsedNow));
                diagnostic(TapDiagnostics.gate(reason));
                return;
            }
            diagnostic(TapDiagnostics.Category.GATE_ADMITTED);
            updateReadingScreen(true);
            LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("action", action);
            offerLatest(new Outbound("input.event", payload, false, false, ticket));
            inputFeedback.queued(ticket, SystemClock.elapsedRealtime());
            refreshInputFeedback();
        }
    }

    private void discardQueuedInputs() {
        for (Outbound message : outbound)
            if ("input.event".equals(message.type)) outbound.remove(message);
    }

    private synchronized void enqueueAck(String messageId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("reply_to", messageId);
        outbound.offerFirst(new Outbound("ack", payload, false, false));
        notifyAll();
    }

    private synchronized void requestLocalStop(String reason, boolean finishAfterWrite) {
        // This is the call-confirm/Stop ordering point. If the TLS write violates its
        // deadline, close that exact socket before presenting Stop.
        if (!callWriteGate.revoke(WRITE_OPERATION_DEADLINE_MS + 250L)) {
            Socket target = socket;
            try { if (target != null) target.close(); } catch (IOException ignored) { }
        }
        talkAbort(false,"Остановлено","OWNER_STOP");
        assistAbort(false, "Остановлено");
        assistPages = null;
        assistUi.end(AssistPresentation.Stage.STOPPED, "Связь остановлена", "OWNER_STOP", SystemClock.elapsedRealtime());
        assistReady = false; assistCancel = null; assistHello = null;
        inputGate.clear();
        invalidateReadingCard();
        releaseReadingScreen();
        discardQueuedInputs();
        cancelIdentityCreation();
        if (localStopRequested) {
            if (finishAfterWrite) {
                terminalExitRequested = true;
                shutdownLink();
                finish();
            }
            return;
        }
        if (finishAfterWrite) terminalExitRequested = true;
        localStopRequested = true;
        inbound.stopLocal();
        ui.removeCallbacksAndMessages(null);
        view.stop(reason);
        outbound.clear();
        if (socket == null || epoch == null) {
            shutdownLink();
            if (finishAfterWrite) finish();
            return;
        }
        outbound.offerFirst(new Outbound("mode.stop", emptyPayload(), true, finishAfterWrite));
        notifyAll();
        startStopWatchdog(generation, finishAfterWrite);
    }

    private void cancelIdentityCreation() {
        AppIdentityStore.LifecycleToken operation = identityToken;
        if (operation != null) AppIdentityStore.cancelIdentityCreation(operation);
    }

    private synchronized void offerLatest(Outbound message) {
        while (!outbound.offerLast(message)) outbound.pollFirst();
        notifyAll();
    }

    private void startStopWatchdog(final long token, final boolean finishAfterWrite) {
        final Socket target = socket;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (localStopRequested && isCurrent(token)
                        && (socket == target || socket == null)) {
                    shutdownLink(token);
                    if (finishAfterWrite) ui.post(new Runnable() {
                        @Override public void run() { finish(); }
                    });
                }
            }
        }, "glass-stop-watchdog").start();
    }

    private synchronized void rememberPing(String messageId) {
        if (pendingPings.size() >= 8) pendingPings.clear();
        pendingPings.put(messageId, Long.valueOf(SystemClock.elapsedRealtime()));
    }

    private synchronized void recordRtt(Protocol.Envelope ack) {
        Object reply = ack.payload.get("reply_to");
        Long sentAt = pendingPings.remove(String.valueOf(reply));
        if (sentAt != null) postStatus("SECURE LINK / RTT "
                + (SystemClock.elapsedRealtime() - sentAt.longValue()) + " ms");
    }

    private static Map<String, Object> emptyPayload() {
        return new LinkedHashMap<String, Object>();
    }

    private void shutdownLink() { shutdownLink(generation); }

    private void shutdownLink(long token) {
        Socket target;
        synchronized (this) {
            if (token != generation) return;
            running = false;
            assistDisconnected();
            invalidateReadingCard();
            inputGate.clear();
            generation++;
            target = socket;
            socket = null;
            outbound.clear();
            pendingPings.clear();
        }
        closeSocket(target);
    }

    private synchronized void clearPeerTransientState() {
        pendingPings.clear();
    }

    private static void closeSecure(SecureTransport target) {
        try { if (target != null) target.close(); } catch (IOException ignored) {}
    }

    private synchronized void closeSocket(Socket target) {
        try { if (target != null) target.close(); } catch (IOException ignored) {}
        if (socket == target) {
            assistDisconnected();
            invalidateReadingCard();
            inputGate.clear();
            discardQueuedInputs();
            socket = null;
        }
    }

    private synchronized boolean ownSocket(long token, Socket target) {
        if (!running || token != generation || localStopRequested || terminalExitRequested) {
            closeSocket(target);
            return false;
        }
        inputGate.clear();
        invalidateReadingCard();
        discardQueuedInputs();
        socket = target;
        return true;
    }

    private synchronized boolean isOwned(long token, Socket target) {
        return running && token == generation && socket == target;
    }

    private synchronized boolean isCurrent(long token) {
        return running && token == generation;
    }

    private synchronized boolean isCurrentGeneration() { return running; }

    private void postStatus(final String status) {
        final long token = generation;
        ui.post(new Runnable() {
            @Override public void run() {
                if (token == generation) {
                    view.status = status;
                    view.invalidate();
                }
            }
        });
    }

    private void postIdentityStatus(final AppIdentityStore.LifecycleToken operation,
                                    final String status) {
        ui.post(new Runnable() {
            public void run() {
                if (identityToken == operation && AppIdentityStore.isCurrent(operation)
                        && !localStopRequested && !terminalExitRequested) {
                    view.status = status;
                    view.invalidate();
                }
            }
        });
    }

    private static void sleepBeforeRetry() {
        try { Thread.sleep(1500L); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String read(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Protocol.UTF8));
        try { return reader.readLine(); } finally { reader.close(); }
    }

    private final class Outbound {
        final String type;
        final Map<String, Object> payload;
        final boolean closeAfterWrite;
        final boolean finishActivity;
        final long generation;
        final ConnectionInputGate.Ticket inputTicket;
        final long queuedAt;
        final TalkSendPolicy.Trace sendTrace;
        final Socket callPeer;
        final String callEpoch;

        Outbound(String type, Map<String, Object> payload,
                 boolean closeAfterWrite, boolean finishActivity) {
            this(type, payload, closeAfterWrite, finishActivity, null);
        }

        Outbound(String type, Map<String, Object> payload,
                 boolean closeAfterWrite, boolean finishActivity, ConnectionInputGate.Ticket ticket) {
            this.inputTicket = ticket;
            this.queuedAt = SystemClock.elapsedRealtime();
            this.sendTrace = type.startsWith("talk.") ? GlassActivity.this.talkTrace : null;
            this.callPeer = (type.startsWith("call.")||type.startsWith("display.")||"capabilities".equals(type)) ? GlassActivity.this.socket : null;
            this.callEpoch = (type.startsWith("call.")||type.startsWith("display.")||"capabilities".equals(type)) ? GlassActivity.this.epoch : null;
            this.type = type;
            this.payload = payload;
            this.closeAfterWrite = closeAfterWrite;
            this.finishActivity = finishActivity;
            this.generation = GlassActivity.this.generation;
        }
    }

    private final class GlassView extends View {
        final Paint paint = new DisplayPaint(Paint.ANTI_ALIAS_FLAG);
        final TalkCardRenderer talkCard = new TalkCardRenderer();
        final ChecklistText.Measurer measure = new ChecklistText.Measurer() {
            public float width(String text) { return paint.measureText(text); }
        };
        final GestureDetector gestures;
        String title = "Чек-лист";
        String[] lines = {"Откройте Pixel и отправьте список"};
        String status = "OFF";
        final TapGesture genericTap = new TapGesture(ViewConfiguration.get(GlassActivity.this).getScaledTouchSlop());
        final TapGesture touchTap = new TapGesture(ViewConfiguration.get(GlassActivity.this).getScaledTouchSlop());
        volatile String touchResult;
        long touchDown;

        GlassView() {
            super(GlassActivity.this);
            setBackgroundColor(Color.TRANSPARENT);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            // Standard Android GestureDetector, NOT the unavailable GDK detector.
            gestures = new GestureDetector(GlassActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    if ("tap".equals(touchResult))
                        dispatchInput("tap", "touch", touchDown, event.getEventTime());
                    else diagnostic(TapDiagnostics.Category.REJECTED);
                    return true;
                }
                @Override public void onLongPress(MotionEvent event) {
                    if (resumed && windowFocused && touchTap.active()) requestLocalStop("long press", false);
                }
                @Override public boolean onFling(MotionEvent first, MotionEvent second,
                        float velocityX, float velocityY) {
                    if (first == null || second == null || touchResult == null || "tap".equals(touchResult)) return false;
                    String action = ChecklistInput.swipe(second.getX() - first.getX(),
                            second.getY() - first.getY(), second.getEventTime() - first.getEventTime());
                    if (action == null) return false;
                    dispatchInput(action, "touch", touchDown, second.getEventTime());
                    return true;
                }
            });
        }

        void cancelInput() {
            genericTap.cancel(); touchTap.cancel(); touchResult = null;
            // Detector is UI-owned; link-thread invalidation synchronously revokes the pure guards.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                ui.post(new Runnable() { public void run() { cancelDetector(); } });
            } else cancelDetector();
        }
        void cancelDetector() {
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }
        boolean acceptedSource(MotionEvent event) {
            int source = event.getSource();
            return (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                    || (source & InputDevice.SOURCE_CLASS_POINTER) != 0;
        }
        String feed(TapGesture helper, MotionEvent event) {
            int count = event.getPointerCount();
            // Historical MOVE samples also participate in maximum excursion/time validation.
            for (int i = 0; count == 1 && i < event.getHistorySize(); i++) {
                helper.event(MotionEvent.ACTION_MOVE, count, count == 1 ? event.getPointerId(0) : -1,
                        event.getHistoricalX(0, i), event.getHistoricalY(0, i),
                        event.getDownTime(), event.getHistoricalEventTime(i));
            }
            return helper.event(event.getActionMasked(), count, count == 1 ? event.getPointerId(0) : -1,
                    count > 0 ? event.getX() : Float.NaN, count > 0 ? event.getY() : Float.NaN,
                    event.getDownTime(), event.getEventTime());
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            diagnostic(TapDiagnostics.motion(false, event.getActionMasked(), acceptedSource(event), event.getPointerCount()));
            if (!resumed || !windowFocused) { cancelInput(); return false; }
            touchResult = feed(touchTap, event);
            touchDown = touchTap.downAt();
            if (!touchTap.active() && event.getActionMasked() != MotionEvent.ACTION_UP) {
                cancelDetector(); diagnostic(TapDiagnostics.Category.REJECTED); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP && touchResult == null) {
                cancelDetector(); diagnostic(TapDiagnostics.Category.REJECTED); return true;
            }
            return gestures.onTouchEvent(event);
        }
        // Generic never enters GestureDetector; physical firmware route remains UNKNOWN.
        boolean genericMotion(MotionEvent event) {
            boolean accepted = acceptedSource(event);
            diagnostic(TapDiagnostics.motion(true, event.getActionMasked(), accepted, event.getPointerCount()));
            if (!resumed || !windowFocused || !accepted) { genericTap.cancel(); return false; }
            String result = feed(genericTap, event);
            if (result != null) dispatchInput(result, "motion", genericTap.downAt(), event.getEventTime());
            else if (event.getActionMasked() != MotionEvent.ACTION_MOVE
                    && (event.getActionMasked() != MotionEvent.ACTION_DOWN || !genericTap.active()))
                diagnostic(TapDiagnostics.Category.REJECTED);
            return true;
        }

        void stop(String reason) {
            boolean callMayHaveStarted = callPrompt.mayHaveStarted();
            callPrompt.clear();
            if (assistMode && callMayHaveStarted)
                assistUi.end(AssistPresentation.Stage.STOPPED,
                        "Вызов мог быть запрошен · проверьте Pixel", "CALL_UNCERTAIN",
                        SystemClock.elapsedRealtime());
            localMirrorVisible = false;
            releaseReadingScreen();
            cancelInput();
            clearInputFeedback();
            physicalInput.reset();
            title = "Остановлено";
            lines = new String[] {"Тап — подключиться снова"};
            if (callMayHaveStarted)
                lines = new String[] {"Вызов мог быть запрошен · проверьте Pixel", "Тап — подключиться снова"};
            status = "STOPPED: " + reason;
            invalidate();
        }

        void apply(Protocol.Envelope envelope) {
            cancelPendingInput();
            String previousTitle = title;
            String[] previousLines = lines;
            if ("ui.card".equals(envelope.type)) {
                title = String.valueOf(envelope.payload.get("title"));
                List<?> incoming = (List<?>) envelope.payload.get("lines");
                // Pixel supplies current step + progress/done labels in existing card fields.
                lines = new String[incoming.size()];
                for (int index = 0; index < incoming.size(); index++)
                    lines[index] = String.valueOf(incoming.get(index));
                if (resumed && windowFocused) {
                    inputFeedback.cardApplied(!title.equals(previousTitle)
                            || !java.util.Arrays.equals(lines, previousLines), SystemClock.elapsedRealtime());
                    refreshInputFeedback();
                } else clearInputFeedback();
            } else {
                clearInputFeedback();
                title = "Чек-лист";
                lines = new String[] {"На Pixel выберите и отправьте список"};
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.scale(getWidth() / 640f, getHeight() / 360f);
            canvas.clipRect(16, 12, 624, 348);
            if(displayActive()){DisplayPatterns.draw(canvas,paint,displayGate.pattern());canvas.restore();return;}
            paint.setColor(Color.WHITE);
            paint.setTextSize(32);
            if (callPrompt.visible()) {
                paint.setFakeBoldText(true);
                canvas.drawText("Сотовый звонок · Pixel", 40, 56, paint);
                paint.setFakeBoldText(false);
                paint.setColor(0xff38c9ed); paint.setStrokeWidth(3);
                canvas.drawLine(40, 76, 600, 76, paint);
                paint.setTextSize(24); paint.setColor(Color.WHITE);
                List<String> names = ChecklistText.wrap(callPrompt.label(), 560, 3, measure);
                for (int i = 0; i < names.size(); i++) canvas.drawText(names.get(i), 40, 111 + i * 32, paint);
                paint.setTextSize(30); paint.setColor(0xff38c9ed);
                canvas.drawText(callPrompt.e164(), 40, 218, paint);
                paint.setTextSize(22); paint.setColor(Color.WHITE);
                String outcome = callPrompt.outcome();
                String detail = outcome == null
                        ? callPrompt.stage() == GlassCallPrompt.Stage.READY
                            ? "Тап — запросить звонок · свайп/назад — отменить"
                            : callPrompt.stage() == GlassCallPrompt.Stage.CONFIRM_QUEUED
                            ? "Отправляю подтверждение на Pixel…"
                            : "Подтверждение отправлено · ждём ответ Pixel"
                        : "REQUESTED".equals(outcome) ? "Запрос передан Pixel · дозвон не подтверждён"
                        : "UNKNOWN".equals(outcome) ? "Итог неизвестен · проверьте Pixel"
                        : "CANCELLED".equals(outcome) ? "Отменено · вызов не запрашивался"
                        : "EXPIRED".equals(outcome) ? "Время вышло · вызов не запрашивался"
                        : "PERMISSION_REQUIRED".equals(outcome) ? "Нет разрешения Pixel на звонки"
                        : "Звонок не запрошен · проверьте Pixel";
                List<String> detailLines = ChecklistText.wrap(detail, 560, 2, measure);
                for (int i = 0; i < detailLines.size(); i++) canvas.drawText(detailLines.get(i), 40, 278 + i * 31, paint);
                paint.setTextSize(19); paint.setColor(Color.LTGRAY);
                canvas.drawText(outcome == null ? "Только физическое действие на Glass" : "Тап — закрыть", 40, 338, paint);
                canvas.restore(); return;
            }
            // Stop must win over a previously selected Talk card as well as the call prompt.
            if (assistMode && !localMirrorVisible && !status.startsWith("STOP")) {
                boolean photoFraming = visionRequest && assistCapture != null
                        && assistUi.stage() == AssistPresentation.Stage.RECORDING
                        && cameraPreview.getVisibility() == View.VISIBLE;
                if (photoFraming) {
                    PhotoPreviewOverlay.draw(canvas, paint);
                    canvas.restore();return;
                }
                if(pendingVision||(visionRequest&&!photoTalkCurrent())){
                    talkCard.draw(canvas, TalkHudState.photo(pendingVision, assistUi.stage(), visionResume));
                    canvas.restore();return;
                }
                if(talkSelected||talkId!=null){
                    talkCard.drawChat(canvas,talkLog,new TalkStatusBar.State(assistLinked(),talkId!=null,
                            talkAudio!=null,talkInputSeen,talkDraining,talkFailed,batteryPercent,batteryCharging,
                            talkOutputPending(),outputVolumePercent,
                            talkId==null?-1L:Math.max(0L,talkDeadline-SystemClock.elapsedRealtime()),talkDeadlineEnded));
                    talkCard.drawPhotoNotice(canvas,photoNotice);
                    canvas.restore();return;
                }
                boolean framing = (assistPhoto||visionRequest) && cameraPreview.getVisibility() == View.VISIBLE;
                if (framing) {
                    paint.setColor(Color.BLACK);
                    canvas.drawRoundRect(new android.graphics.RectF(16, 12, 624, 70), 16, 16, paint);
                    canvas.drawRoundRect(new android.graphics.RectF(16, 250, 624, 344), 16, 16, paint);
                }
                paint.setColor(Color.WHITE);
                paint.setFakeBoldText(true);
                paint.setTextSize(28);
                canvas.drawText("Франциска" + (assistPages == null ? "" : "  " + (assistPage+1) + "/" + assistPages.size()), 40, 56, paint);
                paint.setFakeBoldText(false);
                paint.setColor(0xff38c9ed);paint.setStrokeWidth(3);
                canvas.drawLine(40,76,600,76,paint);
                if (assistPages != null) {
                    paint.setTextSize(32);paint.setColor(Color.WHITE);
                    String[] page = assistPages.get(assistPage);
                    for (int i=0; i<page.length && i<4; i++)
                        canvas.drawText(page[i], 40, 118+i*38, paint);
                } else {
                    paint.setColor(0xff38c9ed);
                    paint.setTextSize(22);
                    canvas.drawText(assistPhoto||visionRequest ? "ФОТО И ВОПРОС" : "ВОПРОС", 40, 113, paint);
                    paint.setColor(Color.WHITE);
                    paint.setTextSize(32);
                    List<String> stateLines = ChecklistText.wrap(assistUi.message(SystemClock.elapsedRealtime()), 560, 2, measure);
                    for (int i=0; i<stateLines.size(); i++) canvas.drawText(stateLines.get(i), 40, 165+i*42, paint);
                    paint.setTextSize(20); paint.setColor(Color.LTGRAY);
                    if (assistUi.code().length() > 0) canvas.drawText(ChecklistText.fit("Код " + assistUi.code(),560,measure), 40, 254, paint);
                }
                paint.setColor(assistUi.stage() == AssistPresentation.Stage.RECORDING ? 0xffff7272 : 0xff38c9ed); paint.setTextSize(22);
                canvas.drawText(ChecklistText.fit(assistUi.stage() == AssistPresentation.Stage.RECORDING ?
                        (visionRequest ? "● Снимаю фото · тап — отменить" : "● Слушаю · тап — закончить раньше") : assistPages != null
                        ? "Свайп — далее · тап — домой" : assistUi.active() ? "Назад — отменить" : assistUi.terminal()
                        ? (assistUi.stage() == AssistPresentation.Stage.STOPPED ? "Тап — подключиться (без записи)" : assistReady ? "Тап — поговорить" : "Откройте Pixel") : "Тап — поговорить",560,measure),40,302,paint);
                paint.setTextSize(20); paint.setColor(Color.LTGRAY);
                canvas.drawText(ChecklistText.fit(assistVoiceUnavailable ? "Голос недоступен · ответ на экране" : assistReady ? "Pixel на связи · вниз — назад" : "Ожидаю Pixel · вниз — назад",560,measure), 40, 334, paint);
                canvas.restore(); return;
            }
            String heading = title;
            if (localMirrorVisible) {
                paint.setTextSize(32);
                String body = lines.length > 0 ? lines[0] : "";
                List<String> pages = ChecklistText.wrap(body, 560, 20, measure);
                int count = Math.max(1, (pages.size() + 3) / 4);
                localMirrorPage = Math.max(0, Math.min(localMirrorPage, count - 1));
                heading = title + " " + (localMirrorPage + 1) + "/" + count;
                canvas.drawText(ChecklistText.fit(heading, 560, measure), 40, 56, paint);
                for (int i = 0; i < 4 && localMirrorPage * 4 + i < pages.size(); i++)
                    canvas.drawText(pages.get(localMirrorPage * 4 + i), 40, 106 + i * 38, paint);
                paint.setColor(0xff38c9ed); paint.setTextSize(22);
                canvas.drawText("КАРТОЧКА PIXEL", 40, 277, paint);
                paint.setColor(Color.LTGRAY); paint.setTextSize(20);
                canvas.drawText(ChecklistText.fit("Свайп — страницы · тап — к ассистенту",560,measure), 40, 332, paint);
                canvas.restore(); return;
            }
            int suffixAt = title.lastIndexOf(' ');
            if (suffixAt > 0 && title.substring(suffixAt + 1).matches("[0-9]{1,2}/[0-9]{1,2}")) {
                String suffix = title.substring(suffixAt);
                heading = ChecklistText.fit(title.substring(0, suffixAt),
                        560 - paint.measureText(suffix), measure) + suffix;
            } else heading = ChecklistText.fit(title, 560, measure);
            canvas.drawText(heading, 40, 56, paint);
            paint.setTextSize(32);
            String body = lines.length > 0 ? lines[0] : "Список пуст";
            List<String> wrapped = ChecklistText.wrap(body, 560, 4, measure);
            for (int i = 0; i < wrapped.size(); i++)
                canvas.drawText(wrapped.get(i), 40, 106 + i * 38, paint);
            paint.setTextSize(22);
            paint.setColor(0xff38c9ed);
            StringBuilder progress = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                if (progress.length() > 0) progress.append(" · ");
                progress.append(lines[i]);
            }
            canvas.drawText(ChecklistText.fit(progress.toString(), 560, measure), 40, 277, paint);
            paint.setColor(Color.LTGRAY);
            paint.setTextSize(20);
            String connection = status.startsWith("SECURE") ? "Pixel · защищённая связь"
                    : status.startsWith("STOP") ? "Связь остановлена"
                    : status.startsWith("DEGRADED") ? "Нет связи · повтор подключения"
                    : "Нет связи · откройте Pixel";
            canvas.drawText(ChecklistText.fit(connection, 560, measure), 40, 307, paint);
            paint.setColor(Color.WHITE);
            paint.setTextSize(20);
            InputFeedback.Stage feedback = resumed && windowFocused
                    ? inputFeedback.stage(SystemClock.elapsedRealtime()) : InputFeedback.Stage.NONE;
            if (feedback != InputFeedback.Stage.NONE)
                paint.setColor(feedback == InputFeedback.Stage.NOT_SENT
                        || feedback == InputFeedback.Stage.UNCERTAIN ? Color.YELLOW : Color.CYAN);
            String footer = feedback == InputFeedback.Stage.NONE
                    ? "Тап — отметить; назад/вниз — выход" : feedback.text;
            if (status.startsWith("STOP")) footer = "Тап — подключиться снова";
            canvas.drawText(ChecklistText.fit(footer, 560, measure), 40, 334, paint);
            canvas.restore();
        }
        void moveMirrorPage(int delta) {
            paint.setTextSize(32);
            String body = lines.length > 0 ? lines[0] : "";
            int count = Math.max(1, (ChecklistText.wrap(body, 560, 20, measure).size() + 3) / 4);
            localMirrorPage = Math.max(0, Math.min(count - 1, localMirrorPage + delta));
            invalidate();
        }
    }
}
