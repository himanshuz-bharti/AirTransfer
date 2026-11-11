package com.example.myapplication.Gesture;

import android.util.Log;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;
public class GestureRecognizer {

    private static final String TAG = "GestureRecognizer";
    public static final int[] TIP_INDICES = {4,8,12,16,20};
    public static final int[] PIP_INDICES = {2,6,10,14,18};
    private GestureType lastLoggedClassification;

    public synchronized GestureType classify(List<NormalizedLandmark> handlandmarks) {
        GestureType classification;
        if(handlandmarks == null){
            classification = GestureType.NONE;
        } else if(handlandmarks.size()<21){
            classification = GestureType.NONE;
        } else {
            boolean indexFolded = isFolded(handlandmarks,TIP_INDICES[1],PIP_INDICES[1]);
            boolean middleFolded = isFolded(handlandmarks,TIP_INDICES[2],PIP_INDICES[2]);
            boolean ringFolded = isFolded(handlandmarks,TIP_INDICES[3],PIP_INDICES[3]);
            boolean pinkyFolded = isFolded(handlandmarks,TIP_INDICES[4],PIP_INDICES[4]);

            classification = indexFolded && middleFolded && ringFolded && pinkyFolded
                    ? GestureType.CLOSED
                    : GestureType.OPEN;
        }

        // Log only transitions, rather than every camera inference frame.
        if (classification != lastLoggedClassification) {
            Log.d(TAG, "Raw classification changed from " + lastLoggedClassification
                    + " to " + classification
                    + "; landmarkCount="
                    + (handlandmarks == null ? 0 : handlandmarks.size()));
            lastLoggedClassification = classification;
        }
        return classification;
    }

    boolean isFolded(List<NormalizedLandmark> handlandmarks,int tip_idx,int pip_idx){
        return handlandmarks.get(tip_idx).y()>handlandmarks.get(pip_idx).y();
    }


}
