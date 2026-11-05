package lab.franziska.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

/** Dependency-free 640x360 Glass canvas; preview pixels never rewrite source media. */
final class GalleryRenderer {
    static final int WIDTH=640, HEIGHT=360, MARGIN=40, BODY=32, FOOTER=24;
    static final class Frame {
        String title="Галерея", primary="", secondary="", footer="";
        Bitmap photo;
        boolean video, media;
    }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    GalleryRenderer() { paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL)); }
    void draw(Canvas canvas, int width, int height, Frame frame) {
        canvas.save();
        canvas.scale(width/(float)WIDTH,height/(float)HEIGHT);
        canvas.drawColor(Color.BLACK);
        paint.setColor(Color.WHITE); paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(BODY);
        line(canvas,frame.title,40,65,560);
        if (frame.media && frame.photo!=null && !frame.photo.isRecycled()) {
            Bitmap image=frame.photo;
            float scale=Math.min(560f/image.getWidth(),196f/image.getHeight());
            float w=image.getWidth()*scale, h=image.getHeight()*scale;
            Rect src=new Rect(0,0,image.getWidth(),image.getHeight());
            RectF dst=new RectF(320-w/2f,180-h/2f,320+w/2f,180+h/2f);
            canvas.drawBitmap(image,src,dst,paint);
        } else if (frame.media && frame.video) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
            canvas.drawRect(40,82,600,278,paint); paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(BODY); line(canvas,"ВИДЕО",260,176,300);
            paint.setTextSize(FOOTER); line(canvas,frame.primary,40,227,560);
        } else {
            paint.setTextSize(BODY); line(canvas,frame.primary,40,130,560);
            if (frame.secondary!=null && frame.secondary.length()>0)
                line(canvas,frame.secondary,40,185,560);
        }
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(FOOTER);line(canvas,frame.footer,40,315,560);
        canvas.restore();
    }
    private void line(Canvas canvas, String value, float x, float y, float maxWidth) {
        if (value==null || value.length()==0) return;
        String clean=label(value);
        if (paint.measureText(clean)>maxWidth) {
            String mark="…";
            int end=clean.length();
            while (end>0 && paint.measureText(clean.substring(0,end)+mark)>maxWidth)
                end=clean.offsetByCodePoints(end,-1);
            clean=clean.substring(0,end)+mark;
        }
        canvas.drawText(clean,x,y,paint);
    }
    private static String label(String value) {
        StringBuilder clean=new StringBuilder();
        for(int i=0;i<value.length() && clean.length()<100;i++) {
            char c=value.charAt(i);
            if(c>=32 && c!=127 && !Character.isISOControl(c)) clean.append(c);
        }
        return clean.toString();
    }
}
