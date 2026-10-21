package com.hdlee.pdfnote;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/** The "EPUB 책" category: every stored book with its reading progress. Tap opens it in the reader; long press offers delete. */
final class EpubShelfDialog extends Dialog {
    interface Actions {
        void open(File book);
        void importBook();
    }

    private static final int INK = 0xFF010102, MUTED = 0xFF848487, ACCENT = 0xFF387AFF, SURFACE = 0xFFFCFCFC;
    private final Activity activity;
    private final EpubStore store;
    private final Actions actions;
    private LinearLayout list;

    EpubShelfDialog(Activity activity, EpubStore store, Actions actions) {
        super(activity);
        this.activity = activity; this.store = store; this.actions = actions;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(SURFACE); page.setFitsSystemWindows(true);
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(4), dp(6), dp(8), 0);
        TextView back = text("‹", 28, INK);
        back.setGravity(Gravity.CENTER); back.setContentDescription("닫기"); back.setOnClickListener(v -> dismiss());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView title = text("EPUB 책", 22, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = text("책 가져오기", 15, ACCENT);
        add.setPadding(dp(12), dp(10), dp(12), dp(10));
        add.setOnClickListener(v -> { dismiss(); actions.importBook(); });
        bar.addView(add, new LinearLayout.LayoutParams(-2, -2));
        page.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
        ScrollView scroll = new ScrollView(activity);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL); list.setPadding(dp(14), dp(6), dp(14), dp(24));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        reload();
    }

    @Override public void show() {
        super.show();
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().setLayout(-1, -1);
        getWindow().setStatusBarColor(SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics())); }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(activity);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        return t;
    }

    void reload() {
        list.removeAllViews();
        List<File> books = store.list();
        if (books.isEmpty()) {
            TextView empty = text("저장된 EPUB 책이 없습니다.\n‘책 가져오기’로 EPUB 파일을 고르면 이곳에 보관됩니다.\n필기하려고 PDF로 변환한 문서는 문서함에 저장됩니다.", 15, MUTED);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(80), dp(20), 0);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        for (File book : books) list.addView(row(book), new LinearLayout.LayoutParams(-1, -2));
    }

    private View row(File book) {
        String[] info = store.info(book);
        String title = info != null && !info[0].trim().isEmpty() ? info[0] : book.getName().replaceFirst("(?i)\\.epub$", "");
        int percent = 0;
        if (info != null) try { percent = Integer.parseInt(info[1].trim()); } catch (RuntimeException ignored) { }
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(10), dp(8), dp(10));
        ImageView cover = new ImageView(activity);
        cover.setImageResource(R.drawable.ic_book); cover.setColorFilter(Color.WHITE); cover.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable tile = new GradientDrawable();
        tile.setCornerRadius(dp(8)); tile.setColor(0xFF5B7CFA);
        cover.setBackground(tile);
        row.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(64)));
        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL); texts.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(title, 16, INK);
        name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        String detail = (info == null ? "아직 읽지 않음" : percent + "% 읽음") + " · " + Math.max(1, book.length() / 1024) + " KB";
        texts.addView(text(detail, 12, MUTED));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { dismiss(); actions.open(book); });
        row.setOnLongClickListener(v -> { confirmDelete(book, title); return true; });
        return row;
    }

    private void confirmDelete(File book, String title) {
        new AlertDialog.Builder(activity).setTitle("책 삭제").setMessage("‘" + title + "’을(를) EPUB 책 목록에서 삭제할까요? 이미 PDF로 변환한 문서는 문서함에 그대로 남습니다.")
            .setPositiveButton("삭제", (d, w) -> { store.delete(book); reload(); }).setNegativeButton("취소", null).show();
    }
}
