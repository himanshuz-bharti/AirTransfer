package com.example.myapplication.Gesture;

import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * Converts noisy per-frame poses into one event for each stable pose change.
 * NONE observations while a hand is present are treated as uncertainty, not
 * as proof that the hand disappeared.
 */
public class GestureDebouncer {

    private static final String TAG = "GestureDebouncer";

    private static final long VOTE_WINDOW_MS = 1400L;
    private static final long MIN_CANDIDATE_SPAN_MS = 250L;
    private static final long SHORT_HAND_GAP_RESET_MS = 300L;
    private static final long NO_HAND_REBASELINE_MS = 1000L;
    private static final int MIN_VALID_VOTES = 4;
    private static final float REQUIRED_VOTE_RATIO = 0.70f;

    private static final class PoseSample {
        final GestureType pose;
        final long timestampMs;

        PoseSample(GestureType pose, long timestampMs) {
            this.pose = pose;
            this.timestampMs = timestampMs;
        }
    }

    private final ArrayDeque<PoseSample> samples = new ArrayDeque<>();

    private GestureType stablePose = GestureType.NONE;
    private long noHandSinceMs;
    private boolean stablePoseClearedForNoHand;

    /**
     * @param observedPose OPEN, CLOSED, or NONE when the pose is uncertain
     * @param handDetected true when MediaPipe returned hand landmarks
     * @param timestampMs monotonic timestamp, such as elapsedRealtime()
     */
    public synchronized GestureEvent update(
            GestureType observedPose,
            boolean handDetected,
            long timestampMs
    ) {
        long nowMs = timestampMs > 0L
                ? timestampMs
                : SystemClock.elapsedRealtime();

        if (observedPose == null) {
            observedPose = GestureType.NONE;
        }

        if (!handDetected) {
            onHandMissing(nowMs);
            return null;
        }

        // A hand is present, even if its pose is currently uncertain. Only
        // actual absence can clear the established baseline.
        noHandSinceMs = 0L;
        stablePoseClearedForNoHand = false;
        pruneOldSamples(nowMs);

        if (observedPose == GestureType.NONE) {
            return null;
        }

        samples.addLast(new PoseSample(observedPose, nowMs));
        pruneOldSamples(nowMs);

        GestureType votedPose = getWinningPose();
        if (votedPose == GestureType.NONE) {
            return null;
        }

        int totalVotes = samples.size();
        int winningVotes = countVotes(votedPose);
        float winningRatio = totalVotes == 0
                ? 0f
                : (float) winningVotes / totalVotes;
        long candidateSpanMs = getPoseSpanMs(votedPose);

        if (totalVotes < MIN_VALID_VOTES
                || winningRatio < REQUIRED_VOTE_RATIO
                || candidateSpanMs < MIN_CANDIDATE_SPAN_MS) {
            return null;
        }

        Log.d(TAG, "Stable vote: pose=" + votedPose
                + "; votes=" + winningVotes + "/" + totalVotes
                + "; ratio=" + winningRatio
                + "; span=" + candidateSpanMs + "ms");

        if (stablePose == GestureType.NONE) {
            stablePose = votedPose;
            samples.clear();
            Log.i(TAG, "Initial stable pose set to " + stablePose
                    + "; no transition event emitted");
            return null;
        }

        if (votedPose == stablePose) {
            // Discard this confirmation window so old votes cannot bias the
            // next opposite-pose transition.
            samples.clear();
            return null;
        }

        GestureType previousPose = stablePose;
        stablePose = votedPose;
        samples.clear();

        if (previousPose == GestureType.OPEN && stablePose == GestureType.CLOSED) {
            Log.i(TAG, "Stable transition OPEN -> CLOSED; emitting GRAB");
            return new GestureEvent(GestureEvent.Kind.GRAB);
        }

        if (previousPose == GestureType.CLOSED && stablePose == GestureType.OPEN) {
            Log.i(TAG, "Stable transition CLOSED -> OPEN; emitting RELEASE");
            return new GestureEvent(GestureEvent.Kind.RELEASE);
        }

        return null;
    }

    private void onHandMissing(long nowMs) {
        if (noHandSinceMs == 0L) {
            noHandSinceMs = nowMs;
        }

        long absentForMs = nowMs - noHandSinceMs;
        if (absentForMs >= SHORT_HAND_GAP_RESET_MS) {
            samples.clear();
        }

        if (!stablePoseClearedForNoHand
                && stablePose != GestureType.NONE
                && absentForMs >= NO_HAND_REBASELINE_MS) {
            Log.i(TAG, "Hand absent for " + absentForMs
                    + "ms; clearing stable pose " + stablePose);
            stablePose = GestureType.NONE;
            stablePoseClearedForNoHand = true;
            samples.clear();
        }
    }

    private void pruneOldSamples(long nowMs) {
        while (!samples.isEmpty()
                && nowMs - samples.peekFirst().timestampMs > VOTE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    private GestureType getWinningPose() {
        int openVotes = countVotes(GestureType.OPEN);
        int closedVotes = countVotes(GestureType.CLOSED);

        if (openVotes == 0 && closedVotes == 0) {
            return GestureType.NONE;
        }
        return openVotes >= closedVotes
                ? GestureType.OPEN
                : GestureType.CLOSED;
    }

    private int countVotes(GestureType pose) {
        int count = 0;
        for (PoseSample sample : samples) {
            if (sample.pose == pose) {
                count++;
            }
        }
        return count;
    }

    private long getPoseSpanMs(GestureType pose) {
        long firstTimestamp = -1L;
        long lastTimestamp = -1L;

        for (PoseSample sample : samples) {
            if (sample.pose == pose) {
                if (firstTimestamp < 0L) {
                    firstTimestamp = sample.timestampMs;
                }
                lastTimestamp = sample.timestampMs;
            }
        }

        if (firstTimestamp < 0L || lastTimestamp < 0L) {
            return 0L;
        }
        return lastTimestamp - firstTimestamp;
    }
}
