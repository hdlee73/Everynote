package com.hdlee.pdfnote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Samsung Notes style popovers: a rounded light card with dashed dividers (sticky memo colours, table menu) and the number wheel of 표 설정. */
final class SamsungPopup {
    static final int INK = 0xFF010102, MUTED = 0xFF848487, DANGER = 0xFFDB332A, CARD = 0xFFF6F6F6;
    private SamsungPopup() {}

    private static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** A vertical card the caller fills with the helpers below; call {@link #show} with it. */
    static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 8));
        return box;
    }

    /** Shows [content] next to the screen point (x, y), kept inside the window. */
    static PopupWindow show(Activity activity, View content, int x, int y, int widthDp) {
        Context c = activity;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(c, 26));
        content.setBackground(bg);
        content.measure(View.MeasureSpec.makeMeasureSpec(dp(c, widthDp), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = content.getMeasuredWidth(), h = content.getMeasuredHeight();
        Rect frame = new Rect();
        View root = activity.getWindow().getDecorView();
        root.getWindowVisibleDisplayFrame(frame);
        int left = Math.max(frame.left + dp(c, 8), Math.min(frame.right - w - dp(c, 8), x - w / 2));
        int top = y + dp(c, 12);
        if (top + h > frame.bottom - dp(c, 8)) top = Math.max(frame.top + dp(c, 8), y - h - dp(c, 12));
        PopupWindow popup = new PopupWindow(content, w, h, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(c, 10));
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.showAtLocation(root, Gravity.NO_GRAVITY, left, top);
        content.setTag(popup);
        return popup;
    }

    static void dismiss(View anyViewInCard) {
        View v = anyViewInCard;
        while (v != null) {
            if (v.getTag() instanceof PopupWindow) { ((PopupWindow) v.getTag()).dismiss(); return; }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
    }

    static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14.5f);
        t.setTextColor(INK);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(c, 6), dp(c, 2), dp(c, 6), dp(c, 8));
        return t;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.LINE);
        g.setStroke(dp(c, 1), 0xFFDADADD, dp(c, 4), dp(c, 3));
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 3));
        lp.setMargins(dp(c, 4), dp(c, 2), dp(c, 4), dp(c, 4));
        v.setLayoutParams(lp);
        return v;
    }

    /** A menu row: [iconRes] (0 = none) and [label]; tapping closes the card and runs [action]. */
    static View row(Context c, String label, int iconRes, boolean danger, Runnable action) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 44));
        r.setPadding(dp(c, 8), 0, dp(c, 8), 0);
        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            if (danger) icon.setColorFilter(DANGER);
            r.addView(icon, new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
        }
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(danger ? DANGER : INK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = iconRes != 0 ? dp(c, 12) : 0;
        r.addView(t, lp);
        r.setContentDescription(label);
        r.setOnClickListener(v -> { dismiss(v); action.run(); });
        return r;
    }

    /** A row of icon buttons; a null entry draws a thin separator. */
    static View iconRow(Context c, IconButton... items) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 6));
        for (IconButton it : items) {
            if (it == null) {
                View sep = new View(c);
                sep.setBackgroundColor(0xFFD6D6DA);
                row.addView(sep, new LinearLayout.LayoutParams(dp(c, 1), dp(c, 20)));
                continue;
            }
            ImageView b = new ImageView(c);
            b.setImageResource(it.icon);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setContentDescription(it.label);
            b.setAlpha(it.enabled ? 1f : .35f);
            if (it.enabled) b.setOnClickListener(v -> { dismiss(v); it.action.run(); });
            row.addView(b, new LinearLayout.LayoutParams(0, dp(c, 36), 1));
        }
        return row;
    }

    static final class IconButton {
        final int icon; final String label; final boolean enabled; final Runnable action;
        IconButton(int icon, String label, boolean enabled, Runnable action) { this.icon = icon; this.label = label; this.enabled = enabled; this.action = action; }
    }

    /** Round colour chips; the one equal to [current] shows a check mark. */
    static View swatches(Context c, int[] colors, int current, IntConsumer pick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, 6), 0, dp(c, 6), dp(c, 10));
        for (int color : colors) {
            TextView chip = new TextView(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            chip.setBackground(g);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(12);
            chip.setTextColor(0xFF3A3A3D);
            if (color == current) chip.setText("✓");
            chip.setContentDescription("배경 색상");
            chip.setOnClickListener(v -> { dismiss(v); pick.accept(color); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 26), dp(c, 26));
            lp.rightMargin = dp(c, 10);
            row.addView(chip, lp);
        }
        return row;
    }

    /** One number wheel (previous / current / next value). Drag up or down, or tap a neighbour, to change it. */
    static final class NumberWheel extends LinearLayout {
        private final int min, max;
        private int value;
        private final TextView[] cells = new TextView[5];
        private float downY;
        private int downValue;

        NumberWheel(Context c, int min, int max, int value) {
            super(c);
            this.min = min; this.max = max; this.value = Math.max(min, Math.min(max, value));
            setOrientation(VERTICAL);
            for (int i = 0; i < 5; i++) {
                TextView t = new TextView(c);
                t.setGravity(Gravity.CENTER);
                t.setTextColor(INK);
                int offset = i - 2;
                t.setTextSize(offset == 0 ? 26 : 20);
                if (offset == 0) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setAlpha(offset == 0 ? 1f : Math.abs(offset) == 1 ? .35f : .1f);
                cells[i] = t;
                addView(t, new LayoutParams(dp(c, 70), dp(c, Math.abs(offset) == 2 ? 30 : 34)));
                t.setOnClickListener(v -> set(this.value + offset));
            }
            setFocusable(true);
            draw();
        }

        int value() { return value; }
        private void set(int v) { value = Math.max(min, Math.min(max, v)); draw(); }
        private void draw() {
            for (int i = 0; i < 5; i++) { int v = value + i - 2; cells[i].setText(v >= min && v <= max ? String.valueOf(v) : ""); }
            setContentDescription(String.valueOf(value));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downY = e.getY(); downValue = value; }
            else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getY() - downY) > dp(getContext(), 8)) { getParent().requestDisallowInterceptTouchEvent(true); return true; }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { downY = e.getY(); downValue = value; return true; }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { set(downValue + Math.round((downY - e.getY()) / dp(getContext(), 28))); return true; }
            return true;
        }
    }
}
