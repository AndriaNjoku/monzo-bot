package org.monzobot.app;

import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source-revision checked edits to the encrypted local workspace. */
public final class CompanionWorkspace {
 public interface Store {JSONObject read()throws Exception;void save(JSONObject data)throws Exception;}
 public interface Mutation {JSONObject apply(JSONObject current)throws Exception;}
 private final Store store;
 public CompanionWorkspace(Context context){SecureStore secure=new SecureStore(context);store=new Store(){public JSONObject read()throws Exception{return secure.load();}public void save(JSONObject data)throws Exception{secure.save(data);}};}
 CompanionWorkspace(Store store){this.store=Objects.requireNonNull(store);}
 public static final class Snapshot {
  public final JSONObject data;
  public final List<Finance.Transaction> history;
  public final Map<String,String> overrides,emojiOverrides;
  public final CompanionPlanner.Plan plan;
  public final CompanionInsights.Overview overview;
  public final JSONArray opportunities;
  public final List<CompanionPlanner.BudgetStatus> budgetStatuses;
  public final CompanionInsights.ActivitySession activitySession;
  public final CompanionInsights.ActivityPage initialPage;
  public final List<CompanionAlertEngine.Candidate> checkIns;
  public final List<String> categoryNames;
  public final String revision;
  private Snapshot(JSONObject source)throws Exception {
   data=new JSONObject(source.toString());revision=revision(data);
   history=Collections.unmodifiableList(SecureStore.transactions(data));
   overrides=Collections.unmodifiableMap(SecureStore.overrides(data));
   Map<String,String> icons=new LinkedHashMap<>();JSONObject raw=data.optJSONObject("categoryEmojiOverrides");
   if(raw!=null){Iterator<String> keys=raw.keys();while(keys.hasNext()){String key=keys.next();icons.put(key,CategoryPresentation.validateEmoji(raw.getString(key)));}}
   Set<String> categories=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);for(Finance.Transaction row:history){String category=overrides.get(row.id);categories.add(CategoryPresentation.categoryLabel(category==null||category.trim().isEmpty()?row.category:category));}categoryNames=Collections.unmodifiableList(new ArrayList<>(categories));
   emojiOverrides=Collections.unmodifiableMap(icons);plan=BudgetWorkspace.read(data);overview=CompanionInsights.overview(history,overrides);
   opportunities=CompanionOpportunities.cards(history,overrides,java.time.LocalDate.now(),plan,data.optString("lastSync"),false);
   budgetStatuses=CompanionPlanner.budgetStatus(history,overrides,plan,java.time.LocalDate.now());
   activitySession=CompanionInsights.browse(history,overrides);initialPage=activitySession.page("",0,50);
   Set<String> pending=new HashSet<>();JSONObject ledger=data.optJSONObject("companionAlertLedger");JSONObject hashes=ledger==null?null:ledger.optJSONObject("pending");if(hashes!=null){Iterator<String> keys=hashes.keys();while(keys.hasNext())pending.add(keys.next());}
   List<String> delta=new ArrayList<>();for(Finance.Transaction row:history)if(pending.contains(CompanionAlertEngine.evidenceRef(row.id)))delta.add(row.id);
   JSONObject config=data.optJSONObject("companionNotifications");int threshold=config==null?80:config.optInt("thresholdPercent",80);
   checkIns=CompanionAlertEngine.evaluate(history,overrides,plan,java.time.LocalDate.now(),java.time.Instant.now(),data.optString("lastSync"),new CompanionAlertEngine.Settings(threshold,false,delta));
  }
 }
 public Snapshot load()throws Exception{synchronized(SecureStore.LOCK){return new Snapshot(store.read());}}
 public Snapshot mutate(String expectedRevision,Mutation mutation)throws Exception {
  synchronized(SecureStore.LOCK){JSONObject current=store.read();if(!revision(current).equals(expectedRevision))throw new IllegalStateException("Your data changed. Review the refreshed screen and try again.");JSONObject edited=Objects.requireNonNull(mutation.apply(new JSONObject(current.toString())),"edited workspace");Snapshot checked=new Snapshot(edited);store.save(checked.data);return checked;}
 }
 /** Excludes delivery ledgers so a successful background notification does not invalidate a form. */
 static String revision(JSONObject data)throws Exception {
  MessageDigest digest=MessageDigest.getInstance("SHA-256");
  for(String key:new String[]{"workspaceId","mode","spreadsheetId","authorized","lastSync","transactions","overrides","companionPlan","budgets","start","end","categoryEmojiOverrides"}){
   byte[] value=(key+"\u0000"+canonical(data.opt(key))+"\u0000").getBytes(StandardCharsets.UTF_8);digest.update(value);
  }
  StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",value&255));return hex.toString();
 }
 private static String canonical(Object value)throws Exception {
  if(value==null||value==JSONObject.NULL)return "null";
  if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys)out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key))).append(',');return out.append('}').toString();}
  if(value instanceof JSONArray){JSONArray a=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<a.length();i++)out.append(canonical(a.get(i))).append(',');return out.append(']').toString();}
  return value instanceof String?JSONObject.quote((String)value):value.toString();
 }
}
