package com.supercell.titan;
// Offline: no Quago telemetry SDK. Same public static API as original; calls are no-ops (native calls setKeyValue/beginSegment around battles).
public class QuagoManager {
 public static void setAppToken(String token){}
 public static void enable(boolean on,int mode,boolean flag){}
 public static void onResume(){}
 public static void onPause(){}
 public static void beginSegment(String name){}
 public static void endSegment(){}
 public static void setKeyValue(String key,String value){}
 public static void setAdditionalId(String id){}
 public static byte[] compress(String data){return data==null?new byte[0]:data.getBytes(java.nio.charset.StandardCharsets.UTF_8);}

 public static native void onLog(int i,String str,String str2);
 public static native void sendJsonSegments(String str,byte[] bArr);
}
