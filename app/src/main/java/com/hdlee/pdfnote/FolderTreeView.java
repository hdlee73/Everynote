package com.hdlee.pdfnote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import java.io.File;
import java.util.*;

/** A navigable folder tree, shared by the navigation pane (Samsung Notes style rows: chevron, line folder icon in the
 *  folder colour, name, item count) and the copy/move picker (which also lists the library root). */
final class FolderTreeView extends ScrollView {
    interface Selected {void folder(File folder);}
    private static final int INK=0xFF010102,MUTED=0xFF848487,SELECTED_BG=0xFFE6E6E9;
    private final LibraryRepository repository;
    private final LinearLayout rows;
    private final Set<String> expanded=new HashSet<>();
    private final boolean showRoot;
    private File selected;
    private final Selected listener; private java.util.function.BiConsumer<File,android.view.View> menu;
    void setFolderMenu(java.util.function.BiConsumer<File,android.view.View> menu){this.menu=menu;}
    FolderTreeView(Context context,LibraryRepository repository,File selected,Selected listener){this(context,repository,selected,listener,true);}
    FolderTreeView(Context context,LibraryRepository repository,File selected,Selected listener,boolean showRoot){
        super(context);this.repository=repository;this.listener=listener;this.selected=selected;this.showRoot=showRoot;setVerticalScrollBarEnabled(false);
        rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);rows.setPadding(dp(4),dp(2),dp(4),dp(6));addView(rows,new LayoutParams(-1,-2));setBackgroundColor(0xFFFCFCFC);
        for(File parent=selected;parent!=null;parent=parent.getParentFile()){expanded.add(parent.getAbsolutePath());if(parent.equals(repository.root))break;}reload();
    }
    File selected(){return selected;}
    void select(File folder){selected=folder;for(File parent=folder;parent!=null;parent=parent.getParentFile()){expanded.add(parent.getAbsolutePath());if(parent.equals(repository.root))break;}reload();}
    void reload(){rows.removeAllViews();if(showRoot)addFolder(repository.root,0);else for(File child:children(repository.root))addFolder(child,0);}
    private List<File> children(File folder){List<File> list=new ArrayList<>();for(File file:repository.list(folder))if(file.isDirectory())list.add(file);return list;}
    private void addFolder(File folder,int depth){
        List<File> children=children(folder);boolean on=folder.equals(selected)&&(showRoot||!folder.equals(repository.root));
        LinearLayout row=new LinearLayout(getContext());row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(dp(Math.min(depth,10)*14),0,dp(10),0);
        if(on){GradientDrawable bg=new GradientDrawable();bg.setColor(SELECTED_BG);bg.setCornerRadius(dp(10));row.setBackground(bg);}
        ImageView arrow=new ImageView(getContext());arrow.setScaleType(ImageView.ScaleType.CENTER);if(!children.isEmpty()){arrow.setImageResource(expanded.contains(folder.getAbsolutePath())?R.drawable.ic_chevron_down:R.drawable.ic_chevron_right);arrow.setColorFilter(MUTED);}
        arrow.setContentDescription(folder.getName()+" 하위 폴더 펼치기");row.addView(arrow,new LinearLayout.LayoutParams(dp(28),dp(44)));
        arrow.setOnClickListener(v->{if(children.isEmpty())return;if(!expanded.remove(folder.getAbsolutePath()))expanded.add(folder.getAbsolutePath());reload();});
        TextView name=new TextView(getContext());name.setText(folder.equals(repository.root)?"폴더":folder.getName());name.setTextSize(14);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setTextColor(INK);if(on)name.setTypeface(Typeface.DEFAULT_BOLD);
        android.graphics.drawable.Drawable icon=new FolderIconDrawable(repository.folderColor(folder),dp(20));name.setCompoundDrawablesWithIntrinsicBounds(icon,null,null,null);name.setCompoundDrawablePadding(dp(12));name.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);name.setIncludeFontPadding(false);
        row.addView(name,new LinearLayout.LayoutParams(0,dp(44),1));name.setContentDescription("폴더 "+name.getText());name.setOnClickListener(v->{selected=folder;reload();listener.folder(folder);});
        int count=0;for(File f:repository.list(folder))if(f.isFile())count++;TextView number=new TextView(getContext());number.setText(String.valueOf(count));number.setTextSize(12);number.setTextColor(MUTED);number.setGravity(android.view.Gravity.CENTER_VERTICAL|android.view.Gravity.END);row.addView(number,new LinearLayout.LayoutParams(-2,dp(44)));
        name.setOnLongClickListener(v->{if(menu==null)return false;menu.accept(folder,v);return true;});rows.addView(row,new LinearLayout.LayoutParams(-1,dp(44)));if(expanded.contains(folder.getAbsolutePath()))for(File child:children)addFolder(child,depth+1);
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
