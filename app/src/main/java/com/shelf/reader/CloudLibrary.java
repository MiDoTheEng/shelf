package com.shelf.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The online library: book files, covers, highlights and drawings kept in a private GitHub
 * repo ("shelf-library"), as files of one release. Free, with no limit on total size
 * (each file must be under 2 GB). A fresh install gets every book back from here.
 */
final class CloudLibrary {
    static final String REPO = "shelf-library";
    private static final String API = "https://api.github.com";
    private static final MediaType BYTES = MediaType.get("application/octet-stream");

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean QUEUED = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OkHttpClient big;
    /** Shown on the sync screen. */
    static volatile String status = "";

    private CloudLibrary() {}

    interface Progress {
        void at(long done, long total);
    }

    private static OkHttpClient big() {
        if (big == null) {
            // Book files can be large: no time limit while sending or receiving them.
            big = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)
                    .writeTimeout(0, TimeUnit.SECONDS)
                    .build();
        }
        return big;
    }

    /** True when the key may also use the online library (a repo), not only the sync file. */
    static boolean enabled(Context c) {
        return Sync.connected(c) && Sync.prefs(c).getBoolean("repoOk", false);
    }

    /** Checks whether a key may use repos; remembered for this device. Background thread. */
    static boolean checkKey(Context c, String token) {
        boolean ok = false;
        try {
            Response r = Sync.http().newCall(Sync.call(token, API + "/user").get().build()).execute();
            try {
                String scopes = r.header("X-OAuth-Scopes");
                ok = r.code() == 200 && scopes != null && (" " + scopes.replace(",", " ") + " ").contains(" repo ");
            } finally {
                r.close();
            }
        } catch (Exception ignored) {
        }
        Sync.prefs(c).edit().putBoolean("repoOk", ok).apply();
        return ok;
    }

    // ------------------------------------------------------------ the repo itself

    private static String owner(Context c) {
        return Sync.account(c);
    }

    /** Finds or makes the private repo and its release; returns the release id. */
    private static long release(Context c, String token) throws Exception {
        long id = Sync.prefs(c).getLong("releaseId", 0);
        if (id != 0) return id;
        String repo = API + "/repos/" + owner(c) + "/" + REPO;
        try {
            Sync.send(Sync.call(token, repo).get().build());
        } catch (Sync.NotFound e) {
            JSONObject body = new JSONObject();
            body.put("name", REPO);
            body.put("private", true);
            body.put("auto_init", true);
            body.put("description", "Books for the Shelf reader app (private)");
            Sync.send(Sync.call(token, API + "/user/repos")
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build());
        }
        JSONObject rel;
        try {
            rel = new JSONObject(Sync.send(Sync.call(token, repo + "/releases/tags/books").get().build()));
        } catch (Sync.NotFound e) {
            JSONObject body = new JSONObject();
            body.put("tag_name", "books");
            body.put("name", "Books");
            body.put("body", "Book files, covers and drawings from the Shelf app.");
            rel = new JSONObject(Sync.send(Sync.call(token, repo + "/releases")
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build()));
        }
        id = rel.getLong("id");
        Sync.prefs(c).edit().putLong("releaseId", id).apply();
        return id;
    }

    /** Every file stored online: name -> id. */
    private static HashMap<String, Long> files(Context c, String token, long release) throws Exception {
        HashMap<String, Long> out = new HashMap<>();
        for (int page = 1; page <= 50; page++) {
            JSONArray arr = new JSONArray(Sync.send(Sync.call(token, API + "/repos/" + owner(c) + "/" + REPO
                    + "/releases/" + release + "/assets?per_page=100&page=" + page).get().build()));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                out.put(a.getString("name"), a.getLong("id"));
            }
            if (arr.length() < 100) break;
        }
        return out;
    }

    private static void upload(Context c, String token, long release, String name, RequestBody body,
                               HashMap<String, Long> files) throws Exception {
        if (files.containsKey(name)) return;
        String url = "https://uploads.github.com/repos/" + owner(c) + "/" + REPO + "/releases/" + release
                + "/assets?name=" + URLEncoder.encode(name, "UTF-8");
        Response r = big().newCall(Sync.call(token, url).post(body).build()).execute();
        try {
            int code = r.code();
            // 422: a file with this name is already there (sent by another device). That's fine.
            if (code != 201 && code != 422) throw new IOException("GitHub answered " + code + " while uploading");
            if (code == 201 && r.body() != null) {
                JSONObject a = new JSONObject(r.body().string());
                files.put(name, a.getLong("id"));
            } else {
                files.put(name, -1L);
            }
        } finally {
            r.close();
        }
    }

    private static void download(Context c, String token, long assetId, File to, Progress p) throws Exception {
        Request req = Sync.call(token, API + "/repos/" + owner(c) + "/" + REPO + "/releases/assets/" + assetId)
                .header("Accept", "application/octet-stream").get().build();
        Response r = big().newCall(req).execute();
        try {
            if (r.code() != 200 || r.body() == null) throw new IOException("GitHub answered " + r.code());
            ResponseBody body = r.body();
            long total = body.contentLength();
            File tmp = new File(to.getPath() + ".part");
            InputStream in = body.byteStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.at(done, total);
                }
            } finally {
                out.close();
                in.close();
            }
            if (!tmp.renameTo(to)) {
                to.delete();
                if (!tmp.renameTo(to)) throw new IOException("Couldn't save the file");
            }
        } finally {
            r.close();
        }
    }

    private static void delete(Context c, String token, long assetId) {
        try {
            Sync.send(Sync.call(token, API + "/repos/" + owner(c) + "/" + REPO + "/releases/assets/" + assetId)
                    .delete().build());
        } catch (Exception ignored) {
        }
    }

    private static String ext(Book b) {
        String f = b.file.isEmpty() ? b.asset : b.file;
        int dot = f.lastIndexOf('.');
        return dot < 0 ? b.format : f.substring(dot + 1);
    }

    // ------------------------------------------------------------- the worker

    /** Moves files to and from the online library soon, in the background. */
    static void request(final Context context) {
        final Context c = context.getApplicationContext();
        if (!enabled(c) || !QUEUED.compareAndSet(false, true)) return;
        WORK.execute(new Runnable() {
            @Override public void run() {
                QUEUED.set(false);
                boolean changed = false;
                try {
                    changed = work(c);
                    status = "";
                } catch (Exception e) {
                    status = e.getMessage() == null ? "Online library: couldn't reach GitHub" : e.getMessage();
                    if (e instanceof Sync.NotFound) Sync.prefs(c).edit().remove("releaseId").apply();
                }
                // New files online are announced to other devices by the next sync.
                if (changed) Sync.request(c);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        Sync.notifyListeners();
                    }
                });
            }
        });
    }

    private static boolean work(Context c) throws Exception {
        String token = Sync.token(c);
        Library lib = Library.get(c);
        long release = release(c, token);
        HashMap<String, Long> files = null;
        boolean changed = false;
        List<Book> books = lib.all();
        int left = 0;
        for (Book b : books) if (!b.onlineOnly() && b.asset.isEmpty() && !b.key.isEmpty()) left++;

        // 1. Highlights and drawings first: they're small, and they're what you're waiting for
        // on the other device. Send ours when they're newer, fetch theirs when theirs are.
        for (Book b : books) {
            if (b.key.isEmpty() || lib.find(b.id) == null) continue;
            if (files == null) files = files(c, token, release);
            Ink showing = Ink.open;
            // Never swap the drawings of an open book while there are unsaved strokes on it.
            if (showing != null && showing.isFor(b.id) && showing.dirty) continue;
            if (b.inkPending && !b.inkAsset.isEmpty()) {
                Long id = files.get(b.inkAsset);
                if (id != null && id > 0) {
                    download(c, token, id, Ink.fileFor(c, b.id), null);
                    synchronized (lib) {
                        b.inkPending = false;
                    }
                    lib.save();
                } else if (id == null) {
                    synchronized (lib) {
                        b.inkPending = false;
                    }
                }
            } else if (b.inkTime > 0 && b.inkAsset.isEmpty() && !b.onlineOnly()) {
                File ink = Ink.fileFor(c, b.id);
                byte[] data = ink.exists() ? com.shelf.reader.core.Util.readAll(new java.io.FileInputStream(ink))
                        : "{}".getBytes(StandardCharsets.UTF_8);
                String name = b.key + "-ink-" + b.inkTime + ".json";
                upload(c, token, release, name, RequestBody.create(data, BYTES), files);
                synchronized (lib) {
                    b.inkAsset = name;
                }
                lib.save();
                changed = true;
            }
        }
        // Tell the other devices about new drawings now, before any long book upload.
        if (changed) {
            Sync.request(c);
            changed = false;
        }

        for (Book b : books) {
            if (b.key.isEmpty() || lib.find(b.id) == null) continue;
            if (files == null) files = files(c, token, release);

            // 2. The book file itself, once.
            if (!b.onlineOnly() && b.asset.isEmpty()) {
                File f = new File(b.file);
                if (!f.exists()) continue;
                status = "Uploading “" + b.title + "” (" + left + " to go)";
                String name = b.key + "." + ext(b);
                upload(c, token, release, name, RequestBody.create(f, BYTES), files);
                synchronized (lib) {
                    b.asset = name;
                }
                lib.save();
                left--;
                changed = true;
            }

            // 3. Its cover: send ours if it's newer, fetch theirs if it's newer.
            if (b.coverPending && !b.coverAsset.isEmpty()) {
                Long id = files.get(b.coverAsset);
                if (id != null && id > 0) {
                    File to = new File(c.getFilesDir(), "covers/" + b.id + "-c" + b.coverTime + ".jpg");
                    download(c, token, id, to, null);
                    synchronized (lib) {
                        CoverCache.forget(b);
                        if (b.onlineOnly() || b.cover.isEmpty()) {
                            if (!b.cover.isEmpty() && !b.cover.equals(to.getPath())) new File(b.cover).delete();
                            b.cover = to.getPath();
                        } else {
                            if (!b.customCover.isEmpty()) new File(b.customCover).delete();
                            b.customCover = to.getPath();
                        }
                        b.coverPending = false;
                    }
                    lib.save();
                } else if (id == null) {
                    synchronized (lib) {
                        b.coverPending = false;
                    }
                }
            } else if (b.coverAsset.isEmpty() && !b.coverPath().isEmpty() && new File(b.coverPath()).exists()) {
                long t = b.coverTime > 0 ? b.coverTime : Math.max(1, b.added);
                String name = b.key + "-cover-" + t + ".jpg";
                upload(c, token, release, name, RequestBody.create(new File(b.coverPath()), BYTES), files);
                synchronized (lib) {
                    b.coverTime = t;
                    b.coverAsset = name;
                }
                lib.save();
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------- user actions

    /** Downloads an online-only book so it can be read. Background thread; throws with a readable reason. */
    static void fetchBook(Context c, Book b, Progress p) throws Exception {
        String token = Sync.token(c);
        if (token.isEmpty()) throw new IOException("Connect sync (↻) to get your online books");
        long release = release(c, token);
        Long id = files(c, token, release).get(b.asset);
        if (id == null) throw new IOException("This book isn't in your online library any more");
        File dir = new File(c.getFilesDir(), "books");
        dir.mkdirs();
        File to = new File(dir, b.id + "." + ext(b));
        download(c, token, id, to, p);
        Library lib = Library.get(c);
        synchronized (lib) {
            b.file = to.getPath();
            b.size = to.length();
        }
        lib.save();
    }

    /** Removes a book's files from the online library (after "Delete everywhere"). */
    static void forget(final Context context, final Book b) {
        final Context c = context.getApplicationContext();
        if (!enabled(c)) return;
        WORK.execute(new Runnable() {
            @Override public void run() {
                try {
                    String token = Sync.token(c);
                    long release = release(c, token);
                    HashMap<String, Long> files = files(c, token, release);
                    for (String name : new ArrayList<>(files.keySet())) {
                        if (name.startsWith(b.key + ".") || name.startsWith(b.key + "-")) delete(c, token, files.get(name));
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** Counts for the sync screen: {books here, uploaded, online-only}. */
    static int[] counts(Context c) {
        int here = 0, up = 0, online = 0;
        for (Book b : Library.get(c).all()) {
            if (b.onlineOnly()) online++;
            else {
                here++;
                if (!b.asset.isEmpty()) up++;
            }
        }
        return new int[]{here, up, online};
    }
}
