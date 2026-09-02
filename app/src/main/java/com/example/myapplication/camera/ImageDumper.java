package com.example.myapplication.camera;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

public class ImageDumper {

    private static final String TAG = "ImageDumper";

    public static void dump(Bitmap bitmap, String tag){
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Cannot dump: bitmap is null or recycled");
            return;
        }

        File dir = new File(tag);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                return;
            }
        }

        String filename = tag + "/dump_" + System.currentTimeMillis() + ".png";
        File outFile = new File(filename);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            Log.d(TAG, "Image dumped to: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to dump image", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to close stream", e);
                }
            }
        }
    }

    public static void dump(Bitmap bitmap, File dir){
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Cannot dump: bitmap is null or recycled");
            return;
        }

        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                Log.e(TAG, "Failed to create directory: " + dir.getAbsolutePath());
                return;
            }
        }

        String filename = dir.getAbsolutePath() + "/dump_" + System.currentTimeMillis() + ".png";
        File outFile = new File(filename);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(outFile);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            Log.d(TAG, "Image dumped to: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to dump image", e);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to close stream", e);
                }
            }
        }
    }
}