package com.example.myapplication.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.example.myapplication.vision.HandGestureController;

/** Non-interactive floating diagnostics for MediaPipe gesture classification. */
public final class GestureDebugOverlay {
    private static final String TAG = "GestureDebugOverlay";

    private final Context context;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextView textView;
    private boolean attached;
    private HandGestureController.Observation latestObservation;
    private String serviceState = "STARTING";
    private String operationStatus = "Starting gesture detector";
    private String peerStatus = "No peer connected";
    private String discoveryStatus = "Idle";
    private String connectionStatus = "Not connected";
    private String transferStatus = "Idle";

    public GestureDebugOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager)
                this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        mainHandler.post(this::showOnMainThread);
    }

    public void updateObservation(
            HandGestureController.Observation observation,
            String currentServiceState
    ) {
        mainHandler.post(() -> {
            latestObservation = observation;
            serviceState = currentServiceState;
            render();
        });
    }

    public void setOperationStatus(String status) {
        mainHandler.post(() -> {
            operationStatus = status;
            render();
        });
    }

    public void updateServiceState(String state) {
        mainHandler.post(() -> {
            serviceState = state;
            render();
        });
    }

    public void setPeerStatus(String status) {
        mainHandler.post(() -> {
            peerStatus = status;
            render();
        });
    }

    public void setDiscoveryStatus(String status) {
        mainHandler.post(() -> {
            discoveryStatus = status;
            render();
        });
    }

    public void setConnectionStatus(String status) {
        mainHandler.post(() -> {
            connectionStatus = status;
            render();
        });
    }

    public void setTransferStatus(String status) {
        mainHandler.post(() -> {
            transferStatus = status;
            render();
        });
    }

    public void remove() {
        mainHandler.post(this::removeOnMainThread);
    }

    private void showOnMainThread() {
        if (attached) {
            render();
            return;
        }
        if (windowManager == null || !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show gesture diagnostics: overlay permission is unavailable");
            return;
        }

        textView = new TextView(context);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setPadding(dp(12), dp(9), dp(12), dp(9));
        textView.setMaxLines(18);
        textView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(232, 13, 20, 30));
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), Color.argb(220, 89, 204, 255));
        textView.setBackground(background);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(278),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.END;
        params.x = dp(8);
        params.y = dp(72);

        try {
            windowManager.addView(textView, params);
            attached = true;
            render();
            Log.i(TAG, "Gesture diagnostics overlay attached");
        } catch (RuntimeException error) {
            textView = null;
            Log.e(TAG, "Could not attach gesture diagnostics overlay", error);
        }
    }

    private void render() {
        if (textView == null) {
            return;
        }

        if (latestObservation == null) {
            textView.setText("AIR GESTURE DEBUG\nWaiting for first camera result...\n"
                    + "Role: " + serviceState + "\n"
                    + "Status: " + operationStatus + "\n"
                    + "Discovery: " + discoveryStatus + "\n"
                    + "Connection: " + connectionStatus + "\n"
                    + "Peer: " + peerStatus + "\n"
                    + "Transfer: " + transferStatus);
            return;
        }
        textView.setText(latestObservation.toOverlayText(serviceState, operationStatus)
                + "\nDiscovery: " + discoveryStatus
                + "\nConnection: " + connectionStatus
                + "\nPeer: " + peerStatus
                + "\nTransfer: " + transferStatus);
    }

    private void removeOnMainThread() {
        if (attached && textView != null && windowManager != null) {
            try {
                windowManager.removeView(textView);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not cleanly remove gesture diagnostics overlay", error);
            }
        }
        textView = null;
        attached = false;
    }

    private int dp(float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
