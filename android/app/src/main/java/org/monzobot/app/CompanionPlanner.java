package org.monzobot.app;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic, read-only GBP planning. Never executes payments or claims a verified bank balance. */
public final class CompanionPlanner {
    private CompanionPlanner() {}
    private static final long MAX_PLAN_PENCE = 9_000_000_000_000_000L;
    public static final int MAX_REVIEW_FLAGS = 50;
    public static final long MIN_LARGE_DEBIT_PENCE = 10_000L;
    public static final int LARGE_DEBIT_MULTIPLIER = 3;

    public static final class Budget {
        public final String category, start, end;
        public final long limitPence;
        public final boolean essential;
        public Budget(String category, String start, String end, long limitPence, boolean essential) {
            this.category = label(category); this.start = date(start); this.end = date(end);
            long days = ChronoUnit.DAYS.between(LocalDate.parse(this.start), LocalDate.parse(this.end));
            if (days < 0 || days > 3660) throw invalid("Budget dates must span at most ten years");
            positive(limitPence); this.limitPence = limitPence; this.essential = essential;
        }
    }
    public static final class Commitment {
        public final String label, dueDate;
        public final long amountPence;
        public final boolean confirmed;
        public Commitment(String label, String dueDate, long amountPence, boolean confirmed) {
            this.label = label(label); this.dueDate = date(dueDate); positive(amountPence);
            this.amountPence = amountPence; this.confirmed = confirmed;
        }
    }
    public static final class Plan {
        public final String currency = "GBP";
        public final List<Budget> budgets;
        public final List<Commitment> commitments;
        public final Long reportedBalancePence;
        public final String reportedBalanceDate;
        public final long reservePence;
        public final boolean commitmentsComplete;
        public final List<String> priorities;
        public Plan(List<Budget> budgets, List<Commitment> commitments, Long reportedBalancePence,
                    String reportedBalanceDate, long reservePence, boolean commitmentsComplete,
                    List<String> priorities) {
            Objects.requireNonNull(budgets, "budgets"); Objects.requireNonNull(commitments, "commitments");
            Objects.requireNonNull(priorities, "priorities");
            if (budgets.size() > 100 || commitments.size() > 500 || priorities.size() > 30) throw invalid("Plan exceeds supported limits");
            this.budgets = immutable(budgets); this.commitments = immutable(commitments);
            for (Budget budget : this.budgets) Objects.requireNonNull(budget, "budget");
            for (Commitment c : this.commitments) Objects.requireNonNull(c, "commitment");
            for (int i = 0; i < this.budgets.size(); i++) for (int j = i + 1; j < this.budgets.size(); j++) {
                Budget a = this.budgets.get(i), b = this.budgets.get(j);
                if (normal(a.category).equals(normal(b.category)) && a.start.compareTo(b.end) <= 0 && b.start.compareTo(a.end) <= 0)
                    throw invalid("Budgets for the same category must not overlap");
            }
            if (reportedBalancePence != null && (reportedBalancePence > MAX_PLAN_PENCE || reportedBalancePence < -MAX_PLAN_PENCE)) throw invalid("Reported balance exceeds supported precision");
            this.reportedBalancePence = reportedBalancePence;
            this.reportedBalanceDate = reportedBalancePence == null ? "" : date(reportedBalanceDate);
            if (reservePence < 0 || reservePence > MAX_PLAN_PENCE) throw invalid("Reserve must be nonnegative GBP pence");
            this.reservePence = reservePence; this.commitmentsComplete = commitmentsComplete;
            List<String> copy = new ArrayList<>(); for (String priority : priorities) copy.add(label(priority));
            this.priorities = immutable(copy);
        }
    }
    public static final class BudgetStatus {
        public final int budgetIndex, daysLeft, observedDays, debitCount;
        public final String category, start, end, pace, recommendation;
        public final String dailyGuideScope = "remaining_budget_allocation_not_available_cash";
        public final boolean essential, projectionIsEstimate = true;
        public final long limitPence, grossDebitsPence, creditsPence, usedPence, remainingPence;
        public final Long dailyGuidePence, projectedDebitPence;
        private BudgetStatus(int index, Budget budget, long debits, long credits, int debitCount, int debitDates, String firstDebitDate, LocalDate today) {
            budgetIndex = index; category = budget.category; start = budget.start; end = budget.end;
            essential = budget.essential; limitPence = budget.limitPence;
            grossDebitsPence = debits; creditsPence = credits; usedPence = debits;
            remainingPence = Math.subtractExact(limitPence, usedPence); this.debitCount = debitCount;
            LocalDate first = LocalDate.parse(start), last = LocalDate.parse(end);
            daysLeft = today.isAfter(last) ? 0 : Math.toIntExact(ChronoUnit.DAYS.between(today.isBefore(first) ? first : today, last) + 1);
            observedDays = today.isBefore(first) ? 0 : Math.toIntExact(ChronoUnit.DAYS.between(first, today.isAfter(last) ? last : today) + 1);
            dailyGuidePence = daysLeft == 0 ? null : Math.max(remainingPence, 0) / daysLeft;
            if (!today.isBefore(first) && today.isBefore(last) && observedDays >= 7 && debitCount >= 3 && debitDates >= 3
                    && !LocalDate.parse(firstDebitDate).isAfter(today.minusDays(6))) {
                long duration = ChronoUnit.DAYS.between(first, last) + 1;
                projectedDebitPence = exact(BigInteger.valueOf(debits).multiply(BigInteger.valueOf(duration))
                        .add(BigInteger.valueOf(observedDays - 1)).divide(BigInteger.valueOf(observedDays)));
            } else projectedDebitPence = null;
            pace = today.isBefore(first) ? "not_started" : today.isAfter(last) ? "ended"
                    : remainingPence < 0 ? "over_budget"
                    : projectedDebitPence != null && projectedDebitPence > limitPence ? "projected_over_budget" : "within_recorded_budget";
            recommendation = essential
                    ? "Protect this user-marked essential allocation. Review flexible categories before changing it. No payment action is authorized."
                    : (remainingPence < 0 || projectedDebitPence != null && projectedDebitPence > limitPence)
                    ? "Review optional future spending in this flexible category. Any projection is an estimate based only on imported debits; do not reduce essential allocations automatically."
                    : "Review this flexible allocation against your own priorities. Remaining budget is not verified available cash.";
        }
    }
    public static final class BudgetImpact {
        public final int budgetIndex;
        public final String category;
        public final boolean essential;
        public final long beforeRemainingPence, afterRemainingPence, countedDebitPence, creditsPence;
        private BudgetImpact(BudgetStatus status, long debit, long credit) {
            budgetIndex = status.budgetIndex; category = status.category; essential = status.essential;
            countedDebitPence = debit; creditsPence = credit;
            afterRemainingPence = status.remainingPence;
            beforeRemainingPence = Math.addExact(afterRemainingPence, debit);
        }
    }
    public static final class TransactionAssessment {
        public final String transactionId, date, category, type;
        public final String comparison = "snapshot_without_transaction_vs_with_transaction";
        public final long amountPence;
        public final boolean potMovement, creditUnclassified, reviewOnly = true;
        public final List<BudgetImpact> budgetImpacts;
        private TransactionAssessment(Finance.Transaction t, String category, List<BudgetImpact> impacts) {
            transactionId = t.id; date = t.date; this.category = category; type = t.type;
            amountPence = t.amount; potMovement = CompanionInsights.isPotMovement(t);
            creditUnclassified = t.amount > 0 && !potMovement; budgetImpacts = immutable(impacts);
        }
    }
    public static final class PaymentFlag {
        public final String kind, explanation;
        public final List<String> evidenceTransactionIds;
        public final boolean requiresReview = true, confirmedFraud = false;
        private PaymentFlag(String kind, String explanation, List<String> ids) {
            this.kind = kind; this.explanation = explanation; evidenceTransactionIds = immutable(ids);
        }
    }
    public static final class CashContext {
        public final String balanceStatus, reportedBalanceDate;
        public final String balanceSource = "user_reported_not_bank_verified";
        public final String headroomScope = "after_known_commitments_and_reserve_before_other_budget_allocations";
        public final Long reportedBalancePence, headroomPence;
        public final long knownCommitmentsPence, reservePence;
        public final boolean commitmentsComplete;
        public final List<Commitment> confirmedCommitments;
        private CashContext(Plan plan, LocalDate today, List<Commitment> confirmed, long total) {
            reportedBalanceDate = plan.reportedBalanceDate;
            balanceStatus = plan.reportedBalancePence == null ? "unknown"
                    : LocalDate.parse(plan.reportedBalanceDate).equals(today) ? "current"
                    : LocalDate.parse(plan.reportedBalanceDate).isBefore(today) ? "stale" : "unknown";
            reportedBalancePence = balanceStatus.equals("current") ? plan.reportedBalancePence : null;
            knownCommitmentsPence = total; reservePence = plan.reservePence;
            commitmentsComplete = plan.commitmentsComplete; confirmedCommitments = immutable(confirmed);
            headroomPence = reportedBalancePence != null && commitmentsComplete
                    ? Math.subtractExact(Math.subtractExact(reportedBalancePence, total), reservePence) : null;
        }
    }

    /** Unclassified credits are reported separately and never restore budget automatically. */
    public static List<BudgetStatus> budgetStatus(List<Finance.Transaction> history, Map<String, String> overrides,
                                                 Plan plan, LocalDate today) {
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(today, "today"); Objects.requireNonNull(overrides, "overrides");
        List<Finance.Transaction> rows = unique(history); List<BudgetStatus> result = new ArrayList<>();
        for (int i = 0; i < plan.budgets.size(); i++) {
            Budget b = plan.budgets.get(i); long debits = 0, credits = 0; int count = 0;
            java.util.Set<String> debitDates = new java.util.HashSet<>(); String firstDebitDate = "";
            for (Finance.Transaction t : rows) {
                if (CompanionInsights.isPotMovement(t) || !normal(category(t, overrides)).equals(normal(b.category))
                        || t.date.compareTo(b.start) < 0 || t.date.compareTo(b.end) > 0 || LocalDate.parse(t.date).isAfter(today)) continue;
                if (t.amount < 0) { debits = Math.addExact(debits, Math.negateExact(t.amount)); count = Math.addExact(count, 1); debitDates.add(t.date); if (firstDebitDate.isEmpty() || t.date.compareTo(firstDebitDate) < 0) firstDebitDate = t.date; }
                if (t.amount > 0) credits = Math.addExact(credits, t.amount);
            }
            result.add(new BudgetStatus(i, b, debits, credits, count, debitDates.size(), firstDebitDate, today));
        }
        return immutable(result);
    }

    public static TransactionAssessment assessTransaction(String transactionId, List<Finance.Transaction> history,
                                                          Map<String, String> overrides, Plan plan, LocalDate today) {
        if (transactionId == null || transactionId.trim().isEmpty()) throw invalid("Transaction ID is required");
        List<Finance.Transaction> rows = unique(history); Finance.Transaction found = null;
        for (Finance.Transaction t : rows) if (t.id.equals(transactionId)) found = t;
        if (found == null) throw invalid("Transaction not found in the imported snapshot");
        List<BudgetImpact> impacts = new ArrayList<>();
        if (!CompanionInsights.isPotMovement(found) && !LocalDate.parse(found.date).isAfter(today)) {
            for (BudgetStatus status : budgetStatus(rows, overrides, plan, today)) {
                if (normal(status.category).equals(normal(category(found, overrides))) && found.date.compareTo(status.start) >= 0 && found.date.compareTo(status.end) <= 0)
                    impacts.add(new BudgetImpact(status, found.amount < 0 ? Math.negateExact(found.amount) : 0, Math.max(found.amount, 0)));
            }
        }
        return new TransactionAssessment(found, category(found, overrides), impacts);
    }

    /** Review recent 30-day debits against all earlier history. A flag is never proof of fraud. */
    public static List<PaymentFlag> reviewFlags(List<Finance.Transaction> history, LocalDate today) {
        Objects.requireNonNull(today, "today"); List<Finance.Transaction> rows = unique(history);
        rows.sort(Comparator.comparing((Finance.Transaction t) -> t.date).thenComparing(t -> t.id));
        Map<String, List<Finance.Transaction>> byMerchant = new TreeMap<>();
        Map<String, List<Finance.Transaction>> duplicates = new TreeMap<>();
        List<PaymentFlag> flags = new ArrayList<>(); LocalDate first = today.minusDays(29);
        for (Finance.Transaction t : rows) {
            if (t.amount >= 0 || CompanionInsights.isPotMovement(t) || normal(t.name).isEmpty()
                    || LocalDate.parse(t.date).isAfter(today)) continue;
            long amount = Math.negateExact(t.amount); String merchant = normal(t.name);
            List<Finance.Transaction> prior = byMerchant.computeIfAbsent(merchant, k -> new ArrayList<>());
            if (!LocalDate.parse(t.date).isBefore(first)) {
                String duplicateKey = merchant.length() + ":" + merchant + "|" + t.date + "|" + amount;
                duplicates.computeIfAbsent(duplicateKey, k -> new ArrayList<>()).add(t);
                List<Finance.Transaction> baseline = new ArrayList<>();
                for (int i = prior.size() - 1; i >= 0 && baseline.size() < 20; i--) if (prior.get(i).date.compareTo(t.date) < 0) baseline.add(prior.get(i));
                if (baseline.size() >= 5 && amount >= MIN_LARGE_DEBIT_PENCE) {
                    List<Long> values = new ArrayList<>(); for (Finance.Transaction previous : baseline) values.add(Math.negateExact(previous.amount));
                    Collections.sort(values); int middle = values.size() / 2;
                    long median = values.size() % 2 == 1 ? values.get(middle)
                            : exact(BigInteger.valueOf(values.get(middle - 1)).add(BigInteger.valueOf(values.get(middle))).divide(BigInteger.valueOf(2)));
                    if (median > 0 && BigInteger.valueOf(amount).compareTo(BigInteger.valueOf(median).multiply(BigInteger.valueOf(LARGE_DEBIT_MULTIPLIER))) >= 0) {
                        List<String> evidence = new ArrayList<>(); evidence.add(t.id); for (Finance.Transaction previous : baseline.subList(0, Math.min(5, baseline.size()))) evidence.add(previous.id);
                        flags.add(new PaymentFlag("unusually_large_debit", "This debit meets a review threshold of at least three times the median of five or more earlier merchant debits and the minimum review amount. It may be legitimate; this is not proof of fraud.", evidence));
                    }
                }
            }
            prior.add(t);
        }
        for (List<Finance.Transaction> group : duplicates.values()) if (group.size() > 1) {
            List<String> ids = new ArrayList<>(); for (Finance.Transaction t : group.subList(0, Math.min(20, group.size()))) ids.add(t.id);
            flags.add(new PaymentFlag("possible_duplicate", "Distinct imported IDs share the same normalized merchant, date and debit amount. Repeated payments can be legitimate; review the original records before taking any action.", ids));
        }
        flags.sort(Comparator.comparing((PaymentFlag f) -> f.kind).thenComparing(f -> f.evidenceTransactionIds.get(0)));
        return immutable(flags.subList(0, Math.min(MAX_REVIEW_FLAGS, flags.size())));
    }

    /** No essential budget remainder is added to commitments: allocations and bills remain separate. */
    public static CashContext cashContext(Plan plan, LocalDate today, LocalDate horizon) {
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(today, "today"); Objects.requireNonNull(horizon, "horizon");
        long days = ChronoUnit.DAYS.between(today, horizon); if (days < 0 || days > 3660) throw invalid("Invalid cash context horizon");
        List<Commitment> confirmed = new ArrayList<>(); long total = 0;
        for (Commitment c : plan.commitments) if (c.confirmed && c.dueDate.compareTo(today.toString()) >= 0 && c.dueDate.compareTo(horizon.toString()) <= 0) {
            total = Math.addExact(total, c.amountPence); confirmed.add(c);
        }
        confirmed.sort(Comparator.comparing((Commitment c) -> c.dueDate).thenComparing(c -> c.label));
        return new CashContext(plan, today, confirmed, total);
    }

    private static List<Finance.Transaction> unique(List<Finance.Transaction> history) {
        Objects.requireNonNull(history, "history"); Map<String, Finance.Transaction> map = new LinkedHashMap<>();
        for (Finance.Transaction t : history) {
            Objects.requireNonNull(t, "transaction"); if (!"GBP".equals(t.currency)) throw invalid("Only GBP account currency is supported");
            Finance.Transaction old = map.get(t.id);
            if (old != null && (!old.date.equals(t.date) || old.amount != t.amount || !old.name.equals(t.name)
                    || !old.category.equals(t.category) || !old.type.equals(t.type) || !old.raw.equals(t.raw))) throw invalid("Conflicting duplicate transaction ID");
            map.put(t.id, t);
        }
        return new ArrayList<>(map.values());
    }
    private static String category(Finance.Transaction t, Map<String, String> overrides) {
        String c = overrides.get(t.id); return c == null || c.trim().isEmpty() ? t.category : c.trim();
    }
    private static String label(String text) { if (text == null || text.trim().isEmpty() || text.trim().length() > 120) throw invalid("Plan labels must contain one to 120 characters"); return text.trim(); }
    private static String date(String text) { if (text == null || !text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Plan dates must use yyyy-MM-dd"); return Finance.isoDate(text); }
    private static String normal(String text) { return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static void positive(long amount) { if (amount <= 0 || amount > MAX_PLAN_PENCE) throw invalid("Plan amounts must be positive integer GBP pence within supported limits"); }
    private static long exact(BigInteger value) { if (value.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || value.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) throw new ArithmeticException("Planning arithmetic overflow"); return value.longValue(); }
    private static <T> List<T> immutable(List<T> list) { return Collections.unmodifiableList(new ArrayList<>(list)); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
