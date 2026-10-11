package com.example.myapplication.Gesture;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;
public class GestureRecognizer {

    public static final int[] TIP_INDICES = {4,8,12,16,20};
    public static final int[] PIP_INDICES = {2,6,10,14,18};

    public GestureType classify(List<NormalizedLandmark> handlandmarks) {
        if(handlandmarks == null){
            return GestureType.NONE;
        }
        if(handlandmarks.size()<21){
            return GestureType.NONE;
        }

        boolean indexFolded = isFolded(handlandmarks,TIP_INDICES[1],PIP_INDICES[1]);
        boolean middleFolded = isFolded(handlandmarks,TIP_INDICES[2],PIP_INDICES[2]);
        boolean ringFolded = isFolded(handlandmarks,TIP_INDICES[3],PIP_INDICES[3]);
        boolean pinkyFolded = isFolded(handlandmarks,TIP_INDICES[4],PIP_INDICES[4]);

        if(indexFolded && middleFolded && ringFolded && pinkyFolded){
            return GestureType.CLOSED;
        }
        else return GestureType.OPEN;
    }

    boolean isFolded(List<NormalizedLandmark> handlandmarks,int tip_idx,int pip_idx){
        return handlandmarks.get(tip_idx).y()>handlandmarks.get(pip_idx).y();
    }


}
