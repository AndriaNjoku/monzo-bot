package org.monzobot.app;

import org.json.*;
import java.time.*;
import java.util.*;

/** Local notification ledger. No network, financial actions, or automatic AI calls. */
public final class CompanionDelivery {
 private CompanionDelivery() {}
 public static final int MAX_SENT=256;
 public interface Publisher { boolean post(CompanionAlertEngine.Candidate candidate) throws Exception; }
 public static final class Outcome {
  public final JSONObject ledger;
  public final String postedKey;
  private Outcome(JSONObject ledger,String key){this.ledger=ledger;postedKey=key;}
 }
 /** Input is not mutated. A blocked/failed delivery never consumes a candidate. */
 public static Outcome deliver(List<CompanionAlertEngine.Candidate> candidates,JSONObject previous,
   Instant now,ZoneId zone,boolean enabled,boolean permission,int quietStart,int quietEnd,Publisher publisher)throws Exception {
  Objects.requireNonNull(candidates);Objects.requireNonNull(now);Objects.requireNonNull(zone);Objects.requireNonNull(publisher);
  JSONObject ledger=new JSONObject(previous.toString());
  long millis=now.toEpochMilli();ZonedDateTime local=now.atZone(zone);String day=local.toLocalDate().toString();
  int count=day.equals(ledger.optString("day"))?ledger.optInt("count",0):0;
  if(!NotificationPolicy.due(millis,ledger.optLong("lastSent",0),count,enabled,permission,local.getHour(),quietStart,quietEnd))return new Outcome(ledger,"");
  JSONObject sent=ledger.optJSONObject("sent");sent=sent==null?new JSONObject():new JSONObject(sent.toString());
  List<String> keys=new ArrayList<>();Iterator<String> it=sent.keys();while(it.hasNext())keys.add(it.next());
  for(String key:keys){long timestamp=sent.optLong(key,-1);if(timestamp<0||timestamp>millis||millis-timestamp>Duration.ofDays(30).toMillis())sent.remove(key);}
  keys.clear();it=sent.keys();while(it.hasNext())keys.add(it.next());
  final JSONObject timestamps=sent;keys.sort(Comparator.comparingLong(k->timestamps.optLong(k)));
  while(keys.size()>=MAX_SENT)sent.remove(keys.remove(0));
  List<CompanionAlertEngine.Candidate> ordered=new ArrayList<>(candidates);ordered.sort(Comparator.comparingInt((CompanionAlertEngine.Candidate c)->c.priority).reversed().thenComparing(c->c.key));
  for(CompanionAlertEngine.Candidate candidate:ordered){
   if(sent.has(candidate.key))continue;
   if(!publisher.post(candidate))return new Outcome(ledger,"");
   sent.put(candidate.key,millis);ledger.put("sent",sent).put("lastSent",millis).put("day",day).put("count",count+1);
   return new Outcome(ledger,candidate.key);
  }
  return new Outcome(ledger,"");
 }
}
