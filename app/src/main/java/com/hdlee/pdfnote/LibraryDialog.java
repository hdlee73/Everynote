package com.hdlee.pdfnote;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Document shelf in the style of a modern note app: icon rail / drawer, square covers, folder cards and a compose button. */
final class LibraryDialog extends Dialog {
    interface Actions {
        void open(File file);
        void importFiles(File folder);
        void newNote(File folder,Runnable refresh);
        void changed(File source,File target,boolean moved);
        void selectedFolder(File folder);
        default void removed(List<File> files){}
        default void settings(){}
        /** The shelf was closed with the back button from its top level (not by opening a document). */
        default void backedOut(){}
        /** Back up the documents of this folder (the library root = everything) to a ZIP. */
        default void backupFolder(File folder){}
        /** Restore documents from a backup ZIP made by backupFolder / the whole-library backup. */
        default void restoreBackup(){}
    }
    private static final int ALL=0,FAVORITES=1,RECENT=2,FOLDER=3;
    private static final int INK=0xFF1C1C1E,MUTED=0xFF8E8E93,ACCENT=0xFF007AFF,ACTIVE_BG=0xFFE5F0FF,SURFACE=0xFFFFFFFF;
    private final Activity activity;
    private final LibraryRepository repository;
    private final Actions actions;
    private File folder;
    private int mode=FOLDER;
    private FolderTreeView tree;
    private FrameLayout content,drawer;private View drawerPanel,drawerScrim;private float drawerP;private int drawerWidth;private android.animation.ValueAnimator drawerAnim;
    private LinearLayout page,grid,rail,selectionBar,selectionCommands,topBar,headingBlock;
    private ScrollView shelf;
    private TextView heading,subtitle,upButton,selectionCount;
    private ImageButton menuButton;
    private View compose;
    private EditText search;private LinearLayout searchRow;
    private final Set<File> selected=new LinkedHashSet<>();private boolean selectionMode;
    private final View[] railButtons=new View[4];
    private static final android.util.LruCache<String,Bitmap> covers=new android.util.LruCache<String,Bitmap>(12*1024*1024){protected int sizeOf(String key,Bitmap image){return image.getAllocationByteCount();}};
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private int generation;
    private boolean busy;
    private final boolean wide;
    LibraryDialog(Activity activity,LibraryRepository repository,File folder,Actions actions){
        super(activity);this.activity=activity;this.repository=repository;this.folder=folder;this.actions=actions;requestWindowFeature(Window.FEATURE_NO_TITLE);
        wide=activity.getResources().getConfiguration().screenWidthDp>=600;
        content=new FrameLayout(activity){
            float downX,downY,startX,startP;boolean dragging;VelocityTracker velocity;
            @Override public boolean onInterceptTouchEvent(MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downY=e.getY();dragging=false;}
                else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!dragging&&drawer!=null){
                    float dx=e.getX()-downX,dy=e.getY()-downY;boolean open=drawer.getVisibility()==View.VISIBLE&&drawerP>.5f;int slop=ViewConfiguration.get(activity).getScaledTouchSlop();
                    if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.5f&&((!open&&dx>0&&downX>=dp(28)&&downX<=dp(150))||(open&&dx<0))){beginDrawerDrag();dragging=true;startX=e.getX();startP=drawerP;velocity=VelocityTracker.obtain();velocity.addMovement(e);return true;}
                }
                return false;
            }
            @Override public boolean onTouchEvent(MotionEvent e){
                if(!dragging)return true;
                velocity.addMovement(e);
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_MOVE:setDrawerProgress(Math.max(0,Math.min(1,startP+(e.getX()-startX)/drawerWidth)));return true;
                    case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                        velocity.computeCurrentVelocity(1000);float vx=velocity.getXVelocity();velocity.recycle();dragging=false;
                        boolean open=Math.abs(vx)>900?vx>0:drawerP>.45f;if(open)settleDrawer(1);else settleDrawer(0);return true;
                }
                return true;
            }
        };content.setTag("library_root");content.setBackgroundColor(SURFACE);content.setFitsSystemWindows(true);
        LinearLayout main=new LinearLayout(activity);main.setOrientation(LinearLayout.HORIZONTAL);content.addView(main,new FrameLayout.LayoutParams(-1,-1));
        buildRail(main);
        page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);main.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        buildTopBar();buildSelectionBar();buildSearchRow();buildHeading();
        shelf=new ScrollView(activity);shelf.setVerticalScrollBarEnabled(false);grid=new LinearLayout(activity);grid.setOrientation(LinearLayout.VERTICAL);grid.setPadding(dp(14),dp(6),dp(14),dp(96));shelf.addView(grid,new ScrollView.LayoutParams(-1,-2));page.addView(shelf,new LinearLayout.LayoutParams(-1,0,1));
        ImageView composeIcon=new ImageView(activity);composeIcon.setImageResource(R.drawable.ic_compose);composeIcon.setColorFilter(0xFFE5484D);composeIcon.setPadding(dp(15),dp(15),dp(15),dp(15));
        FrameLayout composeHost=new FrameLayout(activity);composeHost.setTag("compose_button");composeHost.setContentDescription("새로 만들기");composeHost.setBackground(round(Color.WHITE,28));composeHost.setElevation(dp(6));composeHost.addView(composeIcon,new FrameLayout.LayoutParams(-1,-1));composeHost.setOnClickListener(this::newMenu);compose=composeHost;
        FrameLayout.LayoutParams composeParams=new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM|Gravity.END);composeParams.setMargins(0,0,dp(22),dp(24));content.addView(compose,composeParams);
        buildDrawer();setContentView(content);
        setOnDismissListener(d->{generation++;previews.shutdownNow();});shelf.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)shelf.post(this::refreshGrid);});refresh();
    }
    @Override public void show(){super.show();getWindow().setBackgroundDrawableResource(android.R.color.transparent);getWindow().setLayout(-1,-1);getWindow().setStatusBarColor(SURFACE);getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);getWindow().setNavigationBarColor(SURFACE);content.post(this::refreshGrid);}
    @Override public void onBackPressed(){if(drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}if(selectionMode){selectionMode=false;selected.clear();refreshGrid();return;}if(searchRow.getVisibility()==View.VISIBLE){toggleSearch();return;}if(mode==FOLDER&&!folder.equals(repository.root)){selectFolder(folder.getParentFile());return;}actions.backedOut();dismiss();}

    // ---------------------------------------------------------------- layout
    private void buildRail(LinearLayout main){
        rail=new LinearLayout(activity);rail.setOrientation(LinearLayout.VERTICAL);rail.setGravity(Gravity.CENTER_HORIZONTAL);rail.setPadding(dp(6),dp(12),dp(6),dp(12));rail.setBackgroundColor(SURFACE);
        if(!wide){rail.setVisibility(View.GONE);main.addView(rail,new LinearLayout.LayoutParams(0,0));return;}
        ImageButton menu=icon(R.drawable.ic_menu,"폴더 트리 보기",v->openDrawer());rail.addView(menu,new LinearLayout.LayoutParams(dp(52),dp(52)));
        rail.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(18)));
        int[] icons={R.drawable.ic_document_tab,R.drawable.ic_star_outline,R.drawable.ic_clock,R.drawable.ic_delete};String[] names={"전체 문서","즐겨찾기 문서","최근 문서","휴지통"};
        for(int i=0;i<4;i++){final int index=i;ImageButton b=icon(icons[i],names[i],v->{if(index==3)showTrash();else showMode(index);});railButtons[i]=b;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(52),dp(52));lp.bottomMargin=dp(14);rail.addView(b,lp);}
        View dots=new View(activity);dots.setBackground(dotted());rail.addView(dots,new LinearLayout.LayoutParams(dp(44),dp(2)));
        ImageButton folders=icon(R.drawable.ic_folder_open,"폴더 열기",v->openDrawer());LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(dp(52),dp(52));fp.topMargin=dp(14);rail.addView(folders,fp);
        rail.addView(new View(activity),new LinearLayout.LayoutParams(1,0,1));rail.addView(icon(R.drawable.ic_settings,"설정",v->actions.settings()),new LinearLayout.LayoutParams(dp(52),dp(52)));
        main.addView(rail,new LinearLayout.LayoutParams(dp(68),-1));
    }
    private void buildTopBar(){
        LinearLayout bar=new LinearLayout(activity);topBar=bar;bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(6),dp(4),dp(6),0);
        if(!wide){menuButton=icon(R.drawable.ic_menu,"폴더 트리 보기",v->openDrawer());bar.addView(menuButton,new LinearLayout.LayoutParams(dp(48),dp(52)));}
        bar.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
        bar.addView(icon(R.drawable.ic_search,"문서 이름 검색",v->toggleSearch()),new LinearLayout.LayoutParams(dp(48),dp(52)));
        bar.addView(icon(R.drawable.ic_more_vert,"문서함 메뉴",this::libraryMenu),new LinearLayout.LayoutParams(dp(48),dp(52)));
        page.addView(bar,new LinearLayout.LayoutParams(-1,dp(56)));
    }
    private void buildSearchRow(){
        searchRow=new LinearLayout(activity);searchRow.setGravity(Gravity.CENTER_VERTICAL);searchRow.setPadding(dp(16),0,dp(16),dp(6));searchRow.setVisibility(View.GONE);
        search=new EditText(activity);search.setSingleLine();search.setTextSize(15);search.setTextColor(INK);search.setHint("문서 이름 검색");search.setContentDescription("문서 이름 입력");search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence q,int start,int count,int after){}public void onTextChanged(CharSequence q,int start,int before,int count){refreshGrid();}public void afterTextChanged(Editable e){}});
        searchRow.addView(search,new LinearLayout.LayoutParams(-1,dp(46)));page.addView(searchRow,new LinearLayout.LayoutParams(-1,-2));
    }
    private void buildHeading(){
        LinearLayout block=new LinearLayout(activity);headingBlock=block;block.setOrientation(LinearLayout.VERTICAL);block.setGravity(Gravity.CENTER_HORIZONTAL);block.setPadding(dp(16),dp(2),dp(16),dp(8));
        upButton=button("","상위 폴더로",v->{if(!folder.equals(repository.root))selectFolder(folder.getParentFile());});upButton.setTag("folder_up");upButton.setTextSize(13);upButton.setTextColor(ACCENT);upButton.setGravity(Gravity.CENTER);upButton.setPadding(dp(14),0,dp(14),0);upButton.setBackground(round(ACTIVE_BG,16));block.addView(upButton,new LinearLayout.LayoutParams(-2,dp(32)));
        heading=label("",30,INK);heading.setTag("library_heading");heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setGravity(Gravity.CENTER);heading.setSingleLine();heading.setEllipsize(TextUtils.TruncateAt.END);heading.setPadding(0,dp(6),0,0);block.addView(heading,new LinearLayout.LayoutParams(-1,dp(54)));
        subtitle=label("",15,MUTED);subtitle.setGravity(Gravity.CENTER);block.addView(subtitle,new LinearLayout.LayoutParams(-1,dp(26)));
        page.addView(block,new LinearLayout.LayoutParams(-1,-2));
    }
    /** Replaces the top bar while documents are selected: back, "N개 선택됨", icon commands (with labels on wide screens) and a ⋮ menu. */
    private void buildSelectionBar(){
        selectionBar=new LinearLayout(activity);selectionBar.setGravity(Gravity.CENTER_VERTICAL);selectionBar.setPadding(dp(6),dp(4),dp(6),0);selectionBar.setVisibility(View.GONE);selectionBar.setTag("selection_bar");
        selectionBar.addView(icon(R.drawable.ic_chevron_left,"선택 해제",v->onBackPressed()),new LinearLayout.LayoutParams(dp(48),dp(52)));
        selectionCount=label("0개 선택됨",wide?20:17,INK);selectionCount.setTypeface(Typeface.DEFAULT_BOLD);selectionCount.setSingleLine();selectionCount.setTag("selection_count");selectionBar.addView(selectionCount,new LinearLayout.LayoutParams(0,-1,1));
        selectionCommands=new LinearLayout(activity);selectionCommands.setGravity(Gravity.CENTER_VERTICAL);selectionBar.addView(selectionCommands,new LinearLayout.LayoutParams(-2,-1));
        page.addView(selectionBar,new LinearLayout.LayoutParams(-1,dp(56)));
    }
    private boolean allSelected(){int docs=0;for(File f:items())if(f.isFile()){docs++;if(!selected.contains(f))return false;}return docs>0;}
    private void rebuildSelectionCommands(){
        selectionCommands.removeAllViews();
        boolean all=allSelected();
        String[] labels={"이동","공유","삭제",all?"모두 해제":"전체 선택"};int[] icons={R.drawable.ic_move,R.drawable.ic_share,R.drawable.ic_delete,R.drawable.ic_check_circle};
        for(int i=0;i<labels.length;i++){
            final String label=labels[i];boolean red=label.equals("삭제");
            LinearLayout command=new LinearLayout(activity);command.setGravity(Gravity.CENTER);command.setContentDescription("선택 문서 "+label);command.setTag("selection_command:"+label);
            ImageView image=new ImageView(activity);image.setImageResource(icons[i]);image.setColorFilter(red?0xFFFF3B30:INK);command.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
            if(wide){TextView text=label(label,14,red?0xFFFF3B30:INK);text.setPadding(dp(6),0,0,0);command.addView(text,new LinearLayout.LayoutParams(-2,-1));}
            command.setPadding(dp(wide?12:9),0,dp(wide?12:9),0);command.setBackground(pressBackground());command.setOnClickListener(v->runSelectionCommand(label.equals("이동")||label.equals("공유")||label.equals("삭제")?label:"전체"));
            selectionCommands.addView(command,new LinearLayout.LayoutParams(-2,dp(44)));
        }
        ImageButton more=icon(R.drawable.ic_more_vert,"선택 문서 더 보기",this::selectionMenu);selectionCommands.addView(more,new LinearLayout.LayoutParams(dp(40),dp(44)));
    }
    /** ⋮ of the selection bar: the less common commands. */
    private void selectionMenu(View anchor){
        List<File> files=new ArrayList<>(selected);boolean allFavorite=!files.isEmpty();for(File f:files)if(!repository.favorite(f))allFavorite=false;
        List<AnchoredMenu.Row> rows=new ArrayList<>();
        if(files.size()==1)rows.add(new AnchoredMenu.Row("이름 변경",R.drawable.ic_rename,()->runSelectionCommand("이름 변경")).tint(0xFF8E8E93));
        rows.add(new AnchoredMenu.Row("복사본 만들기",R.drawable.ic_copy,()->runSelectionCommand("복사")).tint(0xFF8E8E93));
        rows.add(new AnchoredMenu.Row(allFavorite?"즐겨찾기 해제":"즐겨찾기에 추가",allFavorite?R.drawable.ic_star:R.drawable.ic_star_outline,()->runSelectionCommand("즐겨찾기")).tint(0xFFF5A623));
        AnchoredMenu.show(activity,anchor,false,rows,null);
    }
    private void runSelectionCommand(String action){
        List<File> files=new ArrayList<>(selected);
        if(action.equals("전체")){if(allSelected())selected.clear();else for(File item:items())if(item.isFile())selected.add(item);refreshGrid();return;}
        if(files.isEmpty()){Toast.makeText(activity,"문서를 선택하세요",Toast.LENGTH_SHORT).show();return;}
        if(action.equals("즐겨찾기")){boolean allFavorite=true;for(File f:files)if(!repository.favorite(f))allFavorite=false;for(File f:files)repository.favorite(f,!allFavorite);selectionMode=false;selected.clear();refresh();Toast.makeText(activity,allFavorite?"즐겨찾기에서 뺐습니다":"즐겨찾기에 추가했습니다",Toast.LENGTH_SHORT).show();}
        else if(action.equals("이름 변경"))rename(files.get(0));
        else if(action.equals("공유"))share(files);
        else if(action.equals("삭제"))deleteDocuments(files);
        else chooseDestination(files,action.equals("이동"));
    }
    private void buildDrawer(){
        drawer=new FrameLayout(activity);drawer.setTag("library_drawer");drawer.setVisibility(View.GONE);
        View scrim=new View(activity);drawerScrim=scrim;scrim.setBackgroundColor(0x40000000);scrim.setOnClickListener(v->closeDrawer());drawer.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);GradientDrawable panelShape=new GradientDrawable();panelShape.setColor(Color.WHITE);panelShape.setCornerRadii(new float[]{0,0,dp(28),dp(28),dp(28),dp(28),0,0});panel.setBackground(panelShape);panel.setElevation(dp(14));panel.setPadding(dp(6),dp(14),dp(10),dp(12));drawerPanel=panel;
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(icon(R.drawable.ic_menu,"폴더 트리 닫기",v->closeDrawer()),new LinearLayout.LayoutParams(dp(56),dp(48)));top.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));panel.addView(top,new LinearLayout.LayoutParams(-1,dp(48)));
        int[] icons={R.drawable.ic_document_tab,R.drawable.ic_star_outline,R.drawable.ic_clock,R.drawable.ic_delete};String[] names={"전체 문서","즐겨찾기","최근 문서","휴지통"};
        for(int i=0;i<4;i++){final int index=i;panel.addView(drawerRow(icons[i],names[i],"drawer_count:"+i,v->{closeDrawer();if(index==3)showTrash();else showMode(index);}),new LinearLayout.LayoutParams(-1,dp(52)));}
        View dots=new View(activity);dots.setBackground(dotted());LinearLayout.LayoutParams dp1=new LinearLayout.LayoutParams(-1,dp(2));dp1.setMargins(dp(10),dp(8),dp(10),dp(8));panel.addView(dots,dp1);
        panel.addView(drawerRow(R.drawable.ic_folder_open,"폴더","drawer_count:4",null),new LinearLayout.LayoutParams(-1,dp(52)));
        tree=new FolderTreeView(activity,repository,folder,this::selectFolder);tree.setFolderMenu(this::folderMenu);tree.setBackgroundColor(Color.TRANSPARENT);panel.addView(tree,new LinearLayout.LayoutParams(-1,0,1));
        TextView manage=button("폴더 관리","폴더 관리",this::folderManageMenu);manage.setTextColor(INK);manage.setTextSize(16);manage.setTypeface(Typeface.DEFAULT_BOLD);manage.setBackground(round(0xFFF2F2F7,24));LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(-1,dp(48));mp.setMargins(dp(24),dp(8),dp(24),0);panel.addView(manage,mp);
        View settingsDots=new View(activity);settingsDots.setBackground(dotted());LinearLayout.LayoutParams sdp=new LinearLayout.LayoutParams(-1,dp(2));sdp.setMargins(dp(10),dp(8),dp(10),dp(4));panel.addView(settingsDots,sdp);
        panel.addView(drawerRow(R.drawable.ic_settings,"설정","drawer_count:5",v->{closeDrawer();actions.settings();}),new LinearLayout.LayoutParams(-1,dp(52)));
        int width=Math.min(dp(320),Math.round(activity.getResources().getDisplayMetrics().widthPixels*.86f));drawerWidth=width;drawer.addView(panel,new FrameLayout.LayoutParams(width,-1,Gravity.START));
        content.addView(drawer,new FrameLayout.LayoutParams(-1,-1));
    }
    private View drawerRow(int iconRes,String title,String countTag,View.OnClickListener click){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(14),0,dp(16),0);row.setContentDescription(title);row.setTag("drawer_row:"+title);
        ImageView image=new ImageView(activity);image.setImageResource(iconRes);row.addView(image,new LinearLayout.LayoutParams(dp(28),dp(28)));
        TextView name=label(title,18,INK);name.setPadding(dp(18),0,0,0);row.addView(name,new LinearLayout.LayoutParams(0,-1,1));
        TextView count=label("",16,MUTED);count.setTag(countTag);row.addView(count,new LinearLayout.LayoutParams(-2,-1));
        if(click!=null){row.setOnClickListener(click);row.setBackground(round(0x00000000,26));}return row;
    }
    private void updateDrawerCounts(){
        int[] counts={repository.allDocuments("").size(),repository.favorites("").size(),Math.min(40,repository.allDocuments("").size()),repository.trashItems().size(),repository.folderCount()};
        for(int i=0;i<counts.length;i++){View v=drawer.findViewWithTag("drawer_count:"+i);if(v instanceof TextView)((TextView)v).setText(i==2?"":String.valueOf(counts[i]));}
        for(int i=0;i<4;i++){View row=drawer.findViewWithTag("drawer_row:"+new String[]{"전체 문서","즐겨찾기","최근 문서","휴지통"}[i]);if(row!=null)row.setBackground(round(i<3&&mode==i?ACTIVE_BG:0x00000000,26));}
    }
    private void beginDrawerDrag(){if(drawerAnim!=null)drawerAnim.cancel();if(drawer.getVisibility()!=View.VISIBLE){tree.select(folder);updateDrawerCounts();setDrawerProgress(0);drawer.setVisibility(View.VISIBLE);}}
    private void setDrawerProgress(float p){drawerP=p;if(drawerPanel!=null){drawerPanel.setTranslationX(-drawerWidth*(1-p));drawerScrim.setAlpha(p);}}
    private void settleDrawer(float target){
        if(drawerAnim!=null)drawerAnim.cancel();drawer.setVisibility(View.VISIBLE);
        android.animation.ValueAnimator a=android.animation.ValueAnimator.ofFloat(drawerP,target);drawerAnim=a;a.setDuration(Math.max(80,Math.round(220*Math.abs(target-drawerP))));a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(v->setDrawerProgress((Float)v.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter(){boolean cancelled;@Override public void onAnimationCancel(android.animation.Animator x){cancelled=true;}@Override public void onAnimationEnd(android.animation.Animator x){if(!cancelled){setDrawerProgress(target);if(target==0)drawer.setVisibility(View.GONE);}}});
        a.start();
    }
    private void openDrawer(){tree.select(folder);updateDrawerCounts();if(drawer.getVisibility()!=View.VISIBLE)setDrawerProgress(0);drawer.setVisibility(View.VISIBLE);settleDrawer(1);}
    private void closeDrawer(){if(drawer.getVisibility()==View.VISIBLE)settleDrawer(0);}
    private void toggleSearch(){
        boolean show=searchRow.getVisibility()!=View.VISIBLE;searchRow.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){search.requestFocus();InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}
        else{search.setText("");InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);}
    }

    // ---------------------------------------------------------------- modes and content
    private void showMode(int newMode){mode=newMode;selected.clear();selectionMode=false;refresh();}
    private void selectFolder(File selectedFolder){this.selected.clear();selectionMode=false;mode=FOLDER;folder=selectedFolder;actions.selectedFolder(folder);closeDrawer();refresh();}
    private List<File> items(){String q=search.getText().toString();switch(mode){case ALL:return repository.allDocuments(q);case FAVORITES:return repository.favorites(q);case RECENT:return repository.recent(q,40);default:return repository.sorted(folder,q);}}
    private void refresh(){styleRail();refreshGrid();}
    private void styleRail(){for(int i=0;i<4;i++){View b=railButtons[i];if(b!=null)b.setBackground(round(i<3&&mode==i?ACTIVE_BG:0x00000000,26));}}
    private void updateHeading(List<File> items){
        String title=mode==ALL?"전체 문서":mode==FAVORITES?"즐겨찾기":mode==RECENT?"최근 문서":folder.equals(repository.root)?"문서함":folder.getName();heading.setText(title);
        int folders=0,docs=0;for(File f:items){if(f.isDirectory())folders++;else docs++;}
        subtitle.setText((folders>0?"폴더 "+folders+"개 · ":"")+"문서 "+docs+"개");
        boolean nested=mode==FOLDER&&!folder.equals(repository.root);upButton.setVisibility(nested?View.VISIBLE:View.GONE);if(nested)upButton.setText(Glyph.leading(activity,R.drawable.ic_chevron_left,upButton.getCurrentTextColor(),18,folder.getParentFile().equals(repository.root)?"문서함":folder.getParentFile().getName()));
    }
    private void refreshGrid(){
        if(previews.isShutdown()||grid==null)return;final int version=++generation;grid.removeAllViews();List<File> items=items();updateHeading(items);
        selectionBar.setVisibility(selectionMode?View.VISIBLE:View.GONE);topBar.setVisibility(selectionMode?View.GONE:View.VISIBLE);headingBlock.setVisibility(selectionMode?View.GONE:View.VISIBLE);if(selectionMode)searchRow.setVisibility(View.GONE);compose.setVisibility(selectionMode?View.GONE:View.VISIBLE);if(selectionMode){selectionCount.setText(selected.size()+"개 선택됨");rebuildSelectionCommands();}
        int viewMode=repository.viewMode();int usable=Math.max(shelf.getWidth(),dp(240))-dp(28);int columns=viewMode==2?1:Math.max(1,Math.min(6,usable/dp(viewMode==1?118:164)));int cell=viewMode==2?0:usable/columns-dp(14);
        if(items.isEmpty()){TextView empty=label(search.getText().length()>0?"검색 결과가 없습니다":mode==FAVORITES?"즐겨찾기한 문서가 없습니다":"아직 문서가 없습니다",16,MUTED);empty.setGravity(Gravity.CENTER);grid.addView(empty,new LinearLayout.LayoutParams(-1,dp(140)));return;}
        for(int i=0;i<items.size();i+=columns){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.TOP);grid.addView(row,new LinearLayout.LayoutParams(-1,-2));for(int j=0;j<columns;j++){int index=i+j;LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,-2,1);layout.setMargins(dp(7),dp(4),dp(7),dp(12));if(index<items.size())row.addView(viewMode==2?listItem(items.get(index),version):card(items.get(index),version,cell),layout);else row.addView(new View(activity),layout);}}
    }
    private View card(File file,int version,int cell){
        LinearLayout card=new LinearLayout(activity);card.setTag("document:"+file.getName());card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);card.setContentDescription(file.getName());
        FrameLayout coverFrame=new FrameLayout(activity);card.addView(coverFrame,new LinearLayout.LayoutParams(-1,cell));
        if(file.isDirectory()){
            ImageView shape=new ImageView(activity);shape.setImageDrawable(new FolderShapeDrawable(repository.folderColor(file)));shape.setContentDescription(file.getName()+" 폴더");coverFrame.addView(shape,new FrameLayout.LayoutParams(-1,Math.round(cell*.84f),Gravity.BOTTOM));
            TextView count=label(String.valueOf(repository.list(file).size()),16,0xFF59607E);count.setGravity(Gravity.START|Gravity.TOP);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.START|Gravity.TOP);cp.setMargins(dp(14),Math.round(cell*.16f+cell*.84f*.3f)+dp(6),0,0);coverFrame.addView(count,cp);
        }else{
            ImageView cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(roundStroke(Color.WHITE,20,0x1A2B2F4A));cover.setClipToOutline(true);cover.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(20));}});cover.setImageResource(R.drawable.ic_note_add);cover.setContentDescription(file.getName()+" 미리보기");coverFrame.addView(cover,new FrameLayout.LayoutParams(-1,-1));preview(file,cover,version);
            if(repository.favorite(file)){ImageView star=new ImageView(activity);star.setImageResource(R.drawable.ic_star);star.setColorFilter(0xFFFFB020);star.setPadding(dp(4),dp(4),dp(4),dp(4));star.setBackground(round(0xE6FFFFFF,13));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.END|Gravity.TOP);sp.setMargins(0,dp(8),dp(8),0);coverFrame.addView(star,sp);}
            if(selectionMode){CheckBox check=new CheckBox(activity);check.setChecked(selected.contains(file));check.setContentDescription("선택 "+file.getName());check.setOnClickListener(v->toggleSelected(file));FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.TOP);kp.setMargins(dp(4),dp(4),0,0);coverFrame.addView(check,kp);}
        }
        TextView name=label(file.getName().replaceFirst("(?i)\\.pdf$",""),15,INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);name.setTypeface(Typeface.DEFAULT_BOLD);name.setPadding(dp(2),dp(8),dp(2),0);card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        if(file.isFile()){TextView date=label(dateText(file),12,MUTED);date.setGravity(Gravity.CENTER);card.addView(date,new LinearLayout.LayoutParams(-1,dp(22)));}
        View.OnClickListener open=v->openOrSelect(file);card.setOnClickListener(open);coverFrame.setOnClickListener(open);card.setOnLongClickListener(v->{longPressed(file,v);return true;});coverFrame.setOnLongClickListener(v->{longPressed(file,v);return true;});return card;
    }
    private void longPressed(File file,View anchor){if(file.isDirectory())folderMenu(file,anchor);else{selectionMode=true;selected.add(file);refreshGrid();}}
    private String dateText(File file){
        long time=Math.max(file.lastModified(),AnnotationStore.modified(activity,Uri.fromFile(file)));Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(time);Date date=new Date(time);
        if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR))return new SimpleDateFormat("a h:mm",Locale.KOREA).format(date);
        if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR))return new SimpleDateFormat("M월 d일",Locale.KOREA).format(date);return new SimpleDateFormat("yyyy. M. d",Locale.KOREA).format(date);
    }
    private void preview(File file,ImageView cover,int version){
        String key=file.getAbsolutePath()+":"+file.lastModified()+":"+AnnotationStore.modified(activity,Uri.fromFile(file))+":top";Bitmap cached=covers.get(key);if(cached!=null){cover.setImageBitmap(cached);return;}
        previews.execute(()->{if(version!=generation)return;try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor);PdfRenderer.Page page=renderer.openPage(0)){
            float scale=Math.min(320f/page.getWidth(),480f/page.getHeight());Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(page.getWidth()*scale)),Math.max(1,Math.round(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Matrix matrix=new Matrix();matrix.postScale(scale,scale);page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);AnnotationStore notes=new AnnotationStore(activity);notes.open(Uri.fromFile(file));AnnotationPainter.all(activity,new Canvas(bitmap),new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),notes,0);
            Bitmap top=bitmap.getHeight()>bitmap.getWidth()?Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getWidth()):bitmap;
            covers.put(key,top);activity.runOnUiThread(()->{if(isShowing()&&version==generation)cover.setImageBitmap(top);});
        }catch(Exception ignored){}});
    }
    private View listItem(File file,int version){
        LinearLayout row=new LinearLayout(activity);row.setTag("document:"+file.getName());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setBackground(round(Color.WHITE,14));row.setContentDescription(file.getName());
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(image,new LinearLayout.LayoutParams(dp(56),dp(56)));
        if(file.isDirectory()){image.setImageDrawable(new FolderShapeDrawable(repository.folderColor(file)));}else{image.setBackground(roundStroke(Color.WHITE,12,0x1A2B2F4A));image.setClipToOutline(true);image.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(12));}});image.setImageResource(R.drawable.ic_note_add);preview(file,image,version);}
        LinearLayout text=new LinearLayout(activity);text.setOrientation(LinearLayout.VERTICAL);text.setPadding(dp(14),0,dp(4),0);TextView name=label(file.getName().replaceFirst("(?i)\\.pdf$",""),15,INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setTypeface(Typeface.DEFAULT_BOLD);text.addView(name,new LinearLayout.LayoutParams(-1,-2));
        TextView meta=label(file.isDirectory()?repository.list(file).size()+"개 항목":dateText(file)+(mode==ALL||mode==FAVORITES||mode==RECENT?" · "+(file.getParentFile().equals(repository.root)?"문서함":file.getParentFile().getName()):""),12,MUTED);text.addView(meta,new LinearLayout.LayoutParams(-1,-2));row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if(file.isFile()&&repository.favorite(file)){ImageView star=new ImageView(activity);star.setImageResource(R.drawable.ic_star);star.setColorFilter(0xFFFFB020);row.addView(star,new LinearLayout.LayoutParams(dp(22),dp(22)));}
        if(selectionMode&&file.isFile()){CheckBox check=new CheckBox(activity);check.setChecked(selected.contains(file));check.setContentDescription("선택 "+file.getName());check.setOnClickListener(v->toggleSelected(file));row.addView(check,new LinearLayout.LayoutParams(dp(40),dp(48)));}
        row.setOnClickListener(v->openOrSelect(file));row.setOnLongClickListener(v->{longPressed(file,v);return true;});return row;
    }
    private void openOrSelect(File file){if(file.isDirectory())selectFolder(file);else if(selectionMode)toggleSelected(file);else{dismiss();actions.open(file);}}
    private void toggleSelected(File file){if(!selected.remove(file))selected.add(file);refreshGrid();}

    // ---------------------------------------------------------------- menus and actions
    private void newMenu(View anchor){
        final File target=mode==FOLDER?folder:repository.root;
        AnchoredMenu.showCentered(activity,anchor,"문서 추가",AnchoredMenu.rows(
            new AnchoredMenu.Row("새 노트 만들기",R.drawable.ic_compose,()->actions.newNote(target,this::refresh)).tint(0xFF34C759),
            new AnchoredMenu.Row("파일 가져오기",R.drawable.ic_import,()->{dismiss();actions.importFiles(target);}).tint(0xFF007AFF),
            new AnchoredMenu.Row("폴더 만들기",R.drawable.ic_folder_open,()->createFolder(target,()->{tree.reload();refresh();})).tint(0xFFF5A623)));
    }
    private void libraryMenu(View anchor){
        PopupMenu menu=new PopupMenu(activity,anchor);menu.getMenu().add(0,1,0,"선택");menu.getMenu().add(0,2,1,"보기 방법");menu.getMenu().add(0,3,2,"정렬");menu.getMenu().add(0,4,3,(repository.pinFavorites()?"✓  ":"")+"즐겨찾기 맨 위 고정");
        if(mode==FOLDER)menu.getMenu().add(0,5,4,"폴더 색상");menu.getMenu().add(0,6,5,"휴지통");menu.getMenu().add(0,8,6,mode==FOLDER&&!folder.equals(repository.root)?"이 폴더 백업":"모든 문서 백업");menu.getMenu().add(0,9,7,"백업 복원");menu.getMenu().add(0,7,8,"설정");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 1:selectionMode=!selectionMode;selected.clear();refreshGrid();break;
            case 2:new AlertDialog.Builder(activity).setTitle("보기 방법").setSingleChoiceItems(LibraryRepository.VIEW_NAMES,repository.viewMode(),(d,index)->{repository.viewMode(index);d.dismiss();refreshGrid();}).show();break;
            case 3:new AlertDialog.Builder(activity).setTitle("정렬").setSingleChoiceItems(LibraryRepository.SORT_NAMES,repository.sortMode(),(d,index)->{repository.sortMode(index);d.dismiss();refreshGrid();}).show();break;
            case 4:repository.pinFavorites(!repository.pinFavorites());refreshGrid();break;
            case 5:chooseFolderColor(folder);break;
            case 7:actions.settings();break;
            case 8:actions.backupFolder(mode==FOLDER?folder:repository.root);break;
            case 9:actions.restoreBackup();break;
            default:showTrash();}return true;});menu.show();
    }
    /** Reloads the folder tree and the list (after documents were restored). */
    void reload(){if(tree!=null)tree.reload();refresh();}
    private void folderMenu(File target,View anchor){PopupMenu menu=new PopupMenu(activity,anchor);menu.getMenu().add("폴더 색상");menu.getMenu().add("하위 폴더 만들기");menu.getMenu().add("이 폴더 백업");menu.setOnMenuItemClickListener(item->{if(item.getTitle().equals("폴더 색상"))chooseFolderColor(target);else if(item.getTitle().equals("이 폴더 백업"))actions.backupFolder(target);else createFolder(target,()->{tree.reload();refresh();});return true;});menu.show();}
    private void folderManageMenu(View anchor){PopupMenu menu=new PopupMenu(activity,anchor);menu.getMenu().add("폴더 만들기");menu.getMenu().add("선택한 폴더 색상");menu.setOnMenuItemClickListener(item->{File target=tree.selected();if(item.getTitle().equals("폴더 만들기"))createFolder(target,()->{tree.reload();refresh();});else chooseFolderColor(target);return true;});menu.show();}
    private void chooseFolderColor(File target){int current=0;for(int i=0;i<LibraryRepository.FOLDER_COLORS.length;i++)if(LibraryRepository.FOLDER_COLORS[i]==repository.folderColor(target))current=i;
        new AlertDialog.Builder(activity).setTitle("폴더 색상 · "+(target.equals(repository.root)?"문서함":target.getName())).setSingleChoiceItems(LibraryRepository.FOLDER_COLOR_NAMES,current,(d,index)->{repository.folderColor(target,LibraryRepository.FOLDER_COLORS[index]);d.dismiss();tree.reload();refreshGrid();}).setNegativeButton("취소",null).show();}
    private void share(List<File> files){
        try{
            ArrayList<Uri> uris=new ArrayList<>();for(File f:files)uris.add(ShareProvider.uri(activity,f));
            Intent send=new Intent(uris.size()==1?Intent.ACTION_SEND:Intent.ACTION_SEND_MULTIPLE).setType("application/pdf");
            if(uris.size()==1)send.putExtra(Intent.EXTRA_STREAM,uris.get(0));else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            ClipData clip=ClipData.newRawUri("문서",uris.get(0));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));send.setClipData(clip);send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(send,"문서 공유"));
        }catch(Exception error){Toast.makeText(activity,"공유할 수 없습니다: "+error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void rename(File file){EditText input=new EditText(activity);input.setSingleLine();input.setText(file.getName().replaceFirst("(?i)\\.pdf$",""));input.selectAll();AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("이름 변경").setView(input).setPositiveButton("저장",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError("이름을 입력하세요");return;}dialog.dismiss();transfer(file,file.getParentFile(),name,true);}));dialog.show();}
    private void chooseDestination(List<File> files,boolean move){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);FolderTreeView picker=new FolderTreeView(activity,repository,folder,f->{});panel.addView(picker,new LinearLayout.LayoutParams(-1,dp(340)));TextView add=button("＋ 폴더 만들기","대상 폴더 만들기",v->createFolder(picker.selected(),picker::reload));panel.addView(add,new LinearLayout.LayoutParams(-1,dp(44)));
        new AlertDialog.Builder(activity).setTitle(move?"이동할 폴더":"복사할 폴더").setView(panel).setPositiveButton(move?"이곳으로 이동":"이곳에 복사",(d,w)->transferMany(files,picker.selected(),move)).setNegativeButton("취소",null).show();
    }
    private void transfer(File file,File target,String name,boolean move){if(busy)return;busy=true;ProgressDialog progress=ProgressDialog.show(activity,"",move?"문서를 이동하는 중…":"문서를 복사하는 중…",true,false);new Thread(()->{try{File saved=repository.transfer(file,target,name,move);activity.runOnUiThread(()->{busy=false;progress.dismiss();actions.changed(file,saved,move);selectionMode=false;selected.clear();refresh();});}catch(Exception error){activity.runOnUiThread(()->{busy=false;progress.dismiss();Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();});}}).start();}
    private void transferMany(List<File> files,File destination,boolean move){if(busy)return;busy=true;ProgressDialog progress=ProgressDialog.show(activity,"",move?"문서를 이동하는 중…":"문서를 복사하는 중…",true,false);new Thread(()->{List<File[]> changes=new ArrayList<>();String error="";for(File file:files)try{changes.add(new File[]{file,repository.transfer(file,destination,file.getName(),move)});}catch(Exception failure){error=failure.getMessage()==null?"작업에 실패했습니다":failure.getMessage();}final String message=error;activity.runOnUiThread(()->{busy=false;progress.dismiss();for(File[] change:changes)actions.changed(change[0],change[1],move);selectionMode=false;selected.clear();refresh();if(!message.isEmpty())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();});}).start();}
    private void deleteDocuments(List<File> files){new AlertDialog.Builder(activity).setTitle("문서 삭제").setMessage(files.size()+"개 문서를 휴지통으로 이동할까요?").setPositiveButton("삭제",(d,w)->{if(busy)return;busy=true;ProgressDialog progress=ProgressDialog.show(activity,"","휴지통으로 이동하는 중…",true,false);new Thread(()->{List<File> removed=new ArrayList<>();String error="";for(File file:files)try{repository.trash(file);removed.add(file);}catch(Exception failure){error=failure.getMessage()==null?"삭제에 실패했습니다":failure.getMessage();}final String message=error;activity.runOnUiThread(()->{busy=false;progress.dismiss();actions.removed(removed);selectionMode=false;selected.clear();refresh();if(!message.isEmpty())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();});}).start();}).setNegativeButton("취소",null).show();}
    private void showTrash(){
        List<LibraryRepository.TrashItem> items=repository.trashItems();if(items.isEmpty()){new AlertDialog.Builder(activity).setTitle("휴지통").setMessage("휴지통이 비어 있습니다").setPositiveButton("닫기",null).show();return;}
        String[] names=new String[items.size()];for(int i=0;i<names.length;i++)names[i]=items.get(i).name;
        new AlertDialog.Builder(activity).setTitle("휴지통 · 눌러서 복원").setItems(names,(d,index)->{try{File restored=repository.restore(items.get(index));actions.changed(items.get(index).file,restored,true);refresh();Toast.makeText(activity,"복원했습니다",Toast.LENGTH_SHORT).show();}catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}}).setNeutralButton("휴지통 비우기",(d,w)->new AlertDialog.Builder(activity).setTitle("휴지통 비우기").setMessage(items.size()+"개 문서를 영구 삭제합니다. 되돌릴 수 없습니다.").setPositiveButton("비우기",(d2,w2)->{int removed=repository.emptyTrash();refresh();Toast.makeText(activity,removed+"개 문서를 영구 삭제했습니다",Toast.LENGTH_SHORT).show();}).setNegativeButton("취소",null).show()).setNegativeButton("닫기",null).show();
    }
    private void createFolder(File parent,Runnable done){EditText input=new EditText(activity);input.setHint("폴더 이름");input.setSingleLine();AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("폴더 만들기").setView(input).setPositiveButton("만들기",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{repository.createFolder(parent,input.getText().toString());dialog.dismiss();done.run();}catch(Exception error){input.setError(error.getMessage());}}));dialog.show();}

    // ---------------------------------------------------------------- view helpers
    private ImageButton icon(int resource,String description,View.OnClickListener click){ImageButton b=new ImageButton(activity);b.setImageResource(resource);b.setColorFilter(INK);b.setContentDescription(description);b.setBackgroundColor(Color.TRANSPARENT);b.setScaleType(ImageView.ScaleType.CENTER);b.setOnClickListener(click);return b;}
    private TextView button(String text,String description,View.OnClickListener click){TextView button=label(text,15,ACCENT);button.setGravity(Gravity.CENTER);button.setContentDescription(description);button.setOnClickListener(click);return button;}
    private TextView label(String text,int size,int color){TextView view=new TextView(activity);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private GradientDrawable roundStroke(int color,int radius,int stroke){GradientDrawable bg=round(color,radius);bg.setStroke(dp(1),stroke);return bg;}
    private GradientDrawable roundTop(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);float r=dp(radius);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});return bg;}
    private android.graphics.drawable.Drawable pressBackground(){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},round(0xFFF2F2F7,12));states.addState(new int[0],new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));return states;}
    private GradientDrawable dotted(){GradientDrawable line=new GradientDrawable();line.setShape(GradientDrawable.LINE);line.setStroke(dp(2),0xFFAEAEB2,dp(2),dp(5));return line;}
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
