package com.example.myapplication.Transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
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
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.example.myapplication.Gesture.GestureDebouncer;
import com.example.myapplication.Gesture.GestureEvent;
import com.example.myapplication.Gesture.GestureType;
import com.example.myapplication.vision.HandGestureClassifier;
import com.example.myapplication.camera.ImageConverter;
import com.google.common.util.concurrent.ListenableFuture;


import java.io.File;


public class AirTransferService extends LifecycleService {

    private enum RoleState{
        IDLE,
        SENDER_WAITING,
        SENDER_CONNECTING,
        SENDER_SENDING,
        RECIEVER_STARTING,
        RECIEVER_LISTENING
    }

    private static final int RECIEVER_PORT = 8080;
    private static final long SENDER_TIMEOUT_MS = 60000L;
    private static final long RECIEVER_TIMEOUT_MS = 60000L;
    private static final long RECIEVER_SESSION_TIMEOUT_MS = 60000L;

    private Runnable recieverSessionTimeout;

    private RoleState roleState = RoleState.IDLE;
//    private NSDTransferDiscovery nsdTransferDiscovery;
    private static final String OFFER_SERVICE_TYPE = NSDTransferDiscovery.OFFER_SERVICE_TYPE;
    private static final String RECIEVER_SERVICE_TYPE = NSDTransferDiscovery.RECIEVER_SERVICE_TYPE;

    private NSDTransferDiscovery offerNsd;
    private NSDTransferDiscovery recieverNsd;
    private final Map<String,NSDTransferDiscovery.Peer> visibleSenderOffers = new HashMap<>();
    private String activeSenderSessionId;
    private String activeRecieverSessionId;
    private TransferWebSocketServer recieverServer;
    private NSDTransferDiscovery.Peer pendingReciever;
    private Uri pendingItemUri;

    private final Runnable senderTimeout = () ->{
        if(roleState == RoleState.SENDER_WAITING || roleState == RoleState.SENDER_CONNECTING){
            finishSenderAttempt("Timed out waiting for reciever or transfer");
        }
    };
    private final Runnable receiverStartupTimeout = () -> {
        if (roleState == RoleState.RECIEVER_STARTING) {
            finishRecieverSession("Receiver server did not start.");
            showStatus("Receiver server did not start.");
        }
    };

    private static final String TAG = "AirTransferService";
    private static final String CHANNEL_ID = "air_transfer";
    private static final int NOTIFICATION_ID = 4101;

    public static final String EXTRA_RESULT_CODE = "com.example.myapplication.EXTRA_RESULT_CODE";
    public static final String EXTRA_RESULT_DATA = "com.example.myapplication.EXTRA_RESULT_DATA";

    private static final String ACTION_STOP = "com.example.myapplication.ACTION_STOP_AIR_TRANSFER";

    private final AtomicBoolean capturePending = new AtomicBoolean(false);
    private final AtomicInteger frameToSkip = new AtomicInteger(0);
    private final Handler mainHandler = new Handler();

    private MediaProjection mediaProjection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;

    private Handler captureHandler;
    private HandlerThread captureThread;
    private boolean resourceReleased;

    private WindowManager windowManager;
    private TextView bubble;
    private boolean bubbleAdded;

    private final ExecutorService galleryMatchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService wsExecutor = Executors.newSingleThreadExecutor();

    private TransferWebSocketClient webSocketClient;
    private TransferFileSender fileSender;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;
    private final GestureDebouncer gestureDebouncer = new GestureDebouncer();

    private HandGestureClassifier handGestureClassifier;

    private void startGestureCamera(){

        if(cameraExecutor!=null){
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try{
                cameraProvider = cameraProviderFuture.get();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(640,480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    long frameStartTime = System.nanoTime();
                    try{
                        Bitmap imgbitmap = ImageConverter.toBitmap(image);

                        long timestampMs = image.getImageInfo().getTimestamp() / 1_000_000L;
                        int rotationDegrees = image.getImageInfo().getRotationDegrees();

                        // Rotate to upright + mirror horizontally (front camera)
                        Matrix matrix = new Matrix();
                        matrix.postRotate(rotationDegrees);
                        matrix.postScale(-1f, 1f);
                        Bitmap rotatedBitmap = Bitmap.createBitmap(imgbitmap, 0, 0,
                                imgbitmap.getWidth(), imgbitmap.getHeight(), matrix, true);

                        if(rotatedBitmap !=null && rotatedBitmap != imgbitmap){
                            imgbitmap.recycle();
                        }
                        // Run hand landmark detection on the upright, mirrored frame
                        // handLandMark.detectAsync(rotatedBitmap, timestampMs, rotationDegrees);
                        handGestureClassifier.detectAsync(rotatedBitmap, timestampMs);
                    } finally {
                        image.close();
                    }
                });

                CameraSelector camSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                try {
                    cameraProvider.unbindAll();
                    cameraProvider.bindToLifecycle(this, camSelector, imageAnalysis);
                } catch(Exception ex){
                    Log.e(TAG, "Usecase binding failed", ex);
                }
            } catch(ExecutionException | InterruptedException e){
                Log.e(TAG, "Error getting camera provider" + e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    Log.i(TAG,"MediaProjection stopped by system/user");
                    releaseCaptureResources(false);
                    mainHandler.post(()->{
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf();
                    });
                }
            };

    @Override
    public void onCreate(){
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        initializeNSD();
    }

    private void initializeNSD(){
        offerNsd = new NSDTransferDiscovery(
                getApplicationContext(),
                OFFER_SERVICE_TYPE,
                new NSDTransferDiscovery.Listener() {
                    @Override
                    public void onStatus(String message) {
                        Log.i(TAG,"Sender NSD:"+message);
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {
                        if(peer.sessionId == null || peer.sessionId.isEmpty()){
                            Log.w(TAG,"Ignore peers without a sessionId");
                            return;
                        }
                        visibleSenderOffers.put(peer.sessionId,peer);
                        Log.i(TAG, "Sender offer detected role=");
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Sender offer lost");

                        if(peer.sessionId!=null){
                            visibleSenderOffers.remove(peer.sessionId);
                        }

                        if(peer.sessionId != null && peer.sessionId.equals(activeRecieverSessionId)
                              && isRecieverState()){
                            finishRecieverSession("Sender session ended");
                        }
                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        Log.e(TAG,"NSD error:"+message + ":code"+errorCode);
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
                    }

                    @Override
                    public void onPeerFound(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Reciever discovered");
                        if(roleState != RoleState.SENDER_WAITING){
                            return;
                        }

                        if(activeSenderSessionId == null || !peer.sessionId.equals(activeSenderSessionId)){
                            Log.i(TAG,"Ignoring reciever with a different session ID");
                            return;
                        }

                        if(pendingReciever == null){
                            pendingReciever = peer;
                        }
                        maybeConnectToReciever();
                    }

                    @Override
                    public void onPeerLost(NSDTransferDiscovery.Peer peer) {
                        Log.i(TAG,"Reciever service lost"+peer);
                        if(pendingReciever != null && pendingReciever.serviceName.equals(peer.serviceName)
                             && roleState == RoleState.SENDER_WAITING){
                            pendingReciever = null;
                        }

                    }

                    @Override
                    public void onError(String message, int errorCode) {
                        Log.i(TAG,"Reciever NSD error"+message + ",code:"+errorCode);
                        if(isSenderState()){
                            finishSenderAttempt("Recieevr discovery failed");
                        }
                    }
                }
        );

        offerNsd.startDiscovery();
    }

    private boolean isSenderState(){
        return roleState == RoleState.SENDER_WAITING || roleState == RoleState.SENDER_CONNECTING || roleState == RoleState.SENDER_SENDING;
    }

    private boolean isRecieverState(){
        return roleState == RoleState.RECIEVER_STARTING || roleState == RoleState.RECIEVER_LISTENING;
    }

    private void initializeHandGestureClassifier(){
        if(handGestureClassifier != null){
            return;
        }

        handGestureClassifier = new HandGestureClassifier(
                getApplicationContext(),
                new HandGestureClassifier.Listener() {
                    @Override
                    public void onObservation(
                            GestureType pose,
                            boolean handDetected,
                            float openScore,
                            float closedScore
                    ) {
                        GestureEvent event = gestureDebouncer.update(
                                pose,
                                handDetected,
                                SystemClock.elapsedRealtime()
                        );
                        if(event != null){
                            mainHandler.post(()->handleGestureEvent(event));
                        }
                    }

                    @Override
                    public void onError(RuntimeException error) {
                        Log.e(TAG,"Gesture classifier failed"+error);
                    }
                }
        );
    }
    private void handleGestureEvent(GestureEvent event){
        Log.i(TAG,"Handling gesture event=" + event + " roleState=" + roleState);
        if(event.getKind() == GestureEvent.Kind.GRAB){
            Log.i(TAG,"Closed fist recognized,starting senderFile");
            startSenderMode();
            return;
        }
        if(event.getKind() == GestureEvent.Kind.RELEASE){
            Log.i(TAG,"Closed fist recognized,starting senderFile");
            startRecieverMode();
            return;
        }

    }

    private void startSenderMode(){
        if(roleState != RoleState.IDLE){
            Log.w(TAG,"roleState is not idle");
            return;
        }
        if(mediaProjection == null || imageReader == null){
            Log.w(TAG,"mediaprojection or imageReader is null");
            showStatus("Screen capture is not active");
            return;
        }

        activeSenderSessionId = UUID.randomUUID().toString();

        roleState = RoleState.SENDER_WAITING;
        pendingItemUri = null;
        pendingReciever = null;

        mainHandler.removeCallbacks(senderTimeout);
        mainHandler.postDelayed(senderTimeout,SENDER_TIMEOUT_MS);
        if(!requestScreenCapture()){
            finishSenderAttempt("Could not get screen capture");
            return;
        }

        offerNsd.startAdvertising(Build.MODEL,RECIEVER_PORT,activeSenderSessionId);

        recieverNsd.startDiscovery();

        showStatus("Finding reciever and gallery matching started");
    }

    private void startRecieverMode(){
        if(roleState != RoleState.IDLE){
            Log.w(TAG, "Ignoring RELEASE because transfer role is already active: "
                    + roleState);
            return;
        }

        if(visibleSenderOffers.isEmpty()){
            showStatus("No sender offer detected. Do GRAB on sender first");
            return;
        }

        if(visibleSenderOffers.size()>1){
            showStatus("Multiple senders detected. Cancel them to proceed");
            Log.i(TAG,"Ignoring release. Offers detected=" + visibleSenderOffers.keySet());
            return;
        }
        NSDTransferDiscovery.Peer offer = visibleSenderOffers.values().iterator().next();
        activeRecieverSessionId = offer.sessionId;
        visibleSenderOffers.remove(activeRecieverSessionId);

        roleState = RoleState.RECIEVER_STARTING;
        showStatus("starting reciever for the sesson" + activeRecieverSessionId);

        mainHandler.removeCallbacks(receiverStartupTimeout);
        mainHandler.postDelayed(receiverStartupTimeout, RECIEVER_TIMEOUT_MS);

        File temporaryDirectory = new File(getCacheDir(),"recieved-parts");
        final String expectedSessionId = activeRecieverSessionId;

        recieverServer = new TransferWebSocketServer(getApplicationContext(),
                RECIEVER_PORT,
                temporaryDirectory.toPath());

        recieverServer.setTransferCompletedListener(()->{
            mainHandler.postDelayed(()->{
                if(expectedSessionId.equals(activeRecieverSessionId) && roleState == RoleState.RECIEVER_LISTENING){
                    finishRecieverSession("Image recieved successfully");
                }
            },1000L);
        });
        recieverServer.setServerStartedListener(()->{
            mainHandler.post(()->{
                if(roleState != RoleState.RECIEVER_STARTING || !expectedSessionId.equals(activeRecieverSessionId)){
                    return;
                }

                mainHandler.removeCallbacks(receiverStartupTimeout);
                roleState = RoleState.RECIEVER_LISTENING;
                recieverNsd.startAdvertising(Build.MODEL,RECIEVER_PORT,expectedSessionId);

                showStatus("Reciever is ready for sender session");
                Log.i(TAG,"Reciever listening and advertised on port " + RECIEVER_PORT);
                scheduleRecieverSessionTimeout(expectedSessionId);
            });
        });

        try{
            recieverServer.start();
        } catch(Exception e){
            Log.e(TAG,"Could not start websocket server"+e);
            finishRecieverSession("Could not start reciever");
            showStatus("Could not start reciever");
        }
    }

    private void scheduleRecieverSessionTimeout(String sessionId){
        if(recieverSessionTimeout!=null){
            mainHandler.removeCallbacks(recieverSessionTimeout);
        }

        recieverSessionTimeout = ()->{
            if(sessionId.equals(activeRecieverSessionId) && roleState==RoleState.RECIEVER_LISTENING){
                showStatus("Reciever timed out waiting for sender");
                finishRecieverSession("Reciever timed out waiting for sender");
            }
        };

        mainHandler.postDelayed(recieverSessionTimeout,RECIEVER_SESSION_TIMEOUT_MS);
    }

    private void finishRecieverSession(String message){
        if(!isRecieverState()){
            return;
        }

        mainHandler.removeCallbacks(receiverStartupTimeout);
        if(recieverSessionTimeout!=null){
            mainHandler.removeCallbacks(recieverSessionTimeout);
            recieverSessionTimeout = null;
        }

        roleState = RoleState.IDLE;
        if(recieverServer!=null){
            try{
                recieverServer.stop();
            } catch(InterruptedException e){
                Thread.currentThread().interrupt();
                Log.w(TAG,"Interrupted while stopping recieevrServer");
            } catch(RuntimeException e){
                Log.w(TAG,"Error stopping recieverServer");
            }

            recieverServer = null;
        }

        if(recieverNsd != null){
            recieverNsd.stopAdvertising();
        }
        activeRecieverSessionId = null;
        showStatus(message);
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
            releaseCaptureResources(true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundForeground();
        initializeHandGestureClassifier();
        startGestureCamera();

        startProjection(resultCode,resultData);
        return START_NOT_STICKY;
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
            int serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
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
            stopSelf();
            return;
        }

        try{
            mediaProjection = manager.getMediaProjection(resultCode,resultData);
            if(mediaProjection == null){
                Log.i(TAG,"Could not obtain mediaProjection");
                stopSelf();
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

            addFloatingButton();

            Log.i(TAG,"Projection staretd at" + width + "x" + height);
        } catch(Exception e){
            Log.e(TAG,"Could not start screen projection",e);
            releaseCaptureResources(true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void addFloatingButton(){
        if(!Settings.canDrawOverlays(this)){
            throw new IllegalStateException("Overlay permission is not granted");
        }
        bubble = new TextView(this);
        bubble.setText("AIR");
        bubble.setTextColor(0xFFFFFFFF);
        bubble.setTextSize(14f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setElevation(8f);
        bubble.setPadding(18,14,18,14);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE3155CC);
        background.setCornerRadius(1000f);

        bubble.setBackground(background);
        bubble.setOnClickListener(view->startSenderMode());

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 24;
        params.y = 300;

        windowManager.addView(bubble,params);
        bubbleAdded = true;
    }

    private boolean requestScreenCapture(){
        if(mediaProjection == null || imageReader == null){
            return false;
        }

        if(capturePending.get()){
            return false;
        }

        if(bubble!=null){
            bubble.setVisibility(TextView.INVISIBLE);
        }
        frameToSkip.set(1);
        capturePending.set(true);
        return true;
    }

    private void onImageAvailable(ImageReader reader){
        Image image = null;
        Bitmap bitmap = null;
        try{
            image = reader.acquireLatestImage();
            if(image == null){
                Log.i(TAG,"image is null");
                return;
            }
            if(!capturePending.get()){
                Log.i(TAG,"capture pending is false");
                return;
            }

            if(frameToSkip.getAndDecrement()>0){
                Log.i(TAG,"frame to skip is >0");
                return;
            }

            if(!capturePending.compareAndSet(true,false)){
                Log.i(TAG,"capturePending compare and set");
                return;
            }

            bitmap = imageToBitmap(image);

            if(bitmap == null){
                Log.i(TAG,"Error in converting from image to bitmap");
                mainHandler.post(() ->
                        finishSenderAttempt("Could not convert screen capture."));
                return;
            }

            Bitmap bitmapForMatching = bitmap.copy(Bitmap.Config.ARGB_8888,false);
            if (bitmapForMatching == null) {
                throw new IllegalStateException("Could not copy captured bitmap for matching.");
            }
            galleryMatchExecutor.execute(()->{
                try{
                    GalleryImageMatcher.MatchResult matchResult = GalleryImageMatcher.findBestMatch(
                            getApplicationContext(),bitmapForMatching
                    );

                    if(!matchResult.hasCandidate()){
                        Log.i(TAG,"No matching candidate was found. Images checked:"+
                                matchResult.galleryImagesChecked);

                        mainHandler.post(() ->
                                finishSenderAttempt("No gallery image match was found."));
                        return;
                    }

                    Log.i(TAG,"Best gallery candidate: "+ matchResult.bestDisplayName
                               + " best uri:" + matchResult.bestUri
                               + " Inlier count" + matchResult.inlierCount
                               + " Inlier ratio" + matchResult.inlierRatio);

                    String message = (matchResult.Confident?"Gallery match: ":"Possible match: ")
                            + matchResult.bestDisplayName;

                    mainHandler.post(()->{
                        Toast.makeText(getApplicationContext(),message,Toast.LENGTH_SHORT).show();
                    });
                    if(!matchResult.Confident) {
                        mainHandler.post(() ->
                                finishSenderAttempt("No confident gallery match was found."));
                        return;
                    }
                    Uri matchedUri = matchResult.bestUri;
                    mainHandler.post(()->onGalleryMatchReady(matchedUri));
                }catch(Exception e){
                    Log.e(TAG,"Gallery matching failed",e);
                    mainHandler.post(() ->
                            finishSenderAttempt("Gallery matching failed."));
                } finally{
                    if(bitmapForMatching!=null && !bitmapForMatching.isRecycled()){
                        bitmapForMatching.recycle();
                    }
                }
            });

            bitmap = null;
            mainHandler.post(()->{
                if(bubble!=null) {
                    bubble.setVisibility(TextView.VISIBLE);
                }
                Toast.makeText(this,"Screen captured; matching Gallery image.",Toast.LENGTH_SHORT).show();
            });
        } catch(Exception e){
            Log.e(TAG,"Could not captrue and save image",e);
            capturePending.set(false);
            frameToSkip.set(0);

            mainHandler.post(()->{
                if(bubble!=null) {
                    bubble.setVisibility(TextView.VISIBLE);
                }
                finishSenderAttempt("Screen capture failed.");
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
            Log.i(TAG,"sender session may have timed out while gallery matching");
            return;
        }

        if(matchedUri == null){
            finishSenderAttempt("matched uri is null");
            return;
        }

        pendingItemUri = matchedUri;

        Log.i(TAG,"Gallery image macthed and ready for transfer");
        maybeConnectToReciever();
    }

    private void maybeConnectToReciever(){
        if(roleState != RoleState.SENDER_WAITING || pendingItemUri == null ||
                pendingReciever == null || activeSenderSessionId == null ||
                !activeSenderSessionId.equals(pendingReciever.sessionId)){
            return;
        }
        recieverNsd.stopDiscovery();

        roleState = RoleState.SENDER_CONNECTING;
        connectToReciever(pendingReciever);
    }

    private void connectToReciever(NSDTransferDiscovery.Peer recieverPeer){
        try{
            URI uri = new URI("ws://" + recieverPeer.hostAddress + ":" + recieverPeer.port);
            Log.i(TAG,"Connectiong to discovered peer:"+uri);
            webSocketClient = new TransferWebSocketClient(uri,
                    new TransferWebSocketClient.Listener() {
                        @Override
                        public void onConnected() {
                            Log.i(TAG,"Connected to reciever");
                            mainHandler.post(()->sendPendingImage());
                        }

                        @Override
                        public void onDisconnected() {
                            Log.i(TAG,"Disocnnected from recievr");

                            mainHandler.post(()->{
                                if(roleState == RoleState.SENDER_CONNECTING ||
                                        roleState == RoleState.SENDER_SENDING){
                                    finishSenderAttempt("Connection to reciever was lost");
                                }
                            });
                        }

                        @Override
                        public void onError(Exception e) {
                            Log.e(TAG,"reciever connection error"+e);
                            mainHandler.post(()->{
                                if(roleState == RoleState.SENDER_CONNECTING ||
                                        roleState == RoleState.SENDER_SENDING){
                                    finishSenderAttempt("Error connecting to reciever");
                                }
                            });

                        }

                        @Override
                        public void onTextMessage(String message) {
                            TransferFileSender sender = fileSender;
                            if(sender != null){
                                sender.onTextMessage(message);
                            }
                            Log.i(TAG,"Websocket reply");
                        }
                    });

            fileSender = new TransferFileSender(webSocketClient);
            wsExecutor.execute(()->{
                try{
                    webSocketClient.connect();
                } catch(Exception e){
                    Log.e(TAG,"websocket connection failed"+e);
                    mainHandler.post(()->{
                        finishSenderAttempt("Could not connect to reciever");
                    });
                }
            });
        } catch(Exception e){
            Log.e(TAG,"Invalid reciever address");
            finishSenderAttempt("Invalid reciever address");
        }
    }

    private void sendPendingImage(){
        if (roleState != RoleState.SENDER_CONNECTING) {
            return;
        }
        if(pendingItemUri == null || pendingReciever == null || fileSender == null){
            finishSenderAttempt("Sender was not ready after connection");
            return;
        }
        mainHandler.removeCallbacks(senderTimeout);
        roleState = RoleState.SENDER_SENDING;
        Uri uritosend = pendingItemUri;
        fileSender.sendFile(getContentResolver(), uritosend,
                new TransferFileSender.TransferCallback() {
                    @Override
                    public void onSuccess() {
                        mainHandler.post(() ->
                                finishSenderAttempt("Image transfer completed."));
                    }

                    @Override
                    public void onFailure(Exception error) {
                        Log.e(TAG, "Image transfer failed", error);
                        mainHandler.post(() ->
                                finishSenderAttempt("Image transfer failed."));
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

        if(bubbleAdded && bubble!=null){
            try{
                windowManager.removeView(bubble);
            } catch (Exception e){
                Log.e(TAG,"Error in removing bubble",e);
            }
        }
        bubbleAdded = false;
        bubble = null;

        if(virtualDisplay != null){
            virtualDisplay.release();
            virtualDisplay = null;
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
        if(cameraProvider != null && imageAnalysis != null){
            cameraProvider.unbind(imageAnalysis);
        }

        if(cameraExecutor != null){
            cameraExecutor.shutdown();
        }

        if(handGestureClassifier != null){
            handGestureClassifier.cleanup();
            handGestureClassifier = null;
        }
    }

    @Override
    public void onDestroy(){
        releaseNetworkResources();
        galleryMatchExecutor.shutdownNow();
        wsExecutor.shutdownNow();
        releaseCaptureResources(true);
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

    private void finishSenderAttempt(String message){
        if(!isSenderState()){
            return;
        }

        mainHandler.removeCallbacks(senderTimeout);
        capturePending.set(false);
        frameToSkip.set(0);

        offerNsd.stopAdvertising();
        recieverNsd.stopDiscovery();
        if (bubble != null) {
            bubble.setVisibility(TextView.VISIBLE);
        }

        if(fileSender!=null){
            fileSender.shutdown();
            fileSender = null;
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
        activeSenderSessionId = null;
        roleState = RoleState.IDLE;
        showStatus(message);
    }

    private void releaseNetworkResources() {
        mainHandler.removeCallbacks(senderTimeout);
        mainHandler.removeCallbacks(receiverStartupTimeout);
        capturePending.set(false);
        roleState = RoleState.IDLE;
        pendingItemUri = null;
        pendingReciever = null;
        if (offerNsd != null) {
            offerNsd.stopAll();
        }
        if(recieverNsd != null){
            recieverNsd.stopAll();
        }
        if (recieverServer != null) {
            try {
                recieverServer.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while stopping receiver server.", e);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not stop receiver server cleanly.", e);
            }
            recieverServer = null;
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
        mainHandler.post(()->Toast.makeText(getApplicationContext(),message,Toast.LENGTH_SHORT).show());

    }
}
