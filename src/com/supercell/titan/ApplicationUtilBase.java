package com.supercell.titan;
import android.content.*;import android.app.*;import android.os.*;import java.util.*;
public class ApplicationUtilBase {
 private static GameApp app(){return GameApp.getInstance();}
 private static SharedPreferences prefs(){return app().getSharedPreferences("offline-local",0);}
 public static boolean isAmazonDeviceMessagingSupported(){return false;}
 public static boolean canOpenURL(String url){return false;}
 public static void openURL(String url){android.util.Log.i("OfflineBridge","External URL unavailable in local container");}
 public static void openMarketURLImplementation(String url){}
 public static void openMarketURL(){}
 public static String getPreferredLanguage(){return Locale.getDefault().getLanguage();}
 public static String getLocaleCountry(){return Locale.getDefault().getCountry();}
 public static String getBundleID(){return app().getPackageName();}
 public static String getTimeZoneID(){return TimeZone.getDefault().getID();}
 public static int getTotalMemory(){ActivityManager.MemoryInfo m=new ActivityManager.MemoryInfo();((ActivityManager)app().getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(m);return (int)(m.totalMem/1048576);}
 public static int getResidentMemoryInMB(){return (int)(android.os.Debug.getPss()/1024);}
 public static String getKeyValue(String key){return prefs().getString(key,"");}
 public static void removeKeyValue(String key){prefs().edit().remove(key).apply();}
 public static void storeKeyValue(String key,String value){prefs().edit().putString(key,value).apply();}
 public static String getOpenUDID(){return getAndroidID();}
 public static String getAndroidID(){String id=prefs().getString("local-id","");if(id.isEmpty()){id=UUID.randomUUID().toString();prefs().edit().putString("local-id",id).apply();}return id;}
 public static void setKeepScreenOn(boolean on){app().runOnUiThread(()->{if(on)app().getWindow().addFlags(128);else app().getWindow().clearFlags(128);});}
 public static void setRequestedOrientation(){}
 public static String getAppVersion(){return "18.647.5";}
 public static String getPlatformDetail(int i){switch(i){case 256:return Build.BOARD;case 257:return Build.BOOTLOADER;case 258:return Build.BRAND;case 259:return Build.CPU_ABI;case 260:return Build.CPU_ABI2;case 261:return Build.DEVICE;case 262:return Build.DISPLAY;case 263:return Build.FINGERPRINT;case 264:return Build.HARDWARE;case 265:return Build.MANUFACTURER;case 266:return Build.MODEL;case 267:return Build.PRODUCT;case 268:return Build.TAGS;case 269:return Build.TYPE;case 270:return Build.VERSION.RELEASE;case 271:return String.valueOf(app().getResources().getDisplayMetrics().xdpi);case 272:return String.valueOf(app().getResources().getDisplayMetrics().ydpi);case 273:return String.valueOf(app().getResources().getDisplayMetrics().density);case 274:return String.valueOf((long)getTotalMemory()*1048576);default:return "";}}
 public static String getIMEI(){return "";}
 public static boolean isLowLatencyDevice(){return app().getPackageManager().hasSystemFeature("android.hardware.audio.low_latency");}
 public static String getServerEnvironment(){return "offline";}
}
