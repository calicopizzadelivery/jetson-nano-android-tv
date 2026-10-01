/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The AirPlay receiver: screen mirroring from a Mac, iPhone or iPad, and
 * AirPlay audio.
 *
 * The protocol is UxPlay's, running in this process (see UxPlay.java); this
 * service owns everything around it. Audio plays through AudioRenderer, and
 * mirroring through VideoRenderer onto MirrorActivity, which is brought up
 * when a sender starts mirroring and taken down when it stops.
 *
 * The rest is what a headless receiver cannot do for itself: hold audio focus
 * so AirPlay and Kodi do not talk over each other, publish a MediaSession so
 * the now-playing panel can show what is playing, and put that panel on the
 * television when music starts while nothing else is on screen.
 *
 * This replaced a shairport-sync daemon started by init. The switch that the
 * Streaming tile reads is still persist.jetsontv.airplay.enabled; it now only
 * records whether the receiver is on.
 */
public class AirPlayService extends Service implements UxPlay.Listener {

    private static final String TAG = "AirPlay";

    public static final String ACTION_START = "org.lineageos.tv.airplay.START";
    public static final String ACTION_STOP = "org.lineageos.tv.airplay.STOP";
    private static final String ACTION_END_SESSION = "org.lineageos.tv.airplay.END_SESSION";
    /** Bench only; see SelfTest. */
    private static final String ACTION_SELFTEST = "org.lineageos.tv.airplay.SELFTEST";

    /** Read by the Streaming tile. */
    static final String PROP_ENABLED = "persist.jetsontv.airplay.enabled";
    /**
     * Overrides the advertised name. Unset (the default), the receiver uses
     * the device name from Settings, so renaming the box renames it on
     * iPhones and Macs too.
     */
    private static final String PROP_NAME = "persist.jetsontv.airplay.name";
    /**
     * "on" (the default): a device must enter the code shown on screen the
     * first time it connects. "off": anyone on the network may stream.
     * Read when the receiver starts; changed through AirPlaySettingsProvider,
     * which restarts a running receiver so the change applies at once.
     */
    private static final String PROP_PIN = "persist.jetsontv.airplay.pin";
    /** Every paired device has to enter the PIN again. */
    private static final String ACTION_FORGET_DEVICES = "org.lineageos.tv.airplay.FORGET_DEVICES";

    private static final String CHANNEL_ID = "airplay";
    private static final int NOTIFICATION_ID = 1;

    private static volatile AirPlayService running;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Start and stop in order, and off the main thread: stop joins UxPlay's threads. */
    private final ExecutorService control = Executors.newSingleThreadExecutor();

    private final AudioRenderer audio = new AudioRenderer();
    private final VideoRenderer video = new VideoRenderer();
    private UxPlay uxplay;
    private ClientRegistry registry;
    /** What senders see now, for telling whether a rename needs a restart. */
    private volatile String advertisedName;

    /**
     * The receiver advertises the device name, so renaming the box in
     * Settings restarts it under the new one, unless PROP_NAME overrides it.
     */
    private final ContentObserver deviceNameObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            String name = receiverName(AirPlayService.this);
            if (uxplay != null && !name.equals(advertisedName)) {
                endSession("the device was renamed");
            }
        }
    };

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSession session;

    // Main thread only from here down.
    private boolean mirroring;
    private String clientName;
    private Bitmap artwork;
    private boolean playing;

    /**
     * The last thing each source told us. MediaMetadata has no partial update
     * -- each setMetadata replaces the lot -- and the pieces arrive separately
     * (tags, then cover art, then progress), so it is always rebuilt from
     * here rather than from whatever call is in hand.
     */
    private String title, artist, album;
    private long durationMs;
    private long positionMs;

    static VideoRenderer video() {
        AirPlayService s = running;
        return s == null ? null : s.video;
    }

    /** Back on the mirroring screen: drop the sender. */
    static void endSessionFromTv(Context context) {
        context.startService(new Intent(ACTION_END_SESSION).setClass(context, AirPlayService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_END_SESSION.equals(action)) {
            endSession("ended from the television");
            return START_STICKY;
        }
        if (ACTION_FORGET_DEVICES.equals(action)) {
            new ClientRegistry(this).forgetAll();
            return START_STICKY;
        }
        start();
        if (ACTION_SELFTEST.equals(action) && android.os.Build.IS_DEBUGGABLE) {
            int seconds = intent.getIntExtra("seconds", 10);
            new Thread(new SelfTest(this, seconds, intent.getStringExtra("audio"),
                    new File(getFilesDir(), "selftest-eld.bin")), "airplay-selftest").start();
        }
        return START_STICKY;
    }

    private void start() {
        if (session != null) {
            return; // already running
        }
        running = this;
        audioManager = getSystemService(AudioManager.class);

        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);

        session = new MediaSession(this, TAG);
        session.setActive(true);
        publishState();

        setEnabled(true);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_NAME), false, deviceNameObserver);
        registry = new ClientRegistry(this);
        uxplay = new UxPlay(this, this, registry);
        control.execute(this::startServer);
    }

    /** Whether new devices must enter the code: see PROP_PIN. */
    static boolean requirePin() {
        return !"off".equals(SystemProperties.get(PROP_PIN, "on"));
    }

    /**
     * Turns the code on or off. A running receiver restarts so that it
     * applies now rather than at the next boot. That ends any stream in
     * progress, which is fine for a setting changed on the TV itself.
     */
    static void setRequirePin(Context context, boolean on) {
        if (on == requirePin()) {
            return;
        }
        try {
            SystemProperties.set(PROP_PIN, on ? "on" : "off");
        } catch (RuntimeException e) {
            // IllegalStateException is one of the few exceptions that reach
            // the caller of a provider's call() intact.
            throw new IllegalStateException("could not set " + PROP_PIN, e);
        }
        Log.i(TAG, on ? "new devices must now enter the PIN"
                : "PIN off: any device on the network may stream");
        AirPlaySettingsProvider.notifyChanged(context);
        AirPlayService service = running;
        if (service != null) {
            service.main.post(() -> service.endSession("the PIN setting changed"));
        }
    }

    /** The name senders show: see PROP_NAME. Read when the receiver starts. */
    static String receiverName(Context context) {
        String name = SystemProperties.get(PROP_NAME, "");
        if (TextUtils.isEmpty(name)) {
            name = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
        }
        return TextUtils.isEmpty(name) ? "JetsonTV" : name;
    }

    private void startServer() {
        String name = receiverName(this);
        boolean requirePin = requirePin();
        int port = uxplay.start(name, deviceId(),
                new File(getFilesDir(), "uxplay.pem").getAbsolutePath(), requirePin);
        advertisedName = name;
        AirPlaySettingsProvider.notifyChanged(this);
        if (port > 0) {
            Log.i(TAG, "receiver enabled, advertising as \"" + name + "\" on port " + port
                    + (requirePin ? "; PIN required for new devices, " + registry.size()
                            + " paired" : "; no PIN"));
        } else {
            Log.e(TAG, "the AirPlay server did not start");
        }
    }

    /**
     * The device id senders know us by, as a MAC address. Kept stable across
     * restarts so a phone that has seen this box before recognises it. A
     * locally administered random address rather than a real one: nothing
     * here needs the hardware's, and it is not ours to broadcast.
     */
    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences("airplay", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02); // unicast, locally administered
            id = String.format(Locale.ROOT, "%02x:%02x:%02x:%02x:%02x:%02x",
                    b[0], b[1], b[2], b[3], b[4], b[5]);
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /**
     * SystemProperties.set throws rather than returning a failure when the
     * write is refused -- on any build without the property's label. Only
     * the tile depends on it, so a refusal is logged and ignored.
     */
    private boolean setEnabled(boolean enabled) {
        try {
            SystemProperties.set(PROP_ENABLED, Boolean.toString(enabled));
            AirPlaySettingsProvider.notifyChanged(this);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "could not set " + PROP_ENABLED + "; is the sepolicy for it installed?", e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        getContentResolver().unregisterContentObserver(deviceNameObserver);
        setEnabled(false);
        abandonFocus();
        MirrorActivity.finishIfShowing();
        final UxPlay server = uxplay;
        control.execute(() -> {
            if (server != null) {
                server.stop();
            }
            audio.stop();
            video.release();
        });
        control.shutdown();
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (running == this) {
            running = null;
        }
        super.onDestroy();
    }

    /**
     * Drop whoever is streaming and go on advertising: another app took
     * audio focus for good, or Back was pressed on the mirroring screen.
     * Restarting the server is the one reliable way to end a session from
     * this side, and takes well under a second.
     */
    private void endSession(String why) {
        Log.i(TAG, "ending this session (" + why + "); still advertising");
        final UxPlay server = uxplay;
        control.execute(() -> {
            if (server == null) {
                return;
            }
            server.stop();
            audio.stop();
            video.reset();
            startServer();
        });
        main.post(() -> {
            MirrorActivity.finishIfShowing();
            mirroring = false;
            title = artist = album = null;
            durationMs = 0;
            setPlaying(false);
        });
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
                        endSession("lost audio focus");
                    }
                }, main)
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

    // ---- UxPlay: sessions -------------------------------------------------

    @Override
    public void onClient(String name, String model) {
        PinActivity.dismiss();
        main.post(() -> {
            clientName = name;
            publishMetadata();
            updateNotification();
        });
    }

    @Override
    public void onConnectionsClosed() {
        audio.stop();
        video.reset();
        PinActivity.dismiss();
        main.post(() -> {
            MirrorActivity.finishIfShowing();
            mirroring = false;
            title = artist = album = null;
            durationMs = 0;
            setPlaying(false);
        });
    }

    // ---- UxPlay: pairing --------------------------------------------------

    /**
     * The code goes over whatever is on screen, screensaver included: it is
     * only asked for when someone is trying to connect right now.
     */
    @Override
    public void onPinRequested(String pin) {
        main.post(() -> {
            wakeFromDream();
            startActivity(new Intent(this, PinActivity.class)
                    .putExtra(PinActivity.EXTRA_PIN, pin)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        });
    }

    @Override
    public void onPaired(String name) {
        PinActivity.dismiss();
    }

    // ---- UxPlay: video ----------------------------------------------------

    @Override
    public void onVideoCodec(boolean h265) {
        video.setCodec(h265);
        main.post(this::showMirroring);
    }

    @Override
    public void onVideoFrame(byte[] frame, long ptsNanos, boolean h265) {
        video.queueFrame(frame, ptsNanos, h265);
        if (!mirroring) {
            main.post(this::showMirroring);
        }
    }

    @Override
    public void onVideoReset() {
        video.reset();
    }

    @Override
    public void onVideoSize(int width, int height) {
    }

    /**
     * Mirroring takes the screen: whatever was showing, including the
     * screensaver, gives way to it. That is an activity start from a
     * background service, which needs START_ACTIVITIES_FROM_BACKGROUND, and
     * waking a dream needs WRITE_DREAM_STATE; both are in the privapp
     * allowlist.
     */
    private void showMirroring() {
        if (mirroring) {
            return;
        }
        mirroring = true;
        wakeFromDream();
        startActivity(new Intent(this, MirrorActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    /** Needs WRITE_DREAM_STATE, which is in the privapp allowlist. */
    private void wakeFromDream() {
        try {
            IDreamManager dreams = IDreamManager.Stub.asInterface(
                    ServiceManager.getService(DreamService.DREAM_SERVICE));
            if (dreams != null) {
                dreams.awaken();
            }
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "could not wake from the screensaver", e);
        }
    }

    // ---- UxPlay: audio ----------------------------------------------------

    @Override
    public void onAudioFormat(int compressionType, int samplesPerFrame, boolean usingScreen) {
        audio.configure(compressionType);
        audio.start();
        main.post(() -> {
            setPlaying(true);
            if (!usingScreen) {
                showNowPlayingIfIdle();
            }
        });
    }

    @Override
    public void onAudioPcm(byte[] pcm, long ptsNanos) {
        audio.queuePcm(pcm, ptsNanos);
    }

    @Override
    public void onAudioEncoded(byte[] frame, long ptsNanos, int compressionType) {
        audio.queueEncoded(frame, ptsNanos);
    }

    @Override
    public void onAudioFlush() {
        audio.flush();
    }

    @Override
    public void onVolume(float airplayDb) {
        audio.setVolume(airplayDb);
    }

    // ---- UxPlay: metadata -------------------------------------------------

    /** DAAP tags, as SET_PARAMETER carries them: usually inside an 'mlit'. */
    @Override
    public void onMetadata(byte[] dmap) {
        if (dmap == null) {
            return;
        }
        String[] fields = new String[3];
        long[] duration = {0};
        parseDmap(dmap, 0, dmap.length, fields, duration);
        main.post(() -> {
            if (!TextUtils.equals(fields[0], title) || !TextUtils.equals(fields[2], album)) {
                artwork = null; // the old cover belongs to the old track
                positionMs = 0;
            }
            title = fields[0];
            artist = fields[1];
            album = fields[2];
            durationMs = duration[0];
            Log.i(TAG, "track: " + title + " / " + artist + " / " + album);
            publishMetadata();
            publishState();
            updateNotification();
        });
    }

    private static void parseDmap(byte[] d, int off, int end, String[] fields, long[] duration) {
        while (off + 8 <= end) {
            String tag = new String(d, off, 4, StandardCharsets.US_ASCII);
            int len = ((d[off + 4] & 0xFF) << 24) | ((d[off + 5] & 0xFF) << 16)
                    | ((d[off + 6] & 0xFF) << 8) | (d[off + 7] & 0xFF);
            int value = off + 8;
            if (len < 0 || value + len > end) {
                return;
            }
            switch (tag) {
                case "mlit":
                    parseDmap(d, value, value + len, fields, duration);
                    break;
                case "minm":
                    fields[0] = new String(d, value, len, StandardCharsets.UTF_8);
                    break;
                case "asar":
                    fields[1] = new String(d, value, len, StandardCharsets.UTF_8);
                    break;
                case "asal":
                    fields[2] = new String(d, value, len, StandardCharsets.UTF_8);
                    break;
                case "astm":
                    if (len == 4) {
                        duration[0] = ((d[value] & 0xFFL) << 24) | ((d[value + 1] & 0xFFL) << 16)
                                | ((d[value + 2] & 0xFFL) << 8) | (d[value + 3] & 0xFFL);
                    }
                    break;
                default:
                    break;
            }
            off = value + len;
        }
    }

    @Override
    public void onCoverArt(byte[] jpeg) {
        Bitmap bitmap = jpeg == null ? null : BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        main.post(() -> {
            artwork = bitmap;
            publishMetadata();
        });
    }

    /**
     * Three RTP timestamps at 44.1 kHz. They are 32-bit and senders start
     * them at random, so a track can wrap mid-play; the distances are taken
     * modulo 2^32.
     */
    @Override
    public void onProgress(long start, long current, long end) {
        long position = ((current - start) & 0xFFFFFFFFL) * 1000L / 44100;
        long span = ((end - start) & 0xFFFFFFFFL) * 1000L / 44100;
        main.post(() -> {
            positionMs = position;
            if (durationMs <= 0 && span > 0) {
                durationMs = span;
                publishMetadata();
            }
            publishState();
        });
    }

    // ---- state ------------------------------------------------------------

    private void setPlaying(boolean nowPlaying) {
        playing = nowPlaying;
        if (nowPlaying) {
            requestFocus();
        } else {
            abandonFocus();
            artwork = null;
            positionMs = 0;
        }
        publishMetadata();
        publishState();
        updateNotification();
    }

    private void publishMetadata() {
        if (session == null) {
            return;
        }
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
        if (durationMs > 0) {
            b.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs);
        }
        if (artwork != null) {
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork);
        }
        // The sending device, so the screen can say where the music came from.
        if (!TextUtils.isEmpty(clientName)) {
            b.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, clientName);
        }
        session.setMetadata(b.build());
    }

    private void publishState() {
        if (session == null) {
            return;
        }
        // The position is paired with the time it was taken, so a client can
        // extrapolate between the once-a-second updates instead of stepping.
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_STOPPED,
                        positionMs, playing ? 1.0f : 0.0f, SystemClock.elapsedRealtime())
                .build());
    }

    // ---- screen -----------------------------------------------------------

    /**
     * Put the now-playing panel on screen when music starts while the box is
     * sitting on its home screen. AmbientDream is where the panel lives, and
     * otherwise it only appears after the screensaver timeout. Only from the
     * home screen itself: in an app, or with the panel open, the screen is
     * left alone. Needs WRITE_DREAM_STATE and REAL_GET_TASKS, both
     * allowlisted.
     */
    private void showNowPlayingIfIdle() {
        try {
            final PowerManager power = getSystemService(PowerManager.class);
            if (power == null || !power.isInteractive()) {
                return; // asleep: waking the television for this would be rude
            }
            // Also covers "already dreaming": a dream runs as DreamActivity
            // on top, so the home screen is not in front then.
            if (!homeIsInFront()) {
                return;
            }
            final IDreamManager dreams = IDreamManager.Stub.asInterface(
                    ServiceManager.getService(DreamService.DREAM_SERVICE));
            if (dreams == null) {
                return;
            }
            Log.i(TAG, "stream started on the home screen; showing the now-playing panel");
            dreams.dream();
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "could not start the screensaver", e);
        }
    }

    /** The home activity itself, not merely the launcher's package. */
    private boolean homeIsInFront() {
        final ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (home == null || home.activityInfo == null) {
            return false;
        }
        final List<ActivityManager.RunningTaskInfo> tasks =
                getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks.isEmpty() || tasks.get(0).topActivity == null) {
            return false;
        }
        final ComponentName top = tasks.get(0).topActivity;
        return top.getPackageName().equals(home.activityInfo.packageName)
                && top.getClassName().equals(home.activityInfo.name);
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
