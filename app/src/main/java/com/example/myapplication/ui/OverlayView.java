package com.example.myapplication.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import  android.graphics.Paint;

import com.example.myapplication.frame.FrameCorners;
import com.example.myapplication.frame.FramePoint;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;


import androidx.annotation.Nullable;

import java.util.List;


public class OverlayView extends View{

    private static String TAG = "OverlayView";

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

    private int rotationDegrees;

    private FrameCorners frameCorners;

    private FramePoint framePoint;
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public OverlayView(Context context){
        this(context,null);
    }

    public OverlayView(Context context,@Nullable AttributeSet attrs){
        super(context,attrs);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setColor(Color.RED);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(Color.GREEN);
        linePaint.setStrokeWidth(5f);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(8f);
        framePaint.setColor(Color.CYAN);
    }

    public void setResult(List<List<NormalizedLandmark>> hands,int imageWidth,int imageHeight,int rotationDegrees){
        this.hands = hands;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.rotationDegrees = rotationDegrees;
        Log.d(
               TAG, "rotation = " + rotationDegrees
        );
        postInvalidate();
    }

    public void setFrameCorners(FrameCorners frameCorners){
        this.frameCorners = frameCorners;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(hands==null || (hands.isEmpty() && frameCorners==null)) return;
        if(imageWidth == 0 || imageHeight == 0) return;

        // fillCenter (cover) scaling - must match PreviewView scaleType
        float scaleX = getWidth() / (float) imageWidth;
        float scaleY = getHeight() / (float) imageHeight;
        float scale = Math.max(scaleX, scaleY);

        float offsetX = (getWidth() - imageWidth * scale) / 2f;
        float offsetY = (getHeight() - imageHeight * scale) / 2f;

        if(hands != null) {
            for (List<NormalizedLandmark> hand : hands) {

                for (int[] connection : CONNECTIONS) {

                    NormalizedLandmark start =
                            hand.get(connection[0]);

                    NormalizedLandmark end =
                            hand.get(connection[1]);

                    float startX = start.x() * imageWidth * scale + offsetX;
                    float startY = start.y() * imageHeight * scale + offsetY;
                    float endX = end.x() * imageWidth * scale + offsetX;
                    float endY = end.y() * imageHeight * scale + offsetY;

                    canvas.drawLine(
                            startX,
                            startY,
                            endX,
                            endY,
                            linePaint
                    );
                }

                for (NormalizedLandmark landmark : hand) {

                    float x = landmark.x() * imageWidth * scale + offsetX;
                    float y = landmark.y() * imageHeight * scale + offsetY;

                    canvas.drawCircle(
                            x,
                            y,
                            14f,
                            pointPaint
                    );
                }


            }
        }

        drawFrameRectangle(canvas, scale, offsetX, offsetY);
    }

    private void drawFrameRectangle(Canvas canvas,float scale,float offsetX,float offsetY){
        if(frameCorners == null) return;

        FramePoint topleft = frameCorners.getTopleft();
        FramePoint topright = frameCorners.getTopright();
        FramePoint bottomright = frameCorners.getBottomright();
        FramePoint bottomleft = frameCorners.getBottomleft();

        if(topleft == null || topright == null ||
           bottomright == null || bottomleft == null) return;

        float tlx = topleft.x * imageWidth * scale + offsetX;
        float tly = topleft.y * imageHeight * scale + offsetY;

        float trx = topright.x * imageWidth * scale + offsetX;
        float try_ = topright.y * imageHeight * scale + offsetY;

        float brx = bottomright.x * imageWidth * scale + offsetX;
        float bry = bottomright.y * imageHeight * scale + offsetY;

        float blx = bottomleft.x * imageWidth * scale + offsetX;
        float bly = bottomleft.y * imageHeight * scale + offsetY;

        // Clockwise: topleft -> topright -> bottomright -> bottomleft -> close
        canvas.drawLine(tlx, tly, trx, try_, framePaint);
        canvas.drawLine(trx, try_, brx, bry, framePaint);
        canvas.drawLine(brx, bry, blx, bly, framePaint);
        canvas.drawLine(blx, bly, tlx, tly, framePaint);
    }
}
