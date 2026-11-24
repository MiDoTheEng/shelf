package com.shelf.reader;

import android.content.Context;
import android.graphics.Path;

import com.shelf.reader.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What the reader drew or highlighted in one book, kept in its own file.
 * PDF pages hold pen strokes and highlighted text boxes; EPUB/Kindle sections hold
 * highlighted character ranges. Page measurements are in page widths (0..1 across).
 */
final class Ink {
    static final float PEN_WIDTH = 0.0035f, MARKER_WIDTH = 0.03f;

    static final class Stroke {
        int color;
        float width;
        boolean marker;
        float[] pts = new float[64];
        int n;
        private Path path;

        void add(float x, float y) {
            if (n + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[n++] = x;
            pts[n++] = y;
            path = null;
        }

        /** A smoothed line through the points. */
        Path path() {
            if (path != null) return path;
            Path p = new Path();
            if (n >= 2) {
                p.moveTo(pts[0], pts[1]);
                if (n == 2) p.lineTo(pts[0], pts[1]);
                for (int i = 2; i + 1 < n; i += 2) {
                    float px = pts[i - 2], py = pts[i - 1], x = pts[i], y = pts[i + 1];
                    if (i + 2 >= n) p.lineTo(x, y);
                    else p.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
                }
            }
            path = p;
            return p;
        }
    }

    /** Highlighted text on a PDF page: one box per line (left, top, right, bottom). */
    static final class Mark {
        int color;
        float[] rects = new float[0];
    }

    static final class Page {
        final ArrayList<Stroke> strokes = new ArrayList<>();
        final ArrayList<Mark> marks = new ArrayList<>();

        boolean isEmpty() {
            return strokes.isEmpty() && marks.isEmpty();
        }
    }

    private final File file;
    private final HashMap<Integer, Page> pages = new HashMap<>();
    /** Section number -> highlighted {start, end} character ranges. */
    private final HashMap<Integer, ArrayList<int[]>> texts = new HashMap<>();
    boolean dirty;

    private Context context;
    private String bookId = "";

    private Ink(File file) {
        this.file = file;
    }

    static File fileFor(Context c, String bookId) {
        File dir = new File(c.getFilesDir(), "ink");
        dir.mkdirs();
        return new File(dir, bookId.replaceAll("[^A-Za-z0-9_-]", "_") + ".json");
    }

    static void delete(Context c, String bookId) {
        fileFor(c, bookId).delete();
    }

    Page page(int index) {
        Page p = pages.get(index);
        if (p == null) {
            p = new Page();
            pages.put(index, p);
        }
        return p;
    }

    ArrayList<int[]> texts(int section) {
        ArrayList<int[]> list = texts.get(section);
        if (list == null) {
            list = new ArrayList<>();
            texts.put(section, list);
        }
        return list;
    }

    static Ink load(Context c, String bookId) {
        Ink ink = new Ink(fileFor(c, bookId));
        ink.context = c.getApplicationContext();
        ink.bookId = bookId;
        if (!ink.file.exists()) return ink;
        try {
            JSONObject o = new JSONObject(new String(Util.readAll(new FileInputStream(ink.file)), StandardCharsets.UTF_8));
            JSONObject pages = o.optJSONObject("pages");
            if (pages != null) {
                Iterator<String> keys = pages.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject pj = pages.optJSONObject(key);
                    if (pj == null) continue;
                    Page page = ink.page(Integer.parseInt(key));
                    JSONArray strokes = pj.optJSONArray("strokes");
                    for (int i = 0; strokes != null && i < strokes.length(); i++) {
                        JSONObject sj = strokes.optJSONObject(i);
                        JSONArray pts = sj == null ? null : sj.optJSONArray("p");
                        if (pts == null || pts.length() < 2) continue;
                        Stroke s = new Stroke();
                        s.color = sj.optInt("c", 0xFF1A1A1A);
                        s.width = (float) sj.optDouble("w", PEN_WIDTH);
                        s.marker = sj.optBoolean("m", false);
                        for (int k = 0; k + 1 < pts.length(); k += 2) s.add(pts.optInt(k) / 10000f, pts.optInt(k + 1) / 10000f);
                        page.strokes.add(s);
                    }
                    JSONArray marks = pj.optJSONArray("marks");
                    for (int i = 0; marks != null && i < marks.length(); i++) {
                        JSONObject mj = marks.optJSONObject(i);
                        JSONArray r = mj == null ? null : mj.optJSONArray("r");
                        if (r == null || r.length() < 4) continue;
                        Mark m = new Mark();
                        m.color = mj.optInt("c", 0xFFFFD54A);
                        m.rects = new float[r.length() / 4 * 4];
                        for (int k = 0; k < m.rects.length; k++) m.rects[k] = r.optInt(k) / 10000f;
                        page.marks.add(m);
                    }
                }
            }
            JSONObject texts = o.optJSONObject("texts");
            if (texts != null) {
                Iterator<String> keys = texts.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = texts.optJSONArray(key);
                    ArrayList<int[]> list = ink.texts(Integer.parseInt(key));
                    for (int i = 0; arr != null && i + 1 < arr.length(); i += 2) {
                        list.add(new int[]{arr.optInt(i), arr.optInt(i + 1)});
                    }
                }
            }
        } catch (Exception ignored) {
            // A damaged file just means starting clean for this book.
        }
        return ink;
    }

    /** Saves, and marks the book's highlights and drawings as changed so they reach the other devices. */
    void save() {
        write();
        if (context == null) return;
        Library lib = Library.get(context);
        Book b = lib.find(bookId);
        if (b == null) return;
        synchronized (lib) {
            b.inkTime = System.currentTimeMillis();
            b.inkAsset = "";
            b.inkPending = false;
        }
        lib.save();
        Sync.request(context);
    }

    private void write() {
        dirty = false;
        try {
            JSONObject pagesJson = new JSONObject();
            for (Map.Entry<Integer, Page> e : pages.entrySet()) {
                Page page = e.getValue();
                if (page.isEmpty()) continue;
                JSONArray strokes = new JSONArray();
                for (Stroke s : page.strokes) {
                    JSONArray pts = new JSONArray();
                    for (int k = 0; k < s.n; k++) pts.put(Math.round(s.pts[k] * 10000f));
                    JSONObject sj = new JSONObject();
                    sj.put("c", s.color);
                    sj.put("w", (double) s.width);
                    sj.put("m", s.marker);
                    sj.put("p", pts);
                    strokes.put(sj);
                }
                JSONArray marks = new JSONArray();
                for (Mark m : page.marks) {
                    JSONArray r = new JSONArray();
                    for (float v : m.rects) r.put(Math.round(v * 10000f));
                    JSONObject mj = new JSONObject();
                    mj.put("c", m.color);
                    mj.put("r", r);
                    marks.put(mj);
                }
                JSONObject pj = new JSONObject();
                pj.put("strokes", strokes);
                pj.put("marks", marks);
                pagesJson.put(String.valueOf(e.getKey()), pj);
            }
            JSONObject textsJson = new JSONObject();
            for (Map.Entry<Integer, ArrayList<int[]>> e : texts.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                JSONArray arr = new JSONArray();
                for (int[] range : e.getValue()) {
                    arr.put(range[0]);
                    arr.put(range[1]);
                }
                textsJson.put(String.valueOf(e.getKey()), arr);
            }
            if (pagesJson.length() == 0 && textsJson.length() == 0) {
                file.delete();
                return;
            }
            JSONObject o = new JSONObject();
            o.put("pages", pagesJson);
            o.put("texts", textsJson);
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception ignored) {
        }
    }
}
