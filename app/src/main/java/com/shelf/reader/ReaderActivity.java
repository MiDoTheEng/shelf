package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.shelf.reader.core.BookContent;
import com.shelf.reader.core.EpubBook;
import com.shelf.reader.core.MobiBook;
import com.shelf.reader.core.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Reader for EPUB and Kindle (MOBI/AZW3) books: one section at a time in a WebView. */
public class ReaderActivity extends Activity {
    private static final String HOST = "shelf.book";
    private static final String[] THEMES = {"dark", "sepia", "light"};

    private Library lib;
    private Book book;
    private SharedPreferences prefs;
    private BookContent content;
    private Ink ink;
    private String readerJs = "";
    private long[] weights = new long[0];
    private long totalWeight = 1;
    private int chapter;
    private float pendingOffset = -1f;
    private volatile float fraction;
    private volatile boolean selecting;
    private int pageInChapter;
    private int pagesInChapter;
    private boolean destroyed;
    /** Where this reading session started, to tell whether the reader actually moved. */
    private int startChapter = -1;
    private float startOffset;
    private boolean askingPending;

    private FrameLayout root;
    private WebView web;
    private View topBar;
    private View bottomBar;
    private View quoteBar;
    private TextView label;
    private TextView star;
    private TextView loading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        prefs = getSharedPreferences("shelf", MODE_PRIVATE);
        String id = getIntent().getStringExtra("id");
        book = id == null ? null : lib.find(id);
        if (book == null) {
            finish();
            return;
        }
        ink = Ink.load(this, book.id);
        try {
            readerJs = new String(Util.readAll(getAssets().open("reader.js")), StandardCharsets.UTF_8);
        } catch (Exception e) {
            readerJs = "";
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(background());

        web = new QuietWebView(this);
        web.setBackgroundColor(background());
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new TextView(this);
        loading.setText("Opening…");
        loading.setTextSize(15);
        loading.setTextColor(0xFF8C8778);
        root.addView(loading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        LinearLayout top = Ui.bar(this);
        top.addView(Ui.barButton(this, "‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        }));
        top.addView(Ui.barTitle(this, book.title), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        star = Ui.barButton(this, "☆", 21, new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleBookmark();
            }
        });
        star.setTextColor(BookView.GOLD);
        star.setContentDescription("Bookmark");
        top.addView(star);
        top.addView(Ui.barButton(this, "Aa", 16, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showTextSettings();
            }
        }));
        TextView more = Ui.barButton(this, "⋮", 22, new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMenu();
            }
        });
        more.setContentDescription("More");
        top.addView(more);
        topBar = top;
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.bar(this);
        bottom.addView(Ui.barButton(this, "‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                go(chapter - 1, 0f);
            }
        }));
        label = new TextView(this);
        label.setTextSize(13);
        label.setTextColor(Ui.INK);
        label.setGravity(Gravity.CENTER);
        bottom.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(Ui.barButton(this, "›", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                go(chapter + 1, 0f);
            }
        }));
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // The small menu that appears while text is selected.
        LinearLayout quote = Ui.selectionMenu(this, new Runnable() {
            @Override public void run() {
                grab(0);
            }
        }, new Runnable() {
            @Override public void run() {
                grab(2);
            }
        }, new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.__shelf&&window.__shelf.mark()", null);
            }
        }, new Runnable() {
            @Override public void run() {
                grab(1);
            }
        });
        quote.setVisibility(View.GONE);
        quoteBar = quote;
        root.addView(quote, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT));

        setContentView(root);

        final File file = new File(book.file);
        final boolean epub = book.format.equals("epub");
        new Thread(new Runnable() {
            @Override public void run() {
                BookContent opened = null;
                String error = null;
                try {
                    opened = epub ? new EpubBook(file) : new MobiBook(file);
                } catch (Exception | OutOfMemoryError e) {
                    error = e.getMessage() == null ? "This book could not be opened" : e.getMessage();
                }
                final BookContent result = opened;
                final String message = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed) {
                            closeQuietly(result);
                        } else if (result == null) {
                            Ui.toast(ReaderActivity.this, message);
                            finish();
                        } else {
                            start(result);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * A WebView that keeps text selection (with its handles) but hides Android's own
     * Copy / Select all bar, so the app's menu beside the text is the only one.
     */
    private static final class QuietWebView extends WebView {
        QuietWebView(android.content.Context c) {
            super(c);
        }

        @Override public ActionMode startActionMode(ActionMode.Callback callback) {
            return super.startActionMode(new Quiet(callback));
        }

        @Override public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return super.startActionMode(new Quiet(callback), type);
        }
    }

    private static final class Quiet extends ActionMode.Callback2 {
        private final ActionMode.Callback inner;

        Quiet(ActionMode.Callback inner) {
            this.inner = inner;
        }

        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            boolean ok = inner.onCreateActionMode(mode, menu);
            menu.clear();
            return ok;
        }

        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean ok = inner.onPrepareActionMode(mode, menu);
            menu.clear();
            return ok;
        }

        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return inner.onActionItemClicked(mode, item);
        }

        @Override public void onDestroyActionMode(ActionMode mode) {
            inner.onDestroyActionMode(mode);
        }

        @Override public void onGetContentRect(ActionMode mode, View view, android.graphics.Rect outRect) {
            if (inner instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) inner).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }

    private static void closeQuietly(BookContent c) {
        try {
            if (c != null) c.close();
        } catch (Exception ignored) {
        }
    }

    private void start(BookContent opened) {
        content = opened;
        int n = content.chapterCount();
        weights = new long[n];
        totalWeight = 0;
        for (int i = 0; i < n; i++) {
            weights[i] = Math.max(1, content.chapterWeight(i));
            totalWeight += weights[i];
        }
        chapter = Math.max(0, Math.min(n - 1, book.chapter));
        fraction = Math.max(0f, Math.min(1f, book.offset));
        pendingOffset = fraction;
        startChapter = chapter;
        startOffset = fraction;
        loading.setVisibility(View.GONE);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(prefs.getInt("textZoom", 110));
        web.addJavascriptInterface(new Bridge(), "Shelf");
        web.setWebViewClient(new Client());

        final GestureDetector flings = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null || paged()) return false;
                float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > Ui.dp(ReaderActivity.this, 110) && Math.abs(dx) > 2.5f * Math.abs(dy)
                        && Math.abs(vx) > 700) {
                    go(dx < 0 ? chapter + 1 : chapter - 1, 0f);
                    return true;
                }
                return false;
            }
        });
        web.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean multi;

            @Override public boolean onTouch(View v, MotionEvent e) {
                flings.onTouchEvent(e);
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = e.getX();
                    downY = e.getY();
                    multi = false;
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    multi = true;
                } else if (action == MotionEvent.ACTION_UP && !multi && !selecting && paged()) {
                    float dx = e.getX() - downX, dy = e.getY() - downY;
                    float min = Ui.dp(ReaderActivity.this, 52);
                    String mode = Ui.mode(prefs);
                    int delta = 0;
                    if (mode.equals("up")) {
                        if (Math.abs(dy) > min && Math.abs(dy) > 1.5f * Math.abs(dx)) delta = dy < 0 ? 1 : -1;
                    } else if (Math.abs(dx) > min && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                        delta = (dx < 0) == mode.equals("left") ? 1 : -1;
                    }
                    if (delta != 0) turnPage(delta, mode);
                }
                return false;
            }
        });
        loadChapter();
        autoHide(web, topBar, bottomBar);
        checkPending();
    }

    /** Leaves the bars up for a moment so they are discoverable, then gets out of the way. */
    static void autoHide(View host, final View... bars) {
        host.postDelayed(new Runnable() {
            @Override public void run() {
                for (View b : bars) b.setVisibility(View.GONE);
            }
        }, 3200);
    }

    private boolean paged() {
        return !Ui.mode(prefs).equals("scroll");
    }

    // --------------------------------------------------------------- navigation

    private void go(int target, float offset) {
        if (content == null) return;
        if (target < 0) {
            Ui.toast(this, "This is the beginning of the book");
            return;
        }
        if (target >= content.chapterCount()) {
            Ui.toast(this, "You've reached the end of the book");
            return;
        }
        chapter = target;
        fraction = offset;
        pendingOffset = offset;
        loadChapter();
    }

    // ------------------------------------------------------------ page turn

    private boolean turning;

    /**
     * Turns like paper: a picture of the page is laid over the book, the book jumps to the
     * next page underneath, and the picture curls away. Going back, the earlier page curls
     * in over the one you were on.
     */
    private void turnPage(final int delta, final String mode) {
        final String go = "window.__shelf?window.__shelf.go(" + delta + ",true):false";
        final android.graphics.Bitmap old = snapshot();
        if (turning || old == null) {
            web.evaluateJavascript(go, null);
            return;
        }
        turning = true;
        final CurlView sheet = new CurlView(old, mode);
        root.addView(sheet, root.indexOfChild(web) + 1, new FrameLayout.LayoutParams(web.getWidth(), web.getHeight()));
        sheet.setX(web.getX());
        sheet.setY(web.getY());
        web.evaluateJavascript(go, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String turned) {
                if (!"true".equals(turned)) {
                    // End of the chapter: the next one loads on its own.
                    finishTurn(sheet);
                    return;
                }
                if (delta > 0) {
                    sheet.run(0f, 1f);
                    return;
                }
                // Let the earlier page draw, then bring it in over the current one.
                web.postDelayed(new Runnable() {
                    @Override public void run() {
                        android.graphics.Bitmap now = snapshot();
                        if (now == null) {
                            finishTurn(sheet);
                            return;
                        }
                        sheet.under = old;
                        sheet.page = now;
                        sheet.run(1f, 0f);
                    }
                }, 90);
            }
        });
    }

    private android.graphics.Bitmap snapshot() {
        int w = web.getWidth(), h = web.getHeight();
        if (w == 0 || h == 0) return null;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            web.draw(new android.graphics.Canvas(b));
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void finishTurn(View sheet) {
        root.removeView(sheet);
        turning = false;
    }

    /** A picture of the page that curls over the book. */
    private final class CurlView extends View {
        android.graphics.Bitmap page, under;
        private final String mode;
        private final int paper;
        private float t;
        private final PageCurl curl = new PageCurl();
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        CurlView(android.graphics.Bitmap page, String mode) {
            super(ReaderActivity.this);
            this.page = page;
            this.mode = mode;
            // The back of the page takes the colour of the page's own background.
            paper = page.getPixel(Math.min(3, page.getWidth() - 1), Math.min(3, page.getHeight() - 1)) | 0xFF000000;
        }

        void run(float from, float to) {
            android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
            anim.setDuration(480);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                    t = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    finishTurn(CurlView.this);
                }
            });
            anim.start();
        }

        @Override protected void onDraw(android.graphics.Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            if (under != null) canvas.drawBitmap(under, null, rect, paint);
            curl.draw(canvas, page, rect, paint, t, mode, paper, true);
        }
    }

    private void loadChapter() {
        pageInChapter = 0;
        pagesInChapter = 0;
        quoteBar.setVisibility(View.GONE);
        selecting = false;
        // Only hide the page while it jumps to a saved spot; a section that starts at the top
        // just replaces the old one, without a dark flash in between.
        if (pendingOffset > 0.001f) web.setAlpha(0f);
        web.loadUrl("https://" + HOST + "/" + content.chapterPath(chapter));
        updateLabel();
        web.postDelayed(new Runnable() {
            @Override public void run() {
                if (!destroyed) web.setAlpha(1f);
            }
        }, 1500);
    }

    private float progress() {
        if (content == null || weights.length == 0) return book.progress;
        long before = 0;
        for (int i = 0; i < chapter && i < weights.length; i++) before += weights[i];
        float p = (before + weights[chapter] * fraction) / (float) totalWeight;
        return Math.max(0f, Math.min(1f, p));
    }

    private String sectionName(int i) {
        String t = content.chapterTitle(i);
        return t != null ? t : "Section " + (i + 1);
    }

    private void updateLabel() {
        if (content == null) return;
        String pages = pagesInChapter > 0 ? "page " + pageInChapter + " of " + pagesInChapter + "   ·   " : "";
        label.setText("Section " + (chapter + 1) + "/" + content.chapterCount() + "   ·   " + pages
                + Math.round(progress() * 100) + "%");
        star.setText(markHere() != null ? "★" : "☆");
    }

    private void toggleBars() {
        int v = topBar.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
        topBar.setVisibility(v);
        bottomBar.setVisibility(v);
    }

    // ---------------------------------------------------------------- bookmarks

    /** The bookmark on the page being shown, or null. */
    private Book.Mark markHere() {
        float tolerance = pagesInChapter > 1 ? 0.5f / (pagesInChapter - 1) : pagesInChapter == 1 ? 2f : 0.03f;
        for (Book.Mark m : book.marks) {
            if (m.chapter == chapter && Math.abs(m.offset - fraction) < tolerance) return m;
        }
        return null;
    }

    private void toggleBookmark() {
        if (content == null) return;
        Book.Mark here = markHere();
        if (here != null) {
            book.marks.remove(here);
            Ui.toast(this, "Bookmark removed");
        } else {
            Book.Mark m = new Book.Mark();
            m.chapter = chapter;
            m.offset = fraction;
            m.time = System.currentTimeMillis();
            m.label = sectionName(chapter)
                    + (pagesInChapter > 0 ? "  ·  page " + pageInChapter : "")
                    + "  ·  " + Math.round(progress() * 100) + "%";
            book.marks.add(m);
            book.sortMarks();
            Ui.toast(this, "Bookmark added");
        }
        book.marksTime = System.currentTimeMillis();
        lib.save();
        updateLabel();
    }

    // ------------------------------------------------------------------- quotes

    private void grab(int share) {
        web.evaluateJavascript("window.__shelf&&window.__shelf.grab(" + share + ")", null);
    }

    private void askRemoveHighlight(final int start) {
        new AlertDialog.Builder(this)
                .setMessage("Remove this highlight?")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        java.util.Iterator<int[]> it = ink.texts(chapter).iterator();
                        while (it.hasNext()) if (it.next()[0] == start) it.remove();
                        ink.save();
                        // Reload the section at the same place so the colour is gone.
                        pendingOffset = fraction;
                        loadChapter();
                    }
                })
                .show();
    }

    private void showMenu() {
        if (content == null) return;
        String[] items = {"Contents", "Bookmarks", "Quotes from this book", "Page turning…"};
        new AlertDialog.Builder(this)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            showContents();
                        } else if (which == 1) {
                            Ui.showMarks(ReaderActivity.this, lib, book, new Ui.MarkPick() {
                                @Override public void open(Book.Mark m) {
                                    go(m.chapter, m.offset);
                                }
                            }, new Runnable() {
                                @Override public void run() {
                                    updateLabel();
                                }
                            });
                        } else if (which == 2) {
                            Intent i = new Intent(ReaderActivity.this, QuotesActivity.class);
                            i.putExtra("book", book.id);
                            startActivity(i);
                        } else {
                            Ui.pickMode(ReaderActivity.this, prefs, new Runnable() {
                                @Override public void run() {
                                    inject(-1f);
                                }
                            });
                        }
                    }
                })
                .show();
    }

    private final class Bridge {
        @JavascriptInterface public void onPage(double f, final int page, final int pages) {
            fraction = (float) Math.max(0, Math.min(1, f));
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    pageInChapter = page;
                    pagesInChapter = pages;
                    updateLabel();
                }
            });
        }

        @JavascriptInterface public void tap() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) toggleBars();
                }
            });
        }

        @JavascriptInterface public void next() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) go(chapter + 1, 0f);
                }
            });
        }

        /** Swiped past the first or last page of this section. */
        @JavascriptInterface public void edge(final int delta) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) go(delta > 0 ? chapter + 1 : chapter - 1, delta > 0 ? 0f : 1f);
                }
            });
        }

        @JavascriptInterface public void selection(final boolean has) {
            selecting = has;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    // It is shown by selectionAt, once it is known where the text is.
                    if (!destroyed && !has) quoteBar.setVisibility(View.GONE);
                }
            });
        }

        /** The selected text was highlighted: remember its place in this section. */
        @JavascriptInterface public void mark(final int start, final int end) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed || end <= start) return;
                    ink.texts(chapter).add(new int[]{start, end});
                    ink.save();
                    quoteBar.setVisibility(View.GONE);
                }
            });
        }

        @JavascriptInterface public void markTap(final int start) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (!destroyed) askRemoveHighlight(start);
                }
            });
        }

        /** Where the selection is, in page pixels; the menu goes right beside it. */
        @JavascriptInterface public void selectionAt(final double l, final double t, final double r, final double b) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed || !selecting) return;
                    float k = getResources().getDisplayMetrics().density;
                    Ui.placeNear(quoteBar, root, (float) l * k + web.getLeft(), (float) t * k + web.getTop(),
                            (float) r * k + web.getLeft(), (float) b * k + web.getTop(), Ui.dp(ReaderActivity.this, 30));
                }
            });
        }

        @JavascriptInterface public void grab(final String text, final int share) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (destroyed || content == null) return;
                    quoteBar.setVisibility(View.GONE);
                    String t = Quotes.clean(text);
                    if (t.isEmpty()) {
                        Ui.toast(ReaderActivity.this, "Select some text first");
                    } else if (share == 2) {
                        Translate.show(ReaderActivity.this, t, book.title);
                    } else if (share == 1) {
                        Quotes.share(ReaderActivity.this, Quotes.shareText(book.title, book.author, t));
                    } else if (Quotes.add(ReaderActivity.this, book, t, sectionName(chapter))) {
                        Ui.toast(ReaderActivity.this, "Quote saved");
                    } else {
                        Ui.toast(ReaderActivity.this, "This quote is already saved");
                    }
                }
            });
        }
    }

    private final class Client extends WebViewClient {
        private WebResourceResponse missing() {
            WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(new byte[0]));
            r.setStatusCodeAndReasonPhrase(404, "Not Found");
            return r;
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                Uri u = request.getUrl();
                BookContent c = content;
                if (c == null || u == null || !HOST.equals(u.getHost()) || u.getPath() == null) return missing();
                String path = u.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                BookContent.Res res = c.open(path);
                if (res == null) return missing();
                return new WebResourceResponse(res.mime, res.charset, res.in);
            } catch (Throwable t) {
                return missing();
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (u == null) return true;
            if (HOST.equals(u.getHost())) {
                String path = u.getPath() == null ? "" : u.getPath();
                if (path.startsWith("/")) path = path.substring(1);
                int idx = content == null ? -1 : content.chapterForPath(path);
                if (idx < 0) return true;
                chapter = idx;
                fraction = 0f;
                pendingOffset = 0f;
                pageInChapter = 0;
                pagesInChapter = 0;
                web.setAlpha(0f);
                updateLabel();
                return false;
            }
            String scheme = u.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) {
                }
            }
            return true;
        }

        @Override public void onPageFinished(WebView view, String url) {
            float offset = pendingOffset;
            pendingOffset = -1f;
            inject(offset);
            view.animate().alpha(1f).setDuration(140).start();
        }
    }

    // -------------------------------------------------------------- appearance

    private String theme() {
        return prefs.getString("theme", "dark");
    }

    private int background() {
        String t = theme();
        return t.equals("sepia") ? 0xFFF4ECD8 : t.equals("light") ? Color.WHITE : 0xFF111114;
    }

    /**
     * Applies the theme and the page-turning mode, and puts the reader at the given
     * position (0..1 inside the section); a negative offset keeps the current place.
     */
    private void inject(float offset) {
        if (content == null || web == null || destroyed) return;
        String t = theme();
        String bg, fg, link;
        if (t.equals("sepia")) {
            bg = "#F4ECD8"; fg = "#4A3B2A"; link = "#8A5A1E";
        } else if (t.equals("light")) {
            bg = "#FFFFFF"; fg = "#1B1B1B"; link = "#8A5A1E";
        } else {
            bg = "#111114"; fg = "#D9D5CC"; link = "#D4AF6A";
        }
        try {
            JSONObject c = new JSONObject();
            c.put("bg", bg);
            c.put("fg", fg);
            c.put("link", link);
            c.put("mode", Ui.mode(prefs));
            c.put("f", (double) offset);
            c.put("hasNext", chapter + 1 < content.chapterCount());
            JSONArray marks = new JSONArray();
            for (int[] range : ink.texts(chapter)) {
                JSONArray one = new JSONArray();
                one.put(range[0]);
                one.put(range[1]);
                marks.put(one);
            }
            c.put("marks", marks);
            web.evaluateJavascript(readerJs + "\ntry{window.__shelfSetup(" + c.toString() + ");}catch(e){}", null);
        } catch (Exception ignored) {
        }
    }

    private void applyTheme() {
        root.setBackgroundColor(background());
        web.setBackgroundColor(background());
        inject(-1f);
    }

    private void showTextSettings() {
        if (content == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 6));

        LinearLayout themes = new LinearLayout(this);
        final ArrayList<TextView> chips = new ArrayList<>();
        final String[] names = {"Dark", "Sepia", "Light"};
        for (int i = 0; i < THEMES.length; i++) {
            final String key = THEMES[i];
            final TextView chip = chip(names[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putString("theme", key).apply();
                    applyTheme();
                    for (int k = 0; k < chips.size(); k++) mark(chips.get(k), THEMES[k].equals(key));
                }
            });
            mark(chip, key.equals(theme()));
            chips.add(chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            themes.addView(chip, lp);
        }
        box.addView(themes);

        LinearLayout sizes = new LinearLayout(this);
        sizes.setGravity(Gravity.CENTER_VERTICAL);
        final TextView value = new TextView(this);
        value.setTextColor(Ui.INK);
        value.setTextSize(14);
        value.setGravity(Gravity.CENTER);
        value.setText("Text size " + prefs.getInt("textZoom", 110) + "%");
        View.OnClickListener change = new View.OnClickListener() {
            @Override public void onClick(View v) {
                int step = "A+".contentEquals(((TextView) v).getText()) ? 10 : -10;
                int z = Math.max(70, Math.min(260, prefs.getInt("textZoom", 110) + step));
                prefs.edit().putInt("textZoom", z).apply();
                web.getSettings().setTextZoom(z);
                value.setText("Text size " + z + "%");
                // Bigger or smaller text changes how many pages the section has.
                web.postDelayed(new Runnable() {
                    @Override public void run() {
                        inject(-1f);
                    }
                }, 200);
            }
        };
        TextView minus = chip("A−");
        TextView plus = chip("A+");
        minus.setOnClickListener(change);
        plus.setOnClickListener(change);
        mark(minus, false);
        mark(plus, false);
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT);
        side.setMargins(Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4), 0);
        sizes.addView(minus, side);
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mid.topMargin = Ui.dp(this, 16);
        sizes.addView(value, mid);
        LinearLayout.LayoutParams side2 = new LinearLayout.LayoutParams(Ui.dp(this, 64), ViewGroup.LayoutParams.WRAP_CONTENT);
        side2.setMargins(Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4), 0);
        sizes.addView(plus, side2);
        box.addView(sizes);

        new AlertDialog.Builder(this).setTitle("Reading").setView(box).setPositiveButton("Done", null).show();
    }

    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        return t;
    }

    private void mark(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(this, 10));
        bg.setColor(selected ? BookView.GOLD : 0x00000000);
        bg.setStroke(Ui.dp(this, 1), selected ? BookView.GOLD : 0x66D4AF6A);
        chip.setBackground(bg);
        chip.setTextColor(selected ? 0xFF17130B : Ui.INK);
    }

    private void showContents() {
        if (content == null) return;
        int n = content.chapterCount();
        int titled = 0;
        for (int i = 0; i < n; i++) if (content.chapterTitle(i) != null) titled++;
        final ArrayList<Integer> targets = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String t = content.chapterTitle(i);
            if (titled >= 2 && t == null) continue;
            targets.add(i);
            String name = t != null ? t : "Section " + (i + 1);
            names.add(i == chapter ? "▸ " + name : name);
        }
        new AlertDialog.Builder(this)
                .setTitle("Contents")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        go(targets.get(which), 0f);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onPause() {
        super.onPause();
        if (book != null && content != null) {
            book.chapter = chapter;
            book.offset = fraction;
            book.progress = progress();
            if (startChapter >= 0 && (chapter != startChapter || Math.abs(fraction - startOffset) > 0.002f)) {
                book.posTime = System.currentTimeMillis();
                startChapter = chapter;
                startOffset = fraction;
            }
            lib.save();
            History.record(this, book, "Section " + (chapter + 1) + " of " + content.chapterCount());
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

    /** Offers the position reached on another device, or simply continues there if nothing was read here yet. */
    private void checkPending() {
        if (content == null || askingPending || destroyed || book.pendingTime <= book.posTime) return;
        final int ch = Math.max(0, Math.min(content.chapterCount() - 1, book.pendingChapter));
        final float off = Math.max(0f, Math.min(1f, book.pendingOffset));
        if (ch == chapter && Math.abs(off - fraction) < 0.002f) {
            book.pendingTime = 0;
            lib.save();
            return;
        }
        String from = book.pendingDevice.isEmpty() ? "your other device" : "your " + book.pendingDevice;
        boolean untouched = chapter == startChapter && Math.abs(fraction - startOffset) < 0.002f;
        if (untouched && book.pendingProgress >= progress() - 0.0005f) {
            acceptPending(ch, off);
            Ui.toast(this, "Continued from " + from);
            return;
        }
        askingPending = true;
        new AlertDialog.Builder(this)
                .setTitle("Continue where you left off?")
                .setMessage("On " + from + " you were at " + Math.round(book.pendingProgress * 100) + "% ("
                        + sectionName(ch) + "). Here you're at " + Math.round(progress() * 100) + "%.")
                .setPositiveButton("Go there", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        acceptPending(ch, off);
                    }
                })
                .setNegativeButton("Stay here", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        book.pendingTime = 0;
                        book.posTime = System.currentTimeMillis();
                        lib.save();
                        Sync.request(ReaderActivity.this);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface d) {
                        askingPending = false;
                    }
                })
                .show();
    }

    private void acceptPending(int ch, float off) {
        book.chapter = ch;
        book.offset = off;
        book.progress = book.pendingProgress;
        book.posTime = book.pendingTime;
        book.pendingTime = 0;
        lib.save();
        startChapter = ch;
        startOffset = off;
        go(ch, off);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (web != null) {
            if (root != null) root.removeView(web);
            web.destroy();
        }
        final BookContent c = content;
        content = null;
        closeQuietly(c);
    }
}
