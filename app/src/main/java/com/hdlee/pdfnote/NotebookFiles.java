package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Color;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.*;
import java.nio.file.*;
import java.util.Locale;

final class NotebookFiles {
    static final String[] PAPER_NAMES={"백지","줄노트 (보통)","모눈종이","법률 노트 (빨간 여백선)","줄노트 (좁게)","줄노트 (넓게)","점 격자","코넬 노트","오선지","내 PDF·이미지 서식","금감원노트","금감원노트_칸나누기","리갈노트"};
    /** Order shown in the paper list: the bundled form templates first, then the plain papers. Kinds 10-12 are PDFs shipped in assets/templates. */
    static final int[] PAPER_ORDER={10,11,12,0,1,2,3,4,5,6,7,8,9};
    static final String[] BUILTIN_TEMPLATES={"note_lines.pdf","note_two_column.pdf","note_legal.pdf"};
    static final int CUSTOM=9;
    static final int[] COLORS={Color.WHITE,0xFFFFF9E8,0xFFFFF6B0,0xFFEFF6FF,0xFFF0F8EE,0xFFFFF0F4,0xFFEDEFF2,0xFFF3ECFF,0xFF1C1C1E};
    static final String[] COLOR_NAMES={"흰색","크림","리갈 옐로","하늘","연두","분홍","회색","연보라","검정"};
    static final class Paper {
        final int kind,color;
        /** For {@link #CUSTOM}: the PDF or image used as the page background (a copy kept inside the app). */
        final File template;
        Paper(int kind,int color){this(kind,color,null);}
        Paper(int kind,int color,File template){if(kind<0||kind>CUSTOM)throw new IllegalArgumentException("종이 형식");if(kind==CUSTOM&&(template==null||!template.isFile()))throw new IllegalArgumentException("서식 파일을 먼저 고르세요");this.kind=kind;this.color=color;this.template=kind==CUSTOM?template:null;}
        String spec(){return kind+":"+color+(template==null?"":":"+template.getAbsolutePath());}
        static Paper parse(String value){String[] p=value.split(":",3);return new Paper(Integer.parseInt(p[0]),Integer.parseInt(p[1]),p.length>2?new File(p[2]):null);}
    }
    /** One ruling mark of a paper type in PDF points (origin bottom-left): x1,y1,x2,y2,style. style 0 = faint line, 1 = red, 2 = dark, 3 = dot (x1==x2). */
    static java.util.List<float[]> layout(int kind,float w,float h){
        java.util.List<float[]> out=new java.util.ArrayList<>();
        switch(kind){
            case 1:case 4:case 5:{float step=kind==1?25:kind==4?18:32;for(float y=h-54;y>=42;y-=step)out.add(new float[]{36,y,w-36,y,0});break;}
            case 2:{for(float y=h-54;y>=42;y-=18)out.add(new float[]{36,y,w-36,y,0});for(float x=36;x<=w-36;x+=18)out.add(new float[]{x,42,x,h-54,0});break;}
            case 3:{out.add(new float[]{36,h-72,w-36,h-72,1});for(float y=h-96;y>=48;y-=22)out.add(new float[]{36,y,w-36,y,0});out.add(new float[]{74,h-36,74,36,1});out.add(new float[]{78,h-36,78,36,1});break;}
            case 6:{for(float y=h-54;y>=42;y-=18)for(float x=36;x<=w-36;x+=18)out.add(new float[]{x,y,x,y,3});break;}
            case 7:{out.add(new float[]{36,h-60,w-36,h-60,2});for(float y=h-84;y>=190;y-=24)out.add(new float[]{36,y,w-36,y,0});out.add(new float[]{160,h-60,160,190,2});out.add(new float[]{36,190,w-36,190,2});for(float y=166;y>=48;y-=24)out.add(new float[]{36,y,w-36,y,0});break;}
            case 8:{for(float top=h-60;top>=110;top-=72)for(int i=0;i<5;i++)out.add(new float[]{36,top-i*8,w-36,top-i*8,2});break;}
            default:break;
        }
        return out;
    }
    /** First rule (distance from page top, pt) and the rule pitch (pt) of a paper, for fitting typed text to its lines; null when it has no ruled lines. */
    static float[] ruler(int kind){
        switch(kind){
            case 1:return new float[]{54,25};case 4:return new float[]{54,18};case 5:return new float[]{54,32};case 2:return new float[]{54,18};
            case 3:return new float[]{96,22};case 7:return new float[]{84,24};
            case 10:return new float[]{69.88f,32.6f};case 11:return new float[]{116.9f,26.95f};case 12:return new float[]{29.49f,26.97f};
            default:return null;
        }
    }
    /** Key into {@link #ruler}: 1-8 for plain papers, 10-12 for the bundled form templates (stored as CUSTOM papers named builtin-*.pdf), -1 without ruled lines. */
    static int rulerKind(Paper p){
        if(p==null)return -1;if(p.kind!=CUSTOM)return ruler(p.kind)!=null?p.kind:-1;
        if(p.template!=null){String n=p.template.getName();for(int i=0;i<BUILTIN_TEMPLATES.length;i++)if(n.equals("builtin-"+BUILTIN_TEMPLATES[i]))return 10+i;}
        return -1;
    }
    /** The copy of a bundled form template kept in the app's files (created from the assets on first use). */
    static File builtinTemplate(Context c,String name)throws IOException{
        boolean known=false;for(String t:BUILTIN_TEMPLATES)if(t.equals(name))known=true;if(!known)throw new IOException("서식 이름");
        File dir=new File(c.getFilesDir(),"templates");dir.mkdirs();File out=new File(dir,"builtin-"+name);
        if(!out.isFile()||out.length()==0){try(java.io.InputStream in=c.getAssets().open("templates/"+name);java.io.OutputStream o=new java.io.FileOutputStream(out)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)o.write(b,0,n);}}
        return out;
    }
    static int[] ruleColor(int style){switch(style){case 1:return new int[]{232,140,140};case 2:return new int[]{120,130,140};case 3:return new int[]{150,160,170};default:return new int[]{185,195,205};}}
    static File root(Context c){File root=new File(c.getFilesDir(),"documents");root.mkdirs();return root;}
    static String name(String text)throws IOException{String n=text.trim();if(n.isEmpty()||n.equals(".")||n.equals("..")||n.length()>100||java.util.regex.Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]").matcher(n).find())throw new IOException("파일 이름에 사용할 수 없는 문자가 있습니다");return n;}
    static String pdfName(String title)throws IOException{String valid=name(title);return valid.toLowerCase(Locale.ROOT).endsWith(".pdf")?valid:valid+".pdf";}
    static File unique(File folder,String name)throws IOException{String valid=name(name);File file=new File(folder,valid);int count=1;int dot=valid.lastIndexOf('.');while(file.exists()){String stem=dot>0?valid.substring(0,dot):valid,ext=dot>0?valid.substring(dot):"";file=new File(folder,stem+" ("+(count++)+")"+ext);}return file;}
    static File blank(File folder,String title,int pages)throws IOException{return create(folder,title,pages,new Paper(0,Color.WHITE));}
    static File create(File folder,String title,int pages,Paper paper)throws IOException{return create(folder,title,pages,paper,false);}
    /** @param landscape A4 landscape instead of portrait (a form PDF keeps its own page size). */
    static File create(File folder,String title,int pages,Paper paper,boolean landscape)throws IOException{
        if(pages<1)throw new IOException("페이지 수가 올바르지 않습니다");File target=unique(folder,pdfName(title));File temp=File.createTempFile(".note-",".tmp",folder);
        try(PDDocument pdf=new PDDocument()){
            pdf.getDocumentInformation().setCustomMetadataValue("PDFNoteNotebook","true");
            pdf.getDocumentInformation().setCustomMetadataValue("PDFNotePaper",paper.spec());
            java.util.List<Closeable> open=new java.util.ArrayList<>();
            try{for(int i=0;i<pages;i++)addPaper(pdf,paper,-1,open,landscape?new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()):null);pdf.save(temp);}finally{for(Closeable c:open)try{c.close();}catch(IOException ignored){}}
            replace(temp,target);return target;
        }finally{temp.delete();}
    }
    static int append(Context context,File file,Paper paper)throws IOException{
        File temp=File.createTempFile(".page-",".tmp",file.getParentFile());int count;
        try{
            try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                if(!pdf.getCurrentAccessPermission().canModify())throw new IOException("페이지 추가가 허용되지 않는 PDF입니다");
                java.util.List<Closeable> open=new java.util.ArrayList<>();
                try{addPaper(pdf,paper,-1,open);count=pdf.getNumberOfPages();pdf.save(temp);}finally{for(Closeable c:open)try{c.close();}catch(IOException ignored){}}
            }
            replace(temp,file);return count;
        }finally{temp.delete();}
    }
    /** Inserts a blank paper page right after {@code afterIndex} (the last page when out of range). */
    static int insert(Context context,File file,Paper paper,int afterIndex)throws IOException{return insert(context,file,paper,afterIndex,0);}
    /** @param sizeMode 0 = same size and orientation as the page it follows, 1 = A4 portrait, 2 = A4 landscape (a form PDF keeps its own size). */
    static int insert(Context context,File file,Paper paper,int afterIndex,int sizeMode)throws IOException{
        File temp=File.createTempFile(".page-",".tmp",file.getParentFile());int count;
        try{
            try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                if(!pdf.getCurrentAccessPermission().canModify())throw new IOException("페이지 추가가 허용되지 않는 PDF입니다");
                java.util.List<Closeable> open=new java.util.ArrayList<>();
                try{int at=Math.min(afterIndex,pdf.getNumberOfPages()-1);addPaper(pdf,paper,at,open,pageSize(pdf,at,sizeMode));count=pdf.getNumberOfPages();pdf.save(temp);}finally{for(Closeable c:open)try{c.close();}catch(IOException ignored){}}
            }
            replace(temp,file);return count;
        }finally{temp.delete();}
    }
    /** Size of a new page: the reference page's visible size (rotation applied), or A4 portrait/landscape. */
    private static PDRectangle pageSize(PDDocument pdf,int refIndex,int sizeMode){
        if(sizeMode==1)return PDRectangle.A4;if(sizeMode==2)return new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth());
        if(refIndex<0||refIndex>=pdf.getNumberOfPages())return PDRectangle.A4;
        PDPage ref=pdf.getPage(refIndex);PDRectangle box=ref.getCropBox()!=null?ref.getCropBox():ref.getMediaBox();int rotation=((ref.getRotation()%360)+360)%360;
        float w=box.getWidth(),h=box.getHeight();if(w<=0||h<=0)return PDRectangle.A4;return rotation==90||rotation==270?new PDRectangle(h,w):new PDRectangle(w,h);
    }
    /** Deletes one page and returns the remaining page count. The last remaining page cannot be deleted. */
    static int delete(Context context,File file,int index)throws IOException{
        File temp=File.createTempFile(".page-",".tmp",file.getParentFile());int count;
        try{
            try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                if(!pdf.getCurrentAccessPermission().canModify())throw new IOException("페이지 삭제가 허용되지 않는 PDF입니다");
                if(index<0||index>=pdf.getNumberOfPages())throw new IOException("삭제할 페이지가 없습니다");
                if(pdf.getNumberOfPages()<=1)throw new IOException("마지막 한 페이지는 삭제할 수 없습니다");
                pdf.removePage(index);count=pdf.getNumberOfPages();pdf.save(temp);
            }
            replace(temp,file);return count;
        }finally{temp.delete();}
    }
    /** Puts a cover (표지) in front of the document: the cover picture is drawn as page 1 in the size of the page it precedes. If the document already starts with a cover made here,
     *  that page is replaced instead (replaced[0] = true) so the notes on the other pages keep their numbers. Returns the new page count. */
    static int setCover(Context context,File file,CoverRenderer.Spec spec,boolean[] replaced)throws IOException{
        File temp=File.createTempFile(".cover-",".tmp",file.getParentFile());int count;
        try{
            try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                if(!pdf.getCurrentAccessPermission().canModify())throw new IOException("표지 추가가 허용되지 않는 PDF입니다");
                if(pdf.getNumberOfPages()<1)throw new IOException("빈 PDF입니다");
                com.tom_roush.pdfbox.cos.COSName mark=com.tom_roush.pdfbox.cos.COSName.getPDFName("PDFNoteCover");boolean replace=pdf.getPage(0).getCOSObject().getBoolean(mark,false);
                PDRectangle size=pageSize(pdf,0,0);float width=size.getWidth(),height=size.getHeight();
                int pixelsW=1240,pixelsH=Math.max(600,Math.round(pixelsW*height/width));
                android.graphics.Bitmap bitmap=CoverRenderer.render(spec,pixelsW,pixelsH);
                try{
                    PDPage page=new PDPage(new PDRectangle(width,height));page.getCOSObject().setBoolean(mark,true);PDPage first=pdf.getPage(0);
                    pdf.getPages().insertBefore(page,first);
                    try(PDPageContentStream canvas=new PDPageContentStream(pdf,page)){canvas.drawImage(com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(pdf,bitmap,.92f),0,0,width,height);}
                    if(replace)pdf.removePage(first);
                    replaced[0]=replace;
                }finally{bitmap.recycle();}
                count=pdf.getNumberOfPages();pdf.save(temp);
            }
            replace(temp,file);return count;
        }finally{temp.delete();}
    }
    /** Number of pages of a PDF. */
    static int pageCount(Context context,File file)throws IOException{
        try(PDDocument pdf=PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){return pdf.getNumberOfPages();}
    }
    /** Writes [target] made of the listed 0-based pages of each source PDF, in order. Returns the page count. */
    static int compose(Context context,File target,java.util.List<File> sources,java.util.List<int[]> pages)throws IOException{
        File temp=File.createTempFile(".compose-",".tmp",target.getParentFile());java.util.List<PDDocument> open=new java.util.ArrayList<>();int count;
        try{
            try(PDDocument out=new PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                for(int i=0;i<sources.size();i++){
                    PDDocument source=PDDocument.load(sources.get(i),MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()));open.add(source);
                    for(int page:pages.get(i)){if(page<0||page>=source.getNumberOfPages())throw new IOException("없는 페이지입니다: "+(page+1));out.importPage(source.getPage(page));}
                }
                count=out.getNumberOfPages();if(count==0)throw new IOException("만들 페이지가 없습니다");
                out.save(temp);
            }
            replace(temp,target);return count;
        }finally{for(PDDocument d:open)try{d.close();}catch(IOException ignored){}temp.delete();}
    }
    static void replace(File temp,File target)throws IOException{
        try{Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException error){Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void addPaper(PDDocument pdf,Paper paper,int afterIndex,java.util.List<Closeable> open)throws IOException{addPaper(pdf,paper,afterIndex,open,null);}
    private static void addPaper(PDDocument pdf,Paper paper,int afterIndex,java.util.List<Closeable> open,PDRectangle size)throws IOException{
        PDPage page;
        if(paper.kind==CUSTOM&&isPdf(paper.template)){
            PDDocument source=PDDocument.load(paper.template);open.add(source);
            if(source.getNumberOfPages()<1)throw new IOException("서식 PDF에 페이지가 없습니다");
            page=pdf.importPage(source.getPage(0));
            if(afterIndex>=0&&afterIndex<pdf.getNumberOfPages()-1){pdf.removePage(page);pdf.getPages().insertAfter(page,pdf.getPage(afterIndex));}
            return;
        }
        page=new PDPage(size==null?PDRectangle.A4:size);if(afterIndex<0||afterIndex>=pdf.getNumberOfPages())pdf.addPage(page);else pdf.getPages().insertAfter(page,pdf.getPage(afterIndex));float width=page.getMediaBox().getWidth(),height=page.getMediaBox().getHeight();
        try(PDPageContentStream canvas=new PDPageContentStream(pdf,page)){
            canvas.setNonStrokingColor(Color.red(paper.color),Color.green(paper.color),Color.blue(paper.color));canvas.addRect(0,0,width,height);canvas.fill();
            if(paper.kind==CUSTOM){
                android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeFile(paper.template.getAbsolutePath());
                if(bitmap==null)throw new IOException("서식 이미지를 읽을 수 없습니다");
                try{
                    float scale=Math.min(width/bitmap.getWidth(),height/bitmap.getHeight()),w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
                    canvas.drawImage(com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(pdf,bitmap,.9f),(width-w)/2,(height-h)/2,w,h);
                }finally{bitmap.recycle();}
                return;
            }
            boolean dark=Color.red(paper.color)+Color.green(paper.color)+Color.blue(paper.color)<300;
            for(int style=0;style<=3;style++){
                boolean any=false;int[] c=ruleColor(style);if(dark)c=new int[]{Math.min(255,c[0]/2+90),Math.min(255,c[1]/2+90),Math.min(255,c[2]/2+90)};
                canvas.setStrokingColor(c[0],c[1],c[2]);canvas.setLineWidth(style==3?1.6f:style==2?.7f:.45f);canvas.setLineCapStyle(style==3?1:0);
                for(float[] seg:layout(paper.kind,width,height))if((int)seg[4]==style){canvas.moveTo(seg[0],seg[1]);canvas.lineTo(seg[2],seg[3]);any=true;}
                if(any)canvas.stroke();
            }
        }
    }
    static boolean isPdf(File file){return file!=null&&file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf");}
}
