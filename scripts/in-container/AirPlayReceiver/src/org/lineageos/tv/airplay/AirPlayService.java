/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;

/**
 * Runs the AirPlay receiver.
 *
 * It does not exec anything: the daemon is an init service, and this only sets
 * the property that starts it. That keeps the SELinux story simple -- an app
 * that could exec a system binary would need far more.
 *
 * Its real jobs are the two things a headless daemon cannot do for itself:
 * hold audio focus, so AirPlay and Kodi do not talk over each other, and turn
 * the metadata pipe into a MediaSession so something appears on screen.
 */
public class AirPlayService extends Service implements MetadataReader.Listener {

    private static final String TAG = "AirPlay";

    public static final String ACTION_START = "org.lineageos.tv.airplay.START";
    public static final String ACTION_STOP = "org.lineageos.tv.airplay.STOP";

    /** Read by init; see shairport-sync.rc. */
    private static final String PROP_ENABLED = "persist.jetsontv.airplay.enabled";
    private static final String PROP_NAME = "persist.jetsontv.airplay.name";

    /** Matches --metadata-pipename in shairport-sync.rc. */
    private static final File METADATA_PIPE = new File("/data/misc/airplay/metadata");

    private static final String CHANNEL_ID = "airplay";
    private static final int NOTIFICATION_ID = 1;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession session;
    private MetadataReader reader;
    private Thread readerThread;

    private String clientName;
    private Bitmap artwork;
    private boolean playing;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        start();
        return START_STICKY;
    }

    private void start() {
        if (session != null) {
            return; // already running
        }
        audioManager = getSystemService(AudioManager.class);

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, buildNotification());

        session = new MediaSession(this, TAG);
        session.setActive(true);
        publishState();

        // Start the daemon. init owns it; we only ask.
        if (setEnabled(true)) {
            Log.i(TAG, "receiver enabled, advertising as \""
                    + SystemProperties.get(PROP_NAME, "Jetson TV") + "\"");
        }

        reader = new MetadataReader(METADATA_PIPE, this);
        readerThread = new Thread(reader, "airplay-metadata");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * SystemProperties.set throws rather than returning a failure when the
     * write is refused -- which it is on any build without our property label,
     * where persist.jetsontv.airplay.enabled is plain default_prop. Letting
     * that escape takes the whole service down with it, so the receiver would
     * die at startup instead of simply having no daemon to talk to.
     */
    private boolean setEnabled(boolean enabled) {
        try {
            SystemProperties.set(PROP_ENABLED, Boolean.toString(enabled));
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "could not set " + PROP_ENABLED + "; is the sepolicy for it installed?", e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        setEnabled(false);
        abandonFocus();
        if (reader != null) {
            reader.stop();
            readerThread.interrupt();
        }
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        super.onDestroy();
    }

    // ---- audio focus ------------------------------------------------------

    /**
     * Taken when a stream actually starts rather than when the receiver is
     * enabled: the box may sit advertising for hours, and holding focus that
     * whole time would silence everything else for no reason.
     */
    private void requestFocus() {
        if (focusRequest != null) {
            return;
        }
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        // Something else took over for good. Stop receiving
                        // rather than fight it; the sender will notice.
                        Log.i(TAG, "lost audio focus; stopping the receiver");
                        stopSelf();
                    }
                })
                .build();
        int result = audioManager.requestAudioFocus(focusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus refused (" + result + ")");
        }
    }

    private void abandonFocus() {
        if (focusRequest != null && audioManager != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    // ---- metadata ---------------------------------------------------------

    @Override
    public void onTrack(String title, String artist, String album) {
        MediaMetadata.Builder b = new MediaMetadata.Builder();
        if (!TextUtils.isEmpty(title)) {
            b.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        }
        if (!TextUtils.isEmpty(artist)) {
            b.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        }
        if (!TextUtils.isEmpty(album)) {
            b.putString(MediaMetadata.METADATA_KEY_ALBUM, album);
        }
        if (artwork != null) {
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        // The sending device, so the screen can say where the music came from.
        if (!TextUtils.isEmpty(clientName)) {
            b.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, clientName);
        }
        if (session != null) {
            session.setMetadata(b.build());
        }
        updateNotification();
    }

    @Override
    public void onArtwork(byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap != null) {
            artwork = bitmap;
            onTrack(null, null, null); // re-publish with the art attached
        }
    }

    @Override
    public void onClientName(String name) {
        clientName = name;
        Log.i(TAG, "streaming from \"" + name + "\"");
        updateNotification();
    }

    @Override
    public void onPlaying(boolean nowPlaying) {
        playing = nowPlaying;
        if (nowPlaying) {
            requestFocus();
        } else {
            abandonFocus();
            artwork = null;
        }
        publishState();
        updateNotification();
    }

    private void publishState() {
        if (session == null) {
            return;
        }
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build());
    }

    // ---- notification -----------------------------------------------------

    private Notification buildNotification() {
        String text = playing
                ? getString(R.string.receiver_streaming,
                        TextUtils.isEmpty(clientName) ? getString(R.string.unknown_device) : clientName)
                : getString(R.string.receiver_ready);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_headset)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification());
        }
    }
}
