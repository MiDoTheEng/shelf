package com.shelf.reader.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class EpubBook implements BookContent {
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries = new HashMap<>();
    private final Map<String, String> lower = new HashMap<>();
    private final List<String> spine = new ArrayList<>();
    private final Map<String, Integer> spineIndex = new HashMap<>();
    private final Map<String, String> mimeByPath = new HashMap<>();
    private String[] titles = new String[0];
    private String title = "";
    private String author = "";
    private String coverPath;

    public EpubBook(File file) throws IOException {
        zip = new ZipFile(file);
        try {
            load();
        } catch (IOException | RuntimeException e) {
            try { zip.close(); } catch (IOException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException("Could not read this EPUB", e);
        }
    }

    private void load() throws IOException {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) continue;
            entries.put(e.getName(), e);
            lower.put(e.getName().toLowerCase(Locale.ROOT), e.getName());
        }
        if (find("META-INF/rights.xml") != null) {
            throw new IOException("This EPUB is DRM-protected and cannot be opened");
        }

        String opfPath = null;
        String container = text("META-INF/container.xml");
        if (container != null) {
            Matcher m = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']").matcher(container);
            if (m.find()) opfPath = Util.resolve("", Util.unescape(m.group(1)));
        }
        if (opfPath == null || find(opfPath) == null) {
            opfPath = null;
            for (String name : entries.keySet()) {
                if (name.toLowerCase(Locale.ROOT).endsWith(".opf")) { opfPath = name; break; }
            }
        }
        if (opfPath == null) throw new IOException("Not a valid EPUB file");
        String opf = text(opfPath);
        if (opf == null) throw new IOException("Not a valid EPUB file");
        opf = opf.replaceAll("(?s)<!--.*?-->", "");
        String base = Util.dirOf(find(opfPath).getName());

        Matcher m = Pattern.compile("(?s)<(?:\\w+:)?title\\b[^>]*>(.*?)</(?:\\w+:)?title>").matcher(opf);
        if (m.find()) title = Util.plain(m.group(1));
        m = Pattern.compile("(?s)<(?:\\w+:)?creator\\b[^>]*>(.*?)</(?:\\w+:)?creator>").matcher(opf);
        if (m.find()) author = Util.plain(m.group(1));

        Map<String, String> hrefById = new HashMap<>();
        Map<String, String> typeById = new HashMap<>();
        String navPath = null, coverByProp = null, coverByName = null;
        m = Pattern.compile("(?s)<(?:\\w+:)?item\\b([^>]*)>").matcher(opf);
        while (m.find()) {
            String tag = m.group(1);
            String id = Util.attr(tag, "id"), href = Util.attr(tag, "href");
            if (href == null) continue;
            String type = Util.attr(tag, "media-type");
            String props = Util.attr(tag, "properties");
            String path = Util.resolve(base, href);
            ZipEntry ze = find(path);
            if (ze != null) path = ze.getName();
            if (id != null) {
                hrefById.put(id, path);
                typeById.put(id, type == null ? "" : type);
            }
            if (type != null && !type.isEmpty()) mimeByPath.put(path, type);
            boolean image = type != null && type.startsWith("image/");
            if (props != null && (" " + props + " ").contains(" nav ")) navPath = path;
            if (image && props != null && props.contains("cover-image")) coverByProp = path;
            if (image && coverByName == null
                    && ((id != null && id.toLowerCase(Locale.ROOT).contains("cover"))
                    || path.toLowerCase(Locale.ROOT).contains("cover"))) {
                coverByName = path;
            }
        }

        String coverByMeta = null;
        m = Pattern.compile("(?s)<(?:\\w+:)?meta\\b([^>]*)>").matcher(opf);
        while (m.find()) {
            String tag = m.group(1);
            if ("cover".equals(Util.attr(tag, "name"))) {
                String id = Util.attr(tag, "content");
                if (id != null && hrefById.containsKey(id) && typeById.get(id).startsWith("image/")) {
                    coverByMeta = hrefById.get(id);
                }
            }
        }
        coverPath = coverByMeta != null ? coverByMeta : coverByProp != null ? coverByProp : coverByName;
        if (coverPath != null && find(coverPath) == null) coverPath = null;

        m = Pattern.compile("(?s)<(?:\\w+:)?itemref\\b([^>]*)>").matcher(opf);
        while (m.find()) {
            String idref = Util.attr(m.group(1), "idref");
            String path = idref == null ? null : hrefById.get(idref);
            if (path == null || find(path) == null || spineIndex.containsKey(path)) continue;
            String type = typeById.get(idref);
            if (type != null && type.startsWith("image/")) continue;
            spineIndex.put(path, spine.size());
            spine.add(path);
        }
        if (spine.isEmpty()) throw new IOException("This EPUB has no readable chapters");
        titles = new String[spine.size()];

        String ncxPath = null;
        m = Pattern.compile("(?s)<(?:\\w+:)?spine\\b([^>]*)>").matcher(opf);
        if (m.find()) {
            String tocId = Util.attr(m.group(1), "toc");
            if (tocId != null) ncxPath = hrefById.get(tocId);
        }
        try {
            if (ncxPath != null) {
                String ncx = text(ncxPath);
                if (ncx != null) parseNcx(ncx, Util.dirOf(ncxPath));
            }
            if (navPath != null) {
                String nav = text(navPath);
                if (nav != null) parseNav(nav, Util.dirOf(navPath));
            }
        } catch (RuntimeException ignored) {
            // A broken table of contents must never stop the book from opening.
        }
    }

    private void parseNcx(String ncx, String base) {
        int start = ncx.indexOf("navMap");
        if (start < 0) return;
        Matcher m = Pattern.compile("(?s)<(?:\\w+:)?text\\b[^>]*>(.*?)</(?:\\w+:)?text>\\s*</(?:\\w+:)?navLabel>"
                + "\\s*<(?:\\w+:)?content\\b([^>]*)>").matcher(ncx);
        m.region(start, ncx.length());
        while (m.find()) {
            String src = Util.attr(m.group(2), "src");
            if (src != null) setTitle(Util.resolve(base, src), m.group(1));
        }
    }

    private void parseNav(String nav, String base) {
        Matcher m = Pattern.compile("(?s)<a\\b([^>]*)>(.*?)</a>").matcher(nav);
        while (m.find()) {
            String href = Util.attr(m.group(1), "href");
            if (href != null && !href.startsWith("#")) setTitle(Util.resolve(base, href), m.group(2));
        }
    }

    private void setTitle(String path, String label) {
        int idx = chapterForPath(path);
        if (idx < 0 || titles[idx] != null) return;
        String t = Util.plain(label);
        if (!t.isEmpty()) titles[idx] = t;
    }

    private ZipEntry find(String path) {
        ZipEntry e = entries.get(path);
        if (e != null) return e;
        String real = lower.get(path.toLowerCase(Locale.ROOT));
        return real == null ? null : entries.get(real);
    }

    private String text(String path) throws IOException {
        ZipEntry e = find(path);
        if (e == null) return null;
        return Util.decodeText(Util.readAll(zip.getInputStream(e)));
    }

    @Override public String title() { return title; }
    @Override public String author() { return author; }

    @Override public byte[] cover() {
        try {
            if (coverPath == null) return null;
            return Util.readAll(zip.getInputStream(find(coverPath)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override public int chapterCount() { return spine.size(); }
    @Override public String chapterPath(int i) { return Util.encodePath(spine.get(i)); }

    @Override public int chapterForPath(String path) {
        Integer i = spineIndex.get(path);
        if (i != null) return i;
        String real = lower.get(path.toLowerCase(Locale.ROOT));
        i = real == null ? null : spineIndex.get(real);
        return i == null ? -1 : i;
    }

    @Override public String chapterTitle(int i) { return titles[i]; }

    @Override public long chapterWeight(int i) {
        ZipEntry e = find(spine.get(i));
        long size = e == null ? -1 : e.getSize();
        return size > 0 ? size : 1;
    }

    @Override public Res open(String path) throws IOException {
        ZipEntry e = find(path);
        if (e == null) return null;
        String mime = mimeByPath.get(e.getName());
        if (mime == null || mime.isEmpty()) mime = Util.mimeFor(e.getName());
        if (spineIndex.containsKey(e.getName()) && !mime.equals("text/html") && !mime.equals("image/svg+xml")) {
            mime = "application/xhtml+xml";
        }
        return new Res(mime, null, zip.getInputStream(e));
    }

    @Override public java.util.List<String> images(int max) {
        ArrayList<String> out = new ArrayList<>();
        if (coverPath != null) out.add(coverPath);
        for (Map.Entry<String, String> e : mimeByPath.entrySet()) {
            if (out.size() >= max) break;
            String m = e.getValue();
            if (m.startsWith("image/") && !m.contains("svg") && !out.contains(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    @Override public void close() throws IOException { zip.close(); }
}
