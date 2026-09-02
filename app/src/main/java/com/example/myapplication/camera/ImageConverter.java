package com.example.myapplication.camera;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;

public class ImageConverter {

    public static Bitmap toBitmap(ImageProxy image){
        int numPlanes = image.getPlanes().length;

        Bitmap bitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
        int[] pixels = new int[image.getWidth() * image.getHeight()];

        if (numPlanes == 1) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();

            int width = image.getWidth();
            int height = image.getHeight();

            int rowStride = image.getPlanes()[0].getRowStride();
            int pixelStride = image.getPlanes()[0].getPixelStride();

            byte[] rowData = new byte[rowStride];

            for (int y = 0; y < height; y++) {
                buffer.position(y * rowStride);

                int rowLength = width * pixelStride;

                if (rowLength > rowData.length) {
                    rowData = new byte[rowLength];
                }

                buffer.get(rowData, 0, rowLength);

                for (int x = 0; x < width; x++) {
                    int offset = x * pixelStride;

                    int r = rowData[offset] & 0xFF;
                    int g = rowData[offset + 1] & 0xFF;
                    int b = rowData[offset + 2] & 0xFF;
                    int a = rowData[offset + 3] & 0xFF;

                    pixels[y * width + x] = Color.argb(a, r, g, b);
                }
            }
        } else {
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

            int width = image.getWidth();
            int height = image.getHeight();

            int yRowStride = image.getPlanes()[0].getRowStride();
            int uRowStride = image.getPlanes()[1].getRowStride();
            int vRowStride = image.getPlanes()[2].getRowStride();

            byte[] yData = new byte[yBuffer.remaining()];
            byte[] uData = new byte[uBuffer.remaining()];
            byte[] vData = new byte[vBuffer.remaining()];

            yBuffer.get(yData);
            uBuffer.get(uData);
            vBuffer.get(vData);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int yIndex = y * yRowStride + x;
                    int Y = yData[yIndex] & 0xFF;

                    int uvY = y / 2;
                    int uvX = x / 2;
                    int uIndex = uvY * uRowStride + uvX;
                    int vIndex = uvY * vRowStride + uvX;

                    int U = uData[uIndex] & 0xFF;
                    int V = vData[vIndex] & 0xFF;

                    int R = (int)(Y + 1.402f * (V - 128));
                    int G = (int)(Y - 0.344136f * (U - 128) - 0.714136f * (V - 128));
                    int B = (int)(Y + 1.772f * (U - 128));

                    R = Math.min(255, Math.max(0, R));
                    G = Math.min(255, Math.max(0, G));
                    B = Math.min(255, Math.max(0, B));

                    pixels[y * width + x] = Color.argb(255, R, G, B);
                }
            }
        }

        bitmap.setPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        return bitmap;
    }
}