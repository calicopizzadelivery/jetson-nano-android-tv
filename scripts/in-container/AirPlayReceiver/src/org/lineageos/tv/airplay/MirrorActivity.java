/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The screen a Mac or iPhone is mirrored onto.
 *
 * Started by AirPlayService when a mirroring stream begins, and finished by it
 * when the stream ends. Its only jobs are to own a Surface for the video
 * renderer, fit the picture to the sender's aspect ratio, and let the remote
 * end the mirroring: Back drops the sender, which the phone shows as
 * mirroring stopped.
 */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback,
        VideoRenderer.SizeListener {

    private static volatile MirrorActivity current;

    private SurfaceView surfaceView;
    private int videoWidth = 16;
    private int videoHeight = 9;

    static void finishIfShowing() {
        MirrorActivity a = current;
        if (a != null) {
            a.runOnUiThread(a::finish);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitSurface());
        setContentView(root);
        surfaceView.getHolder().addCallback(this);
        current = this;
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        VideoRenderer video = AirPlayService.video();
        if (video != null) {
            video.setSizeListener(null);
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Ending it here rather than just hiding it: a mirrored screen
            // behind the launcher would keep the sender streaming for nothing.
            AirPlayService.endSessionFromTv(this);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- surface ----------------------------------------------------------

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        VideoRenderer video = AirPlayService.video();
        if (video != null) {
            video.setSizeListener(this);
            video.setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        VideoRenderer video = AirPlayService.video();
        if (video != null) {
            video.setSurface(null);
        }
    }

    @Override
    public void onVideoSizeChanged(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        runOnUiThread(() -> {
            videoWidth = width;
            videoHeight = height;
            fitSurface();
        });
    }

    /** Letterbox or pillarbox: a phone mirrored upright is taller than wide. */
    private void fitSurface() {
        FrameLayout root = (FrameLayout) surfaceView.getParent();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int fitW = w;
        int fitH = (int) ((long) w * videoHeight / videoWidth);
        if (fitH > h) {
            fitH = h;
            fitW = (int) ((long) h * videoWidth / videoHeight);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != fitW || lp.height != fitH) {
            lp.width = fitW;
            lp.height = fitH;
            lp.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(lp);
        }
    }
}
