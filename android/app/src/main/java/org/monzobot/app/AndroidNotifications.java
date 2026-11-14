package org.monzobot.app;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.*;
import java.time.*;
import java.util.*;

final class AndroidNotifications {
 static final String CHANNEL="budget-checks";
 static void check(Context context,JSONObject data) throws Exception {
  JSONObject companion=data.optJSONObject("companionNotifications");if(companion!=null&&companion.optBoolean("enabled"))return;
  NotificationManager nm=context.getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Private budget check-ins",NotificationManager.IMPORTANCE_DEFAULT));
  boolean permission=Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
  if(!NotificationManagerCompat.from(context).areNotificationsEnabled())permission=false;
  LocalDate today=LocalDate.now();String start=data.getString("start"),end=data.getString("end");
  if(today.isBefore(LocalDate.parse(start))||today.isAfter(LocalDate.parse(end)))return;
  String day=today.toString();int count=day.equals(data.optString("alertDay"))?data.optInt("alertCount"):0;
  long now=System.currentTimeMillis();
  if(!NotificationPolicy.due(now,data.optLong("lastAlert"),count,data.optBoolean("notifications"),permission,LocalTime.now().getHour(),data.optInt("quietStart",21),data.optInt("quietEnd",8)))return;
  List<Finance.Alert> alerts=Finance.alerts(SecureStore.report(data),SecureStore.budgets(data),data.optInt("threshold",80));
  JSONObject sent=data.optJSONObject("sent");if(sent==null)sent=new JSONObject();
  for(Finance.Alert a:alerts){
   String key=NotificationPolicy.key(start,end,a);if(sent.has(key))continue;
   Intent intent=new Intent(context,CompanionActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
   PendingIntent pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   Notification n=new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Private budget check-in").setContentText(NotificationPolicy.genericBody("unknown")).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Private check-in available").build()).setContentIntent(pending).setAutoCancel(true).build();
   try{nm.notify(42,n);}catch(SecurityException denied){return;}
   sent.put(key,now);data.put("sent",sent).put("lastAlert",now).put("alertDay",day).put("alertCount",count+1);return;
  }
 }
}
