/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.ambient;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * Watches whatever is playing, whoever is playing it.
 *
 * Deliberately not an AirPlay-specific hookup. A screensaver is what the
 * television shows while music is on and nothing is being watched, so the
 * useful surface is "the active MediaSession", which covers the AirPlay
 * receiver, Kodi, Plex and anything else on the box for the same code. That
 * costs MEDIA_CONTENT_CONTROL, which is why this app is privileged; the
 * alternative, a notification listener, needs a setting the user has to find
 * and turn on.
 *
 * Two levels of callback are needed. The session list changes when an app
 * starts or stops playing at all, and a controller's own callbacks fire when
 * the track or the transport state changes within one session.
 */
final class NowPlaying {

    private static final String TAG = "AmbientDream";

    interface Listener {
        /** [state] is null when nothing is playing. */
        void onNowPlayingChanged(State state);
    }

    static final class State {
        final String title;
        final String artist;
        /** Who it is coming from: the app, and the sender if it named one. */
        final String source;
        final Bitmap artwork;
        final long durationMs;
        /** Where the position was taken, for extrapolating between updates. */
        private final long positionMs;
        private final long positionAtElapsedMs;
        private final float speed;

        State(String title, String artist, String source, Bitmap artwork, long durationMs,
              long positionMs, long positionAtElapsedMs, float speed) {
            this.title = title;
            this.artist = artist;
            this.source = source;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.positionAtElapsedMs = positionAtElapsedMs;
            this.speed = speed;
        }

        /**
         * Positions arrive about once a second. Extrapolating from the moment
         * the last one was taken is what keeps a progress bar moving smoothly
         * instead of stepping, and is what PlaybackState's timestamp is for.
         */
        long positionNowMs() {
            if (positionAtElapsedMs <= 0) {
                return positionMs;
            }
            long since = SystemClock.elapsedRealtime() - positionAtElapsedMs;
            long extrapolated = positionMs + (long) (since * speed);
            return durationMs > 0 ? Math.min(extrapolated, durationMs) : extrapolated;
        }
    }

    private final Context context;
    private final Listener listener;
    private final MediaSessionManager sessions;

    private MediaController controller;

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publish();
        }

        @Override
        public void onSessionDestroyed() {
            // The app went away without the list being refreshed yet.
            detach();
            publish();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCallback =
            this::onSessions;

    NowPlaying(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.sessions = context.getSystemService(MediaSessionManager.class);
    }

    void start() {
        if (sessions == null) {
            return;
        }
        try {
            // Null means "every session", which is the part that needs
            // MEDIA_CONTENT_CONTROL. Without it this throws rather than
            // returning an empty list, and the dream simply shows no
            // now-playing panel.
            sessions.addOnActiveSessionsChangedListener(sessionsCallback, (ComponentName) null);
            onSessions(sessions.getActiveSessions(null));
        } catch (SecurityException e) {
            Log.w(TAG, "no permission to watch media sessions; no now-playing panel", e);
        }
    }

    void stop() {
        if (sessions != null) {
            try {
                sessions.removeOnActiveSessionsChangedListener(sessionsCallback);
            } catch (RuntimeException e) {
                // Never registered, because start() was refused.
            }
        }
        detach();
    }

    private void onSessions(List<MediaController> controllers) {
        MediaController best = pick(controllers);
        if (best != null && controller != null
                && best.getSessionToken().equals(controller.getSessionToken())) {
            publish();
            return;
        }
        detach();
        if (best != null) {
            controller = best;
            controller.registerCallback(controllerCallback);
        }
        publish();
    }

    /**
     * Whatever is actually playing. A box can hold several sessions at once --
     * a paused video and a playing stream -- and only one belongs on screen,
     * so a session that is merely present does not qualify.
     */
    private static MediaController pick(List<MediaController> controllers) {
        if (controllers == null) {
            return null;
        }
        for (MediaController candidate : controllers) {
            PlaybackState state = candidate.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                return candidate;
            }
        }
        return null;
    }

    private void detach() {
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
    }

    private void publish() {
        listener.onNowPlayingChanged(snapshot());
    }

    private State snapshot() {
        MediaController current = controller;
        if (current == null) {
            return null;
        }
        PlaybackState playback = current.getPlaybackState();
        if (playback == null || playback.getState() != PlaybackState.STATE_PLAYING) {
            return null;
        }
        MediaMetadata metadata = current.getMetadata();
        if (metadata == null) {
            return null;
        }

        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) {
            // Nothing worth drawing a panel for. An app that plays without
            // publishing a title -- a game's background music, say -- should
            // leave the photograph alone.
            return null;
        }

        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        }

        Bitmap artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (artwork == null) {
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }

        return new State(title, artist, source(current, metadata), artwork,
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                playback.getPosition(), playback.getLastPositionUpdateTime(),
                playback.getPlaybackSpeed());
    }

    /**
     * "AirPlay · Phil's iPhone" where the session names a sender, otherwise
     * just the app. DISPLAY_SUBTITLE is what the AirPlay receiver puts the
     * sending device in; most apps leave it unset.
     */
    private String source(MediaController current, MediaMetadata metadata) {
        String app = appLabel(current.getPackageName());
        String sender = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (TextUtils.isEmpty(sender)) {
            return app;
        }
        return TextUtils.isEmpty(app) ? sender : app + " · " + sender;
    }

    private String appLabel(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }
}
