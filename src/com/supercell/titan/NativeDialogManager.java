package com.supercell.titan;
import android.app.AlertDialog;
import android.content.DialogInterface;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
/** Android dialog bridge with the original native button identifiers. */
public final class NativeDialogManager {
 private static final AtomicInteger ids=new AtomicInteger();
 private static final ArrayDeque<Runnable> queue=new ArrayDeque<>();
 private static AlertDialog current;
 private static boolean visible;
 public static int ShowDialog(String title,String message,String neutral,String positive,String negative){
  int id=ids.incrementAndGet();GameApp app=GameApp.getInstance();
  app.runOnUiThread(()->{queue.add(()->show(app,id,title,message,neutral,positive,negative));next();});return id;
 }
 private static void next(){if(!visible&&!queue.isEmpty())queue.remove().run();}
 private static void show(GameApp app,int id,String title,String message,String neutral,String positive,String negative){
  // Sandbox: the original client reports the missing server ("Ошибка подключения") while starting; the local home replaces the
  // connection, so that dialog is never shown (same silent discard as during the local home load).
  boolean connectionError=title!=null&&title.contains("подключ");
  if(connectionError){try{lab.coc.offline.RenderActivity.onConnectionError();}catch(Throwable ignored){}}
  if(discardNew||(GameApp.localHomeState()<2&&connectionError)){try(java.io.FileOutputStream out=app.openFileOutput("native-dialog.txt",android.content.Context.MODE_APPEND)){out.write(("Discarded during local home load "+id+": "+title+"\n").getBytes("UTF-8"));}catch(Exception ignored){}next();return;}
  try(java.io.FileOutputStream out=app.openFileOutput("native-dialog.txt",android.content.Context.MODE_APPEND)){out.write(("Dialog "+id+"\n"+title+"\n"+message+"\nButtons: "+neutral+" / "+positive+" / "+negative+"\n").getBytes("UTF-8"));}catch(Exception ignored){}
  final boolean[] answered={false};DialogInterface.OnClickListener listener=(dialog,which)->{if(!answered[0]){answered[0]=true;GameApp.dialogDismissed(id,which==DialogInterface.BUTTON_POSITIVE?1:which==DialogInterface.BUTTON_NEGATIVE?2:0);}};
  AlertDialog.Builder b=new AlertDialog.Builder(app,4).setTitle(title).setMessage(message).setCancelable(false);
  if(neutral!=null&&!neutral.isEmpty())b.setNeutralButton(neutral,listener);
  if(positive!=null&&!positive.isEmpty())b.setPositiveButton(positive,listener);
  if(negative!=null&&!negative.isEmpty())b.setNegativeButton(negative,listener);
  visible=true;current=b.create();current.setOnDismissListener(dialog->{current=null;visible=false;if(!answered[0]){answered[0]=true;if(!silent)GameApp.dialogDismissed(id,0);}silent=false;next();});current.show();
 }
 public static boolean isDialogVisible(){return visible;}
 private static boolean silent,discardNew;
 public static void setDiscardWithoutCallback(boolean on){discardNew=on;}
 public static void discardWithoutCallback(){GameApp.getInstance().runOnUiThread(()->{queue.clear();if(current!=null){silent=true;current.dismiss();}});}
 public static void nativeDialogDismissAll(){GameApp.getInstance().runOnUiThread(()->{queue.clear();if(current!=null)current.dismiss();});}
}
