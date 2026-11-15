package org.monzobot.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Generic local reminders; no financial labels or figures reach notifications/WorkManager. */
internal object PrivateCheckIns {
  private const val NAME="rn-private-check-ins-v1"
  private const val CHANNEL="companion-check-ins-v1"
  private const val STATUS="companion-status-v1"
  private const val ID=4910
  private const val STATUS_ID=4911
  private fun prefs(context:Context)=context.getSharedPreferences("rn-private-check-ins-v1",Context.MODE_PRIVATE)
  private fun channels(context:Context) {
    val manager=context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL,"Private companion check-ins",NotificationManager.IMPORTANCE_DEFAULT).apply {
      description="Generic reminders to review your local transaction companion";lockscreenVisibility=Notification.VISIBILITY_PRIVATE
    })
    manager.createNotificationChannel(NotificationChannel(STATUS,"Optional companion status",NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
  }
  fun status(context:Context):WritableMap {
    val settings=prefs(context);val manager=context.getSystemService(NotificationManager::class.java)
    val channel=manager.getNotificationChannel(CHANNEL)
    val permission=NotificationManagerCompat.from(context).areNotificationsEnabled()&&(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    return Arguments.createMap().apply {
      putBoolean("enabled",settings.getBoolean("enabled",false));putBoolean("permission",permission)
      putBoolean("persistent",settings.getBoolean("persistent",false));putBoolean("channelEnabled",channel==null||channel.importance!=NotificationManager.IMPORTANCE_NONE)
      putInt("quietStart",settings.getInt("quietStart",21));putInt("quietEnd",settings.getInt("quietEnd",8))
      putString("delivery","On-device checks are inexact and may be delayed. Refresh Google data in the app; no background AI or live-bank push service.")
    }
  }
  fun configure(context:Context,enabled:Boolean,persistent:Boolean,start:Int,end:Int) {
    require(start in 0..23&&end in 0..23)
    val settings=prefs(context)
    settings.edit().putBoolean("enabled",enabled).putBoolean("persistent",persistent).putInt("quietStart",start).putInt("quietEnd",end).apply()
    channels(context)
    if(enabled) {
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<PrivateCheckInWorker>(1,TimeUnit.HOURS).build())
      if(persistent&&status(context).getBoolean("permission")) {
        try { context.getSystemService(NotificationManager::class.java).notify(STATUS_ID,notification(context,STATUS,"Private companion is on","Open to review your imported data. Export coverage is unverified.",true)) } catch(_:SecurityException) {}
      } else context.getSystemService(NotificationManager::class.java).cancel(STATUS_ID)
    } else cancel(context)
  }
  fun cancel(context:Context) {
    WorkManager.getInstance(context).cancelUniqueWork(NAME)
    context.getSystemService(NotificationManager::class.java).cancelAll() // Includes generic notices left by the replaced app.
    prefs(context).edit().putBoolean("enabled",false).putBoolean("persistent",false).apply()
  }
  private fun notification(context:Context,channel:String,title:String,body:String,ongoing:Boolean):Notification {
    val open=PendingIntent.getActivity(context,ID,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val publicVersion=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle("Private check-in available").setContentText("Open the app to review.").build()
    return NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setContentIntent(open).setOnlyAlertOnce(true)
      .setOngoing(ongoing).setSilent(ongoing).setAutoCancel(!ongoing).build()
  }
  fun check(context:Context) {
    val settings=prefs(context)
    if(!settings.getBoolean("enabled",false)) return
    val raw=PrivateStore(context).loadWorkspace() ?: return
    val state=JSONObject(raw)
    val rows=state.optJSONArray("transactions") ?: return
    if(rows.length()==0) return
    val now=Instant.now();val day=LocalDate.now().toString()
    val digest=MessageDigest.getInstance("SHA-256").digest((rows.toString()+state.optString("lastSync")+(state.optJSONObject("companionPlan")?.toString() ?: "null")).toByteArray())
      .joinToString("") { "%02x".format(it) }
    val previous=settings.getString("sourceFingerprint",null)
    if(previous==null) { settings.edit().putString("sourceFingerprint",digest).apply();return }
    val stale=try { val at=Instant.parse(state.optString("lastSync"));at.isAfter(now)||now.epochSecond-at.epochSecond>86400 } catch(_:Exception) { true }
    if(previous==digest&&(!stale||settings.getString("staleDay","")==day)) return
    val hour=LocalTime.now().hour;val start=settings.getInt("quietStart",21);val end=settings.getInt("quietEnd",8)
    val quiet=if(start==end) false else if(start<end) hour in start until end else hour>=start||hour<end
    val count=if(settings.getString("alertDay","")==day) settings.getInt("alertCount",0) else 0
    val last=settings.getLong("lastAlert",0)
    if(quiet||count>=3||now.epochSecond-last<21600||!status(context).getBoolean("permission")||!status(context).getBoolean("channelEnabled")) return
    channels(context)
    try {
      context.getSystemService(NotificationManager::class.java).notify(ID,notification(context,CHANNEL,"Private companion check-in","Open the app to review your local plan and source freshness.",false))
      settings.edit().putString("sourceFingerprint",digest).putString("staleDay",if(stale)day else "").putString("alertDay",day).putInt("alertCount",count+1).putLong("lastAlert",now.epochSecond).apply()
    } catch(_:SecurityException) {}
  }
}
class PrivateCheckInWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) {
  override fun doWork():Result = try { PrivateCheckIns.check(applicationContext);Result.success() } catch(_:Exception) { Result.failure() }
}
/** Preserve names that might already be stored in WorkManager before cancellation completes. */
class SyncWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) { override fun doWork()=Result.success() }
class CompanionMonitorWorker(context:Context,parameters:WorkerParameters):Worker(context,parameters) { override fun doWork()=Result.success() }
