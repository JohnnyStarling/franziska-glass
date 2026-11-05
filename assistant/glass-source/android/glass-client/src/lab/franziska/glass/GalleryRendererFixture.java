package lab.franziska.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

/** Synthetic, private-media-free fixture for the parent's Android bitmap harness. */
public final class GalleryRendererFixture {
    public static final int LIST=0, PHOTO=1, VIDEO=2, CONFIRM=3, EMPTY=4;
    public static void render(Canvas canvas, int fixture) {
        GalleryRenderer.Frame frame=new GalleryRenderer.Frame();
        if (fixture==LIST) {
            frame.title="Галерея · 1 / 2";
            frame.primary="Фото · пример.jpg";
            frame.secondary="Свайп — выбрать";
            frame.footer="Тап — открыть · вниз — выйти";
        } else if (fixture==PHOTO) {
            frame.title="Фото · пример.jpg";
            frame.media=true;frame.photo=Bitmap.createBitmap(4,4,Bitmap.Config.ARGB_8888);
            frame.photo.eraseColor(Color.WHITE);
            frame.footer="Тап — меню · вниз — список";
        } else if (fixture==VIDEO) {
            frame.title="Видео · пример.mp4";frame.media=true;frame.video=true;
            frame.primary="Тап — воспроизвести";
            frame.footer="Вверх — отправить · вниз — список";
        } else if (fixture==CONFIRM) {
            frame.title="Отправить оригинал?";
            frame.primary="Оригинал скопируется на Pixel";
            frame.secondary="Получателя выбирают на Pixel";
            frame.footer="Тап — подтвердить · вниз — отмена";
        } else {
            frame.title="Галерея";frame.primary="Нет фото и видео";
            frame.footer="Вниз — выйти";
        }
        new GalleryRenderer().draw(canvas,640,360,frame);
        if (frame.photo!=null) frame.photo.recycle();
    }
    private GalleryRendererFixture() {}
}
