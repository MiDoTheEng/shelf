package com.shelf.reader.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Util {
    private Util() {}

    public static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Decodes XML/HTML text honouring a UTF-16 byte-order mark, UTF-8 otherwise. */
    public static String decodeText(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        int off = (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) ? 3 : 0;
        return new String(b, off, b.length - off, StandardCharsets.UTF_8);
    }

    public static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?<![\\w:.-])" + Pattern.quote(name)
                + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").matcher(tag);
        if (m.find()) return unescape(m.group(1) != null ? m.group(1) : m.group(2));
        return null;
    }

    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int semi;
            if (c == '&' && (semi = s.indexOf(';', i)) > 0 && semi - i <= 10) {
                String e = s.substring(i + 1, semi);
                String r = null;
                switch (e) {
                    case "amp": r = "&"; break;
                    case "lt": r = "<"; break;
                    case "gt": r = ">"; break;
                    case "quot": r = "\""; break;
                    case "apos": r = "'"; break;
                    case "nbsp": r = " "; break;
                    default:
                        try {
                            if (e.startsWith("#x") || e.startsWith("#X")) {
                                r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                            } else if (e.startsWith("#")) {
                                r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                            }
                        } catch (RuntimeException ignored) {
                            r = null;
                        }
                }
                if (r != null) {
                    sb.append(r);
                    i = semi + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Strips tags and collapses whitespace. */
    public static String plain(String html) {
        if (html == null) return "";
        return unescape(html.replaceAll("(?s)<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
    }

    public static String pctDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%' && i + 2 < in.length) {
                int hi = Character.digit(in[i + 1], 16), lo = Character.digit(in[i + 2], 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            out.write(in[i]);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Resolves an href against a directory ("" or "dir/") into a normalised archive path. */
    public static String resolve(String baseDir, String href) {
        int i = href.indexOf('#');
        if (i >= 0) href = href.substring(0, i);
        i = href.indexOf('?');
        if (i >= 0) href = href.substring(0, i);
        href = pctDecode(href.trim());
        String p = href.startsWith("/") ? href.substring(1) : baseDir + href;
        ArrayList<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    public static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    /** Percent-encodes an archive path so it can be used inside a URL. */
    public static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                sb.append(seg);
            }
        }
        return sb.toString();
    }

    public static String mimeFor(String path) {
        String p = path.toLowerCase();
        int dot = p.lastIndexOf('.');
        String ext = dot < 0 ? "" : p.substring(dot + 1);
        switch (ext) {
            case "xhtml": case "xht": case "html": case "htm": return "application/xhtml+xml";
            case "css": return "text/css";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "svg": return "image/svg+xml";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "js": return "text/javascript";
            case "ncx": case "opf": case "xml": return "application/xml";
            default: return "application/octet-stream";
        }
    }
}
