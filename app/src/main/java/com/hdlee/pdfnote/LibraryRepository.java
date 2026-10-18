package com.hdlee.pdfnote;

import android.content.*;
import android.net.Uri;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

/** Owns the app's permanent PDF copies. All file mutations are serialized. */
final class LibraryRepository {
    private final Context context;
    private final SharedPreferences preferences;
    final File root;
    LibraryRepository(Context context){this.context=context.getApplicationContext();root=NotebookFiles.root(context);preferences=context.getSharedPreferences("pdf_note_library",Context.MODE_PRIVATE);}
    boolean managed(Uri uri){return "file".equals(uri.getScheme())&&uri.getPath()!=null&&managed(new File(uri.getPath()));}
    boolean managed(File file){try{return file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator);}catch(IOException error){return false;}}
    private void folder(File folder)throws IOException{if(!(folder.equals(root)||managed(folder))||!folder.isDirectory())throw new IOException("문서함 폴더를 선택하세요");}
    List<File> list(File directory){File[] files=directory.listFiles(f->!f.getName().startsWith(".")&&(f.isDirectory()||f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")));List<File> result=files==null?new ArrayList<>():new ArrayList<>(Arrays.asList(files));result.sort(Comparator.comparing((File f)->!f.isDirectory()).thenComparing(File::getName,String.CASE_INSENSITIVE_ORDER));return result;}
    static final String[] SORT_NAMES={"이름 오름차순","이름 내림차순","최근 수정","최근 열기"};
    static final String[] VIEW_NAMES={"큰 표지","작은 표지","목록"};
    static final int[] FOLDER_COLORS={0xFF8FA8F0,0xFFF4B67E,0xFF7FCFB2,0xFFB9A0F2,0xFFF2A3BA,0xFFA6B2C6};
    private static final int[] LEGACY_FOLDER_COLORS={0xFF5C86BE,0xFFDA9A43,0xFF54A485,0xFF9272C3,0xFFD36D86,0xFF718096};
    static final String[] FOLDER_COLOR_NAMES={"블루","오렌지","그린","퍼플","로즈","그레이"};
    int sortMode(){return Math.max(0,Math.min(3,preferences.getInt("sort",0)));}
    void sortMode(int mode){preferences.edit().putInt("sort",mode).apply();}
    int viewMode(){return Math.max(0,Math.min(2,preferences.getInt("view",0)));}
    void viewMode(int mode){preferences.edit().putInt("view",mode).apply();}
    int folderColor(File folder){int saved=preferences.getInt("color:"+folder.getAbsolutePath(),FOLDER_COLORS[0]);for(int i=0;i<LEGACY_FOLDER_COLORS.length;i++)if(saved==LEGACY_FOLDER_COLORS[i])return FOLDER_COLORS[i];return saved;}
    void folderColor(File folder,int color){preferences.edit().putInt("color:"+folder.getAbsolutePath(),color).apply();}
    void opened(Uri uri){if(managed(uri))preferences.edit().putLong("opened:"+uri.getPath(),System.currentTimeMillis()).apply();}
    List<File> sorted(File directory,String query){
        List<File> items=list(directory);String q=query.trim().toLowerCase(Locale.ROOT);items.removeIf(f->!f.getName().toLowerCase(Locale.ROOT).contains(q));
        items.sort(Comparator.comparing((File f)->!f.isDirectory()).thenComparing(order()));return items;
    }
    boolean favorite(File file){return preferences.getBoolean("fav:"+file.getAbsolutePath(),false);}
    void favorite(File file,boolean on){SharedPreferences.Editor edit=preferences.edit();if(on)edit.putBoolean("fav:"+file.getAbsolutePath(),true);else edit.remove("fav:"+file.getAbsolutePath());edit.apply();}
    boolean pinFavorites(){return preferences.getBoolean("pin_favorites",false);}
    void pinFavorites(boolean on){preferences.edit().putBoolean("pin_favorites",on).apply();}
    /** Favorite documents from every folder, in the current sort order. */
    List<File> favorites(String query){List<File> items=allDocuments(query);items.removeIf(f->!favorite(f));return items;}
    /** The most recently opened or edited documents, newest first. */
    List<File> recent(String query,int limit){List<File> items=allDocuments(query);items.sort(Comparator.comparingLong((File f)->Math.max(preferences.getLong("opened:"+f.getAbsolutePath(),0),modified(f))).reversed());return items.size()>limit?new ArrayList<>(items.subList(0,limit)):items;}
    int folderCount(){return countFolders(root);}
    private int countFolders(File directory){int n=0;for(File item:list(directory))if(item.isDirectory())n+=1+countFolders(item);return n;}
    private Comparator<File> order(){Comparator<File> base=baseOrder();return pinFavorites()?Comparator.comparing((File f)->!favorite(f)).thenComparing(base):base;}
    private Comparator<File> baseOrder(){
        Comparator<File> byName=Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER);Comparator<File> order=byName;
        switch(sortMode()){case 1:order=byName.reversed();break;case 2:order=Comparator.comparingLong(this::modified).reversed().thenComparing(byName);break;case 3:order=Comparator.comparingLong((File f)->preferences.getLong("opened:"+f.getAbsolutePath(),0)).reversed().thenComparing(byName);break;}
        return order;
    }
    /** Every PDF and note in the library, whatever folder it lives in (the trash is excluded), in the current sort order. */
    List<File> allDocuments(String query){
        List<File> items=new ArrayList<>();collectDocuments(root,items);String q=query.trim().toLowerCase(Locale.ROOT);items.removeIf(f->!f.getName().toLowerCase(Locale.ROOT).contains(q));items.sort(order());return items;
    }
    private void collectDocuments(File directory,List<File> into){for(File item:list(directory)){if(item.isDirectory())collectDocuments(item,into);else into.add(item);}}
    private long modified(File file){return Math.max(file.lastModified(),AnnotationStore.modified(context,Uri.fromFile(file)));}
    private File trashFolder(){File folder=new File(root,".trash");folder.mkdirs();return folder;}
    private boolean inTrash(File file){try{return file.getCanonicalPath().startsWith(new File(root,".trash").getCanonicalPath()+File.separator);}catch(IOException error){return true;}}
    static final class TrashItem {final File file;final String name,parent;final long deleted;TrashItem(File file,String name,String parent,long deleted){this.file=file;this.name=name;this.parent=parent;this.deleted=deleted;}}
    synchronized File trash(File source)throws IOException,JSONException{
        if(inTrash(source))throw new IOException("이미 휴지통에 있습니다");String name=source.getName(),parent=source.getParent();File saved=transfer(source,trashFolder(),UUID.randomUUID()+".pdf",true);
        String info=new JSONObject().put("name",name).put("parent",parent).put("deleted",System.currentTimeMillis()).toString();preferences.edit().putString("trash:"+saved.getAbsolutePath(),info).commit();return saved;
    }
    List<TrashItem> trashItems(){List<TrashItem> result=new ArrayList<>();for(File file:list(trashFolder()))if(file.isFile()){try{JSONObject info=new JSONObject(preferences.getString("trash:"+file.getAbsolutePath(),"{}"));result.add(new TrashItem(file,info.optString("name",file.getName()),info.optString("parent",root.getAbsolutePath()),info.optLong("deleted",file.lastModified())));}catch(JSONException ignored){result.add(new TrashItem(file,file.getName(),root.getAbsolutePath(),file.lastModified()));}}result.sort(Comparator.comparingLong((TrashItem t)->t.deleted).reversed());return result;}
    /** Permanently deletes everything in the trash; returns how many documents were removed. */
    synchronized int emptyTrash(){int count=0;android.content.SharedPreferences.Editor edit=preferences.edit();for(File file:list(trashFolder()))if(file.isFile()){String key="trash:"+file.getAbsolutePath();if(file.delete()){count++;edit.remove(key);}}edit.commit();return count;}
    /** Permanently deletes one document from the trash. */
    synchronized boolean deleteForever(TrashItem item){if(!inTrash(item.file))return false;boolean ok=item.file.delete();if(ok)preferences.edit().remove("trash:"+item.file.getAbsolutePath()).commit();return ok;}
    synchronized File restore(TrashItem item)throws IOException,JSONException{
        if(!inTrash(item.file))throw new IOException("휴지통 문서가 아닙니다");File destination=new File(item.parent);if(!destination.isDirectory()||!(destination.equals(root)||managed(destination)))destination=root;File target=NotebookFiles.unique(destination,NotebookFiles.pdfName(item.name));File restored=transfer(item.file,destination,target.getName(),true);preferences.edit().remove("trash:"+item.file.getAbsolutePath()).commit();return restored;
    }
    synchronized File createFolder(File parent,String title)throws IOException{folder(parent);File result=new File(parent,NotebookFiles.name(title));if(result.exists()||!result.mkdir())throw new IOException("같은 이름의 폴더가 있습니다");return result;}
    synchronized File createNote(File parent,String title,NotebookFiles.Paper paper)throws IOException{return createNote(parent,title,paper,false);}
    synchronized File createNote(File parent,String title,NotebookFiles.Paper paper,boolean landscape)throws IOException{folder(parent);File file=NotebookFiles.create(parent,title,1,paper,landscape);putPaper(file,paper);return file;}
    NotebookFiles.Paper paper(File file){String value=preferences.getString("paper:"+file.getAbsolutePath(),null);if(value==null)return null;try{return NotebookFiles.Paper.parse(value);}catch(RuntimeException error){return null;}}
    void restorePaper(File file,NotebookFiles.Paper paper){putPaper(file,paper);}
    private void putPaper(File file,NotebookFiles.Paper paper){preferences.edit().putString("paper:"+file.getAbsolutePath(),paper.spec()).commit();}
    File imported(Uri uri){String path=preferences.getString("source:"+uri.toString(),null);File file=path==null?null:new File(path);return file!=null&&managed(file)&&!inTrash(file)&&file.isFile()?file:null;}
    synchronized File importPdf(Uri source,String title,File destination)throws IOException,JSONException{
        folder(destination);File existing=imported(source);if(existing!=null)return existing;
        String safe=title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");if(safe.trim().isEmpty())safe="문서.pdf";if(safe.length()>90)safe=safe.substring(0,86)+".pdf";
        File target=NotebookFiles.unique(destination,NotebookFiles.pdfName(safe));File temp=File.createTempFile(".import-",".tmp",destination);int count;NotebookFiles.Paper paper=null;
        try{
            try(InputStream in="file".equals(source.getScheme())?new FileInputStream(source.getPath()):context.getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(temp)){
                if(in==null)throw new IOException("PDF를 읽을 수 없습니다");copy(in,out);
            }
            try(PDDocument pdf=PDDocument.load(temp,MemoryUsageSetting.setupTempFileOnly().setTempDir(context.getCacheDir()))){
                count=pdf.getNumberOfPages();if(count==0)throw new IOException("빈 PDF입니다");
                if("true".equals(pdf.getDocumentInformation().getCustomMetadataValue("PDFNoteNotebook"))){try{paper=NotebookFiles.Paper.parse(pdf.getDocumentInformation().getCustomMetadataValue("PDFNotePaper"));}catch(RuntimeException ignored){}}
            }
            cloneAnnotations(source,Uri.fromFile(target),count);NotebookFiles.replace(temp,target);if(paper!=null)putPaper(target,paper);
            preferences.edit().putString("source:"+source,target.getAbsolutePath()).commit();return target;
        }finally{temp.delete();}
    }
    synchronized File transfer(File source,File destination,String title,boolean move)throws IOException,JSONException{
        folder(destination);if(!managed(source)||!source.isFile())throw new IOException("저장된 PDF를 선택하세요");
        String name=NotebookFiles.pdfName(title);File exact=new File(destination,name);
        if(move&&source.equals(exact))return source;
        File target=move?exact:NotebookFiles.unique(destination,name);if(move&&target.exists())throw new IOException("같은 이름의 PDF가 있습니다");
        File temp=File.createTempFile(".copy-",".tmp",destination);
        try{
            try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(temp)){copy(in,out);}
            cloneAnnotations(Uri.fromFile(source),Uri.fromFile(target),Integer.MAX_VALUE);NotebookFiles.replace(temp,target);
            NotebookFiles.Paper paper=paper(source);if(paper!=null)putPaper(target,paper);
            if(move){if(!source.delete()){target.delete();throw new IOException("원본을 이동할 수 없습니다");}
                SharedPreferences.Editor edit=preferences.edit().remove("fav:"+source.getAbsolutePath()).remove("paper:"+source.getAbsolutePath()).remove("opened:"+source.getAbsolutePath()).putLong("opened:"+target.getAbsolutePath(),preferences.getLong("opened:"+source.getAbsolutePath(),0));if(preferences.getBoolean("fav:"+source.getAbsolutePath(),false))edit.putBoolean("fav:"+target.getAbsolutePath(),true);
                for(Map.Entry<String,?> entry:preferences.getAll().entrySet())if(entry.getKey().startsWith("source:")&&source.getAbsolutePath().equals(entry.getValue()))edit.putString(entry.getKey(),target.getAbsolutePath());edit.commit();
            }
            return target;
        }finally{temp.delete();}
    }
    private void cloneAnnotations(Uri source,Uri target,int count)throws JSONException{
        AnnotationStore original=new AnnotationStore(context);original.open(source);AnnotationStore clone=new AnnotationStore(context);clone.open(target);clone.importJson(original.exportJson(source,"PDF"),count);
    }
    synchronized int insertPage(File file,NotebookFiles.Paper requested,int afterIndex)throws IOException{return insertPage(file,requested,afterIndex,0);}
    synchronized int insertPage(File file,NotebookFiles.Paper requested,int afterIndex,int sizeMode)throws IOException{
        if(!managed(file)||!file.isFile())throw new IOException("저장된 PDF가 아닙니다");return NotebookFiles.insert(context,file,requested,afterIndex,sizeMode);
    }
    synchronized int deletePage(File file,int index)throws IOException{
        if(!managed(file)||!file.isFile())throw new IOException("저장된 PDF가 아닙니다");return NotebookFiles.delete(context,file,index);
    }
    synchronized int append(File file,NotebookFiles.Paper requested)throws IOException{
        if(!managed(file)||!file.isFile())throw new IOException("저장된 PDF가 아닙니다");return NotebookFiles.append(context,file,requested);
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
}
