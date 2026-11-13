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

/** Synthetic offline tests. No account, provider, network or source transaction data is used. */
public final class CompanionOpportunitiesChecks {
    private static int checks;
    private static final LocalDate TODAY=LocalDate.of(2026,11,13);
    private static final String RECENT="2026-11-13T08:00:00Z";
    private static Finance.Transaction tx(String id,String date,String name,String category,String type,long amount) {
        Map<String,String> raw=new HashMap<>();raw.put("Notes and #tags","synthetic-private-opportunity-note");
        raw.put("Address","synthetic-private-opportunity-address");raw.put("Receipt","synthetic-private-opportunity-receipt");
        raw.put("Description","synthetic-private-opportunity-description");raw.put("Pot name","synthetic-private-opportunity-pot");
        return new Finance.Transaction(id,date,name,category,type,"GBP",amount,raw);
    }
    private static Finance.Transaction fx(String id,String date,String code,String amount) {
        Finance.Transaction base=tx(id,date,"Synthetic FX merchant","Travel","Card payment",-1500);
        Map<String,String> raw=new HashMap<>(base.raw);raw.put("Local currency",code);raw.put("Local amount",amount);
        return new Finance.Transaction(base.id,base.date,base.name,base.category,base.type,base.currency,base.amount,raw);
    }
    private static JSONArray cards(List<Finance.Transaction> rows,LocalDate today,CompanionPlanner.Plan plan,String timestamp,boolean labels) {
        return CompanionOpportunities.cards(rows,new HashMap<>(),today,plan,timestamp,labels);
    }
    private static JSONObject find(JSONArray cards,String kind) {
        for(int i=0;i<cards.length();i++)if(cards.optJSONObject(i).optString("kind").equals(kind))return cards.optJSONObject(i);
        return null;
    }
    private static void ok(boolean value){checks++;if(!value)throw new AssertionError("Opportunity check "+checks);}
    private static void equal(long value,long expected){ok(value==expected);}
    private static void privacy(JSONArray cards,boolean labels){
        String text=cards.toString();for(String secret:new String[]{"synthetic-private-opportunity","synthetic-source-id","synthetic-duplicate-id","synthetic-recurring-id"})ok(!text.contains(secret));
        if(!labels){ok(!text.contains("Synthetic personal merchant"));ok(!text.contains("Synthetic personal category"));}
        for(int i=0;i<cards.length();i++){
            JSONObject card=cards.optJSONObject(i);ok(!card.optBoolean("automaticallySentToAi",true));ok(card.optBoolean("requiresUserAction"));
            ok(card.optBoolean("readOnly"));ok(!card.optBoolean("financialActionsAvailable",true));ok(card.optString("sourceCoverage").equals("unknown"));
            ok(!card.optString("question").trim().isEmpty());ok(!card.optString("title").trim().isEmpty());equal(card.optInt("rank"),i+1);
            JSONArray refs=card.optJSONArray("evidenceRefs");ok(refs!=null);ok(refs.length()<=10);for(int j=0;j<refs.length();j++)ok(refs.optString(j).matches("[0-9a-f]{16}"));
        }
    }
    private static CompanionPlanner.Plan plan() {
        return new CompanionPlanner.Plan(Arrays.asList(new CompanionPlanner.Budget("Synthetic personal category","2026-11-01","2026-11-30",1000,false),
            new CompanionPlanner.Budget("Synthetic essential category","2026-11-01","2026-11-30",10000,true)),
            Collections.emptyList(),null,"",0,false,Arrays.asList("Synthetic personal priority"));
    }
    public static void main(String[] ignored) {
        checks=0;
        equal(cards(Collections.emptyList(),TODAY,null,RECENT,false).length(),0);
        List<Finance.Transaction> one=Arrays.asList(tx("synthetic-source-id-single","2026-11-12","Synthetic personal merchant","Synthetic personal category","Card payment",-100));
        JSONArray basic=cards(one,TODAY,null,RECENT,false);ok(basic.length()>0);ok(find(basic,"history_overview")!=null);ok(find(basic,"merchant_drilldown")==null);ok(find(basic,"budget_conservation")==null);privacy(basic,false);
        JSONArray labelled=cards(one,TODAY,null,RECENT,true);ok(find(labelled,"merchant_drilldown")!=null);ok(find(labelled,"merchant_drilldown").optBoolean("requiresLabelSharing"));privacy(labelled,true);
        ok(find(labelled,"merchant_drilldown").optString("question").contains("Synthetic personal merchant"));
        List<Finance.Transaction> comparisonRows=Arrays.asList(
            tx("synthetic-source-id-old","2026-10-02","Synthetic personal merchant","Synthetic personal category","Card payment",-1000),
            tx("synthetic-source-id-oldlate","2026-10-25","Synthetic personal merchant","Synthetic personal category","Card payment",-90000),
            tx("synthetic-source-id-current","2026-11-02","Synthetic personal merchant","Synthetic personal category","Card payment",-2000),
            tx("synthetic-source-id-future","2026-11-20","Synthetic personal merchant","Synthetic personal category","Card payment",-50000),
            tx("synthetic-source-id-pot","2026-11-03","Synthetic pot","Synthetic personal category","Pot transfer",-100000),
            tx("synthetic-source-id-credit","2026-11-04","Synthetic personal merchant","Synthetic personal category","Card payment",500));
        JSONArray comparison=cards(comparisonRows,TODAY,null,RECENT,false);JSONObject matched=find(comparison,"matched_period_comparison");ok(matched!=null);
        equal(matched.optJSONObject("amountsPence").optLong("currentMoneyOutPence"),2000);equal(matched.optJSONObject("amountsPence").optLong("priorMoneyOutPence"),1000);equal(matched.optJSONObject("amountsPence").optLong("differencePence"),1000);
        JSONObject scope=matched.optJSONObject("scope");ok(scope.optString("fromA").equals("2026-10-01"));ok(scope.optString("toA").equals("2026-10-13"));ok(scope.optString("toB").equals("2026-11-13"));equal(scope.optInt("matchedCalendarDays"),13);ok(!scope.optBoolean("currentWindowClipped"));privacy(comparison,false);
        LocalDate marchEnd=LocalDate.of(2026,3,31);
        List<Finance.Transaction> clippingRows=Arrays.asList(tx("synthetic-source-id-feb","2026-02-28","Example","General","Card payment",-100),tx("synthetic-source-id-mar","2026-03-28","Example","General","Card payment",-200),tx("synthetic-source-id-mar-late","2026-03-31","Example","General","Card payment",-99999));
        JSONObject clipped=find(cards(clippingRows,marchEnd,null,"2026-03-31T08:00:00Z",false),"matched_period_comparison");ok(clipped!=null);ok(clipped.optJSONObject("scope").optBoolean("currentWindowClipped"));equal(clipped.optJSONObject("scope").optInt("matchedCalendarDays"),28);ok(clipped.optJSONObject("scope").optString("toB").equals("2026-03-28"));equal(clipped.optJSONObject("amountsPence").optLong("currentMoneyOutPence"),200);
        LocalDate leapMarch=LocalDate.of(2024,3,31);
        List<Finance.Transaction> leapRows=Arrays.asList(tx("synthetic-source-id-leap-feb","2024-02-29","Example","General","Card payment",-100),tx("synthetic-source-id-leap-mar","2024-03-29","Example","General","Card payment",-200));
        JSONObject leap=find(cards(leapRows,leapMarch,null,"2024-03-31T08:00:00Z",false),"matched_period_comparison");equal(leap.optJSONObject("scope").optInt("matchedCalendarDays"),29);ok(leap.optJSONObject("scope").optString("toA").equals("2024-02-29"));
        List<Finance.Transaction> noPrior=Arrays.asList(tx("synthetic-source-id-noprior","2026-11-10","Example","General","Card payment",-100));ok(find(cards(noPrior,TODAY,null,RECENT,false),"matched_period_comparison")==null);
        List<Finance.Transaction> creditsOnly=Arrays.asList(tx("synthetic-source-id-credit-a","2026-10-02","Example","General","Faster payment",1000),tx("synthetic-source-id-credit-b","2026-11-02","Example","General","Faster payment",2000));ok(find(cards(creditsOnly,TODAY,null,RECENT,false),"matched_period_comparison")==null);
        List<Finance.Transaction> potsOnly=Arrays.asList(tx("synthetic-source-id-pot-only","2026-11-10","Example","General","Pot transfer",-100000));equal(cards(potsOnly,TODAY,null,RECENT,false).length(),0);
        List<Finance.Transaction> budgetRows=Arrays.asList(tx("synthetic-source-id-budget","2026-11-02","Synthetic personal merchant","Synthetic personal category","Card payment",-2000),tx("synthetic-source-id-essential","2026-11-03","Example","Synthetic essential category","Card payment",-3000),tx("synthetic-source-id-budget-credit","2026-11-04","Example","Synthetic personal category","Card payment",1000));
        JSONArray planning=cards(budgetRows,TODAY,plan(),RECENT,false);JSONObject conservation=find(planning,"budget_conservation"),protectedCard=find(planning,"protected_essentials");ok(conservation!=null);ok(protectedCard!=null);equal(conservation.optJSONObject("amountsPence").optLong("remainingPence"),-1000);equal(conservation.optJSONObject("amountsPence").optLong("creditsPence"),1000);ok(!conservation.optBoolean("essentialByUser",true));ok(!conservation.has("category"));ok(protectedCard.optString("question").contains("essential"));privacy(planning,false);
        JSONObject namedBudget=find(cards(budgetRows,TODAY,plan(),RECENT,true),"budget_conservation");ok(namedBudget.optString("category").equals("Synthetic personal category"));
        List<Finance.Transaction> repeating=Arrays.asList(tx("synthetic-recurring-id-a","2026-09-10","Example subscription","Bills","Card payment",-999),tx("synthetic-recurring-id-b","2026-10-10","Example subscription","Bills","Card payment",-999),tx("synthetic-recurring-id-c","2026-11-10","Example subscription","Bills","Card payment",-999));
        JSONObject recurring=find(cards(repeating,TODAY,null,RECENT,false),"recurring_review");ok(recurring!=null);ok(recurring.optBoolean("estimate"));ok(recurring.optBoolean("requiresConfirmation"));ok(!recurring.optBoolean("confirmedBill",true));equal(recurring.optInt("candidateCount"),1);
        List<Finance.Transaction> duplicate=Arrays.asList(tx("synthetic-duplicate-id-a","2026-11-12","Example repeat","General","Card payment",-200),tx("synthetic-duplicate-id-b","2026-11-12","Example repeat","General","Card payment",-200));
        JSONObject review=find(cards(duplicate,TODAY,null,RECENT,false),"payment_review");ok(review!=null);ok(review.optBoolean("estimate"));ok(!review.optBoolean("confirmedFraud",true));ok(!review.optBoolean("confirmedDuplicate",true));ok(review.optBoolean("requiresConfirmation"));
        List<Finance.Transaction> fxRows=Arrays.asList(fx("synthetic-source-id-eur","2026-11-10","EUR","-12.34"),fx("synthetic-source-id-jpy","2026-11-11","JPY","-2000"),fx("synthetic-source-id-gbp","2026-11-12","GBP","-15.00"));
        JSONArray foreignCards=cards(fxRows,TODAY,null,RECENT,false);JSONObject foreign=find(foreignCards,"original_currency_review");ok(foreign!=null);equal(foreign.optInt("rowCount"),2);equal(foreign.optJSONArray("originalCurrencies").length(),2);equal(foreign.optInt("rowsWithParsableLocalAmount"),2);ok(!foreign.optJSONObject("scope").optBoolean("fxRateInferred",true));ok(!foreign.optJSONObject("scope").optBoolean("feesInferred",true));
        ok(foreign.toString().contains("original_currency_decimal_not_assumed_two_minor_digits"));privacy(foreignCards,false);
        JSONObject malformedAmount=find(cards(Arrays.asList(fx("synthetic-source-id-unparsed","2026-11-10","EUR","synthetic-private-opportunity-note")),TODAY,null,RECENT,false),"original_currency_review");ok(malformedAmount!=null);equal(malformedAmount.optInt("rowsWithParsableLocalAmount"),0);equal(malformedAmount.optJSONArray("recordedAmountSamples").length(),0);ok(!malformedAmount.toString().contains("synthetic-private"));
        ok(find(cards(Arrays.asList(fx("synthetic-source-id-invalid-code","2026-11-10","not currency","12")),TODAY,null,RECENT,false),"original_currency_review")==null);
        Map<String,String> missingRaw=new HashMap<>();missingRaw.put("Local currency",null);Finance.Transaction missing=new Finance.Transaction("synthetic-source-id-missing","2026-11-10","Example","General","Card payment","GBP",-100,missingRaw);ok(find(cards(Arrays.asList(missing),TODAY,null,RECENT,false),"original_currency_review")==null);
        JSONArray stale=cards(one,TODAY,null,"2026-11-10T08:00:00Z",false);ok(find(stale,"source_review")!=null);ok(stale.optJSONObject(0).optString("retrievalFreshness").equals("stale"));privacy(stale,false);
        for(String timestamp:new String[]{null,"","not-a-time","2026-11-14T00:00:00Z"}){
            JSONArray unknown=cards(one,TODAY,null,timestamp,false);ok(unknown.optJSONObject(0).optString("retrievalFreshness").equals("unknown"));ok(unknown.optJSONObject(0).isNull("sourceRetrievedAt"));ok(find(unknown,"source_review")==null);ok(unknown.optJSONObject(0).optString("sourceCoverage").equals("unknown"));
        }
        JSONArray recentPriorDay=cards(one,TODAY,null,"2026-11-12T08:00:00Z",false);ok(recentPriorDay.optJSONObject(0).optString("retrievalFreshness").equals("recent"));
        // Source retrieval cannot make an old history a current-month comparison or a live balance.
        JSONArray historical=cards(Arrays.asList(tx("synthetic-source-id-ancient","2020-01-01","Example","General","Card payment",-100)),TODAY,null,RECENT,false);ok(find(historical,"matched_period_comparison")==null);ok(historical.optJSONObject(0).optString("sourceCoverage").equals("unknown"));
        List<Finance.Transaction> longHistory=new ArrayList<>();for(int i=0;i<1205;i++)longHistory.add(tx("synthetic-source-id-long-"+i,LocalDate.of(2020,1,1).plusDays(i).toString(),"Example history merchant","General","Card payment",-1));
        JSONObject fullHistory=find(cards(longHistory,TODAY,null,RECENT,false),"history_overview");equal(fullHistory.optInt("rowCount"),1205);equal(fullHistory.optJSONObject("amountsPence").optLong("moneyOutPence"),1205);equal(fullHistory.optJSONArray("evidenceRefs").length(),10);ok(fullHistory.optBoolean("evidenceTruncated"));
        List<Finance.Transaction> rich=new ArrayList<>(comparisonRows);rich.addAll(budgetRows);rich.addAll(repeating);rich.addAll(duplicate);rich.addAll(fxRows);
        JSONArray bounded=cards(rich,TODAY,plan(),"2026-11-10T08:00:00Z",true);equal(bounded.length(),6);ok(bounded.optJSONObject(0).optString("kind").equals("source_review"));ok(find(bounded,"budget_conservation")!=null);privacy(bounded,true);
        // Multiple review flags have bounded references with explicit truncation, not an apparent complete sample.
        List<Finance.Transaction> multiple=new ArrayList<>();for(int i=0;i<8;i++){multiple.add(tx("synthetic-duplicate-id-"+i+"a","2026-11-12","Example repeat "+i,"General","Card payment",-200));multiple.add(tx("synthetic-duplicate-id-"+i+"b","2026-11-12","Example repeat "+i,"General","Card payment",-200));}
        JSONObject manyReview=find(cards(multiple,TODAY,null,RECENT,false),"payment_review");equal(manyReview.optInt("evidenceRowCount"),16);equal(manyReview.optJSONArray("evidenceRefs").length(),10);ok(manyReview.optBoolean("evidenceTruncated"));
        ok(rowsUntouched(one));System.out.println(checks+" opportunity checks passed; all synthetic, offline and read-only.");
    }
    private static boolean rowsUntouched(List<Finance.Transaction> rows){return rows.get(0).raw.get("Notes and #tags").equals("synthetic-private-opportunity-note");}
}
