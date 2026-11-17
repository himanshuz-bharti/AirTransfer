package com.example.myapplication;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.ActivityManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.myapplication.Transfer.AirTransferService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String STATE_SETUP_IN_PROGRESS = "STATE_SETUP_IN_PROGRESS";
    private static final Logger log = LoggerFactory.getLogger(MainActivity.class);

    private TextView statusText;
    private Button startButton;
    private Button stopButton;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean permissionInProgress;
    private boolean stopRequestPending;
    private int stopCheckCount;

    private static final int MAX_STOP_CHECKS = 20;
    private static final long STOP_CHECK_INTERVAL_MS = 150L;

    private final Runnable stopStateCheck = new Runnable() {
        @Override
        public void run() {
            if(!isAirTransferServiceRunning()){
                stopRequestPending = false;
                statusText.setText("Gesture Transfer Stopped");
                setserviceControlsRunning(false);
                return;
            }
            if(++stopCheckCount<MAX_STOP_CHECKS){
                uiHandler.postDelayed(this,STOP_CHECK_INTERVAL_MS);
            }

            stopRequestPending = false;
            statusText.setText("Stopping service is taking longer than expected. Tap stop again");
            setserviceControlsRunning(true);
        }
    };
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if(!permissionInProgress){
                            return;
                        }
                        if (granted) {
                            continueSetup();
                        } else {
                            finishSetupWithMessage("Camera permission is required for hand gestures.");
                        }
                    }
            );

    private final ActivityResultLauncher<Intent> overlaySettingsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if(!permissionInProgress){
                            return;
                        }
                        if (Settings.canDrawOverlays(this)) {
                            continueSetup();
                        } else {
                            finishSetupWithMessage("Overlay permission is required for the transfer animation.");
                        }
                    }
            );

    private final ActivityResultLauncher<String[]> galleryPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        if(!permissionInProgress){
                            return;
                        }
                        if (hasGalleryReadPermission()) {
                            continueSetup();
                        } else {
                            finishSetupWithMessage("Image access is required to match the screenshot to Gallery.");
                        }
                    }
            );

    private final ActivityResultLauncher<Intent> projectionRequest =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if(!permissionInProgress){
                            return;
                        }
                        if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                            finishSetupWithMessage("Screen capture permission was not granted.");
                            return;
                        }

                        Intent serviceIntent = new Intent(this, AirTransferService.class);
                        serviceIntent.putExtra(
                                AirTransferService.EXTRA_RESULT_CODE,
                                result.getResultCode()
                        );
                        serviceIntent.putExtra(
                                AirTransferService.EXTRA_RESULT_DATA,
                                result.getData()
                        );
                        try{
                            ContextCompat.startForegroundService(this, serviceIntent);
                            permissionInProgress = false;
                            statusText.setText("Gesture transfer is active. You can leave this screen.");
                            setserviceControlsRunning(true);
                            uiHandler.postDelayed(this::refreshServiceControls,1000L);
                            Log.i(TAG, "AirTransferService start requested");
                        } catch(RuntimeException e){
                            Log.e(TAG,"Error in starting AirTransferService");
                            finishSetupWithMessage("Error in starting AirTransferService");
                        }
                    }
            );

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
        registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted ->{
                    if(!permissionInProgress){
                        return;
                    }
                    if(granted){
                        continueSetup();
                    }
                    else{
                        finishSetupWithMessage("Allow notification s you can stop gesture transfer from the notification");
                    }
                }
        );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if(savedInstanceState!=null){
            permissionInProgress = savedInstanceState.getBoolean(STATE_SETUP_IN_PROGRESS,false);
        }

        statusText = findViewById(R.id.transferStatus);
        startButton = findViewById(R.id.btnStartAirTransfer);
        stopButton = findViewById(R.id.btnStopAirTransfer);

        startButton.setOnClickListener(view -> beginAirTransferSetup());
        stopButton.setOnClickListener(view -> stopAirTransfer());

        refreshServiceControls();
    }

    @Override
    protected void onResume(){
        super.onResume();

    }

    private void refreshServiceControls(){
        boolean running = isAirTransferServiceRunning();
        if(running){
            permissionInProgress=false;
        }
        setserviceControlsRunning(running);
        if(stopRequestPending){
            uiHandler.removeCallbacks(stopStateCheck);
            uiHandler.post(stopStateCheck);
        } else if(running){

        }

    }

    private void beginAirTransferSetup() {
        if (permissionInProgress) {
            return;
        }

        if(isAirTransferServiceRunning()){
            refreshServiceControls();
            return;
        }
        permissionInProgress = true;
        statusText.setText("Checking permissions…");
        setserviceControlsRunning(false);
        continueSetup();
    }

    private void stopAirTransfer(){
        if(permissionInProgress && !isAirTransferServiceRunning()){
            permissionInProgress = false;
            statusText.setText("Permission cancelled");
            setserviceControlsRunning(false);
            return;
        }

        if(stopRequestPending){
            return;
        }
        if(!isAirTransferServiceRunning()){
            refreshServiceControls();
            return;
        }

        Intent stopIntent = new Intent(this, AirTransferService.class);
        stopIntent.setAction(AirTransferService.ACTION_STOP);
        try{
            startService(stopIntent);
            stopRequestPending = true;
            statusText.setText("Stopping AirtransferService");
            setserviceControlsRunning(true);
            uiHandler.postDelayed(stopStateCheck,STOP_CHECK_INTERVAL_MS);
        }catch(RuntimeException error){
            Log.e(TAG,"Could not send stop request to AirtransferService"+error);
            stopRequestPending=false;
            statusText.setText("Could not stop airtransferservice");
            refreshServiceControls();
        }
    }

    private void setserviceControlsRunning(boolean running){
        boolean controlsEnabled = !stopRequestPending;

        startButton.setEnabled(controlsEnabled && !running && !permissionInProgress);
        stopButton.setEnabled(controlsEnabled && (running || permissionInProgress));

        stopButton.setText(permissionInProgress && !running?"Cancel setup":"Stop");
    }
    @SuppressWarnings("deprecation")
    private boolean isAirTransferServiceRunning(){
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if(manager == null){
            return false;
        }

        for(ActivityManager.RunningServiceInfo info:
                manager.getRunningServices(Integer.MAX_VALUE)){
            if(info.service != null && AirTransferService.class.getName().equals(info.service.getClassName())){
                return true;
            }
        }
        return false;
    }
    /** Continue through permissions in order, then request screen capture consent. */
    private void continueSetup() {
        if(!permissionInProgress){
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Allow camera access for hand gesture detection.");
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            statusText.setText("Allow the transfer animation overlay.");
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            overlaySettingsLauncher.launch(intent);
            return;
        }

        if (!hasGalleryReadPermission()) {
            statusText.setText("Allow image access for Gallery matching.");
            requestGalleryReadPermission();
            return;
        }

        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)
                    !=PackageManager.PERMISSION_GRANTED){
            statusText.setText("Allow notification to control gesture transfer");
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }

        requestProjectionConsent();
    }

    private boolean hasGalleryReadPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            return ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED;
        }

        return ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestGalleryReadPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            galleryPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            });
        } else if (Build.VERSION.SDK_INT >= 33) {
            galleryPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES
            });
        } else {
            galleryPermissionLauncher.launch(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE
            });
        }
    }

    private void requestProjectionConsent() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (manager == null) {
            finishSetupWithMessage("Screen capture is unavailable on this device.");
            return;
        }

        statusText.setText("Confirm screen capture to enable gesture transfer.");
        projectionRequest.launch(manager.createScreenCaptureIntent());
    }

    private void finishSetupWithMessage(String message) {
        permissionInProgress = false;
        statusText.setText(message);
        setserviceControlsRunning(isAirTransferServiceRunning());
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
