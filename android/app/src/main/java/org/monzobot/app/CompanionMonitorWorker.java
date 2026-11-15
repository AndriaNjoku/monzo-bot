package org.monzobot.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

/** Checks encrypted local snapshots; Google sync remains independently scheduled. */
public final class CompanionMonitorWorker extends Worker {
 private static final String NAME="companion-local-checks-v1";
 public CompanionMonitorWorker(@NonNull Context context,@NonNull WorkerParameters parameters){super(context,parameters);}
 public static void schedule(Context context){WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(CompanionMonitorWorker.class,1,TimeUnit.HOURS).build());}
 public static void cancel(Context context){WorkManager.getInstance(context).cancelUniqueWork(NAME);WorkManager.getInstance(context).cancelUniqueWork(NAME+"-now");}
 /** Call after a successful sync or explicit user enable; no financial content enters WorkManager input. */
 public static void checkSoon(Context context){WorkManager.getInstance(context).enqueueUniqueWork(NAME+"-now",ExistingWorkPolicy.KEEP,new OneTimeWorkRequest.Builder(CompanionMonitorWorker.class).build());}
 @NonNull @Override public Result doWork(){try{CompanionNotifications.check(getApplicationContext());return Result.success();}catch(Exception unavailable){return Result.failure();}}
}
