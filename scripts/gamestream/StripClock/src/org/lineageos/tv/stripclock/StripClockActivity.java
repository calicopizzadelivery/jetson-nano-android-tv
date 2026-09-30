/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.stripclock;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.View;
import android.view.WindowManager;

/**
 * pattern.py's timing strip, drawn on the Jetson from its own clock.
 *
 * The strip carries (Jetson time + offset_ms) in milliseconds and a frame
 * counter, in the same 34-block layout, and is redrawn every display frame.
 * With offset_ms set to thebe's clock minus the Jetson's, measure.py reads it
 * off HDMI exactly as it reads a streamed strip, and the result is the
 * Jetson's own draw-to-screen time plus the capture card: everything in a
 * streamed measurement that is not streaming.
 *
 *   adb shell am start -n org.lineageos.tv.stripclock/.StripClockActivity --el offset_ms N
 */
public class StripClockActivity extends Activity {

    private static final int BITS_TIME = 24;
    private static final int BITS_COUNT = 8;
    private static final int BLOCKS = 2 + BITS_TIME + BITS_COUNT;

    private long offsetMs;
    private StripView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        offsetMs = getIntent().getLongExtra("offset_ms", 0);
        view = new StripView(this);
        setContentView(view);
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                view.invalidate();
                Choreographer.getInstance().postFrameCallback(this);
            }
        });
    }

    private final class StripView extends View {
        private final Paint white = new Paint();
        private final Paint black = new Paint();
        private int count;

        StripView(Context context) {
            super(context);
            white.setColor(Color.WHITE);
            black.setColor(Color.BLACK);
            setBackgroundColor(Color.rgb(48, 56, 72));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            int strip = h / 18;
            long now = System.currentTimeMillis() + offsetMs;
            long value = ((now & ((1L << BITS_TIME) - 1)) << BITS_COUNT) | (count++ & 0xFF);
            for (int i = 0; i < BLOCKS; i++) {
                boolean on = i == 0 || (i != 1 && ((value >> (BLOCKS - 1 - i)) & 1) == 1);
                float x0 = (float) i * w / BLOCKS;
                float x1 = (float) (i + 1) * w / BLOCKS;
                canvas.drawRect(x0, h - strip, x1 + 1, h, on ? white : black);
            }
        }
    }
}
