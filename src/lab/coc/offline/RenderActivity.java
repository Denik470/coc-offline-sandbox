package lab.coc.offline;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import android.util.DisplayMetrics;
public class RenderActivity extends com.supercell.titan.GameApp implements SurfaceHolder.Callback {
 static {System.loadLibrary("fmod");System.loadLibrary("offline_probe");}
 private static native String diagnoseSupport(String library,String progress,boolean initialize);
 /** Local home state: 0 not submitted, 1 submitted (loading), 2 accepted (frontend mode 1, no pending home, progress 1). */
 public static native int observeNativeRuntime();
 private static native void configureLocalHome(android.content.res.AssetManager assets,boolean enabled);
 /** Offline battle trigger: queues a local attack, started on the logic thread (from the target chooser; the volume keys keep their normal role). */
 private static native void requestLocalAttack();
 /** Start-up readiness signal: the original client reported the missing server (first connection-error dialog). */
 public static native void onConnectionError();
 public static void startLocalAttack(){requestLocalAttack();}
 // Back goes to the game first (original GameApp.onBackPressed); if the game does not consume it, keep the session alive in the background instead of finishing.
 @Override public void onBackPressed(){if(!backButtonPressed())moveTaskToBack(true);}
 private static native String prepareRenderer(String library,String progress);
 private TextView status;
 private boolean nativeCreated;
 private void record(String value){try(java.io.FileOutputStream out=openFileOutput("renderer-java-status.txt",MODE_APPEND)){out.write((value+"\n").getBytes("UTF-8"));}catch(Exception ignored){}}
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);setShowWhenLocked(true);setTurnScreenOn(true);getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // lab build: testable over the lock screen without unlocking the device
  if(OfflineTools.savePendingLink(this,getIntent()))setIntent(new android.content.Intent(this,RenderActivity.class));deleteFile("renderer-java-status.txt");deleteFile("renderer-progress.txt");deleteFile("native-dialog.txt");instance=this;status=new TextView(this);status.setTextSize(18);status.setPadding(20,60,20,20);setContentView(status);
  try{
   if(!InstallSupport.prepare(this))return;
   smxuhbhxh.ac.d();
   if(getIntent().getBooleanExtra("inspectSupport",false)){status.setText(diagnoseSupport(getApplicationInfo().nativeLibraryDir+"/libsupercell_clashofclans.so",getFilesDir()+"/support-diagnostic.txt",getIntent().getBooleanExtra("initializeSupport",false)));return;}
   record("System.loadLibrary support");System.loadLibrary("supercell_clashofclans");record("Support initialized");System.loadLibrary("g");record("Game library initialized");
   String result=prepareRenderer(getApplicationInfo().nativeLibraryDir+"/libg.so",getFilesDir()+"/renderer-progress.txt");status.setText(result);if(!result.startsWith("JNI initialized"))return;
   if(getIntent().getBooleanExtra("localHome",true))OfflineTools.applyStartupRules(this);configureLocalHome(getAssets(),getIntent().getBooleanExtra("localHome",true));
   record("Native libraries notification");com.supercell.titan.TitanApplication.nOnNativeLibrariesLoaded();record("Application create");nOnApplicationCreate();record("FMOD init");org.fmod.FMOD.init(this);record("createGameMain");
   DisplayMetrics d=getResources().getDisplayMetrics();
   android.content.res.AssetManager renderAssets=getAssets();
   if(getIntent().getBooleanExtra("originalAssets",false)){renderAssets=createPackageContext("com.supercell.clashofclans",0).getAssets();record("Diagnostic original public assets selected");}
   String main=createGameMain(renderAssets,getApplicationInfo().dataDir,getCacheDir().getAbsolutePath(),getCacheDir().getAbsolutePath(),getCacheDir().getUsableSpace(),d.widthPixels,d.heightPixels,d.densityDpi,d.xdpi,d.ydpi,2,getFilesDir().getAbsolutePath(),this);
   record("createGameMain returned: "+main);status.setText(result+"\ncreateGameMain: "+main);
   SurfaceView view=new SurfaceView(this);view.getHolder().addCallback(this);applyOriginalWindowMode(view);view.setOnTouchListener((v,e)->forwardTouch(e));if(getIntent().getBooleanExtra("localHome",true))OfflineTools.install(this,view);else setContentView(view);nOnCreate();nativeCreated=true;record("Native create returned");
  }catch(Throwable ex){record(ex.toString());status.setText(ex.toString());}
 }
 // Game links (link.clashofclans.com: CopyArmy, ...): kept in a file for the sandbox's own army import (returning from the background relaunches)
 // and never handed to the game, whose own link handling restarts the server login and leaves the offline session on the loading screen.
 // A link delivered to the running sandbox pauses and resumes it, and the client then retries the server login; relaunch from the saves
 // instead (as onRestart does), the saved link is imported once the home is ready.
 @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);if(!OfflineTools.savePendingLink(this,intent)){setIntent(intent);return;}
  if(nativeCreated&&getIntent().getBooleanExtra("localHome",true)){record("Game link: relaunch from saves");startActivity(new android.content.Intent(this,RestartActivity.class));android.os.Process.killProcess(android.os.Process.myPid());}}
 @Override public void onStart(){super.onStart();if(nativeCreated){nOnStart();nOnAppStart();}}
 @Override public void onResume(){super.onResume();record("Activity resume, native="+nativeCreated);if(nativeCreated)nOnResume();}
 @Override public void onPause(){record("Activity pause");if(nativeCreated)nOnPause();super.onPause();}
 // Returning from the background: the original client reconnects to the server here and the offline session would stay on the loading screen,
 // so the sandbox relaunches from its saves (autosave runs every 1-5 s while playing).
 @Override protected void onRestart(){super.onRestart();if(getIntent().getBooleanExtra("localHome",true)){record("Activity restart: relaunch from saves");startActivity(new android.content.Intent(this,RestartActivity.class));android.os.Process.killProcess(android.os.Process.myPid());}}
 @Override public void onStop(){record("Activity stop");if(nativeCreated){nOnAppStop();nOnStop();}super.onStop();}
 @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)getWindow().getDecorView().setSystemUiVisibility(5894);record("Window focus "+focused);if(nativeCreated)nOnWindowFocusChanged(focused);}
 public void surfaceCreated(SurfaceHolder holder){record("Surface created");nOnSurfaceCreated(holder.getSurface());record("Native surface create returned");}
 // Original GameApp: immersive mode (system UI flags 5894) and safe margins from status-bar/cutout insets passed to setSafeMargins(bottom, top, left, right),
 // so HUD icons stay clear of the camera cutout and rounded corners. Margins are applied on the logic thread (original uses runOnView).
 static volatile int[] pendingSafeMargins;
 void applyOriginalWindowMode(android.view.View view){
  getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // original game also keeps the screen on while playing
  if(android.os.Build.VERSION.SDK_INT>=28){android.view.WindowManager.LayoutParams lp=getWindow().getAttributes();lp.layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;getWindow().setAttributes(lp);}
  view.setSystemUiVisibility(5894);
  view.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(android.view.WindowInsets.Type.statusBars()|android.view.WindowInsets.Type.displayCutout());pendingSafeMargins=new int[]{i.bottom,i.top,i.left,i.right};record("Safe margins b="+i.bottom+" t="+i.top+" l="+i.left+" r="+i.right);return insets;});
 }
 public static void flushSafeMargins(){int[] m=pendingSafeMargins;if(m!=null){pendingSafeMargins=null;setSafeMargins(m[0],m[1],m[2],m[3]);}}
 // Same mapping as original GL2JNISurfaceView.onTouchEvent: down/pointer-down=0, up/pointer-up=1, move=2 (every pointer), cancel=3; surface has no fixed size, so scale is 1.
 static boolean forwardTouch(android.view.MotionEvent e){int a=e.getActionMasked(),i=e.getActionIndex();
  if(a==android.view.MotionEvent.ACTION_MOVE){for(int p=0;p<e.getPointerCount();p++)com.supercell.titan.GameApp.nOnTouchEvent(2,(int)e.getX(p),(int)e.getY(p),e.getPointerId(p));return true;}
  int type=a==0||a==5?0:a==1||a==6?1:a==3?3:-1;if(type<0)return true;
  com.supercell.titan.GameApp.nOnTouchEvent(type,type==3?0:(int)e.getX(i),type==3?0:(int)e.getY(i),type==3?0:e.getPointerId(i));return true;}
 public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){record("Surface changed "+width+"x"+height);nOnSurfaceChanged(holder.getSurface(),width,height);record("Native surface change returned");}
 public void surfaceDestroyed(SurfaceHolder holder){nOnSurfaceDestroyed(holder.getSurface());}
}
