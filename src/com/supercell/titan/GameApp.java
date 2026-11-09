package com.supercell.titan;
import android.app.*;import android.content.*;import android.content.res.*;import android.view.Surface;
public class GameApp extends android.app.Activity {

 public static GameApp instance;
 // Native (Android) dialogs only carry system/network messages (connection error, maintenance, update); in-game popups are GL. With a local home there is no server,
 // so from submission on they are closed without dialogDismissed: answering them restarts the connection flow, and a visible native dialog stalls the home load.
 private static int localHomeState;
 public static int localHomeState(){return localHomeState;}
 // State 3: local battle finished and its avatar was saved; relaunch through RestartActivity (separate process) to load the home from the saves.
 private static boolean restarting;
 public void beforeLogicCallback(){lab.coc.offline.RenderActivity.flushSafeMargins();int state=lab.coc.offline.RenderActivity.observeNativeRuntime();if(state==3){if(!restarting){restarting=true;runOnUiThread(()->{startActivity(new Intent(this,lab.coc.offline.RestartActivity.class));android.os.Process.killProcess(android.os.Process.myPid());});}return;}
  if(state!=localHomeState){localHomeState=state;NativeDialogManager.setDiscardWithoutCallback(state>=1);if(state>=1)NativeDialogManager.discardWithoutCallback();}if(localHomeState==2)lab.coc.offline.OfflineTools.tick(this);NativeHTTPClientManager.getInstance().updateBeforeFrame();}

 public static boolean isPlayingUserMusic(){android.media.AudioManager a=instance==null?null:(android.media.AudioManager)instance.getSystemService(Context.AUDIO_SERVICE);return a!=null&&a.isMusicActive();}
 public static void hapticFeedback(int type){if(instance!=null)instance.runOnUiThread(()->instance.getWindow().getDecorView().performHapticFeedback(type,2));}

 public static android.view.Display.Mode[] getDisplayModes(){return instance.getWindowManager().getDefaultDisplay().getSupportedModes();}
 public static android.view.Display.Mode getCurrentDisplayMode(){return instance.getWindowManager().getDefaultDisplay().getMode();}
 public static void setDisplayMode(int id){instance.runOnUiThread(()->{android.view.Window w=instance.getWindow();android.view.WindowManager.LayoutParams a=w.getAttributes();a.preferredDisplayModeId=id;w.setAttributes(a);});}
 public void runInUiThreadFromCpp(long callback){runOnUiThread(()->nRunFromUiThread(callback));}

 public static boolean isEmulator(){String f=android.os.Build.FINGERPRINT;return f.startsWith("generic")||f.contains("emulator");}
 public static boolean isFeatureEnabled(String name){return false;}
 public static String getAPKPath(){return instance.getApplicationInfo().sourceDir;}
 public static long getAvailableFSBytes(String path){return new java.io.File(path).getUsableSpace();}
 public static String getBestCacheDir(Context context){return context.getCacheDir().getAbsolutePath();}
 public static boolean isNativeLibraryLoaded(){return true;}

 public static GameApp getInstance(){return instance;}
 public static class NotificationData {public String actionId,channelId,chronosEventId,message,notificationId,title;public int deliveryErrorCode,source;}
 public String getBuildConfigClassName(){return "lab.coc.offline.BuildConfig";}
 public String getStringResourceByName(String name){return "";}
 public String getGameDataKey(){return "offline-local";}
 public static String getGameDataKey(Context context){return "offline-local";}

 // Offline: no local/push notifications. Signatures match the original GameApp; native code calls these via JNI (cancelAllNotifications from loadHomeState).
 public static void cancelAllNotifications(){}
 public static void cancelNotification(int id){}
 public static int createNotification(String a,String b,int c,long d,int e,String f,String g,String h,String i,String j,String k,String l){return 0;}
 public static boolean areNotificationsEnabled(){return false;}
 public static int getNumberOfActiveNotifications(){return 0;}
 public static void openNotificationSettings(){}
 public static void addDisplayedNotificationForCancelTracking(NotificationData data){}
 public static void removeDisplayedNotificationFromCancelTracking(String id){}
 public static String ensureLocalNotificationId(String id){return id==null?java.util.UUID.randomUUID().toString():id;}
 public static boolean intentContainsNotificationLaunchExtras(Intent intent){return false;}
 public static void queuePushNotificationValueUpdate(int a,String b,String c){}
 public static void trackNotificationDelivered(NotificationData data){}
 public static void trackNotificationReceived(NotificationData data){}
 public static void trackNotificationReceivedOnly(NotificationData data){}
 public static void debuggerException(Exception e){android.util.Log.w("GameApp","debuggerException",e);}
 public static void debuggerWarning(String s){android.util.Log.w("GameApp","debuggerWarning "+s);}
 public static void testDeviceIntegrity(byte[] nonce){}
 public static int[] getHuaweiNotchSize(Context context){return new int[]{0,0};}
 public static boolean hasHuaweiNotchInScreen(Context context){return false;}
 public static void vibrateDevice(){hapticFeedback(0);}
 public static void killGame(){android.os.Process.killProcess(android.os.Process.myPid());}
 public static void reloadApp(){if(instance!=null)instance.runOnUiThread(instance::recreate);}

public static native boolean backButtonPressed();
public static native String createGameMain(AssetManager assetManager, String str, String str2, String str3, long j, int i, int i2, int i3, float f, float f2, int i4, String str4, Activity activity);
public static native void deinit();
public static native void dialogDismissed(int i, int i2);
public static native int getAllowedScreenRotations();
public static native void handleDeeplinkURL(String str);
public static native void handleObservation(int i);
public static native boolean isScreenResizeSupported();
public static native void logDebuggerException(String str);
public static native void nOnActivityResult(int i, int i2, Intent intent);
public static native void nOnAppStart();
public static native void nOnAppStop();
public static native void nOnApplicationCreate();
public static native void nOnConfigurationChanged(Configuration configuration);
public static native void nOnCreate();
public static native void nOnDestroy();
public static native void nOnDisplayAdded(int i);
public static native void nOnDisplayChanged(int i);
public static native void nOnDisplayRemoved(int i);
public static native void nOnKeyDownEvent(int i, int i2);
public static native void nOnKeyRemap(long j, int[] iArr, int[] iArr2, String str);
public static native void nOnKeyUpEvent(int i, int i2);
public static native void nOnMouseEvent(float f, float f2);
public static native void nOnMouseHoverEvent(float f, float f2);
public static native void nOnPause();
public static native void nOnRestart();
public static native void nOnResume();
public static native void nOnStart();
public static native void nOnStop();
public static native void nOnSurfaceChanged(Surface surface, int i, int i2);
public static native void nOnSurfaceCreated(Surface surface);
public static native void nOnSurfaceDestroyed(Surface surface);
public static native void nOnTouchEvent(int i, int i2, int i3, int i4);
public static native void nOnTrimMemory(int i);
public static native void nOnWindowFocusChanged(boolean z);
public static native void nRunFromUiThread(long j);
private static native void notificationCanceled(NotificationData notificationData);
private static native void notificationDelivered(NotificationData notificationData);
private static native void notificationReceived(NotificationData notificationData);
private static native void notificationReceivedOnly(NotificationData notificationData);
private static native void notificationScheduled(NotificationData notificationData);
public static native void setDeviceVerificationResult(boolean z, boolean z2, String str);
public static native void setPushNotificationValues(int i, String str, String str2);
public static native void setSafeMargins(int i, int i2, int i3, int i4);
}
