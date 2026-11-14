package org.monzobot.app;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** JVM Android interactions with synthetic data. Never uses Google, a provider, or device secrets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h852dp")
public class CompanionUiTest {
 static volatile JSONObject seed;
 public static class TestActivity extends CompanionActivity {
  @Override protected CompanionWorkspace createWorkspace(){return new CompanionWorkspace(new CompanionWorkspace.Store(){public JSONObject read()throws Exception{return new JSONObject(seed.toString());}public void save(JSONObject value)throws Exception{seed=new JSONObject(value.toString());}});}
 }
 private ActivityController<TestActivity> controller;
 private TestActivity activity;
 private View root;
 @Before public void open()throws Exception {
  seed=SecureStore.defaults();JSONArray transactions=new JSONArray();
  for(int i=0;i<1007;i++){String date=java.time.LocalDate.of(2020,1,1).plusDays(i).toString();transactions.put(new JSONObject().put("id","synthetic-ui-"+i).put("date",date).put("name",i==0?"Ancient bicycle":"Example shop "+i).put("category","Groceries").put("type","Card payment").put("amount",-100).put("raw",new JSONObject().put("Emoji",i==0?"🚲":"🛒").put("Notes and #tags",i==0?"Synthetic old note":"")));}
  seed.put("transactions",transactions).put("lastSync",java.time.Instant.now().toString());
  controller=Robolectric.buildActivity(TestActivity.class).create().start().resume().visible();activity=controller.get();root=activity.findViewById(android.R.id.content);await(()->contains(root,"1007 transactions"));
 }
 @After public void close(){if(controller!=null)controller.pause().stop().destroy();}
 @Test public void wholeHistoryAndOlderSearchAreReachable()throws Exception {
  assertTrue(contains(root,"Your whole history"));click(root,"Activity");await(()->contains(root,"Search"));
  EditText search=field(root,"Search names, categories, notes or pots");search.setText("Ancient bicycle");click(root,"Search history");await(()->contains(root,"Ancient bicycle"));
  assertTrue(contains(root,"🚲"));assertFalse(activity.historyLoading);assertEquals(1,activity.historyPage.total);assertEquals("synthetic-ui-0",activity.historyPage.items.get(0).source.id);
 }
 @Test public void budgetCreationSavesWithoutFilteringHistory()throws Exception {
  click(root,"Plan");click(root,"Create a budget");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);Shadows.shadowOf(Looper.getMainLooper()).idle();
  field(dialog.getWindow().getDecorView(),"Category").setText("Transport");field(dialog.getWindow().getDecorView(),"Budget amount (£)").setText("125.50");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  await(()->seed.optJSONObject("companionPlan")!=null);assertEquals(12550,BudgetWorkspace.read(seed).budgets.get(0).limitPence);assertEquals(1007,seed.getJSONArray("transactions").length());assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
 }
 @Test public void askRequiresConsentAndNeverSavesTheKeyInViewState()throws Exception {
  click(root,"Ask");EditText key=field(root,"OpenAI API key");key.setText("synthetic-test-key");EditText question=field(root,"What would you like help with?");question.setText("Help me understand recorded spending");
  Button ask=(Button)findText(root,"Ask with local evidence");assertNotNull(ask);assertFalse(ask.isEnabled());assertFalse(key.isSaveEnabled());assertFalse(activity.askBusy);assertFalse(seed.has("companionApiKey"));
 }
 @Test public void notificationPreferencesAreReadableAndPrivate()throws Exception {
  click(root,"Settings");click(root,"Notification preferences");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
  TextView enabled=findText(dialog.getWindow().getDecorView(),"Enable private check-ins");assertNotNull(enabled);assertEquals(CompanionActivity.INK,enabled.getCurrentTextColor());assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
 }
 @Test public void transactionImpactUsesTheMatchingBudgetAndExactLocalAmounts()throws Exception {
  seed=BudgetWorkspace.upsertBudget(seed,-1,"Groceries","2020-01-01","2023-01-01","2000.00",true);
  CompanionWorkspace workspace=new CompanionWorkspace(new CompanionWorkspace.Store(){public JSONObject read()throws Exception{return new JSONObject(seed.toString());}public void save(JSONObject value){seed=value;}});
  CompanionWorkspace.Snapshot snapshot=workspace.load();activity.reload();await(()->snapshot.revision.equals(activity.historyRevision));activity.showImpact(snapshot,snapshot.history.get(0));
  await(()->{AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();return dialog!=null&&contains(dialog.getWindow().getDecorView(),"How this fits your plan");});
  View detail=ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();assertTrue(contains(detail,"£994.00"));assertTrue(contains(detail,"£993.00"));assertTrue(contains(detail,"Essential"));assertEquals(1007,seed.getJSONArray("transactions").length());
 }
 @Test public void staleNotificationOpensAnExplicitCurrentWorkspaceNotice()throws Exception {
  activity.onNewIntent(new android.content.Intent(activity,CompanionActivity.class).putExtra("companionEvent","synthetic_old_event").putExtra("companionSource","wrong_source"));
  await(()->contains(root,"no longer current"));assertTrue(contains(root,"Your check-in"));
 }
 @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
 public void renderSyntheticScreensForVisualInspection()throws Exception {
  java.io.File directory=new java.io.File("build/reports/companion-ui");assertTrue(directory.isDirectory()||directory.mkdirs());
  for(String screen:new String[]{"Home","Activity","Plan","Ask","Settings"}){
   click(root,screen);if(screen.equals("Activity"))await(()->!activity.historyLoading);Shadows.shadowOf(Looper.getMainLooper()).idle();
   View decor=activity.getWindow().getDecorView();int width=393,height=852;decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);
   android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(bitmap));
   try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,screen+".png"))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}bitmap.recycle();
  }
 }
 private interface Condition{boolean done()throws Exception;}
 private static void await(Condition condition)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(condition.done())return;Thread.sleep(10);}throw new AssertionError("Android interaction did not settle");}
 private static boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
 private static TextView findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView match=findText(group.getChildAt(i),text);if(match!=null)return match;}}return null;}
 private static EditText field(View view,String description){if(view instanceof EditText&&description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText match=field(group.getChildAt(i),description);if(match!=null)return match;}}return null;}
 private static void click(View root,String text){TextView target=findText(root,text);assertNotNull("Missing control: "+text,target);target.performClick();}
}
