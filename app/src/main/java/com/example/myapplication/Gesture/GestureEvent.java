package com.example.myapplication.Gesture;

import android.util.Log;

public class GestureEvent {
    private static final String TAG = "GestureEvent";

    public enum Kind{
        GRAB,
        RELEASE
    }

    private final Kind kind;
    public GestureEvent(Kind kind){
        if(kind == null){
            throw new IllegalArgumentException("kind must not be null");
        }
        this.kind = kind;
        Log.i(TAG, "Created gesture event: " + kind);
    }

    public Kind getKind(){
        return this.kind;
    }

    @Override
    public boolean equals(Object o){
        if(this == o) return true;
        if(!(o instanceof GestureEvent)) return false;
        GestureEvent that = (GestureEvent) o;
        return kind == that.kind;
    }

    @Override
    public int hashCode(){
        return kind.hashCode();
    }

    @Override
    public String toString(){
        return "GestureEvent{" + kind + "}";
    }



}
