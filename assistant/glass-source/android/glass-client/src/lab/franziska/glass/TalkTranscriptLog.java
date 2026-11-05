package lab.franziska.glass;
import java.util.ArrayList;
import java.util.List;

/** Bounded, in-memory display history. No disk, sensors, transport or work authority. */
final class TalkTranscriptLog {
    static final int MAX_MESSAGES=48, MAX_CHARS=48000, MAX_MESSAGE_CHARS=4000;
    static final class Message {
        final String id,role,text; final boolean complete;
        Message(String id,String role,String text,boolean complete){this.id=id;this.role=role;this.text=text;this.complete=complete;}
    }
    private final ArrayList<Message> messages=new ArrayList<Message>();
    private final ArrayList<String> retired=new ArrayList<String>();
    private int chars,revision,legacy;
    synchronized void clear(){messages.clear();retired.clear();chars=0;legacy=0;revision++;}
    synchronized int revision(){return revision;}
    synchronized int size(){return messages.size();}
    synchronized List<Message> snapshot(){return new ArrayList<Message>(messages);}
    synchronized void accept(String id,String role,String text,boolean complete){
        if(id==null||id.length()==0||(!"user".equals(role)&&!"assistant".equals(role))||text==null||text.length()==0)return;
        if(retired.contains(id))return;
        String bounded=bound(text,MAX_MESSAGE_CHARS);
        for(int i=0;i<messages.size();i++){
            Message old=messages.get(i);
            if(!old.id.equals(id))continue;
            if(old.complete||!old.role.equals(role))return;
            if(old.text.equals(bounded)&&old.complete==complete)return;
            chars+=bounded.length()-old.text.length();
            messages.set(i,new Message(id,role,bounded,complete));revision++;trim();return;
        }
        messages.add(new Message(id,role,bounded,complete));chars+=bounded.length();revision++;trim();
    }
    /** Legacy peer does not transmit boundaries: only its latest preview per role is certain. */
    synchronized void legacy(String role,String text){
        Message last=messages.isEmpty()?null:messages.get(messages.size()-1);
        if(last==null||!last.role.equals(role)||!last.id.startsWith("legacy-")){
            legacy++;accept("legacy-"+legacy,role,text,false);
        } else accept(last.id,role,text,false);
    }
    private void trim(){
        while(messages.size()>MAX_MESSAGES||chars>MAX_CHARS){
            Message old=messages.remove(0);chars-=old.text.length();retired.add(old.id);
            if(retired.size()>128)retired.remove(0);
        }
    }
    static String bound(String text,int max){
        if(text.length()<=max)return text;
        int end=max-1;if(end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end)+"…";
    }
}
