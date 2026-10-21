package com.hdlee.pdfnote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
 * Read-only reflowable reader for EPUB books. The reading controls (font size, line spacing, margins, font, theme) live only here,
 * so the document screens used for PDFs stay as they are. "필기" turns the book into a PDF with the same settings and hands it back
 * to the note editor.
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
    private LinearLayout topBar, bottomBar, panel, panelBody;
    private TextView titleView, progressView, loading;
    private final Deque<float[]> history = new ArrayDeque<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private String name = "", key = "";
    private int chapter;
    private float pendingRatio = -1f;
    private String pendingFragment;
    private boolean pageReady;
    private android.os.Handler ui;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new android.os.Handler(android.os.Looper.getMainLooper());
        settings = new EpubSettings(this);
        name = String.valueOf(getIntent().getStringExtra(EXTRA_NAME));
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
        book = opened;
        if (name.equals("null") || name.isEmpty()) name = book.title;
        key = Integer.toHexString((name + "|" + size).hashCode());
        titleView.setText(book.title.isEmpty() ? name : book.title);
        loading.setVisibility(View.GONE);
        int at = 0; float ratio = 0f;
        String saved = getSharedPreferences("epub_reader", MODE_PRIVATE).getString("pos:" + key, null);
        if (saved != null) try { String[] p = saved.split(":"); at = Integer.parseInt(p[0]); ratio = Float.parseFloat(p[1]); } catch (RuntimeException ignored) { }
        openChapter(Math.max(0, Math.min(book.spine.size() - 1, at)), ratio, null);
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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL); topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(button("‹", v -> onBackPressed()), new LinearLayout.LayoutParams(dp(48), -1));
        titleView = new TextView(this);
        titleView.setTextSize(15); titleView.setSingleLine(true); titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        topBar.addView(titleView, new LinearLayout.LayoutParams(0, -1, 1f));
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(button("목차", v -> showToc()), new LinearLayout.LayoutParams(-2, -1));
        topBar.addView(button("Aa", v -> togglePanel()), new LinearLayout.LayoutParams(-2, -1));
        topBar.addView(button("필기", v -> confirmConvert()), new LinearLayout.LayoutParams(-2, -1));
        column.addView(topBar, new LinearLayout.LayoutParams(-1, dp(52)));

        FrameLayout content = new FrameLayout(this);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true); ws.setAllowFileAccess(false); ws.setAllowContentAccess(false); ws.setBlockNetworkLoads(true);
        ws.setSupportZoom(false); ws.setBuiltInZoomControls(false); ws.setDomStorageEnabled(false);
        ws.setTextZoom(settings.zoom);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) { return serve(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return link(request.getUrl()); }
            @Override public void onPageFinished(WebView view, String url) { pageFinished(); }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Toast.makeText(EpubReaderActivity.this, "책 표시가 중단되었습니다. 다시 열어 주세요", Toast.LENGTH_LONG).show(); finish(); return true;
            }
        });
        web.setOnScrollChangeListener((v, x, y, ox, oy) -> updateProgress());
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        loading = new TextView(this);
        loading.setText("책을 불러오는 중…"); loading.setTextSize(16); loading.setGravity(Gravity.CENTER);
        content.addView(loading, new FrameLayout.LayoutParams(-1, -1));
        column.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL); bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.addView(button("‹ 이전", v -> step(-1)), new LinearLayout.LayoutParams(-2, -1));
        progressView = new TextView(this);
        progressView.setTextSize(13); progressView.setGravity(Gravity.CENTER); progressView.setSingleLine(true); progressView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottomBar.addView(progressView, new LinearLayout.LayoutParams(0, -1, 1f));
        bottomBar.addView(button("다음 ›", v -> step(1)), new LinearLayout.LayoutParams(-2, -1));
        column.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(46)));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setVisibility(View.GONE);
        panel.setPadding(dp(18), dp(10), dp(18), dp(14));
        panel.setClickable(true);
        panelBody = panel;
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pl.bottomMargin = dp(46);
        root.addView(panel, pl);
        buildPanel();
        setContentView(root);
        applyTheme();
    }

    private void buildPanel() {
        panelBody.removeAllViews();
        panelBody.addView(slider("글자 크기", (settings.zoom - EpubSettings.ZOOM_MIN) / EpubSettings.ZOOM_STEP, (EpubSettings.ZOOM_MAX - EpubSettings.ZOOM_MIN) / EpubSettings.ZOOM_STEP,
            p -> { settings.zoom = EpubSettings.ZOOM_MIN + p * EpubSettings.ZOOM_STEP; web.getSettings().setTextZoom(settings.zoom); return settings.zoom + "%"; }));
        panelBody.addView(slider("줄 간격", settings.line - EpubSettings.LINE_MIN, EpubSettings.LINE_MAX - EpubSettings.LINE_MIN,
            p -> { settings.line = EpubSettings.LINE_MIN + p; applyStyle(); return String.format(Locale.US, "%.1f", settings.lineSpacing()); }));
        panelBody.addView(slider("여백", settings.margin / EpubSettings.MARGIN_STEP, EpubSettings.MARGIN_MAX / EpubSettings.MARGIN_STEP,
            p -> { settings.margin = p * EpubSettings.MARGIN_STEP; applyStyle(); return settings.margin + ""; }));

        LinearLayout fonts = new LinearLayout(this);
        fonts.setOrientation(LinearLayout.HORIZONTAL); fonts.setGravity(Gravity.CENTER_VERTICAL);
        fonts.addView(label("글꼴"), new LinearLayout.LayoutParams(dp(64), -2));
        for (int i = 0; i < EpubSettings.FONT_NAMES.length; i++) {
            final int font = i;
            TextView chip = chip(EpubSettings.FONT_NAMES[i], settings.font == i);
            chip.setOnClickListener(v -> { settings.font = font; settings.save(); applyStyle(); buildPanel(); });
            fonts.addView(chip, chipParams());
        }
        panelBody.addView(fonts, new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout themes = new LinearLayout(this);
        themes.setOrientation(LinearLayout.HORIZONTAL); themes.setGravity(Gravity.CENTER_VERTICAL);
        themes.addView(label("배경"), new LinearLayout.LayoutParams(dp(64), -2));
        for (int i = 0; i < EpubSettings.THEME_NAMES.length; i++) {
            final int theme = i;
            TextView chip = chip(EpubSettings.THEME_NAMES[i], settings.theme == i);
            GradientDrawable swatch = new GradientDrawable();
            swatch.setCornerRadius(dp(10)); swatch.setColor(EpubSettings.THEME_BG[i]); swatch.setStroke(dp(settings.theme == i ? 2 : 1), settings.theme == i ? ACCENT : 0xFF9A9AA0);
            chip.setBackground(swatch); chip.setTextColor(EpubSettings.THEME_FG[i]);
            chip.setOnClickListener(v -> { settings.theme = theme; settings.save(); applyTheme(); applyStyle(); buildPanel(); });
            themes.addView(chip, chipParams());
        }
        panelBody.addView(themes, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    private interface Change { String apply(int progress); }

    private View slider(String name, int value, int max, Change change) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label(name), new LinearLayout.LayoutParams(dp(64), -2));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max); bar.setProgress(value);
        row.addView(bar, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView shown = label("");
        shown.setGravity(Gravity.END);
        row.addView(shown, new LinearLayout.LayoutParams(dp(52), -2));
        shown.setText(preview(change, value));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { if (fromUser) { shown.setText(change.apply(progress)); settings.save(); } }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        row.setLayoutParams(lp);
        return row;
    }

    /** The text next to a slider for its initial position, without applying the change. */
    private String preview(Change change, int value) {
        if (name == null) return "";
        int zoom = settings.zoom, line = settings.line, margin = settings.margin, font = settings.font;
        String text = change.apply(value);
        settings.zoom = zoom; settings.line = line; settings.margin = margin; settings.font = font;
        return text;
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
        topBar.setBackgroundColor(bar); bottomBar.setBackgroundColor(bar); panel.setBackgroundColor(bar);
        titleView.setTextColor(fg); progressView.setTextColor(fg); loading.setTextColor(fg);
        for (LinearLayout l : new LinearLayout[]{topBar, bottomBar})
            for (int i = 0; i < l.getChildCount(); i++) if (l.getChildAt(i) instanceof TextView && l.getChildAt(i) != titleView && l.getChildAt(i) != progressView) ((TextView) l.getChildAt(i)).setTextColor(fg);
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        flags = settings.dark() ? flags & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void togglePanel() {
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        buildPanel();
        panel.setVisibility(View.VISIBLE);
    }

    // ---- reading ----

    private void openChapter(int index, float ratio, String fragment) {
        if (book == null) return;
        chapter = Math.max(0, Math.min(book.spine.size() - 1, index));
        pendingRatio = ratio; pendingFragment = fragment; pageReady = false;
        Uri.Builder url = new Uri.Builder().scheme("https").authority(HOST).path("/" + book.spine.get(chapter));
        web.loadUrl(url.build().toString());
        updateProgress();
    }

    private void step(int direction) {
        if (book == null) return;
        int next = chapter + direction;
        if (next < 0 || next >= book.spine.size()) { Toast.makeText(this, direction < 0 ? "첫 부분입니다" : "마지막 부분입니다", Toast.LENGTH_SHORT).show(); return; }
        openChapter(next, 0f, null);
    }

    private void push() {
        if (history.size() > 50) history.removeLast();
        history.push(new float[]{chapter, ratio()});
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

    /** Links inside the book move between chapters; web links open in the browser; everything else is ignored. */
    private boolean link(Uri url) {
        if (HOST.equals(url.getHost()) && book != null) {
            String path = url.getPath() == null ? "" : url.getPath().replaceFirst("^/", "");
            int at = book.spineIndex(path);
            if (at == chapter) return false;
            if (at >= 0) { push(); openChapter(at, 0f, url.getFragment()); }
            return true;
        }
        if ("http".equals(url.getScheme()) || "https".equals(url.getScheme())) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, url)); } catch (RuntimeException e) { Toast.makeText(this, "링크를 열 앱이 없습니다", Toast.LENGTH_SHORT).show(); }
        }
        return true;
    }

    private void pageFinished() {
        if (book == null) return;
        pageReady = true;
        applyStyle();
        final float ratio = pendingRatio; final String fragment = pendingFragment;
        pendingRatio = -1f; pendingFragment = null;
        Runnable place = () -> {
            if (isDestroyed()) return;
            if (fragment != null && !fragment.isEmpty())
                web.evaluateJavascript("(function(){var f=" + JSONObject.quote(fragment) + ";var e=document.getElementById(f)||document.getElementsByName(f)[0];if(e)e.scrollIntoView();})()", null);
            else if (ratio > 0f) {
                float range = web.getContentHeight() * getResources().getDisplayMetrics().density - web.getHeight();
                if (range > 0) web.scrollTo(0, Math.round(ratio * range));
            }
            updateProgress();
        };
        ui.postDelayed(place, 120);
        ui.postDelayed(place, 700);   // once pictures have set the final height
    }

    private void applyStyle() {
        if (!pageReady || web == null) return;
        String script = "(function(){var s=document.getElementById('en-style');if(!s){s=document.createElement('style');s.id='en-style';(document.head||document.documentElement).appendChild(s);}s.textContent="
            + JSONObject.quote(settings.css()) + ";})()";
        web.evaluateJavascript(script, null);
    }

    private float ratio() {
        float range = web.getContentHeight() * getResources().getDisplayMetrics().density - web.getHeight();
        return range <= 0 ? 0f : Math.max(0f, Math.min(1f, web.getScrollY() / range));
    }

    private void updateProgress() {
        if (book == null || progressView == null) return;
        String label = book.chapterTitle(chapter);
        progressView.setText((label == null ? "" : label + " · ") + (chapter + 1) + "/" + book.spine.size() + " · " + Math.round(ratio() * 100) + "%");
    }

    private void savePosition() {
        if (book == null || key.isEmpty()) return;
        getSharedPreferences("epub_reader", MODE_PRIVATE).edit().putString("pos:" + key, chapter + ":" + ratio()).apply();
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
            if (target >= 0) { push(); openChapter(target, 0f, null); }
        }).setNegativeButton("닫기", null).show();
    }

    // ---- conversion to a PDF for note taking ----

    private void confirmConvert() {
        if (book == null) return;
        new AlertDialog.Builder(this).setTitle("PDF로 변환해 필기하기")
            .setMessage("지금의 글자 크기·줄 간격·여백·글꼴로 책을 PDF로 만들어 문서함에 저장하고, 필기 화면에서 엽니다.\n\n"
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
                    savePosition();
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

    @Override public void onBackPressed() {
        if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); return; }
        if (!history.isEmpty()) { float[] last = history.pop(); openChapter((int) last[0], last[1], null); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() { super.onPause(); savePosition(); settings.save(); }

    @Override protected void onDestroy() {
        cancelled.set(true);
        ui.removeCallbacksAndMessages(null);
        if (web != null) { ((ViewGroup) web.getParent()).removeView(web); web.stopLoading(); web.destroy(); web = null; }
        if (book != null) { book.close(); book = null; }
        super.onDestroy();
    }
}
