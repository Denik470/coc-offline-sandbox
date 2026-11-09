package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class VirtualKeyboardHandler {

public static native String getFontPath(String str);
public static native void inputEscPressed();
public static native void inputKeyboardDismissed();
public static native void inputOkPressed();
public static native void inputSelectionChanged(int i, int i2);
public static native void inputTextChanged(String str);
public static native void keyboardSizeChanged(float f, float f2);
}
