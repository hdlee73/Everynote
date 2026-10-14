package com.hdlee.pdfnote;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/**
 * Whole-library backup: one ZIP holding every document (PDF), its notes (the same JSON as the per-document annotation backup),
 * the pictures / recordings / videos, folders with their colours, favourites and paper types. The Windows app reads and writes the same format.
 * Layout: everynote-backup.json first, then docs/N.pdf + notes/N.json per document, then assets/NAME.
 */
final class LibraryBackup {
    static final String FORMAT = "Everynote backup v1", MANIFEST = "everynote-backup.json";
    interface Progress { void update(String message); }

    /** Result counters of a restore. */
    static final class Result { int documents, notes, assets, folders, skipped, failed; }

    static boolean safeRel(String rel, boolean pdf) {
        if (rel == null || rel.isEmpty() || rel.length() > 400 || rel.startsWith("/") || rel.contains("\\") || rel.contains("\0")) return false;
        if (pdf && !rel.toLowerCase(Locale.ROOT).endsWith(".pdf")) return false;
        for (String part : rel.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith(".")) return false;
        return true;
    }

    private static void collect(LibraryRepository lib, File dir, String prefix, List<String> docs, List<String> folders) {
        for (File f : lib.list(dir)) {
            String rel = prefix + f.getName();
            if (f.isDirectory()) { folders.add(rel); collect(lib, f, rel + "/", docs, folders); } else docs.add(rel);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException { byte[] b = new byte[16384]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n); }
    private static String readText(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new IOException("백업 파일이 올바르지 않습니다"); }
        return o.toString("UTF-8");
    }

    /** assetDirs: images, recordings, videos (in that order). Returns the number of documents written. */
    static int write(Context c, LibraryRepository lib, File[] assetDirs, OutputStream out, Progress p) throws Exception { return write(c, lib, null, assetDirs, out, p); }

    /** Documents (and sub folders) of one folder of the library; null or the library root backs up everything. Paths stay relative to the library root, so a restore puts them back in the same folder. */
    static int write(Context c, LibraryRepository lib, File scope, File[] assetDirs, OutputStream out, Progress p) throws Exception {
        List<String> docs = new ArrayList<>(), folders = new ArrayList<>();
        final boolean whole = scope == null || scope.equals(lib.root);
        if (whole) collect(lib, lib.root, "", docs, folders);
        else {
            String base = scope.getAbsolutePath().substring(lib.root.getAbsolutePath().length()).replace(File.separatorChar, '/');
            if (base.startsWith("/")) base = base.substring(1);
            if (base.isEmpty() || !scope.isDirectory()) throw new IOException("백업할 폴더를 찾을 수 없습니다");
            folders.add(base); collect(lib, scope, base + "/", docs, folders);
        }
        Map<Integer, File> templates = new HashMap<>();
        List<File> assets = new ArrayList<>();
        java.util.Set<String> used = null;
        if (!whole) {   // a folder backup carries only the pictures, recordings and videos its own documents use
            used = new java.util.HashSet<>();
            for (String rel : docs) { AnnotationStore st = new AnnotationStore(c); st.open(Uri.fromFile(new File(lib.root, rel))); for (AnnotationStore.PageElement e : st.elements) { used.add(e.asset); used.add(e.text); } }
        }
        for (File d : assetDirs) { File[] l = d.listFiles(); if (l != null) for (File f : l) if (f.isFile() && DeviceSync.ASSET.matcher(f.getName()).matches() && (used == null || used.contains(f.getName()))) assets.add(f); }
        JSONObject m = new JSONObject().put("format", FORMAT).put("created", System.currentTimeMillis()).put("app", "android");
        JSONArray da = new JSONArray(), fa = new JSONArray(), aa = new JSONArray();
        for (int i = 0; i < docs.size(); i++) {
            File f = new File(lib.root, docs.get(i));
            JSONObject o = new JSONObject().put("i", i).put("path", docs.get(i)).put("favorite", lib.favorite(f));
            NotebookFiles.Paper paper = lib.paper(f);
            if (paper != null) {
                if (paper.kind != NotebookFiles.CUSTOM) o.put("paper", paper.kind + ":" + paper.color);
                else if (paper.template != null && paper.template.getName().startsWith("builtin-")) o.put("paper", "builtin:" + paper.template.getName().substring(8) + ":" + paper.color);
                else if (paper.template != null && paper.template.isFile()) { String n = paper.template.getName(), ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9]", "") : "pdf"; o.put("paper", "custom:" + paper.color + ":" + ext); templates.put(i, paper.template); }
            }
            da.put(o);
        }
        for (String rel : folders) fa.put(new JSONObject().put("path", rel).put("color", lib.folderColor(new File(lib.root, rel))));
        for (File f : assets) aa.put(f.getName());
        m.put("documents", da).put("folders", fa).put("assets", aa);
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(out))) {
            z.setLevel(3);
            z.putNextEntry(new ZipEntry(MANIFEST)); z.write(m.toString(2).getBytes("UTF-8")); z.closeEntry();
            for (int i = 0; i < docs.size(); i++) {
                File f = new File(lib.root, docs.get(i)); if (p != null) p.update("백업 중… " + (i + 1) + "/" + docs.size());
                File template = templates.get(i);
                if (template != null) { String n = template.getName(); z.putNextEntry(new ZipEntry("templates/" + i + "." + (n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).replaceAll("[^A-Za-z0-9]", "") : "pdf"))); try (InputStream in = new FileInputStream(template)) { copy(in, z); } z.closeEntry(); }
                z.putNextEntry(new ZipEntry("docs/" + i + ".pdf")); try (InputStream in = new FileInputStream(f)) { copy(in, z); } z.closeEntry();
                AnnotationStore st = new AnnotationStore(c); Uri uri = Uri.fromFile(f); st.open(uri);
                z.putNextEntry(new ZipEntry("notes/" + i + ".json")); z.write(st.exportJson(uri, f.getName()).getBytes("UTF-8")); z.closeEntry();
            }
            for (File f : assets) { z.putNextEntry(new ZipEntry("assets/" + f.getName())); try (InputStream in = new FileInputStream(f)) { copy(in, z); } z.closeEntry(); }
        }
        return docs.size();
    }

    /** overwrite=false: a document whose path exists is added as a copy ("name (1).pdf"); true: it is replaced unless open. */
    static Result restore(Context c, LibraryRepository lib, File[] assetDirs, InputStream in, boolean overwrite, java.util.function.Predicate<File> isOpen, Progress p) throws Exception {
        Result r = new Result();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e = z.getNextEntry();
            if (e == null || !MANIFEST.equals(e.getName())) throw new IOException("Everynote 백업 파일이 아닙니다");
            JSONObject m = new JSONObject(readText(z, 32 * 1024 * 1024));
            if (!FORMAT.equals(m.optString("format"))) throw new IOException("Everynote 백업 파일이 아닙니다");
            JSONArray fa = m.optJSONArray("folders"), da = m.optJSONArray("documents");
            if (fa != null) for (int i = 0; i < fa.length(); i++) {
                JSONObject o = fa.getJSONObject(i); String rel = o.optString("path");
                if (!safeRel(rel, false)) continue; File d = new File(lib.root, rel);
                boolean fresh = !d.exists(); if (!d.isDirectory() && !d.mkdirs()) continue; if (fresh) { lib.folderColor(d, o.optInt("color", LibraryRepository.FOLDER_COLORS[0])); r.folders++; }
            }
            Map<Integer, JSONObject> meta = new HashMap<>(); Map<Integer, File> targets = new HashMap<>(), restoredTemplates = new HashMap<>();
            if (da != null) for (int i = 0; i < da.length(); i++) { JSONObject o = da.getJSONObject(i); meta.put(o.optInt("i", -1), o); }
            int total = meta.size();
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                try {
                    if (n.startsWith("docs/") && n.endsWith(".pdf")) {
                        int i = Integer.parseInt(n.substring(5, n.length() - 4)); JSONObject o = meta.get(i); if (o == null) continue;
                        String rel = o.optString("path"); if (!safeRel(rel, true)) { r.failed++; continue; }
                        File target = new File(lib.root, rel);
                        if (target.exists()) { if (overwrite) { if (isOpen != null && isOpen.test(target)) { r.skipped++; continue; } } else target = NotebookFiles.unique(target.getParentFile(), target.getName()); }
                        File parent = target.getParentFile(); if (parent != null) parent.mkdirs();
                        File temp = File.createTempFile(".restore-", ".tmp", parent);
                        try (OutputStream out = new FileOutputStream(temp)) { copy(z, out); }
                        if (target.exists() && !target.delete()) { temp.delete(); r.failed++; continue; }
                        if (!temp.renameTo(target)) { temp.delete(); r.failed++; continue; }
                        targets.put(i, target); r.documents++; if (p != null) p.update("복원 중… " + r.documents + "/" + total);
                        if (o.optBoolean("favorite")) lib.favorite(target, true);
                        String paper = o.optString("paper", "");
                        if (!paper.isEmpty()) try {
                            if (paper.startsWith("builtin:")) { String[] q = paper.split(":"); lib.restorePaper(target, new NotebookFiles.Paper(NotebookFiles.CUSTOM, Integer.parseInt(q[2]), NotebookFiles.builtinTemplate(c, q[1]))); }
                            else if (paper.startsWith("custom:")) { File t = restoredTemplates.get(i); String[] q = paper.split(":"); if (t != null) lib.restorePaper(target, new NotebookFiles.Paper(NotebookFiles.CUSTOM, Integer.parseInt(q[1]), t)); }
                            else lib.restorePaper(target, NotebookFiles.Paper.parse(paper));
                        } catch (RuntimeException | IOException ignored) { }
                    } else if (n.startsWith("notes/") && n.endsWith(".json")) {
                        int i = Integer.parseInt(n.substring(6, n.length() - 5)); File t = targets.get(i); if (t == null) continue;
                        String json = readText(z, 64 * 1024 * 1024);
                        AnnotationStore st = new AnnotationStore(c); Uri uri = Uri.fromFile(t); st.open(uri);
                        try { st.importJson(json, Integer.MAX_VALUE); r.notes++; } catch (JSONException bad) { r.failed++; }
                    } else if (n.startsWith("templates/")) {
                        String rest = n.substring(10); int dot = rest.indexOf('.'); if (dot < 1) continue;
                        int i = Integer.parseInt(rest.substring(0, dot)); String ext = rest.substring(dot + 1).replaceAll("[^A-Za-z0-9]", ""); if (ext.isEmpty()) ext = "pdf";
                        File dir = new File(c.getFilesDir(), "templates"); dir.mkdirs(); File t = new File(dir, "restored-" + System.currentTimeMillis() + "-" + i + "." + ext);
                        try (OutputStream out = new FileOutputStream(t)) { copy(z, out); } restoredTemplates.put(i, t);
                    } else if (n.startsWith("assets/")) {
                        String name = n.substring(7); if (!DeviceSync.ASSET.matcher(name).matches()) continue;
                        File dir = name.endsWith(".png") ? assetDirs[0] : name.endsWith(".m4a") ? assetDirs[1] : assetDirs[2]; dir.mkdirs();
                        File t = new File(dir, name); if (t.exists() && !overwrite) continue;
                        File temp = File.createTempFile(".restore-", ".tmp", dir);
                        try (OutputStream out = new FileOutputStream(temp)) { copy(z, out); }
                        if (t.exists()) t.delete(); if (temp.renameTo(t)) r.assets++; else temp.delete();
                    }
                } catch (NumberFormatException skip) { }
            }
        }
        return r;
    }
}
