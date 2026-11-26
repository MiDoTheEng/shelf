package com.shelf.reader;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws a page curling over like paper. The page bends around a roll that sweeps from the
 * free edge to the spine, its bottom corner leading. The back of the page shows as plain
 * paper, shaded where it bends, and the roll casts a soft shadow on the page underneath.
 *
 * <p>The spine follows the reading direction: "left" (pages turn to the left, spine on the
 * left), "right" (Arabic: spine on the right) or "up" (spine at the top).
 */
final class PageCurl {
    private static final int MW = 22, MH = 30;
    private static final float PI = (float) Math.PI;

    private final float[] verts = new float[(MW + 1) * (MH + 1) * 2];
    private final int[] colors = new int[(MW + 1) * (MH + 1)];
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] out = new float[3];

    // The current frame: the page's rectangle, which way it turns, and where the roll is.
    private int mode;
    private float left, top, right, bottom;
    private float cos, sin, roll, radius, span;
    /** True when the page is held near the top: the top corner leads instead of the bottom. */
    boolean topLeads;

    /** Distance across the roll from the spine side, for a page point. */
    private float across(float a, float b) {
        return a * cos + (topLeads ? span - b : b) * sin;
    }

    /**
     * Draws {@code page} (normally shown in {@code r}) turned {@code t} of the way over:
     * 0 is flat, 1 is gone. Whatever is already on the canvas shows where the page has lifted.
     */
    void draw(Canvas canvas, Bitmap page, RectF r, Paint paint, float t, String direction, int paper,
              boolean shade) {
        setup(r, direction, t);
        float a = mode == 2 ? r.height() : r.width();
        float b = mode == 2 ? r.width() : r.height();

        // Which way "away from the spine" points on screen.
        float nb = topLeads ? -sin : sin;
        float nx, ny;
        if (mode == 0) {
            nx = cos;
            ny = nb;
        } else if (mode == 1) {
            nx = -cos;
            ny = nb;
        } else {
            nx = nb;
            ny = cos;
        }
        float crest = roll + radius;
        toScreen(crest / cos, topLeads ? b : 0);
        float cx = out[0], cy = out[1];

        // 1. The roll's shadow on the page underneath.
        float reach = 1.2f * radius;
        shadowPaint.setShader(new LinearGradient(cx, cy, cx + nx * reach, cy + ny * reach,
                new int[]{0x6E000000, 0x22000000, 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        path.reset();
        band(crest, crest + reach, b);
        canvas.save();
        canvas.clipRect(r);
        canvas.drawPath(path, shadowPaint);
        canvas.restore();

        // 2. The page itself, bent around the roll.
        int k = 0;
        for (int j = 0; j <= MH; j++) {
            for (int i = 0; i <= MW; i++) {
                float x = left + r.width() * i / MW, y = top + r.height() * j / MH;
                float la, lb;
                if (mode == 0) {
                    la = x - left;
                    lb = y - top;
                } else if (mode == 1) {
                    la = right - x;
                    lb = y - top;
                } else {
                    la = y - top;
                    lb = x - left;
                }
                bend(la, lb);
                toScreen(out[0], out[1], out[2]);
                verts[2 * k] = out[0];
                verts[2 * k + 1] = out[1];
                int g = Math.round(255 * out[2]);
                colors[k] = 0xFF000000 | (g << 16) | (g << 8) | g;
                k++;
            }
        }
        canvas.drawBitmapMesh(page, MW, MH, verts, 0, shade ? colors : null, 0, paint);

        // 3. The back of the page: the part rolled past the top of the curve, as paper.
        path.reset();
        float[] poly = backOutline(a, b, roll + PI * radius / 2f);
        if (poly.length >= 6) {
            int n = poly.length / 2;
            boolean first = true;
            for (int e = 0; e < n; e++) {
                float pa = poly[2 * e], pb = poly[2 * e + 1];
                float qa = poly[2 * ((e + 1) % n)], qb = poly[2 * ((e + 1) % n) + 1];
                for (int step = 0; step < 16; step++) {
                    float f = step / 16f;
                    bend(pa + (qa - pa) * f, pb + (qb - pb) * f);
                    toScreen(out[0], out[1]);
                    if (first) {
                        path.moveTo(out[0], out[1]);
                        first = false;
                    } else {
                        path.lineTo(out[0], out[1]);
                    }
                }
            }
            path.close();
            float ex = cx - nx * 1.5f * radius, ey = cy - ny * 1.5f * radius;
            backPaint.setShader(new LinearGradient(cx, cy, ex, ey,
                    new int[]{tone(paper, 0.80f), tone(paper, 0.95f), paper}, new float[]{0f, 0.66f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawPath(path, backPaint);
        }
    }

    private void setup(RectF r, String direction, float t) {
        mode = "up".equals(direction) ? 2 : "right".equals(direction) ? 1 : 0;
        left = r.left;
        top = r.top;
        right = r.right;
        bottom = r.bottom;
        float a = mode == 2 ? r.height() : r.width();
        float b = mode == 2 ? r.width() : r.height();
        span = b;
        radius = 0.13f * a;
        float tilt = 0.32f - 0.12f * t;
        cos = (float) Math.cos(tilt);
        sin = (float) Math.sin(tilt);
        float far = a * cos + b * sin;
        roll = far - t * (far + PI * radius * 1.15f);
    }

    /**
     * Distance from the spine, and position along it, of the screen point (x, y) for a page
     * in {@code r}: {@code o[0]} runs from the spine (0) to the free edge, {@code o[1]} along it.
     */
    static void local(RectF r, String direction, float x, float y, float[] o) {
        if ("up".equals(direction)) {
            o[0] = y - r.top;
            o[1] = x - r.left;
        } else if ("right".equals(direction)) {
            o[0] = r.right - x;
            o[1] = y - r.top;
        } else {
            o[0] = x - r.left;
            o[1] = y - r.top;
        }
    }

    /** The page's length from the spine to the free edge. */
    static float depth(RectF r, String direction) {
        return "up".equals(direction) ? r.height() : r.width();
    }

    /**
     * How far turned the page must be so that its free edge, at height {@code b} along the
     * spine, sits at {@code target} from the spine: the edge stays under the finger.
     */
    float follow(RectF r, String direction, float target, float b) {
        float lo = 0f, hi = 1f;
        float a = depth(r, direction);
        for (int i = 0; i < 22; i++) {
            float mid = (lo + hi) / 2f;
            setup(r, direction, mid);
            bend(a, b);
            if (out[0] > target) lo = mid;
            else hi = mid;
        }
        return Math.max(0f, Math.min(1f, (lo + hi) / 2f));
    }

    /** Where a point of the flat page ends up, in page coordinates, with its light level. */
    private void bend(float a, float b) {
        float u = across(a, b);
        float d = u - roll;
        float moved, light = 1f;
        if (d <= 0) {
            moved = u;
        } else if (d < PI * radius) {
            float th = d / radius;
            moved = roll + radius * (float) Math.sin(th);
            if (th < PI / 2) {
                float sn = (float) Math.sin(th);
                light = 1f - 0.35f * sn * sn;
            }
        } else {
            moved = roll - (d - PI * radius);
        }
        out[0] = a + (moved - u) * cos;
        out[1] = b + (moved - u) * (topLeads ? -sin : sin);
        out[2] = light;
    }

    private void toScreen(float a, float b) {
        toScreen(a, b, out[2]);
    }

    private void toScreen(float a, float b, float light) {
        if (mode == 0) {
            out[0] = left + a;
            out[1] = top + b;
        } else if (mode == 1) {
            out[0] = right - a;
            out[1] = top + b;
        } else {
            out[0] = left + b;
            out[1] = top + a;
        }
        out[2] = light;
    }

    /** A strip between two lines parallel to the roll, long enough to cross the page. */
    private void band(float u0, float u1, float b) {
        float[][] pts = {{u0, -b}, {u1, -b}, {u1, 2 * b}, {u0, 2 * b}};
        for (int i = 0; i < 4; i++) {
            float pb = pts[i][1];
            toScreen((pts[i][0] - (topLeads ? span - pb : pb) * sin) / cos, pb);
            if (i == 0) path.moveTo(out[0], out[1]);
            else path.lineTo(out[0], out[1]);
        }
        path.close();
    }

    /** The part of the page beyond {@code u >= limit}, as a polygon in page coordinates. */
    private float[] backOutline(float a, float b, float limit) {
        float[] rect = {0, 0, a, 0, a, b, 0, b};
        float[] res = new float[20];
        int n = 0;
        for (int i = 0; i < 4; i++) {
            float pa = rect[2 * i], pb = rect[2 * i + 1];
            float qa = rect[2 * ((i + 1) % 4)], qb = rect[2 * ((i + 1) % 4) + 1];
            float pu = across(pa, pb) - limit, qu = across(qa, qb) - limit;
            if (pu >= 0) {
                res[n++] = pa;
                res[n++] = pb;
            }
            if ((pu >= 0) != (qu >= 0)) {
                float f = pu / (pu - qu);
                res[n++] = pa + (qa - pa) * f;
                res[n++] = pb + (qb - pb) * f;
            }
        }
        float[] poly = new float[n];
        System.arraycopy(res, 0, poly, 0, n);
        return poly;
    }

    private static int tone(int color, float f) {
        int r = Math.round(((color >> 16) & 0xFF) * f);
        int g = Math.round(((color >> 8) & 0xFF) * f);
        int b = Math.round((color & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
