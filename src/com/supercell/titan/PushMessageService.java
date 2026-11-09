package com.supercell.titan;
// Offline: no Firebase push. Same public static API as original PushMessageService (native calls isPermissionGranted etc.).
public class PushMessageService {
 public static void onDestroy(GameApp gameApp){}
 public static void register(){}
 public static void requestToken(){}
 public static void getToken(boolean z){}
 public static boolean shouldShowPermissionRationale(){return false;}
 public static boolean hasPermissionBeenRequested(){return true;}
 public static boolean isPermissionGranted(){return false;}
}
