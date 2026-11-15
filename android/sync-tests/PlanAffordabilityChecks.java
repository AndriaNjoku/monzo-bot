package org.monzobot.app;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic-only deterministic checks; run as a JVM main. */
public final class PlanAffordabilityChecks {
    private static int checks;
    private static final LocalDate TODAY=LocalDate.of(2032,6,12);
    private static final Instant NOW=Instant.parse("2032-06-12T12:00:00Z");
    private static final String FROM="2032-06-01",TO="2032-06-30",FRESH="2032-06-12T11:00:00Z";
    private static ConversationBudget.Proposal proposal(long amount,ConversationBudget.Mode mode) {
        return new ConversationBudget.Proposal("22222222-2222-4222-8222-222222222222","Tentative synthetic purchase","Synthetic item","Flexible",FROM,TO,amount,mode,TODAY.toString());
    }
    private static CompanionPlanner.Plan plan(long limit,Long balance,String date,boolean complete,List<CompanionPlanner.Commitment> commitments) {
        return new CompanionPlanner.Plan(List.of(new CompanionPlanner.Budget("Flexible",FROM,TO,limit,false)),commitments,balance,date,500,complete,List.of());
    }
    private static Finance.Transaction row(String id,String date,String category,String name,String type,long amount) {
        return new Finance.Transaction(id,date,name,category,type,"GBP",amount,Map.of());
    }
    private static PlanAffordability.Result assess(ConversationBudget.Proposal p,List<Finance.Transaction> h,CompanionPlanner.Plan plan,JSONObject ledger,String freshness) {
        return PlanAffordability.assess(p,h,Map.of(),plan,ledger,freshness,TODAY,NOW);
    }
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    private static JSONObject ledger(ConversationBudget.Proposal p) throws Exception {
        return ConversationBudget.apply(new JSONObject().put("companionPlan",BudgetWorkspace.encode(plan(10000,20000L,TODAY.toString(),true,List.of()))),p,true).getJSONObject("conversationBudgets");
    }
    public static void main(String[] args) throws Exception {
        ConversationBudget.Proposal within=proposal(2000,ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION);
        List<Finance.Transaction> history=new ArrayList<>();
        for(int i=0;i<1505;i++) history.add(row("synthetic-"+i,"2032-06-10","Flexible","Synthetic merchant","Card payment",-1));
        history.add(row("credit","2032-06-11","Flexible","Synthetic credit","Faster payment",700));
        history.add(row("pot","2032-06-11","Flexible","Synthetic pot","PoT TrAnSfEr",-9000));
        history.add(row("future","2032-07-01","Flexible","Synthetic future","Card payment",-9999));
        CompanionPlanner.Plan complete=plan(10000,20000L,TODAY.toString(),true,List.of());
        PlanAffordability.Result r=assess(within,history,complete,null,FRESH);
        check(r.cannotConfirmAffordability&&!r.bankCoverageKnown,"never bank affordability");
        check(r.grossDebitsPence==1505&&r.creditsPence==700,"entire history, pot excluded, credits separate");
        check(r.sourceCutoffDate.equals("2032-06-11"),"future source cutoff excluded");
        check(r.allocationRemainingAfterPence==6495,"gross allocation remaining");
        check(r.conditionalCashAfterPence==17500,"cash subtracts reserve and purchase, not allocation");
        check(r.decision.equals("conditional_allocation_fit"),"allocation only fit");
        check(r.retrievalStatus.equals("recent"),"fresh import status");
        check(r.warnings.stream().anyMatch(x->x.contains("does not prove")),"import not bank freshness");
        r=assess(within,history,complete,null,"2032-06-01T10:00:00Z");
        check(r.retrievalStatus.equals("stale")&&r.cannotConfirmAffordability,"stale never confirms");
        r=assess(within,history,complete,null,"invalid"); check(r.retrievalStatus.equals("unknown"),"invalid timestamp unknown");
        r=assess(within,history,complete,null,"2032-06-13T00:00:00Z"); check(r.retrievalStatus.equals("unknown"),"future retrieval unknown");
        r=assess(within,List.of(),plan(10000,null,"",true,List.of()),null,FRESH);
        check(r.conditionalCashAfterPence==null&&r.availablePositiveReportedBalancePence==null,"no manual balance no cash");
        check(r.sourceCutoffDate==null,"empty cutoff unknown");
        r=assess(within,history,plan(10000,20000L,"2032-06-11",true,List.of()),null,FRESH);
        check(r.balanceStatus.equals("stale")&&r.conditionalCashAfterPence==null,"stale balance withheld");
        r=assess(within,history,plan(10000,20000L,TODAY.toString(),false,List.of()),null,FRESH);
        check(r.conditionalCashAfterPence==null,"incomplete commitments withheld");
        r=assess(within,history,plan(10000,-5000L,TODAY.toString(),true,List.of()),null,FRESH);
        check(r.negativeReportedBalance&&r.availablePositiveReportedBalancePence==0,"negative balance not overdraft cash");
        check(r.conditionalCashAfterPence==null&&r.conditionalCashShortfallPence==null,"negative balance debt not dropped from cash shortfall");
        check(r.allocationRemainingAfterPence!=null&&!r.overlapAmbiguous,"negative balance preserves tentative allocation review");
        r=assess(proposal(2000,ConversationBudget.Mode.ADD_ALLOCATION),history,complete,null,FRESH);
        check(r.allocationLimitAfterPence==12000&&r.allocationRemainingAfterPence==8495,"raised budget includes purchase once");
        JSONObject saved=ledger(within);
        r=assess(within,history,complete,saved,FRESH);
        check(r.alreadySaved&&r.existingReservedPence==2000&&r.allocationRemainingAfterPence==6495,"saved reservation not doubled");
        check(r.conditionalCashAfterPence==17500,"saved cash provision not doubled");
        ConversationBudget.Proposal add=proposal(2000,ConversationBudget.Mode.ADD_ALLOCATION);
        r=assess(add,history,plan(12000,20000L,TODAY.toString(),true,List.of()),ledger(add),FRESH);
        check(r.allocationLimitAfterPence==12000&&r.allocationRemainingAfterPence==8495,"saved raised budget not raised twice");
        ConversationBudget.Proposal other=new ConversationBudget.Proposal("33333333-3333-4333-8333-333333333333","Synthetic other","Other item","Flexible",FROM,TO,1000,ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION,TODAY.toString());
        r=assess(within,history,complete,ledger(other),FRESH);
        check(r.existingReservedPence==1000&&r.allocationRemainingAfterPence==5495,"other reserve separate");
        check(r.conditionalCashAfterPence==16500,"other provision in cash once");
        r=assess(within,history,plan(2000,20000L,TODAY.toString(),true,List.of()),null,FRESH);
        check(r.allocationShortfallPence==1505&&r.decision.equals("conditional_allocation_shortfall"),"conditional allocation shortfall");
        ConversationBudget.Proposal future=new ConversationBudget.Proposal("44444444-4444-4444-8444-444444444444","Synthetic future","Future item","Flexible","2032-07-01","2032-07-31",1000,ConversationBudget.Mode.ADD_ALLOCATION,TODAY.toString());
        r=assess(future,history,complete,null,FRESH);
        check(r.conditionalCashAfterPence==null&&r.warnings.stream().anyMatch(x->x.contains("Future income")),"no future income extrapolation");
        CompanionPlanner.Commitment duplicate=new CompanionPlanner.Commitment("Synthetic item","2032-06-20",2000,true);
        r=assess(within,history,plan(10000,20000L,TODAY.toString(),true,List.of(duplicate)),null,FRESH);
        check(r.overlapAmbiguous&&r.conditionalCashAfterPence==null&&r.decision.equals("needs_review"),"duplicate liability no silent reconciliation");
        CompanionPlanner.Commitment distinct=new CompanionPlanner.Commitment("Confirmed obligation","2032-06-20",3000,true);
        r=assess(within,history,plan(10000,20000L,TODAY.toString(),true,List.of(distinct)),null,FRESH);
        check(!r.overlapAmbiguous&&r.conditionalCashAfterPence==14500,"distinct confirmed liability counted");
        history.add(row("possible-purchase","2032-06-12","Flexible","Synthetic item","Card payment",-2000));
        r=assess(within,history,complete,null,FRESH); check(r.overlapAmbiguous&&r.conditionalCashAfterPence==null&&r.allocationRemainingAfterPence==null,"possibly already paid purchase review");
        List<Finance.Transaction> correction=List.of(row("correction","2032-06-10","Other","Merchant","Payment",-1200));
        r=PlanAffordability.assess(within,correction,Map.of("correction","Flexible"),complete,null,FRESH,TODAY,NOW);
        check(r.grossDebitsPence==1200,"local category override");
        List<Finance.Transaction> transfers=List.of(row("external","2032-06-10","Flexible","Merchant","Transfer",-1300));
        r=assess(within,transfers,complete,null,FRESH); check(r.grossDebitsPence==1300,"nonpot external transfers count");
        List<Finance.Transaction> duplicateRows=List.of(transfers.get(0),transfers.get(0));
        r=assess(within,duplicateRows,complete,null,FRESH); check(r.grossDebitsPence==1300,"same row repeated once");
        boolean conflict=false; try { assess(within,List.of(transfers.get(0),row("external","2032-06-10","Flexible","Merchant","Transfer",-1)),complete,null,FRESH); } catch(IllegalArgumentException e){conflict=true;} check(conflict,"conflicting ids rejected");
        boolean overflow=false; try { assess(within,List.of(row("overflow","2032-06-10","Flexible","Merchant","Payment",Long.MIN_VALUE)),complete,null,FRESH); } catch(ArithmeticException e){overflow=true;} check(overflow,"exact arithmetic fails closed");
        boolean immutable=false; try {r.warnings.add("mutate");}catch(UnsupportedOperationException e){immutable=true;} check(immutable,"immutable result");
        List<Finance.Transaction> priorPaid=List.of(row("prior-paid","2032-06-10","Flexible","Unrelated source name","Payment",-1000));
        r=assess(within,priorPaid,complete,ledger(other),FRESH);
        check(r.overlapAmbiguous&&r.allocationRemainingAfterPence==null&&r.conditionalCashAfterPence==null,"saved provision versus imported same-amount debit unresolved");
        r=assess(within,List.of(row("outside","2032-05-31","Flexible","Outside period","Payment",-1000)),complete,ledger(other),FRESH);
        check(!r.overlapAmbiguous&&r.grossDebitsPence==0,"outside-period debit does not consume provision");
        JSONObject cancelled=ledger(within);
        cancelled.getJSONArray("records").getJSONObject(0).put("status","cancelled");
        r=assess(within,List.of(),complete,cancelled,FRESH);
        check(r.alreadySaved&&r.decision.equals("needs_review")&&r.allocationRemainingAfterPence==null&&r.conditionalCashAfterPence==null,"cancelled id cannot be provisioned again");
        JSONObject broken=ledger(other); broken.getJSONArray("records").getJSONObject(0).put("payloadSha256","invalid");
        boolean tampered=false; try {assess(within,List.of(),complete,broken,FRESH);}catch(IllegalArgumentException e){tampered=true;} check(tampered,"ledger integrity validated");
        r=assess(within,List.of(),plan(10000,20000L,"2032-06-13",true,List.of()),null,FRESH);
        check(r.balanceStatus.equals("unknown")&&r.conditionalCashAfterPence==null,"future balance date unknown");
        CompanionPlanner.Plan noAllocation=new CompanionPlanner.Plan(List.of(),List.of(),20000L,TODAY.toString(),0,true,List.of());
        r=assess(within,List.of(),noAllocation,null,FRESH);
        check(r.allocationRemainingAfterPence==null&&r.decision.equals("needs_review"),"within mode needs exact allocation");
        ConversationBudget.Proposal ended=new ConversationBudget.Proposal("55555555-5555-4555-8555-555555555555","Synthetic past","Past item","Flexible","2032-05-01","2032-05-31",1000,ConversationBudget.Mode.ADD_ALLOCATION,TODAY.toString());
        r=assess(ended,List.of(),complete,null,FRESH);
        check(r.decision.equals("needs_review")&&r.conditionalCashAfterPence==null,"ended window not current spending guide");
        CompanionPlanner.Plan overlapPeriod=new CompanionPlanner.Plan(List.of(new CompanionPlanner.Budget("Flexible","2032-06-05","2032-06-25",10000,true)),List.of(),null,"",0,false,List.of());
        r=assess(within,List.of(),overlapPeriod,null,FRESH);
        check(r.decision.equals("needs_review")&&r.allocationRemainingAfterPence==null,"partial period conflict requires review");
        ConversationBudget.Proposal previousDates=new ConversationBudget.Proposal("66666666-6666-4666-8666-666666666666","Synthetic previous period","Prior item","Flexible","2032-06-05","2032-06-25",1000,ConversationBudget.Mode.ADD_ALLOCATION,TODAY.toString());
        JSONObject previousDateData=ConversationBudget.apply(new JSONObject(),previousDates,true);
        r=assess(within,List.of(),complete,previousDateData,FRESH);
        check(r.overlapAmbiguous&&r.decision.equals("needs_review"),"edited budget period overlapping provision ambiguous");
        check(r.allocationRemainingBeforePence==null&&r.allocationRemainingAfterPence==null&&r.conditionalCashAfterPence==null,"overlapping provision window combined figures withheld");
        check(r.warnings.stream().anyMatch(x->x.contains("different date window")),"overlapping provision warning explicit");
        r=assess(proposal(2000,ConversationBudget.Mode.ADD_ALLOCATION),List.of(),complete,previousDateData,FRESH);
        check(r.overlapAmbiguous&&r.allocationRemainingAfterPence==null,"add mode cannot silently reshape earlier provision either");
        List<Finance.Transaction> blankOverride=List.of(row("blank-override","2032-06-10","Flexible","Blank correction","Payment",-1200));
        r=PlanAffordability.assess(within,blankOverride,Map.of("blank-override","   "),complete,null,FRESH,TODAY,NOW);
        check(r.grossDebitsPence==1200,"blank correction falls back consistently with save");
        ConversationBudget.Proposal staleReview=new ConversationBudget.Proposal("77777777-7777-4777-8777-777777777777","Synthetic stale review","Stale item","Flexible",FROM,TO,1000,ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION,"2032-06-11");
        r=assess(staleReview,List.of(),complete,null,FRESH);
        check(r.decision.equals("needs_review")&&r.allocationRemainingAfterPence==null&&r.conditionalCashAfterPence==null,"stale proposal review date cannot claim preview-save agreement");
        CompanionPlanner.Commitment overdue=new CompanionPlanner.Commitment("Overdue synthetic obligation","2032-06-11",3000,true);
        r=assess(within,List.of(),plan(10000,20000L,TODAY.toString(),true,List.of(overdue)),null,FRESH);
        check(r.conditionalCashAfterPence==null&&r.conditionalCashShortfallPence==null,"overdue confirmed liability not presumed paid");
        check(r.allocationRemainingAfterPence==8000&&!r.overlapAmbiguous,"overdue cash review does not erase allocation calculation");
        check(r.warnings.stream().anyMatch(x->x.contains("no recorded paid status")),"overdue status warning");
        CompanionPlanner.Commitment unconfirmed=new CompanionPlanner.Commitment("Unconfirmed synthetic obligation","2032-06-20",3000,false);
        r=assess(within,List.of(),plan(10000,20000L,TODAY.toString(),true,List.of(unconfirmed)),null,FRESH);
        check(r.conditionalCashAfterPence==null&&r.allocationRemainingAfterPence==8000,"unconfirmed in horizon withholds cash only");
        check(r.warnings.stream().anyMatch(x->x.contains("unconfirmed commitment")),"unconfirmed liability warning");
        CompanionPlanner.Commitment oldUnconfirmed=new CompanionPlanner.Commitment("Old unconfirmed synthetic obligation","2032-05-01",3000,false);
        r=assess(within,List.of(),plan(10000,20000L,TODAY.toString(),true,List.of(oldUnconfirmed)),null,FRESH);
        check(r.conditionalCashAfterPence==null,"old unconfirmed entry remains unresolved");
        CompanionPlanner.Commitment laterUnconfirmed=new CompanionPlanner.Commitment("Later synthetic obligation","2032-07-20",3000,false);
        r=assess(within,List.of(),plan(10000,20000L,TODAY.toString(),true,List.of(laterUnconfirmed)),null,FRESH);
        check(r.conditionalCashAfterPence==17500,"unconfirmed beyond explicit horizon not included");
        r=assess(within,List.of(),plan(10000,1000L,TODAY.toString(),true,List.of()),null,FRESH);
        check(r.conditionalCashAfterPence==-1500&&r.conditionalCashShortfallPence==1500,"nonnegative balance retains conditional user-input shortfall");
        System.out.println("PlanAffordabilityChecks: "+checks+" passed");
    }
}
