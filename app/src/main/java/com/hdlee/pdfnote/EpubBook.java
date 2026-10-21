package com.hdlee.pdfnote;

import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A read-only EPUB package: reading order (spine), table of contents and the files inside the zip. */
final class EpubBook implements Closeable {
    static final class Entry {
        final String title, path;
        Entry(String title, String path) { this.title = title; this.path = path; }
    }

    private final ZipFile zip;
    private final Map<String, String> names = new HashMap<>();
    final List<String> spine = new ArrayList<>();
    final List<Entry> toc = new ArrayList<>();
    String title = "";

    private EpubBook(ZipFile zip) {
        this.zip = zip;
        java.util.Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) { String n = all.nextElement().getName(); names.put(n.toLowerCase(Locale.ROOT), n); }
    }

    static EpubBook open(File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        EpubBook book = new EpubBook(zip);
        try { book.parse(); } catch (IOException | RuntimeException e) { zip.close(); throw e instanceof IOException ? (IOException) e : new IOException("EPUB 구조를 읽을 수 없습니다"); }
        return book;
    }

    @Override public void close() { try { zip.close(); } catch (IOException ignored) { } }

    /** Bytes of a file in the package (case-insensitive path), or null when it is missing. */
    byte[] read(String path) {
        String real = path == null ? null : names.get(path.toLowerCase(Locale.ROOT));
        ZipEntry entry = real == null ? null : zip.getEntry(real);
        if (entry == null || entry.isDirectory()) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); if (out.size() > 64 * 1024 * 1024) return null; }
            return out.toByteArray();
        } catch (IOException e) { return null; }
    }

    int spineIndex(String path) {
        for (int i = 0; i < spine.size(); i++) if (spine.get(i).equalsIgnoreCase(path)) return i;
        return -1;
    }

    /** Title of the table-of-contents entry that the chapter [index] belongs to (the last entry that starts at or before it). */
    String chapterTitle(int index) {
        String found = null;
        for (Entry e : toc) { int at = spineIndex(e.path); if (at >= 0 && at <= index) found = e.title; }
        return found;
    }

    static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".xhtml") || p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    // ---- parsing ----

    private void parse() throws IOException {
        byte[] container = read("META-INF/container.xml");
        if (container == null) throw new IOException("EPUB 파일이 아닙니다");
        String opfPath = null;
        try {
            XmlPullParser p = parser(container);
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next())
                if (t == XmlPullParser.START_TAG && "rootfile".equals(local(p.getName())) && opfPath == null) opfPath = p.getAttributeValue(null, "full-path");
        } catch (Exception e) { throw new IOException("EPUB 구조를 읽을 수 없습니다"); }
        if (opfPath == null) throw new IOException("EPUB 본문 목록을 찾을 수 없습니다");
        opfPath = Uri.decode(opfPath);
        byte[] opf = read(opfPath);
        if (opf == null) throw new IOException("EPUB 본문 목록을 찾을 수 없습니다");
        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        Map<String, String[]> manifest = new HashMap<>();   // id -> {path, media-type, properties}
        List<String> order = new ArrayList<>();
        String ncxId = null;
        try {
            XmlPullParser p = parser(opf);
            boolean inTitle = false;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if (n.equals("item")) {
                        String id = p.getAttributeValue(null, "id"), href = p.getAttributeValue(null, "href");
                        if (id != null && href != null) manifest.put(id, new String[]{resolve(base, href), String.valueOf(p.getAttributeValue(null, "media-type")), String.valueOf(p.getAttributeValue(null, "properties"))});
                    } else if (n.equals("itemref")) {
                        String ref = p.getAttributeValue(null, "idref");
                        if (ref != null) order.add(ref);
                    } else if (n.equals("spine")) ncxId = p.getAttributeValue(null, "toc");
                    else if (n.equals("title") && title.isEmpty()) inTitle = true;
                } else if (t == XmlPullParser.TEXT && inTitle) { title = p.getText().trim(); inTitle = false; }
                else if (t == XmlPullParser.END_TAG) inTitle = false;
            }
        } catch (Exception e) { throw new IOException("EPUB 본문 목록을 읽을 수 없습니다"); }

        for (String id : order) {
            String[] item = manifest.get(id);
            if (item != null && item[1].toLowerCase(Locale.ROOT).contains("html") && names.containsKey(item[0].toLowerCase(Locale.ROOT))) spine.add(item[0]);
        }
        if (spine.isEmpty()) throw new IOException("읽을 수 있는 본문이 없습니다");

        // table of contents: EPUB 3 navigation document first, then the EPUB 2 NCX
        for (String[] item : manifest.values()) if (item[2].contains("nav") && toc.isEmpty()) parseNav(item[0]);
        if (toc.isEmpty() && ncxId != null && manifest.containsKey(ncxId)) parseNcx(manifest.get(ncxId)[0]);
        if (toc.isEmpty()) for (String[] item : manifest.values()) if (item[1].contains("dtbncx") && toc.isEmpty()) parseNcx(item[0]);
    }

    private void parseNav(String path) {
        byte[] data = read(path);
        if (data == null) return;
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        try {
            XmlPullParser p = parser(data);
            boolean inToc = false; int navDepth = 0; String href = null; StringBuilder text = null;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if (n.equals("nav")) {
                        String type = p.getAttributeValue(null, "epub:type");
                        if (type == null) type = p.getAttributeValue(null, "type");
                        if (!inToc && type != null && type.contains("toc")) { inToc = true; navDepth = 1; } else if (inToc) navDepth++;
                    } else if (inToc && n.equals("a")) { href = p.getAttributeValue(null, "href"); text = new StringBuilder(); }
                } else if (t == XmlPullParser.TEXT && text != null) text.append(p.getText());
                else if (t == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    if (inToc && n.equals("a") && href != null && text != null) { add(dir, href, text.toString()); href = null; text = null; }
                    else if (inToc && n.equals("nav") && --navDepth == 0) inToc = false;
                }
            }
        } catch (Exception ignored) { }
    }

    private void parseNcx(String path) {
        byte[] data = read(path);
        if (data == null) return;
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        try {
            XmlPullParser p = parser(data);
            List<String> labels = new ArrayList<>(); boolean inLabel = false; StringBuilder label = null;
            for (int t = p.getEventType(); t != XmlPullParser.END_DOCUMENT; t = p.next()) {
                if (t == XmlPullParser.START_TAG) {
                    String n = local(p.getName());
                    if (n.equals("navPoint")) labels.add("");
                    else if (n.equals("navLabel")) inLabel = true;
                    else if (n.equals("text") && inLabel) label = new StringBuilder();
                    else if (n.equals("content") && !labels.isEmpty()) { String src = p.getAttributeValue(null, "src"); if (src != null) add(dir, src, labels.get(labels.size() - 1)); }
                } else if (t == XmlPullParser.TEXT && label != null) label.append(p.getText());
                else if (t == XmlPullParser.END_TAG) {
                    String n = local(p.getName());
                    if (n.equals("text") && label != null && !labels.isEmpty()) { labels.set(labels.size() - 1, label.toString()); label = null; }
                    else if (n.equals("navLabel")) inLabel = false;
                    else if (n.equals("navPoint") && !labels.isEmpty()) labels.remove(labels.size() - 1);
                }
            }
        } catch (Exception ignored) { }
    }

    private void add(String dir, String href, String text) {
        String clean = text.replaceAll("\\s+", " ").trim();
        String path = resolve(dir, href.contains("#") ? href.substring(0, href.indexOf('#')) : href);
        if (!clean.isEmpty() && spineIndex(path) >= 0) toc.add(new Entry(clean, path));
    }

    /** Resolves [href] (URL-encoded, may contain ../) against the folder [base] inside the package. */
    static String resolve(String base, String href) {
        String h = Uri.decode(href);
        if (h.startsWith("/")) h = h.substring(1); else h = base + h;
        List<String> parts = new ArrayList<>();
        for (String s : h.split("/")) {
            if (s.isEmpty() || s.equals(".")) continue;
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); } else parts.add(s);
        }
        return android.text.TextUtils.join("/", parts);
    }

    private static XmlPullParser parser(byte[] data) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new ByteArrayInputStream(data), null);
        return p;
    }

    private static String local(String name) { return name.substring(name.indexOf(':') + 1); }
}
