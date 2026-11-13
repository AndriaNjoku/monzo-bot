package org.monzobot.app;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SyncWorker extends Worker {
 static final String NAME="private-sheet-sync";
 public SyncWorker(@NonNull Context c,@NonNull WorkerParameters params){super(c,params);}
 static void schedule(Context c){WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.KEEP,new PeriodicWorkRequest.Builder(SyncWorker.class,1,TimeUnit.HOURS).setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build());}
 static void cancel(Context c){WorkManager.getInstance(c).cancelUniqueWork(NAME);}
 static AuthorizationRequest request(){return AuthorizationRequest.builder().setRequestedScopes(Collections.singletonList(new Scope(GoogleSheetsClient.READ_ONLY_SCOPE))).build();}
 @NonNull @Override public Result doWork(){
  SecureStore store=new SecureStore(getApplicationContext());
  String selected="";
  try {
   JSONObject initial=store.load();String id=initial.optString("spreadsheetId");selected=id;
   if(id.isEmpty()||!initial.optBoolean("authorized")||!"connected".equals(initial.optString("mode")))return Result.success();
   AuthorizationResult auth=Tasks.await(Identity.getAuthorizationClient(getApplicationContext()).authorize(request()),30,TimeUnit.SECONDS);
   if(auth.hasResolution()||auth.getAccessToken()==null){status(store,id,"Google permission needs attention. Open the app to reconnect.");return Result.success();}
   GoogleSheetsClient.FetchResult result=new GoogleSheetsClient(auth.getAccessToken()).fetch(id);
   synchronized(SecureStore.LOCK){JSONObject current=store.load();if(!id.equals(current.optString("spreadsheetId"))||!current.optBoolean("authorized"))return Result.success();
    List<Finance.Transaction> transactions=Finance.fromRows(result.rows,SecureStore.transactions(current));
    current.put("transactions",SecureStore.encode(transactions)).put("lastSync",result.fetchedAt).put("sourceName",result.title).put("sourceAsOf","").put("status","Retrieved selected spreadsheet. Export coverage is unverified.");
    current.remove("aiExplanation");AndroidNotifications.check(getApplicationContext(),current);store.save(current);
   }
   return Result.success();
  }catch(Exception e){try{if(!selected.isEmpty())status(store,selected,"Automatic sync could not complete. Your previous data is preserved; open the app to review.");}catch(Exception ignored){}return getRunAttemptCount()<2?Result.retry():Result.failure();}
 }
 private static void status(SecureStore store,String id,String message)throws Exception{synchronized(SecureStore.LOCK){JSONObject d=store.load();if(!id.isEmpty()&&id.equals(d.optString("spreadsheetId"))&&d.optBoolean("authorized")){d.put("status",message);store.save(d);}}}
}
