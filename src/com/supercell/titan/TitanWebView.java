package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class TitanWebView {
 public long objPtr;
 private android.webkit.WebView web;
 private boolean visible;
 public long getObjPtr(){return objPtr;}
 public void setObjPtr(long pointer){objPtr=pointer;}
 private void ui(Runnable action){GameApp.getInstance().runOnUiThread(action);}
 private android.webkit.WebView view(){if(web==null){web=new android.webkit.WebView(GameApp.getInstance());web.getSettings().setBlockNetworkLoads(true);web.setWebViewClient(new android.webkit.WebViewClient(){@Override public void onPageFinished(android.webkit.WebView v,String url){if(objPtr!=0)TitanWebView.this.onPageFinished(url);}});}return web;}
 public void init(boolean portrait,boolean navigation,boolean suppressFailure){}
 public void setShowNavigationBar(boolean value){}
 public void setBackgroundColor(int color){ui(()->view().setBackgroundColor(color));}
 public void setNavigationBarTrailingButton(String name){}
 public void setTrackVisibleDisplayFrameEnabled(boolean value){}
 public void setTimeoutMs(int milliseconds){}
 public void registerBridgeFunction(String name){}
 public void unregisterBridgeFunction(String name){}
 public void registerBridgeData(String name,String value){}
 public void unregisterBridgeData(String name){}
 public void loadURL(String url){ui(()->{if(url!=null&&(url.startsWith("file:///android_asset/")||url.equals("about:blank")))view().loadUrl(url);else if(objPtr!=0)onReceivedError(url,"Network unavailable in offline container");});}
 public void loadURL(String url,String[] headers){loadURL(url);}
 public void loadHTML(String html){ui(()->view().loadDataWithBaseURL("about:blank",html,"text/html","UTF-8",null));}
 public void eval(String script){ui(()->view().evaluateJavascript(script,null));}
 public void show(int x,int y,int width,int height,float scale){ui(()->{android.webkit.WebView v=view();if(v.getParent()==null)GameApp.getInstance().addContentView(v,new android.view.ViewGroup.LayoutParams(width,height));v.setX(x);v.setY(y);v.setVisibility(android.view.View.VISIBLE);visible=true;});}
 public void hide(){ui(()->{if(web!=null)web.setVisibility(android.view.View.GONE);visible=false;});}
 public void close(){objPtr=0;ui(()->{if(web!=null){if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy();web=null;}visible=false;});}
 public boolean isVisible(){return visible;}
 public boolean canGoBack(){return false;}
 public void goBack(){ui(()->{if(web!=null&&web.canGoBack())web.goBack();});}
 public void reload(){ui(()->{if(web!=null)web.reload();});}
 public void setAlpha(float value){ui(()->view().setAlpha(value));}
 public void setTextZoom(int percent){ui(()->view().getSettings().setTextZoom(percent));}
 public void disableTextZoom(){setTextZoom(100);}
 public void setIgnoreTouches(boolean ignore){ui(()->view().setOnTouchListener(ignore?(v,e)->true:null));}
 public static void restoreOrientation(){GameApp a=GameApp.getInstance();a.runOnUiThread(()->a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));}
 public static void setStageViewVisibility(int value){}
 public static boolean isInCustomView(){return false;}
 public static void hideCustomView(){}


public native void hideNative();
public native void lambda$deleteCookies$11();
public native void onNavigationBarTrailingAction();
public native void onPageFinished(String str);
public native void onPageStarted(String str);
public native void onReceivedError(String str, String str2);
public native void onSwipeRight();
public native void onWebViewClosed();
public native String postMessage(String str, String str2);
public native boolean shouldOverrideUrlLoading(String str);
}
