package com.example.myapplication.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
public class TransferEffectOverlay {

    private static final String TAG = "TransferEffectOverlay";
    public enum Phase{
        CAPTURED,
        MATCHED,
        CONNECTING,
        SENDING
    }

    private static final class BorderView extends View{
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sparkGlow = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF bounds = new RectF();
        private final Path border = new Path();
        private final PathMeasure pathMeasure = new PathMeasure();

        private ValueAnimator animator;
        private float progress;
        private float alpha = 1f;
        private int color = Color.CYAN;

        BorderView(Context context){
            super(context);

            setLayerType(View.LAYER_TYPE_SOFTWARE,null);

            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeWidth(dp(18));
            glow.setColor(Color.CYAN);
            glow.setShadowLayer(dp(20),0,0,Color.CYAN);

            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(dp(3));
            edge.setColor(Color.WHITE);

            spark.setColor(Color.WHITE);
            sparkGlow.setColor(Color.CYAN);
            sparkGlow.setShadowLayer(dp(14), 0, 0, Color.CYAN);
        }

        void setPhase(Phase phase){
            switch(phase){
                case CAPTURED:
                    color = Color.rgb(70, 220, 255);
                    break;
                case MATCHED:
                    color = Color.rgb(130, 100, 255);
                    break;
                case CONNECTING:
                    color = Color.rgb(40, 225, 190);
                    break;
                case SENDING:
                    color = Color.rgb(255, 180, 55);
                    break;
            }

            alpha = 1f;
            startLoop();
        }

        private void startLoop(){
            stopAnimation();
            animator = ValueAnimator.ofFloat(0f,1f);
            animator.setDuration(1400);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(a->{
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void finish(boolean success,Runnable removeOverlay){
            stopAnimation();
            color =  success ? Color.rgb(60, 255, 145) :
                    Color.rgb(255, 85, 85);


            animator = ValueAnimator.ofFloat(1f,0f);
            animator.setDuration(900);
            animator.setInterpolator(new LinearInterpolator());

            animator.addUpdateListener(a->{
                alpha = (float) a.getAnimatedValue();
                progress = 1f-alpha;
                invalidate();
            });

            animator.addListener(new android.animation.AnimatorListenerAdapter(){
                @Override
                public void onAnimationEnd(android.animation.Animator animation){
                    removeOverlay.run();
                }
            });

            animator.start();

        }

        private void stopAnimation(){
            if(animator != null){
                animator.cancel();
                animator = null;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float inset = dp(20);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            border.reset();
            border.addRoundRect(bounds, dp(30), dp(30), Path.Direction.CW);
            pathMeasure.setPath(border, false);

            int glowAlpha = (int) (115 * alpha);
            glow.setColor(Color.argb(glowAlpha,
                    Color.red(color), Color.green(color), Color.blue(color)));
            glow.setShadowLayer(dp(20), 0, 0, color);

            edge.setColor(Color.argb((int) (230 * alpha), 255, 255, 255));
            sparkGlow.setColor(Color.argb((int) (230 * alpha),
                    Color.red(color), Color.green(color), Color.blue(color)));
            sparkGlow.setShadowLayer(dp(14), 0, 0, color);

            canvas.drawPath(border, glow);
            canvas.drawPath(border, edge);

            float[] point = new float[2];
            float length = pathMeasure.getLength();
            if (length > 0
                    && pathMeasure.getPosTan(progress * length, point, null)) {
                canvas.drawCircle(point[0], point[1], dp(10), sparkGlow);
                canvas.drawCircle(point[0], point[1], dp(4), spark);
            }
        }

        private float dp(float value){
            return value*getResources().getDisplayMetrics().density;
        }
    }

    private final Context context;
    private final WindowManager windowManager;
    private BorderView view;
    private boolean attached;
    private long animationGeneration;

    public TransferEffectOverlay(Context context){
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show(Phase phase){
        animationGeneration++;
        if(windowManager == null || !Settings.canDrawOverlays(context)){
            Log.w(TAG,"windowmanager is null or overlay permission is not given");
            return;
        }
        if(view == null){
            view = new BorderView(context);
        }
        if(!attached){
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = Gravity.TOP | Gravity.START;
            try{
                windowManager.addView(view,params);
                attached = true;
            } catch(RuntimeException error){
                view = null;
                Log.e(TAG,"Error attaching view overlay" + error);
                return;
            }
        }

        view.setPhase(phase);
    }

    public void finish(boolean success){
        if(!attached || view == null){
            return;
        }
        long finishGeneration = ++animationGeneration;
        BorderView finishingView = view;
        view.finish(success, () -> {
            if (view == finishingView && animationGeneration == finishGeneration) {
                remove();
            }
        });
    }

    public void remove(){
        animationGeneration++;
        if(view!=null){
            view.stopAnimation();
        }

        if(attached && view!=null && windowManager!=null){
            try{
                windowManager.removeView(view);
            } catch(RuntimeException e){

            }
        }

        view = null;
        attached = false;
    }

    public void removeImmediately(){
        if(view!=null){
            view.stopAnimation();
        }

        if(attached && view!=null && windowManager!=null){
            try{
                windowManager.removeViewImmediate(view);
            } catch(RuntimeException e){

            }
        }

        view = null;
        attached = false;
    }


}
