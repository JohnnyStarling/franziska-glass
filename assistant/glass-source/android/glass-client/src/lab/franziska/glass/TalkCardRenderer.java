package lab.franziska.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.util.List;

/** Glanceable 640x360 HUD. Draw-only: no sensors, timers, transport or authority. */
final class TalkCardRenderer {
    private final Paint paint = new DisplayPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final TalkChatLayout.RangeMeasurer measure = new TalkChatLayout.RangeMeasurer() {
        public float width(String text) { return paint.measureText(text); }
        public float width(String text,int start,int end) { return paint.measureText(text,start,end); }
    };
    // One entry per fixed style: bounded memory, no repeat text measurement on repaint.
    private final String[] fittedSource=new String[5], fittedText=new String[5];
    private String detailSource;
    private List<String> detailLines;
    // One fixed 18px/180px style; streaming transcript repaints need not refit a stable notice.
    private String photoNoticeSource,photoNoticeText;
    private String fitted(int slot,String text,float width) {
        if(!text.equals(fittedSource[slot])) {
            fittedSource[slot]=text;
            fittedText[slot]=ChecklistText.fit(text,width,measure);
        }
        return fittedText[slot];
    }
    private final TalkChatLayout chat=new TalkChatLayout();
    boolean reading(){return chat.reading();}
    void closeHistory(){chat.reset();}
    void browse(TalkTranscriptLog log,int direction){paint.setTextSize(28);chat.browse(direction,log,measure);}
    void drawChat(Canvas canvas,TalkTranscriptLog log,TalkStatusBar.State status){
        canvas.drawColor(Color.BLACK);paint.setTypeface(regular);paint.setTextSize(28);
        TalkChatLayout.Page content=chat.reading()?chat.page():chat.live(log,measure);
        int top=TalkChatLayout.BOTTOM-content.height;
        for(TalkChatLayout.Bubble bubble:content.bubbles){
            boolean mine="user".equals(bubble.role);
            float left=mine?616-bubble.width:24;
            paint.setStyle(Paint.Style.FILL);paint.setColor(mine?0xff16364a:0xff191c20);
            canvas.drawRoundRect(new android.graphics.RectF(left,top,left+bubble.width,top+bubble.height),16,16,paint);
            paint.setTextSize(28);paint.setColor(0xfff1f3f4);
            int baseline=top+32;
            for(String line:bubble.lines){canvas.drawText(line,left+14,baseline,paint);baseline+=TalkChatLayout.LINE;}
            top+=bubble.height+TalkChatLayout.GAP;
        }
        TalkStatusBar.draw(canvas,paint,status,chat.reading(),chat.pageNumber(),chat.pageCount());
    }
    /** Empty strip slot only: never covers the transcript, Stop icon, timer or battery. */
    void drawPhotoNotice(Canvas canvas,String text) {
        if(text==null||text.length()==0)return;
        paint.setTypeface(regular);paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffe8eaed);paint.setTextSize(18);
        if(!text.equals(photoNoticeSource)) {
            photoNoticeSource=text;
            photoNoticeText=ChecklistText.fit(text,180,measure);
        }
        canvas.drawText(photoNoticeText,62,31,paint);
    }
    void draw(Canvas canvas, TalkHudState state) {
        canvas.drawColor(Color.BLACK);
        paint.setTypeface(regular);
        paint.setColor(0xffbbbbbb);
        paint.setTextSize(24);
        canvas.drawText("Франциска", 40, 54, paint);
        String connection=fitted(0,state.connection,330);
        canvas.drawText(connection, 600-paint.measureText(connection), 54, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(44);
        canvas.drawText(fitted(1,state.heading,560), 40, 130, paint);
        paint.setTextSize(26);
        canvas.drawText(fitted(2,state.cue,560), 40, 168, paint);

        // Transcript is subordinate to live state and never replaces Stop/capture cues.
        paint.setColor(0xffbbbbbb);
        paint.setTextSize(28);
        if(!state.detail.equals(detailSource)) {
            detailSource=state.detail;
            detailLines=ChecklistText.wrap(state.detail,560,2,measure);
        }
        for (int i=0;i<detailLines.size();i++) canvas.drawText(detailLines.get(i),40,211+i*34,paint);

        paint.setColor(state.capturing ? 0xffffb870 : Color.WHITE);
        paint.setTextSize(24);
        canvas.drawText(fitted(3,state.control,560), 40, 294, paint);
        paint.setColor(0xffbbbbbb);
        paint.setTextSize(22);
        canvas.drawText(fitted(4,state.hint,560), 40, 330, paint);
    }
}
