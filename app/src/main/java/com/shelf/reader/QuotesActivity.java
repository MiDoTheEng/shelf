package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The Quotes section: a list of books, and inside each one the quotes saved from it. */
public class QuotesActivity extends Activity {
    private static final int SAVE_FILE = 52;
    private static final int EXPORT = 53;
    private static final int IMPORT = 54;

    private String bookId;
    private Quotes.BookQuotes set;
    private List<Quotes.BookQuotes> sets = new ArrayList<>();
    private TextView barTitle;
    private TextView empty;
    private BaseAdapter adapter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        bookId = getIntent().getStringExtra("book");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15151C, 0xFF09090C}));

        LinearLayout bar = Ui.bar(this);
        bar.addView(Ui.barButton(this, "‹", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        }));
        barTitle = Ui.barTitle(this, "Quotes");
        barTitle.setTextSize(17);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (bookId != null) {
            bar.addView(Ui.barButton(this, "Share all", 13, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (set != null && !set.quotes.isEmpty()) Quotes.share(QuotesActivity.this, Quotes.allText(set));
                }
            }));
            bar.addView(Ui.barButton(this, "Save file", 13, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    saveFile();
                }
            }));
        } else {
            bar.addView(Ui.barButton(this, "Backup", 13, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    backupMenu();
                }
            }));
        }
        root.addView(bar);

        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        list.setClipToPadding(false);
        adapter = new Rows();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (bookId == null) {
                    if (position < sets.size()) {
                        Intent i = new Intent(QuotesActivity.this, QuotesActivity.class);
                        i.putExtra("book", sets.get(position).bookId);
                        startActivity(i);
                    }
                } else if (set != null && position < set.quotes.size()) {
                    quoteMenu(quoteAt(position));
                }
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (bookId == null && position < sets.size()) {
                    deleteAll(sets.get(position));
                    return true;
                }
                return false;
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setTextSize(14.5f);
        empty.setTextColor(0x99B8B2A6);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        empty.setText(bookId == null
                ? "No quotes yet.\n\nWhile reading, select some text and tap “Save quote”. "
                + "Each book gets its own file of quotes here."
                : "No quotes saved from this book yet.");
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        int count;
        if (bookId == null) {
            sets = Quotes.all(this);
            count = sets.size();
        } else {
            set = Quotes.load(this, bookId);
            count = set == null ? 0 : set.quotes.size();
            if (set != null && !set.title.isEmpty()) barTitle.setText(set.title);
        }
        empty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** Newest quote first. */
    private Quotes.Quote quoteAt(int position) {
        return set.quotes.get(set.quotes.size() - 1 - position);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() {
            return bookId == null ? sets.size() : set == null ? 0 : set.quotes.size();
        }

        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            QuotesActivity c = QuotesActivity.this;
            FrameLayout outer = new FrameLayout(c);
            int side = Math.max(Ui.dp(c, 14), (parent.getWidth() - Ui.dp(c, 720)) / 2);
            outer.setPadding(side, Ui.dp(c, 6), side, Ui.dp(c, 6));
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(c, 18), Ui.dp(c, 16), Ui.dp(c, 18), Ui.dp(c, 16));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF17171D);
            bg.setCornerRadius(Ui.dp(c, 14));
            bg.setStroke(Ui.dp(c, 1), 0x33D4AF6A);
            card.setBackground(bg);

            TextView main = new TextView(c);
            main.setTypeface(Typeface.create(Typeface.SERIF, bookId == null ? Typeface.BOLD : Typeface.NORMAL));
            main.setTextColor(0xFFEFE8D8);
            TextView small = new TextView(c);
            small.setTextSize(12);
            small.setTextColor(0xCCD4AF6A);
            small.setPadding(0, Ui.dp(c, 8), 0, 0);
            if (bookId == null) {
                Quotes.BookQuotes s = sets.get(position);
                main.setTextSize(17);
                main.setText(s.title);
                int n = s.quotes.size();
                small.setText((s.author.isEmpty() ? "" : s.author + "   ·   ") + n + (n == 1 ? " quote" : " quotes"));
            } else {
                Quotes.Quote q = quoteAt(position);
                main.setTextSize(16);
                main.setLineSpacing(Ui.dp(c, 4), 1f);
                main.setText(Quotes.quoted(q.text));
                small.setText(q.where);
                if (q.where.isEmpty()) small.setVisibility(View.GONE);
            }
            card.addView(main);
            card.addView(small);
            outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return outer;
        }
    }

    private void quoteMenu(final Quotes.Quote q) {
        final String text = Quotes.shareText(set.title, set.author, q.text);
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Share", "Copy", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            Quotes.share(QuotesActivity.this, text);
                        } else if (which == 1) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Quote", text));
                            Ui.toast(QuotesActivity.this, "Copied");
                        } else {
                            set.quotes.remove(q);
                            Quotes.save(QuotesActivity.this, set);
                            Sync.quoteDeleted(QuotesActivity.this, set, q.text);
                            reload();
                        }
                    }
                })
                .show();
    }

    private void deleteAll(final Quotes.BookQuotes s) {
        new AlertDialog.Builder(this)
                .setTitle("Delete these quotes?")
                .setMessage("All " + s.quotes.size() + " quotes saved from “" + s.title + "” will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        for (Quotes.Quote q : s.quotes) Sync.quoteDeleted(QuotesActivity.this, s, q.text);
                        s.quotes.clear();
                        Quotes.save(QuotesActivity.this, s);
                        reload();
                    }
                })
                .show();
    }

    private void saveFile() {
        if (set == null || set.quotes.isEmpty()) return;
        String name = set.title.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        if (name.isEmpty()) name = "Book";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, name + " - quotes.txt");
        try {
            startActivityForResult(i, SAVE_FILE);
        } catch (Exception e) {
            Ui.toast(this, "No file picker is available on this device");
        }
    }

    // ------------------------------------------------- moving to another device

    private void backupMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Move quotes & words")
                .setItems(new String[]{
                        "Save a backup file (all quotes and words)",
                        "Import a backup file from another device"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Intent i;
                        if (which == 0) {
                            i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            i.setType("application/json");
                            i.putExtra(Intent.EXTRA_TITLE, "Shelf quotes backup.json");
                        } else {
                            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            i.setType("*/*");
                        }
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        try {
                            startActivityForResult(i, which == 0 ? EXPORT : IMPORT);
                        } catch (Exception e) {
                            Ui.toast(QuotesActivity.this, "No file picker is available on this device");
                        }
                    }
                })
                .show();
    }

    private void handleBackup(int requestCode, Uri uri) {
        try {
            if (requestCode == EXPORT) {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) throw new java.io.IOException("no stream");
                try {
                    out.write(Backup.export(this).getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                Ui.toast(this, "Backup saved. Open it with Shelf on the other device: Quotes \u2192 Backup \u2192 Import.");
            } else {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("no stream");
                String json = new String(com.shelf.reader.core.Util.readAll(in), StandardCharsets.UTF_8);
                int[] added = Backup.importText(this, json);
                Sync.request(this);
                reload();
                new AlertDialog.Builder(this)
                        .setTitle("Imported")
                        .setMessage(added[0] + (added[0] == 1 ? " quote" : " quotes") + " and " + added[1]
                                + (added[1] == 1 ? " word" : " words") + " added. "
                                + "Anything you already had was kept and not duplicated.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        } catch (Exception e) {
            Ui.toast(this, requestCode == EXPORT ? "Could not save the backup"
                    : "That file is not a Shelf backup, or it could not be read");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == EXPORT || requestCode == IMPORT) && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            handleBackup(requestCode, data.getData());
            return;
        }
        if (requestCode != SAVE_FILE || resultCode != RESULT_OK || data == null || set == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) throw new java.io.IOException("no stream");
            try {
                out.write(Quotes.allText(set).getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            Ui.toast(this, "Quotes file saved");
        } catch (Exception e) {
            Ui.toast(this, "Could not save the file");
        }
    }
}
