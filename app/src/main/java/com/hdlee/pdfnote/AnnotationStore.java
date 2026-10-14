package com.hdlee.pdfnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AnnotationStore {
    static final class Mark {
        int page;
        float left, top, right, bottom;
        int color;
        String note;
        boolean noteOnly;
        boolean visible = true;
        boolean minimized;
        /** Sticky-note look: paper color, text size in sp, and box size (0 small, 1 medium, 2 large). */
        int paper = 0xFFFFF3A6, fontSp = 13, boxSize = 1;
        /** Own box size in dp (0 = use {@link #boxSize}); set by dragging the corner handle of a selected memo. */
        float boxW, boxH;
        float rot;
        /** Freehand highlight: thickness as a fraction of the page height and the stroke path (x0,y0,x1,y1,… normalized); path == null is the straight band. */
        float thick;
        float[] path;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("paper", paper).put("fontSp", fontSp).put("boxSize", boxSize).put("boxW", boxW).put("boxH", boxH).put("rot", rot).put("thick", thick);
            if (path != null) { JSONArray pa = new JSONArray(); for (float v : path) pa.put(Math.round(v * 10000) / 10000.0); o.put("path", pa); }
            o.put("page", page).put("left", left).put("top", top)
                    .put("right", right).put("bottom", bottom)
                    .put("color", color).put("note", note == null ? "" : note)
                    .put("noteOnly", noteOnly).put("visible", visible)
                    .put("minimized", minimized);
            return o;
        }

        static Mark fromJson(JSONObject o) {
            Mark m = new Mark();
            m.page = o.optInt("page");
            m.left = (float) o.optDouble("left");
            m.top = (float) o.optDouble("top");
            m.right = (float) o.optDouble("right");
            m.bottom = (float) o.optDouble("bottom");
            m.color = o.optInt("color", 0x66FFEB3B);
            m.note = o.optString("note", "");
            m.noteOnly = o.optBoolean("noteOnly", false);
            m.visible = o.optBoolean("visible", true);
            m.minimized = o.optBoolean("minimized", false);
            m.paper = o.optInt("paper", 0xFFFFF3A6);
            m.fontSp = Math.max(9, Math.min(28, o.optInt("fontSp", 13)));
            m.boxSize = Math.max(0, Math.min(2, o.optInt("boxSize", 1)));
            m.boxW = (float) Math.max(0, Math.min(800, o.optDouble("boxW", 0)));
            m.boxH = (float) Math.max(0, Math.min(1200, o.optDouble("boxH", 0)));
            m.rot = (float) o.optDouble("rot", 0);
            m.thick = (float) Math.max(0, Math.min(0.3, o.optDouble("thick", 0)));
            JSONArray pa = o.optJSONArray("path");
            if (pa != null && pa.length() >= 4) { m.path = new float[pa.length() - pa.length() % 2]; for (int i = 0; i < m.path.length; i++) m.path[i] = (float) pa.optDouble(i, 0); }
            return m;
        }
    }

    static final class OutlineItem {
        int page;
        float x, y;
        String title;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("page", page).put("x", x).put("y", y).put("title", title);
        }

        static OutlineItem fromJson(JSONObject o) {
            OutlineItem item = new OutlineItem();
            item.page = o.optInt("page");
            item.x = (float) o.optDouble("x", 0.5);
            item.y = (float) o.optDouble("y", 0.5);
            item.title = o.optString("title", "개요");
            return item;
        }
    }

    static final class InkPoint {
        float x, y, pressure;
        InkPoint(float x, float y, float pressure) { this.x=x; this.y=y; this.pressure=pressure; }
        JSONObject toJson() throws JSONException { return new JSONObject().put("x",x).put("y",y).put("p",pressure); }
        static InkPoint fromJson(JSONObject o) { return new InkPoint((float)o.optDouble("x"),(float)o.optDouble("y"),(float)o.optDouble("p",0.5)); }
    }

    static final class InkStroke {
        int page, color;
        /** 0 ballpoint, 1 pencil, 2 fountain pen, 3 brush, 4 felt marker, 5 highlighter, 6 tape. */
        int pen;
        float width;
        final List<InkPoint> points=new ArrayList<>();
        JSONObject toJson() throws JSONException { JSONObject o=new JSONObject().put("page",page).put("color",color).put("width",width).put("pen",pen);JSONArray a=new JSONArray();for(InkPoint p:points)a.put(p.toJson());return o.put("points",a); }
        static InkStroke fromJson(JSONObject o) throws JSONException { InkStroke s=new InkStroke();s.page=o.optInt("page");s.color=o.optInt("color",0xFF1C1C1E);s.width=(float)o.optDouble("width",0.004);s.pen=Math.max(0,Math.min(6,o.optInt("pen",0)));JSONArray a=o.optJSONArray("points");if(a!=null)for(int i=0;i<a.length();i++)s.points.add(InkPoint.fromJson(a.getJSONObject(i)));return s; }
    }

    static final class TranslationNote {
        int page;
        float left, top, right, bottom;
        String source, translated;
        boolean visible = true;
        boolean minimized;
        float boxW, boxH, rot;

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("boxW", boxW).put("boxH", boxH).put("rot", rot).put("page", page).put("left", left).put("top", top)
                    .put("right", right).put("bottom", bottom).put("source", source)
                    .put("translated", translated).put("visible", visible)
                    .put("minimized", minimized);
        }

        static TranslationNote fromJson(JSONObject o) {
            TranslationNote n = new TranslationNote();
            n.page=o.optInt("page"); n.left=(float)o.optDouble("left"); n.top=(float)o.optDouble("top");
            n.right=(float)o.optDouble("right"); n.bottom=(float)o.optDouble("bottom");
            n.source=o.optString("source",""); n.translated=o.optString("translated","");
            n.visible=o.optBoolean("visible",true); n.minimized=o.optBoolean("minimized",false); n.boxW=(float)o.optDouble("boxW",0); n.boxH=(float)o.optDouble("boxH",0); n.rot=(float)o.optDouble("rot",0); return n;
        }
    }

    static final class StudyEntry {
        String id = java.util.UUID.randomUUID().toString();
        int page;
        float x, y;
        String text = "", comment = "";
        boolean excerpt;
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id",id).put("page",page).put("x",x).put("y",y)
                .put("text",text).put("comment",comment).put("excerpt",excerpt);
        }
        static StudyEntry fromJson(JSONObject o) throws JSONException {
            StudyEntry e=new StudyEntry();e.id=o.optString("id",e.id);e.page=o.getInt("page");
            e.x=(float)o.optDouble("x",0.5);e.y=(float)o.optDouble("y",0.5);
            e.text=o.getString("text");e.comment=o.optString("comment","");e.excerpt=o.optBoolean("excerpt");
            if(e.page<0 || !Float.isFinite(e.x) || !Float.isFinite(e.y) || e.x<0 || e.x>1 || e.y<0 || e.y>1)
                throw new JSONException("잘못된 페이지 링크");
            return e;
        }
    }
    static final class PageElement {
        static final float DEFAULT_TEXT_SIZE = .027f;
        static final int DEFAULT_TEXT_COLOR = 0xFF1C1C1E;
        /** sans=고딕, serif=명조, mono=고정폭, hand=손글씨체 */
        static final List<String> FONTS = java.util.Arrays.asList("sans", "medium", "light", "black", "condensed", "serif", "mono", "typewriter", "hand", "casual");
        int page;
        String kind = "text", text = "", asset = "";
        float left = .1f, top = .1f, right = .8f, bottom = .3f;
        /** Text height as a fraction of the page width (typing boxes only). */
        float textSize = DEFAULT_TEXT_SIZE;
        int color = DEFAULT_TEXT_COLOR;
        String font = "sans";
        boolean bold, italic;
        /** Typing boxes: paragraph alignment (0 left, 1 centre, 2 right), underline and strike-through. */
        int align;
        boolean underline, strike;
        /** Pictures: stretched to fill the box (width and height changed independently) instead of keeping the original ratio. */
        boolean stretch;
        /** Clockwise rotation in degrees around the box centre (pictures, stickers, shapes and tables). */
        float rot;
        /** Opacity of pictures, stickers, shapes and tables (1 = solid). */
        float alpha = 1f;
        /** Text: line step as a multiple of the font size; 0 keeps the default {@link #DEFAULT_LINE}. */
        float lineSpacing;
        static final float DEFAULT_LINE = 1.35f;
        float line() { return lineSpacing >= .8f && lineSpacing <= 4f ? lineSpacing : DEFAULT_LINE; }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("rot", rot).put("page", page).put("kind", kind).put("text", text).put("asset", asset)
                    .put("left", left).put("top", top).put("right", right).put("bottom", bottom)
                    .put("textSize", textSize).put("color", color).put("font", font)
                    .put("bold", bold).put("italic", italic).put("align", align).put("underline", underline).put("strike", strike).put("stretch", stretch).put("alpha", alpha);
            if (lineSpacing > 0) o.put("lineSpacing", lineSpacing);
            return o;
        }

        static PageElement fromJson(JSONObject o) throws JSONException {
            PageElement e = new PageElement();
            e.page = o.getInt("page");
            e.kind = o.optString("kind", "text");
            e.text = o.optString("text");
            e.asset = o.optString("asset");
            e.left = (float) o.optDouble("left", .1);
            e.top = (float) o.optDouble("top", .1);
            e.right = (float) o.optDouble("right", .8);
            e.bottom = (float) o.optDouble("bottom", .3);
            e.rot = (float) o.optDouble("rot", 0);
            e.textSize = (float) o.optDouble("textSize", DEFAULT_TEXT_SIZE);
            e.color = o.optInt("color", DEFAULT_TEXT_COLOR);
            e.font = o.optString("font", "sans");
            if (!FONTS.contains(e.font)) e.font = "sans";
            e.bold = o.optBoolean("bold", false);
            e.italic = o.optBoolean("italic", false);
            e.align = Math.max(0, Math.min(2, o.optInt("align", 0)));
            e.underline = o.optBoolean("underline", false);
            e.strike = o.optBoolean("strike", false);
            e.stretch = o.optBoolean("stretch", false);
            e.lineSpacing = (float) o.optDouble("lineSpacing", 0);
            if (!Float.isFinite(e.lineSpacing) || e.lineSpacing < .8f || e.lineSpacing > 4f) e.lineSpacing = 0f;
            e.alpha = (float) o.optDouble("alpha", 1);
            if (!Float.isFinite(e.alpha) || e.alpha > 1f) e.alpha = 1f;
            if (e.alpha < .05f) e.alpha = .05f;
            if (e.page < 0 || !Float.isFinite(e.left) || !Float.isFinite(e.top) || !Float.isFinite(e.right) || !Float.isFinite(e.bottom)
                    || e.left < 0 || e.top < 0 || e.right > 1 || e.bottom > 1 || e.left >= e.right || e.top >= e.bottom
                    || !java.util.Arrays.asList("text", "image", "link", "audio", "sticker", "video", "hyperlink", "shape", "table", "youtube").contains(e.kind)
                    || (!e.asset.isEmpty() && !e.asset.matches("[a-f0-9-]{36}\\.(png|m4a)|tape-\\d{2}\\.png"))
                    || (e.kind.equals("video") && !e.text.matches("[a-f0-9-]{36}\\.mp4"))
                    || (e.kind.equals("sticker") && (e.text.isEmpty() || e.text.length() > 16))
                    || (e.kind.equals("youtube") && !e.text.matches("[A-Za-z0-9_-]{11}"))
                    || (e.kind.equals("shape") && !Shapes.validShape(e.text))
                    || (e.kind.equals("table") && !Shapes.validTable(e.text))
                    || (e.kind.equals("hyperlink") && !e.text.matches("(https?://\\S+|page:\\d{1,6}|doc:\\S{1,600})")))
                throw new JSONException("잘못된 노트 요소");
            if (!Float.isFinite(e.textSize) || e.textSize < .004f || e.textSize > .3f) e.textSize = DEFAULT_TEXT_SIZE;
            return e;
        }
    }
    final List<PageElement> elements=new ArrayList<>();
    final List<StudyEntry> studyEntries=new ArrayList<>();

    private final SharedPreferences prefs;
    private final java.io.File sidecarDirectory;
    private String key;
    final List<Mark> marks = new ArrayList<>();
    final Set<Integer> bookmarks = new HashSet<>();
    final List<OutlineItem> outlines = new ArrayList<>();
    final List<InkStroke> strokes = new ArrayList<>();
    final List<TranslationNote> translations = new ArrayList<>();

    AnnotationStore(Context context) {
        prefs = context.getSharedPreferences("pdf_note_data", Context.MODE_PRIVATE);
        sidecarDirectory=new java.io.File(context.getFilesDir(),"annotations");
    }

    static long modified(Context context,Uri uri){return new java.io.File(new java.io.File(context.getFilesDir(),"annotations"),"doc_"+sha256(uri.toString())+".json").lastModified();}

    void rebind(Uri uri) { key = "doc_" + sha256(uri.toString()); save(); }

    void open(Uri uri) {
        key = "doc_" + sha256(uri.toString());
        marks.clear();
        bookmarks.clear();
        outlines.clear();
        strokes.clear();
        translations.clear();
        studyEntries.clear();
        elements.clear();
        try {
            String json=prefs.getString(key,"{}");
            android.util.AtomicFile sidecar=new android.util.AtomicFile(new java.io.File(sidecarDirectory,key+".json"));
            if(!prefs.contains(key))try(java.io.InputStream in=sidecar.openRead()){
                java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);json=out.toString("UTF-8");
            }catch(java.io.IOException ignored){}
            loadJson(json);
        } catch (JSONException ignored) { }
        undo.clear();redo.clear();historyChars=0;
        try{current=toJsonString();}catch(JSONException error){current=null;}
    }

    /** Replaces everything held by this store with the saved JSON (the lists stay the same objects, so views keep working). */
    private void loadJson(String json) throws JSONException {
        marks.clear();bookmarks.clear();outlines.clear();strokes.clear();translations.clear();studyEntries.clear();elements.clear();
        JSONObject root = new JSONObject(json);
        JSONArray els=root.optJSONArray("elements");if(els!=null)for(int i=0;i<els.length();i++)elements.add(PageElement.fromJson(els.getJSONObject(i)));
        JSONArray entries=root.optJSONArray("studyEntries");
        if(entries!=null)for(int i=0;i<entries.length();i++)studyEntries.add(StudyEntry.fromJson(entries.getJSONObject(i)));
        JSONArray a = root.optJSONArray("marks");
        if (a != null) for (int i = 0; i < a.length(); i++) marks.add(Mark.fromJson(a.getJSONObject(i)));
        JSONArray b = root.optJSONArray("bookmarks");
        if (b != null) for (int i = 0; i < b.length(); i++) bookmarks.add(b.getInt(i));
        JSONArray o = root.optJSONArray("outlines");
        if (o != null) for (int i = 0; i < o.length(); i++) outlines.add(OutlineItem.fromJson(o.getJSONObject(i)));
        JSONArray s = root.optJSONArray("strokes");
        if (s != null) for (int i = 0; i < s.length(); i++) strokes.add(InkStroke.fromJson(s.getJSONObject(i)));
        JSONArray t = root.optJSONArray("translations");
        if (t != null) for (int i = 0; i < t.length(); i++) translations.add(TranslationNote.fromJson(t.getJSONObject(i)));
    }

    // ---- undo / redo of every kind of edit: each save keeps the previous saved state, so any change (ink, highlights, memos, typed text, shapes, pictures, moves, deletions...) can be taken back
    private static final int MAX_HISTORY = 40;
    private static final long MAX_HISTORY_CHARS = 16L * 1024 * 1024;
    private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>(), redo = new java.util.ArrayDeque<>();
    private String current;
    private long historyChars;
    private Runnable historyListener;
    /** Called after every save, undo and redo (to refresh the undo / redo buttons). */
    void setHistoryListener(Runnable listener) { historyListener = listener; }
    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }
    /** Forget the history (after the page list changed, when old snapshots would no longer fit the document). */
    void clearHistory() { undo.clear(); redo.clear(); historyChars = 0; try { current = toJsonString(); } catch (JSONException error) { current = null; } notifyHistory(); }
    private void notifyHistory() { if (historyListener != null) historyListener.run(); }
    private void remember(String json) {
        if (current != null && !current.equals(json)) {
            undo.addLast(current); historyChars += current.length(); redo.clear();
            while (undo.size() > MAX_HISTORY || (historyChars > MAX_HISTORY_CHARS && undo.size() > 1)) historyChars -= undo.removeFirst().length();
        }
        current = json;
    }
    /** Steps back one edit. Returns the page the edit touched (or 0 when unknown), or -1 when there is nothing to undo. */
    int undo() { return step(undo, redo); }
    /** Steps forward again. Returns the page the edit touched (or 0 when unknown), or -1 when there is nothing to redo. */
    int redo() { return step(redo, undo); }
    private int step(java.util.ArrayDeque<String> from, java.util.ArrayDeque<String> to) {
        if (from.isEmpty() || current == null) return -1;
        String target = from.removeLast(); historyChars = Math.max(0, historyChars - (from == undo ? target.length() : 0));
        int page = changedPage(current, target);
        try { loadJson(target); } catch (JSONException error) { from.addLast(target); return -1; }
        to.addLast(current); if (to == undo) historyChars += current.length();
        current = target; persist(target); notifyHistory();
        return Math.max(0, page);
    }
    /** The first page whose annotations differ between two saved states, or -1 when only page-less data differs. */
    static int changedPage(String a, String b) {
        try {
            JSONObject x = new JSONObject(a), y = new JSONObject(b); int best = -1;
            for (String key : new String[]{"marks", "strokes", "elements", "translations", "outlines", "studyEntries"}) {
                java.util.Map<String, Integer> count = new java.util.HashMap<>(); java.util.Map<String, Integer> pageOf = new java.util.HashMap<>();
                JSONArray p = x.optJSONArray(key), q = y.optJSONArray(key);
                if (p != null) for (int i = 0; i < p.length(); i++) { JSONObject o = p.getJSONObject(i); String k = o.toString(); count.merge(k, 1, Integer::sum); pageOf.put(k, o.optInt("page", 0)); }
                if (q != null) for (int i = 0; i < q.length(); i++) { JSONObject o = q.getJSONObject(i); String k = o.toString(); count.merge(k, -1, Integer::sum); pageOf.put(k, o.optInt("page", 0)); }
                for (java.util.Map.Entry<String, Integer> e : count.entrySet()) if (e.getValue() != 0) { int pg = pageOf.get(e.getKey()); if (best < 0 || pg < best) best = pg; }
            }
            return best;
        } catch (JSONException error) { return -1; }
    }

    private String toJsonString() throws JSONException {
        JSONObject root = new JSONObject();
        JSONArray a = new JSONArray();
        for (Mark m : marks) a.put(m.toJson());
        JSONArray b = new JSONArray();
        for (int page : bookmarks) b.put(page);
        JSONArray o = new JSONArray();
        for (OutlineItem item : outlines) o.put(item.toJson());
        JSONArray s = new JSONArray();
        for (InkStroke stroke : strokes) s.put(stroke.toJson());
        JSONArray t = new JSONArray();
        for (TranslationNote note : translations) t.put(note.toJson());
        JSONArray entries=new JSONArray();for(StudyEntry e:studyEntries)entries.put(e.toJson());
        JSONArray els=new JSONArray();for(PageElement e:elements)els.put(e.toJson());
        root.put("elements",els).put("studyEntries",entries).put("marks", a).put("bookmarks", b).put("outlines", o).put("strokes", s).put("translations",t);
        return root.toString();
    }

    void save() {
        if (key == null) return;
        try {
            String json = toJsonString();
            remember(json);
            persist(json);
            notifyHistory();
        } catch (JSONException ignored) { }
    }

    private void persist(String json) {
        if(key==null)return;
        if(!sidecarDirectory.isDirectory())sidecarDirectory.mkdirs();
        android.util.AtomicFile file=new android.util.AtomicFile(new java.io.File(sidecarDirectory,key+".json"));
        java.io.FileOutputStream out=null;
        try{out=file.startWrite();out.write(json.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(java.io.IOException error){if(out!=null)file.failWrite(out);prefs.edit().putString(key,json).apply();return;}
        prefs.edit().remove(key).apply();
    }

    String exportJson(Uri uri, String title) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "PDF Note annotations v2");
        root.put("document", title);
        root.put("uri", uri.toString());
        JSONArray a = new JSONArray();
        for (Mark m : marks) a.put(m.toJson());
        JSONArray b = new JSONArray();
        for (int page : bookmarks) b.put(page);
        JSONArray o = new JSONArray();
        for (OutlineItem item : outlines) o.put(item.toJson());
        JSONArray s = new JSONArray();
        for (InkStroke stroke : strokes) s.put(stroke.toJson());
        JSONArray t = new JSONArray();
        for (TranslationNote note : translations) t.put(note.toJson());
        JSONArray entries=new JSONArray();for(StudyEntry e:studyEntries)entries.put(e.toJson());
        JSONArray els=new JSONArray();for(PageElement e:elements)els.put(e.toJson());
        root.put("elements",els).put("studyEntries",entries).put("marks", a).put("bookmarks", b).put("outlines", o).put("strokes", s).put("translations",t);
        return root.toString(2);
    }

    /** Removes everything anchored to a deleted page and renumbers the pages after it. Does not save. */
    void removePage(int index) {
        marks.removeIf(m -> m.page == index);
        strokes.removeIf(s -> s.page == index);
        translations.removeIf(t -> t.page == index);
        outlines.removeIf(o -> o.page == index);
        elements.removeIf(e -> e.page == index);
        studyEntries.removeIf(e -> e.page == index);
        bookmarks.remove(index);
        shiftPages(index + 1, -1);
    }

    /** Makes room for a page inserted right after {@code afterIndex}. Does not save. */
    void insertPageAfter(int afterIndex) { shiftPages(afterIndex + 1, 1); }

    private void shiftPages(int from, int delta) {
        for (Mark m : marks) if (m.page >= from) m.page += delta;
        for (InkStroke s : strokes) if (s.page >= from) s.page += delta;
        for (TranslationNote t : translations) if (t.page >= from) t.page += delta;
        for (OutlineItem o : outlines) if (o.page >= from) o.page += delta;
        for (PageElement e : elements) if (e.page >= from) e.page += delta;
        for (StudyEntry e : studyEntries) if (e.page >= from) e.page += delta;
        Set<Integer> moved = new HashSet<>();
        for (int page : bookmarks) moved.add(page >= from ? page + delta : page);
        bookmarks.clear();
        bookmarks.addAll(moved);
    }

    void importJson(String json, int pageCount) throws JSONException {
        JSONObject root=new JSONObject(json);
        String format=root.optString("format");
        if(!format.equals("PDF Note annotations v1")&&!format.equals("PDF Note annotations v2"))
            throw new JSONException("Everynote 주석 백업이 아닙니다");
        AnnotationStore temporary=new AnnotationStore();
        JSONArray a=root.getJSONArray("marks"), b=root.getJSONArray("bookmarks"), o=root.getJSONArray("outlines"),
            s=root.getJSONArray("strokes"), t=root.getJSONArray("translations");
        for(int i=0;i<a.length();i++)temporary.marks.add(Mark.fromJson(a.getJSONObject(i)));
        for(int i=0;i<b.length();i++)temporary.bookmarks.add(b.getInt(i));
        for(int i=0;i<o.length();i++)temporary.outlines.add(OutlineItem.fromJson(o.getJSONObject(i)));
        for(int i=0;i<s.length();i++)temporary.strokes.add(InkStroke.fromJson(s.getJSONObject(i)));
        for(int i=0;i<t.length();i++)temporary.translations.add(TranslationNote.fromJson(t.getJSONObject(i)));
        JSONArray entries=root.optJSONArray("studyEntries");
        if(entries!=null)for(int i=0;i<entries.length();i++)temporary.studyEntries.add(StudyEntry.fromJson(entries.getJSONObject(i)));
        JSONArray els=root.optJSONArray("elements");if(els!=null)for(int i=0;i<els.length();i++)temporary.elements.add(PageElement.fromJson(els.getJSONObject(i)));
        List<Integer> pages=new ArrayList<>(temporary.bookmarks);
        for(Mark m:temporary.marks)pages.add(m.page);for(OutlineItem m:temporary.outlines)pages.add(m.page);
        for(InkStroke m:temporary.strokes)pages.add(m.page);for(TranslationNote m:temporary.translations)pages.add(m.page);
        for(StudyEntry m:temporary.studyEntries)pages.add(m.page);for(PageElement e:temporary.elements)pages.add(e.page);
        for(int page:pages)if(page<0||page>=pageCount)throw new JSONException("문서 페이지 범위를 벗어난 주석");
        marks.clear();marks.addAll(temporary.marks);bookmarks.clear();bookmarks.addAll(temporary.bookmarks);
        outlines.clear();outlines.addAll(temporary.outlines);strokes.clear();strokes.addAll(temporary.strokes);
        translations.clear();translations.addAll(temporary.translations);studyEntries.clear();studyEntries.addAll(temporary.studyEntries);elements.clear();elements.addAll(temporary.elements);save();
    }
    private AnnotationStore() { prefs=null;sidecarDirectory=null; }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
