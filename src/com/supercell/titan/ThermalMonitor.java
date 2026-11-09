package com.supercell.titan;
import android.content.*;import android.os.PowerManager;
// Offline ThermalMonitor: same public fields/signatures as original; real PowerManager thermal status, no prediction (status 1 = not initialized).
public class ThermalMonitor {
 public static final int THERMAL_PREDICTION_INITIALIZED=0,THERMAL_PREDICTION_NOT_AVAILABLE=0,THERMAL_PREDICTION_NOT_INITIALIZED=0,THERMAL_PREDICTION_ONGOING=0;
 public static ThermalMonitor instance;
 public long appLaunchTime,foregroundTime,predictionTime=-1;
 public float prediction=Float.NaN;
 public int forecastTimeSeconds=-1,forecastIntervalSeconds=-1,appLaunchThermalStatus=-1,foregroundThermalStatus=-1,maxThermalStatusSinceForegrounded=-1,maxThermalStatusSinceLaunched=-1;
 public final java.util.Map<String,Session> sessions=new java.util.HashMap<>();
 public PowerManager powerManager;
 public static class PredictionResult {public long nanosecondsSincePrediction;public float prediction;public boolean success;}
 public static class SessionResult {public int maxStatus,minStatus,statusAtEnd,statusAtStart;public boolean success;}
 public static class Session {public int maxStatus,minStatus,statusAtStart;public boolean paused=false;public Session(Context c){statusAtStart=minStatus=maxStatus=getCurrentThermalStatus(c);}}

 public native void updatePrediction(float f);

 public static synchronized ThermalMonitor getInstance(){if(instance==null)instance=new ThermalMonitor();return instance;}
 public void init(Context context){appLaunchTime=foregroundTime=System.currentTimeMillis();appLaunchThermalStatus=foregroundThermalStatus=maxThermalStatusSinceForegrounded=maxThermalStatusSinceLaunched=getCurrentThermalStatus(context);}
 public void startSession(Context context,String name){synchronized(sessions){sessions.put(name,new Session(context));}}
 public SessionResult endSession(Context context,String name){SessionResult r=new SessionResult();Session s;synchronized(sessions){s=sessions.remove(name);}
  if(s!=null){r.success=true;r.minStatus=s.minStatus;r.maxStatus=s.maxStatus;r.statusAtStart=s.statusAtStart;r.statusAtEnd=getCurrentThermalStatus(context);}return r;}
 public void pauseSession(Context context,String name){synchronized(sessions){Session s=sessions.get(name);if(s!=null)s.paused=true;}}
 public void resumeSession(Context context,String name){synchronized(sessions){Session s=sessions.get(name);if(s!=null)s.paused=false;}}
 public static int getCurrentThermalStatus(Context context){PowerManager p=context==null?null:(PowerManager)context.getSystemService(Context.POWER_SERVICE);return p==null?-1:p.getCurrentThermalStatus();}
 public static boolean isThermalStatusAvailable(){return true;}
 public static String thermalStatusToString(int s){switch(s){case 0:return "none";case 1:return "light";case 2:return "moderate";case 3:return "severe";case 4:return "critical";case 5:return "emergency";case 6:return "shutdown";default:return "unknown";}}
 public int getThermalPredictionStatus(){return 1;}
 public void initThermalPrediction(Context context,int forecastSeconds,int intervalSeconds){forecastTimeSeconds=forecastSeconds;forecastIntervalSeconds=intervalSeconds;}
 public void deinitThermalPrediction(){}
 public void startPrediction(){}
 public void stopPrediction(){}
}
