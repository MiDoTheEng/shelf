package com.shelf.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** The ↻ screen: connect this device to the GitHub sync file, sync now, or disconnect. */
final class SyncSetup {
    private static final String NEW_KEY =
            "https://github.com/settings/tokens/new?scopes=gist,repo&description=Shelf%20reader";

    private SyncSetup() {}

    static void show(final Activity a, final Runnable changed) {
        if (Sync.connected(a)) showConnected(a, changed);
        else showConnect(a, changed);
    }

    private static TextView text(Activity a, String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(Ui.INK);
        t.setLineSpacing(Ui.dp(a, 3), 1f);
        return t;
    }

    private static void showConnect(final Activity a, final Runnable changed) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 22), Ui.dp(a, 12), Ui.dp(a, 22), 0);
        box.addView(text(a, "Keeps your books, reading positions, bookmarks, quotes, words, covers, highlights "
                + "and drawings on your GitHub account (private), so every device \u2014 and any new install \u2014 "
                + "has them all.\n\n"
                + "1.  Tap \u201CCreate key\u201D and sign in to GitHub.\n"
                + "2.  Set Expiration to \u201CNo expiration\u201D. \u201Cgist\u201D and \u201Crepo\u201D are already ticked.\n"
                + "3.  Scroll down, tap \u201CGenerate token\u201D, copy the key (starts with ghp_) and paste it here.\n\n"
                + "Use the same key on your other devices. Keep it private like a password: it can read and "
                + "write your gists and repos."));
        final EditText key = new EditText(a);
        key.setHint("ghp_…");
        key.setSingleLine(true);
        key.setTypeface(Typeface.MONOSPACE);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 12);
        box.addView(key, lp);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Sync across your devices")
                .setView(box)
                .setPositiveButton("Connect", null)
                .setNeutralButton("Create key", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(NEW_KEY)));
                } catch (Exception e) {
                    Ui.toast(a, "No browser found. Open github.com → Settings → Developer settings → Tokens");
                }
            }
        });
        final View connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String token = key.getText().toString().trim();
                if (token.length() < 20) {
                    Ui.toast(a, "Paste the key from GitHub first");
                    return;
                }
                connect.setEnabled(false);
                Ui.toast(a, "Connecting…");
                new Thread(new Runnable() {
                    @Override public void run() {
                        String error = null;
                        try {
                            Sync.connect(a, token);
                            CloudLibrary.checkKey(a, token);
                        } catch (Exception e) {
                            error = e.getMessage() == null ? "Couldn't connect" : e.getMessage();
                            if (e instanceof java.net.UnknownHostException) error = "No internet";
                        }
                        final String why = error;
                        a.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (a.isDestroyed()) return;
                                connect.setEnabled(true);
                                if (why != null) {
                                    Ui.toast(a, "Couldn't connect: " + why);
                                    return;
                                }
                                dialog.dismiss();
                                Ui.toast(a, CloudLibrary.enabled(a)
                                        ? "Connected as " + Sync.account(a) + ". Syncing and uploading your books\u2026"
                                        : "Connected as " + Sync.account(a) + " (sync only: this key can't keep books online)");
                                Sync.request(a);
                                changed.run();
                            }
                        });
                    }
                }).start();
            }
        });
    }

    private static void showConnected(final Activity a, final Runnable changed) {
        long last = Sync.lastSync(a);
        String when = last == 0 ? "not yet"
                : android.text.format.DateUtils.getRelativeTimeSpanString(last).toString();
        String error = Sync.lastError(a);
        StringBuilder msg = new StringBuilder("Connected to GitHub as " + Sync.account(a) + ".\n\nLast sync: " + when);
        if (!error.isEmpty()) msg.append("\nLast attempt: ").append(error);
        final int[] n = CloudLibrary.counts(a);
        if (CloudLibrary.enabled(a)) {
            msg.append("\n\nOnline library: ").append(n[1]).append(" of ").append(n[0])
                    .append(n[0] == 1 ? " book" : " books").append(" on this device uploaded");
            if (n[2] > 0) msg.append(", ").append(n[2]).append(" more only online");
            msg.append('.');
            String status = CloudLibrary.status;
            if (!status.isEmpty()) msg.append("\n").append(status);
        } else {
            msg.append("\n\nYour books themselves aren't kept online yet: this key can only sync. "
                    + "Tap More \u2192 Use a new key, and make one with \u201Crepo\u201D too.");
        }
        msg.append("\n\nShelf syncs by itself when you open the app and when you close a book.");
        new AlertDialog.Builder(a)
                .setTitle("Sync & online library")
                .setMessage(msg.toString())
                .setPositiveButton("Sync now", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Sync.request(a);
                        Ui.toast(a, "Syncing\u2026");
                    }
                })
                .setNeutralButton("More\u2026", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        more(a, changed, n[2]);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void more(final Activity a, final Runnable changed, final int onlineOnly) {
        final ArrayList<String> items = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();
        if (onlineOnly > 0 && CloudLibrary.enabled(a)) {
            items.add("Download all books (" + onlineOnly + ")");
            actions.add(new Runnable() {
                @Override public void run() {
                    downloadAll(a, changed);
                }
            });
        }
        items.add("Use a new key");
        actions.add(new Runnable() {
            @Override public void run() {
                showConnect(a, changed);
            }
        });
        items.add("Disconnect");
        actions.add(new Runnable() {
            @Override public void run() {
                Sync.disconnect(a);
                Ui.toast(a, "Disconnected. Your books and quotes stay on this device.");
                changed.run();
            }
        });
        new AlertDialog.Builder(a)
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        actions.get(which).run();
                    }
                })
                .show();
    }

    /** Downloads every book that is only online, one after another. */
    private static void downloadAll(final Activity a, final Runnable changed) {
        final ArrayList<Book> todo = new ArrayList<>();
        for (Book b : Library.get(a).all()) if (b.onlineOnly()) todo.add(b);
        Ui.toast(a, "Downloading " + todo.size() + " books in the background\u2026");
        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                for (Book b : todo) {
                    try {
                        CloudLibrary.fetchBook(a.getApplicationContext(), b, null);
                        ok++;
                    } catch (Exception ignored) {
                    }
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!a.isDestroyed()) changed.run();
                        }
                    });
                }
                final int got = ok;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Ui.toast(a, got == todo.size() ? "All " + got + " books downloaded"
                                : got + " of " + todo.size() + " books downloaded \u2014 try again for the rest");
                    }
                });
            }
        }).start();
    }

}
