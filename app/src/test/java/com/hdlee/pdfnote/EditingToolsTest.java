package com.hdlee.pdfnote;

import android.graphics.*;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class EditingToolsTest {
    private AnnotationStore open(){AnnotationStore st=new AnnotationStore(RuntimeEnvironment.getApplication());st.open(Uri.parse("content://undo/"+UUID.randomUUID()));return st;}
    private static AnnotationStore.PageElement text(String value,int page){AnnotationStore.PageElement e=new AnnotationStore.PageElement();e.text=value;e.page=page;return e;}

    @Test public void undoAndRedoCoverEveryKindOfEdit(){
        AnnotationStore st=open();assertFalse(st.canUndo());assertFalse(st.canRedo());
        AnnotationStore.PageElement a=text("A",0);st.elements.add(a);st.save();
        AnnotationStore.Mark note=new AnnotationStore.Mark();note.page=2;note.left=.1f;note.top=.1f;note.right=.3f;note.bottom=.2f;st.marks.add(note);st.save();
        st.bookmarks.add(4);st.save();
        assertTrue(st.canUndo());
        st.undo();assertTrue("책갈피 되돌리기",st.bookmarks.isEmpty());assertEquals(1,st.marks.size());
        assertEquals("메모가 있던 페이지로 안내",2,st.undo());assertTrue(st.marks.isEmpty());assertEquals(1,st.elements.size());
        assertTrue(st.canRedo());
        assertEquals(2,st.redo());assertEquals(1,st.marks.size());
        st.redo();assertTrue(st.bookmarks.contains(4));assertFalse(st.canRedo());
        // moving or editing an existing object is one step too
        AnnotationStore.PageElement moved=st.elements.get(0);moved.left=.3f;moved.right=.9f;st.save();st.undo();assertEquals(.1f,st.elements.get(0).left,.001f);
        // a new edit forgets the redo steps
        st.elements.add(text("B",1));st.save();assertFalse(st.canRedo());
        // deleting is undone as well
        st.elements.remove(1);st.save();st.undo();assertEquals("B",st.elements.get(1).text);
        while(st.canUndo())st.undo();assertTrue(st.elements.isEmpty()&&st.marks.isEmpty()&&st.bookmarks.isEmpty());assertEquals(-1,st.undo());
    }
    @Test public void unchangedSaveAddsNoUndoStepAndClearHistoryForgets(){
        AnnotationStore st=open();st.elements.add(text("A",0));st.save();st.save();st.save();
        st.undo();assertFalse("같은 내용을 다시 저장해도 단계가 늘지 않습니다",st.canUndo());
        st.redo();st.clearHistory();assertFalse(st.canUndo());assertFalse(st.canRedo());assertEquals(1,st.elements.size());
    }
    @Test public void historyIsBounded(){
        AnnotationStore st=open();for(int i=0;i<120;i++){st.elements.add(text("x"+i,0));st.save();}
        int steps=0;while(st.canUndo()){st.undo();steps++;}assertTrue("단계는 한도까지만 보관합니다",steps>=20&&steps<=60);
    }
    @Test public void tapeIsATranslucentFlatRibbonThatSurvivesSaving()throws Exception{
        AnnotationStore.InkStroke s=new AnnotationStore.InkStroke();s.pen=AnnotationPainter.TAPE;s.color=0xB3FF8FB1;s.width=.06f;
        for(int i=0;i<=20;i++)s.points.add(new AnnotationStore.InkPoint(.1f+.8f*i/20f,.5f,i%2==0?.2f:1f));
        assertEquals(AnnotationPainter.TAPE,AnnotationStore.InkStroke.fromJson(s.toJson()).pen);
        Bitmap b=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);AnnotationPainter.stroke(new Canvas(b),new RectF(0,0,200,100),s);
        assertNotEquals("테이프가 칠해집니다",Color.WHITE,b.getPixel(100,50));
        int wobble=0;for(int x=30;x<170;x++)if(b.getPixel(x,50)!=b.getPixel(100,50))wobble++;assertEquals("필압과 상관없이 같은 굵기·농도",0,wobble);
        assertEquals("테이프 바깥은 그대로 흰색",Color.WHITE,b.getPixel(100,5));
    }
    @Test public void folderBackupHoldsOnlyThatFolderAndRestoresInPlace()throws Exception{
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());LibraryRepository library=new LibraryRepository(RuntimeEnvironment.getApplication());
        File base=Files.createTempDirectory("folder-backup").toFile();File[] assets=new File[]{new File(base,"images"),new File(base,"recordings"),new File(base,"videos")};for(File d:assets)d.mkdirs();
        NotebookFiles.Paper paper=new NotebookFiles.Paper(NotebookFiles.CUSTOM,NotebookFiles.COLORS[0],NotebookFiles.builtinTemplate(RuntimeEnvironment.getApplication(),"note_lines.pdf"));
        File one=library.createFolder(library.root,"가"),two=library.createFolder(library.root,"나");
        File inOne=library.createNote(one,"문서A",paper),inTwo=library.createNote(two,"문서B",paper);
        String used="0123456789abcdef0123456789abcdef0123.png",unused="fedcba9876543210fedcba9876543210fedc.png";
        Files.write(new File(assets[0],used).toPath(),new byte[]{1});Files.write(new File(assets[0],unused).toPath(),new byte[]{2});
        AnnotationStore st=new AnnotationStore(RuntimeEnvironment.getApplication());st.open(Uri.fromFile(inOne));
        AnnotationStore.PageElement pic=new AnnotationStore.PageElement();pic.kind="image";pic.asset=used;st.elements.add(pic);st.save();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        assertEquals(1,LibraryBackup.write(RuntimeEnvironment.getApplication(),library,one,assets,out,null));
        int pdfs=0;java.util.List<String> names=new java.util.ArrayList<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))){for(ZipEntry e;(e=z.getNextEntry())!=null;){names.add(e.getName());if(e.getName().startsWith("docs/")&&e.getName().endsWith(".pdf"))pdfs++;}}
        assertEquals(1,pdfs);assertTrue(names.contains("assets/"+used));assertFalse("다른 폴더의 첨부는 담지 않습니다",names.contains("assets/"+unused));
        assertTrue(inOne.delete());assertTrue(inTwo.delete());
        LibraryBackup.Result r=LibraryBackup.restore(RuntimeEnvironment.getApplication(),library,assets,new ByteArrayInputStream(out.toByteArray()),true,f->false,null);
        assertEquals(1,r.documents);assertTrue("원래 폴더에 복원",inOne.isFile());assertFalse(inTwo.exists());
    }
    @Test public void networkAddressListSkipsMobileData(){
        for(String a:DeviceSync.localAddresses())assertTrue(a.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"));
    }
}
