/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.ambient;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * Current conditions from Open-Meteo, which needs no API key and no account.
 *
 * Location is resolved once and remembered. A set-top box does not move, so
 * repeating an IP lookup on every boot would send the address to a third party
 * for no benefit. Setting {@link #PREF_PLACE} to a place name skips the IP
 * lookup entirely, which is the better answer for a fixed installation.
 */
final class Weather {

    private static final String TAG = "AmbientDream";

    private static final String PREFS = "ambient";
    static final String PREF_PLACE = "place";
    private static final String PREF_LAT = "lat";
    private static final String PREF_LON = "lon";
    private static final String PREF_CITY = "city";

    private static final String GEOCODE =
            "https://geocoding-api.open-meteo.com/v1/search?count=1&name=";
    private static final String BY_IP =
            "https://ipwho.is/?fields=success,city,latitude,longitude";
    private static final String FORECAST =
            "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code";

    static final class Conditions {
        final String text;

        Conditions(String text) {
            this.text = text;
        }
    }

    private Weather() {
    }

    /** Null when the location is unknown and cannot be resolved. */
    static Conditions fetch(Context context, boolean fahrenheit) {
        final SharedPreferences prefs =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (!prefs.contains(PREF_LAT) && !resolveLocation(prefs)) {
            return null;
        }

        final String lat = prefs.getString(PREF_LAT, null);
        final String lon = prefs.getString(PREF_LON, null);
        final String city = prefs.getString(PREF_CITY, "");
        if (lat == null || lon == null) {
            return null;
        }

        try {
            final String url = FORECAST
                    + "&latitude=" + lat
                    + "&longitude=" + lon
                    + "&temperature_unit=" + (fahrenheit ? "fahrenheit" : "celsius");
            final JSONObject current = new JSONObject(NasaFeed.getString(url))
                    .getJSONObject("current");

            final long degrees = Math.round(current.optDouble("temperature_2m", Double.NaN));
            final String described = describe(current.optInt("weather_code", -1));

            final StringBuilder sb = new StringBuilder();
            sb.append(degrees).append('°');
            if (!described.isEmpty()) {
                sb.append("  ").append(described);
            }
            if (!city.isEmpty()) {
                sb.append("  ·  ").append(city);
            }
            return new Conditions(sb.toString());
        } catch (Exception e) {
            Log.i(TAG, "weather unavailable: " + e);
            return null;
        }
    }

    private static boolean resolveLocation(SharedPreferences prefs) {
        final String place = prefs.getString(PREF_PLACE, "");
        try {
            final JSONObject found;
            if (!place.isEmpty()) {
                found = new JSONObject(NasaFeed.getString(
                        GEOCODE + java.net.URLEncoder.encode(place, "UTF-8")))
                        .getJSONArray("results").getJSONObject(0);
                store(prefs,
                        found.getString("latitude"),
                        found.getString("longitude"),
                        found.optString("name", place));
            } else {
                found = new JSONObject(NasaFeed.getString(BY_IP));
                if (!found.optBoolean("success", false)) {
                    return false;
                }
                store(prefs,
                        found.getString("latitude"),
                        found.getString("longitude"),
                        found.optString("city", ""));
            }
            return true;
        } catch (Exception e) {
            Log.i(TAG, "could not resolve a location: " + e);
            return false;
        }
    }

    private static void store(SharedPreferences prefs, String lat, String lon, String city) {
        prefs.edit()
                .putString(PREF_LAT, lat)
                .putString(PREF_LON, lon)
                .putString(PREF_CITY, city)
                .apply();
    }

    /** WMO weather interpretation codes, grouped to what fits on a TV. */
    private static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
