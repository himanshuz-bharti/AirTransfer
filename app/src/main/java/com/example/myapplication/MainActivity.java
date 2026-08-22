package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.myapplication.camera.FrameData;
import com.example.myapplication.camera.FrameProcessor;

import com.example.myapplication.camera.PerformaneMonitor;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CameraX";
    private static final int REQUEST_PERMISSION_CODE = 10;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private FrameProcessor frameProcessor;
    private PreviewView viewFinder;
    private ExecutorService cameraExecutor;

    private PerformaneMonitor performanceMonitor;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        frameProcessor = new FrameProcessor();

        performanceMonitor = new PerformaneMonitor();

        viewFinder = findViewById(R.id.viewFinder);

        cameraExecutor = Executors.newSingleThreadExecutor();
        if(allRequiredPermissions()){
            startCamera();
        }
        else{
            ActivityCompat.requestPermissions(this,
                    REQUIRED_PERMISSIONS,
                    REQUEST_PERMISSION_CODE);
        }
    }

    private void startCamera(){
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(()->{
            try{
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();

                imageAnalysis.setAnalyzer(cameraExecutor,image ->{

                    long frameStartTime = System.nanoTime();
                    try{
                        FrameData data = frameProcessor.processFrame(image);
                        performanceMonitor.onFrameEnd(frameStartTime);
                        Log.i(TAG,"width:"+ data.getWidth() + " height:"+ data.getheight()+ " rotationdegree:"+ data.getrotationDegree() + " timestamp:"+ data.getTimeStamp()
                        +" FPS:" + performanceMonitor.getfps() + " lastframetimems:"+ performanceMonitor.getLastFrameTimeMs());

                    }
                    finally {
                        image.close();
                    }
                });

                    CameraSelector camSelector = CameraSelector.DEFAULT_BACK_CAMERA;

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

    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        cameraExecutor.shutdown();
    }


}