package org.monzobot.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates UI, authorization and private state. OAuth tokens are memory-only. */
final class AppController {
 private static final int AUTH=7201,EXPORT=7202;
 private final Activity activity;private final Runnable changed;private final SecureStore store;
 private final ExecutorService executor=Executors.newSingleThreadExecutor();
 private volatile boolean closed;private volatile String error="";private String exportPending="";
 private volatile String cachedSnapshot="{}";
 private volatile long authorizationEpoch;
 private boolean authorizationPending;
 private long pendingAuthorizationEpoch;
 AppController(Activity activity,Runnable changed){
  this.activity=activity;this.changed=changed;store=new SecureStore(activity);
  try{cachedSnapshot=emptyView("Opening your private workspace…").toString();}catch(Exception ignored){}
  reload();
 }
 interface Operation{void run()throws Exception;}
 private void async(Operation op){execute(op,true);}
 private void execute(Operation op,boolean clearError){
  if(closed)return;
  try{executor.execute(()->{
   if(closed)return;
   try{op.run();if(clearError)error="";}
   catch(Exception e){if(!closed)error="Operation did not complete. Previous data is preserved. "+safeError(e);}
   if(!closed){rebuildSnapshot();refresh();}
  });}catch(RejectedExecutionException ignored){/* Activity closed while a Google callback arrived. */}
 }
 void reload(){execute(()->{},false);}
 private void showError(String message){if(closed)return;error=message;reload();}
 private static String safeError(Exception e){if(e instanceof IllegalArgumentException)return e.getMessage();return "Check your connection, Google permission and source format.";}
 private void refresh(){if(!closed)activity.runOnUiThread(()->{if(!closed)changed.run();});}
 private interface Change{void apply(JSONObject d)throws Exception;}
 private void update(Change change)throws Exception{synchronized(SecureStore.LOCK){if(closed)return;JSONObject d=store.load();change.apply(d);if(!closed)store.save(d);}}
 JSONObject snapshot(){try{return new JSONObject(cachedSnapshot);}catch(Exception ignored){return new JSONObject();}}
 private JSONObject emptyView(String status)throws JSONException{
  JSONObject s=SecureStore.defaults();return s.put("status",status).put("report",new JSONObject()).put("alerts",new JSONArray()).put("transactions",new JSONArray());
 }
 private void rebuildSnapshot(){
  try{
   JSONObject d=store.load();JSONObject s=new JSONObject();
   for(String k:new String[]{"mode","status","sourceName","lastSync","spreadsheetId","start","end","notifications","threshold","quietStart","quietEnd","aiExplanation","budgets"})if(d.has(k))s.put(k,d.get(k));
   // Rehydrate evidence once, off the UI thread. Never copy raw source rows into the view cache.
   List<Finance.Transaction> all=SecureStore.transactions(d);Map<String,String> overrides=SecureStore.overrides(d);
   Finance.Report r=Finance.summarize(all,d.getString("start"),d.getString("end"),overrides);
   s.put("report",new JSONObject().put("inflows",r.inflows).put("outflows",r.outflows).put("net",r.net).put("count",r.count).put("categories",new JSONObject(r.categories)).put("daily",new JSONObject(r.daily)));
   JSONArray alerts=new JSONArray();for(Finance.Alert a:Finance.alerts(r,SecureStore.budgets(d),d.optInt("threshold",80)))alerts.put(new JSONObject().put("category",a.category).put("spent",a.spent).put("limit",a.limit).put("remaining",a.remaining).put("level",a.level));s.put("alerts",alerts);
   // Cap only the presentation list, explicitly labelled in the UI. Totals always cover all rows.
   JSONArray tx=new JSONArray();int selected=0;
   for(int i=all.size()-1;i>=0;i--){Finance.Transaction t=all.get(i);if(t.date.compareTo(d.getString("start"))>=0&&t.date.compareTo(d.getString("end"))<=0){selected++;if(tx.length()<1000)tx.put(new JSONObject().put("id",t.id).put("date",t.date).put("name",t.name).put("category",overrides.getOrDefault(t.id,t.category)).put("amount",t.amount));}}
   s.put("transactions",tx).put("transactionsTotal",selected);
   String sync=d.optString("lastSync");String f=sync.isEmpty()?"unknown":"retrieved";if(!sync.isEmpty()&&Instant.parse(sync).isBefore(Instant.now().minus(Duration.ofHours(24))))f="stale";
   s.put("freshness",f+" · export coverage unverified").put("sourceAsOf","Unknown — source does not report coverage");if(!error.isEmpty())s.put("status",error);cachedSnapshot=s.toString();
  }catch(Exception e){try{cachedSnapshot=emptyView("Private data could not be opened. Do not clear it unless you want to delete it.").toString();}catch(Exception ignored){}}
 }

 void connectGoogle(){
  new AlertDialog.Builder(activity).setTitle("Connect Google Sheets read-only").setMessage("Google's read-only Sheets permission covers spreadsheets in your account. This app reads only the spreadsheet link you select; it does not list Drive or modify files. Transactions stay encrypted on this device. After connection, Android may retrieve this sheet hourly when network and battery permit. Export coverage is not guaranteed live.").setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->authorize(false)).show();
 }
 private void authorize(boolean thenSync){
  if(closed)return;final long epoch=authorizationEpoch;authorizationPending=true;pendingAuthorizationEpoch=epoch;
  Identity.getAuthorizationClient(activity).authorize(SyncWorker.request()).addOnSuccessListener(result->{
   if(closed||epoch!=authorizationEpoch)return;
   if(result.hasResolution()){try{activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(),AUTH,null,0,0,0);}catch(Exception e){authorizationPending=false;showError("Google authorization could not open.");}}
   else{authorizationPending=false;authorized(result,thenSync,epoch);}
  }).addOnFailureListener(e->{if(closed||epoch!=authorizationEpoch)return;authorizationPending=false;showError("Google authorization failed. This APK's package and signing certificate must be registered in the dedicated Google project, with Sheets API enabled.");});
 }
 private void authorized(AuthorizationResult result,boolean thenSync,long epoch){
  if(closed||epoch!=authorizationEpoch)return;
  String token=result.getAccessToken();if(token==null){showError("Google did not grant Sheets access.");return;}
  async(()->{if(closed||epoch!=authorizationEpoch)return;update(d->d.put("authorized",true).put("status","Google permission granted. Select your Monzo transaction spreadsheet."));String id=store.load().optString("spreadsheetId");if(!closed&&epoch==authorizationEpoch&&!id.isEmpty())fetch(token,id);});
 }
 void selectSpreadsheet(String link){
  final String id;try{id=GoogleSheetsClient.spreadsheetId(link);}catch(IllegalArgumentException e){showError(e.getMessage());return;}
  JSONObject current=snapshot();
  if(current.optJSONArray("transactions")!=null&&current.optJSONArray("transactions").length()>0&&!id.equals(current.optString("spreadsheetId"))){new AlertDialog.Builder(activity).setTitle("Replace the local transaction source?").setMessage("Changing sources removes saved transaction rows and category corrections from this device. Your original spreadsheet is untouched. Export a private copy first if you need it. Category budgets are retained for review.").setNegativeButton("Keep current",null).setPositiveButton("Replace local source",(d,w)->commitSpreadsheet(id)).show();}
  else commitSpreadsheet(id);
 }
 private void commitSpreadsheet(String id){authorizationEpoch++;authorizationPending=false;async(()->{SyncWorker.cancel(activity);update(d->{if(!id.equals(d.optString("spreadsheetId"))){d.put("transactions",new JSONArray()).put("overrides",new JSONObject()).put("sent",new JSONObject()).remove("lastSync");d.remove("sourceName");d.remove("sourceAsOf");d.remove("aiExplanation");}d.put("spreadsheetId",id).put("mode","connected").put("status","Spreadsheet selected. Tap Sync to retrieve it.");});});}
 void sync(){
  if(snapshot().optString("spreadsheetId").isEmpty()){showError("Select your Google spreadsheet first.");return;}
  authorize(true);
 }
 private void fetch(String token,String id)throws Exception{
  GoogleSheetsClient.FetchResult result=new GoogleSheetsClient(token).fetch(id);
  update(d->{if(!id.equals(d.optString("spreadsheetId"))||!d.optBoolean("authorized"))return;List<Finance.Transaction> transactions=Finance.fromRows(result.rows,SecureStore.transactions(d));d.put("transactions",SecureStore.encode(transactions)).put("sourceName",result.title).put("lastSync",result.fetchedAt).put("sourceAsOf","").put("mode","connected").put("status","Spreadsheet retrieved. Export coverage is unverified; no current balance is available.");d.remove("aiExplanation");AndroidNotifications.check(activity,d);});
  if(!closed)SyncWorker.schedule(activity);
 }
 void loadDemo(){async(()->{if(!store.load().optString("spreadsheetId").isEmpty()||!SecureStore.transactions(store.load()).isEmpty())throw new IllegalArgumentException("Clear the current workspace before loading the demo, so sources cannot be mixed.");String csv;try(InputStream in=activity.getAssets().open("synthetic.csv")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(out.size()+n>5*1024*1024)throw new IOException("Demo asset too large.");out.write(block,0,n);}csv=new String(out.toByteArray(),StandardCharsets.UTF_8);}List<Finance.Transaction> tx=Finance.importCsv(csv,Collections.emptyList());update(d->d.put("transactions",SecureStore.encode(tx)).put("mode","demo").put("sourceName","Synthetic demonstration").put("start","2026-11-01").put("end","2026-11-30").put("budgets",new JSONObject().put("Groceries",20000)).put("status","SYNTHETIC DEMO — no personal transactions. Notifications off.").put("notifications",false));SyncWorker.cancel(activity);});}
 void importCsv(String csv){async(()->{update(d->{if("demo".equals(d.optString("mode")))throw new IllegalArgumentException("Clear the synthetic demo before importing personal transactions, so sources cannot be mixed.");if(!d.optString("spreadsheetId").isEmpty())throw new IllegalArgumentException("Disconnect and clear the current source before importing a local file.");List<Finance.Transaction> tx=Finance.importCsv(csv,SecureStore.transactions(d));d.put("transactions",SecureStore.encode(tx)).put("mode","local").put("sourceName","Local CSV import").put("status","CSV imported locally. Source coverage unknown.").put("lastSync",Instant.now().toString());d.remove("aiExplanation");});});}
 void setPeriod(String start,String end){async(()->{String a=Finance.isoDate(start),b=Finance.isoDate(end);if(a.compareTo(b)>0)throw new IllegalArgumentException("Start must precede end.");update(d->{
  JSONObject periods=d.optJSONObject("budgetPeriods");if(periods==null)periods=new JSONObject();
  periods.put(d.getString("start")+"|"+d.getString("end"),new JSONObject(d.getJSONObject("budgets").toString()));
  JSONObject next=periods.optJSONObject(a+"|"+b);
  d.put("budgetPeriods",periods).put("start",a).put("end",b).put("budgets",next==null?new JSONObject():new JSONObject(next.toString()));d.remove("aiExplanation");
 });});}
 void copyBudgetTemplate(){async(()->update(d->{
  LocalDate start=LocalDate.parse(d.getString("start")),end=LocalDate.parse(d.getString("end"));
  if(start.getDayOfMonth()!=1||!end.equals(start.withDayOfMonth(start.lengthOfMonth())))throw new IllegalArgumentException("Previous-month templates require a full calendar-month period.");
  LocalDate previous=start.minusMonths(1);JSONObject periods=d.optJSONObject("budgetPeriods");
  JSONObject template=periods==null?null:periods.optJSONObject(previous+"|"+previous.withDayOfMonth(previous.lengthOfMonth()));
  if(template==null||template.length()==0)throw new IllegalArgumentException("No saved category budgets exist for the previous month.");
  JSONObject copy=new JSONObject(template.toString());d.put("budgets",copy);periods.put(start+"|"+end,new JSONObject(copy.toString()));d.remove("aiExplanation");d.put("status","Previous month's budget template copied. Review the amounts for this month.");
 }));}
 void setBudget(String category,String amount){async(()->{String c=category.trim();if(c.isEmpty()||c.length()>80)throw new IllegalArgumentException("Enter a category up to 80 characters.");long value=Finance.minor(amount);if(value<=0)throw new IllegalArgumentException("Budget must be positive.");update(d->{JSONObject b=d.getJSONObject("budgets");if(!b.has(c)&&b.length()>=100)throw new IllegalArgumentException("Maximum 100 category budgets.");b.put(c,value);JSONObject periods=d.optJSONObject("budgetPeriods");if(periods==null)periods=new JSONObject();periods.put(d.getString("start")+"|"+d.getString("end"),new JSONObject(b.toString()));d.put("budgetPeriods",periods);d.remove("aiExplanation");});});}
 void setCategory(String id,String category){async(()->{String c=category.trim();if(c.isEmpty()||c.length()>80)throw new IllegalArgumentException("Enter a category up to 80 characters.");update(d->{boolean exists=false;for(Finance.Transaction t:SecureStore.transactions(d))if(t.id.equals(id))exists=true;if(!exists)throw new IllegalArgumentException("Transaction not found.");d.getJSONObject("overrides").put(id,c);d.remove("aiExplanation");});});}
 void setSettings(boolean enabled,int threshold,int quietStart,int quietEnd){async(()->{if(threshold<1||threshold>100||quietStart<0||quietStart>23||quietEnd<0||quietEnd>23)throw new IllegalArgumentException("Check threshold and hours.");update(d->{d.put("notifications",enabled).put("threshold",threshold).put("quietStart",quietStart).put("quietEnd",quietEnd);d.remove("aiExplanation");});});}
 void clearData(){authorizationEpoch++;authorizationPending=false;exportPending="";async(()->{SyncWorker.cancel(activity);activity.getSystemService(NotificationManager.class).cancelAll();store.clear();});}
 void disconnect(){authorizationEpoch++;authorizationPending=false;async(()->{SyncWorker.cancel(activity);update(d->d.put("authorized",false).put("spreadsheetId","").put("mode","local").put("status","Automatic access disconnected on this device. You can also revoke this app in your Google Account permissions. Saved transactions remain until you delete them."));});}
 void explain(){
  final JSONObject s=snapshot();final JSONObject report=s.optJSONObject("report");if(report==null||report.optInt("count")==0){showError("Import transactions before requesting coaching.");return;}
  String local="Based on "+s.optString("start")+" through "+s.optString("end")+", imported money out is "+Finance.money(report.optLong("outflows"))+". Net movement is not an available balance. Review your largest category and choose one small adjustment. Upcoming commitments and source coverage are unverified.";
  new AlertDialog.Builder(activity).setTitle("Budget coaching").setMessage(local+"\n\nOptional AI can explain these totals using your own provider key. Nothing is sent unless you explicitly confirm.").setNegativeButton("Keep local",(d,w)->async(()->update(v->v.put("aiExplanation",local)))).setPositiveButton("Optional AI…",(d,w)->aiDialog(s)).show();
 }
 private void aiDialog(JSONObject snapshot){
  LinearLayout box=new LinearLayout(activity);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(40,12,40,12);
  EditText key=new EditText(activity);key.setHint("Your OpenAI API key (not saved)");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(key);
  EditText model=new EditText(activity);model.setHint("Model ID from your provider");box.addView(model);
  JSONObject aggregate=new JSONObject();try{aggregate.put("periodStart",snapshot.optString("start")).put("periodEnd",snapshot.optString("end")).put("inflowsPence",snapshot.getJSONObject("report").optLong("inflows")).put("outflowsPence",snapshot.getJSONObject("report").optLong("outflows")).put("budgetAlerts",snapshot.getJSONArray("alerts").length()).put("currency","GBP").put("sourceCoverage","unknown");}catch(Exception ignored){}
  TextView preview=new TextView(activity);preview.setText(activity.getString(R.string.ai_payload_preview, aggregate.toString()));box.addView(preview);
  new AlertDialog.Builder(activity).setTitle("Review optional AI request").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Send & use my API credit",(d,w)->{String secret=key.getText().toString().trim(),chosen=model.getText().toString().trim();key.setText("");async(()->{String result=AiCoach.explain(secret,chosen,aggregate);update(v->{Finance.Report current=SecureStore.report(v);if(!v.getString("start").equals(snapshot.optString("start"))||!v.getString("end").equals(snapshot.optString("end"))||current.inflows!=aggregate.optLong("inflowsPence")||current.outflows!=aggregate.optLong("outflowsPence")||Finance.alerts(current,SecureStore.budgets(v),v.optInt("threshold",80)).size()!=aggregate.optInt("budgetAlerts"))throw new IllegalArgumentException("Your data changed during the AI request. Review the current period before requesting another explanation.");v.put("aiExplanation","AI suggestion based on imported aggregates; verify before acting.\n"+result);});});}).show();
 }
 void exportData(){new AlertDialog.Builder(activity).setTitle("Export private financial data?").setMessage("This creates a plaintext JSON copy containing your imported transactions and settings at the location you choose. Keep it private. No credentials are included.").setNegativeButton("Cancel",null).setPositiveButton("Choose location",(d,w)->async(()->{
  String prepared=store.load().toString(2);
  activity.runOnUiThread(()->{if(closed)return;exportPending=prepared;try{activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"private-budget-export.json"),EXPORT);}catch(Exception e){exportPending="";showError("Export could not be prepared.");}});
 })).show();}

 boolean handleActivityResult(int requestCode,int resultCode,Intent data){
  if(requestCode==AUTH){
   if(closed)return true;
   if(!authorizationPending||pendingAuthorizationEpoch!=authorizationEpoch){showError("Google connection returned after this screen restarted or was disconnected. Tap Connect Google to try again.");return true;}
   authorizationPending=false;
   if(resultCode==Activity.RESULT_OK&&data!=null){try{authorized(Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data),true,pendingAuthorizationEpoch);}catch(Exception e){showError("Google permission was not completed.");}}
   else showError("Google connection cancelled.");return true;
  }
  if(requestCode==EXPORT){String content=exportPending;exportPending="";if(resultCode==Activity.RESULT_OK&&data!=null&&data.getData()!=null){if(content.isEmpty()){showError("The export was interrupted or the screen restarted. Choose Export again; no data was written.");return true;}Uri uri=data.getData();async(()->{try(OutputStream out=activity.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException();out.write(content.getBytes(StandardCharsets.UTF_8));}update(v->v.put("status","Private export saved to your chosen location."));});}return true;}return false;
 }
 void close(){closed=true;authorizationEpoch++;authorizationPending=false;exportPending="";executor.shutdownNow();}
}
