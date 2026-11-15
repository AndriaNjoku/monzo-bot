package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only planning tools. Plans and balances come only from explicit user context, never inference.
 * Labels include custom budget categories, commitments and priorities and require host consent.
 * The host must separately authorize provider processing and check assess_transaction refs against
 * evidence returned earlier in this conversation. This stateless adapter validates opaque refs and
 * resolves them locally; it never accepts raw source transaction IDs, writes plans or moves money.
 */
public final class CompanionPlanningTools {
    private static final int MAX_PAGE = 20;
    private static final int MAX_EVIDENCE = 10;
    private static final int MAX_LABEL_POINTS = 160;
    private CompanionPlanningTools() {}

    public static JSONArray definitions() {
        JSONArray tools = new JSONArray();
        tools.put(definition("planning_context",
                "Read-only user-provided planning context. Balance is manually reported, never bank verified; stale or unknown balances are not exposed as current amounts. Headroom is provided only for a report dated today AND user-confirmed complete commitments. It is after known commitments and reserve BEFORE other budget allocations, not spendable cash or verified affordability. Horizon comes from user budget periods, never an invented payday. Labels require separate host consent.", new JSONObject()));
        tools.put(definition("budget_status",
                "Read-only exact impact of imported transactions on explicit user budgets and their own periods. Pot transfers are excluded, credits separated and not assumed income/refunds. Remaining budget and daily guides are allocations, not available cash. Respect essential categories and user priorities. No category/name filters; labels require host consent. Pages do not limit underlying calculations.", pageProperties()));
        tools.put(definition("assess_transaction",
                "Read-only budget impact of ONE previously returned opaque evidence ref. The host must permit only refs already returned by local tools in this conversation. Do not send raw transaction IDs. Shows exact counterfactual budget remaining without versus with the row, not a chronological bank balance. Credits unclassified; Pot transfers not external spending. Never establishes affordability or changes a budget. Labels require host consent.",
                object("ref", object("type", "string", "minLength", 16, "maxLength", 16, "pattern", "^[0-9a-f]{16}$",
                        "description", "Opaque 16-character SHA-256 evidence ref already returned by a local transaction tool."))));
        tools.put(definition("review_payments",
                "Read-only estimated review flags calculated over ALL imported history, not only a budget period or visible page. Similar or unusual payments are reasons to review source evidence, never findings of fraud, duplicates or unwanted subscriptions. No automatic payment, refund or account action. Evidence refs are opaque; raw source metadata and IDs never returned. Display labels require separate host consent.", pageProperties()));
        return tools;
    }

    public static JSONObject execute(String name, JSONObject args, List<Finance.Transaction> history,
                                     Map<String, String> overrides, LocalDate today,
                                     CompanionPlanner.Plan plan, boolean shareLabels) {
        if (name == null || args == null || history == null || overrides == null || today == null) {
            throw invalid("Planning tool requires object arguments, imported history and local date");
        }
        boolean configured = plan != null;
        CompanionPlanner.Plan active = plan == null ? emptyPlan() : plan;
        try {
            switch (name) {
                case "planning_context":
                    allowOnly(args, Collections.emptySet());
                    return context(active, today, configured, shareLabels);
                case "budget_status":
                    allowOnly(args, set("offset", "limit"));
                    return budgets(history, overrides, active, today, configured,
                            integer(args, "offset", 0, 0, Integer.MAX_VALUE), integer(args, "limit", 20, 1, MAX_PAGE), shareLabels);
                case "assess_transaction":
                    allowOnly(args, set("ref"));
                    return assessment(history, overrides, active, today, requiredRef(args), configured, shareLabels);
                case "review_payments":
                    allowOnly(args, set("offset", "limit"));
                    return review(history, today, integer(args, "offset", 0, 0, Integer.MAX_VALUE),
                            integer(args, "limit", 20, 1, MAX_PAGE), configured, shareLabels);
                default:
                    throw invalid("Unknown read-only planning tool");
            }
        } catch (ArithmeticException overflow) { throw invalid("Planning calculation exceeds supported GBP minor-unit precision"); }
    }

    private static JSONObject context(CompanionPlanner.Plan plan, LocalDate today, boolean configured, boolean labels) {
        LocalDate horizon = today;
        for (CompanionPlanner.Budget budget : plan.budgets) {
            LocalDate end = LocalDate.parse(budget.end);
            if (end.isAfter(horizon)) horizon = end;
        }
        CompanionPlanner.CashContext cash = CompanionPlanner.cashContext(plan, today, horizon);
        boolean currentReport = plan.reportedBalancePence != null && today.toString().equals(plan.reportedBalanceDate)
                && "current".equals(cash.balanceStatus);
        Long balance = currentReport ? cash.reportedBalancePence : null;
        Long headroom = currentReport && cash.commitmentsComplete && plan.commitmentsComplete ? cash.headroomPence : null;
        JSONArray commitments = new JSONArray();
        for (int i = 0; i < Math.min(MAX_PAGE, cash.confirmedCommitments.size()); i++) {
            CompanionPlanner.Commitment commitment = cash.confirmedCommitments.get(i);
            JSONObject item = object("dueDate", commitment.dueDate, "amountPence", commitment.amountPence,
                    "confirmedByUser", commitment.confirmed, "source", "user_provided_not_bank_verified");
            if (labels) put(item, "label", label(commitment.label));
            commitments.put(item);
        }
        JSONObject result = base("planning_context", configured, labels);
        put(result, "scope", object("horizonThrough", horizon.toString(), "horizonSource", "latest_user_budget_end_or_today",
                "balanceSource", "user_reported_not_bank_verified", "bankSourceCoverage", "unknown"));
        put(result, "rowCount", 0); put(result, "budgetCount", plan.budgets.size());
        put(result, "balanceStatus", cash.balanceStatus);
        put(result, "reportedBalanceDate", plan.reportedBalanceDate == null || plan.reportedBalanceDate.isEmpty() ? JSONObject.NULL : plan.reportedBalanceDate);
        put(result, "reportedBalancePence", nullable(balance)); put(result, "balanceVerifiedByBank", false);
        put(result, "knownCommitmentsPence", cash.knownCommitmentsPence); put(result, "reservePence", cash.reservePence);
        put(result, "commitmentsComplete", cash.commitmentsComplete && plan.commitmentsComplete);
        put(result, "headroomPence", nullable(headroom));
        put(result, "headroomScope", "after_known_commitments_and_reserve_before_other_budget_allocations");
        put(result, "headroomIsSpendableCash", false);
        put(result, "reviewShortfall", headroom == null ? JSONObject.NULL : headroom < 0);
        put(result, "verifiedAffordability", false);
        put(result, "confirmedCommitmentCount", cash.confirmedCommitments.size());
        put(result, "commitments", commitments); put(result, "commitmentsTruncated", cash.confirmedCommitments.size() > MAX_PAGE);
        put(result, "priorityCount", plan.priorities.size());
        if (labels) {
            JSONArray priorities = new JSONArray();
            for (int i = 0; i < Math.min(MAX_PAGE, plan.priorities.size()); i++) priorities.put(label(plan.priorities.get(i)));
            put(result, "priorities", priorities); put(result, "prioritiesTruncated", plan.priorities.size() > MAX_PAGE);
        }
        put(result, "interpretation", "Manual planning context only. Pending or missing transactions and unlisted commitments may change the picture. Headroom has not deducted other budget allocations and is not permission or a guarantee to spend.");
        return result;
    }

    private static JSONObject budgets(List<Finance.Transaction> history, Map<String, String> overrides,
                                      CompanionPlanner.Plan plan, LocalDate today, boolean configured,
                                      int offset, int limit, boolean labels) {
        List<CompanionPlanner.BudgetStatus> statuses = CompanionPlanner.budgetStatus(history, overrides, plan, today);
        JSONArray items = new JSONArray(); int end = end(offset, limit, statuses.size());
        for (int i = Math.min(offset, statuses.size()); i < end; i++) {
            CompanionPlanner.BudgetStatus status = statuses.get(i);
            JSONObject item = object("budgetIndex", status.budgetIndex, "from", status.start, "to", status.end,
                    "essentialByUser", status.essential, "limitPence", status.limitPence,
                    "grossDebitsPence", status.grossDebitsPence, "creditsPence", status.creditsPence,
                    "usedPence", status.usedPence, "remainingPence", status.remainingPence,
                    "daysLeft", status.daysLeft, "dailyGuidePence", nullable(status.dailyGuidePence),
                    "dailyGuideScope", "remaining_budget_allocation_not_available_cash",
                    "observedDays", status.observedDays, "debitCount", status.debitCount,
                    "projectedDebitPence", nullable(status.projectedDebitPence), "projectionIsEstimate", true,
                    "pace", status.pace, "recommendation", status.recommendation);
            if (labels) put(item, "category", label(status.category));
            budgetEvidence(item, status, history, overrides, today);
            items.put(item);
        }
        JSONObject result = base("budget_status", configured, labels);
        put(result, "scope", object("history", "all_imported_rows_evaluated_against_each_user_budget_period",
                "periodSource", "user_plan_only", "potMovementsIncluded", false, "sourceCoverage", "imported_rows_only"));
        put(result, "historyRowCount", history.size()); put(result, "rowCount", statuses.size());
        page(result, offset, limit, end, statuses.size()); put(result, "budgets", items);
        put(result, "interpretation", "Remaining budget and daily guide are allocations, not cash balances. Credits are separate unclassified money in. Projections are estimates; preserve essential categories and explicit user priorities.");
        return result;
    }

    private static JSONObject assessment(List<Finance.Transaction> history, Map<String, String> overrides,
                                         CompanionPlanner.Plan plan, LocalDate today, String opaque,
                                         boolean configured, boolean labels) {
        Finance.Transaction selected = null;
        for (Finance.Transaction transaction : history) {
            if (ref(transaction.id).equals(opaque)) {
                if (selected != null) throw invalid("Ambiguous opaque transaction reference");
                selected = transaction;
            }
        }
        if (selected == null) throw invalid("Opaque transaction reference was not found in imported history");
        CompanionPlanner.TransactionAssessment assessed = CompanionPlanner.assessTransaction(selected.id, history, overrides, plan, today);
        JSONArray impacts = new JSONArray();
        for (int i = 0; i < Math.min(MAX_PAGE, assessed.budgetImpacts.size()); i++) {
            CompanionPlanner.BudgetImpact impact = assessed.budgetImpacts.get(i);
            JSONObject item = object("budgetIndex", impact.budgetIndex, "essentialByUser", impact.essential,
                    "beforeRemainingPence", impact.beforeRemainingPence, "afterRemainingPence", impact.afterRemainingPence,
                    "countedDebitPence", impact.countedDebitPence, "creditsPence", impact.creditsPence,
                    "comparison", "snapshot_without_transaction_vs_with_transaction");
            if (labels) put(item, "category", label(impact.category));
            impacts.put(item);
        }
        JSONObject result = base("assess_transaction", configured, labels);
        put(result, "scope", object("history", "all_imported_rows_evaluated_against_user_budget_periods",
                "periodSource", "user_plan_only", "evidenceOriginMustBeCheckedByHost", true));
        put(result, "historyRowCount", history.size()); put(result, "rowCount", 1); put(result, "ref", opaque);
        put(result, "evidenceRefs", new JSONArray().put(opaque)); put(result, "date", assessed.date);
        put(result, "amountPence", assessed.amountPence); put(result, "potMovement", assessed.potMovement);
        put(result, "creditUnclassified", assessed.creditUnclassified); put(result, "reviewOnly", true);
        put(result, "budgetImpactCount", assessed.budgetImpacts.size()); put(result, "budgetImpacts", impacts);
        put(result, "budgetImpactsTruncated", assessed.budgetImpacts.size() > MAX_PAGE);
        if (labels) { put(result, "category", label(assessed.category)); put(result, "type", label(assessed.type)); }
        put(result, "interpretation", "This row's counterfactual effect on declared budgets, not a bank balance. Pot movements are not external spending; positive credits are not automatically income or refunds. No financial actions were taken.");
        return result;
    }

    private static JSONObject review(List<Finance.Transaction> history, LocalDate today, int offset, int limit, boolean configured, boolean labels) {
        List<CompanionPlanner.PaymentFlag> flags = CompanionPlanner.reviewFlags(history, today);
        JSONArray items = new JSONArray(); int end = end(offset, limit, flags.size());
        for (int i = Math.min(offset, flags.size()); i < end; i++) {
            CompanionPlanner.PaymentFlag flag = flags.get(i);
            JSONObject item = object("kind", flag.kind, "explanation", flag.explanation,
                    "severity", "review", "requiresReview", true, "confirmedFraud", false,
                    "confirmedDuplicate", false, "estimatedSignal", true,
                    "evidenceRowCount", flag.evidenceTransactionIds.size(), "evidenceScope", "bounded_planner_evidence_sample",
                    "evidenceTruncated", flag.evidenceTransactionIds.size() > MAX_EVIDENCE);
            JSONArray refs = new JSONArray();
            for (int j = 0; j < Math.min(MAX_EVIDENCE, flag.evidenceTransactionIds.size()); j++) refs.put(ref(flag.evidenceTransactionIds.get(j)));
            put(item, "evidenceRefs", refs);
            if ("unusually_large_debit".equals(flag.kind)) {
                put(item, "calculation", object("minimumReviewDebitPence", CompanionPlanner.MIN_LARGE_DEBIT_PENCE,
                        "atLeastBaselineMedianMultiplier", CompanionPlanner.LARGE_DEBIT_MULTIPLIER,
                        "baselineScope", "at_least_five_earlier_same_merchant_debits", "fullBaselineMedianNotReturned", true));
            } else if ("possible_duplicate".equals(flag.kind)) {
                put(item, "calculation", object("matchedFields", new JSONArray().put("normalized_merchant").put("date").put("debit_amount"),
                        "distinctImportedIds", true, "reviewOnly", true));
            }
            // Source names are never needed to explain these deterministic review signals.
            items.put(item);
        }
        JSONObject result = base("review_payments", configured, labels);
        put(result, "scope", object("history", "all_imported", "today", today.toString(),
                "sourceCoverage", "imported_rows_only", "classification", "estimated_review_signals_only",
                "candidateWindowFrom", today.minusDays(29).toString(), "candidateWindowThrough", today.toString(),
                "baselineHistory", "all_earlier_imported_history"));
        put(result, "historyRowCount", history.size()); put(result, "rowCount", flags.size());
        put(result, "reviewFlagSafetyLimit", CompanionPlanner.MAX_REVIEW_FLAGS);
        put(result, "additionalFlagsMayBeOmitted", flags.size() == CompanionPlanner.MAX_REVIEW_FLAGS);
        page(result, offset, limit, end, flags.size()); put(result, "flags", items);
        put(result, "interpretation", "Flags are suggestions to review original payments and context. Similar rows can be legitimate separate payments. An unusual amount or pattern does not establish fraud, an error, a duplicate, or an unwanted subscription. No account actions were taken.");
        return result;
    }

    private static void budgetEvidence(JSONObject item, CompanionPlanner.BudgetStatus status,
                                       List<Finance.Transaction> history, Map<String,String> overrides, LocalDate today) {
        List<Finance.Transaction> contributing = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (Finance.Transaction transaction : history) {
            String corrected = overrides.get(transaction.id);
            String category = corrected == null || corrected.trim().isEmpty() ? transaction.category : corrected.trim();
            if (transaction.amount == 0 || CompanionInsights.isPotMovement(transaction)
                    || !normal(category).equals(normal(status.category)) || transaction.date.compareTo(status.start) < 0
                    || transaction.date.compareTo(status.end) > 0 || transaction.date.compareTo(today.toString()) > 0
                    || !seen.add(transaction.id)) continue;
            contributing.add(transaction);
        }
        contributing.sort(Comparator.comparing((Finance.Transaction transaction) -> transaction.date)
                .thenComparing(transaction -> transaction.id).reversed());
        JSONArray refs = new JSONArray();
        for (int i = 0; i < Math.min(MAX_EVIDENCE, contributing.size()); i++) refs.put(ref(contributing.get(i).id));
        put(item, "evidenceRefs", refs); put(item, "evidenceRowCount", contributing.size());
        put(item, "evidenceTruncated", contributing.size() > MAX_EVIDENCE);
    }
    private static String normal(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    private static CompanionPlanner.Plan emptyPlan() {
        return new CompanionPlanner.Plan(Collections.emptyList(), Collections.emptyList(), null, "", 0, false, Collections.emptyList());
    }
    private static JSONObject base(String tool, boolean configured, boolean labels) {
        return object("tool", tool, "currency", "GBP", "planningConfigured", configured,
                "labelsShared", labels, "readOnly", true, "financialActionsAvailable", false);
    }
    private static void page(JSONObject result, int offset, int limit, int end, int total) {
        put(result, "offset", offset); put(result, "limit", limit); put(result, "returnedRowCount", Math.max(0, end - Math.min(offset, total)));
        put(result, "hasMore", end < total); put(result, "nextOffset", end < total ? end : JSONObject.NULL);
    }
    private static int end(int offset, int limit, int total) { return (int) Math.min((long) total, (long) offset + limit); }
    private static String requiredRef(JSONObject args) {
        Object value = args.opt("ref");
        if (!(value instanceof String) || !((String) value).matches("[0-9a-f]{16}")) throw invalid("A previously returned opaque transaction reference is required");
        return (String) value;
    }
    private static String ref(String id) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(16); char[] hex = "0123456789abcdef".toCharArray();
            for (int i = 0; i < 8; i++) { int b = digest[i] & 255; value.append(hex[b >>> 4]).append(hex[b & 15]); }
            return value.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static String label(String text) {
        if (text == null) return "";
        return text.codePointCount(0, text.length()) <= MAX_LABEL_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, MAX_LABEL_POINTS)) + "…";
    }
    private static Object nullable(Long value) { return value == null ? JSONObject.NULL : value; }
    private static int integer(JSONObject args, String key, int fallback, int min, int max) {
        Object value = args.opt(key); if (value == null || value == JSONObject.NULL) return fallback;
        if (!(value instanceof Number)) throw invalid("Pagination must use integers");
        try {
            int number = new BigDecimal(value.toString()).intValueExact();
            if (number < min || number > max) throw invalid("Pagination is outside supported range");
            return number;
        } catch (ArithmeticException | NumberFormatException invalidNumber) { throw invalid("Pagination must use supported integers"); }
    }
    private static Set<String> set(String... values) {
        Set<String> result = new HashSet<>(); Collections.addAll(result, values); return result;
    }
    private static void allowOnly(JSONObject args, Set<String> allowed) {
        Iterator<String> names = args.keys(); while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid("Unexpected planning tool argument");
    }
    private static JSONObject pageProperties() {
        return object("offset", object("type", new JSONArray().put("integer").put("null"), "minimum", 0,
                        "description", "Zero-based offset; null defaults to zero."),
                "limit", object("type", new JSONArray().put("integer").put("null"), "minimum", 1, "maximum", MAX_PAGE,
                        "description", "Page size 1 to 20; null defaults to 20."));
    }
    private static JSONObject definition(String name, String description, JSONObject properties) {
        JSONArray required = new JSONArray(); Iterator<String> names = properties.keys(); while (names.hasNext()) required.put(names.next());
        return object("type", "function", "function", object("name", name, "description", description,
                "strict", true, "parameters", object("type", "object", "properties", properties,
                        "required", required, "additionalProperties", false)));
    }
    private static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(value, (String) pairs[i], pairs[i + 1]); return value;
    }
    private static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); } catch (JSONException invalidJson) { throw new IllegalStateException("Unable to represent planning result"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
