package com.shelf.reader;

import android.content.Context;

import com.shelf.reader.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Words and sentences translated while reading, newest first. */
final class Words {
    static final class Entry {
        String text = "";
        String arabic = "";
        /** Other meanings of a single word, one kind per line (may be empty). */
        String meanings = "";
        String book = "";
        long time;
    }

    private Words() {}

    private static File file(Context c) {
        return new File(c.getFilesDir(), "words.json");
    }

    static List<Entry> all(Context c) {
        ArrayList<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            JSONArray arr = new JSONArray(new String(Util.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                Entry e = new Entry();
                e.text = j.optString("text", "");
                e.arabic = j.optString("arabic", "");
                e.meanings = j.optString("meanings", "");
                e.book = j.optString("book", "");
                e.time = j.optLong("time", 0);
                if (!e.text.isEmpty()) out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject j = new JSONObject();
                j.put("text", e.text);
                j.put("arabic", e.arabic);
                j.put("meanings", e.meanings);
                j.put("book", e.book);
                j.put("time", e.time);
                arr.put(j);
            }
            File f = file(c), tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
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

    /** Adds a translation at the top; the same word translated again just moves up. */
    static void add(Context c, String text, String arabic, String meanings, String book) {
        List<Entry> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).text.equalsIgnoreCase(text)) list.remove(i);
        }
        Entry e = new Entry();
        e.text = text;
        e.arabic = arabic;
        e.meanings = meanings == null ? "" : meanings;
        e.book = book == null ? "" : book;
        e.time = System.currentTimeMillis();
        list.add(0, e);
        save(c, list);
        Sync.request(c);
    }
}
