package com.hdlee.pdfnote;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/**
 * Paper chooser in the style of a note app's "new note" sheet: every paper is a small preview tile (bundled form templates first),
 * followed by colour squares and, for generated papers, a portrait / landscape choice.
 */
final class PaperChoiceView extends LinearLayout {
    /** Paper, colour and orientation that are pre-selected for a new note (settings > 기본 노트 스타일). */
    static final class Style { int kind = 10, color = Color.WHITE; boolean landscape; }
    static Style defaultStyle(SharedPreferences prefs) {
        Style s = new Style();
        int kind = prefs.getInt("default_paper_kind", 10), color = prefs.getInt("default_paper_color", Color.WHITE);
        for (int k : NotebookFiles.PAPER_ORDER) if (k == kind && k != NotebookFiles.CUSTOM) s.kind = kind;
        for (int c : NotebookFiles.COLORS) if (c == color) s.color = color;
        s.landscape = prefs.getBoolean("default_paper_landscape", false);
        return s;
    }
    static void saveDefaultStyle(SharedPreferences prefs, PaperChoiceView view) {
        prefs.edit().putInt("default_paper_kind", view.kind).putInt("default_paper_color", view.color).putBoolean("default_paper_landscape", view.layoutChoice && view.landscape).apply();
    }
    static String describe(Style s) {
        int index = 0; for (int i = 0; i < NotebookFiles.COLORS.length; i++) if (NotebookFiles.COLORS[i] == s.color) index = i;
        return NotebookFiles.PAPER_NAMES[s.kind] + " · " + NotebookFiles.COLOR_NAMES[index] + (s.landscape && s.kind < NotebookFiles.CUSTOM ? " · 가로" : "");
    }

    private int kind = 10, color = Color.WHITE;
    private boolean landscape;
    private final boolean layoutChoice;
    private java.io.File template; private Runnable templateRequest; private final TextView templateButton;
    private final LinearLayout colors, layoutRow;
    private final LinearLayout[] tiles = new LinearLayout[NotebookFiles.PAPER_ORDER.length];
    private final View[] thumbs = new View[NotebookFiles.PAPER_ORDER.length];
    private int selected = -1;
    private java.io.File builtin; private final java.util.Map<String, Bitmap> previews = new java.util.HashMap<>();

    PaperChoiceView(Context context) { this(context, 10, Color.WHITE, false, false); }
    PaperChoiceView(Context context, int initialKind, int initialColor, boolean initialLandscape, boolean layoutChoice) {
        super(context);
        setOrientation(VERTICAL);
        this.color = initialColor; this.layoutChoice = layoutChoice; this.landscape = layoutChoice && initialLandscape;
        ScrollView scroll = new ScrollView(context); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(context); content.setOrientation(VERTICAL); content.setPadding(dp(2), 0, dp(2), dp(4));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2)); addView(scroll, new LayoutParams(-1, -2));
        content.addView(section("종이", 4));
        final int columns = 4; LinearLayout row = null;
        for (int i = 0; i < NotebookFiles.PAPER_ORDER.length; i++) {
            if (i % columns == 0) { row = new LinearLayout(context); row.setGravity(Gravity.TOP); content.addView(row, new LayoutParams(-1, -2)); }
            row.addView(tile(i), new LayoutParams(0, -2, 1));
        }
        int rest = (columns - NotebookFiles.PAPER_ORDER.length % columns) % columns;
        for (int i = 0; i < rest; i++) row.addView(new View(context), new LayoutParams(0, 1, 1));
        templateButton = new TextView(context); templateButton.setText("PDF·이미지 서식 고르기"); templateButton.setTextSize(14); templateButton.setGravity(Gravity.CENTER); templateButton.setTextColor(0xFF387AFF); templateButton.setBackground(chipBackground()); templateButton.setVisibility(GONE);
        templateButton.setOnClickListener(v -> { if (templateRequest != null) templateRequest.run(); });
        LayoutParams tp = new LayoutParams(-1, dp(44)); tp.topMargin = dp(10); content.addView(templateButton, tp);
        content.addView(section("색상", 14));
        HorizontalScrollView colorScroll = new HorizontalScrollView(context); colorScroll.setHorizontalScrollBarEnabled(false);
        colors = new LinearLayout(context); colors.setGravity(Gravity.CENTER_VERTICAL); colorScroll.addView(colors, new HorizontalScrollView.LayoutParams(-2, dp(44))); content.addView(colorScroll, new LayoutParams(-1, dp(44)));
        layoutRow = new LinearLayout(context); layoutRow.setGravity(Gravity.CENTER_VERTICAL);
        if (layoutChoice) { content.addView(section("레이아웃", 14)); content.addView(layoutRow, new LayoutParams(-1, -2)); }
        refreshColors();
        int start = 0; for (int i = 0; i < NotebookFiles.PAPER_ORDER.length; i++) if (NotebookFiles.PAPER_ORDER[i] == initialKind) start = i;
        selectPosition(start);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) { super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(Math.round(getResources().getDisplayMetrics().heightPixels * .5f), MeasureSpec.AT_MOST)); }
    int kind() { return kind; }
    int color() { return color; }
    boolean landscape() { return layoutChoice && landscape; }

    private TextView section(String text, int topDp) { TextView t = new TextView(getContext()); t.setText(text); t.setTextSize(13); t.setTextColor(0xFF3A3A3C); t.setTypeface(Typeface.DEFAULT_BOLD); t.setPadding(dp(2), dp(topDp), 0, dp(8)); return t; }
    private LinearLayout tile(int position) {
        final int k = NotebookFiles.PAPER_ORDER[position];
        LinearLayout tile = new LinearLayout(getContext()); tile.setOrientation(VERTICAL); tile.setGravity(Gravity.CENTER_HORIZONTAL); tile.setPadding(dp(2), dp(5), dp(2), dp(6));
        tile.setTag("paper_tile:" + k); tile.setContentDescription(NotebookFiles.PAPER_NAMES[k]);
        View thumb = new Thumb(getContext(), k); thumbs[position] = thumb; tile.addView(thumb, new LayoutParams(dp(56), dp(78)));
        TextView name = new TextView(getContext()); name.setText(k == NotebookFiles.CUSTOM ? "내 서식" : NotebookFiles.PAPER_NAMES[k]); name.setTextSize(11); name.setTextColor(0xFF3A3A3C); name.setGravity(Gravity.CENTER); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); name.setPadding(0, dp(4), 0, 0);
        tile.addView(name, new LayoutParams(-1, -2));
        tile.setOnClickListener(v -> selectPosition(position)); tiles[position] = tile; return tile;
    }
    private void styleTiles() {
        for (int i = 0; i < tiles.length; i++) {
            boolean on = i == selected; GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12));
            if (on) { bg.setColor(0xFFEEF5FF); bg.setStroke(dp(2), 0xFF387AFF); } else bg.setColor(Color.TRANSPARENT);
            tiles[i].setBackground(bg); tiles[i].setSelected(on);
            View label = ((ViewGroup) tiles[i]).getChildAt(1); if (label instanceof TextView) { ((TextView) label).setTextColor(on ? 0xFF387AFF : 0xFF3A3A3C); ((TextView) label).setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT); }
        }
    }
    /** Chooses a paper by its row in the list (the bundled form templates come first). */
    void selectPosition(int position) { selected = position; kind = NotebookFiles.PAPER_ORDER[position]; builtin = kind >= 10 ? builtinFile(kind) : null; templateButton.setVisibility(kind == NotebookFiles.CUSTOM ? VISIBLE : GONE); styleTiles(); refreshLayout(); redraw(); }
    /** Chooses a paper by its kind (see {@link NotebookFiles#PAPER_NAMES}). */
    void selectKind(int paperKind) { for (int i = 0; i < NotebookFiles.PAPER_ORDER.length; i++) if (NotebookFiles.PAPER_ORDER[i] == paperKind) { selectPosition(i); return; } }
    private void redraw() { for (View v : thumbs) if (v != null) v.invalidate(); }
    /** Copies a bundled form PDF out of the assets once so it can be used like a user-chosen template. */
    private java.io.File builtinFile(int k){
        try{java.io.File dir=new java.io.File(getContext().getFilesDir(),"templates");dir.mkdirs();String name=NotebookFiles.BUILTIN_TEMPLATES[k-10];java.io.File out=new java.io.File(dir,"builtin-"+name);
            if(!out.isFile()||out.length()==0){try(java.io.InputStream in=getContext().getAssets().open("templates/"+name);java.io.OutputStream o=new java.io.FileOutputStream(out)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}}
            return out;}catch(java.io.IOException e){return null;}
    }
    private Bitmap pdfPreview(java.io.File file){
        if(file==null||!file.isFile())return null;Bitmap cached=previews.get(file.getPath());if(cached!=null)return cached;
        try(android.os.ParcelFileDescriptor fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY);android.graphics.pdf.PdfRenderer r=new android.graphics.pdf.PdfRenderer(fd)){
            if(r.getPageCount()<1)return null;try(android.graphics.pdf.PdfRenderer.Page page=r.openPage(0)){Bitmap bmp=Bitmap.createBitmap(300,Math.max(1,300*page.getHeight()/page.getWidth()),Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE);page.render(bmp,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);previews.put(file.getPath(),bmp);return bmp;}
        }catch(Exception|Error e){return null;}
    }
    NotebookFiles.Paper paper(){if(kind>=10){if(builtin==null)builtin=builtinFile(kind);return new NotebookFiles.Paper(NotebookFiles.CUSTOM,Color.WHITE,builtin);}return new NotebookFiles.Paper(kind,color,template);}
    /** Called when the user taps "PDF·이미지 서식 고르기"; the host opens a file picker and answers with {@link #setTemplate}. */
    void onTemplateRequest(Runnable request){templateRequest=request;}
    void setTemplate(java.io.File file){template=file;templateButton.setText(file==null?"PDF·이미지 서식 고르기":"서식: "+file.getName()+" · 다시 고르기");redraw();}
    private GradientDrawable chipBackground(){GradientDrawable g=new GradientDrawable();g.setColor(0xFFEAF2FF);g.setCornerRadius(dp(12));return g;}
    private void refreshColors(){
        colors.removeAllViews();
        for(int i=0;i<NotebookFiles.COLORS.length;i++){
            final int value=NotebookFiles.COLORS[i];boolean on=color==value;
            TextView chip=new TextView(getContext());chip.setText(on?Glyph.check(getContext(),0xFF387AFF,""):"");chip.setTextSize(18);chip.setGravity(Gravity.CENTER);chip.setContentDescription("배경색 "+NotebookFiles.COLOR_NAMES[i]);chip.setTag("paper_color:"+i);
            GradientDrawable bg=new GradientDrawable();bg.setColor(value);bg.setCornerRadius(dp(8));bg.setStroke(dp(on?2:1),on?0xFF387AFF:0xFFCBD5E1);chip.setBackground(bg);
            LayoutParams lp=new LayoutParams(dp(34),dp(34));lp.setMargins(dp(2),0,dp(8),0);chip.setOnClickListener(v->{color=value;refreshColors();redraw();});colors.addView(chip,lp);
        }
    }
    /** Portrait / landscape choice (only for generated papers; a form PDF keeps its own page size). */
    private void refreshLayout(){
        if(!layoutChoice)return;layoutRow.removeAllViews();boolean form=kind>=10||kind==NotebookFiles.CUSTOM;
        String[] labels={"기본 (세로)","가로"};
        for(int i=0;i<2;i++){
            final boolean land=i==1;boolean on=landscape==land;
            LinearLayout box=new LinearLayout(getContext());box.setOrientation(VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(14),dp(8),dp(14),dp(8));box.setContentDescription("레이아웃 "+labels[i]);box.setTag("paper_layout:"+(land?"land":"port"));box.setAlpha(form?.4f:1f);
            GradientDrawable bg=new GradientDrawable();bg.setCornerRadius(dp(12));if(on&&!form){bg.setColor(0xFFEEF5FF);bg.setStroke(dp(2),0xFF387AFF);}else bg.setColor(Color.TRANSPARENT);box.setBackground(bg);
            View icon=new View(getContext());GradientDrawable ib=new GradientDrawable();ib.setColor(Color.WHITE);ib.setCornerRadius(dp(4));ib.setStroke(dp(2),on&&!form?0xFF387AFF:0xFF848487);icon.setBackground(ib);
            LayoutParams ip=land?new LayoutParams(dp(36),dp(26)):new LayoutParams(dp(26),dp(36));ip.topMargin=land?dp(5):0;ip.bottomMargin=land?dp(5):0;box.addView(icon,ip);
            TextView label=new TextView(getContext());label.setText(labels[i]);label.setTextSize(12);label.setTextColor(0xFF3A3A3C);label.setGravity(Gravity.CENTER);label.setPadding(0,dp(6),0,0);box.addView(label,new LayoutParams(-2,-2));
            if(!form)box.setOnClickListener(v->{landscape=land;refreshLayout();redraw();});
            LayoutParams lp=new LayoutParams(-2,-2);lp.rightMargin=dp(10);layoutRow.addView(box,lp);
        }
    }
    /** A paper drawn small: its ruling on the chosen colour, or the first page of a form PDF. */
    private final class Thumb extends View {
        private final int paperKind;private java.io.File formFile;
        Thumb(Context c,int paperKind){super(c);this.paperKind=paperKind;}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);boolean form=paperKind>=10,custom=paperKind==NotebookFiles.CUSTOM,land=landscape&&layoutChoice&&!form&&!custom;
            float ptW=land?842:595,ptH=land?595:842,W=getWidth(),H=getHeight(),pw=W-dp(6),ph=pw*ptH/ptW;if(ph>H-dp(6)){ph=H-dp(6);pw=ph*ptW/ptH;}
            RectF rect=new RectF((W-pw)/2,(H-ph)/2,(W+pw)/2,(H+ph)/2);Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(form?Color.WHITE:color);canvas.drawRect(rect,p);
            if(form&&formFile==null)formFile=builtinFile(paperKind);Bitmap formBitmap=form?pdfPreview(formFile):custom?pdfPreview(template):null;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1));p.setColor(0xFFCBD2DA);
            if(formBitmap!=null){canvas.drawBitmap(formBitmap,null,rect,new Paint(Paint.FILTER_BITMAP_FLAG));canvas.drawRect(rect,p);return;}
            canvas.drawRect(rect,p);
            if(custom){p.setStyle(Paint.Style.FILL);p.setColor(0xFF848487);p.setTextSize(dp(22));p.setTextAlign(Paint.Align.CENTER);canvas.drawText(template==null?"+":"✓",rect.centerX(),rect.centerY()+dp(7),p);return;}
            boolean dark=Color.red(color)+Color.green(color)+Color.blue(color)<300;p.setStrokeWidth(1);
            for(float[] seg:NotebookFiles.layout(paperKind,ptW,ptH)){int[] c=NotebookFiles.ruleColor((int)seg[4]);p.setColor(dark?Color.rgb(Math.min(255,c[0]/2+90),Math.min(255,c[1]/2+90),Math.min(255,c[2]/2+90)):Color.rgb(c[0],c[1],c[2]));
                float x1=rect.left+seg[0]*pw/ptW,y1=rect.bottom-seg[1]*ph/ptH,x2=rect.left+seg[2]*pw/ptW,y2=rect.bottom-seg[3]*ph/ptH;
                if(seg[4]==3){p.setStyle(Paint.Style.FILL);canvas.drawCircle(x1,y1,Math.max(.6f,pw/ptW*.9f),p);p.setStyle(Paint.Style.STROKE);}else canvas.drawLine(x1,y1,x2,y2,p);}
        }
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
