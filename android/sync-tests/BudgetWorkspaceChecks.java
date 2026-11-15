package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.List;

public final class BudgetWorkspaceChecks {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Budget workspace check " + checks); }
    private static void fails(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid planning input"); }
    private static JSONObject object(String text) { try { return new JSONObject(text); } catch (org.json.JSONException invalidFixture) { throw new AssertionError(invalidFixture); } }
    private static void period(BudgetWorkspace.Period p, String start, String end) { check(p.start.equals(start)); check(p.end.equals(end)); }
    public static void main(String[] args) throws Exception {
        JSONObject original = object("{\"start\":\"2026-11-01\",\"end\":\"2026-11-30\",\"budgets\":{\"Groceries\":20000},\"transactions\":[{\"evidence\":\"synthetic-only\"}],\"other\":{\"value\":1}}");
        String before = original.toString();
        CompanionPlanner.Plan migrated = BudgetWorkspace.read(original);
        check(migrated.budgets.size() == 1); check(!migrated.budgets.get(0).essential);
        check(migrated.reportedBalancePence == null); check(!migrated.commitmentsComplete);
        check(migrated.commitments.isEmpty()); check(migrated.reservePence == 0);
        check(original.toString().equals(before));
        JSONObject next = BudgetWorkspace.upsertBudget(original, -1, " Transport ", "2026-11-01", "2026-11-30", "120.50", true);
        check(original.toString().equals(before)); check(next != original);
        check(next.getJSONArray("transactions").toString().equals(original.getJSONArray("transactions").toString()));
        next.getJSONObject("other").put("value", 2); check(original.getJSONObject("other").getInt("value") == 1);
        CompanionPlanner.Plan plan = BudgetWorkspace.read(next);
        check(plan.budgets.size() == 2); check(plan.budgets.get(1).limitPence == 12050); check(plan.budgets.get(1).essential);
        check(plan.budgets.get(1).category.equals("Transport"));
        JSONObject encoded = BudgetWorkspace.encode(plan);
        CompanionPlanner.Plan roundtrip = BudgetWorkspace.decode(encoded);
        check(roundtrip.budgets.size() == 2); check(roundtrip.budgets.get(1).essential);
        check(roundtrip.reportedBalancePence == null); check(roundtrip.reportedBalanceDate.equals(""));
        JSONObject update = BudgetWorkspace.upsertBudget(next, 1, "Transport", "2026-11-01", "2026-11-30", "130.00", false);
        check(BudgetWorkspace.read(update).budgets.get(1).limitPence == 13000);
        check(!BudgetWorkspace.read(update).budgets.get(1).essential);
        check(BudgetWorkspace.read(next).budgets.get(1).essential);
        check(BudgetWorkspace.read(BudgetWorkspace.removeBudget(update, 0)).budgets.size() == 1);
        fails(() -> BudgetWorkspace.removeBudget(update, -1)); fails(() -> BudgetWorkspace.removeBudget(update, 20));
        fails(() -> BudgetWorkspace.upsertBudget(original, -1, " groceries ", "2026-11-30", "2026-12-31", "1.00", false));
        check(BudgetWorkspace.read(BudgetWorkspace.upsertBudget(original, -1, " groceries ", "2026-12-01", "2026-12-31", "1.00", false)).budgets.size() == 2);
        for (String bad : new String[]{"0", "-1", "1.001", "1e3", "£1.00", "90000000000000.01"}) fails(() -> BudgetWorkspace.upsertBudget(original, -1, "Other", "2026-11-01", "2026-11-30", bad, false));
        fails(() -> BudgetWorkspace.upsertBudget(original, -1, " ", "2026-11-01", "2026-11-30", "10.00", false));
        fails(() -> BudgetWorkspace.upsertBudget(original, -1, "Other", "01/11/2026", "2026-11-30", "10.00", false));
        fails(() -> BudgetWorkspace.upsertBudget(original, -1, "Other", "2026-02-29", "2026-11-30", "10.00", false));
        fails(() -> BudgetWorkspace.upsertBudget(original, -1, "Other", "2026-12-01", "2026-11-30", "10.00", false));
        JSONObject context = BudgetWorkspace.updateContext(next, -5000L, "2026-11-13", 3000, true, List.of("Protect essentials", "Synthetic travel goal"),
                List.of(new CompanionPlanner.Commitment("Synthetic bill", "2026-11-20", 2500, true), new CompanionPlanner.Commitment("Unconfirmed", "2026-11-21", 5000, false)));
        CompanionPlanner.Plan contextual = BudgetWorkspace.read(context);
        check(contextual.reportedBalancePence == -5000); check(contextual.reportedBalanceDate.equals("2026-11-13"));
        check(contextual.commitmentsComplete); check(contextual.reservePence == 3000); check(contextual.priorities.size() == 2);
        check(contextual.commitments.get(0).confirmed); check(!contextual.commitments.get(1).confirmed);
        check(CompanionPlanner.cashContext(contextual, LocalDate.parse("2026-11-13"), LocalDate.parse("2026-11-30")).headroomPence == -10500);
        check(CompanionPlanner.cashContext(contextual, LocalDate.parse("2026-11-14"), LocalDate.parse("2026-11-30")).headroomPence == null);
        check(BudgetWorkspace.read(BudgetWorkspace.upsertCommitment(context, -1, "New synthetic", "2026-11-25", "10.00", true)).commitmentsComplete == false);
        check(BudgetWorkspace.read(BudgetWorkspace.removeCommitment(context, 0)).commitmentsComplete == false);
        fails(() -> BudgetWorkspace.updateContext(next, null, "2026-11-13", 0, true, List.of(), List.of()));
        fails(() -> BudgetWorkspace.updateContext(next, 100L, "", 0, true, List.of(), List.of()));
        fails(() -> BudgetWorkspace.updateContext(next, null, "", -1, true, List.of(), List.of()));
        fails(() -> BudgetWorkspace.updateContext(next, null, "", 0, false, List.of(" "), List.of()));
        check(BudgetWorkspace.read(new JSONObject()).budgets.isEmpty());
        fails(() -> BudgetWorkspace.read(object("{\"companionPlan\":\"invalid\"}")));
        fails(() -> BudgetWorkspace.read(object("{\"budgets\":{\"Other\":1}}")));
        fails(() -> BudgetWorkspace.read(object("{\"start\":\"2026-11-01\",\"end\":\"2026-11-30\",\"budgets\":{\"Other\":\"100\"}}")));
        JSONObject malformed = BudgetWorkspace.encode(contextual); malformed.put("currency", "EUR"); fails(() -> BudgetWorkspace.decode(malformed));
        JSONObject version = BudgetWorkspace.encode(contextual); version.put("version", 99); fails(() -> BudgetWorkspace.decode(version));
        JSONObject fraction = BudgetWorkspace.encode(contextual); fraction.put("reservePence", 0.5); fails(() -> BudgetWorkspace.decode(fraction));
        JSONObject coercion = BudgetWorkspace.encode(contextual); coercion.getJSONArray("budgets").getJSONObject(0).put("essential", "true"); fails(() -> BudgetWorkspace.decode(coercion));
        JSONObject undefined = BudgetWorkspace.encode(contextual); undefined.remove("reportedBalancePence"); fails(() -> BudgetWorkspace.decode(undefined));
        period(BudgetWorkspace.calendarMonth("2024-02-29"), "2024-02-01", "2024-02-29");
        period(BudgetWorkspace.calendarMonth("2026-02-01"), "2026-02-01", "2026-02-28");
        period(BudgetWorkspace.calendarMonth("2026-12-31"), "2026-12-01", "2026-12-31");
        period(BudgetWorkspace.monthlyPayCycle("2024-02-28", 31), "2024-01-31", "2024-02-28");
        period(BudgetWorkspace.monthlyPayCycle("2024-02-29", 31), "2024-02-29", "2024-03-30");
        period(BudgetWorkspace.monthlyPayCycle("2024-03-30", 31), "2024-02-29", "2024-03-30");
        period(BudgetWorkspace.monthlyPayCycle("2024-03-31", 31), "2024-03-31", "2024-04-29");
        period(BudgetWorkspace.monthlyPayCycle("2026-03-01", 1), "2026-03-01", "2026-03-31");
        period(BudgetWorkspace.monthlyPayCycle("2026-01-01", 15), "2025-12-15", "2026-01-14");
        period(BudgetWorkspace.fixedPayCycle("2026-01-02", 14, "2026-01-01"), "2025-12-19", "2026-01-01");
        period(BudgetWorkspace.fixedPayCycle("2026-01-02", 14, "2026-01-02"), "2026-01-02", "2026-01-15");
        period(BudgetWorkspace.fixedPayCycle("2026-01-02", 14, "2026-01-16"), "2026-01-16", "2026-01-29");
        fails(() -> BudgetWorkspace.monthlyPayCycle("2026-01-01", 0)); fails(() -> BudgetWorkspace.monthlyPayCycle("2026-01-01", 32));
        fails(() -> BudgetWorkspace.fixedPayCycle("2026-01-01", 0, "2026-01-02"));
        fails(() -> BudgetWorkspace.calendarMonth("01/11/2026"));
        fails(() -> BudgetWorkspace.monthlyPayCycle("9999-12-31", 31));
        System.out.println("Budget workspace: " + checks + " checks passed");
    }
}
