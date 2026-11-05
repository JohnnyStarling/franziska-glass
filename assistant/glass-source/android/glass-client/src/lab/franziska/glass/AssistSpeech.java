package lab.franziska.glass;

import android.content.Context;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Foreground answer speech only. Never listens, speaks a question, or survives Stop/pause. */
final class AssistSpeech {
    interface Listener { void unavailable(); void finished(); }
    private final Context context;
    private final Handler ui;
    private final Listener listener;
    private TextToSpeech engine;
    private long generation;

    AssistSpeech(Context context, Handler ui, Listener listener) {
        this.context=context;this.ui=ui;this.listener=listener;
    }

    synchronized void stop() {
        generation++;
        TextToSpeech old=engine;engine=null;
        if(old!=null){
            try{old.stop();}catch(RuntimeException ignored){}
            try{old.shutdown();}catch(RuntimeException ignored){}
        }
    }

    synchronized void say(String answer) {
        stop();
        if(answer==null||answer.trim().length()==0)return;
        final long token=++generation;
        final List<String> parts=chunks(answer,500);
        final TextToSpeech created;
        try{created=new TextToSpeech(context,new TextToSpeech.OnInitListener(){
            public void onInit(final int status){
                ui.post(new Runnable(){public void run(){
                    synchronized(AssistSpeech.this){
                        if(token!=generation||engine==null)return;
                        try{
                            if(status!=TextToSpeech.SUCCESS || engine.setLanguage(new Locale("ru","RU"))<TextToSpeech.LANG_AVAILABLE){
                                stop();listener.unavailable();return;
                            }
                            final String last="franziska-"+token+"-"+(parts.size()-1);
                            engine.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                                public void onStart(String id){}
                                public void onDone(String id){if(last.equals(id))ui.post(new Runnable(){public void run(){
                                    synchronized(AssistSpeech.this){if(token==generation){stop();listener.finished();}}
                                }});}
                                public void onError(String id){ui.post(new Runnable(){public void run(){
                                    synchronized(AssistSpeech.this){if(token==generation){stop();listener.unavailable();}}
                                }});}
                            });
                            for(int i=0;i<parts.size();i++){
                                HashMap<String,String> params=new HashMap<String,String>();
                                params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,"franziska-"+token+"-"+i);
                                if(engine.speak(parts.get(i),i==0?TextToSpeech.QUEUE_FLUSH:TextToSpeech.QUEUE_ADD,params)==TextToSpeech.ERROR){
                                    stop();listener.unavailable();return;
                                }
                            }
                        }catch(RuntimeException unavailable){
                            stop();listener.unavailable();
                        }
                    }
                }});
            }
        });}catch(RuntimeException unavailable){listener.unavailable();return;}
        engine=created;
    }

    static List<String> chunks(String text,int max) {
        if(max<2)throw new IllegalArgumentException();
        ArrayList<String> out=new ArrayList<String>();
        text=text.trim();
        while(text.length()>0){
            int end=Math.min(max,text.length());
            if(end<text.length()){
                int space=text.lastIndexOf(' ',end);
                if(space>end/2)end=space;
                else if(Character.isHighSurrogate(text.charAt(end-1)))end--;
            }
            out.add(text.substring(0,end).trim());
            text=text.substring(end).trim();
        }
        return out;
    }
}
