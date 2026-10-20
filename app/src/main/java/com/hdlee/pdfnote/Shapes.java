package com.hdlee.pdfnote;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Drawing shapes and tables placed on a page. Shape text: "kind|STROKE|FILL|width" (colors as 8 hex digits AARRGGBB). */
final class Shapes {
    static final String[] KINDS = {"rect", "round", "oval", "triangle", "diamond", "star", "heart", "line", "arrow"};
    static final String[] NAMES = {"사각형", "둥근 사각형", "원", "삼각형", "마름모", "별", "하트", "선", "화살표"};
    static final String SHAPE_PATTERN = "(rect|round|oval|triangle|diamond|star|heart|line|arrow)\\|[0-9A-F]{8}\\|[0-9A-F]{8}\\|\\d{1,2}";
    static final String TABLE_HEADER = "\\d{1,2},\\d{1,2},[0-9A-F]{8},[0-9A-F]{8},[0-9A-F]{8}";

    private Shapes() {}

    static String hex(int color) { return String.format(java.util.Locale.US, "%08X", color); }

    static String shapeSpec(String kind, int stroke, int fill, int width) { return kind + "|" + hex(stroke) + "|" + hex(fill) + "|" + width; }

    static boolean validShape(String text) { return text != null && text.matches(SHAPE_PATTERN); }

    static boolean validTable(String text) {
        if (text == null || text.length() > 30000) return false;
        String head = text.split("\n", -1)[0];
        if (!head.matches(TABLE_HEADER)) return false;
        String[] p = head.split(",");
        int r = Integer.parseInt(p[0]), c = Integer.parseInt(p[1]);
        return r >= 1 && r <= 30 && c >= 1 && c <= 12;
    }

    static int parseColor(String hex) { return (int) Long.parseLong(hex, 16); }

    static void drawShape(Canvas c, RectF b, String spec, float pageWidth) {
        if (!validShape(spec)) return;
        String[] p = spec.split("\\|");
        int stroke = AnnotationPainter.adj(parseColor(p[1])), fill = parseColor(p[2]);
        float width = Math.max(1f, Integer.parseInt(p[3]) * pageWidth * .0016f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        RectF r = new RectF(b);
        r.inset(width / 2f, width / 2f);
        if (r.width() <= 0 || r.height() <= 0) return;
        String kind = p[0];
        if (kind.equals("line") || kind.equals("arrow")) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(stroke);
            float y = b.centerY();
            c.drawLine(b.left + width, y, b.right - width, y, paint);
            if (kind.equals("arrow")) {
                float head = Math.min(b.width() * .4f, Math.max(width * 4f, b.height() * .9f));
                Path h = new Path();
                h.moveTo(b.right - head, y - head * .55f);
                h.lineTo(b.right - width, y);
                h.lineTo(b.right - head, y + head * .55f);
                c.drawPath(h, paint);
            }
            return;
        }
        Path path = new Path();
        switch (kind) {
            case "round": path.addRoundRect(r, Math.min(r.width(), r.height()) * .22f, Math.min(r.width(), r.height()) * .22f, Path.Direction.CW); break;
            case "oval": path.addOval(r, Path.Direction.CW); break;
            case "triangle": path.moveTo(r.centerX(), r.top); path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom); path.close(); break;
            case "diamond": path.moveTo(r.centerX(), r.top); path.lineTo(r.right, r.centerY()); path.lineTo(r.centerX(), r.bottom); path.lineTo(r.left, r.centerY()); path.close(); break;
            case "star": {
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float rad = i % 2 == 0 ? 1f : .42f;
                    float ux = (float) Math.cos(a) * rad, uy = (float) Math.sin(a) * rad;
                    float x = r.left + (ux + .951f) / 1.902f * r.width(), y = r.top + (uy + 1f) / 1.809f * r.height();
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                break;
            }
            case "heart": {
                float w = r.width(), h = r.height(), x = r.left, y = r.top;
                path.moveTo(x + w * .5f, y + h);
                path.cubicTo(x - w * .1f, y + h * .62f, x + w * .02f, y - h * .08f, x + w * .5f, y + h * .28f);
                path.cubicTo(x + w * .98f, y - h * .08f, x + w * 1.1f, y + h * .62f, x + w * .5f, y + h);
                path.close();
                break;
            }
            default: path.addRect(r, Path.Direction.CW);
        }
        if (Color_alpha(fill) > 0) { paint.setStyle(Paint.Style.FILL); paint.setColor(fill); c.drawPath(path, paint); }
        if (Color_alpha(stroke) > 0) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setColor(stroke); c.drawPath(path, paint); }
    }

    private static int Color_alpha(int color) { return color >>> 24; }

    /** A table: header line "rows,cols,line,header,fill", one line per cell, then an optional "~" line with a per-cell background colour (8 hex digits, empty = none). */
    static final class Table {
        int rows = 3, cols = 3, line = 0xFF3A3A3C, head = 0xFFE5F0FF, fill = 0x00FFFFFF;
        String[] cells = new String[0];
        /** Per-cell background (AARRGGBB); 0 = the table's own head / fill colour shows. */
        int[] bg = new int[0];

        static Table parse(String text) {
            Table t = new Table();
            if (!validTable(text)) { t.cells = new String[9]; java.util.Arrays.fill(t.cells, ""); t.bg = new int[9]; return t; }
            String[] lines = text.split("\n", -1), p = lines[0].split(",");
            t.rows = Integer.parseInt(p[0]); t.cols = Integer.parseInt(p[1]);
            t.line = parseColor(p[2]); t.head = parseColor(p[3]); t.fill = parseColor(p[4]);
            t.cells = new String[t.rows * t.cols];
            for (int i = 0; i < t.cells.length; i++) t.cells[i] = i + 1 < lines.length ? lines[i + 1] : "";
            t.bg = new int[t.cells.length];
            for (int n = t.cells.length + 1; n < lines.length; n++) {
                if (lines[n].startsWith("~")) {
                    String[] parts = lines[n].substring(1).split(",", -1);
                    for (int i = 0; i < t.bg.length && i < parts.length; i++) if (parts[i].matches("[0-9A-F]{8}")) t.bg[i] = parseColor(parts[i]);
                } else if (lines[n].startsWith("@")) {
                    String[] two = lines[n].substring(1).split(";", -1);
                    if (two.length == 2) { t.colW = weights(two[0], t.cols); t.rowH = weights(two[1], t.rows); }
                }
            }
            return t;
        }

        /** Column widths and row heights as fractions of the table (each sums to 1); null = all equal. Dragged with the handles on the cell borders. */
        float[] colW, rowH;
        static final float MIN_CELL = .04f;

        private static float[] weights(String text, int count) {
            String[] parts = text.split(",", -1);
            if (parts.length != count) return null;
            float[] w = new float[count]; float sum = 0;
            for (int i = 0; i < count; i++) { try { w[i] = Float.parseFloat(parts[i]); } catch (NumberFormatException e) { return null; } if (!(w[i] > 0f) || w[i] > 1000f) return null; sum += w[i]; }
            for (int i = 0; i < count; i++) w[i] /= sum;
            return w;
        }

        /** Column widths (fractions summing to 1). */
        float[] colWidths() { if (colW != null && colW.length == cols) return colW; float[] w = new float[cols]; java.util.Arrays.fill(w, 1f / cols); return w; }
        /** Row heights (fractions summing to 1). */
        float[] rowHeights() { if (rowH != null && rowH.length == rows) return rowH; float[] h = new float[rows]; java.util.Arrays.fill(h, 1f / rows); return h; }

        /** Moves the border after column/row [index] (0-based) by [delta] of the table, taking it from / giving it to the two neighbours. */
        void dragBorder(boolean column, float[] start, int index, float delta) {
            float[] w = start.clone();
            float pair = w[index] + w[index + 1];
            float a = Math.max(MIN_CELL, Math.min(pair - MIN_CELL, w[index] + delta));
            w[index] = a; w[index + 1] = pair - a;
            if (column) colW = w; else rowH = w;
        }

        private static String weightText(float[] w) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < w.length; i++) { if (i > 0) b.append(','); b.append(String.format(java.util.Locale.US, "%.4f", w[i])); }
            return b.toString();
        }

        private static boolean equal(float[] w) { if (w == null) return true; for (float v : w) if (Math.abs(v - 1f / w.length) > .0005f) return false; return true; }

        static Table create(int rows, int cols, int line, int head, int fill) {
            Table t = new Table();
            t.rows = rows; t.cols = cols; t.line = line; t.head = head; t.fill = fill;
            t.cells = new String[rows * cols];
            java.util.Arrays.fill(t.cells, "");
            t.bg = new int[rows * cols];
            return t;
        }

        /** Keeps existing cell text when the grid size changes. */
        Table resized(int newRows, int newCols) {
            Table t = create(newRows, newCols, line, head, fill);
            if (newRows == rows && newCols == cols) { t.colW = colW; t.rowH = rowH; }
            for (int r = 0; r < Math.min(rows, newRows); r++) for (int c = 0; c < Math.min(cols, newCols); c++) { t.cells[r * newCols + c] = cells[r * cols + c]; t.bg[r * newCols + c] = bg[r * cols + c]; }
            return t;
        }

        String serialize() {
            StringBuilder s = new StringBuilder();
            s.append(rows).append(',').append(cols).append(',').append(hex(line)).append(',').append(hex(head)).append(',').append(hex(fill));
            for (String cell : cells) s.append('\n').append(cell == null ? "" : cell.replace('\n', ' ').replace('\r', ' '));
            boolean any = false;
            for (int color : bg) if ((color >>> 24) != 0) any = true;
            if (any) {
                s.append("\n~");
                for (int i = 0; i < bg.length; i++) { if (i > 0) s.append(','); if ((bg[i] >>> 24) != 0) s.append(hex(bg[i])); }
            }
            if (!equal(colW) || !equal(rowH)) s.append("\n@").append(weightText(colWidths())).append(';').append(weightText(rowHeights()));
            return s.toString();
        }
    }

    static void drawTable(Canvas c, RectF b, String text, float pageWidth) {
        Table t = Table.parse(text);
        float[] cwf = t.colWidths(), chf = t.rowHeights();
        float[] xs = new float[t.cols + 1], ys = new float[t.rows + 1];
        xs[0] = b.left; ys[0] = b.top;
        for (int i = 0; i < t.cols; i++) xs[i + 1] = xs[i] + cwf[i] * b.width();
        for (int i = 0; i < t.rows; i++) ys[i + 1] = ys[i] + chf[i] * b.height();
        float cw = b.width() / t.cols, ch = b.height() / t.rows;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float rad = Math.max(2f, Math.min(pageWidth * .02f, Math.min(ch * .5f, cw * .5f)));   // Samsung Notes: rounded outer corners
        int save = c.save();
        android.graphics.Path outline = new android.graphics.Path(); outline.addRoundRect(b, rad, rad, android.graphics.Path.Direction.CW);
        c.clipPath(outline);
        if ((t.fill >>> 24) > 0) { paint.setColor(t.fill); c.drawRect(b, paint); }
        if ((t.head >>> 24) > 0) { paint.setColor(t.head); c.drawRect(b.left, b.top, b.right, ys[1], paint); }
        for (int r = 0; r < t.rows; r++) for (int col = 0; col < t.cols; col++) {
            int color = t.bg[r * t.cols + col];
            if ((color >>> 24) == 0) continue;
            paint.setColor(color); c.drawRect(xs[col], ys[r], xs[col + 1], ys[r + 1], paint);
        }
        c.restoreToCount(save);
        float width = Math.max(1f, pageWidth * .0022f);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); paint.setColor(AnnotationPainter.adj(t.line));
        c.drawRoundRect(b, rad, rad, paint);
        for (int i = 1; i < t.rows; i++) c.drawLine(b.left, ys[i], b.right, ys[i], paint);
        for (int j = 1; j < t.cols; j++) c.drawLine(xs[j], b.top, xs[j], b.bottom, paint);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF1C1C1E);
        float minRow = ch; for (int i = 0; i < t.rows; i++) minRow = Math.min(minRow, ys[i + 1] - ys[i]);
        float size = Math.max(6f, Math.min(minRow * .46f, pageWidth * .03f));
        tp.setTextSize(size);
        Paint.FontMetrics fm = tp.getFontMetrics();
        for (int r = 0; r < t.rows; r++) for (int col = 0; col < t.cols; col++) {
            String s = t.cells[r * t.cols + col];
            if (s == null || s.isEmpty()) continue;
            float avail = xs[col + 1] - xs[col] - size * .6f;
            if (avail <= 0) continue;
            CharSequence shown = android.text.TextUtils.ellipsize(s, new android.text.TextPaint(tp), avail, android.text.TextUtils.TruncateAt.END);
            int cellBg = t.bg[r * t.cols + col];
            boolean lit = (cellBg >>> 24) > 0 || (r == 0 && (t.head >>> 24) > 0) || (r > 0 && (t.fill >>> 24) > 0);
            boolean dark = (cellBg >>> 24) > 0 && (0.299f * ((cellBg >> 16) & 255) + 0.587f * ((cellBg >> 8) & 255) + 0.114f * (cellBg & 255)) < 110f;
            tp.setColor(dark ? 0xFFFFFFFF : lit ? 0xFF1C1C1E : AnnotationPainter.adj(0xFF1C1C1E));
            tp.setFakeBoldText(r == 0 && (t.head >>> 24) > 0);
            c.drawText(shown.toString(), xs[col] + size * .3f, (ys[r] + ys[r + 1]) / 2f - (fm.ascent + fm.descent) / 2f, tp);
        }
    }
}
