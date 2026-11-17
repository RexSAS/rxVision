package com.andrea.tvlauncher;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Claude FM, the Claude channel's 24/7 live stream on YouTube, for SmartTube's card. A live
 * stream gets a new video id whenever it restarts, so the current one is looked up with a
 * logged-out YouTube search each time, falling back to the last id known.
 */
final class ClaudeFm {

    static final String FALLBACK = "tRsQsTMvPNg";

    private ClaudeFm() {}

    static String thumbnail(String id) {
        return "https://i.ytimg.com/vi/" + id + "/hqdefault_live.jpg";
    }

    /** The stream's current video id, on the main thread. */
    static void find(Consumer<String> done) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String id = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                // "EgJAAQ==" is YouTube's own "Live" search filter.
                String body = "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20250101.00.00\"}},"
                    + "\"query\":\"Claude FM\",\"params\":\"EgJAAQ%3D%3D\"}";
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes(StandardCharsets.UTF_8));
                }
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] b = new byte[16384];
                        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                        id = claudeLive(new JSONObject(buf.toString("UTF-8")));
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
                // Keep the last known id.
            }
            String result = id == null ? FALLBACK : id;
            main.post(() -> done.accept(result));
        }, "claude-fm").start();
    }

    /** The first live result posted by the Claude channel, anywhere in the response. */
    private static String claudeLive(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject v = o.optJSONObject("videoRenderer");
            if (v != null && "Claude".equals(runs(v.optJSONObject("ownerText"))) && v.toString().contains("LIVE")) {
                return v.optString("videoId", null);
            }
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String found = claudeLive(o.opt(keys.next()));
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                String found = claudeLive(a.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String runs(JSONObject text) {
        if (text == null) return null;
        JSONArray runs = text.optJSONArray("runs");
        StringBuilder s = new StringBuilder();
        for (int i = 0; runs != null && i < runs.length(); i++) s.append(runs.optJSONObject(i).optString("text"));
        return s.toString();
    }
}
