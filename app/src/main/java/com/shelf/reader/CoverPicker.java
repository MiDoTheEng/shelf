package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;

import com.shelf.reader.core.BookContent;
import com.shelf.reader.core.EpubBook;
import com.shelf.reader.core.MobiBook;
import com.shelf.reader.core.Util;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Choosing a book's cover: one found online (Google Books and Open Library), a photo, a page
 * of a PDF, a picture inside an EPUB/Kindle book, or the original.
 */
final class CoverPicker {
    static final int PICK_PHOTO = 43;

    private CoverPicker() {}

    static void show(final Activity a, final Book b, final Runnable done) {
        final ArrayList<String> items = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Find online");
        actions.add(new Runnable() {
            @Override public void run() {
                askOnline(a, b, done);
            }
        });
        items.add("Choose from photos");
        actions.add(new Runnable() {
            @Override public void run() {
                pickPhoto(a, b);
            }
        });
        if (b.format.equals("pdf")) {
            items.add("Use a page of the book");
            actions.add(new Runnable() {
                @Override public void run() {
                    askPage(a, b, done);
                }
            });
        } else {
            items.add("Choose a picture from the book");
            actions.add(new Runnable() {
                @Override public void run() {
                    bookPictures(a, b, done);
                }
            });
        }
        if (!b.customCover.isEmpty()) {
            items.add("Restore the original cover");
            actions.add(new Runnable() {
                @Override public void run() {
                    Library.get(a).restoreCover(b);
                    done.run();
                }
            });
        }
        new AlertDialog.Builder(a)
                .setTitle("Cover of “" + b.title + "”")
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ photos

    private static String waitingFor;

    private static void pickPhoto(Activity a, Book b) {
        waitingFor = b.id;
        a.getSharedPreferences("shelf", Activity.MODE_PRIVATE).edit().putString("coverFor", b.id).apply();
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("image/*");
        try {
            a.startActivityForResult(i, PICK_PHOTO);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            g.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                a.startActivityForResult(g, PICK_PHOTO);
            } catch (Exception e2) {
                Ui.toast(a, "No photo app is available");
            }
        }
    }

    /** Called with the photo the reader picked. */
    static void photoPicked(final Activity a, final Uri uri, final Runnable done) {
        String id = waitingFor != null ? waitingFor
                : a.getSharedPreferences("shelf", Activity.MODE_PRIVATE).getString("coverFor", "");
        final Book b = Library.get(a).find(id);
        if (b == null || uri == null) return;
        Ui.toast(a, "Setting the cover…");
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap bmp = decodePhoto(a, uri);
                finish(a, b, bmp, done);
            }
        }).start();
    }

    private static Bitmap decodePhoto(Activity a, Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = a.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 600 && o.outHeight / (sample * 2) >= 600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            in = a.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            Bitmap bmp;
            try {
                bmp = BitmapFactory.decodeStream(in, null, o);
            } finally {
                in.close();
            }
            if (bmp == null) return null;
            // Photos from the camera are often stored sideways, with a note saying how to turn them.
            int turn = 0;
            in = a.getContentResolver().openInputStream(uri);
            if (in != null) {
                try {
                    int or = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                    turn = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                            : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                            : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                } catch (Exception ignored) {
                } finally {
                    in.close();
                }
            }
            if (turn != 0) {
                Matrix m = new Matrix();
                m.postRotate(turn);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // ------------------------------------------------------------------- pages

    private static void askPage(final Activity a, final Book b, final Runnable done) {
        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(b.chapter + 1));
        input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(a);
        wrap.setPadding(Ui.dp(a, 22), Ui.dp(a, 8), Ui.dp(a, 22), 0);
        wrap.addView(input);
        new AlertDialog.Builder(a)
                .setTitle("Which page should be the cover?")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use page", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n;
                        try {
                            n = Integer.parseInt(input.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            return;
                        }
                        usePage(a, b, n - 1, done);
                    }
                })
                .show();
    }

    /** Makes a page of a PDF the cover (pages counted from 0). */
    static void usePage(final Activity a, final Book b, final int page, final Runnable done) {
        Ui.toast(a, "Setting the cover…");
        new Thread(new Runnable() {
            @Override public void run() {
                File f = new File(b.file);
                int count = Library.pdfPageCount(f);
                Bitmap bmp = count == 0 ? null : Library.pdfPage(f, Math.max(0, Math.min(count - 1, page)), 600);
                finish(a, b, bmp, done);
            }
        }).start();
    }

    // ------------------------------------------------------- pictures in a book

    private static void bookPictures(final Activity a, final Book b, final Runnable done) {
        Ui.toast(a, "Looking for pictures in the book…");
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<String> paths = new ArrayList<>();
                final ArrayList<Bitmap> thumbs = new ArrayList<>();
                BookContent c = null;
                try {
                    File f = new File(b.file);
                    c = b.format.equals("epub") ? new EpubBook(f) : new MobiBook(f);
                    for (String p : c.images(60)) {
                        byte[] data = read(c, p);
                        Bitmap t = data == null ? null : decode(data, 220);
                        // Skip tiny pictures such as ornaments and dividers.
                        if (t == null || t.getWidth() < 60 || t.getHeight() < 60) continue;
                        paths.add(p);
                        thumbs.add(t);
                        if (thumbs.size() >= 36) break;
                    }
                } catch (Exception | OutOfMemoryError ignored) {
                } finally {
                    try {
                        if (c != null) c.close();
                    } catch (Exception ignored) {
                    }
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isDestroyed()) return;
                        if (thumbs.isEmpty()) {
                            Ui.toast(a, "This book has no pictures to use. Try a photo instead.");
                            return;
                        }
                        showGrid(a, b, paths, thumbs, done);
                    }
                });
            }
        }).start();
    }

    private static byte[] read(BookContent c, String path) {
        try {
            BookContent.Res r = c.open(path);
            return r == null ? null : Util.readAll(r.in);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] data, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** Fetches the full-size picture behind a thumbnail in the grid, off the main thread. */
    private interface Full {
        Bitmap load(int position);
    }

    private static void showGrid(final Activity a, final Book b, final List<String> paths,
                                 final List<Bitmap> thumbs, final Runnable done) {
        showGrid(a, b, thumbs, "Tap a picture", new Full() {
            @Override public Bitmap load(int position) {
                Bitmap full = null;
                BookContent c = null;
                try {
                    File f = new File(b.file);
                    c = b.format.equals("epub") ? new EpubBook(f) : new MobiBook(f);
                    byte[] data = read(c, paths.get(position));
                    if (data != null) full = decode(data, 600);
                } catch (Exception | OutOfMemoryError ignored) {
                } finally {
                    try {
                        if (c != null) c.close();
                    } catch (Exception ignored) {
                    }
                }
                return full;
            }
        }, done);
    }

    private static void showGrid(final Activity a, final Book b, final List<Bitmap> thumbs, String title,
                                 final Full full, final Runnable done) {
        GridView grid = new GridView(a);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(Ui.dp(a, 8));
        grid.setHorizontalSpacing(Ui.dp(a, 8));
        grid.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 10));
        final int cell = Ui.dp(a, 130);
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return thumbs.size(); }
            @Override public Object getItem(int i) { return i; }
            @Override public long getItemId(int i) { return i; }

            @Override public View getView(int i, View convert, ViewGroup parent) {
                ImageView iv = convert instanceof ImageView ? (ImageView) convert : new ImageView(a);
                iv.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cell));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(thumbs.get(i));
                return iv;
            }
        });
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(grid)
                .setNegativeButton("Cancel", null)
                .create();
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, final int position, long id) {
                dialog.dismiss();
                Ui.toast(a, "Setting the cover…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        finish(a, b, full.load(position), done);
                    }
                }).start();
            }
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ online

    private static okhttp3.OkHttpClient http;

    private static synchronized okhttp3.OkHttpClient http() {
        if (http == null) {
            http = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    /** Asks what to search for, starting from the book's name and author. */
    private static void askOnline(final Activity a, final Book b, final Runnable done) {
        final EditText field = new EditText(a);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        String start = b.title == null ? "" : b.title;
        if (b.author != null && !b.author.isEmpty()) start += " " + b.author;
        field.setText(start.trim());
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 8), Ui.dp(a, 20), 0);
        box.addView(field);
        new AlertDialog.Builder(a)
                .setTitle("Find a cover online")
                .setMessage("Book name, and the author if you know it")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String q = field.getText().toString().trim();
                        if (q.isEmpty()) return;
                        searchOnline(a, b, q, done);
                    }
                })
                .show();
    }

    /** One cover found online: a small picture for the grid and the large one to keep. */
    private static final class Found {
        final String small, large;

        Found(String small, String large) {
            this.small = small;
            this.large = large;
        }
    }

    private static void searchOnline(final Activity a, final Book b, final String q, final Runnable done) {
        Ui.toast(a, "Searching for covers…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Found> found = new ArrayList<>();
                try {
                    googleBooks(q, found);
                } catch (Exception ignored) {
                }
                try {
                    openLibrary(q, found);
                } catch (Exception ignored) {
                }
                // Fetch the small pictures a few at a time.
                final Bitmap[] small = new Bitmap[found.size()];
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
                for (int i = 0; i < found.size(); i++) {
                    final int k = i;
                    pool.execute(new Runnable() {
                        @Override public void run() {
                            small[k] = fetch(found.get(k).small, 300);
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(40, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                final List<Bitmap> thumbs = new ArrayList<>();
                final List<Found> shown = new ArrayList<>();
                for (int i = 0; i < found.size(); i++) {
                    // Skip blanks: missing covers come back as tiny placeholder images.
                    if (small[i] != null && small[i].getWidth() > 20 && small[i].getHeight() > 30) {
                        thumbs.add(small[i]);
                        shown.add(found.get(i));
                    }
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (a.isDestroyed()) return;
                        if (thumbs.isEmpty()) {
                            Ui.toast(a, Sync.connected(a) ? "No covers found. Try the book's exact name"
                                    : "You're offline");
                            return;
                        }
                        showGrid(a, b, thumbs, "Tap a cover", new Full() {
                            @Override public Bitmap load(int position) {
                                Bitmap big = fetch(shown.get(position).large, 900);
                                return big != null && big.getWidth() > 20 ? big : thumbs.get(position);
                            }
                        }, done);
                    }
                });
            }
        }).start();
    }

    private static String get(String url) throws java.io.IOException {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Shelf reader (Android)").build();
        try (okhttp3.Response r = http().newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new java.io.IOException("HTTP " + r.code());
            return r.body().string();
        }
    }

    private static Bitmap fetch(String url, int maxWidth) {
        try {
            okhttp3.Request req = new okhttp3.Request.Builder().url(url)
                    .header("User-Agent", "Shelf reader (Android)").build();
            try (okhttp3.Response r = http().newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return null;
                byte[] data = r.body().bytes();
                return decode(data, maxWidth);
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static String enc(String q) {
        try {
            return java.net.URLEncoder.encode(q, "UTF-8");
        } catch (Exception e) {
            return q;
        }
    }

    private static void googleBooks(String q, List<Found> out) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(
                get("https://www.googleapis.com/books/v1/volumes?maxResults=20&printType=books&q=" + enc(q)));
        org.json.JSONArray items = o.optJSONArray("items");
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) {
            org.json.JSONObject info = items.getJSONObject(i).optJSONObject("volumeInfo");
            org.json.JSONObject links = info == null ? null : info.optJSONObject("imageLinks");
            if (links == null) continue;
            String t = links.optString("thumbnail", links.optString("smallThumbnail", ""));
            if (t.isEmpty()) continue;
            t = t.replace("http://", "https://").replace("&edge=curl", "");
            // The same picture, asked for in a larger size.
            String big = t.replaceAll("&zoom=\\d", "") + "&fife=w800";
            out.add(new Found(t, big));
        }
    }

    private static void openLibrary(String q, List<Found> out) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject(
                get("https://openlibrary.org/search.json?limit=20&fields=cover_i&q=" + enc(q)));
        org.json.JSONArray docs = o.optJSONArray("docs");
        if (docs == null) return;
        for (int i = 0; i < docs.length(); i++) {
            long id = docs.getJSONObject(i).optLong("cover_i", 0);
            if (id <= 0) continue;
            String base = "https://covers.openlibrary.org/b/id/" + id;
            out.add(new Found(base + "-M.jpg", base + "-L.jpg"));
        }
    }

    // ------------------------------------------------------------------ saving

    private static void finish(final Activity a, final Book b, final Bitmap bmp, final Runnable done) {
        String error = null;
        if (bmp == null) {
            error = "That picture couldn't be used";
        } else {
            try {
                Library.get(a).setCustomCover(b, bmp);
            } catch (Exception | OutOfMemoryError e) {
                error = "Couldn't save the cover";
            }
        }
        final String why = error;
        a.runOnUiThread(new Runnable() {
            @Override public void run() {
                if (a.isDestroyed()) return;
                Ui.toast(a, why == null ? "Cover changed" : why);
                if (why == null) done.run();
            }
        });
    }
}
