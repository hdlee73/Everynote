package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Shared look of the note-app style UI: soft white surfaces, large radii, a light blue accent and dotted dividers. */
final class UiStyle {
    private UiStyle() {}

    static final int ACCENT = 0xFF3E6DF0, INK = 0xFF17181C, MUTED = 0xFF6B6F7B, LINE = 0xFFE2E4EA, SURFACE = 0xFFFFFFFF,
        PAPER = 0xFFF7F8FB, CHIP = 0xFFE8ECF8, DANGER = 0xFFE5484D, TINT1 = 0xFFCFE9F7, TINT2 = 0xFFD9DCF6;

    static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static GradientDrawable round(Context context, int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radiusDp));
        return shape;
    }

    static GradientDrawable roundStroke(Context context, int color, float radiusDp, int strokeColor) {
        GradientDrawable shape = round(context, color, radiusDp);
        shape.setStroke(Math.max(1, dp(context, 1)), strokeColor);
        return shape;
    }

    /** A white floating panel: large corners and a hairline edge (the soft shadow comes from view elevation). */
    static GradientDrawable panel(Context context, float radiusDp) { return roundStroke(context, SURFACE, radiusDp, 0x14000000); }

    /** A floating panel tinted with a very light blue to lavender wash, white towards the bottom right. */
    static GradientDrawable softPanel(Context context, float radiusDp) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFD7ECF8, 0xFFE4E7F9, 0xFFF8F9FC});
        shape.setCornerRadius(dp(context, radiusDp));
        shape.setStroke(Math.max(1, dp(context, 1)), 0x14000000);
        return shape;
    }

    /** A dotted horizontal rule. */
    static GradientDrawable dotted(Context context) {
        GradientDrawable line = new GradientDrawable();
        line.setShape(GradientDrawable.LINE);
        line.setStroke(dp(context, 1.5f), 0xFFB9BDC9, dp(context, 2), dp(context, 4));
        return line;
    }

    static View dottedDivider(Context context, int marginHorizontalDp, int marginVerticalDp) {
        View view = new View(context);
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // dashed LINE shapes are not drawn by the hardware renderer on every device
        view.setBackground(dotted(context));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2));
        lp.setMargins(dp(context, marginHorizontalDp), dp(context, marginVerticalDp), dp(context, marginHorizontalDp), dp(context, marginVerticalDp));
        view.setLayoutParams(lp);
        return view;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    static GradientDrawable circleStroke(Context context, int color, int strokeColor, float strokeDp) {
        GradientDrawable shape = circle(color);
        shape.setStroke(Math.max(1, dp(context, strokeDp)), strokeColor);
        return shape;
    }

    /** Same colour with another alpha (0-255). */
    static int alpha(int color, int alpha) { return (color & 0xFFFFFF) | (alpha << 24); }

    static boolean isDark(int color) { return Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114 < 140; }
}
