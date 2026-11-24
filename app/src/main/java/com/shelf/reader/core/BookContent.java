package com.shelf.reader.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** A reflowable book (EPUB or MOBI/AZW3) exposed as a list of chapters plus resources. */
public interface BookContent extends Closeable {
    String title();
    String author();
    /** Raw image bytes of the cover, or null. */
    byte[] cover();

    int chapterCount();
    /** Path of chapter i inside the virtual book host, e.g. "OEBPS/ch1.xhtml". */
    String chapterPath(int i);
    /** Index of the chapter served at this path, or -1. */
    int chapterForPath(String path);
    /** Human title for the chapter, or null when unknown. */
    String chapterTitle(int i);
    /** Relative size of the chapter, used to compute reading progress. */
    long chapterWeight(int i);

    /** Opens a resource (chapter, image, stylesheet) or returns null when it does not exist. */
    Res open(String path) throws IOException;

    /** Paths of the pictures inside the book (for choosing a cover), at most {@code max}. */
    java.util.List<String> images(int max);

    final class Res {
        public final String mime;
        public final String charset;
        public final InputStream in;

        public Res(String mime, String charset, InputStream in) {
            this.mime = mime;
            this.charset = charset;
            this.in = in;
        }
    }
}
