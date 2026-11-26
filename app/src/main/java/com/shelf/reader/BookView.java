package com.shelf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** A single book cover standing on a shelf. */
final class BookView extends View {
    static final int GOLD = 0xFFD4AF6A;

    private static final int[][] PALETTE = {
            {0xFF3D2C52, 0xFF1A1226}, {0xFF1F3D52, 0xFF0E1B26}, {0xFF522C2C, 0xFF261212},
            {0xFF2C5240, 0xFF12261C}, {0xFF52472C, 0xFF262012}, {0xFF2C3652, 0xFF121826},
    };

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tmp = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();

    private Book book;
    private Bitmap bitmap;
    private StaticLayout titleLayout;
    private Shader coverShader;

    BookView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        titlePaint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        titlePaint.setColor(0xFFEFE8D8);
        smallPaint.setTextAlign(Paint.Align.CENTER);
    }

    Book book() {
        return book;
    }

    // ------------------------------------------------------------ animation

    private boolean lifted;

    /** Back to standing straight on the shelf, at once (used when a shelf row is reused). */
    void stand() {
        animate().cancel();
        lifted = false;
        setTranslationY(0);
        setRotation(0);
        setScaleX(1f);
        setScaleY(1f);
    }

    boolean lifted() {
        return lifted;
    }

    /** Touching a book lifts it out of the row with a slight tilt, like taking it off the shelf. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (book != null) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                lifted = true;
                setPivotX(getWidth() / 2f);
                setPivotY(getHeight());
                animate().translationY(-10 * d).rotation(-3f).scaleX(1.05f).scaleY(1.05f)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .setDuration(140).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (lifted) settle();
            }
        }
        return super.onTouchEvent(e);
    }

    /** Back onto the shelf with a small bounce. */
    void settle() {
        lifted = false;
        animate().translationY(0).rotation(0).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(2.5f)).setDuration(380).start();
    }

    /** Opening: the book slides up and out of the shelf, then {@code then} runs. */
    void pullOut(Runnable then) {
        lifted = true;
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        animate().translationY(-34 * d).rotation(0).scaleX(1.1f).scaleY(1.1f)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .setDuration(170).withEndAction(then).start();
    }

    void setBook(Book b) {
        stand();
        book = b;
        bitmap = null;
        titleLayout = null;
        coverShader = null;
        if (b != null && !b.coverPath().isEmpty()) {
            bitmap = CoverCache.cached(b);
            if (bitmap == null) {
                final Book want = b;
                CoverCache.load(b, new CoverCache.Callback() {
                    @Override public void onCover(Bitmap bmp) {
                        if (book == want && bmp != null) {
                            bitmap = bmp;
                            invalidate();
                        }
                    }
                });
            }
        }
        setVisibility(b == null ? INVISIBLE : VISIBLE);
        invalidate();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        float coverW = w - 14 * d;
        setMeasuredDimension(w, Math.max(1, Math.round(coverW * 1.48f + 8 * d)));
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        titleLayout = null;
        coverShader = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (book == null || getWidth() == 0) return;
        rect.set(7 * d, 8 * d, getWidth() - 7 * d, getHeight());
        float r = 3 * d;

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1B1B22);
        paint.setShadowLayer(9 * d, 0, 2 * d, 0xDD000000);
        canvas.drawRoundRect(rect, r, r, paint);
        paint.clearShadowLayer();

        canvas.save();
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        canvas.clipPath(clip);

        if (bitmap != null) {
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float scale = Math.max(rect.width() / bw, rect.height() / bh);
            float sw = rect.width() / scale, sh = rect.height() / scale;
            float sx = (bw - sw) / 2f, sy = (bh - sh) / 2f;
            src.set((int) sx, (int) sy, (int) (sx + sw), (int) (sy + sh));
            paint.setColor(0xFFFFFFFF);
            canvas.drawBitmap(bitmap, src, rect, paint);
        } else {
            drawGenerated(canvas);
        }

        // Spine highlight along the left edge.
        paint.setShader(null);
        paint.setColor(0x26FFFFFF);
        canvas.drawRect(rect.left + 2 * d, rect.top, rect.left + 3 * d, rect.bottom, paint);
        paint.setColor(0x33000000);
        canvas.drawRect(rect.left, rect.top, rect.left + 2 * d, rect.bottom, paint);

        // Format tag.
        String tag = book.format.equals("mobi") ? "KINDLE" : book.format.toUpperCase();
        smallPaint.setTextSize(7.5f * d);
        smallPaint.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = smallPaint.measureText(tag) + 8 * d;
        tmp.set(rect.right - tw - 4 * d, rect.top + 4 * d, rect.right - 4 * d, rect.top + 16 * d);
        paint.setColor(0xAA000000);
        canvas.drawRoundRect(tmp, 3 * d, 3 * d, paint);
        smallPaint.setColor(0xFFE8E2D4);
        canvas.drawText(tag, tmp.centerX(), tmp.bottom - 3.4f * d, smallPaint);

        if (book.onlineOnly()) {
            // Not downloaded yet: faded, with a small cloud mark.
            paint.setShader(null);
            paint.setColor(0x8C0A0A0D);
            canvas.drawRect(rect, paint);
            smallPaint.setTextSize(15 * d);
            smallPaint.setColor(0xFFE8E2D4);
            canvas.drawText("\u2601", rect.centerX(), rect.centerY() + 5 * d, smallPaint);
        }

        if (book.reading) {
            float barTop = rect.bottom - 4 * d;
            paint.setColor(0xB3000000);
            canvas.drawRect(rect.left, barTop, rect.right, rect.bottom, paint);
            float p = Math.max(0.03f, Math.min(1f, book.progress));
            paint.setColor(GOLD);
            canvas.drawRect(rect.left, barTop, rect.left + rect.width() * p, rect.bottom, paint);
        }
        canvas.restore();
    }

    private void drawGenerated(Canvas canvas) {
        if (coverShader == null) {
            int[] c = PALETTE[(book.id.hashCode() & 0x7fffffff) % PALETTE.length];
            coverShader = new LinearGradient(rect.left, rect.top, rect.right, rect.bottom, c[0], c[1], Shader.TileMode.CLAMP);
        }
        paint.setShader(coverShader);
        canvas.drawRect(rect, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(d);
        paint.setColor(0x55D4AF6A);
        tmp.set(rect.left + 7 * d, rect.top + 6 * d, rect.right - 6 * d, rect.bottom - 8 * d);
        canvas.drawRect(tmp, paint);
        paint.setStyle(Paint.Style.FILL);

        int textWidth = Math.max(1, (int) (rect.width() - 26 * d));
        if (titleLayout == null) {
            titlePaint.setTextSize(Math.max(10 * d, rect.width() * 0.115f));
            String t = book.title;
            titleLayout = StaticLayout.Builder.obtain(t, 0, t.length(), titlePaint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(5)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
        }
        canvas.save();
        canvas.translate(rect.left + 14 * d, rect.top + rect.height() * 0.2f);
        titleLayout.draw(canvas);
        canvas.restore();

        if (!book.author.isEmpty()) {
            smallPaint.setTextSize(Math.max(8 * d, rect.width() * 0.08f));
            smallPaint.setTypeface(Typeface.DEFAULT);
            smallPaint.setColor(0xB3EFE8D8);
            CharSequence a = TextUtils.ellipsize(book.author, smallPaint, textWidth, TextUtils.TruncateAt.END);
            canvas.drawText(a, 0, a.length(), rect.centerX(), rect.bottom - 16 * d, smallPaint);
        }
    }
}
