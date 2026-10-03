package com.example.myapplication.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Records the app's composited display bitmaps into an H.264 MP4.
 *
 * Uses a Surface input: bitmaps are drawn onto a Surface owned by the encoder,
 * so there is no YUV conversion and no manual timestamp bookkeeping (frames get
 * wall-clock timestamps when posted). The muxer is finalised in stop(), which is
 * what makes the file playable.
 *
 * THREADING: not thread-safe by design. Every call (start/queueFrame/stop) must
 * happen on one single thread -- use MainActivity's videoExecutor.
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";
    private static final long TIMEOUT_US = 10_000;
    private static final int KEY_FRAME_RATE = 30;
    private static final int BIT_RATE = 6_000_000;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private Surface inputSurface;
    private boolean muxerStarted;
    private int trackIndex = -1;
    private File outputFile;
    private int width, height;

    /** Arms the encoder. Must complete before the first queueFrame() call. */
    public void start(File file, int w, int h) throws IOException {
        if (isRunning()) throw new IllegalStateException("Recorder already running");

        width = w & ~1;                 // H.264 needs even dimensions
        height = h & ~1;
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Bad size " + w + "x" + h);
        outputFile = file;

        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, KEY_FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        codec.start();
        muxer = new MediaMuxer(file.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** Encodes one frame. Bitmap is only read, never retained. */
    public void queueFrame(Bitmap bmp) {
        if (!isRunning() || bmp == null || bmp.isRecycled()) return;
        try {
            Canvas canvas = inputSurface.lockCanvas(null);
            if (canvas == null) return;
            if (bmp.getWidth() == width && bmp.getHeight() == height) {
                canvas.drawBitmap(bmp, 0, 0, null);
            } else {
                canvas.drawBitmap(bmp, null,
                        new android.graphics.Rect(0, 0, width, height), null);
            }
            inputSurface.unlockCanvasAndPost(canvas);
            drain(false);
        } catch (Exception e) {
            Log.e(TAG, "queueFrame failed", e);
        }
    }

    /**
     * Collects compressed packets. Non-blocking while recording (timeout 0) so we
     * never stall the frame producer; blocking with a deadline on stop() so every
     * remaining packet is written before the file is closed.
     */
    private void drain(boolean eos) {
        if (codec == null) return;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadlineNanos = System.nanoTime() + TIMEOUT_US * 1000L;

        while (true) {
            long timeoutUs = eos ? Math.max(0L, (deadlineNanos - System.nanoTime()) / 1000L) : 0L;
            int index = codec.dequeueOutputBuffer(info, timeoutUs);

            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || System.nanoTime() >= deadlineNanos) break;
                continue;                       // EOS pending -- keep waiting for it
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) continue;     // format must only change once
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (index < 0) continue;           // INFO_OUTPUT_BUFFERS_CHANGED etc.

            ByteBuffer buffer = codec.getOutputBuffer(index);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                info.size = 0;                 // SPS/PPS go in via the track format
            }
            if (muxerStarted && info.size > 0 && buffer != null) {
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                muxer.writeSampleData(trackIndex, buffer, info);
            }
            codec.releaseOutputBuffer(index, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    /**
     * Flushes, drains everything and closes the muxer. After this the file is
     * playable. Returns the output file (or null if nothing was recorded).
     */
    public File stop() {
        File result = outputFile;
        try {
            if (codec != null) {
                codec.signalEndOfInputStream();   // EOS for a surface input
                drain(true);
                inputSurface.release();
                inputSurface = null;
                codec.stop();
                codec.release();
            }
            if (muxer != null) {
                muxer.stop();                      // writes the MP4 trailer -- required
                muxer.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "stop() failed -- file may be unplayable", e);
        } finally {
            codec = null;
            muxer = null;
            muxerStarted = false;
            trackIndex = -1;
            outputFile = null;
        }
        return result;
    }

    public boolean isRunning() {
        return codec != null;
    }
}
