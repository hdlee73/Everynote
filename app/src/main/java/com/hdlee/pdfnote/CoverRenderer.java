package com.hdlee.pdfnote;

import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Draws a document cover (표지) as a picture: 8 templates in 6 accent colours with a title, a subtitle and a name. */
final class CoverRenderer {
    static final String[] TEMPLATE_NAMES={"클래식","색 블록","풀 컬러","줄노트 라벨","미니멀","도트","다크","다이어리"};
    static final int[] ACCENTS={0xFF4F7CFF,0xFFF08A4B,0xFF4BB88A,0xFF8E6AE6,0xFFE8668A,0xFF5B6575};
    static final String[] ACCENT_NAMES={"블루","오렌지","그린","퍼플","로즈","슬레이트"};

    static final class Spec {
        int template,accent;String title="",subtitle="",name="";
        Spec(int template,int accent,String title,String subtitle,String name){this.template=Math.max(0,Math.min(TEMPLATE_NAMES.length-1,template));this.accent=accent;this.title=title==null?"":title.trim();this.subtitle=subtitle==null?"":subtitle.trim();this.name=name==null?"":name.trim();}
    }

    /** A cover of [width] x [height] pixels. */
    static Bitmap render(Spec spec,int width,int height){
        Bitmap bitmap=Bitmap.createBitmap(Math.max(60,width),Math.max(60,height),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bitmap);float w=bitmap.getWidth(),h=bitmap.getHeight();
        switch(spec.template){
            case 1:blocks(c,spec,w,h);break;case 2:full(c,spec,w,h);break;case 3:ruled(c,spec,w,h);break;case 4:minimal(c,spec,w,h);break;
            case 5:dots(c,spec,w,h);break;case 6:dark(c,spec,w,h);break;case 7:diary(c,spec,w,h);break;default:classic(c,spec,w,h);break;
        }
        return bitmap;
    }

    private static Paint fill(int color){Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);return p;}
    private static Paint stroke(int color,float width){Paint p=fill(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);return p;}
    private static int alpha(int color,int a){return (a<<24)|(color&0xFFFFFF);}
    private static int mix(int a,int b,float t){return Color.rgb(Math.round(Color.red(a)*(1-t)+Color.red(b)*t),Math.round(Color.green(a)*(1-t)+Color.green(b)*t),Math.round(Color.blue(a)*(1-t)+Color.blue(b)*t));}

    /** Draws [text] wrapped inside the box, shrinking the type until it fits; returns the height used. align: left / centre. */
    private static float text(Canvas c,String text,float x,float y,float boxW,float boxH,float size,int color,boolean bold,Layout.Alignment align,int maxLines){
        if(text==null||text.isEmpty())return 0;
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setColor(color);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        StaticLayout layout;float s=size;
        while(true){
            paint.setTextSize(s);layout=new StaticLayout(text,paint,Math.round(boxW),align,1.12f,0,false);
            if((layout.getHeight()<=boxH&&layout.getLineCount()<=maxLines)||s<=size*.3f)break;s*=.92f;
        }
        c.save();c.translate(x,y);layout.draw(c);c.restore();return layout.getHeight();
    }
    private static float measure(String text,float boxW,float boxH,float size,boolean bold,int maxLines){
        if(text==null||text.isEmpty())return 0;
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);float s=size;StaticLayout layout;
        while(true){paint.setTextSize(s);layout=new StaticLayout(text,paint,Math.round(boxW),Layout.Alignment.ALIGN_NORMAL,1.12f,0,false);if((layout.getHeight()<=boxH&&layout.getLineCount()<=maxLines)||s<=size*.3f)break;s*=.92f;}
        return layout.getHeight();
    }
    private static String titleOf(Spec spec){return spec.title.isEmpty()?"제목":spec.title;}

    /** 클래식: white paper, a double frame, the title centred. */
    private static void classic(Canvas c,Spec spec,float w,float h){
        c.drawColor(Color.WHITE);float m=w*.07f;
        c.drawRect(m,m,w-m,h-m,stroke(spec.accent,w*.006f));c.drawRect(m*1.45f,m*1.45f,w-m*1.45f,h-m*1.45f,stroke(alpha(spec.accent,130),w*.002f));
        float box=w*.68f,x=(w-box)/2f;float th=measure(titleOf(spec),box,h*.3f,w*.095f,true,4),sh=measure(spec.subtitle,box,h*.12f,w*.045f,false,3);
        float total=th+(sh>0?w*.05f+sh:0)+w*.06f,y=h*.4f-total/2f;
        text(c,titleOf(spec),x,y,box,h*.3f,w*.095f,0xFF1C1C1E,true,Layout.Alignment.ALIGN_CENTER,4);
        c.drawLine(w/2f-w*.07f,y+th+w*.03f,w/2f+w*.07f,y+th+w*.03f,stroke(spec.accent,w*.006f));
        if(sh>0)text(c,spec.subtitle,x,y+th+w*.06f,box,h*.12f,w*.045f,0xFF636368,false,Layout.Alignment.ALIGN_CENTER,3);
        if(!spec.name.isEmpty())text(c,spec.name,x,h-m*2.6f,box,w*.08f,w*.04f,0xFF48484A,false,Layout.Alignment.ALIGN_CENTER,1);
    }
    /** 색 블록: a coloured block on top holds the title, the rest stays white. */
    private static void blocks(Canvas c,Spec spec,float w,float h){
        c.drawColor(Color.WHITE);float top=h*.5f;c.drawRect(0,0,w,top,fill(spec.accent));
        c.drawCircle(w*.86f,h*.1f,w*.22f,fill(alpha(Color.WHITE,38)));c.drawCircle(w*.72f,h*.04f,w*.1f,fill(alpha(Color.WHITE,30)));
        float x=w*.1f,box=w*.8f,th=measure(titleOf(spec),box,top*.62f,w*.1f,true,4);
        text(c,titleOf(spec),x,top-th-h*.05f,box,top*.62f,w*.1f,Color.WHITE,true,Layout.Alignment.ALIGN_NORMAL,4);
        if(!spec.subtitle.isEmpty())text(c,spec.subtitle,x,top+h*.035f,box,h*.15f,w*.047f,0xFF48484A,false,Layout.Alignment.ALIGN_NORMAL,3);
        if(!spec.name.isEmpty()){c.drawRect(x,h-h*.12f,x+w*.1f,h-h*.12f+w*.008f,fill(spec.accent));text(c,spec.name,x,h-h*.1f,box,w*.08f,w*.042f,0xFF48484A,false,Layout.Alignment.ALIGN_NORMAL,1);}
    }
    /** 풀 컬러: the whole cover in the accent colour, a big white title at the lower left. */
    private static void full(Canvas c,Spec spec,float w,float h){
        c.drawColor(spec.accent);Paint soft=fill(alpha(Color.WHITE,34));
        c.drawCircle(w*.95f,h*.08f,w*.42f,soft);c.drawCircle(w*.1f,h*.2f,w*.14f,fill(alpha(Color.WHITE,24)));c.drawCircle(w*.8f,h*.42f,w*.07f,fill(alpha(Color.WHITE,30)));
        float x=w*.09f,box=w*.82f,sh=measure(spec.subtitle,box,h*.14f,w*.05f,false,3),th=measure(titleOf(spec),box,h*.34f,w*.115f,true,5);
        float bottom=h-(spec.name.isEmpty()?h*.1f:h*.2f);float y=bottom-th-(sh>0?sh+h*.025f:0);
        text(c,titleOf(spec),x,y,box,h*.34f,w*.115f,Color.WHITE,true,Layout.Alignment.ALIGN_NORMAL,5);
        if(sh>0)text(c,spec.subtitle,x,y+th+h*.025f,box,h*.14f,w*.05f,alpha(Color.WHITE,225),false,Layout.Alignment.ALIGN_NORMAL,3);
        if(!spec.name.isEmpty())text(c,spec.name,x,h-h*.11f,box,w*.08f,w*.043f,alpha(Color.WHITE,235),false,Layout.Alignment.ALIGN_NORMAL,1);
    }
    /** 줄노트 라벨: a cream notebook cover with rules and a white name label. */
    private static void ruled(Canvas c,Spec spec,float w,float h){
        c.drawColor(0xFFFFF9E8);Paint rule=stroke(alpha(spec.accent,70),w*.0025f);for(float y=h*.08f;y<h*.95f;y+=h*.034f)c.drawLine(0,y,w,y,rule);
        c.drawLine(w*.14f,0,w*.14f,h,stroke(alpha(0xFFE05555,150),w*.003f));
        float lx=w*.2f,lw=w*.62f,ly=h*.26f,lh=h*.3f;RectF label=new RectF(lx,ly,lx+lw,ly+lh);
        c.drawRoundRect(label,w*.025f,w*.025f,fill(Color.WHITE));c.drawRoundRect(label,w*.025f,w*.025f,stroke(spec.accent,w*.007f));
        float pad=w*.04f,box=lw-pad*2f;float th=measure(titleOf(spec),box,lh*.55f,w*.08f,true,3);
        text(c,titleOf(spec),lx+pad,ly+pad,box,lh*.55f,w*.08f,0xFF1C1C1E,true,Layout.Alignment.ALIGN_CENTER,3);
        if(!spec.subtitle.isEmpty())text(c,spec.subtitle,lx+pad,ly+pad+th+w*.015f,box,lh-th-pad*2.4f,w*.042f,0xFF636368,false,Layout.Alignment.ALIGN_CENTER,2);
        float ny=ly+lh+h*.04f;c.drawText("이름",lx,ny+w*.04f,labelPaint(w*.04f,0xFF636368));
        c.drawLine(lx+w*.1f,ny+w*.045f,lx+lw,ny+w*.045f,stroke(0xFF8E8E93,w*.0025f));
        if(!spec.name.isEmpty())text(c,spec.name,lx+w*.11f,ny,lw-w*.11f,w*.06f,w*.042f,0xFF1C1C1E,false,Layout.Alignment.ALIGN_NORMAL,1);
    }
    private static Paint labelPaint(float size,int color){Paint p=fill(color);p.setTextSize(size);return p;}
    /** 미니멀: white, one accent bar on the left, the title left aligned. */
    private static void minimal(Canvas c,Spec spec,float w,float h){
        c.drawColor(Color.WHITE);c.drawRect(0,0,w*.06f,h,fill(spec.accent));
        float x=w*.17f,box=w*.68f,th=measure(titleOf(spec),box,h*.3f,w*.1f,true,4);
        float y=h*.3f;text(c,titleOf(spec),x,y,box,h*.3f,w*.1f,0xFF1C1C1E,true,Layout.Alignment.ALIGN_NORMAL,4);
        c.drawRect(x,y+th+h*.025f,x+w*.12f,y+th+h*.025f+w*.008f,fill(spec.accent));
        if(!spec.subtitle.isEmpty())text(c,spec.subtitle,x,y+th+h*.055f,box,h*.15f,w*.045f,0xFF636368,false,Layout.Alignment.ALIGN_NORMAL,3);
        if(!spec.name.isEmpty())text(c,spec.name,x,h-h*.1f,box,w*.08f,w*.042f,0xFF48484A,false,Layout.Alignment.ALIGN_NORMAL,1);
    }
    /** 도트: a pale dotted sheet with a white title tile. */
    private static void dots(Canvas c,Spec spec,float w,float h){
        c.drawColor(mix(Color.WHITE,spec.accent,.1f));Paint dot=fill(alpha(spec.accent,95));float step=w*.055f;
        for(float y=step;y<h;y+=step)for(float x=step;x<w;x+=step)c.drawCircle(x,y,w*.0045f,dot);
        float tw=w*.7f,tx=(w-tw)/2f,pad=w*.05f,box=tw-pad*2f;float th=measure(titleOf(spec),box,h*.3f,w*.085f,true,4),sh=measure(spec.subtitle,box,h*.12f,w*.043f,false,3);
        float tile=th+(sh>0?sh+w*.03f:0)+pad*2f+(spec.name.isEmpty()?0:w*.08f);float ty=h*.4f-tile/2f;
        RectF r=new RectF(tx,ty,tx+tw,ty+tile);c.drawRoundRect(r,w*.04f,w*.04f,fill(Color.WHITE));c.drawRoundRect(r,w*.04f,w*.04f,stroke(spec.accent,w*.006f));
        text(c,titleOf(spec),tx+pad,ty+pad,box,h*.3f,w*.085f,0xFF1C1C1E,true,Layout.Alignment.ALIGN_CENTER,4);
        if(sh>0)text(c,spec.subtitle,tx+pad,ty+pad+th+w*.03f,box,h*.12f,w*.043f,0xFF636368,false,Layout.Alignment.ALIGN_CENTER,3);
        if(!spec.name.isEmpty())text(c,spec.name,tx+pad,ty+tile-pad-w*.05f,box,w*.06f,w*.04f,spec.accent,true,Layout.Alignment.ALIGN_CENTER,1);
    }
    /** 다크: near-black, a short accent bar and a white title in the lower part. */
    private static void dark(Canvas c,Spec spec,float w,float h){
        c.drawColor(0xFF1C1C1E);c.drawCircle(w*.9f,h*.12f,w*.3f,fill(alpha(spec.accent,40)));
        float x=w*.1f,box=w*.8f,sh=measure(spec.subtitle,box,h*.14f,w*.047f,false,3),th=measure(titleOf(spec),box,h*.3f,w*.105f,true,4);
        float bottom=h*.78f,y=bottom-th-(sh>0?sh+h*.02f:0);
        c.drawRect(x,y-h*.045f,x+w*.14f,y-h*.045f+w*.012f,fill(spec.accent));
        text(c,titleOf(spec),x,y,box,h*.3f,w*.105f,Color.WHITE,true,Layout.Alignment.ALIGN_NORMAL,4);
        if(sh>0)text(c,spec.subtitle,x,y+th+h*.02f,box,h*.14f,w*.047f,0xFFAEAEB2,false,Layout.Alignment.ALIGN_NORMAL,3);
        if(!spec.name.isEmpty())text(c,spec.name,x,h-h*.09f,box,w*.08f,w*.042f,0xFFC7C7CC,false,Layout.Alignment.ALIGN_NORMAL,1);
    }
    /** 다이어리: a leather-look cover with stitching, an elastic band and a paper label. */
    private static void diary(Canvas c,Spec spec,float w,float h){
        int leather=mix(spec.accent,Color.BLACK,.45f);c.drawColor(leather);
        Paint grain=stroke(alpha(Color.WHITE,10),w*.002f);for(float y=h*.012f;y<h;y+=h*.016f)c.drawLine(0,y,w,y,grain);
        float m=w*.06f;Paint stitch=stroke(alpha(0xFFF3E3C3,200),w*.004f);stitch.setPathEffect(new DashPathEffect(new float[]{w*.02f,w*.014f},0));
        c.drawRoundRect(new RectF(m,m,w-m,h-m),w*.02f,w*.02f,stitch);
        c.drawRect(w*.8f,0,w*.8f+w*.045f,h,fill(mix(leather,Color.BLACK,.35f)));
        float lw=w*.56f,lx=w*.14f,ly=h*.22f,pad=w*.04f,box=lw-pad*2f;float th=measure(titleOf(spec),box,h*.22f,w*.07f,true,3),sh=measure(spec.subtitle,box,h*.08f,w*.038f,false,2);
        float lh=th+(sh>0?sh+w*.02f:0)+pad*2f;RectF label=new RectF(lx,ly,lx+lw,ly+lh);
        c.drawRoundRect(label,w*.012f,w*.012f,fill(0xFFF6EBD0));c.drawRoundRect(label,w*.012f,w*.012f,stroke(alpha(leather,160),w*.003f));
        text(c,titleOf(spec),lx+pad,ly+pad,box,h*.22f,w*.07f,0xFF3A2A1A,true,Layout.Alignment.ALIGN_CENTER,3);
        if(sh>0)text(c,spec.subtitle,lx+pad,ly+pad+th+w*.02f,box,h*.08f,w*.038f,0xFF6A5640,false,Layout.Alignment.ALIGN_CENTER,2);
        if(!spec.name.isEmpty())text(c,spec.name,lx,h-h*.14f,lw,w*.08f,w*.042f,0xFFF3E3C3,false,Layout.Alignment.ALIGN_CENTER,1);
    }
}
