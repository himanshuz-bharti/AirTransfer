package com.example.myapplication.camera;

import android.util.Log;

import androidx.camera.core.ImageProxy;

public class FrameProcessor {

    private static final String TAG = "FrameProcessor";

    public FrameData processFrame(ImageProxy image){

        int width = image.getWidth();
        int height = image.getHeight();
        int rotationDegree = image.getImageInfo().getRotationDegrees();
        long timestamp = image.getImageInfo().getTimestamp();

        return new FrameData(height,width,rotationDegree,timestamp);

    }
}
