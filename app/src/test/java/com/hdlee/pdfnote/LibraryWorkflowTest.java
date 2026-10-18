package com.hdlee.pdfnote;

import android.app.Activity;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LibraryWorkflowTest {
    private LibraryRepository library;
    private File folder;
    @Before public void setup()throws Exception{PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());library=new LibraryRepository(RuntimeEnvironment.getApplication());folder=library.createFolder(library.root,"Tests-"+UUID.randomUUID());}
    @Test public void notebookStartsAtOnePageAndAppendPreservesPaperAndAnnotations()throws Exception{
        File file=library.createNote(folder,"Study",new NotebookFiles.Paper(2,NotebookFiles.COLORS[1]));
        AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.fromFile(file));AnnotationStore.PageElement text=new AnnotationStore.PageElement();text.text="keep my notes";store.elements.add(text);store.save();
        try(PDDocument pdf=PDDocument.load(file)){assertEquals(1,pdf.getNumberOfPages());assertEquals("true",pdf.getDocumentInformation().getCustomMetadataValue("PDFNoteNotebook"));}
        assertEquals(2,library.append(file,library.paper(file)));
        try(PDDocument pdf=PDDocument.load(file)){assertEquals(2,pdf.getNumberOfPages());assertEquals(contents(pdf.getPage(0)),contents(pdf.getPage(1)));assertTrue(contents(pdf.getPage(1)).contains(" l"));}
        AnnotationStore restored=new AnnotationStore(RuntimeEnvironment.getApplication());restored.open(Uri.fromFile(file));assertEquals("keep my notes",restored.elements.get(0).text);
    }
    @Test public void modifiedTimeInSecondsIsReadAsMilliseconds(){
        assertEquals(1_792_000_000_000L,LibraryRepository.millis(1_792_000_000L));assertEquals(1_792_000_000_000L,LibraryRepository.millis(1_792_000_000_000L));assertEquals(0L,LibraryRepository.millis(0L));
    }
    @Test public void allPaperKindsAreActualPdfPages()throws Exception{
        for(int kind=0;kind<3;kind++){File file=library.createNote(folder,"Paper "+kind,new NotebookFiles.Paper(kind,NotebookFiles.COLORS[3]));try(PDDocument pdf=PDDocument.load(file)){String content=contents(pdf.getPage(0));assertTrue(content.contains(" re"));assertEquals(kind!=0,content.contains(" l"));assertEquals(1,pdf.getNumberOfPages());}}
    }
    @Test public void importCopiesOriginalAndFollowsRenameWithoutDuplicates()throws Exception{
        File external=File.createTempFile("external-",".pdf",RuntimeEnvironment.getApplication().getCacheDir());try(PDDocument pdf=new PDDocument()){PDPage page=new PDPage();pdf.addPage(page);try(PDPageContentStream canvas=new PDPageContentStream(pdf,page)){canvas.beginText();canvas.setFont(PDType1Font.HELVETICA,12);canvas.newLineAtOffset(40,700);canvas.showText("Original source text");canvas.endText();}pdf.save(external);}
        Uri source=Uri.fromFile(external);AnnotationStore original=new AnnotationStore(RuntimeEnvironment.getApplication());original.open(source);AnnotationStore.PageElement element=new AnnotationStore.PageElement();element.text="typed annotation";original.elements.add(element);original.save();
        File imported=library.importPdf(source,"External.pdf",folder);assertTrue(library.managed(imported));assertTrue(external.exists());assertEquals(imported,library.importPdf(source,"External.pdf",folder));
        assertEquals(2,library.append(imported,new NotebookFiles.Paper(1,Color.WHITE)));
        try(PDDocument pdf=PDDocument.load(imported)){assertTrue(new PDFTextStripper().getText(pdf).contains("Original source text"));assertEquals(2,pdf.getNumberOfPages());}
        File renamed=library.transfer(imported,folder,"Renamed",true);assertEquals(renamed,library.imported(source));assertFalse(imported.exists());external.delete();assertTrue(renamed.exists());
        AnnotationStore saved=new AnnotationStore(RuntimeEnvironment.getApplication());saved.open(Uri.fromFile(renamed));assertEquals("typed annotation",saved.elements.get(0).text);
    }
    @Test public void copyAndMoveKeepLinkedNotesAndDoNotOverwrite()throws Exception{
        File file=library.createNote(folder,"Notebook",new NotebookFiles.Paper(1,NotebookFiles.COLORS[2]));AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.fromFile(file));AnnotationStore.StudyEntry entry=new AnnotationStore.StudyEntry();entry.text="study link";store.studyEntries.add(entry);store.bookmarks.add(0);store.save();
        File copy=library.transfer(file,folder,file.getName(),false);assertEquals("Notebook (1).pdf",copy.getName());assertTrue(file.exists());assertEquals(1,library.paper(copy).kind);
        File nested=library.createFolder(folder,"Nested");File moved=library.transfer(copy,nested,copy.getName(),true);assertFalse(copy.exists());AnnotationStore restored=new AnnotationStore(RuntimeEnvironment.getApplication());restored.open(Uri.fromFile(moved));assertEquals("study link",restored.studyEntries.get(0).text);assertTrue(restored.bookmarks.contains(0));
        try{library.transfer(file,nested,moved.getName(),true);fail();}catch(IOException expected){}assertTrue(file.exists());assertTrue(moved.exists());assertNull(library.paper(copy));
    }
    @Test public void invalidAppendAndUnsafeDestinationLeaveOriginalIntact()throws Exception{
        File file=new File(folder,"Broken.pdf");Files.write(file.toPath(),new byte[]{1,2,3});try{library.append(file,new NotebookFiles.Paper(0,Color.WHITE));fail();}catch(IOException expected){}assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(file.toPath()));
        try{library.createFolder(folder,"a\nb\nc");fail();}catch(IOException expected){}try{library.createFolder(folder,"../outside");fail();}catch(IOException expected){}try{library.transfer(file,RuntimeEnvironment.getApplication().getCacheDir(),"bad",true);fail();}catch(IOException expected){}assertTrue(file.exists());
    }
    @Test public void shelfHasFolderTreeDocumentActionsAndSelectablePaper()throws Exception{
        folder.delete();folder=library.createFolder(library.root,"업무노트");File colored=library.createFolder(folder,"자료");library.folderColor(colored,LibraryRepository.FOLDER_COLORS[2]);library.viewMode(0);library.createNote(folder,"회의 노트",new NotebookFiles.Paper(0,Color.WHITE));Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        LibraryDialog dialog=new LibraryDialog(activity,library,folder,new LibraryDialog.Actions(){public void open(File f){}public void importFiles(File f){}public void newNote(File f,Runnable r){}public void changed(File a,File b,boolean m){}public void selectedFolder(File f){}});dialog.show();View root=dialog.getWindow().getDecorView().findViewWithTag("library_root");assertNotNull(root);description(root,"폴더 트리 보기").performClick();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(980,View.MeasureSpec.EXACTLY));root.layout(0,0,720,980);
        assertNotNull(root.findViewWithTag("document:회의 노트.pdf"));assertEquals("폴더 트리 서랍이 열립니다",View.VISIBLE,root.findViewWithTag("library_drawer").getVisibility());assertNotNull(description(root,"문서함 메뉴"));assertNull("문서함 상단에는 뒤로(<) 단추가 없습니다",description(root,"문서함 닫기"));assertNotNull(description(root,"새로 만들기"));for(int i=0;i<3;i++){org.robolectric.shadows.ShadowLooper.idleMainLooper();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(980,View.MeasureSpec.EXACTLY));root.layout(0,0,720,980);}assertTrue(root.findViewWithTag("document:회의 노트.pdf").getHeight()>0);writeScreenshot(root,"library-shelf.png");assertTrue("길게 누르면 선택 모드가 됩니다",root.findViewWithTag("document:회의 노트.pdf").performLongClick());assertTrue(((CheckBox)description(root,"선택 회의 노트.pdf")).isChecked());assertNotNull(description(root,"선택 문서 삭제"));assertNotNull(description(root,"선택 문서 공유"));assertNotNull(description(root,"선택 문서 이동"));assertNotNull(description(root,"선택 문서 더 보기"));assertEquals("1개 선택됨",((android.widget.TextView)root.findViewWithTag("selection_count")).getText().toString());library.viewMode(2);java.lang.reflect.Method refresh=LibraryDialog.class.getDeclaredMethod("refreshGrid");refresh.setAccessible(true);refresh.invoke(dialog);for(int i=0;i<3;i++){org.robolectric.shadows.ShadowLooper.idleMainLooper();root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(980,View.MeasureSpec.EXACTLY));root.layout(0,0,720,980);}writeScreenshot(root,"library-list-selection.png");assertTrue(root.findViewWithTag("document:회의 노트.pdf").getHeight()<100);dialog.dismiss();library.viewMode(0);
        PaperChoiceView paper=new PaperChoiceView(activity);paper.findViewWithTag("paper_color:2").performClick();paper.selectKind(2);org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(NotebookFiles.COLORS[2],paper.paper().color);assertEquals(2,paper.paper().kind);paper.measure(View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(330,View.MeasureSpec.EXACTLY));paper.layout(0,0,380,330);writeScreenshot(paper,"paper-picker.png");
    }
    @Test public void sortingFilteringAndPreferencesSurviveReopening()throws Exception{
        File z=library.createNote(folder,"Zulu",new NotebookFiles.Paper(0,Color.WHITE));File a=library.createNote(folder,"Alpha",new NotebookFiles.Paper(0,Color.WHITE));File nested=library.createFolder(folder,"Nested");
        library.sortMode(0);assertEquals(Arrays.asList(nested,a,z),library.sorted(folder,""));library.sortMode(1);assertEquals(Arrays.asList(nested,z,a),library.sorted(folder,""));assertEquals(Collections.singletonList(a),library.sorted(folder,"ALP"));
        a.setLastModified(1000);z.setLastModified(2000);library.sortMode(2);assertEquals(Arrays.asList(nested,z,a),library.sorted(folder,""));AnnotationStore notes=new AnnotationStore(RuntimeEnvironment.getApplication());notes.open(Uri.fromFile(a));notes.save();assertEquals(Arrays.asList(nested,a,z),library.sorted(folder,""));
        library.opened(Uri.fromFile(z));library.sortMode(3);assertEquals(Arrays.asList(nested,z,a),library.sorted(folder,""));library.viewMode(2);library.folderColor(nested,LibraryRepository.FOLDER_COLORS[2]);LibraryRepository again=new LibraryRepository(RuntimeEnvironment.getApplication());assertEquals(3,again.sortMode());assertEquals(2,again.viewMode());assertEquals(LibraryRepository.FOLDER_COLORS[2],again.folderColor(nested));library.sortMode(0);library.viewMode(0);
    }
    @Test public void favoritesFollowDocumentsAndCanBePinnedToTheTop()throws Exception{
        File a=library.createNote(folder,"Alpha",new NotebookFiles.Paper(0,Color.WHITE));File z=library.createNote(folder,"Zulu",new NotebookFiles.Paper(0,Color.WHITE));
        assertTrue(library.favorites("").isEmpty());library.favorite(z,true);assertEquals(Collections.singletonList(z),library.favorites(""));assertTrue(library.favorite(z));
        library.sortMode(0);assertEquals(Arrays.asList(a,z),library.sorted(folder,""));library.pinFavorites(true);assertEquals(Arrays.asList(z,a),library.sorted(folder,""));library.pinFavorites(false);
        File nested=library.createFolder(folder,"Moved");File moved=library.transfer(z,nested,z.getName(),true);assertTrue("즐겨찾기는 이동해도 유지됩니다",library.favorite(moved));assertFalse(library.favorite(z));assertEquals(Collections.singletonList(moved),library.favorites(""));
        File trashed=library.trash(moved);assertTrue(library.favorites("").isEmpty());assertEquals(1,library.recent("",40).size());assertTrue(library.folderCount()>=2);library.favorite(trashed,false);
    }
    @Test public void shareProviderOnlyServesLibraryPdfs()throws Exception{
        File note=library.createNote(folder,"공유 노트",new NotebookFiles.Paper(0,Color.WHITE));Uri uri=ShareProvider.uri(RuntimeEnvironment.getApplication(),note);
        assertEquals("application/pdf",RuntimeEnvironment.getApplication().getContentResolver().getType(uri));
        try(android.os.ParcelFileDescriptor d=RuntimeEnvironment.getApplication().getContentResolver().openFileDescriptor(uri,"r")){assertTrue(d.getStatSize()>0);}
        try(android.database.Cursor c=RuntimeEnvironment.getApplication().getContentResolver().query(uri,null,null,null,null)){assertTrue(c.moveToFirst());assertEquals("공유 노트.pdf",c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)));}
        File outside=File.createTempFile("outside-",".pdf",RuntimeEnvironment.getApplication().getCacheDir());try{ShareProvider.uri(RuntimeEnvironment.getApplication(),outside);fail();}catch(IOException expected){}
        Uri traversal=new Uri.Builder().scheme("content").authority(RuntimeEnvironment.getApplication().getPackageName()+".shared").appendPath("..").appendPath("x.pdf").build();try{RuntimeEnvironment.getApplication().getContentResolver().openFileDescriptor(traversal,"r");fail();}catch(java.io.FileNotFoundException expected){}
        try{RuntimeEnvironment.getApplication().getContentResolver().openFileDescriptor(uri,"w");fail();}catch(java.io.FileNotFoundException expected){}
    }
    @Test public void audioNotesSurviveBackupAndRejectUnsafeAssets()throws Exception{
        AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.parse("content://audio/"+UUID.randomUUID()));
        AnnotationStore.PageElement clip=new AnnotationStore.PageElement();clip.kind="audio";clip.asset=UUID.randomUUID()+".m4a";clip.text="0:12";clip.left=.04f;clip.top=.03f;clip.right=.42f;clip.bottom=.075f;store.elements.add(clip);
        AnnotationStore restored=new AnnotationStore(RuntimeEnvironment.getApplication());restored.open(Uri.parse("content://audio/"+UUID.randomUUID()));restored.importJson(store.exportJson(Uri.parse("content://test"),"note"),1);
        assertEquals("audio",restored.elements.get(0).kind);assertEquals(clip.asset,restored.elements.get(0).asset);assertEquals("0:12",restored.elements.get(0).text);
        clip.asset="../../evil.m4a";try{restored.importJson(store.exportJson(Uri.parse("content://test"),"note"),1);fail();}catch(Exception expected){}
    }
    @Test public void trashRestoresAnnotationsPaperAndAvoidsNameCollision()throws Exception{
        File file=library.createNote(folder,"Keep",new NotebookFiles.Paper(2,NotebookFiles.COLORS[2]));AnnotationStore store=new AnnotationStore(RuntimeEnvironment.getApplication());store.open(Uri.fromFile(file));AnnotationStore.PageElement text=new AnnotationStore.PageElement();text.text="preserve";store.elements.add(text);store.save();library.trash(file);assertFalse(file.exists());assertFalse(library.list(library.root).stream().anyMatch(f->f.getName().equals(".trash")));
        LibraryRepository.TrashItem item=library.trashItems().stream().filter(t->t.name.equals("Keep.pdf")).findFirst().get();library.createNote(folder,"Keep",new NotebookFiles.Paper(0,Color.WHITE));File restored=library.restore(item);assertEquals("Keep (1).pdf",restored.getName());assertEquals(2,library.paper(restored).kind);AnnotationStore notes=new AnnotationStore(RuntimeEnvironment.getApplication());notes.open(Uri.fromFile(restored));assertEquals("preserve",notes.elements.get(0).text);assertFalse(item.file.exists());
    }
    @Test public void trashedImportIsNotReopenedUntilRestored()throws Exception{
        File external=File.createTempFile("source-",".pdf",RuntimeEnvironment.getApplication().getCacheDir());try(PDDocument pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(external);}Uri source=Uri.fromFile(external);File imported=library.importPdf(source,"Source",folder);File trashed=library.trash(imported);assertNull(library.imported(source));LibraryRepository.TrashItem item=library.trashItems().stream().filter(t->t.file.equals(trashed)).findFirst().get();File restored=library.restore(item);assertEquals(restored,library.imported(source));external.delete();
    }
    private static String contents(PDPage page)throws Exception{try(InputStream input=page.getContents();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=input.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static View description(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static void writeScreenshot(View view,String name)throws Exception{File dir=new File("build/test-screenshots");dir.mkdirs();Bitmap image=Bitmap.createBitmap(view.getMeasuredWidth(),view.getMeasuredHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);view.draw(canvas);try(OutputStream out=new FileOutputStream(new File(dir,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
