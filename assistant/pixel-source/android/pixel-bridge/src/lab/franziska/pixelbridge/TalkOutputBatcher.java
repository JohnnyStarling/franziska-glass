package lab.franziska.pixelbridge;

import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import lab.franziska.protocol.TalkWire;

/** Combines provider's 20 ms PCM events into bounded Glass link frames. */
final class TalkOutputBatcher {
    static final int BATCH_BYTES=23040; // Up to 480 ms in a burst; 100 ms timer still releases the first/last partial batch.
    static final long FLUSH_MS=100L;
    private final GatewayTalkClient.Listener listener;
    private final OutputWindow window;
    private final Runnable flowFailure,deliveryFailure;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(new ThreadFactory(){
        public Thread newThread(Runnable task){Thread t=new Thread(task,"pixel-talk-output-flush");t.setDaemon(true);return t;}
    });
    private ScheduledFuture<?> pendingFlush;
    private byte[] pending=new byte[BATCH_BYTES];
    private int length;
    private boolean closed;

    TalkOutputBatcher(GatewayTalkClient.Listener listener){this(listener,System.nanoTime()/1000000L+TalkWire.MAX_SESSION_MS,null);}
    TalkOutputBatcher(GatewayTalkClient.Listener listener,long deadline,Runnable flowFailure){
        this(listener,deadline,flowFailure,null);
    }
    TalkOutputBatcher(GatewayTalkClient.Listener listener,long deadline,Runnable flowFailure,Runnable deliveryFailure){
        this.listener=listener;this.window=new OutputWindow(deadline);this.flowFailure=flowFailure;this.deliveryFailure=deliveryFailure;
    }

    /** Glass sends a cumulative, authenticated release count; duplicates never mint credit. */
    boolean offerCredit(int releasedBytes){return window.release(releasedBytes);}
    /** Stop must wake a blocked producer without taking the batcher's emit lock. */
    void abort(){window.abort();}

    synchronized void append(byte[] pcm){
        if(closed)return;
        for(int at=0;at<pcm.length;){
            int n=Math.min(BATCH_BYTES-length,pcm.length-at);
            System.arraycopy(pcm,at,pending,length,n);at+=n;length+=n;
            if(length==BATCH_BYTES)emit();
            else if(pendingFlush==null)schedule();
        }
    }
    private void schedule(){
        pendingFlush=timer.schedule(new Runnable(){public void run(){flush();}},FLUSH_MS,TimeUnit.MILLISECONDS);
    }
    private void emit(){
        if(length==0)return;
        byte[] ready=Arrays.copyOf(pending,length);
        Arrays.fill(pending,0,length,(byte)0);length=0;
        if(pendingFlush!=null){pendingFlush.cancel(false);pendingFlush=null;}
        if(window.reserve(ready.length)){
            try{listener.audio(ready);}
            catch(RuntimeException failure){
                // ScheduledExecutorService retains callback exceptions in a Future that
                // nobody reads. Revoke the live relay explicitly on both flush paths.
                Arrays.fill(ready,(byte)0);window.abort();
                if(deliveryFailure!=null)deliveryFailure.run();
                throw new IllegalStateException("OUTPUT_DELIVERY_FAILURE");
            }
        }
        else{
            Arrays.fill(ready,(byte)0);
            if(window.failed()){
                if(flowFailure!=null)flowFailure.run();
                throw new IllegalStateException("OUTPUT_FLOW_DEADLINE");
            }
        }
    }
    synchronized void flush(){if(!closed)emit();}
    synchronized void clear(){
        if(closed)return;
        Arrays.fill(pending,0,length,(byte)0);length=0;
        if(pendingFlush!=null){pendingFlush.cancel(false);pendingFlush=null;}
        listener.clear();
    }
    void close(){
        abort();
        synchronized(this){
            if(closed)return;
            closed=true;
            if(pendingFlush!=null){pendingFlush.cancel(false);pendingFlush=null;}
            Arrays.fill(pending,(byte)0);length=0;
            timer.shutdownNow();
        }
    }

    /** The provider reader itself backpressures at the bounded Glass playback window. */
    static final class OutputWindow {
        private final long deadline;
        private int sent,released=-1;
        private boolean stopped,failed;
        OutputWindow(long deadline){this.deadline=deadline;}
        synchronized boolean release(int value){
            if(stopped||value<0||(value&1)!=0||value>TalkWire.MAX_OUTPUT_CREDIT_BYTES
                    ||value>sent||released>=0&&value<released)return false;
            released=value;notifyAll();return true;
        }
        synchronized boolean reserve(int bytes){
            if(bytes<2||(bytes&1)!=0||bytes>TalkWire.BATCH_BYTES_LIMIT){failed=true;stopped=true;notifyAll();return false;}
            while(!stopped&&(released<0||sent-released>TalkWire.OUTPUT_WINDOW_BYTES-bytes)){
                long remaining=deadline-System.nanoTime()/1000000L;
                if(remaining<=0){failed=true;stopped=true;notifyAll();return false;}
                try{wait(remaining);}catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();failed=true;stopped=true;notifyAll();return false;
                }
            }
            if(stopped)return false;
            if(sent>TalkWire.MAX_OUTPUT_CREDIT_BYTES-bytes){failed=true;stopped=true;notifyAll();return false;}
            sent+=bytes;return true;
        }
        synchronized boolean failed(){return failed;}
        synchronized void abort(){stopped=true;notifyAll();}
    }
}
