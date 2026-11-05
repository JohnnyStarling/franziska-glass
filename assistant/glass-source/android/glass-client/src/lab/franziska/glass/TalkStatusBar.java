package lab.franziska.glass;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** API15-safe, event-driven status strip at y=0..46 of the 640x360 chat.
 * Draw-only: no animation, timers, transport or sensor ownership. */
final class TalkStatusBar {
    static final int IDLE=0,CONNECTING=1,ACTIVE=2,ENDING=3,ERROR=4,
            OFFLINE=5,OUTPUT=6,SESSION_LIMIT=7;
    static final int WHITE=0xffe8eaed,MUTED=0xff9aa0a6,ACCENT=0xff8ab4f8,
            AMBER=0xfffdd663,RED=0xfff28b82,GREEN=0xff81c995;
    static final class State {
        final int phase,battery,volumePercent;
        final boolean linked,charging,capturing,outputPending,deadlineEnded;
        final long remainingMs;

        /** Existing callers: unknown volume, no supplied session countdown. */
        State(boolean linked,boolean active,boolean audioReady,boolean inputSeen,
                boolean draining,boolean failed,int battery,boolean charging){
            this(linked,active,audioReady,inputSeen,draining,failed,battery,charging,
                    false,-1,-1L,false);
        }

        /** outputPending means queued output, NOT verified playback or audibility.
         * volumePercent is the observed output volume (negative = unknown).
         * remainingMs is supplied by the owner (negative = absent); no duration is assumed.
         * deadlineEnded is the owner's confirmed session-limit event, not inferred from time. */
        State(boolean linked,boolean active,boolean audioReady,boolean inputSeen,
                boolean draining,boolean failed,int battery,boolean charging,
                boolean outputPending,int volumePercent,long remainingMs,boolean deadlineEnded){
            this.linked=linked;
            this.phase=deadlineEnded?SESSION_LIMIT:failed?ERROR:!linked?OFFLINE:
                    draining?ENDING:!active?IDLE:!audioReady||!inputSeen?CONNECTING:ACTIVE;
            // Output pending must never turn the active session into a different glyph.
            this.capturing=linked&&active&&!draining&&audioReady&&inputSeen&&!failed&&!deadlineEnded;
            this.battery=battery<0?-1:Math.min(100,battery);
            this.charging=charging;
            this.outputPending=outputPending;
            this.volumePercent=volumePercent<0?-1:Math.min(100,volumePercent);
            this.remainingMs=remainingMs;
            this.deadlineEnded=deadlineEnded;
        }
    }
    static int percent(int level,int scale){
        return level<0||scale<=0||level>scale?-1:(int)((long)level*100L/scale);
    }
    static String phaseText(State state){
        switch(state.phase){
            case OFFLINE:return "Нет связи";
            case CONNECTING:return "Подключение";
            case ENDING:return "Завершение";
            case ERROR:return "Ошибка";
            case SESSION_LIMIT:return "Лимит сессии";
            case OUTPUT:return "Звук в очереди";
            default:return state.capturing?"Микрофон":"Готово";
        }
    }
    static String remainingText(long remainingMs){
        if(remainingMs<0)return "";
        if(remainingMs==0)return "0м";
        if(remainingMs<60000L)return "<1м";
        // Round up without overflowing long; bound the label, not the actual session.
        long minutes=remainingMs/60000L+(remainingMs%60000L==0?0:1);
        return minutes>99?"99+м":minutes+"м";
    }
    /** Next change of remainingText or its last-minute color; no periodic second tick. */
    static long nextCountdownDelay(long remainingMs){
        if(remainingMs<=0L)return -1L;
        if(remainingMs<=60000L)return remainingMs==60000L?1L:remainingMs;
        long part=remainingMs%60000L;
        return part==0L?60000L:part;
    }
    private static String percentage(int value){return value<0?"—%":value+"%";}
    private static void stroke(Paint p,int color){p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);}
    private static void fill(Paint p,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);}
    private static void rightText(Canvas c,Paint p,String text,float right,int color){
        fill(p,color);c.drawText(text,right-p.measureText(text),31,p);
    }
    static void draw(Canvas c,Paint p,State state,boolean history,int page,int pages){
        // Restore every changed Paint property; the caller's 28px chat style must survive.
        int savedColor=p.getColor();float savedWidth=p.getStrokeWidth(),savedSize=p.getTextSize();
        Paint.Style savedStyle=p.getStyle();Paint.Align savedAlign=p.getTextAlign();
        boolean savedBold=p.isFakeBoldText();
        try{
            p.setTextSize(20);p.setTextAlign(Paint.Align.LEFT);p.setFakeBoldText(false);
            drawStrip(c,p,state,history,page,pages);
        }finally{
            p.setColor(savedColor);p.setStrokeWidth(savedWidth);p.setTextSize(savedSize);
            p.setStyle(savedStyle);p.setTextAlign(savedAlign);p.setFakeBoldText(savedBold);
        }
    }
    private static void drawStrip(Canvas c,Paint p,State state,boolean history,int page,int pages){
        drawSession(c,p,state);
        // Fixed independent slots: session 24..54, history 248..294,
        // time 308..390, volume 402..480, Pixel link 493..513, battery 530..616.
        if(history){
            stroke(p,MUTED);c.drawCircle(257,24,8,p);
            c.drawLine(257,19,257,24,p);c.drawLine(257,24,261,26,p);
            fill(p,0xff30343a);c.drawRoundRect(new RectF(274,22,294,25),1.5f,1.5f,p);
            int count=Math.max(1,pages),current=Math.max(1,Math.min(page,count));
            fill(p,MUTED);c.drawRoundRect(new RectF(274,22,274+20f*current/count,25),1.5f,1.5f,p);
        }
        if(state.remainingMs>=0){
            stroke(p,MUTED);c.drawCircle(316,24,7,p);
            c.drawLine(316,20,316,24,p);c.drawLine(316,24,319,26,p);
            rightText(c,p,remainingText(state.remainingMs),390,
                    state.remainingMs<60000L?AMBER:WHITE);
        }
        // Output volume setting only; neither this icon nor its value asserts audibility.
        stroke(p,WHITE);c.drawRect(403,21,409,28,p);
        c.drawLine(409,21,418,16,p);c.drawLine(418,16,418,33,p);c.drawLine(418,33,409,28,p);
        rightText(c,p,percentage(state.volumePercent),480,WHITE);

        stroke(p,state.linked?WHITE:MUTED);c.drawRoundRect(new RectF(495,12,510,35),3,3,p);
        c.drawLine(500,31,505,31,p);
        if(!state.linked)c.drawLine(492,10,513,37,p);

        // Actual Glass sticky-broadcast battery; unknown never becomes a demo percentage.
        int batteryColor=state.battery>=0&&state.battery<=15&&!state.charging?RED:WHITE;
        stroke(p,batteryColor);c.drawRoundRect(new RectF(530,15,552,32),3,3,p);
        fill(p,batteryColor);c.drawRect(553,20,556,27,p);
        if(state.battery<0){stroke(p,MUTED);c.drawLine(534,27,548,20,p);}
        else if(state.battery>0){fill(p,batteryColor);c.drawRect(533,18,533+16f*state.battery/100f,29,p);}
        if(state.charging){
            // Contrast-backed bolt remains legible at empty and full charge.
            stroke(p,0xff000000);p.setStrokeWidth(5);bolt(c,p);
            stroke(p,ACCENT);bolt(c,p);
        }
        rightText(c,p,percentage(state.battery),616,batteryColor);
        stroke(p,0xff24272b);p.setStrokeWidth(1);c.drawLine(24,46,616,46,p);
    }
    private static final RectF SESSION=new RectF(26,13,50,31);
    private static void drawSession(Canvas c,Paint p,State state){
        int color=state.phase==ERROR?RED:state.phase==SESSION_LIMIT||
                state.phase==CONNECTING||state.phase==ENDING?AMBER:
                state.phase==ACTIVE?ACCENT:MUTED;
        if(state.phase==ERROR||state.phase==SESSION_LIMIT){
            stroke(p,color);c.drawLine(38,12,51,34,p);c.drawLine(51,34,25,34,p);c.drawLine(25,34,38,12,p);
            c.drawLine(38,21,38,26,p);fill(p,color);c.drawCircle(38,30,1.2f,p);return;
        }
        stroke(p,color);c.drawRoundRect(SESSION,5,5,p);
        c.drawLine(30,31,30,35,p);c.drawLine(30,35,36,31,p);
        if(state.phase==ACTIVE){
            // Ready AND actual input seen: check inside conversation balloon.
            c.drawLine(31,22,36,27,p);c.drawLine(36,27,45,18,p);
        }else if(state.phase==CONNECTING){
            fill(p,color);for(int x=32;x<=44;x+=6)c.drawCircle(x,22,1.4f,p);
        }else if(state.phase==ENDING){
            fill(p,color);c.drawRect(34,18,42,26,p);
        }else c.drawLine(25,11,52,35,p); // Idle/offline: inactive, independently of Pixel.
    }
    private static void bolt(Canvas c,Paint p){
        c.drawLine(545,17,539,24,p);c.drawLine(539,24,545,24,p);c.drawLine(545,24,540,30,p);
    }
}
