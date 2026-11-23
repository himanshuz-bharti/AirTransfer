package com.example.myapplication.Transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleService;

import com.example.myapplication.RecieveImageActivity;
import com.example.myapplication.ui.GestureDebugOverlay;
import com.example.myapplication.ui.TransferEffectOverlay;
import com.example.myapplication.ui.ReceiverStatusOverlay;
import com.example.myapplication.vision.HandGestureController;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;

import com.example.myapplication.utils.BitmapDumpUtil;

public class AirTransferService extends LifecycleService {

    private enum RoleState{
        IDLE,
        SENDER_ADVERTISING,
        SENDER_WAITING,
        SENDER_CONNECTING,
        SENDER_SENDING,
        RECIEVER_STARTING,
        RECIEVER_LISTENING
    }

    private static final int RECIEVER_PORT = 8080;
    // Allow slow receivers to commit large images and return the final DONE reply.
    private static final long SENDER_TIMEOUT_MS = 360000L;
    private static final long SENDER_MATCH_TIMEOUT_MS = 300000L;
    private static final long SENDER_NO_RECEIVER_TIMEOUT_MS = 90000L;
    private static final long WEBSOCKET_CONNECT_ATTEMPT_TIMEOUT_MS = 8000L;
    private static final long WEBSOCKET_CONNECT_RETRY_DELAY_MS = 1000L;
    private static final int MAX_WEBSOCKET_CONNECT_ATTEMPTS = 30;
    private static final long RECIEVER_TIMEOUT_MS = 60000L;
    private static final long RECIEVER_TRANSFER_IDLE_TIMEOUT_MS = 180000L;
    private static final long RECIEVER_WAITING_FOR_SENDER_TIMEOUT_MS = 360000L;
    private static final long GESTURE_FRAME_INTERVAL_MS = 200L;
    // Sender-side self-healing: restarts the receiver browse while no receiver is found.
    private static final long SENDER_DISCOVERY_REFRESH_MS = 12000L;
    // Bounded wait for the platform to acknowledge NSD unregister/stop requests before
    // the next session may re-register the same service types.
    private static final long NSD_TEARDOWN_WAIT_MS = 2500L;
    private Runnable receiverIdleTimeout;
    private Runnable receiverCompletionRunnable;

    private RoleState roleState = RoleState.IDLE;

    private TransferEffectOverlay transferEffectOverlay;
    private ReceiverStatusOverlay receiverStatusOverlay;
    private GestureDebugOverlay gestureDebugOverlay;
    private static final String OFFER_SERVICE_TYPE = NSDTransferDiscovery.OFFER_SERVICE_TYPE;
    private static final String RECIEVER_SERVICE_TYPE = NSDTransferDiscovery.RECIEVER_SERVICE_TYPE;

    private NSDTransferDiscovery offerNsd;
    private NSDTransferDiscovery recieverNsd;
    private final Map<String,NSDTransferDiscovery.Peer> visibleSenderOffers = new HashMap<>();
    private TransferWebSocketServer recieverServer;
    private NSDTransferDiscovery.Peer pendingReciever;
    private String connectedSenderHost;
    private int connectedSenderRemotePort = -1;
    private String connectedSenderIdentity;
    private Uri pendingItemUri;

    private static final String TAG = "AirTransferService";
    private static final String CHANNEL_ID = "air_transfer";
    private static final int NOTIFICATION_ID = 4101;

    public static final String EXTRA_RESULT_CODE = "com.example.myapplication.EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "com.example.myapplication.EXTRA_RESULT_DATA";

    public static final String ACTION_STOP = "com.example.myapplication.ACTION_STOP_AIR_TRANSFER";
    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    private final AtomicBoolean receiverTransferCompleted = new AtomicBoolean(false);
    private final AtomicBoolean receiverTransferCommitting = new AtomicBoolean(false);
    private final AtomicBoolean receiverTransferStarted = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler();

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private Handler captureHandler;
    private HandlerThread captureThread;
    private boolean resourceReleased;

    private final ExecutorService galleryMatchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();
    // Shared across sessions: NSD teardown must complete (off the main thread) even
    // after the service instance that requested it has stopped.
    private static final ExecutorService NSD_TEARDOWN_EXECUTOR =
            Executors.newSingleThreadExecutor(command -> {
                Thread thread = new Thread(command, "AirTransferNsdTeardown");
                thread.setDaemon(true);
                return thread;
            });

    private volatile TransferWebSocketClient webSocketClient;
    private TransferFileSender fileSender;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    private HandGestureController handGestureController;
    private final AtomicBoolean gestureFramesEnabled = new AtomicBoolean(false);
    private boolean gestureCameraPaused;
    private long lastGestureFrameAtMs;
    private long gestureCameraFrameNumber;

    private boolean stopping;
    private boolean startAccepted;
    private volatile long operationGeneration;
    private volatile long captureRequestGeneration;
    private long senderDeadlineAtMs;
    private int websocketConnectAttempts;
    private Runnable websocketConnectRetry;
    private Runnable websocketConnectTimeout;
    private Runnable senderDiscoveryRefresh;
    private boolean networkResourcesReleased;

    private final AtomicBoolean overlayOnScreenDuringCapture = new AtomicBoolean(false);

    private final Runnable senderTimeout = () -> {
        if (!isSenderState()) {
            return;
        }
        long remainingMs = senderDeadlineAtMs - SystemClock.elapsedRealtime();
        if (remainingMs > 0L) {
            mainHandler.postDelayed(AirTransferService.this.senderTimeout, remainingMs);
            return;
        }
        finishSenderAttempt("Sender operation timed out");
    };

    private final Runnable receiverStartupTimeout = () -> {
        if (roleState == RoleState.RECIEVER_STARTING) {
            finishReceiverOperation("Receiver did not become discoverable");
        }
    };

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    Log.i(TAG,"MediaProjection stopped by system/user");
                    mainHandler.post(()->{
                        stopEverything("MediaProjection stopped by system/user",false);
                    });
                }
            };

    @Override
    public void onCreate(){
        super.onCreate();
        transferEffectOverlay = new TransferEffectOverlay(this);
        receiverStatusOverlay = new ReceiverStatusOverlay(this);
        gestureDebugOverlay = new GestureDebugOverlay(this);
        gestureDebugOverlay.setServiceRunning(true);
        gestureDebugOverlay.setControlListener(() ->
                stopEverything("Stopped by overlay control", true));
        createNotificationChannel();
        initializeNSD();
    }

    private void initializeHandGestureController() {
        if (handGestureController != null) {
            return;
        }
        handGestureController = new HandGestureController(
                getApplicationContext(),
                new HandGestureController.Listener() {
                    @Override
                    public void onObservation(HandGestureController.Observation observation) {
                        mainHandler.post(() -> {
                            if (gestureDebugOverlay != null) {
                                gestureDebugOverlay.updateObservation(
                                        observation, roleState.name());
                            }
                        });
                    }

                    @Override
                    public void onAction(HandGestureController.Action action) {
                        Log.i(TAG, "GESTURE_ACTION_ACCEPTED action=" + action
                                + "; roleBeforeAction=" + roleState);
                        mainHandler.post(() -> {
                            if (gestureDebugOverlay != null) {
                                gestureDebugOverlay.setOperationStatus(
                                        "Accepted gesture: " + action);
                            }
                            handleGestureAction(action);
                        });
                    }

                    @Override
                    public void onError(RuntimeException error) {
                        Log.e(TAG, "Gesture model error", error);
                        if (gestureDebugOverlay != null) {
                            gestureDebugOverlay.setOperationStatus(
                                    "Gesture model error: " + error.getMessage());
                        }
                    }
                }
        );
    }

    private void startGestureCamera() {
        if (cameraExecutor != null) {
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                bindGestureCamera();
            } catch (ExecutionException | InterruptedException error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Log.e(TAG, "Could not initialize CameraX", error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindGestureCamera() {
        if (cameraProvider == null || imageAnalysis == null || cameraExecutor == null) {
            Log.w(TAG, "Gesture camera is not ready to bind");
            return;
        }

        imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeGestureFrame);
        try {
            cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
            );
            gestureFramesEnabled.set(true);
            gestureCameraPaused = false;
            Log.i(TAG, "Gesture camera started; sampling at 5 FPS");
            if (gestureDebugOverlay != null) {
                gestureDebugOverlay.setOperationStatus("Gesture detector running");
            }
        } catch (Exception error) {
            gestureFramesEnabled.set(false);
            imageAnalysis.clearAnalyzer();
            gestureCameraPaused = true;
            Log.e(TAG, "Could not bind front camera for gesture recognition", error);
        }
    }

    private void analyzeGestureFrame(ImageProxy image) {
        Bitmap bitmap = null;
        MPImage mpImage = null;
        boolean submittedToRecognizer = false;
        try {
            if (!gestureFramesEnabled.get()) {
                return;
            }

            long nowMs = SystemClock.elapsedRealtime();
            if (nowMs - lastGestureFrameAtMs < GESTURE_FRAME_INTERVAL_MS) {
                return;
            }
            lastGestureFrameAtMs = nowMs;

            int rotationDegrees = image.getImageInfo().getRotationDegrees();
            bitmap = image.toBitmap();
            if (bitmap == null) {
                throw new IllegalStateException("CameraX returned a null bitmap");
            }

            long frameNumber = ++gestureCameraFrameNumber;
            if (frameNumber == 1L || frameNumber % 25L == 0L) {
                Log.i(TAG, "Gesture camera input frame=" + frameNumber
                        + "; size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + "; format=" + image.getFormat()
                        + "; rotationDegrees=" + rotationDegrees
                        + "; sampledLuma=" + getSampledLumaRange(bitmap));
            }

            if (handGestureController != null) {
                mpImage = new BitmapImageBuilder(bitmap).build();
                ImageProcessingOptions processingOptions = ImageProcessingOptions.builder()
                        .setRotationDegrees(rotationDegrees)
                        .build();
                handGestureController.recognizeAsync(
                        mpImage,
                        processingOptions,
                        SystemClock.uptimeMillis());
                submittedToRecognizer = true;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not submit camera frame to gesture model", error);
        } finally {
            image.close();
            if (!submittedToRecognizer) {
                if (mpImage != null) {
                    mpImage.close();
                }
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }
        }
    }

    /** Sparse luminance sanity check: confirms camera frames contain image data. */
    private String getSampledLumaRange(Bitmap bitmap) {
        int min = 255;
        int max = 0;
        long sum = 0L;
        int samples = 0;
        int stepX = Math.max(1, bitmap.getWidth() / 16);
        int stepY = Math.max(1, bitmap.getHeight() / 16);

        for (int y = 0; y < bitmap.getHeight(); y += stepY) {
            for (int x = 0; x < bitmap.getWidth(); x += stepX) {
                int color = bitmap.getPixel(x, y);
                int luma = (int) (0.299 * android.graphics.Color.red(color)
                        + 0.587 * android.graphics.Color.green(color)
                        + 0.114 * android.graphics.Color.blue(color));
                min = Math.min(min, luma);
                max = Math.max(max, luma);
                sum += luma;
                samples++;
            }
        }

        int average = samples == 0 ? 0 : (int) (sum / samples);
        return "avg=" + average + ",min=" + min + ",max=" + max;
    }

    private void pauseGestureCamera() {
        gestureFramesEnabled.set(false);
        gestureCameraPaused = true;
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null && imageAnalysis != null) {
            cameraProvider.unbind(imageAnalysis);
        }
        Log.i(TAG, "Gesture camera paused for transfer operation");
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setOperationStatus(
                    "Gesture camera paused while transfer operation runs");
        }
    }

    private void resumeGestureCamera() {
        if (!gestureCameraPaused) {
            return;
        }
        if (handGestureController != null) {
            handGestureController.requireDifferentPoseOrHandRemoval();
        }
        bindGestureCamera();
    }

    private void handleGestureAction(HandGestureController.Action action) {
        if(stopping){
            Log.w(TAG,"Ignore gesture as the service is stopping");
            return;
        }
        Log.i(TAG, "Accepted gesture action=" + action + "; role=" + roleState);
        if (roleState != RoleState.IDLE) {
            Log.w(TAG, "Ignoring gesture action while transfer role is active: " + roleState);
            return;
        }
        pauseGestureCamera();
        if (action == HandGestureController.Action.SEND) {
            startSenderMode();
        } else if (action == HandGestureController.Action.RECEIVE) {
            startReceiverMode();
        }
    }

    private void initializeNSD(){
        offerNsd = new NSDTransferDiscovery(
                getApplicationContext(),
                OFFER_SERVICE_TYPE,
                new NSDTransferDiscovery.Listener() {
                    @Override
                    public void onStatus(String message) {
                        Log.i(TAG,"Sender NSD:"+message);
                        if ("Discovery started..".equals(message) && isReceiverState()) {
                            updateReceiverDiscoveryStatus();
                        }
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {
                        if(peer == null || peer.serviceName == null){
                            Log.w(TAG,"Ignore sender offers without a service name");
                            return;
                        }
                        visibleSenderOffers.put(peer.serviceName,peer);
                        Log.i(TAG, "Sender offer detected: " + peer);
                        if (isReceiverState()) {
                            if (connectedSenderHost != null
                                    && peer.hostAddress != null
                                    && connectedSenderHost.equalsIgnoreCase(peer.hostAddress)) {
                                connectedSenderIdentity = formatPeer(peer);
                                showPeerStatus("WebSocket connected: "
                                        + connectedSenderIdentity);
                            } else if (connectedSenderHost == null) {
                                updateReceiverDiscoveryStatus();
                                showPeerStatus("Sender: " + formatPeer(peer));
                            }
                        }
                        if (roleState == RoleState.IDLE
                                && pendingReciever == null) {
                            showPeerStatus("Sender nearby: " + formatPeer(peer));
                        }
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Sender offer lost");
                        if(peer==null){
                            Log.w(TAG,"Peer is null");
                            return;
                        }
                        if (peer.serviceName != null) {
                            visibleSenderOffers.remove(peer.serviceName);
                        }
                        if (isReceiverState() && connectedSenderHost == null) {
                            updateReceiverDiscoveryStatus();
                            if (visibleSenderOffers.isEmpty()) {
                                showPeerStatus(roleState == RoleState.RECIEVER_LISTENING
                                        ? "No sender connected"
                                        : "Receiver starting; no sender nearby");
                            } else {
                                NSDTransferDiscovery.Peer nextPeer =
                                        visibleSenderOffers.values().iterator().next();
                                showPeerStatus("Sender: " + formatPeer(nextPeer));
                            }
                        } else if (roleState == RoleState.IDLE) {
                            if (visibleSenderOffers.isEmpty()) {
                                showPeerStatus("No peer nearby");
                            } else {
                                NSDTransferDiscovery.Peer nextPeer =
                                        visibleSenderOffers.values().iterator().next();
                                showPeerStatus("Sender nearby: " + formatPeer(nextPeer));
                            }
                        }
                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        Log.e(TAG,"NSD error:"+message + ":code"+errorCode);
                        if (isReceiverState() && connectedSenderHost == null) {
                            setDiscoveryStatus("Sender discovery interrupted; retrying");
                        }
                    }
                }
        );

        recieverNsd = new NSDTransferDiscovery(
                getApplicationContext(),
                RECIEVER_SERVICE_TYPE,
                new NSDTransferDiscovery.Listener() {
                    @Override
                    public void onStatus(String message) {
                        Log.i(TAG,"Reciever Nsd"+message);
                        if ("Discovery started..".equals(message)
                                && roleState == RoleState.SENDER_WAITING) {
                            setDiscoveryStatus(pendingReciever == null
                                    ? "Sender advertised; searching for receiver"
                                    : "Receiver found");
                        }
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Reciever discovered");
                        if(peer == null || peer.serviceName == null
                                || peer.hostAddress == null || peer.port <= 0){
                            Log.w(TAG,"Ignore receiver offer with invalid address");
                            return;
                        }
                        if(roleState != RoleState.SENDER_WAITING
                                && roleState != RoleState.SENDER_CONNECTING){
                            Log.i(TAG, "Receiver offer arrived while sender role is " + roleState);
                            return;
                        }

                        if (roleState == RoleState.SENDER_CONNECTING
                                && pendingReciever != null
                                && pendingReciever.serviceName.equals(peer.serviceName)) {
                            pendingReciever = peer;
                            Log.i(TAG, "Updated resolved receiver address during connection retries: "
                                    + peer);
                            return;
                        }

                        if(pendingReciever == null){
                            pendingReciever = peer;
                            cancelSenderDiscoveryRefresh();
                            setDiscoveryStatus("Receiver found");
                            setConnectionStatus(pendingItemUri == null
                                    ? "Waiting for image match"
                                    : "Ready to connect");
                            showPeerStatus("Receiver found: " + formatPeer(peer));
                            if (pendingItemUri == null) {
                                setOverlayOperationStatus(
                                        "Receiver found; Gallery matching continues");
                            }
                        }
                        maybeConnectToReciever();
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Reciever service lost"+peer);
                        if (peer == null) {
                            return;
                        }
                        if (pendingReciever != null
                                && pendingReciever.serviceName.equals(peer.serviceName)
                                && (roleState == RoleState.SENDER_WAITING
                                || roleState == RoleState.SENDER_CONNECTING)) {
                            if (roleState == RoleState.SENDER_CONNECTING) {
                                cancelWebSocketConnectCallbacks();
                                TransferWebSocketClient oldClient = webSocketClient;
                                webSocketClient = null;
                                if (fileSender != null) {
                                    fileSender.shutdown();
                                    fileSender = null;
                                }
                                if (oldClient != null) {
                                    oldClient.close();
                                }
                                roleState = RoleState.SENDER_WAITING;
                                scheduleSenderTimeout(SENDER_NO_RECEIVER_TIMEOUT_MS);
                            }
                            pendingReciever = null;
                            scheduleSenderDiscoveryRefresh();
                            setDiscoveryStatus("Sender advertised; searching for receiver");
                            setConnectionStatus("Waiting for receiver");
                            setOverlayOperationStatus("Receiver went offline; waiting for another advertisement");
                            showPeerStatus("Receiver disconnected; searching again");
                        }

                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        Log.i(TAG,"Receiver NSD error"+message + ",code:"+errorCode);
                        if(isSenderState()){
                            setDiscoveryStatus(pendingReciever == null
                                    ? "Receiver discovery interrupted; retrying"
                                    : "Receiver found; discovery retrying");
                        }
                    }
                }
        );

        offerNsd.startDiscovery();
    }

    private boolean isSenderState(){
        return roleState == RoleState.SENDER_ADVERTISING || roleState == RoleState.SENDER_WAITING || roleState == RoleState.SENDER_CONNECTING || roleState == RoleState.SENDER_SENDING;
    }

    private boolean isReceiverState(){
        return roleState == RoleState.RECIEVER_STARTING
                || roleState == RoleState.RECIEVER_LISTENING;
    }

    private void startSenderMode(){
        if(roleState != RoleState.IDLE){
            Log.w(TAG,"roleState is not idle");
            resumeGestureCamera();
            return;
        }
        if(mediaProjection == null || imageReader == null){
            Log.w(TAG,"mediaprojection or imageReader is null");
            showStatus("Screen capture is not active");
            resumeGestureCamera();
            return;
        }

        operationGeneration++;
        websocketConnectAttempts = 0;
        cancelWebSocketConnectCallbacks();
        final long senderGeneration = operationGeneration;
        if (receiverStatusOverlay != null) {
            receiverStatusOverlay.remove();
        }
        roleState = RoleState.SENDER_ADVERTISING;
        pendingItemUri = null;
        pendingReciever = null;
        setDiscoveryStatus("Advertising sender offer");
        setConnectionStatus("Waiting for receiver");
        setTransferStatus("Preparing image capture");
        showPeerStatus("No receiver found yet");

        scheduleSenderTimeout(SENDER_MATCH_TIMEOUT_MS);

        offerNsd.startAdvertising(Build.MODEL, RECIEVER_PORT,
                new NSDTransferDiscovery.AdvertisingCallback() {
                    @Override
                    public void onStarted(String actualServiceName) {
                        mainHandler.post(()->{
                            if (operationGeneration != senderGeneration
                                    || stopping || roleState != RoleState.SENDER_ADVERTISING){
                                offerNsd.stopAdvertising();
                                return;
                            }
                            roleState = RoleState.SENDER_WAITING;
                            recieverNsd.startDiscovery();
                            scheduleSenderDiscoveryRefresh();
                            setDiscoveryStatus("Sender advertised; searching for receiver");
                            setConnectionStatus("Waiting for receiver");
                            setTransferStatus("Capturing screen and matching Gallery");
                            if(!requestScreenCapture()){
                                finishSenderAttempt("Could not get screen capture");
                                return;
                            }
                            showStatus("Searching for receiver while matching Gallery image");
                        });
                    }

                    @Override
                    public void onFailed(String reason) {
                        mainHandler.post(()->{
                            if (operationGeneration == senderGeneration
                                    && roleState == RoleState.SENDER_ADVERTISING){
                                finishSenderAttempt("Could not advertise sender offer: " + reason);
                            }
                        });
                    }
                });

        showStatus("Finding reciever and gallery matching started");
    }

    private void startReceiverMode(){
        if(roleState != RoleState.IDLE){
            Log.w(TAG, "Ignoring RELEASE because transfer role is already active: "
                    + roleState);
            return;
        }
        startReceiverOperation();
    }

    private void startReceiverOperation() {
        if (roleState != RoleState.IDLE) {
            return;
        }

        mainHandler.removeCallbacks(receiverStartupTimeout);
        receiverTransferCompleted.set(false);
        receiverTransferCommitting.set(false);
        receiverTransferStarted.set(false);
        operationGeneration++;

        if (transferEffectOverlay != null) {
            transferEffectOverlay.remove();
        }
        roleState = RoleState.RECIEVER_STARTING;
        connectedSenderHost = null;
        connectedSenderRemotePort = -1;
        connectedSenderIdentity = null;
        setDiscoveryStatus("Searching for sender");
        setConnectionStatus("Waiting for WebSocket connection");
        setTransferStatus("Idle");
        showPeerStatus("Starting receiver; waiting for sender");
        receiverStatusOverlay.show(ReceiverStatusOverlay.State.STARTING);
        showStatus("Starting receiver");

        mainHandler.postDelayed(receiverStartupTimeout, RECIEVER_TIMEOUT_MS);
        scheduleReceiverTransferIdleTimeout(null, "Receiver timed out waiting for sender");

        File temporaryDirectory = new File(getCacheDir(),"recieved-parts");
        if (!isReceiverPortAvailable(RECIEVER_PORT)) {
            Log.e(TAG, "Receiver port " + RECIEVER_PORT + " is unavailable;"
                    + " a previous session's server socket may still be open");
            finishReceiverOperation("Receiver port " + RECIEVER_PORT
                    + " is unavailable; start the receiver again");
            return;
        }
        TransferWebSocketServer server = new TransferWebSocketServer(getApplicationContext(),
                RECIEVER_PORT,
                temporaryDirectory.toPath());
        server.setReuseAddr(true);
        recieverServer = server;

        server.setTransferCompletedListener((uri,mimeType,displayName)->{
            if (recieverServer != server) {
                return;
            }
            receiverTransferCommitting.set(false);
            receiverTransferCompleted.set(true);
            Log.i(TAG, "Receiver persisted image; completion is recorded before DONE acknowledgment");
            if (receiverIdleTimeout != null) {
                mainHandler.removeCallbacks(receiverIdleTimeout);
                receiverIdleTimeout = null;
            }
            mainHandler.removeCallbacks(receiverStartupTimeout);
            setTransferStatus("Image received successfully");
            setOverlayOperationStatus("Image saved; opening received image");
            receiverCompletionRunnable = () -> {
                receiverCompletionRunnable = null;
                if (!stopping && recieverServer == server
                        && roleState == RoleState.RECIEVER_LISTENING) {
                    try {
                        openRecievedImage(uri,mimeType,displayName);
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Could not open the received-image viewer", error);
                    } finally {
                        finishReceiverOperation("Image received successfully");
                    }
                }
            };
            mainHandler.postDelayed(receiverCompletionRunnable, 1000L);
        });
        server.setClientConnectionListener(new TransferWebSocketServer.ClientConnectionListener() {
            @Override
            public void onConnected(String remoteHost, int remotePort) {
                mainHandler.post(() -> {
                    if (recieverServer != server || !isReceiverState()) {
                        return;
                    }
                    if (roleState == RoleState.RECIEVER_STARTING) {
                        mainHandler.removeCallbacks(receiverStartupTimeout);
                        roleState = RoleState.RECIEVER_LISTENING;
                    }
                    NSDTransferDiscovery.Peer senderPeer = findSenderPeer(remoteHost);
                    String senderIdentity = senderPeer == null
                            ? "Sender at " + remoteHost
                            : formatPeer(senderPeer);
                    connectedSenderHost = remoteHost;
                    connectedSenderRemotePort = remotePort;
                    receiverTransferStarted.set(false);
                    connectedSenderIdentity = senderIdentity;
                    setDiscoveryStatus("Sender connected");
                    setConnectionStatus("WebSocket connected");
                    setTransferStatus("Receiving image");
                    receiverStatusOverlay.show(ReceiverStatusOverlay.State.RECEIVING);
                    showPeerStatus("WebSocket connected: " + senderIdentity);
                    showStatus("Connected to " + senderIdentity);
                    scheduleReceiverTransferIdleTimeout(server,
                            "Receiver transfer timed out");
                });
            }

            @Override
            public void onDisconnected(String remoteHost, int remotePort) {
                mainHandler.post(() -> {
                    boolean completed = receiverTransferCompleted.get();
                    boolean transferStarted = receiverTransferStarted.get();
                    Log.i(TAG, "Receiver socket closed by " + remoteHost + ":" + remotePort
                            + "; transferStarted=" + transferStarted
                            + "; transferCompleted=" + completed
                            + "; serverHasActiveConnection=" + server.hasActiveConnection()
                            + "; role=" + roleState);
                    if (recieverServer != server
                            || (roleState != RoleState.RECIEVER_STARTING
                            && roleState != RoleState.RECIEVER_LISTENING)
                            || completed || receiverTransferCommitting.get()) {
                        return;
                    }
                    if (server.hasActiveConnection()) {
                        Log.i(TAG, "Ignoring close from prior socket; receiver has a new active socket");
                        return;
                    }
                    if (connectedSenderRemotePort != remotePort
                            || !sameHost(connectedSenderHost, remoteHost)) {
                        Log.i(TAG, "Ignoring disconnect from a stale/non-active sender socket");
                        return;
                    }

                    // A socket may be opened by a stale NSD result or a previous
                    // connection attempt and close without ever beginning a transfer.
                    // Even an interrupted transfer should not tear down the receiver:
                    // abortTransfer() has already removed its partial file, and keeping
                    // the server/advertisement alive lets the sender retry in a new session.
                    connectedSenderHost = null;
                    connectedSenderRemotePort = -1;
                    connectedSenderIdentity = null;
                    receiverTransferStarted.set(false);
                    receiverStatusOverlay.show(ReceiverStatusOverlay.State.READY);
                    setConnectionStatus("Waiting for sender");
                    setTransferStatus(transferStarted
                            ? "Previous transfer interrupted; waiting for sender"
                            : "Idle");
                    setDiscoveryStatus("Receiver advertised; waiting for sender");
                    showPeerStatus("Sender disconnected; receiver is still available");
                    setOverlayOperationStatus("Receiver is still listening for a sender");
                    scheduleReceiverTransferIdleTimeout(null,
                            "Receiver timed out waiting for sender");
                });
            }

            @Override
            public void onTransferActivity(long bytesReceived, long totalBytes) {
                mainHandler.post(() -> {
                    if (recieverServer == server && roleState == RoleState.RECIEVER_LISTENING
                            && !receiverTransferCompleted.get()
                            && !receiverTransferCommitting.get()) {
                        receiverTransferStarted.set(true);
                        if (totalBytes > 0) {
                            int percent = (int) Math.min(100L,
                                    (bytesReceived * 100L) / totalBytes);
                            setTransferStatus("Receiving image " + percent + "%");
                        } else {
                            setTransferStatus("Receiving image");
                        }
                        scheduleReceiverTransferIdleTimeout(server,
                                "Receiver transfer timed out");
                    }
                });
            }

            @Override
            public void onTransferCommitting() {
                receiverTransferCommitting.set(true);
                mainHandler.post(() -> {
                    if (recieverServer == server && isReceiverState()) {
                        if (receiverIdleTimeout != null) {
                            mainHandler.removeCallbacks(receiverIdleTimeout);
                            receiverIdleTimeout = null;
                        }
                        setTransferStatus("Saving received image");
                    }
                });
            }

            @Override
            public void onTransferCommitFailed(String reason) {
                receiverTransferCommitting.set(false);
                mainHandler.post(() -> {
                    if (recieverServer == server && isReceiverState()
                            && !receiverTransferCompleted.get()) {
                        finishReceiverOperation("Could not save received image: " + reason);
                    }
                });
            }
        });
        server.setServerStartedListener(()->{
            mainHandler.post(()->{
                if(recieverServer != server || !isReceiverState()){
                    return;
                }
                Log.i(TAG,"WebSocket receiver started; advertising receiver offer");
                if (connectedSenderHost == null) {
                    setDiscoveryStatus("Advertising receiver");
                    setConnectionStatus("Waiting for sender");
                }
                Log.i(TAG, "Receiver session " + operationGeneration
                        + " listening on port " + RECIEVER_PORT);
                recieverNsd.startAdvertising(Build.MODEL, RECIEVER_PORT,
                        new NSDTransferDiscovery.AdvertisingCallback() {
                            @Override
                            public void onStarted(String actualServiceName) {
                                mainHandler.post(() -> {
                                    if (recieverServer != server || !isReceiverState()) {
                                        recieverNsd.stopAdvertising();
                                        return;
                                    }
                                    if (roleState == RoleState.RECIEVER_STARTING) {
                                        mainHandler.removeCallbacks(receiverStartupTimeout);
                                        roleState = RoleState.RECIEVER_LISTENING;
                                    }
                                    if (connectedSenderHost != null) {
                                        setDiscoveryStatus("Sender connected");
                                        Log.i(TAG, "Receiver offer registered as "
                                                + actualServiceName);
                                        return;
                                    }
                                    receiverStatusOverlay.show(ReceiverStatusOverlay.State.READY);
                                    boolean senderNearby = !visibleSenderOffers.isEmpty();
                                    setDiscoveryStatus(senderNearby
                                            ? "Receiver advertised; sender nearby"
                                            : "Receiver advertised; waiting for sender");
                                    showPeerStatus(senderNearby
                                            ? "Sender: " + formatPeer(
                                                    visibleSenderOffers.values().iterator().next())
                                            : "No sender connected");
                                    showStatus(senderNearby
                                            ? "Receiver available; sender nearby"
                                            : "Receiver available; waiting for sender");
                                    Log.i(TAG, "Receiver offer registered as " + actualServiceName);
                                });
                            }

                            @Override
                            public void onFailed(String reason) {
                                mainHandler.post(() -> {
                                    if (recieverServer == server && isReceiverState()
                                            && connectedSenderHost == null) {
                                        finishReceiverOperation(
                                                "Could not advertise receiver offer: " + reason);
                                    }
                                });
                            }
                        });
            });
        });

        try{
            server.start();
        } catch(Exception e){
            Log.e(TAG,"Could not start websocket server"+e);
            finishReceiverOperation("Could not start receiver");
        }
    }

    /** Fails fast when the transfer port is still held, instead of advertising a dead server. */
    private boolean isReceiverPortAvailable(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException error) {
            Log.e(TAG, "Receiver port probe failed for " + port, error);
            return false;
        }
    }

    private void openRecievedImage(Uri uri,String mimeType,String displayName){
        Log.i(TAG, "Opening received-image activity for " + uri
                + "; Android=" + Build.VERSION.SDK_INT);
        Intent viewIntent  = new Intent(this, RecieveImageActivity.class);
        viewIntent.putExtra(RecieveImageActivity.EXTRA_NAME,displayName);
        viewIntent.putExtra(RecieveImageActivity.EXTRA_URI,uri.toString());
        viewIntent.putExtra(RecieveImageActivity.EXTRA_MIME,mimeType);
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivity(viewIntent);
    }
    private void scheduleSenderTimeout(long timeoutMs) {
        senderDeadlineAtMs = SystemClock.elapsedRealtime() + timeoutMs;
        mainHandler.removeCallbacks(senderTimeout);
        mainHandler.postDelayed(senderTimeout, timeoutMs);
    }

    /**
     * While the sender is waiting for a receiver and none has been found yet, restart
     * the NSD browse periodically. A browse that started during a previous session's
     * teardown churn can come up silently empty on some Android NSD implementations;
     * restarting it re-attaches to live advertisements.
     */
    private void scheduleSenderDiscoveryRefresh() {
        cancelSenderDiscoveryRefresh();
        senderDiscoveryRefresh = () -> {
            senderDiscoveryRefresh = null;
            if (stopping || roleState != RoleState.SENDER_WAITING || pendingReciever != null) {
                return;
            }
            if (recieverNsd != null) {
                Log.i(TAG, "No receiver discovered yet; restarting receiver discovery");
                setDiscoveryStatus("No receiver found; restarting discovery");
                recieverNsd.restartDiscovery();
            }
            scheduleSenderDiscoveryRefresh();
        };
        mainHandler.postDelayed(senderDiscoveryRefresh, SENDER_DISCOVERY_REFRESH_MS);
    }

    private void cancelSenderDiscoveryRefresh() {
        if (senderDiscoveryRefresh != null) {
            mainHandler.removeCallbacks(senderDiscoveryRefresh);
            senderDiscoveryRefresh = null;
        }
    }

    private void scheduleReceiverTransferIdleTimeout(
            TransferWebSocketServer server,
            String timeoutMessage
    ){
        if(receiverIdleTimeout != null){
            mainHandler.removeCallbacks(receiverIdleTimeout);
        }

        receiverIdleTimeout = () -> {
            if (receiverTransferCommitting.get()) {
                scheduleReceiverTransferIdleTimeout(server, timeoutMessage);
                return;
            }
            boolean waitingForConnection = server == null
                    && recieverServer != null
                    && roleState == RoleState.RECIEVER_LISTENING
                    && connectedSenderHost == null;
            boolean matchingServer = server != null
                    && recieverServer == server
                    && roleState == RoleState.RECIEVER_LISTENING;
            if (waitingForConnection || matchingServer) {
                finishReceiverOperation(timeoutMessage);
            }
        };

        long idleTimeoutMs = server == null
                ? RECIEVER_WAITING_FOR_SENDER_TIMEOUT_MS
                : RECIEVER_TRANSFER_IDLE_TIMEOUT_MS;
        mainHandler.postDelayed(receiverIdleTimeout, idleTimeoutMs);
    }

    private void finishReceiverOperation(String message){
        if(!isReceiverState()){
            return;
        }
        boolean transferSucceeded = receiverTransferCompleted.get();
        if (receiverStatusOverlay != null) {
            receiverStatusOverlay.finish(transferSucceeded);
        }

        mainHandler.removeCallbacks(receiverStartupTimeout);
        if(receiverIdleTimeout != null){
            mainHandler.removeCallbacks(receiverIdleTimeout);
            receiverIdleTimeout = null;
        }

        operationGeneration++;
        roleState = RoleState.IDLE;
        // Network teardown owns stopping and waiting for the server thread. Do not
        // drop this reference here or a new service instance could bind before the old
        // listener has finished closing.
        if(recieverNsd != null){
            recieverNsd.stopAdvertising();
        }
        connectedSenderHost = null;
        connectedSenderRemotePort = -1;
        connectedSenderIdentity = null;
        receiverTransferCompleted.set(false);
        receiverTransferStarted.set(false);
        receiverTransferCommitting.set(false);
        setDiscoveryStatus("Idle");
        setConnectionStatus("Disconnected");
        setTransferStatus("Idle");
        showPeerStatus("No peer connected");
        setOverlayOperationStatus("Ready for gesture");
        showStatus(message);
        stopEverything(message, true);
    }


    @Override
    public int onStartCommand(Intent intent,int flags,int startId){
        super.onStartCommand(intent,flags,startId);
        if(intent==null){
            Log.d(TAG,"Service started without intent");
            stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_STOP.equals(intent.getAction())){
            stopEverything("Stopped by user.",true);
            return START_NOT_STICKY;
        }
        if(stopping){
            return START_NOT_STICKY;
        }
        if(startAccepted){
            Log.w(TAG,"Ignoring duplicate AirTransfer start request");
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE,0);
        Intent resultData;
        if(Build.VERSION.SDK_INT>=33){
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA,Intent.class);
        }
        else{
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }

        if(resultData==null){
            Log.d(TAG,"Projection result data is missing");
            stopEverything("Projection result data is missing",true);
            return START_NOT_STICKY;
        }

        startAccepted = true;

        try{
            startForegroundForeground();
            if (gestureDebugOverlay != null) {
                gestureDebugOverlay.show();
            }
            initializeHandGestureController();
            startGestureCamera();
            startProjection(resultCode,resultData);

        }catch(RuntimeException error){
            Log.e(TAG,"Could not start AirTransferService");
            stopEverything("Could not start AirTransferService",true);
        }
        return START_NOT_STICKY;
    }

    private void stopEverything(String reason,boolean stopProjection){
        if(stopping){
            stopSelf();
            return;
        }

        stopping = true;
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setServiceRunning(false);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);

        mainHandler.removeCallbacks(senderTimeout);
        mainHandler.removeCallbacks(receiverStartupTimeout);
        cancelSenderDiscoveryRefresh();
        if (receiverCompletionRunnable != null) {
            mainHandler.removeCallbacks(receiverCompletionRunnable);
            receiverCompletionRunnable = null;
        }

        if(receiverIdleTimeout != null){
            mainHandler.removeCallbacks(receiverIdleTimeout);
            receiverIdleTimeout = null;
        }
        try{
            releaseNetworkResources();
        } catch(RuntimeException error){
            Log.e(TAG,"Error stopping network resources"+error);
        }

        galleryMatchExecutor.shutdownNow();
        wsExecutor.shutdownNow();
        clearTemporaryTransferFiles();
        try{
            releaseCaptureResources(stopProjection);
        }catch(RuntimeException error){
            Log.e(TAG,"Error stopping camera/capture resources"+error);
        }
        stopSelf();
    }

    private void clearTemporaryTransferFiles() {
        File temporaryDirectory = new File(getCacheDir(), "recieved-parts");
        File[] temporaryFiles = temporaryDirectory.listFiles();
        if (temporaryFiles != null) {
            for (File temporaryFile : temporaryFiles) {
                if (temporaryFile.isFile() && !temporaryFile.delete()) {
                    Log.w(TAG, "Could not delete temporary transfer file: "
                            + temporaryFile.getName());
                }
            }
        }
        if (temporaryDirectory.exists() && !temporaryDirectory.delete()) {
            Log.w(TAG, "Could not remove temporary transfer directory");
        }
    }

    private void startForegroundForeground(){
        Intent stopIntent = new Intent(this, AirTransferService.class);
        stopIntent.setAction(ACTION_STOP);

        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = createNotification(stopPendingIntent);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(NOTIFICATION_ID,notification, serviceType);
        }
        else{
            startForeground(NOTIFICATION_ID,notification);
        }
    }

    private void startProjection(int resultCode,Intent resultData){
        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if(manager == null){
            Log.i(TAG,"mediaprojection manager is null");
            stopEverything("mediaprojection manager is null",true);
            return;
        }

        try{
            mediaProjection = manager.getMediaProjection(resultCode,resultData);
            if(mediaProjection == null){
                Log.i(TAG,"Could not obtain mediaProjection");
                stopEverything("Could not obtain mediaProjection",true);
                return;
            }

            mediaProjection.registerCallback(projectionCallback,mainHandler);

            DisplayMetrics displayMetrics = new DisplayMetrics();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(displayMetrics);

            int width = displayMetrics.widthPixels;
            int height = displayMetrics.heightPixels;
            int densityDpi = displayMetrics.densityDpi;

            imageReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
            );

            captureThread = new HandlerThread("AirTransferCapture");
            captureThread.start();
            captureHandler = new Handler(captureThread.getLooper());

            imageReader.setOnImageAvailableListener(
                    this::onImageAvailable,
                    captureHandler
            );

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "AirTransferScreen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    mainHandler
            );

            if(virtualDisplay == null){
                throw new IllegalStateException("mediaprojection returned no virtualDisplay");
            }

            Log.i(TAG,"Projection staretd at" + width + "x" + height);
        } catch(Exception e){
            Log.e(TAG,"Could not start screen projection",e);
            stopEverything("Could not start screen projection",true);
        }
    }

    private boolean requestScreenCapture(){
        if(mediaProjection == null || imageReader == null){
            return false;
        }

        if(capturePending.get()){
            return false;
        }

        captureRequestGeneration = operationGeneration;
        capturePending.set(true);
        overlayOnScreenDuringCapture.set(true);
        mainHandler.post(this::hideOverlayForCleanCapture);
        return true;
    }

    private void hideOverlayForCleanCapture(){
        if(transferEffectOverlay!=null){
            transferEffectOverlay.removeImmediately();
        }
        if(gestureDebugOverlay!=null){
            gestureDebugOverlay.removeImmediately();
        }
        mainHandler.post(()->overlayOnScreenDuringCapture.set(false));
    }

    private void restoreOverlayAfterCleanup(){
        if(gestureDebugOverlay!=null){
            gestureDebugOverlay.show();
        }
        //transferEffectOverlay comes as the code calls transferEffectOverlay.show(Phase.CAPTURED) right after the frame is taken
    }
    private void onImageAvailable(ImageReader reader){
        Image image = null;
        Bitmap bitmap = null;
        long requestGeneration = 0L;
        try{
            image = reader.acquireLatestImage();
            if(image == null){
                Log.i(TAG,"image is null");
                return;
            }
            if(!capturePending.get()){
                // MediaProjection keeps delivering display frames continuously. Ignore them
                // silently until a gesture actually requests a screen capture.
                return;
            }

            if(overlayOnScreenDuringCapture.get()){
                image.close();
                return;
            }

            if(!capturePending.compareAndSet(true,false)){
                Log.i(TAG,"capturePending compare and set");
                return;
            }
            requestGeneration = captureRequestGeneration;

            mainHandler.post(this::restoreOverlayAfterCleanup);

            bitmap = imageToBitmap(image);

            if(bitmap == null){
                Log.i(TAG,"Error in converting from image to bitmap");
                long failedGeneration = requestGeneration;
                mainHandler.post(() -> {
                    if (operationGeneration == failedGeneration) {
                        finishSenderAttempt("Could not convert screen capture.");
                    }
                });
                return;
            }
          //  dumpImage(bitmap);
            Bitmap bitmapForMatching = bitmap.copy(Bitmap.Config.ARGB_8888,false);
            long capturedGeneration = requestGeneration;
            mainHandler.post(() -> {
                if (operationGeneration != capturedGeneration) {
                    return;
                }
                transferEffectOverlay.show(TransferEffectOverlay.Phase.CAPTURED);
                setTransferStatus("Screen captured; matching Gallery");
                setOverlayOperationStatus("Matching captured screen against Gallery");
            });

            if (bitmapForMatching == null) {
                throw new IllegalStateException("Could not copy captured bitmap for matching.");
            }
            final long matchGeneration = requestGeneration;
            galleryMatchExecutor.execute(()->{
                try{
                    GalleryImageMatcher.MatchResult matchResult = GalleryImageMatcher.findBestMatch(
                            getApplicationContext(),bitmapForMatching
                    );

                    if(!matchResult.hasCandidate()){
                        Log.i(TAG,"No matching candidate was found. Images checked:"+
                                matchResult.galleryImagesChecked);

                        mainHandler.post(() -> {
                            if (operationGeneration == matchGeneration) {
                                finishSenderAttempt("No gallery image match was found.");
                            }
                        });
                        return;
                    }

                    Log.i(TAG,"Best gallery candidate: "+ matchResult.bestDisplayName
                               + " best uri:" + matchResult.bestUri
                               + " Inlier count" + matchResult.inlierCount
                               + " Inlier ratio" + matchResult.inlierRatio);

                    String message = (matchResult.Confident?"Gallery match: ":"Possible match: ")
                            + matchResult.bestDisplayName;

                    mainHandler.post(() -> {
                        if (operationGeneration != matchGeneration) {
                            return;
                        }
                        setTransferStatus("Checking Gallery match");
                        setOverlayOperationStatus(message);
                        Toast.makeText(getApplicationContext(),message,Toast.LENGTH_SHORT).show();
                    });
                    if(!matchResult.Confident) {
                        mainHandler.post(() -> {
                            if (operationGeneration == matchGeneration) {
                                finishSenderAttempt("No confident gallery match was found.");
                            }
                        });
                        return;
                    }
                    Uri matchedUri = matchResult.bestUri;
                    mainHandler.post(() -> {
                        if (operationGeneration == matchGeneration) {
                            onGalleryMatchReady(matchedUri);
                        }
                    });
                }catch(Exception e){
                    Log.e(TAG,"Gallery matching failed",e);
                    mainHandler.post(() -> {
                        if (operationGeneration == matchGeneration) {
                            finishSenderAttempt("Gallery matching failed.");
                        }
                    });
                } finally{
                    if(bitmapForMatching!=null && !bitmapForMatching.isRecycled()){
                        bitmapForMatching.recycle();
                    }
                }
            });

            bitmap = null;
            mainHandler.post(() -> {
                if (operationGeneration == capturedGeneration) {
                    Toast.makeText(this,"Screen captured; matching Gallery image.",Toast.LENGTH_SHORT).show();
                }
            });
        } catch(Exception e){
            Log.e(TAG,"Could not captrue and save image",e);
            capturePending.set(false);

            long failedGeneration = requestGeneration;
            mainHandler.post(() -> {
                if (operationGeneration == failedGeneration) {
                    finishSenderAttempt("Screen capture failed.");
                }
            });
        } finally{
            if(bitmap!=null && !bitmap.isRecycled()){
                bitmap.recycle();
            }
            if(image != null){
                image.close();
            }
        }
    }

    private void onGalleryMatchReady(Uri matchedUri){
        if(roleState != RoleState.SENDER_WAITING){
            Log.i(TAG,"Sender operation may have timed out while gallery matching");
            return;
        }

        if(matchedUri == null){
            finishSenderAttempt("matched uri is null");
            return;
        }

        transferEffectOverlay.show(TransferEffectOverlay.Phase.MATCHED);
        pendingItemUri = matchedUri;

        Log.i(TAG,"Gallery image macthed and ready for transfer");
        setTransferStatus("Image ready");
        setDiscoveryStatus(pendingReciever == null
                ? "Sender advertised; searching for receiver"
                : "Receiver found");
        setConnectionStatus(pendingReciever == null
                ? "Waiting for receiver"
                : "Ready to connect");
        showPeerStatus(pendingReciever == null
                ? "Image matched; searching for receiver"
                : "Receiver: " + formatPeer(pendingReciever));
        setOverlayOperationStatus(pendingReciever == null
                ? "Image matched; waiting for receiver"
                : "Image matched; connecting to receiver");
        // Matching has its own deadline. Once the image is ready, allow a full
        // receiver-discovery window even if NSD found a peer earlier, then use
        // a separate connection/transfer deadline.
        scheduleSenderTimeout(SENDER_NO_RECEIVER_TIMEOUT_MS);
        maybeConnectToReciever();
    }

    private void maybeConnectToReciever(){
        if(stopping){
            return;
        }
        if(roleState != RoleState.SENDER_WAITING){
            Log.e(TAG,"Cannot connect, Sender is not waiting.Sender:"+roleState);
            return;
        }
        if(pendingItemUri == null){
            Log.e(TAG,"Image is not ready yet. waiting for gallery matching.");
            return;
        }
        NSDTransferDiscovery.Peer reciever = pendingReciever;
        if(reciever == null){
            Log.e(TAG,"Image is ready.reciever is null");
            return;
        }

        if(reciever.hostAddress == null || reciever.port<=0){
            Log.i(TAG,"reciever has no usable port or ");
            return;
        }
        roleState = RoleState.SENDER_CONNECTING;
        cancelSenderDiscoveryRefresh();
        websocketConnectAttempts = 0;
        scheduleSenderTimeout(SENDER_TIMEOUT_MS);
        setDiscoveryStatus("Receiver selected");
        setConnectionStatus("Connecting to receiver");

        transferEffectOverlay.show(TransferEffectOverlay.Phase.CONNECTING);
        showPeerStatus("Connecting to receiver: " + formatPeer(reciever));
        setOverlayOperationStatus("Connecting to receiver " + reciever.deviceName);
        connectToReciever(reciever);
    }

    private void connectToReciever(NSDTransferDiscovery.Peer recieverPeer){
        if (stopping || roleState != RoleState.SENDER_CONNECTING) {
            return;
        }
        if (websocketConnectRetry != null) {
            mainHandler.removeCallbacks(websocketConnectRetry);
            websocketConnectRetry = null;
        }
        if (websocketConnectTimeout != null) {
            mainHandler.removeCallbacks(websocketConnectTimeout);
            websocketConnectTimeout = null;
        }
        if (websocketConnectAttempts >= MAX_WEBSOCKET_CONNECT_ATTEMPTS) {
            finishSenderAttempt("Could not connect to receiver after "
                    + websocketConnectAttempts + " attempts");
            return;
        }
        websocketConnectAttempts++;
        final int attempt = websocketConnectAttempts;
        final long attemptGeneration = operationGeneration;
        final AtomicBoolean connectionEstablished = new AtomicBoolean(false);
        final AtomicBoolean failureHandled = new AtomicBoolean(false);
        try{
            URI uri = new URI("ws", null, recieverPeer.hostAddress,
                    recieverPeer.port, "/", null, null);
            Log.i(TAG, "WebSocket connect attempt " + attempt + "/"
                    + MAX_WEBSOCKET_CONNECT_ATTEMPTS + " to " + uri);
            final TransferWebSocketClient[] clientRef = new TransferWebSocketClient[1];
            TransferWebSocketClient client = new TransferWebSocketClient(uri,
                    new TransferWebSocketClient.Listener() {
                        @Override
                        public void onConnected() {
                            Log.i(TAG, "WebSocket transport opened for attempt " + attempt);
                            connectionEstablished.set(true);
                            mainHandler.post(()->{
                                if (operationGeneration != attemptGeneration
                                        || webSocketClient != clientRef[0]
                                        || roleState != RoleState.SENDER_CONNECTING) {
                                    return;
                                }
                                cancelWebSocketConnectCallbacks();
                                recieverNsd.stopDiscovery();
                                setConnectionStatus("WebSocket connected");
                                setTransferStatus("Starting image transfer");
                                showPeerStatus("WebSocket connected to: " + formatPeer(recieverPeer));
                                showStatus("Connected to receiver " + recieverPeer.deviceName);
                                sendPendingImage(clientRef[0]);
                            });
                        }

                        @Override
                        public void onDisconnected() {
                            Log.i(TAG,"Disocnnected from reciever");

                            mainHandler.post(()->{
                                if (operationGeneration != attemptGeneration
                                        || webSocketClient != clientRef[0]) {
                                    return;
                                }
                                if (roleState == RoleState.SENDER_CONNECTING
                                        && !connectionEstablished.get()) {
                                    handleWebSocketConnectFailure(clientRef[0], recieverPeer,
                                            attemptGeneration, failureHandled,
                                            connectionEstablished, "Socket closed before connect");
                                } else if (roleState == RoleState.SENDER_SENDING) {
                                    finishSenderAttempt("Connection to receiver was lost");
                                }
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.e(TAG, "WebSocket error for attempt " + attempt + ": "
                                    + e.getMessage(), e);
                            mainHandler.post(() -> {
                                if (operationGeneration != attemptGeneration
                                        || webSocketClient != clientRef[0]) {
                                    return;
                                }
                                if (roleState == RoleState.SENDER_CONNECTING
                                        && !connectionEstablished.get()) {
                                    handleWebSocketConnectFailure(clientRef[0], recieverPeer,
                                            attemptGeneration, failureHandled,
                                            connectionEstablished,
                                            "WebSocket connect error: " + e.getMessage());
                                } else if (roleState == RoleState.SENDER_SENDING) {
                                    finishSenderAttempt("WebSocket transfer error: "
                                            + e.getMessage());
                                }
                            });
                        }

                        @Override
                        public void onTextMessage(String message) {
                            if (webSocketClient == clientRef[0]) {
                                TransferFileSender sender = fileSender;
                                if(sender != null){
                                    sender.onTextMessage(message);
                                }
                            }
                            Log.i(TAG,"Websocket reply");
                        }
                    });

            clientRef[0] = client;
            webSocketClient = client;
            fileSender = new TransferFileSender(client);
            websocketConnectTimeout = () -> {
                if (operationGeneration == attemptGeneration
                        && webSocketClient == client
                        && roleState == RoleState.SENDER_CONNECTING
                        && !connectionEstablished.get()) {
                    handleWebSocketConnectFailure(client, recieverPeer,
                            attemptGeneration, failureHandled, connectionEstablished,
                            "WebSocket connect timed out");
                }
            };
            mainHandler.postDelayed(websocketConnectTimeout,
                    WEBSOCKET_CONNECT_ATTEMPT_TIMEOUT_MS);
            wsExecutor.execute(()->{
                try{
                    client.connect();
                } catch(Exception e){
                    Log.e(TAG,"websocket connection failed",e);
                    mainHandler.post(()->handleWebSocketConnectFailure(client,
                            recieverPeer, attemptGeneration, failureHandled,
                            connectionEstablished, "WebSocket connect error: "
                                    + e.getMessage()));
                }
            });
        } catch(Exception e){
            Log.e(TAG,"Invalid reciever address");
            finishSenderAttempt("Invalid reciever address");
        }
    }

    private void handleWebSocketConnectFailure(
            TransferWebSocketClient failedClient,
            NSDTransferDiscovery.Peer peer,
            long attemptGeneration,
            AtomicBoolean failureHandled,
            AtomicBoolean connectionEstablished,
            String reason
    ) {
        if (operationGeneration != attemptGeneration
                || webSocketClient != failedClient
                || roleState != RoleState.SENDER_CONNECTING
                || connectionEstablished.get()
                || !failureHandled.compareAndSet(false, true)) {
            return;
        }

        Log.w(TAG, "WebSocket connect attempt " + websocketConnectAttempts + "/"
                + MAX_WEBSOCKET_CONNECT_ATTEMPTS + " failed for " + peer + ": " + reason);
        cancelWebSocketConnectCallbacks();
        webSocketClient = null;
        if (fileSender != null) {
            fileSender.shutdown();
            fileSender = null;
        }
        try {
            failedClient.close();
        } catch (RuntimeException closeError) {
            Log.w(TAG, "Could not close failed WebSocket attempt", closeError);
        }

        if (websocketConnectAttempts >= MAX_WEBSOCKET_CONNECT_ATTEMPTS) {
            finishSenderAttempt("Could not connect to receiver after "
                    + websocketConnectAttempts + " attempts: " + reason);
            return;
        }

        setConnectionStatus("Connection attempt " + websocketConnectAttempts
                + " failed; retrying");
        setOverlayOperationStatus("Receiver is still advertised; retrying WebSocket connection");
        showPeerStatus("Retrying receiver: " + formatPeer(peer));
        websocketConnectRetry = () -> {
            websocketConnectRetry = null;
            if (operationGeneration != attemptGeneration
                    || stopping
                    || roleState != RoleState.SENDER_CONNECTING) {
                return;
            }
            NSDTransferDiscovery.Peer currentPeer = pendingReciever == null
                    ? peer : pendingReciever;
            connectToReciever(currentPeer);
        };
        mainHandler.postDelayed(websocketConnectRetry, WEBSOCKET_CONNECT_RETRY_DELAY_MS);
    }

    private void cancelWebSocketConnectCallbacks() {
        if (websocketConnectRetry != null) {
            mainHandler.removeCallbacks(websocketConnectRetry);
            websocketConnectRetry = null;
        }
        if (websocketConnectTimeout != null) {
            mainHandler.removeCallbacks(websocketConnectTimeout);
            websocketConnectTimeout = null;
        }
    }

    private void sendPendingImage(TransferWebSocketClient connectedClient){
        if (roleState != RoleState.SENDER_CONNECTING
                || webSocketClient != connectedClient) {
            return;
        }
        if(pendingItemUri == null || pendingReciever == null || fileSender == null){
            finishSenderAttempt("Sender was not ready after connection");
            return;
        }
        // The discovery/matching deadline has ended. Give the actual socket
        // send its own bounded interval so a stalled transfer cannot leave the
        // sender operation active indefinitely.
        scheduleSenderTimeout(SENDER_TIMEOUT_MS);
        roleState = RoleState.SENDER_SENDING;

        transferEffectOverlay.show(TransferEffectOverlay.Phase.SENDING);
        setConnectionStatus("WebSocket connected");
        setTransferStatus("Sending image 0%");
        setOverlayOperationStatus("Sending image to " + pendingReciever.deviceName);
        Uri uritosend = pendingItemUri;
        fileSender.sendFile(getContentResolver(), uritosend,
                new TransferFileSender.TransferCallback() {
                    @Override
                    public void onProgress(long bytesSent, long totalBytes) {
                        mainHandler.post(() -> {
                            if (webSocketClient == connectedClient
                                    && roleState == RoleState.SENDER_SENDING) {
                                int percent = totalBytes <= 0 ? 0 : (int) Math.min(100L,
                                        (bytesSent * 100L) / totalBytes);
                                setTransferStatus("Sending image " + percent + "%");
                                scheduleSenderTimeout(SENDER_TIMEOUT_MS);
                            }
                        });
                    }

                    @Override
                    public void onSuccess() {
                        mainHandler.post(() -> {
                            if (webSocketClient == connectedClient) {
                                finishSenderAttempt("Image transfer completed.",true);
                            }
                        });
                    }

                    @Override
                    public void onFailure(Exception error) {
                        Log.e(TAG, "Image transfer failed", error);
                        mainHandler.post(() -> {
                            if (webSocketClient == connectedClient) {
                                finishSenderAttempt("Image transfer failed.",false);
                            }
                        });
                    }
                });

    }

    private Bitmap imageToBitmap(Image image){
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();

        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int paddedWidth = rowStride/pixelStride;

        buffer.rewind();

        Bitmap paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
        );

        paddedBitmap.copyPixelsFromBuffer(buffer);

        Bitmap screenBitmap = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                width,
                height
        );

        if(screenBitmap != paddedBitmap){
            paddedBitmap.recycle();
        }
        Log.i(TAG,"Returning bitmap from image");
        return screenBitmap;

    }
    private void releaseCaptureResources(boolean stopProjection){
        if(resourceReleased){
            return;
        }

        resourceReleased = true;

        if(virtualDisplay != null){
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if(transferEffectOverlay!=null){
            transferEffectOverlay.remove();
        }
        if (receiverStatusOverlay != null) {
            receiverStatusOverlay.remove();
            receiverStatusOverlay = null;
        }
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.remove();
            gestureDebugOverlay = null;
        }
        if(imageReader != null){
            imageReader.setOnImageAvailableListener(null,null);
            imageReader.close();
            imageReader = null;
        }

        MediaProjection oldProjection = mediaProjection;
        mediaProjection = null;

        if(oldProjection!=null){
            try{
                oldProjection.unregisterCallback(projectionCallback);
            }catch(Exception e){
                Log.e(TAG,"Error in unregistering callback",e);
            }

            if(stopProjection){
                oldProjection.stop();
            }
        }

        if(captureThread!=null){
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        gestureFramesEnabled.set(false);
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }
        if (cameraProvider != null && imageAnalysis != null) {
            cameraProvider.unbind(imageAnalysis);
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
            cameraExecutor = null;
        }
        if (handGestureController != null) {
            handGestureController.close();
            handGestureController = null;
        }
    }

    @Override
    public void onDestroy(){
        stopping =  true;
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseNetworkResources();

        galleryMatchExecutor.shutdownNow();
        wsExecutor.shutdownNow();

        releaseCaptureResources(true);
        clearTemporaryTransferFiles();
        super.onDestroy();
    }

    private void createNotificationChannel(){
        NotificationChannel notificationChannel = new NotificationChannel(
          CHANNEL_ID,
          "Air Transfer",
          NotificationManager.IMPORTANCE_LOW
        );
        notificationChannel.setDescription("keep screen active for Air Transfer");

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null){
            manager.createNotificationChannel(notificationChannel);
        }
    }

    private Notification createNotification(PendingIntent stopPendingIntent){
        return new NotificationCompat.Builder(this,CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Air transfer screen capture")
                .setContentText("Gesture transfer is active")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopPendingIntent)
                .build();
    }
    public void finishSenderAttempt(String message){
        finishSenderAttempt(message,false);
    }

    private void finishSenderAttempt(String message,boolean success){
        if(!isSenderState()){
            return;
        }

        Log.i(TAG, "Finishing sender attempt: " + message
                + "; stopping service after attempt");

        operationGeneration++;
        senderDeadlineAtMs = 0L;
        cancelWebSocketConnectCallbacks();
        mainHandler.removeCallbacks(senderTimeout);
        cancelSenderDiscoveryRefresh();
        capturePending.set(false);

        offerNsd.stopAdvertising();
        recieverNsd.stopDiscovery();

        if(fileSender!=null){
            fileSender.shutdown();
            fileSender = null;
        }

        if(transferEffectOverlay != null){
            transferEffectOverlay.finish(success);
        }
        if(webSocketClient != null){
            try{
                webSocketClient.close();
            } catch(Exception e){
                Log.e(TAG,"Error closing websocket client"+e);
            }
            webSocketClient = null;
        }
        pendingReciever = null;
        pendingItemUri = null;
        roleState = RoleState.IDLE;
        setDiscoveryStatus("Idle");
        setConnectionStatus("Disconnected");
        setTransferStatus("Idle");
        showPeerStatus("No peer connected");
        setOverlayOperationStatus("Ready for gesture");
        showStatus(message);
        stopEverything(message, true);
    }

    private void releaseNetworkResources() {
        if(networkResourcesReleased){
            return;
        }
        networkResourcesReleased = true;
        cancelSenderDiscoveryRefresh();
        mainHandler.removeCallbacks(senderTimeout);
        mainHandler.removeCallbacks(receiverStartupTimeout);
        if (receiverCompletionRunnable != null) {
            mainHandler.removeCallbacks(receiverCompletionRunnable);
            receiverCompletionRunnable = null;
        }
        if(receiverIdleTimeout != null){
            mainHandler.removeCallbacks(receiverIdleTimeout);
            receiverIdleTimeout = null;
        }
        capturePending.set(false);
        operationGeneration++;
        roleState = RoleState.IDLE;
        senderDeadlineAtMs = 0L;
        pendingItemUri = null;
        pendingReciever = null;
        connectedSenderHost = null;
        connectedSenderRemotePort = -1;
        connectedSenderIdentity = null;
        visibleSenderOffers.clear();
        receiverTransferCompleted.set(false);
        receiverTransferStarted.set(false);
        receiverTransferCommitting.set(false);
        setDiscoveryStatus("Idle");
        setConnectionStatus("Disconnected");
        setTransferStatus("Idle");
        showPeerStatus("No peer connected");
        if (receiverStatusOverlay != null) {
            receiverStatusOverlay.remove();
        }
        if (offerNsd != null) {
            offerNsd.stopAll();
        }
        if(recieverNsd != null){
            recieverNsd.stopAll();
        }
        final TransferWebSocketServer serverToStop = recieverServer;
        recieverServer = null;
        try {
            NSD_TEARDOWN_EXECUTOR.execute(() -> {
                // NSD callbacks are delivered on the main looper, so the wait for the
                // unregister/stop acknowledgments must happen off the main thread. The
                // next session must not re-register the same service types before the
                // platform has processed this session's teardown, otherwise the new
                // advertisement can silently never be announced and peers stop
                // discovering the re-advertised service.
                if (offerNsd != null) {
                    offerNsd.awaitTeardown(NSD_TEARDOWN_WAIT_MS);
                }
                if (recieverNsd != null) {
                    recieverNsd.awaitTeardown(NSD_TEARDOWN_WAIT_MS);
                }
                if (serverToStop != null) {
                    try {
                        serverToStop.stop(2000);
                        Log.i(TAG, "Receiver WebSocket server stopped before service teardown");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted while waiting for receiver server shutdown.", e);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Could not stop receiver server cleanly.", e);
                    }
                }
            });
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not schedule network teardown", error);
            if (serverToStop != null) {
                try {
                    serverToStop.stop(2000);
                } catch (InterruptedException stopError) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException stopError) {
                    Log.w(TAG, "Could not stop receiver server cleanly.", stopError);
                }
            }
        }
        if (fileSender != null) {
            fileSender.shutdown();
            fileSender = null;
        }
        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null;
        }
    }

    private void showStatus(String message){
        Log.i(TAG,message);
        mainHandler.post(()->{
            if (gestureDebugOverlay != null) {
                gestureDebugOverlay.updateServiceState(roleState.name());
                gestureDebugOverlay.setOperationStatus(message);
            }
            Toast.makeText(getApplicationContext(),message,Toast.LENGTH_SHORT).show();
        });

    }

    private void showPeerStatus(String message) {
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setPeerStatus(message);
        }
    }

    private void setOverlayOperationStatus(String message) {
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.updateServiceState(roleState.name());
            gestureDebugOverlay.setOperationStatus(message);
        }
    }

    private void setDiscoveryStatus(String message) {
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setDiscoveryStatus(message);
        }
    }

    private void setConnectionStatus(String message) {
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setConnectionStatus(message);
        }
    }

    private void setTransferStatus(String message) {
        if (gestureDebugOverlay != null) {
            gestureDebugOverlay.setTransferStatus(message);
        }
    }

    private void updateReceiverDiscoveryStatus() {
        if (!isReceiverState()) {
            return;
        }
        if (connectedSenderHost != null) {
            setDiscoveryStatus("Sender connected");
            return;
        }

        boolean senderNearby = !visibleSenderOffers.isEmpty();
        if (roleState == RoleState.RECIEVER_LISTENING) {
            setDiscoveryStatus(senderNearby
                    ? "Receiver advertised; sender nearby"
                    : "Receiver advertised; waiting for sender");
        } else {
            setDiscoveryStatus(senderNearby
                    ? "Sender nearby; receiver starting"
                    : "Searching for sender; receiver starting");
        }
    }

    private NSDTransferDiscovery.Peer findSenderPeer(String remoteHost) {
        if (remoteHost != null) {
            for (NSDTransferDiscovery.Peer peer : visibleSenderOffers.values()) {
                if (peer.hostAddress != null
                        && peer.hostAddress.equalsIgnoreCase(remoteHost)) {
                    return peer;
                }
            }
        }
        return null;
    }

    private boolean sameHost(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private String formatPeer(NSDTransferDiscovery.Peer peer) {
        String name = peer.deviceName == null || peer.deviceName.trim().isEmpty()
                ? peer.serviceName : peer.deviceName;
        String host = peer.hostAddress;
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return name + " at " + host + ":" + peer.port;
    }

    private void dumpImage(Bitmap capturedImage){
        if(capturedImage == null || capturedImage.isRecycled()){
            Log.w(TAG,"Captured image is null or recycled");
            return;
        }
        try{
            Uri savedUri = BitmapDumpUtil.dumpBitmap(getApplicationContext(),capturedImage,"CaptureDump","debug_frame");
            Log.i(TAG,"Captured dump saved "+savedUri);
        } catch(IOException | RuntimeException error){
            Log.e(TAG,"Error in duymping image",error);
        }
    }
}
