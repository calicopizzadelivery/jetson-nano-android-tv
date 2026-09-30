/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Brings the service back after a reboot.
 *
 * The enable flag is a persist. property, so init restarts the daemon on its
 * own -- and that is exactly the problem this closes. Without something to
 * start the service alongside it, a box rebooted with the receiver on comes up
 * accepting streams that hold no audio focus and publish no MediaSession: it
 * plays over whatever else is running and shows nothing on screen. The two
 * have to come back together.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AirPlay";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!SystemProperties.getBoolean(AirPlayService.PROP_ENABLED, false)) {
            return;
        }
        Log.i(TAG, "receiver was left enabled; starting the service");
        context.startForegroundService(
                new Intent(AirPlayService.ACTION_START).setClass(context, AirPlayService.class));
    }
}
