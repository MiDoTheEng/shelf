package com.shelf.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.shelf.reader.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Keeps reading positions, the Currently Reading shelf, bookmarks, quotes and words the same
 * on every device, through one private ("secret") gist on the user's GitHub account.
 * Works without Google services, so it runs on Huawei tablets too.
 */
final class Sync {
    private static final String API = "https://api.github.com";
    private static final String FILE = "shelf-sync.json";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static final ArrayList<Runnable> LISTENERS = new ArrayList<>();
    private static OkHttpClient http;

    /** The book open in a reader right now; its position is never moved without asking. */
    static volatile String openBookId;

    private Sync() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    static boolean connected(Context c) {
        return !prefs(c).getString("token", "").isEmpty() && !prefs(c).getString("gist", "").isEmpty();
    }

    static String account(Context c) {
        return prefs(c).getString("login", "");
    }

    static long lastSync(Context c) {
        return prefs(c).getLong("last", 0);
    }

    static String lastError(Context c) {
        return prefs(c).getString("error", "");
    }

    static String device() {
        String m = Build.MODEL == null ? "" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!maker.isEmpty() && !m.toLowerCase().startsWith(maker.toLowerCase())) m = maker + " " + m;
        return m.trim().isEmpty() ? "other device" : m.trim();
    }

    /** Called on the main thread after every sync that finished. */
    static void listen(Runnable r) {
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(r)) LISTENERS.add(r);
        }
    }

    /** Main thread: tells the screens something may have changed. */
    static void notifyListeners() {
        ArrayList<Runnable> copy;
        synchronized (LISTENERS) {
            copy = new ArrayList<>(LISTENERS);
        }
        for (Runnable r : copy) r.run();
    }

    static void unlisten(Runnable r) {
        synchronized (LISTENERS) {
            LISTENERS.remove(r);
        }
    }

    // ------------------------------------------------------------------ GitHub

    static OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    static Request.Builder call(String token, String url) {
        return new Request.Builder().url(url)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Shelf-reader");
    }

    /** Runs a request; returns the body, or throws with a readable reason. */
    static String send(Request r) throws IOException {
        Response res = http().newCall(r).execute();
        try {
            String body = res.body() == null ? "" : res.body().string();
            if (res.code() == 401) throw new IOException("GitHub didn't accept the key (it may have expired)");
            if (res.code() == 404) throw new NotFound();
            if (res.code() >= 300) throw new IOException("GitHub answered " + res.code());
            return body;
        } finally {
            res.close();
        }
    }

    static final class NotFound extends IOException {
        NotFound() {
            super("not found");
        }
    }

    /** Checks the key, finds (or makes) the private sync file, and remembers both. Background thread. */
    static void connect(Context c, String token) throws Exception {
        token = token.trim();
        JSONObject user = new JSONObject(send(call(token, API + "/user").get().build()));
        String login = user.optString("login", "");
        String gist = null;
        for (int page = 1; page <= 5 && gist == null; page++) {
            JSONArray list = new JSONArray(send(call(token, API + "/gists?per_page=100&page=" + page).get().build()));
            for (int i = 0; i < list.length() && gist == null; i++) {
                JSONObject g = list.optJSONObject(i);
                JSONObject files = g == null ? null : g.optJSONObject("files");
                if (files != null && files.has(FILE)) gist = g.optString("id", null);
            }
            if (list.length() < 100) break;
        }
        if (gist == null) gist = createGist(token);
        prefs(c).edit().putString("token", token).putString("gist", gist).putString("login", login)
                .putString("error", "").apply();
    }

    private static String createGist(String token) throws Exception {
        JSONObject file = new JSONObject();
        file.put("content", "{\"v\":1}");
        JSONObject files = new JSONObject();
        files.put(FILE, file);
        JSONObject body = new JSONObject();
        body.put("description", "Shelf reading sync (reading positions, quotes and words)");
        body.put("public", false);
        body.put("files", files);
        JSONObject made = new JSONObject(send(call(token, API + "/gists")
                .post(RequestBody.create(body.toString(), JSON)).build()));
        return made.getString("id");
    }

    static void disconnect(Context c) {
        prefs(c).edit().clear().apply();
    }

    private static String download(String token, String gist) throws Exception {
        JSONObject g = new JSONObject(send(call(token, API + "/gists/" + gist).get().build()));
        JSONObject f = g.getJSONObject("files").optJSONObject(FILE);
        if (f == null) return "{\"v\":1}";
        if (f.optBoolean("truncated", false)) {
            return send(call(token, f.getString("raw_url")).get().build());
        }
        return f.optString("content", "{\"v\":1}");
    }

    private static void upload(String token, String gist, String content) throws Exception {
        JSONObject file = new JSONObject();
        file.put("content", content);
        JSONObject files = new JSONObject();
        files.put(FILE, file);
        JSONObject body = new JSONObject();
        body.put("files", files);
        send(call(token, API + "/gists/" + gist).patch(RequestBody.create(body.toString(), JSON)).build());
    }

    // --------------------------------------------------------------- the sync

    /** Asks for a sync soon; several requests close together become one. */
    static void request(final Context context) {
        final Context c = context.getApplicationContext();
        if (!connected(c) || !QUEUED.compareAndSet(false, true)) return;
        WORK.execute(new Runnable() {
            @Override public void run() {
                QUEUED.set(false);
                runNow(c);
            }
        });
    }

    private static void runNow(final Context c) {
        SharedPreferences p = prefs(c);
        String token = p.getString("token", ""), gist = p.getString("gist", "");
        if (token.isEmpty() || gist.isEmpty()) return;
        try {
            final Library lib = Library.get(c);
            fingerprintAll(lib);
            String before;
            try {
                before = download(token, gist);
            } catch (NotFound e) {
                // The sync file was deleted on GitHub: start a new one.
                gist = createGist(token);
                p.edit().putString("gist", gist).apply();
                before = "{\"v\":1}";
            }
            final SyncMerge.Data remote = parse(before);

            // The merge touches the books the screens are showing, so it runs on the main thread.
            final CountDownLatch done = new CountDownLatch(1);
            final String[] after = new String[1];
            MAIN.post(new Runnable() {
                @Override public void run() {
                    try {
                        after[0] = applyMerge(c, lib, remote);
                    } catch (Exception e) {
                        after[0] = null;
                    } finally {
                        done.countDown();
                    }
                }
            });
            done.await(30, TimeUnit.SECONDS);
            if (after[0] != null && !after[0].equals(before)) upload(token, gist, after[0]);
            p.edit().putLong("last", System.currentTimeMillis()).putString("error", "").apply();
            CloudLibrary.request(c);
        } catch (Exception e) {
            String why = e.getMessage();
            if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException
                    || e instanceof java.net.ConnectException) {
                why = "No internet";
            }
            p.edit().putString("error", why == null ? "Sync failed" : why).apply();
        }
        MAIN.post(new Runnable() {
            @Override public void run() {
                notifyListeners();
            }
        });
    }

    private static String applyMerge(Context c, Library lib, SyncMerge.Data remote) throws Exception {
        List<Book> library = lib.all();
        HashMap<String, Long> delQ = loadDeleted(c, "q"), delW = loadDeleted(c, "w"), delB = loadDeleted(c, "b");
        SyncMerge.Outcome out = SyncMerge.merge(library, Quotes.all(c), Words.all(c), delQ, delW, delB,
                remote, openBookId, device());
        for (Book b : out.removed) {
            if (b.id.equals(openBookId)) continue;
            lib.remove(b);
            Ink.delete(c, b.id);
        }
        for (Book b : out.added) lib.addOnline(b);
        if (out.booksChanged) lib.save();
        saveDeleted(c, "b", delB);
        HashMap<String, Long> delH = loadDeleted(c, "h");
        java.util.LinkedHashMap<String, History.Entry> hist = History.load(c);
        if (SyncMerge.mergeHistory(hist, delH, remote.history, remote.deletedHistory)) History.save(c, hist);
        saveDeleted(c, "h", delH);
        for (Quotes.BookQuotes s : out.setsChanged) Quotes.save(c, s);
        if (out.wordsChanged) Words.save(c, out.words);
        saveDeleted(c, "q", delQ);
        saveDeleted(c, "w", delW);
        return serialize(remote);
    }

    // ----------------------------------------------------- deletions on this device

    private static File deletedFile(Context c, String kind) {
        return new File(c.getFilesDir(), "sync-deleted-" + kind + ".json");
    }

    private static HashMap<String, Long> loadDeleted(Context c, String kind) {
        HashMap<String, Long> map = new HashMap<>();
        File f = deletedFile(c, kind);
        if (!f.exists()) return map;
        try {
            JSONObject o = new JSONObject(new String(Util.readAll(new FileInputStream(f)), StandardCharsets.UTF_8));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                map.put(k, o.optLong(k, 0));
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static void saveDeleted(Context c, String kind, Map<String, Long> map) {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Long> e : map.entrySet()) o.put(e.getKey(), (long) e.getValue());
            FileOutputStream out = new FileOutputStream(deletedFile(c, kind));
            try {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** Remembers that a quote was deleted here, so other devices delete it too. */
    static void quoteDeleted(Context c, Quotes.BookQuotes set, String text) {
        HashMap<String, Long> map = loadDeleted(c, "q");
        map.put(SyncMerge.setId(set, Library.get(c).all()) + "\n" + text, System.currentTimeMillis());
        saveDeleted(c, "q", map);
        request(c);
    }

    /** Remembers that a book was deleted everywhere, so every device removes it. */
    static void bookDeleted(Context c, Book b) {
        if (b.key.isEmpty()) return;
        HashMap<String, Long> map = loadDeleted(c, "b");
        map.put(b.key, System.currentTimeMillis());
        saveDeleted(c, "b", map);
        request(c);
    }

    /** Remembers that a book was removed from the reading log, so other devices remove it too. */
    static void historyDeleted(Context c, String key) {
        HashMap<String, Long> map = loadDeleted(c, "h");
        map.put(key, System.currentTimeMillis());
        saveDeleted(c, "h", map);
        request(c);
    }

    static void wordDeleted(Context c, String text) {
        HashMap<String, Long> map = loadDeleted(c, "w");
        map.put(text.toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis());
        saveDeleted(c, "w", map);
        request(c);
    }

    // ----------------------------------------------------------- fingerprints

    /** A fingerprint of a book file: the same file gives the same value on every device. */
    static String fingerprint(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                long len = raf.length();
                md.update(Long.toString(len).getBytes(StandardCharsets.UTF_8));
                byte[] buf = new byte[65536];
                int n = raf.read(buf, 0, (int) Math.min(buf.length, len));
                if (n > 0) md.update(buf, 0, n);
                if (len > buf.length) {
                    long from = Math.max(buf.length, len - buf.length);
                    raf.seek(from);
                    n = raf.read(buf, 0, (int) (len - from));
                    if (n > 0) md.update(buf, 0, n);
                }
            } finally {
                raf.close();
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void fingerprintAll(final Library lib) {
        boolean any = false;
        for (Book b : lib.all()) {
            if (!b.key.isEmpty() || b.onlineOnly()) continue;
            String k = fingerprint(new File(b.file));
            if (k.isEmpty()) continue;
            b.key = k;
            any = true;
        }
        if (any) lib.save();
    }

    // ------------------------------------------------------- reading and writing

    static SyncMerge.Data parse(String json) {
        SyncMerge.Data d = new SyncMerge.Data();
        try {
            JSONObject o = new JSONObject(json);
            JSONObject books = o.optJSONObject("books");
            if (books != null) {
                Iterator<String> it = books.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject j = books.optJSONObject(k);
                    if (j == null) continue;
                    SyncMerge.Pos p = new SyncMerge.Pos();
                    p.title = j.optString("title", "");
                    p.device = j.optString("device", "");
                    p.chapter = j.optInt("chapter", 0);
                    p.offset = (float) j.optDouble("offset", 0);
                    p.progress = (float) j.optDouble("progress", 0);
                    p.time = j.optLong("time", 0);
                    p.reading = j.optBoolean("reading", false);
                    p.shelfTime = j.optLong("shelfTime", 0);
                    p.marksTime = j.optLong("marksTime", 0);
                    JSONArray marks = j.optJSONArray("marks");
                    for (int i = 0; marks != null && i < marks.length(); i++) {
                        JSONObject m = marks.optJSONObject(i);
                        if (m == null) continue;
                        Book.Mark mark = new Book.Mark();
                        mark.chapter = m.optInt("chapter", 0);
                        mark.offset = (float) m.optDouble("offset", 0);
                        mark.label = m.optString("label", "");
                        mark.time = m.optLong("time", 0);
                        p.marks.add(mark);
                    }
                    d.books.put(k, p);
                }
            }
            JSONObject quotes = o.optJSONObject("quotes");
            if (quotes != null) {
                Iterator<String> it = quotes.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject j = quotes.optJSONObject(k);
                    if (j == null) continue;
                    SyncMerge.QSet s = new SyncMerge.QSet();
                    s.title = j.optString("title", "");
                    s.author = j.optString("author", "");
                    JSONArray names = j.optJSONArray("names");
                    for (int i = 0; names != null && i < names.length(); i++) {
                        String n = names.optString(i, "");
                        if (!n.isEmpty()) s.names.add(n);
                    }
                    JSONArray qs = j.optJSONArray("quotes");
                    for (int i = 0; qs != null && i < qs.length(); i++) {
                        JSONObject q = qs.optJSONObject(i);
                        if (q == null || q.optString("text", "").isEmpty()) continue;
                        Quotes.Quote quote = new Quotes.Quote();
                        quote.text = q.optString("text", "");
                        quote.where = q.optString("where", "");
                        quote.time = q.optLong("time", 0);
                        s.quotes.add(quote);
                    }
                    d.quotes.put(k, s);
                }
            }
            JSONArray words = o.optJSONArray("words");
            for (int i = 0; words != null && i < words.length(); i++) {
                JSONObject j = words.optJSONObject(i);
                if (j == null || j.optString("text", "").isEmpty()) continue;
                Words.Entry e = new Words.Entry();
                e.text = j.optString("text", "");
                e.arabic = j.optString("arabic", "");
                e.meanings = j.optString("meanings", "");
                e.book = j.optString("book", "");
                e.time = j.optLong("time", 0);
                d.words.add(e);
            }
            JSONObject del = o.optJSONObject("deleted");
            if (del != null) {
                readTimes(del.optJSONObject("quotes"), d.deletedQuotes);
                readTimes(del.optJSONObject("words"), d.deletedWords);
                readTimes(del.optJSONObject("books"), d.deletedBooks);
                readTimes(del.optJSONObject("history"), d.deletedHistory);
            }
            JSONObject hist = o.optJSONObject("history");
            if (hist != null) {
                Iterator<String> it = hist.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject j = hist.optJSONObject(k);
                    if (j != null) d.history.put(k, History.fromJson(k, j));
                }
            }
            JSONObject lib = o.optJSONObject("library");
            if (lib != null) {
                Iterator<String> it = lib.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject j = lib.optJSONObject(k);
                    if (j == null) continue;
                    SyncMerge.Entry e = new SyncMerge.Entry();
                    e.title = j.optString("title", "");
                    e.author = j.optString("author", "");
                    e.format = j.optString("format", "");
                    e.source = j.optString("source", "");
                    e.size = j.optLong("size", 0);
                    e.added = j.optLong("added", 0);
                    e.infoTime = j.optLong("infoTime", 0);
                    e.asset = j.optString("asset", "");
                    e.cover = j.optString("cover", "");
                    e.coverTime = j.optLong("coverTime", 0);
                    e.ink = j.optString("ink", "");
                    e.inkTime = j.optLong("inkTime", 0);
                    d.library.put(k, e);
                }
            }
        } catch (Exception ignored) {
            // An unreadable online copy is treated as empty; this device's data then fills it.
        }
        return d;
    }

    private static void readTimes(JSONObject o, Map<String, Long> into) {
        if (o == null) return;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            into.put(k, o.optLong(k, 0));
        }
    }

    static String serialize(SyncMerge.Data d) throws Exception {
        JSONObject books = new JSONObject();
        for (Map.Entry<String, SyncMerge.Pos> e : d.books.entrySet()) {
            SyncMerge.Pos p = e.getValue();
            JSONArray marks = new JSONArray();
            for (Book.Mark m : p.marks) {
                JSONObject j = new JSONObject();
                j.put("chapter", m.chapter);
                j.put("offset", (double) m.offset);
                j.put("label", m.label);
                j.put("time", m.time);
                marks.put(j);
            }
            JSONObject j = new JSONObject();
            j.put("title", p.title);
            j.put("device", p.device);
            j.put("chapter", p.chapter);
            j.put("offset", (double) p.offset);
            j.put("progress", (double) p.progress);
            j.put("time", p.time);
            j.put("reading", p.reading);
            j.put("shelfTime", p.shelfTime);
            j.put("marks", marks);
            j.put("marksTime", p.marksTime);
            books.put(e.getKey(), j);
        }
        JSONObject quotes = new JSONObject();
        for (Map.Entry<String, SyncMerge.QSet> e : d.quotes.entrySet()) {
            JSONArray qs = new JSONArray();
            for (Quotes.Quote q : e.getValue().quotes) {
                JSONObject j = new JSONObject();
                j.put("text", q.text);
                j.put("where", q.where);
                j.put("time", q.time);
                qs.put(j);
            }
            JSONObject j = new JSONObject();
            j.put("title", e.getValue().title);
            j.put("author", e.getValue().author);
            JSONArray names = new JSONArray();
            for (String n : e.getValue().names) names.put(n);
            j.put("names", names);
            j.put("quotes", qs);
            quotes.put(e.getKey(), j);
        }
        JSONArray words = new JSONArray();
        for (Words.Entry e : d.words) {
            JSONObject j = new JSONObject();
            j.put("text", e.text);
            j.put("arabic", e.arabic);
            j.put("meanings", e.meanings);
            j.put("book", e.book);
            j.put("time", e.time);
            words.put(j);
        }
        JSONObject delQ = new JSONObject(), delW = new JSONObject();
        for (Map.Entry<String, Long> e : d.deletedQuotes.entrySet()) delQ.put(e.getKey(), (long) e.getValue());
        for (Map.Entry<String, Long> e : d.deletedWords.entrySet()) delW.put(e.getKey(), (long) e.getValue());
        JSONObject del = new JSONObject();
        del.put("quotes", delQ);
        del.put("words", delW);
        JSONObject delB = new JSONObject();
        for (Map.Entry<String, Long> e : d.deletedBooks.entrySet()) delB.put(e.getKey(), (long) e.getValue());
        del.put("books", delB);
        JSONObject delH = new JSONObject();
        for (Map.Entry<String, Long> e : d.deletedHistory.entrySet()) delH.put(e.getKey(), (long) e.getValue());
        del.put("history", delH);
        JSONObject hist = new JSONObject();
        for (Map.Entry<String, History.Entry> e : d.history.entrySet()) hist.put(e.getKey(), History.toJson(e.getValue()));
        JSONObject lib = new JSONObject();
        for (Map.Entry<String, SyncMerge.Entry> me : d.library.entrySet()) {
            SyncMerge.Entry e = me.getValue();
            JSONObject j = new JSONObject();
            j.put("title", e.title);
            j.put("author", e.author);
            j.put("format", e.format);
            j.put("source", e.source);
            j.put("size", e.size);
            j.put("added", e.added);
            j.put("infoTime", e.infoTime);
            j.put("asset", e.asset);
            j.put("cover", e.cover);
            j.put("coverTime", e.coverTime);
            j.put("ink", e.ink);
            j.put("inkTime", e.inkTime);
            lib.put(me.getKey(), j);
        }
        JSONObject o = new JSONObject();
        o.put("v", 1);
        o.put("books", books);
        o.put("quotes", quotes);
        o.put("words", words);
        o.put("deleted", del);
        o.put("library", lib);
        o.put("history", hist);
        return o.toString(1);
    }
}
