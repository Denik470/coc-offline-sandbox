package com.supercell.titan.zendesk;
import android.content.Context;
/** Offline support adapter: reports network service unavailable to native caller. */
public final class ZendeskKt {
 public static void initializeMessagingSdk(Context context,String key,IMessagingSdkInitCallback callback){if(callback!=null)callback.onFailure();}
 public static int getUnreadMessageCount(){return 0;}
 public static void showMessaging(Context context){}
 public static void showMessaging(Context context,String conversation){}
 public static void setPushNotificationToken(String token){}
 public static void setMetadata(String[] keys,String[] values){}
 public static void clearMetadata(){}
 public static void setTags(String[] tags){}
 public static void clearTags(){}
}
