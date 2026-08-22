package com.example.myapplication.camera;

public class PerformaneMonitor {

    private long framecount = 0;
    private long starttime = 0;

    private double lastFrameTimeMs = 0.0;
    private double fps = 0.0;

    public void onFrameStart(){
        long curTimeNs = System.nanoTime();
        if(starttime==0){
            starttime = curTimeNs;
        }
    }

    public void onFrameEnd(long frameStartTime){
        long curTimeNs = System.nanoTime();
        lastFrameTimeMs = (curTimeNs - frameStartTime)/1_000_000.0;
        framecount++;

        long elapsedTime = curTimeNs - starttime;
        if(elapsedTime>=1_000_000_000L){
            fps = framecount/(elapsedTime/1_000_000_000.0);
            framecount = 0;
            starttime = curTimeNs;
        }
    }

    public double getfps(){
        return fps;
    }

    public double getLastFrameTimeMs(){
        return lastFrameTimeMs;
    }

}
