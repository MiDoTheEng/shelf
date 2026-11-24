package com.shelf.reader;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Small helpers shared by the screens. */
final class Ui {
    static final int INK = 0xFFE8E2D4;
    static final int BAR = 0xF2101014;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout bar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR);
        bar.setPadding(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 2));
        bar.setClickable(true);
        return bar;
    }

    static TextView barButton(Context c, String text, float sizeSp, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(c, 48));
        t.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        t.setOnClickListener(l);
        return t;
    }

    static TextView barTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(INK);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(c, 4), 0, dp(c, 4), 0);
        return t;
    }

    static TextView pill(Context c, String text, boolean filled) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        stylePill(t, filled);
        return t;
    }

    /** The small menu shown while text is selected: Quote, Translate, Highlight, Share. */
    static LinearLayout selectionMenu(Context c, final Runnable quote, final Runnable translate,
                                      final Runnable highlight, final Runnable share) {
        LinearLayout menu = new LinearLayout(c);
        menu.setGravity(Gravity.CENTER);
        menu.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7101014);
        bg.setCornerRadius(dp(c, 26));
        bg.setStroke(dp(c, 1), 0x55D4AF6A);
        menu.setBackground(bg);
        menu.setElevation(dp(c, 8));
        String[] names = {"\u201C Quote", "Translate", "Highlight", "Share"};
        final Runnable[] actions = {quote, translate, highlight, share};
        for (int i = 0; i < names.length; i++) {
            final Runnable action = actions[i];
            TextView t = pill(c, names[i], i < 2);
            t.setTextSize(13f);
            t.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    action.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            menu.addView(t, lp);
        }
        return menu;
    }

    /**
     * Puts a menu just above the selected text (l, t, r, b in the parent's pixels), or just
     * below it when there is no room above, centred on it and kept inside the screen.
     */
    static void placeNear(View menu, View parent, float l, float t, float r, float b, float belowGap) {
        Context c = menu.getContext();
        menu.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int mw = menu.getMeasuredWidth(), mh = menu.getMeasuredHeight();
        int w = parent.getWidth(), h = parent.getHeight(), m = dp(c, 8);
        float x = Math.max(m, Math.min(w - mw - m, (l + r) / 2f - mw / 2f));
        float above = t - mh - dp(c, 14), below = b + belowGap;
        float y;
        if (above >= dp(c, 36)) y = above;
        else if (below + mh <= h - m) y = below;
        else y = Math.max(m, Math.min(h - mh - m, (t + b) / 2f - mh / 2f));
        menu.setTranslationX(x);
        menu.setTranslationY(y);
        menu.setVisibility(View.VISIBLE);
    }

    static void stylePill(TextView t, boolean filled) {
        Context c = t.getContext();
        t.setTextColor(filled ? 0xFF17130B : BookView.GOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 22));
        bg.setColor(filled ? BookView.GOLD : 0xF2101014);
        bg.setStroke(dp(c, 1), filled ? BookView.GOLD : 0x88D4AF6A);
        t.setBackground(bg);
    }

    // ------------------------------------------------------------ page turning

    private static final String[] MODES = {"scroll", "left", "right", "up"};

    /** "scroll", or the swipe that goes to the next page: "left", "right" or "up". */
    static String mode(SharedPreferences p) {
        String m = p.getString("pageMode", "left");
        for (String k : MODES) if (k.equals(m)) return m;
        return "left";
    }

    static void pickMode(Context c, final SharedPreferences p, final Runnable changed) {
        String[] names = {
                "Scroll continuously",
                "Page by page \u2014 swipe left for next",
                "Page by page \u2014 swipe right for next (Arabic)",
                "Page by page \u2014 swipe up for next"};
        String now = mode(p);
        int checked = 0;
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(now)) checked = i;
        new AlertDialog.Builder(c)
                .setTitle("Page turning")
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        p.edit().putString("pageMode", MODES[which]).apply();
                        dialog.dismiss();
                        changed.run();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --------------------------------------------------------------- bookmarks

    interface MarkPick {
        void open(Book.Mark mark);
    }

    static void showMarks(final Context c, final Library lib, final Book b, final MarkPick pick, final Runnable changed) {
        if (b.marks.isEmpty()) {
            toast(c, "No bookmarks yet. Tap \u2606 while reading to add one.");
            return;
        }
        String[] names = new String[b.marks.size()];
        for (int i = 0; i < names.length; i++) names[i] = b.marks.get(i).label;
        final AlertDialog dialog = new AlertDialog.Builder(c)
                .setTitle("Bookmarks \u2014 hold one to remove")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which < b.marks.size()) pick.open(b.marks.get(which));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
        dialog.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < b.marks.size()) {
                    b.marks.remove(position);
                    b.marksTime = System.currentTimeMillis();
                    lib.save();
                    changed.run();
                    toast(c, "Bookmark removed");
                }
                dialog.dismiss();
                return true;
            }
        });
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
