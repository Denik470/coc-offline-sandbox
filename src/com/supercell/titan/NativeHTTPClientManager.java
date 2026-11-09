package com.supercell.titan;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
/** Network-disabled adapter. Requests complete with the native transport-failure contract. */
public class NativeHTTPClientManager {
 private static final NativeHTTPClientManager instance=new NativeHTTPClientManager();
 private static final AtomicInteger ids=new AtomicInteger(1234);
 private static final ConcurrentLinkedQueue<int[]> pending=new ConcurrentLinkedQueue<>();
 public static native void getFinished(boolean success,int id,byte[] data,int responseCode);
 public static native void postFinished(boolean success,int id,byte[] data,int responseCode);
 public static NativeHTTPClientManager getInstance(){return instance;}
 public static int startPostRequest(String url,String contentType,byte[] body,String[] keys,String[] values){int id=ids.getAndIncrement();pending.add(new int[]{id,1});return id;}
 public static int startGetRequest(String url,String type,String user,String password){int id=ids.getAndIncrement();pending.add(new int[]{id,0});return id;}
 public void updateBeforeFrame(){int[] r;while((r=pending.poll())!=null){if(r[1]==1)postFinished(false,r[0],null,0);else getFinished(false,r[0],null,0);}}
}
