package com.hdlee.pdfnote;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The AI menu: 요약 · 제목·태그 제안 · 맞춤법 교정 · 번역, all running on the phone with Gemini Nano.
 * The long jobs run on a worker thread behind a progress dialog with a cancel button.
 */
final class AiTools {
    /** What this feature needs from the editor. */
    interface Host {
        Uri uri();
        String title();
        int pageCount();
        int currentPage();
        LibraryRepository library();
        /** Writes pending ink and notes to disk before the document is read. */
        void flush();
        /** Opens [made] next to the document that is on screen, in split view (original left, new document right). */
        void openBeside(File made);
        /** Renames the open document (asks nothing; the caller already confirmed). */
        void rename(String title);
    }

    private interface Job { void run(AtomicBoolean canceled, ProgressDialog progress) throws Exception; }

    private final Activity activity;
    private final Host host;

    AiTools(Activity activity, Host host) { this.activity = activity; this.host = host; }

    /** Shown instead of the menu on a phone without Gemini Nano. */
    static void explainUnsupported(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("AI 기능을 쓸 수 없습니다")
            .setMessage("요약 · 제목/태그 제안 · 맞춤법 교정 · 번역은 기기에 내장된 Gemini Nano로 동작합니다.\n\n이 기기는 Gemini Nano를 지원하지 않거나 아직 AI 구성 요소가 준비되지 않았습니다. "
                + "지원 기기(예: 최신 Pixel, Galaxy S 시리즈)에서 부트로더가 잠겨 있고 ‘Google AICore’가 최신일 때 사용할 수 있습니다. 나머지 기능은 그대로 쓸 수 있습니다.")
            .setPositiveButton("확인", null).show();
    }

    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }
    private static String stem(String name) { return name.replaceFirst("(?i)\\.pdf$", ""); }

    // ------------------------------------------------------------------ plumbing

    /** Runs [job] on a worker thread behind a cancelable progress dialog; the first thing it does is get Gemini Nano ready. */
    private void run(String title, Job job) {
        host.flush();
        final AtomicBoolean canceled = new AtomicBoolean();
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(title);
        progress.setMessage("AI를 준비하는 중…");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "취소", (d, w) -> canceled.set(true));
        progress.show();
        activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final boolean[] resumed = {true};
        final android.app.Application.ActivityLifecycleCallbacks lifecycle = new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) { if (a == activity) resumed[0] = true; }
            @Override public void onActivityPaused(Activity a) { if (a == activity) resumed[0] = false; }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        AiAssistant.foreground = () -> resumed[0];
        AiAssistant.notice = message -> say(progress, message);
        final Runnable cleanup = () -> {
            AiAssistant.foreground = null;
            AiAssistant.notice = null;
            activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
            activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        };
        new Thread(() -> {
            String problem = AiAssistant.prepare((kb, unused) -> activity.runOnUiThread(() -> progress.setMessage("AI 모델을 내려받는 중… " + (kb / 1024) + " MB")));
            if (problem != null) { finish(progress, () -> { cleanup.run(); toast(problem); }); return; }
            try {
                job.run(canceled, progress);
            } catch (Throwable error) {
                final String message = AiAssistant.explain(error);
                final boolean cancel = error instanceof AiAssistant.Canceled;
                finish(progress, () -> { cleanup.run(); if (cancel) toast("취소했습니다"); else showFailure(message); });
                return;
            }
            activity.runOnUiThread(cleanup);
        }, "ai-job").start();
    }

    /** A failed AI job gets a dialog (a toast is gone before it can be read). */
    private void showFailure(String message) {
        new AlertDialog.Builder(activity).setTitle("AI 작업을 마치지 못했습니다").setMessage(message).setPositiveButton("확인", null).show();
    }

    private void finish(ProgressDialog progress, Runnable then) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            progress.dismiss();
            if (then != null) then.run();
        });
    }

    private void say(ProgressDialog progress, String message) { activity.runOnUiThread(() -> progress.setMessage(message)); }

    /** The text of pages [first]..[last], one string per page. */
    private List<String> read(ProgressDialog progress, AtomicBoolean canceled, int first, int last) throws Exception {
        say(progress, "문서에서 글자를 읽는 중…");
        List<String> texts = SearchScanner.pageTexts(activity, host.uri(), first, last, canceled, (done, total) -> say(progress, "글자를 읽는 중… " + (done + 1) + "/" + total + "쪽"));
        if (canceled.get()) throw new AiAssistant.Canceled();
        return texts;
    }

    private static boolean blank(List<String> texts) {
        for (String t : texts) if (!t.trim().isEmpty()) return false;
        return true;
    }

    private static String joinPages(List<String> texts) {
        StringBuilder all = new StringBuilder();
        for (String t : texts) { if (t.trim().isEmpty()) continue; if (all.length() > 0) all.append("\n\n"); all.append(t.trim()); }
        return all.toString();
    }

    private void copy(String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("Everynote AI", text)); Toast.makeText(activity, "복사했습니다", Toast.LENGTH_SHORT).show(); }
    }

    private void showText(String title, String text, String note) {
        ScrollView scroll = new ScrollView(activity);
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xFF1C1C1E);
        view.setLineSpacing(0, 1.25f);
        view.setTextIsSelectable(true);
        int pad = Math.round(18 * activity.getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        scroll.addView(view);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("닫기", null)
            .setNegativeButton("복사", (d, w) -> copy(text));
        if (note != null) builder.setMessage(note);
        builder.show();
    }

    /** A free file name beside the open document for a new PDF made from it. */
    private File newTarget(String suffix) throws java.io.IOException {
        File self = host.uri() != null && "file".equals(host.uri().getScheme()) && host.uri().getPath() != null ? new File(host.uri().getPath()) : null;
        File folder = self != null && host.library().managed(self) ? self.getParentFile() : host.library().root;
        return NotebookFiles.unique(folder, NotebookFiles.pdfName(stem(host.title()) + " (" + suffix + ")"));
    }

    /** Writes [pages] as a new PDF beside the open document and returns it (call from a worker thread). */
    private File makeDocument(String suffix, List<String> pages, List<String> labels) throws Exception {
        File target = newTarget(suffix);
        TextPdf.write(target, pages, labels);
        return target;
    }

    private static List<String> pageLabels(int first, int count) {
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < count; i++) labels.add("원문 " + (first + i + 1) + "쪽");
        return labels;
    }

    /** Asks 현재 페이지 / 문서 전체, then calls [then] with the first and last page (0-based). */
    private void chooseScope(String title, java.util.function.BiConsumer<Integer, Integer> then) {
        final int total = host.pageCount(), current = host.currentPage();
        if (total <= 1) { then.accept(0, 0); return; }
        new AlertDialog.Builder(activity).setTitle(title).setItems(new CharSequence[]{"현재 페이지 (" + (current + 1) + "쪽)", "문서 전체 (" + total + "쪽)"},
            (d, which) -> { if (which == 0) then.accept(current, current); else then.accept(0, total - 1); }).show();
    }

    // ------------------------------------------------------------------ features

    /** 문서 요약. */
    void summarize() {
        run("문서 요약", (canceled, progress) -> {
            List<String> texts = read(progress, canceled, 0, host.pageCount() - 1);
            if (blank(texts)) { finish(progress, () -> toast("요약할 글자를 찾지 못했습니다")); return; }
            say(progress, "요약하는 중…");
            String summary = AiAssistant.summarize(joinPages(texts), canceled, (done, total) -> say(progress, "요약하는 중… " + done + "/" + total));
            if (summary.isEmpty()) { finish(progress, () -> toast("요약을 만들지 못했습니다. 다시 시도하세요")); return; }
            finish(progress, () -> showText("문서 요약", summary, null));
        });
    }

    /** 제목·태그 제안. */
    void suggestTitleAndTags() {
        run("제목·태그 제안", (canceled, progress) -> {
            List<String> texts = new ArrayList<>();
            int total = host.pageCount();
            for (int page = 0; page < total && joinPages(texts).length() < AiAssistant.SUMMARY_CHUNK && page < 6; page++) texts.addAll(read(progress, canceled, page, page));
            if (blank(texts)) { finish(progress, () -> toast("글자를 찾지 못했습니다")); return; }
            say(progress, "제목과 태그를 생각하는 중…");
            AiAssistant.TitleTags result = AiAssistant.suggestTitleAndTags(joinPages(texts), canceled);
            finish(progress, () -> showTitleAndTags(result));
        });
    }

    private void showTitleAndTags(AiAssistant.TitleTags result) {
        StringBuilder message = new StringBuilder();
        if (!result.title.isEmpty()) message.append("제목\n").append(result.title).append("\n\n");
        if (!result.tags.isEmpty()) message.append("태그\n#").append(android.text.TextUtils.join("  #", result.tags)).append("\n\n");
        message.append("제목을 적용하면 문서 이름이 바뀝니다. 태그는 문서함 이름 검색에서도 찾아집니다.");
        final String title = result.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        final String shortTitle = title.length() > 60 ? title.substring(0, 60).trim() : title;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("제목·태그 제안").setMessage(message.toString()).setNegativeButton("닫기", null);
        builder.setPositiveButton("모두 적용", (d, w) -> { saveTags(result.tags); if (!shortTitle.isEmpty()) host.rename(shortTitle); });
        if (!result.tags.isEmpty()) builder.setNeutralButton("태그만 저장", (d, w) -> saveTags(result.tags));
        builder.show();
    }

    private void saveTags(List<String> tags) {
        Uri uri = host.uri();
        if (uri == null || tags.isEmpty()) return;
        File file = "file".equals(uri.getScheme()) && uri.getPath() != null ? new File(uri.getPath()) : null;
        if (file == null || !host.library().managed(file)) { toast("문서함에 저장된 문서에만 태그를 붙일 수 있습니다"); return; }
        List<String> merged = host.library().tags(file);
        for (String t : tags) if (!merged.contains(t)) merged.add(t);
        host.library().tags(file, merged);
        toast("태그를 저장했습니다: #" + android.text.TextUtils.join(" #", tags));
    }

    /** 맞춤법 교정: the corrected text becomes a new document (the original is never changed). */
    void proofread() {
        chooseScope("맞춤법 교정", (first, last) -> run("맞춤법 교정", (canceled, progress) -> {
            List<String> texts = read(progress, canceled, first, last);
            if (blank(texts)) { finish(progress, () -> toast("교정할 글자를 찾지 못했습니다")); return; }
            List<String> fixed = new ArrayList<>();
            int changed = 0;
            for (int i = 0; i < texts.size(); i++) {
                say(progress, "교정하는 중… " + (i + 1) + "/" + texts.size() + "쪽");
                String original = texts.get(i);
                String corrected = original.trim().isEmpty() ? "" : AiAssistant.proofread(original, canceled);
                if (corrected.isEmpty()) corrected = original;
                if (!corrected.equals(original)) changed++;
                fixed.add(corrected);
            }
            if (changed == 0) { finish(progress, () -> toast("고칠 곳을 찾지 못했습니다")); return; }
            final int pages = changed;
            final String all = joinPages(fixed);
            finish(progress, () -> new AlertDialog.Builder(activity).setTitle("맞춤법 교정 결과")
                .setMessage(pages + "쪽에서 고칠 곳을 찾았습니다. 원본은 그대로 두고, 교정한 글을 새 문서로 만들어 원문과 나란히 보여 드릴까요?")
                .setPositiveButton("새 문서로 보기", (d, w) -> saveAndOpen("교정본", fixed, pageLabels(first, fixed.size())))
                .setNeutralButton("교정문 복사", (d, w) -> copy(all)).setNegativeButton("닫기", null).show());
        }));
    }

    /** 번역: pick the language, then the pages; the translation is written over the original pages (pictures and layout stay) as a new document shown beside the original. */
    void translate() {
        new AlertDialog.Builder(activity).setTitle("번역할 언어").setItems(AiAssistant.LANGUAGES, (d, language) ->
            chooseScope("번역 범위", (first, last) -> run("문서 번역", (canceled, progress) -> {
                File target = newTarget("번역-" + AiAssistant.LANGUAGES[language]);
                LayoutTranslator.translate(activity, host.uri(), first, last, target, AiAssistant.LANGUAGE_NAMES[language], canceled,
                    (done, total) -> say(progress, done >= total ? "번역본을 저장하는 중…" : AiAssistant.LANGUAGES[language] + "(으)로 번역하는 중… " + (done + 1) + "/" + total + "쪽"));
                finish(progress, () -> { toast("번역본 ‘" + stem(target.getName()) + "’을 만들었습니다"); host.openBeside(target); });
            }))).show();
    }

    private void saveAndOpen(String suffix, List<String> pages, List<String> labels) {
        host.flush();
        final ProgressDialog progress = ProgressDialog.show(activity, "", "새 문서를 만드는 중…", true, false);
        new Thread(() -> {
            try {
                File made = makeDocument(suffix, pages, labels);
                finish(progress, () -> { toast("‘" + stem(made.getName()) + "’을 만들었습니다"); host.openBeside(made); });
            } catch (Throwable error) {
                finish(progress, () -> toast("문서를 만들지 못했습니다: " + error.getMessage()));
            }
        }, "ai-save").start();
    }
}
