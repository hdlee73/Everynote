package com.hdlee.pdfnote;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The tool strip spreads its tools over the whole bar when there is room, and says so when more tools are hidden. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ToolStripLayoutTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).get();

    private static void layout(View v, int w, int h) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
    }

    private ToolStripLayout.Row row(int orientation, int count) {
        ToolStripLayout.Row row = new ToolStripLayout.Row(activity);
        row.setOrientation(orientation);
        for (int i = 0; i < count; i++) row.addView(new View(activity), new LinearLayout.LayoutParams(40, 40));
        return row;
    }

    @Test public void toolsAreSpreadEvenlyAcrossAWideBar() {
        ToolStripLayout.Row row = row(LinearLayout.HORIZONTAL, 4);
        layout(row, 400, 40);
        int[] left = new int[4];
        for (int i = 0; i < 4; i++) left[i] = row.getChildAt(i).getLeft();
        assertEquals("equal room before the first tool", 48, left[0]);
        for (int i = 1; i < 4; i++) assertEquals("equal gap between the tools", 48, left[i] - left[i - 1] - 40);
        assertEquals("and after the last one", 48, 400 - row.getChildAt(3).getRight());
    }

    @Test public void toolsAreSpreadEvenlyDownATallRail() {
        ToolStripLayout.Row row = row(LinearLayout.VERTICAL, 3);
        layout(row, 40, 300);
        int top0 = row.getChildAt(0).getTop(), top1 = row.getChildAt(1).getTop(), top2 = row.getChildAt(2).getTop();
        assertEquals(45, top0);
        assertEquals(top1 - top0, top2 - top1);
        assertEquals(45, 300 - row.getChildAt(2).getBottom());
    }

    @Test public void toolsStayPackedWhenTheyNeedTheWholeBar() {
        ToolStripLayout.Row row = row(LinearLayout.HORIZONTAL, 4);
        layout(row, 160, 40);
        for (int i = 0; i < 4; i++) assertEquals(i * 40, row.getChildAt(i).getLeft());
    }

    @Test public void hiddenToolsAreHintedAtTheEndWhereTheStripContinues() {
        ToolStripLayout.Horizontal scroll = new ToolStripLayout.Horizontal(activity);
        scroll.setFillViewport(true);
        scroll.addView(row(LinearLayout.HORIZONTAL, 10), new android.widget.FrameLayout.LayoutParams(-2, -1));
        layout(scroll, 200, 40);
        assertTrue(scroll.canScrollHorizontally(1));
        Bitmap shown = draw(scroll, 200, 40);
        assertTrue("fade and chevron at the right end", shown.getPixel(197, 2) != 0 || shown.getPixel(190, 20) != 0 || shown.getPixel(186, 20) != 0);
        assertEquals("nothing hinted at the start yet", 0, shown.getPixel(1, 1));
    }

    @Test public void noHintWhenEveryToolIsVisible() {
        ToolStripLayout.Horizontal scroll = new ToolStripLayout.Horizontal(activity);
        scroll.setFillViewport(true);
        scroll.addView(row(LinearLayout.HORIZONTAL, 3), new android.widget.FrameLayout.LayoutParams(-2, -1));
        layout(scroll, 300, 40);
        assertFalse(scroll.canScrollHorizontally(1));
        Bitmap shown = draw(scroll, 300, 40);
        // the tools themselves are empty views, so anything painted would be the hint
        for (int x = 0; x < 300; x++) for (int y = 0; y < 40; y++) {
            assertEquals("no hint painted at " + x + "," + y, 0, shown.getPixel(x, y));
        }
    }

    private static Bitmap draw(View v, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(out));
        return out;
    }
}
