package com.supercell.titan;
import android.content.*;import android.os.BatteryManager;
// Same public API as original BatteryMonitor (native creates it with a handle and calls start/stop/getOnce).
public class BatteryMonitor {
 public final Context context;
 public final long nativeHandle;
 public boolean running=false;
 private BroadcastReceiver receiver;
 public BatteryMonitor(Context context,long handle){this.context=context.getApplicationContext();this.nativeHandle=handle;}
 public static final class Snapshot {public final boolean charging;public final int level;public Snapshot(int level,boolean charging){this.level=level;this.charging=charging;}}

 public void start(){if(running)return;running=true;IntentFilter f=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);Intent i=context.registerReceiver(null,f);if(i!=null)notifyNative(fromIntent(i));
  receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent in){notifyNative(fromIntent(in));}};context.registerReceiver(receiver,f);}
 public void stop(){if(!running)return;running=false;if(receiver!=null){context.unregisterReceiver(receiver);receiver=null;}}
 public Snapshot getOnce(){Intent i=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));return i!=null?fromIntent(i):new Snapshot(-1,false);}
 public final Snapshot fromIntent(Intent i){int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1),status=i.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
  return new Snapshot(level>=0&&scale>0?Math.round(level*100f/scale):-1,status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);}
 public final void notifyNative(Snapshot s){updateBatteryInfo(nativeHandle,s.level,s.charging);}

 private static native void updateBatteryInfo(long j,int i,boolean z);
}
