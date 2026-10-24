#include <jni.h>
#include <android/bitmap.h>
#include<android/log.h>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/calib3d.hpp>

#include <cmath>
#include <algorithm>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <string>


namespace{
    const int MAX_IMAGE_SIDE = 1280;
    const int ORB_FEATURE_COUNT = 2500;

    constexpr float MATCH_RATIO_LIMIT = 0.78f;
    constexpr float MAX_HAMMING_DISTANCE = 96.0f;

    constexpr int MIN_MATCHES_FOR_HOMOGRAPHY = 8;
    constexpr int MIN_VERIFIED_INLIERS = 8;
    constexpr double MIN_INLIER_RATIO = 0.25;

// Require verified points to cover some area in both images.
// This reduces false matches based on one small repeated pattern.
    constexpr double MIN_CANDIDATE_COVERAGE = 0.010;
    constexpr double MIN_SCREEN_COVERAGE = 0.0015;

    struct FeatureSet{
        cv::Size imageSize;
        std::vector<cv::KeyPoint> keypoint;
        cv::Mat descriptors;
    };

    class BitmapPixelLock{
        private:
            JNIEnv* env_;
            jobject bitmap_;
            bool locked = false;

        public:
             BitmapPixelLock(JNIEnv* env_,jobject bitmap_)
                 :env_(env_),bitmap_(bitmap_){

             }
             BitmapPixelLock(const BitmapPixelLock&) = delete;
             BitmapPixelLock& operator=(const BitmapPixelLock&) = delete;

            ~BitmapPixelLock(){
                unlock();
            }
            bool lock(void** pixel){
                const int res = AndroidBitmap_lockPixels(env_,bitmap_,pixel);
                locked = (res == ANDROID_BITMAP_RESULT_SUCCESS);
                return locked;
            }

            void unlock(){
                if(locked){
                    AndroidBitmap_unlockPixels(env_,bitmap_);
                    locked=false;
                }
            }
    };

    std::vector<int> ratioFilter(
            const std::vector<std::vector<cv::DMatch>>& neighbors,
            int queryDescriptorCount) {

        std::vector<int> accepted(queryDescriptorCount, -1);

        for (const auto& pair : neighbors) {
            // Lowe's ratio test needs the best and second-best match.
            if (pair.size() < 2) {
                continue;
            }

            const cv::DMatch& best = pair[0];
            const cv::DMatch& second = pair[1];

            if (best.queryIdx < 0 ||
                best.queryIdx >= queryDescriptorCount ||
                best.trainIdx < 0) {
                continue;
            }

            if (best.distance > MAX_HAMMING_DISTANCE) {
                continue;
            }

            if (best.distance >= MATCH_RATIO_LIMIT * second.distance) {
                continue;
            }

            accepted[best.queryIdx] = best.trainIdx;
        }

        return accepted;
    }

    double inlierHullCoverage(
            const std::vector<cv::Point2f>& points,
            const cv::Size& imageSize) {

        if (points.size() < 3 || imageSize.width <= 0 || imageSize.height <= 0) {
            return 0.0;
        }

        std::vector<cv::Point2f> hull;
        cv::convexHull(points, hull);

        if (hull.size() < 3) {
            return 0.0;
        }

        const double hullArea = std::abs(cv::contourArea(hull));
        const double imageArea =
                static_cast<double>(imageSize.width) * imageSize.height;

        return imageArea > 0.0 ? hullArea / imageArea : 0.0;
    }

    void resizeToMaximumSize(cv::Mat& image){
        const int longestSide = std::max(image.cols,image.rows);
        if(longestSide<=MAX_IMAGE_SIDE){
            return;
        }

        const double scale = static_cast<double>(MAX_IMAGE_SIDE) / longestSide;
        cv::Mat resized;

        cv::resize(image,resized,cv::Size(),scale,scale,cv::INTER_AREA);
        image = std::move(resized);
    }

    jintArray makeComparisonResult(
            JNIEnv* env,
            jint mutualmatches,
            jint inliers
            ){
        jintArray result = env->NewIntArray(2);
        if(result == nullptr){
            return nullptr;
        }

        const jint res[2] = {mutualmatches,inliers};
        env->SetIntArrayRegion(result,0,2,res);
        return result;
    }

    std::unique_ptr<FeatureSet> createFeatureSet(JNIEnv* env,jobject bitmap){
        if(bitmap == nullptr){
            return nullptr;
        }

        AndroidBitmapInfo info{};

        const int infoResult = AndroidBitmap_getInfo(env,bitmap,&info);

        if(infoResult != ANDROID_BITMAP_RESULT_SUCCESS){
            return nullptr;
        }

        if(info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width ==0 ||
            info.height==0 || info.stride<info.width*4U){
            return nullptr;
        }

        void* pixel = nullptr;
        BitmapPixelLock pixelLock(env,bitmap);
        if(!pixelLock.lock(&pixel) || pixel == nullptr){
            return nullptr;
        }

        cv::Mat rgba(static_cast<int>(info.height),static_cast<int>(info.width),
                     CV_8UC4,pixel,info.stride);

        cv::Mat grayScale;
        cv::cvtColor(rgba,grayScale,cv::COLOR_RGBA2GRAY);

        pixelLock.unlock();

        resizeToMaximumSize(grayScale);

        if(grayScale.empty() || grayScale.cols<32 || grayScale.rows<32){
            return nullptr;
        }

        cv::Mat enhanced;
        cv::Ptr<cv::CLAHE> clahe = cv::createCLAHE(
                2.0,
                cv::Size(8,8)
                );

        clahe->apply(grayScale,enhanced);

        auto features = std::make_unique<FeatureSet>();
        features->imageSize = enhanced.size();

        cv::Ptr<cv::ORB> orb = cv::ORB::create(
                ORB_FEATURE_COUNT,
                1.2f,
                8,
                31,
                0,
                2,
                cv::ORB::HARRIS_SCORE,
                31,
                12
                );

        orb->detectAndCompute(enhanced,cv::noArray(),features->keypoint,features->descriptors,false);

        if(features->descriptors.empty() || features->keypoint.size()<8){
            return nullptr;
        }
        return features;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_myapplication_Transfer_NativeOpenCvBridge_runNativeCheck(
        JNIEnv* env,
        jclass /* clazz */
) {
    try {
        // This invokes OpenCV itself, not just code compiled into this JNI
        // library. A successful result confirms headers, linking, and loading.
        cv::Mat sample(4, 4, CV_8UC1, cv::Scalar(17));
        double meanValue = cv::mean(sample)[0];

        std::string message = "OpenCV ";
        message += CV_VERSION;
        message += " loaded; test mean=";
        message += std::to_string(meanValue);

        return env->NewStringUTF(message.c_str());
    } catch (const cv::Exception& error) {
        return env->NewStringUTF(error.what());
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_myapplication_Transfer_NativeOpenCvBridge_nativeCreateFeatures(
        JNIEnv* env,
        jclass,
        jobject bitmap){
    try {
        std::unique_ptr<FeatureSet> featureSet = createFeatureSet(env,bitmap);
        if(featureSet == nullptr){
            return 0;
        }

        __android_log_print(
                ANDROID_LOG_INFO,
                "GalleryMatcherJNI",
                "Extracted %zu keypoints; descriptors=%d; image=%dx%d",
                featureSet->keypoint.size(),
                featureSet->descriptors.rows,
                featureSet->imageSize.width,
                featureSet->imageSize.height
        );

        const FeatureSet* rawFeatures = featureSet.release();
        return static_cast<jlong>(
                reinterpret_cast<intptr_t>(rawFeatures)
                );
    } catch(const cv::Exception&){
        return 0;
    } catch(...){
        return 0;
    }
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_example_myapplication_Transfer_NativeOpenCvBridge_nativeCompareFeatures(
        JNIEnv* env,
        jclass,
        jlong candidateFeatureHandle,
        jlong screenshotFeatureHandle
){
    if(candidateFeatureHandle == 0 || screenshotFeatureHandle == 0){
        return makeComparisonResult(env,0,0);
    }

    auto* candidate = reinterpret_cast<FeatureSet*>(
            static_cast<intptr_t>(candidateFeatureHandle));

    auto* screenshot = reinterpret_cast<FeatureSet*>(
            static_cast<intptr_t>(screenshotFeatureHandle));

    try{
        if(candidate->descriptors.empty() || screenshot->descriptors.empty()){
            return makeComparisonResult(env,0,0);
        }

        cv::BFMatcher matcher(cv::NORM_HAMMING,false);

        std::vector<std::vector<cv::DMatch>> CandidateToScreen;
        std::vector<std::vector<cv::DMatch>> ScreenToCandidate;

        matcher.knnMatch(candidate->descriptors,screenshot->descriptors,CandidateToScreen,2);

        matcher.knnMatch(screenshot->descriptors,candidate->descriptors,ScreenToCandidate,2);

        const std::vector<int> forward = ratioFilter(CandidateToScreen,candidate->descriptors.rows);

        const std::vector<int> backward = ratioFilter(ScreenToCandidate,screenshot->descriptors.rows);

        std::vector<cv::DMatch> mutualMatches;
        mutualMatches.reserve(forward.size());

        for(int can = 0; can< static_cast<int> (forward.size());can++){
            int screenIdx = forward[can];

            if(screenIdx<0 || screenIdx>=static_cast<int> (backward.size())){
                continue;
            }

            if(backward[screenIdx] != can){
                continue;
            }

            mutualMatches.emplace_back(can,screenIdx,0.0f);
        }

        const jint mutualmatchCount = static_cast<int> (mutualMatches.size());
        if(mutualmatchCount<MIN_MATCHES_FOR_HOMOGRAPHY){
            return makeComparisonResult(env,mutualmatchCount,0);
        }
        std::vector<cv::Point2f> candidatePoints;
        std::vector<cv::Point2f> screenPoints;
        candidatePoints.reserve(mutualMatches.size());
        screenPoints.reserve(mutualMatches.size());

        for (const cv::DMatch& match : mutualMatches) {
            candidatePoints.push_back(
                    candidate->keypoint[match.queryIdx].pt
            );
            screenPoints.push_back(
                    screenshot->keypoint[match.trainIdx].pt
            );
        }

        cv::Mat inlierMask;

        // Estimate the transform from gallery-image coordinates to
        // screenshot coordinates. MAGSAC is robust to outlier matches.
        cv::Mat homography = cv::findHomography(
                candidatePoints,
                screenPoints,
                cv::USAC_MAGSAC,
                3.5,       // reprojection error threshold in resized pixels
                inlierMask,
                3000,      // maximum robust-estimation iterations
                0.995      // estimation confidence
        );

        if (homography.empty() ||
            inlierMask.empty() ||
            inlierMask.total() != candidatePoints.size()) {
            return makeComparisonResult(env, mutualmatchCount, 0);
        }

        std::vector<cv::Point2f> candidateInliers;
        std::vector<cv::Point2f> screenInliers;
        candidateInliers.reserve(candidatePoints.size());
        screenInliers.reserve(screenPoints.size());

        for (int i = 0; i < static_cast<int>(candidatePoints.size()); ++i) {
            if (inlierMask.at<unsigned char>(i, 0) != 0) {
                candidateInliers.push_back(candidatePoints[i]);
                screenInliers.push_back(screenPoints[i]);
            }
        }

        const int inlierCount = static_cast<int>(candidateInliers.size());
        if (inlierCount < MIN_VERIFIED_INLIERS) {
            return makeComparisonResult(env, mutualmatchCount, 0);
        }

        const double inlierRatio =
                static_cast<double>(inlierCount) / candidatePoints.size();

        if (inlierRatio < MIN_INLIER_RATIO) {
            return makeComparisonResult(env, mutualmatchCount, 0);
        }

        const double candidateCoverage = inlierHullCoverage(
                candidateInliers,
                candidate->imageSize
        );

        const double screenCoverage = inlierHullCoverage(
                screenInliers,
                screenshot->imageSize
        );

        if (candidateCoverage < MIN_CANDIDATE_COVERAGE ||
            screenCoverage < MIN_SCREEN_COVERAGE) {
            return makeComparisonResult(env, mutualmatchCount, 0);
        }

        return makeComparisonResult(env, mutualmatchCount, inlierCount);
    }  catch (const cv::Exception&) {
        return makeComparisonResult(env, 0, 0);
    } catch (...) {
        return makeComparisonResult(env, 0, 0);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_myapplication_Transfer_NativeOpenCvBridge_nativeReleaseFeatures(
        JNIEnv* env,
        jclass,
        jlong featureHandle
        ){
    if(featureHandle == 0){
        return;
    }

    auto* features = reinterpret_cast<FeatureSet*>(
            static_cast<intptr_t>(featureHandle)
            );

    delete features;
}


