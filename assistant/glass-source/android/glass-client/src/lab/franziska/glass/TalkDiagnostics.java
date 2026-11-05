package lab.franziska.glass;

import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileInputStream;

/** Numeric-only observations on existing Talk workers. No worker, timer, media or authority. */
final class TalkDiagnostics {
    /** Release builds omit detailed per-frame profiling; terminal failure categories remain. */
    static final boolean ENABLED=false;
    private static final String TAG="GlassTalkDiag";
    private static final String CPU_FREQUENCY="/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
    private TalkDiagnostics() {}

    static long monoMillis(){
        if(!ENABLED)return -1L;
        try {long n=SystemClock.elapsedRealtime();return n<0L?-1L:n;}
        catch(RuntimeException unavailable){return -1L;}
        catch(LinkageError unavailable){return -1L;}
    }
    static long cpuNanos(){
        if(!ENABLED)return -1L;
        try { long n=Debug.threadCpuTimeNanos();return n<0L?-1L:n; }
        catch(RuntimeException unavailable){return -1L;}
        catch(LinkageError unavailable){return -1L;}
    }
    static long delta(long start,long end){return start<0L||end<start?-1L:end-start;}
    static long sum(long total,long value){
        return total<0L||value<0L||total>Long.MAX_VALUE-value?-1L:total+value;
    }
    static long cpuMillis(long nanos){return nanos<0L?-1L:nanos/1000000L;}
    static long number(Object value){
        try{return value instanceof Number?((Number)value).longValue():-1L;}
        catch(RuntimeException unavailable){return -1L;}
    }
    /** Fixed-path, bounded-byte read; failure/unknown is -1, never an invented zero. */
    static int cpuKhz(){
        if(!ENABLED)return -1;
        FileInputStream input=null;
        try {
            input=new FileInputStream(CPU_FREQUENCY);
            byte[] bytes=new byte[64];int count=input.read(bytes);
            return parseKhz(bytes,count);
        }catch(Exception unavailable){return -1;}
        finally{if(input!=null)try{input.close();}catch(Exception ignored){}}
    }
    static int parseKhz(byte[] bytes,int count){
        if(bytes==null||count<1||count>=64||count>bytes.length)return -1;
        int n=0;boolean digit=false,trailing=false;
        for(int i=0;i<count;i++){
            int c=bytes[i]&255;
            if(c>='0'&&c<='9'&&!trailing){
                digit=true;if(n>1000000)return -1;n=n*10+c-'0';
            }else if((c==' '||c=='\n'||c=='\r'||c=='\t')&&digit){trailing=true;}
            else return -1;
        }
        return digit&&n>=1000&&n<=10000000?n:-1;
    }
    static void link(int index,int frameBytes,long readCpu,long decodeCpu){
        if(!ENABLED)return;
        try {Log.i(TAG,"DIAG_LINK INDEX="+index+" MONO_MS="+SystemClock.elapsedRealtime()
                +" FRAME_BYTES="+frameBytes+" READ_CPU_MS="+cpuMillis(readCpu)
                +" DECODE_CPU_MS="+cpuMillis(decodeCpu)+" CPU_KHZ="+cpuKhz());}
        catch(RuntimeException unavailable){}catch(LinkageError unavailable){}
    }
    static void admission(int index,int generation,int active,long owner0,long decodeWall,long decodeCpu,
            long owner1,long admitWall,long admitCpu,long accepted,long completed){
        if(!ENABLED)return;
        try {Log.i(TAG,"DIAG_ADMIT INDEX="+index+" MONO_MS="+SystemClock.elapsedRealtime()
                +" GENERATION="+generation+" ACTIVE="+active+" OWNER0_MS="+owner0+" PCM_MS="+decodeWall
                +" PCM_CPU_MS="+cpuMillis(decodeCpu)+" OWNER1_MS="+owner1
                +" ADMIT_MS="+admitWall+" ADMIT_CPU_MS="+cpuMillis(admitCpu)
                +" ACCEPTED_BYTES="+accepted+" COMPLETED_BYTES="+completed);}
        catch(RuntimeException unavailable){}catch(LinkageError unavailable){}
    }
    static void sent(boolean credit,long counter,long queuedMs,long encodeMs,long encodeCpu,
            long writeMs,long writeCpu,int frameBytes){
        if(!ENABLED)return;
        try {Log.i(TAG,(credit?"DIAG_CREDIT RELEASED_BYTES=":"DIAG_MIC_SEND INDEX=")+counter
                +" MONO_MS="+SystemClock.elapsedRealtime()+" QUEUED_MS="+queuedMs
                +" ENCODE_MS="+encodeMs+" ENCODE_CPU_MS="+cpuMillis(encodeCpu)
                +" WRITE_MS="+writeMs+" WRITE_CPU_MS="+cpuMillis(writeCpu)
                +" FRAME_BYTES="+frameBytes+" CPU_KHZ="+cpuKhz());}
        catch(RuntimeException unavailable){}catch(LinkageError unavailable){}
    }
    static void playout(int revision,int currentRevision,int written,int ready,int head,
            long pollMs,long emptyPollMs,long emptyPolls,long readyPolls,
            long writeMs,long writeCpu,long progressMs){
        if(!ENABLED)return;
        try {Log.i(TAG,"DIAG_PLAYOUT MONO_MS="+SystemClock.elapsedRealtime()
                +" FRAME_REV="+revision+" CURRENT_REV="+currentRevision
                +" WRITTEN_BYTES="+written+" QUEUED_BYTES="+ready+" HEAD_FRAMES="+head
                +" POLL_MS="+pollMs+" EMPTY_POLL_MS="+emptyPollMs
                +" EMPTY_POLLS="+emptyPolls+" READY_POLLS="+readyPolls
                +" WRITE_MS="+writeMs+" WRITE_CPU_MS="+cpuMillis(writeCpu)
                +" PROGRESS_MS="+progressMs);}
        catch(RuntimeException unavailable){}catch(LinkageError unavailable){}
    }
}
