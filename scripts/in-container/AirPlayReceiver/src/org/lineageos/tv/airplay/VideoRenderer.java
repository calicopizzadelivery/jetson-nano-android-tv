/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Decodes AirPlay mirroring video onto a Surface and shows each frame at the
 * time the sender asked for.
 *
 * Frames arrive from UxPlay's threads as Annex-B H.264 or H.265 with a
 * presentation time in the System.nanoTime() base. They are queued, never
 * decoded, on those threads.
 *
 * The parameter sets are the awkward part. The sender includes SPS/PPS (and
 * VPS for H.265) only in the first frame of a stream, and there is no way to
 * ask it for another -- but the player window can take half a second to come
 * up after mirroring starts. So until there is a surface, frames are kept from
 * the most recent one carrying parameter sets onwards, and fed to the decoder
 * in order once it exists. A frame that arrives before any parameter sets
 * can never be decoded and is dropped.
 *
 * MediaCodec runs in asynchronous mode on a thread of its own, and releases
 * each output buffer at its presentation time, which is what keeps the
 * picture in step with the audio.
 */
final class VideoRenderer {

    private static final String TAG = "AirPlay";

    /** A few seconds at mirroring rates: plenty for a window to appear. */
    private static final int MAX_PENDING = 300;
    /** Timing is logged once per this many frames shown. */
    private static final int STATS_EVERY = 900;

    interface SizeListener {
        void onVideoSizeChanged(int width, int height);
    }

    private static final class Frame {
        final byte[] data;
        final long ptsNanos;

        Frame(byte[] data, long ptsNanos) {
            this.data = data;
            this.ptsNanos = ptsNanos;
        }
    }

    private final Object lock = new Object();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();

    private HandlerThread thread;
    private Handler handler;
    private Surface surface;
    private MediaCodec codec;
    private String mime;
    private boolean haveParameterSets;
    private SizeListener sizeListener;

    // Render timing, touched only on the codec thread. Lateness is when the
    // frame actually reached the screen against when it was due, which is
    // what decides whether the picture stays in step with the sound.
    private int shown;
    private int shownLate;
    private long latenessSumNs;
    private long latenessMaxNs;

    void setSizeListener(SizeListener listener) {
        synchronized (lock) {
            sizeListener = listener;
        }
    }

    /** Null when the window goes away; frames keep queuing for the next one. */
    void setSurface(Surface newSurface) {
        synchronized (lock) {
            if (newSurface == surface) {
                return;
            }
            releaseCodecLocked();
            surface = newSurface;
            if (surface != null && mime != null) {
                startCodecLocked();
            }
        }
    }

    void setCodec(boolean h265) {
        String wanted = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
        synchronized (lock) {
            if (wanted.equals(mime)) {
                return;
            }
            releaseCodecLocked();
            mime = wanted;
            pending.clear();
            haveParameterSets = false;
            if (surface != null) {
                startCodecLocked();
            }
        }
    }

    void queueFrame(byte[] data, long ptsNanos, boolean h265) {
        synchronized (lock) {
            if (mime == null) {
                mime = h265 ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            }
            boolean config = hasParameterSets(data, h265);
            if (config) {
                if (codec == null) {
                    // Everything older is undecodable without the codec
                    // having seen these; start the backlog here.
                    pending.clear();
                }
                haveParameterSets = true;
            }
            if (!haveParameterSets) {
                return;
            }
            pending.addLast(new Frame(data, ptsNanos));
            if (codec == null) {
                // Keep the backlog bounded, but never drop its first frame:
                // that is the one with the parameter sets.
                while (pending.size() > MAX_PENDING) {
                    Frame first = pending.pollFirst();
                    pending.pollFirst();
                    pending.addFirst(first);
                }
                return;
            }
            feedLocked();
        }
    }

    /** A new stream is coming; what is queued belongs to the old one. */
    void reset() {
        synchronized (lock) {
            pending.clear();
            haveParameterSets = false;
            if (codec != null) {
                releaseCodecLocked();
                if (surface != null && mime != null) {
                    startCodecLocked();
                }
            }
        }
    }

    void release() {
        synchronized (lock) {
            releaseCodecLocked();
            pending.clear();
            surface = null;
            mime = null;
            haveParameterSets = false;
            if (thread != null) {
                thread.quitSafely();
                thread = null;
                handler = null;
            }
        }
    }

    // ---- codec ----------------------------------------------------------

    private void startCodecLocked() {
        if (thread == null) {
            thread = new HandlerThread("airplay-video");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime, 1920, 1080);
            // A mirrored keyframe can be large; the default would truncate it.
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            c.setCallback(new Callback(), handler);
            c.setOnFrameRenderedListener(this::onFrameRendered, handler);
            c.configure(format, surface, null, 0);
            c.start();
            codec = c;
            freeInputs.clear();
            Log.i(TAG, "video decoder " + c.getName() + " for " + mime);
        } catch (Exception e) {
            Log.e(TAG, "could not start a " + mime + " decoder", e);
            codec = null;
        }
    }

    private void releaseCodecLocked() {
        if (codec != null) {
            if (handler != null) {
                handler.post(this::logStats);
            }
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
        freeInputs.clear();
    }

    private void feedLocked() {
        while (codec != null && !freeInputs.isEmpty() && !pending.isEmpty()) {
            int index = freeInputs.pollFirst();
            Frame f = pending.pollFirst();
            try {
                ByteBuffer buf = codec.getInputBuffer(index);
                if (buf == null || buf.capacity() < f.data.length) {
                    Log.w(TAG, "frame of " + f.data.length + " bytes does not fit; dropped");
                    codec.queueInputBuffer(index, 0, 0, f.ptsNanos / 1000, 0);
                    continue;
                }
                buf.clear();
                buf.put(f.data);
                codec.queueInputBuffer(index, 0, f.data.length, f.ptsNanos / 1000, 0);
            } catch (IllegalStateException e) {
                Log.w(TAG, "queueInputBuffer failed", e);
            }
        }
    }

    private final class Callback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec c, int index) {
            synchronized (lock) {
                if (c != codec) {
                    return;
                }
                freeInputs.addLast(index);
                feedLocked();
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
            synchronized (lock) {
                if (c != codec) {
                    return;
                }
                // Show it when it is due; a late frame goes up at once rather
                // than being dropped, because a mirrored screen that freezes
                // reads worse than one that is briefly behind.
                long due = Math.max(info.presentationTimeUs * 1000, System.nanoTime());
                try {
                    c.releaseOutputBuffer(index, due);
                } catch (IllegalStateException ignored) {
                }
            }
        }

        @Override
        public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
            int width = format.getInteger(MediaFormat.KEY_WIDTH);
            int height = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
            }
            if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
            }
            Log.i(TAG, "mirrored picture is " + width + "x" + height);
            SizeListener l;
            synchronized (lock) {
                l = sizeListener;
            }
            if (l != null) {
                l.onVideoSizeChanged(width, height);
            }
        }

        @Override
        public void onError(MediaCodec c, MediaCodec.CodecException e) {
            Log.e(TAG, "video decoder error", e);
        }
    }

    // ---- timing -----------------------------------------------------------

    private void onFrameRendered(MediaCodec c, long presentationTimeUs, long renderedNs) {
        long late = renderedNs - presentationTimeUs * 1000;
        shown++;
        if (late > 50_000_000L) {
            shownLate++;
        }
        latenessSumNs += late;
        latenessMaxNs = Math.max(latenessMaxNs, late);
        if (shown >= STATS_EVERY) {
            logStats();
        }
    }

    /** Positive lateness is behind the sender's clock; negative, ahead. */
    private void logStats() {
        if (shown == 0) {
            return;
        }
        Log.i(TAG, String.format(Locale.ROOT,
                "video: %d frames shown, mean %+.1f ms from due, worst %+.1f ms, %d over 50 ms",
                shown, latenessSumNs / 1e6 / shown, latenessMaxNs / 1e6, shownLate));
        shown = 0;
        shownLate = 0;
        latenessSumNs = 0;
        latenessMaxNs = 0;
    }

    // ---- bitstream ------------------------------------------------------

    /** True if the Annex-B data carries an SPS (H.264) or VPS/SPS (H.265). */
    static boolean hasParameterSets(byte[] d, boolean h265) {
        for (int i = 0; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int header = d[i + 3] & 0xFF;
                if (h265) {
                    int type = (header >> 1) & 0x3F;
                    if (type == 32 || type == 33) {
                        return true;
                    }
                } else if ((header & 0x1F) == 7) {
                    return true;
                }
                i += 2;
            }
        }
        return false;
    }
}
