package lab.franziska.glass;
import java.util.ArrayList;
import java.util.List;
/** Plain text only, no truncation or executable rendering. Four measured lines per page. */
final class AssistPages {
    // Must match GlassActivity's answer paint and the 40px left/right margins.
    static final int ANSWER_WIDTH = 560;
    static List<String[]> paginate(String text,ChecklistText.Measurer measure){
        text=text.replaceAll("[\\p{Cntrl}&&[^\\n]]"," ").replace('\n',' ');
        ArrayList<String[]> pages=new ArrayList<String[]>();ArrayList<String> lines=new ArrayList<String>();
        int offset=0;
        while(offset<text.length()){
            int end=offset;
            while(end<text.length()){
                int next=text.offsetByCodePoints(end,1);
                if(measure.width(text.substring(offset,next))>ANSWER_WIDTH&&end>offset)break;
                end=next;
            }
            if(end<text.length()){int space=text.lastIndexOf(' ',end);if(space>offset)end=space;}
            lines.add(text.substring(offset,end));offset=end;while(offset<text.length()&&text.charAt(offset)==' ')offset++;
            if(lines.size()==4){pages.add(lines.toArray(new String[lines.size()]));lines.clear();}
        }
        if(!lines.isEmpty())pages.add(lines.toArray(new String[lines.size()]));
        if(pages.isEmpty())pages.add(new String[]{" "});return pages;
    }
}
