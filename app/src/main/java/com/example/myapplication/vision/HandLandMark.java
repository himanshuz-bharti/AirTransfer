package com.example.myapplication.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;

public class HandLandMark {
    private static final String TAG = "HandLandMark";
    private final HandLandmarker handLandmarker;
    public interface ResultListener{
        void onResult(HandLandmarkerResult result,int imageWidth,int imageHeight);
        void onError(RuntimeException error);
    }

    private final ResultListener resultlistener;
    public HandLandMark(Context context,ResultListener resultlistener) {
        BaseOptions baseoptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build();
        HandLandmarker.HandLandmarkerOptions options =  HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseoptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(this::onHandLandmarkerResult)
                .setErrorListener(this::onHandLandmarkerError)
                .build();

        handLandmarker = HandLandmarker.createFromOptions(context,options);
        this.resultlistener = resultlistener;
    }

    private void onHandLandmarkerResult(HandLandmarkerResult result, MPImage input){
        int resultsize =  result.landmarks().size();
        Log.d(TAG,"hand detected" + resultsize);

        if(resultsize==0) return;
        resultlistener.onResult(result, input.getWidth(), input.getHeight());
//        for(int handidx=0;handidx<resultsize;handidx++){
//            var landmarks = result.landmarks().get(handidx);
//            Log.d(TAG, "hand idx:" + handidx + " landmarks size "+landmarks.size());
//            for(int j=0;j<landmarks.size();j++){
//                var landmark = landmarks.get(j);
//                Log.d(TAG," handidx: "+ handidx + " point " + j + " x= " + landmark.x() + " y= " + landmark.y() + " z= " + landmark.z());
//            }
//        }
    }

    private void onHandLandmarkerError(RuntimeException error) {
        Log.e(TAG, "Hand Landmarker error: " + error.getMessage());
        resultlistener.onError(error);
    }

    public void detectAsync(Bitmap bitmap,long timestamp){
        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        handLandmarker.detectAsync(mpImage,timestamp);
    }

    public void cleanup(){
        handLandmarker.close();
    }

}
