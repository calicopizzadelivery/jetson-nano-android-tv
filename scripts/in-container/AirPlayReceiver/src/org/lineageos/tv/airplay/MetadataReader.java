/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.airplay;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads Shairport Sync's metadata pipe.
 *
 * The format is a stream of
 *   &lt;item&gt;&lt;type&gt;hex&lt;/type&gt;&lt;code&gt;hex&lt;/code&gt;&lt;length&gt;n&lt;/length&gt;
 *   &lt;data encoding="base64"&gt;...&lt;/data&gt;&lt;/item&gt;
 * where type and code are four-character codes packed into 32 bits. "core"
 * items carry the track information the sender supplied; "ssnc" items are
 * Shairport's own notifications.
 *
 * Opening a FIFO for reading blocks until a writer appears, and read() returns
 * EOF every time the writer closes. Both are normal here: the daemon only
 * opens the pipe while a session is live, so this loops rather than treating
 * EOF as the end.
 */
final class MetadataReader implements Runnable {

    private static final String TAG = "AirPlay";

    /** 'core' items: what the sending device told us about the track. */
    private static final int TYPE_CORE = fourCC("core");
    /** 'ssnc' items: Shairport's own session notifications. */
    private static final int TYPE_SSNC = fourCC("ssnc");

    private static final int CODE_TITLE = fourCC("minm");
    private static final int CODE_ARTIST = fourCC("asar");
    private static final int CODE_ALBUM = fourCC("asal");
    /** Track length in milliseconds, as a big-endian 32-bit integer. */
    private static final int CODE_DURATION = fourCC("astm");
    private static final int CODE_ARTWORK = fourCC("PICT");
    /** The name of the device doing the sending. */
    private static final int CODE_CLIENT_NAME = fourCC("snam");
    private static final int CODE_PLAY_BEGIN = fourCC("pbeg");
    private static final int CODE_PLAY_END = fourCC("pend");
    private static final int CODE_PLAY_FLUSH = fourCC("pfls");
    /** Progress: three RTP timestamps, "start/current/end". */
    private static final int CODE_PROGRESS = fourCC("prgr");
    /** Brackets a group of 'core' items describing one track. */
    private static final int CODE_METADATA_START = fourCC("mdst");
    private static final int CODE_METADATA_END = fourCC("mden");

    /** Classic RAOP is always 44100; progress timestamps are in frames. */
    private static final int RAOP_FRAME_RATE = 44100;

    interface Listener {
        void onTrack(String title, String artist, String album, long durationMs);

        void onProgress(long positionMs, long durationMs);

        void onArtwork(byte[] jpeg);

        void onClientName(String name);

        void onPlaying(boolean playing);
    }

    private final File pipe;
    private final Listener listener;
    private volatile boolean running = true;

    private String title, artist, album;
    private long durationMs;
    /**
     * Whether we are between an 'mdst' and its 'mden'. Shairport brackets
     * every DAAP group with that pair unconditionally, so inside a group the
     * items are accumulated and published once at the end -- one update per
     * track rather than one per tag. A 'core' item arriving outside a group
     * is published immediately, so a receiver that ever stops bracketing
     * degrades to the old behaviour instead of going silent.
     */
    private boolean inGroup;

    MetadataReader(File pipe, Listener listener) {
        this.pipe = pipe;
        this.listener = listener;
    }

    void stop() {
        running = false;
    }

    private static int fourCC(String s) {
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    @Override
    public void run() {
        boolean warned = false;
        while (running) {
            if (!pipe.exists()) {
                // The daemon makes the FIFO when it starts, so until then
                // there is nothing to open. Wait rather than spin, and say so
                // only once -- otherwise a box with the receiver enabled and
                // the daemon never starting fills the log forever.
                if (!warned) {
                    Log.i(TAG, "waiting for " + pipe + " -- is the daemon running?");
                    warned = true;
                }
                if (!sleep(5000)) {
                    return;
                }
                continue;
            }
            warned = false;

            // Blocks until the daemon opens the pipe for writing, and returns
            // EOF each time it closes; neither is an error.
            try (InputStream in = new FileInputStream(pipe)) {
                readItems(in);
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "metadata pipe: " + e.getMessage());
                }
            }
            if (!sleep(500)) {
                return;
            }
        }
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void readItems(InputStream in) throws IOException {
        StringBuilder buffer = new StringBuilder();
        int c;
        while (running && (c = in.read()) != -1) {
            buffer.append((char) c);
            int end = buffer.indexOf("</item>");
            if (end < 0) {
                // Artwork runs to a few hundred kB; anything far past that is
                // not an item we failed to terminate, it is a desynchronised
                // stream, so start again rather than grow without bound.
                if (buffer.length() > 4 * 1024 * 1024) {
                    buffer.setLength(0);
                }
                continue;
            }
            String item = buffer.substring(0, end);
            buffer.delete(0, end + "</item>".length());
            handle(item);
        }
    }

    private static String tag(String item, String name) {
        int open = item.indexOf("<" + name + ">");
        if (open < 0) {
            return null;
        }
        int close = item.indexOf("</" + name + ">", open);
        return close < 0 ? null : item.substring(open + name.length() + 2, close);
    }

    private void handle(String item) {
        String typeHex = tag(item, "type");
        String codeHex = tag(item, "code");
        if (typeHex == null || codeHex == null) {
            return;
        }
        int type, code;
        try {
            type = (int) Long.parseLong(typeHex.trim(), 16);
            code = (int) Long.parseLong(codeHex.trim(), 16);
        } catch (NumberFormatException e) {
            return;
        }

        byte[] data = null;
        int dataOpen = item.indexOf("<data");
        if (dataOpen >= 0) {
            int bodyStart = item.indexOf('>', dataOpen);
            int bodyEnd = item.indexOf("</data>", bodyStart);
            if (bodyStart > 0 && bodyEnd > bodyStart) {
                try {
                    data = Base64.decode(item.substring(bodyStart + 1, bodyEnd), Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    return;
                }
            }
        }
        String text = data == null ? null : new String(data, StandardCharsets.UTF_8);

        if (type == TYPE_CORE) {
            if (code == CODE_TITLE) {
                title = text;
            } else if (code == CODE_ARTIST) {
                artist = text;
            } else if (code == CODE_ALBUM) {
                album = text;
            } else if (code == CODE_DURATION) {
                durationMs = beInt(data);
            } else {
                return;
            }
            if (!inGroup) {
                publishTrack();
            }
        } else if (type == TYPE_SSNC) {
            if (code == CODE_METADATA_START) {
                // A new track's tags follow. Clear first: a sender that omits
                // a field means "this track has none", not "keep the last
                // track's", and without this an untitled track inherits the
                // previous title.
                title = artist = album = null;
                durationMs = 0;
                inGroup = true;
            } else if (code == CODE_METADATA_END) {
                inGroup = false;
                publishTrack();
            } else if (code == CODE_ARTWORK && data != null && data.length > 0) {
                listener.onArtwork(data);
            } else if (code == CODE_CLIENT_NAME && text != null) {
                listener.onClientName(text);
            } else if (code == CODE_PROGRESS && text != null) {
                handleProgress(text);
            } else if (code == CODE_PLAY_BEGIN) {
                listener.onPlaying(true);
            } else if (code == CODE_PLAY_END || code == CODE_PLAY_FLUSH) {
                listener.onPlaying(false);
            }
        }
    }

    private void publishTrack() {
        listener.onTrack(title, artist, album, durationMs);
    }

    /** Big-endian, and short or absent data reads as zero rather than throwing. */
    private static long beInt(byte[] data) {
        if (data == null || data.length < 4) {
            return 0;
        }
        return ((long) (data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
    }

    /**
     * "start/current/end" in RTP frames. The span gives a duration for senders
     * that send progress but no astm; where both arrive, astm wins, because a
     * station streaming continuously reports a moving end.
     */
    private void handleProgress(String text) {
        String[] parts = text.trim().split("/");
        if (parts.length != 3) {
            return;
        }
        try {
            long start = Long.parseLong(parts[0].trim());
            long current = Long.parseLong(parts[1].trim());
            long end = Long.parseLong(parts[2].trim());
            // RTP timestamps are 32-bit and senders start them at random, so
            // a track can wrap past 2^32 mid-play. Plain subtraction then goes
            // hugely negative and the progress bar vanishes; the distance
            // modulo 2^32 is the real one.
            long positionFrames = (current - start) & 0xFFFFFFFFL;
            long spanFrames = (end - start) & 0xFFFFFFFFL;
            long positionMs = positionFrames * 1000L / RAOP_FRAME_RATE;
            long spanMs = durationMs > 0 ? durationMs : spanFrames * 1000L / RAOP_FRAME_RATE;
            listener.onProgress(positionMs, spanMs);
        } catch (NumberFormatException e) {
            // Not all senders send frame counts here; ignore rather than log
            // once a second.
        }
    }
}
