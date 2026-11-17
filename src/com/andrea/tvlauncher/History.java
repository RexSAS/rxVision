package com.andrea.tvlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * What each app has recently played, newest first, as its media session announced it: fills
 * the dock's long-press window (Spotify's recent songs, RxFilms' shows to continue, ...).
 */
final class History {

    static final class Entry {
        final String id, title, sub;
        final File art;

        Entry(String id, String title, String sub, File art) {
            this.id = id;
            this.title = title;
            this.sub = sub;
            this.art = art;
        }

        Bitmap art() {
            return art.exists() ? BitmapFactory.decodeFile(art.getPath()) : null;
        }
    }

    private static final int MAX = 8;

    private History() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("history", Context.MODE_PRIVATE);
    }

    static void record(Context c, String pkg, MediaMetadata m) {
        if (m == null) return;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (TextUtils.isEmpty(title)) return;
        String sub = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(sub)) sub = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (TextUtils.isEmpty(sub)) sub = m.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String key = TextUtils.isEmpty(id) ? title : id;

        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        File file = artFile(c, pkg, key);
        if (art != null) {
            float s = Math.min(1f, 360f / Math.max(art.getWidth(), art.getHeight()));
            Bitmap small = Bitmap.createScaledBitmap(art, Math.max(1, Math.round(art.getWidth() * s)), Math.max(1, Math.round(art.getHeight() * s)), true);
            try (FileOutputStream out = new FileOutputStream(file)) {
                small.compress(Bitmap.CompressFormat.JPEG, 85, out);
            } catch (Exception ignored) {
                // No cover then; the entry still shows.
            }
        }

        try {
            JSONArray old = new JSONArray(prefs(c).getString(pkg, "[]"));
            JSONArray list = new JSONArray();
            list.put(new JSONObject().put("id", key).put("title", title).put("sub", sub == null ? "" : sub));
            for (int i = 0; i < old.length() && list.length() < MAX; i++) {
                JSONObject e = old.getJSONObject(i);
                if (!key.equals(e.optString("id"))) list.put(e);
            }
            // Covers of entries that fell off the end go with them.
            for (int i = 0; i < old.length(); i++) {
                String k = old.getJSONObject(i).optString("id");
                if (!contains(list, k)) artFile(c, pkg, k).delete();
            }
            prefs(c).edit().putString(pkg, list.toString()).apply();
        } catch (Exception ignored) {
            // A corrupt list just starts over next time.
            prefs(c).edit().remove(pkg).apply();
        }
    }

    static List<Entry> load(Context c, String pkg) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs(c).getString(pkg, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                String id = e.optString("id");
                out.add(new Entry(id, e.optString("title"), e.optString("sub"), artFile(c, pkg, id)));
            }
        } catch (Exception ignored) {
            // Nothing to show.
        }
        return out;
    }

    private static boolean contains(JSONArray list, String key) {
        for (int i = 0; i < list.length(); i++) if (key.equals(list.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    private static File artFile(Context c, String pkg, String key) {
        File dir = new File(c.getFilesDir(), "history");
        dir.mkdirs();
        return new File(dir, Integer.toHexString((pkg + "|" + key).hashCode()) + ".jpg");
    }
}
