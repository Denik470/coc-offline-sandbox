package com.supercell.titan;
import android.content.Context;import android.telephony.TelephonyManager;
// Offline: no network listener registered; activeNetworkTransportType keeps the original default 0.
public class NetworkInfo {
 private static final NetworkInfo instance=new NetworkInfo();
 public static int getNetworkType(Context context){return 0;}
 public static String getSimCountryIso(Context context){TelephonyManager t=getTelephonyManager(context);String s=t==null?null:t.getSimCountryIso();return s==null?"":s;}
 public static TelephonyManager getTelephonyManager(Context context){return context==null?null:(TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);}
 public void init(Context context){}
 public static NetworkInfo getInstance(){return instance;}
 public volatile int activeNetworkTransportType=0;
 public static int getNetworkTransportType(){return getInstance().activeNetworkTransportType;}
}
