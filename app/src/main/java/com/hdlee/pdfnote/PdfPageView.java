package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.DashPathEffect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;

final class PdfPageView extends View {
    interface Listener {
        void onHighlightCreated(AnnotationStore.Mark mark);
        void onMarkTapped(AnnotationStore.Mark mark);
        void onMemoPointRequested(int page, float x, float y);
        void onZoomGestureStarted();
        void onPageSwipe(int direction);
        void onOutlinePointRequested(int page, float x, float y);
        void onInkChanged();
        void onTextSelectionFinished(TextSelection selection, float anchorX, float anchorY);
        void onTranslationTapped(AnnotationStore.TranslationNote note);
        void onSelectionAdjustStarted();
        void onLassoSelectionFinished();
        void onElementTapped(AnnotationStore.PageElement element);
        default void onZoomChanged(float scale) {}
        default void onElementDeleteRequested(AnnotationStore.PageElement element) {}
        /** A tap on a hyperlink follows it at once; a long press (longPress) asks for its menu (open / edit / delete). */
        default void onHyperlinkTapped(AnnotationStore.PageElement element, boolean longPress) {}
        /** Long press on empty paper: offers to insert something there (page, normalized point, view point). */
        default void onBlankLongPress(int page, float x, float y, float viewX, float viewY) {}
    }

    static final class TextRegion {
        final String word, line; final RectF wordBounds, lineBounds;
        TextRegion(String word,String line,RectF wordBounds,RectF lineBounds){this.word=word;this.line=line;this.wordBounds=wordBounds;this.lineBounds=lineBounds;}
    }

    static final class TextSelection {
        final String text;
        final List<RectF> bounds;
        final RectF unionBounds;
        final boolean singleWord;
        TextSelection(String text,List<RectF> bounds,RectF unionBounds,boolean singleWord){this.text=text;this.bounds=bounds;this.unionBounds=unionBounds;this.singleWord=singleWord;}
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Bitmap bitmap;
    private AnnotationStore annotationStore;
    private boolean edgeSwipe,bodySwipeCandidate,directTextSelection;
    private long edgeStartTime;
    private List<AnnotationStore.Mark> marks;
    private List<AnnotationStore.InkStroke> strokes;
    private List<AnnotationStore.TranslationNote> translations;
    private List<TextRegion> textRegions = new ArrayList<>();
    private final List<TextRegion> selectedTextRegions = new ArrayList<>();
    private final Handler selectionHandler = new Handler(Looper.getMainLooper());
    private final IdentityHashMap<AnnotationStore.TranslationNote,RectF> noteHitBoxes=new IdentityHashMap<>();
    private final IdentityHashMap<AnnotationStore.Mark,RectF> memoHitBoxes=new IdentityHashMap<>();
    private boolean lassoMode, lassoDrawing, suppressSelection;
    private final List<PointF> lassoPoints=new ArrayList<>();
    static final int LASSO_FREE=0,LASSO_RECT=1,LASSO_CIRCLE=2;
    private int lassoShape=LASSO_FREE;
    private PointF lassoAnchor;
    private int searchPage=-1;
    private List<RectF> searchBoxes=new ArrayList<>();
    private RectF searchCurrent;
    private final Paint searchPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lassoPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean textSelectMode;
    private boolean showTextBounds;
    private int page;
    private boolean highlightMode;
    private boolean highlightFree;private float highlightThick=0.022f;private final ArrayList<float[]> freePts=new ArrayList<>();
    private boolean memoMode;
    private int highlightColor = 0x66FFEB3B;
    private float startX, startY, currentX, currentY, lastX, lastY;
    private float panX, panY;
    private boolean drawing, panning, gestureMoved, scalingOccurred;
    private boolean verticalPageSwipe;
    private boolean outlineMode;
    private int inkMode;
    private int inkColor=0xFF1C1C1E;
    private float inkWidth=0.004f;
    private int inkPen;
    private AnnotationStore.InkStroke activeStroke;
    private boolean stylusDrawing;
    private boolean fingerInk,pageSwipeEnabled;
    private float scale = 1f;
    private final RectF crop = new RectF(0, 0, 1, 1), bounds = new RectF(0, 0, 1, 1);
    private int paperColor = 0xFFDDDDDD;
    private PageDrag pageDrag; private boolean dragging; private int dragDirection; private android.view.VelocityTracker dragTracker;
    /** Lets the host follow a finger while it turns the page (Kindle style). */
    interface PageDrag { boolean start(int direction); void move(float distance); void end(float velocity); /** Finger height as a fraction of the page height (0 = top), so the curl can start where the finger is. */ default void touchAt(float yFraction) {} }
    void setPageDrag(PageDrag drag) { pageDrag = drag; }
    /** Converts a point in this view to normalized page coordinates (clamped to the page). */
    private float pageFraction(float y){RectF r=pageRect();return r.height()<=0?.5f:Math.max(0f,Math.min(1f,(y-r.top)/r.height()));}
    private final Runnable blankPress=this::fireBlankPress;
    private void fireBlankPress(){if(scalingOccurred||dragging||selectingText)return;RectF d=contentRect();if(d.width()<=0||!d.contains(startX,startY))return;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);bodySwipeCandidate=false;gestureMoved=true;float[] n=toPage(startX,startY);listener.onBlankLongPress(page,n[0],n[1],startX,startY);}
    float[] toPage(float vx,float vy){RectF d=contentRect();if(d.width()<=0||d.height()<=0)return new float[]{.5f,.5f};return new float[]{Math.max(0f,Math.min(1f,(vx-d.left)/d.width())),Math.max(0f,Math.min(1f,(vy-d.top)/d.height()))};}
    private AnnotationStore.PageElement selectedElement;
    private int elementDrag; private boolean elementMoved; private float elementStartX, elementStartY; private final RectF elementOrigin = new RectF(); private float elementRot0;
    /** Shows move and resize handles around an attached picture, sticker, video or link box. */
    void selectElement(AnnotationStore.PageElement element) { selectedElement = element; invalidate(); }
    AnnotationStore.PageElement selectedElement() { return selectedElement; }
    private static boolean resizable(AnnotationStore.PageElement e) { return e != null && !e.kind.equals("text") && !e.kind.equals("audio"); }
    static boolean selectable(AnnotationStore.PageElement e){return e.kind.equals("image")||e.kind.equals("sticker")||e.kind.equals("video")||e.kind.equals("shape")||e.kind.equals("table")||e.kind.equals("youtube");}
    private static boolean aspectLocked(AnnotationStore.PageElement e) { return e.kind.equals("image") || e.kind.equals("sticker") || e.kind.equals("video") || e.kind.equals("youtube"); }
    private void drawElementHandles(Canvas canvas, RectF dest) {
        if (selectedElement == null || selectedElement.page != page || annotationStore == null || !annotationStore.elements.contains(selectedElement) || dest.width() <= 0) return;
        float density = getResources().getDisplayMetrics().density; RectF b = AnnotationPainter.box(dest, selectedElement);
        int rotSave = canvas.save(); boolean turns = AnnotationPainter.rotates(selectedElement); if (turns && selectedElement.rot != 0f) canvas.rotate(selectedElement.rot, b.centerX(), b.centerY());
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f * density); p.setColor(0xFF007AFF); canvas.drawRect(b, p);
        if (turns) { canvas.drawLine(b.centerX(), b.top, b.centerX(), b.top - 24f * density, p); p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); canvas.drawCircle(b.centerX(), b.top - 28f * density, 10f * density, p); p.setStyle(Paint.Style.STROKE); p.setColor(0xFF007AFF); canvas.drawCircle(b.centerX(), b.top - 28f * density, 10f * density, p); p.setStyle(Paint.Style.FILL); p.setTextSize(13f * density); p.setTextAlign(Paint.Align.CENTER); canvas.drawText("↻", b.centerX(), b.top - 23.5f * density, p); p.setStyle(Paint.Style.STROKE); }
        { float dcx = b.right + 14f * density, dcy = b.top - 28f * density; p.setStyle(Paint.Style.FILL); p.setColor(0xFFFF3B30); canvas.drawCircle(dcx, dcy, 11f * density, p); p.setColor(Color.WHITE); p.setStrokeWidth(2f * density); p.setStrokeCap(Paint.Cap.ROUND); float k = 4f * density; canvas.drawLine(dcx - k, dcy - k, dcx + k, dcy + k, p); canvas.drawLine(dcx - k, dcy + k, dcx + k, dcy - k, p); p.setStrokeCap(Paint.Cap.BUTT); p.setStyle(Paint.Style.STROKE); p.setColor(0xFF007AFF); }
        float[][] corners = {{b.left, b.top}, {b.right, b.top}, {b.left, b.bottom}, {b.right, b.bottom}};
        for (float[] c : corners) { p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); canvas.drawCircle(c[0], c[1], 9f * density, p); p.setStyle(Paint.Style.STROKE); p.setColor(0xFF007AFF); canvas.drawCircle(c[0], c[1], 9f * density, p); }
        if (aspectLocked(selectedElement)) { float[][] mids = {{b.left, b.centerY()}, {b.right, b.centerY()}, {b.centerX(), b.top}, {b.centerX(), b.bottom}}; for (int i = 0; i < 4; i++) { float hw = (i < 2 ? 4f : 9f) * density, hh = (i < 2 ? 9f : 4f) * density; RectF r = new RectF(mids[i][0] - hw, mids[i][1] - hh, mids[i][0] + hw, mids[i][1] + hh); p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); canvas.drawRoundRect(r, 4f * density, 4f * density, p); p.setStyle(Paint.Style.STROKE); p.setColor(0xFF007AFF); canvas.drawRoundRect(r, 4f * density, 4f * density, p); } }
        canvas.restoreToCount(rotSave);
    }
    private boolean handleElementGesture(MotionEvent e, RectF dest) {
        if (selectedElement == null || annotationStore == null || dest.width() <= 0) return false;
        if (selectedElement.page != page || !annotationStore.elements.contains(selectedElement)) { selectedElement = null; return false; }
        int action = e.getActionMasked(); float density = getResources().getDisplayMetrics().density;
        if (action == MotionEvent.ACTION_DOWN && e.getPointerCount() == 1 && !isStylus(e)) {
            RectF b = AnnotationPainter.box(dest, selectedElement); float reach = 24f * density; int hit = 0;
            float tx = e.getX(), ty = e.getY(); boolean turns = AnnotationPainter.rotates(selectedElement);
            if (turns && selectedElement.rot != 0f) { double a = Math.toRadians(-selectedElement.rot); float dx0 = tx - b.centerX(), dy0 = ty - b.centerY(); tx = b.centerX() + (float) (dx0 * Math.cos(a) - dy0 * Math.sin(a)); ty = b.centerY() + (float) (dx0 * Math.sin(a) + dy0 * Math.cos(a)); }
            if (Math.hypot(tx - (b.right + 14f * density), ty - (b.top - 28f * density)) <= 16f * density) hit = 7;
            else if (turns && Math.hypot(tx - b.centerX(), ty - (b.top - 28f * density)) <= reach) hit = 6;
            else if (Math.hypot(tx - b.left, ty - b.top) <= reach) hit = 2; else if (Math.hypot(tx - b.right, ty - b.top) <= reach) hit = 3;
            else if (Math.hypot(tx - b.left, ty - b.bottom) <= reach) hit = 4; else if (Math.hypot(tx - b.right, ty - b.bottom) <= reach) hit = 5;
            else if (aspectLocked(selectedElement) && Math.hypot(tx - b.left, ty - b.centerY()) <= 18f * density) hit = 8;
            else if (aspectLocked(selectedElement) && Math.hypot(tx - b.right, ty - b.centerY()) <= 18f * density) hit = 9;
            else if (aspectLocked(selectedElement) && Math.hypot(tx - b.centerX(), ty - b.top) <= 18f * density) hit = 10;
            else if (aspectLocked(selectedElement) && Math.hypot(tx - b.centerX(), ty - b.bottom) <= 18f * density) hit = 11;
            else if (b.contains(tx, ty)) hit = 1;
            if (hit == 0) { selectedElement = null; invalidate(); return false; }
            if (hit > 1 && hit < 6 && !resizable(selectedElement)) hit = 1;
            if (hit >= 8 && hit <= 11 && !resizable(selectedElement)) hit = 1;
            if (hit == 7) { elementDrag = 7; elementMoved = false; getParent().requestDisallowInterceptTouchEvent(true); return true; }
            listener.onSelectionAdjustStarted(); elementDrag = hit; elementMoved = false; elementStartX = e.getX(); elementStartY = e.getY();
            elementOrigin.set(selectedElement.left, selectedElement.top, selectedElement.right, selectedElement.bottom); elementRot0 = selectedElement.rot;
            getParent().requestDisallowInterceptTouchEvent(true); return true;
        }
        if (elementDrag == 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            if (elementDrag == 7) return true;
            float dx = (e.getX() - elementStartX) / dest.width(), dy = (e.getY() - elementStartY) / dest.height();
            if (!elementMoved && Math.hypot(e.getX() - elementStartX, e.getY() - elementStartY) < 8f * density) return true;
            elementMoved = true; AnnotationStore.PageElement el = selectedElement; float w = elementOrigin.width(), h = elementOrigin.height();
            if (elementDrag == 6) {
                float cx = dest.left + (elementOrigin.left + elementOrigin.right) / 2f * dest.width(), cy = dest.top + (elementOrigin.top + elementOrigin.bottom) / 2f * dest.height();
                float deg = (float) Math.toDegrees(Math.atan2(e.getX() - cx, -(e.getY() - cy))); if (deg < 0f) deg += 360f;
                float snap = Math.round(deg / 15f) * 15f; if (Math.abs(snap - deg) < 4f) deg = snap % 360f;
                el.rot = deg; invalidate(); return true;
            }
            if (elementDrag >= 8 && elementDrag <= 11) {
                float px = e.getX(), py = e.getY();
                if (el.rot != 0f && AnnotationPainter.rotates(el)) { float ocx = dest.left + (elementOrigin.left + elementOrigin.right) / 2f * dest.width(), ocy = dest.top + (elementOrigin.top + elementOrigin.bottom) / 2f * dest.height(); double a = Math.toRadians(-el.rot); float ddx = px - ocx, ddy = py - ocy; px = ocx + (float) (ddx * Math.cos(a) - ddy * Math.sin(a)); py = ocy + (float) (ddx * Math.sin(a) + ddy * Math.cos(a)); }
                float nx = Math.max(0f, Math.min(1f, (px - dest.left) / dest.width())), ny = Math.max(0f, Math.min(1f, (py - dest.top) / dest.height()));
                el.left = elementOrigin.left; el.right = elementOrigin.right; el.top = elementOrigin.top; el.bottom = elementOrigin.bottom; el.stretch = true;
                if (elementDrag == 8) el.left = Math.min(nx, elementOrigin.right - .04f); else if (elementDrag == 9) el.right = Math.max(nx, elementOrigin.left + .04f);
                else if (elementDrag == 10) el.top = Math.min(ny, elementOrigin.bottom - .03f); else el.bottom = Math.max(ny, elementOrigin.top + .03f);
                invalidate(); return true;
            }
            if (elementDrag == 1) {
                float left = Math.max(0f, Math.min(1f - w, elementOrigin.left + dx)), top = Math.max(0f, Math.min(1f - h, elementOrigin.top + dy));
                el.left = left; el.top = top; el.right = left + w; el.bottom = top + h;
            } else {
                boolean leftCorner = elementDrag == 2 || elementDrag == 4, topCorner = elementDrag == 2 || elementDrag == 3;
                float fx = leftCorner ? elementOrigin.right : elementOrigin.left, fy = topCorner ? elementOrigin.bottom : elementOrigin.top;
                float px = e.getX(), py = e.getY();
                if (el.rot != 0f && AnnotationPainter.rotates(el)) { float ocx = dest.left + (elementOrigin.left + elementOrigin.right) / 2f * dest.width(), ocy = dest.top + (elementOrigin.top + elementOrigin.bottom) / 2f * dest.height(); double a = Math.toRadians(-el.rot); float ddx = px - ocx, ddy = py - ocy; px = ocx + (float) (ddx * Math.cos(a) - ddy * Math.sin(a)); py = ocy + (float) (ddx * Math.sin(a) + ddy * Math.cos(a)); }
                float nx = Math.max(0f, Math.min(1f, (px - dest.left) / dest.width())), ny = Math.max(0f, Math.min(1f, (py - dest.top) / dest.height()));
                float nw = Math.max(.04f, leftCorner ? fx - nx : nx - fx), nh = Math.max(.03f, topCorner ? fy - ny : ny - fy);
                if (aspectLocked(el)) {
                    float ratio = (w * dest.width()) / (h * dest.height()); nh = nw * dest.width() / (ratio * dest.height());
                    float room = topCorner ? fy : 1f - fy; if (nh > room) { nh = room; nw = nh * ratio * dest.height() / dest.width(); }
                    float roomX = leftCorner ? fx : 1f - fx; if (nw > roomX) { nw = roomX; nh = nw * dest.width() / (ratio * dest.height()); }
                }
                el.left = leftCorner ? fx - nw : fx; el.right = leftCorner ? fx : fx + nw; el.top = topCorner ? fy - nh : fy; el.bottom = topCorner ? fy : fy + nh;
            }
            invalidate(); return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean moved = elementMoved; boolean deleting = elementDrag == 7; elementDrag = 0; elementMoved = false; getParent().requestDisallowInterceptTouchEvent(false);
            if (deleting) { if (action == MotionEvent.ACTION_UP) { AnnotationStore.PageElement gone = selectedElement; selectedElement = null; listener.onElementDeleteRequested(gone); } invalidate(); return true; }
            if (action == MotionEvent.ACTION_CANCEL) { selectedElement.rot = elementRot0; selectedElement.left = elementOrigin.left; selectedElement.top = elementOrigin.top; selectedElement.right = elementOrigin.right; selectedElement.bottom = elementOrigin.bottom; invalidate(); }
            else if (moved) listener.onInkChanged(); else listener.onElementTapped(selectedElement);
            return true;
        }
        return true;
    }
    private final Listener listener;
    private TextRegion selectionStartRegion, selectionEndRegion;
    private boolean selectionCandidate, selectingText;
    private final Runnable beginTextSelection = () -> {
        if (!selectionCandidate || selectionStartRegion == null) return;
        bodySwipeCandidate=false;
        selectingText = true;
        selectionCandidate = false;
        panning = false;
        selectedTextRegions.clear();
        selectedTextRegions.add(selectionStartRegion);
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    };

    PdfPageView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        applyBackground();
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                clearLassoSelection();
                selectionHandler.removeCallbacks(beginTextSelection);
                selectingText=selectionCandidate=false;
                selectedTextRegions.clear();
                listener.onSelectionAdjustStarted();
                finishInkStroke();
                drawing = false;
                panning = false;
                scalingOccurred = true;
                listener.onZoomGestureStarted();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                RectF before = contentRect();
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float nx = before.width() == 0 ? 0.5f : (focusX - before.left) / before.width();
                float ny = before.height() == 0 ? 0.5f : (focusY - before.top) / before.height();
                float next = Math.max(MIN_ZOOM, Math.min(4f, scale * detector.getScaleFactor()));
                if (spreadSide != 0) zoomSpread(focusX, focusY, next);
                else {
                scale = next;
                float[] size = contentSize();
                panX = focusX - nx * size[0] - baseLeft(size);
                panY = focusY - ny * size[1] - baseTop(size);
                }
                clampPan();
                invalidate();
                listener.onZoomChanged(scale);
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                clampPan();
            }
        });
    }

    void showPage(Bitmap pageBitmap, int pageNumber, List<AnnotationStore.Mark> allMarks, List<AnnotationStore.InkStroke> allStrokes, List<AnnotationStore.TranslationNote> allTranslations) {
        clearLassoSelection();
        stopTextSelection();
        noteHitBoxes.clear(); memoHitBoxes.clear(); selectedElement = null; selSticky = null; stDrag = 0; elementDrag = 0;
        if (bitmap != null && bitmap != pageBitmap) bitmap.recycle();
        bitmap = pageBitmap;
        page = pageNumber;
        marks = allMarks;
        strokes = allStrokes;
        translations=allTranslations; textRegions.clear(); selectedTextRegions.clear(); textSelectMode=false;
        scale = 1f;
        panX = panY = 0f;
        listener.onZoomChanged(1f);
        analyze();
        invalidate();
    }

    void clearPage() {
        clearLassoSelection();
        stopTextSelection();
        noteHitBoxes.clear(); memoHitBoxes.clear();
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
        bounds.set(0, 0, 1, 1); crop.set(0, 0, 1, 1); paperColor = 0xFFDDDDDD; applyBackground();
        marks = null;
        strokes = null;
        translations=null; textRegions.clear(); selectedTextRegions.clear(); textSelectMode=false;
        scale = 1f;
        panX = panY = 0f;
        listener.onZoomChanged(1f);
        invalidate();
    }

    /** Highlighter style: straight band or freehand stroke, and its thickness as a fraction of the page height. */
    void setHighlightStyle(boolean free, float thickFraction) { highlightFree = free; highlightThick = Math.max(.006f, Math.min(.08f, thickFraction)); invalidate(); }
    void setHighlightMode(boolean enabled, int color) {
        highlightMode = enabled;
        if (enabled) { setLassoMode(false);memoMode = false; outlineMode = false; }
        highlightColor = color;
        invalidate();
    }

    void setMemoMode(boolean enabled) {
        memoMode = enabled;
        if (enabled) { setLassoMode(false);highlightMode = false; outlineMode = false; }
        invalidate();
    }

    void setOutlineMode(boolean enabled) {
        outlineMode = enabled;
        if (enabled) { setLassoMode(false);highlightMode = false; memoMode = false; }
        invalidate();
    }

    void setInkPen(int pen) { inkPen = Math.max(0, Math.min(6, pen)); }
    void setInkTool(int mode, int color, float width) {
        inkMode=mode; inkColor=color; inkWidth=width;
        if(mode!=0){setLassoMode(false);highlightMode=memoMode=outlineMode=false;}
        invalidate();
    }

    private long lastWheelTurn;
    /** Mouse wheel: scrolls inside a zoomed page, otherwise turns the page (up = previous, down = next). Ctrl + wheel zooms. */
    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_SCROLL && (e.getSource() & android.view.InputDevice.SOURCE_CLASS_POINTER) != 0 && bitmap != null) {
            float v = e.getAxisValue(MotionEvent.AXIS_VSCROLL), h = e.getAxisValue(MotionEvent.AXIS_HSCROLL);
            if (((e.getMetaState() & android.view.KeyEvent.META_CTRL_ON) != 0) && v != 0f) { setZoom(scale * (v > 0 ? 1.15f : 1f / 1.15f)); return true; }
            if (scale > 1f) { panY += v * 56f * getResources().getDisplayMetrics().density; panX += h * 56f * getResources().getDisplayMetrics().density; clampPan(); invalidate(); return true; }
            float d = Math.abs(v) >= Math.abs(h) ? v : h; long now = e.getEventTime();
            if (d != 0f && now - lastWheelTurn > 380) { lastWheelTurn = now; listener.onPageSwipe(d < 0 ? 1 : -1); }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }
    /** Smallest zoom: the page may be shrunk to 40% and is then centred on the grey background. */
    static final float MIN_ZOOM = 0.4f;
    float zoom() { return scale; }
    float panOffsetX() { return panX; }
    float panOffsetY() { return panY; }
    /** Re-applies a zoom and position carried over from the previous page (clamped to this page). */
    void restoreView(float value, float px, float py) { scale = Math.max(MIN_ZOOM, Math.min(4f, value)); panX = px; panY = py; clampPan(); invalidate(); listener.onZoomChanged(scale); }
    /** Sets the zoom (1 = whole page, up to 4) around the centre of the view; the pan is kept inside the page. */
    void setZoom(float value) {
        if (bitmap == null || getWidth() == 0) return;
        RectF before = contentRect(); float fx = getWidth() / 2f, fy = getHeight() / 2f;
        float nx = before.width() == 0 ? 0.5f : (fx - before.left) / before.width(), ny = before.height() == 0 ? 0.5f : (fy - before.top) / before.height();
        if (spreadSide != 0) { View parent = (View) getParent(); zoomSpread(parent.getWidth() / 2f - getLeft(), parent.getHeight() / 2f - getTop(), Math.max(MIN_ZOOM, Math.min(4f, value))); clampPan(); invalidate(); listener.onZoomChanged(scale); return; }
        scale = Math.max(MIN_ZOOM, Math.min(4f, value));
        float[] size = contentSize(); panX = fx - nx * size[0] - baseLeft(size); panY = fy - ny * size[1] - baseTop(size);
        clampPan(); invalidate(); listener.onZoomChanged(scale);
    }
    void focusOnPoint(float x, float y) {
        if (bitmap == null) return;
        scale = Math.max(scale, 1.7f);
        if (spreadSide != 0) { RectF b = spreadBase(); panX = getWidth() / 2f + getLeft() - scale * (b.left + x * b.width()); panY = getHeight() / 2f + getTop() - scale * (b.top + y * b.height()); }
        else {
        float[] size = contentSize();
        panX = getWidth() / 2f - (baseLeft(size) + x * size[0]);
        panY = getHeight() / 2f - (baseTop(size) + y * size[1]);
        }
        clampPan();
        invalidate();
        listener.onZoomChanged(scale);
    }

    void setDirectTextSelection(boolean enabled){directTextSelection=enabled;clearTextSelectionOverlay();}
    void copyToolsFrom(PdfPageView other){darkPage=other.darkPage;applyBackground();directTextSelection=other.directTextSelection;highlightMode=other.highlightMode;memoMode=other.memoMode;outlineMode=other.outlineMode;highlightColor=other.highlightColor;highlightFree=other.highlightFree;highlightThick=other.highlightThick;inkMode=other.inkMode;eraserPartial=other.eraserPartial;inkColor=other.inkColor;inkWidth=other.inkWidth;eraserRadius=other.eraserRadius;inkPen=other.inkPen;fingerInk=other.fingerInk;pageSwipeEnabled=other.pageSwipeEnabled;verticalPageSwipe=other.verticalPageSwipe;lassoShape=other.lassoShape;if(lassoMode!=other.lassoMode){lassoMode=other.lassoMode;clearLassoSelection();}invalidate();}
    void setAnnotationStore(AnnotationStore store){annotationStore=store;invalidate();}
    void setFingerInk(boolean enabled){fingerInk=enabled;}
    void setPageSwipeEnabled(boolean enabled){pageSwipeEnabled=enabled;}

    void setVerticalPageSwipe(boolean vertical) {
        verticalPageSwipe = vertical;
    }

    Bitmap copyPageBitmap(){return bitmap==null?null:bitmap.copy(Bitmap.Config.ARGB_8888,false);}
    int getPageNumber(){return page;}
    void setTextRegions(List<TextRegion> regions,boolean showBounds){
        selectionHandler.removeCallbacks(beginTextSelection);
        clearTextSelectionOverlay();
        textRegions=regions==null?new ArrayList<>():new ArrayList<>(regions);textSelectMode=true;showTextBounds=showBounds;
        invalidate();
    }
    void stopTextSelection(){directTextSelection=false;textSelectMode=false;textRegions.clear();clearTextSelectionOverlay();}
    void clearTextSelectionOverlay(){
        selectionHandler.removeCallbacks(beginTextSelection);
        selectionCandidate=selectingText=false;
        selectedTextRegions.clear();selectionStartRegion=selectionEndRegion=null;
        drawing=panning=gestureMoved=false;bodySwipeCandidate=false;
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();
    }

    @Override protected void onDetachedFromWindow(){
        clearLassoSelection();
        clearTextSelectionOverlay();
        super.onDetachedFromWindow();
    }

    private float highlightHeight(RectF dest) {
        return Math.max(6f, dest.height() * highlightThick);
    }

    /** Finds the paper colour and the box that holds the printed content, so margins can be trimmed. */
    private void analyze() {
        bounds.set(0, 0, 1, 1); crop.set(0, 0, 1, 1); paperColor = 0xFFFFFFFF;
        try {
            int w = Math.min(200, bitmap.getWidth()), h = Math.min(Math.max(1, Math.round(bitmap.getHeight() * (w / (float) bitmap.getWidth()))), bitmap.getHeight()); int[] px = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = bitmap.getPixel(Math.min(bitmap.getWidth() - 1, x * bitmap.getWidth() / w), Math.min(bitmap.getHeight() - 1, y * bitmap.getHeight() / h));
            java.util.HashMap<Integer, Integer> votes = new java.util.HashMap<>(); int best = 0, bestVotes = 0;
            for (int i = 0; i < w * h; i++) { int x = i % w, y = i / w; if (x > 2 && x < w - 3 && y > 2 && y < h - 3) continue; int key = (px[i] & 0xFFF0F0F0); int n = votes.merge(key, 1, Integer::sum); if (n > bestVotes) { bestVotes = n; best = px[i]; } }
            paperColor = best | 0xFF000000; int minX = w, minY = h, maxX = -1, maxY = -1;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int c = px[y * w + x]; int diff = Math.abs(Color.red(c) - Color.red(paperColor)) + Math.abs(Color.green(c) - Color.green(paperColor)) + Math.abs(Color.blue(c) - Color.blue(paperColor)); if (diff > 60) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y; } }
            if (maxX >= minX && maxY >= minY) {
                float pad = 0.03f; bounds.set(Math.max(0f, minX / (float) w - pad), Math.max(0f, minY / (float) h - pad), Math.min(1f, (maxX + 1) / (float) w + pad), Math.min(1f, (maxY + 1) / (float) h + pad));
                if (bounds.width() * bounds.height() < 0.1f) bounds.set(0, 0, 1, 1);
            }
        } catch (RuntimeException ignored) { bounds.set(0, 0, 1, 1); }
        applyBackground();
    }
    /** Normalised box around the printed content (the whole page when it is nearly blank). */
    RectF contentBounds() { return new RectF(bounds); }
    int paperColor() { return paperColor; }
    private boolean darkPage;
    private static final android.graphics.ColorMatrixColorFilter DARK_FILTER;
    static {
        android.graphics.ColorMatrix invert = new android.graphics.ColorMatrix(new float[]{-1,0,0,0,255, 0,-1,0,0,255, 0,0,-1,0,255, 0,0,0,1,0});
        android.graphics.ColorMatrix hue = new android.graphics.ColorMatrix(new float[]{-0.574f,1.430f,0.144f,0,0, 0.426f,0.430f,0.144f,0,0, 0.426f,1.430f,-0.856f,0,0, 0,0,0,1,0});
        invert.postConcat(hue);
        DARK_FILTER = new android.graphics.ColorMatrixColorFilter(invert);
    }
    /** Black paper with light text: the page picture is inverted (keeping hues) and dark ink is lightened. */
    void setDarkPage(boolean enabled) { darkPage = enabled; applyBackground(); invalidate(); }
    boolean isDarkPage() { return darkPage; }
    /** The area around the page is a neutral grey so the sheet itself stands out from the background. */
    private void applyBackground() { int color = darkPage ? 0xFF121214 : 0xFFD9DADF; if (spreadSide != 0 && getParent() instanceof View) { setBackgroundColor(Color.TRANSPARENT); ((View) getParent()).setBackgroundColor(color); } else setBackgroundColor(color); }
    /** Zooms the base view onto this normalised box so margins disappear; null shows the whole page. */
    void setCrop(RectF box) {
        if (box == null || box.width() < 0.2f || box.height() < 0.2f) crop.set(0, 0, 1, 1); else crop.set(box);
        panX = panY = 0f; invalidate();
    }

    private float[] contentSize() {
        if (bitmap == null) return new float[]{0f, 0f};
        float base = Math.min(getWidth() / (bitmap.getWidth() * crop.width()), getHeight() / (bitmap.getHeight() * crop.height()));
        float w = bitmap.getWidth() * base * scale;
        float h = bitmap.getHeight() * base * scale;
        return new float[]{w, h};
    }
    private float baseLeft(float[] size) { return (getWidth() - size[0]) / 2f - (crop.centerX() - 0.5f) * size[0]; }
    private float baseTop(float[] size) { return (getHeight() - size[1]) / 2f - (crop.centerY() - 0.5f) * size[1]; }

    // ---- two-page spread: both pages touch at the seam and zoom / pan together as one sheet.
    private int spreadSide; private PdfPageView partner;
    /** side -1 = left page (aligned to its right edge), 1 = right page (aligned to its left edge), 0 = single page centred. In a spread
     * scale/panX/panY are one shared transform of the whole spread (in the parent's coordinates), mirrored to the partner page. */
    @Override public void invalidate() { super.invalidate(); if (spreadSide != 0 && getParent() instanceof View) ((View) getParent()).invalidate(); }   // a zoomed spread page may draw past its own half
    /** Back to a plain, unzoomed single page view (used when a split screen ends and the pane is reused for a spread). */
    void resetPaneState() { spreadSide = 0; partner = null; scale = 1f; panX = panY = 0f; applyBackground(); invalidate(); }
    void setSpread(int side, PdfPageView other) { spreadSide = other == null ? 0 : side; partner = other; applyBackground(); invalidate(); }
    private AnnotationStore.PageElement linkHit; private boolean linkLongFired;
    private final Runnable linkLongPress = () -> { if (linkHit != null) { linkLongFired = true; AnnotationStore.PageElement hit = linkHit; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); PdfPageView.this.listener.onHyperlinkTapped(hit, true); } };
    private boolean linkTouchAllowed() { return !highlightMode && !memoMode && !outlineMode && !lassoMode && inkMode == 0 && !directTextSelection; }
    /** The hyperlink under a touch point (the touch is padded a little so a thin line of text is easy to hit). */
    private AnnotationStore.PageElement hyperlinkAt(float x, float y, RectF dest) {
        if (annotationStore == null || dest.width() <= 0 || dest.height() <= 0) return null;
        float pad = 6f * getResources().getDisplayMetrics().density, nx = (x - dest.left) / dest.width(), ny = (y - dest.top) / dest.height(), px = pad / dest.width(), py = pad / dest.height();
        for (int i = annotationStore.elements.size() - 1; i >= 0; i--) {
            AnnotationStore.PageElement e = annotationStore.elements.get(i);
            if (e.page == page && "hyperlink".equals(e.kind) && nx >= e.left - px && nx <= e.right + px && ny >= e.top - py && ny <= e.bottom + py) return e;
        }
        return null;
    }
    /** In a spread a page turn may only start on the document (either page), not on the grey surround; a single page is unrestricted. */
    private boolean onSpreadDocument(float x, float y) {
        if (spreadSide == 0 || partner == null || partner.getVisibility() != VISIBLE) return true;
        if (contentRect().contains(x, y)) return true;
        RectF o = partner.contentRect(); o.offset(partner.getLeft() - getLeft(), partner.getTop() - getTop()); return o.contains(x, y);
    }
    /** This page at zoom 1 in the parent's (spread) coordinates. */
    private RectF spreadBase() {
        if (bitmap == null || getWidth() == 0) return new RectF();
        float base = Math.min(getWidth() / (bitmap.getWidth() * crop.width()), getHeight() / (bitmap.getHeight() * crop.height()));
        float w = bitmap.getWidth() * base, h = bitmap.getHeight() * base;
        float left = spreadSide < 0 ? getWidth() - crop.right * w : -crop.left * w, top = (getHeight() - h) / 2f - (crop.centerY() - 0.5f) * h;
        return new RectF(left + getLeft(), top + getTop(), left + getLeft() + w, top + getTop() + h);
    }
    /** The cropped (visible) part of {@link #spreadBase()}. */
    private RectF visibleBase() { RectF b = spreadBase(); return new RectF(b.left + crop.left * b.width(), b.top + crop.top * b.height(), b.left + crop.right * b.width(), b.top + crop.bottom * b.height()); }
    private void zoomSpread(float focusX, float focusY, float next) { float fx = focusX + getLeft(), fy = focusY + getTop(), k = next / scale; panX = fx - k * (fx - panX); panY = fy - k * (fy - panY); scale = next; }
    private void clampSpread() {
        View parent = (View) getParent(); float pw = parent.getWidth(), ph = parent.getHeight();
        RectF u = visibleBase(); if (partner != null && partner.bitmap != null && partner.getVisibility() == VISIBLE) u.union(partner.visibleBase());
        if (scale <= 1f) { panX = (1f - scale) * pw / 2f; panY = (1f - scale) * ph / 2f; }
        else {
            panX = scale * u.width() <= pw ? (1f - scale) * pw / 2f : Math.max(pw - scale * u.right, Math.min(-scale * u.left, panX));
            panY = scale * u.height() <= ph ? (1f - scale) * ph / 2f : Math.max(ph - scale * u.bottom, Math.min(-scale * u.top, panY));
        }
        if (partner != null) { partner.scale = scale; partner.panX = panX; partner.panY = panY; partner.invalidate(); }
    }

    private void clampPan() {
        if (bitmap == null) return;
        if (spreadSide != 0 && getParent() instanceof View) { clampSpread(); return; }
        float[] size = contentSize();
        float maxX = Math.max(0f, (size[0] * crop.width() - getWidth()) / 2f);
        float maxY = Math.max(0f, (size[1] * crop.height() - getHeight()) / 2f);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        if (scale <= 1f) panX = panY = 0f;
    }

    /** Where the page is drawn inside this view (follows zoom and pan), in view pixels. */
    RectF pageRect() { return contentRect(); }

    private RectF contentRect() {
        if (bitmap == null) return new RectF();
        if (spreadSide != 0) { RectF b = spreadBase(); return new RectF(b.left * scale + panX - getLeft(), b.top * scale + panY - getTop(), b.right * scale + panX - getLeft(), b.bottom * scale + panY - getTop()); }
        float[] size = contentSize();
        float left = baseLeft(size) + panX;
        float top = baseTop(size) + panY;
        return new RectF(left, top, left + size[0], top + size[1]);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        RectF dest = contentRect();
        { float d = getResources().getDisplayMetrics().density; paint.setStyle(Paint.Style.FILL); for (int i = 3; i >= 1; i--) { paint.setColor((darkPage ? 0x22000000 : 0x14000000) | 0); canvas.drawRect(dest.left - (spreadSide > 0 ? 0 : i * d), dest.top - i * d * .6f, dest.right + (spreadSide < 0 ? 0 : i * d), dest.bottom + i * d * 1.4f, paint); } }
        paint.setColor(Color.WHITE);
        if (darkPage) paint.setColor(Color.BLACK);
        canvas.drawRect(dest, paint);
        if (darkPage) paint.setColorFilter(DARK_FILTER);
        canvas.drawBitmap(bitmap, null, dest, paint);
        paint.setColorFilter(null);
        AnnotationPainter.dark = darkPage;
        if(!suppressSelection&&textSelectMode&&showTextBounds){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*getResources().getDisplayMetrics().density);paint.setColor(0xAA2563EB);for(TextRegion r:textRegions){RectF b=r.wordBounds;canvas.drawRoundRect(new RectF(dest.left+b.left*dest.width(),dest.top+b.top*dest.height(),dest.left+b.right*dest.width(),dest.top+b.bottom*dest.height()),4,4,paint);}paint.setStyle(Paint.Style.FILL);}
        if(!suppressSelection&&!selectedTextRegions.isEmpty()){
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x663B82F6);
            for(RectF b:selectionLineBounds()){canvas.drawRoundRect(new RectF(dest.left+b.left*dest.width(),dest.top+b.top*dest.height(),dest.left+b.right*dest.width(),dest.top+b.bottom*dest.height()),5,5,paint);}
            RectF first=selectedTextRegions.get(0).wordBounds,last=selectedTextRegions.get(selectedTextRegions.size()-1).wordBounds;float handle=5f*getResources().getDisplayMetrics().density;paint.setColor(0xFF007AFF);canvas.drawCircle(dest.left+first.left*dest.width(),dest.top+first.bottom*dest.height(),handle,paint);canvas.drawCircle(dest.left+last.right*dest.width(),dest.top+last.bottom*dest.height(),handle,paint);
        }
        if (marks != null) for (AnnotationStore.Mark m : marks) if (m.page == page) {
            if (!m.noteOnly) {
                paint.setColor(m.color);
                canvas.drawRect(dest.left + m.left * dest.width(), dest.top + m.top * dest.height(),
                        dest.left + m.right * dest.width(), dest.top + m.bottom * dest.height(), paint);
            }
        }
        if(strokes!=null){for(AnnotationStore.InkStroke s:strokes)if(s.page==page)AnnotationPainter.stroke(canvas,dest,s);paint.setStyle(Paint.Style.FILL);}
        memoHitBoxes.clear();if(marks!=null)for(AnnotationStore.Mark m:marks)if(m.page==page&&m.visible&&(m.noteOnly||(m.note!=null&&!m.note.isEmpty())))drawMemo(canvas,dest,m);
        noteHitBoxes.clear();if(translations!=null)for(AnnotationStore.TranslationNote n:translations)if(n.page==page&&n.visible)drawTranslation(canvas,dest,n);
        drawMemoSelection(canvas);
        if (!suppressSelection && drawing && highlightFree && freePts.size() > 1) {
            android.graphics.Path line = new android.graphics.Path(); boolean firstPt = true;
            for (float[] q : freePts) { if (firstPt) { line.moveTo(q[0], q[1]); firstPt = false; } else line.lineTo(q[0], q[1]); }
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG); hp.setColor(highlightColor | 0xFF000000); hp.setStyle(Paint.Style.STROKE); hp.setStrokeCap(Paint.Cap.ROUND); hp.setStrokeJoin(Paint.Join.ROUND); hp.setStrokeWidth(highlightHeight(dest));
            int saved = canvas.saveLayerAlpha(dest.left, dest.top, dest.right, dest.bottom, Math.max(8, Color.alpha(highlightColor))); canvas.drawPath(line, hp); canvas.restoreToCount(saved);
        } else if (!suppressSelection && drawing && !highlightFree) {
            paint.setColor(highlightColor);
            float centerY = (startY + currentY) / 2f;
            float half = highlightHeight(dest) / 2f;
            canvas.drawRect(Math.min(startX, currentX), centerY - half,
                    Math.max(startX, currentX), centerY + half, paint);
        }
        AnnotationPainter.elements(getContext(),canvas,dest,annotationStore,page);
        AnnotationPainter.dark = false;
        if(eraserX>=0&&stylusDrawing){float d=getResources().getDisplayMetrics().density;paint.setStyle(Paint.Style.FILL);paint.setColor(0x22FF6B8A);canvas.drawCircle(eraserX,eraserY,eraserRadius*d,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);paint.setColor(0xCCFF6B8A);canvas.drawCircle(eraserX,eraserY,eraserRadius*d,paint);paint.setStyle(Paint.Style.FILL);}
        if(!suppressSelection)drawElementHandles(canvas,dest);
        if(!suppressSelection&&searchPage==page)drawSearchHighlights(canvas,dest);
        if(!suppressSelection&&!lassoPoints.isEmpty()){
            Path path=lassoPath(dest);lassoPaint.setStyle(Paint.Style.FILL);lassoPaint.setColor(0x222563EB);canvas.drawPath(path,lassoPaint);
            lassoPaint.setStyle(Paint.Style.STROKE);lassoPaint.setStrokeWidth(2*getResources().getDisplayMetrics().density);lassoPaint.setColor(0xFF007AFF);
            lassoPaint.setPathEffect(new DashPathEffect(new float[]{8,5},0));canvas.drawPath(path,lassoPaint);lassoPaint.setPathEffect(null);
        }
    }

    void setLassoMode(boolean enabled){
        lassoMode=enabled;clearLassoSelection();clearTextSelectionOverlay();
        if(enabled){finishInkStroke();inkMode=0;highlightMode=memoMode=outlineMode=false;}
    }
    boolean isLassoMode(){return lassoMode;}
    int getLassoShape(){return lassoShape;}
    /** Switches between freehand, rectangle and circle selection; any selection in progress is discarded. */
    void setLassoShape(int shape){lassoShape=Math.max(LASSO_FREE,Math.min(LASSO_CIRCLE,shape));clearLassoSelection();}
    private PointF normalizedPoint(float x,float y,RectF dest){return new PointF(Math.max(0,Math.min(1,(x-dest.left)/dest.width())),Math.max(0,Math.min(1,(y-dest.top)/dest.height())));}
    /** Rebuilds the rectangle / ellipse polygon spanned by the touch-down point and the current finger position. */
    private void updateLassoShape(float x,float y,RectF dest){
        if(lassoAnchor==null||dest.width()<=0||dest.height()<=0)return;
        PointF end=normalizedPoint(x,y,dest);lassoPoints.clear();
        float left=Math.min(lassoAnchor.x,end.x),right=Math.max(lassoAnchor.x,end.x),top=Math.min(lassoAnchor.y,end.y),bottom=Math.max(lassoAnchor.y,end.y);
        if(lassoShape==LASSO_RECT){
            lassoPoints.add(new PointF(left,top));lassoPoints.add(new PointF(right,top));lassoPoints.add(new PointF(right,bottom));lassoPoints.add(new PointF(left,bottom));
        }else{
            int steps=72;float cx=(left+right)/2,cy=(top+bottom)/2,rx=(right-left)/2,ry=(bottom-top)/2;
            for(int i=0;i<steps;i++){double angle=2*Math.PI*i/steps;lassoPoints.add(new PointF((float)(cx+rx*Math.cos(angle)),(float)(cy+ry*Math.sin(angle))));}
        }
    }
    void setSearchHighlights(int pageNumber,List<RectF> others,RectF current){searchPage=pageNumber;searchBoxes=others==null?new ArrayList<>():new ArrayList<>(others);searchCurrent=current==null?null:new RectF(current);invalidate();}
    void clearSearchHighlights(){searchPage=-1;searchBoxes=new ArrayList<>();searchCurrent=null;invalidate();}
    private RectF searchRect(RectF b,RectF dest,float pad){return new RectF(dest.left+b.left*dest.width()-pad,dest.top+b.top*dest.height()-pad,dest.left+b.right*dest.width()+pad,dest.top+b.bottom*dest.height()+pad);}
    private void drawSearchHighlights(Canvas canvas,RectF dest){
        float density=getResources().getDisplayMetrics().density;
        searchPaint.setStyle(Paint.Style.FILL);searchPaint.setColor(0x66FFD54F);
        for(RectF b:searchBoxes)canvas.drawRoundRect(searchRect(b,dest,density),3*density,3*density,searchPaint);
        if(searchCurrent!=null){
            RectF b=searchRect(searchCurrent,dest,2*density);
            searchPaint.setStyle(Paint.Style.FILL);searchPaint.setColor(0x99FF9800);canvas.drawRoundRect(b,4*density,4*density,searchPaint);
            searchPaint.setStyle(Paint.Style.STROKE);searchPaint.setStrokeWidth(2*density);searchPaint.setColor(0xFFEA580C);canvas.drawRoundRect(b,4*density,4*density,searchPaint);
            searchPaint.setStyle(Paint.Style.FILL);
        }
    }
    float pageAspect(){return bitmap==null||bitmap.getWidth()==0?1.414f:(float)bitmap.getHeight()/bitmap.getWidth();}
    void clearLassoSelection(){lassoAnchor=null;lassoDrawing=false;lassoPoints.clear();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);invalidate();}
    private void addLassoPoint(float x,float y,RectF dest){
        if(dest.width()<=0||dest.height()<=0)return;
        PointF point=new PointF(Math.max(0,Math.min(1,(x-dest.left)/dest.width())),Math.max(0,Math.min(1,(y-dest.top)/dest.height())));
        if(!lassoPoints.isEmpty()){PointF last=lassoPoints.get(lassoPoints.size()-1);if(Math.hypot((point.x-last.x)*dest.width(),(point.y-last.y)*dest.height())<2)return;}
        if(lassoPoints.size()<8192)lassoPoints.add(point);
    }
    private Path lassoPath(RectF dest){Path path=new Path();for(int i=0;i<lassoPoints.size();i++){PointF p=lassoPoints.get(i);if(i==0)path.moveTo(dest.left+p.x*dest.width(),dest.top+p.y*dest.height());else path.lineTo(dest.left+p.x*dest.width(),dest.top+p.y*dest.height());}path.close();return path;}
    private boolean validLasso(RectF dest){
        if(lassoPoints.size()<3)return false;RectF bounds=new RectF();lassoPath(dest).computeBounds(bounds,true);
        double area=0;for(int i=0;i<lassoPoints.size();i++){PointF a=lassoPoints.get(i),b=lassoPoints.get((i+1)%lassoPoints.size());area+=a.x*b.y-b.x*a.y;}
        float minimum=8*getResources().getDisplayMetrics().density;
        return bounds.width()>=minimum&&bounds.height()>=minimum&&Math.abs(area)*dest.width()*dest.height()/2>=minimum*minimum;
    }
    Bitmap captureLasso(){
        RectF dest=contentRect();if(bitmap==null||!validLasso(dest))return null;
        RectF normalized=new RectF();lassoPath(new RectF(0,0,1,1)).computeBounds(normalized,true);
        int left=Math.max(0,(int)Math.floor(normalized.left*bitmap.getWidth())),top=Math.max(0,(int)Math.floor(normalized.top*bitmap.getHeight()));
        int right=Math.min(bitmap.getWidth(),(int)Math.ceil(normalized.right*bitmap.getWidth())),bottom=Math.min(bitmap.getHeight(),(int)Math.ceil(normalized.bottom*bitmap.getHeight()));
        float outputScale=Math.min(1f,(float)Math.sqrt(8000000d/((double)(right-left)*(bottom-top))));
        int width=Math.max(1,(int)Math.ceil((right-left)*outputScale)),height=Math.max(1,(int)Math.ceil((bottom-top)*outputScale));
        Bitmap capture=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(capture);
        // Clip in original-page pixels; render the same visible annotations without selection controls.
        canvas.scale(outputScale,outputScale);canvas.translate(-left,-top);
        canvas.clipPath(lassoPath(new RectF(0,0,bitmap.getWidth(),bitmap.getHeight())));
        canvas.scale(bitmap.getWidth()/dest.width(),bitmap.getHeight()/dest.height());canvas.translate(-dest.left,-dest.top);
        suppressSelection=true;try{onDraw(canvas);}catch(RuntimeException error){capture.recycle();throw error;}finally{suppressSelection=false;}
        return capture;
    }
    /** Inserted objects (typed text, pictures, shapes, tables, stickers...) whose centre lies inside the lasso on this page. */
    List<AnnotationStore.PageElement> lassoElements(){
        List<AnnotationStore.PageElement> out=new ArrayList<>();
        if(annotationStore==null||bitmap==null||!validLasso(contentRect()))return out;
        for(AnnotationStore.PageElement e:annotationStore.elements){
            if(e.page!=page||e.kind.equals("hyperlink")||e.kind.equals("audio"))continue;
            if(insideLasso((e.left+e.right)/2f,(e.top+e.bottom)/2f))out.add(e);
        }
        return out;
    }
    private boolean insideLasso(float x,float y){
        boolean in=false;int n=lassoPoints.size();
        for(int i=0,j=n-1;i<n;j=i++){PointF a=lassoPoints.get(i),b=lassoPoints.get(j);if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)in=!in;}
        return in;
    }
    String lassoText(){
        if(lassoPoints.size()<3)return "";android.graphics.Region region=new android.graphics.Region();region.setPath(lassoPath(new RectF(0,0,10000,10000)),new android.graphics.Region(0,0,10000,10000));
        StringBuilder text=new StringBuilder();RectF previousLine=null;
        for(TextRegion word:textRegions)if(region.contains((int)(word.wordBounds.centerX()*10000),(int)(word.wordBounds.centerY()*10000))){if(text.length()>0)text.append(previousLine!=null&&!previousLine.equals(word.lineBounds)?'\n':' ');text.append(word.word);previousLine=word.lineBounds;}
        return text.toString();
    }

    private RectF drawSticky(Canvas canvas,RectF dest,float nx,float ny,String text,boolean minimized,int accent){return drawSticky(canvas,dest,nx,ny,text,minimized,accent,0xFFFFF3A6,13,1,0,0,0);}
    private RectF drawSticky(Canvas canvas,RectF dest,float nx,float ny,String text,boolean minimized,int accent,int paper,int fontSp,int boxSize,float customW,float customH,float rot){
        float density=getResources().getDisplayMetrics().density;
        float anchorX=dest.left+nx*dest.width(),anchorY=dest.top+ny*dest.height();
        if(minimized){float r=14*density;RectF box=new RectF(anchorX-r,anchorY-r,anchorX+r,anchorY+r);paint.setColor(accent);canvas.drawCircle(anchorX,anchorY,r,paint);paint.setColor(Color.WHITE);paint.setTextSize(17*density);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("▣",anchorX,anchorY+6*density,paint);paint.setTextAlign(Paint.Align.LEFT);return box;}
        float[] boxW={150,220,300},boxH={64,96,170};float w=Math.min(boxW[boxSize]*density,dest.width()*(boxSize==2?0.7f:0.46f)),h=boxH[boxSize]*density;if(customW>0&&customH>0){w=Math.min(customW*density,dest.width()*.92f);h=customH*density;}
        float left=Math.min(dest.right-w-6*density,anchorX+8*density);if(left<dest.left)left=dest.left+6*density;
        float top=Math.max(dest.top+6*density,Math.min(dest.bottom-h-6*density,anchorY));RectF box=new RectF(left,top,left+w,top+h);
        canvas.save();if(rot!=0)canvas.rotate(rot,box.centerX(),box.centerY());try{drawStickyBody(canvas,box,anchorX,anchorY,text,accent,paper,fontSp,density);}finally{canvas.restore();}return box;
    }
    private void drawStickyBody(Canvas canvas,RectF box,float anchorX,float anchorY,String text,int accent,int paper,int fontSp,float density){
        paint.setColor(paper);canvas.drawRoundRect(box,10*density,10*density,paint);paint.setColor(accent);canvas.drawCircle(anchorX,anchorY,6*density,paint);
        canvas.save();canvas.clipRect(box);try{drawStickyText(canvas,box,text,fontSp,density);}finally{canvas.restore();}
    }
    private void drawStickyText(Canvas canvas,RectF box,String text,int fontSp,float density){
        paint.setColor(0xFF3F3A2D);paint.setTextSize(fontSp*density);float lineHeight=fontSp*1.4f*density;float x=box.left+10*density,y=box.top+(fontSp+9)*density,max=box.width()-20*density;
        for(String paragraph:(text==null?"":text).split("\\n")){String line="";for(String word:paragraph.split(" ")){String candidate=line.isEmpty()?word:line+" "+word;if(paint.measureText(candidate)>max&&!line.isEmpty()){canvas.drawText(line,x,y,paint);y+=lineHeight;line=word;if(y>box.bottom-12*density)return;}else line=candidate;}if(!line.isEmpty()){canvas.drawText(line,x,y,paint);y+=18*density;if(y>box.bottom-12*density)return;}}
        return;
    }
    /** Selected post-it (a memo Mark or a TranslationNote): shape-like frame with 4 corner handles, rotate knob, delete badge; body drag moves it. */
    private Object selSticky;private int stDrag;private boolean stMoved;private float stStartX,stStartY,stW0,stH0,stAx0,stAy0;
    private RectF stBox(Object o){return o instanceof AnnotationStore.Mark?memoHitBoxes.get(o):noteHitBoxes.get(o);}
    private float stRot(Object o){return o instanceof AnnotationStore.Mark?((AnnotationStore.Mark)o).rot:((AnnotationStore.TranslationNote)o).rot;}
    private boolean stMin(Object o){return o instanceof AnnotationStore.Mark?((AnnotationStore.Mark)o).minimized:((AnnotationStore.TranslationNote)o).minimized;}
    private int stPage(Object o){return o instanceof AnnotationStore.Mark?((AnnotationStore.Mark)o).page:((AnnotationStore.TranslationNote)o).page;}
    private float stAx(Object o){return o instanceof AnnotationStore.Mark?((AnnotationStore.Mark)o).right:((AnnotationStore.TranslationNote)o).right;}
    private float stAy(Object o){return o instanceof AnnotationStore.Mark?((AnnotationStore.Mark)o).top:((AnnotationStore.TranslationNote)o).top;}
    private void stSetAnchor(Object o,float x,float y){x=Math.max(0f,Math.min(1f,x));y=Math.max(0f,Math.min(1f,y));if(o instanceof AnnotationStore.Mark){((AnnotationStore.Mark)o).right=x;((AnnotationStore.Mark)o).top=y;}else{((AnnotationStore.TranslationNote)o).right=x;((AnnotationStore.TranslationNote)o).top=y;}}
    private void stSetSize(Object o,float w,float h){if(o instanceof AnnotationStore.Mark){((AnnotationStore.Mark)o).boxW=w;((AnnotationStore.Mark)o).boxH=h;}else{((AnnotationStore.TranslationNote)o).boxW=w;((AnnotationStore.TranslationNote)o).boxH=h;}}
    private void stSetRot(Object o,float r){if(o instanceof AnnotationStore.Mark)((AnnotationStore.Mark)o).rot=r;else((AnnotationStore.TranslationNote)o).rot=r;}
    private float[] unrotate(RectF b,float rot,float x,float y){double r=Math.toRadians(-rot);float dx=x-b.centerX(),dy=y-b.centerY();return new float[]{(float)(b.centerX()+dx*Math.cos(r)-dy*Math.sin(r)),(float)(b.centerY()+dx*Math.sin(r)+dy*Math.cos(r))};}
    private Object stickyAt(float x,float y){
        for(AnnotationStore.TranslationNote n:noteHitBoxes.keySet()){RectF b=noteHitBoxes.get(n);if(b!=null){float[] q=n.minimized?new float[]{x,y}:unrotate(b,n.rot,x,y);if(b.contains(q[0],q[1]))return n;}}
        for(AnnotationStore.Mark m:memoHitBoxes.keySet()){RectF b=memoHitBoxes.get(m);if(b!=null){float[] q=m.minimized?new float[]{x,y}:unrotate(b,m.rot,x,y);if(b.contains(q[0],q[1]))return m;}}
        return null;
    }
    private void drawMemoSelection(Canvas canvas){
        if(selSticky==null)return;RectF b=stBox(selSticky);if(b==null||stPage(selSticky)!=page||stMin(selSticky)){return;}
        float d=getResources().getDisplayMetrics().density;Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);canvas.save();canvas.rotate(stRot(selSticky),b.centerX(),b.centerY());
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(0xFF007AFF);p.setPathEffect(new DashPathEffect(new float[]{8*d,5*d},0));canvas.drawRoundRect(b,10*d,10*d,p);p.setPathEffect(null);
        canvas.drawLine(b.centerX(),b.top,b.centerX(),b.top-28*d,p);
        float[][] cs={{b.left,b.top},{b.right,b.top},{b.left,b.bottom},{b.right,b.bottom}};
        for(float[] c:cs){p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);canvas.drawCircle(c[0],c[1],8*d,p);p.setStyle(Paint.Style.STROKE);p.setColor(0xFF007AFF);canvas.drawCircle(c[0],c[1],8*d,p);}
        p.setStyle(Paint.Style.FILL);p.setColor(0xFF007AFF);canvas.drawCircle(b.centerX(),b.top-28*d,12*d,p);p.setColor(Color.WHITE);p.setTextSize(15*d);p.setTextAlign(Paint.Align.CENTER);canvas.drawText("↻",b.centerX(),b.top-28*d+5*d,p);
        p.setColor(0xFFFF3B30);canvas.drawCircle(b.right+14*d,b.top-28*d,12*d,p);p.setColor(Color.WHITE);p.setTextSize(16*d);canvas.drawText("×",b.right+14*d,b.top-28*d+5.5f*d,p);
        canvas.restore();
    }
    private boolean handleMemoGesture(MotionEvent e,RectF dest){
        if(selSticky==null)return false;RectF b=stBox(selSticky);if(b==null||stPage(selSticky)!=page||stMin(selSticky)){selSticky=null;return false;}
        int action=e.getActionMasked();float d=getResources().getDisplayMetrics().density;
        if(action==MotionEvent.ACTION_DOWN&&e.getPointerCount()==1&&!isStylus(e)){
            float rot=stRot(selSticky);float[] q=unrotate(b,rot,e.getX(),e.getY());float x=q[0],y=q[1];int hit=0;
            if(Math.hypot(x-(b.right+14*d),y-(b.top-28*d))<=20*d)hit=7;
            else if(Math.hypot(x-b.centerX(),y-(b.top-28*d))<=20*d)hit=5;
            else if(Math.hypot(x-b.left,y-b.top)<=22*d)hit=1;else if(Math.hypot(x-b.right,y-b.top)<=22*d)hit=2;else if(Math.hypot(x-b.left,y-b.bottom)<=22*d)hit=3;else if(Math.hypot(x-b.right,y-b.bottom)<=22*d)hit=4;
            else if(b.contains(x,y))hit=6;
            if(hit==0)return false;
            stDrag=hit;stMoved=false;stStartX=e.getX();stStartY=e.getY();stW0=b.width()/d;stH0=b.height()/d;stAx0=stAx(selSticky);stAy0=stAy(selSticky);
            if(hit!=7)listener.onSelectionAdjustStarted();getParent().requestDisallowInterceptTouchEvent(true);return true;
        }
        if(stDrag==0)return false;
        float rot=stRot(selSticky);
        if(action==MotionEvent.ACTION_MOVE){
            float dx=e.getX()-stStartX,dy=e.getY()-stStartY;
            if(stDrag==6){if(!stMoved&&Math.hypot(dx,dy)<touchSlop())return true;stMoved=true;stSetAnchor(selSticky,stAx0+dx/dest.width(),stAy0+dy/dest.height());invalidate();return true;}
            if(stDrag==5){double ang=Math.toDegrees(Math.atan2(e.getY()-b.centerY(),e.getX()-b.centerX()))+90;float r=(float)((ang%360+360)%360);for(int k=0;k<=360;k+=90)if(Math.abs(r-k)<4)r=k%360;stSetRot(selSticky,r);stMoved=true;invalidate();return true;}
            if(stDrag>=1&&stDrag<=4){
                double rr=Math.toRadians(-rot);float lx=(float)(dx*Math.cos(rr)-dy*Math.sin(rr))/d,ly=(float)(dx*Math.sin(rr)+dy*Math.cos(rr))/d;
                boolean left=stDrag==1||stDrag==3,top=stDrag==1||stDrag==2;float maxW=Math.min(560f,dest.width()/d*.92f);
                float w=Math.max(44f,Math.min(maxW,left?stW0-lx:stW0+lx)),h=Math.max(26f,Math.min(700f,top?stH0-ly:stH0+ly));
                stSetSize(selSticky,w,h);stSetAnchor(selSticky,stAx0+(left?(stW0-w)*d/dest.width():0),stAy0+(top?(stH0-h)*d/dest.height():0));stMoved=true;invalidate();return true;
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
            int mode=stDrag;stDrag=0;getParent().requestDisallowInterceptTouchEvent(false);Object o=selSticky;
            if(action==MotionEvent.ACTION_UP){
                if(mode==7){selSticky=null;if(o instanceof AnnotationStore.Mark&&marks!=null)marks.remove(o);else if(translations!=null)translations.remove(o);listener.onInkChanged();}
                else if(mode==6&&!stMoved){selSticky=null;if(o instanceof AnnotationStore.Mark)listener.onMarkTapped((AnnotationStore.Mark)o);else listener.onTranslationTapped((AnnotationStore.TranslationNote)o);}
                else if(stMoved)listener.onInkChanged();
            }
            invalidate();return true;
        }
        return true;
    }
    private void drawMemo(Canvas canvas,RectF dest,AnnotationStore.Mark m){memoHitBoxes.put(m,drawSticky(canvas,dest,m.right,m.top,m.note,m.minimized,0xFFFFB300,m.paper,m.fontSp,m.boxSize,m.boxW,m.boxH,m.rot));}
    private void drawTranslation(Canvas canvas,RectF dest,AnnotationStore.TranslationNote n){noteHitBoxes.put(n,drawSticky(canvas,dest,n.right,n.top,n.translated,n.minimized,0xFF7C3AED,0xFFFFF3A6,13,1,n.boxW,n.boxH,n.rot));}

    private float strokeWidth(float base,float pressure,RectF dest){float p=Math.max(0.12f,Math.min(1f,pressure));return Math.max(1.5f,base*dest.width()*(0.45f+p*1.15f));}

    private boolean isStylus(MotionEvent e){int tool=e.getToolType(0);return tool==MotionEvent.TOOL_TYPE_STYLUS||tool==MotionEvent.TOOL_TYPE_ERASER;}
    private TextRegion textRegionAt(float x,float y,RectF dest){
        if(!textSelectMode||dest.width()==0||!dest.contains(x,y))return null;
        float nx=(x-dest.left)/dest.width(),ny=(y-dest.top)/dest.height();
        float tolerance=(directTextSelection?10f:3f)*getResources().getDisplayMetrics().density;
        TextRegion best=null;float bestDistance=Float.MAX_VALUE;
        for(TextRegion r:textRegions){
            float dx=(nx-Math.max(r.wordBounds.left,Math.min(nx,r.wordBounds.right)))*dest.width();
            float dy=(ny-Math.max(r.wordBounds.top,Math.min(ny,r.wordBounds.bottom)))*dest.height();
            float distance=dx*dx+dy*dy;
            if(distance<bestDistance){bestDistance=distance;best=r;}
        }
        return bestDistance<=tolerance*tolerance?best:null;
    }
    private TextRegion nearestTextRegion(float x,float y,RectF dest){TextRegion hit=textRegionAt(x,y,dest);if(hit!=null)return hit;if(dest.width()==0||textRegions.isEmpty())return null;float nx=Math.max(0f,Math.min(1f,(x-dest.left)/dest.width())),ny=Math.max(0f,Math.min(1f,(y-dest.top)/dest.height())),bestDistance=Float.MAX_VALUE;TextRegion best=null;for(TextRegion r:textRegions){float dx=nx-Math.max(r.wordBounds.left,Math.min(nx,r.wordBounds.right)),dy=ny-Math.max(r.wordBounds.top,Math.min(ny,r.wordBounds.bottom));float distance=dx*dx+dy*dy*2f;if(distance<bestDistance){bestDistance=distance;best=r;}}return best;}
    private void beginTextSelectionNow(){selectionHandler.removeCallbacks(beginTextSelection);if(selectingText||selectionStartRegion==null)return;selectingText=true;selectionCandidate=false;bodySwipeCandidate=false;panning=false;selectedTextRegions.clear();selectedTextRegions.add(selectionStartRegion);getParent().requestDisallowInterceptTouchEvent(true);performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);invalidate();}
    private void updateTextSelection(TextRegion end){if(end==null||selectionStartRegion==null)return;selectionEndRegion=end;int a=textRegions.indexOf(selectionStartRegion),b=textRegions.indexOf(end);if(a<0||b<0)return;selectedTextRegions.clear();for(int i=Math.min(a,b);i<=Math.max(a,b);i++)selectedTextRegions.add(textRegions.get(i));invalidate();}
    private List<RectF> selectionLineBounds(){
        List<RectF> bounds=new ArrayList<>();RectF previousLine=null,current=null;
        for(TextRegion region:selectedTextRegions){
            if(previousLine!=null&&previousLine.equals(region.lineBounds)){current.union(region.wordBounds);}
            else{current=new RectF(region.wordBounds);bounds.add(current);previousLine=region.lineBounds;}
        }
        return bounds;
    }
    private TextSelection finishTextSelection(){if(selectedTextRegions.isEmpty())return null;StringBuilder text=new StringBuilder();List<RectF> bounds=selectionLineBounds();RectF union=new RectF(selectedTextRegions.get(0).wordBounds);for(TextRegion r:selectedTextRegions){if(text.length()>0)text.append(' ');text.append(r.word);union.union(r.wordBounds);}return new TextSelection(text.toString(),bounds,union,selectedTextRegions.size()==1);}
    private boolean temporaryEraser(MotionEvent e){return e.getToolType(0)==MotionEvent.TOOL_TYPE_ERASER||(e.getButtonState()&MotionEvent.BUTTON_STYLUS_PRIMARY)!=0;}
    private void finishInkStroke(){if(activeStroke!=null&&!activeStroke.points.isEmpty())listener.onInkChanged();activeStroke=null;stylusDrawing=false;getParent().requestDisallowInterceptTouchEvent(false);}
    private float inputPressure(MotionEvent e){return isStylus(e)?Math.max(0.05f,Math.min(1f,e.getPressure())):0.65f;}
    /** Pen stabiliser (v1.55.0): digitizer jitter is a fixed number of screen pixels, so at 100% zoom it is large compared to the letters (written at 300% the same jitter is a third as big in page units). Input is therefore filtered in screen space: adaptive exponential smoothing (strong for slow, tiny moves, almost none for fast strokes) and points closer than ~1 dp are dropped. */
    private float smx,smy;private boolean smValid;
    private void inkSmoothTo(float x,float y){
        if(!smValid){smx=x;smy=y;smValid=true;return;}
        float dx=x-smx,dy=y-smy,dist=(float)Math.hypot(dx,dy),k=7f*getResources().getDisplayMetrics().density,a=dist/(dist+k);
        smx+=dx*a;smy+=dy*a;
    }
    private void addInkPoint(MotionEvent e,RectF dest){pushInkPoint(e.getX(),e.getY(),inputPressure(e),dest,false);}
    private void pushInkPoint(float sx,float sy,float pressure,RectF dest,boolean raw){
        if(activeStroke==null||!dest.contains(sx,sy))return;
        java.util.List<AnnotationStore.InkPoint> pts=activeStroke.points;
        float x=(sx-dest.left)/dest.width(),y=(sy-dest.top)/dest.height();
        if(pts.isEmpty()){pts.add(new AnnotationStore.InkPoint(x,y,pressure));smx=sx;smy=sy;smValid=true;return;}
        if(inkMode==3){AnnotationStore.InkPoint end=new AnnotationStore.InkPoint(x,y,pressure);if(pts.size()==1)pts.add(end);else pts.set(1,end);return;}
        float fx=sx,fy=sy;
        if(raw)smValid=false;else{inkSmoothTo(sx,sy);fx=smx;fy=smy;}   // the last point of a stroke is the raw pen-up position: no lag at the end
        AnnotationStore.InkPoint last=pts.get(pts.size()-1);
        float lx=dest.left+last.x*dest.width(),ly=dest.top+last.y*dest.height();
        float minDist=(raw?.5f:1.5f)*getResources().getDisplayMetrics().density;
        if(Math.hypot(fx-lx,fy-ly)>=minDist)pts.add(new AnnotationStore.InkPoint((fx-dest.left)/dest.width(),(fy-dest.top)/dest.height(),raw?last.pressure:pressure));   // pen-up reports pressure 0: keep the last real one
    }
    /** Eraser radius in dp (the circle shown under the finger / pen while erasing). */
    private float eraserRadius=10f;private float eraserX=-1,eraserY=-1;private boolean eraserPartial;
    void setEraserPartial(boolean on){eraserPartial=on;}
    boolean eraserPartial(){return eraserPartial;}
    /** Removes every ink stroke and highlight of this page; returns whether anything was removed. */
    /** What the last {@link #clearPageInk()} removed, so the caller can offer an undo. */
    final List<AnnotationStore.InkStroke> clearedStrokes=new ArrayList<>();final List<AnnotationStore.Mark> clearedMarks=new ArrayList<>();
    boolean clearPageInk(){clearedStrokes.clear();clearedMarks.clear();boolean changed=false;if(strokes!=null)for(int i=strokes.size()-1;i>=0;i--)if(strokes.get(i).page==page){clearedStrokes.add(0,strokes.remove(i));changed=true;}if(marks!=null)for(int i=marks.size()-1;i>=0;i--){AnnotationStore.Mark m=marks.get(i);if(m.page==page&&!m.noteOnly){clearedMarks.add(0,marks.remove(i));changed=true;}}if(changed)listener.onInkChanged();invalidate();return changed;}
    /** Partial erase: cuts the parts of the stroke inside the circle (centre cx,cy, radius r, all in view px) out and returns the remaining pieces (null when untouched). */
    private List<AnnotationStore.InkStroke> cutStroke(AnnotationStore.InkStroke s,RectF dest,float cx,float cy,float r){
        float reach=r+s.width*dest.width()/2f,w=dest.width(),h=dest.height();boolean near=false;
        for(AnnotationStore.InkPoint p:s.points)if(Math.hypot(dest.left+p.x*w-cx,dest.top+p.y*h-cy)<=reach+r*2){near=true;break;}
        if(!near)return null;
        List<AnnotationStore.InkPoint> dense=new ArrayList<>();float step=Math.max(1.5f,reach/3f);
        for(int i=0;i<s.points.size();i++){AnnotationStore.InkPoint b=s.points.get(i);if(i>0){AnnotationStore.InkPoint a=s.points.get(i-1);float len=(float)Math.hypot((b.x-a.x)*w,(b.y-a.y)*h);int n=(int)(len/step);for(int k=1;k<=n;k++){float t=k/(float)(n+1);dense.add(new AnnotationStore.InkPoint(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.pressure+(b.pressure-a.pressure)*t));}}dense.add(b);}
        List<AnnotationStore.InkStroke> out=new ArrayList<>();AnnotationStore.InkStroke cur=null;boolean cut=false;
        for(AnnotationStore.InkPoint p:dense){boolean inside=Math.hypot(dest.left+p.x*w-cx,dest.top+p.y*h-cy)<=reach;
            if(inside){cut=true;cur=null;continue;}
            if(cur==null){cur=new AnnotationStore.InkStroke();cur.page=s.page;cur.color=s.color;cur.width=s.width;cur.pen=s.pen;out.add(cur);}
            cur.points.add(p);}
        if(!cut)return null;
        for(int i=out.size()-1;i>=0;i--)if(out.get(i).points.size()<2)out.remove(i);
        return out;}

    void setEraserRadius(float dp){eraserRadius=Math.max(3f,Math.min(60f,dp));}
    float eraserRadius(){return eraserRadius;}
    private void eraseAt(MotionEvent e,RectF dest){eraserX=e.getX();eraserY=e.getY();if(strokes==null||dest.width()==0)return;float r=eraserRadius*getResources().getDisplayMetrics().density,ex=e.getX(),ey=e.getY();boolean changed=false;
        if(eraserPartial)for(int i=strokes.size()-1;i>=0;i--){AnnotationStore.InkStroke s=strokes.get(i);if(s.page!=page)continue;List<AnnotationStore.InkStroke> rest=cutStroke(s,dest,ex,ey,r);if(rest==null)continue;strokes.remove(i);strokes.addAll(i,rest);changed=true;}
        else for(int i=strokes.size()-1;i>=0;i--){AnnotationStore.InkStroke s=strokes.get(i);if(s.page!=page)continue;float reach=r+s.width*dest.width()/2f;for(AnnotationStore.InkPoint p:s.points)if(Math.hypot(dest.left+p.x*dest.width()-ex,dest.top+p.y*dest.height()-ey)<=reach){strokes.remove(i);changed=true;break;}}
        if(marks!=null)for(int i=marks.size()-1;i>=0;i--){AnnotationStore.Mark m=marks.get(i);if(m.page!=page||m.noteOnly)continue;float l=dest.left+m.left*dest.width(),t=dest.top+m.top*dest.height(),rr=dest.left+m.right*dest.width(),b=dest.top+m.bottom*dest.height();float nx=Math.max(l,Math.min(rr,ex)),ny=Math.max(t,Math.min(b,ey));if(Math.hypot(nx-ex,ny-ey)<=r){marks.remove(i);changed=true;}}
        if(changed)eraseDirty=true;invalidate();}
    /** Something was erased during the current eraser gesture; saved once when the pen lifts so a whole swipe is a single undo step. */
    private boolean eraseDirty;

    private float touchSlop(){return Math.max(14*getResources().getDisplayMetrics().density,android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()*1.5f);}
    private float swipeDistance(){return Math.max(48*getResources().getDisplayMetrics().density,Math.min((verticalPageSwipe?getHeight():getWidth())*.1f,100*getResources().getDisplayMetrics().density));}

    /** Palm rejection: while a pen is near or drawing, touches (the resting hand) neither draw, pan, zoom nor swipe pages. */
    private long lastStylusTime=-100000;private boolean palmBlocked;
    @Override public boolean onHoverEvent(MotionEvent e){if(isStylus(e))lastStylusTime=e.getEventTime();return super.onHoverEvent(e);}
    private boolean stylusPointer(MotionEvent e,int index){int t=e.getToolType(index);return t==MotionEvent.TOOL_TYPE_STYLUS||t==MotionEvent.TOOL_TYPE_ERASER;}
    private boolean largeContact(MotionEvent e){int t=e.getToolType(0);if(t==5/*TOOL_TYPE_PALM*/)return true;float mm=e.getTouchMajor()/Math.max(1f,getResources().getDisplayMetrics().xdpi)*25.4f;return inkMode!=0&&mm>18f;}
    private boolean smallContacts(MotionEvent e){float xdpi=Math.max(1f,getResources().getDisplayMetrics().xdpi);for(int i=0;i<e.getPointerCount();i++){if(e.getToolType(i)==5/*TOOL_TYPE_PALM*/||e.getTouchMajor(i)/xdpi*25.4f>14f)return false;}return true;}
    /** Returns true when the event belongs to a palm / resting hand and must be swallowed. */
    private boolean rejectPalm(MotionEvent e){
        int am=e.getActionMasked();
        for(int i=0;i<e.getPointerCount();i++)if(stylusPointer(e,i))lastStylusTime=e.getEventTime();
        if(am==MotionEvent.ACTION_DOWN){palmBlocked=!stylusPointer(e,0)&&(e.getEventTime()-lastStylusTime<500||largeContact(e));}
        // two small finger contacts arriving together are a pinch, not a palm: take over a gesture that was swallowed as a possible palm (write mode used to ignore pinch zoom)
        if(palmBlocked&&am==MotionEvent.ACTION_POINTER_DOWN&&e.getPointerCount()==2&&!stylusPointer(e,0)&&!stylusPointer(e,1)&&e.getEventTime()-lastStylusTime>=200&&smallContacts(e))palmBlocked=false;
        if(palmBlocked){if(am==MotionEvent.ACTION_UP||am==MotionEvent.ACTION_CANCEL)palmBlocked=false;return true;}
        if(stylusDrawing&&e.getPointerCount()>1&&(am==MotionEvent.ACTION_POINTER_DOWN||am==MotionEvent.ACTION_POINTER_UP)){
            if(am==MotionEvent.ACTION_POINTER_UP&&stylusPointer(e,e.getActionIndex())){finishInkStroke();eraserX=eraserY=-1;palmBlocked=true;invalidate();}
            return true;}
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if(rejectPalm(e))return true;
        if(!(stylusDrawing&&e.getPointerCount()>1))scaleDetector.onTouchEvent(e);
        if (scaleDetector.isInProgress()) return true;
        RectF dest = contentRect();
        boolean stylus=isStylus(e);
        {int am=e.getActionMasked();if(am==MotionEvent.ACTION_UP||am==MotionEvent.ACTION_CANCEL||am==MotionEvent.ACTION_POINTER_DOWN||(am==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-startX,e.getY()-startY)>touchSlop()))selectionHandler.removeCallbacks(blankPress);}
        if(handleMemoGesture(e,dest))return true;
        if(handleElementGesture(e,dest))return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)scalingOccurred=false;
        if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){finishInkStroke();clearLassoSelection();}
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            listener.onSelectionAdjustStarted();
            float edge=72*getResources().getDisplayMetrics().density;
            boolean eligible=pageSwipeEnabled&&!stylus&&!directTextSelection&&!lassoMode&&!highlightMode&&!memoMode&&!outlineMode&&!(inkMode!=0&&fingerInk);
            bodySwipeCandidate=eligible&&scale<=1f&&onSpreadDocument(e.getX(),e.getY());edgeStartTime=e.getEventTime();
            edgeSwipe=eligible&&scale>1f&&
                (verticalPageSwipe?(e.getY()<edge||e.getY()>getHeight()-edge):(e.getX()<edge||e.getX()>getWidth()-edge));
            if(edgeSwipe){startX=e.getX();startY=e.getY();edgeStartTime=e.getEventTime();selectionHandler.removeCallbacks(beginTextSelection);getParent().requestDisallowInterceptTouchEvent(true);return true;}
        }
        if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){edgeSwipe=bodySwipeCandidate=false;if(dragging){dragging=false;if(dragTracker!=null){dragTracker.recycle();dragTracker=null;}pageDrag.end(-1e6f);}}
        if(edgeSwipe){
            if(e.getActionMasked()==MotionEvent.ACTION_UP){float along=verticalPageSwipe?e.getY()-startY:e.getX()-startX,cross=verticalPageSwipe?e.getX()-startX:e.getY()-startY;
                edgeSwipe=false;getParent().requestDisallowInterceptTouchEvent(false);
                float threshold=swipeDistance();
                if(Math.abs(along)>=threshold&&Math.abs(along)>Math.abs(cross)*1.5f&&e.getEventTime()-edgeStartTime<=1200)listener.onPageSwipe(along<0?1:-1);
            }else if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){edgeSwipe=false;getParent().requestDisallowInterceptTouchEvent(false);}return true;
        }
        if(lassoMode){
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){listener.onSelectionAdjustStarted();clearLassoSelection();clearTextSelectionOverlay();if(dest.contains(e.getX(),e.getY())){lassoDrawing=true;getParent().requestDisallowInterceptTouchEvent(true);lassoAnchor=normalizedPoint(e.getX(),e.getY(),dest);if(lassoShape==LASSO_FREE)addLassoPoint(e.getX(),e.getY(),dest);}invalidate();return true;}
            if(action==MotionEvent.ACTION_CANCEL){clearLassoSelection();return true;}
            if(e.getPointerCount()!=1||scalingOccurred)return true;
            if(action==MotionEvent.ACTION_MOVE&&lassoDrawing){if(lassoShape==LASSO_FREE){for(int i=0;i<e.getHistorySize();i++)addLassoPoint(e.getHistoricalX(i),e.getHistoricalY(i),dest);addLassoPoint(e.getX(),e.getY(),dest);}else updateLassoShape(e.getX(),e.getY(),dest);invalidate();return true;}
            if(action==MotionEvent.ACTION_UP&&lassoDrawing){if(lassoShape==LASSO_FREE)addLassoPoint(e.getX(),e.getY(),dest);else updateLassoShape(e.getX(),e.getY(),dest);lassoDrawing=false;getParent().requestDisallowInterceptTouchEvent(false);if(validLasso(dest))listener.onLassoSelectionFinished();else clearLassoSelection();invalidate();return true;}
            return true;
        }
        if(inkMode!=0&&(stylus||fingerInk)&&(e.getPointerCount()==1||stylusDrawing&&stylus)&&!scalingOccurred){int action=e.getActionMasked();boolean erase=inkMode==2||temporaryEraser(e);if(action==MotionEvent.ACTION_DOWN){getParent().requestDisallowInterceptTouchEvent(true);stylusDrawing=true;if(erase)eraseAt(e,dest);else if(dest.contains(e.getX(),e.getY())){activeStroke=new AnnotationStore.InkStroke();activeStroke.page=page;activeStroke.color=inkColor;activeStroke.width=inkWidth;activeStroke.pen=inkPen;addInkPoint(e,dest);if(strokes!=null)strokes.add(activeStroke);}invalidate();return true;}if(action==MotionEvent.ACTION_MOVE&&stylusDrawing){if(erase)eraseAt(e,dest);else{for(int i=0;inkMode!=3&&i<e.getHistorySize();i++){float p=stylus?Math.max(0.05f,Math.min(1f,e.getHistoricalPressure(i))):0.65f;pushInkPoint(e.getHistoricalX(i),e.getHistoricalY(i),p,dest,false);}addInkPoint(e,dest);}invalidate();return true;}if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)&&stylusDrawing){if(erase&&eraseDirty){eraseDirty=false;listener.onInkChanged();}if(!erase&&activeStroke!=null){if(action==MotionEvent.ACTION_CANCEL){if(strokes!=null)strokes.remove(activeStroke);}else{pushInkPoint(e.getX(),e.getY(),inputPressure(e),dest,true);if(!activeStroke.points.isEmpty())listener.onInkChanged();}}activeStroke=null;stylusDrawing=false;eraserX=eraserY=-1;getParent().requestDisallowInterceptTouchEvent(false);invalidate();return true;}}
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            listener.onSelectionAdjustStarted();
            selectionHandler.removeCallbacks(beginTextSelection);
            startX = currentX = lastX = e.getX();
            startY = currentY = lastY = e.getY();
            gestureMoved = false;
            if(!selectedTextRegions.isEmpty()){
                TextRegion first=selectedTextRegions.get(0),last=selectedTextRegions.get(selectedTextRegions.size()-1);
                float radius=22f*getResources().getDisplayMetrics().density;
                float leftDistance=(float)Math.hypot(e.getX()-(dest.left+first.wordBounds.left*dest.width()),e.getY()-(dest.top+first.wordBounds.bottom*dest.height()));
                float rightDistance=(float)Math.hypot(e.getX()-(dest.left+last.wordBounds.right*dest.width()),e.getY()-(dest.top+last.wordBounds.bottom*dest.height()));
                // A touch on a word starts a new selection, even near an old handle.
                boolean onWord=false;
                for(TextRegion region:textRegions){
                    RectF bounds=region.wordBounds;
                    if(new RectF(dest.left+bounds.left*dest.width(),dest.top+bounds.top*dest.height(),dest.left+bounds.right*dest.width(),dest.top+bounds.bottom*dest.height()).contains(e.getX(),e.getY())){onWord=true;break;}
                }
                boolean left=leftDistance<radius&&leftDistance<=rightDistance;
                boolean right=rightDistance<radius&&!left;
                if(!onWord&&(left||right)){selectionStartRegion=left?last:first;selectionEndRegion=left?first:last;selectingText=true;selectionCandidate=false;bodySwipeCandidate=false;panning=false;drawing=false;scalingOccurred=false;getParent().requestDisallowInterceptTouchEvent(true);return true;}
                clearTextSelectionOverlay();
            }
            startX = currentX = e.getX(); startY = currentY = e.getY();
            lastX = startX; lastY = startY;
            gestureMoved = false; scalingOccurred = false;bodySwipeCandidate=pageSwipeEnabled&&!stylus&&!directTextSelection&&!lassoMode&&!highlightMode&&!memoMode&&!outlineMode&&!(inkMode!=0&&fingerInk)&&scale<=1f&&onSpreadDocument(e.getX(),e.getY());
            drawing = highlightMode && dest.contains(startX, startY);if(drawing&&highlightFree){freePts.clear();freePts.add(new float[]{startX,startY});}
            selectionStartRegion=(!drawing&&!memoMode&&!outlineMode&&inkMode==0)?textRegionAt(startX,startY,dest):null;selectionEndRegion=selectionStartRegion;selectionCandidate=selectionStartRegion!=null;selectingText=false;if(selectionCandidate)selectionHandler.postDelayed(beginTextSelection,420);
            selectionHandler.removeCallbacks(blankPress);if(!selectionCandidate&&!drawing&&!memoMode&&!outlineMode&&inkMode==0&&!lassoMode&&!directTextSelection&&!stylus&&scale<=1.05f&&dest.contains(startX,startY))selectionHandler.postDelayed(blankPress,650);
            selectionHandler.removeCallbacks(linkLongPress);linkLongFired=false;linkHit=!stylus&&scale<=1.05f&&linkTouchAllowed()?hyperlinkAt(startX,startY,dest):null;if(linkHit!=null){selectionCandidate=false;selectionHandler.removeCallbacks(beginTextSelection);selectionHandler.removeCallbacks(blankPress);selectionHandler.postDelayed(linkLongPress,500);}
            panning = scale > 1f && !outlineMode && !selectionCandidate;
            getParent().requestDisallowInterceptTouchEvent(drawing || panning || selectionCandidate);
            invalidate(); return true;
        }
        if(linkHit!=null&&(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN||e.getActionMasked()==MotionEvent.ACTION_CANCEL||(e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.hypot(e.getX()-startX,e.getY()-startY)>touchSlop()))){selectionHandler.removeCallbacks(linkLongPress);linkHit=null;}
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
            selectionHandler.removeCallbacks(beginTextSelection);selectionCandidate=selectingText=false;selectedTextRegions.clear();
            drawing = false; panning = false; scalingOccurred = true;
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_MOVE&&selectionCandidate&&Math.hypot(e.getX()-startX,e.getY()-startY)>touchSlop()){
            if(directTextSelection||stylus){beginTextSelectionNow();updateTextSelection(nearestTextRegion(e.getX(),e.getY(),dest));return true;}
            selectionHandler.removeCallbacks(beginTextSelection);selectionCandidate=false;panning=scale>1f;
        }
        if(dragging&&e.getActionMasked()==MotionEvent.ACTION_MOVE){dragTracker.addMovement(e);pageDrag.touchAt(pageFraction(e.getY()));float ddx=e.getX()-startX;pageDrag.move(Math.max(0f,dragDirection>0?-ddx:ddx));return true;}
        if(bodySwipeCandidate&&!selectingText&&e.getActionMasked()==MotionEvent.ACTION_MOVE){if(Math.hypot(e.getX()-startX,e.getY()-startY)>touchSlop()){gestureMoved=true;selectionHandler.removeCallbacks(beginTextSelection);selectionCandidate=false;
            if(pageDrag!=null&&!verticalPageSwipe){float dx=e.getX()-startX,dy=e.getY()-startY;
                if(!dragging&&Math.abs(dx)>Math.abs(dy)*1.5f){dragDirection=dx<0?1:-1;dragging=pageDrag.start(dragDirection);if(dragging){pageDrag.touchAt(pageFraction(e.getY()));dragTracker=android.view.VelocityTracker.obtain();getParent().requestDisallowInterceptTouchEvent(true);}}
                if(dragging){dragTracker.addMovement(e);pageDrag.move(Math.max(0f,dragDirection>0?-dx:dx));}
            }}return true;}
        if(dragging&&(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)){dragging=false;bodySwipeCandidate=false;getParent().requestDisallowInterceptTouchEvent(false);dragTracker.addMovement(e);dragTracker.computeCurrentVelocity(1000);float v=dragTracker.getXVelocity()*(dragDirection>0?-1:1);dragTracker.recycle();dragTracker=null;pageDrag.end(e.getActionMasked()==MotionEvent.ACTION_CANCEL?-1e6f:v);return true;}
        if(bodySwipeCandidate&&e.getActionMasked()==MotionEvent.ACTION_UP){bodySwipeCandidate=false;if(!selectingText&&gestureMoved){selectionHandler.removeCallbacks(beginTextSelection);selectionCandidate=false;getParent().requestDisallowInterceptTouchEvent(false);float along=verticalPageSwipe?e.getY()-startY:e.getX()-startX,cross=verticalPageSwipe?e.getX()-startX:e.getY()-startY;if(Math.abs(along)>=swipeDistance()&&Math.abs(along)>Math.abs(cross)*1.5f&&e.getEventTime()-edgeStartTime<=1200)listener.onPageSwipe(along<0?1:-1);return true;}}
        if(e.getAction()==MotionEvent.ACTION_MOVE&&selectingText){currentX=e.getX();currentY=e.getY();updateTextSelection(nearestTextRegion(currentX,currentY,dest));return true;}
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP && scale > 1f) {
            int remaining = e.getActionIndex() == 0 ? 1 : 0;
            if (remaining < e.getPointerCount()) {
                lastX = e.getX(remaining);
                lastY = e.getY(remaining);
                startX = lastX;
                startY = lastY;
                panning = true;
                gestureMoved = false;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE && drawing) {
            currentX = e.getX(); currentY = e.getY();
            if(highlightFree){float fx=Math.max(dest.left,Math.min(dest.right,currentX)),fy=Math.max(dest.top,Math.min(dest.bottom,currentY));float[] last=freePts.get(freePts.size()-1);if(Math.hypot(fx-last[0],fy-last[1])>=3)freePts.add(new float[]{fx,fy});}
            invalidate(); return true;
        }
        if (e.getAction() == MotionEvent.ACTION_MOVE && panning && e.getPointerCount() == 1) {
            if(!gestureMoved&&Math.hypot(e.getX()-startX,e.getY()-startY)<=touchSlop())return true;
            float dx = e.getX() - lastX;
            float dy = e.getY() - lastY;
            panX += dx*.8f; panY += dy*.8f;
            lastX = e.getX(); lastY = e.getY();
            if (Math.hypot(e.getX() - startX, e.getY() - startY) > touchSlop()) gestureMoved = true;
            clampPan(); invalidate(); return true;
        }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            selectionHandler.removeCallbacks(beginTextSelection);
            selectionHandler.removeCallbacks(linkLongPress);
            if(linkHit!=null){AnnotationStore.PageElement tapped=linkHit;linkHit=null;getParent().requestDisallowInterceptTouchEvent(false);selectionCandidate=false;if(linkLongFired||Math.hypot(e.getX()-startX,e.getY()-startY)>=touchSlop())return true;listener.onHyperlinkTapped(tapped,false);return true;}
            getParent().requestDisallowInterceptTouchEvent(false);
            if(selectingText){updateTextSelection(nearestTextRegion(e.getX(),e.getY(),dest));TextSelection selection=finishTextSelection();selectingText=selectionCandidate=false;if(selection!=null){listener.onTextSelectionFinished(selection,e.getX(),e.getY());return true;}}
            if(selectionCandidate&&selectionStartRegion!=null&&(directTextSelection||stylus)){
                selectedTextRegions.clear();selectedTextRegions.add(selectionStartRegion);
                TextSelection selection=finishTextSelection();selectionCandidate=false;
                listener.onTextSelectionFinished(selection,e.getX(),e.getY());invalidate();return true;
            }
            selectionCandidate=false;
            if (panning && gestureMoved) {
                panning = false; return true;
            }
            if (scalingOccurred) {
                panning = false; return true;
            }
            if(Math.hypot(e.getX()-startX,e.getY()-startY)<20){Object hitSticky=stickyAt(e.getX(),e.getY());if(hitSticky!=null){if(!stMin(hitSticky)&&hitSticky!=selSticky){selSticky=hitSticky;selectedElement=null;invalidate();return true;}selSticky=null;if(hitSticky instanceof AnnotationStore.Mark)listener.onMarkTapped((AnnotationStore.Mark)hitSticky);else listener.onTranslationTapped((AnnotationStore.TranslationNote)hitSticky);return true;}if(selSticky!=null){selSticky=null;invalidate();}}
            if (drawing) {
                currentX = Math.max(dest.left, Math.min(dest.right, e.getX()));
                currentY = Math.max(dest.top, Math.min(dest.bottom, e.getY()));
                if (highlightFree) {
                    if (freePts.size() >= 2) {
                        AnnotationStore.Mark m = new AnnotationStore.Mark(); m.page = page; m.color = highlightColor; m.thick = highlightThick;
                        java.util.List<float[]> pts = new ArrayList<>(freePts); int cap = 600; if (pts.size() > cap) { java.util.List<float[]> thin = new ArrayList<>(); for (int i = 0; i < cap; i++) thin.add(pts.get(Math.round(i * (pts.size() - 1f) / (cap - 1)))); pts = thin; }
                        m.path = new float[pts.size() * 2]; float minX = 1, minY = 1, maxX = 0, maxY = 0;
                        for (int i = 0; i < pts.size(); i++) { float nx = (pts.get(i)[0] - dest.left) / dest.width(), ny = (pts.get(i)[1] - dest.top) / dest.height(); m.path[2 * i] = nx; m.path[2 * i + 1] = ny; minX = Math.min(minX, nx); maxX = Math.max(maxX, nx); minY = Math.min(minY, ny); maxY = Math.max(maxY, ny); }
                        float hx = highlightThick * dest.height() / 2f / dest.width(), hy = highlightThick / 2f;
                        m.left = Math.max(0, minX - hx); m.right = Math.min(1, maxX + hx); m.top = Math.max(0, minY - hy); m.bottom = Math.min(1, maxY + hy);
                        listener.onHighlightCreated(m);
                    }
                } else if (Math.abs(currentX - startX) > 12) {
                    AnnotationStore.Mark m = new AnnotationStore.Mark();
                    m.page = page;
                    m.left = (Math.min(startX, currentX) - dest.left) / dest.width();
                    m.right = (Math.max(startX, currentX) - dest.left) / dest.width();
                    float centerY = (startY + currentY) / 2f;
                    float half = highlightHeight(dest) / 2f;
                    m.top = (Math.max(dest.top, centerY - half) - dest.top) / dest.height();
                    m.bottom = (Math.min(dest.bottom, centerY + half) - dest.top) / dest.height();
                    m.color = highlightColor;
                    listener.onHighlightCreated(m);
                }
                freePts.clear(); drawing = false; invalidate(); return true;
            }
            if (Math.hypot(e.getX() - startX, e.getY() - startY) < 20 && memoMode && dest.contains(e.getX(), e.getY())) {
                listener.onMemoPointRequested(page, (e.getX() - dest.left) / dest.width(),
                        (e.getY() - dest.top) / dest.height());
                return true;
            }
            if (Math.hypot(e.getX() - startX, e.getY() - startY) < 20 && outlineMode && dest.contains(e.getX(), e.getY())) {
                listener.onOutlinePointRequested(page, (e.getX() - dest.left) / dest.width(),
                        (e.getY() - dest.top) / dest.height());
                return true;
            }
            if (Math.hypot(e.getX() - startX, e.getY() - startY) < 20 && marks != null) {
                float nx = (e.getX() - dest.left) / dest.width();
                float ny = (e.getY() - dest.top) / dest.height();
                if(annotationStore!=null)for(int i=annotationStore.elements.size()-1;i>=0;i--){AnnotationStore.PageElement element=annotationStore.elements.get(i);if(element.page==page&&nx>=element.left&&nx<=element.right&&ny>=element.top&&ny<=element.bottom){if(selectable(element)){if(element==selectedElement)listener.onElementTapped(element);else{selectedElement=element;invalidate();}}else listener.onElementTapped(element);return true;}}
                for (int i = marks.size() - 1; i >= 0; i--) {
                    AnnotationStore.Mark m = marks.get(i);
                    if (m.page == page && nx >= m.left && nx <= m.right && ny >= m.top && ny <= m.bottom) {
                        listener.onMarkTapped(m); return true;
                    }
                }
            }

        }
        if(e.getAction()==MotionEvent.ACTION_CANCEL){edgeSwipe=bodySwipeCandidate=false;clearTextSelectionOverlay();}
        return true;
    }
}
