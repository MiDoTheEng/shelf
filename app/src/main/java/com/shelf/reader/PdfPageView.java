package com.shelf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Shows one PDF page at a time: swipe to turn, pinch or double-tap to zoom, drag to move,
 * hold on a word to select text and drag (or pull the two handles) to extend it.
 */
final class PdfPageView extends View {
    interface Listener {
        void onTurn(int delta);
        void onTap();
        void onZoomSettled(float zoom);
        /** Asks for the text between a and b; word is true when a single word was held. */
        void onSelect(PdfSelect.Bound a, PdfSelect.Bound b, boolean word);
        void onSelectClear();
        void onInkChanged();
        /** A stroke or an erase is about to change the page (for undo). */
        void onInkStart();
    }

    static final int PEN = 1, MARKER = 2, ERASER = 3;

    private static final int NONE = 0, MOVE_STOP = 1, MOVE_START = 2;

    Listener listener;
    /** The swipe that goes to the next page: "left", "right" or "up". */
    String mode = "left";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final float[] handles = new float[4];
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private final float d;
    private Bitmap bitmap;
    private float zoom = 1f, panX, panY;
    private float downX, downY;
    private boolean multi, zoomChanged;

    // Drawing. A stylus always draws; a finger draws only while the drawing tools are open.
    int tool = PEN;
    int penColor = 0xFFE53935, markerColor = 0xFFFFD54A;
    boolean fingerDraws;

    /** Opens or closes drawing; the page steps in from the edges while it is open. */
    void setDrawing(boolean on) {
        fingerDraws = on;
        updateExclusion();
        invalidate();
    }
    private Ink.Page ink;
    private Ink.Stroke live;
    private boolean inkGesture, stylusGesture, scaleOnly;
    private int activeTool;
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PdfSelect.Result sel;
    private int drag = NONE;
    private boolean handleGesture, selectGesture;
    /** The end that stays put while a handle is pulled. */
    private PdfSelect.Bound fixed;
    /** The word first picked by holding; dragging on extends from it. */
    private PdfSelect.Result word;
    private PdfSelect.Bound anchor;
    private float holdX, holdY;

    PdfPageView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        selPaint.setColor(0x66D4AF6A);
        inkPaint.setStyle(Paint.Style.STROKE);
        inkPaint.setStrokeCap(Paint.Cap.ROUND);
        inkPaint.setStrokeJoin(Paint.Join.ROUND);
        handlePaint.setColor(BookView.GOLD);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector det) {
                zoomTo(zoom * det.getScaleFactor(), det.getFocusX(), det.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (zoom > 1.02f) {
                    panX -= dx;
                    panY -= dy;
                    invalidate();
                }
                return true;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener == null) return true;
                if (sel != null) listener.onSelectClear();
                else listener.onTap();
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                zoomTo(zoom > 1.02f ? 1f : 2.2f, e.getX(), e.getY());
                zoomChanged = false;
                if (listener != null) listener.onZoomSettled(zoom);
                return true;
            }

            @Override public void onLongPress(MotionEvent e) {
                if (listener == null || bitmap == null || multi) return;
                place();
                holdX = e.getX();
                holdY = e.getY();
                anchor = spot(holdX, holdY);
                drag = MOVE_STOP;
                word = null;
                selectGesture = true;
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                listener.onSelect(anchor, anchor, true);
            }
        });
    }

    float zoom() {
        return zoom;
    }

    // Turning pages: the old page stays until the new one is drawn, then fades into it.
    private Bitmap previous;
    private final RectF previousDst = new RectF();
    private long fadeStart;
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private boolean waiting;
    private Ink.Page waitingInk;
    private float lastAspect = 1.414f;
    private int paper = 0xFFFFFFFF;
    private static final long FADE_MS = 150;
    private static final long TURN_MS = 480;

    // Turning like paper: the leaving page swings around the book's spine.
    private Ink.Page previousInk;
    private int turnDir, waitingDir;
    private final PageCurl curl = new PageCurl();
    /** The page that curls over, with its ink drawn in. */
    private Bitmap curling;

    /** Shows another page. If it isn't drawn yet, the current page stays up until {@link #arrived}. */
    void turnTo(Bitmap b, Ink.Page page) {
        turnTo(b, page, 0);
    }

    /** {@code dir} is +1 for the next page, -1 for the one before, 0 to just swap. */
    void turnTo(Bitmap b, Ink.Page page, int dir) {
        if (b == null && bitmap != null) {
            waiting = true;
            waitingInk = page;
            waitingDir = dir;
            return;
        }
        waiting = false;
        waitingInk = null;
        if (bitmap != null && b != null && bitmap != b && place()) {
            previous = bitmap;
            previousDst.set(dst);
            previousInk = ink;
            turnDir = dir;
            fadeStart = android.os.SystemClock.uptimeMillis();
            // Forward, the page you leave curls away; back, the earlier page curls in over it.
            curling = dir > 0 ? withInk(bitmap, ink) : dir < 0 ? withInk(b, page) : null;
        }
        ink = page;
        live = null;
        setBitmap(b, true);
    }

    boolean isWaiting() {
        return waiting;
    }

    /** The page that was being waited for is ready. */
    void arrived(Bitmap b) {
        if (waiting) turnTo(b, waitingInk, waitingDir);
    }

    void setBitmap(Bitmap b, boolean resetView) {
        if (b != null) lastAspect = b.getHeight() / (float) Math.max(1, b.getWidth());
        bitmap = b;
        if (resetView) {
            zoom = 1f;
            panX = 0;
            panY = 0;
        }
        invalidate();
    }

    void setNight(boolean on) {
        paper = on ? 0xFF16161A : 0xFFFFFFFF;
        fadePaint.setColorFilter(on ? PdfActivity.INVERT : null);
        paint.setColorFilter(on ? PdfActivity.INVERT : null);
        invalidate();
    }

    private boolean stylusSeen;
    private float lastInkY = -1f;

    /**
     * Asks the system not to treat swipes from the left and right screen edges as "back"
     * while drawing. Android allows this for a strip up to 200dp tall per edge, so the
     * strip follows the height where the last stroke was made.
     */
    void updateExclusion() {
        if (Build.VERSION.SDK_INT < 29) return;
        java.util.ArrayList<Rect> rects = new java.util.ArrayList<>();
        int w = getWidth(), h = getHeight();
        if ((fingerDraws || stylusSeen) && w > 0 && h > 0) {
            int band = Math.round(200 * d), edge = Math.round(56 * d);
            float centre = lastInkY < 0 ? h / 2f : lastInkY;
            int top = Math.max(0, Math.min(h - band, Math.round(centre - band / 2f)));
            int bottom = Math.min(h, top + band);
            rects.add(new Rect(0, top, edge, bottom));
            rects.add(new Rect(w - edge, top, w, bottom));
        }
        setSystemGestureExclusionRects(rects);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateExclusion();
    }

    void setInk(Ink.Page page) {
        ink = page;
        live = null;
        invalidate();
    }

    /** Height of the page divided by its width, or 0 before the page is drawn. */
    float aspect() {
        return bitmap == null ? 0f : bitmap.getHeight() / (float) Math.max(1, bitmap.getWidth());
    }

    private float inkX(float x) {
        return Math.max(0f, Math.min(1f, (x - dst.left) / Math.max(1f, dst.width())));
    }

    private float inkY(float y) {
        float max = dst.height() / Math.max(1f, dst.width());
        return Math.max(0f, Math.min(max, (y - dst.top) / Math.max(1f, dst.width())));
    }

    private void inkTo(float x, float y) {
        float u = inkX(x), v = inkY(y);
        if (activeTool == ERASER) {
            if (erase(u, v)) invalidate();
        } else if (live != null) {
            live.add(u, v);
            invalidate();
        }
    }

    private static float segmentGap(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
        float t = len == 0 ? 0 : Math.max(0f, Math.min(1f, ((px - ax) * dx + (py - ay) * dy) / len));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Removes whole strokes and text highlights touched at this spot. */
    private boolean erase(float u, float v) {
        boolean changed = false;
        float reach = 0.018f;
        for (int i = ink.strokes.size() - 1; i >= 0; i--) {
            Ink.Stroke s = ink.strokes.get(i);
            boolean hit = false;
            for (int k = 0; k + 1 < s.n && !hit; k += 2) {
                int j = Math.min(k + 2, s.n - 2);
                hit = segmentGap(u, v, s.pts[k], s.pts[k + 1], s.pts[j], s.pts[j + 1]) < reach + s.width / 2f;
            }
            if (hit) {
                ink.strokes.remove(i);
                changed = true;
            }
        }
        for (int i = ink.marks.size() - 1; i >= 0; i--) {
            float[] r = ink.marks.get(i).rects;
            for (int k = 0; k + 3 < r.length; k += 4) {
                if (u >= r[k] && u <= r[k + 2] && v >= r[k + 1] && v <= r[k + 3]) {
                    ink.marks.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) inkChanged = true;
        return changed;
    }

    private boolean inkChanged;

    /** Shows (or with null, removes) the highlighted selection. */
    void setSelection(PdfSelect.Result r, boolean wordResult) {
        sel = r;
        // The first answer to a hold is the word under the finger; dragging on extends from it.
        if (r != null && wordResult && drag == MOVE_STOP && !handleGesture) word = r;
        invalidate();
    }

    private void zoomTo(float target, float fx, float fy) {
        float old = zoom;
        zoom = Math.max(1f, Math.min(4f, target));
        float cx = fx - getWidth() / 2f, cy = fy - getHeight() / 2f;
        panX = (panX - cx) * (zoom / old) + cx;
        panY = (panY - cy) * (zoom / old) + cy;
        zoomChanged = true;
        invalidate();
    }

    /** Works out where the page sits on screen for the current zoom and position. */
    private boolean place() {
        float w = getWidth(), h = getHeight();
        if (bitmap == null || w == 0 || h == 0) return false;
        float room = w;
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float fit = Math.min(room / bw, h / bh);
        float dw = bw * fit * zoom, dh = bh * fit * zoom;
        float maxX = Math.max(0, (dw - room) / 2f), maxY = Math.max(0, (dh - h) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        float left = (w - dw) / 2f + panX, top = (h - dh) / 2f + panY;
        dst.set(left, top, left + dw, top + dh);
        return true;
    }

    private PdfSelect.Bound spot(float x, float y) {
        float u = (x - dst.left) / Math.max(1f, dst.width()), v = (y - dst.top) / Math.max(1f, dst.height());
        return PdfSelect.Bound.at(Math.max(0f, Math.min(1f, u)), Math.max(0f, Math.min(1f, v)));
    }

    /** Screen positions of the start handle (0,1) and the stop handle (2,3); false when nothing is selected. */
    /** Where the selected text is on screen (left, top, right, bottom), or null. */
    float[] selectionBounds() {
        if (sel == null || sel.rects.isEmpty() || !place()) return null;
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (RectF q : sel.rects) {
            l = Math.min(l, q.left);
            t = Math.min(t, q.top);
            r = Math.max(r, q.right);
            b = Math.max(b, q.bottom);
        }
        return new float[]{dst.left + l * dst.width(), dst.top + t * dst.height(),
                dst.left + r * dst.width(), dst.top + b * dst.height()};
    }

    private boolean handlePositions() {
        if (sel == null || sel.rects.isEmpty() || !place()) return false;
        RectF first = sel.rects.get(0), last = sel.rects.get(sel.rects.size() - 1);
        handles[0] = dst.left + (sel.rtl ? first.right : first.left) * dst.width();
        handles[1] = dst.top + first.bottom * dst.height() + 10 * d;
        handles[2] = dst.left + (sel.rtl ? last.left : last.right) * dst.width();
        handles[3] = dst.top + last.bottom * dst.height() + 10 * d;
        return true;
    }

    private void extendTo(float x, float y) {
        if (listener == null || !place()) return;
        PdfSelect.Bound p = spot(x, y);
        if (handleGesture) {
            if (fixed == null) return;
            if (drag == MOVE_STOP) listener.onSelect(fixed, p, false);
            else listener.onSelect(p, fixed, false);
            return;
        }
        if (word == null || word.rects.isEmpty()) return;
        // Dragging on from a held word: forwards keeps the word's start, backwards keeps its end.
        RectF first = word.rects.get(0);
        boolean before = p.v < first.top
                || (p.v <= first.bottom && (word.rtl ? p.u > first.right : p.u < first.left));
        if (before) listener.onSelect(p, PdfSelect.Bound.at(word.eu, word.ev), false);
        else listener.onSelect(PdfSelect.Bound.at(word.su, word.sv), p, false);
    }

    /** A stylus hovering just above the screen moves the protected strip to where it is about to draw. */
    @Override public boolean onHoverEvent(MotionEvent e) {
        int type = e.getToolType(0);
        if ((type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER)
                && Math.abs(e.getY() - lastInkY) > 40 * d) {
            stylusSeen = true;
            lastInkY = e.getY();
            updateExclusion();
        }
        return super.onHoverEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            int type = e.getToolType(0);
            stylusGesture = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER;
            scaleOnly = false;
            if (stylusGesture && !stylusSeen) {
                stylusSeen = true;
                updateExclusion();
            }
            inkGesture = ink != null && (stylusGesture || fingerDraws) && place();
            if (inkGesture) {
                activeTool = type == MotionEvent.TOOL_TYPE_ERASER ? ERASER : tool;
                if (listener != null) listener.onInkStart();
                inkChanged = false;
                live = null;
                if (activeTool != ERASER) {
                    live = new Ink.Stroke();
                    live.marker = activeTool == MARKER;
                    live.color = live.marker ? markerColor : penColor;
                    live.width = live.marker ? Ink.MARKER_WIDTH : Ink.PEN_WIDTH;
                    ink.strokes.add(live);
                    inkChanged = true;
                }
                inkTo(e.getX(), e.getY());
                return true;
            }
        }
        if (inkGesture) {
            if (action == MotionEvent.ACTION_POINTER_DOWN && !stylusGesture) {
                // A second finger means zooming, not drawing: drop the stroke and hand over.
                if (live != null) ink.strokes.remove(live);
                live = null;
                inkGesture = false;
                scaleOnly = true;
                multi = true;
                invalidate();
            } else {
                if (action == MotionEvent.ACTION_MOVE) {
                    for (int i = 0; i < e.getHistorySize(); i++) inkTo(e.getHistoricalX(i), e.getHistoricalY(i));
                    inkTo(e.getX(), e.getY());
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    live = null;
                    inkGesture = false;
                    lastInkY = e.getY();
                    updateExclusion();
                    if (inkChanged && listener != null) listener.onInkChanged();
                }
                return true;
            }
        }
        if (scaleOnly) {
            scaler.onTouchEvent(e);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                scaleOnly = false;
                if (zoomChanged) {
                    zoomChanged = false;
                    if (listener != null) listener.onZoomSettled(zoom);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            multi = false;
            drag = NONE;
            handleGesture = false;
            selectGesture = false;
            if (handlePositions()) {
                float reach = 36 * d;
                float toStop = (float) Math.hypot(downX - handles[2], downY - handles[3]);
                float toStart = (float) Math.hypot(downX - handles[0], downY - handles[1]);
                if (Math.min(toStop, toStart) < reach) {
                    boolean stop = toStop <= toStart;
                    drag = stop ? MOVE_STOP : MOVE_START;
                    fixed = stop ? PdfSelect.Bound.at(sel.su, sel.sv) : PdfSelect.Bound.at(sel.eu, sel.ev);
                    handleGesture = true;
                    return true;
                }
            }
        }
        if (handleGesture) {
            // Aim a little above the finger, at the line the handle hangs from.
            if (action == MotionEvent.ACTION_MOVE) {
                extendTo(e.getX(), e.getY() - 20 * d);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                drag = NONE;
                handleGesture = false;
            }
            return true;
        }

        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multi = true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (drag == MOVE_STOP && Math.hypot(e.getX() - holdX, e.getY() - holdY) > 10 * d) {
                extendTo(e.getX(), e.getY());
            }
        } else if (action == MotionEvent.ACTION_UP) {
            if (selectGesture) {
                drag = NONE;
            } else if (zoomChanged) {
                zoomChanged = false;
                if (listener != null) listener.onZoomSettled(zoom);
            } else if (!multi && zoom <= 1.02f && listener != null) {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                float min = 52 * d;
                int delta = 0;
                if (mode.equals("up")) {
                    if (Math.abs(dy) > min && Math.abs(dy) > 1.5f * Math.abs(dx)) delta = dy < 0 ? 1 : -1;
                } else if (Math.abs(dx) > min && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                    delta = (dx < 0) == mode.equals("left") ? 1 : -1;
                }
                if (delta != 0) listener.onTurn(delta);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            zoomChanged = false;
            drag = NONE;
        }
        return true;
    }

    private void drawPage(Canvas canvas, Bitmap b, RectF where, Ink.Page page, Paint p) {
        p.setAlpha(255);
        canvas.drawBitmap(b, null, where, p);
        drawInk(canvas, page, where);
    }

    /** A copy of the page with its ink drawn on, so the ink bends with the paper. */
    private Bitmap withInk(Bitmap b, Ink.Page page) {
        if (page == null || page.isEmpty()) return b;
        try {
            Bitmap copy = b.copy(Bitmap.Config.ARGB_8888, true);
            drawInk(new Canvas(copy), page, new RectF(0, 0, copy.getWidth(), copy.getHeight()));
            return copy;
        } catch (Throwable t) {
            return b;
        }
    }

    private void drawInk(Canvas canvas, Ink.Page page, RectF where) {
        if (page == null || page.isEmpty()) return;
        // Ink is stored in page widths, so one scale puts it on the page at any zoom.
        canvas.save();
        canvas.translate(where.left, where.top);
        canvas.scale(where.width(), where.width());
        for (Ink.Mark m : page.marks) {
            markPaint.setColor((m.color & 0x00FFFFFF) | 0x66000000);
            for (int k = 0; k + 3 < m.rects.length; k += 4) {
                canvas.drawRect(m.rects[k], m.rects[k + 1], m.rects[k + 2], m.rects[k + 3], markPaint);
            }
        }
        for (Ink.Stroke st : page.strokes) {
            inkPaint.setColor(st.marker ? (st.color & 0x00FFFFFF) | 0x66000000 : st.color);
            inkPaint.setStrokeWidth(st.width);
            canvas.drawPath(st.path(), inkPaint);
        }
        canvas.restore();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!place()) {
            // Not drawn yet (only when a book first opens): a blank sheet instead of black.
            float w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float pw = Math.min(w, h / lastAspect), ph = pw * lastAspect;
            markPaint.setColor(paper);
            canvas.drawRect((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f, markPaint);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float t = previous == null ? 1f
                : (now - fadeStart) / (float) (turnDir == 0 ? FADE_MS : TURN_MS);
        if (t >= 1f) {
            previous = null;
            previousInk = null;
            curling = null;
            drawPage(canvas, bitmap, dst, ink, paint);
        } else if (turnDir == 0) {
            drawPage(canvas, bitmap, dst, ink, paint);
            fadePaint.setAlpha(Math.round(255 * (1f - t)));
            canvas.drawBitmap(previous, null, previousDst, fadePaint);
            postInvalidateOnAnimation();
        } else {
            float e = t * t * (3f - 2f * t);
            boolean day = paint.getColorFilter() == null;
            paint.setAlpha(255);
            fadePaint.setAlpha(255);
            if (turnDir > 0) {
                // The page you leave curls away; the next one is underneath.
                drawPage(canvas, bitmap, dst, ink, paint);
                curl.draw(canvas, curling, previousDst, fadePaint, e, mode, paper, day);
            } else {
                // Going back, the earlier page uncurls over the one you were on.
                drawPage(canvas, previous, previousDst, previousInk, fadePaint);
                curl.draw(canvas, curling, dst, paint, 1f - e, mode, paper, day);
            }
            postInvalidateOnAnimation();
        }
        if (sel == null) return;
        for (RectF r : sel.rects) {
            canvas.drawRect(dst.left + r.left * dst.width(), dst.top + r.top * dst.height(),
                    dst.left + r.right * dst.width(), dst.top + r.bottom * dst.height(), selPaint);
        }
        if (handlePositions()) {
            canvas.drawCircle(handles[0], handles[1], 10 * d, handlePaint);
            canvas.drawCircle(handles[2], handles[3], 10 * d, handlePaint);
        }
    }
}
