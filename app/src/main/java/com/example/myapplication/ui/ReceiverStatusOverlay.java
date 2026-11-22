package com.example.myapplication.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;

/** A receiver-only radar cue, distinct from the sender's full-screen border. */
public final class ReceiverStatusOverlay {
    private static final String TAG = "ReceiverStatusOverlay";

    public enum State {
        STARTING,
        READY,
        RECEIVING
    }

    private final Context context;
    private final WindowManager windowManager;
    private RadarView view;
    private boolean attached;
    private long generation;

    public ReceiverStatusOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager)
                this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(State state) {
        generation++;
        if (windowManager == null || !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show receiver indicator: overlay permission unavailable");
            return;
        }
        if (view == null) {
            view = new RadarView(context);
        }
        if (!attached) {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                windowManager.addView(view, params);
                attached = true;
            } catch (RuntimeException error) {
                view = null;
                Log.e(TAG, "Could not attach receiver indicator", error);
                return;
            }
        }
        view.setState(state);
    }

    public void finish(boolean success) {
        if (!attached || view == null) {
            return;
        }
        long finishGeneration = ++generation;
        RadarView finishingView = view;
        finishingView.finish(success, () -> {
            if (view == finishingView && generation == finishGeneration) {
                remove();
            }
        });
    }

    public void remove() {
        generation++;
        if (view != null) {
            view.stopAnimation();
        }
        if (attached && view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not remove receiver indicator", error);
            }
        }
        view = null;
        attached = false;
    }

    private static final class RadarView extends View {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arcBounds = new RectF();
        private ValueAnimator animator;
        private float pulse;
        private float alpha = 1f;
        private int color = Color.rgb(73, 224, 183);
        private String label = "STARTING RECEIVER";

        RadarView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            ringPaint.setShadowLayer(dp(10), 0, 0, color);
            centerPaint.setStyle(Paint.Style.FILL);
            centerPaint.setShadowLayer(dp(18), 0, 0, color);
            iconPaint.setColor(Color.WHITE);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(3));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            badgePaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(dp(13));
            textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            textPaint.setTextAlign(Paint.Align.CENTER);
            startPulse();
        }

        void setState(State state) {
            switch (state) {
                case STARTING:
                    color = Color.rgb(73, 196, 255);
                    label = "STARTING RECEIVER";
                    break;
                case READY:
                    color = Color.rgb(73, 224, 183);
                    label = "RECEIVER READY  ·  WAITING FOR SENDER";
                    break;
                case RECEIVING:
                    color = Color.rgb(255, 184, 76);
                    label = "SENDER CONNECTED  ·  RECEIVING";
                    break;
            }
            alpha = 1f;
            startPulse();
            invalidate();
        }

        void finish(boolean success, Runnable onFinished) {
            stopAnimation();
            color = success ? Color.rgb(73, 235, 145) : Color.rgb(255, 91, 91);
            label = success ? "IMAGE RECEIVED" : "RECEIVER STOPPED";
            animator = ValueAnimator.ofFloat(1f, 0f);
            animator.setDuration(1100L);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(valueAnimator -> {
                alpha = (float) valueAnimator.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    onFinished.run();
                }
            });
            animator.start();
        }

        private void startPulse() {
            stopAnimation();
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1800L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(valueAnimator -> {
                pulse = (float) valueAnimator.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void stopAnimation() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float baseRadius = dp(48);
            ringPaint.setColor(Color.argb((int) (160 * alpha),
                    Color.red(color), Color.green(color), Color.blue(color)));
            for (int i = 0; i < 3; i++) {
                float phase = (pulse + i / 3f) % 1f;
                float radius = baseRadius + phase * dp(44);
                ringPaint.setAlpha((int) (alpha * 185 * (1f - phase)));
                canvas.drawCircle(cx, cy - dp(20), radius, ringPaint);
            }

            centerPaint.setColor(Color.argb((int) (230 * alpha),
                    Color.red(color), Color.green(color), Color.blue(color)));
            canvas.drawCircle(cx, cy - dp(20), baseRadius, centerPaint);
            drawReceiverIcon(canvas, cx, cy - dp(20));
            drawLabel(canvas, cx, cy + dp(95));
        }

        private void drawReceiverIcon(Canvas canvas, float cx, float cy) {
            float radius = dp(22);
            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            iconPaint.setAlpha((int) (255 * alpha));
            canvas.drawArc(arcBounds, 210f, 120f, false, iconPaint);
            canvas.drawArc(arcBounds, 30f, 120f, false, iconPaint);
            canvas.drawLine(cx, cy - dp(12), cx, cy + dp(3), iconPaint);
            canvas.drawLine(cx - dp(8), cy - dp(4), cx, cy + dp(4), iconPaint);
            canvas.drawLine(cx + dp(8), cy - dp(4), cx, cy + dp(4), iconPaint);
            canvas.drawLine(cx - dp(8), cy + dp(11), cx + dp(8), cy + dp(11), iconPaint);
        }

        private void drawLabel(Canvas canvas, float cx, float y) {
            float horizontalPadding = dp(20);
            float labelWidth = textPaint.measureText(label) + horizontalPadding * 2;
            float height = dp(42);
            float left = Math.max(dp(12), cx - labelWidth / 2f);
            float right = Math.min(getWidth() - dp(12), cx + labelWidth / 2f);
            float top = y - height / 2f;
            badgePaint.setColor(Color.argb((int) (225 * alpha), 10, 24, 33));
            canvas.drawRoundRect(left, top, right, top + height,
                    height / 2, height / 2, badgePaint);
            badgePaint.setColor(Color.argb((int) (235 * alpha),
                    Color.red(color), Color.green(color), Color.blue(color)));
            badgePaint.setStyle(Paint.Style.STROKE);
            badgePaint.setStrokeWidth(dp(1.5f));
            canvas.drawRoundRect(left, top, right, top + height,
                    height / 2, height / 2, badgePaint);
            badgePaint.setStyle(Paint.Style.FILL);
            textPaint.setAlpha((int) (255 * alpha));
            Paint.FontMetrics metrics = textPaint.getFontMetrics();
            float baseline = top + (height - metrics.ascent - metrics.descent) / 2f;
            canvas.drawText(label, cx, baseline, textPaint);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
