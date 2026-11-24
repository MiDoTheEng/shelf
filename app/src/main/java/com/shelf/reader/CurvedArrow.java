package com.shelf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A hand-drawn style curved arrow: a line sweeping up into an arrowhead.
 * Redo curves up to the right; undo is its mirror image and curves up to the left.
 */
final class CurvedArrow extends View {
    private final boolean towardsLeft;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path head = new Path();
    private final float d;

    CurvedArrow(Context c, boolean towardsLeft) {
        super(c);
        this.towardsLeft = towardsLeft;
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    private float x(float fraction) {
        float w = getWidth();
        return towardsLeft ? w - fraction * w : fraction * w;
    }

    @Override protected void onDraw(Canvas canvas) {
        float h = getHeight();
        if (getWidth() == 0 || h == 0) return;
        // The line: from the bottom, sweeping up and over towards the head.
        float sx = x(0.30f), sy = 0.74f * h;
        float cx = x(0.26f), cy = 0.42f * h;
        float ex = x(0.56f), ey = 0.32f * h;
        curve.reset();
        curve.moveTo(sx, sy);
        curve.quadTo(cx, cy, ex, ey);

        // The head points the way the line is going at its end.
        float dx = ex - cx, dy = ey - cy;
        float len = (float) Math.hypot(dx, dy);
        dx /= len;
        dy /= len;
        float size = 0.30f * h, half = 0.24f * h;
        float baseX = ex - dx * size * 0.15f, baseY = ey - dy * size * 0.15f;
        float tipX = ex + dx * size, tipY = ey + dy * size;
        head.reset();
        head.moveTo(tipX, tipY);
        head.lineTo(baseX - dy * half, baseY + dx * half);
        head.lineTo(baseX + dy * half, baseY - dx * half);
        head.close();

        // A thin white edge under the red, so the arrow shows on white pages and dark ones alike.
        line.setColor(0xF2FFFFFF);
        line.setStrokeWidth(13f * d);
        canvas.drawPath(curve, line);
        fill.setColor(0xF2FFFFFF);
        line.setStrokeWidth(3f * d);
        canvas.drawPath(head, line);
        canvas.drawPath(head, fill);

        line.setColor(0xFFE53935);
        line.setStrokeWidth(9.5f * d);
        canvas.drawPath(curve, line);
        fill.setColor(0xFFE53935);
        canvas.drawPath(head, fill);
    }
}
