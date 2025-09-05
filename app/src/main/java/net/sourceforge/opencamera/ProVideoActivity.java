package net.sourceforge.opencamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Range;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Surface;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProVideoActivity extends AppCompatActivity {
    private static final int CAMERA_REQUEST_CODE = 2001;
    private static final String TAG = "ProVideoActivity";

    private SurfaceView surfaceView;
    private Button recordButton;
    private Spinner spnRes, spnFps, spnBitrate;
    private CheckBox chkEis;
    private EditText etExposureMs, etIso, etFocusM;
    private TextView txtStatus;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private Surface previewSurface, recordSurface;
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;

    // ParcelFileDescriptor and Uri MUST be kept open while recording
    private ParcelFileDescriptor currentPfd = null;
    private Uri currentVideoUri = null;

    private int selWidth = 1920, selHeight = 1080;
    private int selFps = 30;
    private int selBitrate = 16_000_000; // 16 Mbps
    private boolean wantEis = false;

    private long userExposureNs = 0;      // 0 = auto
    private int userISO = 0;              // 0 = auto
    private Float userFocusDiopters = null; // null = auto

    // Telemetry callback (throttled)
    private final CameraCaptureSession.CaptureCallback statusCallback =
            new CameraCaptureSession.CaptureCallback() {
                private int skip = 0;
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull CaptureResult result) {
                    if ((skip++ & 7) != 0) return; // throttle updates: every 8th frame
                    Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                    Long expNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                    Float fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                    Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);

                    String isoStr = iso != null ? String.valueOf(iso) : "auto";
                    String expStr = (expNs != null && expNs > 0) ? formatExposure(expNs) : "auto";
                    String fStr = (fd != null && fd > 0f) ? String.format("f=%.2fm", 1f / fd) : "f=auto";
                    String aeStr = (ae != null && ae == CaptureResult.CONTROL_AE_STATE_CONVERGED) ? "AE ok" : "AE";
                    runOnUiThread(() -> {
                        if (txtStatus != null) {
                            txtStatus.setText("ISO=" + isoStr + ", " + expStr + ", " + fStr +
                                    " • " + selWidth + "x" + selHeight + "@" + selFps +
                                    " • " + (selBitrate/1_000_000) + "Mbps • " + aeStr);
                        }
                    });
                }
            };

    private static String formatExposure(long ns) {
        double s = ns / 1_000_000_000.0;
        if (s < 0.1) {
            int denom = Math.max(1, (int)Math.round(1.0 / s));
            return "1/" + denom + "s";
        }
        int ms = (int)Math.round(s * 1000.0);
        return ms + "ms";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pro_video);

        surfaceView = findViewById(R.id.camera_preview);
        recordButton = findViewById(R.id.record_button);
        spnRes = findViewById(R.id.spn_resolution);
        spnFps = findViewById(R.id.spn_fps);
        spnBitrate = findViewById(R.id.spn_bitrate);
        chkEis = findViewById(R.id.chk_eis);
        etExposureMs = findViewById(R.id.et_exposure_ms);
        etIso = findViewById(R.id.et_iso);
        etFocusM = findViewById(R.id.et_focus_m);
        txtStatus = findViewById(R.id.txt_status);

        setupSpinners();

        chkEis.setOnCheckedChangeListener((b, checked) -> wantEis = checked);

        recordButton.setOnClickListener(v -> {
            if (!isRecording) startRecording();
            else stopRecording();
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    CAMERA_REQUEST_CODE);
        } else {
            initSurface();
        }
    }

    private void setupSpinners() {
        String[] res = new String[]{"1920x1080","2560x1440","3840x2160","1280x720"};
        spnRes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, res));
        spnRes.setSelection(0);

        String[] fps = new String[]{"24","25","30","60"};
        spnFps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fps));
        spnFps.setSelection(2);

        String[] br = new String[]{"10","16","24","35","50","75","100"};
        spnBitrate.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, br));
        spnBitrate.setSelection(1);

        spnRes.setOnItemSelectedListener(SimpleSelect.on((pos, val) -> {
            String[] wh = val.split("x");
            selWidth = Integer.parseInt(wh[0]);
            selHeight = Integer.parseInt(wh[1]);
            restartPreviewSafe("Res changed");
        }));
        spnFps.setOnItemSelectedListener(SimpleSelect.on((pos, val) -> {
            selFps = Integer.parseInt(val);
            restartPreviewSafe("FPS changed");
        }));
        spnBitrate.setOnItemSelectedListener(SimpleSelect.on((pos, val) -> selBitrate = Integer.parseInt(val) * 1_000_000));
    }

    private void restartPreviewSafe(String reason) {
        if (isRecording) {
            toast("Cannot change while recording");
            return;
        }
        txtStatus.setText("Restart preview: " + reason);
        closeSessionOnly();
        startPreview();
    }

    private void initSurface() {
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(@NonNull SurfaceHolder holder) {
                // ensure preview surface size matches desired recording size (helps avoid mismatch)
                try {
                    holder.setFixedSize(selWidth, selHeight);
                } catch (Exception ignored) {}
                previewSurface = holder.getSurface();
                openCamera();
            }
            @Override public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(@NonNull SurfaceHolder holder) { closeCamera(); }
        });
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0]; // back camera (simple choice)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(@NonNull CameraDevice camera) { cameraDevice = camera; startPreview(); }
                @Override public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
                @Override public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    toast("Camera error: " + error);
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "openCamera failed", e);
            toast("Open camera failed");
        }
    }

    private void startPreview() {
        try {
            if (cameraDevice == null || previewSurface == null) return;
            closeSessionOnly();

            // attempt to set fixed size (again), if available
            try { surfaceView.getHolder().setFixedSize(selWidth, selHeight); } catch (Exception ignored) {}

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            if (wantEis) enableEIS(builder);
            applyManualControlsIfAny(builder);
            setFpsRange(builder, selFps);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), statusCallback, null);
                                txtStatus.setText("Preview " + selWidth + "x" + selHeight + " @" + selFps + "fps");
                            } catch (Exception e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                                toast("Preview failed");
                            }
                        }
                        @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { toast("Preview config failed"); }
                    }, null
            );
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
            toast("Preview start failed");
        }
    }

    /**
     * Prepare MediaRecorder and create MediaStore entry + keep ParcelFileDescriptor open.
     * On success: recordSurface is set, currentVideoUri/currentPfd are set and MUST be closed after recording.
     */
    private void prepareMediaRecorder() throws IOException {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ProVideo");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "provideo_" + System.currentTimeMillis() + ".mp4");

        Uri videoUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (videoUri == null) throw new IOException("Failed to create MediaStore entry");

        // IMPORTANT: keep the ParcelFileDescriptor open while recording
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(videoUri, "w");
        if (pfd == null) {
            // clean up
            getContentResolver().delete(videoUri, null, null);
            throw new IOException("Failed to open ParcelFileDescriptor");
        }

        // save references for close on stop
        currentVideoUri = videoUri;
        currentPfd = pfd;
        FileDescriptor fd = pfd.getFileDescriptor();

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(fd);

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        // configure based on user selections
        mediaRecorder.setVideoSize(selWidth, selHeight);
        mediaRecorder.setVideoFrameRate(selFps);
        mediaRecorder.setVideoEncodingBitRate(selBitrate);
        mediaRecorder.setAudioEncodingBitRate(128_000);
        mediaRecorder.setAudioSamplingRate(48_000);

        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "MediaRecorder error: " + what + " extra: " + extra);
            toast("Recorder error: " + what);
        });

        mediaRecorder.prepare();
        recordSurface = mediaRecorder.getSurface();
    }

    private void startRecording() {
        readManualInputs();
        try {
            if (cameraDevice == null) { toast("No camera"); return; }
            closeSessionOnly();

            prepareMediaRecorder();

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recordSurface);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recordSurface);

            if (wantEis) enableEIS(builder);
            applyManualControlsIfAny(builder);
            setFpsRange(builder, selFps);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(builder.build(), statusCallback, null);
                        mediaRecorder.start();
                        isRecording = true;
                        recordButton.setText("STOP (Pro)");
                        txtStatus.setText("Recording " + selWidth + "x" + selHeight + " @" + selFps + "fps / " + (selBitrate/1_000_000) + "Mbps");
                        toast("Recording started");
                    } catch (Exception e) {
                        Log.e(TAG, "Recorder start failure", e);
                        toast("Recorder start failed: " + e.getMessage());
                        // cleanup if start fails
                        safeReleaseMediaRecorderAndDeleteUri();
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    toast("Recording config failed");
                    safeReleaseMediaRecorderAndDeleteUri();
                }
            }, null);

        } catch (Exception e) {
            Log.e(TAG, "startRecording error", e);
            toast("Recording failed: " + e.getMessage());
            safeReleaseMediaRecorderAndDeleteUri();
        }
    }

    private void stopRecording() {
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.stop(); } catch (Exception ex) { Log.w(TAG, "mediaRecorder.stop() threw", ex); }
                try { mediaRecorder.reset(); } catch (Exception ignore) {}
                try { mediaRecorder.release(); } catch (Exception ignore) {}
                mediaRecorder = null;
            }

            // close ParcelFileDescriptor AFTER stopping and releasing recorder
            if (currentPfd != null) {
                try { currentPfd.close(); } catch (Exception ignore) {}
                currentPfd = null;
            }

            isRecording = false;
            recordButton.setText("Record (Pro)");
            toast("Saved to Gallery");
            txtStatus.setText("Saved. Ready.");

            // restart preview
            startPreview();
        } catch (Exception e) {
            Log.e(TAG, "stopRecording error", e);
            toast("Stop recording failed");
            safeReleaseMediaRecorderAndDeleteUri();
            startPreview();
        }
    }

    /**
     * If something fails during prepare/start, release mediaRecorder and delete the MediaStore entry
     * so we don't leave a 0-byte file.
     */
    private void safeReleaseMediaRecorderAndDeleteUri() {
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.reset(); } catch (Exception ignore) {}
                try { mediaRecorder.release(); } catch (Exception ignore) {}
                mediaRecorder = null;
            }
        } catch (Exception ignore) {}
        try {
            if (currentPfd != null) {
                try { currentPfd.close(); } catch (Exception ignore) {}
                currentPfd = null;
            }
        } catch (Exception ignore) {}
        try {
            if (currentVideoUri != null) {
                getContentResolver().delete(currentVideoUri, null, null);
                currentVideoUri = null;
            }
        } catch (Exception ignore) {}
    }

    private void setFpsRange(CaptureRequest.Builder b, int fps) {
        try { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range.create(fps, fps)); } catch (Exception ignore) {}
    }

    private void enableEIS(CaptureRequest.Builder b) {
        try { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON); } catch (Exception ignore) {}
    }

    private void applyManualControlsIfAny(CaptureRequest.Builder b) {
        boolean any = false;

        if (userISO > 0 || userExposureNs > 0) {
            try {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
                if (userISO > 0) { b.set(CaptureRequest.SENSOR_SENSITIVITY, userISO); any = true; }
                if (userExposureNs > 0) { b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, userExposureNs); any = true; }
            } catch (Exception ignore) {}
        }

        if (userFocusDiopters != null) {
            try {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, userFocusDiopters);
                any = true;
            } catch (Exception ignore) {}
        }

        if (!any) {
            try {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            } catch (Exception ignore) {}
        }
    }

    private void readManualInputs() {
        String expStr = etExposureMs.getText().toString().trim();
        userExposureNs = (expStr.isEmpty() || expStr.equals("0")) ? 0 : Long.parseLong(expStr) * 1_000_000L;

        String isoStr = etIso.getText().toString().trim();
        userISO = (isoStr.isEmpty() ? 0 : Integer.parseInt(isoStr));

        String fStr = etFocusM.getText().toString().trim();
        if (fStr.isEmpty() || fStr.equals("0")) userFocusDiopters = null;
        else {
            float meters = Float.parseFloat(fStr);
            userFocusDiopters = meters <= 0 ? null : 1.0f / meters;
        }
    }

    private void closeSessionOnly() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignore) {}
            captureSession = null;
        }
        recordSurface = null;
    }

    private void closeCamera() {
        closeSessionOnly();
        if (cameraDevice != null) { try { cameraDevice.close(); } catch (Exception ignore) {} cameraDevice = null; }
        safeReleaseMediaRecorderAndDeleteUri(); // release recorder + cleanup if needed
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
        if (txtStatus != null) txtStatus.setText(m);
    }

    private static class SimpleSelect implements android.widget.AdapterView.OnItemSelectedListener {
        interface OnPicked { void run(int position, String value); }
        private final OnPicked cb;
        private SimpleSelect(OnPicked cb){ this.cb = cb; }
        static SimpleSelect on(OnPicked cb){ return new SimpleSelect(cb); }
        @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) {
            Object v = parent.getItemAtPosition(pos);
            cb.run(pos, String.valueOf(v));
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }

    @Override protected void onDestroy() { super.onDestroy(); closeCamera(); }
}
