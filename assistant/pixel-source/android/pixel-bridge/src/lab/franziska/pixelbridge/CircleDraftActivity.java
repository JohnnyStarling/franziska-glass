package lab.franziska.pixelbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Owner-started local video-note draft. Intentionally contains no send/share action. */
public final class CircleDraftActivity extends Activity implements CircleRecorder.Listener {
    private static final int PERMISSION_REQUEST = 7102;
    private CircleDraftStore store;
    private CircleRecorder recorder;
    private FrameLayout previewFrame;
    private TextureView livePreview;
    private VideoView playback;
    private TextView status;
    private Button recordButton, stopButton, playButton, discardButton;
    private File ready;
    private boolean verifiedReady;
    private File waitingForPreview;
    private int playbackGeneration;
    private long recordingStartedAt;
    private boolean recordingUi;
    private boolean foreground;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!recordingUi) return;
            long elapsed = SystemClock.elapsedRealtime() - recordingStartedAt;
            status.setText(String.format(Locale.getDefault(), "Запись: %d с из 60 с. Остановится автоматически.",
                    Math.min(60, elapsed / 1000)));
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout[] holder = new LinearLayout[1];
        ScrollView scroll = CompanionUi.screen(this, holder);
        LinearLayout root = holder[0];
        CompanionUi.eyebrow(this, root, "PIXEL · ЛОКАЛЬНЫЙ ЧЕРНОВИК");
        CompanionUi.heading(this, root, "Видеочерновик");
        LinearLayout intro = CompanionUi.card(this, root);
        CompanionUi.badge(this, intro, "ДО 60 СЕКУНД · ТОЛЬКО PIXEL");
        CompanionUi.text(this, intro,
                "Камера и микрофон включатся только после вашего нажатия. Черновик сохранится на Pixel, без отправки в Telegram.",
                15, CompanionUi.MUTED);
        CompanionUi.disclosure(this, intro, "О кадре и хранении",
                "Квадратный предпросмотр показывает центральную область; прямоугольный исходник может содержать больше по краям. "
                + "Черновик остаётся только в закрытом хранилище Франциски на Pixel; Telegram не откроется и ничего не отправится.");
        status = CompanionUi.text(this, intro, "Камера выключена.", 15, CompanionUi.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        previewFrame = new SquarePreview(this);
        previewFrame.setBackgroundColor(0xff050609);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(-1, -2);
        frameParams.bottomMargin = CompanionUi.dp(this, 14);
        intro.addView(previewFrame, frameParams);
        livePreview = new TextureView(this);
        previewFrame.addView(livePreview, new FrameLayout.LayoutParams(-1, -1));
        livePreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                beginWhenPreviewReady();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                if (recorder != null) recorder.onPreviewSizeChanged();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (waitingForPreview != null) cancelWaitingForPreview();
                if (recorder != null) recorder.stop();
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
        playback = new VideoView(this);
        playback.setVisibility(View.GONE);
        previewFrame.addView(playback, new FrameLayout.LayoutParams(-1, -1));
        playback.setMediaController(new MediaController(this));
        previewFrame.setVisibility(View.GONE);

        recordButton = CompanionUi.button(this, intro, "Записать черновик", new View.OnClickListener() {
            @Override public void onClick(View view) { askToRecord(); }
        });
        stopButton = CompanionUi.button(this, intro, "Остановить запись", new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (waitingForPreview != null) {
                    cancelWaitingForPreview();
                    previewFrame.setVisibility(View.GONE);
                    status.setText("Запуск камеры отменён; черновик удалён.");
                    updateButtons();
                } else recorder.stop();
            }
        });
        CompanionUi.stop(stopButton, this);
        playButton = CompanionUi.button(this, intro, "Просмотреть черновик", new View.OnClickListener() {
            @Override public void onClick(View view) { playDraft(); }
        });
        CompanionUi.secondary(playButton, this);
        discardButton = CompanionUi.button(this, intro, "Удалить черновик", new View.OnClickListener() {
            @Override public void onClick(View view) { discardDraft(); }
        });
        CompanionUi.stop(discardButton, this);

        LinearLayout detail = CompanionUi.card(this, root);
        CompanionUi.text(this, detail, "Что дальше", 19, CompanionUi.INK);
        CompanionUi.text(this, detail,
                "Это только подготовка файла. Доставка на Mac, авторизация Telegram, выбор адресата и отправка "
                + "не подключены. Если камера выдаст прямоугольное видео, перед видеосообщением потребуется "
                + "проверенная квадратная перекодировка.", 14, CompanionUi.MUTED);
        setContentView(scroll);
        try {
            store = new CircleDraftStore(getFilesDir());
            store.clearPending(); // interrupted recordings are never treated as completed drafts
            recorder = new CircleRecorder(this, livePreview, this);
            refreshReady();
        } catch (IOException failure) {
            status.setText("Закрытое хранилище недоступно; запись отключена.");
            updateButtons();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (store != null && recorder != null && !recorder.isBusy()) refreshReady();
    }

    @Override protected void onPause() {
        foreground = false;
        ++playbackGeneration;
        if (playback != null) {
            playback.setOnPreparedListener(null);
            playback.stopPlayback(); // also cancels an asynchronous prepare not yet playing
            playback.setVisibility(View.GONE);
        }
        if (waitingForPreview != null) cancelWaitingForPreview();
        if (recorder != null) recorder.stop(); // never record in the background
        if (previewFrame != null && !recordingUi) previewFrame.setVisibility(View.GONE);
        recordingUi = false;
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (recorder != null) recorder.release();
        if (playback != null) playback.stopPlayback();
        super.onDestroy();
    }

    private void askToRecord() {
        if (store == null || recorder == null || recorder.isBusy()) return;
        if (ready != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Записать новый черновик?")
                    .setMessage("Текущий останется до успешного завершения новой записи, затем будет заменён.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Записать", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            requestCapture();
                        }
                    })
                    .show();
        } else requestCapture();
    }

    private void requestCapture() {
        if (!foreground) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    PERMISSION_REQUEST);
            return;
        }
        startCapture();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != PERMISSION_REQUEST) return;
        boolean camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (camera && microphone && foreground) startCapture();
        else status.setText("Для черновика нужны оба разрешения: камера и микрофон. Запись не началась.");
    }

    private void startCapture() {
        if (!foreground || store == null || recorder == null || recorder.isBusy()) return;
        try {
            File pending = store.createPending();
            ++playbackGeneration;
            playback.stopPlayback();
            playback.setVisibility(View.GONE);
            livePreview.setVisibility(View.VISIBLE);
            previewFrame.setVisibility(View.VISIBLE);
            status.setText("Запускаю камеру и микрофон…");
            waitingForPreview = pending;
            beginWhenPreviewReady();
            updateButtons();
        } catch (IOException failure) {
            status.setText("Не удалось создать закрытый черновик. Запись не началась.");
        }
    }

    private void beginWhenPreviewReady() {
        if (waitingForPreview == null || recorder == null || !livePreview.isAvailable()) return;
        File pending = waitingForPreview;
        waitingForPreview = null;
        recorder.start(pending);
        updateButtons();
    }

    private void cancelWaitingForPreview() {
        File pending = waitingForPreview;
        waitingForPreview = null;
        if (pending == null) return;
        try { store.discardPending(pending); }
        catch (IOException ignored) { pending.delete(); }
    }

    @Override public void onRecording() {
        recordingStartedAt = SystemClock.elapsedRealtime();
        recordingUi = true;
        ticker.run();
        updateButtons();
    }

    @Override public void onRecorded(File pending, long elapsedMs) {
        recordingUi = false;
        main.removeCallbacks(ticker);
        try {
            VideoInfo info = inspect(pending);
            if (!CircleDraftPolicy.playable(info.durationMs, pending.length(), info.hasAudio,
                    info.width, info.height)) {
                store.discardPending(pending);
                status.setText("Запись неполная, слишком длинная или без звука; она удалена.");
            } else {
                ready = store.promote(pending);
                verifiedReady = true;
                status.setText(String.format(Locale.getDefault(),
                        "Черновик готов: %.1f с, %d×%d, со звуком. Только на Pixel; не отправлен.%s",
                        info.durationMs / 1000.0, info.width, info.height,
                        CircleDraftPolicy.requiresSquareConversion(info.width, info.height)
                                ? " Для кружка нужна квадратная обработка." : ""));
            }
        } catch (IOException | RuntimeException failure) {
            try { store.discardPending(pending); } catch (IOException ignored) { /* still app-private */ }
            ready = store.ready().isFile() ? store.ready() : null;
            verifiedReady = false;
            status.setText("Видео не удалось проверить; неполный черновик не сохранён.");
        }
        previewFrame.setVisibility(View.GONE);
        updateButtons();
    }

    @Override public void onFailure(String reason) {
        recordingUi = false;
        main.removeCallbacks(ticker);
        previewFrame.setVisibility(View.GONE);
        status.setText(reason + " Ничего не отправлено.");
        refreshReadyButtons();
    }

    private void refreshReady() {
        ready = store.ready().isFile() ? store.ready() : null;
        verifiedReady = false;
        if (ready == null) { updateButtons(); return; }
        VideoInfo info;
        try {
            info = inspect(ready);
        } catch (IOException | RuntimeException inspectionFailure) {
            // A retriever or file-system hiccup cannot prove that this formerly
            // accepted draft is invalid. Keep it and retry inspection on resume.
            status.setText("Не удалось проверить сохранённый черновик; файл сохранён. Повторите позже.");
            updateButtons();
            return;
        }
        if (!CircleDraftPolicy.playable(info.durationMs, ready.length(), info.hasAudio,
                info.width, info.height)) {
            try {
                store.discard();
                ready = null;
                status.setText("Неполный старый черновик удалён.");
            } catch (IOException deletionFailure) {
                status.setText("Неполный старый черновик не удалось удалить; повторите попытку.");
            }
        } else {
            verifiedReady = true;
            status.setText(String.format(Locale.getDefault(),
                    "Черновик на Pixel: %.1f с, %d×%d. Ничего не отправлено.",
                    info.durationMs / 1000.0, info.width, info.height));
        }
        updateButtons();
    }

    private void refreshReadyButtons() {
        ready = store != null && store.ready().isFile() ? store.ready() : null;
        if (ready == null) verifiedReady = false;
        updateButtons();
    }

    private void playDraft() {
        if (!foreground || ready == null || recorder.isBusy()) return;
        if (!verifiedReady) refreshReady(); // explicit owner retry after a transient metadata failure
        if (ready == null || !verifiedReady) return;
        final int ticket = ++playbackGeneration;
        playback.stopPlayback();
        livePreview.setVisibility(View.GONE);
        playback.setVisibility(View.VISIBLE);
        previewFrame.setVisibility(View.VISIBLE);
        playback.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer player) {
                if (ticket != playbackGeneration || !foreground || ready == null || !verifiedReady) return;
                playback.start();
                status.setText("Просмотр локального черновика. Ничего не отправлено.");
            }
        });
        playback.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer player, int what, int extra) {
                if (ticket != playbackGeneration) return true;
                ++playbackGeneration;
                playback.stopPlayback();
                playback.setVisibility(View.GONE);
                previewFrame.setVisibility(View.GONE);
                status.setText("Черновик сохранён, но воспроизвести его сейчас не удалось.");
                return true;
            }
        });
        playback.setVideoPath(ready.getAbsolutePath());
        status.setText("Готовлю локальный просмотр. Ничего не отправлено.");
    }

    private void discardDraft() {
        if (store == null || recorder.isBusy()) return;
        ++playbackGeneration;
        playback.stopPlayback();
        previewFrame.setVisibility(View.GONE);
        try {
            store.discard();
            ready = null;
            verifiedReady = false;
            status.setText("Черновик удалён с Pixel.");
        } catch (IOException failure) {
            status.setText("Не удалось удалить черновик; повторите попытку.");
        }
        updateButtons();
    }

    private void updateButtons() {
        boolean busy = waitingForPreview != null || (recorder != null && recorder.isBusy());
        recordButton.setEnabled(store != null && !busy);
        stopButton.setEnabled(busy);
        playButton.setEnabled(ready != null && !busy);
        playButton.setText(verifiedReady ? "Просмотреть черновик" : "Проверить черновик");
        discardButton.setEnabled(ready != null && !busy);
    }

    private static VideoInfo inspect(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("missing file");
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            metadata.setDataSource(file.getAbsolutePath());
            String duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String audio = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (duration == null || width == null || height == null) throw new IOException("missing tracks");
            return new VideoInfo(Long.parseLong(duration), Integer.parseInt(width),
                    Integer.parseInt(height), "yes".equalsIgnoreCase(audio));
        } finally {
            metadata.release();
        }
    }

    private static final class VideoInfo {
        final long durationMs;
        final int width, height;
        final boolean hasAudio;
        VideoInfo(long durationMs, int width, int height, boolean hasAudio) {
            this.durationMs = durationMs; this.width = width; this.height = height; this.hasAudio = hasAudio;
        }
    }

    private static final class SquarePreview extends FrameLayout {
        SquarePreview(Activity activity) { super(activity); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec);
            int square = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            super.onMeasure(square, square);
        }
    }
}
