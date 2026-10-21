package com.hdlee.pdfnote;

import android.content.Context;
import android.content.SharedPreferences;

/** Reading preferences of the EPUB reader (font size, line spacing, margins, font, theme). They also drive the PDF conversion. */
final class EpubSettings {
    static final int ZOOM_MIN = 70, ZOOM_MAX = 250, ZOOM_STEP = 10;
    static final int LINE_MIN = 12, LINE_MAX = 30;       // tenths: 1.2 .. 3.0
    static final int MARGIN_MAX = 48, MARGIN_STEP = 4;   // dp
    static final String[] FONT_NAMES = {"기본", "명조", "고딕"};
    static final String[] THEME_NAMES = {"흰색", "크림", "회색", "검정"};
    static final int[] THEME_BG = {0xFFFFFFFF, 0xFFF4ECD8, 0xFF3A3A3C, 0xFF000000};
    static final int[] THEME_FG = {0xFF1C1C1E, 0xFF4A3B28, 0xFFE5E5EA, 0xFFD1D1D6};

    int zoom = 100, line = 16, margin = 16, font = 0, theme = 0;
    private final SharedPreferences prefs;

    EpubSettings(Context context) {
        prefs = context.getSharedPreferences("epub_reader", Context.MODE_PRIVATE);
        zoom = clamp(prefs.getInt("zoom", 100), ZOOM_MIN, ZOOM_MAX);
        line = clamp(prefs.getInt("line", 16), LINE_MIN, LINE_MAX);
        margin = clamp(prefs.getInt("margin", 16), 0, MARGIN_MAX);
        font = clamp(prefs.getInt("font", 0), 0, FONT_NAMES.length - 1);
        theme = clamp(prefs.getInt("theme", 0), 0, THEME_NAMES.length - 1);
    }

    void save() { prefs.edit().putInt("zoom", zoom).putInt("line", line).putInt("margin", margin).putInt("font", font).putInt("theme", theme).apply(); }

    int background() { return THEME_BG[theme]; }
    int foreground() { return THEME_FG[theme]; }
    boolean dark() { return theme >= 2; }
    float lineSpacing() { return line / 10f; }

    static String fontStack(int font) { return font == 1 ? "serif" : font == 2 ? "sans-serif" : null; }

    /** Side padding of a page in CSS pixels: the reader's margin setting on top of a small fixed gutter. */
    int side() { return 12 + margin; }

    /**
     * Style sheet injected into every chapter. The body becomes a column container exactly one screen wide and high, so each
     * column is one page and a page turn is a horizontal scroll by one screen width. [top] and [bottom] (CSS pixels) keep the text
     * clear of the status bar, a camera cut-out and the page number.
     */
    String css(int top, int bottom) {
        String family = fontStack(font);
        String color = String.format("#%06X", foreground() & 0xFFFFFF), bg = String.format("#%06X", background() & 0xFFFFFF);
        String link = dark() ? "#8AB4FF" : "#0A5BD8";
        int side = side();
        return "html{height:100% !important;overflow:hidden !important;background:" + bg + " !important;color:" + color + " !important;}"
            + "body{margin:0 !important;padding:" + top + "px " + side + "px " + bottom + "px !important;width:100vw !important;height:100vh !important;box-sizing:border-box !important;"
            + "max-width:none !important;background:transparent !important;overflow-wrap:break-word;"
            + "column-width:calc(100vw - " + (2 * side) + "px) !important;column-gap:" + (2 * side) + "px !important;column-fill:auto !important;}"
            + "body,body *{line-height:" + lineSpacing() + " !important;color:inherit !important;background-color:transparent !important;max-width:100%;box-sizing:border-box;"
            + (family == null ? "" : "font-family:" + family + " !important;") + "}"
            + "code,pre,tt,kbd{font-family:monospace !important;}"
            + "img,svg,video{max-width:100% !important;max-height:calc(100vh - " + (top + bottom) + "px) !important;width:auto;height:auto !important;object-fit:contain;break-inside:avoid;}"
            + "h1,h2,h3,h4{break-after:avoid;}"
            + "a,a *{color:" + link + " !important;}";
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
