package lab.coc.offline;
import android.app.Activity;import android.content.Intent;import android.os.Bundle;import android.os.Handler;import android.os.Looper;
/** Runs in its own process: waits for the render process to exit, then relaunches the local home (used to return from an offline battle). */
public class RestartActivity extends Activity {
 @Override protected void onCreate(Bundle saved){super.onCreate(saved);
  new Handler(Looper.getMainLooper()).postDelayed(()->{startActivity(new Intent(this,RenderActivity.class).putExtra("localHome",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();android.os.Process.killProcess(android.os.Process.myPid());},1500);}
}
