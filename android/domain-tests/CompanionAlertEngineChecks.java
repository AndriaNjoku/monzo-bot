import org.monzobot.app.CompanionAlertEngine;
import org.monzobot.app.CompanionPlanner;
import org.monzobot.app.Finance;
import org.monzobot.app.NotificationPolicy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Synthetic-only candidate regressions; runtime delivery/ledger persistence is tested separately. */
public final class CompanionAlertEngineChecks {
    private static int checks;
    private static final LocalDate TODAY = LocalDate.of(2025, 3, 15);
    private static final Instant NOW = Instant.parse("2025-03-15T12:00:00Z");
    private static void eq(Object actual, Object expected) { checks++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    private static void yes(boolean value) { eq(value, true); }
    private static void fails(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; } throw new AssertionError("Expected validation failure"); }
    private static Finance.Transaction tx(String id, String date, String name, String category, String type, long amount) {
        return new Finance.Transaction(id, date, name, category, type, "GBP", amount, Map.of());
    }
    private static Finance.Transaction debit(String id, long amount) { return tx(id, TODAY.toString(), "Example merchant " + id, "Sensitive Envelope", "Card payment", -amount); }
    private static CompanionPlanner.Plan plan(List<CompanionPlanner.Budget> budgets, List<CompanionPlanner.Commitment> commitments) {
        return new CompanionPlanner.Plan(budgets, commitments, null, "", 0, false, List.of());
    }
    private static List<CompanionAlertEngine.Candidate> evaluate(List<Finance.Transaction> rows, CompanionPlanner.Plan plan, boolean initial, List<String> delta) {
        return CompanionAlertEngine.evaluate(rows, Map.of(), plan, TODAY, NOW, NOW.toString(), new CompanionAlertEngine.Settings(80, initial, delta));
    }
    public static void main(String[] args) {
        var budget = new CompanionPlanner.Budget("Sensitive Envelope", "2025-03-01", "2025-03-31", 10000, false);
        var basic = plan(List.of(budget), List.of());
        var old = tx("baseline-row", "2025-03-01", "Example baseline", "Sensitive Envelope", "Card payment", -7000);
        var current = debit("new-row", 1500);
        var rows = List.of(old, current);
        var threshold = evaluate(rows, basic, false, List.of(current.id));
        eq(threshold.size(), 1); var signal = threshold.get(0);
        eq(signal.kind, "budget_threshold"); eq(signal.priority, 80); eq(signal.estimate, false); eq(signal.requiresReview, true);
        eq(signal.sourceCoverage, "unverified"); eq(signal.retrievalStatus, "recent");
        yes(signal.key.matches("[a-f0-9]{64}")); eq(signal.evidenceRefs.size(), 1);
        yes(signal.evidenceRefs.get(0).matches("[a-f0-9]{16}"));
        eq(signal.evidenceRefs.get(0), CompanionAlertEngine.evidenceRef("new-row"));
        yes(!signal.key.contains("Sensitive")); yes(!signal.key.contains("new-row"));
        yes(!signal.title.contains("Sensitive")); yes(!signal.body.contains("Sensitive"));
        yes(!signal.body.contains("1500")); yes(!signal.title.contains("85.00"));
        yes(signal.localExplanation.contains("Sensitive Envelope")); yes(signal.localExplanation.contains("not available bank cash"));
        yes(signal.localExplanation.contains("unverified"));
        eq(evaluate(rows, basic, true, List.of(old.id, current.id)).size(), 0);
        eq(evaluate(rows, basic, false, List.of()).size(), 0);
        eq(evaluate(rows, basic, false, List.of(current.id)).get(0).key, signal.key);
        // Candidate generation is pure: only the runtime records successful delivery.
        eq(evaluate(rows, basic, false, List.of(current.id)).get(0).key, signal.key);
        var another = debit("another-new-row", 1600);
        eq(evaluate(List.of(old, another), basic, false, List.of(another.id)).get(0).key, signal.key);
        eq(evaluate(rows, basic, false, List.of(current.id, current.id)).size(), 1);

        var exceeds = debit("exceed-row", 4000);
        var exceeded = evaluate(List.of(old, exceeds), basic, false, List.of(exceeds.id));
        eq(exceeded.size(), 1); eq(exceeded.get(0).kind, "budget_exceeded"); eq(exceeded.get(0).priority, 90);
        yes(!exceeded.get(0).key.equals(signal.key));
        var alreadyOver = debit("already-over", 12000); var more = debit("more", 100);
        eq(evaluate(List.of(alreadyOver, more), basic, false, List.of(more.id)).size(), 0);
        var credit = tx("credit", TODAY.toString(), "Example credit", "Sensitive Envelope", "Card payment", 5000);
        eq(evaluate(List.of(old, credit), basic, false, List.of(credit.id)).size(), 0);
        var pot = tx("pot", TODAY.toString(), "", "Sensitive Envelope", "Pot transfer", -90000);
        eq(evaluate(List.of(old, pot), basic, false, List.of(pot.id)).size(), 0);
        var external = tx("external", TODAY.toString(), "Example payee", "Sensitive Envelope", "Faster payment", -1500);
        eq(evaluate(List.of(old, external), basic, false, List.of(external.id)).get(0).kind, "budget_threshold");
        var overridden = CompanionAlertEngine.evaluate(rows, Map.of(current.id, "Other envelope"), basic, TODAY, NOW, NOW.toString(), new CompanionAlertEngine.Settings(80, false, List.of(current.id)));
        eq(overridden.size(), 0);
        var future = tx("future", TODAY.plusDays(1).toString(), "Example future", "Sensitive Envelope", "Card payment", -5000);
        eq(evaluate(List.of(old, future), basic, false, List.of(future.id)).size(), 0);
        var oldBudget = plan(List.of(new CompanionPlanner.Budget("Sensitive Envelope", "2025-01-01", "2025-01-31", 1000, false)), List.of());
        eq(evaluate(List.of(tx("old-period", "2025-01-15", "Example", "Sensitive Envelope", "Card payment", -2000)), oldBudget, false, List.of("old-period")).size(), 0);
        var longBudget = plan(List.of(new CompanionPlanner.Budget("General", "2021-01-01", "2025-03-31", 2000, false)), List.of());
        List<Finance.Transaction> longHistory = new ArrayList<>();
        for (int i = 0; i < 1300; i++) longHistory.add(tx("history-" + i, LocalDate.of(2021, 1, 1).plusDays(i).toString(), "Historical example " + i, "General", "Card payment", -1));
        longHistory.add(tx("latest", TODAY.toString(), "Latest example", "General", "Card payment", -500));
        eq(evaluate(longHistory, longBudget, false, List.of("latest")).get(0).kind, "budget_threshold");
        var backfill = tx("backfill", "2024-09-01", "Backfill example", "General", "Card payment", -9000);
        eq(evaluate(List.of(backfill), longBudget, false, List.of(backfill.id)).size(), 0);

        var paceRows = List.of(tx("pace1", "2025-03-01", "First example", "Sensitive Envelope", "Card payment", -2000),
                tx("pace2", "2025-03-08", "Second example", "Sensitive Envelope", "Card payment", -2000),
                tx("pace3", TODAY.toString(), "Third example", "Sensitive Envelope", "Card payment", -2000));
        var pace = evaluate(paceRows, basic, false, List.of("pace3"));
        eq(pace.size(), 1); eq(pace.get(0).kind, "budget_pace_estimate"); eq(pace.get(0).estimate, true);
        yes(pace.get(0).localExplanation.contains("uncertain estimate"));
        var essential = plan(List.of(new CompanionPlanner.Budget("Sensitive Envelope", budget.start, budget.end, 10000, true)), List.of());
        yes(evaluate(rows, essential, false, List.of(current.id)).get(0).localExplanation.contains("protect it"));

        var due = new CompanionPlanner.Commitment("Private example bill", TODAY.plusDays(2).toString(), 12345, true);
        var soon = plan(List.of(), List.of(due)); var dueSignals = evaluate(List.of(), soon, false, List.of());
        eq(dueSignals.size(), 1); eq(dueSignals.get(0).kind, "confirmed_commitment_due"); eq(dueSignals.get(0).priority, 95);
        eq(dueSignals.get(0).estimate, false); yes(dueSignals.get(0).localExplanation.contains("user-confirmed"));
        yes(!dueSignals.get(0).body.contains(due.label)); yes(!dueSignals.get(0).title.contains("12345"));
        eq(evaluate(List.of(), soon, true, List.of()).size(), 0);
        eq(evaluate(List.of(), plan(List.of(), List.of(new CompanionPlanner.Commitment("Unconfirmed", TODAY.toString(), 1000, false))), false, List.of()).size(), 0);
        eq(evaluate(List.of(), plan(List.of(), List.of(new CompanionPlanner.Commitment("Past", TODAY.minusDays(1).toString(), 1000, true))), false, List.of()).size(), 0);
        eq(evaluate(List.of(), plan(List.of(), List.of(new CompanionPlanner.Commitment("Later", TODAY.plusDays(4).toString(), 1000, true))), false, List.of()).size(), 0);
        eq(evaluate(List.of(), plan(List.of(), List.of(due, due)), false, List.of()).size(), 1);

        var emptyPlan = plan(List.of(), List.of());
        var dupA = tx("dup-a", TODAY.toString(), "Example Merchant", "General", "Card payment", -400);
        var dupB = tx("dup-b", TODAY.toString(), " example  MERCHANT ", "General", "Card payment", -400);
        var duplicate = evaluate(List.of(dupA, dupB), emptyPlan, false, List.of(dupB.id));
        eq(duplicate.size(), 1); eq(duplicate.get(0).kind, "possible_duplicate"); eq(duplicate.get(0).priority, 100);
        eq(duplicate.get(0).evidenceRefs.size(), 2); yes(duplicate.get(0).localExplanation.contains("legitimate"));
        eq(evaluate(List.of(dupA, dupA), emptyPlan, false, List.of(dupA.id)).size(), 0);
        List<Finance.Transaction> anomaly = new ArrayList<>();
        for (int i = 0; i < 5; i++) anomaly.add(tx("baseline" + i, TODAY.minusDays(6 - i).toString(), "Example Shop", "General", "Card payment", -1000));
        anomaly.add(tx("large-new", TODAY.toString(), "Example Shop", "General", "Card payment", -20000));
        var largeFlags = evaluate(anomaly, emptyPlan, false, List.of("large-new"));
        eq(largeFlags.size(), 1); eq(largeFlags.get(0).kind, "unusually_large_debit");
        yes(largeFlags.get(0).localExplanation.contains("not proof of fraud"));
        // A backfilled baseline observation must not wake an old large-event alert.
        eq(evaluate(anomaly, emptyPlan, false, List.of("baseline0")).size(), 0);
        var combined = evaluate(List.of(old, exceeds, dupA, dupB), plan(List.of(budget), List.of(due)), false, List.of(exceeds.id, dupB.id));
        eq(combined.get(0).priority, 100); eq(combined.get(1).priority, 95); eq(combined.get(2).priority, 90);

        eq(CompanionAlertEngine.retrievalStatus(NOW, ""), "unknown");
        eq(CompanionAlertEngine.retrievalStatus(NOW, null), "unknown");
        eq(CompanionAlertEngine.retrievalStatus(NOW, "not-a-time"), "unknown");
        eq(CompanionAlertEngine.retrievalStatus(NOW, NOW.plusSeconds(1).toString()), "unknown");
        eq(CompanionAlertEngine.retrievalStatus(NOW, NOW.minus(Duration.ofHours(24)).toString()), "recent");
        eq(CompanionAlertEngine.retrievalStatus(NOW, NOW.minus(Duration.ofHours(24)).minusSeconds(1).toString()), "stale");
        for (String retrieval : List.of("", "bad", NOW.minus(Duration.ofDays(2)).toString())) {
            var freshness = CompanionAlertEngine.evaluate(rows, Map.of(), plan(List.of(budget), List.of(due)), TODAY, NOW, retrieval, new CompanionAlertEngine.Settings(80, false, List.of(current.id)));
            eq(freshness.size(), 1); eq(freshness.get(0).kind, "freshness"); eq(freshness.get(0).evidenceRefs.size(), 0);
            eq(freshness.get(0).sourceCoverage, "unverified"); yes(!freshness.get(0).body.contains(due.label));
        }
        var firstUnknown = CompanionAlertEngine.evaluate(rows, Map.of(), basic, TODAY, NOW, "", new CompanionAlertEngine.Settings(80, true, List.of(current.id)));
        eq(firstUnknown.get(0).kind, "freshness");
        List<CompanionPlanner.Commitment> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(new CompanionPlanner.Commitment("Example bill " + i, TODAY.toString(), 1000, true));
        eq(evaluate(List.of(), plan(List.of(), many), false, List.of()).size(), 50);
        yes(!NotificationPolicy.due(NOW.toEpochMilli(), 0, 0, true, true, 23, 21, 8));
        yes(!NotificationPolicy.due(NOW.toEpochMilli(), NOW.minus(Duration.ofHours(1)).toEpochMilli(), 0, true, true, 12, 21, 8));
        yes(!NotificationPolicy.due(NOW.toEpochMilli(), 0, 3, true, true, 12, 21, 8));
        yes(NotificationPolicy.due(NOW.toEpochMilli(), 0, 0, true, true, 12, 21, 8));
        // The host can deduplicate a successful send using the opaque key, without raw IDs.
        var sentKeys = new java.util.HashSet<String>();
        yes(!sentKeys.contains(signal.key)); sentKeys.add(signal.key);
        yes(sentKeys.contains(evaluate(rows, basic, false, List.of(current.id)).get(0).key));
        fails(() -> new CompanionAlertEngine.Settings(0, false, List.of()));
        fails(() -> new CompanionAlertEngine.Settings(101, false, List.of()));
        fails(() -> CompanionAlertEngine.evidenceRef(""));
        fails(() -> evaluate(List.of(dupA, tx(dupA.id, dupA.date, dupA.name, dupA.category, dupA.type, -999)), emptyPlan, false, List.of(dupA.id)));
        fails(() -> evaluate(List.of(dupA, tx(dupA.id, TODAY.plusDays(1).toString(), dupA.name, dupA.category, dupA.type, dupA.amount)), emptyPlan, false, List.of(dupA.id)));
        System.out.println("Companion alert engine: " + checks + " checks passed; synthetic data only");
    }
}
