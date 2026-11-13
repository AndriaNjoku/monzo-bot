package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Synthetic offline checks. Run in a plain JVM; no providers, tokens or private data needed. */
public final class CompanionToolsChecks {
    private static int checks;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static Finance.Transaction tx(String id, String date, String name, String category, String type, long amount) {
        Map<String, String> raw = new HashMap<>();
        raw.put("Notes and #tags", "synthetic-private-note-token"); raw.put("Description", "synthetic-description-token");
        raw.put("Address", "synthetic-private-address-token"); raw.put("Receipt", "synthetic-private-receipt-token");
        raw.put("Pot name", "synthetic-private-pot-name-token");
        return new Finance.Transaction(id,date,name,category,type,"GBP",amount,raw);
    }
    private static Finance.Transaction timed(String id,String time,long amount) {
        Finance.Transaction base=tx(id,"2026-11-13","Example merchant","General","Card payment",amount);
        Map<String,String> raw=new HashMap<>(base.raw);raw.put("Time",time);
        return new Finance.Transaction(base.id,base.date,base.name,base.category,base.type,base.currency,base.amount,raw);
    }
    private static List<Finance.Transaction> fixture() {
        return Arrays.asList(
            tx("synthetic-source-id-1","2026-11-01","Synthetic Market","Personal category","Card payment",-1001),
            tx("synthetic-source-id-2","2026-11-02","Synthetic Market","Personal category","Card payment",200),
            tx("synthetic-source-id-3","2026-11-03","Synthetic Pot Merchant","Transfers"," Pot transfer ",-5000),
            tx("synthetic-source-id-4","2026-11-04","Synthetic Pot Merchant","Transfers","Pot transfer",3000),
            tx("synthetic-source-id-5","2026-11-05","Synthetic Person","Transfers","Faster payment",-750),
            tx("synthetic-source-id-6","2026-10-01","Synthetic Market","Personal category","Card payment",-499));
    }
    private static JSONObject args(String json) throws Exception { return new JSONObject(json); }
    private static JSONObject run(String name,String json,List<Finance.Transaction> rows,boolean labels) throws Exception {
        return CompanionTools.execute(name,args(json),rows,new HashMap<>(),TODAY,labels);
    }
    private static void ok(boolean value) { checks++; if(!value) throw new AssertionError("Companion tool check " + checks); }
    private static void equal(long value,long expected){ok(value==expected);}
    private static void rejects(Runnable operation){checks++;try{operation.run();throw new AssertionError("Expected safe argument rejection at check "+checks);}catch(IllegalArgumentException expected){}}
    private static void reject(String tool,String json,List<Finance.Transaction> rows) throws Exception {
        JSONObject object=args(json); rejects(()->CompanionTools.execute(tool,object,rows,new HashMap<>(),TODAY,false));
    }
    public static void main(String[] ignored) throws Exception {
        checks=0;
        List<Finance.Transaction> rows=fixture();
        JSONArray definitions=CompanionTools.definitions();equal(definitions.length(),5);
        for(int i=0;i<definitions.length();i++) {
            JSONObject definition=definitions.getJSONObject(i);ok(definition.getString("type").equals("function"));
            JSONObject function=definition.getJSONObject("function"), parameters=function.getJSONObject("parameters");
            ok(function.getBoolean("strict"));ok(!parameters.getBoolean("additionalProperties"));
            equal(parameters.getJSONArray("required").length(),parameters.getJSONObject("properties").length());
        }
        ok(!definitions.toString().contains("synthetic-source-id"));ok(!definitions.toString().contains("Personal category"));
        JSONObject summary=run("summarize_transactions","{}",rows,false);
        equal(summary.getInt("historyRowCount"),6);equal(summary.getInt("matchedRowCount"),6);equal(summary.getInt("rowCount"),4);
        equal(summary.getInt("externalRowCount"),4);equal(summary.getInt("potMovementCount"),2);
        JSONObject amount=summary.getJSONObject("amountsPence"), pots=summary.getJSONObject("potAmountsPence");
        equal(amount.getLong("moneyOutPence"),2250);equal(amount.getLong("moneyInPence"),200);equal(amount.getLong("netOutflowPence"),2050);
        equal(pots.getLong("moneyOutPence"),5000);equal(pots.getLong("moneyInPence"),3000);
        ok(summary.getJSONObject("scope").getString("history").equals("all_imported"));ok(!summary.has("categories"));
        equal(summary.getJSONArray("evidenceRefs").length(),4);
        String hidden=summary.toString();
        for(String secret:new String[]{"Synthetic Market","Personal category","Synthetic Person","synthetic-source-id","synthetic-private","synthetic-description"})ok(!hidden.contains(secret));
        ok(!amount.has("income"));ok(!amount.has("refund"));
        JSONObject included=run("summarize_transactions","{\"includePotMovements\":true}",rows,false);
        equal(included.getInt("rowCount"),6);equal(included.getJSONObject("amountsPence").getLong("moneyOutPence"),7250);
        equal(included.getJSONObject("amountsPence").getLong("moneyInPence"),3200);
        JSONObject transfers=run("summarize_transactions","{\"category\":\"Transfers\"}",rows,true);
        equal(transfers.getJSONObject("amountsPence").getLong("moneyOutPence"),750);equal(transfers.getInt("potMovementCount"),2);
        JSONObject periods=run("summarize_transactions","{\"from\":\"2026-11-01\",\"to\":\"2026-11-02\"}",rows,false);
        equal(periods.getInt("rowCount"),2);equal(periods.getJSONObject("amountsPence").getLong("netOutflowPence"),801);
        JSONObject empty=run("summarize_transactions","{\"from\":\"2027-01-01\"}",rows,false);equal(empty.getInt("rowCount"),0);
        JSONObject labelled=run("summarize_transactions","{}",rows,true);ok(labelled.has("categories"));ok(labelled.toString().contains("Personal category"));
        ok(!labelled.toString().contains("synthetic-private"));ok(!labelled.toString().contains("synthetic-description"));
        // Each category cites only the rows used for that category's totals, never root mixed evidence.
        Map<String,Set<String>> expectedCategoryRefs=new HashMap<>();
        JSONArray labelledRows=run("search_transactions","{}",rows,true).getJSONArray("transactions");
        for(int i=0;i<labelledRows.length();i++){
            JSONObject row=labelledRows.getJSONObject(i);if(row.getString("flowKind").equals("pot_movement"))continue;
            expectedCategoryRefs.computeIfAbsent(row.getString("category"),unused->new HashSet<>()).add(row.getString("ref"));
        }
        Set<String> observedCategoryRefs=new HashSet<>();
        JSONArray categoryGroups=labelled.getJSONArray("categories");equal(categoryGroups.length(),2);
        for(int i=0;i<categoryGroups.length();i++){
            JSONObject group=categoryGroups.getJSONObject(i);JSONArray groupRefs=group.getJSONArray("evidenceRefs");Set<String> actual=new HashSet<>();
            for(int j=0;j<groupRefs.length();j++){String ref=groupRefs.getString(j);actual.add(ref);ok(!observedCategoryRefs.contains(ref));}
            ok(actual.equals(expectedCategoryRefs.get(group.getString("category"))));equal(group.getInt("evidenceRowCount"),group.getInt("rowCount"));ok(!group.getBoolean("evidenceTruncated"));
            ok(groupRefs.length()<labelled.getJSONArray("evidenceRefs").length());observedCategoryRefs.addAll(actual);
        }
        equal(observedCategoryRefs.size(),labelled.getJSONArray("evidenceRefs").length());
        Map<String,String> overrides=new HashMap<>();overrides.put("synthetic-source-id-1","Corrected personal category");
        JSONObject corrected=CompanionTools.execute("summarize_transactions",args("{\"category\":\"Corrected personal category\"}"),rows,overrides,TODAY,true);
        equal(corrected.getInt("rowCount"),1);ok(corrected.toString().contains("Corrected personal category"));
        JSONObject search=run("search_transactions","{}",rows,false);
        equal(search.getInt("rowCount"),6);equal(search.getJSONArray("transactions").length(),6);
        JSONObject item=search.getJSONArray("transactions").getJSONObject(0);
        ok(item.getString("ref").matches("[0-9a-f]{16}"));ok(item.getString("description").equals("transaction"));ok(!item.has("name"));ok(!item.has("category"));ok(!item.has("type"));
        String searchText=search.toString();for(String secret:new String[]{"synthetic-source-id","synthetic-private","synthetic-description","Synthetic Market","Personal category"})ok(!searchText.contains(secret));
        JSONObject visible=run("search_transactions","{\"query\":\"Synthetic Market\"}",rows,true);
        equal(visible.getInt("rowCount"),3);ok(visible.getJSONArray("transactions").getJSONObject(0).has("name"));
        ok(!visible.toString().contains("synthetic-private"));ok(!visible.toString().contains("synthetic-description"));
        JSONObject potSearch=run("search_transactions","{\"query\":\"pot transfer\"}",rows,true);equal(potSearch.getInt("rowCount"),2);
        ok(potSearch.getJSONArray("transactions").getJSONObject(0).getString("flowKind").equals("pot_movement"));
        JSONObject receiptSearch=run("search_transactions","{\"query\":\"synthetic-private-receipt-token\"}",rows,true);equal(receiptSearch.getInt("rowCount"),0);
        reject("merchant_summary","{\"query\":\"synthetic market\"}",rows);
        reject("merchant_summary","{\"query\":\"guessed sensitive merchant\"}",rows);
        JSONObject namedMerchant=run("merchant_summary","{\"query\":\"synthetic market\"}",rows,true);equal(namedMerchant.getJSONArray("merchants").length(),1);
        equal(namedMerchant.getJSONObject("amountsPence").getLong("moneyOutPence"),1500);equal(namedMerchant.getJSONObject("amountsPence").getLong("moneyInPence"),200);
        for(String query:new String[]{"Synthetic Market","guessed sensitive merchant","Personal category","synthetic-private-note-token","synthetic-description-token"}) {
            reject("search_transactions",new JSONObject().put("query",query).toString(),rows);
        }
        for(String category:new String[]{"Personal category","guessed sensitive category"})reject("summarize_transactions",new JSONObject().put("category",category).toString(),rows);
        reject("summarize_transactions","{\"type\":\"Card payment\"}",rows);
        reject("summarize_transactions","{\"type\":\"guessed sensitive type\"}",rows);
        for(String privateOnlyQuery:new String[]{"synthetic-private-note-token","synthetic-description-token","synthetic-private-pot-name-token","synthetic-private-address-token"}) {
            JSONObject privateMatch=run("search_transactions",new JSONObject().put("query",privateOnlyQuery).toString(),rows,true);
            equal(privateMatch.getInt("rowCount"),0);
        }
        JSONObject comparison=run("compare_spending","{\"fromA\":\"2026-10-01\",\"toA\":\"2026-10-31\",\"fromB\":\"2026-11-01\",\"toB\":\"2026-11-30\"}",rows,false);
        equal(comparison.getInt("rowCount"),4);equal(comparison.getJSONObject("differenceAmountsPence").getLong("moneyOutPence"),1252);equal(comparison.getJSONObject("differenceAmountsPence").getLong("netOutflowPence"),1052);
        List<Finance.Transaction> longHistory=new ArrayList<>();
        for(int i=0;i<1205;i++)longHistory.add(tx("synthetic-history-"+i,LocalDate.of(2020,1,1).plusDays(i).toString(),i==0?"Oldest historical merchant":"Example merchant","General","Card payment",-1));
        JSONObject oldSearch=run("search_transactions","{\"query\":\"Oldest historical merchant\"}",longHistory,true);equal(oldSearch.getInt("rowCount"),1);equal(oldSearch.getInt("historyRowCount"),1205);
        JSONObject page=run("search_transactions","{\"offset\":1200,\"limit\":50}",longHistory,false);equal(page.getJSONArray("transactions").length(),5);ok(!page.getBoolean("hasMore"));
        JSONObject firstPage=run("search_transactions","{\"limit\":50}",longHistory,false);equal(firstPage.getInt("rowCount"),1205);equal(firstPage.getJSONArray("transactions").length(),50);equal(firstPage.getInt("nextOffset"),50);
        JSONObject allTotals=run("summarize_transactions","{}",longHistory,false);equal(allTotals.getInt("rowCount"),1205);equal(allTotals.getJSONObject("amountsPence").getLong("moneyOutPence"),1205);equal(allTotals.getJSONArray("evidenceRefs").length(),20);ok(allTotals.getBoolean("evidenceTruncated"));
        List<Finance.Transaction> sameDay=Arrays.asList(timed("z-early","08:15:00",-100),timed("a-late","19:30:00",-200),timed("zz-invalid","not a time",-300));
        JSONArray sameDayRows=run("search_transactions","{}",sameDay,false).getJSONArray("transactions");
        equal(sameDayRows.getJSONObject(0).getLong("amountPence"),-200);equal(sameDayRows.getJSONObject(1).getLong("amountPence"),-100);equal(sameDayRows.getJSONObject(2).getLong("amountPence"),-300);
        List<Finance.Transaction> tiedTime=Arrays.asList(timed("a-stable","19:30:00",-400),timed("b-stable","19:30:00",-500));
        equal(run("search_transactions","{}",tiedTime,false).getJSONArray("transactions").getJSONObject(0).getLong("amountPence"),-500);
        JSONObject pastEnd=run("search_transactions","{\"offset\":2147483647,\"limit\":50}",longHistory,false);equal(pastEnd.getJSONArray("transactions").length(),0);ok(!pastEnd.getBoolean("hasMore"));
        List<Finance.Transaction> recurrence=Arrays.asList(tx("synthetic-r1","2026-09-10","Synthetic Subscription","Bills","Card payment",-999),tx("synthetic-r2","2026-10-10","Synthetic Subscription","Bills","Card payment",-999),tx("synthetic-r3","2026-11-10","Synthetic Subscription","Bills","Card payment",-999));
        JSONObject patterns=run("recurring_candidates","{}",recurrence,false);equal(patterns.getInt("candidateCount"),1);JSONObject pattern=patterns.getJSONArray("candidates").getJSONObject(0);
        ok(pattern.getBoolean("estimated"));ok(pattern.getBoolean("requiresConfirmation"));equal(pattern.getJSONArray("evidenceRefs").length(),3);ok(!patterns.toString().contains("Synthetic Subscription"));ok(!patterns.toString().contains("synthetic-r1"));
        JSONObject namedPattern=run("recurring_candidates","{}",recurrence,true);ok(namedPattern.toString().contains("Synthetic Subscription"));
        for(String json:new String[]{"{\"limit\":0}","{\"limit\":51}","{\"limit\":1.2}","{\"offset\":-1}","{\"offset\":2147483648}","{\"limit\":\"20\"}","{\"query\":true}","{\"extra\":1}"})reject("search_transactions",json,rows);
        for(String json:new String[]{"{\"from\":\"01/11/2026\"}","{\"from\":\"2026-02-30\"}","{\"from\":\"2026-12-01\",\"to\":\"2026-11-01\"}","{\"includePotMovements\":\"true\"}","{\"category\":[]}","{\"type\":{}}","{\"category\":\"\"}","{\"type\":\"\"}","{\"shareMerchantNames\":true}"})reject("summarize_transactions",json,rows);
        reject("merchant_summary","{}",rows);reject("merchant_summary","{\"query\":\"\"}",rows);reject("merchant_summary","{\"query\":false}",rows);
        reject("compare_spending","{}",rows);reject("compare_spending","{\"fromA\":\"2026-01-01\",\"toA\":\"2026-01-31\",\"fromB\":\"2026-11-01\",\"toB\":\"2026-10-01\"}",rows);
        reject("recurring_candidates","{\"tool\":\"pay\"}",rows);reject("make_payment","{}",rows);
        JSONObject overlapping=run("compare_spending","{\"fromA\":\"2026-11-01\",\"toA\":\"2026-11-02\",\"fromB\":\"2026-11-02\",\"toB\":\"2026-11-05\"}",rows,false);equal(overlapping.getInt("rowCount"),3);
        List<Finance.Transaction> overflow=Arrays.asList(tx("synthetic-overflow1","2026-11-01","Example","General","Card payment",Long.MAX_VALUE),tx("synthetic-overflow2","2026-11-02","Example","General","Card payment",1));
        reject("summarize_transactions","{}",overflow);
        reject("summarize_transactions","{}",Arrays.asList(tx("synthetic-negative-overflow","2026-11-01","Example","General","Card payment",Long.MIN_VALUE)));
        // Returned JSON is independent of source evidence; labels-off policy cannot be enabled by a tool argument.
        ok(rows.get(0).name.equals("Synthetic Market"));ok(rows.get(0).raw.get("Notes and #tags").equals("synthetic-private-note-token"));
        System.out.println(checks+" companion tool checks passed; all synthetic and offline.");
    }
}
