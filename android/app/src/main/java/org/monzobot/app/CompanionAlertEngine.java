package org.monzobot.app;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic candidates from the complete imported snapshot and explicit plan.
 * No posting, ledger mutation, network or financial actions. The runtime must deduplicate sent
 * keys, apply NotificationPolicy, persist only successful sends, and keep rich explanations local.
 */
public final class CompanionAlertEngine {
    private CompanionAlertEngine() {}
    public static final Duration RETRIEVAL_STALE_AFTER = Duration.ofHours(24);
    public static final int COMMITMENT_WINDOW_DAYS = 3;
    public static final int MAX_CANDIDATES = 50;
    private static final int RECENT_TRANSACTION_DAYS = 30;
    private static final String UNVERIFIED = " Based on imported records; bank-data coverage is unverified.";

    public static final class Settings {
        public final int thresholdPercent;
        public final boolean initialSync;
        /** SHA-256 first sixteen hex characters. Raw IDs are not retained. */
        public final Set<String> newEvidenceRefs;
        public Settings(int thresholdPercent, boolean initialSync, List<String> newlyImportedTransactionIds) {
            if (thresholdPercent < 1 || thresholdPercent > 100) throw new IllegalArgumentException("Threshold must be 1–100 percent");
            Objects.requireNonNull(newlyImportedTransactionIds, "newlyImportedTransactionIds");
            if (newlyImportedTransactionIds.size() > 50_000) throw new IllegalArgumentException("Too many delta IDs");
            Set<String> refs = new LinkedHashSet<>();
            for (String id : newlyImportedTransactionIds) refs.add(evidenceRef(id));
            this.thresholdPercent = thresholdPercent; this.initialSync = initialSync;
            newEvidenceRefs = Collections.unmodifiableSet(refs);
        }
    }

    public static final class Candidate {
        /** Opaque full SHA-256: suitable for a private sent-key ledger and OS notification tag. */
        public final String key, kind, title, body, localExplanation, retrievalStatus;
        public final String sourceCoverage = "unverified";
        public final List<String> evidenceRefs;
        public final int priority;
        public final boolean estimate, requiresReview = true;
        private Candidate(String kind, int priority, boolean estimate, String explanation,
                          List<String> refs, String retrievalStatus, String... keyParts) {
            this.kind = kind; this.priority = priority; this.estimate = estimate;
            key = key(kind, keyParts); localExplanation = explanation;
            this.retrievalStatus = retrievalStatus;
            title = "Private companion check-in";
            body = kind.equals("freshness")
                    ? NotificationPolicy.genericBody(retrievalStatus)
                    : "Open your private companion to review a new check-in.";
            evidenceRefs = Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(refs)));
        }
    }

    /**
     * Call with complete history, not a period-filtered UI list. Settings.initialSync suppresses
     * historic money signals. Delta IDs should be detected by the sync owner before replacement;
     * pending deltas may be retained briefly by that owner across quiet hours/cooldowns.
     *
     * Successful retrieval is NOT proof of live bank data. An absent, invalid, future or older
     * than 24-hour retrieval produces one generic freshness candidate and no financial candidate.
     */
    public static List<Candidate> evaluate(List<Finance.Transaction> history, Map<String, String> overrides,
                                           CompanionPlanner.Plan plan, LocalDate today, Instant now,
                                           String retrievedAt, Settings settings) {
        Objects.requireNonNull(history, "history"); Objects.requireNonNull(overrides, "overrides");
        Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(today, "today");
        Objects.requireNonNull(now, "now"); Objects.requireNonNull(settings, "settings");
        String retrieval = retrievalStatus(now, retrievedAt);
        if (!retrieval.equals("recent")) {
            return immutable(List.of(new Candidate("freshness", 40, false,
                    "The app cannot establish a recent successful retrieval. Refresh the selected export before relying on snapshot-based check-ins. Bank-data coverage remains unverified.",
                    List.of(), retrieval, retrieval, today.toString())));
        }
        if (settings.initialSync) return List.of();

        List<Finance.Transaction> rows = uniquePastRows(history, today);
        Map<String, Finance.Transaction> byId = new LinkedHashMap<>();
        Set<String> recentNovel = new LinkedHashSet<>();
        LocalDate recentStart = today.minusDays(RECENT_TRANSACTION_DAYS - 1L);
        for (Finance.Transaction t : rows) {
            byId.put(t.id, t);
            if (t.amount < 0 && !CompanionInsights.isPotMovement(t)
                    && !LocalDate.parse(t.date).isBefore(recentStart)
                    && settings.newEvidenceRefs.contains(evidenceRef(t.id))) recentNovel.add(t.id);
        }
        // Backfilled old rows are present in both snapshots, so they cannot create a fake crossing.
        List<Finance.Transaction> before = new ArrayList<>();
        for (Finance.Transaction t : rows) if (!recentNovel.contains(t.id)) before.add(t);
        List<CompanionPlanner.BudgetStatus> afterStatus = CompanionPlanner.budgetStatus(rows, overrides, plan, today);
        List<CompanionPlanner.BudgetStatus> beforeStatus = CompanionPlanner.budgetStatus(before, overrides, plan, today);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < afterStatus.size(); i++) {
            CompanionPlanner.BudgetStatus after = afterStatus.get(i), prior = beforeStatus.get(i);
            if (today.toString().compareTo(after.start) < 0 || today.toString().compareTo(after.end) > 0) continue;
            List<String> evidence = new ArrayList<>();
            for (Finance.Transaction t : rows) if (recentNovel.contains(t.id)
                    && normal(category(t, overrides)).equals(normal(after.category))
                    && t.date.compareTo(after.start) >= 0 && t.date.compareTo(after.end) <= 0) evidence.add(evidenceRef(t.id));
            if (evidence.isEmpty()) continue;
            String category = after.category;
            String essential = after.essential ? " This allocation is user-marked essential; protect it and review flexible spending first." : " Review optional future spending against your priorities.";
            String[] baseKey = {normal(category), after.start, after.end, Long.toString(after.limitPence)};
            if (after.usedPence > after.limitPence && prior.usedPence <= prior.limitPence) {
                candidates.add(new Candidate("budget_exceeded", 90, false,
                        "Imported debits for " + category + " are " + Finance.money(after.usedPence) + " against a "
                                + Finance.money(after.limitPence) + " allocation. Credits are separate and are not assumed refunds." + essential + UNVERIFIED,
                        bounded(evidence, 20), retrieval, baseKey));
            } else if (after.usedPence >= threshold(after.limitPence, settings.thresholdPercent)
                    && prior.usedPence < threshold(after.limitPence, settings.thresholdPercent)) {
                candidates.add(new Candidate("budget_threshold", 80, false,
                        "Imported debits for " + category + " reached the " + settings.thresholdPercent + "% budget threshold. "
                                + Finance.money(after.remainingPence) + " of the allocation remains; this is not available bank cash." + essential + UNVERIFIED,
                        bounded(evidence, 20), retrieval, normal(category), after.start, after.end,
                        Long.toString(after.limitPence), Integer.toString(settings.thresholdPercent)));
            } else if (after.projectedDebitPence != null && after.projectedDebitPence > after.limitPence
                    && (prior.projectedDebitPence == null || prior.projectedDebitPence <= prior.limitPence)) {
                candidates.add(new Candidate("budget_pace_estimate", 60, true,
                        "The imported debit pace for " + category + " projects " + Finance.money(after.projectedDebitPence)
                                + " over the allocation window, compared with " + Finance.money(after.limitPence)
                                + ". This is an uncertain estimate, not a future payment or affordability guarantee." + essential + UNVERIFIED,
                        bounded(evidence, 20), retrieval, baseKey));
            }
        }

        for (CompanionPlanner.PaymentFlag flag : CompanionPlanner.reviewFlags(rows, today)) {
            boolean novel = false;
            if (flag.kind.equals("unusually_large_debit")) novel = !flag.evidenceTransactionIds.isEmpty()
                    && recentNovel.contains(flag.evidenceTransactionIds.get(0));
            else for (String id : flag.evidenceTransactionIds) if (recentNovel.contains(id)) novel = true;
            if (!novel || flag.evidenceTransactionIds.isEmpty()) continue;
            Finance.Transaction event = byId.get(flag.evidenceTransactionIds.get(0));
            if (event == null) continue;
            List<String> refs = new ArrayList<>(); for (String id : flag.evidenceTransactionIds) refs.add(evidenceRef(id));
            candidates.add(new Candidate(flag.kind, 100, false, flag.explanation + UNVERIFIED,
                    bounded(refs, 20), retrieval, normal(event.name), event.date, Long.toString(event.amount)));
        }

        for (CompanionPlanner.Commitment commitment : plan.commitments) {
            LocalDate due = LocalDate.parse(commitment.dueDate);
            if (!commitment.confirmed || due.isBefore(today) || due.isAfter(today.plusDays(COMMITMENT_WINDOW_DAYS))) continue;
            candidates.add(new Candidate("confirmed_commitment_due", 95, false,
                    "Your user-confirmed plan lists " + commitment.label + " for " + Finance.money(commitment.amountPence)
                            + " on " + commitment.dueDate + ". This reminder comes from your plan, not a bank-confirmed upcoming payment. No payment has been made.",
                    List.of(), retrieval, normal(commitment.label), commitment.dueDate, Long.toString(commitment.amountPence)));
        }
        // One strongest budget signal per budget and one stable group per review/commitment event.
        Map<String, Candidate> deduplicated = new LinkedHashMap<>();
        for (Candidate candidate : candidates) deduplicated.putIfAbsent(candidate.key, candidate);
        List<Candidate> result = new ArrayList<>(deduplicated.values());
        result.sort(Comparator.comparingInt((Candidate c) -> c.priority).reversed().thenComparing(c -> c.key));
        return immutable(bounded(result, MAX_CANDIDATES));
    }

    public static String retrievalStatus(Instant now, String retrievedAt) {
        Objects.requireNonNull(now, "now");
        if (retrievedAt == null || retrievedAt.trim().isEmpty()) return "unknown";
        try {
            Instant retrieved = Instant.parse(retrievedAt);
            if (retrieved.isAfter(now)) return "unknown";
            return Duration.between(retrieved, now).compareTo(RETRIEVAL_STALE_AFTER) > 0 ? "stale" : "recent";
        } catch (DateTimeParseException invalid) { return "unknown"; }
    }

    /** Same local opaque reference convention as the companion tool adapters. */
    public static String evidenceRef(String transactionId) {
        if (transactionId == null || transactionId.trim().isEmpty() || transactionId.length() > 2000) throw new IllegalArgumentException("Invalid transaction ID");
        return hex(digest().digest(transactionId.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
    }
    private static String key(String kind, String... parts) {
        MessageDigest digest = digest(); part(digest, "companion-alert-v1"); part(digest, kind);
        for (String value : parts) part(digest, value);
        return hex(digest.digest());
    }
    private static void part(MessageDigest digest, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (value.length >>> 24)); digest.update((byte) (value.length >>> 16));
        digest.update((byte) (value.length >>> 8)); digest.update((byte) value.length); digest.update(value);
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static String hex(byte[] value) {
        char[] chars = new char[value.length * 2]; String digits = "0123456789abcdef";
        for (int i = 0; i < value.length; i++) { chars[i * 2] = digits.charAt((value[i] & 255) >>> 4); chars[i * 2 + 1] = digits.charAt(value[i] & 15); }
        return new String(chars);
    }
    private static long threshold(long limit, int percentage) {
        return BigInteger.valueOf(limit).multiply(BigInteger.valueOf(percentage)).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValue();
    }
    private static String category(Finance.Transaction t, Map<String, String> overrides) { String value = overrides.get(t.id); return value == null || value.trim().isEmpty() ? t.category : value.trim(); }
    private static String normal(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static List<Finance.Transaction> uniquePastRows(List<Finance.Transaction> history, LocalDate today) {
        Map<String, Finance.Transaction> rows = new LinkedHashMap<>();
        for (Finance.Transaction t : history) {
            Objects.requireNonNull(t, "transaction"); Finance.Transaction old = rows.get(t.id);
            if (old != null && (!old.raw.equals(t.raw) || old.amount != t.amount || !old.date.equals(t.date)
                    || !old.category.equals(t.category) || !old.name.equals(t.name) || !old.type.equals(t.type))) throw new IllegalArgumentException("Conflicting duplicate transaction ID");
            rows.put(t.id, t);
        }
        List<Finance.Transaction> past = new ArrayList<>();
        for (Finance.Transaction t : rows.values()) if (!LocalDate.parse(t.date).isAfter(today)) past.add(t);
        return past;
    }
    private static <T> List<T> bounded(List<T> values, int max) { return new ArrayList<>(values.subList(0, Math.min(values.size(), max))); }
    private static <T> List<T> immutable(List<T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
}
