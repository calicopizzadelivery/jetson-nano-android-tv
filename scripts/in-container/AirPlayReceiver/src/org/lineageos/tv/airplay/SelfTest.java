/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * An AirPlay session with no sender, for the bench.
 *
 * Mirroring can only be driven for real by an Apple device -- no open source
 * sender speaks the protocol UxPlay implements -- so this exercises everything
 * after the protocol instead, through exactly the UxPlay.Listener calls UxPlay
 * makes. The screen coming up from the background, hardware decode, timed
 * release, audio scheduling, the now-playing panel and the teardown are all
 * the real code paths. Three modes, by the "audio" extra:
 *
 *   pcm    (default) mirroring: a moving test card from the device's own
 *          H.264 encoder, and a 440 Hz tone as PCM, which is how ALAC arrives
 *   eld    the same, with the tone as AAC-ELD, which is what a phone sends
 *          while mirroring. Android's own encoder frames ELD at 512 samples
 *          where senders use 480, so that stream is made on the host by
 *          tests/eldgen and pushed to files/selftest-eld.bin.
 *   music  no picture: the tone as PCM with a title, cover art and progress,
 *          as the Music app sends them
 *   pin    no stream: the pairing code screen, as a first-time device asks
 *          for it, then paired after the given seconds
 *
 * Only reachable on debuggable builds:
 *
 *   adb shell am start-foreground-service -a org.lineageos.tv.airplay.SELFTEST \
 *       -n org.lineageos.tv.airplay/.AirPlayService --ei seconds 10 [--es audio eld]
 */
final class SelfTest implements Runnable {

    private static final String TAG = "AirPlay";

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final int RATE = 44100;
    /** How far ahead of now frames are stamped: roughly what a sender uses. */
    private static final long LEAD_NS = 400_000_000L;
    /**
     * Where the music mode's RTP clock starts: just short of 2^32, so the
     * progress wraps a few seconds in, as a real sender's can at any time.
     */
    private static final long RTP_START = 0x1_0000_0000L - 3L * RATE;

    private final UxPlay.Listener listener;
    private final int seconds;
    private final String mode;
    private final File eldFile;

    SelfTest(UxPlay.Listener listener, int seconds, String mode, File eldFile) {
        this.listener = listener;
        this.seconds = seconds;
        this.mode = mode == null ? "pcm" : mode;
        this.eldFile = eldFile;
    }

    @Override
    public void run() {
        if (mode.equals("pin")) {
            listener.onPinRequested("4827");
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException ignored) {
            }
            listener.onPaired(null);
            Log.i(TAG, "self-test done");
            return;
        }
        boolean mirroring = !mode.equals("music");
        MediaCodec encoder = null;
        Surface input = null;
        try {
            ArrayDeque<byte[]> eld = mode.equals("eld") ? readUnits(eldFile) : null;
            listener.onClient("Self-test", "bench");
            if (mirroring) {
                encoder = videoEncoder();
                input = encoder.createInputSurface();
                encoder.start();
                listener.onVideoCodec(false);
            }
            if (eld != null) {
                listener.onAudioFormat(8, 480, true);
            } else {
                listener.onAudioFormat(2, 352, mirroring); // PCM route, as ALAC takes
            }
            Log.i(TAG, "self-test: " + seconds + " s, " + mode
                    + (encoder != null ? ", video through " + encoder.getName() : "")
                    + (eld != null ? ", " + eld.size() + " AAC-ELD units" : ""));
            if (!mirroring) {
                listener.onMetadata(dmap("Self-test tone", "Jetson TV bench", "AirPlay",
                        seconds * 1000L));
                listener.onCoverArt(coverArt());
            }

            long start = System.nanoTime();
            long frameNs = 1_000_000_000L / FPS;
            long audioFrames = 0;
            long total = (long) seconds * RATE;
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(64);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] config = null;

            for (int i = 0; i < seconds * FPS; i++) {
                long due = start + i * frameNs;

                if (mirroring) {
                    Canvas c = input.lockHardwareCanvas();
                    c.drawColor(Color.rgb(20, 24, 32));
                    paint.setColor(Color.rgb(230, 190, 90));
                    float x = (i * 12) % (WIDTH - 160);
                    c.drawRect(x, HEIGHT / 2f - 80, x + 160, HEIGHT / 2f + 80, paint);
                    paint.setColor(Color.WHITE);
                    c.drawText(String.format(Locale.ROOT, "AirPlay self-test  frame %d", i),
                            60, 100, paint);
                    input.unlockCanvasAndPost(c);

                    // Encoder output, in Annex-B. The parameter sets come out
                    // once, as a codec-config buffer; a sender puts them in
                    // front of its first frame, so do the same.
                    int out;
                    while ((out = encoder.dequeueOutputBuffer(info, 5_000)) >= 0) {
                        ByteBuffer buf = encoder.getOutputBuffer(out);
                        byte[] data = new byte[info.size];
                        buf.position(info.offset);
                        buf.get(data);
                        encoder.releaseOutputBuffer(out, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            config = data;
                            continue;
                        }
                        if (config != null) {
                            byte[] joined = new byte[config.length + data.length];
                            System.arraycopy(config, 0, joined, 0, config.length);
                            System.arraycopy(data, 0, joined, config.length, data.length);
                            data = joined;
                            config = null;
                        }
                        // The input surface stamps each frame with the time it
                        // was drawn, in the System.nanoTime() base.
                        listener.onVideoFrame(data, info.presentationTimeUs * 1000 + LEAD_NS,
                                false);
                    }
                }

                // This frame's worth of tone, stamped to the same clock.
                long wanted = (long) (i + 1) * RATE / FPS;
                while (audioFrames < wanted) {
                    long pts = start + audioFrames * 1_000_000_000L / RATE + LEAD_NS;
                    if (eld != null) {
                        if (eld.isEmpty()) {
                            break;
                        }
                        listener.onAudioEncoded(eld.poll(), pts, 8);
                        audioFrames += 480;
                    } else {
                        listener.onAudioPcm(tone(audioFrames, 352), pts);
                        audioFrames += 352;
                    }
                }

                // Once a second, as a sender's SET_PARAMETER progress does.
                if (!mirroring && i % FPS == 0) {
                    long now = (long) i * RATE / FPS;
                    listener.onProgress(RTP_START & 0xFFFFFFFFL,
                            (RTP_START + now) & 0xFFFFFFFFL,
                            (RTP_START + total) & 0xFFFFFFFFL);
                }

                long sleep = due + frameNs - System.nanoTime();
                if (sleep > 0) {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                }
            }
            Thread.sleep(LEAD_NS / 1_000_000L + 500);
        } catch (Exception e) {
            Log.e(TAG, "self-test failed", e);
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (IllegalStateException ignored) {
                }
                encoder.release();
            }
            if (input != null) {
                input.release();
            }
            listener.onConnectionsClosed();
            Log.i(TAG, "self-test done");
        }
    }

    private static MediaCodec videoEncoder() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return encoder;
    }

    /** 440 Hz, 16-bit stereo, continuous across calls. */
    private static byte[] tone(long firstFrame, int frames) {
        byte[] pcm = new byte[frames * 4];
        for (int f = 0; f < frames; f++) {
            short s = (short) (18000 * Math.sin(2 * Math.PI * 440 * (firstFrame + f) / RATE));
            for (int ch = 0; ch < 2; ch++) {
                pcm[f * 4 + ch * 2] = (byte) s;
                pcm[f * 4 + ch * 2 + 1] = (byte) (s >> 8);
            }
        }
        return pcm;
    }

    /** The eldgen format: a two-byte big-endian length before each unit. */
    private static ArrayDeque<byte[]> readUnits(File file) throws IOException {
        ArrayDeque<byte[]> units = new ArrayDeque<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            while (in.available() > 0) {
                byte[] unit = new byte[in.readUnsignedShort()];
                in.readFully(unit);
                units.add(unit);
            }
        }
        return units;
    }

    /** A track as SET_PARAMETER carries it: DAAP tags inside an mlit. */
    private static byte[] dmap(String title, String artist, String album, long durationMs) {
        ByteArrayOutputStream item = new ByteArrayOutputStream();
        tag(item, "minm", title.getBytes(StandardCharsets.UTF_8));
        tag(item, "asar", artist.getBytes(StandardCharsets.UTF_8));
        tag(item, "asal", album.getBytes(StandardCharsets.UTF_8));
        tag(item, "astm", new byte[] {(byte) (durationMs >> 24), (byte) (durationMs >> 16),
                (byte) (durationMs >> 8), (byte) durationMs});
        ByteArrayOutputStream outer = new ByteArrayOutputStream();
        tag(outer, "mlit", item.toByteArray());
        return outer.toByteArray();
    }

    private static void tag(ByteArrayOutputStream out, String name, byte[] value) {
        out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
        int n = value.length;
        out.writeBytes(new byte[] {(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n});
        out.writeBytes(value);
    }

    private static byte[] coverArt() {
        Bitmap b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(40, 70, 120));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(230, 190, 90));
        c.drawCircle(300, 300, 200, p);
        p.setColor(Color.WHITE);
        p.setTextSize(80);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("440 Hz", 300, 330, p);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 90, jpeg);
        return jpeg.toByteArray();
    }
}
