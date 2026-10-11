package com.example.myapplication.Gesture;

public class GestureDebouncer {

    public static class DebounceState{
        GestureType current = GestureType.NONE;
        int consecutiveFrame = 0;


    }
}
