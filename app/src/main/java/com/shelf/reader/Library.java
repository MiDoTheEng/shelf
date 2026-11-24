package com.shelf.reader;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import com.shelf.reader.core.BookContent;
import com.shelf.reader.core.EpubBook;
import com.shelf.reader.core.MobiBook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipFile;

/** The collection of books, stored as private copies inside the app plus a small JSON index. */
final class Library {
    private static Library instance;

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    private final File index;
    private final File booksDir;
    private final File coversDir;
    private final ArrayList<Book> books = new ArrayList<>();
    private final Random random = new Random();

    private final Context context;

    private Library(Context c) {
        context = c;
        File root = c.getFilesDir();
        index = new File(root, "library.json");
        booksDir = new File(root, "books");
        coversDir = new File(root, "covers");
        booksDir.mkdirs();
        coversDir.mkdirs();
        load();
    }

    private void load() {
        if (!index.exists()) return;
        try {
            byte[] data = com.shelf.reader.core.Util.readAll(new FileInputStream(index));
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                Book b = Book.fromJson(arr.getJSONObject(i));
                // Online-only books have no file here yet; they are downloaded when opened.
                if (b.onlineOnly() ? !b.asset.isEmpty() : new File(b.file).exists()) books.add(b);
            }
        } catch (Exception ignored) {
            // A damaged index simply starts an empty library; the files stay on disk.
        }
    }

    synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Book b : books) arr.put(b.toJson());
            File tmp = new File(index.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(index)) {
                index.delete();
                tmp.renameTo(index);
            }
        } catch (Exception ignored) {
        }
    }

    synchronized List<Book> all() {
        return new ArrayList<>(books);
    }

    synchronized Book find(String id) {
        for (Book b : books) if (b.id.equals(id)) return b;
        return null;
    }

    /** Adds a book that so far exists only in the online library. */
    synchronized void addOnline(Book b) {
        for (Book other : books) if (other.id.equals(b.id) || (!b.key.isEmpty() && b.key.equals(other.key))) return;
        books.add(b);
    }

    /** Deletes the file on this device but keeps the book on the shelves, ready to download again. */
    synchronized void removeDownload(Book b) {
        if (b.onlineOnly() || b.asset.isEmpty()) return;
        new File(b.file).delete();
        b.file = "";
        save();
    }

    synchronized void remove(Book b) {
        books.remove(b);
        if (!b.file.isEmpty()) new File(b.file).delete();
        if (!b.cover.isEmpty()) new File(b.cover).delete();
        if (!b.customCover.isEmpty()) new File(b.customCover).delete();
        CoverCache.forget(b);
        save();
    }

    /** Copies the picked file into the app and puts it on the Library shelf. */
    Book importUri(Context c, Uri uri) throws IOException {
        String name = displayName(c, uri);
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(random.nextInt(46656), 36);
        File tmp = new File(booksDir, id + ".tmp");
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Could not open the file");
        try {
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }

        Book b = new Book();
        b.title = baseName(name);
        File file = null;
        try {
            String format = sniff(tmp);
            file = new File(booksDir, id + "." + format);
            if (!tmp.renameTo(file)) throw new IOException("Could not store the file");
            b.id = id;
            b.format = format;
            b.file = file.getPath();
            b.source = name == null ? "" : name;
            b.size = file.length();
            b.added = System.currentTimeMillis();
            b.key = Sync.fingerprint(file);
            synchronized (this) {
                for (Book other : books) {
                    if (!b.key.isEmpty() && b.key.equals(other.key) && other.onlineOnly()) {
                        File mine = new File(booksDir, other.id + "." + format);
                        if (!file.renameTo(mine)) mine = file;
                        other.file = mine.getPath();
                        other.size = mine.length();
                        save();
                        return other;
                    }
                    boolean same = !b.key.isEmpty() && b.key.equals(other.key);
                    if (same || (other.size == b.size && other.source.equals(b.source) && !b.source.isEmpty())) {
                        file.delete();
                        return other;
                    }
                }
            }
            Bitmap cover;
            if (format.equals("pdf")) {
                cover = pdfCover(file);
            } else {
                BookContent content = format.equals("epub") ? new EpubBook(file) : new MobiBook(file);
                try {
                    if (!content.title().trim().isEmpty()) b.title = content.title().trim();
                    b.author = content.author() == null ? "" : content.author().trim();
                    cover = decodeCover(content.cover());
                } finally {
                    content.close();
                }
            }
            if (b.title.isEmpty()) b.title = "Untitled";
            if (cover != null) {
                File cf = new File(coversDir, id + ".jpg");
                FileOutputStream out = new FileOutputStream(cf);
                try {
                    cover.compress(Bitmap.CompressFormat.JPEG, 88, out);
                } finally {
                    out.close();
                }
                b.cover = cf.getPath();
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            tmp.delete();
            if (file != null) file.delete();
            String label = b.title.isEmpty() ? "File" : b.title;
            String why = e instanceof IOException && e.getMessage() != null ? e.getMessage() : "could not read this file";
            throw new IOException(label + ": " + why, e);
        }
        synchronized (this) {
            books.add(b);
        }
        save();
        return b;
    }

    private static String sniff(File f) throws IOException {
        byte[] head = new byte[1024];
        int n;
        FileInputStream in = new FileInputStream(f);
        try {
            n = Math.max(0, in.read(head));
        } finally {
            in.close();
        }
        String s = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        if (s.contains("%PDF")) return "pdf";
        if (n >= 68 && (s.startsWith("BOOKMOBI", 60) || s.startsWith("TEXtREAd", 60))) return "mobi";
        if (s.startsWith("PK")) {
            ZipFile zip = null;
            try {
                zip = new ZipFile(f);
                if (zip.getEntry("META-INF/container.xml") != null) return "epub";
            } catch (IOException | RuntimeException ignored) {
            } finally {
                if (zip != null) try { zip.close(); } catch (IOException ignored) { }
            }
        }
        if (s.contains("DRMION") || s.startsWith("CONT")) {
            throw new IOException("KFX Kindle files are not supported (use MOBI or AZW3)");
        }
        throw new IOException("not a PDF, EPUB or Kindle (MOBI/AZW3) file");
    }

    private static String displayName(Context c, Uri uri) {
        String name = null;
        try {
            Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null) {
                try {
                    if (cur.moveToFirst()) name = cur.getString(0);
                } finally {
                    cur.close();
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        return name;
    }

    private static String baseName(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) name = name.substring(0, dot);
        return name.replace('_', ' ').trim();
    }

    private static Bitmap pdfCover(File file) {
        return pdfPage(file, 0, 480);
    }

    /** Draws one page of a PDF, {@code width} pixels wide; null when it can't. */
    static Bitmap pdfPage(File file, int index, int width) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            if (renderer.getPageCount() == 0) return null;
            PdfRenderer.Page page = renderer.openPage(Math.max(0, Math.min(renderer.getPageCount() - 1, index)));
            try {
                int w = width;
                int h = Math.max(1, Math.min(w * 3, Math.round(w * (float) page.getHeight() / Math.max(1, page.getWidth()))));
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally {
                page.close();
            }
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try {
                if (renderer != null) renderer.close();
                else if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Number of pages in a PDF, or 0. */
    static int pdfPageCount(File file) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            return renderer.getPageCount();
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                if (renderer != null) renderer.close();
                else if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Makes the picture this book's cover; the original cover is kept for restoring. */
    void setCustomCover(Book b, Bitmap picture) throws IOException {
        if (picture.getWidth() > 640) {
            int h = Math.max(1, Math.round(600f * picture.getHeight() / picture.getWidth()));
            picture = Bitmap.createScaledBitmap(picture, 600, h, true);
        }
        File f = new File(coversDir, b.id + "-own-" + System.currentTimeMillis() + ".jpg");
        FileOutputStream out = new FileOutputStream(f);
        try {
            picture.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            out.close();
        }
        synchronized (this) {
            if (!b.customCover.isEmpty()) new File(b.customCover).delete();
            CoverCache.forget(b);
            b.customCover = f.getPath();
            b.coverTime = System.currentTimeMillis();
            b.coverAsset = "";
            b.coverPending = false;
        }
        save();
        Sync.request(context);
    }

    void restoreCover(Book b) {
        synchronized (this) {
            if (!b.customCover.isEmpty()) new File(b.customCover).delete();
            CoverCache.forget(b);
            b.customCover = "";
            b.coverTime = System.currentTimeMillis();
            b.coverAsset = "";
            b.coverPending = false;
        }
        save();
        Sync.request(context);
    }

    private static Bitmap decodeCover(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 480) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) return null;
            if (bmp.getWidth() > 520) {
                int h = Math.max(1, Math.round(480f * bmp.getHeight() / bmp.getWidth()));
                bmp = Bitmap.createScaledBitmap(bmp, 480, h, true);
            }
            return bmp;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
