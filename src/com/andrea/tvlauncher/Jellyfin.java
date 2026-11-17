package com.andrea.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * RxFilms' Continue watching and Next up, straight from its Jellyfin server: signed in once with
 * Quick Connect (a code approved from any logged-in Jellyfin app), then kept as a token in the
 * launcher's prefs.
 */
final class Jellyfin {

    static final class Item {
        final String id, title, sub, image;

        Item(String id, String title, String sub, String image) {
            this.id = id;
            this.title = title;
            this.sub = sub;
            this.image = image;
        }
    }

    /** What a Quick Connect sign-in is up to, for the card to show. */
    interface SignIn {
        void code(String code);
        void done(boolean ok);
    }

    /** RxFilms' server; Jellyfin's LAN discovery can't reach it, as it isn't on the home network. */
    private static final String SERVER = "https://rxfilms.rxsas.it";

    private static final ExecutorService NET = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Jellyfin() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return prefs(c).contains("token");
    }

    /** Continue watching, then Next up, on the main thread; empty lists if anything fails. */
    static void load(Context c, Consumer<List<List<Item>>> done) {
        SharedPreferences p = prefs(c);
        String url = p.getString("url", SERVER), user = p.getString("user", null), token = p.getString("token", null);
        NET.execute(() -> {
            List<Item> resume = new ArrayList<>(), next = new ArrayList<>();
            try {
                String fields = "&Fields=PrimaryImageAspectRatio&ImageTypeLimit=1&EnableImageTypes=Primary,Backdrop,Thumb";
                collect(url, get(url + "/UserItems/Resume?UserId=" + user + "&Limit=8&MediaTypes=Video" + fields, c, token), resume);
                collect(url, get(url + "/Shows/NextUp?UserId=" + user + "&Limit=8" + fields, c, token), next);
            } catch (Exception e) {
                Log.w("Jellyfin", "load failed", e);
            }
            List<List<Item>> both = new ArrayList<>();
            both.add(resume);
            both.add(next);
            MAIN.post(() -> done.accept(both));
        });
    }

    private static void collect(String url, String body, List<Item> out) throws Exception {
        JSONArray items = new JSONObject(body).getJSONArray("Items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i);
            String id = it.getString("Id");
            boolean episode = "Episode".equals(it.optString("Type"));
            String title = episode ? it.optString("SeriesName") : it.optString("Name");
            String sub = episode
                ? "S" + it.optInt("ParentIndexNumber") + ":E" + it.optInt("IndexNumber") + " · " + it.optString("Name")
                : it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "";
            // Wide cards: an episode's own still, a film's backdrop.
            String image = episode ? url + "/Items/" + id + "/Images/Primary?maxHeight=240"
                : url + "/Items/" + id + "/Images/Backdrop?maxHeight=240";
            out.add(new Item(id, title, sub, image));
        }
    }

    /** A cover, on the main thread; null if it can't be had. */
    static void image(String url, Consumer<Bitmap> done) {
        NET.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    try (InputStream in = c.getInputStream()) {
                        b = BitmapFactory.decodeStream(in);
                    }
                }
                c.disconnect();
            } catch (Exception ignored) {
                // The card keeps its placeholder.
            }
            Bitmap result = b;
            MAIN.post(() -> done.accept(result));
        });
    }

    /**
     * Signs in with Quick Connect: reports the code to show, waits up to
     * five minutes for it to be approved, then stores the token.
     */
    static void signIn(Context c, SignIn ui) {
        NET.execute(() -> {
            boolean ok = false;
            try {
                String url = prefs(c).getString("url", SERVER);
                JSONObject start = new JSONObject(post(url + "/QuickConnect/Initiate", c, null, ""));
                String secret = start.getString("Secret"), code = start.getString("Code");
                MAIN.post(() -> ui.code(code));
                for (int i = 0; i < 100; i++) {
                    Thread.sleep(3000);
                    if (new JSONObject(get(url + "/QuickConnect/Connect?Secret=" + secret, c, null)).optBoolean("Authenticated")) {
                        JSONObject auth = new JSONObject(post(url + "/Users/AuthenticateWithQuickConnect", c, null,
                            new JSONObject().put("Secret", secret).toString()));
                        prefs(c).edit().putString("url", url).putString("token", auth.getString("AccessToken"))
                            .putString("user", auth.getJSONObject("User").getString("Id")).apply();
                        ok = true;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w("Jellyfin", "sign-in failed", e);
            }
            boolean result = ok;
            MAIN.post(() -> ui.done(result));
        });
    }

    private static String get(String url, Context c, String token) throws Exception {
        return send("GET", url, c, token, null);
    }

    private static String post(String url, Context c, String token, String body) throws Exception {
        return send("POST", url, c, token, body);
    }

    private static String send(String method, String url, Context c, String token, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(5000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Authorization", "MediaBrowser Client=\"RxHome\", Device=\"Fire TV\", DeviceId=\""
            + deviceId(c) + "\", Version=\"1.0\"" + (token == null ? "" : ", Token=\"" + token + "\""));
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = h.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            int code = h.getResponseCode();
            if (code == 401 && token != null) prefs(c).edit().remove("token").apply(); // signed out elsewhere: ask again
            if (code / 100 != 2) throw new IllegalStateException(method + " " + url.replaceAll("\\?.*", "") + " -> " + code);
            try (InputStream in = h.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            h.disconnect();
        }
    }

    private static String deviceId(Context c) {
        String id = prefs(c).getString("device", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs(c).edit().putString("device", id).apply();
        }
        return id;
    }
}
