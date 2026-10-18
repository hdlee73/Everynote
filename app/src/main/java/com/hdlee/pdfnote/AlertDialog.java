package com.hdlee.pdfnote;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * App-wide dialog with an iOS-like look and the same builder API as android.app.AlertDialog:
 * a centered rounded card for title/message/custom view with hairline-separated buttons, and a bottom action sheet
 * (centered blue rows plus a separate Cancel card) when the dialog lists items.
 */
final class AlertDialog extends Dialog {
    static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE, BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE, BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;
    private static final int ACCENT = 0xFF387AFF, INK = 0xFF010102, GRAY = 0xFF848487, LINE = 0xFFE4E4E7;
    private final TextView[] buttons = new TextView[3];
    private final boolean sheet;
    private TextView titleView, messageView;
    private int widthDp;

    private AlertDialog(Context context, boolean sheet) { super(context, R.style.SheetDialog); this.sheet = sheet; }

    TextView getButton(int which) { return buttons[-which - 1]; }
    CharSequence getTitleText() { return titleView == null ? null : titleView.getText(); }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window == null) return;
        float density = getContext().getResources().getDisplayMetrics().density;
        int screen = getContext().getResources().getDisplayMetrics().widthPixels;
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (sheet) { window.setGravity(Gravity.CENTER); window.setWindowAnimations(android.R.style.Animation_Dialog); window.setLayout(Math.min(screen - Math.round(40 * density), Math.round(320 * density)), ViewGroup.LayoutParams.WRAP_CONTENT); }
        else { window.setGravity(Gravity.CENTER); window.setWindowAnimations(android.R.style.Animation_Dialog); window.setLayout(Math.min(screen - Math.round((widthDp > 330 ? 32 : 48) * density), Math.round((widthDp > 0 ? widthDp : 330) * density)), ViewGroup.LayoutParams.WRAP_CONTENT); }
    }

    private static final class LimitedScroll extends ScrollView {
        private final int max;
        LimitedScroll(Context c, int max) { super(c); this.max = max; setVerticalScrollBarEnabled(false); }
        @Override protected void onMeasure(int w, int h) { super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST)); }
    }

    static final class Builder {
        private final Context context; private final float density;
        private CharSequence title, message; private View view; private CharSequence[] items; private int checked = -1; private boolean choice;
        private DialogInterface.OnClickListener itemListener;
        private final CharSequence[] labels = new CharSequence[3]; private final DialogInterface.OnClickListener[] listeners = new DialogInterface.OnClickListener[3];
        private boolean cancelable = true; private DialogInterface.OnDismissListener dismiss; private DialogInterface.OnCancelListener cancel; private int widthDp;

        Builder(Context context) { this.context = context; density = context.getResources().getDisplayMetrics().density; }
        Builder setTitle(CharSequence t) { title = t; return this; }
        Builder setMessage(CharSequence m) { message = m; return this; }
        Builder setView(View v) { view = v; return this; }
        Builder setItems(CharSequence[] list, DialogInterface.OnClickListener l) { items = list; itemListener = l; choice = false; return this; }
        Builder setSingleChoiceItems(CharSequence[] list, int selected, DialogInterface.OnClickListener l) { items = list; checked = selected; itemListener = l; choice = true; return this; }
        Builder setPositiveButton(CharSequence t, DialogInterface.OnClickListener l) { labels[0] = t; listeners[0] = l; return this; }
        Builder setNegativeButton(CharSequence t, DialogInterface.OnClickListener l) { labels[1] = t; listeners[1] = l; return this; }
        Builder setNeutralButton(CharSequence t, DialogInterface.OnClickListener l) { labels[2] = t; listeners[2] = l; return this; }
        Builder setCancelable(boolean c) { cancelable = c; return this; }
        /** Card width in dp (default 330) for dialogs whose custom view needs more room, e.g. rows of colour swatches. */
        Builder setWidth(int dp) { widthDp = dp; return this; }
        Builder setOnDismissListener(DialogInterface.OnDismissListener l) { dismiss = l; return this; }
        Builder setOnCancelListener(DialogInterface.OnCancelListener l) { cancel = l; return this; }
        AlertDialog show() { AlertDialog d = create(); d.show(); return d; }

        private int dp(float v) { return Math.round(v * density); }
        private GradientDrawable round(int color, int radius) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
        private android.graphics.drawable.Drawable pressable(float[] radii) {
            GradientDrawable on = new GradientDrawable(); on.setColor(0xFFF2F2F4); on.setCornerRadii(radii);
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, on); states.addState(new int[0], new ColorDrawable(Color.TRANSPARENT)); return states;
        }
        private View hairline(boolean vertical) { View v = new View(context); v.setBackgroundColor(LINE); v.setLayoutParams(vertical ? new LinearLayout.LayoutParams(Math.max(1, dp(.5f)), -1) : new LinearLayout.LayoutParams(-1, Math.max(1, dp(.5f)))); return v; }

        AlertDialog create() {
            final AlertDialog dialog = new AlertDialog(context, items != null); dialog.widthDp = widthDp;
            dialog.setCancelable(cancelable); dialog.setCanceledOnTouchOutside(cancelable);
            if (dismiss != null) dialog.setOnDismissListener(dismiss);
            if (cancel != null) dialog.setOnCancelListener(cancel);
            final int[] which = {BUTTON_POSITIVE, BUTTON_NEGATIVE, BUTTON_NEUTRAL};
            for (int i = 0; i < 3; i++) {
                final int slot = i;
                TextView b = new TextView(context); b.setTextSize(17); b.setGravity(Gravity.CENTER); b.setTextAlignment(View.TEXT_ALIGNMENT_CENTER); b.setIncludeFontPadding(false); b.setSingleLine(); b.setEllipsize(android.text.TextUtils.TruncateAt.END); b.setText(labels[i]); b.setContentDescription(labels[i]); b.setPadding(dp(8), 0, dp(8), 0); b.setTag("dialog_button");
                b.setTextColor(ACCENT); b.setTypeface(i == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                if (i == 1 && labels[i] != null && labels[i].toString().contains("삭제")) b.setTextColor(0xFFDB332A);
                b.setOnClickListener(v -> { if (listeners[slot] != null) listeners[slot].onClick(dialog, which[slot]); dialog.dismiss(); });
                dialog.buttons[i] = b;
            }
            FrameLayout frame = new FrameLayout(context);
            if (items == null) frame.addView(card(dialog), new FrameLayout.LayoutParams(-1, -2)); else frame.addView(sheet(dialog), new FrameLayout.LayoutParams(-1, -2));
            dialog.setContentView(frame);
            Window w = dialog.getWindow(); if (w != null) { w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); if (items != null) w.setDimAmount(0.12f); }
            return dialog;
        }

        private View card(AlertDialog dialog) {
            LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setBackground(round(Color.WHITE, 18)); card.setTag("alert_card");
            LinearLayout body = new LinearLayout(context); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(22), dp(20), dp(18));
            if (title != null) { TextView t = new TextView(context); t.setText(title); t.setTextSize(17); t.setTextColor(INK); t.setTypeface(Typeface.DEFAULT_BOLD); t.setGravity(Gravity.CENTER); t.setTextAlignment(View.TEXT_ALIGNMENT_CENTER); t.setTag("dialog_title"); body.addView(t, new LinearLayout.LayoutParams(-1, -2)); dialog.titleView = t; }
            if (message != null) { TextView m = new TextView(context); m.setText(message); m.setTextSize(14); m.setTextColor(0xFF48484A); m.setGravity(Gravity.CENTER); m.setTextAlignment(View.TEXT_ALIGNMENT_CENTER); m.setLineSpacing(0, 1.2f); m.setTag("dialog_message"); LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.topMargin = dp(title == null ? 0 : 8); LimitedScroll s = new LimitedScroll(context, Math.round(context.getResources().getDisplayMetrics().heightPixels * .5f)); s.addView(m); body.addView(s, mp); dialog.messageView = m; }
            if (view != null) { if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view); LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-1, -2); vp.topMargin = dp(title == null && message == null ? 0 : 12); body.addView(view, vp); }
            card.addView(body, new LinearLayout.LayoutParams(-1, -2));
            int count = 0; for (CharSequence l : labels) if (l != null) count++;
            if (count == 0) return card;
            card.addView(hairline(false));
            boolean row = count <= 2 && labels[2] == null;
            LinearLayout bar = new LinearLayout(context); bar.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            int[] order = row ? new int[]{1, 0} : new int[]{0, 2, 1}; boolean first = true; int index = 0;
            for (int slot : order) {
                if (labels[slot] == null) continue;
                if (!first) bar.addView(hairline(row)); first = false;
                // iOS alert buttons: equal-width halves (or full-width rows), label centred in its own cell, a soft grey press highlight
                // that follows the card's rounded bottom corners
                boolean last = ++index == count, firstCell = index == 1; float r = dp(18);
                float bl = row ? (firstCell ? r : 0) : (last ? r : 0), br = row ? (last ? r : 0) : (last ? r : 0);
                dialog.buttons[slot].setBackground(pressable(new float[]{0, 0, 0, 0, br, br, bl, bl}));
                bar.addView(dialog.buttons[slot], row ? new LinearLayout.LayoutParams(0, dp(50), 1) : new LinearLayout.LayoutParams(-1, dp(50)));
            }
            card.addView(bar, new LinearLayout.LayoutParams(-1, -2));
            return card;
        }

        /** List dialog in the same look as the anchored menu card: left-aligned 44dp rows, accent check on the chosen row, red danger rows, hairline dividers. */
        /** A leading icon for list rows so plain lists look like the app's other menu cards. */
        private int iconFor(CharSequence text) {
            String t = text == null ? "" : text.toString();
            if (t.contains("삭제") || t.contains("비우기")) return R.drawable.ic_delete;
            if (t.contains("복원") || t.contains("불러오기") || t.contains("가져오기") || t.contains("열기") && !t.contains("저장")) return R.drawable.ic_import;
            if (t.contains("이름")) return R.drawable.ic_rename;
            if (t.contains("내보내") || t.contains("공유") || t.contains("저장")) return R.drawable.ic_export;
            if (t.contains("PDF")) return R.drawable.ic_pdf;
            if (t.contains("Word") || t.contains("docx")) return R.drawable.ic_word;
            if (t.contains("Excel") || t.contains("CSV") || t.contains("Markdown")) return R.drawable.ic_table;
            if (t.contains("폴더")) return R.drawable.ic_folder_open;
            if (t.contains("색")) return R.drawable.ic_palette;
            if (t.contains("이동") || t.contains("페이지")) return R.drawable.ic_page;
            if (t.contains("닫기") || t.contains("취소")) return 0;
            return R.drawable.ic_page;
        }
        private int tintFor(CharSequence text) {
            String t = text == null ? "" : text.toString();
            if (t.contains("삭제") || t.contains("비우기")) return 0xFFDB332A;
            if (t.contains("복원") || t.contains("불러오기") || t.contains("가져오기")) return 0xFF34C759;
            if (t.contains("내보내") || t.contains("공유") || t.contains("저장") || t.contains("PDF")) return 0xFF34C759;
            if (t.contains("Word")) return 0xFF387AFF;
            if (t.contains("Excel") || t.contains("CSV") || t.contains("Markdown")) return 0xFF30B0C7;
            if (t.contains("색")) return 0xFFAF52DE;
            return 0xFF848487;
        }
        private View sheet(AlertDialog dialog) {
            FrameLayout root = new FrameLayout(context); root.setTag("action_sheet");
            LinearLayout card = new LinearLayout(context); card.setOrientation(LinearLayout.VERTICAL); card.setTag("anchored_menu");
            GradientDrawable bg = round(Color.WHITE, 18); bg.setStroke(Math.max(1, dp(.5f)), 0x14000000); card.setBackground(bg); card.setPadding(dp(6), dp(6), dp(6), dp(6)); card.setElevation(dp(12));
            if (title != null) { TextView t = new TextView(context); t.setText(title); t.setTextSize(13); t.setTextColor(GRAY); t.setGravity(Gravity.CENTER_VERTICAL); t.setPadding(dp(12), dp(8), dp(12), dp(6)); t.setTag("dialog_title"); card.addView(t, new LinearLayout.LayoutParams(-1, -2)); }
            if (message != null) { TextView m = new TextView(context); m.setText(message); m.setTextSize(13); m.setTextColor(GRAY); m.setPadding(dp(12), 0, dp(12), dp(8)); card.addView(m, new LinearLayout.LayoutParams(-1, -2)); }
            LinearLayout rows = new LinearLayout(context); rows.setOrientation(LinearLayout.VERTICAL);
            final View[] cells = new View[items.length]; final TextView[] texts = new TextView[items.length]; final ImageView[] marks = new ImageView[items.length]; final int[] current = {checked};
            Runnable refresh = () -> { for (int i = 0; i < cells.length; i++) { boolean on = choice && i == current[0]; texts[i].setTextColor(on ? ACCENT : INK); texts[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); marks[i].setVisibility(on ? View.VISIBLE : View.GONE); } };
            for (int i = 0; i < items.length; i++) {
                final int index = i;
                LinearLayout line = new LinearLayout(context); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(dp(12), 0, dp(12), 0); line.setContentDescription(items[i]);
                if (!choice) { int icon = iconFor(items[i]); if (icon != 0) { ImageView glyph = new ImageView(context); glyph.setImageResource(icon); glyph.setColorFilter(tintFor(items[i])); LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(20), dp(20)); gp.rightMargin = dp(12); line.addView(glyph, gp); } }
                TextView text = new TextView(context); text.setText(items[i]); text.setTextSize(15); text.setSingleLine(); text.setEllipsize(android.text.TextUtils.TruncateAt.END); text.setTextColor(INK); line.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                ImageView mark = new ImageView(context); mark.setImageResource(R.drawable.ic_check_bold); mark.setColorFilter(ACCENT); mark.setVisibility(View.GONE); line.addView(mark, new LinearLayout.LayoutParams(dp(18), dp(18)));
                line.setOnClickListener(v -> { if (choice) { current[0] = index; refresh.run(); } if (itemListener != null) itemListener.onClick(dialog, index); if (!choice) dialog.dismiss(); });
                cells[i] = line; texts[i] = text; marks[i] = mark; rows.addView(line, new LinearLayout.LayoutParams(-1, dp(44)));
            }
            refresh.run();
            LimitedScroll scroll = new LimitedScroll(context, Math.round(context.getResources().getDisplayMetrics().heightPixels * .55f)); scroll.addView(rows); card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
            if (view != null) { if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view); card.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
            boolean any = false; for (int slot : new int[]{0, 2, 1}) if (labels[slot] != null) any = true;
            if (any) { View line = hairline(false); LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) line.getLayoutParams(); lp.setMargins(dp(12), dp(4), dp(12), dp(4)); card.addView(line); }
            for (int slot : new int[]{0, 2, 1}) if (labels[slot] != null) {
                TextView b = dialog.buttons[slot]; b.setGravity(Gravity.CENTER_VERTICAL); b.setTextSize(15); b.setPadding(dp(12), 0, dp(12), 0);
                String label = labels[slot].toString(); boolean danger = label.contains("삭제") || label.contains("비우기");
                b.setTextColor(danger ? 0xFFDB332A : slot == 1 ? GRAY : ACCENT); b.setTypeface(Typeface.DEFAULT);
                int icon = danger ? R.drawable.ic_delete : slot == 1 ? R.drawable.ic_close : iconFor(label);
                if (icon != 0) { android.graphics.drawable.Drawable d = context.getResources().getDrawable(icon, null).mutate(); d.setTint(danger ? 0xFFDB332A : slot == 1 ? GRAY : tintFor(label)); d.setBounds(0, 0, dp(20), dp(20)); b.setCompoundDrawablesRelative(d, null, null, null); b.setCompoundDrawablePadding(dp(12)); }
                card.addView(b, new LinearLayout.LayoutParams(-1, dp(44)));
            }
            root.addView(card, new FrameLayout.LayoutParams(-1, -2));
            return root;
        }
    }
}
