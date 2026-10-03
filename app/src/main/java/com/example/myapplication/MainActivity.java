package com.example.myapplication;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.myapplication.camera.VideoRecorder;
import com.example.myapplication.filter.CartoonFilter;
import com.example.myapplication.filter.RoiCartoonProcessor;
import com.example.myapplication.frame.FrameCorners;
import com.example.myapplication.frame.FrameDetector;
import com.example.myapplication.frame.FramePoint;
import com.example.myapplication.vision.HandLandMark;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.myapplication.camera.FrameData;
import com.example.myapplication.camera.FrameProcessor;

import com.example.myapplication.camera.PerformaneMonitor;
import com.example.myapplication.ui.OverlayView;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import com.example.myapplication.camera.ImageConverter;
import com.example.myapplication.camera.ImageDumper;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.example.myapplication.filter.AnimeGanProcessor;

import org.opencv.android.OpenCVLoader;
import android.graphics.Rect;

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

    private volatile Bitmap originalBitmapBeforeRotation;

    private ImageView processedView;

    private FrameDetector frameDetector;
    private OverlayView overlayView;

    private AnimeGanProcessor animeGanProcessor;

    private ExecutorService ganExecutor;
    private volatile Bitmap animeFullFrame;
    private volatile Rect lastGanRoi;
    private long lastGanMs;
    private volatile boolean ganBusy = false;
    private volatile long ganGeneration;
    private volatile Bitmap latestframe;
    private ExecutorService captureExecutor;
    private VideoRecorder videoRecorder;
    private volatile boolean isRecording = false;
    private ExecutorService videoExecutor;
    private File pendingVideoFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if(!OpenCVLoader.initLocal()){
            Log.i(TAG,"OpenCV loading failed");
        }
        else{
            Log.i(TAG,"OpenCV loading succeded");
        }

        frameProcessor = new FrameProcessor();

        performanceMonitor = new PerformaneMonitor();

        try{
            animeGanProcessor = new AnimeGanProcessor(this);
//            InputStream inputStream = getAssets().open("20260919_151325.jpg");
//            Bitmap testBitmap = BitmapFactory.decodeStream(inputStream);
//            inputStream.close();
//
//            long startTime = System.currentTimeMillis();
//
//            Bitmap result = animeGanProcessor.process(testBitmap);
//
//            long elapsed = System.currentTimeMillis() - startTime;
//
//            Log.d(TAG, "Inference completed in " + elapsed + " ms");
//
//            //save bitmap
//
//            File outputFile = new File(getExternalFilesDir(null),"anime_res.png");
//            FileOutputStream outputStream = new FileOutputStream(outputFile);
//
//            result.compress(Bitmap.CompressFormat.PNG,100,outputStream);
//
//            outputStream.flush();
//            outputStream.close();
//
//            Log.d(TAG, "Result saved to: " + outputFile.getAbsolutePath());
//
        }catch(Exception e){
            Log.e(TAG,"AnimeGan faield to load");
        }

        viewFinder = findViewById(R.id.viewFinder);

        overlayView = findViewById(R.id.overlayView);

        processedView = findViewById(R.id.processedView);

        frameDetector = new FrameDetector();

        handlandmark = new HandLandMark(this,
                new HandLandMark.ResultListener() {
                    @Override
                    public void onResult(HandLandmarkerResult result,int imageWidth,int imageHeight,int rotationDegrees) {
                        Log.d(TAG, "MediaPipe image = " + imageWidth + "x" + imageHeight
                                       + " | Overlay = "
                                        + overlayView.getWidth()
                                        + "x"
                                        + overlayView.getHeight()
                        );

                        FrameCorners frameCorners = frameDetector.detect(result);
                        latestframeCorners = frameCorners;
                        if(frameCorners!=null) {
                            Log.i(TAG, "TL = " + frameCorners.getTopleft().x
                                    + "," + frameCorners.getTopleft().y);
                            Log.i(TAG, "BL = " + frameCorners.getBottomleft().x
                                    + "," + frameCorners.getBottomleft().y);
                            Log.i(TAG, "TR = " + frameCorners.getTopright().x
                                    + "," + frameCorners.getTopright().y);
                            Log.i(TAG, "BR = " + frameCorners.getBottomright().x
                                    + "," + frameCorners.getBottomright().y);
                        }

                        overlayView.setResult(result.landmarks(),imageWidth,imageHeight,rotationDegrees);
                        overlayView.setFrameCorners(frameCorners);
                    }

                    @Override
                    public void onError(RuntimeException error) {
                        Log.e(
                                TAG,
                                "MediaPipe error",
                                error
                        );
                    }
                });

        cameraExecutor = Executors.newSingleThreadExecutor();
        ganExecutor = Executors.newSingleThreadExecutor();
        captureExecutor = Executors.newSingleThreadExecutor();
        videoExecutor = Executors.newSingleThreadExecutor();
        videoRecorder = new VideoRecorder();

        findViewById(R.id.btnCapture).setOnClickListener(v->capturePhoto());
        findViewById(R.id.btnRecord).setOnClickListener(v->toggleRecord());
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
            Toast.makeText(this,"No image for capture",Toast.LENGTH_SHORT).show();
            return;
        }

        final FrameCorners corners = latestframeCorners;

        captureExecutor.execute(()->{

            Bitmap out = capturedFrame.copy(Bitmap.Config.ARGB_8888,true);
            if(out==null) return;

            if(corners!=null){
                drawQuad(corners,out);
            }
            long currentTimeMs = System.currentTimeMillis();
            String filename = "anime_" + currentTimeMs + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME,filename);
            v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES + "/AnimeFilter");
            v.put(MediaStore.Images.Media.IS_PENDING,1);

            ContentResolver contentResolver = getContentResolver();
            Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
            if(uri==null) return;
            try(OutputStream os = contentResolver.openOutputStream(uri)){
                out.compress(Bitmap.CompressFormat.JPEG,95,os);
            } catch(IOException e){
                Log.e(TAG,"Photo saved failed"+e);
                return;
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING,0);
            contentResolver.update(uri,v,null,null);
            runOnUiThread(()->{
                Toast.makeText(this,"Saved to Pictures/AnieFilter"+filename, Toast.LENGTH_LONG).show();
            });

        });
    }

    private void toggleRecord(){
        if(isRecording){
            isRecording = false;
            videoExecutor.execute(()->{
                File file = videoRecorder.stop();
                if(file!=null && file.exists() && file.length()>0){
                    // TODO: publish the finished mp4 to MediaStore Movies/AnimeFilter here.
                     publishVideoToGallery(file);
                    Log.i(TAG, "Recording saved to " + file.getAbsolutePath()
                            + " (" + file.length() / 1024 + " KB)");
                    runOnUiThread(()-> Toast.makeText(this,
                            "Video saved: " + file.getName(), Toast.LENGTH_LONG).show());
                }
                else{
                    runOnUiThread(()-> Toast.makeText(this,"Recording Failed",Toast.LENGTH_SHORT).show());
                }
            });

            runOnUiThread(()->((Button)findViewById(R.id.btnRecord)).setText("● REC"));
        }
        else{
            final Bitmap first = latestframe;
            if(first==null){
                Toast.makeText(this,"No camera Frames yet",Toast.LENGTH_SHORT).show();
                return;
            }
            File dir = new File(getCacheDir(),"video");
            dir.mkdirs();
            pendingVideoFile = new File(dir,"anime_"+System.currentTimeMillis()+".mp4");
            File file = pendingVideoFile;
            videoExecutor.execute(()->{
                try{
                    videoRecorder.start(file,first.getWidth(),first.getHeight());
                    runOnUiThread(()->isRecording=true);
                } catch(Exception e){
                    Log.e(TAG,"failed to start recording"+e);
                    runOnUiThread(()-> Toast.makeText(this,"Could not start recording",Toast.LENGTH_SHORT).show());
                }

            });
            runOnUiThread(()->((Button)findViewById(R.id.btnRecord)).setText("■ STOP"));
        }
    }


    private void drawQuad(FrameCorners fc,Bitmap bmp){
        FramePoint tl = fc.getTopleft();
        FramePoint tr = fc.getTopright();
        FramePoint bl = fc.getBottomleft();
        FramePoint br = fc.getBottomright();

        if(tl==null || tr==null || bl==null || br==null) return;
        Path quad = new Path();
        float w = bmp.getWidth() , h = bmp.getHeight();

        quad.moveTo(tl.x*w,tl.y*h);
        quad.lineTo(tr.x*w,tr.y*h);
        quad.lineTo(br.x*w,br.y*h);
        quad.lineTo(bl.x*w,bl.y*h);
        quad.close();

        Canvas canvas = new Canvas(bmp);

        float stroke = Math.max(6f,bmp.getWidth()/60f);

        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setStyle(Paint.Style.STROKE);
        halo.setStrokeWidth(stroke * 2.2f);
        halo.setColor(Color.argb(160, 0, 0, 0));
        halo.setStrokeCap(Paint.Cap.ROUND);
        halo.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(quad, halo);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);       // cyan top stroke (matches OverlayView)
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

        cameraProviderFuture.addListener(()->{
            try{
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().
                        setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor,image ->{

                    long frameStartTime = System.nanoTime();
                    try{
                        FrameData data = frameProcessor.processFrame(image);
                        performanceMonitor.onFrameEnd(frameStartTime);

                        Log.d(TAG, "ImageProxy = " + image.getWidth() + "x" + image.getHeight());

                        Bitmap imgbitmap = ImageConverter.toBitmap(image);

                        originalBitmapBeforeRotation = imgbitmap.copy(Bitmap.Config.ARGB_8888,true);

                        Log.d(TAG, "Bitmap = " + imgbitmap.getWidth() + "x" + imgbitmap.getHeight());

                        long timestampMs = image.getImageInfo().getTimestamp()/1_000_000L;

                        int rotationDegrees = image.getImageInfo().getRotationDegrees();

                        // Rotate to upright + mirror horizontally (front camera)
                        // so MediaPipe works in the same orientation as the preview
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationDegrees);
                        matrix.postScale(-1f, 1f);
                        imgbitmap = Bitmap.createBitmap(imgbitmap, 0, 0,
                                imgbitmap.getWidth(), imgbitmap.getHeight(), matrix, true);

                        handlandmark.detectAsync(imgbitmap,timestampMs,0);

                        FrameCorners curCorners = latestframeCorners;
                        int imageW = imgbitmap.getWidth();
                        int imageH = imgbitmap.getHeight();

                        // Snapshot corners for the recorder so the outline baked into
                        // the video matches the pixels of this exact frame.
                        final FrameCorners cornersForRecording = latestframeCorners;

                        // Set by whichever branch runs below; feeds the recorder and
                        // mirrors exactly what processedView displays.
                        Bitmap displayFrame;

                        if(curCorners!=null){
                            // Quad present: run the cartoon filter + ROI blend.
                            // Skip both entirely when there is no quad (saves a
                            // full bilateral/adaptive-threshold pass per frame).

//                            Bitmap cartoon = CartoonFilter.apply(bitmap);
//                            Log.d(TAG, "Cartoon generated: " + cartoon.getWidth()
//                                            + "x" + cartoon.getHeight());

                            Rect box = computeQuad(curCorners,imageW,imageH);
//                            Bitmap stylized = (box!=null)?animeGanProcessor.stylize(bitmap,box):CartoonFilter.apply(bitmap);

                            if(box!=null){
                                long now = System.currentTimeMillis();
                                boolean movedFar = lastGanRoi == null || (Math.abs(box.centerX()-lastGanRoi.centerX())>40)
                                        || (Math.abs(box.centerY()-lastGanRoi.centerY())>40);
                                boolean stale = now - lastGanMs > 500;
                                if(!ganBusy && (movedFar || stale)){
                                    animeFullFrame = null;
                                    lastGanRoi = box;
                                    lastGanMs = now;
                                    ganBusy = true;
                                    ganGeneration++;
                                    final long gen = ganGeneration;
                                    final Bitmap frameforanime = imgbitmap;
                                    final Rect r = box;
                                    ganExecutor.execute(()->{
                                        try{
                                            Bitmap anime = animeGanProcessor.stylize(frameforanime,r);
                                            if(gen == ganGeneration) animeFullFrame = anime;
                                        } catch (Exception e) {
                                            throw new RuntimeException(e);
                                        } finally {
                                            ganBusy = false;
                                        }
                                    });
                                }
                            }
                            // displayFrame is in the correct upright+mirrored
                            // orientation (same as what PreviewView shows).
                            Bitmap stylized = (animeFullFrame!=null)?animeFullFrame:CartoonFilter.apply(imgbitmap);
                            final Bitmap finalbitmap = RoiCartoonProcessor.apply(
                                    imgbitmap,stylized,curCorners,imageW,imageH);

                            latestframe = finalbitmap;
                            displayFrame = finalbitmap;

                            runOnUiThread(()->{
                                processedView.setImageBitmap(finalbitmap);
                                processedView.setVisibility(View.VISIBLE);
                            });
                        }
                        else {
                            // No quad: hide the processed layer so the plain
                            // live PreviewView shows through — no cartoon
                            // anywhere on screen until hands return.
                            latestframe = imgbitmap;
                            displayFrame = imgbitmap;
                            runOnUiThread(()->{
                                processedView.setVisibility(View.GONE);
                            });
                        }

                        if (isRecording) {
                            // Copy: the display bitmap is shared with the UI thread, so
                            // drawing the outline on it would also paint the live preview.
                            Bitmap recordCopy = displayFrame.copy(Bitmap.Config.ARGB_8888, true);
                            if (cornersForRecording != null) {
                                drawQuad(cornersForRecording, recordCopy);
                            }
                            final Bitmap frameToRecord = recordCopy;
                            videoExecutor.execute(() -> videoRecorder.queueFrame(frameToRecord));
                        }
                        performanceMonitor.onFrameEnd(frameStartTime);
                        Log.i(TAG,"width:"+ data.getWidth() + " height:"+ data.getheight()+ " rotationdegree:"+ data.getrotationDegree() + " timestamp:"+ data.getTimeStamp()
                                +" FPS:" + performanceMonitor.getfps() + " lastframetimems:"+ performanceMonitor.getLastFrameTimeMs());

                        Log.d(
                                TAG,
                                "Frame sent to MediaPipe | "
                                        + "timestamp="
                                        + timestampMs
                        );

                    }
                    finally {
                        image.close();
                    }
                });

                    CameraSelector camSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                    try {
                        cameraProvider.unbindAll();

                        cameraProvider.bindToLifecycle(this, camSelector, preview, imageAnalysis);
                    } catch(Exception ex){
                        Log.e(TAG,"Usecase binding failed",ex);
                    }
            } catch(ExecutionException | InterruptedException e){
                Log.e(TAG,"Error getting camera provoder"+ e);
            }
        },ContextCompat.getMainExecutor(this));
    }

    private Rect computeQuad(FrameCorners fc,int w,int h){
        float minX=1,minY=1,maxX=0,maxY=0;
        FramePoint[] pts={fc.getTopleft(),fc.getTopright(),fc.getBottomleft(),fc.getBottomright()};
        for(FramePoint p:pts){
            if(p==null) return null;
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
        }
        int mx = (int) ((maxX - minX) * w * 0.10f) + 8;   // 10% margin + 8px padding
        int my = (int) ((maxY - minY) * h * 0.10f) + 8;
        return new Rect((int) (minX * w) - mx, (int) (minY * h) - my,
                (int) (maxX * w) + mx, (int) (maxY * h) + my);
    }

    private void requestStorageIfNeeded(){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_STORAGE_CODE);

        }
    }

    private boolean allRequiredPermissions(){
        for(String permission:REQUIRED_PERMISSIONS){
            if(ContextCompat.checkSelfPermission(this,permission)
                !=PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,@NonNull String[] permissions,@NonNull int[] grantRes){
        super.onRequestPermissionsResult(requestCode,permissions,grantRes);
        if(requestCode == REQUEST_PERMISSION_CODE){
            if(allRequiredPermissions()){
                startCamera();
            }
            else{
                Toast.makeText(this,"Permission not granted by user",Toast.LENGTH_SHORT).show();
                finish();
            }
        }

        if(requestCode == REQUEST_STORAGE_CODE){
            if(ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
                Toast.makeText(this,"Storage permission is denied, image wont save",Toast.LENGTH_LONG).show();;
            }
        }

    }

    @Override
    protected void onStop(){
        super.onStop();
        // A recording left open never gets its MP4 trailer written -> unplayable file.
        finishRecordingIfActive();
    }

    private void finishRecordingIfActive(){
        if(!isRecording) return;
        isRecording = false;
        videoExecutor.execute(()->{
            File file = videoRecorder.stop();
            if(file!=null && file.exists() && file.length()>0){
                publishVideoToGallery(file);
                Log.i(TAG,"Recording finalised on stop: "+file.getAbsolutePath());
            }
        });
        runOnUiThread(()->((Button)findViewById(R.id.btnRecord)).setText("● REC"));
    }

    private void publishVideoToGallery(File file){
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME,file.getName());
        v.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/AnimeFIlter");
        v.put(MediaStore.Video.Media.IS_PENDING,1);

        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);
        if(uri == null){
            file.delete();
            return;
        }
        try(OutputStream os = cr.openOutputStream(uri); InputStream in = new FileInputStream(file)){
            byte[] buf = new byte[8192];
            int n;
            while((n=in.read(buf))>0) os.write(buf,0,n);
        }catch (IOException e) {
            Log.e(TAG, "video publish failed", e);
        }
        v.clear();
        v.put(MediaStore.Video.Media.IS_PENDING,0);
        cr.update(uri,v,null,null);
        file.delete();
        runOnUiThread(() -> Toast.makeText(this,
                "Saved to Movies/AnimeFilter", Toast.LENGTH_SHORT).show());
    }
    @Override
    protected void onDestroy(){
        super.onDestroy();
        finishRecordingIfActive();
        cameraExecutor.shutdown();
        ganExecutor.shutdown();
        captureExecutor.shutdown();
        videoExecutor.shutdown();

        animeGanProcessor.close();
        handlandmark.cleanup();
    }


}