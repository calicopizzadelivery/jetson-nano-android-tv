/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.lineageos.tv.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** A paired device, as the settings list it. */
    static final class Device {
        final String key;
        final String id;
        /** Empty if the device never sent one. */
        final String name;

        Device(String key, String id, String name) {
            this.key = key;
            this.id = id;
            this.name = name;
        }
    }

    /** Every paired device, ordered by name. */
    List<Device> devices() {
        List<Device> devices = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String value = String.valueOf(e.getValue());
            int tab = value.indexOf('\t');
            devices.add(new Device(e.getKey(),
                    tab < 0 ? value : value.substring(0, tab),
                    tab < 0 ? "" : value.substring(tab + 1)));
        }
        devices.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return devices;
    }

    /** It needs the PIN again next time. A stream it has open carries on. */
    void forget(String publicKey) {
        String old = prefs.getString(publicKey, null);
        if (old == null) {
            return;
        }
        prefs.edit().remove(publicKey).apply();
        Log.i(TAG, "forgot " + old.replace('\t', ' ').trim() + "; it needs the PIN again");
    }

    void forgetAll() {
        int n = size();
        prefs.edit().clear().apply();
        Log.i(TAG, "forgot " + n + " paired device(s): each needs the PIN again");
    }
}
