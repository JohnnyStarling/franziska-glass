package lab.franziska.pixelbridge;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Resolution-independent outlined controls; 24dp glyph inside a 48dp touch target. */
final class GalleryIcon extends Drawable {
    static final int BACK=0,SELECT=1,LINK=2,REFRESH=3,COPY=4,SHARE=5,EDIT=6,MORE=7,INFO=8;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;
    GalleryIcon(int kind){this.kind=kind;p.setColor(0xffc4d5ff);p.setStrokeWidth(1.8f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
    private void line(Canvas c,float a,float b,float x,float y){c.drawLine(a,b,x,y,p);}
    public void draw(Canvas c){Rect b=getBounds();int save=c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
        switch(kind){
        case BACK:line(c,19,12,5,12);line(c,5,12,11,6);line(c,5,12,11,18);break;
        case SELECT:c.drawRoundRect(new RectF(3,3,21,21),4,4,p);line(c,7,12,10,15);line(c,10,15,17,8);break;
        case LINK:c.drawRoundRect(new RectF(7,2,17,22),2,2,p);line(c,10,18,14,18);line(c,10,8,12,10);line(c,12,10,19,4);break;
        case REFRESH:c.drawArc(new RectF(4,4,20,20),40,290,false,p);line(c,19,3,19,9);line(c,19,9,13,9);break;
        case COPY:line(c,12,3,12,15);line(c,7,10,12,15);line(c,12,15,17,10);line(c,4,17,4,21);line(c,4,21,20,21);line(c,20,21,20,17);break;
        case SHARE:c.drawCircle(6,12,2.5f,p);c.drawCircle(18,5,2.5f,p);c.drawCircle(18,19,2.5f,p);line(c,8,11,16,6);line(c,8,13,16,18);break;
        case EDIT:Path q=new Path();q.moveTo(4,20);q.lineTo(5,14);q.lineTo(16,3);q.lineTo(21,8);q.lineTo(10,19);q.close();c.drawPath(q,p);line(c,13,6,18,11);break;
        case MORE:c.drawCircle(12,5,1,p);c.drawCircle(12,12,1,p);c.drawCircle(12,19,1,p);break;
        case INFO:c.drawCircle(12,12,9,p);line(c,12,11,12,17);c.drawCircle(12,7,0.7f,p);break;
        }
        c.restoreToCount(save);
    }
    public void setAlpha(int a){p.setAlpha(a);invalidateSelf();}
    public void setColorFilter(ColorFilter f){p.setColorFilter(f);invalidateSelf();}
    public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
