package org.monzobot.app;

import android.app.AlertDialog;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** Host-only Android interactions. Synthetic stores and gateways; no provider, microphone or Keystore IO. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w393dp-h852dp")
public class ConversationAiUiTest {
 private static final String MESSAGE="Next month I might buy a bicycle for £120 in Shopping";
 private static volatile JSONObject seed,response,request;
 private static volatile AiProviders.Provider calledProvider;
 private static final AtomicInteger calls=new AtomicInteger();
 private static final Map<AiProviders.Provider,String> keys=Collections.synchronizedMap(new EnumMap<>(AiProviders.Provider.class));
 private static volatile CountDownLatch entered,release,returned;
 private static volatile boolean block;
 private static View diagnosticRoot;
 public static class TestActivity extends CompanionActivity {
  @Override protected boolean planVoiceAvailable(){return false;}
  @Override protected ProviderSecrets createProviderSecrets(){return new ProviderSecrets(){
   public String read(AiProviders.Provider p){return keys.getOrDefault(p,"");}
   public void save(AiProviders.Provider p,String value,java.util.function.BooleanSupplier allowed)throws Exception{if(!allowed.getAsBoolean())throw new InterruptedException();keys.put(p,value);}
   public void forget(AiProviders.Provider p){keys.remove(p);}
   public void forgetAll(){keys.clear();}
  };}
  @Override protected CompanionWorkspace createWorkspace(){return workspace();}
  @Override protected ConversationAiPlanner createAiPlanner(AiProviders.Provider provider){return new ConversationAiPlanner(provider,(body,key)->{
   request=new JSONObject(body.toString());calledProvider=provider;calls.incrementAndGet();entered.countDown();
   if(block){boolean waiting=true;while(waiting){try{waiting=!release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* Simulate an in-flight transport which cannot be unsent. */}}}
   returned.countDown();return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",response.toString()))));
  });}
 }
 private ActivityController<TestActivity> controller;
 private TestActivity activity;
 private View root;
 private static CompanionWorkspace workspace(){return new CompanionWorkspace(new CompanionWorkspace.Store(){public JSONObject read()throws Exception{return new JSONObject(seed.toString());}public void save(JSONObject value)throws Exception{seed=new JSONObject(value.toString());}});}
 @Before public void open()throws Exception {
  keys.clear();calls.set(0);request=null;calledProvider=null;block=false;entered=new CountDownLatch(1);release=new CountDownLatch(1);returned=new CountDownLatch(1);
  seed=SecureStore.defaults().put("lastSync",java.time.Instant.now().toString()).put("transactions",new JSONArray().put(new JSONObject().put("id","synthetic-hidden-source").put("date","2020-01-01").put("name","Synthetic hidden merchant").put("category","Groceries").put("type","Card payment").put("amount",-7341).put("raw",new JSONObject().put("Emoji","🛒"))));
  setResponse(MESSAGE);
  controller=Robolectric.buildActivity(TestActivity.class).create().start().resume().visible();activity=controller.get();root=activity.findViewById(android.R.id.content);diagnosticRoot=root;await(()->contains(root,"1 transactions"));
 }
 @After public void close(){release.countDown();if(controller!=null)controller.pause().stop().destroy();}
 private static void setResponse(String original)throws Exception {ConversationBudget.Draft parsed=ConversationBudget.parse(original,LocalDate.now());response=new JSONObject().put("tentative",true).put("name",original.contains("bicycle")?"bicycle":"").put("category",original.contains("Shopping")?"Shopping":"").put("amountPence",parsed.amountPence==null?JSONObject.NULL:parsed.amountPence).put("from",parsed.from==null?JSONObject.NULL:parsed.from).put("to",parsed.to==null?JSONObject.NULL:parsed.to);}

 @Test public void aiRequiresNamedConsentAndFieldEditsRevokeIt()throws Exception {
  AlertDialog input=openAi(MESSAGE);View form=input.getWindow().getDecorView();EditText key=field(form,"OpenAI API key"),model=model(form);key.setText("synthetic-ui-key");
  CheckBox consent=consent(form);assertTrue(consent.getText().toString().contains("OpenAI"));assertFalse(consent.isChecked());assertFalse(input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(0,calls.get());assertFalse(key.isSaveEnabled());assertFalse(key.isSaveFromParentEnabled());
  consent.setChecked(true);assertTrue(input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());model.setText("synthetic-model");assertFalse(consent.isChecked());
  consent.setChecked(true);key.setText("synthetic-edited-key");assertFalse(consent.isChecked());
  consent.setChecked(true);field(form,"Planning message to send").setText(MESSAGE+" please");assertFalse(consent.isChecked());assertEquals(0,calls.get());assertFalse(seed.has("conversationBudgets"));
 }
 @Test public void oneProviderRequestPreparesAnEditablePlanButDoesNotSaveIt()throws Exception {
  AlertDialog input=openAi(MESSAGE);View form=input.getWindow().getDecorView();field(form,"OpenAI API key").setText("synthetic-private-key");model(form).setText("synthetic-model");consent(form).setChecked(true);input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  await(()->latestContains("Review your proposed plan"));AlertDialog review=ShadowAlertDialog.getLatestAlertDialog();View fields=review.getWindow().getDecorView();assertEquals(1,calls.get());assertEquals(AiProviders.Provider.OPENAI,calledProvider);assertTrue(contains(fields,MESSAGE));assertEquals("bicycle",field(fields,"Name this allocation").getText().toString());assertEquals("Shopping",field(fields,"Category").getText().toString());assertEquals("120.00",field(fields,"Amount (£)").getText().toString());
  assertFalse(seed.has("conversationBudgets"));assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());String sent=request.toString();assertFalse(sent.contains("Synthetic hidden merchant"));assertFalse(sent.contains("synthetic-hidden-source"));assertFalse(sent.contains("7341"));assertFalse(sent.contains("synthetic-private-key"));JSONObject payload=new JSONObject(request.getJSONArray("messages").getJSONObject(1).getString("content"));assertEquals(MESSAGE,payload.getString("message"));assertEquals(LocalDate.now().toString(),payload.getString("referenceDate"));assertEquals(2,payload.length());
  spinner(fields).setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();review.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();await(()->review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());assertTrue(contains(fields,"cannot confirm affordability"));assertFalse(seed.has("conversationBudgets"));
  review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();review.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->seed.has("conversationBudgets"));assertEquals(1,ConversationBudget.records(seed).length());assertEquals(12000,BudgetWorkspace.read(seed).budgets.get(0).limitPence);assertEquals(1,seed.getJSONArray("transactions").length());assertEquals(MESSAGE,ConversationBudget.decode(ConversationBudget.records(seed).getJSONObject(0).getJSONObject("proposal")).originalText);assertFalse(seed.toString().contains("synthetic-private-key"));assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
 }
 @Test public void anthropicPlanningUsesFreshNamedConsentAndItsOwnKey()throws Exception {
  keys.put(AiProviders.Provider.OPENAI,"synthetic-other-provider-key");activity.selectAiProvider(AiProviders.Provider.ANTHROPIC);await(()->!activity.providerKeyLoading&&activity.activeAiProvider()==AiProviders.Provider.ANTHROPIC);
  AlertDialog input=openAi(MESSAGE);View form=input.getWindow().getDecorView();assertTrue(contains(form,"Anthropic"));assertTrue(contains(form,"Imported transactions, balances, budgets"));assertEquals("",field(form,"Anthropic API key").getText().toString());field(form,"Anthropic API key").setText("synthetic-anthropic-key");field(form,"Anthropic model ID").setText("synthetic-anthropic-model");CheckBox consent=consent(form);assertEquals("Allow this planning message and reference date to be processed by Anthropic",consent.getText().toString());assertFalse(consent.isChecked());assertFalse(input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());consent.setChecked(true);input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
  await(()->latestContains("Review your proposed plan"));assertEquals(1,calls.get());assertEquals(AiProviders.Provider.ANTHROPIC,calledProvider);assertEquals("synthetic-anthropic-model",request.getString("model"));assertEquals("synthetic-other-provider-key",keys.get(AiProviders.Provider.OPENAI));assertFalse(request.toString().contains("synthetic-other-provider-key"));assertFalse(request.toString().contains("synthetic-anthropic-key"));assertFalse(seed.has("conversationBudgets"));assertFalse(ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
 }
 @Test public void unknownAiFieldsStayUnresolvedAndOfflineReviewMakesNoRequest()throws Exception {
  String unknown="I might buy a bicycle sometime";setResponse(unknown);AlertDialog input=openAi(unknown);View form=input.getWindow().getDecorView();field(form,"OpenAI API key").setText("synthetic-key");consent(form).setChecked(true);input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->latestContains("Review your proposed plan"));AlertDialog review=ShadowAlertDialog.getLatestAlertDialog();View fields=review.getWindow().getDecorView();assertEquals("",field(fields,"Amount (£)").getText().toString());assertEquals("",field(fields,"First day (included) (yyyy-MM-dd)").getText().toString());assertFalse(review.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());review.dismiss();
  click(root,"Plan");click(root,"Plan from a message");Shadows.shadowOf(Looper.getMainLooper()).idle();AlertDialog offline=ShadowAlertDialog.getLatestAlertDialog();field(offline.getWindow().getDecorView(),"Your plan, in your own words").setText(MESSAGE);offline.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->latestContains("Review your proposed plan"));assertEquals(1,calls.get());assertFalse(seed.has("conversationBudgets"));
 }
 @Test public void expiredDisclosedReferenceDateStopsBeforeRequestsOrCredentialChanges()throws Exception {
  keys.put(AiProviders.Provider.OPENAI,"synthetic-preserved-key");String originalStore=seed.toString();String[] failure={null};AtomicInteger accepted=new AtomicInteger();
  activity.previewAiConversation(workspace().load(),AiProviders.Provider.OPENAI,"synthetic-replacement-key","synthetic-model",MESSAGE,true,new CompanionAgent.Consent(AiProviders.Provider.OPENAI,true,false),LocalDate.now().minusDays(1),result->accepted.incrementAndGet(),message->failure[0]=message);
  assertNotNull(failure[0]);assertTrue(failure[0].contains("reference date changed"));assertEquals(0,calls.get());assertEquals(0,accepted.get());assertEquals(originalStore,seed.toString());assertEquals("synthetic-preserved-key",keys.get(AiProviders.Provider.OPENAI));assertFalse(activity.aiPlanBusy);assertFalse(seed.has("conversationBudgets"));
 }
 @Test public void changedSourceRejectsAnInFlightProposal()throws Exception {
  AlertDialog input=startBlocked();seed.put("workspaceId","synthetic-other-workspace");release.countDown();await(()->contains(input.getWindow().getDecorView(),"source, plan or reference day changed"));assertFalse(latestContains("Review your proposed plan"));assertFalse(seed.has("conversationBudgets"));assertEquals(1,calls.get());
 }
 @Test public void switchingProviderDiscardsAnInFlightProposal()throws Exception {
  AlertDialog input=startBlocked();activity.selectAiProvider(AiProviders.Provider.ANTHROPIC);release.countDown();awaitReturned();assertFalse(input.isShowing());assertFalse(latestContains("Review your proposed plan"));assertFalse(seed.has("conversationBudgets"));assertEquals(AiProviders.Provider.OPENAI,calledProvider);assertEquals(1,calls.get());
 }
 @Test public void dismissingAiDialogDiscardsAnInFlightProposal()throws Exception {
  AlertDialog input=startBlocked();input.dismiss();release.countDown();awaitReturned();assertFalse(latestContains("Review your proposed plan"));assertFalse(seed.has("conversationBudgets"));assertEquals(1,calls.get());
 }
 @Test public void stoppingActivityDiscardsAnInFlightProposal()throws Exception {
  AlertDialog input=startBlocked();controller.pause().stop();release.countDown();awaitReturned();assertFalse(input.isShowing());assertFalse(latestContains("Review your proposed plan"));assertFalse(seed.has("conversationBudgets"));assertEquals(1,calls.get());controller.destroy();controller=null;
 }
 private AlertDialog startBlocked()throws Exception {block=true;AlertDialog input=openAi(MESSAGE);View form=input.getWindow().getDecorView();field(form,"OpenAI API key").setText("synthetic-key");consent(form).setChecked(true);input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();await(()->entered.getCount()==0);return input;}
 private void awaitReturned()throws Exception {
  await(()->returned.getCount()==0);
  // A barrier on the Activity's serial worker proves the canceled request has exited before
  // asserting absence of a late callback; elapsed time alone would permit false positives.
  java.lang.reflect.Field worker=CompanionActivity.class.getDeclaredField("agentIo");worker.setAccessible(true);
  ((ExecutorService)worker.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);
  Shadows.shadowOf(Looper.getMainLooper()).idle();
 }
 private AlertDialog openAi(String message)throws Exception {click(root,"Plan");click(root,"Plan from a message");AlertDialog local=ShadowAlertDialog.getLatestAlertDialog();field(local.getWindow().getDecorView(),"Your plan, in your own words").setText(message);click(local.getWindow().getDecorView(),"Use AI to prepare draft");Shadows.shadowOf(Looper.getMainLooper()).idle();return ShadowAlertDialog.getLatestAlertDialog();}
 private static EditText model(View root){EditText f=field(root,"OpenAI model ID");assertNotNull("Missing model field",f);return f;}
 private static CheckBox consent(View view){if(view instanceof CheckBox&&((CheckBox)view).getText().toString().startsWith("Allow"))return (CheckBox)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){CheckBox f=consent(group.getChildAt(i));if(f!=null)return f;}}return null;}
 private interface Condition{boolean done()throws Exception;}
 private static void await(Condition condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();if(condition.done())return;Thread.sleep(10);}throw new AssertionError("Synthetic AI UI interaction did not settle; calls="+calls.get()+"; main="+diagnosticText(diagnosticRoot)+"; dialog="+diagnosticText(ShadowAlertDialog.getLatestAlertDialog()==null?null:ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView()));}
 private static String diagnosticText(View view){if(view==null)return "<none>";if(view instanceof EditText)return "<editable field>";StringBuilder result=new StringBuilder();if(view instanceof TextView)result.append(((TextView)view).getText()).append(" | ");if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)result.append(diagnosticText(group.getChildAt(i)));}return result.toString();}
 private static boolean latestContains(String text){AlertDialog d=ShadowAlertDialog.getLatestAlertDialog();return d!=null&&d.isShowing()&&contains(d.getWindow().getDecorView(),text);}
 private static boolean contains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)if(contains(g.getChildAt(i),text))return true;}return false;}
 private static TextView findText(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return (TextView)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){TextView found=findText(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
 private static EditText field(View view,String description){if(view instanceof EditText&&description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){EditText found=field(g.getChildAt(i),description);if(found!=null)return found;}}return null;}
 private static Spinner spinner(View view){if(view instanceof Spinner)return (Spinner)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Spinner found=spinner(g.getChildAt(i));if(found!=null)return found;}}return null;}
 private static void click(View root,String text){TextView target=findText(root,text);assertNotNull("Missing control: "+text,target);target.performClick();}
}
