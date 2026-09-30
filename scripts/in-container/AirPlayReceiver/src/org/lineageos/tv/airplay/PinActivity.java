/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The code a phone or Mac asks for the first time it uses AirPlay here.
 *
 * Shown over whatever is on screen when a device asks to pair, and gone when
 * it has paired, when it gives up, or when the code expires -- two minutes,
 * the same as the receiver's own limit on it. A new request replaces the
 * code in place. Back just hides it; the code stays valid until it expires.
 */
public class PinActivity extends Activity {

    static final String EXTRA_PIN = "pin";

    /** Matches PIN_LIFETIME_NS in UxPlay's raop_handlers.h. */
    private static final long SHOW_MS = 120_000;

    private static volatile PinActivity current;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView pinView;

    static void dismiss() {
        PinActivity a = current;
        if (a != null) {
            a.runOnUiThread(a::finish);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xD9000000);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(48);
        card.setPadding(pad, dp(40), pad, dp(40));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1C2029);
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);

        TextView title = text(getString(R.string.pin_title), 20, 0xFFB8C0CC);
        title.setAllCaps(true);
        title.setLetterSpacing(0.15f);
        card.addView(title);

        pinView = text("", 112, Color.WHITE);
        pinView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pinView.setLetterSpacing(0.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(8);
        card.addView(pinView, lp);

        String name = SystemProperties.get("persist.jetsontv.airplay.name", "Jetson TV");
        // A fixed width: with only a maximum, the card measured the text at
        // one width and laid it out at another, and the last line was cut.
        TextView body = text(getString(R.string.pin_body, name), 22, 0xFFDDE2EA);
        card.addView(body, new LinearLayout.LayoutParams(
                dp(560), LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(card, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(root);
        current = this;
        show(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        show(intent);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void show(Intent intent) {
        String pin = intent == null ? null : intent.getStringExtra(EXTRA_PIN);
        if (pin == null) {
            finish();
            return;
        }
        pinView.setText(pin);
        pinView.setContentDescription(String.join(" ", pin.split("")));
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::finish, SHOW_MS);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
