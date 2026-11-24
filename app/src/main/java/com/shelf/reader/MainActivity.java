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
    private int cols;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        lib = Library.get(this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        cols = Math.max(3, Math.min(8, (int) (dm.widthPixels / dm.density / 118f)));

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15151C, 0xFF09090C}));

        ListView list = new ListView(this);
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
        list.addHeaderView(header, null, false);

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
        rebuild();
        Sync.listen(synced);
        Sync.request(this);
    }

    @Override protected void onPause() {
        super.onPause();
        Sync.unlisten(synced);
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
        addShelf("Library", library, reading.isEmpty()
                ? "Tap + to add PDF, EPUB or Kindle books"
                : "Every book is on your reading shelf");
        int total = reading.size() + library.size();
        subtitle.setText(total == 0 ? "Your shelves are empty" : total == 1 ? "1 book" : total + " books");
        adapter.notifyDataSetChanged();
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
                if (b == null) {
                    v.setOnClickListener(null);
                    v.setOnLongClickListener(null);
                    v.setClickable(false);
                    v.setLongClickable(false);
                } else {
                    v.setContentDescription(b.title);
                    v.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            open(b);
                        }
                    });
                    v.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override public boolean onLongClick(View view) {
                            menu(b);
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
        if (b.onlineOnly()) {
            fetch(b, true);
            return;
        }
        if (!new File(b.file).exists()) {
            Ui.toast(this, "This book's file is missing");
            return;
        }
        if (!b.reading) {
            b.reading = true;
            b.shelfTime = System.currentTimeMillis();
        }
        b.opened = System.currentTimeMillis();
        lib.save();
        Intent i = new Intent(this, b.format.equals("pdf") ? PdfActivity.class : ReaderActivity.class);
        i.putExtra("id", b.id);
        startActivity(i);
    }

    private void menu(final Book b) {
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
                            b.reading = !b.reading;
                            b.shelfTime = System.currentTimeMillis();
                            if (b.reading) b.opened = System.currentTimeMillis();
                            lib.save();
                            rebuild();
                        } else {
                            confirmDelete(b);
                        }
                    }
                })
                .show();
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
