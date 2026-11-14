package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Local evidence-backed prompts. Cards are suggestions; creating one never calls a provider.
 * Raw notes, addresses, receipts, descriptions, Pot names and source transaction IDs are not returned.
 * Merchant and category labels require host consent; provider processing requires separate consent.
 */
public final class CompanionOpportunities {
    private static final int MAX_CARDS = 6;
    private static final int MAX_REFS = 10;
    private CompanionOpportunities() {}

    /** All imported history is evaluated. Today makes period and freshness behavior deterministic. */
    public static JSONArray cards(List<Finance.Transaction> history, Map<String,String> overrides,
                                  LocalDate today, CompanionPlanner.Plan plan,
                                  String sourceRetrievedAt, boolean shareLabels) {
        if (history == null || overrides == null || today == null) throw new IllegalArgumentException("Imported history and local date are required");
        if (history.isEmpty()) return new JSONArray();
        for (Finance.Transaction row : history) if (row == null || !"GBP".equals(row.currency)) throw new IllegalArgumentException("Imported GBP rows are required");
        Freshness freshness = freshness(sourceRetrievedAt, today);
        List<Ranked> candidates = new ArrayList<>();
        if (freshness.state.equals("stale")) candidates.add(new Ranked(5, freshnessCard(history, freshness)));
        reviewCard(candidates, history, today);
        budgetCards(candidates, history, overrides, today, plan, shareLabels);
        comparisonCard(candidates, history, overrides, today);
        overviewCard(candidates, history, overrides, today);
        fxCard(candidates, history);
        recurringCard(candidates, history, today);
        if (shareLabels) merchantCard(candidates, history);
        candidates.sort(Comparator.comparingInt((Ranked item) -> item.priority).thenComparing(item -> item.card.optString("kind")));
        JSONArray result = new JSONArray();
        for (int i = 0; i < Math.min(MAX_CARDS, candidates.size()); i++) {
            JSONObject card = candidates.get(i).card;
            put(card, "rank", i + 1); put(card, "generatedForDate", today.toString());
            put(card, "sourceRetrievedAt", freshness.timestamp == null ? JSONObject.NULL : freshness.timestamp);
            put(card, "sourceCoverage", "unknown"); put(card, "retrievalFreshness", freshness.state);
            put(card, "retrievalFreshnessResolution", "calendar_days_against_today");
            put(card, "labelsShared", shareLabels); put(card, "readOnly", true);
            put(card, "automaticallySentToAi", false); put(card, "requiresUserAction", true);
            put(card, "financialActionsAvailable", false);
            result.put(card);
        }
        return result;
    }

    private static JSONObject freshnessCard(List<Finance.Transaction> rows, Freshness freshness) {
        JSONObject card = card("source_review", "Check your export before relying on it",
                "This history was retrieved more than one calendar day ago. Its bank/export coverage remains unverified.",
                "Explain what my imported history can show and what needs a refreshed export before making a budgeting decision.", false,
                object("history", "all_imported", "retrievedAt", freshness.timestamp));
        evidence(card, refs(rows, MAX_REFS), rows.size()); return card;
    }

    private static void comparisonCard(List<Ranked> output, List<Finance.Transaction> rows, Map<String,String> overrides, LocalDate today) {
        YearMonth current = YearMonth.from(today), previous = current.minusMonths(1);
        int days = Math.min(today.getDayOfMonth(), previous.lengthOfMonth());
        LocalDate fromA = previous.atDay(1), toA = previous.atDay(days), fromB = current.atDay(1), toB = current.atDay(days);
        JSONObject comparison = CompanionTools.execute("compare_spending", object("fromA", fromA.toString(), "toA", toA.toString(),
                "fromB", fromB.toString(), "toB", toB.toString()), rows, overrides, today, false);
        JSONObject first = comparison.optJSONObject("windowA"), second = comparison.optJSONObject("windowB");
        if (first.optInt("externalRowCount") == 0 || second.optInt("externalRowCount") == 0) return;
        JSONObject before = first.optJSONObject("amountsPence"), now = second.optJSONObject("amountsPence");
        long earlier = before.optLong("moneyOutPence"), currentAmount = now.optLong("moneyOutPence");
        if (earlier == 0 && currentAmount == 0) return;
        long difference = Math.subtractExact(currentAmount, earlier);
        String title = difference > 0 ? "Recorded spending is higher in the matched window"
                : difference < 0 ? "Recorded spending is lower in the matched window" : "Recorded spending matches the prior window";
        JSONObject scope = object("fromA", fromA.toString(), "toA", toA.toString(), "fromB", fromB.toString(), "toB", toB.toString(),
                "matchedCalendarDays", days, "elapsedCurrentMonthDays", today.getDayOfMonth(),
                "currentWindowClipped", days < today.getDayOfMonth(), "potMovementsIncluded", false,
                "clippingRule", "both_windows_use_first_min_elapsed_current_days_and_prior_month_length");
        JSONObject card = card("matched_period_comparison", title,
                Finance.money(currentAmount) + " recorded money out versus " + Finance.money(earlier) + " across matching first " + days + "-day windows. Export coverage is unknown.",
                "Compare recorded external money out for " + fromB + " through " + toB + " against " + fromA + " through " + toA
                        + ". Explain what changed without comparing a partial month to a full prior month or assuming available cash.", false, scope);
        put(card, "amountsPence", object("currentMoneyOutPence", currentAmount, "priorMoneyOutPence", earlier, "differencePence", difference));
        JSONArray refs = new JSONArray(); addRefs(refs, first.optJSONArray("evidenceRefs"), MAX_REFS / 2); addRefs(refs, second.optJSONArray("evidenceRefs"), MAX_REFS);
        evidence(card, refs, Math.addExact(first.optInt("rowCount"), second.optInt("rowCount"))); output.add(new Ranked(30, card));
    }

    private static void overviewCard(List<Ranked> output, List<Finance.Transaction> rows, Map<String,String> overrides, LocalDate today) {
        JSONObject summary = CompanionTools.execute("summarize_transactions", new JSONObject(), rows, overrides, today, false);
        if (summary.optInt("externalRowCount") == 0) return;
        JSONObject amounts = summary.optJSONObject("amountsPence");
        JSONObject card = card("history_overview", "See the whole recorded picture",
                summary.optInt("externalRowCount") + " external rows: " + Finance.money(amounts.optLong("moneyOutPence"))
                        + " money out and " + Finance.money(amounts.optLong("moneyInPence")) + " unclassified money in. This is not an account balance.",
                "Summarize money in and out across all my imported history, keeping Pot movements separate and credits unclassified. What useful budgeting questions can this evidence answer?", false,
                object("history", "all_imported", "firstObservedDate", summary.opt("firstObservedDate"), "lastObservedDate", summary.opt("lastObservedDate"), "potMovementsIncluded", false));
        put(card, "rowCount", summary.optInt("rowCount")); put(card, "amountsPence", amounts);
        evidence(card, limited(summary.optJSONArray("evidenceRefs"), MAX_REFS), summary.optInt("evidenceRowCount")); output.add(new Ranked(35, card));
    }

    private static void budgetCards(List<Ranked> output, List<Finance.Transaction> rows, Map<String,String> overrides,
                                    LocalDate today, CompanionPlanner.Plan plan, boolean labels) {
        if (plan == null || plan.budgets.isEmpty()) return;
        List<CompanionPlanner.BudgetStatus> statuses = CompanionPlanner.budgetStatus(rows, overrides, plan, today);
        CompanionPlanner.BudgetStatus flexible = null; long maximumPressure = Long.MIN_VALUE;
        List<CompanionPlanner.BudgetStatus> essential = new ArrayList<>();
        for (CompanionPlanner.BudgetStatus status : statuses) {
            if (today.toString().compareTo(status.start) < 0 || today.toString().compareTo(status.end) > 0) continue;
            if (status.essential) essential.add(status);
            if (status.essential || !("over_budget".equals(status.pace) || "projected_over_budget".equals(status.pace))) continue;
            long projected = status.projectedDebitPence == null ? status.grossDebitsPence : Math.max(status.grossDebitsPence, status.projectedDebitPence);
            long pressure = Math.subtractExact(projected, status.limitPence);
            if (flexible == null || pressure > maximumPressure) { flexible = status; maximumPressure = pressure; }
        }
        if (flexible != null) {
            String category = labels ? " for " + label(flexible.category) : "";
            JSONObject card = card("budget_conservation", "A flexible budget needs a check-in" + category,
                    Finance.money(flexible.grossDebitsPence) + " recorded debits against a " + Finance.money(flexible.limitPence)
                            + " allocation. Credits are shown separately; any projection is an estimate.",
                    "Review my flexible budgets and suggest optional conservation steps that respect my stated priorities and protect essential allocations. Use my declared budget periods and do not assume a bank balance.", flexible.projectedDebitPence != null,
                    object("periodSource", "user_plan_only", "from", flexible.start, "to", flexible.end, "budgetIndex", flexible.budgetIndex));
            put(card, "amountsPence", object("grossDebitsPence", flexible.grossDebitsPence, "creditsPence", flexible.creditsPence,
                    "limitPence", flexible.limitPence, "remainingPence", flexible.remainingPence,
                    "projectedDebitPence", flexible.projectedDebitPence == null ? JSONObject.NULL : flexible.projectedDebitPence));
            put(card, "essentialByUser", false); if (labels) put(card, "category", label(flexible.category));
            List<Finance.Transaction> contributing = budgetRows(rows, overrides, flexible, today);
            evidence(card, refs(contributing, MAX_REFS), contributing.size()); output.add(new Ranked(15, card));
        }
        if (!essential.isEmpty()) {
            JSONObject card = card("protected_essentials", "Keep your essential allocations protected",
                    "Your plan marks " + essential.size() + " active allocations as essential. Review flexible choices before changing those priorities.",
                    "Help me preserve my user-marked essential allocations while reviewing flexible budgets and my stated priorities. Keep budget allocations separate from bills and available cash.", false,
                    object("periodSource", "user_plan_only", "activeOn", today.toString(), "essentialBudgetCount", essential.size()));
            List<Finance.Transaction> contributing = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (CompanionPlanner.BudgetStatus status : essential) for (Finance.Transaction row : budgetRows(rows, overrides, status, today)) if (seen.add(row.id)) contributing.add(row);
            evidence(card, refs(contributing, MAX_REFS), contributing.size()); output.add(new Ranked(25, card));
        }
    }

    private static void reviewCard(List<Ranked> output, List<Finance.Transaction> rows, LocalDate today) {
        List<CompanionPlanner.PaymentFlag> flags = CompanionPlanner.reviewFlags(rows, today);
        if (flags.isEmpty()) return;
        JSONArray evidence = new JSONArray(); Set<String> seen = new HashSet<>();
        for (CompanionPlanner.PaymentFlag flag : flags) for (String id : flag.evidenceTransactionIds) if (seen.add(id) && evidence.length() < MAX_REFS) evidence.put(ref(id));
        JSONObject card = card("payment_review", "Some recorded payments are worth reviewing",
                (flags.size() == CompanionPlanner.MAX_REVIEW_FLAGS ? "At least " : "") + flags.size()
                        + " review signals were found. Similar or unusual payments may be legitimate; these are not findings of fraud or confirmed duplicates.",
                "Review unusual or repeated payments in my imported history. Explain the evidence, the calculation and legitimate alternatives without calling them fraud or confirmed duplicates.", true,
                object("history", "all_imported", "candidateFrom", today.minusDays(29).toString(), "candidateThrough", today.toString(), "baselineHistory", "all_earlier_imported_history"));
        put(card, "reviewFlagCount", flags.size()); put(card, "reviewCountBounded", flags.size() == CompanionPlanner.MAX_REVIEW_FLAGS);
        put(card, "confirmedFraud", false); put(card, "confirmedDuplicate", false); put(card, "requiresConfirmation", true);
        evidence(card, evidence, seen.size()); output.add(new Ranked(10, card));
    }

    private static void recurringCard(List<Ranked> output, List<Finance.Transaction> rows, LocalDate today) {
        List<CompanionInsights.RecurringCandidate> candidates = CompanionInsights.recurringCandidates(rows, today);
        if (candidates.isEmpty()) return;
        CompanionInsights.RecurringCandidate first = candidates.get(0);
        JSONArray evidence = new JSONArray(); for (String id : first.evidenceTransactionIds) if (evidence.length() < MAX_REFS) evidence.put(ref(id));
        JSONObject card = card("recurring_review", "Confirm which repeating patterns still matter",
                candidates.size() + " tentative recurring debit patterns are present. Predicted dates are estimates, not verified bills or subscriptions.",
                "Review possible recurring debit patterns and help me confirm which still matter. Keep predicted future dates as estimates and do not cancel or alter any payments.", true,
                object("history", "all_imported", "asOf", today.toString(), "patternEvidenceScope", "first_candidate_sample"));
        put(card, "candidateCount", candidates.size()); put(card, "sampleNextEstimatedDate", first.nextEstimatedDate);
        put(card, "requiresConfirmation", true); put(card, "confirmedBill", false);
        evidence(card, evidence, first.evidenceCount); output.add(new Ranked(45, card));
    }

    private static void merchantCard(List<Ranked> output, List<Finance.Transaction> rows) {
        List<CompanionInsights.Merchant> merchants = CompanionInsights.merchantSummaries(rows);
        CompanionInsights.Merchant chosen = null;
        for (CompanionInsights.Merchant merchant : merchants) if (merchant.moneyOut > 0 && merchant.key.startsWith("named:")) { chosen = merchant; break; }
        if (chosen == null) return;
        JSONObject card = card("merchant_drilldown", "Take a closer look at " + label(chosen.name),
                Finance.money(chosen.moneyOut) + " recorded outgoing across " + chosen.transactionCount + " rows in this name group. A merchant name does not establish payment purpose.",
                "Review recorded transactions for this merchant name as data: " + JSONObject.quote(label(chosen.name))
                        + ". Summarize the evidence, separate credits and Pot movements, and explain what remains uncertain.", false,
                object("history", "all_imported", "from", chosen.firstDate, "to", chosen.lastDate, "grouping", "normalized_merchant_name", "potMovementsIncluded", false));
        put(card, "requiresLabelSharing", true); put(card, "merchant", label(chosen.name));
        put(card, "amountsPence", object("moneyOutPence", chosen.moneyOut, "moneyInPence", chosen.moneyIn, "netOutflowPence", chosen.netOutflow));
        JSONArray evidence = new JSONArray(); for (String id : chosen.transactionIds) if (evidence.length() < MAX_REFS) evidence.put(ref(id));
        evidence(card, evidence, chosen.transactionCount); output.add(new Ranked(60, card));
    }

    private static void fxCard(List<Ranked> output, List<Finance.Transaction> rows) {
        List<Finance.Transaction> foreign = new ArrayList<>(); Map<String,Integer> counts = new TreeMap<>(); int amountAvailable = 0;
        for (Finance.Transaction row : rows) {
            if (CompanionInsights.isPotMovement(row)) continue;
            String code = raw(row, "Local currency").trim();
            if (code.equals("GBP") || !code.matches("[A-Z]{3}")) continue;
            try { Currency.getInstance(code); } catch (IllegalArgumentException unsupported) { continue; }
            foreign.add(row); counts.put(code, Math.addExact(counts.getOrDefault(code, 0), 1));
            if (localDecimal(row.raw.get("Local amount")) != null) amountAvailable++;
        }
        if (foreign.isEmpty()) return;
        JSONObject card = card("original_currency_review", "Your export includes original-currency details",
                foreign.size() + " external rows contain a non-GBP original currency. Review the actual local amounts without assuming an exchange rate or fee.",
                "Help me review imported rows with non-GBP original-currency details. Keep currencies separate, show only amounts actually recorded, and do not infer exchange rates or fees.", false,
                object("history", "all_imported", "originalCurrencySource", "imported_local_currency_field", "fxRateInferred", false, "feesInferred", false));
        JSONArray currencies = new JSONArray(); for (Map.Entry<String,Integer> entry : counts.entrySet()) currencies.put(object("currency", entry.getKey(), "rowCount", entry.getValue()));
        put(card, "originalCurrencies", currencies); put(card, "rowCount", foreign.size()); put(card, "rowsWithParsableLocalAmount", amountAvailable);
        JSONArray samples = new JSONArray();
        for (Finance.Transaction row : foreign) {
            if (samples.length() == 3) break;
            String decimal = localDecimal(row.raw.get("Local amount"));
            if (decimal != null) samples.put(object("ref", ref(row.id), "date", row.date, "accountAmountPence", row.amount,
                    "accountCurrency", "GBP", "localAmountDecimal", decimal, "localCurrency", row.raw.get("Local currency")));
        }
        put(card, "recordedAmountSamples", samples); put(card, "localAmountUnit", "original_currency_decimal_not_assumed_two_minor_digits");
        evidence(card, refs(foreign, MAX_REFS), foreign.size()); output.add(new Ranked(40, card));
    }

    private static List<Finance.Transaction> budgetRows(List<Finance.Transaction> rows, Map<String,String> overrides,
                                                       CompanionPlanner.BudgetStatus budget, LocalDate today) {
        List<Finance.Transaction> result = new ArrayList<>();
        for (Finance.Transaction row : rows) {
            String category = overrides.get(row.id); if (category == null || category.trim().isEmpty()) category = row.category;
            if (row.amount != 0 && !CompanionInsights.isPotMovement(row) && normal(category).equals(normal(budget.category))
                    && row.date.compareTo(budget.start) >= 0 && row.date.compareTo(budget.end) <= 0 && row.date.compareTo(today.toString()) <= 0) result.add(row);
        }
        return result;
    }
    private static String raw(Finance.Transaction row, String key) { String value = row.raw.get(key); return value == null ? "" : value; }
    private static String localDecimal(String value) {
        if (value == null || !value.trim().matches("-?[0-9]{1,18}(?:\\.[0-9]{1,6})?")) return null;
        return new BigDecimal(value.trim()).toPlainString();
    }
    private static JSONObject card(String kind, String title, String body, String question, boolean estimate, JSONObject scope) {
        return object("kind", kind, "title", title, "body", body, "question", question, "estimate", estimate, "scope", scope);
    }
    private static JSONArray refs(List<Finance.Transaction> rows, int limit) {
        List<Finance.Transaction> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((Finance.Transaction row) -> row.date).thenComparing(row -> row.id).reversed());
        JSONArray refs = new JSONArray(); Set<String> seen = new HashSet<>();
        for (Finance.Transaction row : sorted) if (refs.length() < limit && seen.add(row.id)) refs.put(ref(row.id));
        return refs;
    }
    private static JSONArray limited(JSONArray source, int limit) { JSONArray result = new JSONArray(); addRefs(result, source, limit); return result; }
    private static void addRefs(JSONArray target, JSONArray source, int limit) {
        if (source == null) return; Set<String> seen = new HashSet<>();
        for (int i = 0; i < target.length(); i++) seen.add(target.optString(i));
        for (int i = 0; i < source.length() && target.length() < limit; i++) { String value = source.optString(i); if (seen.add(value)) target.put(value); }
    }
    private static void evidence(JSONObject card, JSONArray refs, int count) {
        put(card, "evidenceRefs", refs); put(card, "evidenceRowCount", count); put(card, "evidenceTruncated", count > refs.length());
    }
    private static String ref(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16); char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 8; i++) { int b = digest[i] & 255; result.append(hex[b >>> 4]).append(hex[b & 15]); } return result.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String label(String value) { return value.codePointCount(0, value.length()) <= 100 ? value : value.substring(0, value.offsetByCodePoints(0, 100)) + "…"; }
    private static String normal(String value) { return value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private static final class Ranked { final int priority; final JSONObject card; Ranked(int priority, JSONObject card) { this.priority = priority; this.card = card; } }
    private static final class Freshness { final String state, timestamp; Freshness(String state, String timestamp) { this.state = state; this.timestamp = timestamp; } }
    private static Freshness freshness(String value, LocalDate today) {
        if (value == null || value.trim().isEmpty()) return new Freshness("unknown", null);
        try {
            Instant timestamp = Instant.parse(value); LocalDate date = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
            if (date.isAfter(today)) return new Freshness("unknown", null);
            return new Freshness(date.isBefore(today.minusDays(1)) ? "stale" : "recent", timestamp.toString());
        } catch (java.time.DateTimeException invalid) { return new Freshness("unknown", null); }
    }
    private static JSONObject object(Object... pairs) { JSONObject result = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(result, (String) pairs[i], pairs[i + 1]); return result; }
    private static void put(JSONObject result, String key, Object value) { try { result.put(key, value); } catch (JSONException invalid) { throw new IllegalStateException("Unable to represent local opportunity"); } }
}
