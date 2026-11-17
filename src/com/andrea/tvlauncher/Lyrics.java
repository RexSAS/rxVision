package com.andrea.tvlauncher;

import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced song lyrics from LRCLIB (lrclib.net), a free open lyrics database. Only the
 * track's title, artist, album and length are sent.
 */
final class Lyrics {

    final long[] times;
    final String[] lines;

    private Lyrics(long[] times, String[] lines) {
        this.times = times;
        this.lines = lines;
    }

    /** The line being sung at ms into the track, or -1 before the first one. */
    int indexAt(long ms) {
        int i = -1;
        while (i + 1 < times.length && times[i + 1] <= ms) i++;
        return i;
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    /** Parses LRC text ("[01:23.45] words"); null when it has no timed lines. */
    static Lyrics parse(String lrc) {
        if (lrc == null) return null;
        List<Object[]> entries = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = TAG.matcher(raw);
            int end = 0;
            List<Long> stamps = new ArrayList<>();
            // A line can carry several stamps when it repeats: "[00:12.00][01:40.00] chorus".
            while (m.find() && m.start() == end) {
                stamps.add(Long.parseLong(m.group(1)) * 60000 + Math.round(Double.parseDouble(m.group(2)) * 1000));
                end = m.end();
            }
            String words = raw.substring(end).trim();
            for (long t : stamps) entries.add(new Object[] {t, words});
        }
        if (entries.isEmpty()) return null;
        Collections.sort(entries, (a, b) -> Long.compare((Long) a[0], (Long) b[0]));
        long[] times = new long[entries.size()];
        String[] lines = new String[entries.size()];
        for (int i = 0; i < times.length; i++) {
            times[i] = (Long) entries.get(i)[0];
            lines[i] = (String) entries.get(i)[1];
        }
        return new Lyrics(times, lines);
    }

    /** Looks the track up off the main thread; `done` gets the lyrics, or null, on `main`. */
    static void fetch(String artist, String title, String album, long durationMs, Handler main, Consumer<Lyrics> done) {
        new Thread(() -> {
            Lyrics found = null;
            try {
                Uri.Builder exact = Uri.parse("https://lrclib.net/api/get").buildUpon()
                    .appendQueryParameter("artist_name", artist)
                    .appendQueryParameter("track_name", title);
                if (album != null) exact.appendQueryParameter("album_name", album);
                if (durationMs > 0) exact.appendQueryParameter("duration", String.valueOf(durationMs / 1000));
                String body = get(exact.build().toString());
                if (body != null) found = parse(new JSONObject(body).optString("syncedLyrics", null));
                // No exact match (album or length differ): take the best search hit with timings.
                if (found == null) {
                    body = get(Uri.parse("https://lrclib.net/api/search").buildUpon()
                        .appendQueryParameter("artist_name", artist)
                        .appendQueryParameter("track_name", title).build().toString());
                    JSONArray hits = body == null ? new JSONArray() : new JSONArray(body);
                    for (int i = 0; i < hits.length() && found == null; i++) {
                        found = parse(hits.getJSONObject(i).optString("syncedLyrics", null));
                    }
                }
            } catch (Exception e) {
                Log.w("Lyrics", "lookup failed", e);
            }
            Lyrics result = found;
            main.post(() -> done.accept(result));
        }, "lyrics").start();
    }

    /** The response body, or null on anything but 200. */
    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "TvLauncher/1.0");
        try {
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
