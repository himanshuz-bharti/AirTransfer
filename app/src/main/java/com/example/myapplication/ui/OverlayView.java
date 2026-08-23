package com.example.myapplication.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import  android.graphics.Paint;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;


import androidx.annotation.Nullable;

import java.util.List;


public class OverlayView extends View{

    private static final int[][] CONNECTIONS = {

            // Thumb
            {0, 1},
            {1, 2},
            {2, 3},
            {3, 4},

            // Index
            {0, 5},
            {5, 6},
            {6, 7},
            {7, 8},

            // Middle
            {0, 9},
            {9, 10},
            {10, 11},
            {11, 12},

            // Ring
            {0, 13},
            {13, 14},
            {14, 15},
            {15, 16},

            // Pinky
            {0, 17},
            {17, 18},
            {18, 19},
            {19, 20},

            // Palm
            {5, 9},
            {9, 13},
            {13, 17}
    };
    private Paint pointPaint;
    private Paint linePaint;

    private List<List<NormalizedLandmark>> hands;
    private int imageWidth;
    private int imageHeight;

    public OverlayView(Context context){
        this(context,null);
    }

    public OverlayView(Context context,@Nullable AttributeSet attrs){
        super(context,attrs);

        pointPaint = new Paint();
        linePaint = new Paint();

        pointPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);

        pointPaint.setStrokeWidth(8f);
        linePaint.setStrokeWidth(5f);
    }

    public void setResult(List<List<NormalizedLandmark>> hands,int imageWidth,int imageHeight){
        this.hands = hands;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(hands == null) return;

        for (List<NormalizedLandmark> hand : hands) {

            // Draw connections
            for (int[] connection : CONNECTIONS) {

                NormalizedLandmark start =
                        hand.get(connection[0]);

                NormalizedLandmark end =
                        hand.get(connection[1]);

                float startX =
                        start.x() * getWidth();

                float startY =
                        start.y() * getHeight();

                float endX =
                        end.x() * getWidth();

                float endY =
                        end.y() * getHeight();

                canvas.drawLine(
                        startX,
                        startY,
                        endX,
                        endY,
                        linePaint
                );
            }

            // Draw landmark points
            for (NormalizedLandmark landmark : hand) {

                float x =
                        landmark.x() * getWidth();

                float y =
                        landmark.y() * getHeight();

                canvas.drawCircle(
                        x,
                        y,
                        10f,
                        pointPaint
                );
            }
        }
    }
}
