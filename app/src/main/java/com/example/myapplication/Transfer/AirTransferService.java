package com.example.myapplication.Transfer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.util.concurrent.ExecutorService;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.myapplication.MainActivity;
import com.example.myapplication.SenderDiscoveryActivity;
import com.example.myapplication.utils.BitmapDumpUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public class AirTransferService extends Service {
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
    private static final String PREFS_NAME = "airtransfer_prefs";
    private static final String PREF_WS_IP = "ws_ip";
    private static final String DEFAULT_WS_URI = "ws://192.168.1.4:8080";

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
    }

    private void connectWebSocket(String selectedhost, int selectedport){
        try{
            String host = selectedhost;

            if(host==null || host.trim().isEmpty()){
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                host = prefs.getString(PREF_WS_IP, "192.168.1.26");
            }

            String uriStr = "ws://" + host + ":" + selectedport;
            Log.i(TAG, "Connecting to " + uriStr);
            URI uri = new URI(uriStr);
            webSocketClient = new TransferWebSocketClient(uri, new TransferWebSocketClient.Listener() {
                @Override
                public void onConnected() {
                    Log.i(TAG, "WebSocket connected in AirTransferService");
                    mainHandler.post(() -> Toast.makeText(AirTransferService.this, "Connected to receiver", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onDisconnected() {
                    Log.i(TAG, "WebSocket disconnected in AirTransferService");
                }

                @Override
                public void onError(Exception e) {
                    Log.e(TAG, "WebSocket error in AirTransferService", e);
                }

                @Override
                public void onTextMessage(String message) {
                    if (fileSender != null) {
                        fileSender.onTextMessage(message);
                    }
                    Log.d(TAG, "WS message: " + message);
                }
            });
            fileSender = new TransferFileSender(webSocketClient);
            wsExecutor.execute(() -> {
                try {
                    webSocketClient.connect();
                } catch (Exception e) {
                    Log.e(TAG, "Connection failed", e);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "connectWebSocket failed", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent,int flags,int startId){
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

        String peerHost = intent.getStringExtra(SenderDiscoveryActivity.EXTRA_PEER_HOST);
        int peerPort = intent.getIntExtra(SenderDiscoveryActivity.EXTRA_PEER_PORT, 8080);

        if(webSocketClient == null){
            connectWebSocket(peerHost,peerPort);
        }

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
            startForeground(NOTIFICATION_ID,notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
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
        bubble.setOnClickListener(view->onFloatingButtonTapped());

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

    private void onFloatingButtonTapped(){
        if(mediaProjection == null || imageReader == null){
            Toast.makeText(this,"Screen capture is not active",Toast.LENGTH_SHORT).show();
            return;
        }

        if(capturePending.get()){
            return;
        }

        if(bubble!=null){
            bubble.setVisibility(TextView.INVISIBLE);
        }
        frameToSkip.set(1);
        capturePending.set(true);
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
                Log.i(TAG,"Error in convertung from image to bitmap");
                return;
            }

//            try{
//                Uri dumpImageUri = BitmapDumpUtil.dumpBitmap(getApplicationContext(),
//                        bitmap,"AirTransfer","Screen");
//                Log.i(TAG,"ScreenShot saved to"+dumpImageUri);
//            } catch(IOException e){
//                Log.e(TAG,"Could not save the image"+e);
//            }

            Bitmap bitmapForMatching = bitmap.copy(Bitmap.Config.ARGB_8888,false);
            galleryMatchExecutor.execute(()->{
                try{
                    GalleryImageMatcher.MatchResult matchResult = GalleryImageMatcher.findBestMatch(
                            getApplicationContext(),bitmapForMatching
                    );

                    if(!matchResult.hasCandidate()){
                        Log.i(TAG,"No matching candidate was found. Images checked:"+
                                matchResult.galleryImagesChecked);

                        mainHandler.post(()->{
                            Toast.makeText(getApplicationContext(),"No images match found",Toast.LENGTH_SHORT)
                                    .show();
                        });
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
                    if(matchResult.Confident) {
                        try {
                            Uri matchedUri = BitmapDumpUtil.copyImageToPictures(getApplicationContext(),
                                    matchResult.bestUri, "AirTransfer");
                            Log.i(TAG, "Matched image copied to gallery" + matchedUri);
                        } catch (IOException e) {
                            Log.e(TAG, "Could not copy matched image");
                        }

                        TransferWebSocketClient client = webSocketClient;
                        TransferFileSender sender = fileSender;
                        if(client == null || sender==null){
                            Log.e(TAG,"Cannot send, laptop websocket server is not connected");
                            mainHandler.post(()->{
                                Toast.makeText(getApplicationContext(),"Server is not connected",Toast.LENGTH_SHORT).show();
                            });
                        }
                        else{
                            Log.i(TAG,"Image before being sent over websocket" + matchResult.bestDisplayName);
                            sender.sendFile(getContentResolver(),matchResult.bestUri);
                            Log.i(TAG,"Image sent over websocket" + matchResult.bestDisplayName);
                        }
                    }
                    // If we have a confident match, send the original file from gallery
                }catch(RuntimeException e){
                    Log.e(TAG,"Gallery matching failed"+e);
                } finally{
                    if(bitmapForMatching!=null && !bitmapForMatching.isRecycled()){
                        bitmapForMatching.recycle();
                    }
                }
            });

           // saveCapturedBitmap(bitmap);
            bitmap = null;
            mainHandler.post(()->{
                if(bubble!=null) {
                    bubble.setVisibility(TextView.VISIBLE);
                }
                Toast.makeText(this,"Screen Image saved",Toast.LENGTH_SHORT).show();
            });
        } catch(Exception e){
            Log.e(TAG,"Could not captrue and save image",e);
            capturePending.set(false);
            frameToSkip.set(0);

            mainHandler.post(()->{
                if(bubble!=null) {
                    bubble.setVisibility(TextView.VISIBLE);
                }
                Toast.makeText(this,"Screen Image failed",Toast.LENGTH_SHORT).show();
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
    }

    @Override
    public void onDestroy(){
        releaseCaptureResources(true);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent){
        return null;
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
                .setContentText("Capturing screeen image shortly")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopPendingIntent)
                .build();
    }
}
