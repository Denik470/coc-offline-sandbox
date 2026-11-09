package com.supercell.titan.usercentrics;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class UsercentricsSDK {

public final native void gotConsent(String str, boolean z, boolean z2);
public final native void gotFailure();
public final native void popupClosed();
public final native void popupShowing();
}
