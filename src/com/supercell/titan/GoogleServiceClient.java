package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class GoogleServiceClient {

public static native void onSignIn();
public static native void onSignInCanceled();
public static native void onSignInFailed();
public static native void onSignOut();
public static native void updateNativeInstance(GoogleServiceClient googleServiceClient);
}
