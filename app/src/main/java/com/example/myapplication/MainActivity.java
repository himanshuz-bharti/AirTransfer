package com.example.myapplication;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.ContentResolver;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.example.myapplication.Transfer.TransferWebSocketClient;
import com.example.myapplication.camera.FrameData;
import com.example.myapplication.camera.FrameProcessor;
import com.example.myapplication.camera.ImageConverter;
import com.example.myapplication.camera.PerformaneMonitor;
import com.example.myapplication.camera.VideoRecorder;
import com.example.myapplication.frame.FrameCorners;
import com.example.myapplication.frame.FrameDetector;
import com.example.myapplication.frame.FramePoint;
import com.example.myapplication.ui.OverlayView;
import com.example.myapplication.vision.HandLandMark;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.example.myapplication.Transfer.TransferFileSender;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraX";
    private static final int REQUEST_PERMISSION_CODE = 10;
    private static final int REQUEST_STORAGE_CODE = 11;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private FrameProcessor frameProcessor;
    private PreviewView viewFinder;
    private ExecutorService cameraExecutor;
    private HandLandMark handlandmark;
    private PerformaneMonitor performanceMonitor;

    private volatile FrameCorners latestframeCorners;
    private volatile Bitmap latestframe;

    private OverlayView overlayView;

    private ExecutorService captureExecutor;
    private VideoRecorder videoRecorder;
    private volatile boolean isRecording = false;
    private ExecutorService videoExecutor;
    private File pendingVideoFile;

    private TextView recTimer;
    private View recDot;
    private LinearLayout recIndicator;
    private ImageButton btnRecordBtn;
    private long recordingStartedAtMs;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private TransferWebSocketClient webSocketClient;
    private TransferFileSender fileSender;

    private final Runnable recTick = new Runnable() {
        @Override
        public void run() {
            if(recordingStartedAtMs == 0) return;
            updateRecTimer();
            uiHandler.postDelayed(this, 200);
        }
    };

    private void startRecIndicator(){
        recordingStartedAtMs = SystemClock.elapsedRealtime();
        recIndicator.setVisibility(View.VISIBLE);
        recDot.setAlpha(1f);
        updateRecTimer();
        uiHandler.removeCallbacks(recTick);
        uiHandler.post(recTick);
    }

    private void stopRecIndicator(){
        recordingStartedAtMs = 0;
        uiHandler.removeCallbacks(recTick);
        recIndicator.setVisibility(View.GONE);
    }

    private void setRecordButtonState(boolean recording){
        Drawable bg = btnRecordBtn.getBackground();
        if(bg != null){
            bg.setTint(recording ? Color.argb(255, 220, 30, 30) : Color.argb(0, 255, 255, 255));
        }
    }

    private void updateRecTimer(){
        long elapsed = (SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000;
        long mm = elapsed / 60;
        long ss = elapsed % 60;
        recTimer.setText(String.format(Locale.US, "REC %02d:%02d", mm, ss));
        recDot.setAlpha(elapsed % 2 == 0 ? 1f : 0.25f);
    }

    private final ActivityResultLauncher<String> filePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    if (fileSender != null) {
                        fileSender.sendFile(getContentResolver(), uri);
                    } else {
                        Toast.makeText(this, "WebSocket not initialized", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    private void openFilePicker() {
        filePicker.launch("*/*");
    }

    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();

    private void testWebSocket() {
        try {
            URI uri = new URI("ws://192.168.1.4:8080");
            webSocketClient = new TransferWebSocketClient(uri, new TransferWebSocketClient.Listener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "WebSocket connected");
                }

                @Override
                public void onDisconnected() {
                    Log.i(TAG, "WebSocket disconnected");
                }

                @Override
                public void onTextMessage(String message) {
                    if (fileSender != null) {
                        fileSender.onTextMessage(message);
                    }
                    Log.d(TAG, "WS message: " + message);
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "WebSocket error", e);
                }
            });
            wsExecutor.execute(() -> {
                try {
                    webSocketClient.connect();
                } catch (Exception e) {
                    Log.e(TAG, "Connect failed", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "testWebSocket error", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        frameProcessor = new FrameProcessor();
        performanceMonitor = new PerformaneMonitor();

        viewFinder = findViewById(R.id.viewFinder);
        overlayView = findViewById(R.id.overlayView);

        recIndicator = findViewById(R.id.recIndicator);
        recTimer = findViewById(R.id.recTimer);
        recDot = findViewById(R.id.recDot);

        // Initialize file sender safely
        // Note: webSocketClient is created elsewhere in your code if you have it.
        // If it's not initialized yet, fileSender will be null; the button will guard against it.
        Log.i(TAG,"Before websockets initialization");
        testWebSocket();
        Log.i(TAG,"After websockets initialization");
        if (webSocketClient != null) {
            fileSender = new TransferFileSender(webSocketClient);
        } else {
            fileSender = null;
        }

        // Set up send file button (guarded if connection not ready)
        ImageButton btnSendFile = findViewById(R.id.btnSendFile);
        if (btnSendFile != null) {
            btnSendFile.setOnClickListener(v -> {
                if (webSocketClient != null && webSocketClient.isOpen() && fileSender != null) {
                    openFilePicker();
                } else {
                    Toast.makeText(this, "WebSocket not connected", Toast.LENGTH_SHORT).show();
                }
            });
        }

        handlandmark = new HandLandMark(this,
                new HandLandMark.ResultListener() {
                    @Override
                    public void onResult(HandLandmarkerResult result, int imageWidth, int imageHeight, int rotationDegrees) {
                        Log.d(TAG, "MediaPipe image = " + imageWidth + "x" + imageHeight
                                + " | Overlay = " + overlayView.getWidth()
                                + "x" + overlayView.getHeight());

                        FrameCorners frameCorners = FrameDetector.detect(result);
                        latestframeCorners = frameCorners;
                        if(frameCorners != null) {
                            Log.i(TAG, "TL = " + frameCorners.getTopleft().x
                                    + "," + frameCorners.getTopleft().y);
                            Log.i(TAG, "BL = " + frameCorners.getBottomleft().x
                                    + "," + frameCorners.getBottomleft().y);
                            Log.i(TAG, "TR = " + frameCorners.getTopright().x
                                    + "," + frameCorners.getTopright().y);
                            Log.i(TAG, "BR = " + frameCorners.getBottomright().x
                                    + "," + frameCorners.getBottomright().y);
                        }

                        overlayView.setResult(result.landmarks(), imageWidth, imageHeight, rotationDegrees);
                        overlayView.setFrameCorners(frameCorners);
                    }

                    @Override
                    public void onError(RuntimeException error) {
                        Log.e(TAG, "MediaPipe error", error);
                    }
                });

        cameraExecutor = Executors.newSingleThreadExecutor();
        captureExecutor = Executors.newSingleThreadExecutor();
        videoExecutor = Executors.newSingleThreadExecutor();
        videoRecorder = new VideoRecorder();

        findViewById(R.id.btnCapture).setOnClickListener(v -> capturePhoto());
        btnRecordBtn = findViewById(R.id.btnRecord);
        btnRecordBtn.setOnClickListener(v -> toggleRecord());

        if(allRequiredPermissions()){
            startCamera();
        }
        else{
            ActivityCompat.requestPermissions(this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_PERMISSION_CODE);
        }
    }

    private void capturePhoto(){
        final Bitmap capturedFrame = latestframe;
        if (capturedFrame == null) {
            Toast.makeText(this, "No image for capture", Toast.LENGTH_SHORT).show();
            return;
        }

        final FrameCorners corners = latestframeCorners;

        captureExecutor.execute(() -> {
            Bitmap out = capturedFrame.copy(Bitmap.Config.ARGB_8888, true);
            if(out == null) return;

            if(corners != null){
                drawQuad(corners, out);
            }

            long currentTimeMs = System.currentTimeMillis();
            String filename = "hand_" + currentTimeMs + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GestureController");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);

            ContentResolver contentResolver = getContentResolver();
            Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if(uri == null) return;
            try(OutputStream os = contentResolver.openOutputStream(uri)){
                out.compress(Bitmap.CompressFormat.JPEG, 95, os);
            } catch(IOException e){
                Log.e(TAG, "Photo saved failed" + e);
                return;
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            contentResolver.update(uri, v, null, null);
            runOnUiThread(() -> {
                Toast.makeText(this, "Saved to Pictures/GestureController/" + filename, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void toggleRecord(){
        if(isRecording){
            isRecording = false;
            videoExecutor.execute(() -> {
                File file = videoRecorder.stop();
                if(file != null && file.exists() && file.length() > 0){
                    publishVideoToGallery(file);
                    Log.i(TAG, "Recording saved to " + file.getAbsolutePath()
                            + " (" + file.length() / 1024 + " KB)");
                    runOnUiThread(() -> Toast.makeText(this, "Video saved: " + file.getName(), Toast.LENGTH_LONG).show());
                }
                else{
                    runOnUiThread(() -> Toast.makeText(this, "Recording Failed", Toast.LENGTH_SHORT).show());
                }
            });
            runOnUiThread(() -> setRecordButtonState(false));
            stopRecIndicator();
        }
        else{
            final Bitmap first = latestframe;
            if(first == null){
                Toast.makeText(this, "No camera Frames yet", Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getCacheDir(), "video");
            dir.mkdirs();
            pendingVideoFile = new File(dir, "gestures_" + System.currentTimeMillis() + ".mp4");
            File file = pendingVideoFile;
            videoExecutor.execute(() -> {
                try{
                    videoRecorder.start(file, first.getWidth(), first.getHeight());
                    runOnUiThread(() -> {
                        isRecording = true;
                        setRecordButtonState(true);
                        startRecIndicator();
                    });
                } catch(Exception e){
                    Log.e(TAG, "failed to start recording" + e);
                    runOnUiThread(() -> Toast.makeText(this, "Could not start recording", Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void drawQuad(FrameCorners fc, Bitmap bmp){
        FramePoint tl = fc.getTopleft();
        FramePoint tr = fc.getTopright();
        FramePoint bl = fc.getBottomleft();
        FramePoint br = fc.getBottomright();

        if(tl == null || tr == null || bl == null || br == null) return;
        Path quad = new Path();
        float w = bmp.getWidth(), h = bmp.getHeight();

        quad.moveTo(tl.x * w, tl.y * h);
        quad.lineTo(tr.x * w, tr.y * h);
        quad.lineTo(br.x * w, br.y * h);
        quad.lineTo(bl.x * w, bl.y * h);
        quad.close();

        Canvas canvas = new Canvas(bmp);
        float stroke = Math.max(6f, bmp.getWidth() / 60f);

        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(stroke * 2.2f);
        halo.setColor(Color.argb(160, 0, 0, 0));
        halo.setStrokeCap(Paint.Cap.ROUND);
        halo.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(quad, halo);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setColor(Color.CYAN);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(quad, line);
    }

    private void startCamera(){
        requestStorageIfNeeded();

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try{
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    long frameStartTime = System.nanoTime();
                    try{
                        FrameData data = frameProcessor.processFrame(image);

                        Bitmap imgbitmap = ImageConverter.toBitmap(image);

                        long timestampMs = image.getImageInfo().getTimestamp() / 1_000_000L;
                        int rotationDegrees = image.getImageInfo().getRotationDegrees();

                        // Rotate to upright + mirror horizontally (front camera)
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationDegrees);
                        matrix.postScale(-1f, 1f);
                        Bitmap rotatedBitmap = Bitmap.createBitmap(imgbitmap, 0, 0,
                                imgbitmap.getWidth(), imgbitmap.getHeight(), matrix, true);

                        // Run hand landmark detection on the upright, mirrored frame
                        handlandmark.detectAsync(rotatedBitmap, timestampMs, rotationDegrees);

                        // Keep a copy of the rotated frame for capture / recording
                        latestframe = rotatedBitmap;

                        final FrameCorners cornersForRecording = latestframeCorners;

                        if (isRecording) {
                            Bitmap recordCopy = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true);
                            if (cornersForRecording != null) {
                                drawQuad(cornersForRecording, recordCopy);
                            }
                            videoExecutor.execute(() -> videoRecorder.queueFrame(recordCopy));
                        }

                        performanceMonitor.onFrameEnd(frameStartTime);
                        Log.i(TAG, "width:" + data.getWidth() + " height:" + data.getheight()
                                + " rotationdegree:" + data.getrotationDegree() + " timestamp:" + data.getTimeStamp()
                                + " FPS:" + performanceMonitor.getfps()
                                + " lastframetimems:" + performanceMonitor.getLastFrameTimeMs());

                    } finally {
                        image.close();
                    }
                });

                CameraSelector camSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                try {
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, camSelector, preview, imageAnalysis);
                } catch(Exception ex){
                    Log.e(TAG, "Usecase binding failed", ex);
                }
            } catch(ExecutionException | InterruptedException e){
                Log.e(TAG, "Error getting camera provider" + e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void requestStorageIfNeeded(){
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_CODE);
        }
    }

    private boolean allRequiredPermissions(){
        for(String permission : REQUIRED_PERMISSIONS){
            if(ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantRes){
        super.onRequestPermissionsResult(requestCode, permissions, grantRes);
        if(requestCode == REQUEST_PERMISSION_CODE){
            if(allRequiredPermissions()){
                startCamera();
            }
            else{
                Toast.makeText(this, "Permission not granted by user", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        if(requestCode == REQUEST_STORAGE_CODE){
            if(ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                Toast.makeText(this, "Storage permission is denied, image won't save", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onStop(){
        super.onStop();
        finishRecordingIfActive();
    }

    private void finishRecordingIfActive(){
        if(!isRecording) return;
        isRecording = false;
        videoExecutor.execute(() -> {
            File file = videoRecorder.stop();
            if(file != null && file.exists() && file.length() > 0){
                publishVideoToGallery(file);
                Log.i(TAG, "Recording finalised on stop: " + file.getAbsolutePath());
            }
        });
        runOnUiThread(() -> setRecordButtonState(false));
        stopRecIndicator();
    }

    private void publishVideoToGallery(File file){
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/GestureController");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);

        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if(uri == null){
            file.delete();
            return;
        }
        try(OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(file)){
            byte[] buf = new byte[8192];
            int n;
            while((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }catch (IOException e) {
            Log.e(TAG, "video publish failed", e);
        }
        v.clear();
        v.put(MediaStore.Video.Media.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        file.delete();
        runOnUiThread(() -> Toast.makeText(this,
                "Saved to Movies/GestureController", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        finishRecordingIfActive();
        cameraExecutor.shutdown();
        captureExecutor.shutdown();
        videoExecutor.shutdown();
        handlandmark.cleanup();
    }
}
