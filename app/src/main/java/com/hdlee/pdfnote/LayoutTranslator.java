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

    /** Covers [block] and writes [translated] over it, shrinking the letters until the text fits. */
    private static void draw(Canvas canvas, Bitmap image, Block block, String translated) {
        float pad = block.lineHeight * 0.12f;
        float left = Math.max(0f, block.left - pad), top = Math.max(0f, block.top - pad);
        float right = Math.min(image.getWidth(), block.right + pad), bottom = Math.min(image.getHeight(), block.bottom + pad);
        if (right - left < 2f || bottom - top < 2f) return;
        int background = surround(image, left, top, right, bottom);
        Paint fill = new Paint();
        fill.setColor(background);
        canvas.drawRect(left, top, right, bottom, fill);
        if (translated.isEmpty()) return;
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
                            for (int i = 0; i < blocks.size(); i++) draw(canvas, original, blocks.get(i), done.get(i));
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
