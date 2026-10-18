package com.hdlee.pdfnote;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

/** Renders the library on a tablet/desktop-wide screen and on a phone, for visual review of the Samsung Notes style. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LibraryScreenshotTest {
    private LibraryRepository library;
    private File folder;
    @Before public void setup()throws Exception{
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication());library=new LibraryRepository(RuntimeEnvironment.getApplication());
        folder=library.createFolder(library.root,"업무노트");File colored=library.createFolder(folder,"자료");library.folderColor(colored,LibraryRepository.FOLDER_COLORS[2]);library.createFolder(library.root,"개인");
        library.viewMode(0);for(String name:new String[]{"회의 노트","아이디어","주간 계획","독서 기록"})library.createNote(folder,name,new NotebookFiles.Paper(1,Color.WHITE));
    }
    @Test @Config(qualifiers="w1200dp-h800dp-mdpi") public void wideLibraryHasDockedNavigationAndNoBackButton()throws Exception{
        View root=open();layout(root,1200,800);
        assertNull("문서함 상단에는 뒤로(<) 단추가 없습니다",description(root,"문서함 닫기"));assertNotNull(description(root,"폴더 트리 보기"));
        screenshot(root,"library-wide.png");
    }
    /** A foldable's inner screen (about 620dp) with the pane docked leaves the header ~350dp: nothing may be cut off at the edge. */
    @Test @Config(qualifiers="w620dp-h800dp-mdpi") public void foldableHeaderFitsBesideTheDockedPane()throws Exception{
        View root=open();layout(root,620,800);
        View bar=(View)description(root,"문서함 메뉴").getParent();
        assertTrue("⋮ 단추가 화면 안에 보입니다",inside(description(root,"문서함 메뉴"),bar));
        int shown=0;for(String action:new String[]{"노트 작성","PDF 가져오기","편집","보기 방식"}){View a=description(root,action);if(a!=null&&a.isShown()){shown++;assertTrue(action+" 단추가 잘리지 않습니다",inside(a,bar));}}
        assertTrue("상단 동작 단추가 하나 이상 보입니다",shown>0);
        View title=root.findViewWithTag("library_heading");assertTrue("제목이 읽을 수 있는 너비로 남습니다",title.getWidth()>=80);
        screenshot(root,"library-foldable.png");
    }
    @Test public void phoneLibraryKeepsDrawerAndCompactTopBar()throws Exception{
        View root=open();layout(root,360,720);
        assertNull(description(root,"문서함 닫기"));assertNotNull(description(root,"문서함 메뉴"));
        screenshot(root,"library-phone.png");
    }
    private View open(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        LibraryDialog dialog=new LibraryDialog(activity,library,folder,new LibraryDialog.Actions(){public void open(File f){}public void importFiles(File f){}public void newNote(File f,Runnable r){}public void changed(File a,File b,boolean m){}public void selectedFolder(File f){}});dialog.show();
        View root=dialog.getWindow().getDecorView().findViewWithTag("library_root");assertNotNull(root);return root;
    }
    private static boolean inside(View v,View bar){int[] a=new int[2],b=new int[2];v.getLocationInWindow(a);bar.getLocationInWindow(b);return v.getWidth()>0&&a[0]>=b[0]&&a[0]+v.getWidth()<=b[0]+bar.getWidth();}
    private static void layout(View root,int w,int h){for(int i=0;i<3;i++){org.robolectric.shadows.ShadowLooper.idleMainLooper();root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}}
    private static View description(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static void screenshot(View root,String name)throws Exception{File out=new File("build/test-screenshots");out.mkdirs();Bitmap image=Bitmap.createBitmap(root.getMeasuredWidth(),root.getMeasuredHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));try(OutputStream o=new FileOutputStream(new File(out,name))){image.compress(Bitmap.CompressFormat.PNG,100,o);}image.recycle();}
}
