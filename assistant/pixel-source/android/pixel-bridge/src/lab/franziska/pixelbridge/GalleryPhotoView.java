package lab.franziska.pixelbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Bounded sampled display only. Sharing always uses the separate immutable original. */
final class GalleryPhotoView extends View {
    private Bitmap bitmap;
    private float zoom=1,dx,dy;
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    GalleryPhotoView(Context c) {
        super(c);setContentDescription("Фото. Два касания — увеличить; разведите пальцы для масштаба.");
        scale=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));clamp();invalidate();return true;}
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){zoom=zoom>1?1:3;dx=dy=0;invalidate();return true;}
            @Override public boolean onScroll(MotionEvent a,MotionEvent b,float x,float y){if(!scale.isInProgress()){dx-=x;dy-=y;clamp();invalidate();}return true;}
        });
    }
    void setBitmap(Bitmap b){Bitmap old=bitmap;bitmap=b;zoom=1;dx=dy=0;if(old!=null&&old!=b)old.recycle();invalidate();}
    void release(){setBitmap(null);}
    private float fit(){return bitmap==null?1:Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());}
    private void clamp(){if(bitmap==null)return;float s=fit()*zoom;float x=Math.max(0,(bitmap.getWidth()*s-getWidth())/2),y=Math.max(0,(bitmap.getHeight()*s-getHeight())/2);dx=Math.max(-x,Math.min(x,dx));dy=Math.max(-y,Math.min(y,dy));}
    @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap==null)return;clamp();float s=fit()*zoom;Matrix m=new Matrix();m.postScale(s,s);m.postTranslate((getWidth()-bitmap.getWidth()*s)/2+dx,(getHeight()-bitmap.getHeight()*s)/2+dy);c.drawBitmap(bitmap,m,new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));}
    @Override public boolean onTouchEvent(MotionEvent e){scale.onTouchEvent(e);gestures.onTouchEvent(e);return true;}
}
