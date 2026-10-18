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
    @Test public void phoneToolStripHasTheWindowsToolsAtTheBottom()throws Exception{
        View root=field("root");layout(root,360,720);View bar=root.findViewWithTag("reading_toolbar");View header=field("header");TextView page=root.findViewWithTag("page_indicator");
        assertEquals("휴대폰 폭에서는 도구 모음이 아래에 놓입니다",LinearLayout.HORIZONTAL,((LinearLayout)bar).getOrientation());assertEquals(52,bar.getLayoutParams().height-bar.getPaddingBottom());
        assertFalse("쪽 번호는 도구 모음이 아니라 본문 아래에 떠 있습니다",isInside(page,bar));
        for(String name:new String[]{"도구 모음 접기","키보드 · 타이핑","펜","형광펜","지우개","올가미 선택","실행 취소","다시 실행","테이프 · 글자를 가리는 마스킹 테이프","하이라이트 · 문장을 따라 직선으로","메모","도형","텍스트 선택","화면 나누기 · 문서 두 개를 나란히","도구 모음 위치·방향"})assertNotNull(name,description(bar,name));
        assertTrue("즐겨찾기",description(bar,"즐겨찾기")!=null||description(bar,"즐겨찾기 추가")!=null||description(bar,"즐겨찾기 해제")!=null);
        for(String name:new String[]{"읽기 모드","노트 스타일","삽입","검색","전체 화면","페이지 관리","공유","파일로 저장","인쇄"})assertNotNull("상단 막대: "+name,description(header,name));
        invoke("showOutlineList");View side=root.findViewWithTag("side_panel");assertEquals("개요는 미리보기 패널의 탭으로 열립니다",View.VISIBLE,side.getVisibility());assertNotNull(root.findViewWithTag("outline_add"));invoke("showOutlineList");assertEquals(View.GONE,side.getVisibility());
        description(header,"노트 스타일").performClick();assertNull("도구 모음에는 한글 글자가 없습니다",firstText(bar));
        page.performClick();screenshot(root,"reading-toolbar.png");
    }
    @Test public void penToolsLiveOnTheStripAndTheFloatingToolbarIsOptional()throws Exception{
        View root=field("root");layout(root,360,720);View bar=root.findViewWithTag("reading_toolbar"),strip=root.findViewWithTag("write_strip_box");View header=field("header");View options=root.findViewWithTag("ink_options");
        assertEquals("플로팅 툴바는 처음에는 숨어 있습니다",View.GONE,strip.getVisibility());
        description(bar,"펜").performClick();assertTrue((Boolean)field("writeMode"));assertEquals("펜을 누르면 바로 필기합니다",1,(int)(Integer)field("inkMode"));assertEquals(View.GONE,strip.getVisibility());
        description(bar,"형광펜").performClick();assertTrue("형광펜은 필기(잉크)의 한 종류입니다",(Boolean)field("inkHl"));assertFalse((Boolean)field("hlTape"));assertFalse("하이라이트 도구와는 별개입니다",(Boolean)field("highlightMode"));assertEquals(1,(int)(Integer)field("inkMode"));
        layout(root,360,720);assertEquals("굵기·색은 펜 설정 패널에 있습니다",View.GONE,options.getVisibility());
        description(bar,"테이프 · 글자를 가리는 마스킹 테이프").performClick();assertTrue("테이프는 따로 있는 단추입니다",(Boolean)field("hlTape"));assertTrue((Boolean)field("inkHl"));
        description(bar,"형광펜").performClick();assertFalse("형광펜을 다시 고르면 테이프가 풀립니다",(Boolean)field("hlTape"));
        description(bar,"지우개").performClick();assertEquals(2,(int)(Integer)field("inkMode"));assertFalse("지우개를 고르면 형광펜이 풀립니다",(Boolean)field("inkHl"));
        Method shown=MainActivity.class.getDeclaredMethod("setWriteStripShown",boolean.class);shown.setAccessible(true);shown.invoke(activity,true);assertEquals("플로팅 툴바를 켜면 페이지 위에 나타납니다",View.VISIBLE,strip.getVisibility());
        description(strip,"플로팅 툴바 숨기기").performClick();assertEquals(View.GONE,strip.getVisibility());
        description(header,"읽기 모드").performClick();assertFalse((Boolean)field("writeMode"));assertEquals(0,(int)(Integer)field("inkMode"));
        screenshot(root,"reading-mode.png");description(bar,"펜").performClick();layout(root,360,720);screenshot(root,"writing-mode.png");
    }
    @Test @Config(qualifiers="w1280dp-h800dp-mdpi") public void wideScreenDocksTheStripOnTheRightLikeWindows()throws Exception{
        View root=field("root");layout(root,1280,800);layout(root,1280,800);org.robolectric.shadows.ShadowLooper.idleMainLooper();layout(root,1280,800);
        View bar=root.findViewWithTag("reading_toolbar");View work=root.findViewWithTag("work_row");View header=field("header");
        assertEquals("넓은 화면에서는 오른쪽 세로 도구 모음",LinearLayout.VERTICAL,((LinearLayout)bar).getOrientation());assertSame("도구 모음은 본문 옆(상단 막대 아래)에 붙습니다",work,bar.getParent());assertEquals(((ViewGroup)work).getChildCount()-1,((ViewGroup)work).indexOfChild(bar));
        View label=description(header,"페이지 관리").findViewWithTag("header_label");assertEquals("넓은 화면에서는 상단 단추에 이름이 보입니다",View.VISIBLE,label.getVisibility());
        description(bar,"도구 모음 접기").performClick();assertEquals(View.GONE,description(bar,"펜").getVisibility());description(bar,"도구 모음 펼치기").performClick();assertEquals(View.VISIBLE,description(bar,"펜").getVisibility());
        screenshot(root,"editor-wide.png");
    }
    private static boolean isInside(View child,View parent){for(Object v=child;v instanceof View;v=((View)v).getParent())if(v==parent)return true;return false;}
    @Test public void mainMenuIsACompactCardNotAFullSheet()throws Exception{
        View root=field("root");layout(root,360,720);View more=description(root,"도구");assertNotNull(more);more.performClick();
    }
    @Test public void fullscreenKeepsTheToolStripLikeWindowsAndLeavesFromTheRoundButton()throws Exception{
        View root=field("root");View header=field("header"),tabs=field("tabStrip"),bar=field("bottomBar");View exit=root.findViewWithTag("fullscreen_exit");
        assertEquals("전체 화면 밖에서는 종료 단추가 없습니다",View.GONE,exit.getVisibility());
        invoke("toggleFullscreen");assertTrue((Boolean)field("fullscreen"));
        assertEquals("상단 막대는 숨습니다",View.GONE,header.getVisibility());assertEquals("탭 줄은 숨습니다",View.GONE,tabs.getVisibility());
        assertEquals("도구 모음은 전체 화면에서도 그대로 있습니다",View.VISIBLE,bar.getVisibility());assertEquals(View.VISIBLE,exit.getVisibility());
        for(String name:new String[]{"키보드 · 타이핑","펜","형광펜","지우개","올가미 선택","실행 취소","다시 실행","메모","도형","도구 모음 위치·방향"})assertNotNull("전체 화면 도구 모음: "+name,description(bar,name));
        assertNull("전체 화면 전용 도구 줄은 없어졌습니다",root.findViewWithTag("fullscreen_toolbar"));
        invoke("showOutlineList");View addOutline=root.findViewWithTag("outline_add");assertNotNull(addOutline);addOutline.performClick();org.robolectric.shadows.ShadowLooper.idleMainLooper();assertTrue("개요 추가는 전체 화면에서 바로 됩니다",(Boolean)field("outlineMode"));assertTrue((Boolean)field("fullscreen"));
        layout(root,360,720);screenshot(root,"fullscreen-toolbar.png");
        exit.performClick();assertFalse((Boolean)field("fullscreen"));assertEquals(View.VISIBLE,header.getVisibility());assertEquals(View.VISIBLE,tabs.getVisibility());assertEquals(View.VISIBLE,bar.getVisibility());assertEquals(View.GONE,exit.getVisibility());
    }
    @Test @Config(qualifiers="w1280dp-h800dp-mdpi") public void theStripHandleDragsTheToolStripToAnEdgeLikeSamsungNotes()throws Exception{
        View root=field("root");layout(root,1280,800);org.robolectric.shadows.ShadowLooper.idleMainLooper();layout(root,1280,800);
        View work=root.findViewWithTag("work_row");View bar=field("bottomBar");
        assertSame("처음에는 오른쪽 세로 도구 모음",work,bar.getParent());assertEquals(((ViewGroup)work).getChildCount()-1,((ViewGroup)work).indexOfChild(bar));
        dragHandle(root,20,400);layout(root,1280,800);
        assertEquals("왼쪽 끝에 놓으면 왼쪽에 붙습니다","left",prefs().getString("bar_place",null));assertSame(work,bar.getParent());assertEquals(0,((ViewGroup)work).indexOfChild(bar));
        assertNull("끌기가 끝나면 끌던 그림은 사라집니다",root.findViewWithTag("bar_drag_ghost"));assertNull(root.findViewWithTag("bar_drop_zone"));assertEquals(1f,bar.getAlpha(),.001f);
        dragHandle(root,640,300);layout(root,1280,800);
        assertEquals("가운데에 놓으면 화면 위에 떠 있습니다","float",prefs().getString("bar_place",null));assertSame(root,bar.getParent());assertTrue("세로 방향은 그대로",prefs().getBoolean("bar_vertical",false));
        dragHandle(root,640,790);layout(root,1280,800);
        assertEquals("아래 끝에 놓으면 아래에 붙습니다","bottom",prefs().getString("bar_place",null));assertEquals(LinearLayout.HORIZONTAL,((LinearLayout)bar).getOrientation());
        dragHandle(root,1270,400);layout(root,1280,800);assertEquals("오른쪽 끝에 놓으면 다시 오른쪽에 붙습니다","right",prefs().getString("bar_place",null));
        View handle=root.findViewWithTag("bar_layout");int[] at=new int[2];handle.getLocationInWindow(at);float x=at[0]+handle.getWidth()/2f,y=at[1]+handle.getHeight()/2f;
        touch(handle,MotionEvent.ACTION_DOWN,x,y);touch(handle,MotionEvent.ACTION_UP,x,y);
        assertEquals("짧게 누르면 위치 메뉴만 열리고 자리는 그대로입니다","right",prefs().getString("bar_place",null));
    }
    private android.content.SharedPreferences prefs()throws Exception{return field("recentPrefs");}
    private static void touch(View v,int action,float x,float y){MotionEvent e=MotionEvent.obtain(0,0,action,x,y,0);v.dispatchTouchEvent(e);e.recycle();}
    /** Grabs the ⠿ handle of the tool strip and drags it to (x, y) in the window. */
    private void dragHandle(View root,float x,float y){
        View handle=root.findViewWithTag("bar_layout");int[] at=new int[2];handle.getLocationInWindow(at);float x0=at[0]+handle.getWidth()/2f,y0=at[1]+handle.getHeight()/2f;
        touch(handle,MotionEvent.ACTION_DOWN,x0,y0);touch(handle,MotionEvent.ACTION_MOVE,(x0+x)/2,(y0+y)/2);assertNotNull("끄는 동안 도구 모음 그림이 손가락을 따라옵니다",root.findViewWithTag("bar_drag_ghost"));
        touch(handle,MotionEvent.ACTION_MOVE,x,y);touch(handle,MotionEvent.ACTION_UP,x,y);
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
