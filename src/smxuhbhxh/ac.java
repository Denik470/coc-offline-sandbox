package smxuhbhxh;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.*;
/** Diagnostic implementation of the original native report channel ABI. */
public class ac {
 private static volatile int a=-1;
 private static final String b="Report";
 private static volatile boolean started;
 private static String a() throws IOException {
  try(Reader in=new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(a)))){StringBuilder out=new StringBuilder();char[] buf=new char[1024];int n;while((n=in.read(buf))>0){out.append(buf,0,n);if(out.charAt(out.length()-1)=='?'){out.setLength(out.length()-1);break;}}return out.toString();}
 }
 public static void a(String value){Log.e(b,"Native startup report: "+value);throw new RuntimeException("Native startup report: "+value);}
 public static synchronized void d(){if(started)return;started=true;new Thread(()->{try{for(int i=0;i<10&&a<0;i++)Thread.sleep(1000);if(a>=0)a(a());}catch(IOException|InterruptedException ex){throw new RuntimeException(ex);}},"NativeReport").start();}
}
