/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays AirPlay audio at the times the sender asked for.
 *
 * Frames arrive from UxPlay's threads with a presentation time each: PCM for
 * ALAC (decoded natively), or AAC-ELD / AAC-LC (decoded here with Android's
 * own AAC decoder). Nothing on those threads may block -- a stalled receiver
 * thread is exactly how the shairport port lost packets -- so frames are only
 * queued there, and a writer thread of our own holds each until it is due.
 *
 * "Due" is measured against when audio written now will actually be heard,
 * which AudioTrack.getTimestamp() gives: a frame position the device
 * presented at a known time, i.e. the whole output path including the HAL.
 * The writer pads with silence when a frame is early and drops it when it is
 * late, which keeps the output within a few milliseconds of the sender's
 * clock -- and, since video is released at its own presentation times, in
 * step with the picture while mirroring.
 */
final class AudioRenderer {

    private static final String TAG = "AirPlay";

    private static final int RATE = 44100;
    private static final int BYTES_PER_FRAME = 4; // 16-bit stereo

    /** Later than this and a frame is dropped rather than played out of step. */
    private static final long LATE_NS = 60_000_000L;
    /** Earlier than this and silence is written to hold it back. */
    private static final long EARLY_NS = 15_000_000L;

    /** The AudioSpecificConfigs UxPlay's GStreamer renderer uses. */
    private static final byte[] CSD_AAC_ELD = {(byte) 0xf8, (byte) 0xe8, 0x50, 0x00};
    private static final byte[] CSD_AAC_LC = {0x12, 0x10};

    private static final class Chunk {
        final byte[] pcm;
        final long ptsNanos;

        Chunk(byte[] pcm, long ptsNanos) {
            this.pcm = pcm;
            this.ptsNanos = ptsNanos;
        }
    }

    private final LinkedBlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
    private final Object codecLock = new Object();
    private final AudioTimestamp timestamp = new AudioTimestamp();

    private AudioTrack track;
    private Thread writer;
    private volatile boolean running;
    private long framesWritten;
    private volatile float gain = 1f;

    private MediaCodec aac;
    private int aacType;

    // What the writer had to do to keep in step, logged when a stream ends.
    private long chunksPlayed;
    private long chunksDropped;
    private long framesPadded;
    private long lateSumNs;

    void start() {
        if (running) {
            return;
        }
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                // A quarter of a second: enough to ride out scheduling jitter,
                // small enough that the silence-padding stays responsive.
                .setBufferSizeInBytes(Math.max(min, RATE / 4 * BYTES_PER_FRAME))
                .build();
        track.setVolume(gain);
        track.play();
        framesWritten = 0;
        chunksPlayed = chunksDropped = framesPadded = lateSumNs = 0;
        running = true;
        writer = new Thread(this::writeLoop, "airplay-audio");
        writer.start();
    }

    void stop() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(1000);
            } catch (InterruptedException ignored) {
            }
            writer = null;
            if (chunksPlayed + chunksDropped > 0) {
                Log.i(TAG, String.format(Locale.ROOT,
                        "audio: %d chunks played, mean %+.1f ms from due, %d dropped late,"
                                + " %.0f ms of silence padded",
                        chunksPlayed, chunksPlayed == 0 ? 0 : lateSumNs / 1e6 / chunksPlayed,
                        chunksDropped, framesPadded * 1000.0 / RATE));
            }
        }
        queue.clear();
        if (track != null) {
            track.release();
            track = null;
        }
        releaseAac();
    }

    /** ct 2 is ALAC (arrives as PCM); 4 is AAC-LC and 8 AAC-ELD. */
    void configure(int compressionType) {
        if (compressionType != 4 && compressionType != 8) {
            releaseAac();
            return;
        }
        synchronized (codecLock) {
            if (aac != null && aacType == compressionType) {
                return;
            }
            releaseAacLocked();
            try {
                MediaFormat format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, compressionType == 8
                        ? MediaCodecInfo.CodecProfileLevel.AACObjectELD
                        : MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setByteBuffer("csd-0",
                        ByteBuffer.wrap(compressionType == 8 ? CSD_AAC_ELD : CSD_AAC_LC));
                aac = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aac.configure(format, null, null, 0);
                aac.start();
                aacType = compressionType;
                Log.i(TAG, "AAC decoder " + aac.getName() + " for "
                        + (compressionType == 8 ? "AAC-ELD" : "AAC-LC"));
            } catch (Exception e) {
                Log.e(TAG, "no AAC decoder", e);
                releaseAacLocked();
            }
        }
    }

    void queuePcm(byte[] pcm, long ptsNanos) {
        if (running) {
            queue.offer(new Chunk(pcm, ptsNanos));
        }
    }

    /**
     * Decode on the caller's thread: MediaCodec's AAC decoder is fast and
     * takes a frame in, a frame out, and the timeouts are short enough that a
     * stuck decoder costs a frame rather than the receiver.
     */
    void queueEncoded(byte[] frame, long ptsNanos) {
        synchronized (codecLock) {
            if (aac == null || !running) {
                return;
            }
            try {
                int in = aac.dequeueInputBuffer(5_000);
                if (in >= 0) {
                    ByteBuffer buf = aac.getInputBuffer(in);
                    buf.clear();
                    buf.put(frame);
                    aac.queueInputBuffer(in, 0, frame.length, ptsNanos / 1000, 0);
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int out;
                while ((out = aac.dequeueOutputBuffer(info, 0)) >= 0) {
                    if (info.size > 0) {
                        ByteBuffer pcm = aac.getOutputBuffer(out);
                        byte[] copy = new byte[info.size];
                        pcm.position(info.offset);
                        pcm.get(copy);
                        queue.offer(new Chunk(copy, info.presentationTimeUs * 1000));
                    }
                    aac.releaseOutputBuffer(out, false);
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "AAC decode failed", e);
            }
        }
    }

    void flush() {
        queue.clear();
        synchronized (codecLock) {
            if (aac != null) {
                aac.flush();
                // A flushed codec in synchronous mode needs start() again only
                // after stop(); flush() leaves it running.
            }
        }
    }

    /**
     * AirPlay volume is in dB, -30 to 0, with -144 meaning mute. Applied to
     * this stream only; the television's own volume stays the master.
     */
    void setVolume(float airplayDb) {
        float g;
        if (airplayDb <= -30f) {
            g = 0f;
        } else if (airplayDb >= 0f) {
            g = 1f;
        } else {
            g = (float) Math.pow(10, airplayDb / 20.0);
        }
        gain = g;
        AudioTrack t = track;
        if (t != null) {
            t.setVolume(g);
        }
    }

    private void releaseAac() {
        synchronized (codecLock) {
            releaseAacLocked();
        }
    }

    private void releaseAacLocked() {
        if (aac != null) {
            try {
                aac.stop();
            } catch (IllegalStateException ignored) {
            }
            aac.release();
            aac = null;
            aacType = 0;
        }
    }

    // ---- writer ---------------------------------------------------------

    private void writeLoop() {
        byte[] silence = new byte[RATE * BYTES_PER_FRAME / 10]; // 100 ms
        while (running) {
            Chunk c;
            try {
                c = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (c == null) {
                continue;
            }
            // Hold the chunk until the output catches up to it.
            long early = 0;
            while (running) {
                early = c.ptsNanos - nextFramePlaysAt();
                if (early < -LATE_NS) {
                    c = null; // too late to be in step; drop it
                    chunksDropped++;
                    break;
                }
                if (early <= EARLY_NS) {
                    break;
                }
                int frames = (int) Math.min(early * RATE / 1_000_000_000L,
                        silence.length / BYTES_PER_FRAME);
                if (!write(silence, frames * BYTES_PER_FRAME)) {
                    return;
                }
                framesPadded += frames;
            }
            if (c != null) {
                if (!write(c.pcm, c.pcm.length)) {
                    return;
                }
                chunksPlayed++;
                lateSumNs -= early;
            }
        }
    }

    private boolean write(byte[] data, int length) {
        AudioTrack t = track;
        if (t == null) {
            return false;
        }
        int done = 0;
        while (done < length && running) {
            int n = t.write(data, done, length - done);
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write: " + n);
                return false;
            }
            done += n;
        }
        framesWritten += done / BYTES_PER_FRAME;
        return true;
    }

    /** When a frame written now will be heard, in System.nanoTime(). */
    private long nextFramePlaysAt() {
        long now = System.nanoTime();
        AudioTrack t = track;
        if (t == null) {
            return now;
        }
        if (t.getTimestamp(timestamp) && timestamp.framePosition > 0) {
            long presentedNow = timestamp.framePosition
                    + (now - timestamp.nanoTime) * RATE / 1_000_000_000L;
            long ahead = Math.max(0, framesWritten - presentedNow);
            return now + ahead * 1_000_000_000L / RATE;
        }
        // Not presenting yet: all of it is still ahead, plus the path to the
        // speaker, which getTimestamp would have included.
        long ahead = Math.max(0, framesWritten - t.getPlaybackHeadPosition());
        return now + ahead * 1_000_000_000L / RATE + 50_000_000L;
    }
}
