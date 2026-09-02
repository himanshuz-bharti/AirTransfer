package com.example.myapplication.filter;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Core;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

public class CartoonFilter {
    public static Bitmap apply(Bitmap input){
        Mat src = new Mat();
        Utils.bitmapToMat(input, src);

        Mat bgr = new Mat();
        Imgproc.cvtColor(src,bgr,Imgproc.COLOR_RGBA2BGR);

        Mat smoothMat = new Mat();
        Imgproc.bilateralFilter(bgr,smoothMat,9,100,100);

        Mat gray = new Mat();
        Imgproc.cvtColor(bgr,gray,Imgproc.COLOR_RGB2GRAY);

        Mat grayBlur = new Mat();
        Imgproc.medianBlur(gray,grayBlur,5);

        Mat edges = new Mat();
        Imgproc.adaptiveThreshold(grayBlur,edges,255,Imgproc.ADAPTIVE_THRESH_MEAN_C,
                Imgproc.THRESH_BINARY,9,2);

        Mat cartoon = new Mat();
        Core.bitwise_and(smoothMat, smoothMat, cartoon, edges);

        Mat cartoonRGBA = new Mat();
        Imgproc.cvtColor(cartoon, cartoonRGBA, Imgproc.COLOR_BGR2RGBA);

        Bitmap output = Bitmap.createBitmap(cartoonRGBA.cols(),cartoonRGBA.rows(),Bitmap.Config.ARGB_8888);

        Utils.matToBitmap(cartoonRGBA,output);

        src.release();
        gray.release();
        smoothMat.release();
        bgr.release();
        edges.release();
        grayBlur.release();
        cartoon.release();
        cartoonRGBA.release();

        return output;
    }
}
