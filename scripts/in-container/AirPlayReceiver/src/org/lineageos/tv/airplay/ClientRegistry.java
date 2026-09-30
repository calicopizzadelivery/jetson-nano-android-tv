/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The devices that have paired with the PIN, so they need it only once.
 *
 * A device is known by the Ed25519 public key it proved the PIN with, base64
 * encoded, which is what pair-verify presents when it comes back. The device
 * id and name are kept beside it for the log and for whoever reads the prefs
 * file; only the key decides anything.
 *
 * Read from UxPlay's threads during pair-verify, so every access goes
 * through SharedPreferences, which is safe for that.
 */
final class ClientRegistry {

    private static final String TAG = "AirPlay";
    private static final String PREFS = "airplay_clients";

    private final SharedPreferences prefs;

    ClientRegistry(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean contains(String publicKey) {
        return publicKey != null && prefs.contains(publicKey);
    }

    /** A null name keeps the one already recorded: SETUP brings it later. */
    void remember(String publicKey, String deviceId, String name) {
        String old = prefs.getString(publicKey, null);
        if (name == null && old != null) {
            int tab = old.indexOf('\t');
            name = tab < 0 ? null : old.substring(tab + 1);
        }
        String value = deviceId + "\t" + (name == null ? "" : name);
        if (value.equals(old)) {
            return;
        }
        prefs.edit().putString(publicKey, value).apply();
        Log.i(TAG, (old == null ? "remembering " : "updating ") + deviceId
                + (name == null ? "" : " (\"" + name + "\")") + "; "
                + prefs.getAll().size() + " device(s) paired");
    }

    int size() {
        return prefs.getAll().size();
    }

    void forgetAll() {
        int n = size();
        prefs.edit().clear().apply();
        Log.i(TAG, "forgot " + n + " paired device(s): each needs the PIN again");
    }
}
