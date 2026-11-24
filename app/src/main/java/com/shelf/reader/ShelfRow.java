package com.shelf.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.widget.LinearLayout;

/** One shelf: a row of books standing on a softly glowing plank. */
final class ShelfRow extends LinearLayout {
    final BookView[] slots;
    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Shader plank, line, glowDown, glowUp;
    private String hint;
    /** Room under the books: the shelf plank and its glow, then the books' names. */
    private static final float BELOW = 66;
    private final android.text.TextPaint namePaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.HashMap<String, android.text.StaticLayout> names = new java.util.HashMap<>();

    ShelfRow(Context c, int cols) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.BOTTOM);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (BELOW * d));
        slots = new BookView[cols];
        for (int i = 0; i < cols; i++) {
            slots[i] = new BookView(c);
            addView(slots[i], new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }
        hintPaint.setColor(0x80B8B2A6);
        hintPaint.setTextSize(13.5f * d);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setColor(0xCCE8E2D4);
        namePaint.setTextSize(11.5f * d);
    }

    void setHint(String text) {
        hint = text;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float top = h - BELOW * d;
        names.clear();
        plank = new LinearGradient(0, top, 0, top + 9 * d, 0xFF2B2B35, 0xFF131318, Shader.TileMode.CLAMP);
        line = new LinearGradient(0, 0, w, 0,
                new int[]{0x00D4AF6A, 0xAAD4AF6A, 0xAAD4AF6A, 0x00D4AF6A},
                new float[]{0f, 0.25f, 0.75f, 1f}, Shader.TileMode.CLAMP);
        glowDown = new LinearGradient(0, top + 9 * d, 0, top + 29 * d, 0x30D4AF6A, 0x00D4AF6A, Shader.TileMode.CLAMP);
        glowUp = new LinearGradient(0, top - 80 * d, 0, top, 0x00D4AF6A, 0x12D4AF6A, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (plank == null || w == 0) return;
        float top = h - BELOW * d;

        paint.setShader(glowUp);
        canvas.drawRect(0, top - 80 * d, w, top, paint);

        paint.setShader(plank);
        rect.set(5 * d, top, w - 5 * d, top + 9 * d);
        canvas.drawRoundRect(rect, 2 * d, 2 * d, paint);

        paint.setShader(line);
        canvas.drawRect(5 * d, top, w - 5 * d, top + 1.2f * d, paint);

        paint.setShader(glowDown);
        canvas.drawRect(14 * d, top + 9 * d, w - 14 * d, top + 29 * d, paint);
        paint.setShader(null);

        if (hint != null) {
            canvas.drawText(hint, w / 2f, top - (top - getPaddingTop()) / 2f, hintPaint);
        }

        // Each book's name, centred under it, two lines at most.
        for (BookView slot : slots) {
            Book b = slot.book();
            if (b == null || slot.getVisibility() != VISIBLE) continue;
            int width = Math.max(1, slot.getWidth() - (int) (8 * d));
            String key = b.title + "\u0000" + width;
            android.text.StaticLayout name = names.get(key);
            if (name == null) {
                name = android.text.StaticLayout.Builder.obtain(b.title, 0, b.title.length(), namePaint, width)
                        .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(2)
                        .setEllipsize(android.text.TextUtils.TruncateAt.END)
                        .build();
                names.put(key, name);
            }
            canvas.save();
            canvas.translate(slot.getLeft() + (slot.getWidth() - width) / 2f, top + 17 * d);
            name.draw(canvas);
            canvas.restore();
        }
    }
}
