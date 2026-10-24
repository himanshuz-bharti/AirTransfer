package com.example.myapplication.Transfer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NativeOpenCvInstrumentationTest {

    @Test
    public void nativeLibraryLoadsAndCallsOpenCv() {
        String result = NativeOpenCvBridge.runNativeCheck();
        Log.i("NativeOpenCvTest", "Native OpenCV check: " + result);

        assertNotNull(result);
        assertTrue(result, result.contains("OpenCV 4.14.0 loaded"));
        assertTrue(result, result.contains("test mean=17.000000"));
    }
}
