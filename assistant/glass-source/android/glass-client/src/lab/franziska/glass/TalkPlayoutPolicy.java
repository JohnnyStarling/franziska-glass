package lab.franziska.glass;

/** Speaker-owned, bounded software preroll. Never alters PCM or capture lifetime. */
final class TalkPlayoutPolicy {
    static final int MIN_MS=96,INITIAL_MS=192,MAX_MS=320;
    static final int BYTES_PER_MS=48,STABLE_BYTES=24000*2*3;
    private int targetMs=INITIAL_MS,stableBytes;
    int targetMs(){return targetMs;}
    int targetBytes(){return targetMs*BYTES_PER_MS;}
    void boundary(){stableBytes=0;}
    void starved(long arrivalGapMs){
        stableBytes=0;
        // Long inter-answer/think silence is not evidence of transport jitter.
        if(arrivalGapMs>=0L&&arrivalGapMs<=750L)targetMs=Math.min(MAX_MS,targetMs+64);
    }
    void written(int bytes){
        if(bytes<=0)return;
        stableBytes+=bytes;
        if(stableBytes>=STABLE_BYTES){
            stableBytes%=STABLE_BYTES;
            targetMs=Math.max(MIN_MS,targetMs-32);
        }
    }
}
