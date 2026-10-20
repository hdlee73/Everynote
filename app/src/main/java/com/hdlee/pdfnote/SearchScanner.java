package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Document search.
 *
 * Speed comes from three things: (1) the PDF's own text layer is read instead of running OCR whenever the layer
 * is usable, (2) OCR (Korean model, which also reads Latin text) only runs on pages without a usable layer and
 * on pages that contain pen strokes, and (3) every page's text is cached on disk, so the next keyword is instant.
 */
final class SearchScanner {
    static final int MAX_HITS = 500;

    /** One search result. {@code box} is in normalized page coordinates (0..1). */
    static final class Hit {
        final int page;
        final float x, y;
        final RectF box;
        final String kind, text;
        final int matchStart, matchEnd;

        Hit(int page, RectF box, String kind, String text, int matchStart, int matchEnd) {
            this.page = page;
            this.box = new RectF(box);
            this.x = box.left;
            this.y = box.top;
            this.kind = kind;
            this.text = text;
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
        }
    }

    interface Listener {
        void progress(int done, int total);
        void hits(List<Hit> hits);
    }

    /** A line of text with per-word horizontal extents so a match can be highlighted precisely. */
    static final class Line {
        final String text;
        final float left, top, right, bottom;
        final int[] wordStart;
        final float[] wordLeft, wordRight;

        Line(String text, float left, float top, float right, float bottom, int[] wordStart, float[] wordLeft, float[] wordRight) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.wordStart = wordStart;
            this.wordLeft = wordLeft;
            this.wordRight = wordRight;
        }

        /** Builds a line from words with normalized {l, t, r, b} boxes. */
        static Line fromWords(List<String> words, List<float[]> boxes) {
            StringBuilder text = new StringBuilder();
            int n = words.size();
            int[] starts = new int[n];
            float[] lefts = new float[n], rights = new float[n];
            float l = 1f, t = 1f, r = 0f, b = 0f;
            for (int i = 0; i < n; i++) {
                if (i > 0) text.append(' ');
                starts[i] = text.length();
                text.append(words.get(i));
                float[] box = boxes.get(i);
                lefts[i] = clamp(box[0]);
                rights[i] = clamp(box[2]);
                l = Math.min(l, box[0]);
                t = Math.min(t, box[1]);
                r = Math.max(r, box[2]);
                b = Math.max(b, box[3]);
            }
            return new Line(text.toString(), clamp(l), clamp(t), clamp(r), clamp(b), starts, lefts, rights);
        }

        RectF match(int start, int end) {
            float l = Float.MAX_VALUE, r = -1f;
            for (int i = 0; i < wordStart.length; i++) {
                int wordEnd = i + 1 < wordStart.length ? wordStart[i + 1] - 1 : text.length();
                if (wordEnd > start && wordStart[i] < end) {
                    // a "word" from the PDF may be a whole text run: narrow the box to the matched characters
                    float len = Math.max(1, wordEnd - wordStart[i]), span = wordRight[i] - wordLeft[i];
                    int from = Math.max(start, wordStart[i]), to = Math.min(end, wordEnd);
                    l = Math.min(l, wordLeft[i] + span * (from - wordStart[i]) / len);
                    r = Math.max(r, wordLeft[i] + span * (to - wordStart[i]) / len);
                }
            }
            if (r < 0f) {
                float len = Math.max(1, text.length()), width = right - left;
                l = left + width * start / len;
                r = left + width * end / len;
            }
            if (r - l < .01f) r = Math.min(1f, l + .01f);
            return new RectF(clamp(l), top, clamp(r), bottom);
        }

        JSONArray toJson() throws JSONException {
            JSONArray words = new JSONArray();
            for (int i = 0; i < wordStart.length; i++) words.put(new JSONArray().put(wordStart[i]).put(wordLeft[i]).put(wordRight[i]));
            return new JSONArray().put(left).put(top).put(right).put(bottom).put(text).put(words);
        }

        static Line fromJson(JSONArray a) throws JSONException {
            JSONArray words = a.getJSONArray(5);
            int n = words.length();
            int[] starts = new int[n];
            float[] lefts = new float[n], rights = new float[n];
            for (int i = 0; i < n; i++) {
                JSONArray w = words.getJSONArray(i);
                starts[i] = w.getInt(0);
                lefts[i] = (float) w.getDouble(1);
                rights[i] = (float) w.getDouble(2);
            }
            return new Line(a.getString(4), (float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2), (float) a.getDouble(3), starts, lefts, rights);
        }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    static boolean contains(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    /** Builds a hit with a short snippet around the first match of {@code query}. */
    private static Hit hit(int page, RectF box, String kind, String full, String query) {
        String flat = full == null ? "" : full.replaceAll("\\s+", " ").trim();
        int index = flat.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        if (flat.length() > 100) {
            int from = index < 0 ? 0 : Math.max(0, index - 30);
            int to = Math.min(flat.length(), from + 100);
            String snippet = (from > 0 ? "…" : "") + flat.substring(from, to) + (to < flat.length() ? "…" : "");
            int shift = from > 0 ? 1 - from : -from;
            return new Hit(page, box, kind, snippet, index < 0 ? -1 : index + shift, index < 0 ? -1 : index + query.length() + shift);
        }
        return new Hit(page, box, kind, flat, index, index < 0 ? -1 : index + query.length());
    }

    private static RectF point(float x, float y) {
        return new RectF(clamp(x), clamp(y), clamp(x + .12f), clamp(y + .025f));
    }

    /** Memos, translations, notes and typed text are stored as plain strings, so they need no OCR. */
    static List<Hit> annotations(AnnotationStore store, String q) {
        List<Hit> hits = new ArrayList<>();
        for (AnnotationStore.Mark m : store.marks)
            if (contains(m.note, q)) hits.add(hit(m.page, new RectF(m.left, m.top, m.right, m.bottom), "메모", m.note, q));
        for (AnnotationStore.TranslationNote n : store.translations)
            if (contains(n.translated, q) || contains(n.source, q))
                hits.add(hit(n.page, new RectF(n.left, n.top, n.right, n.bottom), "번역", contains(n.translated, q) ? n.translated : n.source, q));
        for (AnnotationStore.StudyEntry e : store.studyEntries)
            if (contains(e.text, q) || contains(e.comment, q))
                hits.add(hit(e.page, point(e.x, e.y), "노트", contains(e.text, q) ? e.text : e.comment, q));
        for (AnnotationStore.PageElement e : store.elements)
            if (!e.kind.equals("image") && contains(e.text, q))
                hits.add(hit(e.page, new RectF(e.left, e.top, e.right, e.bottom), e.kind.equals("link") ? "링크" : "타이핑", e.text, q));
        return hits;
    }

    private static Hit lineHit(int page, Line line, String query, String kind) {
        String lower = line.text.toLowerCase(Locale.ROOT);
        int index = lower.indexOf(query.toLowerCase(Locale.ROOT));
        if (index < 0) return null;
        int end = index + query.length();
        RectF box = lower.length() == line.text.length() ? line.match(index, end) : new RectF(line.left, line.top, line.right, line.bottom);
        return hit(page, box, kind, line.text, query);
    }

    // ---------------------------------------------------------------- cache

    /** Per-document page text, kept in memory and mirrored to a JSON file in the app cache. */
    private static final class Index {
        final File file;
        final String signature;
        final Map<Integer, List<Line>> layer = new HashMap<>(), ocr = new HashMap<>();
        final Map<Integer, Integer> inkHash = new HashMap<>();
        final Map<Integer, List<Line>> ink = new HashMap<>();
        boolean dirty;

        Index(File file, String signature) { this.file = file; this.signature = signature; }
    }

    private static final Map<String, Index> MEMORY = new LinkedHashMap<String, Index>(4, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Index> eldest) { return size() > 3; }
    };

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }

    private static synchronized Index index(Context context, Uri uri) {
        String signature = "x";
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            File source = new File(uri.getPath());
            signature = source.length() + "-" + source.lastModified();
        }
        Index cached = MEMORY.get(uri.toString());
        if (cached != null && cached.signature.equals(signature)) return cached;
        File directory = new File(context.getCacheDir(), "search_index");
        Index index = new Index(new File(directory, sha256(uri.toString()) + ".json"), signature);
        if (!signature.equals("x") && index.file.isFile()) {
            try (InputStream in = new FileInputStream(index.file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                JSONObject root = new JSONObject(out.toString("UTF-8"));
                if (signature.equals(root.optString("sig"))) {
                    readLines(root.optJSONObject("layer"), index.layer);
                    readLines(root.optJSONObject("ocr"), index.ocr);
                    JSONObject inkRoot = root.optJSONObject("ink");
                    if (inkRoot != null) for (Iterator<String> it = inkRoot.keys(); it.hasNext(); ) {
                        String key = it.next();
                        JSONObject entry = inkRoot.getJSONObject(key);
                        List<Line> lines = new ArrayList<>();
                        JSONArray array = entry.getJSONArray("lines");
                        for (int i = 0; i < array.length(); i++) lines.add(Line.fromJson(array.getJSONArray(i)));
                        index.inkHash.put(Integer.parseInt(key), entry.getInt("hash"));
                        index.ink.put(Integer.parseInt(key), lines);
                    }
                }
            } catch (Exception ignored) {
                index.layer.clear();
                index.ocr.clear();
                index.ink.clear();
                index.inkHash.clear();
            }
        }
        MEMORY.put(uri.toString(), index);
        return index;
    }

    private static void readLines(JSONObject source, Map<Integer, List<Line>> target) throws JSONException {
        if (source == null) return;
        for (Iterator<String> it = source.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray array = source.getJSONArray(key);
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) lines.add(Line.fromJson(array.getJSONArray(i)));
            target.put(Integer.parseInt(key), lines);
        }
    }

    private static JSONObject writeLines(Map<Integer, List<Line>> source) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<Integer, List<Line>> entry : source.entrySet()) {
            JSONArray array = new JSONArray();
            for (Line line : entry.getValue()) array.put(line.toJson());
            out.put(String.valueOf(entry.getKey()), array);
        }
        return out;
    }

    private static void save(Index index) {
        synchronized (index) {
            if (!index.dirty || index.signature.equals("x")) return;
            try {
                JSONObject root = new JSONObject().put("sig", index.signature).put("layer", writeLines(index.layer)).put("ocr", writeLines(index.ocr));
                JSONObject inkRoot = new JSONObject();
                for (Map.Entry<Integer, List<Line>> entry : index.ink.entrySet()) {
                    JSONArray array = new JSONArray();
                    for (Line line : entry.getValue()) array.put(line.toJson());
                    inkRoot.put(String.valueOf(entry.getKey()), new JSONObject().put("hash", index.inkHash.get(entry.getKey())).put("lines", array));
                }
                root.put("ink", inkRoot);
                File directory = index.file.getParentFile();
                if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return;
                File temporary = new File(directory, index.file.getName() + ".tmp");
                try (OutputStream out = new FileOutputStream(temporary)) { out.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
                if (!temporary.renameTo(index.file)) { index.file.delete(); temporary.renameTo(index.file); }
                index.dirty = false;
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------- extraction

    /** Collects the PDF text layer line by line with word-level boxes. */
    private static final class LineCollector extends PDFTextStripper {
        private final List<Line> lines = new ArrayList<>();
        private final List<String> words = new ArrayList<>();
        private final List<float[]> boxes = new ArrayList<>();
        private float pageWidth = 1f, pageHeight = 1f, lineTop, lineBottom;

        LineCollector() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override protected void writeString(String word, List<TextPosition> positions) throws IOException {
            if (word == null || word.trim().isEmpty() || positions == null || positions.isEmpty()) return;
            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = 0f, b = 0f;
            for (TextPosition p : positions) {
                float x = p.getXDirAdj(), w = p.getWidthDirAdj(), base = p.getYDirAdj(), h = p.getHeightDir();
                pageWidth = Math.max(1f, p.getPageWidth());
                pageHeight = Math.max(1f, p.getPageHeight());
                l = Math.min(l, x);
                r = Math.max(r, x + w);
                t = Math.min(t, base - h);
                b = Math.max(b, base);
            }
            float center = (t + b) / 2f, slack = Math.max(1f, (b - t) * .3f);
            if (!words.isEmpty() && (center < lineTop - slack || center > lineBottom + slack)) flushLine();
            if (words.isEmpty()) { lineTop = t; lineBottom = b; } else { lineTop = Math.min(lineTop, t); lineBottom = Math.max(lineBottom, b); }
            words.add(word.trim());
            boxes.add(new float[]{l, t, r, b});
        }

        private void flushLine() {
            if (words.isEmpty()) return;
            List<float[]> normalized = new ArrayList<>();
            for (float[] box : boxes) normalized.add(new float[]{box[0] / pageWidth, box[1] / pageHeight, box[2] / pageWidth, box[3] / pageHeight});
            lines.add(Line.fromWords(new ArrayList<>(words), normalized));
            words.clear();
            boxes.clear();
        }

        @Override protected void writeLineSeparator() { flushLine(); }
        @Override protected void writeParagraphSeparator() { flushLine(); }

        List<Line> extract(PDDocument document, int page) throws IOException {
            lines.clear();
            words.clear();
            boxes.clear();
            setStartPage(page + 1);
            setEndPage(page + 1);
            getText(document);
            flushLine();
            return new ArrayList<>(lines);
        }
    }

    /** A text layer is only trusted when it holds enough readable characters. Scanned or mis-encoded pages fall back to OCR. */
    static boolean usableTextLayer(List<Line> lines) {
        int total = 0, good = 0;
        for (Line line : lines) {
            for (int i = 0; i < line.text.length(); i++) {
                char c = line.text.charAt(i);
                if (c == ' ') continue;
                total++;
                if (Character.isLetterOrDigit(c) || (c > 32 && c < 127) || "·…“”‘’「」『』《》〈〉".indexOf(c) >= 0) good++;
            }
        }
        return total >= 12 && good >= total * .9;
    }

    private static int inkSignature(AnnotationStore store, int page) {
        int hash = 17;
        for (AnnotationStore.InkStroke stroke : store.strokes) {
            if (stroke.page != page) continue;
            hash = 31 * hash + stroke.points.size();
            for (AnnotationStore.InkPoint p : stroke.points) hash = 31 * hash + Float.floatToIntBits(p.x) * 7 + Float.floatToIntBits(p.y);
        }
        return hash;
    }

    /** Lazily opened resources for one scan. */
    private static final class Engine implements AutoCloseable {
        private final Context context;
        private final Uri uri;
        private PDDocument document;
        private LineCollector collector;
        private boolean layerUnavailable;
        private ParcelFileDescriptor descriptor;
        private PdfRenderer renderer;
        private TextRecognizer recognizer;

        Engine(Context context, Uri uri) { this.context = context.getApplicationContext(); this.uri = uri; }

        /** Returns null when the document has no usable text layer on this page. */
        List<Line> textLayer(int page) {
            if (layerUnavailable) return null;
            try {
                if (document == null) {
                    PDFBoxResourceLoader.init(context);
                    collector = new LineCollector();
                    MemoryUsageSetting memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir());
                    if ("file".equals(uri.getScheme()) && uri.getPath() != null) document = PDDocument.load(new File(uri.getPath()), memory);
                    else try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("PDF를 읽을 수 없습니다");
                        document = PDDocument.load(in, memory);
                    }
                }
                if (page >= document.getNumberOfPages()) return null;
                List<Line> lines = collector.extract(document, page);
                return usableTextLayer(lines) ? lines : null;
            } catch (Throwable error) {
                if (document == null) layerUnavailable = true;
                return null;
            }
        }

        private void openRenderer() throws IOException {
            if (renderer != null) return;
            descriptor = "file".equals(uri.getScheme()) ? ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY) : context.getContentResolver().openFileDescriptor(uri, "r");
            if (descriptor == null) throw new IOException("PDF를 읽을 수 없습니다");
            renderer = new PdfRenderer(descriptor);
        }

        /** Renders a page (white background) at OCR resolution; {@code pageImage=false} leaves it blank for ink-only OCR. */
        Bitmap bitmap(int page, boolean pageImage) throws IOException {
            openRenderer();
            try (PdfRenderer.Page p = renderer.openPage(page)) {
                float scale = Math.min(2f, 1600f / Math.max(p.getWidth(), p.getHeight()));
                Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(p.getWidth() * scale)), Math.max(1, Math.round(p.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                image.eraseColor(Color.WHITE);
                if (pageImage) {
                    Matrix matrix = new Matrix();
                    matrix.postScale(scale, scale);
                    p.render(image, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
                return image;
            }
        }

        List<Line> recognize(Bitmap image) throws Exception {
            if (recognizer == null) recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)));
            float w = image.getWidth(), h = image.getHeight();
            List<Line> lines = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) {
                List<String> words = new ArrayList<>();
                List<float[]> boxes = new ArrayList<>();
                for (Text.Element element : line.getElements()) {
                    Rect r = element.getBoundingBox();
                    String word = element.getText().trim();
                    if (r != null && !word.isEmpty()) { words.add(word); boxes.add(new float[]{r.left / w, r.top / h, r.right / w, r.bottom / h}); }
                }
                if (words.isEmpty()) {
                    Rect r = line.getBoundingBox();
                    String text = line.getText().trim();
                    if (r != null && !text.isEmpty()) { words.add(text); boxes.add(new float[]{r.left / w, r.top / h, r.right / w, r.bottom / h}); }
                }
                if (!words.isEmpty()) lines.add(Line.fromWords(words, boxes));
            }
            return lines;
        }

        @Override public void close() {
            try { if (document != null) document.close(); } catch (IOException ignored) { }
            if (renderer != null) renderer.close();
            try { if (descriptor != null) descriptor.close(); } catch (IOException ignored) { }
            if (recognizer != null) recognizer.close();
        }
    }

    /**
     * Searches the whole document, starting at {@code startPage} so results near the reader appear first.
     * {@code listener} is called from this (background) thread. Returns true when the result cap cut the search short.
     */
    static boolean scan(Context context, Uri uri, AnnotationStore store, int pageCount, int startPage, String query, boolean forceOcr, AtomicBoolean canceled, Listener listener) throws Exception {
        List<Hit> instant = annotations(store, query);
        int found = instant.size();
        if (!instant.isEmpty()) listener.hits(instant);
        Index index = index(context, uri);
        Set<Integer> inkPages = new HashSet<>();
        for (AnnotationStore.InkStroke stroke : store.strokes) inkPages.add(stroke.page);
        boolean truncated = false;
        try (Engine engine = new Engine(context, uri)) {
            for (int step = 0; step < pageCount && !canceled.get(); step++) {
                int page = (Math.max(0, startPage) + step) % pageCount;
                listener.progress(step, pageCount);
                List<Hit> pageHits = new ArrayList<>();
                for (Line line : bodyLines(engine, index, page, forceOcr)) {
                    Hit hit = lineHit(page, line, query, "본문");
                    if (hit != null) pageHits.add(hit);
                }
                if (inkPages.contains(page) && !canceled.get())
                    for (Line line : inkLines(engine, index, store, page)) {
                        Hit hit = lineHit(page, line, query, "필기");
                        if (hit != null) pageHits.add(hit);
                    }
                if (canceled.get()) break;
                if (!pageHits.isEmpty()) {
                    pageHits.sort(Comparator.comparingDouble((Hit h) -> h.box.top));
                    if (found + pageHits.size() > MAX_HITS) { pageHits = new ArrayList<>(pageHits.subList(0, Math.max(0, MAX_HITS - found))); truncated = true; }
                    found += pageHits.size();
                    listener.hits(pageHits);
                    if (truncated) break;
                }
            }
            listener.progress(pageCount, pageCount);
        } finally {
            save(index);
        }
        return truncated;
    }

    /**
     * The running text of pages [first]..[last] (0-based, inclusive), one string per page: the PDF text layer, or OCR for scanned pages.
     * Used by the AI features. Stops early with the pages read so far when [canceled] is set.
     */
    static List<String> pageTexts(Context context, Uri uri, int first, int last, AtomicBoolean canceled, java.util.function.BiConsumer<Integer, Integer> progress) throws Exception {
        List<String> texts = new ArrayList<>();
        Index index = index(context, uri);
        try (Engine engine = new Engine(context, uri)) {
            for (int page = first; page <= last && !canceled.get(); page++) {
                if (progress != null) progress.accept(page - first, last - first + 1);
                List<String> lines = new ArrayList<>();
                for (Line line : bodyLines(engine, index, page, false)) lines.add(line.text);
                texts.add(AiAssistant.reflow(lines));
            }
        } finally {
            save(index);
        }
        return texts;
    }

    private static List<Line> bodyLines(Engine engine, Index index, int page, boolean forceOcr) throws Exception {
        synchronized (index) {
            if (!forceOcr) {
                List<Line> cached = index.layer.get(page);
                if (cached != null) return cached;
            }
            List<Line> cachedOcr = index.ocr.get(page);
            if (cachedOcr != null && (forceOcr || !index.layer.containsKey(page))) return cachedOcr;
        }
        if (!forceOcr) {
            List<Line> layer = engine.textLayer(page);
            if (layer != null) {
                synchronized (index) { index.layer.put(page, layer); index.dirty = true; }
                return layer;
            }
        }
        Bitmap image = engine.bitmap(page, true);
        try {
            List<Line> lines = engine.recognize(image);
            synchronized (index) { index.ocr.put(page, lines); index.dirty = true; }
            return lines;
        } finally { image.recycle(); }
    }

    private static List<Line> inkLines(Engine engine, Index index, AnnotationStore store, int page) throws Exception {
        int signature = inkSignature(store, page);
        synchronized (index) {
            Integer known = index.inkHash.get(page);
            if (known != null && known == signature && index.ink.containsKey(page)) return index.ink.get(page);
        }
        Bitmap image = engine.bitmap(page, false);
        try {
            AnnotationPainter.strokes(new Canvas(image), new RectF(0, 0, image.getWidth(), image.getHeight()), store, page);
            List<Line> lines = engine.recognize(image);
            synchronized (index) { index.ink.put(page, lines); index.inkHash.put(page, signature); index.dirty = true; }
            return lines;
        } finally { image.recycle(); }
    }
}
