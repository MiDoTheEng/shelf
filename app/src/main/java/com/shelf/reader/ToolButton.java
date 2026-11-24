package com.shelf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * A round drawing-tool button with a drawn icon: pen, highlighter or eraser.
 * The big one is red and shows the tool in use; the small ones are dark and show the others.
 */
final class ToolButton extends View {
    final boolean big;
    int tool = PdfPageView.PEN;
    /** Colour of the pen or highlighter, shown as a small dot. */
    int color = 0xFFE53935;
    /** Drawing is on: the big circle gets a bright ring. */
    boolean active;
    /** While the tools menu is open the big circle shows ✕ (tap to close), as before. */
    boolean close;

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    ToolButton(Context c, boolean big) {
        super(c);
        this.big = big;
        d = c.getResources().getDisplayMetrics().density;
    }

    void set(int tool, int color, boolean active) {
        this.tool = tool;
        this.color = color;
        this.active = active;
        setContentDescription(tool == PdfPageView.MARKER ? "Highlighter" : tool == PdfPageView.ERASER ? "Eraser" : "Pen");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
        float rad = Math.min(w, h) / 2f - (big ? 4 : 2) * d;
        if (rad <= 0) return;

        // The circle: red for the tool in use, white for the ones waiting underneath.
        paint.setStyle(Paint.Style.FILL);
        if (big && active) {
            paint.setColor(0x66FFFFFF);
            canvas.drawCircle(cx, cy, rad + 3.5f * d, paint);
        }
        paint.setColor(big ? 0xFFE53935 : 0xFFFFFFFF);
        canvas.drawCircle(cx, cy, rad, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((big ? (active ? 3f : 2f) : 2.5f) * d);
        paint.setColor(big ? (active ? 0xFFFFFFFF : 0x88FFFFFF) : 0xFFE53935);
        canvas.drawCircle(cx, cy, rad, paint);

        // Outlines: white on the red circle, near-black on the white ones.
        int edge = big ? 0xFFFFFFFF : 0xFF26262B;
        if (big && close) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(3.2f * d);
            paint.setColor(0xFFFFFFFF);
            float k = rad * 0.38f;
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, paint);
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, paint);
            return;
        }

        // The icon, in full colour, drawn on a slant like a tool held in the hand.
        float s = rad * 1.45f;
        float line = (big ? 1.6f : 1.4f) * d;
        canvas.save();
        canvas.translate(cx, cy);
        if (tool == PdfPageView.MARKER) {
            // The highlight it makes: a soft stroke under the marker.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((color & 0x00FFFFFF) | 0xAA000000);
            r.set(-0.40f * s, 0.20f * s, 0.10f * s, 0.32f * s);
            canvas.drawRoundRect(r, 0.05f * s, 0.05f * s, paint);
        }
        canvas.rotate(-45);
        if (tool == PdfPageView.PEN) {
            // Pencil: body, wooden cone, and a point in the pen's colour.
            shape(canvas, rectPath(-0.40f * s, -0.085f * s, 0.14f * s, 0.085f * s), big ? 0xFFFFFFFF : 0xFF3A3A44, edge, line);
            shape(canvas, rectPath(-0.46f * s, -0.085f * s, -0.38f * s, 0.085f * s), 0xFFF48FB1, edge, line);
            path.reset();
            path.moveTo(0.14f * s, -0.085f * s);
            path.lineTo(0.38f * s, 0);
            path.lineTo(0.14f * s, 0.085f * s);
            path.close();
            shape(canvas, path, 0xFFF3D9A4, edge, line);
            path.reset();
            path.moveTo(0.29f * s, -0.033f * s);
            path.lineTo(0.38f * s, 0);
            path.lineTo(0.29f * s, 0.033f * s);
            path.close();
            shape(canvas, path, color, color, 0);
        } else if (tool == PdfPageView.MARKER) {
            // Highlighter: a chunky body in its colour, a dark grip and a chisel tip.
            shape(canvas, rectPath(-0.38f * s, -0.12f * s, 0.08f * s, 0.12f * s), color, edge, line);
            shape(canvas, rectPath(-0.12f * s, -0.12f * s, 0.08f * s, 0.12f * s), 0xFF3A3A44, edge, line);
            path.reset();
            path.moveTo(0.08f * s, -0.12f * s);
            path.lineTo(0.30f * s, -0.07f * s);
            path.lineTo(0.30f * s, 0.05f * s);
            path.lineTo(0.08f * s, 0.12f * s);
            path.close();
            shape(canvas, path, color, edge, line);
        } else {
            // Eraser: the classic pink-and-blue block.
            shape(canvas, rectPath(-0.34f * s, -0.15f * s, 0.34f * s, 0.15f * s), 0xFFF48FB1, edge, line);
            shape(canvas, rectPath(-0.34f * s, -0.15f * s, 0.0f, 0.15f * s), 0xFF5C8DF6, edge, line);
        }
        canvas.restore();
    }

    private Path rectPath(float l, float t, float rr, float b) {
        Path p = new Path();
        p.moveTo(l, t);
        p.lineTo(rr, t);
        p.lineTo(rr, b);
        p.lineTo(l, b);
        p.close();
        return p;
    }

    private void shape(Canvas canvas, Path p, int fillColor, int edgeColor, float width) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        canvas.drawPath(p, paint);
        if (width <= 0) return;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(edgeColor);
        canvas.drawPath(p, paint);
    }
}
