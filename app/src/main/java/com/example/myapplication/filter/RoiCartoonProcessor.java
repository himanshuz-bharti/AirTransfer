package com.example.myapplication.filter;

import android.graphics.Bitmap;
import android.graphics.PointF;

import com.example.myapplication.frame.FrameCorners;
import com.example.myapplication.frame.FramePoint;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;


public class RoiCartoonProcessor {

    public static Bitmap apply(Bitmap original, Bitmap cartoon, FrameCorners corners,int imgWidth,
                               int imgHeight){

        if(corners == null) return original;
        FramePoint tl = corners.getTopleft();
        FramePoint bl = corners.getBottomleft();
        FramePoint tr = corners.getTopright();
        FramePoint br = corners.getBottomright();

        if(tl==null || bl==null || tr==null || br==null) return original;

        // Points must be in convex (perimeter) order, otherwise the polygon
        // self-intersects like a bowtie and fillConvexPoly produces garbage.
        // Same order OverlayView draws: tl -> tr -> br -> bl
        Point[] quadpoints = new Point[]{
                new Point(tl.x*imgWidth , tl.y*imgHeight),
                new Point(tr.x*imgWidth, tr.y*imgHeight),
                new Point(br.x*imgWidth, br.y*imgHeight),
                new Point(bl.x*imgWidth, bl.y*imgHeight)
        };

        Mat originalMat = new Mat();
        Utils.bitmapToMat(original,originalMat);

        Mat cartoonMat = new Mat();
        Utils.bitmapToMat(cartoon,cartoonMat);

        // Bitmaps come in as RGBA (4-channel), masks are 3-channel.
        // Convert to BGR (3-channel) so all Mats match for Core.multiply,
        // and so the final COLOR_BGR2RGBA conversion below is correct —
        // treating RGB data as BGR would swap red/blue on the whole frame.
        Mat originalRgb = new Mat();
        Imgproc.cvtColor(originalMat, originalRgb, Imgproc.COLOR_RGBA2BGR);

        Mat cartoonRgb = new Mat();
        Imgproc.cvtColor(cartoonMat, cartoonRgb, Imgproc.COLOR_RGBA2BGR);

        // OpenCV Mat takes (rows, cols) = (height, width)
        Mat mask = Mat.zeros(imgHeight,imgWidth,CvType.CV_8UC1);
        Imgproc.fillConvexPoly(mask,new MatOfPoint(quadpoints),new Scalar(255));

        Mat softMask = new Mat();
        Imgproc.GaussianBlur(mask,softMask,new Size(15,15),5.0);

        Mat softMask2 = new Mat();
        Imgproc.cvtColor(softMask,softMask2,Imgproc.COLOR_GRAY2RGB);
        softMask2.convertTo(softMask2,CvType.CV_32FC3,1.0/255.0);

        Mat invMask = new Mat();
        Core.subtract(new Mat(softMask2.rows(),softMask2.cols(), softMask2.type(),Scalar.all(1.0)),softMask2,invMask);

        Mat cartoonF = new Mat();
        Mat originalF = new Mat();

        cartoonRgb.convertTo(cartoonF,CvType.CV_32FC3);
        originalRgb.convertTo(originalF,CvType.CV_32FC3);

        Mat blended = new Mat();
        Core.multiply(cartoonF,softMask2,cartoonF);
        Core.multiply(originalF,invMask,originalF);
        Core.add(cartoonF,originalF,blended);

        Mat result = new Mat();
        blended.convertTo(result,CvType.CV_8UC3);
        Imgproc.cvtColor(result,result,Imgproc.COLOR_BGR2RGBA);

        Bitmap output = Bitmap.createBitmap(result.cols(),result.rows(),Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(result, output);

        originalMat.release();
        originalRgb.release();
        cartoonMat.release();
        cartoonRgb.release();
        mask.release();
        softMask.release();
        softMask2.release();
        invMask.release();
        cartoonF.release();
        originalF.release();
        blended.release();
        result.release();

        return output;


    }
}
