package lab.franziska.glass;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lab.franziska.protocol.TalkWire;

/** Foreground-only PCM mic/playback for one bounded native Talk session. */
final class GlassTalkAudio {
    private static final int MAX_PENDING_FRAMES=8;
    private static final int POOL_FRAMES=202; // 192 queued + 8 preroll + writer + producer.
    private static final int MAX_QUEUED_BYTES=24000*2*4; // Four seconds of 24 kHz mono PCM; burst storage, not playout delay.
    private static final long OUTPUT_DRAIN_MS=6000L;
    interface Listener { void input(byte[] pcm); void failed(); }
    /** Borrowed only for this synchronous callback; callers must not retain PCM. */
    interface BorrowedInputListener extends Listener { void input(byte[] pcm,int length); }
    interface OutputProgress { void written(int bytes,int revision); }
    interface DrainListener { void complete(boolean drained); }
    private static final class Frame {
        final byte[] pcm=new byte[TalkWire.MAX_PCM];
        int length,revision;long arrivedAt;
    }
    private final Listener listener;
    private final OutputProgress outputProgress;
    private final ArrayBlockingQueue<Frame> output=new ArrayBlockingQueue<Frame>(192);
    private final ArrayBlockingQueue<Frame> available=new ArrayBlockingQueue<Frame>(POOL_FRAMES);
    private final Object outputSignal=new Object();
    private final AtomicInteger queuedBytes=new AtomicInteger();
    private long playoutMetricAt;
    private int playoutWrittenBytes;
    // Speaker-owned interval counters only; never gate PCM, playback or credits.
    private long diagnosticPollMs,diagnosticEmptyPollMs,diagnosticEmptyPolls,diagnosticReadyPolls;
    private long diagnosticWriteMs,diagnosticWriteCpu,diagnosticProgressMs;
    private volatile boolean stopped;
    private volatile boolean inputClosed,outputComplete;
    private volatile DrainListener drainListener;
    private volatile long drainDeadline;
    private volatile AudioRecord recorder;
    private volatile AudioTrack player;
    private final AtomicInteger playbackRevision=new AtomicInteger();
    private Thread reader,writer;
    // All lifecycle metadata below is protected by this; no native method and no
    // external callback executes under this monitor or the process admission gate.
    private boolean started,setupDone,readerDone=true,recordingAttempted,cleanupFailed;
    private int owners;
    private void ownerDone(){
        boolean settled,clean;
        synchronized(this){owners--;settled=owners==0;clean=!cleanupFailed;notifyAll();}
        if(settled)NativeAudioAdmission.settle(this,clean);
    }
    private synchronized void uncertainCleanup(){cleanupFailed=true;}
    private void fail(){
        boolean report;
        synchronized(this){report=!stopped;stop();}
        if(report)listener.failed();
    }
    private void launch(final Runnable work,String name){
        synchronized(this){owners++;}
        try{new Thread(new Runnable(){public void run(){try{work.run();}finally{ownerDone();}}},name).start();}
        catch(RuntimeException failure){uncertainCleanup();ownerDone();throw failure;}
    }
    GlassTalkAudio(Listener listener){this(listener,null);}
    GlassTalkAudio(Listener listener,OutputProgress progress){
        this.listener=listener;this.outputProgress=progress;
        for(int i=0;i<POOL_FRAMES;i++)available.add(new Frame());
    }
    private void recycle(Frame frame){
        Arrays.fill(frame.pcm,0,frame.length,(byte)0);
        frame.length=0;frame.arrivedAt=0L;
        if(!available.offer(frame))throw new IllegalStateException("PCM_POOL_OWNERSHIP");
    }
    void start(){
        synchronized(this){if(started||stopped)return;started=true;owners=1;}
        if(!NativeAudioAdmission.acquire(this)){
            synchronized(this){owners=0;}
            fail();return;
        }
        boolean constructing=false;
        try{
            // The controller belongs to this lease and cannot release it before
            // setupDone and the reader have settled, including late setup returns.
            launch(new Runnable(){public void run(){closeInput();}},"glass-talk-control");
            if(stopped)return;
            int in=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(stopped)return;
            int out=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(stopped)return;
            if(in<2||out<2||in>65536||out>65536)throw new IllegalStateException();
            constructing=true;
            recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(in,TalkWire.MAX_PCM*2));
            constructing=false;
            if(stopped)return;
            constructing=true;
            player=new AudioTrack(AudioManager.STREAM_MUSIC,24000,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(out,TalkWire.MAX_PCM*4),AudioTrack.MODE_STREAM);
            constructing=false;
            if(stopped)return;
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();
            if(stopped)return;
            if(player.getState()!=AudioTrack.STATE_INITIALIZED)throw new IllegalStateException();
            if(stopped)return;
            if(!inputClosed){recordingAttempted=true;recorder.startRecording();}
            synchronized(this){
                if(!stopped&&!inputClosed){
                    final AudioRecord owned=recorder;
                    readerDone=false;owners++;
                    reader=new Thread(new Runnable(){public void run(){
                        try{readLoop(owned);}finally{
                            synchronized(GlassTalkAudio.this){readerDone=true;GlassTalkAudio.this.notifyAll();}
                            ownerDone();
                        }
                    }},"glass-talk-mic");
                    try{reader.start();}catch(RuntimeException failure){readerDone=true;owners--;throw failure;}
                }
                if(!stopped){
                    final AudioTrack owned=player;
                    owners++;
                    writer=new Thread(new Runnable(){public void run(){try{writeLoop(owned);}finally{ownerDone();}}},"glass-talk-speaker");
                    try{writer.start();}catch(RuntimeException failure){writer=null;owners--;throw failure;}
                }
            }
        }catch(Exception failure){fail();}
        finally{
            // No worker owns the track when startup was revoked or failed before
            // handoff. Setup retains its own lease count through this release.
            // A throwing constructor leaves no handle with which to establish
            // cleanup. Quarantine that uncertainty instead of opening a new lease.
            if(constructing)uncertainCleanup();
            synchronized(this){setupDone=true;notifyAll();}
            try{if(writer==null){AudioTrack p=player;if(p!=null){releasePlayer(p);player=null;}}}
            finally{ownerDone();}
        }
    }
    private void closeInput(){
        boolean completed=false;
        try{
            boolean interrupted=false;
            AudioRecord owned;
            synchronized(this){
                while(!setupDone||(!inputClosed&&!readerDone)){
                    try{wait();}catch(InterruptedException ignored){interrupted=true;}
                }
                owned=recorder;
            }
            if(owned!=null){
                if(recordingAttempted)try{owned.stop();}catch(Exception failure){uncertainCleanup();}
                synchronized(this){while(!readerDone){try{wait();}catch(InterruptedException ignored){interrupted=true;}}}
                try{owned.release();}catch(Exception failure){uncertainCleanup();}
                recorder=null;
            }
            if(interrupted)Thread.currentThread().interrupt();
            completed=true;
        }finally{if(!completed)uncertainCleanup();} // Includes unchecked native Errors.
    }
    private void releasePlayer(AudioTrack owned){
        // Only the speaker (or setup before handoff) reaches this, after the last
        // native write/query has returned. Never release an in-flight write buffer.
        boolean completed=false;
        try{
            try{owned.pause();}catch(Exception failure){uncertainCleanup();}
            try{owned.flush();}catch(Exception failure){uncertainCleanup();}
            try{owned.release();}catch(Exception failure){uncertainCleanup();}
            completed=true;
        }finally{if(!completed)uncertainCleanup();}
    }
    private void resetPlayer(AudioTrack owned){
        boolean completed=false;
        try{owned.pause();owned.flush();completed=true;}
        finally{if(!completed)uncertainCleanup();}
    }
    synchronized boolean play(byte[] pcm){
        if(pcm==null||pcm.length<2||pcm.length>TalkWire.MAX_PCM||(pcm.length&1)!=0||stopped||outputComplete)return false;
        if(queuedBytes.get()>MAX_QUEUED_BYTES-pcm.length||output.remainingCapacity()==0)return false;
        Frame frame=available.poll();if(frame==null)return false;
        frame.length=pcm.length;frame.revision=playbackRevision.get();frame.arrivedAt=SystemClock.elapsedRealtime();
        System.arraycopy(pcm,0,frame.pcm,0,pcm.length);
        queuedBytes.addAndGet(frame.length);
        if(output.offer(frame)){signalOutput();return true;}
        queuedBytes.addAndGet(-frame.length);recycle(frame);return false;
    }
    synchronized void clear(){
        playbackRevision.incrementAndGet();
        Frame b;while((b=output.poll())!=null){queuedBytes.addAndGet(-b.length);recycle(b);}
        // Speaker applies pause/flush for this revision before new-turn PCM.
        signalOutput();
    }
    synchronized void finishOutput(DrainListener listener){
        if(stopped||outputComplete)return;
        drainListener=listener;drainDeadline=SystemClock.elapsedRealtime()+OUTPUT_DRAIN_MS;
        inputClosed=true;outputComplete=true; // Publish callback/deadline first.
        Thread mic=reader;if(mic!=null)mic.interrupt();
        notifyAll();signalOutput();
    }
    synchronized void stop(){
        if(stopped)return;
        stopped=true;inputClosed=true;drainListener=null;clear();
        Thread a=reader,b=writer;if(a!=null)a.interrupt();if(b!=null)b.interrupt();
        notifyAll();
    }
    private void readLoop(AudioRecord owned){
        byte[] b=new byte[TalkWire.MAX_PCM];
        long levelAt=TalkDiagnostics.ENABLED?SystemClock.elapsedRealtime():0L,energy=0L;
        int samples=0,peak=0;
        try{
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while(!stopped&&!inputClosed){
                int n=owned.read(b,0,b.length);
                if(stopped||inputClosed)break;
                if(n<2||n>b.length)throw new IllegalStateException();
                n&=~1;
                if(TalkDiagnostics.ENABLED){
                for(int i=0;i<n;i+=2){
                    int value=(short)((b[i]&255)|((b[i+1]&255)<<8));
                    int absolute=Math.abs(value);if(absolute>peak)peak=absolute;
                    energy+=(long)value*value;samples++;
                }
                long now=SystemClock.elapsedRealtime();
                if(now-levelAt>=1000L&&samples>0){
                    double rms=Math.sqrt((double)energy/samples);
                    int db=(int)Math.max(-96,Math.round(20.0*Math.log10(Math.max(1.0,rms)/32768.0)));
                    Log.i("GlassTalkDiag","MIC_DBFS="+db+" PEAK="+peak+" SAMPLES="+samples);
                    levelAt=now;energy=0L;samples=0;peak=0;
                }
                }
                if(n>0&&!stopped&&!inputClosed){
                    try{
                        if(listener instanceof BorrowedInputListener)((BorrowedInputListener)listener).input(b,n);
                        else listener.input(Arrays.copyOf(b,n)); // Legacy synchronous listener compatibility.
                    }finally{Arrays.fill(b,0,n,(byte)0);}
                }
            }
        }catch(Exception failure){if(!stopped&&!inputClosed)fail();}
        finally{Arrays.fill(b,(byte)0);}
    }
    private void signalOutput(){synchronized(outputSignal){outputSignal.notifyAll();}}
    /** The signal monitor closes the empty-check/wait race without holding the
     * control monitor across a wait or depending on its potentially native calls.
     * Only prebuffer and hardware-tail deadlines need timed wakeups. */
    private Frame awaitOutput(boolean playing,long firstAt,int revision,int waitMs)throws InterruptedException{
        synchronized(outputSignal){
            if(output.isEmpty()&&!stopped&&revision==playbackRevision.get()){
                if(firstAt!=0L&&!outputComplete){
                    long remaining=waitMs-(SystemClock.elapsedRealtime()-firstAt);
                    if(remaining>0L)outputSignal.wait(remaining);
                }else if(outputComplete){
                    if(playing)outputSignal.wait(20L); // Retain bounded hardware-tail checks.
                }else outputSignal.wait();
            }
            return output.poll();
        }
    }
    private void writeLoop(AudioTrack owned){
        ArrayDeque<Frame> pending=new ArrayDeque<Frame>();
        int pendingBytes=0,revision=playbackRevision.get();
        long firstAt=0L,lastArrivalAt=0L;
        final TalkPlayoutPolicy playout=new TalkPlayoutPolicy();
        boolean playing=false;
        long writtenFrames=0L;
        try{
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while(!stopped){
                if(revision!=playbackRevision.get()){
                    int next=playbackRevision.get();
                    Frame old;while((old=pending.poll())!=null)recycle(old);
                    resetPlayer(owned);
                    pendingBytes=0;firstAt=0L;playing=false;writtenFrames=0L;lastArrivalAt=0L;playout.boundary();revision=next;
                    continue; // A clear during native reset must be observed too.
                }
                long diagnosticPollAt=TalkDiagnostics.monoMillis();
                Frame b=awaitOutput(playing,firstAt,revision,playout.targetMs());
                if(TalkDiagnostics.ENABLED){
                long diagnosticPollElapsed=TalkDiagnostics.delta(diagnosticPollAt,TalkDiagnostics.monoMillis());
                diagnosticPollMs=TalkDiagnostics.sum(diagnosticPollMs,diagnosticPollElapsed);
                if(b==null){diagnosticEmptyPolls++;
                    diagnosticEmptyPollMs=TalkDiagnostics.sum(diagnosticEmptyPollMs,diagnosticPollElapsed);}
                else diagnosticReadyPolls++;
                }
                if(b!=null)queuedBytes.addAndGet(-b.length);
                if(b==null){
                    if(!playing&&pendingBytes>0&&(outputComplete||android.os.SystemClock.elapsedRealtime()-firstAt>=playout.targetMs())){
                        if(!stopped&&revision==playbackRevision.get()){owned.play();playing=true;}
                        while(!stopped&&revision==playbackRevision.get()&&(b=pending.poll())!=null)writtenFrames+=write(owned,b,playout)/2;
                        pendingBytes=0;firstAt=0L;
                    }
                    if(outputComplete&&pendingBytes==0&&output.isEmpty()&&!stopped&&revision==playbackRevision.get()){
                        long head=((long)owned.getPlaybackHeadPosition())&0xffffffffL;
                        if(stopped||revision!=playbackRevision.get())continue;
                        boolean drained=!playing||head>=writtenFrames;
                        if(drained||SystemClock.elapsedRealtime()>=drainDeadline){
                            DrainListener callback=drainListener;
                            if(callback!=null&&!stopped)callback.complete(drained);
                            return;
                        }
                    }
                    continue;
                }
                if(b.revision!=playbackRevision.get()){recycle(b);continue;}
                if(revision!=b.revision){
                    Frame old;while((old=pending.poll())!=null)recycle(old);
                    try{resetPlayer(owned);}catch(RuntimeException failure){recycle(b);throw failure;}
                    pendingBytes=0;firstAt=0L;playing=false;writtenFrames=0L;lastArrivalAt=0L;playout.boundary();revision=b.revision;
                    if(stopped||b.revision!=playbackRevision.get()){recycle(b);continue;}
                }
                try{
                    if(playing&&!outputComplete&&writtenFrames>0L){
                        long head=((long)owned.getPlaybackHeadPosition())&0xffffffffL;
                        if(head>=writtenFrames){
                            playout.starved(lastArrivalAt==0L?Long.MAX_VALUE:Math.max(0L,b.arrivedAt-lastArrivalAt));
                            // Hardware has no unread PCM: pause without flushing voiced bytes.
                            if(!stopped&&revision==playbackRevision.get()){
                                boolean paused=false;
                                try{owned.pause();paused=true;}finally{if(!paused)uncertainCleanup();}
                            }
                            playing=false;firstAt=0L;
                        }
                    }
                }catch(RuntimeException failed){recycle(b);throw failed;}
                lastArrivalAt=b.arrivedAt;
                if(!playing){
                    pending.add(b);pendingBytes+=b.length;
                    if(firstAt==0L)firstAt=android.os.SystemClock.elapsedRealtime();
                    if(!outputComplete&&pending.size()<MAX_PENDING_FRAMES&&pendingBytes<playout.targetBytes()
                            &&android.os.SystemClock.elapsedRealtime()-firstAt<playout.targetMs())continue;
                    if(!stopped&&revision==playbackRevision.get()){owned.play();playing=true;}
                    while(!stopped&&revision==playbackRevision.get()&&(b=pending.poll())!=null)writtenFrames+=write(owned,b,playout)/2;
                    pendingBytes=0;firstAt=0L;
                    continue;
                }
                writtenFrames+=write(owned,b,playout)/2;
            }
        }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
        catch(Exception failure){if(!stopped)fail();}
        finally{
            try{
                stop();
                Frame old;while((old=pending.poll())!=null)recycle(old);
            }finally{
                releasePlayer(owned);player=null;
            }
        }
    }
    private int write(AudioTrack owned,Frame b,TalkPlayoutPolicy playout){
        try{long diagnosticWriteAt=TalkDiagnostics.monoMillis(),diagnosticCpuAt=TalkDiagnostics.cpuNanos();
            int at=0;while(!stopped&&b.revision==playbackRevision.get()&&at<b.length){
            int n=owned.write(b.pcm,at,b.length-at);if(n<=0||n>b.length-at||(n&1)!=0)throw new IllegalStateException();at+=n;
        }
            if(TalkDiagnostics.ENABLED){
            diagnosticWriteMs=TalkDiagnostics.sum(diagnosticWriteMs,TalkDiagnostics.delta(diagnosticWriteAt,TalkDiagnostics.monoMillis()));
            diagnosticWriteCpu=TalkDiagnostics.sum(diagnosticWriteCpu,TalkDiagnostics.delta(diagnosticCpuAt,TalkDiagnostics.cpuNanos()));
            playoutWrittenBytes+=at;
            }
            long diagnosticProgressAt=TalkDiagnostics.monoMillis();
            if(at>0&&outputProgress!=null&&!stopped&&b.revision==playbackRevision.get())outputProgress.written(at,b.revision);
            if(b.revision==playbackRevision.get()&&!stopped)playout.written(at);
            if(TalkDiagnostics.ENABLED){
            long now=SystemClock.elapsedRealtime();
            diagnosticProgressMs=TalkDiagnostics.sum(diagnosticProgressMs,TalkDiagnostics.delta(diagnosticProgressAt,now));
            if(now-playoutMetricAt>=1000L){
                int diagnosticReady=queuedBytes.get(),diagnosticHead=owned.getPlaybackHeadPosition();
                Log.i("GlassTalkDiag","PLAYOUT_WRITTEN_BYTES="+playoutWrittenBytes
                        +" QUEUED_BYTES="+diagnosticReady+" HEAD_FRAMES="+diagnosticHead);
                TalkDiagnostics.playout(b.revision,playbackRevision.get(),playoutWrittenBytes,diagnosticReady,diagnosticHead,
                        diagnosticPollMs,diagnosticEmptyPollMs,diagnosticEmptyPolls,diagnosticReadyPolls,
                        diagnosticWriteMs,diagnosticWriteCpu,diagnosticProgressMs);
                playoutMetricAt=now;playoutWrittenBytes=0;
                diagnosticPollMs=0L;diagnosticEmptyPollMs=0L;diagnosticEmptyPolls=0L;diagnosticReadyPolls=0L;
                diagnosticWriteMs=0L;diagnosticWriteCpu=0L;diagnosticProgressMs=0L;
            }
            }
            return at;
        }finally{recycle(b);}
    }
}
