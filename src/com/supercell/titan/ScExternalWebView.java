package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class ScExternalWebView {

public native void onClosed();
public native void onPageFailed(String str);
public native void onPageFinished(String str);
}
