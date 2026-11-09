package com.supercell.titan;
import android.content.*;import java.util.*;
// Offline: no Helpshift support SDK. Same public static API as original (native code calls start() during home load); calls are no-ops.
public class HelpshiftTitan {
 public static final int ORIENTATION_LANDSCAPE=0,ORIENTATION_PORTRAIT=0,ORIENTATION_UNSPECIFIED=0;
 public static volatile int cachedNotificationCount;
 public static final HashMap config=new HashMap(),customMetadata=new HashMap(),dataTypeMapping=new HashMap();
 public static final Map customIssueFields=new HashMap();
 public static String[] customTags;
 public static boolean enableFullPrivacy;

 public static void handlePushNotification(String s){}
 public static void setMetadataCallback(){}
 public static void start(String a,String b,String c,int orientation){}
 public static void login(String a,String b,String c,String d){}
 public static void setPushNotificationToken(String s){}
 public static void setEnableFullPrivacy(boolean z){enableFullPrivacy=z;}
 public static void showConversation(){}
 public static Map createConfig(){return new HashMap(config);}
 public static void showFAQ(){}
 public static void showSingleFAQ(String s){}
 public static void showFAQSection(String s){}
 public static void setCustomIssueField(String a,String b,String c){}
 public static void setInternalCustomIssueField(String a,String b,String c){}
 public static void setMetadata(String a,String b){}
 public static void setMetadataTags(String s){}
 public static void setInitialUserMessage(String s){}
 public static void setWithTagsMatching(String s){}
 public static void clearMetadata(){}
 public static void leaveBreadcrumb(String s){}
 public static void setSDKLanguage(String s){}
 public static void requestNotificationCount(){}
 public static int getNotificationCount(){return 0;}
 public static void onResume(){}
 public static void setContactUsMode(int i){}
 public static boolean isVisible(){return false;}
 public static void enableChat(){}
 public static void handlePushInternal(Context context,Intent intent){}
}
