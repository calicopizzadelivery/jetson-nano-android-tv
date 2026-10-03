/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jetsontv.keyhandler;

import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

/**
 * Makes a game controller's Guide button go Home, when the user wants it to.
 *
 * A controller's big centre button -- BTN_MODE, which Android calls
 * KEYCODE_BUTTON_MODE -- is the natural "take me back" button on a television,
 * and that is what SHIELD's own remote and controller do. Android does nothing
 * with that keycode on its own, so without this it is dead on the home screen.
 *
 * Mapping it to HOME in the key layout would work, but it is not reversible:
 * Android consumes HOME before any app sees it, so Moonlight could never hand
 * it to a host as the Guide button and an emulator could never bind it. The
 * key layout therefore keeps the honest BUTTON_MODE, and the choice lives
 * here, behind a setting, where it can be turned off.
 *
 * Settings.Secure "jetsontv_gamepad_home_button": 1 (the default) goes Home,
 * 0 passes the button through to whatever app is in front.
 *
 * This runs inside system_server, on the input dispatcher's thread, so it does
 * as little as possible and never blocks.
 */
public class GamepadKeyHandler implements DeviceKeyHandler {

    private static final String TAG = "JetsonTVKeyHandler";

    /** 1: the Guide button goes Home. 0: apps get it. */
    public static final String SETTING_HOME_BUTTON = "jetsontv_gamepad_home_button";

    private final Context mContext;

    public GamepadKeyHandler(Context context) {
        mContext = context;
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_MODE || !goesHome()) {
            return event;
        }
        // Consume both halves, so the app never sees a stray down without an
        // up, and act on the release: that is when a button press is complete,
        // and it matches how the framework treats HOME.
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            goHome();
        }
        return null;
    }

    private boolean goesHome() {
        try {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    SETTING_HOME_BUTTON, 1, UserHandle.USER_CURRENT) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void goHome() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            mContext.startActivityAsUser(home, UserHandle.CURRENT);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not go home from the controller's Guide button", e);
        }
    }
}
