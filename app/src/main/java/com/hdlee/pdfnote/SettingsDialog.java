package com.hdlee.pdfnote;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings screen in the style of a note app: grouped cards of rows with switches and radio choices. */
final class SettingsDialog extends Dialog {
    /** What the screen reads and changes; implemented by the main activity, which owns the real state. */
    interface Host {
        android.content.SharedPreferences prefs();
        boolean keepAwake();
        void setKeepAwake(boolean on);
        boolean dockPinned();
        void toggleDockPinned();
        boolean cropMargins();
        void setCropMargins(boolean on);
        boolean darkPage();
        void toggleDarkPage();
        String[] swipeChoices();
        int swipeMode();
        void setSwipeMode(int which);
        String[] animChoices();
        int pageAnim();
        void setPageAnim(int which);
        String pendingUpdate();
        void showAbout();
        void showHelp();
        void requestTemplate(PaperChoiceView view);
    }

    private static final int INK = 0xFF010102, MUTED = 0xFF848487, ACCENT = 0xFF387AFF, BACKGROUND = 0xFFF6F6F8, LINE = 0xFFEFEFF2;
    private final Activity activity;
    private final Host host;
    private final LinearLayout body;
    private final ScrollView scroll;

    SettingsDialog(Activity activity, Host host) {
        super(activity);
        this.activity = activity; this.host = host;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout content = new FrameLayout(activity); content.setBackgroundColor(BACKGROUND); content.setFitsSystemWindows(true); content.setTag("settings_root");
        LinearLayout column = new LinearLayout(activity); column.setOrientation(LinearLayout.VERTICAL); content.addView(column, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(activity); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(6), dp(4), dp(14), 0);
        ImageButton back = new ImageButton(activity); back.setImageResource(R.drawable.ic_chevron_left); back.setColorFilter(INK); back.setBackgroundColor(Color.TRANSPARENT); back.setContentDescription("설정 닫기"); back.setOnClickListener(v -> dismiss());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));
        TextView title = text("설정", 22, INK); title.setTypeface(Typeface.DEFAULT_BOLD); top.addView(title, new LinearLayout.LayoutParams(-2, -1));
        column.addView(top, new LinearLayout.LayoutParams(-1, dp(60)));
        scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false);
        body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(2), dp(16), dp(40));
        FrameLayout.LayoutParams bodyParams = new FrameLayout.LayoutParams(Math.min(dp(720), activity.getResources().getDisplayMetrics().widthPixels), -2, Gravity.CENTER_HORIZONTAL);
        FrameLayout holder = new FrameLayout(activity); holder.addView(body, bodyParams); scroll.addView(holder, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(content);
        render();
    }

    @Override public void show() {
        super.show();
        Window window = getWindow(); if (window == null) return;
        window.setBackgroundDrawableResource(android.R.color.transparent); window.setLayout(-1, -1); window.setStatusBarColor(BACKGROUND); window.setNavigationBarColor(BACKGROUND);
        window.getDecorView().setSystemUiVisibility(window.getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void render() {
        final int offset = scroll.getScrollY();
        body.removeAllViews();
        android.content.SharedPreferences prefs = host.prefs();
        PaperChoiceView.Style style = PaperChoiceView.defaultStyle(prefs);
        section("일반",
            link("기본 노트 스타일", "새 노트를 만들 때 가장 처음 선택되어 있는 양식을 정합니다.", PaperChoiceView.describe(style), "set_note_style", this::chooseNoteStyle),
            toggle("화면 켜 둠", "읽는 동안 화면이 꺼지지 않습니다.", host.keepAwake(), "set_keep_awake", on -> { host.setKeepAwake(on); toast(on ? "읽는 동안 화면이 꺼지지 않습니다" : "화면 자동 꺼짐을 따릅니다"); }),
            toggle("전체 화면 메뉴 계속 표시", "전체 화면에서도 아래쪽 도구 줄을 항상 보여 줍니다.", host.dockPinned(), "set_dock_pinned", on -> host.toggleDockPinned()));
        section("문서 보기",
            toggle("여백 자르기", "문서의 흰 여백을 잘라 화면에 꽉 채웁니다.", host.cropMargins(), "set_crop", on -> { host.setCropMargins(on); toast(on ? "문서 여백을 잘라 화면에 꽉 채웁니다" : "원래 여백을 그대로 보여줍니다"); }),
            toggle("검은 문서 배경", "문서 배경을 검게 표시합니다. 어두운 글씨 필기는 밝게 보입니다.", host.darkPage(), "set_dark_page", on -> host.toggleDarkPage()));
        String[] swipe = host.swipeChoices(); View[] swipeRows = new View[swipe.length];
        for (int i = 0; i < swipe.length; i++) { final int which = i; swipeRows[i] = radio(swipe[i], host.swipeMode() == i, "set_swipe:" + i, () -> host.setSwipeMode(which)); }
        section("페이지 넘김", swipeRows);
        String[] anim = host.animChoices(); View[] animRows = new View[anim.length];
        for (int i = 0; i < anim.length; i++) { final int which = i; animRows[i] = radio(anim[i], host.pageAnim() == i, "set_anim:" + i, () -> host.setPageAnim(which)); }
        section("넘김 효과", animRows);
        String update = host.pendingUpdate();
        section("정보",
            link(update == null ? "앱 정보·업데이트" : "앱 정보·업데이트 (새 버전 v" + update + ")", null, null, "set_about", host::showAbout),
            link("사용법", null, null, "set_help", host::showHelp));
        scroll.post(() -> scroll.scrollTo(0, offset));
    }

    // ---------------------------------------------------------------- rows
    private void section(String title, View... rows) {
        TextView label = text(title, 13, 0xFF6B6B70); label.setTypeface(Typeface.DEFAULT_BOLD); label.setPadding(dp(14), dp(22), 0, dp(8)); body.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(round(Color.WHITE, 20)); card.setPadding(0, dp(4), 0, dp(4));
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) { View hair = new View(activity); hair.setBackgroundColor(LINE); LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))); hp.setMargins(dp(18), 0, dp(18), 0); card.addView(hair, hp); }
            card.addView(rows[i], new LinearLayout.LayoutParams(-1, -2));
        }
        body.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
    private LinearLayout row(int minDp) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(20), dp(10), dp(20), dp(10)); row.setMinimumHeight(dp(minDp));
        android.util.TypedValue ripple = new android.util.TypedValue(); if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) row.setBackgroundResource(ripple.resourceId);
        return row;
    }
    private LinearLayout texts(String title, String sub, String value) {
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 15, INK), new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) { TextView s = text(sub, 12.5f, MUTED); s.setPadding(0, dp(2), 0, 0); box.addView(s, new LinearLayout.LayoutParams(-1, -2)); }
        if (value != null) { TextView v = text(value, 13, ACCENT); v.setPadding(0, dp(3), 0, 0); v.setTag("set_note_style_value"); box.addView(v, new LinearLayout.LayoutParams(-1, -2)); }
        return box;
    }
    private View link(String title, String sub, String value, String tag, Runnable action) {
        LinearLayout row = row(56); row.setTag(tag); row.setContentDescription(title); row.addView(texts(title, sub, value), new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> action.run()); return row;
    }
    private View toggle(String title, String sub, boolean on, String tag, java.util.function.Consumer<Boolean> change) {
        LinearLayout row = row(56); row.setContentDescription(title); row.addView(texts(title, sub, null), new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(activity); sw.setChecked(on); sw.setTag(tag); sw.setContentDescription(title);
        sw.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        sw.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, new int[0]}, new int[]{ACCENT, 0xFFD1D1D6}));
        sw.setOnCheckedChangeListener((button, checked) -> { change.accept(checked); render(); });
        row.addView(sw, new LinearLayout.LayoutParams(-2, -2)); row.setOnClickListener(v -> sw.toggle()); return row;
    }
    private View radio(String label, boolean on, String tag, Runnable pick) {
        LinearLayout row = row(48); row.setTag(tag); row.setContentDescription(label); row.setSelected(on);
        View dot = new View(activity); GradientDrawable ring = new GradientDrawable(); ring.setShape(GradientDrawable.OVAL); ring.setColor(Color.WHITE); ring.setStroke(dp(2), on ? ACCENT : MUTED);
        FrameLayout mark = new FrameLayout(activity); mark.setBackground(ring);
        if (on) { GradientDrawable fill = new GradientDrawable(); fill.setShape(GradientDrawable.OVAL); fill.setColor(ACCENT); dot.setBackground(fill); mark.addView(dot, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER)); }
        row.addView(mark, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView text = text(label, 15, INK); text.setPadding(dp(14), 0, 0, 0); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { pick.run(); render(); }); return row;
    }

    /** 기본 노트 스타일: the same paper / colour / layout chooser as the new-note dialog; the result becomes the first choice there. */
    private void chooseNoteStyle() {
        PaperChoiceView.Style start = PaperChoiceView.defaultStyle(host.prefs());
        PaperChoiceView view = new PaperChoiceView(activity, start.kind, start.color, start.landscape, true);
        view.onTemplateRequest(() -> host.requestTemplate(view));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("기본 노트 스타일").setView(view).setPositiveButton("저장", null).setNegativeButton("취소", null).setWidth(400).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            if (view.kind() == NotebookFiles.CUSTOM) { toast("내 서식은 기본 스타일로 정할 수 없습니다. 새 노트를 만들 때 고르세요"); return; }
            PaperChoiceView.saveDefaultStyle(host.prefs(), view); dialog.dismiss(); render(); toast("새 노트의 기본 스타일을 저장했습니다");
        }));
        dialog.show();
    }

    // ---------------------------------------------------------------- helpers
    private void toast(String message) { Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private TextView text(String value, float size, int color) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private GradientDrawable round(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private int dp(float n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
}
