package com.shelf.reader;

import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Selecting text on a PDF page. Android only offers this from version 15 on, so the
 * system calls are looked up by name at run time and older phones report "unsupported".
 * Both ends of a selection are always given as spots on the page.
 */
final class PdfSelect {
    /** A spot on the page: 0..1 across and 0..1 down. */
    static final class Bound {
        final float u, v;

        private Bound(float u, float v) {
            this.u = u;
            this.v = v;
        }

        static Bound at(float u, float v) {
            return new Bound(u, v);
        }
    }

    static final class Result {
        String text = "";
        /** One rectangle per selected line, as fractions of the page width and height. */
        final ArrayList<RectF> rects = new ArrayList<>();
        /** Spots that give this same start and end when asked for again. */
        float su, sv, eu, ev;
        boolean rtl;
        boolean unsupported;
        /** Why the system refused, when it did. */
        String error;
    }

    private PdfSelect() {}

    private static Object boundary(Class<?> type, Bound b, float pw, float ph) throws Exception {
        return type.getConstructor(Point.class).newInstance(new Point(Math.round(b.u * pw), Math.round(b.v * ph)));
    }

    private static Object invoke(Method select, Object page, Object start, Object stop, Result r)
            throws IllegalAccessException {
        try {
            return select.invoke(page, start, stop);
        } catch (InvocationTargetException e) {
            r.error = String.valueOf(e.getCause());
            return null;
        }
    }

    private static float gap(Bound p, float u, float v, float pw, float ph) {
        return (float) Math.hypot((p.u - u) * pw, (p.v - v) * ph);
    }

    /** Selects from a to b; when both are the same spot, the word there. Must run off the main thread. */
    static Result select(PdfRenderer.Page page, Bound a, Bound b) {
        Result r = new Result();
        try {
            Class<?> type = Class.forName("android.graphics.pdf.models.selection.SelectionBoundary");
            Method select = page.getClass().getMethod("selectContent", type, type);
            float pw = Math.max(1, page.getWidth()), ph = Math.max(1, page.getHeight());
            boolean word = a.u == b.u && a.v == b.v;
            Object sel = invoke(select, page, boundary(type, a, pw, ph), boundary(type, b, pw, ph), r);
            if (sel == null && !word) {
                // Dragged back past the other end: ask again the other way round.
                sel = invoke(select, page, boundary(type, b, pw, ph), boundary(type, a, pw, ph), r);
            }
            if (sel == null) return r;

            StringBuilder text = new StringBuilder();
            Object contents = sel.getClass().getMethod("getSelectedTextContents").invoke(sel);
            if (contents instanceof List) {
                for (Object c : (List<?>) contents) {
                    Object t = c.getClass().getMethod("getText").invoke(c);
                    if (t != null) text.append(t).append(' ');
                    Object bounds = c.getClass().getMethod("getBounds").invoke(c);
                    if (!(bounds instanceof List)) continue;
                    for (Object o : (List<?>) bounds) {
                        if (!(o instanceof RectF)) continue;
                        RectF q = (RectF) o;
                        r.rects.add(new RectF(Math.min(q.left, q.right) / pw, Math.min(q.top, q.bottom) / ph,
                                Math.max(q.left, q.right) / pw, Math.max(q.top, q.bottom) / ph));
                    }
                }
            }
            r.text = text.toString().trim();
            r.error = null;
            try {
                Object start = sel.getClass().getMethod("getStart").invoke(sel);
                r.rtl = (Boolean) start.getClass().getMethod("getIsRtl").invoke(start);
            } catch (Throwable ignored) {
            }
            if (r.rects.isEmpty()) return r;

            finish(r, a, b, word, pw, ph);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            r.unsupported = true;
        } catch (Throwable t) {
            if (r.error == null) r.error = String.valueOf(t);
        }
        return r;
    }

    /** Works out the two end spots of a finished selection, so its handles can be pulled. */
    static void finish(Result r, Bound a, Bound b, boolean word, float pw, float ph) {
        if (r.rects.isEmpty()) return;
        RectF first = r.rects.get(0), last = r.rects.get(r.rects.size() - 1);
        float startEdge = r.rtl ? first.right : first.left, endEdge = r.rtl ? last.left : last.right;
        if (word) {
            // Just inside the first letter, and just past the last one.
            float in = Math.min(first.width() * 0.25f, first.height() * ph * 0.15f / pw);
            float out = last.height() * ph * 0.08f / pw;
            r.su = r.rtl ? startEdge - in : startEdge + in;
            r.sv = first.centerY();
            r.eu = r.rtl ? endEdge - out : endEdge + out;
            r.ev = last.centerY();
        } else {
            // The two spots that were asked for; work out which one is the start.
            float straight = gap(a, startEdge, first.centerY(), pw, ph) + gap(b, endEdge, last.centerY(), pw, ph);
            float crossed = gap(b, startEdge, first.centerY(), pw, ph) + gap(a, endEdge, last.centerY(), pw, ph);
            Bound s = straight <= crossed ? a : b, e = straight <= crossed ? b : a;
            r.su = s.u;
            r.sv = s.v;
            r.eu = e.u;
            r.ev = e.v;
        }
    }
}
