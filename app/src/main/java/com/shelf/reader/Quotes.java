package com.shelf.reader;

import android.content.Context;
import android.content.Intent;

import com.shelf.reader.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Saved quotes: one file per book, kept even after the book leaves the shelves. */
final class Quotes {
    static final class Quote {
        String text = "";
        String where = "";
        long time;
    }

    static final class BookQuotes {
        String bookId = "";
        String title = "";
        String author = "";
        /** The name these quotes go by on every device, fixed once they have been synced. */
        String syncId = "";
        final ArrayList<Quote> quotes = new ArrayList<>();

        long latest() {
            long t = 0;
            for (Quote q : quotes) t = Math.max(t, q.time);
            return t;
        }
    }

    private Quotes() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "quotes");
        d.mkdirs();
        return d;
    }

    private static File file(Context c, String bookId) {
        return new File(dir(c), bookId.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    private static BookQuotes read(File f) {
        try {
            JSONObject o = new JSONObject(new String(Util.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            BookQuotes s = new BookQuotes();
            s.bookId = o.optString("bookId", "");
            s.title = o.optString("title", "");
            s.author = o.optString("author", "");
            s.syncId = o.optString("syncId", "");
            JSONArray arr = o.optJSONArray("quotes");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                Quote q = new Quote();
                q.text = j.optString("text", "");
                q.where = j.optString("where", "");
                q.time = j.optLong("time", 0);
                if (!q.text.isEmpty()) s.quotes.add(q);
            }
            return s.bookId.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    static BookQuotes load(Context c, String bookId) {
        File f = file(c, bookId);
        return f.exists() ? read(f) : null;
    }

    /** Every book that has quotes, most recently quoted first. */
    static List<BookQuotes> all(Context c) {
        ArrayList<BookQuotes> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".json")) continue;
                BookQuotes s = read(f);
                if (s != null && !s.quotes.isEmpty()) out.add(s);
            }
        }
        Collections.sort(out, new Comparator<BookQuotes>() {
            @Override public int compare(BookQuotes a, BookQuotes b) {
                return Long.compare(b.latest(), a.latest());
            }
        });
        return out;
    }

    static void save(Context c, BookQuotes s) {
        File f = file(c, s.bookId);
        if (s.quotes.isEmpty()) {
            f.delete();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("bookId", s.bookId);
            o.put("title", s.title);
            o.put("author", s.author);
            o.put("syncId", s.syncId);
            JSONArray arr = new JSONArray();
            for (Quote q : s.quotes) {
                JSONObject j = new JSONObject();
                j.put("text", q.text);
                j.put("where", q.where);
                j.put("time", q.time);
                arr.put(j);
            }
            o.put("quotes", arr);
            File tmp = new File(f.getPath() + ".tmp");
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

    /** Saves a quote; returns false when the text is empty or already saved. */
    static boolean add(Context c, Book b, String text, String where) {
        String t = clean(text);
        if (t.isEmpty()) return false;
        BookQuotes s = load(c, b.id);
        if (s == null) {
            s = new BookQuotes();
            s.bookId = b.id;
        }
        s.title = b.title;
        s.author = b.author;
        for (Quote q : s.quotes) if (q.text.equals(t)) return false;
        Quote q = new Quote();
        q.text = t;
        q.where = where == null ? "" : where;
        q.time = System.currentTimeMillis();
        s.quotes.add(q);
        save(c, s);
        Sync.request(c);
        return true;
    }

    /** Keeps the quotes file in step when a book is renamed. */
    static void renamed(Context c, Book b) {
        BookQuotes s = load(c, b.id);
        if (s == null) return;
        s.title = b.title;
        s.author = b.author;
        save(c, s);
    }

    /** Joins broken lines and drops quote marks the text already carries. */
    static String clean(String s) {
        if (s == null) return "";
        s = s.replace(' ', ' ').replace("​", "").replaceAll("\\s+", " ").trim();
        String open = "\"“«„'‘", close = "\"”»“'’";
        while (s.length() > 1 && open.indexOf(s.charAt(0)) >= 0 && close.indexOf(s.charAt(s.length() - 1)) >= 0) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    static String quoted(String text) {
        return "“" + text + "”";
    }

    static String credit(String title, String author) {
        if (title == null || title.isEmpty()) return "";
        return "— " + title + (author == null || author.isEmpty() ? "" : ", " + author);
    }

    static String shareText(String title, String author, String text) {
        String credit = credit(title, author);
        return quoted(text) + (credit.isEmpty() ? "" : "\n" + credit);
    }

    /** The whole collection for one book as plain text. */
    static String allText(BookQuotes s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.title);
        if (!s.author.isEmpty()) sb.append("\n").append(s.author);
        sb.append("\n");
        for (Quote q : s.quotes) {
            sb.append("\n").append(quoted(q.text)).append("\n");
            if (!q.where.isEmpty()) sb.append("(").append(q.where).append(")\n");
        }
        return sb.toString();
    }

    static void share(Context c, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            c.startActivity(Intent.createChooser(i, "Share quote"));
        } catch (Exception e) {
            Ui.toast(c, "No app available to share with");
        }
    }
}
