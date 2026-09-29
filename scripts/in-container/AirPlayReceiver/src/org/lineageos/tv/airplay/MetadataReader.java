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
    private static final int CODE_ARTWORK = fourCC("PICT");
    /** The name of the device doing the sending. */
    private static final int CODE_CLIENT_NAME = fourCC("snam");
    private static final int CODE_PLAY_BEGIN = fourCC("pbeg");
    private static final int CODE_PLAY_END = fourCC("pend");
    private static final int CODE_PLAY_FLUSH = fourCC("pfls");

    interface Listener {
        void onTrack(String title, String artist, String album);

        void onArtwork(byte[] jpeg);

        void onClientName(String name);

        void onPlaying(boolean playing);
    }

    private final File pipe;
    private final Listener listener;
    private volatile boolean running = true;

    private String title, artist, album;

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
        String text = data == null ? null : new String(data);

        if (type == TYPE_CORE) {
            if (code == CODE_TITLE) {
                title = text;
            } else if (code == CODE_ARTIST) {
                artist = text;
            } else if (code == CODE_ALBUM) {
                album = text;
            } else {
                return;
            }
            listener.onTrack(title, artist, album);
        } else if (type == TYPE_SSNC) {
            if (code == CODE_ARTWORK && data != null && data.length > 0) {
                listener.onArtwork(data);
            } else if (code == CODE_CLIENT_NAME && text != null) {
                listener.onClientName(text);
            } else if (code == CODE_PLAY_BEGIN) {
                listener.onPlaying(true);
            } else if (code == CODE_PLAY_END || code == CODE_PLAY_FLUSH) {
                listener.onPlaying(false);
            }
        }
    }
}
