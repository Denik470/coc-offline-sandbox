package lab.coc.offline;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
public class MainActivity extends Activity {
 static { System.loadLibrary("offline_probe"); }
 private static native String battle();
 private static native String probe(String library, android.content.res.AssetManager assets, String progress);
 public void onCreate(Bundle b) {
  super.onCreate(b);deleteFile("native-probe.txt");TextView text=new TextView(this);text.setTextSize(18);text.setPadding(28,64,28,28);text.setText("Проверка локального движка…");android.widget.LinearLayout layout=new android.widget.LinearLayout(this);layout.setOrientation(1);layout.addView(text);android.widget.Button test=new android.widget.Button(this);test.setText("Проверить локальный NPC-бой");test.setEnabled(false);layout.addView(test);setContentView(layout);test.setOnClickListener(v->{test.setEnabled(false);new Thread(()->{String r=battle();runOnUiThread(()->text.setText(r));}).start();});
  new Thread(()->{String result;
   try(InputStream in=getAssets().open("resources.csv")) {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
    while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
    result=probe(getApplicationInfo().nativeLibraryDir+"/libg_headless.so",getAssets(),getFilesDir()+"/native-progress.txt");
   } catch(Throwable ex) {result=ex.toString();}
   if(getIntent().getBooleanExtra("testBattle",false))result+="\n"+battle();
   final String report=result;
   try(FileOutputStream out=openFileOutput("native-probe.txt",MODE_PRIVATE)){out.write(report.getBytes("UTF-8"));}catch(Exception ignored){}
   runOnUiThread(()->{text.setText("CoC Offline Lab — диагностика\n\n"+report+"\n\nИгровая сцена пока не подключена.");test.setEnabled(true);});
  }).start();
 }
}
