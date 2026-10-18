package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * Layout pieces of the tool strip (the row of tools at the bottom, the rail at a side, the floating pill):
 * {@link Row} spreads the tools evenly over the strip when the screen leaves room (a strip docked at the bottom of a DeX window
 * or a tablet no longer huddles at one end), and the scroll containers {@link Horizontal} / {@link Vertical} show a fade with a
 * chevron on the side where more tools are hidden and scroll one page when that chevron is tapped.
 */
final class ToolStripLayout {
    private ToolStripLayout() { }

    /** A LinearLayout whose visible children are placed with equal gaps (before, between and after them) whenever they need less room than the strip has. */
    static final class Row extends LinearLayout {
        Row(Context context) { super(context); }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            spread(this, r - l, b - t);
        }
    }

    /** Moves the children of {@code bar} along its main axis so that the free room is shared equally; does nothing when the children fill the bar. */
    static void spread(LinearLayout bar, int width, int height) {
        boolean vertical = bar.getOrientation() == LinearLayout.VERTICAL;
        int n = 0, used = 0;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) c.getLayoutParams();
            used += vertical ? c.getMeasuredHeight() + p.topMargin + p.bottomMargin : c.getMeasuredWidth() + p.leftMargin + p.rightMargin;
            n++;
        }
        int start = vertical ? bar.getPaddingTop() : bar.getPaddingLeft();
        int room = (vertical ? height - bar.getPaddingTop() - bar.getPaddingBottom() : width - bar.getPaddingLeft() - bar.getPaddingRight()) - used;
        if (n < 2 || room <= 0) return;
        float gap = room / (float) (n + 1), at = start + gap;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c.getVisibility() == View.GONE) continue;
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) c.getLayoutParams();
            if (vertical) {
                int top = Math.round(at) + p.topMargin;
                c.layout(c.getLeft(), top, c.getRight(), top + c.getMeasuredHeight());
                at += c.getMeasuredHeight() + p.topMargin + p.bottomMargin + gap;
            } else {
                int left = Math.round(at) + p.leftMargin;
                c.layout(left, c.getTop(), left + c.getMeasuredWidth(), c.getBottom());
                at += c.getMeasuredWidth() + p.leftMargin + p.rightMargin + gap;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ "there is more" hint
    private static final int FADE = 0xF0FCFCFF, CHEVRON = 0xFF5C5C61;
    private static final int HINT_DP = 26;

    /** Draws the fade and chevron at the end(s) of {@code view} where its content continues (drawn in view coordinates: the canvas of a scroll view is shifted by its scroll offset). */
    private static void drawHint(Canvas canvas, View view, boolean vertical) {
        float d = view.getResources().getDisplayMetrics().density, size = HINT_DP * d;
        int back = vertical ? view.canScrollVertically(-1) ? -1 : 0 : view.canScrollHorizontally(-1) ? -1 : 0;
        int fwd = vertical ? view.canScrollVertically(1) ? 1 : 0 : view.canScrollHorizontally(1) ? 1 : 0;
        if (back == 0 && fwd == 0) return;
        int w = view.getWidth(), h = view.getHeight();
        canvas.save();
        canvas.translate(view.getScrollX(), view.getScrollY());
        for (int dir = -1; dir <= 1; dir += 2) {
            if ((dir < 0 ? back : fwd) == 0) continue;
            float x0 = vertical ? 0 : dir < 0 ? 0 : w - size, x1 = vertical ? w : dir < 0 ? size : w;
            float y0 = vertical ? dir < 0 ? 0 : h - size : 0, y1 = vertical ? dir < 0 ? size : h : h;
            Paint fade = new Paint();
            fade.setShader(vertical
                ? new LinearGradient(0, dir < 0 ? y0 : y1, 0, dir < 0 ? y1 : y0, FADE, 0x00FCFCFF, Shader.TileMode.CLAMP)
                : new LinearGradient(dir < 0 ? x0 : x1, 0, dir < 0 ? x1 : x0, 0, FADE, 0x00FCFCFF, Shader.TileMode.CLAMP));
            canvas.drawRect(x0, y0, x1, y1, fade);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(CHEVRON); stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(2f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
            float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f, a = 4.5f * d, bb = 3f * d;
            Path chevron = new Path();
            if (vertical) { float tip = cy + dir * bb / 2f; chevron.moveTo(cx - a, tip - dir * bb); chevron.lineTo(cx, tip); chevron.lineTo(cx + a, tip - dir * bb); }
            else { float tip = cx + dir * bb / 2f; chevron.moveTo(tip - dir * bb, cy - a); chevron.lineTo(tip, cy); chevron.lineTo(tip - dir * bb, cy + a); }
            canvas.drawPath(chevron, stroke);
        }
        canvas.restore();
    }

    /** -1 / 1 when (x, y) lies on the hint at the start / end that is showing, else 0. */
    private static int hintAt(View view, boolean vertical, float x, float y) {
        float size = HINT_DP * view.getResources().getDisplayMetrics().density, along = vertical ? y : x, length = vertical ? view.getHeight() : view.getWidth();
        if (along < size && (vertical ? view.canScrollVertically(-1) : view.canScrollHorizontally(-1))) return -1;
        if (along > length - size && (vertical ? view.canScrollVertically(1) : view.canScrollHorizontally(1))) return 1;
        return 0;
    }

    /** Horizontal scroller: the hint and a one-tap page scroll. */
    static final class Horizontal extends HorizontalScrollView {
        private int pressed; private float downX, downY;
        Horizontal(Context context) { super(context); }
        @Override protected void dispatchDraw(Canvas canvas) { super.dispatchDraw(canvas); drawHint(canvas, this, false); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { pressed = hintAt(this, false, e.getX(), e.getY()); downX = e.getX(); downY = e.getY(); }
            if (pressed == 0) return super.dispatchTouchEvent(e);
            if (action == MotionEvent.ACTION_UP) {
                int dir = pressed; pressed = 0;
                if (Math.hypot(e.getX() - downX, e.getY() - downY) <= ViewConfiguration.get(getContext()).getScaledTouchSlop()) smoothScrollBy(Math.round(dir * getWidth() * .7f), 0);
            } else if (action == MotionEvent.ACTION_CANCEL) pressed = 0;
            return true;
        }
    }

    /** Vertical scroller: the hint and a one-tap page scroll. */
    static final class Vertical extends ScrollView {
        private int pressed; private float downX, downY;
        Vertical(Context context) { super(context); }
        @Override protected void dispatchDraw(Canvas canvas) { super.dispatchDraw(canvas); drawHint(canvas, this, true); }
        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) { pressed = hintAt(this, true, e.getX(), e.getY()); downX = e.getX(); downY = e.getY(); }
            if (pressed == 0) return super.dispatchTouchEvent(e);
            if (action == MotionEvent.ACTION_UP) {
                int dir = pressed; pressed = 0;
                if (Math.hypot(e.getX() - downX, e.getY() - downY) <= ViewConfiguration.get(getContext()).getScaledTouchSlop()) smoothScrollBy(0, Math.round(dir * getHeight() * .7f));
            } else if (action == MotionEvent.ACTION_CANCEL) pressed = 0;
            return true;
        }
    }
}
