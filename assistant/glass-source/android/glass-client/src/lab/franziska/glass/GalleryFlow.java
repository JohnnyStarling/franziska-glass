package lab.franziska.glass;

/** Pure local gallery navigation; no camera, microphone, transport or auto-play. */
final class GalleryFlow {
    static final int LIST=0, DETAIL=1, MENU=2, CONFIRM=3, PLAYING=4, PAUSED=5;
    static final int NONE=0, PREVIEW=1, START=2, PAUSE=3, RESUME=4, STOP=5,
            SHARE=6, FINISH=7;
    private int count, index, mode=LIST;
    int count() { return count; }
    int index() { return index; }
    int mode() { return mode; }
    void reset(int size, int selected) {
        count=Math.max(0,size);
        index=count==0?0:Math.max(0,Math.min(count-1,selected));
        mode=LIST;
    }
    void stop() { if (mode==PLAYING || mode==PAUSED) mode=DETAIL; }
    void dismissTransient() {
        if (mode==MENU || mode==CONFIRM || mode==PLAYING || mode==PAUSED) mode=DETAIL;
    }
    int action(String action, boolean video) {
        if ("back".equals(action)) {
            if (mode==LIST) return FINISH;
            if (mode==PLAYING || mode==PAUSED) { mode=DETAIL; return STOP; }
            if (mode==CONFIRM) { mode=MENU; return NONE; }
            if (mode==MENU) { mode=DETAIL; return NONE; }
            mode=LIST; return NONE;
        }
        if ("menu".equals(action)) {
            if (count==0 || mode==LIST) return NONE;
            if (mode==PLAYING || mode==PAUSED) { mode=MENU; return STOP; }
            mode=mode==MENU || mode==CONFIRM ? DETAIL : MENU;
            return NONE;
        }
        if ("next".equals(action) || "previous".equals(action)) {
            if (count==0 || mode==MENU || mode==CONFIRM) return NONE;
            int next=Math.max(0,Math.min(count-1,index+("next".equals(action)?1:-1)));
            if (next==index) return NONE;
            boolean playing=mode==PLAYING || mode==PAUSED;
            index=next;
            if (mode!=LIST) mode=DETAIL;
            return playing ? STOP : mode==DETAIL ? PREVIEW : NONE;
        }
        if (!"tap".equals(action) || count==0) return NONE;
        switch (mode) {
            case LIST: mode=DETAIL; return PREVIEW;
            case DETAIL: if (video) { mode=PLAYING; return START; }
                         mode=MENU; return NONE;
            case MENU: mode=CONFIRM; return NONE;
            case CONFIRM: mode=DETAIL; return SHARE;
            case PLAYING: mode=PAUSED; return PAUSE;
            case PAUSED: mode=PLAYING; return RESUME;
            default: return NONE;
        }
    }
}
