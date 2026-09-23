package com.example.myapplication.filter;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Core;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

public class CartoonFilter {

    // ---- Tuning knobs (change these only, in this order) ----
    // Canny: too many random lines -> raise (100, 200); broken/dashed -> lower (50, 120)
    private static final double CANNY_LOW = 70;
    private static final double CANNY_HIGH = 150;
    // Saturation boost: faces too orange -> 1.2; colors dull -> 1.6
    private static final double SATURATION_SCALE = 1.4;
    // Posterize keeps top 3 bits of each channel (8 levels/color).
    // Not flat enough -> 0xC0 (4 levels, coarsest). Too blocky -> 0xF0 (16 levels)
    private static final int POSTERIZE_MASK = 0xE0;
    // Line color: harsh pure black -> 0,0,0; softer pencil look -> 50,50,50
    private static final Scalar LINE_COLOR = new Scalar(25, 25, 25);

    public static Bitmap apply(Bitmap input) {
        Mat src = new Mat();
        Utils.bitmapToMat(input, src);

        Mat bgr = new Mat();
        Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR);

        // ---------- Edge branch: real, continuous outlines ----------
        Mat gray = new Mat();
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);       // data is BGR -> use BGR2GRAY

        Mat grayBlur = new Mat();
        Imgproc.medianBlur(gray, grayBlur, 7);                     // denoise without killing edges

        Mat edges = new Mat();
        Imgproc.Canny(grayBlur, edges, CANNY_LOW, CANNY_HIGH);     // white=line, black=no line

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        Imgproc.dilate(edges, edges, kernel, new Point(-1, -1), 1); // close gaps -> continuous lines

        // ---------- Color branch: vivid palette ----------
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
        List<Mat> hsvChannels = new ArrayList<>(3);
        Core.split(hsv, hsvChannels);
        hsvChannels.get(1).convertTo(hsvChannels.get(1), CvType.CV_8UC1, SATURATION_SCALE);
        Core.merge(hsvChannels, hsv);
        Mat vivid = new Mat();
        Imgproc.cvtColor(hsv, vivid, Imgproc.COLOR_HSV2BGR);

        Mat smooth = new Mat();
        Imgproc.bilateralFilter(vivid, smooth, 9, 100, 100);       // flatten gradients

        // ---------- Posterize: quantize to flat color bands ----------
        // pixel & 0xE0 on every channel = keep top 3 bits (8 flat levels).
        // Same math a 256-entry LUT would do, but via bitwise_and with a
        // constant Mat (Imgproc.LUT is not available in these bindings).
        Mat posterizeMask = new Mat(smooth.size(), smooth.type(),
                new Scalar(POSTERIZE_MASK, POSTERIZE_MASK, POSTERIZE_MASK));
        Mat flat = new Mat();
        Core.bitwise_and(smooth, posterizeMask, flat);

        // ---------- Composite: dark lines ON flat color ----------
        // NOT bitwise_and(smooth, edges) — that keeps highlight blobs and
        // destroys the colors. Instead: keep the flat color image, then
        // paint line-colored pixels wherever the edge mask is white.
        flat.setTo(LINE_COLOR, edges);

        Mat cartoonRGBA = new Mat();
        Imgproc.cvtColor(flat, cartoonRGBA, Imgproc.COLOR_BGR2RGBA);

        Bitmap output = Bitmap.createBitmap(cartoonRGBA.cols(), cartoonRGBA.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(cartoonRGBA, output);

        src.release();
        bgr.release();
        gray.release();
        grayBlur.release();
        edges.release();
        kernel.release();
        hsv.release();
        for (Mat m : hsvChannels) m.release();
        vivid.release();
        smooth.release();
        posterizeMask.release();
        flat.release();
        cartoonRGBA.release();

        return output;
    }
}
