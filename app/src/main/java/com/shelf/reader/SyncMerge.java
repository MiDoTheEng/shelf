package com.shelf.reader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Combines this device's reading data with the copy kept online. Pure logic, no network:
 * - a book's position, shelf and bookmarks: the most recent change wins, except that a
 *   position which would move you backwards, or move a book that is open right now,
 *   is kept aside and offered when the book is opened;
 * - quotes and words: everything from both sides, minus what was deleted on either.
 */
final class SyncMerge {
    /** Online copy of one book's state, keyed by the book file's fingerprint. */
    static final class Pos {
        String title = "", device = "";
        int chapter;
        float offset, progress;
        long time;
        boolean reading;
        long shelfTime;
        final ArrayList<Book.Mark> marks = new ArrayList<>();
        long marksTime;
    }

    /** Online copy of the quotes from one book. */
    static final class QSet {
        String title = "", author = "";
        /** Every name this book has had on any device, to recognise it when known only by name. */
        final java.util.TreeSet<String> names = new java.util.TreeSet<>();
        final ArrayList<Quotes.Quote> quotes = new ArrayList<>();
    }

    /** Online record of one book in the library, keyed by its file fingerprint. */
    static final class Entry {
        String title = "", author = "", format = "", source = "";
        long size, added, infoTime;
        String asset = "", cover = "", ink = "";
        long coverTime, inkTime;
    }

    /** Everything kept online. */
    static final class Data {
        final LinkedHashMap<String, Entry> library = new LinkedHashMap<>();
        /** Books deleted everywhere (fingerprint), with when. */
        final HashMap<String, Long> deletedBooks = new HashMap<>();
        /** The reading log, and entries removed from it. */
        final LinkedHashMap<String, History.Entry> history = new LinkedHashMap<>();
        final HashMap<String, Long> deletedHistory = new HashMap<>();
        final LinkedHashMap<String, Pos> books = new LinkedHashMap<>();
        final LinkedHashMap<String, QSet> quotes = new LinkedHashMap<>();
        final ArrayList<Words.Entry> words = new ArrayList<>();
        /** Deleted quotes ("set id" + newline + text) and words (lower-case text), with when. */
        final HashMap<String, Long> deletedQuotes = new HashMap<>();
        final HashMap<String, Long> deletedWords = new HashMap<>();
    }

    /** What changed on this device. */
    static final class Outcome {
        boolean booksChanged, wordsChanged;
        /** Books that exist online but not yet here: shown on the shelves, downloaded when opened. */
        final ArrayList<Book> added = new ArrayList<>();
        /** Books deleted everywhere from another device. */
        final ArrayList<Book> removed = new ArrayList<>();
        final ArrayList<Quotes.BookQuotes> setsChanged = new ArrayList<>();
        List<Words.Entry> words;
    }

    private SyncMerge() {}

    static String titleKey(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * The id a book's quotes are known by on every device: fixed once synced; before that,
     * the book file's fingerprint, or the book's name when the book isn't on this device.
     */
    static String setId(Quotes.BookQuotes set, List<Book> library) {
        if (!set.syncId.isEmpty()) return set.syncId;
        for (Book b : library) {
            if (b.id.equals(set.bookId) && !b.key.isEmpty()) return "k:" + b.key;
        }
        return "t:" + titleKey(set.title);
    }

    static Outcome merge(List<Book> library, List<Quotes.BookQuotes> sets, List<Words.Entry> words,
                         Map<String, Long> deletedQuotes, Map<String, Long> deletedWords,
                         Data remote, String openBookId, String device) {
        return merge(library, sets, words, deletedQuotes, deletedWords, new HashMap<String, Long>(),
                remote, openBookId, device);
    }

    static Outcome merge(List<Book> libraryIn, List<Quotes.BookQuotes> sets, List<Words.Entry> words,
                         Map<String, Long> deletedQuotes, Map<String, Long> deletedWords,
                         Map<String, Long> deletedBooks, Data remote, String openBookId, String device) {
        Outcome out = new Outcome();
        ArrayList<Book> library = new ArrayList<>(libraryIn);
        mergeLibrary(library, deletedBooks, remote, out);
        library.removeAll(out.removed);
        library.addAll(out.added);

        // ---- books
        for (Book b : library) {
            if (b.key.isEmpty()) continue;
            Pos r = remote.books.get(b.key);
            if (r == null) {
                r = new Pos();
                remote.books.put(b.key, r);
                copyPosition(b, r, device);
                r.reading = b.reading;
                r.shelfTime = b.shelfTime;
                r.marks.addAll(b.marks);
                r.marksTime = b.marksTime;
                r.title = b.title;
                continue;
            }
            r.title = b.title;
            // The furthest point read on any device is where the book continues. A device only
            // moves forward to it, never back, and only when the other device read on after this
            // one last moved (so deliberately going back to re-read on one device is respected).
            boolean ahead = r.progress > b.progress + 0.0005f;
            boolean behind = r.progress < b.progress - 0.0005f;
            if (ahead && r.time > b.posTime) {
                if (b.id.equals(openBookId)) {
                    // Open right now: the reader moves there (or asks, if pages were turned meanwhile).
                    if (r.time > b.pendingTime) {
                        b.pendingChapter = r.chapter;
                        b.pendingOffset = r.offset;
                        b.pendingProgress = r.progress;
                        b.pendingTime = r.time;
                        b.pendingDevice = r.device;
                        out.booksChanged = true;
                    }
                } else {
                    b.chapter = r.chapter;
                    b.offset = r.offset;
                    b.progress = r.progress;
                    b.posTime = r.time;
                    b.pendingTime = 0;
                    out.booksChanged = true;
                }
            } else if (behind || (!ahead && b.posTime > r.time)) {
                copyPosition(b, r, device);
            }
            if (r.shelfTime > b.shelfTime) {
                if (b.reading != r.reading) out.booksChanged = true;
                b.reading = r.reading;
                b.shelfTime = r.shelfTime;
                if (b.reading && b.opened < r.shelfTime) b.opened = r.shelfTime;
            } else if (b.shelfTime > r.shelfTime) {
                r.reading = b.reading;
                r.shelfTime = b.shelfTime;
            }
            if (r.marksTime > b.marksTime) {
                b.marks.clear();
                b.marks.addAll(r.marks);
                b.marksTime = r.marksTime;
                out.booksChanged = true;
            } else if (b.marksTime > r.marksTime) {
                r.marks.clear();
                r.marks.addAll(b.marks);
                r.marksTime = b.marksTime;
            }
        }

        // ---- deletions known on either side
        for (Map.Entry<String, Long> e : remote.deletedQuotes.entrySet()) later(deletedQuotes, e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : deletedQuotes.entrySet()) later(remote.deletedQuotes, e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : remote.deletedWords.entrySet()) later(deletedWords, e.getKey(), e.getValue());
        for (Map.Entry<String, Long> e : deletedWords.entrySet()) later(remote.deletedWords, e.getKey(), e.getValue());

        // ---- quotes
        java.util.LinkedHashSet<Quotes.BookQuotes> changed = new java.util.LinkedHashSet<>();
        LinkedHashMap<String, Quotes.BookQuotes> localById = new LinkedHashMap<>();
        for (Quotes.BookQuotes s : sets) {
            String id = setId(s, library);
            if (s.syncId.isEmpty()) {
                s.syncId = id;
                changed.add(s);
            }
            Quotes.BookQuotes other = localById.get(id);
            if (other == null) {
                localById.put(id, s);
                continue;
            }
            // Two lists of quotes for one book on this device: combine them into the book's own.
            Quotes.BookQuotes keep = inLibrary(s, library) ? s : other, drop = keep == s ? other : s;
            for (Quotes.Quote q : drop.quotes) if (!hasText(keep.quotes, q.text)) keep.quotes.add(q);
            drop.quotes.clear();
            changed.add(keep);
            changed.add(drop);
            localById.put(id, keep);
        }
        // Quotes of a book known here by name and online by its file: they are the same book.
        for (String rid : new ArrayList<>(remote.quotes.keySet())) {
            if (localById.containsKey(rid)) continue;
            QSet online = remote.quotes.get(rid);
            for (String lid : new ArrayList<>(localById.keySet())) {
                Quotes.BookQuotes s = localById.get(lid);
                String mine = titleKey(s.title);
                boolean same = mine.equals(titleKey(online.title)) || online.names.contains(mine);
                if (remote.quotes.containsKey(lid) || !same) continue;
                localById.remove(lid);
                s.syncId = rid;
                localById.put(rid, s);
                changed.add(s);
                break;
            }
        }
        HashSet<String> ids = new HashSet<>(localById.keySet());
        ids.addAll(remote.quotes.keySet());
        for (String id : ids) {
            Quotes.BookQuotes local = localById.get(id);
            QSet r = remote.quotes.get(id);
            boolean localChanged = false;
            if (local == null) {
                local = new Quotes.BookQuotes();
                local.bookId = bookIdFor(id, r, library, sets);
                local.syncId = id;
                local.title = r.title;
                local.author = r.author;
            }
            if (r == null) {
                r = new QSet();
                remote.quotes.put(id, r);
            }
            if (r.title.isEmpty()) r.title = local.title;
            if (r.author.isEmpty()) r.author = local.author;
            if (!local.title.isEmpty()) r.names.add(titleKey(local.title));

            LinkedHashMap<String, Quotes.Quote> all = new LinkedHashMap<>();
            for (Quotes.Quote q : local.quotes) all.put(q.text, q);
            for (Quotes.Quote q : r.quotes) {
                if (!all.containsKey(q.text)) {
                    all.put(q.text, q);
                    localChanged = true;
                }
            }
            ArrayList<Quotes.Quote> kept = new ArrayList<>();
            for (Quotes.Quote q : all.values()) {
                Long gone = deletedQuotes.get(id + "\n" + q.text);
                if (gone != null && gone >= q.time) {
                    if (local.quotes.contains(q)) localChanged = true;
                    continue;
                }
                kept.add(q);
            }
            Collections.sort(kept, new Comparator<Quotes.Quote>() {
                @Override public int compare(Quotes.Quote x, Quotes.Quote y) {
                    return Long.compare(x.time, y.time);
                }
            });
            if (localChanged) {
                local.quotes.clear();
                local.quotes.addAll(kept);
                if (local.title.isEmpty()) local.title = r.title;
                if (local.author.isEmpty()) local.author = r.author;
                changed.add(local);
            }
            r.quotes.clear();
            r.quotes.addAll(kept);
            if (kept.isEmpty()) remote.quotes.remove(id);
        }

        out.setsChanged.addAll(changed);

        // ---- words
        LinkedHashMap<String, Words.Entry> allWords = new LinkedHashMap<>();
        for (Words.Entry e : words) allWords.put(e.text.toLowerCase(Locale.ROOT), e);
        boolean wordsChanged = false;
        for (Words.Entry e : remote.words) {
            String k = e.text.toLowerCase(Locale.ROOT);
            Words.Entry mine = allWords.get(k);
            if (mine == null) {
                allWords.put(k, e);
                wordsChanged = true;
            } else if (e.time > mine.time) {
                allWords.put(k, e);
                wordsChanged = true;
            }
        }
        ArrayList<Words.Entry> keptWords = new ArrayList<>();
        for (Map.Entry<String, Words.Entry> e : allWords.entrySet()) {
            Long gone = deletedWords.get(e.getKey());
            if (gone != null && gone >= e.getValue().time) {
                if (words.contains(e.getValue())) wordsChanged = true;
                continue;
            }
            keptWords.add(e.getValue());
        }
        Collections.sort(keptWords, new Comparator<Words.Entry>() {
            @Override public int compare(Words.Entry x, Words.Entry y) {
                return Long.compare(y.time, x.time);
            }
        });
        remote.words.clear();
        remote.words.addAll(keptWords);
        out.wordsChanged = wordsChanged;
        out.words = keptWords;
        return out;
    }

    /** The list of books: names, online files, covers, drawings, and books deleted everywhere. */
    private static void mergeLibrary(List<Book> library, Map<String, Long> deletedBooks, Data remote, Outcome out) {
        for (Map.Entry<String, Long> e : remote.deletedBooks.entrySet()) later(deletedBooks, e.getKey(), e.getValue());
        // A book added again after it was deleted everywhere is back: forget that deletion.
        for (Book b : library) {
            Long gone = deletedBooks.get(b.key);
            if (!b.key.isEmpty() && gone != null && b.added > gone) deletedBooks.remove(b.key);
        }
        for (Map.Entry<String, Entry> me : remote.library.entrySet()) {
            Long gone = deletedBooks.get(me.getKey());
            if (gone != null && me.getValue().added > gone) deletedBooks.remove(me.getKey());
        }
        remote.deletedBooks.clear();
        remote.deletedBooks.putAll(deletedBooks);

        HashSet<String> here = new HashSet<>();
        for (Book b : library) {
            if (b.key.isEmpty()) continue;
            here.add(b.key);
            Long gone = deletedBooks.get(b.key);
            if (gone != null && gone >= b.added) {
                out.removed.add(b);
                remote.library.remove(b.key);
                continue;
            }
            Entry e = remote.library.get(b.key);
            if (e == null) {
                e = new Entry();
                e.title = b.title;
                e.author = b.author;
                e.format = b.format;
                e.source = b.source;
                e.size = b.size;
                e.added = b.added;
                e.infoTime = b.infoTime;
                e.asset = b.asset;
                e.cover = b.coverAsset;
                e.coverTime = b.coverAsset.isEmpty() ? 0 : b.coverTime;
                e.ink = b.inkAsset;
                e.inkTime = b.inkAsset.isEmpty() ? 0 : b.inkTime;
                remote.library.put(b.key, e);
                continue;
            }
            if (b.infoTime > e.infoTime) {
                e.title = b.title;
                e.author = b.author;
                e.infoTime = b.infoTime;
            } else if (e.infoTime > b.infoTime) {
                b.title = e.title;
                b.author = e.author;
                b.infoTime = e.infoTime;
                out.booksChanged = true;
            }
            if (e.asset.isEmpty() && !b.asset.isEmpty()) {
                e.asset = b.asset;
            } else if (!e.asset.isEmpty() && b.asset.isEmpty()) {
                b.asset = e.asset;
                out.booksChanged = true;
            }
            if (!b.coverAsset.isEmpty() && !b.coverPending && b.coverTime > e.coverTime) {
                e.cover = b.coverAsset;
                e.coverTime = b.coverTime;
            } else if (!e.cover.isEmpty() && e.coverTime > b.coverTime) {
                b.coverAsset = e.cover;
                b.coverTime = e.coverTime;
                b.coverPending = true;
                out.booksChanged = true;
            }
            if (!b.inkAsset.isEmpty() && !b.inkPending && b.inkTime > e.inkTime) {
                e.ink = b.inkAsset;
                e.inkTime = b.inkTime;
            } else if (!e.ink.isEmpty() && e.inkTime > b.inkTime) {
                b.inkAsset = e.ink;
                b.inkTime = e.inkTime;
                b.inkPending = true;
                out.booksChanged = true;
            }
        }
        // Books online that this device doesn't have yet.
        for (Map.Entry<String, Entry> me : new ArrayList<>(remote.library.entrySet())) {
            String key = me.getKey();
            Entry e = me.getValue();
            Long gone = deletedBooks.get(key);
            if (gone != null && gone >= e.added) {
                remote.library.remove(key);
                continue;
            }
            if (here.contains(key) || e.asset.isEmpty()) continue;
            Book b = new Book();
            b.id = "o" + key.substring(0, Math.min(14, key.length()));
            b.key = key;
            b.title = e.title;
            b.author = e.author;
            b.format = e.format;
            b.source = e.source;
            b.size = e.size;
            b.added = e.added;
            b.infoTime = e.infoTime;
            b.asset = e.asset;
            b.coverAsset = e.cover;
            b.coverTime = e.coverTime;
            b.coverPending = !e.cover.isEmpty();
            b.inkAsset = e.ink;
            b.inkTime = e.inkTime;
            b.inkPending = !e.ink.isEmpty();
            out.added.add(b);
            out.booksChanged = true;
        }
    }

    /**
     * The reading log: everything from both sides. The furthest progress and latest reading time
     * win; whether a book is finished follows whichever device decided it last; name changes
     * follow the latest change; entries removed on either side are removed everywhere.
     */
    static boolean mergeHistory(Map<String, History.Entry> local, Map<String, Long> deleted,
                                Map<String, History.Entry> remote, Map<String, Long> remoteDeleted) {
        for (Map.Entry<String, Long> e : remoteDeleted.entrySet()) later(deleted, e.getKey(), e.getValue());
        boolean changed = false;
        HashSet<String> keys = new HashSet<>(local.keySet());
        keys.addAll(remote.keySet());
        for (String k : keys) {
            History.Entry l = local.get(k), r = remote.get(k), m;
            if (l == null) {
                m = r.copy();
            } else if (r == null) {
                m = l.copy();
            } else {
                m = (l.updated >= r.updated ? l : r).copy();
                History.Entry further = r.progress > l.progress + 0.0005f ? r : l;
                m.progress = further.progress;
                m.where = further.where;
                m.lastRead = Math.max(l.lastRead, r.lastRead);
                m.started = l.started == 0 ? r.started : r.started == 0 ? l.started : Math.min(l.started, r.started);
                History.Entry decided = r.finishedSet > l.finishedSet ? r : l;
                m.finished = decided.finished;
                m.finishedSet = decided.finishedSet;
                if (m.thumb.isEmpty()) m.thumb = l.thumb.isEmpty() ? r.thumb : l.thumb;
            }
            Long gone = deleted.get(k);
            if (gone != null && gone >= Math.max(m.lastRead, m.updated)) {
                if (local.remove(k) != null) changed = true;
                remote.remove(k);
                continue;
            }
            if (l == null || !same(l, m)) {
                local.put(k, m);
                changed = true;
            }
            remote.put(k, m.copy());
        }
        remoteDeleted.clear();
        remoteDeleted.putAll(deleted);
        return changed;
    }

    private static boolean same(History.Entry a, History.Entry b) {
        return a.progress == b.progress && a.lastRead == b.lastRead && a.started == b.started
                && a.finished == b.finished && a.finishedSet == b.finishedSet && a.updated == b.updated
                && a.title.equals(b.title) && a.author.equals(b.author) && a.where.equals(b.where)
                && a.thumb.equals(b.thumb);
    }

    private static boolean inLibrary(Quotes.BookQuotes s, List<Book> library) {
        for (Book b : library) if (b.id.equals(s.bookId)) return true;
        return false;
    }

    private static boolean hasText(List<Quotes.Quote> list, String text) {
        for (Quotes.Quote q : list) if (q.text.equals(text)) return true;
        return false;
    }

    private static void copyPosition(Book b, Pos r, String device) {
        r.chapter = b.chapter;
        r.offset = b.offset;
        r.progress = b.progress;
        r.time = b.posTime;
        r.device = device;
    }

    private static void later(Map<String, Long> map, String key, Long time) {
        Long now = map.get(key);
        if (now == null || time > now) map.put(key, time);
    }

    private static String bookIdFor(String id, QSet r, List<Book> library, List<Quotes.BookQuotes> sets) {
        if (id.startsWith("k:")) {
            String key = id.substring(2);
            for (Book b : library) if (key.equals(b.key)) return b.id;
        }
        String title = titleKey(r == null ? "" : r.title);
        for (Book b : library) if (titleKey(b.title).equals(title)) return b.id;
        return "synced-" + Integer.toHexString(id.hashCode() & 0x7fffffff);
    }

    static Set<String> keys(Map<String, ?> m) {
        return m.keySet();
    }
}
