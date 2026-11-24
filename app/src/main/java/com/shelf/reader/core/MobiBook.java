package com.shelf.reader.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reader for unlocked Kindle files: MOBI, AZW, PRC and AZW3 (KF8). */
public final class MobiBook implements BookContent {
    private static final long NONE = 0xFFFFFFFFL;
    private static final int MAX_CHUNK = 300_000;

    private final RandomAccessFile raf;
    private long fileLen;
    private long[] recOff = new long[0];
    private Charset charset = Charset.forName("windows-1252");
    private String title = "";
    private String author = "";
    private long firstImage = NONE;
    private long coverOffset = NONE;
    private long thumbOffset = NONE;
    private boolean kf8;
    private final List<String> chapters = new ArrayList<>();
    private final List<String> chapterTitles = new ArrayList<>();
    private final List<byte[]> flows = new ArrayList<>();

    public MobiBook(File file) throws IOException {
        raf = new RandomAccessFile(file, "r");
        try {
            load();
        } catch (IOException | RuntimeException e) {
            try { raf.close(); } catch (IOException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException("Could not read this Kindle file", e);
        }
    }

    // ---------------------------------------------------------------- parsing

    private void load() throws IOException {
        fileLen = raf.length();
        if (fileLen < 78) throw new IOException("Not a valid Kindle file");
        byte[] head = new byte[78];
        raf.seek(0);
        raf.readFully(head);
        String type = new String(head, 60, 8, StandardCharsets.ISO_8859_1);
        if (!type.equals("BOOKMOBI") && !type.equals("TEXtREAd")) {
            throw new IOException("Not a valid Kindle file");
        }
        int numRec = u16(head, 76);
        if (numRec < 2) throw new IOException("Not a valid Kindle file");
        byte[] table = new byte[numRec * 8];
        raf.readFully(table);
        recOff = new long[numRec];
        for (int i = 0; i < numRec; i++) recOff[i] = u32(table, i * 8);

        byte[] r0 = record(0);
        if (r0.length < 16) throw new IOException("Not a valid Kindle file");
        int compression = u16(r0, 0);
        int textRecs = u16(r0, 8);
        int encryption = u16(r0, 12);
        if (encryption != 0) {
            throw new IOException("This Kindle book is DRM-protected and cannot be opened");
        }

        int version = 0, extraFlags = 0;
        long huffOff = NONE, huffNum = 0, fdst = NONE;
        String pdbName = new String(head, 0, 32, StandardCharsets.ISO_8859_1);
        int z = pdbName.indexOf('\0');
        title = (z >= 0 ? pdbName.substring(0, z) : pdbName).replace('_', ' ').trim();

        boolean hasMobi = r0.length >= 0x18 && r0[16] == 'M' && r0[17] == 'O' && r0[18] == 'B' && r0[19] == 'I';
        if (hasMobi) {
            int mobiLen = (int) u32(r0, 0x14);
            if (r0.length >= 0x20 && u32(r0, 0x1C) == 65001) charset = StandardCharsets.UTF_8;
            if (r0.length >= 0x28) version = (int) u32(r0, 0x24);
            if (r0.length >= 0x5C) {
                long tOff = u32(r0, 0x54), tLen = u32(r0, 0x58);
                if (tLen > 0 && tOff + tLen <= r0.length) {
                    title = new String(r0, (int) tOff, (int) tLen, charset).trim();
                }
            }
            if (r0.length >= 0x70) firstImage = u32(r0, 0x6C);
            if (r0.length >= 0x78) {
                huffOff = u32(r0, 0x70);
                huffNum = u32(r0, 0x74);
            }
            if (version == 8 && r0.length >= 0xC4) fdst = u32(r0, 0xC0);
            if (mobiLen >= 0xE4 && version >= 5 && r0.length >= 0xF4) extraFlags = u16(r0, 0xF2);
            if (r0.length >= 0x84 && (u32(r0, 0x80) & 0x40) != 0) parseExth(r0, 16 + mobiLen);
        }
        kf8 = version == 8;

        Huff huff = null;
        if (compression == 17480) {
            if (huffOff == NONE || huffNum < 2) throw new IOException("Unsupported Kindle compression");
            huff = new Huff(record((int) huffOff));
            for (int i = 1; i < huffNum; i++) huff.addCdic(record((int) huffOff + i));
        } else if (compression != 1 && compression != 2) {
            throw new IOException("Unsupported Kindle compression");
        }

        ByteArrayOutputStream text = new ByteArrayOutputStream(textRecs * 4096);
        for (int i = 1; i <= textRecs && i < numRec; i++) {
            byte[] d = record(i);
            int size = d.length - trailing(d, d.length, extraFlags);
            if (size <= 0) continue;
            if (compression == 1) {
                text.write(d, 0, size);
            } else if (compression == 2) {
                palmDoc(d, size, text);
            } else {
                byte[] in = new byte[size];
                System.arraycopy(d, 0, in, 0, size);
                huff.unpack(in, text, 0);
            }
        }
        byte[] raw = text.toByteArray();

        if (kf8) {
            buildKf8(raw, fdst);
        } else {
            buildMobi6(new String(raw, charset));
        }
        if (chapters.isEmpty()) throw new IOException("This Kindle file has no readable text");
    }

    private void parseExth(byte[] r0, int pos) {
        if (pos + 12 > r0.length || r0[pos] != 'E' || r0[pos + 1] != 'X' || r0[pos + 2] != 'T' || r0[pos + 3] != 'H') {
            return;
        }
        long count = u32(r0, pos + 8);
        pos += 12;
        for (long i = 0; i < count && pos + 8 <= r0.length; i++) {
            int type = (int) u32(r0, pos);
            int len = (int) u32(r0, pos + 4);
            if (len < 8 || pos + len > r0.length) break;
            int dataLen = len - 8;
            switch (type) {
                case 100:
                    if (author.isEmpty()) author = new String(r0, pos + 8, dataLen, charset).trim();
                    break;
                case 503:
                    if (dataLen > 0) title = new String(r0, pos + 8, dataLen, charset).trim();
                    break;
                case 201:
                    if (dataLen >= 4) coverOffset = u32(r0, pos + 8);
                    break;
                case 202:
                    if (dataLen >= 4) thumbOffset = u32(r0, pos + 8);
                    break;
                default:
                    break;
            }
            pos += len;
        }
    }

    private void buildMobi6(String html) {
        html = html.replaceAll("(?is)<head\\b.*?</head>", "");
        html = html.replaceAll("(?i)</?(?:html|body)\\b[^>]*>", "");
        html = html.replaceAll("(?i)(?<![a-z])recindex\\s*=\\s*[\"']?0*(\\d+)[\"']?", "src=\"/img/$1\"");
        String[] parts = html.split("(?i)</?mbp:pagebreak\\s*/?>");
        List<String> chunks = new ArrayList<>();
        for (String p : parts) {
            if (isBlank(p)) continue;
            splitBig(p, chunks);
        }
        for (String c : chunks) {
            chapters.add(c);
            chapterTitles.add(headingOf(c));
        }
    }

    private void buildKf8(byte[] raw, long fdstIdx) throws IOException {
        int textEnd = raw.length;
        if (fdstIdx != NONE && fdstIdx > 0 && fdstIdx < recOff.length) {
            byte[] f = record((int) fdstIdx);
            if (f.length >= 12 && f[0] == 'F' && f[1] == 'D' && f[2] == 'S' && f[3] == 'T') {
                int n = (int) u32(f, 8);
                for (int i = 0; i < n && 12 + i * 8 + 8 <= f.length; i++) {
                    long s = u32(f, 12 + i * 8), e = u32(f, 16 + i * 8);
                    if (s > e || e > raw.length) break;
                    if (i == 0) {
                        textEnd = (int) e;
                        flows.add(new byte[0]);
                    } else {
                        byte[] flow = new byte[(int) (e - s)];
                        System.arraycopy(raw, (int) s, flow, 0, flow.length);
                        flows.add(flow);
                    }
                }
            }
        }
        String text = new String(raw, 0, textEnd, charset);
        Matcher m = Pattern.compile("(?:<\\?xml[^>]*\\?>\\s*)?(?:<!DOCTYPE[^>]*>\\s*)?<html[\\s>]").matcher(text);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) starts.add(m.start());
        if (starts.isEmpty()) {
            buildMobi6(rewriteKindleLinks(text));
            kf8 = false;
            return;
        }
        for (int i = 0; i < starts.size(); i++) {
            int a = starts.get(i);
            int b = i + 1 < starts.size() ? starts.get(i + 1) : text.length();
            String part = text.substring(a, b);
            // Each file is stored as an empty skeleton followed by its content fragments.
            int body = part.indexOf("</body>");
            int end = body < 0 ? -1 : part.indexOf("</html>", body);
            if (end >= 0) {
                String tail = part.substring(end + 7);
                part = part.substring(0, body) + tail + "</body></html>";
            }
            part = part.replaceAll("^<\\?xml[^>]*\\?>\\s*", "");
            part = part.replaceAll("(?i)<(?:title|script)\\b[^<>]*/>", "");
            part = part.replaceAll(
                    "(?i)<(a|div|span|p|i|b|em|strong|h[1-6]|td|section|blockquote)(\\s[^<>]*?)?/>",
                    "<$1$2></$1>");
            part = rewriteKindleLinks(part);
            if (isBlank(part.replaceAll("(?is)<head\\b.*?</head>", ""))) continue;
            chapters.add(part);
            chapterTitles.add(headingOf(part));
        }
    }

    private static String rewriteKindleLinks(String s) {
        StringBuffer sb = new StringBuffer(s.length());
        Matcher m = Pattern.compile("kindle:(embed|flow):([0-9A-Va-v]+)(?:\\?mime=[^\"'\\s>)]*)?").matcher(s);
        while (m.find()) {
            String rep;
            try {
                int n = Integer.parseInt(m.group(2), 32);
                rep = (m.group(1).equals("embed") ? "/img/" : "/flow/") + n;
            } catch (NumberFormatException e) {
                rep = "about:blank";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isBlank(String html) {
        if (html.length() > 20000) return false;
        String lower = html.toLowerCase();
        if (lower.contains("<img") || lower.contains("<svg") || lower.contains("<image")) return false;
        return Util.plain(html).isEmpty();
    }

    private static void splitBig(String s, List<String> out) {
        while (s.length() > MAX_CHUNK) {
            int cut = s.lastIndexOf("<p", MAX_CHUNK);
            if (cut < MAX_CHUNK / 2) cut = s.lastIndexOf("<br", MAX_CHUNK);
            if (cut < MAX_CHUNK / 2) cut = s.lastIndexOf('<', MAX_CHUNK);
            if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK;
            out.add(s.substring(0, cut));
            s = s.substring(cut);
        }
        out.add(s);
    }

    private static String headingOf(String html) {
        String headPart = html.length() > 6000 ? html.substring(0, 6000) : html;
        Matcher m = Pattern.compile("(?is)<h([1-3])\\b[^>]*>(.*?)</h\\1>").matcher(headPart);
        if (m.find()) {
            String t = Util.plain(m.group(2));
            if (!t.isEmpty() && t.length() <= 90) return t;
        }
        return null;
    }

    // ------------------------------------------------------------ record access

    private synchronized byte[] record(int i) throws IOException {
        if (i < 0 || i >= recOff.length) throw new IOException("Damaged Kindle file");
        long start = recOff[i];
        long end = i + 1 < recOff.length ? recOff[i + 1] : fileLen;
        if (end > fileLen) end = fileLen;
        if (start > end || end - start > 64L * 1024 * 1024) throw new IOException("Damaged Kindle file");
        byte[] b = new byte[(int) (end - start)];
        raf.seek(start);
        raf.readFully(b);
        return b;
    }

    private static String imageMime(byte[] b) {
        if (b.length < 4) return null;
        if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) return "image/jpeg";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return "image/webp";
        return null;
    }

    private byte[] image(long offset) {
        if (firstImage == NONE || offset == NONE) return null;
        try {
            byte[] b = record((int) (firstImage + offset));
            return imageMime(b) == null ? null : b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ BookContent

    @Override public String title() { return title; }
    @Override public String author() { return author; }

    @Override public byte[] cover() {
        byte[] b = image(coverOffset);
        if (b == null) b = image(thumbOffset);
        for (int i = 0; b == null && i < 4; i++) b = image(i);
        return b;
    }

    @Override public int chapterCount() { return chapters.size(); }
    @Override public String chapterPath(int i) { return "c/" + i + ".html"; }

    @Override public int chapterForPath(String path) {
        Matcher m = Pattern.compile("^c/(\\d+)\\.html$").matcher(path);
        if (!m.matches()) return -1;
        try {
            int i = Integer.parseInt(m.group(1));
            return i < chapters.size() ? i : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override public String chapterTitle(int i) { return chapterTitles.get(i); }
    @Override public long chapterWeight(int i) { return Math.max(1, chapters.get(i).length()); }

    @Override public Res open(String path) throws IOException {
        int ch = chapterForPath(path);
        if (ch >= 0) {
            String html = chapters.get(ch);
            if (!kf8) {
                html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>"
                        + html + "</body></html>";
            }
            return new Res("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        }
        try {
            if (path.startsWith("img/")) {
                int n = Integer.parseInt(path.substring(4));
                byte[] b = n >= 1 ? image(n - 1) : null;
                return b == null ? null : new Res(imageMime(b), null, new ByteArrayInputStream(b));
            }
            if (path.startsWith("flow/")) {
                int n = Integer.parseInt(path.substring(5));
                if (n < 1 || n >= flows.size()) return null;
                String css = rewriteKindleLinks(new String(flows.get(n), charset));
                boolean svg = css.contains("<svg");
                return new Res(svg ? "image/svg+xml" : "text/css", "UTF-8",
                        new ByteArrayInputStream(css.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    @Override public java.util.List<String> images(int max) {
        ArrayList<String> out = new ArrayList<>();
        if (firstImage == NONE) return out;
        for (long i = 0; firstImage + i < recOff.length && out.size() < max && i < 400; i++) {
            if (image(i) != null) out.add("img/" + (i + 1));
        }
        return out;
    }

    @Override public void close() throws IOException { raf.close(); }

    // ------------------------------------------------------------ decompression

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff);
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** Number of bytes of trailing data entries at the end of a text record. */
    private static int trailing(byte[] d, int size, int flags) {
        int num = 0;
        int f = flags >> 1;
        while (f != 0) {
            if ((f & 1) != 0) num += trailingEntry(d, size - num);
            f >>= 1;
        }
        if ((flags & 1) != 0 && size - num - 1 >= 0) num += (d[size - num - 1] & 3) + 1;
        return Math.max(0, Math.min(num, size));
    }

    private static int trailingEntry(byte[] d, int size) {
        int bitpos = 0, result = 0;
        while (size > 0) {
            int v = d[size - 1] & 0xff;
            result |= (v & 0x7f) << bitpos;
            bitpos += 7;
            size--;
            if ((v & 0x80) != 0 || bitpos >= 28) break;
        }
        return result;
    }

    private static void palmDoc(byte[] d, int size, ByteArrayOutputStream text) {
        byte[] out = new byte[Math.max(8192, size * 8)];
        int o = 0, i = 0;
        while (i < size) {
            int c = d[i++] & 0xff;
            if (o + 16 > out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
            if (c >= 1 && c <= 8) {
                for (int k = 0; k < c && i < size; k++) out[o++] = d[i++];
            } else if (c < 0x80) {
                out[o++] = (byte) c;
            } else if (c >= 0xC0) {
                out[o++] = ' ';
                out[o++] = (byte) (c ^ 0x80);
            } else if (i < size) {
                int v = ((c << 8) | (d[i++] & 0xff)) & 0x3fff;
                int dist = v >> 3, len = (v & 7) + 3;
                if (dist <= 0 || dist > o) continue;
                for (int k = 0; k < len; k++) {
                    out[o] = out[o - dist];
                    o++;
                }
            }
        }
        text.write(out, 0, o);
    }

    /** HUFF/CDIC decompressor used by books produced with Amazon's own tools. */
    private static final class Huff {
        private final int[] len1 = new int[256];
        private final boolean[] term1 = new boolean[256];
        private final long[] max1 = new long[256];
        private final long[] mincode = new long[33];
        private final long[] maxcode = new long[33];
        private final List<byte[]> dict = new ArrayList<>();
        private final List<Boolean> expanded = new ArrayList<>();

        Huff(byte[] huff) throws IOException {
            if (huff.length < 24 || huff[0] != 'H' || huff[1] != 'U' || huff[2] != 'F' || huff[3] != 'F') {
                throw new IOException("Damaged Kindle file");
            }
            int off1 = (int) u32(huff, 8), off2 = (int) u32(huff, 12);
            if (off1 + 1024 > huff.length || off2 + 256 > huff.length) throw new IOException("Damaged Kindle file");
            for (int i = 0; i < 256; i++) {
                long v = u32(huff, off1 + 4 * i);
                int codelen = (int) (v & 0x1f);
                if (codelen == 0) throw new IOException("Damaged Kindle file");
                len1[i] = codelen;
                term1[i] = (v & 0x80) != 0;
                max1[i] = (((v >>> 8) + 1) << (32 - codelen)) - 1;
            }
            mincode[0] = 0;
            maxcode[0] = (1L << 32) - 1;
            for (int codelen = 1; codelen <= 32; codelen++) {
                long mn = u32(huff, off2 + 8 * (codelen - 1));
                long mx = u32(huff, off2 + 8 * (codelen - 1) + 4);
                mincode[codelen] = mn << (32 - codelen);
                maxcode[codelen] = ((mx + 1) << (32 - codelen)) - 1;
            }
        }

        void addCdic(byte[] cdic) throws IOException {
            if (cdic.length < 16 || cdic[0] != 'C' || cdic[1] != 'D' || cdic[2] != 'I' || cdic[3] != 'C') {
                throw new IOException("Damaged Kindle file");
            }
            long phrases = u32(cdic, 8);
            int bits = (int) u32(cdic, 12);
            long n = Math.min(1L << bits, phrases - dict.size());
            for (int i = 0; i < n; i++) {
                int off = u16(cdic, 16 + 2 * i);
                int blen = u16(cdic, 16 + off);
                int from = 18 + off, to = from + (blen & 0x7fff);
                if (to > cdic.length) throw new IOException("Damaged Kindle file");
                dict.add(java.util.Arrays.copyOfRange(cdic, from, to));
                expanded.add((blen & 0x8000) != 0);
            }
        }

        void unpack(byte[] data, ByteArrayOutputStream out, int depth) throws IOException {
            if (depth > 32) throw new IOException("Damaged Kindle file");
            long bitsleft = data.length * 8L;
            byte[] d = java.util.Arrays.copyOf(data, data.length + 8);
            int pos = 0;
            long x = u64(d, pos);
            int n = 32;
            while (true) {
                if (n <= 0) {
                    pos += 4;
                    if (pos + 8 > d.length) break;
                    x = u64(d, pos);
                    n += 32;
                }
                long code = (x >>> n) & 0xFFFFFFFFL;
                int idx = (int) (code >>> 24);
                int codelen = len1[idx];
                long max = max1[idx];
                if (!term1[idx]) {
                    while (codelen < 32 && code < mincode[codelen]) codelen++;
                    max = maxcode[codelen];
                }
                n -= codelen;
                bitsleft -= codelen;
                if (bitsleft < 0) break;
                int r = (int) ((max - code) >>> (32 - codelen));
                if (r < 0 || r >= dict.size()) throw new IOException("Damaged Kindle file");
                byte[] slice = dict.get(r);
                if (!expanded.get(r)) {
                    ByteArrayOutputStream sub = new ByteArrayOutputStream();
                    unpack(slice, sub, depth + 1);
                    slice = sub.toByteArray();
                    dict.set(r, slice);
                    expanded.set(r, true);
                }
                out.write(slice, 0, slice.length);
            }
        }

        private static long u64(byte[] b, int o) {
            return (u32(b, o) << 32) | u32(b, o + 4);
        }
    }
}
