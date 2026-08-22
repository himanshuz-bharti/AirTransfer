package com.example.myapplication.camera;

public class FrameData {
    private final int height;
    private final int width;
    private final int rotationDegree;
    private final long timeStamp;

    public FrameData(int height,int width,int rotationDegree,long timeStamp){
        this.height = height;
        this.width = width;
        this.rotationDegree = rotationDegree;
        this.timeStamp = timeStamp;
    }

    public int getheight(){
        return height;
    }
    public int getWidth() {
        return width;
    }

    public int getrotationDegree(){
        return rotationDegree;
    }

    public long getTimeStamp(){
        return timeStamp;
    }



}
