/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.ambient;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A clock over a slow slideshow of NASA photographs.
 *
 * The box this runs on is expected to always be driving a television, so the
 * dream is the resting state rather than a step towards a blank screen. That
 * shapes two decisions: it never gives up and shows nothing, and it keeps a
 * local cache so an unreachable network changes nothing the viewer can see.
 */
public class AmbientDreamService extends DreamService implements NowPlaying.Listener {

    private static final String TAG = "AmbientDream";

    /** How long a photograph stays up. */
    private static final long DWELL_MS = 30 * 60_000L;

    /** Conditions go stale faster than the picture changes. */
    private static final long WEATHER_MS = 10 * 60_000L;
    private static final long FADE_MS = 2_000L;

    /** How often the progress bar is nudged along while something plays. */
    private static final long PROGRESS_MS = 1_000L;

    /** Roughly a day of viewing before anything repeats. */
    private static final int CACHE_MAX = 40;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final List<NasaFeed.Photo> mQueue = new ArrayList<>();

    private ImageView mFront;
    private ImageView mBack;
    private TextView mCredit;
    private TextView mCreditAttribution;
    private TextView mWeather;
    private ImageView mWeatherIcon;

    private View mNowPlaying;
    private View mNowPlayingScrim;
    private ImageView mNowPlayingArt;
    private TextView mNowPlayingSource;
    private TextView mNowPlayingTitle;
    private TextView mNowPlayingArtist;
    private ProgressBar mNowPlayingProgress;

    private NowPlaying mNowPlayingWatcher;
    private NowPlaying.State mNowPlayingState;

    private int mIndex;
    private boolean mRunning;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        setInteractive(false);
        setFullscreen(true);
        // The panel stays lit: this is what the television is showing, not a
        // low-power state on the way to off.
        setScreenBright(true);
        setContentView(R.layout.dream);

        mFront = findViewById(R.id.imageA);
        mBack = findViewById(R.id.imageB);
        mCredit = findViewById(R.id.credit);
        mCreditAttribution = findViewById(R.id.creditAttribution);
        mWeather = findViewById(R.id.weather);
        mWeatherIcon = findViewById(R.id.weatherIcon);

        mNowPlaying = findViewById(R.id.nowPlaying);
        mNowPlayingScrim = findViewById(R.id.nowPlayingScrim);
        mNowPlayingArt = findViewById(R.id.nowPlayingArt);
        mNowPlayingSource = findViewById(R.id.nowPlayingSource);
        mNowPlayingTitle = findViewById(R.id.nowPlayingTitle);
        mNowPlayingArtist = findViewById(R.id.nowPlayingArtist);
        mNowPlayingProgress = findViewById(R.id.nowPlayingProgress);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mRunning = true;
        mIo.execute(this::loadQueue);
        refreshWeather();
        mNowPlayingWatcher = new NowPlaying(this, this);
        mNowPlayingWatcher.start();
    }

    @Override
    public void onDreamingStopped() {
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
        if (mNowPlayingWatcher != null) {
            mNowPlayingWatcher.stop();
            mNowPlayingWatcher = null;
        }
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        mIo.shutdownNow();
        super.onDetachedFromWindow();
    }

    /**
     * Build the playlist. Anything already cached is usable immediately, so the
     * first photo appears without waiting on the network; the fetch then tops
     * the cache up for later.
     */
    private void loadQueue() {
        final List<NasaFeed.Photo> found = NasaFeed.search(NasaFeed.randomTopic());
        synchronized (mQueue) {
            mQueue.clear();
            mQueue.addAll(found);
            Collections.shuffle(mQueue);
        }

        if (found.isEmpty()) {
            // No network, or the search failed. Whatever is on disk is the show.
            final File[] cached = cachedFiles();
            Log.i(TAG, "no results; falling back to " + cached.length + " cached images");
            mHandler.post(() -> {
                if (cached.length > 0) {
                    mCredit.setText(R.string.credit_offline);
                    mCreditAttribution.setVisibility(View.GONE);
                }
            });
        }

        trimCache();
        mHandler.post(this::showNext);
    }

    private void showNext() {
        if (!mRunning) {
            return;
        }
        mIo.execute(() -> {
            final NasaFeed.Photo photo = pick();
            final Bitmap bitmap = photo != null ? loadOrFetch(photo) : loadAnyCached();
            if (bitmap == null) {
                // Nothing to show yet. Try again rather than sitting on black.
                mHandler.postDelayed(this::showNext, 5_000L);
                return;
            }
            mHandler.post(() -> {
                crossfadeTo(bitmap);
                if (photo != null && !TextUtils.isEmpty(photo.title)) {
                    mCredit.setText(photo.title);
                    // Not every item names a center, and "· public domain"
                    // hanging off nothing reads as a bug.
                    mCreditAttribution.setText(TextUtils.isEmpty(photo.center)
                            ? getString(R.string.credit_attribution_nocenter)
                            : getString(R.string.credit_attribution, photo.center));
                    mCreditAttribution.setVisibility(View.VISIBLE);
                }
                mHandler.postDelayed(this::showNext, DWELL_MS);
            });
        });
    }

    private NasaFeed.Photo pick() {
        synchronized (mQueue) {
            if (mQueue.isEmpty()) {
                return null;
            }
            return mQueue.get(mIndex++ % mQueue.size());
        }
    }

    /** Cache hit, else download and cache. Null when both fail. */
    private Bitmap loadOrFetch(NasaFeed.Photo photo) {
        final File file = new File(getCacheDir(), photo.cacheName());
        if (file.exists() && file.length() > 0) {
            final Bitmap cached = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (cached != null) {
                return cached;
            }
            // Truncated or not an image; drop it and re-fetch.
            file.delete();
        }

        try {
            final String url = NasaFeed.resolveImageUrl(photo);
            if (url == null) {
                return loadAnyCached();
            }
            final byte[] bytes = NasaFeed.fetch(url);
            final Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) {
                return loadAnyCached();
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
            return bitmap;
        } catch (Exception e) {
            Log.i(TAG, "fetch failed, using cache: " + e);
            return loadAnyCached();
        }
    }

    private Bitmap loadAnyCached() {
        final File[] files = cachedFiles();
        if (files.length == 0) {
            return null;
        }
        final File pickFile = files[(int) (Math.random() * files.length)];
        return BitmapFactory.decodeFile(pickFile.getAbsolutePath());
    }

    private File[] cachedFiles() {
        final File[] files = getCacheDir().listFiles(
                (dir, name) -> name.endsWith(".jpg"));
        return files == null ? new File[0] : files;
    }

    /** Keep the cache bounded; oldest out first. */
    private void trimCache() {
        final File[] files = cachedFiles();
        if (files.length <= CACHE_MAX) {
            return;
        }
        java.util.Arrays.sort(files,
                (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - CACHE_MAX; i++) {
            files[i].delete();
        }
    }

    /**
     * Conditions under the clock. Failure is silent: an empty line is better
     * than an error message on a television.
     */
    private void refreshWeather() {
        if (!mRunning) {
            return;
        }
        mIo.execute(() -> {
            final Weather.Conditions conditions = Weather.fetch(this, true);
            mHandler.post(() -> {
                if (conditions != null) {
                    mWeather.setText(conditions.text);
                    if (conditions.icon != 0) {
                        mWeatherIcon.setImageResource(conditions.icon);
                        mWeatherIcon.setVisibility(View.VISIBLE);
                    } else {
                        mWeatherIcon.setVisibility(View.GONE);
                    }
                }
                mHandler.postDelayed(this::refreshWeather, WEATHER_MS);
            });
        });
    }

    /**
     * Callbacks arrive on the main looper, so this touches views directly.
     * Called for every metadata and transport change, so it has to be cheap
     * and idempotent -- a track with a long title fires several in a row as
     * the pieces land.
     */
    @Override
    public void onNowPlayingChanged(NowPlaying.State state) {
        mNowPlayingState = state;
        if (!mRunning) {
            return;
        }
        if (state == null) {
            mNowPlaying.setVisibility(View.GONE);
            mNowPlayingScrim.setVisibility(View.GONE);
            mHandler.removeCallbacks(mProgressTick);
            return;
        }

        mNowPlayingTitle.setText(state.title);
        mNowPlayingArtist.setText(state.artist);
        mNowPlayingArtist.setVisibility(
                TextUtils.isEmpty(state.artist) ? View.GONE : View.VISIBLE);
        mNowPlayingSource.setText(state.source);
        mNowPlayingSource.setVisibility(
                TextUtils.isEmpty(state.source) ? View.GONE : View.VISIBLE);

        if (state.artwork != null) {
            mNowPlayingArt.setImageBitmap(state.artwork);
            mNowPlayingArt.setVisibility(View.VISIBLE);
        } else {
            // No art yet, or none at all. Keep the tile rather than reflowing
            // the row: for AirPlay the cover arrives a moment after the title,
            // and the text jumping left then right is worse than a blank.
            mNowPlayingArt.setImageDrawable(null);
        }

        mNowPlaying.setVisibility(View.VISIBLE);
        mNowPlayingScrim.setVisibility(View.VISIBLE);

        mHandler.removeCallbacks(mProgressTick);
        mProgressTick.run();
    }

    private final Runnable mProgressTick = new Runnable() {
        @Override
        public void run() {
            final NowPlaying.State state = mNowPlayingState;
            if (!mRunning || state == null) {
                return;
            }
            if (state.durationMs <= 0) {
                mNowPlayingProgress.setVisibility(View.GONE);
                return;
            }
            final long position = Math.max(0, Math.min(state.positionNowMs(), state.durationMs));
            mNowPlayingProgress.setProgress(
                    (int) (position * mNowPlayingProgress.getMax() / state.durationMs));
            mNowPlayingProgress.setVisibility(View.VISIBLE);
            mHandler.postDelayed(this, PROGRESS_MS);
        }
    };

    private void crossfadeTo(Bitmap bitmap) {
        mBack.setImageBitmap(bitmap);
        mBack.setAlpha(0f);
        mBack.animate()
                .alpha(1f)
                .setDuration(FADE_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // Swap roles so the next fade goes the other way and the
                        // pair keeps alternating without reallocating bitmaps.
                        final ImageView shown = mBack;
                        mBack = mFront;
                        mFront = shown;
                        mBack.setAlpha(0f);
                    }
                })
                .start();
    }
}
