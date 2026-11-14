package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ConversationBudgetChecks {
    private static int checks;
    private static final LocalDate TODAY = LocalDate.parse("2026-11-14");
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Conversation budget check " + checks); }
    private static void fails(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected review rejection"); }
    private static void message(Runnable action, String fragment) {
        checks++; try { action.run(); } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(fragment)) throw new AssertionError("Expected review message: " + fragment + ", got " + expected.getMessage()); return;
        } throw new AssertionError("Expected review rejection");
    }
    private static JSONObject row(String id, long amount, String type) throws org.json.JSONException {
        return new JSONObject().put("id", id).put("date", "2026-11-10").put("name", "Synthetic merchant")
                .put("category", "Shopping").put("type", type).put("amount", amount)
                .put("raw", new JSONObject().put("Type", type).put("Emoji", "🚲"));
    }
    private static JSONObject workspace() throws org.json.JSONException {
        CompanionPlanner.Plan plan = new CompanionPlanner.Plan(List.of(new CompanionPlanner.Budget("Shopping", "2026-11-01", "2026-11-30", 20000, true)),
                List.of(new CompanionPlanner.Commitment("Synthetic bill", "2026-11-20", 5000, true)), null, "", 1000, false, List.of("Synthetic priority"));
        JSONObject data = new JSONObject().put("transactions", new JSONArray().put(row("synthetic-debit", -4000, "Card payment"))
                .put(row("synthetic-credit", 10000, "Faster payment")).put(row("synthetic-pot", -50000, "Pot transfer")))
                .put("overrides", new JSONObject()).put("note", "untouched synthetic data");
        return BudgetWorkspace.write(data, plan);
    }
    private static ConversationBudget.Proposal proposal(long amount, ConversationBudget.Mode mode) {
        return ConversationBudget.review(ConversationBudget.parse("This month I might buy a bicycle for £120", TODAY),
                "Bicycle", "Shopping", "2026-11-01", "2026-11-30", amount, mode, TODAY);
    }
    private static String hash(ConversationBudget.Proposal p) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String value : new String[]{"conversation-budget-v1",p.id,p.originalText,p.name,p.category,p.from,p.to,Long.toString(p.amountPence),p.mode.name(),p.reviewedOn}) {
            byte[] b=value.getBytes(StandardCharsets.UTF_8);digest.update((byte)(b.length>>>24));digest.update((byte)(b.length>>>16));digest.update((byte)(b.length>>>8));digest.update((byte)b.length);digest.update(b);
        }
        StringBuilder out = new StringBuilder();for(byte b:digest.digest())out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public static void main(String[] args) throws Exception {
        String verbatim = "  Next month I might buy a bicycle for £120.  ";
        ConversationBudget.Draft draft = ConversationBudget.parse(verbatim, TODAY);
        check(draft.originalText.equals(verbatim)); check(draft.tentative); check(draft.amountPence == 12000);
        check(draft.from.equals("2026-12-01")); check(draft.to.equals("2026-12-31")); check(draft.issues.isEmpty());
        check(ConversationBudget.parse("This month a bicycle for GBP 120.50", TODAY).amountPence == 12050);
        check(ConversationBudget.parse("This month a bicycle for 120 pounds", TODAY).amountPence == 12000);
        check(ConversationBudget.parse("This month a bicycle for £1,200.50", TODAY).amountPence == 120050);
        for (String text : List.of("This month a bicycle", "This month £120 or £150", "This month €120", "This month £120 and $20",
                "This month £120-150", "This month 120-150 pounds", "This month £120 to 150", "This month around £120",
                "This month about 120 pounds", "This month £120.999", "This month £1,20", "This month £120..50", "This month £120k",
                "This month £-120", "This month minus £120", "This month - £120", "This month I owe £120", "This month refund £120")) {
            ConversationBudget.Draft bad = ConversationBudget.parse(text, TODAY); check(bad.amountPence == null); check(!bad.issues.isEmpty());
        }
        check(ConversationBudget.parse("A bicycle for £120", TODAY).from == null);
        check(ConversationBudget.parse("This month a bicycle for £120", TODAY).from.equals("2026-11-01"));
        ConversationBudget.Draft leap = ConversationBudget.parse("Next month £120", LocalDate.parse("2024-01-31")); check(leap.to.equals("2024-02-29"));
        ConversationBudget.Draft exact = ConversationBudget.parse("£120 between 2026-12-01 and 2026-12-20", TODAY);
        check(exact.from.equals("2026-12-01")); check(exact.to.equals("2026-12-20")); check(exact.amountPence == 12000);
        check(ConversationBudget.parse("£120 on 2026-12-15", TODAY).from.equals("2026-12-15"));
        for (String text : List.of("Next month and this month £120", "Next month on 2026-12-15 £120", "£120 on 2026-02-30", "£120 from 2026-12-20 to 2026-12-01", "£120 on 2026-12-01 or 2026-12-15")) check(ConversationBudget.parse(text, TODAY).from == null);
        for (String text : List.of("£120 from 1/12/2026 to 2026-12-31", "£120 from 2026-12-01 to 31/12/2026",
                "Next month £120 on Tuesday", "Next month £120 on 26-12-01", "£120 from 2026-1-2 to 2026-12-31")) {
            ConversationBudget.Draft ambiguousDate = ConversationBudget.parse(text, TODAY);
            check(ambiguousDate.from == null); check(ambiguousDate.to == null); check(!ambiguousDate.issues.isEmpty());
            check(ambiguousDate.originalText.equals(text));
        }
        for (String text : List.of("Next month I do not want to buy a bicycle for £120", "Cancel the £120 bicycle next month",
                "I don’t want a bicycle for £120 this month", "I no longer want to buy it for £120 next month")) {
            ConversationBudget.Draft negated = ConversationBudget.parse(text, TODAY);
            check(negated.amountPence == null); check(!negated.issues.isEmpty()); check(negated.originalText.equals(text));
        }
        fails(() -> ConversationBudget.parse(" ", TODAY));
        JSONObject data = workspace(); String original = data.toString(), originalHistory = data.getJSONArray("transactions").toString();
        ConversationBudget.Proposal add = proposal(12000, ConversationBudget.Mode.ADD_ALLOCATION);
        fails(() -> ConversationBudget.apply(data, add, false)); check(data.toString().equals(original));
        JSONObject saved = ConversationBudget.apply(data, add, true);
        check(BudgetWorkspace.read(saved).budgets.get(0).limitPence == 32000); check(BudgetWorkspace.read(saved).budgets.get(0).essential);
        check(saved.getJSONArray("transactions").toString().equals(originalHistory)); check(data.toString().equals(original));
        check(BudgetWorkspace.read(saved).commitments.size() == 1); check(BudgetWorkspace.read(saved).priorities.get(0).equals("Synthetic priority"));
        check(ConversationBudget.records(saved).length() == 1); check(ConversationBudget.reservedPence(saved, " shopping ", "2026-11-01", "2026-11-30") == 12000);
        check(ConversationBudget.records(saved).getJSONObject(0).getJSONObject("proposal").getString("originalText").contains("might"));
        JSONObject replay = ConversationBudget.apply(new JSONObject(saved.toString()), ConversationBudget.decode(ConversationBudget.encode(add)), true);
        check(replay.toString().equals(saved.toString())); check(ConversationBudget.records(replay).length() == 1); check(BudgetWorkspace.read(replay).budgets.get(0).limitPence == 32000);
        ConversationBudget.Proposal changed = new ConversationBudget.Proposal(add.id, add.originalText, "Changed review", add.category, add.from, add.to, add.amountPence, add.mode, add.reviewedOn);
        fails(() -> ConversationBudget.apply(saved, changed, true));
        JSONObject within = ConversationBudget.apply(data, proposal(10000, ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION), true);
        check(BudgetWorkspace.read(within).budgets.get(0).limitPence == 20000);
        check(ConversationBudget.reservedPence(within, "Shopping", "2026-11-01", "2026-11-30") == 10000);
        fails(() -> ConversationBudget.apply(within, proposal(7000, ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION), true));
        check(ConversationBudget.apply(within, proposal(6000, ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION), true).getJSONObject("conversationBudgets").getJSONArray("records").length() == 2);
        // £100 of credits does not restore allocation; £500 Pot transfer does not consume external-spend allocation.
        check(data.getJSONArray("transactions").toString().equals(originalHistory));
        ConversationBudget.Proposal missing = new ConversationBudget.Proposal(UUID.randomUUID().toString(), "Next month £50", "Synthetic", "Travel", "2026-12-01", "2026-12-31", 5000,
                ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION, "2026-11-14");
        fails(() -> ConversationBudget.apply(data, missing, true));
        ConversationBudget.Proposal fresh = new ConversationBudget.Proposal(UUID.randomUUID().toString(), missing.originalText, missing.name, missing.category, missing.from, missing.to, missing.amountPence,
                ConversationBudget.Mode.ADD_ALLOCATION, missing.reviewedOn);
        check(BudgetWorkspace.read(ConversationBudget.apply(data, fresh, true)).budgets.size() == 2);
        ConversationBudget.Proposal partial = new ConversationBudget.Proposal(UUID.randomUUID().toString(), "£120 for a goal", "Synthetic", "Shopping", "2026-11-15", "2026-12-15", 12000,
                ConversationBudget.Mode.ADD_ALLOCATION, "2026-11-14");
        fails(() -> ConversationBudget.apply(data, partial, true));
        // An equal recorded debit may already fulfil a tentative provision; do not call double-counted reserves a verified shortfall.
        JSONObject possibleOverlap = new JSONObject(within.toString());possibleOverlap.getJSONArray("transactions").put(row("synthetic-unlinked-purchase",-10000,"Card payment"));
        message(() -> ConversationBudget.apply(possibleOverlap, proposal(7000, ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION), true), "recorded-payment overlap");
        JSONObject movedDates = BudgetWorkspace.upsertBudget(within, 0, "Shopping", "2026-11-15", "2026-12-15", "200.00", true);
        String movedBefore = movedDates.toString();
        ConversationBudget.Proposal movedReview = new ConversationBudget.Proposal(UUID.randomUUID().toString(), "£10 for a goal", "Synthetic", "Shopping", "2026-11-15", "2026-12-15", 1000,
                ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION, "2026-11-14");
        message(() -> ConversationBudget.apply(movedDates, movedReview, true), "Review existing plan dates");
        check(movedDates.toString().equals(movedBefore));
        JSONObject tampered = new JSONObject(saved.toString()); tampered.getJSONObject("conversationBudgets").getJSONArray("records").getJSONObject(0).getJSONObject("proposal").put("amountPence", 1);
        fails(() -> ConversationBudget.records(tampered));
        JSONObject malformed = ConversationBudget.encode(add).put("amountPence", "12000"); fails(() -> ConversationBudget.decode(malformed));
        fails(() -> new ConversationBudget.Proposal("not-a-uuid",add.originalText,add.name,add.category,add.from,add.to,add.amountPence,add.mode,add.reviewedOn));
        fails(() -> new ConversationBudget.Proposal(UUID.randomUUID().toString(),add.originalText," ",add.category,add.from,add.to,add.amountPence,add.mode,add.reviewedOn));
        fails(() -> new ConversationBudget.Proposal(UUID.randomUUID().toString(),add.originalText,add.name,add.category,add.from,add.to,0,add.mode,add.reviewedOn));
        JSONArray ledger = new JSONArray(); ConversationBudget.Proposal first = null;
        for (int i=0;i<ConversationBudget.MAX_RECORDS;i++) {
            ConversationBudget.Proposal p=proposal(1,ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION);if(first==null)first=p;
            ledger.put(new JSONObject().put("proposal",ConversationBudget.encode(p)).put("payloadSha256",hash(p)).put("status","active").put("budgetLimitBeforePence",20000).put("budgetLimitAfterPence",20000));
        }
        JSONObject full = new JSONObject(data.toString()).put("conversationBudgets",new JSONObject().put("version",1).put("records",ledger));
        check(ConversationBudget.records(full).length()==500);fails(() -> ConversationBudget.apply(full,proposal(1,ConversationBudget.Mode.WITHIN_EXISTING_ALLOCATION),true));
        check(ConversationBudget.records(ConversationBudget.apply(full,first,true)).length()==500);
        JSONObject cancelled = new JSONObject(saved.toString());
        cancelled.getJSONObject("conversationBudgets").getJSONArray("records").getJSONObject(0).put("status","cancelled");
        check(ConversationBudget.records(ConversationBudget.apply(cancelled,add,true)).getJSONObject(0).getString("status").equals("cancelled"));
        check(ConversationBudget.reservedPence(cancelled,"Shopping","2026-11-01","2026-11-30")==0);
        JSONArray detached=ConversationBudget.records(saved);detached.getJSONObject(0).put("status","cancelled");check(ConversationBudget.reservedPence(saved,"Shopping","2026-11-01","2026-11-30")==12000);
        System.out.println("Conversation budget: "+checks+" checks passed");
    }
}
