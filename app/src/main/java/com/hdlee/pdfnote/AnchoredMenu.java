package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact floating menu card in the style of a modern note app: a short list of text rows (optionally with a small icon, check
 * or chevron), an optional row of icon-only shortcuts, anchored to the button that opened it, either below it (header menus)
 * or directly above it (bottom-toolbar menus).
 */
final class AnchoredMenu {
    static final int INK = 0xFF010102, ACCENT = 0xFF387AFF, GRAY = 0xFF848487, LINE = 0xFFE5E5EA, RED = 0xFFFF3B30;

    static final class Row {
        final String label; final int icon; final Runnable action; boolean selected, danger, submenu, divider; View custom; int tint; List<Row> children;
        Row(String label, int icon, Runnable action) { this.label = label; this.icon = icon; this.action = action; }
        static Row divider() { Row r = new Row("", 0, null); r.divider = true; return r; }
        static Row custom(View view) { Row r = new Row("", 0, null); r.custom = view; return r; }
        Row tint(int value) { tint = value; return this; }
        Row selected(boolean value) { selected = value; return this; }
        Row danger() { danger = true; return this; }
        Row submenu() { submenu = true; return this; }
        /** Rows shown in a second card right next to this one when this row is tapped (the parent card stays open). */
        Row children(List<Row> rows) { children = rows; submenu = true; return this; }
        Row copy(Runnable newAction) { Row r = new Row(label, icon, newAction); r.selected = selected; r.danger = danger; r.submenu = submenu; r.divider = divider; r.custom = custom; r.tint = tint; r.children = children; return r; }
    }
    static final class Shortcut {
        final String description; final int icon; final boolean active; final Runnable action;
        Shortcut(String description, int icon, boolean active, Runnable action) { this.description = description; this.icon = icon; this.active = active; this.action = action; }
    }

    private AnchoredMenu() {}

    /** @param above true to place the card right above the anchor (toolbar menus), false to drop it below the anchor. */
    static PopupWindow show(Context context, View anchor, boolean above, List<Row> rows, List<Shortcut> shortcuts) { return show(context, anchor, above, rows, shortcuts, null); }

    /** @param avoid screen rectangle the card must not cover (e.g. the selected text); the card goes below, above, beside it, whichever fits. */
    static PopupWindow show(Context context, View anchor, boolean above, List<Row> rows, List<Shortcut> shortcuts, android.graphics.Rect avoid) { return build(context, anchor, above, rows, shortcuts, avoid, 0, true); }

    private static PopupWindow build(Context context, View anchor, boolean above, List<Row> rows, List<Shortcut> shortcuts, android.graphics.Rect avoid, int mode, boolean focusable) { return build(context, anchor, above, rows, shortcuts, avoid, mode, focusable, null); }

    /** @param tapped for a submenu: the tapped row on screen; the new card opens right beside it, level with it. */
    private static PopupWindow build(Context context, View anchor, boolean above, List<Row> rows, List<Shortcut> shortcuts, android.graphics.Rect avoid, int mode, boolean focusable, android.graphics.Rect tapped) {
        final boolean beside = mode == 1;
        final float density = context.getResources().getDisplayMetrics().density;
        final int screenW = context.getResources().getDisplayMetrics().widthPixels, screenH = context.getResources().getDisplayMetrics().heightPixels;
        final PopupWindow[] holder = new PopupWindow[1], childWin = new PopupWindow[1];
        final LinearLayout[] cardRef = new LinearLayout[1];
        LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setTag("anchored_menu"); cardRef[0] = card;
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(18 * density); bg.setStroke(Math.max(1, Math.round(density * .5f)), 0x14000000); card.setBackground(bg);
        card.setPadding(Math.round(6 * density), Math.round(6 * density), Math.round(6 * density), Math.round(6 * density)); card.setElevation(12 * density);
        LinearLayout list = new LinearLayout(context); list.setOrientation(LinearLayout.VERTICAL);
        boolean wide = false, panel = false;
        for (final Row row : rows) {
            if (row.custom != null) { list.addView(row.custom, new LinearLayout.LayoutParams(-1, -2)); wide = true; panel |= "pen_panel".equals(row.custom.getTag()); continue; }
            if (row.divider) { View line = new View(context); line.setBackgroundColor(LINE); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(density * .5f))); lp.setMargins(Math.round(12 * density), Math.round(4 * density), Math.round(12 * density), Math.round(4 * density)); list.addView(line, lp); continue; }
            LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(Math.round(12 * density), Math.round(4 * density), Math.round(12 * density), Math.round(4 * density)); line.setMinimumHeight(Math.round(42 * density)); line.setContentDescription(row.label);
            if (row.icon != 0) { ImageView glyph = new ImageView(context); glyph.setImageResource(row.icon); glyph.setColorFilter(row.danger ? RED : row.tint != 0 ? row.tint : ACCENT); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(Math.round(20 * density), Math.round(20 * density)); gp.rightMargin = Math.round(12 * density); line.addView(glyph, gp); }
            TextView text = new TextView(context); text.setText(row.label); text.setTextSize(14); text.setMaxLines(2); text.setEllipsize(android.text.TextUtils.TruncateAt.END); text.setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);   // small screens: shrink before cutting text.setTextColor(row.danger ? RED : row.selected ? ACCENT : INK); if (row.selected) text.setTypeface(Typeface.DEFAULT_BOLD);
            line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            if (row.selected || row.submenu) { ImageView mark = new ImageView(context); mark.setImageResource(row.submenu ? R.drawable.ic_chevron_right : R.drawable.ic_check_bold); mark.setColorFilter(row.submenu ? GRAY : ACCENT); mark.setScaleType(ImageView.ScaleType.FIT_CENTER); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Math.round((row.submenu ? 18 : 20) * density), Math.round((row.submenu ? 18 : 20) * density)); mp.leftMargin = Math.round(8 * density); line.addView(mark, mp); }
            line.setOnClickListener(v -> {
                if (row.children != null && holder[0] != null) {
                    if (childWin[0] != null) childWin[0].dismiss();
                    int[] loc = new int[2]; cardRef[0].getLocationOnScreen(loc);
                    android.graphics.Rect box = new android.graphics.Rect(loc[0], loc[1], loc[0] + cardRef[0].getWidth(), loc[1] + cardRef[0].getHeight());
                    List<Row> inner = new ArrayList<>();
                    for (final Row child : row.children) inner.add(child.custom != null || child.divider ? child : child.copy(() -> { if (holder[0] != null) holder[0].dismiss(); if (child.action != null) child.action.run(); }));
                    int[] rl = new int[2]; v.getLocationOnScreen(rl);
                    childWin[0] = build(context, anchor, above, inner, null, box, 1, false, new android.graphics.Rect(rl[0], rl[1], rl[0] + v.getWidth(), rl[1] + v.getHeight()));
                    return;
                }
                if (holder[0] != null) holder[0].dismiss(); if (row.action != null) row.action.run(); });
            list.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(context) { @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(screenH * .6f), MeasureSpec.AT_MOST)); } };
        scroll.setVerticalScrollBarEnabled(true); scroll.setScrollbarFadingEnabled(false); scroll.setScrollBarSize(Math.round(3 * density)); scroll.addView(list);   // a thin bar on the right shows there is more to scroll
        android.widget.FrameLayout scrollBox = new android.widget.FrameLayout(context); scrollBox.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -2));
        // more rows below the visible part: a fading strip with a double chevron (⌄⌄) that disappears at the end of the list
        final ImageView more = new ImageView(context); more.setImageResource(R.drawable.ic_chevron_double_down); more.setColorFilter(ACCENT); more.setScaleType(ImageView.ScaleType.CENTER_INSIDE); more.setPadding(0, Math.round(9 * density), 0, Math.round(3 * density)); more.setTag("menu_more"); more.setContentDescription("아래에 더 있음");
        more.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00FFFFFF, 0xE6FFFFFF, 0xFFFFFFFF})); more.setVisibility(View.GONE);
        more.setOnClickListener(v -> scroll.smoothScrollBy(0, Math.round(screenH * .3f)));
        scrollBox.addView(more, new android.widget.FrameLayout.LayoutParams(-1, Math.round(40 * density), Gravity.BOTTOM));
        final Runnable checkMore = () -> more.setVisibility(scroll.canScrollVertically(1) ? View.VISIBLE : View.GONE);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> checkMore.run()); scroll.getViewTreeObserver().addOnGlobalLayoutListener(checkMore::run); scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> checkMore.run()); scroll.post(checkMore);
        card.addView(scrollBox, new LinearLayout.LayoutParams(-1, -2));
        if (shortcuts != null && !shortcuts.isEmpty()) {
            View line = new View(context); line.setBackgroundColor(LINE); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(density * .5f))); lp.setMargins(Math.round(8 * density), Math.round(4 * density), Math.round(8 * density), Math.round(2 * density)); card.addView(line, lp);
            LinearLayout icons = new LinearLayout(context); icons.setGravity(Gravity.CENTER);
            for (final Shortcut s : shortcuts) {
                ImageButton b = new ImageButton(context); b.setImageResource(s.icon); b.setColorFilter(s.active ? 0xFFF5A623 : 0xFF5856D6); b.setBackgroundColor(Color.TRANSPARENT); b.setContentDescription(s.description); b.setPadding(Math.round(10 * density), Math.round(10 * density), Math.round(10 * density), Math.round(10 * density)); b.setScaleType(ImageView.ScaleType.FIT_CENTER);
                b.setOnClickListener(v -> { if (holder[0] != null) holder[0].dismiss(); s.action.run(); });
                icons.addView(b, new LinearLayout.LayoutParams(0, Math.round(44 * density), 1));
            }
            card.addView(icons, new LinearLayout.LayoutParams(-1, Math.round(44 * density)));
        }
        float natural = Math.min(screenW - 24 * density, (panel ? 304 : wide ? 268 : 250) * density);
        boolean sideRight = true;
        final android.graphics.Rect bar = verticalBar(anchor);
        if (beside && avoid != null) {
            // a submenu opens right next to its parent: on whichever side has room, narrowed (rows ellipsize) when the screen is tight
            float roomRight = screenW - avoid.right - 14 * density, roomLeft = avoid.left - 14 * density;
            if (roomRight >= natural) sideRight = true; else if (roomLeft >= natural) sideRight = false;
            else { sideRight = roomRight >= roomLeft; natural = Math.max(Math.min(natural, 150 * density), Math.max(roomRight, roomLeft)); natural = Math.min(natural, screenW - 24 * density); }
            // opened from a side toolbar: keep growing away from the bar (narrowed if needed) rather than doubling back over it
            if (bar != null) { boolean barLeft = bar.centerX() < screenW / 2; float room = barLeft ? roomRight : roomLeft; if (room >= 150 * density && sideRight != barLeft) { sideRight = barLeft; natural = Math.min(natural, room); } }
        }
        card.measure(View.MeasureSpec.makeMeasureSpec(Math.round(natural), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST));
        int w = card.getMeasuredWidth(), h = card.getMeasuredHeight();
        PopupWindow window = new PopupWindow(card, w, h, focusable); holder[0] = window;
        window.setOnDismissListener(() -> { if (childWin[0] != null) childWin[0].dismiss(); });
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); window.setElevation(12 * density); window.setOutsideTouchable(true);
        int[] at = new int[2]; anchor.getLocationOnScreen(at);
        int margin = Math.round(8 * density), gap = Math.round(6 * density);
        int x = above ? at[0] + anchor.getWidth() / 2 - w / 2 : at[0] + anchor.getWidth() - w;
        x = Math.max(margin, Math.min(screenW - w - margin, x));
        int y = above ? at[1] - h - gap : at[1] + anchor.getHeight() + gap;
        y = Math.max(margin, Math.min(screenH - h - margin, y));
        if (beside && avoid != null) {
            int sideGap = Math.round(4 * density);
            x = sideRight ? avoid.right + sideGap : avoid.left - w - sideGap;
            if (x < margin || x + w > screenW - margin) x = Math.max(margin, Math.min(screenW - w - margin, x));   // tight screen: overlap the parent a little rather than leave the screen
            int rowTop = tapped != null ? tapped.top - Math.round(6 * density) : avoid.top;   // first child row sits level with the tapped row
            y = Math.max(margin, Math.min(screenH - h - margin, rowTop));
        } else if (avoid != null && mode != 3) {
            int topLimit = Math.round(24 * density);
            int[][] tries = beside ? new int[][]{
                {avoid.left - w - gap, avoid.top}, {avoid.right + gap, avoid.top},
                {avoid.centerX() - w / 2, avoid.bottom + gap}, {avoid.centerX() - w / 2, avoid.top - h - gap}} : new int[][]{
                {avoid.centerX() - w / 2, avoid.bottom + gap}, {avoid.centerX() - w / 2, avoid.top - h - gap},
                {avoid.right + gap, avoid.centerY() - h / 2}, {avoid.left - w - gap, avoid.centerY() - h / 2}};
            boolean placed = false;
            for (int[] t : tries) {
                int tx = Math.max(margin, Math.min(screenW - w - margin, t[0])), ty = t[1];
                boolean sideTry = beside ? (t == tries[0] || t == tries[1]) : (t == tries[2] || t == tries[3]);
                if (ty < topLimit || ty + h > screenH - margin) { if (sideTry) ty = Math.max(topLimit, Math.min(screenH - h - margin, ty)); else continue; }
                if (tx + w + gap / 2 <= avoid.left || tx >= avoid.right + gap / 2 || ty + h + gap / 2 <= avoid.top || ty >= avoid.bottom + gap / 2) { x = tx; y = ty; placed = true; break; }
            }
            if (!placed) {
                // nothing fits without a cover: dock at the screen edge that hides the least of the selection
                int topY = topLimit, bottomY = screenH - h - margin;
                int coverTop = Math.max(0, Math.min(topY + h, avoid.bottom) - Math.max(topY, avoid.top)), coverBottom = Math.max(0, Math.min(bottomY + h, avoid.bottom) - Math.max(bottomY, avoid.top));
                y = coverTop <= coverBottom ? topY : bottomY; x = Math.max(margin, Math.min(screenW - w - margin, avoid.centerX() - w / 2));
            }
        }
        if (bar != null && !beside && avoid == null && mode == 0) {
            // vertical toolbar: the card opens beside the bar, level with the tapped button, never on top of the bar
            boolean right = bar.centerX() < screenW / 2;
            x = right ? bar.right + gap : bar.left - w - gap; x = Math.max(margin, Math.min(screenW - w - margin, x));
            y = Math.max(margin, Math.min(screenH - h - margin, at[1] + anchor.getHeight() / 2 - h / 2));
        }
        if (mode == 3 && avoid != null) { x = Math.min(screenW - w - margin, avoid.right + gap); y = Math.max(margin, Math.min(screenH - h - margin, at[1] + anchor.getHeight() / 2 - h / 2)); }
        if (mode == 2) { x = (screenW - w) / 2; y = Math.max(margin, (screenH - h) / 3); }
        window.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.START, x, y);
        return window;
    }
    /** Screen bounds of the vertical (side) toolbar that holds {@code anchor}, or null when the anchor is not in one. */
    static android.graphics.Rect verticalBar(View anchor) {
        for (android.view.ViewParent p = anchor.getParent(); p instanceof View; p = p.getParent()) {
            View v = (View) p; Object tag = v.getTag();
            if (("reading_toolbar".equals(tag) || "fullscreen_toolbar".equals(tag)) && v instanceof LinearLayout && ((LinearLayout) v).getOrientation() == LinearLayout.VERTICAL) {
                int[] l = new int[2]; v.getLocationOnScreen(l); return new android.graphics.Rect(l[0], l[1], l[0] + v.getWidth(), l[1] + v.getHeight());
            }
        }
        return null;
    }
    /** A menu card that opens to the right of {@code panel} (e.g. the side panel), level with the tapped ⋮ button. */
    static PopupWindow showRightOf(Context context, View anchor, View panel, List<Row> rows) {
        int[] loc = new int[2]; panel.getLocationOnScreen(loc);
        return build(context, anchor, false, rows, null, new android.graphics.Rect(loc[0], loc[1], loc[0] + panel.getWidth(), loc[1] + panel.getHeight()), 3, true);
    }
    /** A titled menu card in the middle of the screen (no dimming), for lists that are not opened from one particular button. */
    static PopupWindow showCentered(Context context, View anyView, String title, List<Row> rows) {
        List<Row> all = new ArrayList<>();
        if (title != null) { TextView t = new TextView(context); t.setText(title); t.setTextSize(13); t.setTextColor(GRAY); t.setPadding(Math.round(12 * context.getResources().getDisplayMetrics().density), Math.round(8 * context.getResources().getDisplayMetrics().density), Math.round(12 * context.getResources().getDisplayMetrics().density), Math.round(4 * context.getResources().getDisplayMetrics().density)); all.add(Row.custom(t)); }
        all.addAll(rows);
        return build(context, anyView, false, all, null, null, 2, true);
    }
    static List<Row> rows(Row... rows) { List<Row> list = new ArrayList<>(); for (Row r : rows) list.add(r); return list; }
}
