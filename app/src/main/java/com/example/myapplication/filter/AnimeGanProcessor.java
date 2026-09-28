package com.example.myapplication.filter;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AnimeGanProcessor{
    private static final int INPUT_SIZE = 512;

    private static final String TAG = "AnimeGANProcessor";
    private Interpreter interpreter;
    public AnimeGanProcessor(Context context) throws Exception{
        MappedByteBuffer model = loadmodelFile(context);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4);
        interpreter = new Interpreter(model,options);
        logModelInfo();
    }

    public Bitmap process(Bitmap inputbitmap){
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(inputbitmap,INPUT_SIZE,
                INPUT_SIZE,true);

        ByteBuffer inputbuffer = ByteBuffer.allocateDirect(1*INPUT_SIZE*INPUT_SIZE*3*4);
        inputbuffer.order(ByteOrder.nativeOrder());

        for(int y=0;y<INPUT_SIZE;y++){
            for(int x=0;x<INPUT_SIZE;x++){
                int pixel = resizedBitmap.getPixel(x,y);

                int r = Color.red(pixel);
                int g = Color.green(pixel);
                int b = Color.blue(pixel);

                inputbuffer.putFloat(r/127.5f-1.0f);
                inputbuffer.putFloat(g/127.5f-1.0f);
                inputbuffer.putFloat(b/127.5f-1.0f);
            }
        }

        float[][][][] output = new float[1][INPUT_SIZE][INPUT_SIZE][3];
        interpreter.run(inputbuffer,output);

        Bitmap outputBitmap = Bitmap.createBitmap(
                INPUT_SIZE,
                INPUT_SIZE,
                Bitmap.Config.ARGB_8888
        );

        for(int y=0;y<INPUT_SIZE;y++){
            for(int x=0;x<INPUT_SIZE;x++){
                float r = output[0][y][x][0];
                float g = output[0][y][x][1];
                float b = output[0][y][x][2];

                int red = Math.round((r+1.0f)*127.5f);
                int green = Math.round((g+1.0f)*127.5f);
                int blue = Math.round((b+1.0f)*127.5f);

                red = Math.max(0,Math.min(255,red));
                green = Math.max(0,Math.min(255,green));
                blue = Math.max(0,Math.min(255,blue));

                int color = Color.rgb(red,green,blue);
                outputBitmap.setPixel(x,y,color);
            }
        }
        return outputBitmap;
    }

    public Bitmap stylize(Bitmap frame, Rect roi){
        Rect r = new Rect(Math.max(0,roi.left),Math.max(0,roi.top),
                Math.min(frame.getWidth(),roi.right),Math.min(frame.getHeight(),roi.bottom));

        Bitmap crop = Bitmap.createBitmap(frame,r.left,r.top,r.width(),r.height());
        Bitmap animeres = process(crop);
        Bitmap scaledres = Bitmap.createScaledBitmap(animeres,r.width(),r.height(),true);

        Bitmap result = frame.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(scaledres,r.left,r.top,null);

        animeres.recycle();
        return result;

    }
    private MappedByteBuffer loadmodelFile(Context context) throws Exception{
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd("AnimeGANv3_Hayao_36.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();

        return fileChannel.map(
                FileChannel.MapMode.READ_ONLY,
                fileDescriptor.getStartOffset(),
                fileDescriptor.getDeclaredLength()
        );
    }

    private void logModelInfo(){
        int[] inputShape = interpreter.getInputTensor(0).shape();
        int[] outputShape = interpreter.getOutputTensor(0).shape();

        Log.d(TAG,"Input shape:" + Arrays.toString(inputShape));
        Log.d(TAG,"Input type:" + interpreter.getInputTensor(0).dataType());

        Log.d(TAG,"Input shape:" + Arrays.toString(outputShape));
        Log.d(TAG,"Input type:" + interpreter.getOutputTensor(0).dataType());
    }

    public void close(){
        if(interpreter!=null){
            interpreter.close();
            interpreter = null;
        }
    }
}
