package lab.franziska.glass;
import android.graphics.*;
/** Small patches, never a full-screen white stimulus. Coordinates are native 640x360. */
final class DisplayPatterns {
    static void draw(Canvas c,Paint p,int pattern){
        c.drawColor(Color.BLACK);p.setStyle(Paint.Style.FILL);p.setTextSize(22);p.setColor(Color.LTGRAY);
        c.drawText("Изображение Glass · настройка с Pixel",24,38,p);
        int[] colors=pattern==0?new int[]{0,1,2,4,8,16,24,32,48,64}:
            pattern==1?new int[]{32,64,96,128,160,192,224,255}:new int[]{0xff3030,0x30ff30,0x3030ff,0xffff00,0x00ffff,0xff00ff,0xffffff};
        int width=560/colors.length;
        for(int i=0;i<colors.length;i++){
            int v=colors[i],rgb=pattern==2?v:(v<<16)|(v<<8)|v;
            p.setColor(0xff000000|rgb);c.drawRect(36+i*width,95,36+i*width+width-6,165,p);
            p.setColor(Color.LTGRAY);p.setTextSize(16);c.drawText(pattern==2?Integer.toString(i+1):Integer.toString(v),36+i*width,192,p);
        }
        p.setTextSize(24);p.setColor(Color.WHITE);c.drawText("Белый · серый · текст 0123456789",36,240,p);
        p.setColor(0xff808080);c.drawText("Серый / нейтральный",36,274,p);
        p.setTextSize(18);p.setColor(Color.LTGRAY);c.drawText("Назад / свайп вниз — отменить · фон RGB 0",24,332,p);
    }
}
