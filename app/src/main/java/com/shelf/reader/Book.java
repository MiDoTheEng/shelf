package com.shelf.reader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** One book on the shelves. */
final class Book {
    String id = "";
    String title = "";
    String author = "";
    /** "pdf", "epub" or "mobi". */
    String format = "";
    /** Absolute path of the app's private copy of the file. */
    String file = "";
    /** Absolute path of the cover image, or empty. */
    String cover = "";
    /** A cover the reader chose (photo, page or picture from the book); the original stays in {@link #cover}. */
    String customCover = "";

    /** The cover to show. */
    String coverPath() {
        return customCover.isEmpty() ? cover : customCover;
    }
    String source = "";
    long size;
    boolean reading;
    float progress;
    /** Page (PDF) or chapter (EPUB/MOBI) the reader stopped at. */
    int chapter;
    /** Position inside the chapter, 0..1. */
    float offset;
    long added;
    long opened;
    /** Fingerprint of the book file, the same on every device; used for syncing. */
    String key = "";
    /** When the reading position, the shelf, and the bookmarks were last changed. */
    long posTime, shelfTime, marksTime;
    /** A position from another device, waiting to be offered when the book is opened. */
    int pendingChapter;
    float pendingOffset, pendingProgress;
    long pendingTime;
    String pendingDevice = "";

    // ---- online library (GitHub). A book whose file is empty lives only online until opened.
    /** Name of the book file stored online, empty until uploaded. */
    String asset = "";
    /** When the name or author was last changed, so renames reach other devices. */
    long infoTime;
    /** The cover stored online, when it was chosen, and whether it still needs downloading here. */
    String coverAsset = "";
    long coverTime;
    boolean coverPending;
    /** Highlights and drawings stored online, when they last changed, and whether to download them. */
    String inkAsset = "";
    long inkTime;
    boolean inkPending;

    /** True when only the online copy exists on this device. */
    boolean onlineOnly() {
        return file.isEmpty();
    }
    final ArrayList<Mark> marks = new ArrayList<>();

    /** A bookmark: a page (PDF) or a place inside a section (EPUB/Kindle). */
    static final class Mark {
        int chapter;
        float offset;
        String label = "";
        long time;
    }

    void sortMarks() {
        Collections.sort(marks, new Comparator<Mark>() {
            @Override public int compare(Mark a, Mark b) {
                return a.chapter != b.chapter ? Integer.compare(a.chapter, b.chapter) : Float.compare(a.offset, b.offset);
            }
        });
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("author", author);
        o.put("format", format);
        o.put("file", file);
        o.put("cover", cover);
        o.put("customCover", customCover);
        o.put("source", source);
        o.put("size", size);
        o.put("reading", reading);
        o.put("progress", (double) progress);
        o.put("chapter", chapter);
        o.put("offset", (double) offset);
        o.put("added", added);
        o.put("opened", opened);
        o.put("key", key);
        o.put("posTime", posTime);
        o.put("shelfTime", shelfTime);
        o.put("marksTime", marksTime);
        o.put("pendingChapter", pendingChapter);
        o.put("pendingOffset", (double) pendingOffset);
        o.put("pendingProgress", (double) pendingProgress);
        o.put("pendingTime", pendingTime);
        o.put("pendingDevice", pendingDevice);
        o.put("asset", asset);
        o.put("infoTime", infoTime);
        o.put("coverAsset", coverAsset);
        o.put("coverTime", coverTime);
        o.put("coverPending", coverPending);
        o.put("inkAsset", inkAsset);
        o.put("inkTime", inkTime);
        o.put("inkPending", inkPending);
        JSONArray arr = new JSONArray();
        for (Mark m : marks) {
            JSONObject j = new JSONObject();
            j.put("chapter", m.chapter);
            j.put("offset", (double) m.offset);
            j.put("label", m.label);
            j.put("time", m.time);
            arr.put(j);
        }
        o.put("marks", arr);
        return o;
    }

    static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.id = o.optString("id", "");
        b.title = o.optString("title", "");
        b.author = o.optString("author", "");
        b.format = o.optString("format", "");
        b.file = o.optString("file", "");
        b.cover = o.optString("cover", "");
        b.customCover = o.optString("customCover", "");
        b.source = o.optString("source", "");
        b.size = o.optLong("size", 0);
        b.reading = o.optBoolean("reading", false);
        b.progress = (float) o.optDouble("progress", 0);
        b.chapter = o.optInt("chapter", 0);
        b.offset = (float) o.optDouble("offset", 0);
        b.added = o.optLong("added", 0);
        b.opened = o.optLong("opened", 0);
        b.key = o.optString("key", "");
        b.posTime = o.optLong("posTime", 0);
        b.shelfTime = o.optLong("shelfTime", 0);
        b.marksTime = o.optLong("marksTime", 0);
        b.pendingChapter = o.optInt("pendingChapter", 0);
        b.pendingOffset = (float) o.optDouble("pendingOffset", 0);
        b.pendingProgress = (float) o.optDouble("pendingProgress", 0);
        b.pendingTime = o.optLong("pendingTime", 0);
        b.pendingDevice = o.optString("pendingDevice", "");
        b.asset = o.optString("asset", "");
        b.infoTime = o.optLong("infoTime", 0);
        b.coverAsset = o.optString("coverAsset", "");
        b.coverTime = o.optLong("coverTime", 0);
        b.coverPending = o.optBoolean("coverPending", false);
        b.inkAsset = o.optString("inkAsset", "");
        b.inkTime = o.optLong("inkTime", 0);
        b.inkPending = o.optBoolean("inkPending", false);
        JSONArray arr = o.optJSONArray("marks");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            Mark m = new Mark();
            m.chapter = j.optInt("chapter", 0);
            m.offset = (float) j.optDouble("offset", 0);
            m.label = j.optString("label", "");
            m.time = j.optLong("time", 0);
            b.marks.add(m);
        }
        return b;
    }
}
