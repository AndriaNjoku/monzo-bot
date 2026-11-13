package org.monzobot.app;
import org.json.*;
import java.time.LocalDate;
import java.util.*;
import java.io.IOException;

public final class CompanionAgentChecks {
 static int checks;
 static void ok(boolean value){checks++;if(!value)throw new AssertionError("Companion agent check failed "+checks);}
 interface Throwing {void run()throws Exception;}
 static void rejects(Throwing r)throws Exception{checks++;try{r.run();}catch(IllegalArgumentException|IOException expected){return;}throw new AssertionError("Expected rejection");}
 static JSONObject tools(String id,String name,String arguments)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("tool_calls",new JSONArray().put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",arguments)))))));}
 static JSONObject answer(String content)throws Exception{return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("content",content))));}
 static JSONObject grounded(String interpretation,String id,String path,JSONArray refs)throws Exception{
  return answer(new JSONObject().put("interpretation",interpretation).put("facts",new JSONArray().put(new JSONObject().put("toolCallId",id).put("path",path))).put("evidenceRefs",refs).put("nextSteps",new JSONArray().put("Review flexible spending before changing the essentials you have chosen to protect.")).toString());
 }
 static List<Finance.Transaction> fixture(){Map<String,String> raw=new LinkedHashMap<>();raw.put("Emoji","🍝🦘");raw.put("Notes and #tags","synthetic private note");raw.put("Address","Synthetic private address");return Collections.singletonList(new Finance.Transaction("synthetic-source-id","2021-02-03","Synthetic Cafe","Synthetic Category","Card payment","GBP",-1234,raw));}
 static final LocalDate TODAY=LocalDate.of(2027,4,9);
 static final String RETRIEVED="2027-04-09T12:00:00Z";
 public static void main(String[] args)throws Exception{
  int[] called={0};CompanionAgent never=new CompanionAgent((body,key)->{called[0]++;return answer("ungrounded");});
  rejects(()->never.answer("unit-test-key","test-model","Summarize",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(false,false),3));ok(called[0]==0);
  rejects(()->never.answer("bad\nheader","test-model","Summarize",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));
  rejects(()->never.answer("key","bad model","Summarize",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));
  rejects(()->never.answer("key","model","",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));
  rejects(()->never.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),5));
  rejects(()->never.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(called[0]==1);
  List<JSONObject> requests=new ArrayList<>();String secret="unit-test-key-only-header";
  CompanionAgent agent=new CompanionAgent((request,key)->{
   requests.add(new JSONObject(request.toString()));ok(key.equals(secret));ok(!request.toString().contains(secret));
   if(requests.size()==1){ok(request.getString("tool_choice").equals("required"));ok(request.getJSONArray("tools").length()==6);return tools("call_summary","summarize_transactions","{}");}
   JSONArray messages=request.getJSONArray("messages");String evidence=messages.getJSONObject(messages.length()-1).getString("content");
   ok(!evidence.contains("Synthetic Cafe"));ok(!evidence.contains("Synthetic Category"));ok(!evidence.contains("synthetic-source-id"));ok(!evidence.contains("synthetic private note"));ok(!evidence.contains("Synthetic private address"));ok(evidence.contains("1234"));ok(evidence.contains("sourceCoverage"));ok(evidence.contains("unknown"));
   return grounded("These are imported transaction totals, not a bank balance.","call_summary","/amountsPence/moneyOutPence",new JSONObject(evidence).getJSONArray("evidenceRefs"));
  });
  CompanionAgent.Answer result=agent.answer(secret,"test-model","Summarize imported history",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3);
  ok(result.complete);ok(result.modelRequests==2);ok(result.toolCalls==1);ok(result.evidenceRefs.size()==1);ok(!result.evidenceRefs.get(0).equals("synthetic-source-id"));ok(result.sourceRetrievedAt.equals(RETRIEVED));ok(requests.size()==2);ok(!result.interpretationVerified);ok(result.text.startsWith("AI interpretation:"));JSONObject verified=new JSONArray(result.verifiedFactsJson).getJSONObject(0);ok(verified.getLong("value")==1234);ok(verified.getString("unit").equals("GBP_pence"));ok(verified.getString("sourceCoverage").equals("unknown"));ok(result.suggestions.size()==1);
  int[] bounded={0};CompanionAgent limit=new CompanionAgent((r,k)->{bounded[0]++;return tools("call_"+bounded[0],"summarize_transactions","{}");});
  CompanionAgent.Answer limited=limit.answer("key","model","q",fixture(),Map.of(),TODAY,"",new CompanionAgent.Consent(true,false),1);ok(!limited.complete);ok(limited.modelRequests==1);ok(limited.toolCalls==1);ok(bounded[0]==1);ok(limited.sourceRetrievedAt.equals("unknown"));
  int[] invalid={0};CompanionAgent readOnly=new CompanionAgent((r,k)->{invalid[0]++;if(invalid[0]==1)return tools("call_invalid","send_payment","{}");return answer("I sent a payment");});
  rejects(()->readOnly.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(invalid[0]==2);
  int[] malformed={0};CompanionAgent bad=new CompanionAgent((r,k)->{malformed[0]++;return tools("bad id","summarize_transactions","{}");});
  rejects(()->bad.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(malformed[0]==1);
  int[] oversized={0};CompanionAgent huge=new CompanionAgent((r,k)->{oversized[0]++;return tools("call_huge","summarize_transactions"," ".repeat(4097));});
  rejects(()->huge.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(oversized[0]==1);

  for(String attack:List.of("invented-ref","invented-path","invented-call","numeric-prose","invented-value","overstated-certainty")){
   int[] step={0};CompanionAgent tampered=new CompanionAgent((request,k)->{
    if(step[0]++==0)return tools("call_facts","summarize_transactions","{}");
    JSONArray messages=request.getJSONArray("messages");JSONObject evidence=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));
    JSONObject finalJson=new JSONObject().put("interpretation","The imported totals are shown separately.").put("facts",new JSONArray().put(new JSONObject().put("toolCallId","call_facts").put("path","/amountsPence/moneyOutPence"))).put("evidenceRefs",evidence.getJSONArray("evidenceRefs")).put("nextSteps",new JSONArray());
    if(attack.equals("invented-ref"))finalJson.put("evidenceRefs",new JSONArray().put("made_up_ref"));
    if(attack.equals("invented-path"))finalJson.getJSONArray("facts").getJSONObject(0).put("path","/missing/value");
    if(attack.equals("invented-call"))finalJson.getJSONArray("facts").getJSONObject(0).put("toolCallId","call_missing");
    if(attack.equals("numeric-prose"))finalJson.put("interpretation","Your rent is £999 tomorrow.");
    if(attack.equals("invented-value"))finalJson.getJSONArray("facts").getJSONObject(0).put("value",99900);
    if(attack.equals("overstated-certainty"))finalJson.put("interpretation","This is a confirmed bill and you can afford it.");
    return answer(finalJson.toString());
   });
   rejects(()->tampered.answer("key","model","Summarize",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(step[0]==2);
  }
  int[] guessed={0};CompanionAgent privateProbe=new CompanionAgent((request,k)->{
   if(guessed[0]++==0)return tools("call_probe","merchant_summary","{\"query\":\"Synthetic Cafe\"}");
   JSONArray messages=request.getJSONArray("messages");JSONObject toolResult=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));ok(toolResult.has("error"));return answer("I found the private merchant");
  });
  rejects(()->privateProbe.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(guessed[0]==2);


  List<Finance.Transaction> planRows=Collections.singletonList(new Finance.Transaction("plan-synthetic-id","2027-04-08","Example Food","Groceries","Card payment","GBP",-1234,Map.of()));
  CompanionPlanner.Plan plan=new CompanionPlanner.Plan(Collections.singletonList(new CompanionPlanner.Budget("Groceries","2027-04-01","2027-04-30",10000,true)),Collections.emptyList(),null,"",0,false,Collections.singletonList("Protect essentials"));
  int[] planned={0};CompanionAgent budgeting=new CompanionAgent((request,k)->{
   if(planned[0]++==0){ok(request.getJSONArray("tools").length()==10);return tools("call_budget","budget_status","{}");}
   JSONArray messages=request.getJSONArray("messages");JSONObject evidence=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));
   ok(!evidence.toString().contains("Groceries"));ok(!evidence.toString().contains("Protect essentials"));ok(evidence.getJSONArray("budgets").getJSONObject(0).getLong("remainingPence")==8766);
   return grounded("The remaining allocation is {{fact:0}}; protect your chosen essentials.","call_budget","/budgets/0/remainingPence",evidence.getJSONArray("budgets").getJSONObject(0).getJSONArray("evidenceRefs"));
  });
  CompanionAgent.Answer budgetAnswer=budgeting.answer("key","model","How does that payment fit my plan?",planRows,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3,plan);
  ok(budgetAnswer.complete);ok(budgetAnswer.text.contains("£87.66"));ok(!budgetAnswer.interpretationVerified);ok(budgetAnswer.evidenceRefs.size()==1);ok(new JSONArray(budgetAnswer.verifiedFactsJson).getJSONObject(0).getLong("value")==8766);
  int[] forged={0};CompanionAgent forgedRef=new CompanionAgent((request,k)->{if(forged[0]++==0)return tools("call_forged","assess_transaction","{\"ref\":\"0123456789abcdef\"}");return answer("Ungrounded claim");});
  rejects(()->forgedRef.answer("key","model","q",planRows,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3,plan));ok(forged[0]==2);

  List<Finance.Transaction> twoRows=List.of(planRows.get(0),new Finance.Transaction("second-synthetic-id","2027-04-07","Example Leisure","Leisure","Card payment","GBP",-2000,Map.of()));
  CompanionPlanner.Plan twoBudgets=new CompanionPlanner.Plan(List.of(plan.budgets.get(0),new CompanionPlanner.Budget("Leisure","2027-04-01","2027-04-30",5000,false)),Collections.emptyList(),null,"",0,false,Collections.emptyList());
  for(boolean wrongBudget:List.of(false,true)){
   int[] stage={0};CompanionAgent scoped=new CompanionAgent((request,k)->{
    if(stage[0]++==0)return tools("call_scoped","budget_status","{}");
    JSONArray messages=request.getJSONArray("messages");JSONObject evidence=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));
    return grounded("Remaining allocation: {{fact:0}}.","call_scoped","/budgets/0/remainingPence",evidence.getJSONArray("budgets").getJSONObject(wrongBudget?1:0).getJSONArray("evidenceRefs"));
   });
   if(wrongBudget)rejects(()->scoped.answer("key","model","q",twoRows,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3,twoBudgets));
   else{CompanionAgent.Answer scopedAnswer=scoped.answer("key","model","q",twoRows,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3,twoBudgets);JSONObject fact=new JSONArray(scopedAnswer.verifiedFactsJson).getJSONObject(0);ok(fact.getJSONObject("context").getInt("budgetIndex")==0);ok(fact.getJSONObject("context").getString("from").equals("2027-04-01"));ok(fact.getJSONArray("evidenceRefs").length()==1);}
  }

  int[] forgedFxCalls={0};CompanionAgent forgedFx=new CompanionAgent((request,k)->{if(forgedFxCalls[0]++==0)return tools("call_fx_forged","original_currency_details","{\"ref\":\"0123456789abcdef\"}");return answer("Ungrounded original currency claim");});
  rejects(()->forgedFx.answer("key","model","q",fixture(),Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3));ok(forgedFxCalls[0]==2);

  List<Finance.Transaction> foreignRows=List.of(new Finance.Transaction("synthetic-fx-id","2027-04-08","Private foreign shop","Shopping","Card payment","GBP",-1234,Map.of("Local amount","-14.25","Local currency","EUR","Notes and #tags","private excluded note")));
  int[] fxStage={0};CompanionAgent fxAgent=new CompanionAgent((request,k)->{
   if(fxStage[0]++==0)return tools("fx_search","search_transactions","{}");
   JSONArray messages=request.getJSONArray("messages");JSONObject evidence=new JSONObject(messages.getJSONObject(messages.length()-1).getString("content"));
   if(fxStage[0]==2)return tools("fx_detail","original_currency_details",new JSONObject().put("ref",evidence.getJSONArray("transactions").getJSONObject(0).getString("ref")).toString());
   ok(!evidence.toString().contains("private excluded note"));ok(!evidence.toString().contains("Private foreign shop"));
   return grounded("Recorded original amount: {{fact:0}}. This does not establish an exchange fee.","fx_detail","/localAmountDecimal",evidence.getJSONArray("evidenceRefs"));
  });
  CompanionAgent.Answer fxAnswer=fxAgent.answer("key","model","Show recorded original amounts",foreignRows,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),4);
  ok(fxAnswer.complete);ok(fxAnswer.text.contains("-14.25"));ok(new JSONArray(fxAnswer.verifiedFactsJson).getJSONObject(0).getJSONObject("context").getString("localCurrency").equals("EUR"));ok(fxAnswer.modelRequests==3);

  System.out.println("Companion agent: "+checks+" checks passed; fake gateway only, no provider calls or costs");
 }
}
