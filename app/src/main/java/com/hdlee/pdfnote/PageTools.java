package com.hdlee.pdfnote;

import android.app.Activity;
import android.app.ProgressDialog;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Merge several library documents into one, and extract some pages of a document into a new file. */
final class PageTools {
    private PageTools() {}

    private static int dp(Activity a, float v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
    private static String stem(File f) { String n = f.getName(); return n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? n.substring(0, n.length() - 4) : n; }

    /** "1-3, 5, 8-" -> 0-based pages in the order typed, duplicates dropped. Throws IllegalArgumentException with a Korean message. */
    static int[] parseRanges(String text, int total) {
        Set<Integer> out = new LinkedHashSet<>();
        String[] parts = text.trim().split("[,\\s]+");
        if (text.trim().isEmpty()) throw new IllegalArgumentException("뽑을 페이지를 입력하세요 (예: 1-3, 5)");
        for (String part : parts) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d*)\\s*[-~]\\s*(\\d*)$").matcher(part);
            int a, b;
            if (m.matches()) { a = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1)); b = m.group(2).isEmpty() ? total : Integer.parseInt(m.group(2)); }
            else if (part.matches("\\d{1,6}")) { a = b = Integer.parseInt(part); }
            else throw new IllegalArgumentException("‘" + part + "’은(는) 올바른 페이지 표기가 아닙니다");
            if (a < 1 || b < 1 || a > total || b > total) throw new IllegalArgumentException("페이지는 1~" + total + " 사이여야 합니다");
            int step = a <= b ? 1 : -1;
            for (int p = a; step > 0 ? p <= b : p >= b; p += step) out.add(p - 1);
        }
        int[] result = new int[out.size()];
        int i = 0;
        for (int p : out) result[i++] = p;
        return result;
    }

    private static void run(Activity activity, LibraryRepository repository, File destination, String title, List<File> files, List<int[]> pages, Runnable refresh, java.util.function.Consumer<File> done) {
        ProgressDialog progress = ProgressDialog.show(activity, "", "새 문서를 만드는 중…", true, false);
        new Thread(() -> {
            try {
                File made = repository.compose(destination, title, files, pages);
                int count = 0;
                for (int[] p : pages) count += p.length;
                final int total = count;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    progress.dismiss();
                    Toast.makeText(activity, "새 문서 ‘" + stem(made) + "’ (" + total + "쪽)를 만들었습니다", Toast.LENGTH_LONG).show();
                    if (refresh != null) refresh.run();
                    if (done != null) done.accept(made);
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    progress.dismiss();
                    Toast.makeText(activity, "문서를 만들지 못했습니다: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "compose-pdf").start();
    }

    private static EditText field(Activity activity, String text) {
        EditText e = new EditText(activity);
        e.setSingleLine();
        e.setText(text);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private static TextView caption(Activity activity, String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(0xFF848487);
        t.setPadding(dp(activity, 4), dp(activity, 10), 0, dp(activity, 2));
        return t;
    }

    /** [files] in the order shown; the order can be changed in the dialog. */
    static void merge(Activity activity, LibraryRepository repository, List<File> files, File destination, Runnable refresh) {
        if (files.size() < 2) { Toast.makeText(activity, "합칠 문서를 두 개 이상 선택하세요", Toast.LENGTH_SHORT).show(); return; }
        final List<File> order = new ArrayList<>(files);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 4));
        TextView hint = caption(activity, "아래 순서대로 이어 붙입니다. 원본 문서는 그대로 남고 필기도 함께 옮겨집니다.");
        box.addView(hint);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        box.addView(caption(activity, "새 문서 이름"));
        EditText name = field(activity, stem(order.get(0)) + " 합본");
        box.addView(name);
        final Runnable[] redraw = new Runnable[1];
        redraw[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int at = i;
                LinearLayout row = new LinearLayout(activity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView no = new TextView(activity);
                no.setText(String.valueOf(i + 1));
                no.setTextColor(0xFF848487);
                no.setGravity(Gravity.CENTER);
                row.addView(no, new LinearLayout.LayoutParams(dp(activity, 26), -2));
                TextView label = new TextView(activity);
                label.setText(stem(order.get(i)));
                label.setTextSize(15);
                label.setTextColor(0xFF010102);
                label.setSingleLine();
                label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(mover(activity, "▲", "위로", i > 0, () -> { Collections.swap(order, at, at - 1); redraw[0].run(); }), new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
                row.addView(mover(activity, "▼", "아래로", i < order.size() - 1, () -> { Collections.swap(order, at, at + 1); redraw[0].run(); }), new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 40)));
                list.addView(row, new LinearLayout.LayoutParams(-1, dp(activity, 44)));
            }
        };
        redraw[0].run();
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("문서 합치기").setView(scroll).setPositiveButton("합치기", null).setNegativeButton("취소", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("이름을 입력하세요"); return; }
            try { NotebookFiles.name(title); } catch (Exception error) { name.setError(error.getMessage()); return; }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    List<int[]> pages = new ArrayList<>();
                    for (File f : order) { int n = repository.pageCount(f); int[] all = new int[n]; for (int k = 0; k < n; k++) all[k] = k; pages.add(all); }
                    activity.runOnUiThread(() -> run(activity, repository, destination, title, order, pages, refresh, null));
                } catch (Exception error) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "PDF를 읽을 수 없습니다 (암호가 걸린 문서일 수 있습니다)", Toast.LENGTH_LONG).show());
                }
            }, "page-count").start();
        }));
        dialog.show();
    }

    private static TextView mover(Activity activity, String glyph, String label, boolean enabled, Runnable action) {
        TextView t = new TextView(activity);
        t.setText(glyph);
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(label);
        t.setTextColor(enabled ? 0xFF387AFF : 0xFFCFCFD4);
        if (enabled) t.setOnClickListener(v -> action.run());
        return t;
    }

    /** Asks for page numbers and saves them as a new library document. [defaultPages] is text such as "3" or "2-4". */
    static void extract(Activity activity, LibraryRepository repository, File file, File destination, String defaultPages, Runnable refresh, java.util.function.Consumer<File> done) {
        new Thread(() -> {
            final int total;
            try { total = repository.pageCount(file); }
            catch (Exception error) { activity.runOnUiThread(() -> Toast.makeText(activity, "PDF를 읽을 수 없습니다 (암호가 걸린 문서일 수 있습니다)", Toast.LENGTH_LONG).show()); return; }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 4));
                box.addView(caption(activity, "선택한 페이지만 모아 새 문서로 저장합니다. 원본은 그대로 남고 필기도 함께 옮겨집니다."));
                box.addView(caption(activity, "뽑을 페이지 (전체 " + total + "쪽) · 예: 1-3, 5, 8-"));
                EditText range = field(activity, defaultPages);
                box.addView(range);
                box.addView(caption(activity, "새 문서 이름"));
                EditText name = field(activity, stem(file) + " 발췌");
                box.addView(name);
                AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("페이지 뽑기").setView(box).setPositiveButton("만들기", null).setNegativeButton("취소", null).create();
                dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
                    int[] pages;
                    try { pages = parseRanges(range.getText().toString(), total); } catch (IllegalArgumentException error) { range.setError(error.getMessage()); return; }
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) { name.setError("이름을 입력하세요"); return; }
                    try { NotebookFiles.name(title); } catch (Exception error) { name.setError(error.getMessage()); return; }
                    dialog.dismiss();
                    run(activity, repository, destination, title, Collections.singletonList(file), Collections.singletonList(pages), refresh, done);
                }));
                dialog.show();
            });
        }, "page-count").start();
    }
}
