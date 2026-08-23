package com.example.myapplication.frame;

import android.util.Log;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;

public class FrameDetector {

    public static String TAG = "FrameDetector";

    public FrameCorners detect(HandLandmarkerResult result) {
        if(result==null || result.landmarks().size()<2) return null;
        List<List<NormalizedLandmark>> hands = result.landmarks();
        List<List<Category>> handedness = result.handedness();

        FramePoint topleft=null,bottomleft=null,topright=null,bottomright=null;
        for(int i=0;i<hands.size();i++){
            List<NormalizedLandmark> hand = hands.get(i);
            String label =  handedness.get(i).get(0).categoryName();
            String userHand;
            if(label.equals("Left")) userHand="Right";
            else userHand="Left";

            NormalizedLandmark thumbtip = hand.get(4);
            NormalizedLandmark indextip = hand.get(8);
            Log.d(TAG, userHand + " hand | " + "thumb=(" + thumbtip.x() + ", "
                    + thumbtip.y() + ") " + "index=(" + indextip.x() + ", " +
                    indextip.y() + ")");

            if(userHand.equals("Left")){
                topleft = new FramePoint(indextip.x(), indextip.y());
                bottomleft = new FramePoint(thumbtip.x(), thumbtip.y());
            }
            else{
                topright = new FramePoint(indextip.x(), indextip.y());
                bottomright = new FramePoint(thumbtip.x(), thumbtip.y());
            }
        }

        FrameCorners corner = new FrameCorners(topleft,bottomleft,topright,bottomright);
        return corner;
    }
}
