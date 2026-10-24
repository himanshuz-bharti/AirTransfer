package com.example.myapplication.Transfer;

import android.graphics.Bitmap;

/** Loads the app's native library and exposes its JNI entry points. */
public final class NativeOpenCvBridge {

    static {
        System.loadLibrary("gallerymatcher");
    }

    private NativeOpenCvBridge() {
    }

    /** Runs a small native OpenCV operation and returns a diagnostic string. */
    public static native String runNativeCheck();

    public static native long nativeCreateFeatures(Bitmap bitmap);

    public static native int[] nativeCompareFeatures(
      long candidateFeatures,
      long screenshotFeatures
    );

    public static native void nativeReleaseFeatures(long feature);
}
