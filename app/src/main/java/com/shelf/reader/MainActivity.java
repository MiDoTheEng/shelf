package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The shelves: "Currently reading" on top, the rest of the library underneath. */
public class MainActivity extends Activity {
    private static final int PICK = 41;

    private static final class Row {
        String header;
        Book[] books;
        String hint;
    }

    private Library lib;
    private final ArrayList<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private TextView subtitle;
    /** What the search box holds, in lower case; empty shows the whole Library. */
    private String query = "";
    private FrameLayout shelvesRoot;
    private ListView shelves;
    /** The book flying between shelves: its place stays empty until it lands. */
    private Book flying;
    private int cols;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cols = Math.max(3, Math.min(8, (int) (dm.widthPixels / dm.density / 118f)));

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15151C, 0xFF09090C}));

        shelvesRoot = root;
        ListView list = new ListView(this);
        shelves = list;
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setVerticalScrollBarEnabled(false);

        // Title with the sync button, then a row of tabs: Quotes, Words, Progress.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 30), Ui.dp(this, 18), Ui.dp(this, 6));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Shelf");
        title.setTextSize(36);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setTextColor(0xFFF2ECDD);
        names.addView(title);
        TextView motto = new TextView(this);
        motto.setText("Reading Saved Me");
        motto.setTextSize(15);
        motto.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        motto.setTextColor(0xFFD4AF6A);
        motto.setLetterSpacing(0.04f);
        motto.setPadding(0, 0, 0, Ui.dp(this, 4));
        names.addView(motto);
        subtitle = new TextView(this);
        subtitle.setTextSize(13);
        subtitle.setTextColor(0x99B8B2A6);
        names.addView(subtitle);
        top.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sync = Ui.pill(this, "\u21BB", false);
        sync.setContentDescription("Sync");
        sync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SyncSetup.show(MainActivity.this, new Runnable() {
                    @Override public void run() {
                        rebuild();
                    }
                });
            }
        });
        top.addView(sync);
        header.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabNames = {"\u201C  Quotes", "\u0639  Words", "\u25A4  Progress"};
        final Class<?>[] screens = {QuotesActivity.class, WordsActivity.class, ProgressActivity.class};
        for (int i = 0; i < tabNames.length; i++) {
            final Class<?> screen = screens[i];
            TextView tab = Ui.pill(this, tabNames[i], false);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(MainActivity.this, screen));
                }
            });
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.rightMargin = Ui.dp(this, 8);
            tlp.topMargin = Ui.dp(this, 14);
            tabs.addView(tab, tlp);
        }
        header.addView(tabs);

        // Search the Library by book name or author as you type.
        final EditText search = new EditText(this);
        search.setHint("\u2315  Search your library");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(0xFFF2ECDD);
        search.setHintTextColor(0x77B8B2A6);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x14FFFFFF);
        box.setStroke(Math.max(1, Ui.dp(this, 1)), 0x40D4AF6A);
        box.setCornerRadius(Ui.dp(this, 22));
        search.setBackground(box);
        search.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence q, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence q, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable q) {
                query = q.toString().trim().toLowerCase(java.util.Locale.ROOT);
                rebuild();
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int action, android.view.KeyEvent e) {
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
                return true;
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 14);
        slp.rightMargin = Ui.dp(this, 4);
        header.addView(search, slp);
        list.addHeaderView(header, null, false);
        // Lets the search box keep the keyboard while the shelves below change.
        list.setItemsCanFocus(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        View space = new View(this);
        space.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 100)));
        list.addFooterView(space, null, false);

        adapter = new RowAdapter();
        list.setAdapter(adapter);
        root.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        TextView add = new TextView(this);
        add.setText("+");
        add.setTextSize(30);
        add.setIncludeFontPadding(false);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(0xFF17130B);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(BookView.GOLD);
        add.setBackground(circle);
        add.setElevation(Ui.dp(this, 10));
        add.setContentDescription("Add books");
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pick();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60),
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Ui.dp(this, 22), Ui.dp(this, 26));
        root.addView(add, lp);

        setContentView(root);
        if (state == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private final Runnable synced = new Runnable() {
        @Override public void run() {
            if (!isDestroyed()) rebuild();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        closeOpening();
        rebuild();
        Sync.listen(synced);
        Sync.request(this);
    }

    @Override protected void onPause() {
        super.onPause();
        Sync.unlisten(synced);
    }

    @Override protected void onStop() {
        super.onStop();
        closeOpening();
    }

    private void handleIntent(Intent intent) {
        String openId = intent == null ? null : intent.getStringExtra("open");
        if (openId != null) {
            intent.removeExtra("open");
            Book b = lib.find(openId);
            if (b != null) open(b);
            return;
        }
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(intent.getData());
            importAll(uris, true);
        }
    }

    // ------------------------------------------------------------------ shelves

    private void rebuild() {
        List<Book> reading = new ArrayList<>();
        List<Book> library = new ArrayList<>();
        for (Book b : lib.all()) (b.reading ? reading : library).add(b);
        Collections.sort(reading, new Comparator<Book>() {
            @Override public int compare(Book a, Book b) {
                return Long.compare(b.opened, a.opened);
            }
        });
        Collections.sort(library, new Comparator<Book>() {
            @Override public int compare(Book a, Book b) {
                return Long.compare(b.added, a.added);
            }
        });

        rows.clear();
        addShelf("Currently reading", reading, "Books you open will wait for you here");
        int total = reading.size() + library.size();
        String libraryHint = reading.isEmpty()
                ? "Tap + to add PDF, EPUB or Kindle books"
                : "Every book is on your reading shelf";
        if (!query.isEmpty()) {
            List<Book> found = new ArrayList<>();
            for (Book b : library) if (matches(b, query)) found.add(b);
            library = found;
            libraryHint = "No books in your library match \u201C" + query + "\u201D";
        }
        addShelf("Library", library, libraryHint);
        subtitle.setText(total == 0 ? "Your shelves are empty" : total == 1 ? "1 book" : total + " books");
        adapter.notifyDataSetChanged();
    }

    private static boolean matches(Book b, String q) {
        String title = b.title == null ? "" : b.title.toLowerCase(java.util.Locale.ROOT);
        String author = b.author == null ? "" : b.author.toLowerCase(java.util.Locale.ROOT);
        for (String word : q.split("\\s+")) {
            if (!title.contains(word) && !author.contains(word)) return false;
        }
        return true;
    }

    private void addShelf(String name, List<Book> books, String emptyHint) {
        Row head = new Row();
        head.header = name.toUpperCase() + (books.isEmpty() ? "" : "   ·   " + books.size());
        rows.add(head);
        if (books.isEmpty()) {
            Row r = new Row();
            r.books = new Book[0];
            r.hint = emptyHint;
            rows.add(r);
            return;
        }
        for (int i = 0; i < books.size(); i += cols) {
            Row r = new Row();
            List<Book> part = books.subList(i, Math.min(books.size(), i + cols));
            r.books = part.toArray(new Book[0]);
            rows.add(r);
        }
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return rows.get(position).header != null ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.header != null) {
                TextView t = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                t.setText(row.header);
                t.setTextSize(11.5f);
                t.setLetterSpacing(0.16f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(0xCCD4AF6A);
                t.setPadding(Ui.dp(MainActivity.this, 22), Ui.dp(MainActivity.this, 22),
                        Ui.dp(MainActivity.this, 22), Ui.dp(MainActivity.this, 2));
                return t;
            }
            ShelfRow shelf = convertView instanceof ShelfRow ? (ShelfRow) convertView
                    : new ShelfRow(MainActivity.this, cols);
            shelf.setHint(row.hint);
            for (int i = 0; i < shelf.slots.length; i++) {
                final Book b = i < row.books.length ? row.books[i] : null;
                BookView v = shelf.slots[i];
                v.setBook(b);
                v.setAlpha(b != null && b == flying ? 0f : 1f);
                if (b == null) {
                    v.setOnClickListener(null);
                    v.setOnLongClickListener(null);
                    v.setClickable(false);
                    v.setLongClickable(false);
                } else {
                    v.setContentDescription(b.title);
                    final BookView bv = v;
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            // The book comes off the shelf and opens like a real one.
                            openLikeABook(b, bv);
                        }
                    });
                    v.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View view) {
                            menu(b, bv);
                            return true;
                        }
                    });
                }
            }
            shelf.invalidate();
            return shelf;
        }
    }

    // ------------------------------------------------------------------ actions

    private void open(final Book b) {
        Intent i = readerFor(b);
        if (i != null) startActivity(i);
    }

    /** Gets a book ready to read and says which screen reads it, or null if it can't open yet. */
    private Intent readerFor(Book b) {
        if (b.onlineOnly()) {
            fetch(b, true);
            return null;
        }
        if (!new File(b.file).exists()) {
            Ui.toast(this, "This book's file is missing");
            return null;
        }
        if (!b.reading) {
            b.reading = true;
            b.shelfTime = System.currentTimeMillis();
        }
        b.opened = System.currentTimeMillis();
        lib.save();
        Intent i = new Intent(this, b.format.equals("pdf") ? PdfActivity.class : ReaderActivity.class);
        i.putExtra("id", b.id);
        return i;
    }

    // ------------------------------------------------------------ opening a book

    /** The picture of the book lifted off the shelf while it opens, until the reader is up. */
    private FrameLayout opening;

    /**
     * Opens a book like a real one. It comes off the shelf to the middle of the screen, its
     * cover swings open on the spine and shows the first page, and that page grows to fill the
     * screen as the book appears. The spine is on the right for right-to-left reading.
     */
    private void openLikeABook(final Book b, BookView from) {
        if (opening != null) return;
        if (b.onlineOnly() || !new File(b.file).exists() || from.getWidth() == 0 || !from.isShown()) {
            open(b);
            return;
        }
        final float d = getResources().getDisplayMetrics().density;
        android.graphics.Bitmap cover;
        int[] at = inRoot(from);
        final float cw = from.getWidth() - 14 * d, ch = from.getHeight() - 8 * d;
        try {
            from.stand();
            android.graphics.Bitmap whole = android.graphics.Bitmap.createBitmap(from.getWidth(), from.getHeight(),
                    android.graphics.Bitmap.Config.ARGB_8888);
            from.draw(new android.graphics.Canvas(whole));
            cover = android.graphics.Bitmap.createBitmap(whole, Math.round(7 * d), Math.round(8 * d),
                    Math.max(1, Math.round(cw)), Math.max(1, Math.round(ch)));
        } catch (Throwable t) {
            open(b);
            return;
        }
        final float sx = at[0] + 7 * d, sy = at[1] + 8 * d;
        final boolean rtl = "right".equals(Ui.mode(getSharedPreferences("shelf", MODE_PRIVATE)));

        final FrameLayout stage = new FrameLayout(this);
        stage.setElevation(30 * d);
        stage.setClickable(true);
        final View dim = new View(this);
        dim.setBackgroundColor(0xFF0E0E12);
        dim.setAlpha(0f);
        stage.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // The first page, waiting under the cover: paper, darker by the spine.
        final View page = new View(this);
        GradientDrawable paper = new GradientDrawable(rtl ? GradientDrawable.Orientation.RIGHT_LEFT
                : GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFCFC6B4, 0xFFEDE6D6, 0xFFF6F1E6, 0xFFF3EDE0});
        paper.setCornerRadius(2 * d);
        page.setBackground(paper);
        stage.addView(page, new FrameLayout.LayoutParams(Math.round(cw), Math.round(ch)));

        final android.widget.ImageView front = new android.widget.ImageView(this);
        front.setImageBitmap(cover);
        front.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        front.setCameraDistance(9000 * d);
        stage.addView(front, new FrameLayout.LayoutParams(Math.round(cw), Math.round(ch)));

        shelvesRoot.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        opening = stage;
        from.setAlpha(0f);
        final BookView shelfSlot = from;

        final float W = shelvesRoot.getWidth(), H = shelvesRoot.getHeight();
        // Where the closed book rests in the middle of the screen.
        final float mid = Math.min(0.74f * H / ch, 0.72f * W / cw);
        final float mx = (W - cw * mid) / 2f + (rtl ? -1 : 1) * 0.12f * cw * mid;
        final float my = (H - ch * mid) / 2f;
        for (View v : new View[]{page, front}) {
            v.setPivotX(rtl ? cw : 0);
            v.setPivotY(0);
            v.setX(sx);
            v.setY(sy);
        }

        // 1. Off the shelf to the middle of the screen.
        final android.animation.ValueAnimator lift = android.animation.ValueAnimator.ofFloat(0f, 1f);
        lift.setDuration(340);
        lift.setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f));
        lift.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                float scale = 1f + (mid - 1f) * t;
                float x = sx + (mx - sx) * t, y = sy + (my - sy) * t;
                for (View v : new View[]{page, front}) {
                    // The pivot is on the spine side, so place the left edge by hand.
                    v.setX(rtl ? x + cw * scale - cw : x);
                    v.setY(y);
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                }
                front.setRotationY((rtl ? 1 : -1) * 8f * (float) Math.sin(Math.PI * t));
                dim.setAlpha(0.9f * t);
            }
        });

        // 2. The cover swings open on the spine, showing its inside as it lies back.
        final android.animation.ValueAnimator swing = android.animation.ValueAnimator.ofFloat(0f, 1f);
        swing.setDuration(520);
        swing.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        swing.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            boolean inside;

            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                float angle = 165f * t;
                front.setRotationY((rtl ? 1 : -1) * angle);
                if (!inside && angle > 90f) {
                    // Past upright, the inside of the cover faces you.
                    inside = true;
                    front.setImageDrawable(null);
                    front.setBackgroundColor(0xFF3A2E26);
                }
                // Light falls across the cover as it turns.
                int shade = Math.round(255 * (1f - 0.35f * (float) Math.sin(Math.PI * t)));
                front.setColorFilter(android.graphics.Color.rgb(shade, shade, shade), android.graphics.PorterDuff.Mode.MULTIPLY);
            }
        });

        // 3. The first page fills the screen, and the book is there.
        final android.animation.ValueAnimator grow = android.animation.ValueAnimator.ofFloat(0f, 1f);
        grow.setDuration(300);
        grow.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
        grow.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                float scaleX = mid + (W / cw - mid) * t, scaleY = mid + (H / ch - mid) * t;
                float x = mx + (0 - mx) * t, y = my + (0 - my) * t;
                page.setX(rtl ? x + cw * scaleX - cw : x);
                page.setY(y);
                page.setScaleX(scaleX);
                page.setScaleY(scaleY);
                front.setAlpha(1f - t);
            }
        });

        lift.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                swing.start();
            }
        });
        swing.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                grow.start();
            }
        });
        grow.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                Intent i = readerFor(b);
                if (i == null) {
                    closeOpening();
                    return;
                }
                startActivity(i);
                overridePendingTransition(android.R.anim.fade_in, 0);
                shelfSlot.setAlpha(1f);
            }
        });
        lift.start();
    }

    /** Takes the opened-book picture away once the reader covers it, or when back on the shelves. */
    private void closeOpening() {
        if (opening == null) return;
        shelvesRoot.removeView(opening);
        opening = null;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void menu(final Book b, final BookView from) {
        final String move = b.reading ? "Move back to Library" : "Move to Currently reading";
        String[] items = {b.onlineOnly() ? "Download and open" : "Open", "Rename", "Change cover", move, "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(b.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            open(b);
                        } else if (which == 1) {
                            rename(b);
                        } else if (which == 2) {
                            CoverPicker.show(MainActivity.this, b, new Runnable() {
                                @Override public void run() {
                                    rebuild();
                                }
                            });
                        } else if (which == 3) {
                            moveBook(b, from);
                        } else {
                            confirmDelete(b);
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ moving between shelves

    /**
     * Moves a book between Currently reading and Library. The book lifts off its shelf, flies
     * in an arc to its new place and lands with a bounce. Back in the Library it also leaves
     * Progress (on every device); reading it again brings it back there.
     */
    private void moveBook(final Book b, BookView from) {
        b.reading = !b.reading;
        b.shelfTime = System.currentTimeMillis();
        if (b.reading) b.opened = System.currentTimeMillis();
        lib.save();
        if (!b.reading) History.remove(this, History.keyOf(b));

        android.graphics.Bitmap pic = null;
        int[] start = new int[2];
        if (from != null && from.book() == b && from.getWidth() > 0 && from.isShown()) {
            try {
                pic = android.graphics.Bitmap.createBitmap(from.getWidth(), from.getHeight(),
                        android.graphics.Bitmap.Config.ARGB_8888);
                from.stand();
                from.draw(new android.graphics.Canvas(pic));
                start = inRoot(from);
            } catch (Throwable t) {
                pic = null;
            }
        }
        if (pic == null) {
            rebuild();
            return;
        }
        final android.widget.ImageView bird = new android.widget.ImageView(this);
        bird.setImageBitmap(pic);
        bird.setElevation(Ui.dp(this, 12));
        shelvesRoot.addView(bird, new FrameLayout.LayoutParams(from.getWidth(), from.getHeight()));
        bird.setX(start[0]);
        bird.setY(start[1]);
        flying = b;
        rebuild();
        final float sx = start[0], sy = start[1];
        shelves.post(new Runnable() {
            int tries;

            @Override public void run() {
                BookView target = findSlot(b);
                if (target == null && tries++ == 0) {
                    // Its new place is off screen: bring that shelf into view first.
                    int row = rowOf(b);
                    if (row >= 0) shelves.setSelectionFromTop(row + shelves.getHeaderViewsCount(),
                            shelves.getHeight() / 3);
                    shelves.post(this);
                    return;
                }
                if (target == null) {
                    land(bird, null);
                    return;
                }
                fly(bird, sx, sy, target);
            }
        });
    }

    private int[] inRoot(View v) {
        int[] a = new int[2], r = new int[2];
        v.getLocationInWindow(a);
        shelvesRoot.getLocationInWindow(r);
        return new int[]{a[0] - r[0], a[1] - r[1]};
    }

    private BookView findSlot(Book b) {
        for (int i = 0; i < shelves.getChildCount(); i++) {
            View child = shelves.getChildAt(i);
            if (!(child instanceof ShelfRow)) continue;
            for (BookView v : ((ShelfRow) child).slots) if (v.book() == b) return v;
        }
        return null;
    }

    private int rowOf(Book b) {
        for (int i = 0; i < rows.size(); i++) {
            Book[] books = rows.get(i).books;
            if (books == null) continue;
            for (Book x : books) if (x == b) return i;
        }
        return -1;
    }

    private void fly(final View bird, final float sx, final float sy, final BookView target) {
        int[] end = inRoot(target);
        final float ex = end[0], ey = end[1];
        final float rise = Ui.dp(this, 70);
        final float dir = ex >= sx ? 1f : -1f;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(560);
        anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                float t = (Float) va.getAnimatedValue();
                float arc = (float) Math.sin(Math.PI * t);
                bird.setX(sx + (ex - sx) * t);
                bird.setY(sy + (ey - sy) * t - rise * arc);
                bird.setRotation(dir * 10f * arc);
                float scale = 1f + 0.14f * arc;
                bird.setScaleX(scale);
                bird.setScaleY(scale);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                land(bird, target);
            }
        });
        anim.start();
    }

    private void land(View bird, BookView target) {
        shelvesRoot.removeView(bird);
        Book b = flying;
        flying = null;
        if (target != null && target.book() == b) {
            target.setAlpha(1f);
            // A little drop onto the shelf, then the usual bounce.
            target.setPivotX(target.getWidth() / 2f);
            target.setPivotY(target.getHeight());
            target.setTranslationY(-8 * getResources().getDisplayMetrics().density);
            target.settle();
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private EditText field(LinearLayout box, String caption, String value) {
        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextSize(12);
        label.setTextColor(0xCCD4AF6A);
        label.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        box.addView(label);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(value);
        box.addView(edit);
        return edit;
    }

    private void rename(final Book b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        final EditText name = field(box, "Book name", b.title);
        final EditText author = field(box, "Author", b.author);
        new AlertDialog.Builder(this)
                .setTitle("Rename")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        String title = name.getText().toString().replaceAll("\\s+", " ").trim();
                        if (title.isEmpty()) {
                            Ui.toast(MainActivity.this, "The book name can't be empty");
                            return;
                        }
                        b.title = title;
                        b.author = author.getText().toString().replaceAll("\\s+", " ").trim();
                        b.infoTime = System.currentTimeMillis();
                        lib.save();
                        Sync.request(MainActivity.this);
                        Quotes.renamed(MainActivity.this, b);
                        rebuild();
                    }
                })
                .show();
    }

    /** Downloads a book from the online library, showing how far it got; then opens it if asked. */
    private void fetch(final Book b, final boolean openAfter) {
        final TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setTextColor(Ui.INK);
        msg.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 4));
        msg.setText("Downloading \u201C" + b.title + "\u201D from your online library\u2026");
        final boolean[] cancelled = {false};
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(msg)
                .setCancelable(false)
                .setNegativeButton("Hide", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        cancelled[0] = true;
                    }
                })
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    CloudLibrary.fetchBook(getApplicationContext(), b, new CloudLibrary.Progress() {
                        long last;

                        @Override public void at(final long done, final long total) {
                            if (done - last < 256 * 1024 && done != total) return;
                            last = done;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    String size = total > 0 ? Math.round(done * 100f / total) + "%"
                                            : (done / (1024 * 1024)) + " MB";
                                    msg.setText("Downloading \u201C" + b.title + "\u201D\u2026  " + size);
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    error = e.getMessage() == null ? "Couldn't download the book" : e.getMessage();
                    if (e instanceof java.net.UnknownHostException) error = "No internet";
                }
                final String why = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isDestroyed()) return;
                        if (dialog.isShowing()) dialog.dismiss();
                        rebuild();
                        if (why != null) {
                            Ui.toast(MainActivity.this, why);
                        } else if (openAfter && !cancelled[0]) {
                            open(b);
                        } else {
                            Ui.toast(MainActivity.this, "\u201C" + b.title + "\u201D is ready");
                        }
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final Book b) {
        if (!b.asset.isEmpty() || b.onlineOnly()) {
            // The book is also in the online library: free space here, or delete it everywhere.
            ArrayList<String> items = new ArrayList<>();
            final ArrayList<Runnable> actions = new ArrayList<>();
            if (!b.onlineOnly()) {
                items.add("Remove the download (keep it online)");
                actions.add(new Runnable() {
                    @Override public void run() {
                        lib.removeDownload(b);
                        rebuild();
                    }
                });
            }
            items.add("Delete everywhere");
            actions.add(new Runnable() {
                @Override public void run() {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Delete everywhere?")
                            .setMessage("\u201C" + b.title + "\u201D will be deleted from this device, your other "
                                    + "devices and your online library, with its drawings and highlights. "
                                    + "Quotes you saved from it are kept.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface dialog, int which) {
                                    Sync.bookDeleted(MainActivity.this, b);
                                    CloudLibrary.forget(MainActivity.this, b);
                                    lib.remove(b);
                                    Ink.delete(MainActivity.this, b.id);
                                    rebuild();
                                }
                            })
                            .show();
                }
            });
            new AlertDialog.Builder(this)
                    .setTitle(b.title)
                    .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            actions.get(which).run();
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete this book?")
                .setMessage("“" + b.title + "” will be removed from your shelves. "
                        + "Your original file on the phone is not touched, and quotes you saved from it are kept. "
                        + "Drawings and highlights in it are deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        lib.remove(b);
                        Ink.delete(MainActivity.this, b.id);
                        rebuild();
                    }
                })
                .show();
    }

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK);
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, "No file picker is available on this device");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CoverPicker.PICK_PHOTO) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                CoverPicker.photoPicked(this, data.getData(), new Runnable() {
                    @Override public void run() {
                        rebuild();
                    }
                });
            }
            return;
        }
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (!uris.isEmpty()) importAll(uris, false);
    }

    private void importAll(final List<Uri> uris, final boolean openAfter) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(uris.size() == 1 ? "Adding to your shelves\u2026" : "Adding " + uris.size() + " books\u2026")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                int added = 0, already = 0, attached = 0;
                Book last = null;
                final StringBuilder errors = new StringBuilder();
                for (Uri u : uris) {
                    // Which books were here before, and which had no file on this device yet.
                    java.util.HashSet<String> before = new java.util.HashSet<>(), onlineBefore = new java.util.HashSet<>();
                    for (Book b : lib.all()) {
                        before.add(b.id);
                        if (b.onlineOnly()) onlineBefore.add(b.id);
                    }
                    try {
                        last = lib.importUri(getApplicationContext(), u);
                        if (onlineBefore.contains(last.id)) attached++;
                        else if (before.contains(last.id)) already++;
                        else added++;
                    } catch (Exception | OutOfMemoryError e) {
                        if (errors.length() > 0) errors.append("\n\n");
                        errors.append(e.getMessage() == null ? "A file could not be added" : e.getMessage());
                    }
                }
                final int newBooks = added, sameBooks = already, gotFile = attached;
                final Book book = last;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isDestroyed()) return;
                        progress.dismiss();
                        rebuild();
                        int total = newBooks + sameBooks + gotFile;
                        if (errors.length() > 0) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle(total == 0 ? "Couldn't add" : "Some files were skipped")
                                    .setMessage(errors.toString())
                                    .setPositiveButton("OK", null)
                                    .show();
                        } else if (total == 1 && book != null) {
                            if (sameBooks == 1) {
                                // Already there: say so and open it instead of adding a copy.
                                Ui.toast(MainActivity.this, "\u201C" + book.title + "\u201D is already on your shelves");
                                open(book);
                            } else if (gotFile == 1) {
                                Ui.toast(MainActivity.this, "\u201C" + book.title + "\u201D is now on this device");
                                if (openAfter) open(book);
                            } else if (openAfter) {
                                open(book);
                            } else {
                                Ui.toast(MainActivity.this, "Added \u201C" + book.title + "\u201D");
                            }
                        } else if (openAfter && book != null) {
                            open(book);
                        } else {
                            StringBuilder msg = new StringBuilder();
                            if (newBooks > 0) msg.append(newBooks).append(newBooks == 1 ? " book added" : " books added");
                            if (gotFile > 0) {
                                if (msg.length() > 0) msg.append(", ");
                                msg.append(gotFile).append(" now on this device");
                            }
                            if (sameBooks > 0) {
                                if (msg.length() > 0) msg.append(", ");
                                msg.append(sameBooks).append(sameBooks == 1 ? " was already on your shelves"
                                        : " were already on your shelves");
                            }
                            Ui.toast(MainActivity.this, msg.toString());
                        }
                    }
                });
            }
        }).start();
    }
}
