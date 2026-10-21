package com.hdlee.pdfnote;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen paginated reader for EPUB books. Pages have a fixed size: font size, line spacing and margins re-flow the text and change
 * the page count. The screen shows only the book; a tap in the middle brings up the menu (top bar, page slider, reading settings), taps
 * or swipes at the sides turn pages. The reading controls live only here, so the document screens used for PDFs keep their menus.
 * "필기" turns the book into a PDF with the same settings and hands it back to the note editor.
 */
public class EpubReaderActivity extends Activity {
    static final String EXTRA_NAME = "name", RESULT_PDF = "pdf";
    private static final String HOST = "epub.local";
    private static final int ACCENT = 0xFF387AFF;
    private static final int[] BAR = {0xFFF2F2F7, 0xFFE9DFC6, 0xFF2C2C2E, 0xFF111111};

    private EpubBook book;
    private EpubSettings settings;
    private WebView web;
    private FrameLayout root;
    private LinearLayout topBar, bottomBar, panel;
    private TextView titleView, infoView, footer, loading;
    private SeekBar slider;
    private final Deque<float[]> history = new ArrayDeque<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private String name = "", key = "";
    private int chapter, page, pages = 1, topInset, bottomInset;
    private int[] known;            // measured page count per chapter (0 = not measured yet in the current layout)
    private double sumPages, sumBytes;
    private float pendingRatio = -1f;
    private int pendingPage = -1;
    private boolean pendingLast;
    private String pendingFragment;
    private boolean pageReady, menuVisible;
    private long bookSize;
    private android.os.Handler ui;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new android.os.Handler(android.os.Looper.getMainLooper());
        settings = new EpubSettings(this);
        name = String.valueOf(getIntent().getStringExtra(EXTRA_NAME));
        if (Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        Uri source = getIntent().getData();
        if (source == null) { finish(); return; }
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "epub-reader");
                dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File copy = new File(dir, "book.epub");
                try (InputStream in = "file".equals(source.getScheme()) ? new java.io.FileInputStream(source.getPath()) : getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IOException("파일을 읽을 수 없습니다");
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                EpubBook opened = EpubBook.open(copy);
                runOnUiThread(() -> started(opened, copy.length()));
            } catch (Exception e) {
                String reason = e.getMessage();
                runOnUiThread(() -> { if (isFinishing()) return; Toast.makeText(this, "책을 열 수 없습니다: " + reason, Toast.LENGTH_LONG).show(); finish(); });
            }
        }, "epub-open").start();
    }

    private void started(EpubBook opened, long size) {
        if (isFinishing() || isDestroyed()) { opened.close(); return; }
        book = opened; bookSize = size;
        if (name.equals("null") || name.isEmpty()) name = book.title;
        key = EpubStore.key(name, size);
        known = new int[book.spine.size()];
        titleView.setText(book.title.isEmpty() ? name : book.title);
        loading.setVisibility(View.GONE);
        int at = 0; float ratio = 0f;
        String saved = getSharedPreferences("epub_reader", MODE_PRIVATE).getString("pos:" + key, null);
        if (saved != null) try { String[] p = saved.split(":"); at = Integer.parseInt(p[0]); ratio = Float.parseFloat(p[1]); } catch (RuntimeException ignored) { }
        new EpubStore(this).opened(key);
        openChapter(Math.max(0, Math.min(book.spine.size() - 1, at)), ratio, -1, false, null);
    }

    // ---- UI ----

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private TextView button(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label); t.setTextSize(15); t.setGravity(Gravity.CENTER); t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnClickListener(click);
        return t;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true); ws.setAllowFileAccess(false); ws.setAllowContentAccess(false); ws.setBlockNetworkLoads(true);
        ws.setSupportZoom(false); ws.setBuiltInZoomControls(false); ws.setDomStorageEnabled(false);
        ws.setTextZoom(settings.zoom);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return serve(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return link(request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) { pageFinished(); }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Toast.makeText(EpubReaderActivity.this, "책 표시가 중단되었습니다. 다시 열어 주세요", Toast.LENGTH_LONG).show(); finish(); return true;
            }
        });
        // Pages are turned by taps and swipes only; the WebView itself never scrolls by touch.
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) { tapped(e.getX(), e.getY()); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy) * 1.5f) { turn(dx < 0 ? 1 : -1); return true; }
                return false;
            }
        });
        web.setOnTouchListener((v, e) -> { gestures.onTouchEvent(e); return true; });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        footer = new TextView(this);
        footer.setTextSize(11); footer.setGravity(Gravity.CENTER); footer.setSingleLine(true);
        root.addView(footer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        loading = new TextView(this);
        loading.setText("책을 불러오는 중…"); loading.setTextSize(16); loading.setGravity(Gravity.CENTER);
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL); topBar.setGravity(Gravity.CENTER_VERTICAL); topBar.setVisibility(View.GONE);
        topBar.setElevation(dp(4));
        topBar.addView(button("‹", v -> leave()), new LinearLayout.LayoutParams(dp(48), dp(52)));
        titleView = new TextView(this);
        titleView.setTextSize(15); titleView.setSingleLine(true); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END); titleView.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, dp(52), 1f));
        topBar.addView(button("목차", v -> showToc()), new LinearLayout.LayoutParams(-2, dp(52)));
        topBar.addView(button("Aa", v -> togglePanel()), new LinearLayout.LayoutParams(-2, dp(52)));
        topBar.addView(button("필기", v -> confirmConvert()), new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL); bottom.setVisibility(View.GONE); bottom.setElevation(dp(4));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setVisibility(View.GONE);
        panel.setPadding(dp(18), dp(8), dp(18), dp(8));
        bottom.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        slider = new SeekBar(this);
        slider.setPadding(dp(22), dp(8), dp(22), dp(0));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser) infoView.setText((progress + 1) + " / " + totalPages() + "쪽"); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { goToGlobal(s.getProgress()); }
        });
        bottom.addView(slider, new LinearLayout.LayoutParams(-1, dp(36)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(button("‹ 이전 장", v -> step(-1)), new LinearLayout.LayoutParams(-2, dp(44)));
        infoView = new TextView(this);
        infoView.setTextSize(13); infoView.setGravity(Gravity.CENTER); infoView.setSingleLine(true); infoView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(infoView, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row.addView(button("다음 장 ›", v -> step(1)), new LinearLayout.LayoutParams(-2, dp(44)));
        bottom.addView(row, new LinearLayout.LayoutParams(-1, -2));
        bottomBar = bottom;
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottomPx;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = b.top; bottomPx = b.bottom;
            } else { top = insets.getStableInsetTop(); bottomPx = insets.getStableInsetBottom(); }
            if (top != topInset || bottomPx != bottomInset) { topInset = top; bottomInset = bottomPx; insetsChanged(); }
            return insets;
        });
        setContentView(root);
        buildPanel();
        applyTheme();
        setMenu(false);
    }

    private void insetsChanged() {
        topBar.setPadding(0, topInset, 0, 0);
        bottomBar.setPadding(0, 0, 0, bottomInset);
        FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) footer.getLayoutParams();
        fp.bottomMargin = bottomInset + dp(6);
        footer.setLayoutParams(fp);
        if (pageReady) relayout();
    }

    private int cssTop() { return Math.round(topInset / getResources().getDisplayMetrics().density) + 12; }
    private int cssBottom() { return Math.round(bottomInset / getResources().getDisplayMetrics().density) + 34; }

    private void buildPanel() {
        panel.removeAllViews();
        panel.addView(slider("글자 크기", (settings.zoom - EpubSettings.ZOOM_MIN) / EpubSettings.ZOOM_STEP, (EpubSettings.ZOOM_MAX - EpubSettings.ZOOM_MIN) / EpubSettings.ZOOM_STEP,
            p -> { settings.zoom = EpubSettings.ZOOM_MIN + p * EpubSettings.ZOOM_STEP; web.getSettings().setTextZoom(settings.zoom); return settings.zoom + "%"; }));
        panel.addView(slider("줄 간격", settings.line - EpubSettings.LINE_MIN, EpubSettings.LINE_MAX - EpubSettings.LINE_MIN,
            p -> { settings.line = EpubSettings.LINE_MIN + p; return String.format(Locale.US, "%.1f", settings.lineSpacing()); }));
        panel.addView(slider("여백", settings.margin / EpubSettings.MARGIN_STEP, EpubSettings.MARGIN_MAX / EpubSettings.MARGIN_STEP,
            p -> { settings.margin = p * EpubSettings.MARGIN_STEP; return settings.margin + ""; }));

        LinearLayout fonts = new LinearLayout(this);
        fonts.setOrientation(LinearLayout.HORIZONTAL); fonts.setGravity(Gravity.CENTER_VERTICAL);
        fonts.addView(label("글꼴"), new LinearLayout.LayoutParams(dp(64), -2));
        for (int i = 0; i < EpubSettings.FONT_NAMES.length; i++) {
            final int font = i;
            TextView chip = chip(EpubSettings.FONT_NAMES[i], settings.font == i);
            chip.setOnClickListener(v -> { settings.font = font; settings.save(); relayout(); buildPanel(); });
            fonts.addView(chip, chipParams());
        }
        panel.addView(fonts, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout themes = new LinearLayout(this);
        themes.setOrientation(LinearLayout.HORIZONTAL); themes.setGravity(Gravity.CENTER_VERTICAL);
        themes.addView(label("배경"), new LinearLayout.LayoutParams(dp(64), -2));
        for (int i = 0; i < EpubSettings.THEME_NAMES.length; i++) {
            final int theme = i;
            TextView chip = chip(EpubSettings.THEME_NAMES[i], false);
            GradientDrawable swatch = new GradientDrawable();
            swatch.setCornerRadius(dp(10)); swatch.setColor(EpubSettings.THEME_BG[i]); swatch.setStroke(dp(settings.theme == i ? 2 : 1), settings.theme == i ? ACCENT : 0xFF9A9AA0);
            chip.setBackground(swatch); chip.setTextColor(EpubSettings.THEME_FG[i]);
            chip.setOnClickListener(v -> { settings.theme = theme; settings.save(); applyTheme(); relayout(); buildPanel(); });
            themes.addView(chip, chipParams());
        }
        panel.addView(themes, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private interface Change { String apply(int progress); }

    private View slider(String label, int value, int max, Change change) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(label), new LinearLayout.LayoutParams(dp(64), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max); bar.setProgress(value);
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView shown = label("");
        shown.setGravity(Gravity.END);
        row.addView(shown, new LinearLayout.LayoutParams(dp(52), -2));
        shown.setText(change.apply(value));   // sets the value it was built from again; shows it
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser) shown.setText(change.apply(progress)); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { settings.save(); relayout(); }
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46)));
        return row;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(14); t.setTextColor(settings.foreground());
        return t;
    }

    private TextView chip(String text, boolean on) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(14); t.setGravity(Gravity.CENTER); t.setTextColor(on ? Color.WHITE : settings.foreground());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10)); bg.setColor(on ? ACCENT : 0x22808080);
        t.setBackground(bg);
        return t;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private void applyTheme() {
        int bar = BAR[settings.theme], fg = settings.foreground();
        root.setBackgroundColor(settings.background());
        web.setBackgroundColor(settings.background());
        topBar.setBackgroundColor(bar); bottomBar.setBackgroundColor(bar);
        titleView.setTextColor(fg); infoView.setTextColor(fg); loading.setTextColor(fg);
        footer.setTextColor((fg & 0x00FFFFFF) | 0x99000000);
        for (LinearLayout l : new LinearLayout[]{topBar, (LinearLayout) bottomBar.getChildAt(2)})
            for (int i = 0; i < l.getChildCount(); i++) if (l.getChildAt(i) instanceof TextView && l.getChildAt(i) != titleView && l.getChildAt(i) != infoView) ((TextView) l.getChildAt(i)).setTextColor(fg);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(settings.dark() ? 0 : light, light);
            }
        }
    }

    /** Menu hidden = the book fills the whole screen (system bars hidden too); shown = top bar, page slider and system bars. */
    private void setMenu(boolean visible) {
        menuVisible = visible;
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) panel.setVisibility(View.GONE);
        footer.setVisibility(visible ? View.INVISIBLE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (visible) c.show(WindowInsets.Type.systemBars()); else c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            int base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            int flags = visible ? base : base | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (!settings.dark()) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (visible) updateLabels();
    }

    private void togglePanel() { panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }

    // ---- touch ----

    private void tapped(float x, float y) {
        if (book == null) return;
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        float density = getResources().getDisplayMetrics().density;
        web.evaluateJavascript("(function(x,y){var e=document.elementFromPoint(x,y);var a=e&&e.closest?e.closest('a[href]'):null;return a?a.href:'';})(" + (x / density) + "," + (y / density) + ")", value -> {
            String href = value == null ? "" : value.replace("\"", "");
            if (!href.isEmpty() && !href.equals("null") && link(Uri.parse(href.replace("\\u0026", "&")))) return;
            float w = web.getWidth();
            if (menuVisible) setMenu(false);
            else if (x < w * 0.3f) turn(-1);
            else if (x > w * 0.7f) turn(1);
            else setMenu(true);
        });
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (book != null && !menuVisible) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { turn(1); return true; }
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) { turn(-1); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- pages ----

    private void turn(int direction) {
        if (book == null || !pageReady) return;
        int next = page + direction;
        if (next >= 0 && next < pages) { goToPage(next); return; }
        int chapterNext = chapter + direction;
        if (chapterNext < 0 || chapterNext >= book.spine.size()) { Toast.makeText(this, direction < 0 ? "첫 쪽입니다" : "마지막 쪽입니다", Toast.LENGTH_SHORT).show(); return; }
        openChapter(chapterNext, 0f, -1, direction < 0, null);
    }

    private void goToPage(int p) {
        page = Math.max(0, Math.min(pages - 1, p));
        web.evaluateJavascript("window.scrollTo(" + page + "*window.innerWidth,0)", null);
        updateLabels();
    }

    private void step(int direction) {
        if (book == null) return;
        int next = chapter + direction;
        if (next < 0 || next >= book.spine.size()) { Toast.makeText(this, direction < 0 ? "첫 부분입니다" : "마지막 부분입니다", Toast.LENGTH_SHORT).show(); return; }
        openChapter(next, 0f, -1, false, null);
    }

    private int pagesOf(int i) {
        if (known[i] > 0) return known[i];
        if (sumBytes <= 0) return 1;
        return Math.max(1, (int) Math.round(book.size(book.spine.get(i)) * sumPages / sumBytes));
    }

    private int totalPages() { int t = 0; for (int i = 0; i < known.length; i++) t += pagesOf(i); return Math.max(1, t); }

    private int globalPage() { int before = 0; for (int i = 0; i < chapter; i++) before += pagesOf(i); return before + page; }

    private void goToGlobal(int g) {
        int before = 0;
        for (int i = 0; i < known.length; i++) {
            int n = pagesOf(i);
            if (g < before + n || i == known.length - 1) {
                if (i == chapter) goToPage(g - before);
                else { push(); openChapter(i, 0f, Math.max(0, Math.min(n - 1, g - before)), false, null); }
                return;
            }
            before += n;
        }
    }

    private void updateLabels() {
        if (book == null) return;
        int total = totalPages(), here = globalPage();
        footer.setText((here + 1) + " / " + total + "쪽");
        if (menuVisible) {
            String label = book.chapterTitle(chapter);
            infoView.setText((here + 1) + " / " + total + "쪽" + (label == null ? "" : " · " + label));
            slider.setMax(Math.max(1, total - 1)); slider.setProgress(Math.min(here, Math.max(1, total - 1)));
        }
    }

    // ---- chapters ----

    private void openChapter(int index, float ratio, int pageNumber, boolean last, String fragment) {
        if (book == null) return;
        chapter = Math.max(0, Math.min(book.spine.size() - 1, index));
        pendingRatio = ratio; pendingPage = pageNumber; pendingLast = last; pendingFragment = fragment; pageReady = false; page = 0; pages = 1;
        Uri.Builder url = new Uri.Builder().scheme("https").authority(HOST).path("/" + book.spine.get(chapter));
        web.loadUrl(url.build().toString());
    }

    private void push() {
        if (history.size() > 50) history.removeLast();
        history.push(new float[]{chapter, page / (float) Math.max(1, pages)});
    }

    private WebResourceResponse serve(Uri url) {
        if (book == null || !"https".equals(url.getScheme()) || !HOST.equals(url.getHost()) || url.getPath() == null) return missing();
        String path = url.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        byte[] data = book.read(path);
        if (data == null) return missing();
        return new WebResourceResponse(EpubBook.mime(path), "UTF-8", new ByteArrayInputStream(data));
    }

    private static WebResourceResponse missing() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    /** Links inside the book move between chapters; web links open in the browser; everything else is ignored. Returns true when handled. */
    private boolean link(Uri url) {
        if (HOST.equals(url.getHost()) && book != null) {
            String path = url.getPath() == null ? "" : url.getPath().replaceFirst("^/", "");
            int at = book.spineIndex(path);
            if (at == chapter) {
                if (url.getFragment() != null) jumpToFragment(url.getFragment(), true);
                return true;
            }
            if (at >= 0) { push(); openChapter(at, 0f, -1, false, url.getFragment()); }
            return true;
        }
        if ("http".equals(url.getScheme()) || "https".equals(url.getScheme())) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (RuntimeException e) { Toast.makeText(this, "링크를 열 앱이 없습니다", Toast.LENGTH_SHORT).show(); }
            return true;
        }
        return false;
    }

    private void jumpToFragment(String fragment, boolean remember) {
        web.evaluateJavascript("(function(f){var e=document.getElementById(f)||document.getElementsByName(f)[0];return e?Math.floor((e.getBoundingClientRect().left+window.scrollX)/window.innerWidth):-1;})(" + JSONObject.quote(fragment) + ")", v -> {
            try { int p = Integer.parseInt(v.trim()); if (p >= 0) { if (remember) push(); goToPage(p); } } catch (RuntimeException ignored) { }
        });
    }

    private void pageFinished() {
        if (book == null) return;
        pageReady = true;
        applyStyle();
        for (int delay : new int[]{80, 500, 1500}) ui.postDelayed(this::measure, delay);   // again once pictures have their final size
    }

    private void applyStyle() {
        if (!pageReady || web == null) return;
        String script = "(function(){var s=document.getElementById('en-style');if(!s){s=document.createElement('style');s.id='en-style';(document.head||document.documentElement).appendChild(s);}s.textContent="
            + JSONObject.quote(settings.css(cssTop(), cssBottom())) + ";})()";
        web.evaluateJavascript(script, null);
    }

    /** Re-flows the current chapter after a setting, rotation or inset change and keeps the reading position. */
    private void relayout() {
        if (book == null || !pageReady) return;
        float ratio = pages <= 1 ? 0f : page / (float) pages;
        java.util.Arrays.fill(known, 0); sumPages = 0; sumBytes = 0;
        pendingRatio = ratio; pendingPage = -1; pendingLast = false; pendingFragment = null;
        applyStyle();
        for (int delay : new int[]{120, 600}) ui.postDelayed(this::measure, delay);
    }

    /** Reads the number of pages the chapter has now and moves to the page that was asked for. */
    private void measure() {
        if (isDestroyed() || book == null || !pageReady) return;
        final int my = chapter;
        web.evaluateJavascript("Math.max(1,Math.round(document.scrollingElement.scrollWidth/window.innerWidth))", v -> {
            if (isDestroyed() || my != chapter) return;
            int count;
            try { count = Math.max(1, Integer.parseInt(v.trim())); } catch (RuntimeException e) { return; }
            float anchor = pages <= 1 ? 0f : page / (float) pages;
            boolean first = known[my] == 0;
            if (first) { sumPages += count; sumBytes += Math.max(1, book.size(book.spine.get(my))); }
            else if (known[my] != count) { sumPages += count - known[my]; }
            known[my] = count; pages = count;
            if (pendingFragment != null) { String f = pendingFragment; pendingFragment = null; pendingRatio = -1f; jumpToFragment(f, false); }
            else if (pendingPage >= 0) { page = Math.min(count - 1, pendingPage); pendingPage = -1; pendingRatio = -1f; goToPage(page); }
            else if (pendingLast) { pendingLast = false; pendingRatio = -1f; goToPage(count - 1); }
            else if (pendingRatio >= 0f) { goToPage((int) Math.floor(pendingRatio * count + 1e-4)); pendingRatio = -1f; }
            else if (count != pages || anchor > 0f) goToPage(Math.min(count - 1, Math.round(anchor * count)));
            updateLabels();
            save();
        });
    }

    private void save() {
        if (book == null || key.isEmpty()) return;
        float ratio = pages <= 1 ? 0f : page / (float) pages;
        int percent = Math.round(100f * (globalPage() + 1) / totalPages());
        getSharedPreferences("epub_reader", MODE_PRIVATE).edit().putString("pos:" + key, chapter + ":" + ratio).apply();
        new EpubStore(this).info(key, book.title.isEmpty() ? name : book.title, Math.min(100, percent));
    }

    private void showToc() {
        if (book == null) return;
        int count = book.toc.isEmpty() ? book.spine.size() : book.toc.size();
        String[] items = new String[count];
        String current = book.chapterTitle(chapter);
        for (int i = 0; i < count; i++) {
            String title = book.toc.isEmpty() ? (i + 1) + "." : book.toc.get(i).title;
            boolean here = book.toc.isEmpty() ? i == chapter : title.equals(current) && book.spineIndex(book.toc.get(i).path) <= chapter;
            items[i] = (here ? "● " : "    ") + title;
        }
        new AlertDialog.Builder(this).setTitle("목차").setItems(items, (d, which) -> {
            int target = book.toc.isEmpty() ? which : book.spineIndex(book.toc.get(which).path);
            if (target >= 0) { push(); openChapter(target, 0f, -1, false, null); setMenu(false); }
        }).setNegativeButton("닫기", null).show();
    }

    // ---- conversion to a PDF for note taking ----

    private void confirmConvert() {
        if (book == null) return;
        new AlertDialog.Builder(this).setTitle("PDF로 변환해 필기하기")
            .setMessage("지금의 글자 크기·줄 간격·여백·글꼴로 책을 PDF로 만들어 문서함에 저장하고, 필기 화면에서 엽니다. 책은 EPUB 책 목록에 그대로 남습니다.\n\n"
                + "나중에 설정을 바꿔 다시 변환하면 새 PDF가 따로 만들어지며, 이미 쓴 필기는 새 PDF로 옮겨지지 않습니다. 표나 복잡한 그림이 있는 책은 읽기 화면과 모양이 다를 수 있습니다.")
            .setPositiveButton("변환", (d, w) -> convert()).setNegativeButton("취소", null).show();
    }

    private void convert() {
        TextView status = new TextView(this);
        status.setText("PDF를 만드는 중…"); status.setTextSize(16); status.setPadding(dp(24), dp(20), dp(24), dp(20));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("PDF로 변환").setView(status).setCancelable(false).setNegativeButton("취소", null).create();
        progress.show();
        cancelled.set(false);
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { cancelled.set(true); progress.dismiss(); });
        final EpubBook source = book;
        final String base = LibraryRepository.folderName(name.replaceFirst("(?i)\\.epub$", ""), "책");
        new Thread(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("epub-", ".pdf", getCacheDir());
                EpubPdf.write(source, settings, temp, (done, total) -> runOnUiThread(() -> status.setText("PDF를 만드는 중… " + done + "/" + total)), cancelled);
                if (cancelled.get()) throw new IOException("취소됨");
                LibraryRepository library = new LibraryRepository(this);
                File saved = library.importPdf(Uri.fromFile(temp), base + ".pdf", library.root);
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (isFinishing() || isDestroyed()) return;
                    save();
                    setResult(RESULT_OK, new Intent().putExtra(RESULT_PDF, saved.getAbsolutePath()));
                    finish();
                });
            } catch (Throwable e) {
                String reason = e.getMessage();
                runOnUiThread(() -> { progress.dismiss(); if (!cancelled.get() && !isFinishing() && !isDestroyed()) Toast.makeText(this, "PDF 변환 실패: " + reason, Toast.LENGTH_LONG).show(); });
            } finally { if (temp != null) temp.delete(); }
        }, "epub-pdf").start();
    }

    // ---- lifecycle ----

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        ui.postDelayed(this::relayout, 200);
    }

    /** The ‹ button: back to the position before the last link / contents jump, else out of the book. */
    private void leave() {
        if (!history.isEmpty()) { float[] last = history.pop(); openChapter((int) last[0], last[1], -1, false, null); return; }
        finish();
    }

    @Override public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        if (menuVisible && history.isEmpty()) { setMenu(false); return; }
        if (!history.isEmpty()) { float[] last = history.pop(); openChapter((int) last[0], last[1], -1, false, null); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); save(); settings.save(); }

    @Override protected void onDestroy() {
        cancelled.set(true);
        ui.removeCallbacksAndMessages(null);
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.stopLoading(); web.destroy(); web = null; }
        if (book != null) { book.close(); book = null; }
        super.onDestroy();
    }
}
