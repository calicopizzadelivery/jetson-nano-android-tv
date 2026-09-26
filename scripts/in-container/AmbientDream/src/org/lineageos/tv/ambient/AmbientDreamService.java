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
import android.widget.ImageView;
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
public class AmbientDreamService extends DreamService {

    private static final String TAG = "AmbientDream";

    /** How long a photograph stays up. */
    private static final long DWELL_MS = 30 * 60_000L;

    /** Conditions go stale faster than the picture changes. */
    private static final long WEATHER_MS = 10 * 60_000L;
    private static final long FADE_MS = 2_000L;

    /** Roughly a day of viewing before anything repeats. */
    private static final int CACHE_MAX = 40;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private final List<NasaFeed.Photo> mQueue = new ArrayList<>();

    private ImageView mFront;
    private ImageView mBack;
    private TextView mCredit;
    private TextView mWeather;

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
        mWeather = findViewById(R.id.weather);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mRunning = true;
        mIo.execute(this::loadQueue);
        refreshWeather();
    }

    @Override
    public void onDreamingStopped() {
        mRunning = false;
        mHandler.removeCallbacksAndMessages(null);
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
                    mCredit.setText(getString(R.string.credit_format,
                            photo.title, photo.center));
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
                }
                mHandler.postDelayed(this::refreshWeather, WEATHER_MS);
            });
        });
    }

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
