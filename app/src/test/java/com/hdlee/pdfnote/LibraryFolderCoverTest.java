package com.hdlee.pdfnote;

import android.graphics.*;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Folder cut / copy / rename, folder names made by 폴더 일괄 불러오기, table cell sizes and 표지 만들기. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LibraryFolderCoverTest {
    private LibraryRepository library;
    private File folder;
    @Before public void setup()throws Exception{PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());library=new LibraryRepository(RuntimeEnvironment.getApplication());folder=library.createFolder(library.root,"Folder "+System.nanoTime());}

    private void note(File file,String text)throws Exception{
        AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.fromFile(file));
        AnnotationStore.PageElement element=new AnnotationStore.PageElement();element.text=text;store.elements.add(element);store.save();
    }
    private String noteOf(File file){AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.fromFile(file));return store.elements.isEmpty()?null:store.elements.get(0).text;}

    @Test public void folderMoveCopyAndRenameCarryDocumentsNotesAndColour()throws Exception{
        File source=library.createFolder(folder,"Source");File inner=library.createFolder(source,"Inner");
        File a=library.createNote(source,"A",new NotebookFiles.Paper(1,Color.WHITE));File b=library.createNote(inner,"B",new NotebookFiles.Paper(2,Color.WHITE));note(a,"note A");note(b,"note B");library.folderColor(source,LibraryRepository.FOLDER_COLORS[2]);
        File target=library.createFolder(folder,"Target");
        List<File[]> changes=new ArrayList<>();
        File copy=library.transferFolder(source,target,"Source",false,changes);
        assertEquals(2,changes.size());assertTrue(a.exists());assertTrue(b.exists());assertEquals("note A",noteOf(new File(copy,"A.pdf")));assertEquals("note B",noteOf(new File(new File(copy,"Inner"),"B.pdf")));assertEquals(LibraryRepository.FOLDER_COLORS[2],library.folderColor(copy));
        File again=library.transferFolder(source,target,"Source",false,null);assertEquals("Source (1)",again.getName());
        changes.clear();File renamed=library.transferFolder(source,folder,"Renamed",true,changes);
        assertEquals("Renamed",renamed.getName());assertFalse(source.exists());assertEquals(2,changes.size());assertEquals("note A",noteOf(new File(renamed,"A.pdf")));assertEquals("note B",noteOf(new File(new File(renamed,"Inner"),"B.pdf")));assertEquals(1,library.paper(new File(renamed,"A.pdf")).kind);
        assertEquals(LibraryRepository.FOLDER_COLORS[2],library.folderColor(renamed));
        try{library.transferFolder(renamed,new File(renamed,"Inner"),"Loop",true,null);fail();}catch(IOException expected){}
        File clash=library.createFolder(folder,"Clash");try{library.transferFolder(renamed,folder,"Clash",true,null);fail();}catch(IOException expected){}assertTrue(renamed.exists());assertTrue(clash.exists());
        try{library.transferFolder(library.root,folder,"Root",true,null);fail();}catch(IOException expected){}
    }
    @Test public void trashingAFolderSendsItsDocumentsToTheTrashAndRemovesTheFolder()throws Exception{
        File source=library.createFolder(folder,"Gone");File a=library.createNote(source,"A",new NotebookFiles.Paper(0,Color.WHITE));
        List<File> removed=new ArrayList<>();int before=library.trashItems().size();library.trashFolder(source,removed);
        assertEquals(Collections.singletonList(a),removed);assertFalse(source.exists());assertEquals(before+1,library.trashItems().size());
    }
    @Test public void importedFolderNamesAreAlwaysValid()throws Exception{
        assertEquals("msf_1234",LibraryRepository.folderName("msf:1234","x"));assertEquals("가져온 폴더",LibraryRepository.folderName("  ","가져온 폴더"));assertEquals("가져온 폴더",LibraryRepository.folderName("...","가져온 폴더"));
        assertEquals("a_b_c",LibraryRepository.folderName("a/b*c","x"));assertEquals(100,LibraryRepository.folderName(new String(new char[300]).replace('\0','가'),"x").length());assertEquals("수업 자료",LibraryRepository.folderName(" 수업 자료 ","x"));
        File made=library.createFolder(folder,LibraryRepository.folderName("강의: 1주차?","x"));assertTrue(made.isDirectory());
    }

    @Test public void tableCellSizesSurviveSerializeAndDragKeepsTheSum()throws Exception{
        Shapes.Table t=Shapes.Table.create(3,4,0xFF000000,0,0);t.cells[0]="a";
        assertFalse(t.serialize().contains("\n@"));
        t.dragBorder(true,t.colWidths(),0,.15f);t.dragBorder(false,t.rowHeights(),1,-.1f);
        String text=t.serialize();assertTrue(text.contains("\n@"));assertTrue(Shapes.validTable(text));
        Shapes.Table back=Shapes.Table.parse(text);float[] cols=back.colWidths(),rows=back.rowHeights();
        assertEquals(.4f,cols[0],.001f);assertEquals(.1f,cols[1],.001f);assertEquals(1f,cols[0]+cols[1]+cols[2]+cols[3],.001f);assertEquals(.2333f,rows[1],.001f);assertEquals(.4333f,rows[2],.001f);assertEquals("a",back.cells[0]);
        t.dragBorder(true,back.colWidths(),0,-5f);assertEquals(Shapes.Table.MIN_CELL,t.colWidths()[0],.0001f);
        Shapes.Table withColours=Shapes.Table.parse(text);withColours.bg[1]=0xFFFF0000;Shapes.Table both=Shapes.Table.parse(withColours.serialize());assertEquals(0xFFFF0000,both.bg[1]);assertEquals(.4f,both.colWidths()[0],.001f);
        assertEquals(.4f,withColours.resized(3,4).colWidths()[0],.001f);assertEquals(.2f,withColours.resized(3,5).colWidths()[0],.001f);
        Bitmap bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Shapes.drawTable(new Canvas(bitmap),new RectF(20,20,380,280),text,400);
        assertNotEquals(Color.WHITE,bitmap.getPixel(20+Math.round(.4f*360),150));
    }
    @Test public void legacyTablesWithoutSizesStayEqual()throws Exception{
        Shapes.Table t=Shapes.Table.parse("2,2,FF000000,00000000,00000000\na\nb\nc\nd");assertEquals(.5f,t.colWidths()[0],.0001f);assertEquals(.5f,t.rowHeights()[1],.0001f);
        Shapes.Table bad=Shapes.Table.parse("2,2,FF000000,00000000,00000000\na\nb\nc\nd\n@0.9,0.1,0.0;0.5,0.5");assertEquals(.5f,bad.colWidths()[0],.0001f);
    }

    @Test public void coverBecomesPageOneAndIsReplacedNotStacked()throws Exception{
        File file=library.createNote(folder,"Cover",new NotebookFiles.Paper(1,Color.WHITE));library.append(file,new NotebookFiles.Paper(1,Color.WHITE));
        boolean[] replaced={true};int count=library.setCover(file,new CoverRenderer.Spec(2,CoverRenderer.ACCENTS[1],"내 노트","2026 가을","이현덕"),replaced);
        assertEquals(3,count);assertFalse(replaced[0]);
        count=library.setCover(file,new CoverRenderer.Spec(6,CoverRenderer.ACCENTS[0],"새 제목","","") ,replaced);assertEquals(3,count);assertTrue(replaced[0]);
        try(PDDocument pdf=PDDocument.load(file)){assertEquals(3,pdf.getNumberOfPages());assertTrue(pdf.getPage(0).getCOSObject().getBoolean(com.tom_roush.pdfbox.cos.COSName.getPDFName("PDFNoteCover"),false));assertFalse(pdf.getPage(1).getCOSObject().getBoolean(com.tom_roush.pdfbox.cos.COSName.getPDFName("PDFNoteCover"),false));}
    }
    @Test public void everyCoverTemplateDrawsSomethingInEveryColour(){
        for(int template=0;template<CoverRenderer.TEMPLATE_NAMES.length;template++)for(int accent:CoverRenderer.ACCENTS){
            Bitmap bitmap=CoverRenderer.render(new CoverRenderer.Spec(template,accent,"아주 긴 제목이 들어가는 경우에도 글자가 표지 안에 들어가야 합니다 "+template,"부제목","이름"),310,438);
            assertEquals(310,bitmap.getWidth());boolean drawn=false;for(int y=0;y<bitmap.getHeight()&&!drawn;y+=7)for(int x=0;x<bitmap.getWidth();x+=7)if(bitmap.getPixel(x,y)!=bitmap.getPixel(0,0)){drawn=true;break;}
            assertTrue("template "+template,drawn);
        }
        assertNotNull(CoverRenderer.render(new CoverRenderer.Spec(0,CoverRenderer.ACCENTS[0],"","",""),310,438));
    }
}
