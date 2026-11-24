package com.shelf.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One file holding every saved quote and translated word, for moving them to another
 * device. Importing adds what is missing and never deletes anything.
 */
final class Backup {
    private Backup() {}

    static String export(Context c) throws Exception {
        JSONArray books = new JSONArray();
        for (Quotes.BookQuotes s : Quotes.all(c)) {
            JSONArray qs = new JSONArray();
            for (Quotes.Quote q : s.quotes) {
                JSONObject j = new JSONObject();
                j.put("text", q.text);
                j.put("where", q.where);
                j.put("time", q.time);
                qs.put(j);
            }
            JSONObject b = new JSONObject();
            b.put("title", s.title);
            b.put("author", s.author);
            b.put("quotes", qs);
            books.put(b);
        }
        JSONArray words = new JSONArray();
        for (Words.Entry e : Words.all(c)) {
            JSONObject j = new JSONObject();
            j.put("text", e.text);
            j.put("arabic", e.arabic);
            j.put("meanings", e.meanings);
            j.put("book", e.book);
            j.put("time", e.time);
            words.put(j);
        }
        JSONObject o = new JSONObject();
        o.put("app", "Shelf");
        o.put("version", 1);
        o.put("quotes", books);
        o.put("words", words);
        JSONObject hist = new JSONObject();
        for (java.util.Map.Entry<String, History.Entry> e : History.load(c).entrySet()) {
            hist.put(e.getKey(), History.toJson(e.getValue()));
        }
        o.put("history", hist);
        return o.toString();
    }

    private static String key(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    /** Adds the backup's quotes and words; returns {quotes added, words added}. */
    static int[] importText(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (!"Shelf".equals(o.optString("app", ""))) throw new IllegalArgumentException("not a Shelf backup");
        int addedQuotes = 0, addedWords = 0;

        JSONArray books = o.optJSONArray("quotes");
        List<Quotes.BookQuotes> existing = Quotes.all(c);
        List<Book> library = Library.get(c).all();
        for (int i = 0; books != null && i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            if (b == null) continue;
            String title = b.optString("title", "");
            // Quotes go with the same book on this device: matched by name, as each device has its own copy.
            Quotes.BookQuotes set = null;
            for (Quotes.BookQuotes s : existing) if (key(s.title).equals(key(title))) set = s;
            if (set == null) {
                set = new Quotes.BookQuotes();
                set.bookId = "imported-" + Integer.toHexString(key(title).hashCode() & 0x7fffffff);
                for (Book book : library) if (key(book.title).equals(key(title))) set.bookId = book.id;
                Quotes.BookQuotes old = Quotes.load(c, set.bookId);
                if (old != null) set = old;
                set.title = title;
                set.author = b.optString("author", "");
                existing.add(set);
            }
            JSONArray qs = b.optJSONArray("quotes");
            boolean changed = false;
            for (int k = 0; qs != null && k < qs.length(); k++) {
                JSONObject j = qs.optJSONObject(k);
                String text = j == null ? "" : j.optString("text", "");
                if (text.isEmpty()) continue;
                boolean have = false;
                for (Quotes.Quote q : set.quotes) if (q.text.equals(text)) have = true;
                if (have) continue;
                Quotes.Quote q = new Quotes.Quote();
                q.text = text;
                q.where = j.optString("where", "");
                q.time = j.optLong("time", System.currentTimeMillis());
                set.quotes.add(q);
                changed = true;
                addedQuotes++;
            }
            if (changed) {
                Collections.sort(set.quotes, new Comparator<Quotes.Quote>() {
                    @Override public int compare(Quotes.Quote x, Quotes.Quote y) {
                        return Long.compare(x.time, y.time);
                    }
                });
                Quotes.save(c, set);
            }
        }

        // The reading log: added to, never replaced.
        JSONObject hist = o.optJSONObject("history");
        if (hist != null) {
            java.util.LinkedHashMap<String, History.Entry> mine = History.load(c), theirs = new java.util.LinkedHashMap<>();
            java.util.Iterator<String> it = hist.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject j = hist.optJSONObject(k);
                if (j != null) theirs.put(k, History.fromJson(k, j));
            }
            if (SyncMerge.mergeHistory(mine, new java.util.HashMap<String, Long>(), theirs,
                    new java.util.HashMap<String, Long>())) {
                History.save(c, mine);
            }
        }

        JSONArray words = o.optJSONArray("words");
        List<Words.Entry> list = Words.all(c);
        for (int i = 0; words != null && i < words.length(); i++) {
            JSONObject j = words.optJSONObject(i);
            String text = j == null ? "" : j.optString("text", "");
            if (text.isEmpty()) continue;
            boolean have = false;
            for (Words.Entry e : list) if (e.text.equalsIgnoreCase(text)) have = true;
            if (have) continue;
            Words.Entry e = new Words.Entry();
            e.text = text;
            e.arabic = j.optString("arabic", "");
            e.meanings = j.optString("meanings", "");
            e.book = j.optString("book", "");
            e.time = j.optLong("time", 0);
            list.add(e);
            addedWords++;
        }
        if (addedWords > 0) {
            Collections.sort(list, new Comparator<Words.Entry>() {
                @Override public int compare(Words.Entry x, Words.Entry y) {
                    return Long.compare(y.time, x.time);
                }
            });
            Words.save(c, list);
        }
        return new int[]{addedQuotes, addedWords};
    }
}
