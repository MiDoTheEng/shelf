package com.shelf.reader;

import android.content.Context;
import android.graphics.RectF;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Reads the words on PDF pages with the app's own PDF library (PdfBox), so text can be
 * selected on any Android version, including tablets older than Android 15.
 * Positions are fractions of the page (0..1 across and down). Call from a background thread.
 */
final class PdfText {
    /** One character on the page; spaces and line breaks have no box. */
    static final class Glyph {
        final String s;
        final float l, t, r, b;
        final boolean gap;

        Glyph(String s, float l, float t, float r, float b) {
            this.s = s;
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
            this.gap = s.trim().isEmpty();
        }

        static Glyph gap(String s) {
            return new Glyph(s, 0, 0, 0, 0);
        }
    }

    /** The characters of one page in reading order, plus the page size in points. */
    static final class Page {
        final ArrayList<Glyph> glyphs = new ArrayList<>();
        float width = 1, height = 1;

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Glyph g : glyphs) sb.append(g.s);
            return sb.toString().replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").trim();
        }

        /** How far (in points) the last spot looked up was from the character found. */
        private float lastDistance;

        /** The character under the spot, or the closest one (same line preferred). */
        int nearest(float u, float v) {
            int best = -1;
            float bestScore = Float.MAX_VALUE;
            for (int i = 0; i < glyphs.size(); i++) {
                Glyph g = glyphs.get(i);
                if (g.gap) continue;
                float dx = u < g.l ? g.l - u : u > g.r ? u - g.r : 0;
                float dy = v < g.t ? g.t - v : v > g.b ? v - g.b : 0;
                // In points, with being on another line weighing much more than being further along it.
                float score = dx * width + 4f * dy * height;
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                    lastDistance = (float) Math.hypot(dx * width, dy * height);
                }
            }
            return best;
        }

        private boolean letter(int i) {
            return i >= 0 && i < glyphs.size() && !glyphs.get(i).gap;
        }

        /** Selects the word at a, or everything from a to b. */
        PdfSelect.Result select(PdfSelect.Bound a, PdfSelect.Bound b) {
            PdfSelect.Result r = new PdfSelect.Result();
            boolean word = a.u == b.u && a.v == b.v;
            int i = nearest(a.u, a.v);
            // Holding blank space away from any text selects nothing.
            if (word && lastDistance > 14f) return r;
            int j = word ? i : nearest(b.u, b.v);
            if (i < 0 || j < 0) return r;
            if (i > j) {
                int k = i;
                i = j;
                j = k;
            }
            if (word) {
                while (letter(i - 1)) i--;
                while (letter(j + 1)) j++;
            }
            StringBuilder text = new StringBuilder();
            RectF line = null;
            for (int k = i; k <= j; k++) {
                Glyph g = glyphs.get(k);
                text.append(g.s);
                if (g.gap) continue;
                float mid = (g.t + g.b) / 2f;
                if (line != null && mid > line.top && mid < line.bottom && g.l >= line.left - 0.02f) {
                    line.union(g.l, g.t, g.r, g.b);
                } else {
                    line = new RectF(g.l, g.t, g.r, g.b);
                    r.rects.add(line);
                }
            }
            r.text = text.toString().replaceAll("\\s+", " ").trim();
            PdfSelect.finish(r, a, b, word, width, height);
            return r;
        }
    }

    private final File file;
    private final Context context;
    private PDDocument doc;
    private boolean broken;
    private final HashMap<Integer, Page> pages = new HashMap<>();

    PdfText(Context c, File file) {
        this.context = c.getApplicationContext();
        this.file = file;
    }

    /** The text of a page, read once and kept; null when the file can't be read. */
    synchronized Page page(int index) {
        Page cached = pages.get(index);
        if (cached != null) return cached;
        if (broken) return null;
        try {
            if (doc == null) {
                PDFBoxResourceLoader.init(context);
                doc = PDDocument.load(file);
            }
            final Page page = new Page();
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        float pw = Math.max(1f, p.getPageWidth()), ph = Math.max(1f, p.getPageHeight());
                        page.width = pw;
                        page.height = ph;
                        float h = p.getHeightDir();
                        if (h <= 0) h = p.getFontSizeInPt() * 0.7f;
                        float x = p.getXDirAdj(), y = p.getYDirAdj(), w = p.getWidthDirAdj();
                        String s = p.getUnicode();
                        if (s == null || s.isEmpty()) continue;
                        page.glyphs.add(new Glyph(s, x / pw, (y - h * 1.25f) / ph, (x + w) / pw, (y + h * 0.3f) / ph));
                    }
                }

                @Override protected void writeWordSeparator() {
                    page.glyphs.add(Glyph.gap(" "));
                }

                @Override protected void writeLineSeparator() {
                    page.glyphs.add(Glyph.gap("\n"));
                }
            };
            stripper.setSortByPosition(true);
            stripper.setStartPage(index + 1);
            stripper.setEndPage(index + 1);
            stripper.getText(doc);
            pages.put(index, page);
            return page;
        } catch (Throwable t) {
            // Damaged, encrypted or unusual files: no text, the reader carries on.
            broken = doc == null;
            return null;
        }
    }

    PdfSelect.Result select(int index, PdfSelect.Bound a, PdfSelect.Bound b) {
        Page p = page(index);
        return p == null ? new PdfSelect.Result() : p.select(a, b);
    }

    String pageText(int index) {
        Page p = page(index);
        return p == null ? "" : p.text();
    }

    synchronized void close() {
        try {
            if (doc != null) doc.close();
        } catch (IOException ignored) {
        }
        doc = null;
        pages.clear();
    }
}
