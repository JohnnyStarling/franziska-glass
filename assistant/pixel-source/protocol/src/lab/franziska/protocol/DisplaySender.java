package lab.franziska.protocol;
import java.util.*;
/** One in-flight command plus one latest value; instance belongs to one authenticated epoch. */
public final class DisplaySender {
    private String id,slot,terminal;
    private int generation,revision;
    private Map<String,Object> inFlight,latest,last;
    private long sentAt,nextAt;
    public boolean active(){return id!=null;}
    public String id(){return id;}
    public Map<String,Object> last(){return last;}
    public void begin(String owner,String selected,int gen,long now){
        if(active())throw new IllegalStateException("DISPLAY_BUSY");
        id=owner;slot=selected;generation=gen;revision=-1;last=null;terminal=null;latest=null;nextAt=now;
        latest=command("begin",DisplayCalibration.Profile.identity(),0,false);
    }
    private Map<String,Object> command(String op,DisplayCalibration.Profile profile,int pattern,boolean original){
        return AssistWire.payload("id",id,"generation",generation,"revision",0,"op",op,"slot",slot,
            "pattern",pattern,"original",original,"profile",profile.map());
    }
    public void update(DisplayCalibration.Profile profile,int pattern,boolean original){
        if(active()&&last!=null&&terminal==null)latest=command("update",profile,pattern,original);
    }
    public void finish(boolean save){
        if(!active())return;
        terminal=save?"save":"cancel";
        if(!save)latest=null;
    }
    public Map<String,Object> next(long now){
        if(!active()||inFlight!=null||now<nextAt)return null;
        if(latest!=null){inFlight=latest;latest=null;}
        else if(terminal!=null){inFlight=command(terminal,DisplayCalibration.Profile.identity(),0,false);terminal=null;}
        else return null;
        inFlight.put("revision",++revision);sentAt=now;nextAt=now+120;return inFlight;
    }
    public boolean acknowledge(Map<String,Object> state){
        if(!active()||!id.equals(state.get("id"))||generation!=DisplayCalibration.n(state,"generation",1,Integer.MAX_VALUE)
            ||revision!=DisplayCalibration.n(state,"revision",0,Integer.MAX_VALUE)||!slot.equals(state.get("slot")))return false;
        String status=(String)state.get("status");
        // A bounded remote expiry/back is allowed after the last ACK.
        if(inFlight==null&&!("timeout".equals(status)||"cancelled".equals(status)))return false;
        last=state;inFlight=null;
        if(!"applied".equals(status)&&!"storage_error".equals(status)){id=null;latest=null;terminal=null;}
        return true;
    }
    public boolean timedOut(long now){return inFlight!=null&&now-sentAt>=5000;}
    public Map<String,Object> abort(){
        if(!active())return null;
        Map<String,Object> cancel=command("cancel",DisplayCalibration.Profile.identity(),0,false);
        cancel.put("revision",++revision);clear();return cancel;
    }
    public void clear(){id=null;inFlight=null;latest=null;terminal=null;}
}
