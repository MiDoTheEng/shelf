package com.shelf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.shelf.reader.core.Util;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The reading log: every book read, how far, when, and when it was finished, with a small
 * picture of its cover. Kept even after a book leaves the shelves; synced and backed up.
 */
final class History {
    static final class Entry {
        /** The book file's fingerprint (or its id when that isn't known). */
        String key = "";
        String title = "", author = "", format = "";
        float progress;
        /** "Page 84 of 300" or "Section 5 of 20". */
        String where = "";
        long started, lastRead;
        /** When it was finished (0 = not finished), and when that was last decided. */
        long finished, finishedSet;
        /** When the name or author last changed. */
        long updated;
        /** A small JPEG of the cover, as text. */
        String thumb = "";

        Entry copy() {
            Entry e = new Entry();
            e.key = key;
            e.title = title;
            e.author = author;
            e.format = format;
            e.progress = progress;
            e.where = where;
            e.started = started;
            e.lastRead = lastRead;
            e.finished = finished;
            e.finishedSet = finishedSet;
            e.updated = updated;
            e.thumb = thumb;
            return e;
        }
    }

    private History() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "history.json");
    }

    static String keyOf(Book b) {
        return b.key.isEmpty() ? "id:" + b.id : b.key;
    }

    static synchronized LinkedHashMap<String, Entry> load(Context c) {
        LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
        File f = file(c);
        if (!f.exists()) return map;
        try {
            JSONObject o = new JSONObject(new String(Util.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject j = o.optJSONObject(k);
                if (j != null) map.put(k, fromJson(k, j));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    static synchronized void save(Context c, Map<String, Entry> map) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Entry> e : map.entrySet()) o.put(e.getKey(), toJson(e.getValue()));
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (Exception ignored) {
        }
    }

    static JSONObject toJson(Entry e) throws Exception {
        JSONObject j = new JSONObject();
        j.put("title", e.title);
        j.put("author", e.author);
        j.put("format", e.format);
        j.put("progress", (double) e.progress);
        j.put("where", e.where);
        j.put("started", e.started);
        j.put("lastRead", e.lastRead);
        j.put("finished", e.finished);
        j.put("finishedSet", e.finishedSet);
        j.put("updated", e.updated);
        j.put("thumb", e.thumb);
        return j;
    }

    static Entry fromJson(String key, JSONObject j) {
        Entry e = new Entry();
        e.key = key;
        e.title = j.optString("title", "");
        e.author = j.optString("author", "");
        e.format = j.optString("format", "");
        e.progress = (float) j.optDouble("progress", 0);
        e.where = j.optString("where", "");
        e.started = j.optLong("started", 0);
        e.lastRead = j.optLong("lastRead", 0);
        e.finished = j.optLong("finished", 0);
        e.finishedSet = j.optLong("finishedSet", 0);
        e.updated = j.optLong("updated", 0);
        e.thumb = j.optString("thumb", "");
        return e;
    }

    /** Called when a book is closed: notes how far it got, and whether it was finished. */
    static void record(Context c, Book b, String where) {
        if (b == null) return;
        LinkedHashMap<String, Entry> map = load(c);
        String key = keyOf(b);
        Entry e = map.get(key);
        long now = System.currentTimeMillis();
        if (e == null) {
            e = new Entry();
            e.key = key;
            e.started = now;
            map.put(key, e);
        }
        if (!b.title.equals(e.title) || !b.author.equals(e.author)) e.updated = now;
        e.title = b.title;
        e.author = b.author;
        e.format = b.format;
        if (b.progress >= e.progress - 0.0005f || e.finished > 0) {
            e.progress = b.progress;
            if (where != null && !where.isEmpty()) e.where = where;
        }
        e.lastRead = now;
        // Reaching the end counts as finishing the book.
        if (b.progress >= 0.995f && e.finished == 0) {
            e.finished = now;
            e.finishedSet = now;
        }
        if (e.thumb.isEmpty()) e.thumb = thumbOf(b);
        save(c, map);
        Sync.request(c);
    }

    /**
     * Adds books read before the log existed. Done once per device only, and never for a book
     * removed from the log or one not read on this device (its entry comes from the other device).
     */
    static void backfill(Context c) {
        android.content.SharedPreferences prefs = c.getSharedPreferences("shelf", Context.MODE_PRIVATE);
        if (prefs.getBoolean("historyFilled", false)) return;
        prefs.edit().putBoolean("historyFilled", true).apply();
        LinkedHashMap<String, Entry> map = load(c);
        java.util.Set<String> removed = Sync.removedFromHistory(c);
        boolean changed = false;
        for (Book b : Library.get(c).all()) {
            String key = keyOf(b);
            if (b.progress <= 0.0005f || b.onlineOnly() || map.containsKey(key) || removed.contains(key)) continue;
            Entry e = new Entry();
            e.key = keyOf(b);
            e.title = b.title;
            e.author = b.author;
            e.format = b.format;
            e.progress = b.progress;
            e.where = Math.round(b.progress * 100) + "%";
            e.started = b.opened > 0 ? b.opened : b.added;
            e.lastRead = b.opened > 0 ? b.opened : b.added;
            if (b.progress >= 0.995f) {
                e.finished = e.lastRead;
                e.finishedSet = 1;
            }
            e.thumb = thumbOf(b);
            map.put(e.key, e);
            changed = true;
        }
        if (changed) save(c, map);
    }

    static void setFinished(Context c, String key, boolean finished) {
        LinkedHashMap<String, Entry> map = load(c);
        Entry e = map.get(key);
        if (e == null) return;
        long now = System.currentTimeMillis();
        e.finished = finished ? now : 0;
        e.finishedSet = now;
        if (finished) e.progress = Math.max(e.progress, 1f);
        save(c, map);
        Sync.request(c);
    }

    static void remove(Context c, String key) {
        LinkedHashMap<String, Entry> map = load(c);
        if (map.remove(key) == null) return;
        save(c, map);
        Sync.historyDeleted(c, key);
    }

    /** Book's cover as a small JPEG in text form, or empty. */
    static String thumbOf(Book b) {
        String path = b.coverPath();
        if (path.isEmpty() || !new File(path).exists()) return "";
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 120) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return "";
            if (bmp.getWidth() > 130) {
                bmp = Bitmap.createScaledBitmap(bmp, 120, Math.max(1, Math.round(120f * bmp.getHeight() / bmp.getWidth())), true);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception | OutOfMemoryError e) {
            return "";
        }
    }

    static Bitmap thumbBitmap(Entry e) {
        if (e.thumb.isEmpty()) return null;
        try {
            byte[] data = Base64.decode(e.thumb, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e2) {
            return null;
        }
    }

    /** Entries sorted for the Progress tab: reading (latest first), then finished (latest first). */
    static List<Entry> sorted(Map<String, Entry> map, boolean finished) {
        ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : map.values()) if ((e.finished > 0) == finished) out.add(e);
        java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                long x = finished ? a.finished : a.lastRead, y = finished ? b.finished : b.lastRead;
                return Long.compare(y, x);
            }
        });
        return out;
    }
}
