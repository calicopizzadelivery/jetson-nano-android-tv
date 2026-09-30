/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.util.SparseArray;

import java.nio.charset.StandardCharsets;

/**
 * UxPlay's AirPlay server, running in this process through libuxplay_jni.
 *
 * The native side does the protocol -- pairing, FairPlay, decryption, clock
 * sync -- and calls the on* methods below from threads of its own, with
 * frames already decrypted and presentation times already in the
 * System.nanoTime() base. Those methods run on UxPlay's threads, so a
 * listener must not block in them: anything slow is queued.
 *
 * Advertising goes through NsdManager. UxPlay builds the TXT records itself
 * and asks for a registration through a dns_sd shim; nsdRegister turns that
 * into an NsdServiceInfo.
 *
 * GPLv3, because it is a thin shell around UxPlay.
 */
final class UxPlay {

    private static final String TAG = "UxPlay";

    static {
        System.loadLibrary("uxplay_jni");
    }

    interface Listener {
        void onClient(String name, String model);

        void onConnectionsClosed();

        void onVideoCodec(boolean h265);

        void onVideoFrame(byte[] frame, long ptsNanos, boolean h265);

        void onVideoReset();

        void onVideoSize(int width, int height);

        void onAudioFormat(int compressionType, int samplesPerFrame, boolean usingScreen);

        void onAudioPcm(byte[] pcm, long ptsNanos);

        void onAudioEncoded(byte[] frame, long ptsNanos, int compressionType);

        void onAudioFlush();

        void onVolume(float airplayDb);

        void onMetadata(byte[] dmap);

        void onCoverArt(byte[] jpeg);

        void onProgress(long start, long current, long end);

        /** A device asked to pair: put this code on the screen. */
        void onPinRequested(String pin);

        /** A device proved the PIN (name null), or a paired one named itself. */
        void onPaired(String name);
    }

    private final NsdManager nsd;
    private final Listener listener;
    private final ClientRegistry registry;
    private final SparseArray<NsdManager.RegistrationListener> registrations = new SparseArray<>();
    private int nextRegistrationId = 1;
    private int openConnections;

    UxPlay(Context context, Listener listener, ClientRegistry registry) {
        this.nsd = context.getSystemService(NsdManager.class);
        this.listener = listener;
        this.registry = registry;
    }

    /**
     * @param requirePin a device must enter the code shown on screen the
     *     first time; after that it is known by its key.
     * @return the port the server listens on, or a negative value on failure.
     */
    int start(String name, String deviceId, String keyFile, boolean requirePin) {
        // 1920x1080, 60 Hz, 30 fps: UxPlay's defaults, and what the Tegra
        // decoder is proven at. Senders scale to fit.
        return nativeStart(name, deviceId, keyFile, 1920, 1080, 60, 30, requirePin);
    }

    void stop() {
        nativeStop();
        synchronized (registrations) {
            for (int i = 0; i < registrations.size(); i++) {
                unregister(registrations.valueAt(i));
            }
            registrations.clear();
        }
        openConnections = 0;
    }

    private native int nativeStart(String name, String deviceId, String keyFile, int width,
            int height, int refreshRate, int maxFps, boolean requirePin);

    private native void nativeStop();

    // ---- called from native: mDNS -------------------------------------

    /** Returns a positive id, or a negative dns_sd error. */
    @SuppressWarnings("unused")
    private int nsdRegister(String name, String type, int port, byte[] txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        parseTxt(txt, info);

        NsdManager.RegistrationListener callback = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo registered) {
                Log.i(TAG, "advertising " + registered.getServiceName() + " as " + type);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo failed, int errorCode) {
                Log.e(TAG, "could not advertise " + type + ": " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo unregistered) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo failed, int errorCode) {
            }
        };
        int id;
        synchronized (registrations) {
            id = nextRegistrationId++;
            registrations.put(id, callback);
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback);
        return id;
    }

    @SuppressWarnings("unused")
    private void nsdUnregister(int id) {
        NsdManager.RegistrationListener callback;
        synchronized (registrations) {
            callback = registrations.get(id);
            registrations.remove(id);
        }
        if (callback != null) {
            unregister(callback);
        }
    }

    private void unregister(NsdManager.RegistrationListener callback) {
        try {
            nsd.unregisterService(callback);
        } catch (IllegalArgumentException e) {
            // Registration never completed; nothing to take down.
        }
    }

    /** TXT rdata is a run of length-prefixed "key=value" strings. */
    private static void parseTxt(byte[] txt, NsdServiceInfo info) {
        int off = 0;
        while (txt != null && off < txt.length) {
            int len = txt[off] & 0xFF;
            if (off + 1 + len > txt.length) {
                break;
            }
            String entry = new String(txt, off + 1, len, StandardCharsets.UTF_8);
            int eq = entry.indexOf('=');
            if (eq < 0) {
                info.setAttribute(entry, (String) null);
            } else {
                info.setAttribute(entry.substring(0, eq), entry.substring(eq + 1));
            }
            off += 1 + len;
        }
    }

    // ---- called from native: sessions ---------------------------------

    @SuppressWarnings("unused")
    private void onConnInit() {
        synchronized (this) {
            openConnections++;
        }
    }

    @SuppressWarnings("unused")
    private void onConnDestroy() {
        boolean last;
        synchronized (this) {
            openConnections = Math.max(0, openConnections - 1);
            last = openConnections == 0;
        }
        if (last) {
            listener.onConnectionsClosed();
        }
    }

    @SuppressWarnings("unused")
    private void onConnReset(int reason) {
        Log.i(TAG, "connection reset, reason " + reason);
    }

    @SuppressWarnings("unused")
    private void onClient(String name, String model, String deviceId) {
        Log.i(TAG, "connection from \"" + name + "\" (" + model + ")");
        listener.onClient(name, model);
    }

    // ---- called from native: pairing ----------------------------------

    @SuppressWarnings("unused")
    private void onPin(String pin) {
        Log.i(TAG, "a device asked to pair; showing the code");
        listener.onPinRequested(pin);
    }

    /** name is null when it has just proved the PIN, set when SETUP names it. */
    @SuppressWarnings("unused")
    private void onRegisterClient(String deviceId, String publicKey, String name) {
        registry.remember(publicKey, deviceId, name);
        listener.onPaired(name);
    }

    @SuppressWarnings("unused")
    private boolean isRegistered(String publicKey) {
        boolean known = registry.contains(publicKey);
        Log.i(TAG, known ? "a paired device is back" : "an unpaired device skipped the PIN: refused");
        return known;
    }

    // ---- called from native: video ------------------------------------

    @SuppressWarnings("unused")
    private void onVideoCodec(boolean h265) {
        listener.onVideoCodec(h265);
    }

    @SuppressWarnings("unused")
    private void onVideoFrame(byte[] frame, long ptsNanos, boolean h265) {
        listener.onVideoFrame(frame, ptsNanos, h265);
    }

    /** 1 pause, 2 resume, 3 flush, 4 reset; see uxplay_jni.c. */
    @SuppressWarnings("unused")
    private void onVideoState(int what) {
        if (what == 4) {
            listener.onVideoReset();
        }
    }

    @SuppressWarnings("unused")
    private void onVideoSize(int widthSource, int heightSource, int width, int height) {
        listener.onVideoSize(widthSource, heightSource);
    }

    @SuppressWarnings("unused")
    private void onMirrorRunning(boolean running) {
        Log.i(TAG, "mirroring " + (running ? "running" : "stopped"));
    }

    // ---- called from native: audio ------------------------------------

    @SuppressWarnings("unused")
    private void onAudioFormat(int compressionType, int samplesPerFrame, boolean usingScreen,
            boolean isMedia) {
        listener.onAudioFormat(compressionType, samplesPerFrame, usingScreen);
    }

    @SuppressWarnings("unused")
    private void onAudioPcm(byte[] pcm, long ptsNanos) {
        listener.onAudioPcm(pcm, ptsNanos);
    }

    @SuppressWarnings("unused")
    private void onAudioEncoded(byte[] frame, long ptsNanos, int compressionType) {
        listener.onAudioEncoded(frame, ptsNanos, compressionType);
    }

    @SuppressWarnings("unused")
    private void onAudioFlush() {
        listener.onAudioFlush();
    }

    @SuppressWarnings("unused")
    private void onVolume(float airplayDb) {
        listener.onVolume(airplayDb);
    }

    @SuppressWarnings("unused")
    private void onMetadata(byte[] dmap) {
        listener.onMetadata(dmap);
    }

    @SuppressWarnings("unused")
    private void onCoverArt(byte[] jpeg) {
        listener.onCoverArt(jpeg);
    }

    @SuppressWarnings("unused")
    private void onProgress(long start, long current, long end) {
        listener.onProgress(start, current, end);
    }
}
