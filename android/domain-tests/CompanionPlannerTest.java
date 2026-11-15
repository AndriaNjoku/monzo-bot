import org.monzobot.app.CompanionPlanner;
import org.monzobot.app.Finance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Planning regressions use synthetic transaction and plan data only. */
public final class CompanionPlannerTest {
    private static int checks;
    private static void eq(Object actual, Object expected) {
        checks++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void yes(boolean condition) { eq(condition, true); }
    private static void fails(Runnable action) {
        checks++; try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
    private static Finance.Transaction tx(String id, String date, String name, String category, String type, long pence) {
        return new Finance.Transaction(id, date, name, category, type, "GBP", pence, Map.of());
    }
    private static CompanionPlanner.Plan plan(List<CompanionPlanner.Budget> budgets) {
        return new CompanionPlanner.Plan(budgets, List.of(), null, "", 0, false, List.of());
    }
    public static void main(String[] args) {
        LocalDate today = LocalDate.of(2025, 3, 15);
        var food = new CompanionPlanner.Budget("Groceries", "2025-03-01", "2025-03-31", 10000, false);
        var home = new CompanionPlanner.Budget("Home", "2025-03-01", "2025-03-31", 80000, true);
        var context = plan(List.of(food, home));
        var debit1 = tx("demo-1", "2025-03-01", "Example Grocer", "Groceries", "Card payment", -2000);
        var debit2 = tx("demo-2", "2025-03-08", "Example Grocer", "Groceries", "Card payment", -3000);
        var credit = tx("demo-3", "2025-03-09", "Example Grocer", "Groceries", "Card payment", 500);
        var pot = tx("demo-pot", "2025-03-10", "", "Groceries", "Pot transfer", -99000);
        var transfer = tx("demo-4", "2025-03-14", "Example payee", "Groceries", "Faster payment", -700);
        var future = tx("demo-future", "2025-03-16", "Example Grocer", "Groceries", "Card payment", -9000);
        var outside = tx("demo-outside", "2025-02-28", "Example Grocer", "Groceries", "Card payment", -9000);
        List<Finance.Transaction> history = List.of(debit1, debit2, credit, pot, transfer, future, outside);
        var statuses = CompanionPlanner.budgetStatus(history, Map.of(), context, today);
        var s = statuses.get(0);
        eq(s.grossDebitsPence, 5700L); eq(s.creditsPence, 500L); eq(s.usedPence, 5700L); eq(s.remainingPence, 4300L);
        eq(s.daysLeft, 17); eq(s.dailyGuidePence, 252L); eq(s.observedDays, 15); eq(s.debitCount, 3);
        eq(s.projectedDebitPence, 11780L); eq(s.projectionIsEstimate, true); eq(s.pace, "projected_over_budget");
        eq(s.essential, false); yes(s.recommendation.contains("flexible"));
        yes(statuses.get(1).essential); yes(statuses.get(1).recommendation.startsWith("Protect"));
        yes(!s.recommendation.contains("Groceries"));
        eq(s.dailyGuideScope, "remaining_budget_allocation_not_available_cash");
        eq(CompanionPlanner.budgetStatus(history, Map.of("demo-2", "Home"), context, today).get(0).usedPence, 2700L);
        eq(CompanionPlanner.budgetStatus(history, Map.of("demo-2", "Home"), context, today).get(1).usedPence, 3000L);
        var impact = CompanionPlanner.assessTransaction("demo-1", history, Map.of(), context, today);
        eq(impact.amountPence, -2000L); eq(impact.category, "Groceries"); eq(impact.reviewOnly, true);
        eq(impact.budgetImpacts.get(0).beforeRemainingPence, 6300L); eq(impact.budgetImpacts.get(0).afterRemainingPence, 4300L);
        eq(impact.budgetImpacts.get(0).countedDebitPence, 2000L);
        eq(impact.comparison, "snapshot_without_transaction_vs_with_transaction");
        var creditImpact = CompanionPlanner.assessTransaction("demo-3", history, Map.of(), context, today);
        eq(creditImpact.creditUnclassified, true); eq(creditImpact.budgetImpacts.get(0).countedDebitPence, 0L);
        eq(creditImpact.budgetImpacts.get(0).beforeRemainingPence, 4300L);
        eq(creditImpact.budgetImpacts.get(0).afterRemainingPence, 4300L);
        eq(creditImpact.budgetImpacts.get(0).creditsPence, 500L);
        eq(CompanionPlanner.assessTransaction("demo-pot", history, Map.of(), context, today).potMovement, true);
        eq(CompanionPlanner.assessTransaction("demo-pot", history, Map.of(), context, today).budgetImpacts.size(), 0);
        eq(CompanionPlanner.assessTransaction("demo-future", history, Map.of(), context, today).budgetImpacts.size(), 0);
        eq(CompanionPlanner.assessTransaction("demo-outside", history, Map.of(), context, today).budgetImpacts.size(), 0);
        fails(() -> CompanionPlanner.assessTransaction("unknown", history, Map.of(), context, today));
        eq(CompanionPlanner.budgetStatus(List.of(), Map.of(), context, today).get(0).projectedDebitPence, null);
        eq(CompanionPlanner.budgetStatus(history, Map.of(), context, LocalDate.of(2025, 3, 2)).get(0).projectedDebitPence, null);
        eq(CompanionPlanner.budgetStatus(history, Map.of(), context, LocalDate.of(2025, 4, 1)).get(0).daysLeft, 0);
        eq(CompanionPlanner.budgetStatus(history, Map.of(), context, LocalDate.of(2025, 4, 1)).get(0).dailyGuidePence, null);
        eq(CompanionPlanner.budgetStatus(history, Map.of(), context, LocalDate.of(2025, 4, 1)).get(0).pace, "ended");
        eq(CompanionPlanner.budgetStatus(history, Map.of(), context, LocalDate.of(2025, 2, 1)).get(0).pace, "not_started");
        var sameDay = List.of(tx("same1", "2025-03-15", "Example", "Groceries", "Card payment", -100),
                tx("same2", "2025-03-15", "Example", "Groceries", "Card payment", -100),
                tx("same3", "2025-03-15", "Example", "Groceries", "Card payment", -100));
        eq(CompanionPlanner.budgetStatus(sameDay, Map.of(), context, today).get(0).projectedDebitPence, null);
        var transfersPlan = plan(List.of(new CompanionPlanner.Budget("Transfers", "2025-03-01", "2025-03-31", 10000, false)));
        eq(CompanionPlanner.budgetStatus(List.of(tx("external-transfer", "2025-03-01", "Example payee", "Transfers", "Bank transfer", -500)), Map.of(), transfersPlan, today).get(0).usedPence, 500L);
        List<Finance.Transaction> large = new ArrayList<>();
        for (int i = 0; i < 1505; i++) large.add(tx("history-" + i, LocalDate.of(2021, 1, 1).plusDays(i).toString(), "Example merchant", "General", "Card payment", -100));
        var allPlan = plan(List.of(new CompanionPlanner.Budget("General", "2021-01-01", "2025-12-31", 900000, false)));
        eq(CompanionPlanner.budgetStatus(large, Map.of(), allPlan, today).get(0).usedPence, 150500L);
        eq(CompanionPlanner.budgetStatus(large, Map.of(), allPlan, today).get(0).debitCount, 1505);
        eq(CompanionPlanner.budgetStatus(List.of(debit1, debit1), Map.of(), context, today).get(0).usedPence, 2000L);
        fails(() -> CompanionPlanner.budgetStatus(List.of(debit1, tx("demo-1", debit1.date, debit1.name, debit1.category, debit1.type, -999)), Map.of(), context, today));
        fails(() -> CompanionPlanner.budgetStatus(List.of(tx("overflow", "2025-03-01", "Example", "Groceries", "Card payment", Long.MIN_VALUE)), Map.of(), context, today));
        fails(() -> CompanionPlanner.budgetStatus(List.of(tx("overflow1", "2025-03-01", "Example", "Groceries", "Card payment", -Long.MAX_VALUE), tx("overflow2", "2025-03-02", "Example", "Groceries", "Card payment", -1)), Map.of(), context, today));

        List<Finance.Transaction> anomaly = new ArrayList<>();
        for (int i = 0; i < 5; i++) anomaly.add(tx("baseline-" + i, today.minusDays(10 - i).toString(), "Example Shop", "General", "Card payment", -1500));
        anomaly.add(tx("large-review", today.toString(), "Example Shop", "General", "Card payment", -12000));
        var flags = CompanionPlanner.reviewFlags(anomaly, today);
        eq(flags.size(), 1); eq(flags.get(0).kind, "unusually_large_debit"); eq(flags.get(0).requiresReview, true);
        eq(flags.get(0).confirmedFraud, false); eq(flags.get(0).evidenceTransactionIds.size(), 6);
        yes(flags.get(0).explanation.contains("not proof of fraud")); yes(!flags.get(0).explanation.contains("Example Shop"));
        eq(CompanionPlanner.reviewFlags(anomaly.subList(1, anomaly.size()), today).size(), 0);
        eq(CompanionPlanner.reviewFlags(List.of(debit1, debit1), today).size(), 0);
        var duplicated = List.of(debit1, tx("other-id", debit1.date, "  EXAMPLE   GROCER ", debit1.category, debit1.type, debit1.amount));
        var duplicateFlags = CompanionPlanner.reviewFlags(duplicated, today);
        eq(duplicateFlags.size(), 1); eq(duplicateFlags.get(0).kind, "possible_duplicate"); eq(duplicateFlags.get(0).confirmedFraud, false);
        eq(CompanionPlanner.reviewFlags(List.of(pot, tx("other-pot", pot.date, pot.name, pot.category, pot.type, pot.amount)), today).size(), 0);
        eq(CompanionPlanner.reviewFlags(duplicated, today.plusDays(60)).size(), 0);
        List<Finance.Transaction> zeroBaseline = new ArrayList<>();
        for (int i = 0; i < 7; i++) zeroBaseline.add(tx("zero-" + i, today.minusDays(i + 1).toString(), "Zero example", "General", "Card payment", 0));
        zeroBaseline.add(tx("zero-new", today.toString(), "Zero example", "General", "Card payment", -20000));
        eq(CompanionPlanner.reviewFlags(zeroBaseline, today).size(), 0);
        List<Finance.Transaction> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) { many.add(tx("pair-a-" + i, today.toString(), "Distinct example " + i, "General", "Card payment", -200)); many.add(tx("pair-b-" + i, today.toString(), "Distinct example " + i, "General", "Card payment", -200)); }
        eq(CompanionPlanner.reviewFlags(many, today).size(), 50);

        var commitments = List.of(new CompanionPlanner.Commitment("Confirmed example bill", "2025-03-20", 20000, true),
                new CompanionPlanner.Commitment("Unconfirmed example", "2025-03-21", 9000, false),
                new CompanionPlanner.Commitment("Outside horizon", "2025-04-01", 8000, true),
                new CompanionPlanner.Commitment("Past due without paid evidence", "2025-03-14", 5000, true));
        var manualPlan = new CompanionPlanner.Plan(List.of(home), commitments, 100000L, today.toString(), 10000, true, List.of("Protect essentials"));
        var cash = CompanionPlanner.cashContext(manualPlan, today, LocalDate.of(2025, 3, 31));
        eq(cash.balanceStatus, "current"); eq(cash.reportedBalancePence, 100000L); eq(cash.knownCommitmentsPence, 20000L);
        eq(cash.reservePence, 10000L); eq(cash.headroomPence, 70000L); eq(cash.confirmedCommitments.size(), 1);
        eq(cash.headroomScope, "after_known_commitments_and_reserve_before_other_budget_allocations");
        eq(cash.balanceSource, "user_reported_not_bank_verified");
        // The essential category allocation is separate: its 80000 is NOT subtracted again.
        eq(cash.headroomPence, 100000L - 20000L - 10000L);
        var incomplete = new CompanionPlanner.Plan(List.of(home), commitments, 100000L, today.toString(), 10000, false, List.of());
        eq(CompanionPlanner.cashContext(incomplete, today, today.plusDays(16)).headroomPence, null);
        var stale = CompanionPlanner.cashContext(manualPlan, today.plusDays(1), today.plusDays(16));
        eq(stale.balanceStatus, "stale"); eq(stale.reportedBalancePence, null); eq(stale.headroomPence, null);
        var unknown = CompanionPlanner.cashContext(context, today, today.plusDays(5));
        eq(unknown.balanceStatus, "unknown"); eq(unknown.headroomPence, null);
        var futureBalance = new CompanionPlanner.Plan(List.of(), List.of(), 1000L, today.plusDays(1).toString(), 0, true, List.of());
        eq(CompanionPlanner.cashContext(futureBalance, today, today.plusDays(1)).balanceStatus, "unknown");
        eq(CompanionPlanner.cashContext(futureBalance, today, today.plusDays(1)).reportedBalancePence, null);
        var negative = new CompanionPlanner.Plan(List.of(), List.of(), -1000L, today.toString(), 500, true, List.of());
        eq(CompanionPlanner.cashContext(negative, today, today).headroomPence, -1500L);
        fails(() -> CompanionPlanner.cashContext(context, today, today.minusDays(1)));
        fails(() -> new CompanionPlanner.Budget("", "2025-03-01", "2025-03-31", 100, false));
        fails(() -> new CompanionPlanner.Budget("Example", "2025-02-30", "2025-03-31", 100, false));
        fails(() -> new CompanionPlanner.Budget("Example", "03/01/2025", "2025-03-31", 100, false));
        fails(() -> new CompanionPlanner.Budget("Example", "2025-04-01", "2025-03-31", 100, false));
        fails(() -> new CompanionPlanner.Budget("Example", "2025-03-01", "2025-03-31", 0, false));
        fails(() -> new CompanionPlanner.Budget("Example", "2025-03-01", "2025-03-31", -100, false));
        fails(() -> plan(List.of(food, new CompanionPlanner.Budget(" groceries ", "2025-03-15", "2025-04-30", 1000, true))));
        fails(() -> new CompanionPlanner.Commitment("Example", "2025-03-01", 0, true));
        fails(() -> new CompanionPlanner.Plan(List.of(), List.of(), 100L, "", 0, false, List.of()));
        fails(() -> new CompanionPlanner.Plan(List.of(), List.of(), null, "", -1, false, List.of()));
        var mutable = new ArrayList<>(List.of(food)); var captured = plan(mutable); mutable.clear(); eq(captured.budgets.size(), 1);
        System.out.println("Companion planner: " + checks + " checks passed; synthetic data only");
    }
}
