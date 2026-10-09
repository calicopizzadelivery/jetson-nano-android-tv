/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.jetsontv.keyhandler;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.DeviceKeyHandler;

/**
 * Two buttons that Android would otherwise waste: a controller's Guide
 * button, and the SHIELD remote's Netflix key.
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
 * The second is KEYCODE_BUTTON_4, which is where every SHIELD remote's
 * Netflix key lands (keycode 393 in Vendor_0955_Product_72xx.kl). It is a
 * labelled, prominent button that nothing in this build handles, and we have
 * no Netflix to give it. Settings.Secure "jetsontv_button4_package" names the
 * package to launch instead; it defaults to Kodi. Setting it to an empty
 * string passes the key through, and a package that is not installed does the
 * same rather than swallowing the press.
 *
 * This runs inside system_server, on the input dispatcher's thread, so it does
 * as little as possible and never blocks.
 */
public class GamepadKeyHandler implements DeviceKeyHandler {

    private static final String TAG = "JetsonTVKeyHandler";

    /** 1: the Guide button goes Home. 0: apps get it. */
    public static final String SETTING_HOME_BUTTON = "jetsontv_gamepad_home_button";

    /** Package the remote's Netflix key launches. Empty: apps get the key. */
    public static final String SETTING_BUTTON4_PACKAGE = "jetsontv_button4_package";

    private static final String DEFAULT_BUTTON4_PACKAGE = "org.xbmc.kodi";

    private final Context mContext;

    // Resolving a launch intent is a binder call to PackageManager, and this
    // runs on the input dispatcher's thread, so the answer is cached and only
    // recomputed when the setting's value actually changes.
    private String mCachedPackage;
    private Intent mCachedIntent;

    public GamepadKeyHandler(Context context) {
        mContext = context;
    }

    @Override
    public KeyEvent handleKeyEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_MODE:
                if (!goesHome()) {
                    return event;
                }
                // Consume both halves, so the app never sees a stray down
                // without an up, and act on the release: that is when a button
                // press is complete, and it matches how the framework treats
                // HOME.
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    goHome();
                }
                return null;

            case KeyEvent.KEYCODE_BUTTON_4:
                Intent launch = button4Intent();
                if (launch == null) {
                    return event;
                }
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    launch(launch);
                }
                return null;

            default:
                return event;
        }
    }

    private boolean goesHome() {
        try {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    SETTING_HOME_BUTTON, 1, UserHandle.USER_CURRENT) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * The intent the Netflix key should fire, or null to leave the key alone.
     */
    private Intent button4Intent() {
        String pkg;
        try {
            pkg = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    SETTING_BUTTON4_PACKAGE, UserHandle.USER_CURRENT);
        } catch (RuntimeException e) {
            pkg = null;
        }
        if (pkg == null) {
            pkg = DEFAULT_BUTTON4_PACKAGE;
        }
        pkg = pkg.trim();
        if (pkg.isEmpty()) {
            mCachedPackage = "";
            mCachedIntent = null;
            return null;
        }
        if (pkg.equals(mCachedPackage)) {
            return mCachedIntent;
        }
        PackageManager pm = mContext.getPackageManager();
        // Prefer the leanback entry point: on a television an app's phone
        // launcher activity is often the wrong one, or absent.
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        mCachedPackage = pkg;
        mCachedIntent = intent;
        return intent;
    }

    private void launch(Intent intent) {
        try {
            mContext.startActivityAsUser(intent, UserHandle.CURRENT);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not launch from the remote's Netflix key", e);
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
