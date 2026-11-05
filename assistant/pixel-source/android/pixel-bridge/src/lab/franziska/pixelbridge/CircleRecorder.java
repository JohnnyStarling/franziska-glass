package lab.franziska.pixelbridge;

import android.app.Activity;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Foreground-only Camera2 + microphone capture into an app-private pending MP4. */
final class CircleRecorder {
    private static final int MAX_CAPTURE_ATTEMPTS = 8;
    private static final int MAX_SIZES_PER_CAMERA = 4;
    interface Listener {
        void onRecording();
        void onRecorded(File pending, long elapsedMs);
        void onFailure(String reason);
    }

    private static final int IDLE = 0, STARTING = 1, RECORDING = 2, STOPPING = 3;
    private final Activity owner;
    private final TextureView preview;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int state = IDLE;
    private int generation;
    private long startedAt;
    private File pending;
    private MediaRecorder recorder;
    private CameraManager cameraManager;
    private List<CameraChoice> choices;
    private int nextChoice;
    private CameraChoice activeChoice;
    private int activeOrientationHint;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private Surface previewSurface;
    private Surface recordSurface;
    private final Runnable deadline = new Runnable() {
        @Override public void run() { stop(); }
    };

    CircleRecorder(Activity owner, TextureView preview, Listener listener) {
        this.owner = owner;
        this.preview = preview;
        this.listener = listener;
    }

    boolean isBusy() { return state != IDLE; }

    void start(File file) {
        if (state != IDLE) return;
        if (file == null || !preview.isAvailable()) {
            if (file != null) file.delete();
            listener.onFailure("Предпросмотр камеры пока не готов.");
            return;
        }
        pending = file;
        state = STARTING;
        try {
            cameraManager = (CameraManager) owner.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) throw new IOException("camera manager unavailable");
            choices = chooseCameras(cameraManager);
            nextChoice = 0;
            tryNextChoice();
        } catch (CameraAccessException | IOException | RuntimeException failure) {
            fail("Не удалось начать запись: " + failure.getClass().getSimpleName() + ".");
        }
    }

    private List<CameraChoice> chooseCameras(CameraManager manager)
            throws CameraAccessException, IOException {
        List<CameraChoice> front = new ArrayList<CameraChoice>();
        List<CameraChoice> back = new ArrayList<CameraChoice>();
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) continue;
            Size[] available = map.getOutputSizes(MediaRecorder.class);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            if (available == null || previewSizes == null) continue;
            List<int[]> compatible = new ArrayList<int[]>();
            for (Size recorderSize : available) {
                for (Size previewSize : previewSizes) {
                    if (recorderSize.equals(previewSize)) {
                        compatible.add(new int[] {recorderSize.getWidth(), recorderSize.getHeight()});
                        break;
                    }
                }
            }
            int[][] ranked = CircleDraftPolicy.rankVideoSizes(
                    compatible.toArray(new int[0][]), MAX_SIZES_PER_CAMERA);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            Integer sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            boolean isFront = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            for (int[] size : ranked) {
                CameraChoice choice = new CameraChoice(id, new Size(size[0], size[1]),
                        isFront, sensor == null ? 90 : sensor);
                (isFront ? front : back).add(choice);
            }
        }
        if (front.isEmpty() && back.isEmpty())
            throw new IOException("no compatible bounded video and preview size");
        List<CameraChoice> attempts = new ArrayList<CameraChoice>();
        int frontReserve = back.isEmpty() ? MAX_CAPTURE_ATTEMPTS : MAX_CAPTURE_ATTEMPTS - 2;
        for (CameraChoice choice : front) {
            if (attempts.size() >= frontReserve) break;
            attempts.add(choice);
        }
        for (CameraChoice choice : back) {
            if (attempts.size() >= MAX_CAPTURE_ATTEMPTS) break;
            attempts.add(choice);
        }
        for (int i = frontReserve; i < front.size() && attempts.size() < MAX_CAPTURE_ATTEMPTS; i++)
            attempts.add(front.get(i));
        return attempts;
    }

    private void tryNextChoice() {
        if (state != STARTING) return;
        ++generation; // invalidate callbacks from the just-closed Camera2 attempt
        releaseResources();
        while (nextChoice < choices.size() && nextChoice < MAX_CAPTURE_ATTEMPTS) {
            final CameraChoice choice = choices.get(nextChoice++);
            final int ticket = ++generation;
            try {
                // A failed prepare/start may have left an incomplete MP4 here.
                new FileOutputStream(pending, false).close();
                activeChoice = choice;
                setupRecorder(pending, choice);
                cameraManager.openCamera(choice.id, new CameraDevice.StateCallback() {
                    @Override public void onOpened(CameraDevice opened) {
                        if (ticket != generation || state != STARTING) { opened.close(); return; }
                        camera = opened;
                        configureCapture(ticket, choice);
                    }
                    @Override public void onDisconnected(CameraDevice opened) {
                        if (camera != opened) opened.close();
                        if (ticket == generation && state == STARTING) tryNextChoice();
                        else if (ticket == generation) fail("Камера отключилась.");
                    }
                    @Override public void onError(CameraDevice opened, int error) {
                        if (camera != opened) opened.close();
                        if (ticket == generation && state == STARTING) tryNextChoice();
                        else if (ticket == generation) fail("Ошибка камеры (" + error + ").");
                    }
                }, main);
                return;
            } catch (CameraAccessException | IOException | RuntimeException unsupported) {
                ++generation;
                releaseResources();
            }
        }
        fail("Камера не поддержала доступные безопасные размеры записи. Черновик не создан.");
    }

    private void setupRecorder(File file, CameraChoice choice) throws IOException {
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(file.getAbsolutePath());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(choice.size.getWidth(), choice.size.getHeight());
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(3_000_000);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(44_100);
        recorder.setAudioEncodingBitRate(96_000);
        activeOrientationHint = orientationHint(choice);
        recorder.setOrientationHint(activeOrientationHint);
        recorder.setMaxDuration(CircleDraftPolicy.MAX_DURATION_MS);
        recorder.setMaxFileSize(CircleDraftPolicy.MAX_FILE_BYTES);
        recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override public void onInfo(MediaRecorder source, int what, int extra) {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop();
            }
        });
        recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
            @Override public void onError(MediaRecorder source, int what, int extra) {
                if (state == STARTING) tryNextChoice();
                else fail("Рекордер сообщил об ошибке (" + what + ").");
            }
        });
        recorder.prepare();
        recordSurface = recorder.getSurface();
    }

    private int orientationHint(CameraChoice choice) {
        int rotation = owner.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180
                : rotation == Surface.ROTATION_270 ? 270 : 0;
        return choice.front ? (choice.sensorOrientation + degrees) % 360
                : (choice.sensorOrientation - degrees + 360) % 360;
    }

    private void configureCapture(final int ticket, CameraChoice choice) {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null) throw new IOException("preview surface unavailable");
            texture.setDefaultBufferSize(choice.size.getWidth(), choice.size.getHeight());
            updatePreviewTransform(choice);
            previewSurface = new Surface(texture);
            camera.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (ticket != generation || state != STARTING) { configured.close(); return; }
                            session = configured;
                            try {
                                CaptureRequest.Builder request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD);
                                request.addTarget(previewSurface);
                                request.addTarget(recordSurface);
                                request.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                session.setRepeatingRequest(request.build(), null, main);
                                recorder.start();
                                startedAt = SystemClock.elapsedRealtime();
                                state = RECORDING;
                                main.postDelayed(deadline, CircleDraftPolicy.RECORDING_CUTOFF_MS);
                                listener.onRecording();
                            } catch (CameraAccessException | RuntimeException failure) {
                                tryNextChoice();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession failed) {
                            failed.close();
                            if (ticket == generation) tryNextChoice();
                        }
                    }, main);
        } catch (CameraAccessException | IOException | RuntimeException failure) {
            tryNextChoice();
        }
    }

    void onPreviewSizeChanged() {
        if (activeChoice != null && state != IDLE) {
            try { updatePreviewTransform(activeChoice); }
            catch (IllegalArgumentException ignored) { /* next available size callback will retry */ }
        }
    }

    private void updatePreviewTransform(CameraChoice choice) {
        Matrix transform = new Matrix();
        transform.setValues(CirclePreviewGeometry.transform(
                choice.size.getWidth(), choice.size.getHeight(), preview.getWidth(), preview.getHeight(),
                activeOrientationHint));
        preview.setTransform(transform);
    }

    void stop() {
        if (state == STARTING) { cancelStarting(); return; }
        if (state != RECORDING) return;
        state = STOPPING;
        ++generation;
        main.removeCallbacks(deadline);
        File finished = pending;
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        try {
            if (session != null) session.stopRepeating();
            recorder.stop();
            releaseResources();
            state = IDLE;
            pending = null;
            listener.onRecorded(finished, elapsed);
        } catch (CameraAccessException | RuntimeException failure) {
            releaseResources();
            state = IDLE;
            pending = null;
            if (finished != null) finished.delete();
            listener.onFailure("Запись не завершилась корректно; черновик удалён.");
        }
    }

    void cancelStarting() {
        if (state != STARTING) return;
        ++generation;
        File failed = pending;
        pending = null;
        state = IDLE;
        releaseResources();
        if (failed != null) failed.delete();
    }

    void release() {
        if (state == RECORDING) stop();
        else if (state == STARTING) cancelStarting();
        else releaseResources();
    }

    private void fail(String message) {
        if (state == IDLE || state == STOPPING) return;
        ++generation;
        main.removeCallbacks(deadline);
        File failed = pending;
        pending = null;
        state = IDLE;
        releaseResources();
        if (failed != null) failed.delete();
        listener.onFailure(message);
    }

    private void releaseResources() {
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (recordSurface != null) { recordSurface.release(); recordSurface = null; }
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { /* already stopped or failed */ }
            recorder.release();
            recorder = null;
        }
        activeChoice = null;
    }

    private static final class CameraChoice {
        final String id;
        final Size size;
        final boolean front;
        final int sensorOrientation;
        CameraChoice(String id, Size size, boolean front, int sensorOrientation) {
            this.id = id; this.size = size; this.front = front; this.sensorOrientation = sensorOrientation;
        }
    }
}
