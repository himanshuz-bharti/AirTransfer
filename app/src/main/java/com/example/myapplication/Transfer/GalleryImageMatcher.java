package com.example.myapplication.Transfer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.util.ArrayList;
import java.util.List;
public class GalleryImageMatcher {
    private static final String TAG = "GalleryImageMatcher";
    private static final int MAX_IMAGES = 100;
    private static final int THUMBNAIL_SIZE = 640;
    public static final int MIN_INLINERS = 14;
    public static final double MIN_INLINER_RATIO = 0.35;

    private GalleryImageMatcher(){

    }

    public static final class MatchResult{
        public final Uri bestUri;
        public final String bestDisplayName;
        public final int inlierCount;
        public final double inlierRatio;

        public final int galleryImagesChecked;
        public final boolean Confident;

        public MatchResult(Uri bestUri,String bestDisplayName,
                           int inlierCount,double inlierRatio,int galleryImagesChecked,boolean Confident){
            this.bestUri = bestUri;
            this.bestDisplayName = bestDisplayName;
            this.inlierCount = inlierCount;
            this.inlierRatio = inlierRatio;
            this.galleryImagesChecked = galleryImagesChecked;
            this.Confident = Confident;
        }

        public static MatchResult noMatch(int checked){
            return new MatchResult(null,null,0,0.0,checked,false);
        }

        public boolean hasCandidate(){
            return bestUri!=null;
        }

    }

    public static class GalleryImage{
        private final Uri imageUri;
        private final String displayName;

        public GalleryImage(Uri imageUri,String displayName){
            this.imageUri = imageUri;
            this.displayName = displayName;
        }

        public Uri getImageUri() {
            return imageUri;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static MatchResult findBestMatch(Context context,Bitmap capturedScreenshot){
        if(context == null || capturedScreenshot == null || capturedScreenshot.isRecycled()){
            return MatchResult.noMatch(0);
        }

        ContentResolver resolver = context.getContentResolver();
        List<GalleryImage> images = queryGalleryImages(resolver);

//        for(GalleryImage img:images){
//            Log.i(TAG,"Gallery image uri:" + img.imageUri);
//            Log.i(TAG,"Gallery image name:" + img.displayName);
//        }
        
        if (images.isEmpty()) {
            return MatchResult.noMatch(0);
        }

        Bitmap screenshotArgb = capturedScreenshot.copy(Bitmap.Config.ARGB_8888,false);
        if(screenshotArgb==null){
            return MatchResult.noMatch(0);
        }

        long screenshotHandle = 0;
        int imageCompared = 0;

        Uri bestUri = null;
        String bestDisplayName = null;
        int bestMatch = 0;
        int bestInliers = 0;
        double bestInlierRatio = 0.0f;
        try{
            Log.i(TAG,"Screenshot handle before nativeCreateFeatures");
            screenshotHandle = NativeOpenCvBridge.nativeCreateFeatures(screenshotArgb);
            Log.i(TAG,"Screenshot handle after nativeCreateFeatures");
            if(screenshotHandle == 0){
                return MatchResult.noMatch(0);
            }

            for(GalleryImage img:images){
                Bitmap galleryImageBitmap = null;
                long galleryImageHandle=0;
                try{
                    galleryImageBitmap = loadThumbnails(resolver,img.imageUri);
                    if(galleryImageBitmap == null){
                        continue;
                    }
                    Bitmap galleryImageArgb = galleryImageBitmap.copy(Bitmap.Config.ARGB_8888,false);

                    if(galleryImageArgb == null){
                        continue;
                    }
                    try{
                        Log.i(TAG,"Gallery handle before nativeCreateFeatures");
                        galleryImageHandle = NativeOpenCvBridge.nativeCreateFeatures(galleryImageArgb);
                        Log.i(TAG,"Gallery handle after nativeCreateFeatures");
                    }finally{
                        galleryImageArgb.recycle();
                    }

                    if(galleryImageHandle==0){
                        continue;
                    }

                    int[] comparison = NativeOpenCvBridge.nativeCompareFeatures(galleryImageHandle,screenshotHandle);
                    if(comparison == null || comparison.length<2){
                        Log.i(TAG,"Comparison returned no result for"+img.displayName);
                        continue;
                    }
                    int mutualMatches = comparison[0];
                    int inliers = comparison[1];
                    if(mutualMatches<0 | inliers<0){
                        Log.i(TAG,"Mututal mathces or inliers returned is invalid");
                        continue;
                    }

                    double inlierRatio = mutualMatches == 0? 0.0:(double)inliers/mutualMatches;
                    imageCompared++;

                    Log.i(TAG,"Compared:"+img.displayName + " mutualMatches:" + mutualMatches
                    + " inliers:" + inliers + " inlierRatio:" + inlierRatio);

                    boolean isBetter = (inliers > bestInliers) || (inliers == bestInliers && inliers>0 &&
                            inlierRatio>bestInlierRatio);

                    if(isBetter){
                        bestDisplayName = img.displayName;
                        bestInliers = inliers;
                        bestUri = img.imageUri;
                        bestMatch = mutualMatches;
                        bestInlierRatio = inlierRatio;
                    }
                } catch(Exception e){
                    Log.e(TAG,"Cannot compare gallery image",e);
                } finally{
                    if(galleryImageHandle != 0){
                        NativeOpenCvBridge.nativeReleaseFeatures(galleryImageHandle);
                    }
                    if(galleryImageBitmap!=null && !galleryImageBitmap.isRecycled()){
                        galleryImageBitmap.recycle();
                    }
                }
            }
        } finally{
            if(screenshotHandle!=0){
                NativeOpenCvBridge.nativeReleaseFeatures(screenshotHandle);
            }

            if(!screenshotArgb.isRecycled()){
                screenshotArgb.recycle();
            }
        }
        if(bestUri == null){
            return MatchResult.noMatch(0);
        }
        boolean confident = bestInliers >= MIN_INLINERS && bestInlierRatio >= MIN_INLINER_RATIO;

        Log.i(TAG, "Best gallery match: " + bestDisplayName + ", inliers=" + bestInliers + ", ratio=" + bestInlierRatio
                + ", confident=" + confident
                + ", imagesCompared=" + imageCompared);

        return new MatchResult(bestUri, bestDisplayName, bestInliers, bestInlierRatio, imageCompared,
                confident);
    }

    private static List<GalleryImage> queryGalleryImages(ContentResolver resolver){
        List<GalleryImage> images = new ArrayList<>();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try(Cursor cursor = resolver.query(
                collection,
                projection,
                null,
                null,
                sortOrder
        )){
            if(cursor == null){
                return images;
            }

            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);

            while(cursor.moveToNext() && images.size() < MAX_IMAGES){
                long id = cursor.getLong(idColumn);
                String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "unknown.jpg";
                Uri uri = ContentUris.withAppendedId(collection, id);
                images.add(new GalleryImage(uri, name));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return images;
    }
    @SuppressWarnings("deprecation")
    private static Bitmap loadThumbnails(ContentResolver resolver,Uri imageUri) throws Exception{
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            return resolver.loadThumbnail(
              imageUri,
              new Size(THUMBNAIL_SIZE,THUMBNAIL_SIZE), null
            );
        }

        long imageId = ContentUris.parseId(imageUri);

        return MediaStore.Images.Thumbnails.getThumbnail(
                resolver,imageId,MediaStore.Images.Thumbnails.MINI_KIND,null
        );
    }
}
