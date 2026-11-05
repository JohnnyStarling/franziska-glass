package lab.franziska.glass;
import android.graphics.Paint;
import lab.franziska.protocol.DisplayCalibration.Profile;
/** Bounded per-paint palette cache; bitmap pixels and camera textures stay unfiltered. */
final class DisplayPaint extends Paint {
    static volatile Profile profile=Profile.identity();
    private int source=0xff000000;
    private Profile cachedProfile;
    private final int[] keys=new int[32],values=new int[32];
    private int valid;
    DisplayPaint(int flags){super(flags);}
    @Override public void setColor(int color){
        source=color;
        Profile current=profile;
        if(current!=cachedProfile){cachedProfile=current;valid=0;}
        int slot=(color^(color>>>8)^(color>>>16)^(color>>>24))&31,bit=1<<slot;
        if((valid&bit)==0||keys[slot]!=color){
            keys[slot]=color;values[slot]=current.color(color);valid|=bit;
        }
        super.setColor(values[slot]);
    }
    @Override public int getColor(){return source;}
}
