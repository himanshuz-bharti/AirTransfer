package com.example.myapplication.vision;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.processors.ClassifierOptions;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;
import com.google.mediapipe.framework.image.MPImage;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Lightweight adapter around MediaPipe's bundled hand and gesture models. */
public final class HandGestureController implements AutoCloseable {
    private static final String TAG = "HandGestureController";

    // The camera is sampled at 5 FPS. Three matching observations within this
    // short window accept a held pose while tolerating an occasional None.
    private static final long SAMPLE_WINDOW_MS = 1000L;
    private static final long MIN_STABLE_SPAN_MS = 250L;
    private static final long HAND_ABSENT_TO_REARM_MS = 600L;
    private static final int REQUIRED_MATCHING_SAMPLES = 3;

    private static final float OPEN_MIN_SCORE = 0.55f;
    private static final float OPEN_SCORE_LEAD = 0.08f;
    private static final float CLOSED_MIN_SCORE = 0.40f;
    private static final float CLOSED_SCORE_LEAD = 0.06f;
    // If a hand is definitely present but the model gives Open_Palm very little
    // support, use the two-pose interaction assumption and treat it as a fist.
    // An uncertain/partially-open palm stays NONE instead of becoming SEND.
    private static final float MAX_OPEN_SCORE_FOR_FIST_FALLBACK = 0.30f;
    private static final float MIN_HAND_DETECTION_CONFIDENCE = 0.30f;
    private static final float MIN_HAND_PRESENCE_CONFIDENCE = 0.30f;
    private static final float MIN_TRACKING_CONFIDENCE = 0.30f;

    public enum Action {
        SEND,
        RECEIVE
    }

    public interface Listener {
        void onObservation(Observation observation);
        void onAction(Action action);
        void onError(RuntimeException error);
    }

    /** One MediaPipe result plus the current temporal vote state. */
    public static final class Observation {
        public final long frameNumber;
        public final boolean handDetected;
        public final String modelWinner;
        public final String qualifiedPose;
        public final float openScore;
        public final float closedScore;
        public final float scoreDelta;
        public final int openVotes;
        public final int closedVotes;
        public final long stableSpanMs;
        public final boolean latched;
        public final String latchedPose;
        public final boolean readyToTrigger;
        public final boolean closedFallback;

        private Observation(
                long frameNumber,
                boolean handDetected,
                String modelWinner,
                String qualifiedPose,
                float openScore,
                float closedScore,
                int openVotes,
                int closedVotes,
                long stableSpanMs,
                boolean latched,
                String latchedPose,
                boolean readyToTrigger,
                boolean closedFallback
        ) {
            this.frameNumber = frameNumber;
            this.handDetected = handDetected;
            this.modelWinner = modelWinner;
            this.qualifiedPose = qualifiedPose;
            this.openScore = openScore;
            this.closedScore = closedScore;
            this.scoreDelta = openScore - closedScore;
            this.openVotes = openVotes;
            this.closedVotes = closedVotes;
            this.stableSpanMs = stableSpanMs;
            this.latched = latched;
            this.latchedPose = latchedPose;
            this.readyToTrigger = readyToTrigger;
            this.closedFallback = closedFallback;
        }

        public String toLogString() {
            return String.format(Locale.US,
                    "frame=%d hand=%s model=%s decision=%s open=%.3f fist=%.3f "
                            + "delta(open-fist)=%.3f votes(open/fist)=%d/%d span=%dms "
                            + "source=%s ready=%s latched=%s(%s) thresholds(open>=%.2f+lead%.2f, "
                            + "fist>=%.2f+lead%.2f, fallbackOpen<=%.2f)",
                    frameNumber, handDetected, modelWinner, qualifiedPose,
                    openScore, closedScore, scoreDelta, openVotes, closedVotes,
                    stableSpanMs, closedFallback ? "NON_OPEN_HAND_FALLBACK" : "MODEL",
                    readyToTrigger, latched, latchedPose,
                    OPEN_MIN_SCORE, OPEN_SCORE_LEAD,
                    CLOSED_MIN_SCORE, CLOSED_SCORE_LEAD,
                    MAX_OPEN_SCORE_FOR_FIST_FALLBACK);
        }

        public String toOverlayText(String serviceState, String operationStatus) {
            return String.format(Locale.US,
                    "AIR GESTURE DEBUG\n"
                            + "Hand: %s   Model: %s\n"
                            + "Decision: %s\n"
                            + "Role: %s\n"
                            + "Status: %s",
                    handDetected ? "DETECTED" : "NOT DETECTED",
                    modelWinner,
                    qualifiedPose,
                    serviceState,
                    operationStatus);
        }
    }

    private enum Pose {
        NONE,
        OPEN,
        CLOSED
    }

    private static final class Sample {
        final Pose pose;
        final long timestampMs;

        Sample(Pose pose, long timestampMs) {
            this.pose = pose;
            this.timestampMs = timestampMs;
        }
    }

    private final GestureRecognizer recognizer;
    private final Listener listener;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    private boolean latched;
    private Pose latchedPose = Pose.NONE;
    private long handAbsentSinceMs;
    private long frameNumber;

    public HandGestureController(Context context, Listener listener) {
        ClassifierOptions classifierOptions = ClassifierOptions.builder()
                .setCategoryAllowlist(Arrays.asList("Open_Palm", "Closed_Fist"))
                .setScoreThreshold(0f)
                .setMaxResults(2)
                .build();

        GestureRecognizer.GestureRecognizerOptions options =
                GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder()
                                .setModelAssetPath("gesture_recognizer.task")
                                .build())
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(MIN_HAND_DETECTION_CONFIDENCE)
                        .setMinHandPresenceConfidence(MIN_HAND_PRESENCE_CONFIDENCE)
                        .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
                        .setCannedGesturesClassifierOptions(classifierOptions)
                        .setResultListener(this::onResult)
                        .setErrorListener(this::onError)
                        .build();

        recognizer = GestureRecognizer.createFromOptions(context, options);
        this.listener = listener;
        Log.i(TAG, "GestureRecognizer ready; one hand; hand thresholds="
                + MIN_HAND_DETECTION_CONFIDENCE + "/"
                + MIN_HAND_PRESENCE_CONFIDENCE + "/"
                + MIN_TRACKING_CONFIDENCE);
    }

    public void recognizeAsync(
            MPImage image,
            ImageProcessingOptions processingOptions,
            long timestampMs
    ) {
        if (image == null) {
            return;
        }
        recognizer.recognizeAsync(image, processingOptions, timestampMs);
    }

    private synchronized void onResult(GestureRecognizerResult result, MPImage inputImage) {
        try {
            processResult(result);
        } finally {
            if (inputImage != null) {
                inputImage.close();
            }
        }
    }

    private void processResult(GestureRecognizerResult result) {
        long nowMs = SystemClock.elapsedRealtime();
        boolean handDetected = result.landmarks() != null && !result.landmarks().isEmpty();
        float openScore = 0f;
        float closedScore = 0f;

        List<List<Category>> gestureLists = result.gestures();
        if (handDetected && gestureLists != null && !gestureLists.isEmpty()
                && gestureLists.get(0) != null) {
            for (Category category : gestureLists.get(0)) {
                if ("Open_Palm".equals(category.categoryName())) {
                    openScore = category.score();
                } else if ("Closed_Fist".equals(category.categoryName())) {
                    closedScore = category.score();
                }
            }
        }

        Pose pose = classify(openScore, closedScore);
        boolean closedFallback = handDetected
                && pose == Pose.NONE
                && openScore <= MAX_OPEN_SCORE_FOR_FIST_FALLBACK;
        if (closedFallback) {
            pose = Pose.CLOSED;
        }
        updateSamples(pose, nowMs);

        if (latched) {
            if (!handDetected) {
                if (handAbsentSinceMs == 0L) {
                    handAbsentSinceMs = nowMs;
                } else if (nowMs - handAbsentSinceMs >= HAND_ABSENT_TO_REARM_MS) {
                    latched = false;
                    latchedPose = Pose.NONE;
                    handAbsentSinceMs = 0L;
                    samples.clear();
                    Log.i(TAG, "Rearmed after hand was out of view");
                }
            } else {
                handAbsentSinceMs = 0L;
                if (hasStableOppositePose(pose)) {
                    Log.i(TAG, "Rearmed on stable opposite pose: "
                            + latchedPose + " -> " + pose);
                    latched = false;
                    latchedPose = Pose.NONE;
                }
            }
            if (latched) {
                publishObservation(pose, handDetected, openScore, closedScore, closedFallback);
                return;
            }
        }

        if (!handDetected) {
            samples.clear();
            publishObservation(Pose.NONE, false, openScore, closedScore, false);
            return;
        }

        int openVotes = count(Pose.OPEN);
        int closedVotes = count(Pose.CLOSED);
        Pose stablePose = pose;
        int stableVotes = stablePose == Pose.OPEN ? openVotes
                : stablePose == Pose.CLOSED ? closedVotes : 0;
        long spanMs = span(stablePose);

        publishObservation(pose, true, openScore, closedScore, closedFallback);

        if (stableVotes < REQUIRED_MATCHING_SAMPLES || spanMs < MIN_STABLE_SPAN_MS) {
            return;
        }

        if (stablePose == Pose.CLOSED && closedVotes > openVotes) {
            latched = true;
            latchedPose = stablePose;
            samples.clear();
            Log.i(TAG, String.format(Locale.US,
                    "ACTION_ACCEPTED action=SEND gesture=Closed_Fist open=%.3f fist=%.3f "
                            + "source=%s votes(open/fist)=%d/%d span=%dms",
                    openScore, closedScore,
                    closedFallback ? "NON_OPEN_HAND_FALLBACK" : "MODEL",
                    openVotes, closedVotes, spanMs));
            listener.onAction(Action.SEND);
        } else if (stablePose == Pose.OPEN && openVotes > closedVotes) {
            latched = true;
            latchedPose = stablePose;
            samples.clear();
            Log.i(TAG, String.format(Locale.US,
                    "ACTION_ACCEPTED action=RECEIVE gesture=Open_Palm open=%.3f fist=%.3f "
                            + "votes(open/fist)=%d/%d span=%dms",
                    openScore, closedScore, openVotes, closedVotes, spanMs));
            listener.onAction(Action.RECEIVE);
        }
    }

    private Pose classify(float openScore, float closedScore) {
        if (openScore >= OPEN_MIN_SCORE && openScore - closedScore >= OPEN_SCORE_LEAD) {
            return Pose.OPEN;
        }
        if (closedScore >= CLOSED_MIN_SCORE
                && closedScore - openScore >= CLOSED_SCORE_LEAD) {
            return Pose.CLOSED;
        }
        return Pose.NONE;
    }

    private void updateSamples(Pose pose, long nowMs) {
        samples.addLast(new Sample(pose, nowMs));
        while (!samples.isEmpty()
                && nowMs - samples.peekFirst().timestampMs > SAMPLE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    private int count(Pose pose) {
        int votes = 0;
        for (Sample sample : samples) {
            if (sample.pose == pose) {
                votes++;
            }
        }
        return votes;
    }

    private long span(Pose pose) {
        long first = -1L;
        long last = -1L;
        for (Sample sample : samples) {
            if (sample.pose == pose) {
                if (first < 0L) {
                    first = sample.timestampMs;
                }
                last = sample.timestampMs;
            }
        }
        return first < 0L || last < 0L ? 0L : last - first;
    }

    /** A stable opposite pose is a new gesture even when the hand stays visible. */
    private boolean hasStableOppositePose(Pose pose) {
        if (!latched || pose == Pose.NONE || pose == latchedPose) {
            return false;
        }

        int poseVotes = count(pose);
        int otherVotes = count(pose == Pose.OPEN ? Pose.CLOSED : Pose.OPEN);
        return poseVotes >= REQUIRED_MATCHING_SAMPLES
                && poseVotes > otherVotes
                && span(pose) >= MIN_STABLE_SPAN_MS;
    }

    private void publishObservation(
            Pose pose,
            boolean handDetected,
            float openScore,
            float closedScore,
            boolean closedFallback
    ) {
        int openVotes = count(Pose.OPEN);
        int closedVotes = count(Pose.CLOSED);
        int votesForPose = pose == Pose.OPEN ? openVotes
                : pose == Pose.CLOSED ? closedVotes : 0;
        int oppositeVotes = pose == Pose.OPEN ? closedVotes
                : pose == Pose.CLOSED ? openVotes : Integer.MAX_VALUE;
        long stableSpanMs = span(pose);
        boolean readyToTrigger = handDetected
                && votesForPose >= REQUIRED_MATCHING_SAMPLES
                && votesForPose > oppositeVotes
                && stableSpanMs >= MIN_STABLE_SPAN_MS
                && (!latched || pose != latchedPose);

        String modelWinner = !handDetected || (openScore == 0f && closedScore == 0f)
                ? "NONE"
                : openScore >= closedScore ? "Open_Palm" : "Closed_Fist";

        Observation observation = new Observation(
                ++frameNumber,
                handDetected,
                modelWinner,
                pose.name(),
                openScore,
                closedScore,
                openVotes,
                closedVotes,
                stableSpanMs,
                latched,
                latched ? latchedPose.name() : Pose.NONE.name(),
                readyToTrigger,
                closedFallback);

        Log.d(TAG, observation.toLogString());
        listener.onObservation(observation);
    }

    /** Block a repeated pose, while allowing the opposite pose or hand absence to re-arm. */
    public synchronized void requireDifferentPoseOrHandRemoval() {
        latched = true;
        handAbsentSinceMs = 0L;
        samples.clear();
        Log.i(TAG, "REARM_REQUIRED: accept a stable opposite pose or 600ms hand absence; "
                + "latchedPose=" + latchedPose);
    }

    private void onError(RuntimeException error) {
        Log.e(TAG, "MediaPipe gesture recognition failed", error);
        listener.onError(error);
    }

    @Override
    public void close() {
        recognizer.close();
    }
}
