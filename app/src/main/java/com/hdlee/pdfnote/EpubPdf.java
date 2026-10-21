package com.hdlee.pdfnote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfDocument;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Typesets an EPUB with the reader settings onto A5 pages and writes a PDF that the note editor can open. */
final class EpubPdf {
    private EpubPdf() {}

    interface Progress { void update(int done, int total); }

    static final int WIDTH = 420, HEIGHT = 595;
    private static final float BASE_TEXT = 14f;
    private static final Pattern BODY = Pattern.compile("<body[^>]*>(.*)</body>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SVG_IMAGE = Pattern.compile("<image\\b[^>]*?(?:xlink:)?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    static void write(EpubBook book, EpubSettings settings, File target, Progress progress, AtomicBoolean cancelled) throws IOException {
        int margin = 20 + settings.margin, usableWidth = WIDTH - 2 * margin, usableHeight = HEIGHT - 2 * margin, number = 0;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(28, 28, 30));
        paint.setTextSize(BASE_TEXT * settings.zoom / 100f);
        paint.setTypeface(settings.font == 1 ? Typeface.SERIF : settings.font == 2 ? Typeface.SANS_SERIF : Typeface.DEFAULT);
        PdfDocument pdf = new PdfDocument();
        try {
            for (int chapter = 0; chapter < book.spine.size(); chapter++) {
                if (cancelled.get()) throw new IOException("변환이 취소되었습니다");
                if (progress != null) progress.update(chapter, book.spine.size());
                CharSequence text = chapterText(book, book.spine.get(chapter), usableWidth, usableHeight);
                if (text.length() == 0) continue;
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, usableWidth)
                    .setLineSpacing(0f, settings.lineSpacing()).setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
                int first = 0, lines = layout.getLineCount();
                while (first < lines) {
                    int last = first;
                    int top = layout.getLineTop(first);
                    while (last + 1 < lines && layout.getLineBottom(last + 1) - top <= usableHeight) last++;
                    PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, ++number).create());
                    Canvas canvas = page.getCanvas();
                    canvas.save();
                    canvas.translate(margin, margin - top);
                    canvas.clipRect(0, top, usableWidth, layout.getLineBottom(last));
                    layout.draw(canvas);
                    canvas.restore();
                    pdf.finishPage(page);
                    first = last + 1;
                }
            }
            if (number == 0) throw new IOException("PDF로 옮길 본문이 없습니다");
            try (OutputStream out = new FileOutputStream(target)) { pdf.writeTo(out); }
        } finally { pdf.close(); }
        if (progress != null) progress.update(book.spine.size(), book.spine.size());
    }

    /** Plain flowing text of one chapter with its headings, emphasis and pictures; the book's own style sheet is not used. */
    static CharSequence chapterText(EpubBook book, String path, int usableWidth, int usableHeight) {
        byte[] raw = book.read(path);
        if (raw == null) return "";
        String html = new String(raw, StandardCharsets.UTF_8);
        Matcher body = BODY.matcher(html);
        if (body.find()) html = body.group(1);
        html = html.replaceAll("(?is)<script\\b.*?</script>", "").replaceAll("(?is)<style\\b.*?</style>", "");
        html = SVG_IMAGE.matcher(html).replaceAll("<img src=\"$1\"/>");
        html = html.replaceAll("(?i)</?svg\\b[^>]*>", "");
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        Html.ImageGetter pictures = source -> picture(book, dir, source, usableWidth, usableHeight);
        SpannableStringBuilder text = new SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, pictures, null));
        trim(text);
        return text;
    }

    private static void trim(Editable text) {
        while (text.length() > 0 && Character.isWhitespace(text.charAt(text.length() - 1))) text.delete(text.length() - 1, text.length());
        int lead = 0;
        while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) lead++;
        if (lead > 0) text.delete(0, lead);
    }

    private static Drawable picture(EpubBook book, String dir, String source, int usableWidth, int usableHeight) {
        Drawable none = new ColorDrawable(Color.TRANSPARENT);
        none.setBounds(0, 0, 1, 1);
        if (source == null || source.startsWith("data:") || source.contains("://")) return none;
        byte[] data = book.read(EpubBook.resolve(dir, source));
        if (data == null) return none;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return none;
            BitmapFactory.Options load = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= usableWidth * 2) sample *= 2;
            load.inSampleSize = sample;
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, load);
            if (bitmap == null) return none;
            float fit = Math.min(usableWidth / (float) bounds.outWidth, usableHeight * 0.9f / bounds.outHeight);
            float scale = Math.min(0.5f, fit);   // a pixel is half a point, but never wider than the page
            BitmapDrawable drawable = new BitmapDrawable(null, bitmap);
            drawable.setBounds(0, 0, Math.max(1, Math.round(bounds.outWidth * scale)), Math.max(1, Math.round(bounds.outHeight * scale)));
            return drawable;
        } catch (RuntimeException | OutOfMemoryError e) { return none; }
    }
}
