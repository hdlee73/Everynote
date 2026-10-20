package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Translation that keeps the page: every paragraph of the original page is covered with its own background colour and the translation is
 * written over it, so pictures, tables, colours and the arrangement of the page stay as they were. The result is a new PDF made of page images.
 */
final class LayoutTranslator {
    private LayoutTranslator() { }

    /** A paragraph: consecutive lines of one column, in page pixels. */
    static final class Block {
        final StringBuilder text = new StringBuilder();
        float left, top, right, bottom, lineHeight, lastLeft;
        int lines;

        Block(SearchScanner.Line line, float w, float h) {
            left = line.left * w; top = line.top * h; right = line.right * w; bottom = line.bottom * h;
            lastLeft = left; lineHeight = Math.max(1f, bottom - top); lines = 1;
            text.append(line.text.trim());
        }

        void add(SearchScanner.Line line, float w, float h) {
            float l = line.left * w, t = line.top * h, r = line.right * w, b = line.bottom * h;
            left = Math.min(left, l); right = Math.max(right, r); top = Math.min(top, t); bottom = Math.max(bottom, b);
            lineHeight = (lineHeight * lines + Math.max(1f, b - t)) / (lines + 1);
            lastLeft = l; lines++;
            text.append(' ').append(line.text.trim());
        }
    }

    private static final Pattern ITEM_START = Pattern.compile("^([\\u2460-\\u2473\\u2474-\\u2487]|\\(?\\d{1,3}[.)]|[\\uAC00-\\uD7A3][.)]|[-\\u2022\\u00B7\\u25CF\\u25A0]).*");

    /** Groups lines that read as one paragraph; the page size [w] x [h] turns the normalized boxes into pixels. */
    static List<Block> blocks(List<SearchScanner.Line> lines, float w, float h) {
        List<SearchScanner.Line> sorted = new ArrayList<>();
        for (SearchScanner.Line line : lines) if (!line.text.trim().isEmpty()) sorted.add(line);
        Collections.sort(sorted, (a, b) -> a.top != b.top ? Float.compare(a.top, b.top) : Float.compare(a.left, b.left));
        List<Block> blocks = new ArrayList<>();
        for (SearchScanner.Line line : sorted) {
            float l = line.left * w, t = line.top * h, r = line.right * w, lh = Math.max(1f, (line.bottom - line.top) * h);
            boolean item = ITEM_START.matcher(line.text.trim()).matches();
            Block best = null;
            float bestGap = Float.MAX_VALUE;
            if (!item) for (Block b : blocks) {
                float gap = t - b.bottom;
                if (gap < -0.35f * lh || gap > 0.9f * lh) continue;
                if (Math.abs(l - b.lastLeft) > 2.5f * lh && Math.abs(l - b.left) > 2.5f * lh) continue;
                float overlap = Math.min(r, b.right) - Math.max(l, b.left);
                if (overlap < 0.4f * Math.min(r - l, b.right - b.left)) continue;
                float ratio = lh / b.lineHeight;
                if (ratio < 0.7f || ratio > 1.4f) continue;
                if (gap < bestGap) { bestGap = gap; best = b; }
            }
            if (best != null) best.add(line, w, h); else blocks.add(new Block(line, w, h));
        }
        return blocks;
    }

    /** The colour around a box (the middle one of eight samples just outside it), so the cover blends into the page. */
    private static int surround(Bitmap image, float left, float top, float right, float bottom) {
        int w = image.getWidth(), h = image.getHeight(), gap = 3;
        float cx = (left + right) / 2f, cy = (top + bottom) / 2f;
        float[][] at = {{left - gap, top - gap}, {right + gap, top - gap}, {left - gap, bottom + gap}, {right + gap, bottom + gap},
            {cx, top - gap}, {cx, bottom + gap}, {left - gap, cy}, {right + gap, cy}};
        List<Integer> colors = new ArrayList<>();
        for (float[] p : at) colors.add(image.getPixel(Math.max(0, Math.min(w - 1, Math.round(p[0]))), Math.max(0, Math.min(h - 1, Math.round(p[1])))));
        Collections.sort(colors, (a, b) -> Integer.compare(luma(a), luma(b)));
        return colors.get(colors.size() / 2) | 0xFF000000;
    }

    private static int luma(int color) { return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000; }

    /** What to wipe for one paragraph: the paragraph's box, grown to take in the ink of the original letters, minus ruling lines. */
    private static final class Cover {
        int left, top, right, bottom, background;
        boolean[] keepRow, keepCol;
    }

    private static boolean ink(int color, int backgroundLuma) { return Math.abs(luma(color) - backgroundLuma) > 48; }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * Works out the area to wipe. The text layer only gives the height of the letters above the baseline and the width of the words,
     * so the box is grown downward (descenders), upward (accents, dots) and sideways along any ink that continues the line,
     * but never over a ruling line (a row or column that is ink almost all the way across) or into the next block of text.
     */
    private static Cover plan(Bitmap image, Block block, List<Block> all) {
        int w = image.getWidth(), h = image.getHeight();
        float lh = block.lineHeight;
        int coreLeft = clamp(Math.round(block.left), 0, w), coreRight = clamp(Math.round(block.right), 0, w);
        int coreTop = clamp(Math.round(block.top), 0, h), coreBottom = clamp(Math.round(block.bottom), 0, h);
        int top = clamp(Math.round(block.top - 0.3f * lh), 0, h), bottom = clamp(Math.round(block.bottom + 0.5f * lh), 0, h);
        int left0 = clamp(Math.round(block.left - 0.3f * lh), 0, w), right0 = clamp(Math.round(block.right + 0.3f * lh), 0, w);
        if (bottom - top < 2 || right0 - left0 < 2) return null;
        int background = surround(image, block.left, block.top, block.right, block.bottom);
        int bgLuma = luma(background);
        // text beside this paragraph (another column on the same rows) limits how far the wipe may reach
        int leftLimit = 0, rightLimit = w;
        for (Block o : all) {
            if (o == block) continue;
            float overlap = Math.min(block.bottom, o.bottom) - Math.max(block.top, o.top);
            if (overlap < 0.5f * (block.bottom - block.top)) continue;
            if (o.left >= block.right - 1f) rightLimit = Math.min(rightLimit, Math.round(o.left - 0.3f * lh));
            else if (o.right <= block.left + 1f) leftLimit = Math.max(leftLimit, Math.round(o.right + 0.3f * lh));
        }
        int reach = w / 2;
        int rx0 = clamp(Math.max(leftLimit, left0 - reach), 0, left0), rx1 = clamp(Math.min(rightLimit, right0 + reach), right0, w);
        int rw = rx1 - rx0, rh = bottom - top;
        int[] px = new int[rw * rh];
        image.getPixels(px, 0, rw, rx0, top, rw, rh);
        int left = left0, right = right0;
        float gap = 1.2f * lh;
        int clean = 0;
        for (int x = right0; x < rx1; x++) {
            int count = 0;
            for (int y = 0; y < rh; y++) if (ink(px[y * rw + (x - rx0)], bgLuma)) count++;
            if (count >= 0.85f * rh) break;
            if (count > 0) { right = x + 1; clean = 0; } else if (++clean > gap) break;
        }
        clean = 0;
        for (int x = left0 - 1; x >= rx0; x--) {
            int count = 0;
            for (int y = 0; y < rh; y++) if (ink(px[y * rw + (x - rx0)], bgLuma)) count++;
            if (count >= 0.85f * rh) break;
            if (count > 0) { left = x; clean = 0; } else if (++clean > gap) break;
        }
        Cover cover = new Cover();
        cover.left = left; cover.right = right; cover.top = top; cover.bottom = bottom; cover.background = background;
        cover.keepRow = new boolean[bottom - top];
        cover.keepCol = new boolean[right - left];
        // ruling lines in the margins around the letters stay
        for (int y = top; y < bottom; y++) {
            if (y >= coreTop && y < coreBottom) continue;
            int count = 0;
            for (int x = left0; x < right0; x++) if (ink(px[(y - top) * rw + (x - rx0)], bgLuma)) count++;
            cover.keepRow[y - top] = count >= 0.85f * (right0 - left0);
        }
        for (int x = left; x < right; x++) {
            if (x >= coreLeft && x < coreRight) continue;
            if (x < left0 || x >= right0) continue;
            int count = 0;
            for (int y = 0; y < rh; y++) if (ink(px[y * rw + (x - rx0)], bgLuma)) count++;
            cover.keepCol[x - left] = count >= 0.85f * rh;
        }
        return cover;
    }

    private static void wipe(Canvas canvas, Cover cover) {
        Paint fill = new Paint();
        fill.setColor(cover.background);
        int y = cover.top;
        while (y < cover.bottom) {
            if (cover.keepRow[y - cover.top]) { y++; continue; }
            int y2 = y;
            while (y2 < cover.bottom && !cover.keepRow[y2 - cover.top]) y2++;
            int x = cover.left;
            while (x < cover.right) {
                if (cover.keepCol[x - cover.left]) { x++; continue; }
                int x2 = x;
                while (x2 < cover.right && !cover.keepCol[x2 - cover.left]) x2++;
                canvas.drawRect(x, y, x2, y2, fill);
                x = x2;
            }
            y = y2;
        }
    }

    /** Writes [translated] into the paragraph's own box, shrinking the letters until the text fits. */
    private static void write(Canvas canvas, Bitmap image, Block block, int background, String translated) {
        if (translated.isEmpty()) return;
        float pad = block.lineHeight * 0.12f;
        float left = Math.max(0f, block.left - pad), top = Math.max(0f, block.top - pad);
        float right = Math.min(image.getWidth(), block.right + pad), bottom = Math.min(image.getHeight(), block.bottom + pad);
        if (right - left < 2f || bottom - top < 2f) return;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(luma(background) < 128 ? Color.WHITE : Color.BLACK);
        float size = block.lineHeight * 0.82f, min = size * 0.4f;
        int width = Math.max(1, Math.round(right - left));
        StaticLayout layout;
        while (true) {
            paint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(translated, 0, translated.length(), paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f).build();
            if (layout.getHeight() <= (bottom - top) * 1.02f || size <= min) break;
            size = Math.max(min, size * 0.93f);
        }
        canvas.save();
        canvas.clipRect(left, top, right, bottom + block.lineHeight * 0.3f);
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restore();
    }

    /**
     * Writes pages [first]..[last] of [uri], translated into [languageName], to [target]. [progress] gets (pages done, pages in all).
     * Throws when no page holds any text.
     */
    static void translate(Context context, Uri uri, int first, int last, File target, String languageName, AtomicBoolean canceled,
                          java.util.function.BiConsumer<Integer, Integer> progress) throws Exception {
        PDFBoxResourceLoader.init(context);
        int total = last - first + 1, translatedPages = 0;
        try (SearchScanner.PageSource source = new SearchScanner.PageSource(context, uri);
             PDDocument pdf = new PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))) {
            for (int page = first; page <= last; page++) {
                if (canceled.get()) throw new AiAssistant.Canceled();
                progress.accept(page - first, total);
                Bitmap image = source.image(page, 2200f, 3f);
                try {
                    List<SearchScanner.Line> lines = source.lines(page);
                    List<Block> blocks = blocks(lines, image.getWidth(), image.getHeight());
                    if (!blocks.isEmpty()) {
                        List<String> texts = new ArrayList<>();
                        for (Block b : blocks) texts.add(b.text.toString());
                        List<String> done = AiAssistant.translateBlocks(texts, languageName, canceled);
                        Bitmap original = image.copy(Bitmap.Config.ARGB_8888, false);
                        try {
                            Canvas canvas = new Canvas(image);
                            List<Cover> covers = new ArrayList<>();
                            for (Block b : blocks) covers.add(plan(original, b, blocks));
                            for (Cover c : covers) if (c != null) wipe(canvas, c);   // all the wiping first, so no cover can erase a neighbour's new text
                            for (int i = 0; i < blocks.size(); i++) {
                                Cover c = covers.get(i);
                                int background = c != null ? c.background : Color.WHITE;
                                write(canvas, original, blocks.get(i), background, done.get(i));
                            }
                        } finally { original.recycle(); }
                        translatedPages++;
                    }
                    float width = image.getWidth() / source.scale, height = image.getHeight() / source.scale;
                    PDPage out = new PDPage(new PDRectangle(width, height));
                    pdf.addPage(out);
                    try (PDPageContentStream content = new PDPageContentStream(pdf, out)) {
                        content.drawImage(JPEGFactory.createFromImage(pdf, image, 0.88f), 0, 0, width, height);
                    }
                } finally { image.recycle(); }
            }
            if (translatedPages == 0) throw new IOException("번역할 글자를 찾지 못했습니다");
            progress.accept(total, total);
            pdf.save(target);
        } catch (Exception | Error error) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw error;
        }
    }
}
