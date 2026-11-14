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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.myapplication.Transfer.AirTransferService;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private TextView statusText;
    private Button enableButton;
    private boolean setupInProgress;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
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

                        ContextCompat.startForegroundService(this, serviceIntent);
                        setupInProgress = false;
                        statusText.setText("Gesture transfer is active. You can leave this screen.");
                        enableButton.setEnabled(false);
                        Log.i(TAG, "AirTransferService started");
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.transferStatus);
        enableButton = findViewById(R.id.btnEnableAirTransfer);
        enableButton.setOnClickListener(view -> beginAirTransferSetup());
    }

    private void beginAirTransferSetup() {
        if (setupInProgress) {
            return;
        }

        setupInProgress = true;
        enableButton.setEnabled(false);
        statusText.setText("Checking permissions…");
        continueSetup();
    }

    /** Continue through permissions in order, then request screen capture consent. */
    private void continueSetup() {
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
        setupInProgress = false;
        statusText.setText(message);
        enableButton.setEnabled(true);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
