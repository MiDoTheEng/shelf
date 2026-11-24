package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** The Progress tab: every book read, how far, and the ones finished. */
public class ProgressActivity extends Activity {
    /** A row: either a section heading or a book. */
    private static final class Row {
        String heading;
        History.Entry entry;
    }

    private final ArrayList<Row> rows = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF15151C, 0xFF09090C}));

        LinearLayout bar = Ui.bar(this);
        bar.addView(Ui.barButton(this, "\u2039", 26, new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        }));
        TextView title = Ui.barTitle(this, "Progress");
        title.setTextSize(17);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        FrameLayout body = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setCacheColorHint(0);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 24));
        list.setClipToPadding(false);
        adapter = new Rows();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < rows.size() && rows.get(position).entry != null) menu(rows.get(position).entry);
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(this);
        empty.setTextSize(14.5f);
        empty.setTextColor(0x99B8B2A6);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        empty.setText("Nothing here yet.\\n\\nOpen a book and read a little: it will appear here with how far "
                + "you got, and move to Finished when you reach the end.");
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private final Runnable synced = new Runnable() {
        @Override public void run() {
            if (!isDestroyed()) reload();
        }
    };

    @Override protected void onResume() {
        super.onResume();
        History.backfill(this);
        reload();
        Sync.listen(synced);
    }

    @Override protected void onPause() {
        super.onPause();
        Sync.unlisten(synced);
    }

    private void reload() {
        LinkedHashMap<String, History.Entry> map = History.load(this);
        List<History.Entry> reading = History.sorted(map, false), done = History.sorted(map, true);
        rows.clear();
        add("READING   \u00B7   " + reading.size(), reading);
        add("FINISHED   \u00B7   " + done.size(), done);
        empty.setVisibility(map.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void add(String heading, List<History.Entry> entries) {
        if (entries.isEmpty()) return;
        Row h = new Row();
        h.heading = heading;
        rows.add(h);
        for (History.Entry e : entries) {
            Row r = new Row();
            r.entry = e;
            rows.add(r);
        }
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return rows.get(position).entry != null; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ProgressActivity c = ProgressActivity.this;
            Row row = rows.get(position);
            int side = Math.max(Ui.dp(c, 14), (parent.getWidth() - Ui.dp(c, 720)) / 2);
            if (row.heading != null) {
                TextView t = new TextView(c);
                t.setText(row.heading);
                t.setTextSize(11.5f);
                t.setLetterSpacing(0.16f);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setTextColor(0xCCD4AF6A);
                t.setPadding(side + Ui.dp(c, 6), Ui.dp(c, 18), side, Ui.dp(c, 6));
                return t;
            }
            History.Entry e = row.entry;
            FrameLayout outer = new FrameLayout(c);
            outer.setPadding(side, Ui.dp(c, 5), side, Ui.dp(c, 5));
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 16), Ui.dp(c, 12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF17171D);
            bg.setCornerRadius(Ui.dp(c, 14));
            bg.setStroke(Ui.dp(c, 1), e.finished > 0 ? 0x5566BB6A : 0x33D4AF6A);
            card.setBackground(bg);

            // The little cover.
            ImageView cover = new ImageView(c);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = History.thumbBitmap(e);
            if (thumb != null) {
                cover.setImageBitmap(thumb);
            } else {
                GradientDrawable plain = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        new int[]{0xFF3D2C52, 0xFF1A1226});
                plain.setCornerRadius(Ui.dp(c, 4));
                cover.setBackground(plain);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(c, 50), Ui.dp(c, 74));
            clp.rightMargin = Ui.dp(c, 14);
            card.addView(cover, clp);

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(c);
            name.setText(e.title);
            name.setTextSize(15.5f);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            name.setTextColor(0xFFEFE8D8);
            text.addView(name);
            if (!e.author.isEmpty()) {
                TextView by = new TextView(c);
                by.setText(e.author);
                by.setTextSize(12);
                by.setTextColor(0x99E8E2D4);
                text.addView(by);
            }

            // How far: a bar, then the page and the percentage.
            int pct = Math.min(100, Math.round(e.progress * 100));
            LinearLayout track = new LinearLayout(c);
            GradientDrawable trackBg = new GradientDrawable();
            trackBg.setColor(0x33FFFFFF);
            trackBg.setCornerRadius(Ui.dp(c, 3));
            track.setBackground(trackBg);
            View fill = new View(c);
            GradientDrawable fillBg = new GradientDrawable();
            fillBg.setColor(e.finished > 0 ? 0xFF66BB6A : BookView.GOLD);
            fillBg.setCornerRadius(Ui.dp(c, 3));
            fill.setBackground(fillBg);
            track.addView(fill, new LinearLayout.LayoutParams(0, Ui.dp(c, 5), Math.max(0.01f, pct)));
            track.addView(new View(c), new LinearLayout.LayoutParams(0, Ui.dp(c, 5), Math.max(0.01f, 100 - pct)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Ui.dp(c, 5));
            tlp.topMargin = Ui.dp(c, 9);
            text.addView(track, tlp);

            TextView where = new TextView(c);
            where.setTextSize(12);
            where.setTextColor(0xCCD4AF6A);
            where.setPadding(0, Ui.dp(c, 6), 0, 0);
            String place = e.where.isEmpty() ? "" : e.where + "   \u00B7   ";
            where.setText(e.finished > 0 ? "\u2713  Finished " + date(e.finished) : place + pct + "%");
            text.addView(where);

            TextView when = new TextView(c);
            when.setTextSize(11.5f);
            when.setTextColor(0x80B8B2A6);
            when.setPadding(0, Ui.dp(c, 2), 0, 0);
            when.setText(e.finished > 0 ? "Started " + date(e.started)
                    : "Last read " + DateUtils.getRelativeTimeSpanString(e.lastRead));
            text.addView(when);

            card.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return outer;
        }
    }

    private String date(long t) {
        if (t <= 1) return "";
        return DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                | DateUtils.FORMAT_ABBREV_MONTH);
    }

    private Book bookFor(History.Entry e) {
        for (Book b : Library.get(this).all()) if (History.keyOf(b).equals(e.key)) return b;
        return null;
    }

    private void menu(final History.Entry e) {
        final Book book = bookFor(e);
        final ArrayList<String> items = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (book != null) {
            items.add(book.onlineOnly() ? "Open (download first)" : "Open");
            actions.add(new Runnable() {
                @Override public void run() {
                    // The shelves open it, downloading it first if it's only online.
                    Intent i = new Intent(ProgressActivity.this, MainActivity.class);
                    i.putExtra("open", book.id);
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(i);
                }
            });
        }
        items.add(e.finished > 0 ? "Mark as not finished" : "Mark as finished");
        actions.add(new Runnable() {
            @Override public void run() {
                History.setFinished(ProgressActivity.this, e.key, e.finished == 0);
                reload();
            }
        });
        items.add("Remove from Progress");
        actions.add(new Runnable() {
            @Override public void run() {
                new AlertDialog.Builder(ProgressActivity.this)
                        .setMessage("Remove \u201C" + e.title + "\u201D from your progress? The book itself stays on "
                                + "your shelves.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                History.remove(ProgressActivity.this, e.key);
                                reload();
                            }
                        })
                        .show();
            }
        });
        new AlertDialog.Builder(this)
                .setTitle(e.title)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }
}
