package lab.franziska.glass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Only top/bottom chrome: the owner must see the actual TextureView in the center. */
final class PhotoPreviewOverlay {
    private PhotoPreviewOverlay() {}
    static void draw(Canvas canvas, Paint paint) {
        paint.setStyle(Paint.Style.FILL);
        // Opaque caption bars retain contrast even over a bright camera preview.
        paint.setColor(Color.BLACK);
        canvas.drawRect(16, 12, 624, 70, paint);
        canvas.drawRect(16, 266, 624, 344, paint);
        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        paint.setTextSize(24);
        canvas.drawText("Фото по кнопке камеры", 40, 51, paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(22);
        canvas.drawText("Снимаю фото · тап — отменить", 40, 309, paint);
    }
}
