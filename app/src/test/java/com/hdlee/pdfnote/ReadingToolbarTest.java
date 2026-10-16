package com.hdlee.pdfnote;

import android.app.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="mdpi",shadows=ReadingToolbarTest.RendererShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReadingToolbarTest {
    @Implements(PdfRenderer.class)
    public static class RendererShadow {
        @Implementation protected void __constructor__(ParcelFileDescriptor descriptor){}
        @Implementation protected int getPageCount(){return 1;}
        @Implementation protected void close(){}
    }
    private MainActivity activity;
    private AnnotationStore store;
    @Before public void setup()throws Exception{
        activity=Robolectric.buildActivity(MainActivity.class).setup().get();
        store=new AnnotationStore(activity);store.open(Uri.parse("content://toolbar/"+UUID.randomUUID()));
        set("store",store);set("renderer",new PdfRenderer(null));
    }
    @Test public void mainToolbarIsCompactAndOutlineAndViewAreDirectlyAvailable()throws Exception{
        View root=field("root");layout(root,360,720);View bar=root.findViewWithTag("reading_toolbar");TextView page=root.findViewWithTag("page_indicator");
        assertEquals(54,bar.getLayoutParams().height-bar.getPaddingBottom());assertEquals(64,page.getLayoutParams().width);assertTrue(page.getLayoutParams().width<bar.getWidth()/3);
        assertNull("문서 개요는 페이지 이동 메뉴로 옮겼습니다",description(bar,"문서 개요"));
        for(String name:new String[]{"홈 · 문서함","보기 · 이동","필기","삽입 · 메모 사진 스티커 도형 표","도구 · 설정","하단 메뉴 위치·방향"})assertNotNull(name,description(bar,name));
        assertNull("즐겨찾기는 도구 메뉴에 있습니다",description(bar,"즐겨찾기"));assertNull("타이핑은 삽입 메뉴에 있습니다",description(bar,"타이핑"));assertNull("올가미는 삽입 메뉴에 있습니다",description(bar,"올가미 선택"));
        assertNull("메모는 삽입 메뉴 안에 있습니다",description(bar,"메모"));
        invoke("showOutlineList");View side=root.findViewWithTag("side_panel");assertEquals("개요는 미리보기 패널의 탭으로 열립니다",View.VISIBLE,side.getVisibility());assertNotNull(root.findViewWithTag("outline_add"));invoke("showOutlineList");assertEquals(View.GONE,side.getVisibility());
        description(bar,"보기 · 이동").performClick();assertNull("타이핑은 삽입 메뉴에 있습니다",description(bar,"타이핑"));assertNull("하단 막대에는 한글 글자가 없습니다",firstText(bar));
        page.performClick();screenshot(root,"reading-toolbar.png");
    }
    @Test public void writingToolsFloatAsAStripThatCanBeHidden()throws Exception{
        View root=field("root");layout(root,360,720);View bar=root.findViewWithTag("reading_toolbar"),read=root.findViewWithTag("read_bar"),write=root.findViewWithTag("write_strip_box");View strip=write;
        assertEquals(View.VISIBLE,read.getVisibility());assertEquals(View.GONE,strip.getVisibility());assertNull("필기 도구는 하단 막대 밖에 있습니다",description(bar,"펜"));
        description(bar,"필기").performClick();assertEquals("필기 모드에서도 하단 메뉴는 그대로입니다",View.VISIBLE,read.getVisibility());assertEquals(View.VISIBLE,strip.getVisibility());assertEquals("필기 모드에서는 펜이 바로 켜집니다",1,(int)(Integer)field("inkMode"));
        assertNotNull("지우개는 떠 있는 필기 패널에 있습니다",description(root,"지우개"));View options=root.findViewWithTag("ink_options");
        layout(root,360,720);assertEquals("굵기·색은 필기 도구 줄 아래가 아니라 펜 설정 패널에 있습니다",View.GONE,options.getVisibility());
        description(root,"펜").performClick();description(root,"형광펜").performClick();assertTrue("형광펜은 필기(잉크)의 한 종류입니다",(Boolean)field("inkHl"));assertFalse("하이라이트 도구와는 별개입니다",(Boolean)field("highlightMode"));assertEquals(1,(int)(Integer)field("inkMode"));layout(root,360,720);assertEquals("형광펜에도 줄이 생기지 않습니다",View.GONE,options.getVisibility());
        description(root,"형광펜").performClick();description(root,"지우개").performClick();assertEquals(2,(int)(Integer)field("inkMode"));assertFalse("지우개를 고르면 형광펜이 풀립니다",(Boolean)field("inkHl"));assertEquals("지우개에는 굵기·색 줄이 없습니다",View.GONE,options.getVisibility());
        description(bar,"필기").performClick();assertEquals("한 번 더 누르면 필기 도구 줄이 숨습니다",View.GONE,strip.getVisibility());assertTrue((Boolean)field("writeMode"));
        description(bar,"필기").performClick();assertEquals(View.VISIBLE,strip.getVisibility());
        Method sw=MainActivity.class.getDeclaredMethod("setWriteMode",boolean.class);sw.setAccessible(true);sw.invoke(activity,false);
        assertEquals(View.GONE,strip.getVisibility());assertEquals(0,(int)(Integer)field("inkMode"));
        screenshot(root,"reading-mode.png");description(bar,"필기").performClick();layout(root,360,720);screenshot(root,"writing-mode.png");
    }
    @Test public void mainMenuIsACompactCardNotAFullSheet()throws Exception{
        View root=field("root");layout(root,360,720);View more=description(root,"도구");assertNotNull(more);more.performClick();
    }
    @Test public void fullscreenCanAddOutlineWithoutExitingAndReturnsToNormalToolbar()throws Exception{
        invoke("toggleFullscreen");assertTrue((Boolean)field("fullscreen"));View dock=field("fullscreenDock");assertEquals(View.VISIBLE,dock.getVisibility());assertEquals(View.GONE,((View)field("bottomBar")).getVisibility());
        description(dock,"전체 화면 개요").performClick();View panelRoot=field("root");View addOutline=panelRoot.findViewWithTag("outline_add");assertNotNull(addOutline);addOutline.performClick();org.robolectric.shadows.ShadowLooper.idleMainLooper();assertTrue("Outline placement starts from the panel", (Boolean)field("outlineMode"));assertTrue((Boolean)field("fullscreen"));
        activity.onOutlinePointRequested(0,.25f,.5f);AlertDialog editor=(AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();EditText input=findEdit(editor.getWindow().getDecorView());input.setText("중요한 내용");editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(1,store.outlines.size());assertEquals("중요한 내용",store.outlines.get(0).title);assertEquals(.25f,store.outlines.get(0).x,.001f);assertFalse((Boolean)field("outlineMode"));assertTrue((Boolean)field("fullscreen"));assertEquals(View.VISIBLE,dock.getVisibility());
        View root=field("root");layout(root,360,720);screenshot(root,"fullscreen-toolbar.png");description(dock,"전체 화면 종료").performClick();assertFalse((Boolean)field("fullscreen"));assertEquals(View.VISIBLE,((View)field("bottomBar")).getVisibility());assertEquals(View.GONE,dock.getVisibility());
    }
    @Test public void longTabNamesStayAtTheirBeginningWithEndEllipsis()throws Exception{
        Class<?> type=Class.forName("com.hdlee.pdfnote.MainActivity$DocumentSession");Constructor<?> ctor=type.getDeclaredConstructor();ctor.setAccessible(true);Object session=ctor.newInstance();Field title=type.getDeclaredField("title");title.setAccessible(true);String filename="Beginning_of_a_very_long_document_name_2026.pdf";title.set(session,filename);Field uri=type.getDeclaredField("uri");uri.setAccessible(true);uri.set(session,Uri.parse("content://tabs/document.pdf"));
        ((List<Object>)field("sessions")).add(session);set("activeSession",session);invoke("updateTabs");View root=field("root");layout(root,360,720);TextView name=root.findViewWithTag("document_tab_title");assertNotNull(name);assertEquals(filename,name.getText().toString());assertEquals("Title horizontal scroll",0,name.getScrollX());assertEquals("Title gravity",Gravity.START|Gravity.CENTER_VERTICAL,name.getGravity());assertEquals("Title ellipsis setting",android.text.TextUtils.TruncateAt.END,name.getEllipsize());assertEquals("Rendered title paragraph starts at the left",1,name.getLayout().getParagraphDirection(0));assertTrue("Long title must be ellipsized: width="+name.getWidth()+", textWidth="+name.getPaint().measureText(filename)+", layoutWidth="+name.getLayout().getWidth(),name.getLayout().getEllipsisCount(0)>0);screenshot(root,"document-tabs.png");
    }
    @Test public void folderIconRendersAssignedColorAtSmallAndLargeSizes(){
        for(int size:new int[]{26,96}){Bitmap image=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);FolderIconDrawable icon=new FolderIconDrawable(0xFF54A485,size);icon.setBounds(0,0,size,size);icon.draw(new Canvas(image));int front=image.getPixel(size/2,size*40/64);assertEquals(255,Color.alpha(front));assertTrue(Color.green(front)>Color.red(front));assertEquals(0,Color.alpha(image.getPixel(0,0)));image.recycle();}
    }
    @Test public void aPenColourIsCheckedInExactlyOneRow()throws Exception{
        Field f1=MainActivity.class.getDeclaredField("INK_COLORS"),f2=MainActivity.class.getDeclaredField("INK_COLORS2");f1.setAccessible(true);f2.setAccessible(true);int[] a=(int[])f1.get(null),b=(int[])f2.get(null);
        int[] all=new int[a.length+b.length];System.arraycopy(a,0,all,0,a.length);System.arraycopy(b,0,all,a.length,b.length);
        Method m=MainActivity.class.getDeclaredMethod("swatches",int[].class,java.util.function.IntSupplier.class,java.util.function.IntConsumer.class,int.class,int.class,int[].class);m.setAccessible(true);
        for(int pick:new int[]{a[2],b[3]}){
            LinearLayout one=(LinearLayout)m.invoke(activity,a,(java.util.function.IntSupplier)()->pick,(java.util.function.IntConsumer)c->{},24,0,all),two=(LinearLayout)m.invoke(activity,b,(java.util.function.IntSupplier)()->pick,(java.util.function.IntConsumer)c->{},24,1,all);
            int checks=0;for(LinearLayout row:new LinearLayout[]{one,two})for(int i=0;i<row.getChildCount();i++)if(row.getChildAt(i) instanceof ImageView&&((ImageView)row.getChildAt(i)).getDrawable()!=null)checks++;
            assertEquals("색 하나만 체크됩니다",1,checks);
        }
    }
    @Test public void thumbnailPanelCanListOnlyPagesWithHandwriting()throws Exception{
        set("thumbInk",true);Method m=MainActivity.class.getDeclaredMethod("thumbnailPages");m.setAccessible(true);
        assertTrue("필기가 없으면 목록이 비어 있습니다",((List<?>)m.invoke(activity)).isEmpty());
        AnnotationStore.InkStroke stroke=new AnnotationStore.InkStroke();stroke.page=0;stroke.pen=AnnotationPainter.HIGHLIGHTER;stroke.points.add(new AnnotationStore.InkPoint(.1f,.1f,.5f));store.strokes.add(stroke);
        assertEquals("형광펜 획도 필기로 칩니다",1,((List<?>)m.invoke(activity)).size());
    }
    private <T>T field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return (T)f.get(activity);}
    private void set(String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}
    private void invoke(String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(activity);}
    private static void layout(View root,int w,int h){root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h);}
    private static View description(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;}
    private static CharSequence firstText(View view){if(view instanceof TextView&&((TextView)view).getText().length()>0&&!"page_indicator".equals(view.getTag()))return ((TextView)view).getText();if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){CharSequence t=firstText(((ViewGroup)view).getChildAt(i));if(t!=null)return t;}return null;}
    private static boolean hasText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasText(((ViewGroup)view).getChildAt(i),text))return true;return false;}
    private static EditText findEdit(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText e=findEdit(((ViewGroup)view).getChildAt(i));if(e!=null)return e;}return null;}
    private static void screenshot(View root,String name)throws Exception{File folder=new File("build/test-screenshots");folder.mkdirs();Bitmap image=Bitmap.createBitmap(root.getMeasuredWidth(),root.getMeasuredHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(image));try(OutputStream out=new FileOutputStream(new File(folder,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
}
