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
 static final java.util.Map<AiProviders.Provider,String> keys=java.util.Collections.synchronizedMap(new java.util.EnumMap<>(AiProviders.Provider.class));
 static final java.util.List<AiProviders.Provider> fakeCalls=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
 public static class TestActivity extends CompanionActivity {
  @Override protected boolean planVoiceAvailable(){return false;}
  @Override protected ProviderSecrets createProviderSecrets(){return new ProviderSecrets(){public String read(AiProviders.Provider p){return keys.getOrDefault(p,"");}public void save(AiProviders.Provider p,String value,java.util.function.BooleanSupplier allowed)throws Exception{if(!allowed.getAsBoolean())throw new InterruptedException();keys.put(p,value);}public void forget(AiProviders.Provider p){keys.remove(p);}public void forgetAll(){keys.clear();}};}
  @Override protected CompanionAgent createAgent(AiProviders.Provider provider){int[] stage={0};return new CompanionAgent(provider,(request,key)->{fakeCalls.add(provider);if(stage[0]++==0)return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id","ui_summary").put("type","function").put("function",new JSONObject().put("name","summarize_transactions").put("arguments","{}")))))));JSONArray messages=request.getJSONArray("messages");JSONObject evidence=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));JSONObject answer=new JSONObject().put("interpretation","These are recorded source totals, not an account balance.").put("facts",new JSONArray().put(new JSONObject().put("toolCallId","ui_summary").put("path","/amountsPence/moneyOutPence"))).put("evidenceRefs",evidence.getJSONArray("evidenceRefs")).put("nextSteps",new JSONArray());return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",answer.toString()))));});}

  @Override protected CompanionWorkspace createWorkspace(){return new CompanionWorkspace(new CompanionWorkspace.Store(){public JSONObject read()throws Exception{return new JSONObject(seed.toString());}public void save(JSONObject value)throws Exception{seed=new JSONObject(value.toString());}});}
 }
 private ActivityController<TestActivity> controller;
 private TestActivity activity;
 private View root;
 @Before public void open()throws Exception {
  keys.clear();fakeCalls.clear();seed=SecureStore.defaults();JSONArray transactions=new JSONArray();
  for(int i=0;i<1007;i++){String date=java.time.LocalDate.of(2020,1,1).plusDays(i).toString();transactions.put(new JSONObject().put("id","synthetic-ui-"+i).put("date",date).put("name",i==0?"Ancient bicycle":"Example shop "+i).put("category","Groceries").put("type","Card payment").put("amount",-100).put("raw",new JSONObject().put("Emoji",i==0?"🚲":"🛒").put("Notes and #tags",i==0?"Synthetic old note":"")));}
  seed.put("transactions",transactions).put("lastSync",java.time.Instant.now().toString());
  controller=Robolectric.buildActivity(TestActivity.class).create().start().resume().visible();activity=controller.get();root=activity.findViewById(android.R.id.content);await(()->contains(root,"1007 transactions"));
 }
 @After public void close(){if(controller!=null)controller.pause().stop().destroy();}
 @Test public void wholeHistoryAndOlderSearchAreReachable()throws Exception {
  assertTrue(contains(root,"Your whole history"));click(root,"Activity");await(()->contains(root,"Search"));
  EditText search=field(root,"Search names, categories, notes or pots");search.setText("Ancient bicycle");click(root,"Search history");await(()->!activity.historyLoading&&activity.historyPage!=null&&activity.historyPage.total==1&&contains(root,"🚲"));
  assertTrue(contains(root,"🚲"));assertFalse(activity.historyLoading);assertEquals(1,activity.historyPage.total);assertEquals("synthetic-ui-0",activity.historyPage.items.get(0).source.id);
 }
 @Test public void budgetCreationSavesWithoutFilteringHistory()throws Exception {
  click(root,"Plan");click(root,"Create a budget");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);Shadows.shadowOf(Looper.getMainLooper()).idle();
  field(dialog.getWindow().getDecorView(),"Category").setText("Transport");field(dialog.getWindow().getDecorView(),"Budget amount (£)").setText("125.50");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  await(()->seed.optJSONObject("companionPlan")!=null);assertEquals(12550,BudgetWorkspace.read(seed).budgets.get(0).limitPence);assertEquals(1007,seed.getJSONArray("transactions").length());assertTrue((dialog.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
 }
 @Test public void askRequiresConsentAndNeverSavesTheKeyInViewState()throws Exception {
  click(root,"Ask");EditText key=field(root,"OpenAI API key");key.setText("synthetic-test-key");EditText question=field(root,"What would you like help with?");question.setText("Help me understand recorded spending");
  Button ask=(Button)findText(root,"Ask OpenAI with local evidence");assertNotNull(ask);assertFalse(ask.isEnabled());assertFalse(key.isSaveEnabled());assertFalse(activity.askBusy);assertFalse(seed.has("companionApiKey"));
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
 @Test public void conversationalPlanRequiresReviewAndSingleConfirmationThenSeparateHandoffs()throws Exception {
  String original="Next month I want to buy a bicycle for £120";
  AlertDialog review=openConversation(original);View fields=review.getWindow().getDecorView();
  assertEquals("120",field(fields,"Amount (£)").getText().toString().replace(".00",""));
  field(fields,"Name this allocation").setText("Bicycle");field(fields,"Category").setText("Shopping");spinner(fields).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertFalse(seed.has("conversationBudgets"));
  review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();await(()->review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
  assertTrue(contains(fields,"cannot confirm affordability"));assertTrue(contains(fields,"Future income"));assertFalse(seed.has("conversationBudgets"));
  review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  await(()->seed.optJSONObject("conversationBudgets")!=null);await(()->latestDialogContains("Plan saved"));
  assertEquals(1,ConversationBudget.records(seed).length());assertEquals(12000,BudgetWorkspace.read(seed).budgets.get(0).limitPence);assertEquals(1007,seed.getJSONArray("transactions").length());
  assertEquals(original,ConversationBudget.decode(ConversationBudget.records(seed).getJSONObject(0).getJSONObject("proposal")).originalText);assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
  AlertDialog saved=ShadowAlertDialog.getLatestAlertDialog();click(saved.getWindow().getDecorView(),"Review calendar reminder");await(()->latestDialogContains("Review calendar draft"));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
  AlertDialog calendar=ShadowAlertDialog.getLatestAlertDialog();assertFalse(((CheckBox)findText(calendar.getWindow().getDecorView(),"Include the planning amount in this handoff")).isChecked());calendar.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
  click(saved.getWindow().getDecorView(),"Review notes draft");await(()->latestDialogContains("Review note text"));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());assertEquals(1,ConversationBudget.records(seed).length());
 }
 @Test public void savedPlansArePreservedButNotReusedUnderAnotherExport()throws Exception {
  AlertDialog review=openConversation("Next month buy a bicycle for £120");View fields=review.getWindow().getDecorView();field(fields,"Name this allocation").setText("Bicycle");field(fields,"Category").setText("Shopping");spinner(fields).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();await(()->review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->latestDialogContains("Plan saved"));ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  String saved=seed.getJSONObject("conversationBudgets").toString();seed.put("workspaceId","synthetic-replaced-source");activity.reload();await(()->contains(root,"belong to a different export"));assertEquals(saved,seed.getJSONObject("conversationBudgets").toString());assertEquals(1,ConversationBudget.records(seed).length());
 }
 @Test public void editingAReviewedConversationRequiresAnotherReview()throws Exception {
  AlertDialog review=openConversation("Next month I might buy a bicycle for £120");View fields=review.getWindow().getDecorView();field(fields,"Name this allocation").setText("Bicycle");field(fields,"Category").setText("Shopping");spinner(fields).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();
  review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();await(()->review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());field(fields,"Amount (£)").setText("150.00");assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertFalse(seed.has("conversationBudgets"));
 }
 @Test public void unknownConversationValuesStayBlankAndVoiceHasAnHonestBoundary()throws Exception {
  click(root,"Plan");click(root,"Plan from a message");Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog input=ShadowAlertDialog.getLatestAlertDialog();click(input.getWindow().getDecorView(),"Speak a draft");assertTrue(latestDialogContains("On-device voice unavailable"));assertFalse(seed.has("conversationBudgets"));ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  field(input.getWindow().getDecorView(),"Your plan, in your own words").setText("I might buy a bicycle sometime");input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->latestDialogContains("Review your proposed plan"));AlertDialog review=ShadowAlertDialog.getLatestAlertDialog();Shadows.shadowOf(Looper.getMainLooper()).idle();View fields=review.getWindow().getDecorView();assertEquals("",field(fields,"Amount (£)").getText().toString());assertEquals("",field(fields,"First day (included) (yyyy-MM-dd)").getText().toString());assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
 }
 @Test public void aChangedSourceCannotApplyAnOldConversationReview()throws Exception {
  AlertDialog review=openConversation("Next month buy a bicycle for £120");View fields=review.getWindow().getDecorView();field(fields,"Name this allocation").setText("Bicycle");field(fields,"Category").setText("Shopping");spinner(fields).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();await(()->review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
  seed.put("workspaceId","synthetic-other-workspace");review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->contains(fields,"source or plan changed"));assertFalse(seed.has("conversationBudgets"));assertEquals("Bicycle",field(fields,"Name this allocation").getText().toString());assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
 }
 private AlertDialog openConversation(String message)throws Exception{click(root,"Plan");click(root,"Plan from a message");Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog input=ShadowAlertDialog.getLatestAlertDialog();field(input.getWindow().getDecorView(),"Your plan, in your own words").setText(message);input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->latestDialogContains("Review your proposed plan"));Shadows.shadowOf(Looper.getMainLooper()).idle();return ShadowAlertDialog.getLatestAlertDialog();}
 private static boolean latestDialogContains(String text){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();return dialog!=null&&contains(dialog.getWindow().getDecorView(),text);}
 private static Spinner spinner(View view){if(view instanceof Spinner)return (Spinner)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Spinner found=spinner(group.getChildAt(i));if(found!=null)return found;}}return null;}
 @Test public void switchingProviderClearsKeyAndRequiresNamedFreshConsent()throws Exception {
  click(root,"Ask");EditText oldKey=field(root,"OpenAI API key");oldKey.setText("synthetic-unsaved-openai");field(root,"What would you like help with?").setText("Explain my recorded spending");((CheckBox)findText(root,"Allow this question and selected financial facts to be processed by OpenAI")).setChecked(true);assertTrue(findText(root,"Ask OpenAI with local evidence").isEnabled());
  spinner(root).setSelection(1);await(()->activity.activeAiProvider()==AiProviders.Provider.ANTHROPIC&&!activity.providerKeyLoading&&field(root,"Anthropic API key")!=null);assertEquals("",oldKey.getText().toString());assertEquals("",field(root,"Anthropic API key").getText().toString());assertFalse(((CheckBox)findText(root,"Allow this question and selected financial facts to be processed by Anthropic")).isChecked());assertFalse(findText(root,"Ask Anthropic with local evidence").isEnabled());assertTrue(fakeCalls.isEmpty());
 }
 @Test public void rememberedProviderKeysStaySeparateAndCanBeForgottenIndividually()throws Exception {
  keys.put(AiProviders.Provider.OPENAI,"synthetic-remembered-openai");keys.put(AiProviders.Provider.ANTHROPIC,"synthetic-remembered-anthropic");activity.reload();await(()->activity.recalledKey().equals("synthetic-remembered-openai"));click(root,"Ask");assertEquals("synthetic-remembered-openai",field(root,"OpenAI API key").getText().toString());spinner(root).setSelection(1);await(()->field(root,"Anthropic API key")!=null&&field(root,"Anthropic API key").getText().toString().equals("synthetic-remembered-anthropic"));click(root,"Forget saved Anthropic key");await(()->!keys.containsKey(AiProviders.Provider.ANTHROPIC));assertEquals("synthetic-remembered-openai",keys.get(AiProviders.Provider.OPENAI));spinner(root).setSelection(0);await(()->field(root,"OpenAI API key")!=null&&field(root,"OpenAI API key").getText().toString().equals("synthetic-remembered-openai"));click(root,"Settings");click(root,"Forget all provider keys");await(keys::isEmpty);assertTrue(fakeCalls.isEmpty());
 }
 @Test public void providerModelsAndAcceptedAnswersKeepTheirOwnIdentity()throws Exception {
  click(root,"Ask");submitFakeQuestion(AiProviders.Provider.OPENAI,"synthetic-openai-model","synthetic-openai-secret");assertEquals("OpenAI",activity.lastAnswerProvider);assertEquals("synthetic-openai-model",activity.lastAnswerModel);spinner(root).setSelection(1);await(()->!activity.providerKeyLoading&&field(root,"Anthropic API key")!=null);assertNull(activity.lastAnswer);submitFakeQuestion(AiProviders.Provider.ANTHROPIC,"synthetic-anthropic-model","synthetic-anthropic-secret");assertEquals("Anthropic",activity.lastAnswerProvider);assertEquals("synthetic-anthropic-model",activity.lastAnswerModel);JSONObject models=seed.getJSONObject("companionModels");assertEquals("synthetic-openai-model",models.getString("openai"));assertEquals("synthetic-anthropic-model",models.getString("anthropic"));assertFalse(seed.toString().contains("synthetic-openai-secret"));assertFalse(seed.toString().contains("synthetic-anthropic-secret"));assertEquals("synthetic-openai-secret",keys.get(AiProviders.Provider.OPENAI));assertEquals("synthetic-anthropic-secret",keys.get(AiProviders.Provider.ANTHROPIC));assertEquals(4,fakeCalls.size());
 }
 @Test public void changingTheModelOrSharingScopeRevokesProcessingConsent()throws Exception {
  click(root,"Ask");field(root,"OpenAI API key").setText("synthetic-key");field(root,"What would you like help with?").setText("Explain recorded totals");CheckBox consent=(CheckBox)findText(root,"Allow this question and selected financial facts to be processed by OpenAI");consent.setChecked(true);field(root,"OpenAI model ID — editable").setText("another-model");assertFalse(consent.isChecked());consent.setChecked(true);((CheckBox)findText(root,"Also share merchant, category, type and user-plan labels with OpenAI")).setChecked(true);assertFalse(consent.isChecked());assertTrue(fakeCalls.isEmpty());
 }
 private void submitFakeQuestion(AiProviders.Provider provider,String model,String key)throws Exception {
  await(()->!activity.providerKeyLoading);
  field(root,provider.displayName+" API key").setText(key);field(root,provider.displayName+" model ID — editable").setText(model);field(root,"What would you like help with?").setText("Explain my recorded totals");((CheckBox)findText(root,"Remember this "+provider.displayName+" key in encrypted storage on this device")).setChecked(true);((CheckBox)findText(root,"Allow this question and selected financial facts to be processed by "+provider.displayName)).setChecked(true);click(root,"Ask "+provider.displayName+" with local evidence");await(()->!activity.askBusy&&activity.lastAnswer!=null);assertTrue(activity.lastAnswer.complete);
 }
 private interface Condition{boolean done()throws Exception;}
 private static void await(Condition condition)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(condition.done())return;Thread.sleep(10);}throw new AssertionError("Android interaction did not settle");}
 private static boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(contains(group.getChildAt(i),text))return true;}return false;}
 private static TextView findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView match=findText(group.getChildAt(i),text);if(match!=null)return match;}}return null;}
 private static EditText field(View view,String description){if(view instanceof EditText&&description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText match=field(group.getChildAt(i),description);if(match!=null)return match;}}return null;}
 private static void click(View root,String text){TextView target=findText(root,text);assertNotNull("Missing control: "+text,target);target.performClick();}
}
