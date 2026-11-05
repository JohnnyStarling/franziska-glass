package lab.franziska.glass;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Short, glanceable opt-in card; the stock camera remains the photo source. */
public final class PhotoSyncActivity extends Activity {
    private final ChecklistInput keysAndDedupe = new ChecklistInput();
    private TapGesture touch, generic;
    private SharedPreferences prefs;
    private Card card;
    private volatile boolean resumed;
    private boolean focused;
    private long lastToggle;
    private volatile String localError;
    private final SharedPreferences.OnSharedPreferenceChangeListener statusListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override public void onSharedPreferenceChanged(SharedPreferences changed, String key) {
            if ("status".equals(key) || "enabled".equals(key) || "oversize_count".equals(key)) {
                localError = null;
                if (resumed && card != null) card.postInvalidate();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("photo-sync", MODE_PRIVATE);
        float slop = ViewConfiguration.get(this).getScaledTouchSlop();
        touch = new TapGesture(slop);
        generic = new TapGesture(slop);
        card = new Card(); setContentView(card);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        prefs.registerOnSharedPreferenceChangeListener(statusListener);
        card.invalidate();
    }
    @Override protected void onPause() {
        resumed = false; revokeInput();
        prefs.unregisterOnSharedPreferenceChangeListener(statusListener);
        super.onPause();
    }
    @Override protected void onStop() { revokeInput(); super.onStop(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        focused = hasFocus;
        if (!hasFocus) revokeInput();
        else if (resumed) card.invalidate();
    }
    private void revokeInput() { keysAndDedupe.reset(); touch.cancel(); generic.cancel(); }
    private boolean foreground() { return resumed && focused && !isFinishing(); }

    private boolean enabled() {
        if (!prefs.getBoolean("enabled", false)) return false;
        ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        java.util.List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(100);
        if (services != null) for (ActivityManager.RunningServiceInfo item : services)
            if (PhotoSyncService.class.getName().equals(item.service.getClassName())) return true;
        return false;
    }
    private void toggle() {
        if (!foreground()) return;
        long now = SystemClock.elapsedRealtime();
        if (lastToggle != 0 && now - lastToggle < 700L) return;
        lastToggle = now;
        Intent intent = new Intent(this, PhotoSyncService.class);
        intent.setAction(enabled() ? PhotoSyncService.STOP : PhotoSyncService.START);
        try {
            if (startService(intent) == null) localError = "Служба недоступна";
            else localError = null;
        } catch (RuntimeException unavailable) { localError = "Служба недоступна"; }
        card.invalidate();
        card.postDelayed(new Runnable() { public void run() { if (resumed) card.invalidate(); } }, 350L);
    }
    private void dispatch(String action, String route, long down, long at) {
        if (!foreground() || !keysAndDedupe.route(route, action, down, at)) return;
        if ("tap".equals(action)) toggle();
        else if ("exit".equals(action)) finish();
    }
    private static String keyAction(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) return "tap";
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_DOWN) return "exit";
        return null;
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyAction(keyCode) == null) return super.onKeyDown(keyCode, event);
        if (!foreground()) { keysAndDedupe.reset(); return true; }
        keysAndDedupe.down(keyCode, event.getRepeatCount(), event.getDownTime(),
                event.isLongPress() || event.isCanceled());
        return true;
    }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        String action = keyAction(keyCode);
        if (action == null) return super.onKeyUp(keyCode, event);
        long down = keysAndDedupe.downAt();
        if (foreground() && keysAndDedupe.up(keyCode, event.getEventTime(),
                event.isCanceled() || event.isLongPress() || event.getRepeatCount() != 0
                        || event.getDownTime() != down))
            dispatch(action, "key", down, event.getEventTime());
        else keysAndDedupe.reset();
        return true;
    }
    private static boolean accepted(MotionEvent event) {
        int source = event.getSource();
        return (source & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD
                || (source & InputDevice.SOURCE_CLASS_POINTER) != 0;
    }
    private static String feed(TapGesture gate, MotionEvent event) {
        int count = event.getPointerCount();
        for (int i = 0; count == 1 && i < event.getHistorySize(); i++)
            gate.event(MotionEvent.ACTION_MOVE, count, event.getPointerId(0),
                    event.getHistoricalX(0, i), event.getHistoricalY(0, i),
                    event.getDownTime(), event.getHistoricalEventTime(i));
        return gate.event(event.getActionMasked(), count,
                count == 1 ? event.getPointerId(0) : -1,
                count > 0 ? event.getX() : Float.NaN,
                count > 0 ? event.getY() : Float.NaN,
                event.getDownTime(), event.getEventTime());
    }
    private boolean motion(MotionEvent event, TapGesture gate, String route) {
        if (!accepted(event)) { gate.cancel(); return false; }
        if (!foreground()) { gate.cancel(); return true; }
        String action = feed(gate, event);
        if (action != null) dispatch(action, route, gate.downAt(), event.getEventTime());
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return motion(event, touch, "touch") || super.dispatchTouchEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return motion(event, generic, "motion") || super.onGenericMotionEvent(event);
    }

    private final class Card extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ChecklistText.Measurer measure = new ChecklistText.Measurer() {
            public float width(String value) { return paint.measureText(value); }
        };
        Card() { super(PhotoSyncActivity.this); setBackgroundColor(Color.BLACK); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 640f, getHeight() / 360f);
            canvas.save(); canvas.scale(scale, scale);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-light", 0));
            paint.setColor(Color.WHITE); paint.setTextSize(38);
            canvas.drawText("Фото на Pixel", 40, 92, paint);
            boolean active = enabled();
            paint.setTextSize(32);
            String status = localError != null ? localError
                    : active ? prefs.getString("status", "Каталог активен") : "Выключено";
            canvas.drawText(ChecklistText.fit(status, 560, measure), 40, 170, paint);
            paint.setTextSize(24); paint.setColor(Color.WHITE);
            int skipped = prefs.getInt("oversize_count", 0);
            String detail = skipped > 0 ? "Авто не перенесло фото >32 МБ: " + skipped
                    : "Авто: новые фото камеры; архив — с Pixel";
            canvas.drawText(ChecklistText.fit(detail, 560, measure), 40, 218, paint);
            canvas.drawText(ChecklistText.fit(active ? "Тап — выключить · вниз — назад"
                    : "Тап — включить · вниз — назад", 560, measure), 40, 302, paint);
            canvas.restore();
        }
    }
}
