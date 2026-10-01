/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.Manifest;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;

import java.util.List;

/**
 * The receiver's pairing settings, for the launcher's Streaming tile: whether
 * new devices must enter the code, and which devices have paired. The state
 * stays in this app, which acts on it, so the tile and anything else that
 * shows it cannot disagree.
 *
 * Only call() is implemented:
 *
 *   get                                   the state
 *   set_require_pin  extras "value"        boolean; a running receiver restarts
 *   forget           arg: a device's key   or no arg, for every device
 *
 * Each returns the state afterwards: "enabled" and "require_pin" (booleans),
 * and the paired devices as parallel "paired_keys" and "paired_names" string
 * arrays, ordered by name. A name is empty if the device never sent one.
 *
 * Callers need WRITE_SECURE_SETTINGS, as for starting and stopping the
 * service. The manifest has the system check it when the provider is
 * acquired, and call() checks it again: the platform applies a provider's
 * permissions to queries and writes, not to call().
 *
 *   adb shell content call --uri content://org.lineageos.tv.airplay.settings --method get
 */
public final class AirPlaySettingsProvider extends ContentProvider {

    static final String AUTHORITY = "org.lineageos.tv.airplay.settings";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        getContext().enforceCallingPermission(Manifest.permission.WRITE_SECURE_SETTINGS,
                "the AirPlay receiver's settings");
        ClientRegistry registry = new ClientRegistry(getContext());
        switch (method) {
            case "get":
                break;
            case "set_require_pin":
                if (extras == null || !extras.containsKey("value")) {
                    throw new IllegalArgumentException("set_require_pin needs a boolean \"value\"");
                }
                AirPlayService.setRequirePin(extras.getBoolean("value"));
                break;
            case "forget":
                if (arg == null) {
                    registry.forgetAll();
                } else {
                    registry.forget(arg);
                }
                break;
            default:
                throw new IllegalArgumentException("unknown method " + method);
        }
        return state(registry);
    }

    private static Bundle state(ClientRegistry registry) {
        List<ClientRegistry.Device> devices = registry.devices();
        String[] keys = new String[devices.size()];
        String[] names = new String[devices.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = devices.get(i).key;
            names[i] = devices.get(i).name;
        }
        Bundle state = new Bundle();
        state.putBoolean("enabled", SystemProperties.getBoolean(AirPlayService.PROP_ENABLED, false));
        state.putBoolean("require_pin", AirPlayService.requirePin());
        state.putStringArray("paired_keys", keys);
        state.putStringArray("paired_names", names);
        return state;
    }

    // Nothing here is a table.

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
