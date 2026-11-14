package org.monzobot.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import androidx.activity.ComponentActivity;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** The companion entry point; legacy connection/auth screens remain independently owned. */
public class CompanionActivity extends ComponentActivity {
 static final int BG=Color.rgb(248,246,242),INK=Color.rgb(23,42,58),MUTED=Color.rgb(82,101,115),CORAL=Color.rgb(255,107,85);
 private static final int PERMISSION=7801;
 private final ExecutorService io=Executors.newSingleThreadExecutor();
 private final ExecutorService agentIo=Executors.newSingleThreadExecutor();
 private Future<?> activeAsk;
 private volatile long askGeneration;
 private CompanionWorkspace workspace;
 private CompanionWorkspace.Snapshot snapshot;
 private LinearLayout body,nav;
 private ScrollView scroll;
 private String tab="Home",error="";
 private volatile boolean closed;
 private boolean notificationStatusRequested;
 private String requestedEvent="",requestedSource="";
 private long historyGeneration;
 boolean historyLoading;
 CompanionInsights.ActivityPage historyPage;
 String historyQuery="",historyRevision="",askQuestion="",askStatus="";
 int historyOffset;
 boolean askBusy;
 CompanionInsights.ActivitySession historySession;
 CompanionAgent.Answer lastAnswer;
 private String savedKey="";
 private long loadGeneration;
 protected CompanionWorkspace createWorkspace(){return new CompanionWorkspace(this);}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  if(state!=null){tab=state.getString("tab","Home");historyQuery=state.getString("historyQuery","");}
  workspace=createWorkspace();LinearLayout shell=column();shell.setBackgroundColor(BG);shell.setPadding(dp(18),0,dp(18),0);
  getWindow().setDecorFitsSystemWindows(false);shell.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());shell.setPadding(dp(18)+bars.left,bars.top,dp(18)+bars.right,bars.bottom);return insets;});
  TextView brand=label("Your money companion",22,INK);brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);brand.setPadding(0,dp(18),0,dp(4));shell.addView(brand);
  TextView independent=label("Independent · Unofficial · Private by default",11,MUTED);independent.setPadding(0,0,0,dp(10));shell.addView(independent);
  scroll=new ScrollView(this);scroll.setFillViewport(true);body=column();body.setPadding(0,dp(6),0,dp(18));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  nav=new LinearLayout(this);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setPadding(0,dp(8),0,dp(8));shell.addView(nav);setContentView(shell);handleIntent(getIntent());render();
 }
 @Override protected void onResume(){super.onResume();reload();}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent);reload();}
 private void handleIntent(Intent intent){if(intent!=null&&intent.hasExtra("companionEvent")){tab="Home";requestedEvent=intent.getStringExtra("companionEvent");requestedSource=intent.getStringExtra("companionSource");}}
 @Override protected void onSaveInstanceState(Bundle out){out.putString("tab",tab);out.putString("historyQuery",historyQuery);super.onSaveInstanceState(out);}
 void reload(){long generation=++loadGeneration;io.execute(()->{try{CompanionWorkspace.Snapshot loaded=workspace.load();String recalled=loadKey();runOnUiThread(()->{if(closed||generation!=loadGeneration)return;accept(loaded);savedKey=recalled;error="";render();});scheduleIfEnabled(loaded.data);}catch(Exception failed){runOnUiThread(()->{if(closed||generation!=loadGeneration)return;error="Your private workspace could not be opened. Saved data has been preserved.";render();});}});}
 private void accept(CompanionWorkspace.Snapshot loaded){
  if(snapshot==null||!snapshot.revision.equals(loaded.revision)){historyOffset=0;historyQuery="";historyLoading=false;historyGeneration++;historySession=loaded.activitySession;historyPage=loaded.initialPage;historyRevision=loaded.revision;lastAnswer=null;if(!askBusy)askStatus="";}
  snapshot=loaded;
 }
 void mutate(String expectedRevision,CompanionWorkspace.Mutation mutation){io.execute(()->{try{CompanionWorkspace.Snapshot edited=workspace.mutate(expectedRevision,mutation);runOnUiThread(()->{if(!closed){accept(edited);render();}});checkSoonIfEnabled(edited.data);}catch(Exception changed){runOnUiThread(()->{if(!closed){toast(changed instanceof IllegalStateException?"Data changed. Review the refreshed screen and try again.":"Could not save that change. Check the values and try again.");reload();}});}});}
 void showHistory(String query,int offset){historyQuery=query==null?"":query;historyOffset=Math.max(0,offset);tab="Activity";if(snapshot==null)return;historyLoading=true;long generation=++historyGeneration;String revision=snapshot.revision;CompanionInsights.ActivitySession session=historySession;String needle=historyQuery;int position=historyOffset;render();io.execute(()->{try{CompanionInsights.ActivityPage page=session.page(needle,position,50);runOnUiThread(()->{if(!closed&&generation==historyGeneration&&snapshot!=null&&revision.equals(snapshot.revision)){historyPage=page;historyLoading=false;render();scroll.scrollTo(0,0);}});}catch(Exception failed){runOnUiThread(()->{if(!closed&&generation==historyGeneration){historyLoading=false;toast("Could not search this snapshot. Refresh and try again.");}});}});}
 private void checkSoonIfEnabled(JSONObject data){JSONObject config=data.optJSONObject("companionNotifications");if(config!=null&&config.optBoolean("enabled"))try{CompanionMonitorWorker.checkSoon(this);}catch(Exception unavailable){/* Keep successful financial edits independent of OS scheduling availability. */}}
 private void scheduleIfEnabled(JSONObject data){JSONObject config=data.optJSONObject("companionNotifications");if(config!=null&&config.optBoolean("enabled"))try{CompanionMonitorWorker.schedule(this);CompanionMonitorWorker.checkSoon(this);}catch(Exception unavailable){/* Permission and scheduler availability are reported separately from stored finances. */}}
 void showImpact(CompanionWorkspace.Snapshot source,Finance.Transaction transaction){AlertDialog loading=dialog().setTitle("Budget impact").setMessage("Checking your imported record against your plan…").setNegativeButton("Close",null).create();loading.show();io.execute(()->{try{CompanionPlanner.TransactionAssessment assessment=CompanionPlanner.assessTransaction(transaction.id,source.history,source.overrides,source.plan,LocalDate.now());runOnUiThread(()->{if(closed){loading.dismiss();return;}if(!loading.isShowing())return;loading.dismiss();if(snapshot==null||!source.revision.equals(snapshot.revision)){toast("Data changed. Open the transaction again.");return;}LinearLayout details=column();details.setPadding(dp(20),dp(8),dp(20),dp(8));paragraph(details,transaction.name+" · "+transaction.date+" · "+Finance.money(transaction.amount));if(assessment.potMovement)paragraph(details,"This is a Pot movement. It stays visible but does not count as external budget spending.");else if(assessment.creditUnclassified)paragraph(details,"This credit is shown separately. It is not assumed to be income or a refund and does not automatically restore a spending allocation.");if(assessment.budgetImpacts.isEmpty())paragraph(details,"This record has no matching allocation in your current plan. You can add a category budget with dates that cover it.");for(CompanionPlanner.BudgetImpact impact:assessment.budgetImpacts){paragraph(details,CategoryPresentation.categoryEmoji(impact.category,source.emojiOverrides)+" "+impact.category+(impact.essential?" · Essential":""));paragraph(details,"Allocation remaining before this record: "+Finance.money(impact.beforeRemainingPence)+"\nAfter this record: "+Finance.money(impact.afterRemainingPence));if(impact.essential)paragraph(details,"Keep this essential allocation protected. Review flexible choices before changing it.");}paragraph(details,"Comparison uses the current imported snapshot with and without this record, not a reconstructed historical balance. Remaining allocation is not available bank cash.");ScrollView view=new ScrollView(this);view.addView(details);dialog().setTitle("How this fits your plan").setView(view).setPositiveButton("Done",null).setNeutralButton("Open plan",(d,w)->select("Plan")).show();});}catch(Exception failed){runOnUiThread(()->{loading.dismiss();toast("Could not assess this record. Refresh and try again.");});}});}
 private void select(String name){if("Activity".equals(name)){showHistory(historyQuery,historyOffset);return;}tab=name;render();scroll.scrollTo(0,0);}
 void showAsk(String question){askQuestion=question;select("Ask");}
 private void render(){if(body==null||closed)return;body.removeAllViews();nav.removeAllViews();
  for(String name:new String[]{"Home","Activity","Plan","Ask","Settings"}){Button item=new Button(this);item.setText(name);item.setTextSize(11);item.setAllCaps(false);item.setTextColor(INK);item.setBackground(shape(name.equals(tab)?CORAL:BG));item.setMinWidth(0);item.setMinimumWidth(0);item.setPadding(0,dp(6),0,dp(6));item.setMinHeight(dp(48));nav.addView(item,new LinearLayout.LayoutParams(0,dp(48),1));item.setOnClickListener(v->select(name));}
  if(!error.isEmpty()){LinearLayout card=card("Workspace unavailable");paragraph(card,error);action(card,"Try again",this::reload,true);body.addView(card);return;}
  if(snapshot==null){paragraph(body,"Opening your private workspace…");return;}
  switch(tab){case "Activity":CompanionHistoryScreen.render(this,body,snapshot);break;case "Plan":CompanionPlanScreen.render(this,body,snapshot);break;case "Ask":CompanionAskScreen.render(this,body,snapshot);break;case "Settings":settings();break;default:home();}
 }
 private void home(){CompanionWorkspace.Snapshot s=snapshot;notificationDetail(s);
  String retrieved=s.data.optString("lastSync");String freshness=CompanionAlertEngine.retrievalStatus(Instant.now(),retrieved);
  if(s.history.isEmpty()){LinearLayout welcome=card("Make your transactions useful");paragraph(welcome,"Connect your export or try the synthetic demo. Your whole history stays available, with no date setup required.");action(welcome,"Connect or import transactions",this::connection,true);body.addView(welcome);return;}
  paragraph(body,("demo".equals(s.data.optString("mode"))?"SYNTHETIC DEMO · ":"")+s.overview.transactionCount+" transactions · "+("recent".equals(freshness)?"retrieved "+displayTime(retrieved):"refresh recommended"));
  paragraph(body,"Latest recorded: "+s.overview.lastDate+" · Export coverage unverified");
  LinearLayout recent=card("Recent activity");for(CompanionInsights.ActivityItem item:s.initialPage.items.subList(0,Math.min(4,s.initialPage.items.size()))){CategoryPresentation.Presentation p=CategoryPresentation.transaction(item.source,s.overrides,s.emojiOverrides);TextView transaction=label(p.emoji+"  "+item.name+"  ·  "+Finance.money(item.source.amount),16,INK);transaction.setPadding(0,dp(12),0,dp(2));recent.addView(transaction);paragraph(recent,item.source.date+" · "+p.accessibleLabel);}action(recent,"See all activity",()->showHistory("",0),false);body.addView(recent);
  JSONArray cards=s.opportunities;for(int i=0;i<cards.length();i++){JSONObject idea=cards.optJSONObject(i);if(idea==null||"history_overview".equals(idea.optString("kind")))continue;LinearLayout card=card(idea.optString("title"));paragraph(card,idea.optString("body"));if(idea.optBoolean("estimate"))paragraph(card,"Estimate · review the evidence before acting.");String question=idea.optString("question");action(card,"Explore with optional AI",()->showAsk(question),false);body.addView(card);}
  LinearLayout categories=card("Where your recorded spending went");List<Map.Entry<String,Long>> amounts=new ArrayList<>(s.overview.categoryDebits.entrySet());amounts.sort((a,b)->Long.compare(b.getValue(),a.getValue()));for(int i=0;i<Math.min(6,amounts.size());i++){Map.Entry<String,Long> amount=amounts.get(i);paragraph(categories,CategoryPresentation.categoryEmoji(amount.getKey(),s.emojiOverrides)+"  "+amount.getKey()+"  ·  "+Finance.money(amount.getValue()));ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(10000);bar.setProgress(s.overview.moneyOut==0?0:(int)Math.min(10000,Math.round((double)amount.getValue()/s.overview.moneyOut*10000)));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(CORAL));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BG));categories.addView(bar,new LinearLayout.LayoutParams(-1,dp(8)));}paragraph(categories,"Share of all-history gross external debits. Credits and Pot movements stay separate.");body.addView(categories);
  LinearLayout totals=card("Your whole history");paragraph(totals,s.overview.firstDate+" → "+s.overview.lastDate);paragraph(totals,"Money out  "+Finance.money(s.overview.moneyOut)+"\nMoney in  "+Finance.money(s.overview.moneyIn));paragraph(totals,s.overview.potMovementCount+" Pot movements shown separately. Money in is unclassified; these totals are not your balance.");action(totals,"Browse every transaction",()->showHistory("",0),false);action(totals,"Connection & refresh",this::connection,false);body.addView(totals);
 }
 private void notificationDetail(CompanionWorkspace.Snapshot s){if(requestedEvent==null||requestedEvent.isEmpty())return;LinearLayout detail=card("Your check-in");boolean sourceMatches=false;try{sourceMatches=requestedSource!=null&&requestedSource.equals(CompanionNotifications.sourceToken(s.data));}catch(Exception ignored){}CompanionAlertEngine.Candidate found=null;if(sourceMatches)for(CompanionAlertEngine.Candidate candidate:s.checkIns)if(candidate.key.equals(requestedEvent)){found=candidate;break;}if(found==null)paragraph(detail,"This check-in is no longer current for the open workspace. Review the latest insights below.");else{paragraph(detail,found.localExplanation);paragraph(detail,"Retrieval: "+found.retrievalStatus+" · bank/export coverage unverified.");}action(detail,"Review your plan",()->select("Plan"),false);action(detail,"Browse transactions",()->showHistory("",0),false);action(detail,"Dismiss check-in",()->{requestedEvent="";requestedSource="";render();},false);body.addView(detail);}
 private String loadKey(){try{return new CompanionKeyStore(this).read();}catch(Exception unavailable){return "";}}
 private void settings(){CompanionWorkspace.Snapshot s=snapshot;LinearLayout source=card("Connection & data");paragraph(source,"Connect Google, refresh your export, import a CSV, or manage your saved data.");action(source,"Open connection settings",this::connection,true);body.addView(source);
  JSONObject config=s.data.optJSONObject("companionNotifications");if(config==null)config=new JSONObject();final JSONObject current=config;
  LinearLayout alerts=card("Private check-ins");paragraph(alerts,current.optBoolean("enabled")?"Companion check-ins are enabled. Android permission and channel settings control delivery.":"Check-ins are off. Your budget insights remain available here.");paragraph(alerts,"On-device export checks run roughly hourly and after refresh. First imports establish a quiet baseline. No background AI calls.");
  action(alerts,"Notification preferences",()->notificationPreferences(current),true);action(alerts,"Android notification settings",()->startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName())),false);body.addView(alerts);
  LinearLayout privacy=card("Your privacy");paragraph(privacy,"Screenshots and app previews are protected. Transactions stay in encrypted local storage. Optional AI asks for provider consent and label-sharing consent separately. Never enter a bank password or payment credential.");action(privacy,"Forget saved AI key",this::forgetKey,false);paragraph(privacy,"Independent open-source project. Not affiliated with or endorsed by Monzo Bank.");body.addView(privacy);
 }
 private void notificationPreferences(JSONObject config){LinearLayout form=column();form.setPadding(dp(20),dp(8),dp(20),dp(8));CheckBox enabled=new CheckBox(this);enabled.setText("Enable private check-ins");enabled.setTextColor(INK);enabled.setChecked(config.optBoolean("enabled"));form.addView(enabled);CheckBox persistent=new CheckBox(this);persistent.setText("Show a quiet companion status notification");persistent.setTextColor(INK);persistent.setChecked(config.optBoolean("persistent"));form.addView(persistent);
  EditText start=field("Quiet hours start (0–23)",Integer.toString(config.optInt("quietStart",21)),InputType.TYPE_CLASS_NUMBER);EditText end=field("Quiet hours end (0–23)",Integer.toString(config.optInt("quietEnd",8)),InputType.TYPE_CLASS_NUMBER);EditText threshold=field("Budget alert threshold (%)",Integer.toString(config.optInt("thresholdPercent",80)),InputType.TYPE_CLASS_NUMBER);form.addView(start);form.addView(end);form.addView(threshold);paragraph(form,"No amounts or merchant names appear on the lock screen. Android may delay or suppress delivery.");
  AlertDialog dialog=dialog().setTitle("Check-ins").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(unused->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int a=Integer.parseInt(start.getText().toString()),b=Integer.parseInt(end.getText().toString()),t=Integer.parseInt(threshold.getText().toString());if(a<0||a>23||b<0||b>23||t<1||t>100)throw new IllegalArgumentException();boolean turnOn=enabled.isChecked(),keep=persistent.isChecked();dialog.dismiss();io.execute(()->{try{CompanionNotifications.configure(this,turnOn,keep,a,b,t);runOnUiThread(()->{if(!closed){if(turnOn&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){notificationStatusRequested=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION);}reload();}});}catch(Exception failed){runOnUiThread(()->toast("Notification preferences could not be saved."));}});}catch(Exception invalid){toast("Use hours 0–23 and a threshold from 1–100.");}}));dialog.show();
 }
 @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==PERMISSION&&notificationStatusRequested){notificationStatusRequested=false;if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)CompanionMonitorWorker.checkSoon(this);else toast("Android permission is off. Check-ins remain available inside the app.");reload();}}
 private void connection(){CompanionMonitorWorker.cancel(this);CompanionNotifications.clearPosted(this);startActivity(new Intent(this,MainActivity.class));}
 String recalledKey(){return savedKey;}
 void forgetKey(){cancelQuestion();io.execute(()->{try{new CompanionKeyStore(this).clear();runOnUiThread(()->{savedKey="";lastAnswer=null;toast("Saved AI key removed.");reload();});}catch(Exception failed){runOnUiThread(()->toast("Could not remove the saved key."));}});}
 void ask(CompanionWorkspace.Snapshot source,String key,String model,String question,boolean shareLabels,boolean rememberKey){if(askBusy)return;askBusy=true;askQuestion=question;lastAnswer=null;askStatus="Checking your imported evidence…";render();long generation=++askGeneration;activeAsk=agentIo.submit(()->{try{
   CompanionWorkspace.Snapshot current=workspace.load();if(!current.revision.equals(source.revision))throw new IllegalStateException("Source changed");
   workspace.mutate(source.revision,data->{data.put("companionModel",model);return data;});
   if(closed||generation!=askGeneration||Thread.currentThread().isInterrupted())throw new InterruptedException();
   if(rememberKey)new CompanionKeyStore(this).save(key,()->!closed&&generation==askGeneration&&!Thread.currentThread().isInterrupted());else new CompanionKeyStore(this).clear();
   CompanionAgent.Answer answer=new CompanionAgent().answer(key,model,question,source.history,source.overrides,LocalDate.now(),source.data.optString("lastSync"),new CompanionAgent.Consent(true,shareLabels),3,source.plan);
   if(closed||generation!=askGeneration)return;CompanionWorkspace.Snapshot after=workspace.load();if(!source.revision.equals(after.revision))throw new IllegalStateException("Source changed");
   runOnUiThread(()->{if(!closed&&generation==askGeneration){accept(after);savedKey=rememberKey?key:"";askBusy=false;lastAnswer=answer;askStatus=answer.complete?"AI interpretation · review before acting":"Call limit reached. No further provider requests were made.";render();}});
  }catch(Exception failed){runOnUiThread(()->{if(!closed&&generation==askGeneration){askBusy=false;lastAnswer=null;askStatus=failed instanceof IllegalStateException?"Your source or plan changed. Ask again using the refreshed data.":"The optional provider could not return an accepted, grounded answer. No financial action was taken.";reload();}});}});}
 void cancelQuestion(){askGeneration++;if(activeAsk!=null&&!activeAsk.isDone())activeAsk.cancel(true);if(askBusy){askBusy=false;lastAnswer=null;askStatus="Question stopped. An in-flight provider request may finish, but no further calls will be started.";} }
 @Override protected void onStop(){cancelQuestion();super.onStop();}
 LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
 LinearLayout card(String heading){LinearLayout card=column();card.setPadding(dp(16),dp(14),dp(16),dp(14));card.setBackground(shape(Color.WHITE));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);card.setLayoutParams(lp);TextView title=label(heading,18,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setPadding(0,0,0,dp(6));card.addView(title);return card;}
 void paragraph(LinearLayout parent,String text){TextView view=label(text,14,MUTED);view.setPadding(0,dp(5),0,dp(5));view.setLineSpacing(dp(2),1.05f);parent.addView(view);}
 void action(LinearLayout parent,String text,Runnable action,boolean primary){Button button=new Button(this);button.setText(text);button.setAllCaps(false);button.setTextSize(14);button.setTextColor(INK);button.setBackground(shape(primary?CORAL:BG));button.setMinHeight(dp(48));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);parent.addView(button,lp);button.setOnClickListener(v->action.run());}
 EditText field(String hint,String value,int type){EditText field=new EditText(this);field.setHint(hint);field.setContentDescription(hint);field.setText(value);field.setInputType(type);field.setTextColor(INK);field.setHintTextColor(MUTED);field.setTextSize(15);field.setSingleLine(true);field.setMinHeight(dp(52));field.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return field;}
 TextView label(String text,int size,int color){TextView label=new TextView(this);label.setText(text);label.setTextSize(size);label.setTextColor(color);return label;}
 AlertDialog.Builder dialog(){return new AlertDialog.Builder(this,android.R.style.Theme_Material_Light_Dialog_Alert){@Override public AlertDialog create(){AlertDialog dialog=super.create();if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);return dialog;}};}
 void toast(String message){if(!closed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
 int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private GradientDrawable shape(int color){GradientDrawable background=new GradientDrawable();background.setColor(color);background.setCornerRadius(dp(14));return background;}
 private String displayTime(String value){try{return DateTimeFormatter.ofPattern("d MMM, HH:mm",Locale.UK).format(Instant.parse(value).atZone(ZoneId.systemDefault()));}catch(Exception invalid){return "not yet retrieved";}}
 @Override protected void onDestroy(){closed=true;loadGeneration++;savedKey="";lastAnswer=null;io.shutdownNow();agentIo.shutdownNow();super.onDestroy();}
}
