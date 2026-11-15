package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic, offline validation of planning tool privacy, evidence and deterministic calculations. */
public final class CompanionPlanningToolsChecks {
    private static int checks;
    private static final LocalDate TODAY=LocalDate.of(2026,11,13);
    private static Finance.Transaction tx(String id,String date,String name,String category,String type,long amount) {
        Map<String,String> raw=new HashMap<>();raw.put("Notes and #tags","synthetic-secret-note");raw.put("Description","synthetic-secret-description");
        raw.put("Address","synthetic-secret-address");raw.put("Receipt","synthetic-secret-receipt");raw.put("Pot name","synthetic-secret-pot");
        return new Finance.Transaction(id,date,name,category,type,"GBP",amount,raw);
    }
    private static List<Finance.Transaction> history() {
        return Arrays.asList(tx("synthetic-plan-source-id-1","2026-11-02","Synthetic private merchant","Synthetic private essentials","Card payment",-2000),
            tx("synthetic-plan-source-id-2","2026-11-03","Synthetic private merchant","Synthetic private essentials","Card payment",500),
            tx("synthetic-plan-source-id-3","2026-11-04","Synthetic private pot","Synthetic private essentials","Pot transfer",-100000),
            tx("synthetic-plan-source-id-4","2026-11-05","Synthetic private merchant","Synthetic private flexible","Card payment",-4000));
    }
    private static CompanionPlanner.Plan plan(Long balance,String date,boolean complete) {
        return new CompanionPlanner.Plan(Arrays.asList(
            new CompanionPlanner.Budget("Synthetic private essentials","2026-11-01","2026-11-30",10000,true),
            new CompanionPlanner.Budget("Synthetic private flexible","2026-11-01","2026-11-30",3000,false)),
            Arrays.asList(new CompanionPlanner.Commitment("Synthetic private commitment","2026-11-20",3000,true),
                new CompanionPlanner.Commitment("Synthetic unconfirmed commitment","2026-11-21",90000,false)),
            balance,date,1000,complete,Arrays.asList("Synthetic private priority"));
    }
    private static JSONObject run(String name,String args,List<Finance.Transaction> history,CompanionPlanner.Plan plan,boolean labels)throws Exception{
        return CompanionPlanningTools.execute(name,new JSONObject(args),history,new HashMap<>(),TODAY,plan,labels);
    }
    private static void ok(boolean value){checks++;if(!value)throw new AssertionError("Planning tool check "+checks);}
    private static void equal(long value,long expected){ok(value==expected);}
    private static void reject(String name,String args,List<Finance.Transaction> history,CompanionPlanner.Plan plan)throws Exception{
        JSONObject arguments=new JSONObject(args);checks++;try{CompanionPlanningTools.execute(name,arguments,history,new HashMap<>(),TODAY,plan,false);throw new AssertionError("Expected rejection at "+checks);}catch(IllegalArgumentException expected){}
    }
    private static void privateFieldsAbsent(JSONObject result){
        String text=result.toString();for(String secret:new String[]{"synthetic-secret","synthetic-plan-source-id","Synthetic private","Synthetic unconfirmed"})ok(!text.contains(secret));
    }
    private static String evidenceRef(List<Finance.Transaction> rows,long amount)throws Exception{
        JSONArray results=CompanionTools.execute("search_transactions",new JSONObject(),rows,new HashMap<>(),TODAY,false).getJSONArray("transactions");
        for(int i=0;i<results.length();i++){JSONObject row=results.getJSONObject(i);if(row.getLong("amountPence")==amount)return row.getString("ref");}
        throw new AssertionError("Missing synthetic evidence");
    }
    public static void main(String[] ignored)throws Exception{
        checks=0;List<Finance.Transaction> rows=history();CompanionPlanner.Plan active=plan(10000L,TODAY.toString(),true);
        JSONArray definitions=CompanionPlanningTools.definitions();equal(definitions.length(),4);
        for(int i=0;i<definitions.length();i++){
            JSONObject function=definitions.getJSONObject(i).getJSONObject("function"),schema=function.getJSONObject("parameters");
            ok(definitions.getJSONObject(i).getString("type").equals("function"));ok(function.getBoolean("strict"));ok(!schema.getBoolean("additionalProperties"));
            equal(schema.getJSONArray("required").length(),schema.getJSONObject("properties").length());
        }
        ok(!definitions.toString().contains("synthetic-secret"));ok(!definitions.toString().contains("Synthetic private"));
        JSONObject context=run("planning_context","{}",rows,active,false);privateFieldsAbsent(context);
        ok(context.getString("balanceStatus").equals("current"));equal(context.getLong("reportedBalancePence"),10000);
        equal(context.getLong("knownCommitmentsPence"),3000);equal(context.getLong("reservePence"),1000);equal(context.getLong("headroomPence"),6000);
        ok(!context.getBoolean("balanceVerifiedByBank"));ok(!context.getBoolean("headroomIsSpendableCash"));ok(!context.getBoolean("verifiedAffordability"));
        ok(context.getString("headroomScope").equals("after_known_commitments_and_reserve_before_other_budget_allocations"));
        equal(context.getInt("confirmedCommitmentCount"),1);equal(context.getInt("priorityCount"),1);ok(!context.has("priorities"));
        ok(!context.getJSONArray("commitments").getJSONObject(0).has("label"));ok(context.getJSONObject("scope").getString("horizonThrough").equals("2026-11-30"));
        JSONObject visibleContext=run("planning_context","{}",rows,active,true);ok(visibleContext.toString().contains("Synthetic private commitment"));ok(visibleContext.toString().contains("Synthetic private priority"));
        ok(!visibleContext.toString().contains("synthetic-secret"));
        JSONObject stale=run("planning_context","{}",rows,plan(123456L,"2026-11-12",true),false);
        ok(stale.getString("balanceStatus").equals("stale"));ok(stale.isNull("reportedBalancePence"));ok(stale.isNull("headroomPence"));ok(stale.isNull("reviewShortfall"));ok(!stale.toString().contains("123456"));
        JSONObject incomplete=run("planning_context","{}",rows,plan(10000L,TODAY.toString(),false),false);equal(incomplete.getLong("reportedBalancePence"),10000);ok(incomplete.isNull("headroomPence"));
        JSONObject unknown=run("planning_context","{}",rows,plan(null,"",true),false);ok(unknown.getString("balanceStatus").equals("unknown"));ok(unknown.isNull("reportedBalancePence"));ok(unknown.isNull("headroomPence"));
        JSONObject future=run("planning_context","{}",rows,plan(987654L,"2026-11-14",true),false);ok(future.getString("balanceStatus").equals("unknown"));ok(future.isNull("reportedBalancePence"));ok(future.isNull("headroomPence"));
        JSONObject shortfall=run("planning_context","{}",rows,plan(-500L,TODAY.toString(),true),false);equal(shortfall.getLong("headroomPence"),-4500);ok(shortfall.getBoolean("reviewShortfall"));ok(!shortfall.getBoolean("verifiedAffordability"));
        JSONObject absent=run("planning_context","{}",rows,null,false);ok(!absent.getBoolean("planningConfigured"));ok(absent.isNull("reportedBalancePence"));ok(absent.isNull("headroomPence"));
        JSONObject status=run("budget_status","{}",rows,active,false);privateFieldsAbsent(status);equal(status.getInt("rowCount"),2);equal(status.getInt("historyRowCount"),4);
        JSONArray budgets=status.getJSONArray("budgets");JSONObject essentials=budgets.getJSONObject(0),flexible=budgets.getJSONObject(1);
        equal(essentials.getLong("grossDebitsPence"),2000);equal(essentials.getLong("creditsPence"),500);equal(essentials.getLong("usedPence"),2000);equal(essentials.getLong("remainingPence"),8000);
        equal(essentials.getInt("evidenceRowCount"),2);equal(essentials.getJSONArray("evidenceRefs").length(),2);ok(!essentials.getBoolean("evidenceTruncated"));
        for(int i=0;i<essentials.getJSONArray("evidenceRefs").length();i++)ok(essentials.getJSONArray("evidenceRefs").getString(i).matches("[0-9a-f]{16}"));
        ok(essentials.getBoolean("essentialByUser"));ok(essentials.getString("recommendation").contains("Protect"));ok(!essentials.has("category"));
        ok(essentials.getString("dailyGuideScope").equals("remaining_budget_allocation_not_available_cash"));
        equal(flexible.getLong("remainingPence"),-1000);ok(flexible.getString("pace").equals("over_budget"));ok(flexible.getBoolean("projectionIsEstimate"));
        JSONObject visibleStatus=run("budget_status","{}",rows,active,true);ok(visibleStatus.toString().contains("Synthetic private essentials"));ok(!visibleStatus.toString().contains("synthetic-secret"));
        String debitRef=evidenceRef(rows,-2000),creditRef=evidenceRef(rows,500),potRef=evidenceRef(rows,-100000);
        JSONObject assessment=run("assess_transaction",new JSONObject().put("ref",debitRef).toString(),rows,active,false);privateFieldsAbsent(assessment);
        equal(assessment.getLong("amountPence"),-2000);ok(assessment.getString("ref").equals(debitRef));ok(assessment.getJSONArray("evidenceRefs").getString(0).equals(debitRef));
        JSONObject impact=assessment.getJSONArray("budgetImpacts").getJSONObject(0);equal(impact.getLong("beforeRemainingPence"),10000);equal(impact.getLong("afterRemainingPence"),8000);equal(impact.getLong("countedDebitPence"),2000);
        ok(impact.getBoolean("essentialByUser"));ok(impact.getString("comparison").equals("snapshot_without_transaction_vs_with_transaction"));ok(assessment.getBoolean("reviewOnly"));ok(!assessment.has("category"));
        JSONObject credit=run("assess_transaction",new JSONObject().put("ref",creditRef).toString(),rows,active,false);ok(credit.getBoolean("creditUnclassified"));equal(credit.getJSONArray("budgetImpacts").getJSONObject(0).getLong("countedDebitPence"),0);equal(credit.getJSONArray("budgetImpacts").getJSONObject(0).getLong("creditsPence"),500);
        JSONObject pot=run("assess_transaction",new JSONObject().put("ref",potRef).toString(),rows,active,false);ok(pot.getBoolean("potMovement"));equal(pot.getInt("budgetImpactCount"),0);
        JSONObject namedAssessment=run("assess_transaction",new JSONObject().put("ref",debitRef).toString(),rows,active,true);ok(namedAssessment.has("category"));ok(!namedAssessment.toString().contains("synthetic-secret"));
        for(String ref:new String[]{"synthetic-plan-source-id-1","badref","ABCDEF0123456789","0000000000000000"})reject("assess_transaction",new JSONObject().put("ref",ref).toString(),rows,active);
        reject("assess_transaction","{}",rows,active);reject("assess_transaction","{\"ref\":true}",rows,active);reject("assess_transaction",new JSONObject().put("ref",debitRef).put("category","guessed label").toString(),rows,active);
        List<Finance.Transaction> reviewRows=new ArrayList<>();
        for(int i=0;i<5;i++)reviewRows.add(tx("synthetic-old-baseline-"+i,LocalDate.of(2020,1,1).plusDays(i).toString(),"Synthetic baseline merchant","General","Card payment",-1000));
        for(int i=0;i<1205;i++)reviewRows.add(tx("synthetic-filler-"+i,LocalDate.of(2021,1,1).plusDays(i).toString(),"Synthetic unrelated merchant "+i,"General","Card payment",-1));
        reviewRows.add(tx("synthetic-large-recent","2026-11-12","Synthetic baseline merchant","General","Card payment",-10000));
        reviewRows.add(tx("synthetic-duplicate-one","2026-11-13","Synthetic repeated merchant","General","Card payment",-200));
        reviewRows.add(tx("synthetic-duplicate-two","2026-11-13","Synthetic repeated merchant","General","Card payment",-200));
        JSONObject review=run("review_payments","{}",reviewRows,active,false);privateFieldsAbsent(review);equal(review.getInt("historyRowCount"),1213);equal(review.getInt("rowCount"),2);
        ok(review.getJSONObject("scope").getString("baselineHistory").equals("all_earlier_imported_history"));ok(review.getJSONObject("scope").getString("candidateWindowFrom").equals("2026-10-15"));
        for(int i=0;i<review.getJSONArray("flags").length();i++){
            JSONObject flag=review.getJSONArray("flags").getJSONObject(i);ok(flag.getBoolean("requiresReview"));ok(flag.getBoolean("estimatedSignal"));ok(!flag.getBoolean("confirmedFraud"));ok(!flag.getBoolean("confirmedDuplicate"));ok(flag.getString("severity").equals("review"));
            ok(flag.has("calculation"));JSONArray refs=flag.getJSONArray("evidenceRefs");ok(refs.length()>=2);for(int j=0;j<refs.length();j++)ok(refs.getString(j).matches("[0-9a-f]{16}"));
            if(flag.getString("kind").equals("unusually_large_debit")){equal(flag.getJSONObject("calculation").getLong("minimumReviewDebitPence"),10000);equal(flag.getJSONObject("calculation").getInt("atLeastBaselineMedianMultiplier"),3);}
        }
        ok(!review.toString().contains("Synthetic baseline merchant"));ok(!review.toString().contains("synthetic-large-recent"));
        JSONObject below=run("review_payments","{}",Arrays.asList(tx("synthetic-small-1","2026-11-12","Synthetic single merchant","General","Card payment",-9999)),active,false);equal(below.getInt("rowCount"),0);
        List<CompanionPlanner.Budget> manyBudgets=new ArrayList<>();for(int i=0;i<25;i++)manyBudgets.add(new CompanionPlanner.Budget("Synthetic category "+i,"2026-11-01","2026-11-30",10000,false));
        CompanionPlanner.Plan many=new CompanionPlanner.Plan(manyBudgets,Collections.emptyList(),null,"",0,false,Collections.emptyList());
        JSONObject firstPage=run("budget_status","{}",rows,many,false);equal(firstPage.getInt("rowCount"),25);equal(firstPage.getJSONArray("budgets").length(),20);equal(firstPage.getInt("nextOffset"),20);
        JSONObject secondPage=run("budget_status","{\"offset\":20,\"limit\":20}",rows,many,false);equal(secondPage.getJSONArray("budgets").length(),5);ok(!secondPage.getBoolean("hasMore"));
        for(String tool:new String[]{"budget_status","review_payments"})for(String invalid:new String[]{"{\"offset\":-1}","{\"offset\":2147483648}","{\"limit\":0}","{\"limit\":21}","{\"limit\":1.5}","{\"limit\":\"20\"}","{\"category\":\"guessed label\"}","{\"shareLabels\":true}"})reject(tool,invalid,rows,active);
        reject("planning_context","{\"reportedBalancePence\":1000000}",rows,active);reject("make_payment","{}",rows,active);reject("change_budget","{}",rows,active);
        ok(rows.get(0).raw.get("Notes and #tags").equals("synthetic-secret-note"));ok(active.budgets.get(0).limitPence==10000);
        System.out.println(checks+" planning tool checks passed; synthetic, offline and read-only.");
    }
}
