package com.hdlee.pdfnote;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** Lays out plain text on A4 pages and writes it as a PDF (used for AI translations and proofread copies). */
final class TextPdf {
    private TextPdf() {}

    private static final int WIDTH = 595, HEIGHT = 842, MARGIN = 48;

    /**
     * Writes one or more pages for every entry of [pages]. [labels] (same size, may be null) is a small grey caption
     * above each entry, such as the number of the source page, so a translation can be read against the original.
     */
    static void write(File target, List<String> pages, List<String> labels) throws IOException {
        PdfDocument pdf = new PdfDocument();
        try {
            TextPaint body = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            body.setColor(Color.rgb(28, 28, 30));
            body.setTextSize(12.5f);
            TextPaint caption = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
            caption.setColor(Color.rgb(142, 142, 147));
            caption.setTextSize(9.5f);
            int usable = WIDTH - 2 * MARGIN, height = HEIGHT - 2 * MARGIN, number = 1;
            for (int i = 0; i < pages.size(); i++) {
                String text = pages.get(i) == null || pages.get(i).trim().isEmpty() ? "(텍스트 없음)" : pages.get(i);
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), body, usable).setLineSpacing(3f, 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
                String label = labels == null || i >= labels.size() ? null : labels.get(i);
                int captionHeight = label == null ? 0 : 18, line = 0, lines = layout.getLineCount();
                do {
                    int room = height - (line == 0 ? captionHeight : 0), start = line, top = layout.getLineTop(start);
                    while (line < lines && layout.getLineBottom(line) - top <= room) line++;
                    if (line == start) line++; // a single line taller than the page still has to go somewhere
                    PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number++).create());
                    Canvas canvas = page.getCanvas();
                    canvas.save();
                    canvas.translate(MARGIN, MARGIN);
                    if (start == 0 && label != null) { canvas.drawText(label, 0, 10, caption); canvas.translate(0, captionHeight); }
                    canvas.clipRect(0, 0, usable, layout.getLineBottom(line - 1) - top);
                    canvas.translate(0, -top);
                    layout.draw(canvas);
                    canvas.restore();
                    pdf.finishPage(page);
                } while (line < lines);
            }
            if (pdf.getPages().isEmpty()) throw new IOException("만들 내용이 없습니다");
            try (OutputStream out = new FileOutputStream(target)) { pdf.writeTo(out); }
        } finally {
            pdf.close();
        }
    }
}
