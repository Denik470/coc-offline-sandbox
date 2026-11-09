package com.supercell.id;
import android.app.Activity;
import java.nio.ByteBuffer;
import io.flutter.plugin.common.BinaryMessenger;
/** Offline account-service adapter. A null platform reply means not implemented. */
public class FlutterSupport {
 static {System.loadLibrary("scid_sdk");}
 private final Activity activity;
 public FlutterSupport(long owner,Activity activity){this.activity=activity;}
 public static native void binaryChannelReply(ByteBuffer value,long callback);
 public static native void binaryMessageHandler(long owner,ByteBuffer message,BinaryMessenger.BinaryReply reply);
 public void setMessageHandler(String channel,long handler){}
 public void sendMessage(String channel,ByteBuffer value,long callback){if(callback!=0)activity.runOnUiThread(()->binaryChannelReply(null,callback));}
 public void setWindowVisibility(boolean visible,float[] margins){}
 public void resetUserData(){}
 public void onUserLeaveHint(){}
 public void onRequestPermissionsResult(int request,String[] permissions,int[] results){}
 public void onActivityResult(int request,int result,android.content.Intent data){}
}
