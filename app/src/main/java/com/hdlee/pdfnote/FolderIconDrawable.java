package com.hdlee.pdfnote;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Line-style folder (Samsung Notes navigation look): a rounded outline with a tab, stroked in the folder colour over a pale wash of it. */
final class FolderIconDrawable extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),fill=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color,size;
    FolderIconDrawable(int color,int size){this.color=color;this.size=size;paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);}
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void draw(Canvas canvas){
        Rect b=getBounds();if(b.isEmpty())return;float s=Math.min(b.width(),b.height())/24f;canvas.save();canvas.translate(b.centerX()-12*s,b.centerY()-12*s);canvas.scale(s,s);
        paint.setColor(darken(color));paint.setStrokeWidth(1.7f);
        Path p=new Path();p.moveTo(3.5f,7f);p.quadTo(3.5f,5f,5.5f,5f);p.lineTo(9.3f,5f);p.quadTo(10.3f,5f,10.9f,5.8f);p.lineTo(11.9f,7.2f);p.lineTo(18.5f,7.2f);p.quadTo(20.5f,7.2f,20.5f,9.2f);p.lineTo(20.5f,17f);p.quadTo(20.5f,19f,18.5f,19f);p.lineTo(5.5f,19f);p.quadTo(3.5f,19f,3.5f,17f);p.close();fill.setColor(tint(color));canvas.drawPath(p,fill);canvas.drawPath(p,paint);
        canvas.drawLine(3.5f,10.2f,20.5f,10.2f,paint);canvas.restore();
    }
    /** Pastel folder colours are too light for a 1.7px line, so the outline uses a deeper tone of the same hue. */
    private static int darken(int c){float[] hsv=new float[3];Color.colorToHSV(c,hsv);hsv[1]=Math.min(1f,hsv[1]*1.35f+.08f);hsv[2]=Math.max(0f,hsv[2]*.82f);return Color.HSVToColor(Color.alpha(c),hsv);}
    /** A pale wash of the folder colour inside the outline, so coloured folders stay easy to tell apart. */
    private static int tint(int c){float k=.2f;return Color.argb(Color.alpha(c),Math.round(255-(255-Color.red(c))*k),Math.round(255-(255-Color.green(c))*k),Math.round(255-(255-Color.blue(c))*k));}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);fill.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);fill.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
