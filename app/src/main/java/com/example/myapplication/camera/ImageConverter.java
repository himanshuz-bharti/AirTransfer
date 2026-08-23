package com.example.myapplication.camera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;
public class ImageConverter {

    public static Bitmap toBitmap(ImageProxy image){
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        buffer.rewind();

        int width = image.getWidth();
        int height = image.getHeight();

        int rowStride = image.getPlanes()[0].getRowStride();
        int pixelStride = image.getPlanes()[0].getPixelStride();

        Bitmap bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);

        int[] pixels =
                new int[width * height];

        byte[] rowData =
                new byte[rowStride];

        for (int y = 0; y < height; y++) {

            buffer.position(y * rowStride);

            int bytesPerPixel = pixelStride;

            int rowLength =
                    width * bytesPerPixel;

            if (rowLength > rowData.length) {
                rowData = new byte[rowLength];
            }

            buffer.get(
                    rowData,
                    0,
                    rowLength
            );

            for (int x = 0; x < width; x++) {

                int offset =
                        x * bytesPerPixel;

                int r =
                        rowData[offset] & 0xFF;

                int g =
                        rowData[offset + 1] & 0xFF;

                int b =
                        rowData[offset + 2] & 0xFF;

                int a =
                        rowData[offset + 3] & 0xFF;

                pixels[
                        y * width + x
                        ] = Color.argb(
                        a,
                        r,
                        g,
                        b
                );
            }
        }

        bitmap.setPixels(
                pixels,
                0,
                width,
                0,
                0,
                width,
                height
        );

        return bitmap;

    }
}
