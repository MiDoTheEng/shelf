package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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

import java.util.ArrayList;
import java.util.List;

/** The Words tab: everything translated while reading, English with its Arabic. */
public class WordsActivity extends Activity {
    private List<Words.Entry> words = new ArrayList<>();
    private BaseAdapter adapter;
    private TextView empty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        TextView title = Ui.barTitle(this, "Words");
        title.setTextSize(17);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(Ui.barButton(this, "Share all", 13, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (words.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                for (Words.Entry e : words) sb.append(e.text).append(" — ").append(e.arabic).append('\n');
                Quotes.share(WordsActivity.this, sb.toString().trim());
            }
        }));
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
                if (position < words.size()) menu(words.get(position));
            }
        });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(this);
        empty.setTextSize(14.5f);
        empty.setTextColor(0x99B8B2A6);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 40), 0, Ui.dp(this, 40), 0);
        empty.setText("No words yet.\n\nWhile reading, hold a word or sentence and tap “Translate”. "
                + "Every translation is kept here.");
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
        words = Words.all(this);
        empty.setVisibility(words.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return words.size(); }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            WordsActivity c = WordsActivity.this;
            Words.Entry e = words.get(position);
            FrameLayout outer = new FrameLayout(c);
            int side = Math.max(Ui.dp(c, 14), (parent.getWidth() - Ui.dp(c, 720)) / 2);
            outer.setPadding(side, Ui.dp(c, 5), side, Ui.dp(c, 5));
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(Ui.dp(c, 18), Ui.dp(c, 14), Ui.dp(c, 18), Ui.dp(c, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF17171D);
            bg.setCornerRadius(Ui.dp(c, 14));
            bg.setStroke(Ui.dp(c, 1), 0x33D4AF6A);
            card.setBackground(bg);

            TextView en = new TextView(c);
            en.setTextSize(16.5f);
            en.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
            en.setTextColor(0xFFEFE8D8);
            en.setText(e.text);
            card.addView(en);

            TextView ar = new TextView(c);
            ar.setTextSize(19);
            ar.setTextColor(0xFFD4AF6A);
            ar.setGravity(Gravity.END);
            ar.setTextDirection(View.TEXT_DIRECTION_RTL);
            ar.setPadding(0, Ui.dp(c, 6), 0, 0);
            ar.setText(e.arabic);
            card.addView(ar);

            if (!e.meanings.isEmpty()) {
                TextView more = new TextView(c);
                more.setTextSize(14);
                more.setTextColor(0xB3EFE8D8);
                more.setGravity(Gravity.END);
                more.setTextDirection(View.TEXT_DIRECTION_RTL);
                more.setLineSpacing(Ui.dp(c, 2), 1f);
                more.setPadding(0, Ui.dp(c, 6), 0, 0);
                more.setText(e.meanings);
                card.addView(more);
            }

            if (!e.book.isEmpty()) {
                TextView book = new TextView(c);
                book.setTextSize(11.5f);
                book.setTextColor(0x80B8B2A6);
                book.setPadding(0, Ui.dp(c, 8), 0, 0);
                book.setText(e.book);
                card.addView(book);
            }
            outer.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return outer;
        }
    }

    private void menu(final Words.Entry e) {
        final String text = e.text + " — " + e.arabic + (e.meanings.isEmpty() ? "" : "\n" + e.meanings);
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Copy", "Share", "Delete"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Word", text));
                            Ui.toast(WordsActivity.this, "Copied");
                        } else if (which == 1) {
                            Quotes.share(WordsActivity.this, text);
                        } else {
                            words.remove(e);
                            Words.save(WordsActivity.this, words);
                            Sync.wordDeleted(WordsActivity.this, e.text);
                            reload();
                        }
                    }
                })
                .show();
    }
}
