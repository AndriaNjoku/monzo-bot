package org.monzobot.app;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only review of tentative allocations. An export cannot establish bank affordability. */
public final class PlanAffordability {
    private PlanAffordability() {}
    public static final long RETRIEVAL_FRESHNESS_HOURS = 24;

    public static final class Result {
        public final boolean cannotConfirmAffordability = true, bankCoverageKnown = false, estimate = true;
        public final boolean alreadySaved, overlapAmbiguous, negativeReportedBalance;
        public final String decision, retrievalStatus, sourceCutoffDate, balanceStatus;
        public final String cashScope = "conditional_user_reported_positive_cash_after_known_commitments_reserve_and_unreconciled_purchase_provisions_before_other_spending";
        public final String balanceSource = "user_reported_not_bank_verified";
        public final String proposalFrom, proposalTo, assessedOn;
        public final long proposalPence, grossDebitsPence, creditsPence, existingReservedPence;
        public final Long allocationLimitBeforePence, allocationLimitAfterPence;
        public final Long allocationRemainingBeforePence, allocationRemainingAfterPence, allocationShortfallPence;
        public final Long availablePositiveReportedBalancePence, knownCommitmentsPence, reservePence;
        public final Long conditionalCashAfterPence, conditionalCashShortfallPence;
        public final List<String> explanations, warnings;
        private Result(State s) {
            alreadySaved=s.saved; overlapAmbiguous=s.ambiguous; negativeReportedBalance=s.negative;
            decision=s.decision; retrievalStatus=s.freshness; sourceCutoffDate=s.cutoff;
            balanceStatus=s.balanceStatus; proposalFrom=s.p.from; proposalTo=s.p.to; assessedOn=s.today.toString();
            proposalPence=s.p.amountPence; grossDebitsPence=s.debits; creditsPence=s.credits; existingReservedPence=s.reserved;
            allocationLimitBeforePence=s.beforeLimit; allocationLimitAfterPence=s.afterLimit;
            allocationRemainingBeforePence=s.before; allocationRemainingAfterPence=s.after;
            allocationShortfallPence=s.shortfall; availablePositiveReportedBalancePence=s.positiveBalance;
            knownCommitmentsPence=s.commitments; reservePence=s.reserve;
            conditionalCashAfterPence=s.cashAfter; conditionalCashShortfallPence=s.cashShortfall;
            explanations=Collections.unmodifiableList(new ArrayList<>(s.explanations));
            warnings=Collections.unmodifiableList(new ArrayList<>(s.warnings));
        }
    }
    private static final class State {
        ConversationBudget.Proposal p; LocalDate today;
        boolean saved, ambiguous, negative, cancelled, cashLiabilitiesUnresolved;
        String decision="needs_review",freshness,cutoff,balanceStatus;
        long debits,credits,reserved;
        Long beforeLimit,afterLimit,before,after,shortfall,positiveBalance,commitments,reserve,cashAfter,cashShortfall;
        final List<String> explanations=new ArrayList<>(),warnings=new ArrayList<>();
    }

    /** savedPlans is the local conversationBudgets object, or the enclosing workspace object. */
    public static Result assess(ConversationBudget.Proposal proposal, List<Finance.Transaction> history,
            Map<String,String> overrides, CompanionPlanner.Plan plan, JSONObject savedPlans,
            String retrievedAt, LocalDate today, Instant now) {
        Objects.requireNonNull(proposal,"proposal"); Objects.requireNonNull(history,"history");
        Objects.requireNonNull(overrides,"overrides"); Objects.requireNonNull(plan,"plan");
        Objects.requireNonNull(today,"today"); Objects.requireNonNull(now,"now");
        State s=new State(); s.p=proposal; s.today=today; s.freshness=freshness(now,retrievedAt);
        s.explanations.add("This is a tentative allocation review, not confirmation of affordability or a payment instruction.");
        s.warnings.add("Budget remaining is an allocation, not available cash. Bank coverage and pending payments are unknown from an export.");
        s.explanations.add("Proposal window: "+proposal.from+" to "+proposal.to+" inclusive; assessed on "+today+".");
        if (!s.freshness.equals("recent")) s.warnings.add("The export retrieval is "+s.freshness+"; recorded spending may be incomplete.");
        else s.warnings.add("Recent retrieval only dates the import. It does not prove the bank data is current.");

        Map<String,Finance.Transaction> unique=new LinkedHashMap<>();
        for (Finance.Transaction t:history) {
            Objects.requireNonNull(t,"transaction");
            Finance.Transaction old=unique.putIfAbsent(t.id,t);
            if (old!=null && (!old.date.equals(t.date)||old.amount!=t.amount||!old.name.equals(t.name)
                    ||!old.category.equals(t.category)||!old.type.equals(t.type)||!old.raw.equals(t.raw)))
                throw new IllegalArgumentException("Conflicting imported transaction identifiers");
            if (!LocalDate.parse(t.date).isAfter(today) && (s.cutoff==null || t.date.compareTo(s.cutoff)>0)) s.cutoff=t.date;
        }
        if(s.cutoff==null) s.warnings.add("No observed transaction date is available on or before the assessment date.");
        else s.explanations.add("Latest observed transaction date: "+s.cutoff+". This is an observation cutoff, not verified bank coverage.");
        for(Finance.Transaction t:unique.values()) {
            if(LocalDate.parse(t.date).isAfter(today)||CompanionInsights.isPotMovement(t)
                    ||t.date.compareTo(proposal.from)<0||t.date.compareTo(proposal.to)>0) continue;
            if(normal(category(t,overrides)).equals(normal(proposal.category))) {
                if(t.amount<0) s.debits=Math.addExact(s.debits,Math.negateExact(t.amount));
                if(t.amount>0) s.credits=Math.addExact(s.credits,t.amount);
            }
            if(t.amount<0 && Math.negateExact(t.amount)==proposal.amountPence && (normal(t.name).equals(normal(proposal.name)) || normal(category(t,overrides)).equals(normal(proposal.category)))) {
                s.ambiguous=true;
                s.warnings.add("An imported debit may already represent this proposed purchase. Reconcile it locally before combining cash figures.");
            }
        }
        s.explanations.add("Recorded category debits: "+s.debits+" GBP pence; credits kept separate: "+s.credits+" GBP pence. Credits are not assumed to be refunds or future income.");
        JSONArray records=records(savedPlans);
        long otherCashProvisions=0;
        for(int i=0;i<records.length();i++) {
            JSONObject record=records.optJSONObject(i);
            if(record==null) throw new IllegalArgumentException("Invalid saved conversation record");
            JSONObject encoded=record.optJSONObject("proposal");
            if(encoded==null) throw new IllegalArgumentException("Missing saved proposal");
            ConversationBudget.Proposal saved=ConversationBudget.decode(encoded);
            boolean same=saved.id.equals(proposal.id);
            if(same) {
                if(!sameProposal(saved,proposal)) throw new IllegalArgumentException("Saved proposal identity differs from the reviewed proposal");
                if(s.saved) throw new IllegalArgumentException("Duplicate saved proposal identity");
                s.saved=true;
                if (!"active".equals(record.optString("status"))) s.cancelled=true;
            }
            if (!"active".equals(record.optString("status"))) continue;
            if (normal(saved.category).equals(normal(proposal.category))
                    && saved.from.compareTo(proposal.to)<=0 && saved.to.compareTo(proposal.from)>=0
                    && (!saved.from.equals(proposal.from)||!saved.to.equals(proposal.to))) {
                s.ambiguous=true;
                s.warnings.add("An active prior provision in this category overlaps a different date window. Review existing plan dates explicitly; its provision has not been reshaped, linked or assumed fulfilled.");
            }
            if(normal(saved.category).equals(normal(proposal.category))&&saved.from.equals(proposal.from)&&saved.to.equals(proposal.to))
                s.reserved=Math.addExact(s.reserved,saved.amountPence);
            for (Finance.Transaction t:unique.values()) {
                if (!LocalDate.parse(t.date).isAfter(today) && !CompanionInsights.isPotMovement(t) && t.amount<0
                        && t.date.compareTo(saved.from)>=0 && t.date.compareTo(saved.to)<=0
                        && Math.negateExact(t.amount)==saved.amountPence
                        && (normal(t.name).equals(normal(saved.name)) || normal(category(t,overrides)).equals(normal(saved.category)))) {
                    s.ambiguous=true;
                }
            }
            if(!same && saved.to.compareTo(today.toString())>=0 && saved.from.compareTo(proposal.to)<=0) {
                otherCashProvisions=Math.addExact(otherCashProvisions,saved.amountPence);
                if(possibleOverlap(saved,plan,today,proposal.to)) s.ambiguous=true;
                if(saved.amountPence==proposal.amountPence && normal(saved.name).equals(normal(proposal.name))
                        &&saved.from.compareTo(proposal.to)<=0&&saved.to.compareTo(proposal.from)>=0) s.ambiguous=true;
            }
        }
        CompanionPlanner.Budget matched=null;
        boolean periodConflict=false;
        for(CompanionPlanner.Budget b:plan.budgets) if(normal(b.category).equals(normal(proposal.category))) {
            if(b.start.equals(proposal.from)&&b.end.equals(proposal.to)) matched=b;
            else if(b.start.compareTo(proposal.to)<=0&&b.end.compareTo(proposal.from)>=0) periodConflict=true;
        }
        if(periodConflict) s.warnings.add("A category allocation overlaps a different date window. Review the periods explicitly before saving.");
        if(!periodConflict && (matched!=null || proposal.mode==ConversationBudget.Mode.ADD_ALLOCATION)) {
            s.beforeLimit=matched==null?0L:matched.limitPence;
            s.afterLimit=(!s.saved && proposal.mode==ConversationBudget.Mode.ADD_ALLOCATION)
                    ?Math.addExact(s.beforeLimit,proposal.amountPence):s.beforeLimit;
            s.before=Math.subtractExact(Math.subtractExact(s.beforeLimit,s.debits),s.reserved);
            long newProvision=s.saved?0:proposal.amountPence;
            s.after=Math.subtractExact(Math.subtractExact(Math.subtractExact(s.afterLimit,s.debits),s.reserved),newProvision);
            s.shortfall=s.after<0?Math.negateExact(s.after):0L;
            s.decision=s.after>=0?"conditional_allocation_fit":"conditional_allocation_shortfall";
            s.explanations.add("Conditional allocation calculation in GBP pence: limit "+s.afterLimit+" minus recorded gross debits "+s.debits+" minus existing purchase provisions "+s.reserved+" minus additional provision "+newProvision+" = "+s.after+". The purchase is reserved once; an increased limit already contains it.");
        } else s.warnings.add("No matching exact-period allocation is available for this within-allocation proposal.");
        if(s.saved) s.explanations.add("This proposal is already saved. Its provision and any allocation increase are not applied again.");
        if(LocalDate.parse(proposal.to).isBefore(today)) {
            s.decision="needs_review"; s.warnings.add("The proposed window has ended; this is a historical allocation, not a current spending guide.");
        }
        s.balanceStatus=plan.reportedBalancePence==null?"unknown":today.toString().equals(plan.reportedBalanceDate)?"current"
                :plan.reportedBalanceDate.compareTo(today.toString())<0?"stale":"unknown";
        if(s.balanceStatus.equals("current")) {
            s.negative=plan.reportedBalancePence<0;
            s.positiveBalance=Math.max(0L,plan.reportedBalancePence);
            if(s.negative) {
                s.cashLiabilitiesUnresolved=true;
                s.warnings.add("The current user-reported balance is negative. Available positive cash is clamped to zero; an overdraft is not income or cash. Existing negative-balance debt remains unresolved, so combined cash and shortfall figures are withheld rather than dropping that liability.");
            }
            s.explanations.add("Manual balance date: "+plan.reportedBalanceDate+"; this input is user-reported and not bank verified.");
        } else s.warnings.add("A same-day user-reported balance is missing or stale. No combined cash figure can be shown.");
        if(!plan.commitmentsComplete) s.warnings.add("The user has not confirmed that the commitment list is complete. No combined cash figure can be shown.");
        LocalDate horizon=LocalDate.parse(proposal.to).isBefore(today)?today:LocalDate.parse(proposal.to);
        long commitments=0;
        for(CompanionPlanner.Commitment c:plan.commitments) {
            if(c.dueDate.compareTo(horizon.toString())>0) continue;
            if(c.confirmed&&c.dueDate.compareTo(today.toString())<0) {
                s.cashLiabilitiesUnresolved=true;
                s.warnings.add("A confirmed commitment is overdue and has no recorded paid status. Review whether it remains owed; it has not been assumed paid or silently removed from cash liabilities.");
            } else if(!c.confirmed) {
                s.cashLiabilitiesUnresolved=true;
                s.warnings.add("An unconfirmed commitment is due on or before the assessment horizon. Review whether it is a liability; combined cash figures are withheld rather than treating it as paid or excluding it silently.");
            } else commitments=Math.addExact(commitments,c.amountPence);
        }
        s.commitments=commitments; s.reserve=plan.reservePence;
        if(possibleOverlap(proposal,plan,today,horizon.toString())) s.ambiguous=true;
        boolean futureWindow=LocalDate.parse(proposal.from).isAfter(today);
        if(futureWindow) s.warnings.add("This is a future window. Future income and future cash balances are unknown; current cash is not projected into that window.");
        boolean staleReview=!proposal.reviewedOn.equals(today.toString());
        if(staleReview) {
            s.decision="needs_review"; s.before=null; s.after=null; s.shortfall=null;
            s.explanations.removeIf(x -> x.startsWith("Conditional allocation calculation"));
            s.warnings.add("This proposal was reviewed on "+proposal.reviewedOn+". Review it again on the assessment date before saving; preview and save must use the same recorded-spending cutoff.");
        }
        if(s.cancelled) {
            s.decision="needs_review"; s.before=null; s.after=null; s.shortfall=null;
            s.explanations.removeIf(x -> x.startsWith("Conditional allocation calculation"));
            s.warnings.add("This review token belongs to a cancelled saved proposal. It cannot create another allocation; review a new proposal explicitly.");
        }
        if(s.ambiguous) {
            s.decision="needs_review";
            s.before=null; s.after=null; s.shortfall=null;
            s.explanations.removeIf(x -> x.startsWith("Conditional allocation calculation"));
            s.warnings.add("Possible overlap between a purchase provision, saved provision, imported payment or commitment needs explicit reconciliation. Combined cash figures are withheld rather than counting or removing a liability silently.");
        }
        if(s.positiveBalance!=null&&plan.commitmentsComplete&&!s.cashLiabilitiesUnresolved&&!s.ambiguous&&!s.cancelled&&!staleReview&&!futureWindow&&!LocalDate.parse(proposal.to).isBefore(today)) {
            long provisions=Math.addExact(otherCashProvisions,proposal.amountPence);
            s.cashAfter=Math.subtractExact(Math.subtractExact(Math.subtractExact(s.positiveBalance,commitments),plan.reservePence),provisions);
            s.cashShortfall=s.cashAfter<0?Math.negateExact(s.cashAfter):0L;
            s.explanations.add("Conditional cash calculation using current user inputs only, through "+horizon+": positive reported cash "+s.positiveBalance+" minus confirmed known commitments "+commitments+" minus reserve "+plan.reservePence+" minus purchase provisions "+provisions+" = "+s.cashAfter+" GBP pence. Other budget allocations and unrecorded spending are not included. No future income is assumed.");
            if(s.cashShortfall>0) s.warnings.add("There is a conditional shortfall under these user inputs; this is not a verified bank shortfall.");
        }
        return new Result(s);
    }
    private static boolean possibleOverlap(ConversationBudget.Proposal p,CompanionPlanner.Plan plan,LocalDate today,String horizon) {
        for(CompanionPlanner.Commitment c:plan.commitments)
            if(c.confirmed&&c.dueDate.compareTo(today.toString())>=0&&c.dueDate.compareTo(horizon)<=0
                    &&c.dueDate.compareTo(p.from)>=0&&c.dueDate.compareTo(p.to)<=0
                    &&(c.amountPence==p.amountPence||normal(c.label).equals(normal(p.name))||normal(c.label).equals(normal(p.category)))) return true;
        return false;
    }
    private static JSONArray records(JSONObject supplied) {
        if(supplied==null) return new JSONArray();
        if(supplied.has("conversationBudgets")) return ConversationBudget.records(supplied);
        if(!supplied.has("records")) return ConversationBudget.records(supplied);
        try { return ConversationBudget.records(new JSONObject().put("conversationBudgets", supplied)); }
        catch(org.json.JSONException e) { throw new IllegalArgumentException("Invalid saved conversation ledger"); }
    }
    private static boolean sameProposal(ConversationBudget.Proposal a,ConversationBudget.Proposal b) {
        return a.name.equals(b.name)&&a.category.equals(b.category)&&a.from.equals(b.from)&&a.to.equals(b.to)
                &&a.amountPence==b.amountPence&&a.mode==b.mode&&a.reviewedOn.equals(b.reviewedOn)&&a.originalText.equals(b.originalText);
    }
    private static String freshness(Instant now,String retrievedAt) {
        if(retrievedAt==null||retrievedAt.trim().isEmpty()) return "unknown";
        try { Instant retrieved=Instant.parse(retrievedAt); if(retrieved.isAfter(now)) return "unknown";
            return Duration.between(retrieved,now).compareTo(Duration.ofHours(RETRIEVAL_FRESHNESS_HOURS))>0?"stale":"recent";
        } catch(java.time.format.DateTimeParseException e) { return "unknown"; }
    }
    private static String category(Finance.Transaction t,Map<String,String> overrides) {
        String correction=overrides.get(t.id);
        return correction==null||correction.trim().isEmpty()?t.category:correction.trim();
    }
    private static String normal(String value) { return value==null?"":value.trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT); }
}
