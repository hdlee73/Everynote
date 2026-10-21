package com.hdlee.pdfnote;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.*;

/** Local-only HWP/HWPX to PDF bridge. Only bundled assets and the chosen file are exposed. */
final class HwpConversion {
    interface Callback { void status(String text); void success(File pdf); void failure(String reason); }
    private final Activity activity;
    private final ViewGroup parent;
    private final Callback callback;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private volatile boolean ended;
    private WebView web;
    private File input,output;
    private OutputStream stream;
    private long expected,written;
    private final Runnable timeout=()->fail("변환 시간이 초과되었습니다");
    // Text fonts first (the first CJK sans/serif become the document defaults), then symbol/math fallbacks that only fill glyphs the text fonts lack (예: ∼ ⅓ ∑).
    static final String[] FONTS={"/system/fonts/NotoSansCJK-Regular.ttc","/system/fonts/Roboto-Regular.ttf","/system/fonts/NotoSerifCJK-Regular.ttc",
        "/system/fonts/NotoSansSymbols-Regular-Subsetted.ttf","/system/fonts/NotoSansSymbols-Regular-Subsetted2.ttf","/system/fonts/NotoSansSymbols2-Regular.ttf","/system/fonts/NotoSansMath-Regular.ttf","/system/fonts/DroidSansFallbackFull.ttf"};

    HwpConversion(Activity activity,ViewGroup parent,Callback callback){this.activity=activity;this.parent=parent;this.callback=callback;}
    void start(Uri source){
        ui.postDelayed(timeout,180000);
        new Thread(()->{
            try{
                synchronized(this){if(ended)return;input=File.createTempFile("hwp-input-",".bin",activity.getCacheDir());output=File.createTempFile("hwp-result-",".pdf",activity.getCacheDir());}
                try(InputStream in=activity.getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(input)){
                    if(in==null)throw new IOException("문서를 읽을 수 없습니다");byte[] buf=new byte[65536];int n;long size=0;
                    while((n=in.read(buf))!=-1){if(ended)return;size+=n;if(size>48L*1024*1024)throw new IOException("48MB 이하의 한글 문서를 선택하세요");out.write(buf,0,n);}
                }
                ui.post(this::launch);
            }catch(Exception e){fail(e.getMessage());}
        },"hwp-input").start();
    }
    private void launch(){
        if(ended||activity.isFinishing()||activity.isDestroyed()){cancel();return;}
        try{
            web=new WebView(activity);web.setVisibility(View.INVISIBLE);parent.addView(web,new ViewGroup.LayoutParams(1,1));
            web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
            web.getSettings().setBlockNetworkLoads(true);web.addJavascriptInterface(new Bridge(),"NativePdf");
            web.setWebViewClient(new WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    Uri u=request.getUrl();String path=u.getPath();
                    if(!"https".equals(u.getScheme())||!"pdfnote.local".equals(u.getHost())||path==null)return missing();
                    try{
                        if(path.equals("/hwp/document"))return response("application/octet-stream",new FileInputStream(input));
                        for(int i=0;i<FONTS.length;i++)if(path.equals("/hwp/font"+i))return response("font/ttf",new FileInputStream(FONTS[i]));
                        String name=path.substring(path.lastIndexOf('/')+1);
                        if(!name.equals("convert.html")&&!name.equals("rhwptopdf.umd.js")&&!name.equals("rhwptopdf.umd_bg.wasm"))return missing();
                        return response(name.endsWith(".wasm")?"application/wasm":name.endsWith(".js")?"application/javascript":"text/html",activity.getAssets().open("hwp/"+name));
                    }catch(Exception e){return missing();}
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
                @Override public void onPageFinished(WebView view,String url){if(!ended&&url.equals("https://pdfnote.local/hwp/convert.html"))view.evaluateJavascript("convertDocument()",null);}
                @Override public boolean onRenderProcessGone(WebView view,android.webkit.RenderProcessGoneDetail detail){fail("변환 엔진이 종료되었습니다. 더 작은 문서로 시도하세요");return true;}
            });
            web.loadUrl("https://pdfnote.local/hwp/convert.html");
        }catch(Exception e){fail(e.getMessage());}
    }
    private static WebResourceResponse response(String mime,InputStream in){return new WebResourceResponse(mime,"UTF-8",in);}
    private static WebResourceResponse missing(){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",java.util.Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void dispose(){ui.removeCallbacks(timeout);if(web!=null){parent.removeView(web);web.removeJavascriptInterface("NativePdf");web.stopLoading();web.destroy();web=null;}if(input!=null)input.delete();}
    synchronized void cancel(){if(ended)return;ended=true;closeStream();if(output!=null)output.delete();ui.post(this::dispose);}
    private void closeStream(){if(stream!=null)try{stream.close();}catch(IOException ignored){}stream=null;}
    private synchronized void fail(String reason){if(ended)return;ended=true;closeStream();if(output!=null)output.delete();ui.post(()->{dispose();callback.failure(reason==null?"알 수 없는 오류":reason);});}
    final class Bridge {
        @JavascriptInterface public void status(String text){ui.post(()->{if(!ended)callback.status(text);});}
        @JavascriptInterface public boolean begin(int size){synchronized(HwpConversion.this){if(ended)return false;try{if(size<5||size>128*1024*1024)throw new IOException("변환 결과가 너무 크거나 비어 있습니다");expected=size;written=0;stream=new FileOutputStream(output);return true;}catch(Exception e){fail(e.getMessage());return false;}}}
        @JavascriptInterface public boolean chunk(String text){synchronized(HwpConversion.this){if(ended)return false;try{if(stream==null||text.length()>45000)throw new IOException("잘못된 PDF 데이터");byte[] bytes=Base64.decode(text,Base64.NO_WRAP);written+=bytes.length;if(written>expected)throw new IOException("PDF 크기가 일치하지 않습니다");stream.write(bytes);return true;}catch(Exception e){fail(e.getMessage());return false;}}}
        @JavascriptInterface public void complete(){synchronized(HwpConversion.this){if(ended)return;try{closeStream();if(written!=expected)throw new IOException("PDF 저장이 완료되지 않았습니다");try(InputStream in=new FileInputStream(output)){byte[] head=new byte[5];if(in.read(head)!=5||!new String(head,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IOException("변환 결과가 PDF가 아닙니다");}ended=true;ui.post(()->{dispose();callback.success(output);});}catch(Exception e){fail(e.getMessage());}}}
        @JavascriptInterface public void error(String reason){fail(reason);}
    }
}
