package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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

    /**
     * A pen illustration standing on {@code baseY} (the part below it is cut off by the card edge), tip up and {@code color} on the tip.
     * kind 0 ballpoint, 1 pencil, 2 fountain pen, 3 brush, 4 felt marker, 5 highlighter (chisel tip).
     */
    static void drawPen(Canvas c, float cx, float baseY, float w, float h, int kind, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int tip = (color & 0xFFFFFF) | 0xFF000000;
        float top = baseY - h, tipH = h * .30f, gripH = h * .22f, tipBottom = top + tipH, gripBottom = tipBottom + gripH, half = w / 2f;
        // body: a long rounded barrel
        p.setStyle(Paint.Style.FILL); p.setColor(kind == 1 ? tip : 0xFFF1F2F6);
        c.drawRoundRect(new RectF(cx - half, gripBottom - w * .1f, cx + half, baseY + w), w * .2f, w * .2f, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, w * .035f)); p.setColor(0x22000000);
        c.drawRoundRect(new RectF(cx - half, gripBottom - w * .1f, cx + half, baseY + w), w * .2f, w * .2f, p);
        // grip: slightly narrower towards the tip
        Path grip = new Path();
        grip.moveTo(cx - half, gripBottom); grip.lineTo(cx - half * .78f, tipBottom); grip.lineTo(cx + half * .78f, tipBottom); grip.lineTo(cx + half, gripBottom); grip.close();
        p.setStyle(Paint.Style.FILL); p.setColor(kind == 2 || kind == 3 ? 0xFF8A8E99 : 0xFF4A4D57); c.drawPath(grip, p);
        // colour band so the chosen colour is visible on every pen
        p.setColor(tip); c.drawRect(cx - half, gripBottom, cx + half, gripBottom + Math.max(2f, h * .035f), p);
        Path nib = new Path();
        switch (kind) {
            case 1: {   // pencil: wood cone with a lead
                nib.moveTo(cx - half * .78f, tipBottom); nib.lineTo(cx, top); nib.lineTo(cx + half * .78f, tipBottom); nib.close();
                p.setColor(0xFFF0D3A0); c.drawPath(nib, p);
                Path lead = new Path(); lead.moveTo(cx - half * .24f, top + tipH * .68f); lead.lineTo(cx, top); lead.lineTo(cx + half * .24f, top + tipH * .68f); lead.close();
                p.setColor(tip); c.drawPath(lead, p); break; }
            case 2: {   // fountain pen: kite shaped nib
                nib.moveTo(cx, top); nib.lineTo(cx + half * .7f, top + tipH * .62f); nib.lineTo(cx + half * .5f, tipBottom); nib.lineTo(cx - half * .5f, tipBottom); nib.lineTo(cx - half * .7f, top + tipH * .62f); nib.close();
                p.setColor(0xFFC9B27C); c.drawPath(nib, p);
                p.setColor(tip); c.drawCircle(cx, top + tipH * .55f, Math.max(1.5f, w * .08f), p); break; }
            case 3: {   // brush: tapered bristles
                nib.moveTo(cx - half * .62f, tipBottom); nib.quadTo(cx - half * .7f, top + tipH * .3f, cx + half * .06f, top); nib.quadTo(cx + half * .62f, top + tipH * .35f, cx + half * .62f, tipBottom); nib.close();
                p.setColor(tip); c.drawPath(nib, p); break; }
            case 4: {   // felt marker: rounded block
                p.setColor(tip); c.drawRoundRect(new RectF(cx - half * .52f, top, cx + half * .52f, tipBottom + 1f), w * .16f, w * .16f, p); break; }
            case 5: {   // highlighter: wide chisel
                nib.moveTo(cx - half * .86f, tipBottom); nib.lineTo(cx - half * .86f, top + tipH * .5f); nib.lineTo(cx + half * .5f, top); nib.lineTo(cx + half * .86f, top + tipH * .5f); nib.lineTo(cx + half * .86f, tipBottom); nib.close();
                p.setColor(tip); c.drawPath(nib, p); break; }
            default: {  // ballpoint: slim cone
                nib.moveTo(cx - half * .6f, tipBottom); nib.lineTo(cx - half * .12f, top + tipH * .12f); nib.lineTo(cx, top); nib.lineTo(cx + half * .12f, top + tipH * .12f); nib.lineTo(cx + half * .6f, tipBottom); nib.close();
                p.setColor(0xFFB9BCC6); c.drawPath(nib, p);
                p.setColor(tip); c.drawCircle(cx, top + Math.max(1f, w * .03f), Math.max(1.5f, w * .07f), p); break; }
        }
    }
}
