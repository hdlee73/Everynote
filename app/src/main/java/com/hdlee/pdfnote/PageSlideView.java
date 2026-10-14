package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * Slide page turn: the old page is pushed out and the new one pushed in, side by side, following {@link #setProgress}
 * (0 = old page fully shown, 1 = new page fully shown), so a finger drag and the automatic animation share one code path.
 * A soft shadow on the leading edge of the incoming page separates the two pages.
 */
final class PageSlideView extends View {
    private Bitmap oldPage, newPage;
    private int direction = 1;
    private boolean vertical;
    private float progress;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG), shade = new Paint();

    PageSlideView(Context context) { super(context); }

    /** @param direction +1 when the next page comes in (from the right / bottom), -1 for the previous one. */
    void setup(Bitmap oldPage, Bitmap newPage, int direction, boolean vertical) {
        this.oldPage = oldPage; this.newPage = newPage; this.direction = direction >= 0 ? 1 : -1; this.vertical = vertical; progress = 0f;
    }
    void setProgress(float value) { progress = Math.max(0f, Math.min(1f, value)); invalidate(); }
    float progress() { return progress; }
    /** Distance the pages travel: the width (or height when sliding vertically) of the area. */
    float span() { return vertical ? getHeight() : getWidth(); }
    void release() { for (Bitmap b : new Bitmap[]{oldPage, newPage}) if (b != null && !b.isRecycled()) b.recycle(); oldPage = newPage = null; }

    @Override protected void onDraw(Canvas canvas) {
        if (oldPage == null || newPage == null) return;
        final float span = span(), out = -direction * progress * span, in = direction * (1f - progress) * span;
        canvas.save(); canvas.clipRect(0, 0, getWidth(), getHeight());
        if (vertical) { canvas.drawBitmap(oldPage, 0, out, paint); canvas.drawBitmap(newPage, 0, in, paint); }
        else { canvas.drawBitmap(oldPage, out, 0, paint); canvas.drawBitmap(newPage, in, 0, paint); }
        if (progress > 0f && progress < 1f) {   // shadow cast by the incoming page on the page it covers
            final float reach = Math.max(8f, span * .04f), edge = in;
            final float x0 = direction > 0 ? edge - reach : edge + span, x1 = direction > 0 ? edge : edge + span + reach;
            if (vertical) shade.setShader(new LinearGradient(0, direction > 0 ? edge - reach : edge + span, 0, direction > 0 ? edge : edge + span + reach, direction > 0 ? 0x00000000 : 0x33000000, direction > 0 ? 0x33000000 : 0x00000000, Shader.TileMode.CLAMP));
            else shade.setShader(new LinearGradient(x0, 0, x1, 0, direction > 0 ? 0x00000000 : 0x33000000, direction > 0 ? 0x33000000 : 0x00000000, Shader.TileMode.CLAMP));
            if (vertical) canvas.drawRect(0, direction > 0 ? edge - reach : edge + span, getWidth(), direction > 0 ? edge : edge + span + reach, shade);
            else canvas.drawRect(x0, 0, x1, getHeight(), shade);
            shade.setShader(null);
        }
        canvas.restore();
    }
}
