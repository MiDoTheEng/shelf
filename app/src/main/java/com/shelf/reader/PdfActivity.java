package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** PDF reader: page by page with a swipe, or continuous scroll; zoom, night mode, bookmarks, quotes. */
public class PdfActivity extends Activity {
    static final ColorMatrixColorFilter INVERT = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
            -1, 0, 0, 0, 255,
            0, -1, 0, 0, 255,
            0, 0, -1, 0, 255,
            0, 0, 0, 1, 0}));

    /** A frame whose width is dictated by the zoom level so it can scroll sideways. */
    private static final class FixedWidthFrame extends FrameLayout {
        int fixedWidth = 1;

        FixedWidthFrame(Context c) {
            super(c);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(fixedWidth, MeasureSpec.EXACTLY), heightSpec);
        }
    }

    private Library lib;
    private Book book;
    private SharedPreferences prefs;
    private PdfRenderer renderer;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private float[] aspect = new float[0];
    private int pageCount;
    private int baseWidth;
    private int maxRenderWidth;
    private volatile boolean closed;
    private boolean night;
    private View topBar;
    private TextView pill;
    private TextView star;

    // Continuous scroll.
    private LruCache<Integer, Bitmap> cache;
    private final HashSet<Integer> pending = new HashSet<>();
    private float zoom = 1f;
    private volatile int generation;
    private volatile int firstVisible;
    private volatile int lastVisible;
    private int lastKnown;
    private ListView list;
    private FixedWidthFrame frame;
    private HorizontalScrollView sideways;
    private PageAdapter adapter;
    private ScaleGestureDetector scaler;
    private boolean pinching;
    private float pinch = 1f;

    // Page by page.
    private boolean paged;
    private PdfPageView pageView;
    private volatile int pagedPage;
    private volatile int pagedGen;
    private final HashMap<Integer, Bitmap> near = new HashMap<>();
    private final HashSet<String> pagedPending = new HashSet<>();

    // Text selected on the page.
    private PdfSelect.Result selection;
    private View quoteBar;
    private boolean selBusy;
    private Object[] selNext;
    private int selGen;
    private boolean selWarned;
    /** The page this reading session started on, to tell whether the reader actually moved. */
    private int startPage = -1;
    private boolean askingPending;
    /** The app's own reader of page text, for when Android can't read it (before Android 15). */
    private PdfText pdfText;

    // Drawing and highlighting.
    private Ink ink;
    private View drawBar;
    private View penButton;
    /** The big tool circle, and the two small ones under it holding the other tools. */
    private ToolButton bigTool;
    private final ToolButton[] smallTools = new ToolButton[2];
    /** Drawing with a finger is on (with or without the colours menu open). */
    private boolean drawing;
    /** Round undo and redo buttons beside the pen, shown while drawing (redo left, undo right). */
    private View undoButton, redoButton;
    /** What undo took away on this page, so redo can bring it back. */
    private final java.util.ArrayList<Snap> redoStack = new java.util.ArrayList<>();
    /** The page's drawings before each change on it: strokes, erasing and highlights alike. */
    private final java.util.ArrayList<Snap> undoStack = new java.util.ArrayList<>();
    /** Taken when a stroke or erase begins; kept only if the page really changed. */
    private Snap startOfChange;

    /** The strokes and highlights on a page at one moment (strokes never change once drawn). */
    private static final class Snap {
        final java.util.ArrayList<Ink.Stroke> strokes;
        final java.util.ArrayList<Ink.Mark> marks;

        Snap(Ink.Page p) {
            strokes = new java.util.ArrayList<>(p.strokes);
            marks = new java.util.ArrayList<>(p.marks);
        }
    }
    private TextView[] toolPills;
    private View[] colorDots;
    private static final int[] COLORS = {0xFF1A1A1A, 0xFFE53935, 0xFF1E6FE0, 0xFF2E9E4F, 0xFFFFD54A, 0xFFFF7EB6};

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        prefs = getSharedPreferences("shelf", MODE_PRIVATE);
        night = prefs.getBoolean("pdfNight", false);
        String id = getIntent().getStringExtra("id");
        book = id == null ? null : lib.find(id);
        if (book == null) {
            finish();
            return;
        }

        ink = Ink.load(this, book.id);
        pdfText = new PdfText(this, new File(book.file));

        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(new File(book.file), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            pageCount = renderer.getPageCount();
            if (pageCount <= 0) throw new IllegalStateException("empty");
            PdfRenderer.Page first = renderer.openPage(0);
            float a = first.getHeight() / (float) Math.max(1, first.getWidth());
            first.close();
            aspect = new float[pageCount];
            Arrays.fill(aspect, a);
        } catch (Exception e) {
            try {
                if (renderer != null) renderer.close();
                else if (pfd != null) pfd.close();
            } catch (Exception ignored) {
            }
            renderer = null;
            Ui.toast(this, "This PDF can't be opened (it may be password-protected or damaged)");
            finish();
            return;
        }

        long maxKb = Runtime.getRuntime().maxMemory() / 1024;
        maxRenderWidth = maxKb < 200 * 1024 ? 1500 : 2200;
        cache = new LruCache<Integer, Bitmap>((int) Math.min(Integer.MAX_VALUE, maxKb / 4)) {
            @Override protected int sizeOf(Integer key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        baseWidth = getResources().getDisplayMetrics().widthPixels;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0A0D);

        list = new ListView(this);
        list.setDivider(new ColorDrawable(0xFF0A0A0D));
        list.setDividerHeight(Ui.dp(this, 6));
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new PageAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long rowId) {
                toggleBar();
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long rowId) {
                Ui.toast(PdfActivity.this, "To select text on the page, switch to page by page: "
                        + "\u22EE \u2192 Page turning");
                return true;
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState != SCROLL_STATE_IDLE) return;
                for (int i = 0; i < view.getChildCount(); i++) {
                    View child = view.getChildAt(i);
                    if (child instanceof ImageView && ((ImageView) child).getDrawable() == null
                            && child.getTag() instanceof Integer) {
                        request((Integer) child.getTag());
                    }
                }
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                firstVisible = first;
                lastVisible = first + Math.max(1, visible) - 1;
                if (!paged) updatePill();
            }
        });

        frame = new FixedWidthFrame(this);
        frame.fixedWidth = pageWidth();
        frame.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sideways = new HorizontalScrollView(this);
        sideways.setHorizontalScrollBarEnabled(false);
        sideways.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sideways.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(sideways, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        pageView = new PdfPageView(this);
        pageView.setNight(night);
        pageView.listener = new PdfPageView.Listener() {
            @Override public void onTurn(int delta) {
                int target = pagedPage + delta;
                if (target < 0) {
                    Ui.toast(PdfActivity.this, "This is the first page");
                } else if (target >= pageCount) {
                    Ui.toast(PdfActivity.this, "This is the last page");
                } else {
                    showPage(target, delta);
                }
            }

            @Override public void onTap() {
                toggleBar();
            }

            @Override public void onZoomSettled(float z) {
                if (z > 1.3f) renderPaged(pagedPage, Math.min(z, 3f));
            }

            @Override public void onSelect(PdfSelect.Bound a, PdfSelect.Bound b, boolean word) {
                requestSelect(a, b, word);
            }

            @Override public void onSelectClear() {
                clearSelection();
            }

            @Override public void onInkStart() {
                startOfChange = new Snap(ink.page(pagedPage));
            }

            @Override public void onInkChanged() {
                ink.dirty = true;
                if (startOfChange != null) remember(startOfChange);
                startOfChange = null;
                refreshUndo();
            }
        };
        root.addView(pageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = Ui.bar(this);
        bar.addView(Ui.barButton(this, "‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        }));
        bar.addView(Ui.barTitle(this, book.title), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        star = Ui.barButton(this, "☆", 21, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleBookmark();
            }
        });
        star.setTextColor(BookView.GOLD);
        star.setContentDescription("Bookmark");
        bar.addView(star);
        TextView more = Ui.barButton(this, "⋮", 22, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMenu();
            }
        });
        more.setContentDescription("More");
        bar.addView(more);
        topBar = bar;
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        pill = new TextView(this);
        pill.setTextSize(12.5f);
        pill.setTextColor(Ui.INK);
        pill.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xD9101014);
        pillBg.setCornerRadius(Ui.dp(this, 18));
        pillBg.setStroke(Ui.dp(this, 1), 0x55D4AF6A);
        pill.setBackground(pillBg);
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                askPage();
            }
        });
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = Ui.dp(this, 18);
        root.addView(pill, plp);

        // The small menu that appears while text is selected on the page.
        LinearLayout quote = Ui.selectionMenu(this, new Runnable() {
            @Override public void run() {
                useSelection(0);
            }
        }, new Runnable() {
            @Override public void run() {
                useSelection(2);
            }
        }, new Runnable() {
            @Override public void run() {
                highlightSelection();
            }
        }, new Runnable() {
            @Override public void run() {
                useSelection(1);
            }
        });
        quote.setVisibility(View.GONE);
        quoteBar = quote;
        root.addView(quote, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        // The pen tools open in a small panel beside the floating pen button.
        drawBar = buildDrawBar();
        drawBar.setVisibility(View.GONE);
        root.addView(drawBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        // A round pen button floating over the page: tap to open the tools, drag to move it.
        final ToolButton pen = new ToolButton(this, true);
        pen.setElevation(Ui.dp(this, 8));
        bigTool = pen;
        pen.setOnTouchListener(new View.OnTouchListener() {
            private float startX, startY, offX, offY;
            private boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = e.getRawX();
                        startY = e.getRawY();
                        offX = v.getX() - startX;
                        offY = v.getY() - startY;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getRawX() - startX, e.getRawY() - startY) > Ui.dp(PdfActivity.this, 8)) {
                            moved = true;
                        }
                        if (moved) {
                            View parent = (View) v.getParent();
                            v.setX(Math.max(0, Math.min(parent.getWidth() - v.getWidth(), e.getRawX() + offX)));
                            v.setY(Math.max(0, Math.min(parent.getHeight() - v.getHeight(), e.getRawY() + offY)));
                            placeUndo();
                            if (drawBar.getVisibility() == View.VISIBLE) placeDrawBar();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            View parent = (View) v.getParent();
                            prefs.edit()
                                    .putFloat("penX", v.getX() / Math.max(1, parent.getWidth() - v.getWidth()))
                                    .putFloat("penY", v.getY() / Math.max(1, parent.getHeight() - v.getHeight()))
                                    .apply();
                        } else {
                            bigTap();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        penButton = pen;
        redoButton = arrowButton(false, "Redo");
        undoButton = arrowButton(true, "Undo");
        redoButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                redo();
            }
        });
        undoButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                undo();
            }
        });
        int small = Ui.dp(this, 52);
        root.addView(redoButton, new FrameLayout.LayoutParams(small, small, Gravity.TOP | Gravity.LEFT));
        root.addView(undoButton, new FrameLayout.LayoutParams(small, small, Gravity.TOP | Gravity.LEFT));
        int mini = Ui.dp(this, 46);
        for (int i = 0; i < 2; i++) {
            final ToolButton t = new ToolButton(this, false);
            t.setElevation(Ui.dp(this, 6));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    chooseTool(t.tool);
                }
            });
            smallTools[i] = t;
            root.addView(t, new FrameLayout.LayoutParams(mini, mini, Gravity.TOP | Gravity.LEFT));
        }
        int size = Ui.dp(this, 56);
        root.addView(pen, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
        root.post(new Runnable() {
            @Override public void run() {
                placePen();
            }
        });

        scaler = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                pinching = true;
                pinch = 1f;
                long now = SystemClock.uptimeMillis();
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                PdfActivity.super.dispatchTouchEvent(cancel);
                cancel.recycle();
                frame.setPivotX(detector.getFocusX() + sideways.getScrollX());
                frame.setPivotY(detector.getFocusY());
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                pinch *= detector.getScaleFactor();
                float target = Math.max(1f, Math.min(4f, zoom * pinch));
                frame.setScaleX(target / zoom);
                frame.setScaleY(target / zoom);
                return true;
            }
        });

        setContentView(root);
        int start = Math.max(0, Math.min(pageCount - 1, book.chapter));
        firstVisible = start;
        lastVisible = start;
        lastKnown = start;
        list.setSelection(start);
        applyMode(start);
        startPage = start;
        ReaderActivity.autoHide(root, topBar);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (paged || scaler == null) return super.dispatchTouchEvent(ev);
        scaler.onTouchEvent(ev);
        if (pinching) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pinching = false;
                frame.setScaleX(1f);
                frame.setScaleY(1f);
                setZoom(zoom * pinch);
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (renderer == null) return;
        baseWidth = getResources().getDisplayMetrics().widthPixels;
        if (penButton != null) {
            penButton.post(new Runnable() {
                @Override public void run() {
                    placePen();
                }
            });
        }
        if (paged) {
            pagedGen++;
            near.clear();
            pagedPending.clear();
            showPage(pagedPage, 0);
        } else {
            relayoutPages(1f);
        }
    }

    private void toggleBar() {
        topBar.setVisibility(topBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------- where we are

    private int currentPage() {
        if (paged) return pagedPage;
        if (list.getChildCount() == 0) return lastKnown;
        int p = list.pointToPosition(list.getWidth() / 2, list.getHeight() / 2);
        if (p < 0) p = list.getFirstVisiblePosition();
        lastKnown = Math.max(0, Math.min(pageCount - 1, p));
        return lastKnown;
    }

    private void goTo(int page) {
        page = Math.max(0, Math.min(pageCount - 1, page));
        if (paged) {
            showPage(page, 0);
        } else {
            lastKnown = page;
            list.setSelection(page);
        }
    }

    private void updatePill() {
        if (pill == null) return;
        int p = currentPage();
        pill.setText((p + 1) + " / " + pageCount);
        star.setText(markOn(p) != null ? "★" : "☆");
    }

    /** Switches between continuous scroll and page-by-page, staying on the given page. */
    private void applyMode(int page) {
        clearSelection();
        if (Ui.mode(prefs).equals("scroll") && drawBar != null && drawing) {
            stopDrawing();
        }
        String mode = Ui.mode(prefs);
        paged = !mode.equals("scroll");
        pageView.mode = mode;
        sideways.setVisibility(paged ? View.GONE : View.VISIBLE);
        pageView.setVisibility(paged ? View.VISIBLE : View.GONE);
        if (paged) {
            showPage(page, 0);
        } else {
            lastKnown = page;
            list.setSelection(page);
            updatePill();
        }
    }

    // ------------------------------------------------------------ page by page

    private void showPage(int page, int direction) {
        clearSelection();
        pagedPage = Math.max(0, Math.min(pageCount - 1, page));
        Iterator<Integer> it = near.keySet().iterator();
        while (it.hasNext()) if (Math.abs(it.next() - pagedPage) > 2) it.remove();
        Bitmap ready = near.get(pagedPage);
        if (ink.dirty) ink.save();
        // The current page stays on screen until the new one is drawn, then turns over to it
        // like paper: no black flash in between.
        pageView.turnTo(ready, ink.page(pagedPage), direction);
        // Each page keeps its own undo history while you're on it.
        redoStack.clear();
        undoStack.clear();
        startOfChange = null;
        refreshUndo();
        if (ready == null) renderPaged(pagedPage, 1f);
        // Get the next two pages ready in the reading direction, and one behind.
        int step = direction < 0 ? -1 : 1;
        int[] soon = {pagedPage + step, pagedPage + 2 * step, pagedPage - step};
        for (int p : soon) {
            if (p >= 0 && p < pageCount && !near.containsKey(p)) renderPaged(p, 1f);
        }
        updatePill();
    }

    private void renderPaged(final int pos, final float scale) {
        final int gen = pagedGen;
        final String key = pos + "@" + scale;
        if (!pagedPending.add(key)) return;
        exec.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    if (!closed && gen == pagedGen && Math.abs(pos - pagedPage) <= 2) {
                        PdfRenderer.Page page = renderer.openPage(pos);
                        try {
                            float a = page.getHeight() / (float) Math.max(1, page.getWidth());
                            int w = Math.max(1, Math.min(Math.round(baseWidth * scale), maxRenderWidth));
                            int h = Math.max(1, Math.round(w * a));
                            if (h > 2 * maxRenderWidth) {
                                w = Math.max(1, Math.round(w * (2f * maxRenderWidth / h)));
                                h = 2 * maxRenderWidth;
                            }
                            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            page.close();
                        }
                    }
                } catch (Throwable t) {
                    bmp = null;
                }
                final Bitmap result = bmp;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen == pagedGen) pagedPending.remove(key);
                        if (closed || gen != pagedGen || result == null) return;
                        if (scale == 1f) {
                            if (Math.abs(pos - pagedPage) <= 2) near.put(pos, result);
                            if (pos == pagedPage && pageView.isWaiting()) {
                                pageView.arrived(result);
                            } else if (pos == pagedPage && pageView.zoom() <= 1.3f) {
                                pageView.setBitmap(result, false);
                            }
                        } else if (pos == pagedPage && pageView.zoom() > 1.3f) {
                            pageView.setBitmap(result, false);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------- selecting text on a page

    private void clearSelection() {
        selGen++;
        selNext = null;
        selection = null;
        if (pageView != null) pageView.setSelection(null, false);
        if (quoteBar != null) quoteBar.setVisibility(View.GONE);
    }

    /** Looks up the text between two points; only the newest request waits while one is running. */
    private void requestSelect(final PdfSelect.Bound a, final PdfSelect.Bound b, final boolean word) {
        if (selBusy) {
            selNext = new Object[]{a, b, word};
            return;
        }
        selBusy = true;
        if (word) selGen++;
        final int gen = selGen;
        final int page = pagedPage;
        exec.execute(new Runnable() {
            @Override public void run() {
                PdfSelect.Result found = null;
                try {
                    if (!closed) {
                        PdfRenderer.Page p = renderer.openPage(page);
                        try {
                            found = PdfSelect.select(p, a, b);
                        } finally {
                            p.close();
                        }
                        if (found == null || found.unsupported) found = pdfText.select(page, a, b);
                    }
                } catch (Throwable ignored) {
                }
                final PdfSelect.Result result = found;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        selBusy = false;
                        if (closed || isDestroyed()) return;
                        if (gen == selGen && page == pagedPage && paged) showSelection(result, word);
                        Object[] next = selNext;
                        selNext = null;
                        if (next != null) {
                            requestSelect((PdfSelect.Bound) next[0], (PdfSelect.Bound) next[1], (Boolean) next[2]);
                        }
                    }
                });
            }
        });
    }

    private void showSelection(PdfSelect.Result r, boolean word) {
        if (r == null || r.text.isEmpty() || r.rects.isEmpty()) {
            // Nothing under the finger: keep what is already selected while dragging.
            if (word) {
                selection = null;
                pageView.setSelection(null, false);
                quoteBar.setVisibility(View.GONE);
                Ui.toast(this, "No text here to select (scanned pages are pictures)");
            } else if (r != null && r.error != null && !selWarned) {
                selWarned = true;
                Ui.toast(this, "The selection could not be extended: " + r.error);
            }
            return;
        }
        selection = r;
        pageView.setSelection(r, word);
        float[] at = pageView.selectionBounds();
        if (at != null) {
            // The gap below leaves room for the two gold handles under the text.
            Ui.placeNear(quoteBar, pageView, at[0], at[1], at[2], at[3], Ui.dp(this, 34));
        }
    }

    /** 0 saves the selection as a quote, 1 shares it, 2 translates it. */
    private void useSelection(int action) {
        String text = selection == null ? "" : Quotes.clean(selection.text);
        int page = pagedPage;
        if (text.isEmpty()) {
            Ui.toast(this, "Select some text first");
        } else if (action == 2) {
            Translate.show(this, text, book.title);
        } else if (action == 1) {
            Quotes.share(this, Quotes.shareText(book.title, book.author, text));
        } else if (Quotes.add(this, book, text, "Page " + (page + 1))) {
            Ui.toast(this, "Quote saved");
        } else {
            Ui.toast(this, "This quote is already saved");
        }
        clearSelection();
    }

    // --------------------------------------------------- drawing and highlighting

    /** The drawing tools in three short rows, so everything shows at once without sliding. */
    private View buildDrawBar() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101014);
        bg.setCornerRadius(Ui.dp(this, 22));
        bg.setStroke(Ui.dp(this, 1), 0x55D4AF6A);
        box.setBackground(bg);
        box.setElevation(Ui.dp(this, 8));
        box.setClickable(true);

        LinearLayout toolsRow = new LinearLayout(this);
        toolsRow.setGravity(Gravity.CENTER);
        String[] names = {"Pen", "Highlighter", "Eraser"};
        final int[] tools = {PdfPageView.PEN, PdfPageView.MARKER, PdfPageView.ERASER};
        toolPills = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int tool = tools[i];
            TextView t = Ui.pill(this, names[i], false);
            t.setTextSize(13f);
            t.setPadding(Ui.dp(this, 13), Ui.dp(this, 8), Ui.dp(this, 13), Ui.dp(this, 8));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pageView.tool = tool;
                    refreshDrawBar();
                }
            });
            toolPills[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            toolsRow.addView(t, lp);
        }
        box.addView(toolsRow);

        LinearLayout colorsRow = new LinearLayout(this);
        colorsRow.setGravity(Gravity.CENTER);
        colorDots = new View[COLORS.length];
        for (int i = 0; i < COLORS.length; i++) {
            final int color = COLORS[i];
            View dot = new View(this);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (pageView.tool == PdfPageView.MARKER) pageView.markerColor = color;
                    else pageView.penColor = color;
                    if (pageView.tool == PdfPageView.ERASER) pageView.tool = PdfPageView.PEN;
                    refreshDrawBar();
                }
            });
            colorDots[i] = dot;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
            lp.setMargins(Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 4));
            colorsRow.addView(dot, lp);
        }
        box.addView(colorsRow);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setGravity(Gravity.CENTER);
        actionsRow.addView(Ui.barButton(this, "Done", 14f, new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopDrawing();
            }
        }));
        box.addView(actionsRow);
        return box;
    }

    private void refreshDrawBar() {
        int[] tools = {PdfPageView.PEN, PdfPageView.MARKER, PdfPageView.ERASER};
        for (int i = 0; i < toolPills.length; i++) Ui.stylePill(toolPills[i], pageView.tool == tools[i]);
        int current = pageView.tool == PdfPageView.MARKER ? pageView.markerColor : pageView.penColor;
        for (int i = 0; i < colorDots.length; i++) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(COLORS[i]);
            boolean on = COLORS[i] == current && pageView.tool != PdfPageView.ERASER;
            dot.setStroke(Ui.dp(this, on ? 3 : 1), on ? 0xFFFFFFFF : 0x66FFFFFF);
            colorDots[i].setBackground(dot);
        }
        refreshTools();
    }

    /** Opens or closes the drawing tools; while they are open a finger draws too. */
    /** Puts the pen button where it was last dragged to (bottom-right the first time). */
    private void placePen() {
        View parent = (View) penButton.getParent();
        if (parent == null || parent.getWidth() == 0) return;
        float fx = prefs.getFloat("penX", 0.5f), fy = prefs.getFloat("penY", 0.86f);
        penButton.setX(Math.max(0, Math.min(1, fx)) * (parent.getWidth() - penButton.getWidth()));
        penButton.setY(Math.max(0, Math.min(1, fy)) * (parent.getHeight() - penButton.getHeight()));
        placeUndo();
        refreshUndo();
        refreshTools();
        if (drawBar.getVisibility() == View.VISIBLE) placeDrawBar();
    }

    /** Undo or redo, drawn as a curved arrow like a hand sketch (no button shape around it). */
    private View arrowButton(boolean towardsLeft, String name) {
        CurvedArrow a = new CurvedArrow(this, towardsLeft);
        a.setContentDescription(name);
        a.setClickable(true);
        return a;
    }

    /**
     * Redo on the left of the pen, undo on the right, level with it. Near a screen edge both go
     * on the side that has room, still redo-then-undo from left to right.
     */
    private void placeUndo() {
        View parent = (View) penButton.getParent();
        if (parent == null) return;
        float gap = Ui.dp(this, 2), w = undoButton.getLayoutParams().width;
        float px = penButton.getX(), pw = penButton.getWidth() > 0 ? penButton.getWidth() : Ui.dp(this, 56);
        float y = penButton.getY() + (pw - w) / 2f;
        float redoX = px - gap - w, undoX = px + pw + gap;
        if (redoX < 0) {
            redoX = undoX;
            undoX = redoX + w + gap;
        } else if (undoX + w > parent.getWidth()) {
            undoX = redoX;
            redoX = undoX - gap - w;
        }
        redoButton.setX(redoX);
        undoButton.setX(undoX);
        redoButton.setY(y);
        undoButton.setY(y);

        // The two small tool circles sit side by side just under the big one (above it near the bottom).
        float m = smallTools[0].getLayoutParams().width, g = Ui.dp(this, 6);
        float under = penButton.getY() + pw + g;
        float ty = under + m <= parent.getHeight() ? under : penButton.getY() - g - m;
        float left = px + pw / 2f - m - g / 2f;
        left = Math.max(0, Math.min(parent.getWidth() - 2 * m - g, left));
        smallTools[0].setX(left);
        smallTools[1].setX(left + m + g);
        smallTools[0].setY(ty);
        smallTools[1].setY(ty);
    }

    /** Big circle shows the tool in use; the small ones the other two, ready to swap in. */
    private void refreshTools() {
        if (bigTool == null) return;
        int t = pageView.tool;
        bigTool.set(t, colorOf(t), drawing);
        bigTool.close = drawBar.getVisibility() == View.VISIBLE;
        int k = 0;
        for (int other : new int[]{PdfPageView.PEN, PdfPageView.MARKER, PdfPageView.ERASER}) {
            if (other == t) continue;
            smallTools[k++].set(other, colorOf(other), false);
        }
    }

    private int colorOf(int tool) {
        return tool == PdfPageView.MARKER ? pageView.markerColor : pageView.penColor;
    }

    private void bigTap() {
        if (drawing) {
            stopDrawing();
        } else {
            // As before: opening the tools starts with the red pen.
            pageView.tool = PdfPageView.PEN;
            startDrawing(true);
        }
    }

    /** A small circle tapped: that tool goes into the big circle and drawing starts, no menu needed. */
    private void chooseTool(int tool) {
        pageView.tool = tool;
        if (!drawing) startDrawing(false);
        if (drawBar.getVisibility() == View.VISIBLE) refreshDrawBar();
        refreshTools();
    }

    private void startDrawing(boolean withMenu) {
        if (!paged) {
            Ui.toast(this, "Drawing works page by page. Switch in \u22EE \u2192 Page turning.");
            return;
        }
        clearSelection();
        if (!drawing) {
            // Every time drawing starts, the pen is red.
            pageView.penColor = 0xFFE53935;
            drawing = true;
            pageView.setDrawing(true);
            pageView.updateExclusion();
        }
        if (withMenu) {
            refreshDrawBar();
            placeDrawBar();
        }
        refreshTools();
        bigTool.invalidate();
    }

    private void stopDrawing() {
        drawing = false;
        drawBar.setVisibility(View.GONE);
        pageView.setDrawing(false);
        pageView.updateExclusion();
        if (ink.dirty) ink.save();
        refreshTools();
    }

    /** Records the page as it was before a change, so undo can go back to it. */
    private void remember(Snap before) {
        undoStack.add(before);
        if (undoStack.size() > 100) undoStack.remove(0);
        redoStack.clear();
    }

    private void refreshUndo() {
        if (undoButton == null) return;
        undoButton.setAlpha(undoStack.isEmpty() ? 0.4f : 1f);
        redoButton.setAlpha(redoStack.isEmpty() ? 0.4f : 1f);
    }

    private void restore(Snap snap) {
        Ink.Page p = ink.page(pagedPage);
        p.strokes.clear();
        p.strokes.addAll(snap.strokes);
        p.marks.clear();
        p.marks.addAll(snap.marks);
        ink.dirty = true;
        pageView.invalidate();
        refreshUndo();
    }

    /** Takes back the last change on this page, whether drawing, erasing or highlighting. */
    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.add(new Snap(ink.page(pagedPage)));
        restore(undoStack.remove(undoStack.size() - 1));
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(new Snap(ink.page(pagedPage)));
        restore(redoStack.remove(redoStack.size() - 1));
    }

    /** Puts the pen tools right beside the pen button. */
    private void placeDrawBar() {
        View parent = (View) penButton.getParent();
        float x = penButton.getX(), y = penButton.getY();
        float top = Math.min(y, smallTools[0].getY()), bottom = Math.max(y + penButton.getHeight(),
                smallTools[0].getY() + smallTools[0].getLayoutParams().height);
        Ui.placeNear(drawBar, parent, x, top, x + penButton.getWidth(), bottom, Ui.dp(this, 10));
    }


    /**
     * While the drawing tools are open the system bars are hidden. In that state Android
     * no longer goes "back" on a swipe from the screen edge (the first swipe only brings
     * the bars back for a moment), so strokes can start right at the page border.
     */
    private void hideSystemBars(boolean on) {
        if (Build.VERSION.SDK_INT < 30) return;
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) return;
        if (on) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        } else {
            controller.show(WindowInsets.Type.systemBars());
        }
    }

    /** Turns the selected text into a lasting highlight on the page. */
    private void highlightSelection() {
        float aspect = pageView.aspect();
        if (selection == null || selection.rects.isEmpty() || aspect <= 0) {
            clearSelection();
            return;
        }
        Ink.Mark m = new Ink.Mark();
        m.color = pageView.markerColor;
        m.rects = new float[selection.rects.size() * 4];
        int k = 0;
        for (android.graphics.RectF r : selection.rects) {
            m.rects[k++] = r.left;
            m.rects[k++] = r.top * aspect;
            m.rects[k++] = r.right;
            m.rects[k++] = r.bottom * aspect;
        }
        remember(new Snap(ink.page(pagedPage)));
        ink.page(pagedPage).marks.add(m);
        ink.dirty = true;
        refreshUndo();
        ink.save();
        clearSelection();
        pageView.invalidate();
    }

    // -------------------------------------------------------- continuous scroll

    private int pageWidth() {
        return Math.max(1, Math.round(baseWidth * zoom));
    }

    private void setZoom(float z) {
        z = Math.max(1f, Math.min(4f, z));
        if (Math.abs(z - zoom) < 0.02f) return;
        float ratio = z / zoom;
        zoom = z;
        relayoutPages(ratio);
    }

    private void relayoutPages(float ratio) {
        int pos = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top == null ? 0 : Math.round(top.getTop() * ratio);
        generation++;
        cache.evictAll();
        pending.clear();
        frame.fixedWidth = pageWidth();
        frame.requestLayout();
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(pos, offset);
    }

    private final class PageAdapter extends BaseAdapter {
        @Override public int getCount() { return pageCount; }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ImageView iv = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(PdfActivity.this);
            int w = pageWidth();
            int h = Math.max(1, Math.round(w * aspect[position]));
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            if (lp == null) {
                lp = new AbsListView.LayoutParams(w, h);
            } else {
                lp.width = w;
                lp.height = h;
            }
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setBackgroundColor(night ? 0xFF16161A : 0xFFF4F4F4);
            iv.setColorFilter(night ? INVERT : null);
            iv.setTag(position);
            Bitmap bmp = cache.get(position);
            iv.setImageBitmap(bmp);
            if (bmp == null) request(position);
            return iv;
        }
    }

    private void request(final int pos) {
        if (pos < firstVisible) firstVisible = pos;
        if (pos > lastVisible) lastVisible = pos;
        if (!pending.add(pos)) return;
        final int gen = generation;
        final int w = Math.min(pageWidth(), maxRenderWidth);
        exec.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                float a = 0f;
                try {
                    if (!closed && gen == generation && pos >= firstVisible - 2 && pos <= lastVisible + 2) {
                        PdfRenderer.Page page = renderer.openPage(pos);
                        try {
                            a = page.getHeight() / (float) Math.max(1, page.getWidth());
                            int h = Math.max(1, Math.round(w * a));
                            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        } finally {
                            page.close();
                        }
                    }
                } catch (Throwable t) {
                    bmp = null;
                }
                final Bitmap result = bmp;
                final float pageAspect = a;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen == generation) pending.remove(pos);
                        if (closed || gen != generation || result == null) return;
                        cache.put(pos, result);
                        if (Math.abs(aspect[pos] - pageAspect) > 0.01f) {
                            aspect[pos] = pageAspect;
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        for (int i = 0; i < list.getChildCount(); i++) {
                            View child = list.getChildAt(i);
                            if (child instanceof ImageView && Integer.valueOf(pos).equals(child.getTag())) {
                                ((ImageView) child).setImageBitmap(result);
                            }
                        }
                    }
                });
            }
        });
    }

    // --------------------------------------------------------------------- menu

    private void showMenu() {
        String[] items = {"Page turning…", "Bookmarks", "Save a quote from this page",
                "Quotes from this book", night ? "Day mode" : "Night mode", "Use this page as cover"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            final int page = currentPage();
                            Ui.pickMode(PdfActivity.this, prefs, new Runnable() {
                                @Override public void run() {
                                    applyMode(page);
                                }
                            });
                        } else if (which == 1) {
                            Ui.showMarks(PdfActivity.this, lib, book, new Ui.MarkPick() {
                                @Override public void open(Book.Mark m) {
                                    goTo(m.chapter);
                                }
                            }, new Runnable() {
                                @Override public void run() {
                                    updatePill();
                                }
                            });
                        } else if (which == 2) {
                            quoteFromPage();
                        } else if (which == 3) {
                            Intent i = new Intent(PdfActivity.this, QuotesActivity.class);
                            i.putExtra("book", book.id);
                            startActivity(i);
                        } else if (which == 4) {
                            night = !night;
                            prefs.edit().putBoolean("pdfNight", night).apply();
                            pageView.setNight(night);
                            adapter.notifyDataSetChanged();
                        } else {
                            CoverPicker.usePage(PdfActivity.this, book, currentPage(), new Runnable() {
                                @Override public void run() {
                                }
                            });
                        }
                    }
                })
                .show();
    }

    private void askPage() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("1 – " + pageCount);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Go to page")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            goTo(Integer.parseInt(input.getText().toString().trim()) - 1);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                })
                .show();
    }

    // ---------------------------------------------------------------- bookmarks

    private Book.Mark markOn(int page) {
        for (Book.Mark m : book.marks) if (m.chapter == page) return m;
        return null;
    }

    private void toggleBookmark() {
        int page = currentPage();
        Book.Mark here = markOn(page);
        if (here != null) {
            book.marks.remove(here);
            Ui.toast(this, "Bookmark removed");
        } else {
            Book.Mark m = new Book.Mark();
            m.chapter = page;
            m.label = "Page " + (page + 1);
            m.time = System.currentTimeMillis();
            book.marks.add(m);
            book.sortMarks();
            Ui.toast(this, "Page " + (page + 1) + " bookmarked");
        }
        book.marksTime = System.currentTimeMillis();
        lib.save();
        updatePill();
    }

    // ------------------------------------------------------------------- quotes

    /**
     * Reads the words on a page. Android can only do this from version 15 on, so the
     * call is looked up at run time and older phones simply get no text.
     */
    private static String pageText(PdfRenderer.Page page) {
        try {
            Object contents = page.getClass().getMethod("getTextContents").invoke(page);
            StringBuilder sb = new StringBuilder();
            if (contents instanceof List) {
                for (Object c : (List<?>) contents) {
                    Object text = c.getClass().getMethod("getText").invoke(c);
                    if (text != null) sb.append(text).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private void quoteFromPage() {
        final int page = currentPage();
        exec.execute(new Runnable() {
            @Override public void run() {
                String text = "";
                try {
                    if (!closed) {
                        PdfRenderer.Page p = renderer.openPage(page);
                        try {
                            text = pageText(p);
                        } finally {
                            p.close();
                        }
                        if (text.isEmpty()) text = pdfText.pageText(page);
                    }
                } catch (Throwable ignored) {
                }
                final String found = text;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!closed && !isDestroyed()) showQuoteEditor(page, found);
                    }
                });
            }
        });
    }

    private void showQuoteEditor(final int page, String text) {
        final boolean typing = text.isEmpty();
        final TextView field;
        View body;
        if (typing) {
            // Nothing to pick from, so the quote is typed; kept short so it fits above the keyboard.
            EditText edit = new EditText(this);
            edit.setHint("Type or paste the quote here");
            edit.setMaxLines(5);
            field = edit;
            body = edit;
        } else {
            // Text that can be selected but not typed into, so the keyboard never opens.
            field = new TextView(this);
            field.setText(text);
            field.setTextColor(Ui.INK);
            field.setLineSpacing(Ui.dp(this, 3), 1f);
            field.setTextIsSelectable(true);
            final int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.5f);
            ScrollView scroll = new ScrollView(this) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
                }
            };
            scroll.addView(field);
            body = scroll;
        }
        field.setTextSize(15);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 18));

        // The buttons sit at the top so neither a long page nor the keyboard can hide them.
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        TextView save = Ui.pill(this, "Save quote", true);
        TextView share = Ui.pill(this, "Share", false);
        TextView cancel = Ui.barButton(this, "Cancel", 13.5f, null);
        buttons.addView(save);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(this, 10);
        buttons.addView(share, gap);
        buttons.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        buttons.addView(cancel);
        box.addView(buttons);

        TextView hint = new TextView(this);
        hint.setTextSize(12.5f);
        hint.setTextColor(0x99B8B2A6);
        hint.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 10));
        hint.setText(typing
                ? "This page has no readable text (it is probably a scanned picture). "
                + "You can type the quote instead."
                : "Hold a word and drag to select the part you want, then tap Save quote. "
                + "With nothing selected, the whole page is saved.");
        box.addView(hint);
        box.addView(body);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Quote from page " + (page + 1))
                .setView(box)
                .create();
        View.OnClickListener done = new View.OnClickListener() {
            @Override public void onClick(View v) {
                String all = field.getText().toString();
                int a = Math.max(0, Math.min(field.getSelectionStart(), field.getSelectionEnd()));
                int b = Math.min(all.length(), Math.max(field.getSelectionStart(), field.getSelectionEnd()));
                String chosen = Quotes.clean(b > a ? all.substring(a, b) : all);
                if (chosen.isEmpty()) {
                    Ui.toast(PdfActivity.this, "There is no text to save");
                    return;
                }
                if (v == share) {
                    Quotes.share(PdfActivity.this, Quotes.shareText(book.title, book.author, chosen));
                } else if (Quotes.add(PdfActivity.this, book, chosen, "Page " + (page + 1))) {
                    Ui.toast(PdfActivity.this, "Quote saved");
                } else {
                    Ui.toast(PdfActivity.this, "This quote is already saved");
                }
                dialog.dismiss();
            }
        };
        save.setOnClickListener(done);
        share.setOnClickListener(done);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        // When the keyboard does open (typing), the dialog shrinks to stay above it.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onPause() {
        super.onPause();
        if (ink != null && ink.dirty) ink.save();
        if (book != null && renderer != null && list != null) {
            int page = currentPage();
            book.chapter = page;
            book.progress = pageCount <= 1 ? 1f : Math.min(1f, (page + 1) / (float) pageCount);
            if (!paged && list.getChildCount() > 0 && list.getLastVisiblePosition() >= pageCount - 1) {
                book.progress = 1f;
            }
            if (startPage >= 0 && page != startPage) {
                book.posTime = System.currentTimeMillis();
                startPage = page;
            }
            lib.save();
            History.record(this, book, "Page " + (page + 1) + " of " + pageCount);
        }
        stopSyncWatch();
    }

    // ------------------------------------------------------------ other devices

    private final Runnable synced = new Runnable() {
        @Override public void run() {
            checkPending();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (book == null) return;
        Sync.openBookId = book.id;
        Sync.listen(synced);
        Sync.request(this);
        checkPending();
    }

    private void stopSyncWatch() {
        if (book == null) return;
        if (book.id.equals(Sync.openBookId)) Sync.openBookId = null;
        Sync.unlisten(synced);
        Sync.request(this);
    }

    /** Offers the page reached on another device, or simply continues there if nothing was read here yet. */
    private void checkPending() {
        if (renderer == null || closed || askingPending || book.pendingTime <= book.posTime) return;
        final int target = Math.max(0, Math.min(pageCount - 1, book.pendingChapter));
        int here = currentPage();
        if (target == here) {
            book.pendingTime = 0;
            lib.save();
            return;
        }
        String from = book.pendingDevice.isEmpty() ? "your other device" : "your " + book.pendingDevice;
        if (here == startPage && target > here) {
            acceptPending(target);
            Ui.toast(this, "Continued from " + from + " \u2014 page " + (target + 1));
            return;
        }
        askingPending = true;
        new AlertDialog.Builder(this)
                .setTitle("Continue where you left off?")
                .setMessage("On " + from + " you were on page " + (target + 1) + " of " + pageCount
                        + ". Here you're on page " + (here + 1) + ".")
                .setPositiveButton("Go there", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        acceptPending(target);
                    }
                })
                .setNegativeButton("Stay here", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        book.pendingTime = 0;
                        book.posTime = System.currentTimeMillis();
                        lib.save();
                        Sync.request(PdfActivity.this);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        askingPending = false;
                    }
                })
                .show();
    }

    private void acceptPending(int page) {
        book.chapter = page;
        book.progress = book.pendingProgress;
        book.posTime = book.pendingTime;
        book.pendingTime = 0;
        lib.save();
        startPage = page;
        goTo(page);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        closed = true;
        final PdfRenderer r = renderer;
        final PdfText text = pdfText;
        if (r != null) {
            exec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        r.close();
                    } catch (Exception ignored) {
                    }
                    if (text != null) text.close();
                }
            });
        }
        exec.shutdown();
    }
}
