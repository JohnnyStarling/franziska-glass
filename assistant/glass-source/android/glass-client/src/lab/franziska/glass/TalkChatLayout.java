package lab.franziska.glass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded full-width chat; history is a frozen reading snapshot, not a second HUD. */
final class TalkChatLayout {
    static final int TOP=58,BOTTOM=344,AREA=BOTTOM-TOP,LINE=32,PADDING=20,GAP=10;
    static final int MAX_LINES=8;
    static final float TEXT_WIDTH=484;
    /** Chat can measure a source range without allocating a temporary prefix String. */
    interface RangeMeasurer extends ChecklistText.Measurer {
        float width(String text,int start,int end);
    }
    static final class Bubble {
        final List<String> lines;final String role;final int height;final float width;
        Bubble(List<String> lines,String role,ChecklistText.Measurer measure){
            this.lines=new ArrayList<String>(lines);this.role=role;this.height=lines.size()*LINE+PADDING;
            float longest=0;for(String line:lines)longest=Math.max(longest,measure.width(line));
            width=Math.min(512,Math.max(64,longest+28));
        }
    }
    static final class Page {
        final List<Bubble> bubbles=new ArrayList<Bubble>();int height;
        void add(Bubble bubble){if(!bubbles.isEmpty())height+=GAP;bubbles.add(bubble);height+=bubble.height;}
    }
    private Page live=new Page();
    private List<Page> history;
    private int liveRevision=-1,page;
    Page live(TalkTranscriptLog log,ChecklistText.Measurer measure){
        int revision=log.revision();
        if(revision!=liveRevision){
            List<TalkTranscriptLog.Message> messages=log.snapshot();
            ArrayList<Bubble> latest=new ArrayList<Bubble>();int remaining=AREA;
            for(int i=messages.size()-1;i>=0;i--){
                int room=remaining-(latest.isEmpty()?0:GAP);
                int count=Math.min(MAX_LINES,(room-PADDING)/LINE);if(count<1)break;
                TalkTranscriptLog.Message message=messages.get(i);
                List<String> rows=wrap(message.text,TEXT_WIDTH,measure);
                if(rows.isEmpty())continue;
                int from=Math.max(0,rows.size()-count);
                Bubble bubble=new Bubble(rows.subList(from,rows.size()),message.role,measure);
                latest.add(bubble);remaining=room-bubble.height;
                if(from>0)break;
            }
            Collections.reverse(latest);live=new Page();for(Bubble bubble:latest)live.add(bubble);
            liveRevision=revision;
        }
        return live;
    }
    boolean reading(){return history!=null;}
    void reset(){history=null;page=0;liveRevision=-1;}
    void browse(int direction,TalkTranscriptLog log,ChecklistText.Measurer measure){
        if(history==null){
            if(log.size()==0)return;
            history=new ArrayList<Page>();Page current=new Page();
            for(TalkTranscriptLog.Message message:log.snapshot()){
                List<String> rows=wrap(message.text,TEXT_WIDTH,measure);int index=0;
                while(index<rows.size()){
                    int available=AREA-current.height-(current.bubbles.isEmpty()?0:GAP);
                    int count=Math.min(MAX_LINES,(available-PADDING)/LINE);
                    if(count<1){history.add(current);current=new Page();continue;}
                    int end=Math.min(rows.size(),index+count);
                    current.add(new Bubble(rows.subList(index,end),message.role,measure));index=end;
                }
            }
            if(!current.bubbles.isEmpty())history.add(current);
            if(history.isEmpty()){history=null;return;}
            page=history.size()-1;return;
        }
        int next=page+direction;
        if(next>=history.size()){history=null;page=0;return;}
        page=Math.max(0,next);
    }
    Page page(){return history==null?new Page():history.get(page);}
    int pageNumber(){return page+1;}
    int pageCount(){return history==null?0:history.size();}
    static List<String> wrap(String source,float width,ChecklistText.Measurer measure){
        ArrayList<String> rows=new ArrayList<String>();
        String text=source.replace('\r',' ').replace('\t',' ');
        RangeMeasurer range=measure instanceof RangeMeasurer?(RangeMeasurer)measure:null;
        int start=0;
        while(start<text.length()){
            int end=start,lastSpace=-1;
            while(end<text.length()&&text.charAt(end)!='\n'){
                int next=text.offsetByCodePoints(end,1);
                if((range!=null?range.width(text,start,next):measure.width(text.substring(start,next)))>width)break;
                if(text.charAt(end)==' ')lastSpace=end;
                end=next;
            }
            if(end==start&&text.charAt(start)=='\n'){rows.add("");start++;continue;}
            if(end==start)end=text.offsetByCodePoints(start,1);
            else if(end<text.length()&&text.charAt(end)!='\n'&&lastSpace>start)end=lastSpace;
            rows.add(text.substring(start,end));start=end;
            while(start<text.length()&&text.charAt(start)==' ')start++;
            if(start<text.length()&&text.charAt(start)=='\n')start++;
        }
        return rows;
    }
}
