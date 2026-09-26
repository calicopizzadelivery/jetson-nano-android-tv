/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.tv.ambient;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The NASA image library, which needs no API key and whose media is public
 * domain, so images can be cached on disk and shown without attribution
 * obligations. A credit line is shown anyway because it is interesting.
 *
 * Two steps per image. The search gives metadata and an asset-collection URL;
 * that collection lists the renditions that actually exist for the item. They
 * are not uniform — some items have ~large, some only ~medium, some only
 * ~orig — so the rendition has to be chosen from the list rather than guessed
 * by pattern. Only uncached images pay for the extra request.
 */
final class NasaFeed {

    private static final String TAG = "AmbientDream";

    private static final String SEARCH =
            "https://images-api.nasa.gov/search?media_type=image&q=";

    /** Subjects that look good filling a television and are safe by construction. */
    private static final String[] TOPICS = {
            "earth from orbit", "nebula", "galaxy", "aurora",
            "planet", "star cluster", "solar eclipse",
    };

    static final class Photo {
        final String id;
        final String assetsUrl;
        final String title;
        final String center;
        final String year;

        Photo(String id, String assetsUrl, String title, String center, String year) {
            this.id = id;
            this.assetsUrl = assetsUrl;
            this.title = title;
            this.center = center;
            this.year = year;
        }

        /** Stable per image, so a repeat visit is a cache hit. */
        String cacheName() {
            return id.replaceAll("[^A-Za-z0-9_.-]", "_") + ".jpg";
        }
    }

    /**
     * Preference order. ~orig is often tens of megabytes, so it is a fallback
     * rather than a first choice; anything at or above 1080p wide is plenty for
     * a television.
     */
    private static final String[] RENDITIONS = {"~large", "~medium", "~orig", "~small"};

    private NasaFeed() {
    }

    static String randomTopic() {
        return TOPICS[(int) (Math.random() * TOPICS.length)];
    }

    static List<Photo> search(String topic) {
        final List<Photo> out = new ArrayList<>();
        try {
            final String body = get(SEARCH + java.net.URLEncoder.encode(topic, "UTF-8"));
            final JSONArray items = new JSONObject(body)
                    .getJSONObject("collection")
                    .getJSONArray("items");

            for (int i = 0; i < items.length(); i++) {
                final JSONObject item = items.getJSONObject(i);
                final JSONArray data = item.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    continue;
                }
                final JSONObject meta = data.getJSONObject(0);
                final String id = meta.optString("nasa_id", "");
                final String href = item.optString("href", "");
                if (id.isEmpty() || href.isEmpty()) {
                    continue;
                }

                final String date = meta.optString("date_created", "");
                out.add(new Photo(
                        id,
                        href,
                        meta.optString("title", ""),
                        meta.optString("center", "NASA"),
                        date.length() >= 4 ? date.substring(0, 4) : ""));
            }
        } catch (Exception e) {
            // Offline is an ordinary state for this app, not an error worth shouting about.
            Log.i(TAG, "search failed for '" + topic + "': " + e);
        }
        return out;
    }

    /**
     * Resolve an item's best available rendition. Returns null when the
     * collection cannot be read or lists nothing usable.
     */
    static String resolveImageUrl(Photo photo) {
        try {
            final JSONArray assets = new JSONArray(get(photo.assetsUrl));
            for (String want : RENDITIONS) {
                for (int i = 0; i < assets.length(); i++) {
                    final String url = assets.optString(i, "");
                    if (url.contains(want) && url.endsWith(".jpg")) {
                        // The collection lists its assets as cleartext http,
                        // which Android has blocked by default since API 28.
                        // The same host serves them over TLS, so upgrade rather
                        // than opening a cleartext exemption for the app.
                        return url.startsWith("http://")
                                ? "https://" + url.substring("http://".length())
                                : url;
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "no renditions for " + photo.id + ": " + e);
        }
        return null;
    }

    static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = null;
        try {
            c = open(url);
            try (InputStream in = c.getInputStream()) {
                final ByteArrayOutputStream buf = new ByteArrayOutputStream(512 * 1024);
                final byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                return buf.toByteArray();
            }
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String get(String url) throws IOException {
        return getString(url);
    }

    /** Shared with {@link Weather}; both talk to keyless JSON endpoints. */
    static String getString(String url) throws IOException {
        return new String(fetch(url), StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        return c;
    }
}
