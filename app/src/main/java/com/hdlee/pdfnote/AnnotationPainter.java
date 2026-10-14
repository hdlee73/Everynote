package com.hdlee.pdfnote;
import android.content.Context;
import android.graphics.*;
import java.io.File;

final class AnnotationPainter {
    private static final android.util.LruCache<String,Bitmap> images=new android.util.LruCache<String,Bitmap>(12*1024*1024){@Override protected int sizeOf(String key,Bitmap value){return value.getAllocationByteCount();}};
    private static Bitmap image(File file){Bitmap b=images.get(file.getPath());if(b==null){b=BitmapFactory.decodeFile(file.getPath());if(b!=null)images.put(file.getPath(),b);}return b;}

    /** Maps a stored font id (sans, serif, mono, hand) plus style flags to a platform typeface. */
    /** The picture file of an attached element; the bundled masking tapes are copied out of the assets on first use. */
    static File builtinAsset(Context context,String asset){
        File file=new File(new File(context.getFilesDir(),"images"),asset);
        if(!file.isFile()&&asset.matches("tape-\\d{2}\\.png")){file.getParentFile().mkdirs();try(java.io.InputStream in=context.getAssets().open("stickers/"+asset);java.io.OutputStream out=new java.io.FileOutputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}catch(java.io.IOException ignored){file.delete();}}
        return file;
    }
    static Typeface typeface(String font,boolean bold,boolean italic){
        int style=(bold?Typeface.BOLD:0)|(italic?Typeface.ITALIC:0);
        Typeface base;
        if("serif".equals(font))base=Typeface.SERIF;
        else if("mono".equals(font))base=Typeface.MONOSPACE;
        else if("hand".equals(font))base=Typeface.create("cursive",Typeface.NORMAL);
        else if("casual".equals(font))base=Typeface.create("casual",Typeface.NORMAL);
        else if("typewriter".equals(font))base=Typeface.create("serif-monospace",Typeface.NORMAL);
        else if("medium".equals(font))base=Typeface.create("sans-serif-medium",Typeface.NORMAL);
        else if("light".equals(font))base=Typeface.create("sans-serif-light",Typeface.NORMAL);
        else if("black".equals(font))base=Typeface.create("sans-serif-black",Typeface.NORMAL);
        else if("condensed".equals(font))base=Typeface.create("sans-serif-condensed",Typeface.NORMAL);
        else base=Typeface.SANS_SERIF;
        return Typeface.create(base,style);
    }

    static void text(Canvas canvas,String text,RectF box,float size,int color){text(canvas,text,box,size,color,Typeface.DEFAULT);}
    static void text(Canvas canvas,String text,RectF box,float size,int color,Typeface face){text(canvas,text,box,size,color,face,0,false,false);}
    /** align: 0 left, 1 centre, 2 right. */
    static void text(Canvas canvas,String text,RectF box,float size,int color,Typeface face,int align,boolean underline,boolean strike){text(canvas,text,box,size,color,face,align,underline,strike,1.35f);}
    /** line: distance between baselines as a multiple of size. */
    static void text(Canvas canvas,String text,RectF box,float size,int color,Typeface face,int align,boolean underline,boolean strike,float step){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(color);p.setTextSize(size);p.setTypeface(face);p.setUnderlineText(underline);p.setStrikeThruText(strike);
        int save=canvas.save();canvas.clipRect(box);float y=box.top+size;
        for(String paragraph:text.split("\n",-1)){
            String remaining=paragraph;
            while(!remaining.isEmpty()){int n=Math.max(1,p.breakText(remaining,true,box.width(),null));String line=remaining.substring(0,n);float x=box.left;if(align!=0){String shown=line.replaceAll("\\s+$","");float room=box.width()-p.measureText(shown);x=box.left+(align==1?room/2f:room);}canvas.drawText(line,x,y,p);y+=size*step;remaining=remaining.substring(n);}
            if(paragraph.isEmpty())y+=size*step;
        }
        canvas.restoreToCount(save);
    }

    /**
     * Height (as a fraction of the page height) a typing box needs so that none of its text is clipped.
     * Uses the same wrapping rules as {@link #text}. {@code pageAspect} is page height divided by page width.
     */
    static float fitHeight(String text,float widthFraction,float sizeFraction,float pageAspect,Typeface face){return fitHeight(text,widthFraction,sizeFraction,pageAspect,face,1.35f);}
    static float fitHeight(String text,float widthFraction,float sizeFraction,float pageAspect,Typeface face,float line){
        final float pageWidth=1000f;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);float size=Math.max(1f,sizeFraction*pageWidth);p.setTextSize(size);p.setTypeface(face);
        float boxWidth=Math.max(size,widthFraction*pageWidth);int lines=0;
        for(String paragraph:text.split("\n",-1)){
            String remaining=paragraph;
            if(remaining.isEmpty()){lines++;continue;}
            while(!remaining.isEmpty()){int n=Math.max(1,p.breakText(remaining,true,boxWidth,null));lines++;remaining=remaining.substring(n);}
        }
        float heightPx=size*(line*Math.max(1,lines)+.15f);
        return heightPx/(pageWidth*Math.max(.1f,pageAspect));
    }

    static RectF box(RectF dest,AnnotationStore.PageElement e){return new RectF(dest.left+e.left*dest.width(),dest.top+e.top*dest.height(),dest.left+e.right*dest.width(),dest.top+e.bottom*dest.height());}
    /** The text box being edited in place; it is drawn by the editor instead of the page. */
    static volatile AnnotationStore.PageElement skip;
    private static boolean lastOfGroup(AnnotationStore store,AnnotationStore.PageElement e){
        boolean after=false;
        for(AnnotationStore.PageElement o:store.elements){if(o==e){after=true;continue;}if(after&&o.kind.equals("hyperlink")&&o.page==e.page&&o.color==e.color&&o.text.equals(e.text))return false;}
        return true;
    }
    /** True while a page is drawn on a dark paper: dark ink and text are lightened so they stay readable. */
    static boolean dark;
    static int adj(int color){
        if(!dark)return color;
        int r=Color.red(color),g=Color.green(color),b=Color.blue(color);
        if(.299f*r+.587f*g+.114f*b>120)return color;
        return Color.argb(Color.alpha(color),r+(int)((255-r)*.88f),g+(int)((255-g)*.88f),b+(int)((255-b)*.88f));
    }
    static void elements(Context context,Canvas c,RectF d,AnnotationStore store,int page){
        if(store==null)return;
        for(AnnotationStore.PageElement e:store.elements){
            if(e.page!=page||e==skip)continue;RectF b=box(d,e);
            int rotSave=c.save();if(e.rot!=0f&&rotates(e))c.rotate(e.rot,b.centerX(),b.centerY());
            if(e.alpha<.999f&&rotates(e))c.saveLayerAlpha(new RectF(d.left-d.width(),d.top-d.height(),d.right+d.width(),d.bottom+d.height()),Math.round(e.alpha*255));
            if(e.kind.equals("image")){
                File file=builtinAsset(context,e.asset);Bitmap image=image(file);
                if(image!=null){
                    float scale=Math.min(b.width()/image.getWidth(),b.height()/image.getHeight());
                    RectF fitted=e.stretch?b:new RectF(b.centerX()-image.getWidth()*scale/2,b.centerY()-image.getHeight()*scale/2,b.centerX()+image.getWidth()*scale/2,b.centerY()+image.getHeight()*scale/2);
                    c.drawBitmap(image,null,fitted,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
                }
            }else if(e.kind.equals("sticker")){
                Paint glyph=new Paint(Paint.ANTI_ALIAS_FLAG);glyph.setTextAlign(Paint.Align.CENTER);float size=Math.min(b.width(),b.height())*.82f;glyph.setTextSize(size);Paint.FontMetrics fm=glyph.getFontMetrics();c.drawText(e.text,b.centerX(),b.centerY()-(fm.ascent+fm.descent)/2f,glyph);
            }else if(e.kind.equals("video")||e.kind.equals("youtube")){
                File file=new File(new File(context.getFilesDir(),"images"),e.asset);Bitmap frame=image(file);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
                if(frame!=null){c.drawBitmap(frame,null,b,paint);}else{paint.setColor(0xFF2C2C2E);c.drawRoundRect(b,b.width()*.03f,b.width()*.03f,paint);}
                paint.setColor(0x55000000);c.drawRect(b,paint);float r=Math.min(b.width(),b.height())*.17f;boolean yt=e.kind.equals("youtube");if(yt){r*=1.15f;paint.setColor(0xFFFF0000);c.drawRoundRect(new RectF(b.centerX()-r*1.25f,b.centerY()-r*.88f,b.centerX()+r*1.25f,b.centerY()+r*.88f),r*.5f,r*.5f,paint);}else{paint.setColor(0xE6FFFFFF);c.drawCircle(b.centerX(),b.centerY(),r,paint);}
                android.graphics.Path tri=new android.graphics.Path();tri.moveTo(b.centerX()-r*.32f,b.centerY()-r*.5f);tri.lineTo(b.centerX()-r*.32f,b.centerY()+r*.5f);tri.lineTo(b.centerX()+r*.55f,b.centerY());tri.close();paint.setColor(yt?0xFFFFFFFF:0xFF1C1C1E);c.drawPath(tri,paint);
            }else if(e.kind.equals("shape")){Shapes.drawShape(c,b,e.text,d.width());
            }else if(e.kind.equals("table")){Shapes.drawTable(c,b,e.text,d.width());
            }else if(e.kind.equals("hyperlink")){
                // only a thin underline marks a link: no blue box and no badge over the text
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xFF007AFF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1.2f,d.width()*.0022f));c.drawLine(b.left,b.bottom-paint.getStrokeWidth(),b.right,b.bottom-paint.getStrokeWidth(),paint);
            }else if(e.kind.equals("audio")){
                Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);float r=b.height()/2;fill.setColor(0xFFE5F0FF);c.drawRoundRect(b,r,r,fill);fill.setStyle(Paint.Style.STROKE);fill.setStrokeWidth(Math.max(1f,d.width()*.002f));fill.setColor(0xFF007AFF);c.drawRoundRect(b,r,r,fill);
                float size=Math.max(8f,b.height()*.46f);text(c,"▶  녹음 "+e.text,new RectF(b.left+r*.9f,b.top+(b.height()-size*1.35f)/2f,b.right-r*.4f,b.bottom),size,0xFF007AFF);
            }else if(e.kind.equals("link")){
                text(c,"↗ "+e.text,b,Math.max(9,d.width()*.027f),0xFF007AFF);
            }else{
                text(c,e.text,b,Math.max(9,d.width()*e.textSize),adj(e.color),typeface(e.font,e.bold,e.italic),e.align,e.underline,e.strike,e.line());
            }
            c.restoreToCount(rotSave);
        }
    }
    /** Elements that can be turned around their centre. */
    static boolean rotates(AnnotationStore.PageElement e){return e.kind.equals("image")||e.kind.equals("sticker")||e.kind.equals("shape")||e.kind.equals("table");}

    /** Pen strokes of one page. Shared by the page view, export and handwriting search. */
    static void strokes(Canvas c,RectF d,AnnotationStore store,int page){
        for(AnnotationStore.InkStroke s:store.strokes)if(s.page==page)stroke(c,d,s);
    }
    static final String[] PEN_NAMES={"볼펜","연필","만년필","붓","사인펜"};
    /** The ink highlighter (형광펜) pen id: a freehand or straight translucent band of constant width. */
    static final int HIGHLIGHTER=5;
    /** The tape pen id: a wide, flat, translucent ribbon with a lighter strip down its middle (masking tape); like the highlighter it has no pressure. */
    static final int TAPE=6;
    /** Smoothed copy of a stroke's points used for drawing (v1.54.0): raw digitizer samples are jittery and sparse at speed, so straight segments through them gave wrinkled letters and angular curves. Light 1-2-1 smoothing of position (not at sharp corners) and pressure, then a Catmull-Rom spline subdivided finely. Page-normalized, cached per stroke until its points change. */
    private static final class Geo{int n;double sx,sy,sp;float[] x,y,p;}
    private static final java.util.WeakHashMap<AnnotationStore.InkStroke,Geo> GEO=new java.util.WeakHashMap<>();
    private static synchronized Geo geo(AnnotationStore.InkStroke s){
        int n=s.points.size();double sx=0,sy=0,sp=0;
        for(int i=0;i<n;i++){AnnotationStore.InkPoint q=s.points.get(i);sx+=q.x;sy+=q.y;sp+=q.pressure;}
        Geo g=GEO.get(s);if(g!=null&&g.n==n&&g.sx==sx&&g.sy==sy&&g.sp==sp)return g;
        g=new Geo();g.n=n;g.sx=sx;g.sy=sy;g.sp=sp;
        if(n<=2){g.x=new float[n];g.y=new float[n];g.p=new float[n];for(int i=0;i<n;i++){AnnotationStore.InkPoint q=s.points.get(i);g.x[i]=q.x;g.y[i]=q.y;g.p[i]=q.pressure;}GEO.put(s,g);return g;}
        float[] rx=new float[n],ry=new float[n],X=new float[n],Y=new float[n],P=new float[n];
        for(int i=0;i<n;i++){AnnotationStore.InkPoint q=s.points.get(i);rx[i]=X[i]=q.x;ry[i]=Y[i]=q.y;P[i]=q.pressure;}
        for(int pass=0;pass<2;pass++){float[] src=P.clone();for(int i=1;i<n-1;i++)P[i]=(src[i-1]+2*src[i]+src[i+1])/4f;}
        for(int pass=0;pass<2;pass++){System.arraycopy(X,0,rx,0,n);System.arraycopy(Y,0,ry,0,n);   // two passes also calm strokes saved by older versions
        for(int i=1;i<n-1;i++){
            float ax=rx[i]-rx[i-1],ay=(ry[i]-ry[i-1])*1.414f,bx=rx[i+1]-rx[i],by=(ry[i+1]-ry[i])*1.414f;
            float la=(float)Math.hypot(ax,ay),lb=(float)Math.hypot(bx,by);
            if(la<1e-9f||lb<1e-9f)continue;
            if((ax*bx+ay*by)/(la*lb)<.5f)continue;   // turn of more than 60 degrees: a real corner
            X[i]=(rx[i-1]+2*rx[i]+rx[i+1])/4f;Y[i]=(ry[i-1]+2*ry[i]+ry[i+1])/4f;
        }}
        java.util.ArrayList<float[]> out=new java.util.ArrayList<>();out.add(new float[]{X[0],Y[0],P[0]});
        for(int i=0;i<n-1;i++){
            int i0=Math.max(0,i-1),i3=Math.min(n-1,i+2);
            double seg=Math.hypot((X[i+1]-X[i])*1000.0,(Y[i+1]-Y[i])*1414.0);   // length on a 1000 px wide reference page
            int steps=(int)Math.max(1,Math.min(12,Math.ceil(seg/3)));
            for(int k=1;k<=steps;k++){
                float t=k/(float)steps,t2=t*t,t3=t2*t;
                float f0=-.5f*t3+t2-.5f*t,f1=1.5f*t3-2.5f*t2+1f,f2=-1.5f*t3+2f*t2+.5f*t,f3=.5f*t3-.5f*t2;
                out.add(new float[]{f0*X[i0]+f1*X[i]+f2*X[i+1]+f3*X[i3],f0*Y[i0]+f1*Y[i]+f2*Y[i+1]+f3*Y[i3],P[i]+(P[i+1]-P[i])*t});
            }
        }
        int m=out.size();g.x=new float[m];g.y=new float[m];g.p=new float[m];
        for(int i=0;i<m;i++){float[] o=out.get(i);g.x[i]=o[0];g.y[i]=o[1];g.p[i]=o[2];}
        GEO.put(s,g);return g;
    }
    /** One stroke in the style of its pen: ballpoint, pencil, fountain pen (nib angle), brush (taper), felt marker or (pen 5, not in {@link #PEN_NAMES}) the highlighter: flat constant width, no pressure, translucent. Translucent colours do not darken where the stroke overlaps itself. */
    static void stroke(Canvas c,RectF d,AnnotationStore.InkStroke s){
        if(s.points.isEmpty()||d.width()<=0)return;
        Geo g=geo(s);int n=g.x.length;
        int argb=adj(s.color);float penAlpha=s.pen==1?.78f:s.pen==3?.92f:s.pen==4?.82f:1f;int eff=Math.round(Color.alpha(argb)*penAlpha);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(argb|0xFF000000);p.setStrokeCap(s.pen==TAPE?Paint.Cap.BUTT:s.pen>=4?Paint.Cap.SQUARE:Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        int save=-1;if(eff<255)save=c.saveLayerAlpha(d.left,d.top,d.right,d.bottom,Math.max(8,eff));
        float base=s.width*d.width();
        float[] px=new float[n],py=new float[n],W=new float[n];
        for(int i=0;i<n;i++){px[i]=d.left+g.x[i]*d.width();py[i]=d.top+g.y[i]*d.height();}
        for(int i=0;i<n;i++){
            int a=Math.max(0,i-1);float pr=(g.p[a]+g.p[i])/2f,w;
            switch(s.pen){
                case 1:w=base*.75f*(.5f+pr*.9f);break;
                case 2:{double ang=Math.atan2(py[i]-py[a],px[i]-px[a]);double cut=Math.abs(Math.sin(ang+Math.PI/4));w=base*(float)(.32+1.05*cut)*(.65f+pr*.7f);break;}
                case 3:{float t=n<=1?.5f:i/(float)(n-1);float taper=Math.min(1f,Math.min(t,1f-t)*7f);w=base*2.1f*(.35f+pr*.95f)*(.35f+.65f*taper);break;}
                case 4:w=base*1.5f;break;
                case 5:case 6:w=base;break;
                default:w=base*(.45f+pr*1.15f);
            }
            W[i]=Math.max(1.5f,w);
        }
        if(s.pen<4&&n>2){float prev=W[0];for(int i=1;i<n-1;i++){float cur=W[i];W[i]=(prev+2*cur+W[i+1])/4f;prev=cur;}}   // no steps in the width where the pressure changes
        if(s.pen>=4&&n>1){   // constant width: one path, no overlapping caps
            Path path=new Path();path.moveTo(px[0],py[0]);for(int i=1;i<n;i++)path.lineTo(px[i],py[i]);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(W[0]);c.drawPath(path,p);
            if(s.pen==TAPE){p.setColor(0xFFFFFFFF);p.setStrokeWidth(W[0]*.42f);p.setAlpha(70);c.drawPath(path,p);}   // glossy strip along the tape
        }else{
            p.setStyle(Paint.Style.FILL);c.drawCircle(px[0],py[0],W[0]/2f,p);p.setStyle(Paint.Style.STROKE);
            for(int i=1;i<n;i++){p.setStrokeWidth((W[i]+W[i-1])/2f);c.drawLine(px[i-1],py[i-1],px[i],py[i],p);}
        }
        if(save>=0)c.restoreToCount(save);
    }

    static void all(Context context,Canvas c,RectF d,AnnotationStore store,int page){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        for(AnnotationStore.Mark m:store.marks)if(m.page==page){
            RectF b=new RectF(d.left+m.left*d.width(),d.top+m.top*d.height(),d.left+m.right*d.width(),d.top+m.bottom*d.height());
            if(!m.noteOnly&&m.path!=null&&m.thick>0){
                Paint hp=new Paint(Paint.ANTI_ALIAS_FLAG);hp.setColor(m.color|0xFF000000);hp.setStyle(Paint.Style.STROKE);hp.setStrokeCap(Paint.Cap.ROUND);hp.setStrokeJoin(Paint.Join.ROUND);hp.setStrokeWidth(Math.max(2f,m.thick*d.height()));
                android.graphics.Path line=new android.graphics.Path();for(int i=0;i+1<m.path.length;i+=2){float x=d.left+m.path[i]*d.width(),y=d.top+m.path[i+1]*d.height();if(i==0)line.moveTo(x,y);else line.lineTo(x,y);}
                int save=c.saveLayerAlpha(d.left,d.top,d.right,d.bottom,Math.max(8,Color.alpha(m.color)));c.drawPath(line,hp);c.restoreToCount(save);
            }else if(!m.noteOnly){p.setColor(m.color);c.drawRect(b,p);}
            if(m.visible&&m.note!=null&&!m.note.isEmpty()){
                RectF note=new RectF(b.right,b.top,Math.min(d.right,b.right+d.width()*.35f),Math.min(d.bottom,b.top+d.height()*.12f));
                p.setColor(0xFFFFF7D6);c.drawRect(note,p);text(c,m.minimized?"메모":m.note,note,d.width()*.023f,0xFF1C1C1E);
            }
        }
        strokes(c,d,store,page);
        for(AnnotationStore.TranslationNote n:store.translations)if(n.page==page&&n.visible){
            RectF box=new RectF(d.left+n.right*d.width(),d.top+n.top*d.height(),Math.min(d.right,d.left+n.right*d.width()+d.width()*.35f),Math.min(d.bottom,d.top+n.top*d.height()+d.height()*.12f));
            p.setColor(0xFFF3E8FF);c.drawRect(box,p);text(c,n.minimized?"번역":n.translated,box,d.width()*.023f,0xFF1C1C1E);
        }
        elements(context,c,d,store,page);
    }
}
