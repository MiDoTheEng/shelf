package com.shelf.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads cover images off the main thread and keeps the recent ones in memory. */
final class CoverCache {
    interface Callback {
        void onCover(Bitmap bitmap);
    }

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CoverCache() {}

    static Bitmap cached(Book b) {
        return CACHE.get(b.coverPath());
    }

    static void forget(Book b) {
        CACHE.remove(b.cover);
        CACHE.remove(b.customCover);
    }

    static void load(final Book b, final Callback cb) {
        final String path = b.coverPath();
        POOL.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    bmp = BitmapFactory.decodeFile(path);
                } catch (RuntimeException | OutOfMemoryError ignored) {
                }
                final Bitmap result = bmp;
                if (result != null) CACHE.put(path, result);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        cb.onCover(result);
                    }
                });
            }
        });
    }
}
