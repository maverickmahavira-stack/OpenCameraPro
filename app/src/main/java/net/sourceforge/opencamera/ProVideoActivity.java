package net.sourceforge.opencamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraManager;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProVideoActivity extends AppCompatActivity {
    private static final int CAMERA_REQUEST_CODE = 2001;
    private static final String TAG = "ProVideoActivity";

    private SurfaceView surfaceView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private boolean isRecording = false;
    private String outputFilePath;

    private MediaRecorder mediaRecorder;
    private Surface previewSurface;
    private Surface recordSurface;

    private Button recordButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pro_video);

        surfaceView = findViewById(R.id.camera_preview);
        recordButton = findViewById(R.id.record_button);

        recordButton.setOnClickListener(v -> {
            if (!isRecording) {
                startRecording();
            } else {
                stopRecording();
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    CAMERA_REQUEST_CODE);
        } else {
            initSurface();
        }
    }

    private void initSurface() {
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                previewSurface = holder.getSurface();
                openCamera();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                closeCamera();
            }
        });
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0]; // back camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
        }
    }

    private void startPreview() {
        try {
            if (cameraDevice == null || previewSurface == null) return;

            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                    List.of(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, null);
                                Toast.makeText(ProVideoActivity.this,
                                        "Pro Video Preview started",
                                        Toast.LENGTH_SHORT).show();
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(ProVideoActivity.this,
                                    "Preview config failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    private void prepareMediaRecorder() throws IOException {
        mediaRecorder = new MediaRecorder();

        // ✅ Save to Movies directory (safe for Android 12+)
        String folderPath = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES).getAbsolutePath();
        java.io.File folder = new java.io.File(folderPath);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        outputFilePath = folderPath + "/provideo_" + System.currentTimeMillis() + ".mp4";
        Log.d(TAG, "Output file: " + outputFilePath);

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outputFilePath);

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        mediaRecorder.setVideoEncodingBitRate(10_000_000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(1920, 1080);

        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            Log.e(TAG, "MediaRecorder error: " + what + " extra: " + extra);
            Toast.makeText(this, "Recorder error: " + what, Toast.LENGTH_SHORT).show();
        });

        mediaRecorder.prepare();
        recordSurface = mediaRecorder.getSurface();
    }

    private void startRecording() {
        try {
            if (cameraDevice == null) return;

            // ✅ Close previous session to avoid conflict
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }

            prepareMediaRecorder();

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(recordSurface);

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(recordSurface);

            cameraDevice.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(builder.build(), null, null);

                                try {
                                    mediaRecorder.start();
                                    isRecording = true;
                                    recordButton.setText("STOP (Pro)");
                                    Toast.makeText(ProVideoActivity.this,
                                            "Recording started: " + outputFilePath,
                                            Toast.LENGTH_SHORT).show();
                                } catch (Exception e) {
                                    Log.e(TAG, "MediaRecorder.start() failed", e);
                                    Toast.makeText(ProVideoActivity.this,
                                            "❌ MediaRecorder failed: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show();
                                }

                            } catch (CameraAccessException e) {
                                Log.e(TAG, "startRecording session failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(ProVideoActivity.this,
                                    "Recording config failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    },
                    null
            );

        } catch (Exception e) {
            Log.e(TAG, "startRecording error", e);
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;

            isRecording = false;
            recordButton.setText("Record (Pro)");

            Toast.makeText(this,
                    "Recording stopped. Saved: " + outputFilePath,
                    Toast.LENGTH_LONG).show();

            Log.d(TAG, "Recording stopped. File saved at: " + outputFilePath);

            // ✅ Return to preview
            startPreview();
        } catch (Exception e) {
            Log.e(TAG, "stopRecording error", e);
            Toast.makeText(this, "Stop recording failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
    }
}
