package org.monzobot.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Opt-in Android delivery adapter. All content on OS surfaces is deliberately generic. */
public final class CompanionNotifications {
 private CompanionNotifications() {}
 private static final String CONFIG="companionNotifications", LEDGER="companionAlertLedger";
 private static final String CHANNEL="companion-check-ins-v1", STATUS_CHANNEL="companion-status-v1";
 private static final String STATUS_TAG="companion-status";
 private static final int ID=701, STATUS_ID=702, MAX_PENDING=2000, MAX_ROWS=50000;
 public static JSONObject defaults()throws JSONException{return new JSONObject().put("enabled",false).put("persistent",false).put("quietStart",21).put("quietEnd",8).put("thresholdPercent",80);}
 /** Call off the main thread after the user opts in. Runtime permission must be requested in the UI. */
 public static void configure(Context context,boolean enabled,boolean persistent,int quietStart,int quietEnd,int thresholdPercent)throws Exception {
  if(quietStart<0||quietStart>23||quietEnd<0||quietEnd>23||thresholdPercent<1||thresholdPercent>100)throw new IllegalArgumentException("Invalid notification settings");
  SecureStore store=new SecureStore(context);
  synchronized(SecureStore.LOCK){JSONObject state=store.load();JSONObject config=defaults().put("enabled",enabled).put("persistent",persistent).put("quietStart",quietStart).put("quietEnd",quietEnd).put("thresholdPercent",thresholdPercent);state.put(CONFIG,config);
   // The new engine replaces the legacy budget-only delivery path when enabled.
   if(enabled)state.put("notifications",false);
   store.save(state);
  }
  if(enabled){CompanionMonitorWorker.schedule(context);CompanionMonitorWorker.checkSoon(context);}else{CompanionMonitorWorker.cancel(context);clearPosted(context);}
 }
 /** Saves only user-approved local planning context. Never edits source cells or executes payments. */
 public static void savePlan(Context context,CompanionPlanner.Plan plan)throws Exception {
  SecureStore store=new SecureStore(context);synchronized(SecureStore.LOCK){store.save(BudgetWorkspace.write(store.load(),plan));}
  CompanionMonitorWorker.checkSoon(context);
 }
 /** UI status without financial content; permissions and channel choices remain user-controlled. */
 public static JSONObject availability(Context context)throws Exception {
  JSONObject config=new SecureStore(context).load().optJSONObject(CONFIG);if(config==null)config=defaults();
  NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
  boolean permission=(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&NotificationManagerCompat.from(context).areNotificationsEnabled();
  boolean channelEnabled=channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
  return new JSONObject().put("enabled",config.optBoolean("enabled")).put("permissionGranted",permission).put("channelEnabled",channelEnabled).put("deliveryAvailable",config.optBoolean("enabled")&&permission&&channelEnabled).put("schedule","hourly_inexact_local_snapshot_check").put("bankCoverage","unverified").put("automaticAiCalls",false);
 }
 public static void check(Context context)throws Exception {
  SecureStore store=new SecureStore(context);
  synchronized(SecureStore.LOCK){
   JSONObject state=store.load();JSONObject config=state.optJSONObject(CONFIG);
   if(config==null||!config.optBoolean("enabled")||"demo".equals(state.optString("mode"))){clearPosted(context);return;}
   if("connected".equals(state.optString("mode"))&&!state.optBoolean("authorized")){clearPosted(context);return;}
   List<Finance.Transaction> history=SecureStore.transactions(state);if(history.size()>MAX_ROWS)throw new IllegalArgumentException("Notification snapshot exceeds supported row limit");
   Instant now=Instant.now();ZoneId zone=ZoneId.systemDefault();LocalDate today=now.atZone(zone).toLocalDate();
   String source=sourceToken(state);
   JSONObject ledger=state.optJSONObject(LEDGER);boolean sourceChanged=ledger==null||!source.equals(ledger.optString("source"));
   if(sourceChanged){clearPosted(context);ledger=new JSONObject().put("source",source);}
   boolean initial=sourceChanged||!ledger.has("knownRefs");
   Set<String> known=new HashSet<>();JSONArray prior=ledger.optJSONArray("knownRefs");if(prior!=null)for(int i=0;i<prior.length();i++)known.add(prior.getString(i));
   JSONObject pending=ledger.optJSONObject("pending");pending=pending==null?new JSONObject():new JSONObject(pending.toString());
   List<String> pendingKeys=new ArrayList<>();Iterator<String> iterator=pending.keys();while(iterator.hasNext())pendingKeys.add(iterator.next());
   for(String key:pendingKeys){long at=pending.optLong(key,-1);if(at<0||at>now.toEpochMilli()||now.toEpochMilli()-at>Duration.ofDays(7).toMillis())pending.remove(key);}
   JSONArray current=new JSONArray();List<String> deltaIds=new ArrayList<>();
   for(Finance.Transaction row:history){String ref=CompanionAlertEngine.evidenceRef(row.id);current.put(ref);if(!initial&&!known.contains(ref))pending.put(ref,now.toEpochMilli());if(pending.has(ref))deltaIds.add(row.id);}
   // A large source replacement is treated as a baseline, rather than a flood of new-payment alerts.
   if(pending.length()>MAX_PENDING){initial=true;pending=new JSONObject();deltaIds.clear();}
   List<CompanionAlertEngine.Candidate> candidates=CompanionAlertEngine.evaluate(history,SecureStore.overrides(state),BudgetWorkspace.read(state),today,now,state.optString("lastSync"),new CompanionAlertEngine.Settings(config.optInt("thresholdPercent",80),initial,deltaIds));
   NotificationManager manager=context.getSystemService(NotificationManager.class);channels(manager);
   boolean permission=Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
   permission=permission&&NotificationManagerCompat.from(context).areNotificationsEnabled();
   NotificationChannel channel=manager.getNotificationChannel(CHANNEL);permission=permission&&channel!=null&&channel.getImportance()!=NotificationManager.IMPORTANCE_NONE;
   CompanionDelivery.Outcome outcome=CompanionDelivery.deliver(candidates,ledger,now,zone,true,permission,config.optInt("quietStart",21),config.optInt("quietEnd",8),candidate->{
    try{manager.notify(candidate.key,ID,notification(context,candidate,source));return true;}catch(SecurityException denied){return false;}
   });
   outcome.ledger.put("source",source).put("pending",pending);
   if(!history.isEmpty()||"recent".equals(CompanionAlertEngine.retrievalStatus(now,state.optString("lastSync"))))outcome.ledger.put("knownRefs",current);
   state.put(LEDGER,outcome.ledger);
   try{store.save(state);}catch(Exception failed){if(!outcome.postedKey.isEmpty())manager.cancel(outcome.postedKey,ID);throw failed;}
   if(permission&&config.optBoolean("persistent"))status(context,manager);else manager.cancel(STATUS_TAG,STATUS_ID);
  }
 }
 private static void channels(NotificationManager manager){
  NotificationChannel checks=new NotificationChannel(CHANNEL,"Private companion check-ins",NotificationManager.IMPORTANCE_DEFAULT);checks.setDescription("Budget, payment-review, confirmed-payment and source-freshness check-ins");checks.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(checks);
  NotificationChannel status=new NotificationChannel(STATUS_CHANNEL,"Optional companion status",NotificationManager.IMPORTANCE_LOW);status.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);manager.createNotificationChannel(status);
 }
 private static PendingIntent open(Context context,String key,String kind){return open(context,key,kind,"");}
 private static PendingIntent open(Context context,String key,String kind,String source){Intent intent=new Intent(context,CompanionActivity.class).setData(android.net.Uri.parse("monzobot://companion/"+key)).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("companionEvent",key).putExtra("companionKind",kind).putExtra("companionSource",source);return PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
 private static Notification notification(Context context,CompanionAlertEngine.Candidate candidate,String source){
  Notification publicVersion=new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Private check-in available").setContentText("Open the app to review.").build();
  return new NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(candidate.title).setContentText(candidate.body).setStyle(new NotificationCompat.BigTextStyle().bigText(candidate.body)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).setContentIntent(open(context,candidate.key,candidate.kind,source)).setAutoCancel(true).setOnlyAlertOnce(true).build();
 }
 private static void status(Context context,NotificationManager manager){
  Notification n=new NotificationCompat.Builder(context,STATUS_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Private companion is on").setContentText("Export coverage is unverified. Open to review.").setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open(context,STATUS_TAG,"status")).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).build();
  try{manager.notify(STATUS_TAG,STATUS_ID,n);}catch(SecurityException ignored){}
 }
 /** Invoke on disconnect, source switch and deletion as well as disabling the feature. */
 public static void clearPosted(Context context){NotificationManager manager=context.getSystemService(NotificationManager.class);for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())if(n.getId()==ID||n.getId()==STATUS_ID)manager.cancel(n.getTag(),n.getId());}
 static String sourceToken(JSONObject state)throws Exception{return hash(state.optString("workspaceId")+"\u0000"+state.optString("mode")+"\u0000"+state.optString("spreadsheetId"));}
 private static String hash(String value)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
}
