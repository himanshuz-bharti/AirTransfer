package com.example.myapplication.vision;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.example.myapplication.Gesture.GestureType;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.processors.ClassifierOptions;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.util.Arrays;
import java.util.List;

/**
 * Runs MediaPipe's built-in hand gesture classifier and reports a pose
 * observation separately from whether a hand was detected.
 */
public final class HandGestureClassifier {

    private static final String TAG = "HandGestureClassifier";

    // The recognizer returns scores; the app applies its own acceptance rule
    // so short classifier gaps can be smoothed by GestureDebouncer.
    private static final float MIN_POSE_SCORE = 0.55f;
    private static final float MIN_SCORE_MARGIN = 0.12f;

    public interface Listener {
        void onObservation(
                GestureType pose,
                boolean handDetected,
                float openScore,
                float closedScore
        );

        void onError(RuntimeException error);
    }

    private final GestureRecognizer mediaPipeRecognizer;
    private final Listener listener;

    private String lastLoggedState = "";
    private long lastScoreLogMs;

    public HandGestureClassifier(Context context, Listener listener) {
        this.listener = listener;

        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build();

        // Return both categories even when their scores are low. We distinguish
        // low-confidence pose from no detected hand using result.landmarks().
        ClassifierOptions gestureOptions = ClassifierOptions.builder()
                .setCategoryAllowlist(Arrays.asList("Open_Palm", "Closed_Fist"))
                .setScoreThreshold(0.0f)
                .setMaxResults(2)
                .build();

        GestureRecognizer.GestureRecognizerOptions options =
                GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setCannedGesturesClassifierOptions(gestureOptions)
                        .setResultListener(this::onResult)
                        .setErrorListener(this::onError)
                        .build();

        mediaPipeRecognizer = GestureRecognizer.createFromOptions(context, options);
    }

    public void detectAsync(Bitmap bitmap, long timestampMs) {
        if (bitmap == null) {
            return;
        }

        MPImage mpImage = new BitmapImageBuilder(bitmap).build();
        mediaPipeRecognizer.recognizeAsync(mpImage, timestampMs);
    }

    private void onResult(GestureRecognizerResult result, MPImage inputImage) {
        boolean handDetected = result.landmarks() != null
                && !result.landmarks().isEmpty();

        float openScore = 0f;
        float closedScore = 0f;

        List<List<Category>> gestureResults = result.gestures();
        if (handDetected
                && gestureResults != null
                && !gestureResults.isEmpty()
                && gestureResults.get(0) != null) {
            for (Category category : gestureResults.get(0)) {
                if ("Open_Palm".equals(category.categoryName())) {
                    openScore = category.score();
                } else if ("Closed_Fist".equals(category.categoryName())) {
                    closedScore = category.score();
                }
            }
        }

        GestureType pose = GestureType.NONE;
        float bestScore = Math.max(openScore, closedScore);
        float scoreMargin = Math.abs(openScore - closedScore);

        if (handDetected
                && bestScore >= MIN_POSE_SCORE
                && scoreMargin >= MIN_SCORE_MARGIN) {
            pose = openScore > closedScore
                    ? GestureType.OPEN
                    : GestureType.CLOSED;
        }

        String logState = "hand=" + handDetected + ",pose=" + pose;
        long nowMs = SystemClock.elapsedRealtime();
        if (!logState.equals(lastLoggedState) || nowMs - lastScoreLogMs >= 500L) {
            Log.d(TAG, "Observation: handDetected=" + handDetected
                    + "; pose=" + pose
                    + "; openScore=" + openScore
                    + "; closedScore=" + closedScore
                    + "; margin=" + scoreMargin);
            lastLoggedState = logState;
            lastScoreLogMs = nowMs;
        }

        listener.onObservation(pose, handDetected, openScore, closedScore);
    }

    private void onError(RuntimeException error) {
        Log.e(TAG, "Gesture Recognizer error", error);
        listener.onError(error);
    }

    public void cleanup() {
        mediaPipeRecognizer.close();
    }
}
