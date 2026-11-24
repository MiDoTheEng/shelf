package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.shelf.reader.core.Util;

import org.json.JSONArray;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * English to Arabic. When there is internet it asks Google Translate (the same quality as
 * the Google Translate app, with the other meanings of a single word); without internet,
 * or if that fails, it uses Google's offline translator on the phone (ML Kit), whose
 * dictionary is downloaded once, about 30 MB.
 */
final class Translate {
    /** A finished translation: the Arabic, other meanings (may be empty), and where it came from. */
    static final class Result {
        String arabic = "";
        String meanings = "";
        boolean online;
    }

    private static Translator offline;

    private Translate() {}

    // ------------------------------------------------------------------ online

    /** Asks Google Translate; returns null when offline or when anything goes wrong. Off the main thread. */
    static Result online(String text) {
        HttpURLConnection con = null;
        try {
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar"
                    + "&dt=t&dt=bd&q=" + URLEncoder.encode(text, "UTF-8");
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(7000);
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (con.getResponseCode() != 200) return null;
            InputStream in = con.getInputStream();
            JSONArray root = new JSONArray(new String(Util.readAll(in), StandardCharsets.UTF_8));

            Result r = new Result();
            r.online = true;
            StringBuilder ar = new StringBuilder();
            JSONArray parts = root.optJSONArray(0);
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONArray part = parts.optJSONArray(i);
                if (part != null && !part.isNull(0)) ar.append(part.optString(0, ""));
            }
            r.arabic = ar.toString().trim();

            // For a single word, Google also lists meanings by kind of word.
            StringBuilder more = new StringBuilder();
            JSONArray dict = root.length() > 1 ? root.optJSONArray(1) : null;
            for (int i = 0; dict != null && i < dict.length(); i++) {
                JSONArray entry = dict.optJSONArray(i);
                JSONArray terms = entry == null ? null : entry.optJSONArray(1);
                if (terms == null || terms.length() == 0) continue;
                String kind = entry.optString(0, "");
                StringBuilder list = new StringBuilder();
                for (int k = 0; k < terms.length() && k < 7; k++) {
                    String term = terms.optString(k, "").trim();
                    if (term.isEmpty()) continue;
                    if (list.length() > 0) list.append("، ");
                    list.append(term);
                }
                if (list.length() == 0) continue;
                if (more.length() > 0) more.append('\n');
                if (!kind.isEmpty()) more.append(kind).append(":  ");
                more.append(list);
            }
            r.meanings = more.toString();
            return r.arabic.isEmpty() ? null : r;
        } catch (Throwable t) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    // ----------------------------------------------------------------- offline

    private static Translator offline() {
        if (offline == null) {
            offline = Translation.getClient(new TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build());
        }
        return offline;
    }

    interface Done {
        void ok(Result r);
        void fail();
    }

    /** Offline translation on the phone; callbacks arrive on the main thread. */
    static void offline(final String text, final Done done) {
        final Translator t = offline();
        final OnFailureListener failed = new OnFailureListener() {
            @Override public void onFailure(Exception e) {
                done.fail();
            }
        };
        t.downloadModelIfNeeded().addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override public void onSuccess(Void v) {
                t.translate(text).addOnSuccessListener(new OnSuccessListener<String>() {
                    @Override public void onSuccess(String s) {
                        if (s == null || s.trim().isEmpty()) {
                            done.fail();
                            return;
                        }
                        Result r = new Result();
                        r.arabic = s.trim();
                        done.ok(r);
                    }
                }).addOnFailureListener(failed);
            }
        }).addOnFailureListener(failed);
    }

    // ---------------------------------------------------------------------- UI

    /** Translates the text, shows the Arabic in a small box and saves it to the Words tab. */
    static void show(final Activity a, final String text, final String bookTitle) {
        if (text == null || text.trim().isEmpty()) {
            Ui.toast(a, "Select a word or sentence first");
            return;
        }
        final String source = text.trim();
        final TextView arabic = new TextView(a);
        arabic.setTextSize(21);
        arabic.setTextColor(Ui.INK);
        arabic.setGravity(Gravity.END);
        arabic.setTextDirection(View.TEXT_DIRECTION_RTL);
        arabic.setLineSpacing(Ui.dp(a, 4), 1f);
        arabic.setTextIsSelectable(true);
        arabic.setText("…");

        final TextView meanings = new TextView(a);
        meanings.setTextSize(15);
        meanings.setTextColor(0xCCEFE8D8);
        meanings.setGravity(Gravity.END);
        meanings.setTextDirection(View.TEXT_DIRECTION_RTL);
        meanings.setLineSpacing(Ui.dp(a, 3), 1f);
        meanings.setPadding(0, Ui.dp(a, 10), 0, 0);
        meanings.setTextIsSelectable(true);
        meanings.setVisibility(View.GONE);

        final TextView status = new TextView(a);
        status.setTextSize(12.5f);
        status.setTextColor(0x99B8B2A6);
        status.setPadding(0, Ui.dp(a, 12), 0, 0);
        status.setText("Translating…");

        TextView original = new TextView(a);
        original.setTextSize(14);
        original.setTextColor(0xCCD4AF6A);
        original.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        original.setText(source);
        original.setPadding(0, 0, 0, Ui.dp(a, 12));

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 18), Ui.dp(a, 22), Ui.dp(a, 6));
        box.addView(original);
        box.addView(arabic);
        box.addView(meanings);
        box.addView(status);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setView(scroll)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Translation", arabic.getText()));
                Ui.toast(a, "Copied");
            }
        });

        final Done done = new Done() {
            @Override public void ok(Result r) {
                Words.add(a.getApplicationContext(), source, r.arabic, r.meanings, bookTitle);
                if (a.isDestroyed()) return;
                arabic.setText(r.arabic);
                if (!r.meanings.isEmpty()) {
                    meanings.setText(r.meanings);
                    meanings.setVisibility(View.VISIBLE);
                }
                status.setText((r.online ? "Google Translate" : "Offline translation (no internet)")
                        + "   ·   Saved to Words");
            }

            @Override public void fail() {
                if (a.isDestroyed()) return;
                arabic.setText("");
                status.setText("Couldn't translate. Without internet, the offline dictionary must have "
                        + "been downloaded once before — connect and try again.");
            }
        };

        new Thread(new Runnable() {
            @Override public void run() {
                final Result r = online(source);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (r != null) {
                            done.ok(r);
                            // Get the offline dictionary ready in the background for days without internet.
                            offline().downloadModelIfNeeded();
                        } else {
                            if (!a.isDestroyed()) status.setText("No internet — translating offline…");
                            offline(source, done);
                        }
                    }
                });
            }
        }).start();
    }
}
