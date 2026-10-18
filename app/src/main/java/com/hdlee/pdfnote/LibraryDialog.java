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

/** Document shelf laid out like Samsung Notes: navigation pane (docked on wide screens, a drawer on phones) with
 *  모든 노트 · 즐겨찾기 · 최근 노트 · 휴지통 · 폴더 tree · 설정, a left-aligned title with labelled actions, and a card grid. */
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
    private static final int ALL=0,FAVORITES=1,RECENT=2,FOLDER=3,TRASH=4;
    private static final String[] NAV_NAMES={"모든 노트","즐겨찾기","최근 노트","휴지통"};
    private static final int INK=0xFF010102,MUTED=0xFF848487,ACCENT=0xFF387AFF,ACTIVE_BG=0xFFE6E6E9,SURFACE=0xFFFCFCFC,NAV_BG=0xFFF3F3F5,FIELD_BG=0xFFECECEE,RED=0xFFDB332A;
    private final Activity activity;
    private final LibraryRepository repository;
    private final Actions actions;
    private File folder;
    private int mode=FOLDER;
    private FolderTreeView tree;
    private FrameLayout content,drawer;private View drawerPanel,drawerScrim;private float drawerP;private int drawerWidth;private android.animation.ValueAnimator drawerAnim;
    private LinearLayout page,grid,navPane,selectionBar,selectionCommands,topBar,headingBlock,headerActions,pathRow;
    private ScrollView shelf;
    private TextView heading,subtitle,sortLabel,selectionCount;private boolean navCollapsed;
    private View compose;
    private EditText search;private LinearLayout searchRow;
    private final Set<File> selected=new LinkedHashSet<>();private boolean selectionMode;
    private final Map<File,LibraryRepository.TrashItem> trashIndex=new HashMap<>();
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
                else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&!dragging&&drawer!=null&&!wide){
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
        if(wide)buildNavPane(main);
        page=new LinearLayout(activity);page.setOrientation(LinearLayout.VERTICAL);main.addView(page,new LinearLayout.LayoutParams(0,-1,1));
        buildSearchField();buildTopBar();buildSelectionBar();buildSearchRow();buildHeading();
        shelf=new ScrollView(activity);shelf.setVerticalScrollBarEnabled(false);grid=new LinearLayout(activity);grid.setOrientation(LinearLayout.VERTICAL);grid.setPadding(dp(14),dp(6),dp(14),dp(96));shelf.addView(grid,new ScrollView.LayoutParams(-1,-2));page.addView(shelf,new LinearLayout.LayoutParams(-1,0,1));
        ImageView composeIcon=new ImageView(activity);composeIcon.setImageResource(R.drawable.ic_compose);composeIcon.setColorFilter(INK);composeIcon.setPadding(dp(15),dp(15),dp(15),dp(15));
        FrameLayout composeHost=new FrameLayout(activity);composeHost.setTag("compose_button");composeHost.setContentDescription("새로 만들기");composeHost.setBackground(roundStroke(0xFFFCFCFC,28,0x14000000));composeHost.setElevation(dp(6));composeHost.addView(composeIcon,new FrameLayout.LayoutParams(-1,-1));composeHost.setOnClickListener(this::newMenu);compose=composeHost;
        FrameLayout.LayoutParams composeParams=new FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM|Gravity.END);composeParams.setMargins(0,0,dp(22),dp(24));content.addView(compose,composeParams);
        buildDrawer();setContentView(content);
        setOnDismissListener(d->{generation++;previews.shutdownNow();});shelf.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(r-l!=or-ol)shelf.post(this::refreshGrid);});refresh();
    }
    @Override public void show(){super.show();getWindow().setBackgroundDrawableResource(android.R.color.transparent);getWindow().setLayout(-1,-1);getWindow().setStatusBarColor(SURFACE);getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);getWindow().setNavigationBarColor(SURFACE);content.post(this::refreshGrid);}
    @Override public void onBackPressed(){if(drawer.getVisibility()==View.VISIBLE){closeDrawer();return;}if(selectionMode){selectionMode=false;selected.clear();refreshGrid();return;}if(searchRow.getVisibility()==View.VISIBLE){toggleSearch();return;}if(mode==FOLDER&&!folder.equals(repository.root)){selectFolder(folder.getParentFile());return;}if(mode==TRASH){showMode(ALL);return;}actions.backedOut();dismiss();}

    // ---------------------------------------------------------------- layout
    /** Wide screens: the navigation pane is always docked on the left; ☰ folds it into an icon-only rail. */
    private void buildNavPane(LinearLayout main){
        navPane=buildNavPanel(true);navPane.setTag("library_nav");GradientDrawable shape=new GradientDrawable();shape.setColor(NAV_BG);float r=dp(16);shape.setCornerRadii(new float[]{0,0,r,r,r,r,0,0});navPane.setBackground(shape);
        main.addView(navPane,new LinearLayout.LayoutParams(dp(272),-1));
    }
    private void toggleNavCollapsed(){
        navCollapsed=!navCollapsed;LinearLayout.LayoutParams lp=(LinearLayout.LayoutParams)navPane.getLayoutParams();lp.width=dp(navCollapsed?64:272);navPane.setLayoutParams(lp);
        for(int i=0;i<navPane.getChildCount();i++){View child=navPane.getChildAt(i);Object tag=child.getTag();if(tag instanceof String&&((String)tag).startsWith("drawer_row:"))styleNavRow(child,navCollapsed);else if(child==tree||"nav_tree_only".equals(tag))child.setVisibility(navCollapsed?View.GONE:View.VISIBLE);}
    }
    private void styleNavRow(View row,boolean iconOnly){if(!(row instanceof LinearLayout))return;LinearLayout r=(LinearLayout)row;for(int i=1;i<r.getChildCount();i++)r.getChildAt(i).setVisibility(iconOnly?View.GONE:View.VISIBLE);r.setPadding(dp(iconOnly?13:12),0,dp(iconOnly?0:14),0);}
    /** Title on the left (Samsung Notes style, no back arrow). Wide screens show labelled actions and an inline search box; phones keep 검색 and ⋮. */
    private void buildTopBar(){
        LinearLayout bar=new LinearLayout(activity);topBar=bar;bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(wide?20:4),dp(6),dp(wide?12:4),0);
        if(!wide)bar.addView(icon(R.drawable.ic_menu,"폴더 트리 보기",v->openDrawer()),new LinearLayout.LayoutParams(dp(48),dp(52)));
        heading=label("",wide?24:22,INK);heading.setTag("library_heading");heading.setTypeface(Typeface.DEFAULT_BOLD);heading.setSingleLine();heading.setEllipsize(TextUtils.TruncateAt.END);heading.setPadding(dp(wide?0:6),0,dp(8),0);
        bar.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        headerActions=new LinearLayout(activity);headerActions.setGravity(Gravity.CENTER_VERTICAL);bar.addView(headerActions,new LinearLayout.LayoutParams(-2,-1));
        if(wide){
            headerActions.addView(textAction(R.drawable.ic_compose,"노트 작성",this::newMenu));
            headerActions.addView(textAction(R.drawable.ic_import,"PDF 가져오기",v->{dismiss();actions.importFiles(mode==FOLDER?folder:repository.root);}));
            headerActions.addView(textAction(R.drawable.ic_check_circle,"편집",v->{selectionMode=true;selected.clear();refreshGrid();}));
            headerActions.addView(textAction(R.drawable.ic_grid,"보기 방식",v->chooseViewMode()));
            headerActions.addView(textAction(0,"비우기",v->confirmEmptyTrash()),new LinearLayout.LayoutParams(-2,-1));headerActions.getChildAt(headerActions.getChildCount()-1).setTag("trash_empty");
            LinearLayout box=new LinearLayout(activity);box.setGravity(Gravity.CENTER_VERTICAL);box.setBackground(round(FIELD_BG,10));box.setPadding(dp(12),0,dp(6),0);
            search.setBackground(null);search.setTextSize(14);search.setHint("검색");box.addView(search,new LinearLayout.LayoutParams(0,-1,1));
            ImageView glass=new ImageView(activity);glass.setImageResource(R.drawable.ic_search);glass.setColorFilter(INK);glass.setPadding(dp(4),dp(4),dp(4),dp(4));box.addView(glass,new LinearLayout.LayoutParams(dp(28),dp(28)));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(dp(220),dp(38));bp.setMargins(dp(8),0,dp(2),0);headerActions.addView(box,bp);
        }else headerActions.addView(icon(R.drawable.ic_search,"문서 이름 검색",v->toggleSearch()),new LinearLayout.LayoutParams(dp(48),dp(52)));
        bar.addView(icon(R.drawable.ic_more_vert,"문서함 메뉴",this::libraryMenu),new LinearLayout.LayoutParams(dp(44),dp(52)));
        page.addView(bar,new LinearLayout.LayoutParams(-1,dp(wide?64:58)));
    }
    /** Icon + label header button (전체 화면 · 노트 작성 …), the Samsung Notes desktop toolbar look. */
    private View textAction(int iconRes,String text,View.OnClickListener click){
        LinearLayout b=new LinearLayout(activity);b.setGravity(Gravity.CENTER_VERTICAL);b.setPadding(dp(10),0,dp(10),0);b.setContentDescription(text);b.setTag("action:"+text);b.setBackground(pressBackground());b.setOnClickListener(click);
        if(iconRes!=0){ImageView image=new ImageView(activity);image.setImageResource(iconRes);image.setColorFilter(INK);b.addView(image,new LinearLayout.LayoutParams(dp(18),dp(18)));}
        TextView t=label(text,14,INK);t.setPadding(dp(iconRes!=0?6:0),0,0,0);t.setSingleLine();b.addView(t,new LinearLayout.LayoutParams(-2,-1));
        b.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(40)));return b;
    }
    private void buildSearchField(){search=new EditText(activity);search.setSingleLine();search.setTextSize(15);search.setTextColor(INK);search.setHintTextColor(MUTED);search.setHint("문서 이름 검색");search.setContentDescription("문서 이름 입력");search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence q,int start,int count,int after){}public void onTextChanged(CharSequence q,int start,int before,int count){refreshGrid();}public void afterTextChanged(Editable e){}});}
    private void buildSearchRow(){
        searchRow=new LinearLayout(activity);searchRow.setGravity(Gravity.CENTER_VERTICAL);searchRow.setPadding(dp(16),0,dp(16),dp(6));searchRow.setVisibility(View.GONE);
        if(!wide){search.setBackground(round(FIELD_BG,10));search.setPadding(dp(14),0,dp(14),0);searchRow.addView(search,new LinearLayout.LayoutParams(-1,dp(44)));}
        page.addView(searchRow,new LinearLayout.LayoutParams(-1,-2));
    }
    /** Second header line: folder path (폴더 › 업무 › 자료) or the trash note on the left, the sort order on the right. */
    private void buildHeading(){
        LinearLayout block=new LinearLayout(activity);headingBlock=block;block.setGravity(Gravity.CENTER_VERTICAL);block.setPadding(dp(wide?20:16),0,dp(wide?14:10),dp(4));
        LinearLayout left=new LinearLayout(activity);left.setGravity(Gravity.CENTER_VERTICAL);block.addView(left,new LinearLayout.LayoutParams(0,-1,1));
        pathRow=new LinearLayout(activity);pathRow.setGravity(Gravity.CENTER_VERTICAL);pathRow.setTag("folder_path");HorizontalScrollView pathScroll=new HorizontalScrollView(activity);pathScroll.setHorizontalScrollBarEnabled(false);pathScroll.addView(pathRow,new HorizontalScrollView.LayoutParams(-2,-1));left.addView(pathScroll,new LinearLayout.LayoutParams(0,-1,1));
        subtitle=label("",13,MUTED);subtitle.setSingleLine();subtitle.setEllipsize(TextUtils.TruncateAt.END);left.addView(subtitle,new LinearLayout.LayoutParams(-2,-1));
        LinearLayout sort=new LinearLayout(activity);sort.setGravity(Gravity.CENTER_VERTICAL);sort.setPadding(dp(8),0,dp(8),0);sort.setContentDescription("정렬 방식");sort.setTag("sort_order");sort.setBackground(pressBackground());sort.setOnClickListener(v->chooseSort());
        ImageView sortIcon=new ImageView(activity);sortIcon.setImageResource(R.drawable.ic_sort);sortIcon.setColorFilter(INK);sort.addView(sortIcon,new LinearLayout.LayoutParams(dp(16),dp(16)));
        sortLabel=label("",13,INK);sortLabel.setPadding(dp(5),0,0,0);sort.addView(sortLabel,new LinearLayout.LayoutParams(-2,-1));block.addView(sort,new LinearLayout.LayoutParams(-2,dp(34)));
        page.addView(block,new LinearLayout.LayoutParams(-1,dp(38)));
    }
    /** Replaces the top bar while documents are selected: back, "N개 선택됨", icon commands (with labels on wide screens) and a ⋮ menu. */
    private void buildSelectionBar(){
        selectionBar=new LinearLayout(activity);selectionBar.setGravity(Gravity.CENTER_VERTICAL);selectionBar.setPadding(dp(6),dp(4),dp(6),0);selectionBar.setVisibility(View.GONE);selectionBar.setTag("selection_bar");
        selectionBar.addView(icon(R.drawable.ic_close,"선택 해제",v->onBackPressed()),new LinearLayout.LayoutParams(dp(48),dp(52)));
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
        if(files.size()==1)rows.add(new AnchoredMenu.Row("이름 변경",R.drawable.ic_rename,()->runSelectionCommand("이름 변경")).tint(0xFF848487));
        rows.add(new AnchoredMenu.Row("복사본 만들기",R.drawable.ic_copy,()->runSelectionCommand("복사")).tint(0xFF848487));
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
        int width=Math.min(dp(320),Math.round(activity.getResources().getDisplayMetrics().widthPixels*.86f));drawerWidth=width;
        if(!wide){LinearLayout panel=buildNavPanel(false);GradientDrawable panelShape=new GradientDrawable();panelShape.setColor(SURFACE);panelShape.setCornerRadii(new float[]{0,0,dp(24),dp(24),dp(24),dp(24),0,0});panel.setBackground(panelShape);panel.setElevation(dp(14));drawerPanel=panel;drawer.addView(panel,new FrameLayout.LayoutParams(width,-1,Gravity.START));}
        else drawerPanel=new View(activity);
        content.addView(drawer,new FrameLayout.LayoutParams(-1,-1));
    }
    /** 모든 노트 · 즐겨찾기 · 최근 노트 · 휴지통 ┄ 폴더 (tree with counts) ┄ 설정 — shared by the docked pane and the phone drawer. */
    private LinearLayout buildNavPanel(boolean docked){
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(8),dp(10),dp(8),dp(10));
        LinearLayout top=new LinearLayout(activity);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(icon(R.drawable.ic_menu,docked?"폴더 트리 보기":"폴더 트리 닫기",v->{if(docked)toggleNavCollapsed();else closeDrawer();}),new LinearLayout.LayoutParams(dp(48),dp(48)));panel.addView(top,new LinearLayout.LayoutParams(-1,dp(52)));
        int[] icons={R.drawable.ic_notes_all,R.drawable.ic_star_outline,R.drawable.ic_clock,R.drawable.ic_delete};
        for(int i=0;i<4;i++){final int index=i;panel.addView(drawerRow(icons[i],NAV_NAMES[i],"drawer_count:"+i,v->{closeDrawer();showMode(index==3?TRASH:index);}),new LinearLayout.LayoutParams(-1,dp(46)));}
        panel.addView(dashes(),dashParams());
        panel.addView(drawerRow(R.drawable.ic_folder_check,"폴더","drawer_count:4",v->selectFolder(repository.root)),new LinearLayout.LayoutParams(-1,dp(46)));
        tree=new FolderTreeView(activity,repository,folder,this::selectFolder,false);tree.setFolderMenu(this::folderMenu);tree.setBackgroundColor(Color.TRANSPARENT);panel.addView(tree,new LinearLayout.LayoutParams(-1,0,1));
        View manage=drawerRow(R.drawable.ic_plus,"폴더 관리",null,this::folderManageMenu);manage.setTag("nav_tree_only");panel.addView(manage,new LinearLayout.LayoutParams(-1,dp(44)));
        panel.addView(dashes(),dashParams());
        panel.addView(drawerRow(R.drawable.ic_settings,"설정","drawer_count:5",v->{closeDrawer();actions.settings();}),new LinearLayout.LayoutParams(-1,dp(46)));
        return panel;
    }
    private View dashes(){View v=new View(activity);v.setBackground(dotted());return v;}
    private LinearLayout.LayoutParams dashParams(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(2));lp.setMargins(dp(10),dp(6),dp(10),dp(6));return lp;}
    private View drawerRow(int iconRes,String title,String countTag,View.OnClickListener click){
        LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),0,dp(14),0);row.setContentDescription(title);row.setTag("drawer_row:"+title);
        ImageView image=new ImageView(activity);image.setImageResource(iconRes);image.setColorFilter(INK);row.addView(image,new LinearLayout.LayoutParams(dp(22),dp(22)));
        TextView name=label(title,15,INK);name.setPadding(dp(14),0,0,0);name.setSingleLine();row.addView(name,new LinearLayout.LayoutParams(0,-1,1));
        TextView count=label("",13,MUTED);if(countTag!=null)count.setTag(countTag);row.addView(count,new LinearLayout.LayoutParams(-2,-1));
        if(click!=null){row.setOnClickListener(click);row.setBackground(round(0x00000000,10));}return row;
    }
    private void updateDrawerCounts(){
        View host=wide?navPane:drawer;if(host==null)return;
        int[] counts={repository.allDocuments("").size(),repository.favorites("").size(),Math.min(40,repository.allDocuments("").size()),repository.trashItems().size(),repository.folderCount()};
        for(int i=0;i<counts.length;i++){View v=host.findViewWithTag("drawer_count:"+i);if(v instanceof TextView)((TextView)v).setText(i==2?"":String.valueOf(counts[i]));}
        for(int i=0;i<5;i++){View row=host.findViewWithTag("drawer_row:"+(i<4?NAV_NAMES[i]:"폴더"));if(row==null)continue;boolean on=i<4?mode==(i==3?TRASH:i):mode==FOLDER&&folder.equals(repository.root);row.setBackground(round(on?ACTIVE_BG:0x00000000,10));View name=((LinearLayout)row).getChildAt(1);if(name instanceof TextView)((TextView)name).setTypeface(on?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);}
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
    private void openDrawer(){if(wide){toggleNavCollapsed();return;}tree.select(folder);updateDrawerCounts();if(drawer.getVisibility()!=View.VISIBLE)setDrawerProgress(0);drawer.setVisibility(View.VISIBLE);settleDrawer(1);}
    private void closeDrawer(){if(drawer.getVisibility()==View.VISIBLE)settleDrawer(0);}
    private void toggleSearch(){
        boolean show=searchRow.getVisibility()!=View.VISIBLE;searchRow.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){search.requestFocus();InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.showSoftInput(search,InputMethodManager.SHOW_IMPLICIT);}
        else{search.setText("");InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(search.getWindowToken(),0);}
    }

    // ---------------------------------------------------------------- modes and content
    private void showMode(int newMode){mode=newMode;selected.clear();selectionMode=false;refresh();}
    private void selectFolder(File selectedFolder){this.selected.clear();selectionMode=false;mode=FOLDER;folder=selectedFolder;actions.selectedFolder(folder);closeDrawer();refresh();}
    private List<File> items(){String q=search.getText().toString();switch(mode){case ALL:return repository.allDocuments(q);case FAVORITES:return repository.favorites(q);case RECENT:return repository.recent(q,40);case TRASH:{trashIndex.clear();List<File> files=new ArrayList<>();String needle=q.trim().toLowerCase(Locale.ROOT);for(LibraryRepository.TrashItem item:repository.trashItems())if(needle.isEmpty()||item.name.toLowerCase(Locale.ROOT).contains(needle)){trashIndex.put(item.file,item);files.add(item.file);}return files;}default:return repository.sorted(folder,q);}}
    private void refresh(){updateDrawerCounts();if(tree!=null&&mode==FOLDER)tree.select(folder);refreshGrid();}
    private String modeTitle(){return mode==ALL?NAV_NAMES[0]:mode==FAVORITES?NAV_NAMES[1]:mode==RECENT?NAV_NAMES[2]:mode==TRASH?NAV_NAMES[3]:folder.equals(repository.root)?"폴더":folder.getName();}
    private void updateHeading(List<File> items){
        heading.setText(modeTitle());
        boolean trash=mode==TRASH;subtitle.setText(trash?"문서를 누르면 복원하거나 영구 삭제할 수 있습니다.":"");subtitle.setVisibility(trash?View.VISIBLE:View.GONE);
        sortLabel.setText(LibraryRepository.SORT_NAMES[Math.max(0,Math.min(LibraryRepository.SORT_NAMES.length-1,repository.sortMode()))]);((View)sortLabel.getParent()).setVisibility(trash?View.GONE:View.VISIBLE);
        if(headerActions!=null&&wide)for(int i=0;i<headerActions.getChildCount();i++){View a=headerActions.getChildAt(i);boolean empty="trash_empty".equals(a.getTag());a.setVisibility(trash?(empty||!(a.getTag() instanceof String)?View.VISIBLE:View.GONE):(empty?View.GONE:View.VISIBLE));}
        pathRow.removeAllViews();boolean nested=mode==FOLDER&&!folder.equals(repository.root);((View)pathRow.getParent()).setVisibility(nested?View.VISIBLE:View.GONE);
        if(nested){List<File> chain=new ArrayList<>();for(File f=folder;f!=null;f=f.getParentFile()){chain.add(0,f);if(f.equals(repository.root))break;}
            for(int i=0;i<chain.size();i++){final File target=chain.get(i);boolean last=i==chain.size()-1;
                if(i>0){ImageView sep=new ImageView(activity);sep.setImageResource(R.drawable.ic_chevron_right);sep.setColorFilter(MUTED);pathRow.addView(sep,new LinearLayout.LayoutParams(dp(14),dp(14)));}
                TextView crumb=label(target.equals(repository.root)?"폴더":target.getName(),13,last?INK:0xFF636368);crumb.setPadding(dp(6),0,dp(6),0);if(last)crumb.setTypeface(Typeface.DEFAULT_BOLD);else{crumb.setBackground(pressBackground());crumb.setOnClickListener(v->selectFolder(target));}crumb.setContentDescription(last?"현재 폴더 "+crumb.getText():"상위 폴더 "+crumb.getText()+"로");if(i==chain.size()-2)crumb.setTag("folder_up");pathRow.addView(crumb,new LinearLayout.LayoutParams(-2,dp(30)));}}
    }
    private void refreshGrid(){
        if(previews.isShutdown()||grid==null)return;final int version=++generation;grid.removeAllViews();List<File> items=items();updateHeading(items);
        selectionBar.setVisibility(selectionMode?View.VISIBLE:View.GONE);topBar.setVisibility(selectionMode?View.GONE:View.VISIBLE);headingBlock.setVisibility(selectionMode?View.GONE:View.VISIBLE);if(selectionMode)searchRow.setVisibility(View.GONE);compose.setVisibility(selectionMode||wide||mode==TRASH?View.GONE:View.VISIBLE);if(selectionMode){selectionCount.setText(selected.size()+"개 선택됨");rebuildSelectionCommands();}
        int viewMode=mode==TRASH?0:repository.viewMode();int usable=Math.max(shelf.getWidth(),dp(240))-dp(wide?40:28);int columns=viewMode==2?1:Math.max(2,Math.min(8,usable/dp(viewMode==1?100:wide?136:124)));int cell=viewMode==2?0:usable/columns-dp(wide?22:16);
        if(items.isEmpty()){TextView empty=label(search.getText().length()>0?"검색 결과가 없습니다":mode==FAVORITES?"즐겨찾기한 노트가 없습니다":mode==TRASH?"휴지통이 비어 있습니다":"노트가 없습니다",16,MUTED);empty.setGravity(Gravity.CENTER);grid.addView(empty,new LinearLayout.LayoutParams(-1,dp(160)));return;}
        for(int i=0;i<items.size();i+=columns){LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.TOP);grid.addView(row,new LinearLayout.LayoutParams(-1,-2));for(int j=0;j<columns;j++){int index=i+j;LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(0,-2,1);layout.setMargins(dp(wide?11:8),dp(4),dp(wide?11:8),dp(viewMode==2?6:18));if(index<items.size())row.addView(viewMode==2?listItem(items.get(index),version):card(items.get(index),version,cell),layout);else row.addView(new View(activity),layout);}}
    }
    /** Samsung Notes style card: portrait thumbnail with a hairline frame, title centred below, date in grey. */
    private View card(File file,int version,int cell){
        LibraryRepository.TrashItem trashed=mode==TRASH?trashIndex.get(file):null;String title=trashed!=null?trashed.name:file.getName();
        LinearLayout card=new LinearLayout(activity);card.setTag("document:"+title);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER_HORIZONTAL);card.setContentDescription(title);
        int coverHeight=file.isDirectory()?Math.round(cell*.86f):Math.round(cell*1.28f);
        FrameLayout coverFrame=new FrameLayout(activity);card.addView(coverFrame,new LinearLayout.LayoutParams(-1,coverHeight));
        if(file.isDirectory()){
            ImageView shape=new ImageView(activity);shape.setImageDrawable(new FolderShapeDrawable(repository.folderColor(file)));shape.setContentDescription(file.getName()+" 폴더");coverFrame.addView(shape,new FrameLayout.LayoutParams(-1,-1));
            TextView count=label(String.valueOf(repository.list(file).size()),13,0xFF4D4D52);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.BOTTOM);cp.setMargins(0,0,dp(10),dp(8));coverFrame.addView(count,cp);
        }else{
            ImageView cover=new ImageView(activity);cover.setScaleType(ImageView.ScaleType.CENTER_CROP);cover.setBackground(roundStroke(Color.WHITE,8,0x1F000000));cover.setClipToOutline(true);cover.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(8));}});cover.setImageDrawable(null);cover.setContentDescription(title+" 미리보기");FrameLayout.LayoutParams cvp=new FrameLayout.LayoutParams(-1,-1);coverFrame.addView(cover,cvp);preview(file,cover,version);
            if(trashed==null&&repository.favorite(file)){ImageView star=new ImageView(activity);star.setImageResource(R.drawable.ic_star);star.setColorFilter(0xFFFFB020);star.setPadding(dp(3),dp(3),dp(3),dp(3));FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(dp(22),dp(22),Gravity.END|Gravity.TOP);sp.setMargins(0,dp(4),dp(4),0);coverFrame.addView(star,sp);}
            if(selectionMode){CheckBox check=new CheckBox(activity);check.setChecked(selected.contains(file));check.setContentDescription("선택 "+file.getName());check.setOnClickListener(v->toggleSelected(file));FrameLayout.LayoutParams kp=new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.TOP);kp.setMargins(dp(2),dp(2),0,0);coverFrame.addView(check,kp);}
        }
        TextView name=label(title.replaceFirst("(?i)\\.pdf$",""),13,INK);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setGravity(Gravity.CENTER);name.setPadding(dp(2),dp(8),dp(2),0);card.addView(name,new LinearLayout.LayoutParams(-1,-2));
        if(file.isFile()){TextView date=label(trashed!=null?"삭제 "+dayText(trashed.deleted):dateText(file),11,MUTED);date.setGravity(Gravity.CENTER);card.addView(date,new LinearLayout.LayoutParams(-1,dp(20)));}
        View.OnClickListener open=v->{if(trashed!=null)trashItemMenu(trashed,v);else openOrSelect(file);};card.setOnClickListener(open);coverFrame.setOnClickListener(open);
        View.OnLongClickListener hold=v->{if(trashed!=null)trashItemMenu(trashed,v);else longPressed(file,v);return true;};card.setOnLongClickListener(hold);coverFrame.setOnLongClickListener(hold);return card;
    }
    private void longPressed(File file,View anchor){if(file.isDirectory())folderMenu(file,anchor);else{selectionMode=true;selected.add(file);refreshGrid();}}
    private String dayText(long time){Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(time);return new SimpleDateFormat(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)?"M월 d일":"yyyy년 M월 d일",Locale.KOREA).format(new Date(time));}
    private String dateText(File file){
        long time=Math.max(file.lastModified(),AnnotationStore.modified(activity,Uri.fromFile(file)));Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(time);Date date=new Date(time);
        if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR))return new SimpleDateFormat("a h:mm",Locale.KOREA).format(date);
        if(now.get(Calendar.YEAR)==then.get(Calendar.YEAR))return new SimpleDateFormat("M월 d일",Locale.KOREA).format(date);return new SimpleDateFormat("yyyy년 M월 d일",Locale.KOREA).format(date);
    }
    private void preview(File file,ImageView cover,int version){
        String key=file.getAbsolutePath()+":"+file.lastModified()+":"+AnnotationStore.modified(activity,Uri.fromFile(file))+":top128";Bitmap cached=covers.get(key);if(cached!=null){cover.setImageBitmap(cached);return;}
        previews.execute(()->{if(version!=generation)return;try(ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(descriptor);PdfRenderer.Page page=renderer.openPage(0)){
            float scale=Math.min(320f/page.getWidth(),480f/page.getHeight());Bitmap bitmap=Bitmap.createBitmap(Math.max(1,Math.round(page.getWidth()*scale)),Math.max(1,Math.round(page.getHeight()*scale)),Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Matrix matrix=new Matrix();matrix.postScale(scale,scale);page.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);AnnotationStore notes=new AnnotationStore(activity);notes.open(Uri.fromFile(file));AnnotationPainter.all(activity,new Canvas(bitmap),new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),notes,0);
            int keep=Math.round(bitmap.getWidth()*1.28f);Bitmap top=bitmap.getHeight()>keep?Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),keep):bitmap;
            covers.put(key,top);activity.runOnUiThread(()->{if(isShowing()&&version==generation)cover.setImageBitmap(top);});
        }catch(Exception ignored){}});
    }
    private View listItem(File file,int version){
        LinearLayout row=new LinearLayout(activity);row.setTag("document:"+file.getName());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),dp(8),dp(10),dp(8));row.setBackground(round(Color.WHITE,14));row.setContentDescription(file.getName());
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);row.addView(image,new LinearLayout.LayoutParams(dp(56),dp(56)));
        if(file.isDirectory()){image.setImageDrawable(new FolderShapeDrawable(repository.folderColor(file)));}else{image.setBackground(roundStroke(Color.WHITE,12,0x1A2B2F4A));image.setClipToOutline(true);image.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dp(12));}});image.setImageDrawable(null);preview(file,image,version);}
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
            new AnchoredMenu.Row("파일 가져오기",R.drawable.ic_import,()->{dismiss();actions.importFiles(target);}).tint(0xFF387AFF),
            new AnchoredMenu.Row("폴더 만들기",R.drawable.ic_folder_open,()->createFolder(target,()->{tree.reload();refresh();})).tint(0xFFF5A623)));
    }
    private void libraryMenu(View anchor){
        PopupMenu menu=new PopupMenu(activity,anchor);int order=0;
        if(mode==TRASH){menu.getMenu().add(0,10,order++,"비우기");menu.getMenu().add(0,11,order++,"모두 복원");}
        else{
            if(!wide){menu.getMenu().add(0,12,order++,"PDF 가져오기");menu.getMenu().add(0,1,order++,"편집");menu.getMenu().add(0,2,order++,"보기 방식");}
            menu.getMenu().add(0,3,order++,"정렬 방식");menu.getMenu().add(0,4,order++,(repository.pinFavorites()?"✓  ":"")+"즐겨찾기 맨 위 고정");
            menu.getMenu().add(0,13,order++,"폴더 만들기");if(mode==FOLDER)menu.getMenu().add(0,5,order++,"폴더 색상");
            menu.getMenu().add(0,8,order++,mode==FOLDER&&!folder.equals(repository.root)?"이 폴더 백업":"모든 문서 백업");menu.getMenu().add(0,9,order++,"백업 복원");
        }
        menu.getMenu().add(0,7,order++,"설정");
        menu.setOnMenuItemClickListener(item->{switch(item.getItemId()){
            case 1:selectionMode=!selectionMode;selected.clear();refreshGrid();break;
            case 2:chooseViewMode();break;
            case 3:chooseSort();break;
            case 4:repository.pinFavorites(!repository.pinFavorites());refreshGrid();break;
            case 5:chooseFolderColor(folder);break;
            case 7:actions.settings();break;
            case 8:actions.backupFolder(mode==FOLDER?folder:repository.root);break;
            case 9:actions.restoreBackup();break;
            case 10:confirmEmptyTrash();break;
            case 11:restoreAll();break;
            case 12:dismiss();actions.importFiles(mode==FOLDER?folder:repository.root);break;
            case 13:createFolder(mode==FOLDER?folder:repository.root,()->{tree.reload();refresh();});break;
        }return true;});menu.show();
    }
    private void chooseViewMode(){new AlertDialog.Builder(activity).setTitle("보기 방식").setSingleChoiceItems(LibraryRepository.VIEW_NAMES,repository.viewMode(),(d,index)->{repository.viewMode(index);d.dismiss();refreshGrid();}).show();}
    private void chooseSort(){new AlertDialog.Builder(activity).setTitle("정렬 방식").setSingleChoiceItems(LibraryRepository.SORT_NAMES,repository.sortMode(),(d,index)->{repository.sortMode(index);d.dismiss();refreshGrid();}).show();}
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
    private void showTrash(){showMode(TRASH);}
    /** Tapping a document in 휴지통: 복원 or 영구 삭제 (the trash is a normal view, as in Samsung Notes). */
    private void trashItemMenu(LibraryRepository.TrashItem item,View anchor){
        AnchoredMenu.show(activity,anchor,false,AnchoredMenu.rows(
            new AnchoredMenu.Row("복원",R.drawable.ic_restore,()->{try{File restored=repository.restore(item);actions.changed(item.file,restored,true);if(tree!=null)tree.reload();refresh();Toast.makeText(activity,"복원했습니다",Toast.LENGTH_SHORT).show();}catch(Exception error){Toast.makeText(activity,error.getMessage(),Toast.LENGTH_LONG).show();}}),
            new AnchoredMenu.Row("영구 삭제",R.drawable.ic_delete,()->new AlertDialog.Builder(activity).setMessage("‘"+item.name.replaceFirst("(?i)\\.pdf$","")+"’을(를) 영구 삭제합니다. 되돌릴 수 없습니다.").setPositiveButton("삭제",(d,w)->{if(repository.deleteForever(item))refresh();}).setNegativeButton("취소",null).show()).tint(RED)),null);
    }
    private void confirmEmptyTrash(){int n=repository.trashItems().size();if(n==0){Toast.makeText(activity,"휴지통이 비어 있습니다",Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(activity).setMessage("휴지통의 노트 "+n+"개를 영구 삭제합니다. 되돌릴 수 없습니다.").setPositiveButton("삭제",(d,w)->{int removed=repository.emptyTrash();refresh();Toast.makeText(activity,removed+"개 문서를 영구 삭제했습니다",Toast.LENGTH_SHORT).show();}).setNegativeButton("취소",null).show();}
    private void restoreAll(){int done=0;for(LibraryRepository.TrashItem item:repository.trashItems())try{File restored=repository.restore(item);actions.changed(item.file,restored,true);done++;}catch(Exception ignored){}if(tree!=null)tree.reload();refresh();Toast.makeText(activity,done+"개 문서를 복원했습니다",Toast.LENGTH_SHORT).show();}
    private void createFolder(File parent,Runnable done){EditText input=new EditText(activity);input.setHint("폴더 이름");input.setSingleLine();AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("폴더 만들기").setView(input).setPositiveButton("만들기",null).setNegativeButton("취소",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{repository.createFolder(parent,input.getText().toString());dialog.dismiss();done.run();}catch(Exception error){input.setError(error.getMessage());}}));dialog.show();}

    // ---------------------------------------------------------------- view helpers
    private ImageButton icon(int resource,String description,View.OnClickListener click){ImageButton b=new ImageButton(activity);b.setImageResource(resource);b.setColorFilter(INK);b.setContentDescription(description);b.setBackgroundColor(Color.TRANSPARENT);b.setScaleType(ImageView.ScaleType.CENTER);b.setOnClickListener(click);return b;}
    private TextView button(String text,String description,View.OnClickListener click){TextView button=label(text,15,ACCENT);button.setGravity(Gravity.CENTER);button.setContentDescription(description);button.setOnClickListener(click);return button;}
    private TextView label(String text,int size,int color){TextView view=new TextView(activity);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);bg.setCornerRadius(dp(radius));return bg;}
    private GradientDrawable roundStroke(int color,int radius,int stroke){GradientDrawable bg=round(color,radius);bg.setStroke(dp(1),stroke);return bg;}
    private GradientDrawable roundTop(int color,int radius){GradientDrawable bg=new GradientDrawable();bg.setColor(color);float r=dp(radius);bg.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});return bg;}
    private android.graphics.drawable.Drawable pressBackground(){android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();states.addState(new int[]{android.R.attr.state_pressed},round(0xFFF2F2F4,12));states.addState(new int[0],new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));return states;}
    private GradientDrawable dotted(){GradientDrawable line=new GradientDrawable();line.setShape(GradientDrawable.LINE);line.setStroke(dp(2),0xFFAEAEB2,dp(2),dp(5));return line;}
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
}
