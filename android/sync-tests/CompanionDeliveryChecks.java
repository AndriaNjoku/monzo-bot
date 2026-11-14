package org.monzobot.app;
import org.json.*;
import java.time.*;
import java.util.*;

public final class CompanionDeliveryChecks {
 private static int checks;
 private static void ok(boolean value){checks++;if(!value)throw new AssertionError("Delivery check "+checks);}
 public static void main(String[] args)throws Exception {
  Instant now=Instant.parse("2027-04-09T12:00:00Z");ZoneId zone=ZoneId.of("Europe/London");
  List<Finance.Transaction> rows=List.of(new Finance.Transaction("synthetic-delivery-source","2027-04-09","Example private shop","Example category","Card payment","GBP",-9000,Map.of()));
  CompanionPlanner.Plan plan=new CompanionPlanner.Plan(List.of(new CompanionPlanner.Budget("Example category","2027-04-01","2027-04-30",10000,false)),List.of(),null,"",0,false,List.of());
  List<CompanionAlertEngine.Candidate> candidates=CompanionAlertEngine.evaluate(rows,Map.of(),plan,now.atZone(zone).toLocalDate(),now,now.toString(),new CompanionAlertEngine.Settings(80,false,List.of("synthetic-delivery-source")));
  ok(!candidates.isEmpty());int[] posts={0};CompanionDelivery.Publisher publish=c->{posts[0]++;ok(!c.title.contains("Example"));ok(!c.body.contains("9000"));ok(!c.key.contains("synthetic"));return true;};
  JSONObject empty=new JSONObject();CompanionDelivery.Outcome first=CompanionDelivery.deliver(candidates,empty,now,zone,true,true,21,8,publish);
  ok(posts[0]==1);ok(empty.length()==0);ok(!first.postedKey.isEmpty());ok(first.ledger.getInt("count")==1);ok(first.ledger.getJSONObject("sent").length()==1);
  CompanionDelivery.Outcome repeat=CompanionDelivery.deliver(candidates,first.ledger,now.plus(Duration.ofHours(7)),zone,true,true,21,8,publish);ok(repeat.postedKey.isEmpty());ok(posts[0]==1);
  for(int scenario=0;scenario<5;scenario++){
   JSONObject ledger=new JSONObject();Instant at=now;boolean enabled=true,permission=true;
   if(scenario==0)enabled=false;if(scenario==1)permission=false;if(scenario==2)at=Instant.parse("2027-04-09T22:00:00Z");
   if(scenario==3)ledger.put("lastSent",now.minusSeconds(1).toEpochMilli());
   if(scenario==4)ledger.put("day",now.atZone(zone).toLocalDate().toString()).put("count",3);
   CompanionDelivery.Outcome blocked=CompanionDelivery.deliver(candidates,ledger,at,zone,enabled,permission,21,8,publish);ok(blocked.postedKey.isEmpty());ok(posts[0]==1);
  }
  CompanionDelivery.Outcome refused=CompanionDelivery.deliver(candidates,empty,now,zone,true,true,21,8,c->false);ok(refused.postedKey.isEmpty());ok(refused.ledger.length()==0);
  boolean threw=false;try{CompanionDelivery.deliver(candidates,empty,now,zone,true,true,21,8,c->{throw new java.io.IOException("synthetic OS refusal");});}catch(java.io.IOException expected){threw=true;}ok(threw);ok(empty.length()==0);
  JSONObject priorDay=new JSONObject().put("day","2027-04-08").put("count",3).put("lastSent",now.minus(Duration.ofDays(1)).toEpochMilli());
  CompanionDelivery.Outcome reset=CompanionDelivery.deliver(candidates,priorDay,now,zone,true,true,21,8,c->true);ok(reset.ledger.getInt("count")==1);ok(priorDay.getInt("count")==3);
  JSONObject many=new JSONObject();for(int i=0;i<300;i++)many.put("opaque"+i,now.minusSeconds(i+1).toEpochMilli());many.put("expired",now.minus(Duration.ofDays(31)).toEpochMilli());
  CompanionDelivery.Outcome bounded=CompanionDelivery.deliver(candidates,new JSONObject().put("sent",many),now,zone,true,true,21,8,c->true);ok(bounded.ledger.getJSONObject("sent").length()<=CompanionDelivery.MAX_SENT);ok(!bounded.ledger.getJSONObject("sent").has("expired"));
  JSONObject rollback=new JSONObject().put("lastSent",now.plusSeconds(10).toEpochMilli());ok(CompanionDelivery.deliver(candidates,rollback,now,zone,true,true,21,8,c->true).postedKey.isEmpty());
  // A UTC evening is already inside quiet hours in the user's local zone.
  ok(CompanionDelivery.deliver(candidates,empty,Instant.parse("2027-04-09T20:30:00Z"),zone,true,true,21,8,c->true).postedKey.isEmpty());
  System.out.println("Companion delivery: "+checks+" checks passed; fake OS publisher only");
 }
}
