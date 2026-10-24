package com.example.myapplication.utils;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class BitmapDumpUtil {

    private static String TAG = "BitmapDumpUtil";

    private BitmapDumpUtil(){

    }

    private static String safeName(String originalName,String defaultName){
        if(originalName == null || originalName.trim().isEmpty()){
            return defaultName;
        }
        String safe = originalName.trim().replaceAll("[^A-Za-z0-9_-]","-");
        return safe.isEmpty()?defaultName:safe;
    }
    public static Uri dumpBitmap(Context context,Bitmap bitmap,String folderName,String filePrefix)
    throws IOException{
        if(context == null){
            Log.e(TAG,"context is null");
            return null;
        }
        if(bitmap == null || bitmap.isRecycled()){
            Log.e(TAG,"Bitmap is invalid");
            return null;
        }

        String folder = safeName(folderName,"ImageDump");
        String image = safeName(filePrefix,"Image");

        String fileName = image+"-"+System.currentTimeMillis()+".png";
        return saveWithMediaStore(context,bitmap,folder,fileName);
    }

    private static Uri saveWithMediaStore(Context context,Bitmap bitmap,String folder, String fileName)
    throws IOException{
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES+"/"+folder);
        values.put(MediaStore.Images.Media.IS_PENDING,1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);

        if(uri == null){
            throw new IOException("Mediastore cannot create image entry");
        }
        boolean published = false;
        try{
            try(OutputStream output=resolver.openOutputStream(uri,"w")){
                if(output==null){
                    throw new IOException("Cannot open the output stream");
                }
                if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output)){
                    throw new IOException("Failed to compress bitmaop");
                }
                output.flush();
            }
            ContentValues publishedValues = new ContentValues();
            publishedValues.put(MediaStore.Images.Media.IS_PENDING,0);

            if(resolver.update(uri,publishedValues,null,null)<0){
                throw new IOException("Failed to publish the image");
            }
            published = true;
            return uri;
        } catch(IOException e){
            throw e;
        } catch(RuntimeException e){
            throw new IOException("Could not save the image to pictures directory",e);
        } finally{
            if(!published){
                resolver.delete(uri,null,null);
            }
        }
    }

    public static Uri copyImageToPictures(Context context,Uri sourceUri,String folderName) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (sourceUri == null) {
            throw new IllegalArgumentException("sourceUri cannot be null");
        }

        ContentResolver resolver = context.getContentResolver();
        String mimeType = resolver.getType(sourceUri);

        if (mimeType == null || !mimeType.startsWith("image/")) {
            throw new IOException("The source uri is invalid");
        }

        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null || extension.isEmpty()) {
            extension = "img";
        }

        String folder = safeName(folderName, "ImageDump");
        String fileName = "matched_" + System.currentTimeMillis() + "." + extension;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + folder);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri destinationUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (destinationUri == null) {
            throw new IOException("Error in creating destination Uri");
        }

        boolean published = false;
        try {
            try (InputStream input = resolver.openInputStream(sourceUri);
                 OutputStream out = resolver.openOutputStream(destinationUri, "w")) {
                if (input == null || out == null) {
                    throw new IOException("Error in opening input or output stream");
                }

                byte[] buffer = new byte[32 * 1024];
                int byteRead;
                while ((byteRead = input.read(buffer)) != -1) {
                    out.write(buffer, 0, byteRead);
                }
                out.flush();
            }

            ContentValues publishValue = new ContentValues();
            publishValue.put(MediaStore.Images.Media.IS_PENDING, 0);

            if (resolver.update(destinationUri, publishValue, null, null) <= 0) {
                throw new IOException("Error in updating isPending field");
            }

            published = true;
            return destinationUri;
        }catch (IOException e){
            throw e;
        }catch(RuntimeException e){
            throw new IOException("Could not dump imag eusing sourceUri",e);
        }finally{
            if(!published){
                resolver.delete(destinationUri,null,null);
            }
        }
    }
}
