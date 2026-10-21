package com.hdlee.pdfnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The "EPUB 책" shelf: books are kept apart from the PDF library; a converted PDF goes to the normal library. */
final class EpubStore {
    private final File root;
    private final SharedPreferences prefs;

    EpubStore(Context context) {
        root = new File(context.getFilesDir(), "epubs");
        prefs = context.getSharedPreferences("epub_reader", Context.MODE_PRIVATE);
    }

    static boolean isEpub(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".epub"); }

    /** Key shared with the reader for a book's reading position and display info. */
    static String key(String name, long size) { return Integer.toHexString((name + "|" + size).hashCode()); }

    boolean contains(File file) {
        try { return file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator); } catch (IOException e) { return false; }
    }

    /** Copies the picked book into the shelf (an identical name and size is not copied twice) and returns the stored file. */
    synchronized File add(Context context, Uri source, String name) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("EPUB 보관 폴더를 만들 수 없습니다");
        String safe = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty() || !isEpub(safe)) safe = (safe.isEmpty() ? "책" : safe) + ".epub";
        File temp = File.createTempFile(".import-", ".tmp", root);
        try {
            try (InputStream in = "file".equals(source.getScheme()) ? new FileInputStream(source.getPath()) : context.getContentResolver().openInputStream(source);
                 OutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IOException("파일을 읽을 수 없습니다");
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            File target = new File(root, safe);
            String base = safe.substring(0, safe.length() - 5);
            for (int i = 1; target.exists(); i++) {
                if (target.length() == temp.length() && sameBytes(target, temp)) return target;
                target = new File(root, base + " (" + i + ").epub");
            }
            if (!temp.renameTo(target)) throw new IOException("책을 저장할 수 없습니다");
            return target;
        } finally { temp.delete(); }
    }

    private static boolean sameBytes(File a, File b) throws IOException {
        try (InputStream x = new FileInputStream(a); InputStream y = new FileInputStream(b)) {
            byte[] p = new byte[65536], q = new byte[65536];
            int n;
            while ((n = x.read(p)) != -1) {
                int m = 0;
                while (m < n) { int r = y.read(q, m, n - m); if (r < 0) return false; m += r; }
                if (!Arrays.equals(Arrays.copyOf(p, n), Arrays.copyOf(q, n))) return false;
            }
            return true;
        }
    }

    List<File> list() {
        File[] files = root.listFiles(f -> f.isFile() && isEpub(f.getName()));
        List<File> out = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        out.sort(Comparator.comparingLong((File f) -> opened(f)).reversed().thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    long opened(File f) { return prefs.getLong("opened:" + key(f.getName(), f.length()), 0); }
    void opened(String key) { prefs.edit().putLong("opened:" + key, System.currentTimeMillis()).apply(); }

    /** {title, percent} the reader last saved for the book; null before it was opened. */
    String[] info(File f) {
        String raw = prefs.getString("info:" + key(f.getName(), f.length()), null);
        if (raw == null) return null;
        int cut = raw.lastIndexOf('\n');
        return cut < 0 ? new String[]{raw, "0"} : new String[]{raw.substring(0, cut), raw.substring(cut + 1)};
    }
    void info(String key, String title, int percent) { prefs.edit().putString("info:" + key, title + "\n" + percent).apply(); }

    boolean delete(File f) {
        String key = key(f.getName(), f.length());
        boolean ok = contains(f) && f.delete();
        if (ok) prefs.edit().remove("pos:" + key).remove("info:" + key).remove("opened:" + key).apply();
        return ok;
    }
}
