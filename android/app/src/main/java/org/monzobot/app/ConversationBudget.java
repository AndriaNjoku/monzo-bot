package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tentative text -> explicit reviewed local allocation. No bank, transaction or commitment writes. */
public final class ConversationBudget {
    private ConversationBudget() {}
    public static final int MAX_RECORDS = 500;
    private static final int MAX_TEXT = 8192;
    private static final Pattern AMOUNT = Pattern.compile("(?:£\\s*|\\bGBP\\s+)([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)|([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:GBP\\b|pounds?\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOREIGN = Pattern.compile("[$€¥₹₩₽]|\\b(?:USD|EUR|JPY|AUD|CAD|CHF|INR|CNY|NZD|dollars?|euros?|yen)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("(?<![0-9])([0-9]{4}-[0-9]{2}-[0-9]{2})(?![0-9])");
    private static final Pattern LIABILITY = Pattern.compile("\\b(?:owe|owed|debt|loan|repay|repayment|refund|salary|income|transfer)\\b|\\bpay\\s+back\\b|\\bcredit\\s+card\\s+balance\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPROXIMATE = Pattern.compile("\\b(?:about|around|approximately|roughly|between|up\\s+to|at\\s+least|at\\s+most)\\s*(?:(?:£|GBP)\\s*)?(?![0-9]{4}-[0-9]{2}-[0-9]{2})[0-9]", Pattern.CASE_INSENSITIVE);

    private static final Pattern DATE_LIKE = Pattern.compile("(?<![0-9])(?:[0-9]{1,4}/[0-9]{1,4}(?:/[0-9]{1,4})?|[0-9]{1,4}-[0-9]{1,2}(?:-[0-9]{1,4})?)(?![0-9])");
    private static final Pattern WEEKDAY_SCOPE = Pattern.compile("\\b(?:on|next|this|by|before|after)\\s+(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATED_INTENT = Pattern.compile("\\b(?:cancel|cancelled|canceled|cancelling|canceling|don['’]t|dont|do\\s+not|doesn['’]t|not\\s+want|no\\s+longer|never\\s+mind|forget\\s+(?:it|that))\\b", Pattern.CASE_INSENSITIVE);

    public enum Mode { ADD_ALLOCATION, WITHIN_EXISTING_ALLOCATION }

    public static final class Draft {
        public final String originalText;
        public final Long amountPence;
        public final String from, to;
        public final List<String> issues;
        /** The source utterance is preserved as tentative; parsing never creates an obligation. */
        public final boolean tentative = true;
        private Draft(String originalText, Long amountPence, String from, String to, List<String> issues) {
            this.originalText = originalText; this.amountPence = amountPence; this.from = from; this.to = to;
            this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
        }
    }

    public static final class Proposal {
        public final String id, originalText, name, category, from, to, reviewedOn;
        public final long amountPence;
        public final Mode mode;
        public final boolean tentative = true;
        public Proposal(String id, String originalText, String name, String category, String from, String to,
                        long amountPence, Mode mode, String reviewedOn) {
            try {
                this.id = UUID.fromString(Objects.requireNonNull(id, "id")).toString();
                if (!this.id.equals(id.toLowerCase(Locale.ROOT))) throw invalid("Review token must be a UUID");
            } catch (RuntimeException e) { throw invalid("Review token must be a UUID"); }
            this.originalText = text(originalText);
            this.name = label(name); this.category = label(category);
            CompanionPlanner.Budget checked = new CompanionPlanner.Budget(this.category, from, to, amountPence, false);
            this.from = checked.start; this.to = checked.end; this.amountPence = checked.limitPence;
            if (mode == null) throw invalid("Choose an explicit allocation mode");
            this.mode = mode; this.reviewedOn = strictDate(reviewedOn);
        }
    }

    /** Extract only one precise explicit GBP amount and supported explicit date scope. */
    public static Draft parse(String originalText, LocalDate today) {
        String original = text(originalText); Objects.requireNonNull(today, "today");
        List<String> issues = new ArrayList<>();
        Matcher prices = AMOUNT.matcher(original); List<String> amounts = new ArrayList<>(); boolean ambiguous = false;
        while (prices.find()) {
            amounts.add(prices.group(1) == null ? prices.group(2) : prices.group(1));
            String after = original.substring(prices.end());
            String before = original.substring(0, prices.start());
            if (after.matches("(?s)^\\s*[-–—/]\\s*[£]?[0-9].*")
                    || after.matches("(?is)^\\s*(?:to|or|and)\\s+£?[0-9].*")
                    || after.matches("(?s)^[a-zA-Z%].*") || after.matches("(?s)^[.,][.,0-9].*")
                    || before.matches("(?s).*[0-9]\\s*[-–—/]\\s*$")
                    || before.matches("(?is).*\\b(?:minus|negative)\\s*$")
                    || before.matches("(?s).*[+\\-−]\\s*$")) ambiguous = true;
        }
        Long amount = null;
        if (FOREIGN.matcher(original).find()) issues.add("Foreign or mixed currencies need explicit review in GBP; no conversion was inferred.");
        else if (amounts.size() != 1) issues.add(amounts.isEmpty() ? "Enter one explicit GBP amount, such as £120."
                : "More than one GBP amount appears. Choose one amount explicitly.");
        else if (ambiguous || APPROXIMATE.matcher(original).find()) issues.add("The amount is a range or estimate. Enter one precise amount for review.");
        else {
            String value = amounts.get(0);
            if (!value.matches("[0-9]+(?:\\.[0-9]{1,2})?|[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?")) {
                issues.add("The GBP amount has unsupported precision or formatting.");
            } else try {
                long parsed = Finance.minor(value.replace(",", ""));
                if (parsed <= 0 || parsed > 9_000_000_000_000_000L) throw invalid("Unsupported amount");
                amount = parsed;
            } catch (IllegalArgumentException e) { issues.add("Enter a positive GBP amount within supported precision."); }
        }
        boolean negatedIntent = NEGATED_INTENT.matcher(original).find();
        if (negatedIntent) {
            amount = null;
            issues.add("Negation or cancellation wording needs explicit review. No purchase, cancellation or budget change was inferred.");
        }
        if (LIABILITY.matcher(original).find()) {
            amount = null;
            issues.add("This may describe a debt, credit or money movement rather than a new purchase. Review the purpose and amount; no commitment was created.");
        }
        boolean next = Pattern.compile("\\bnext\\s+month\\b", Pattern.CASE_INSENSITIVE).matcher(original).find();
        boolean current = Pattern.compile("\\bthis\\s+month\\b", Pattern.CASE_INSENSITIVE).matcher(original).find();
        List<String> dates = new ArrayList<>(); List<Integer> dateStarts = new ArrayList<>(), dateEnds = new ArrayList<>();
        Matcher matches = ISO.matcher(original); boolean invalidDate = false;
        while (matches.find()) {
            dateStarts.add(matches.start()); dateEnds.add(matches.end());
            try { dates.add(strictDate(matches.group(1))); }
            catch (IllegalArgumentException e) { invalidDate = true; }
        }
        boolean unsupportedDate = WEEKDAY_SCOPE.matcher(original).find();
        Matcher dateLike = DATE_LIKE.matcher(original);
        while (dateLike.find()) {
            if (!dateLike.group().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) unsupportedDate = true;
        }
        boolean dateAlternatives = dates.size() == 2 && dateStarts.size() == 2
                && Pattern.compile("\\b(?:or|either)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(original.substring(dateEnds.get(0), dateStarts.get(1))).find();
        String from = null, to = null;
        if (unsupportedDate || negatedIntent || invalidDate || dateAlternatives || next && current || (next || current) && !dates.isEmpty() || dates.size() > 2) {
            issues.add("The date scope is invalid or ambiguous. Choose the first and last day explicitly.");
        } else if (next || current) {
            LocalDate month = (next ? today.plusMonths(1) : today).withDayOfMonth(1);
            from = month.toString(); to = month.withDayOfMonth(month.lengthOfMonth()).toString();
        } else if (dates.size() == 1) { from = dates.get(0); to = from; }
        else if (dates.size() == 2 && dates.get(0).compareTo(dates.get(1)) <= 0) { from = dates.get(0); to = dates.get(1); }
        else issues.add("Choose an explicit date or date range, this month, or next month. No period was assumed.");
        return new Draft(original, amount, from, to, issues);
    }

    /** Create once for reviewed fields, then retain this Proposal/token through preview and confirmation. */
    public static Proposal review(Draft draft, String name, String category, String from, String to,
                                  long amountPence, Mode mode, LocalDate today) {
        Objects.requireNonNull(draft, "draft"); Objects.requireNonNull(today, "today");
        return new Proposal(UUID.randomUUID().toString(), draft.originalText, name, category, from, to,
                amountPence, mode, today.toString());
    }

    public static JSONObject encode(Proposal proposal) {
        Objects.requireNonNull(proposal, "proposal"); JSONObject result = new JSONObject();
        put(result, "id", proposal.id); put(result, "originalText", proposal.originalText); put(result, "name", proposal.name);
        put(result, "category", proposal.category); put(result, "from", proposal.from); put(result, "to", proposal.to);
        put(result, "amountPence", proposal.amountPence); put(result, "mode", proposal.mode.name());
        put(result, "reviewedOn", proposal.reviewedOn); put(result, "tentative", true); return result;
    }

    public static Proposal decode(JSONObject value) {
        Objects.requireNonNull(value, "proposal");
        if (!Boolean.TRUE.equals(value.opt("tentative"))) throw invalid("A conversation plan must preserve its tentative source wording");
        final Mode mode;
        try { mode = Mode.valueOf(string(value, "mode")); } catch (RuntimeException e) { throw invalid("Choose an explicit allocation mode"); }
        return new Proposal(string(value, "id"), string(value, "originalText"), string(value, "name"), string(value, "category"),
                string(value, "from"), string(value, "to"), integer(value, "amountPence"), mode, string(value, "reviewedOn"));
    }

    /**
     * Host must also enforce its expected source/plan revision. Confirmation changes only local plan
     * and provision ledger. Replay of an identical saved token is a no-op even at the ledger limit.
     */
    public static JSONObject apply(JSONObject data, Proposal proposal, boolean confirmed) {
        Objects.requireNonNull(data, "data"); Objects.requireNonNull(proposal, "proposal");
        if (!confirmed) throw invalid("Explicit confirmation is required to save a local allocation");
        JSONArray existing = records(data); String hash = payload(proposal);
        for (int i = 0; i < existing.length(); i++) {
            JSONObject record = object(existing.opt(i), "Planning record"); Proposal saved = decode(object(record.opt("proposal"), "Saved proposal"));
            if (saved.id.equals(proposal.id)) {
                if (!hash.equals(string(record, "payloadSha256"))) throw invalid("Review token was already used for different fields; review again");
                return copy(data);
            }
        }
        if (existing.length() >= MAX_RECORDS) throw invalid("The local planning ledger is full. No idempotency records were removed; nothing was saved.");
        CompanionPlanner.Plan plan = BudgetWorkspace.read(data); int exact = -1;
        for (int i = 0; i < plan.budgets.size(); i++) {
            CompanionPlanner.Budget budget = plan.budgets.get(i);
            if (!normal(budget.category).equals(normal(proposal.category))) continue;
            if (budget.start.equals(proposal.from) && budget.end.equals(proposal.to)) exact = i;
            else if (budget.start.compareTo(proposal.to) <= 0 && proposal.from.compareTo(budget.end) <= 0) {
                throw invalid("A budget for this category partially overlaps these dates. Review one exact budget period.");
            }
        }
        JSONObject next; long before = exact < 0 ? 0 : plan.budgets.get(exact).limitPence, after = before;
        if (proposal.mode == Mode.ADD_ALLOCATION) {
            try { after = Math.addExact(before, proposal.amountPence); }
            catch (ArithmeticException e) { throw invalid("The new allocation exceeds supported precision"); }
            boolean essential = exact >= 0 && plan.budgets.get(exact).essential;
            next = BudgetWorkspace.upsertBudgetPence(data, exact, proposal.category, proposal.from, proposal.to, after, essential);
        } else {
            if (exact < 0) throw invalid("Choose an existing budget with the same category and exact dates for this mode");
            if (hasNonexactOverlappingProvision(existing, proposal)) {
                throw invalid("Review existing plan dates: a prior provision overlaps this allocation with a different window. Nothing was linked or changed.");
            }
            List<Finance.Transaction> imported = history(data);
            Map<String, String> corrections = overrides(data);
            List<CompanionPlanner.BudgetStatus> statuses = CompanionPlanner.budgetStatus(imported, corrections, plan,
                    LocalDate.parse(proposal.reviewedOn));
            long remaining = statuses.get(exact).remainingPence;
            final long required;
            try { required = Math.addExact(reservedPence(data, proposal.category, proposal.from, proposal.to), proposal.amountPence); }
            catch (ArithmeticException e) { throw invalid("The provision total exceeds supported precision"); }
            if (remaining < required) {
                if (possibleProvisionPaymentOverlap(existing, proposal, imported, corrections)) {
                    throw invalid("Review the existing plan and recorded-payment overlap: a prior provision shares an amount with a recorded debit. Fulfilment is unknown; nothing was linked or marked paid.");
                }
                throw invalid("The recorded remaining allocation does not cover this purchase plus unresolved local provisions. This is an allocation check, not a verified cash shortfall; credits stay separate.");
            }
            next = copy(data);
        }
        JSONObject record = new JSONObject(); put(record, "proposal", encode(proposal)); put(record, "payloadSha256", hash);
        put(record, "status", "active"); put(record, "budgetLimitBeforePence", before); put(record, "budgetLimitAfterPence", after);
        existing.put(record); JSONObject ledger = new JSONObject(); put(ledger, "version", 1); put(ledger, "records", existing);
        put(next, "conversationBudgets", ledger); return next;
    }

    /** Copies and validates the complete bounded ledger; never silently prunes durable replay records. */
    public static JSONArray records(JSONObject data) {
        Objects.requireNonNull(data, "data");
        if (!data.has("conversationBudgets") || data.isNull("conversationBudgets")) return new JSONArray();
        JSONObject ledger = object(data.opt("conversationBudgets"), "Local planning ledger");
        if (integer(ledger, "version") != 1) throw invalid("Unsupported local planning ledger version");
        Object items = ledger.opt("records"); if (!(items instanceof JSONArray)) throw invalid("Local planning records must be an array");
        JSONArray result = (JSONArray) items;
        if (result.length() > MAX_RECORDS) throw invalid("Local planning ledger exceeds its supported limit");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < result.length(); i++) {
            JSONObject record = object(result.opt(i), "Planning record"); Proposal p = decode(object(record.opt("proposal"), "Saved proposal"));
            if (!ids.add(p.id)) throw invalid("Duplicate review tokens in the local planning ledger");
            if (!payload(p).equals(string(record, "payloadSha256"))) throw invalid("Local planning payload does not match its replay record");
            if (!"active".equals(string(record, "status")) && !"cancelled".equals(string(record, "status"))) throw invalid("Unknown local planning record status");
            if (integer(record, "budgetLimitBeforePence") < 0 || integer(record, "budgetLimitAfterPence") < 0) throw invalid("Invalid saved allocation limits");
        }
        try { return new JSONArray(result.toString()); } catch (JSONException e) { throw invalid("Planning records could not be copied"); }
    }

    /** Active purchases provision their explicit allocation, regardless of which budget mode created it. */
    public static long reservedPence(JSONObject data, String category, String from, String to) {
        String start = strictDate(from), end = strictDate(to); long sum = 0;
        if (start.compareTo(end) > 0) throw invalid("Provision dates must form a valid period");
        JSONArray items = records(data);
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject record = object(items.opt(i), "Planning record"); if (!"active".equals(string(record, "status"))) continue;
                Proposal proposal = decode(object(record.opt("proposal"), "Saved proposal"));
                if (normal(proposal.category).equals(normal(category)) && proposal.from.equals(start) && proposal.to.equals(end)) {
                    sum = Math.addExact(sum, proposal.amountPence);
                }
            }
        } catch (ArithmeticException e) { throw invalid("Provision total exceeds supported precision"); }
        return sum;
    }

    private static boolean hasNonexactOverlappingProvision(JSONArray records, Proposal proposal) {
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = object(records.opt(i), "Planning record");
            if (!"active".equals(string(record, "status"))) continue;
            Proposal prior = decode(object(record.opt("proposal"), "Saved proposal"));
            if (normal(prior.category).equals(normal(proposal.category)) && prior.from.compareTo(proposal.to) <= 0
                    && proposal.from.compareTo(prior.to) <= 0
                    && (!prior.from.equals(proposal.from) || !prior.to.equals(proposal.to))) return true;
        }
        return false;
    }
    private static boolean possibleProvisionPaymentOverlap(JSONArray records, Proposal proposal,
                                                            List<Finance.Transaction> history, Map<String, String> overrides) {
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = object(records.opt(i), "Planning record");
            if (!"active".equals(string(record, "status"))) continue;
            Proposal prior = decode(object(record.opt("proposal"), "Saved proposal"));
            if (!normal(prior.category).equals(normal(proposal.category)) || !prior.from.equals(proposal.from)
                    || !prior.to.equals(proposal.to)) continue;
            for (Finance.Transaction transaction : history) {
                String corrected = overrides.get(transaction.id);
                String category = corrected == null || corrected.trim().isEmpty() ? transaction.category : corrected;
                if (!CompanionInsights.isPotMovement(transaction) && transaction.amount == -prior.amountPence
                        && normal(category).equals(normal(prior.category)) && transaction.date.compareTo(prior.from) >= 0
                        && transaction.date.compareTo(prior.to) <= 0 && transaction.date.compareTo(proposal.reviewedOn) <= 0) return true;
            }
        }
        return false;
    }

    private static List<Finance.Transaction> history(JSONObject data) {
        List<Finance.Transaction> result = new ArrayList<>(); Object value = data.opt("transactions");
        if (value == null || value == JSONObject.NULL) return result;
        if (!(value instanceof JSONArray)) throw invalid("Imported transactions must be an array");
        JSONArray rows = (JSONArray) value;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = object(rows.opt(i), "Imported transaction"), raw = object(row.opt("raw"), "Original transaction evidence");
            Map<String, String> source = new LinkedHashMap<>(); Iterator<String> keys = raw.keys();
            while (keys.hasNext()) { String key = keys.next(); source.put(key, string(raw, key)); }
            Object currency = row.opt("currency");
            if (currency != null && currency != JSONObject.NULL && !"GBP".equals(currency)) throw invalid("Only GBP account currency is supported");
            result.add(new Finance.Transaction(string(row, "id"), string(row, "date"), string(row, "name"),
                    string(row, "category"), row.optString("type", ""), "GBP", integer(row, "amount"), source));
        }
        return result;
    }
    private static Map<String, String> overrides(JSONObject data) {
        Map<String, String> result = new LinkedHashMap<>(); Object value = data.opt("overrides");
        if (value == null || value == JSONObject.NULL) return result;
        JSONObject object = object(value, "Category corrections"); Iterator<String> keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); result.put(key, string(object, key)); } return result;
    }
    private static String payload(Proposal proposal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{"conversation-budget-v1", proposal.id, proposal.originalText, proposal.name,
                    proposal.category, proposal.from, proposal.to, Long.toString(proposal.amountPence), proposal.mode.name(), proposal.reviewedOn}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte)(bytes.length >>> 24)); digest.update((byte)(bytes.length >>> 16));
                digest.update((byte)(bytes.length >>> 8)); digest.update((byte)bytes.length); digest.update(bytes);
            }
            StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }
    private static String strictDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Review dates must use yyyy-MM-dd");
        return Finance.isoDate(value);
    }
    private static String text(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_TEXT) throw invalid("Enter a short planning statement of at most 8192 characters");
        return value;
    }
    private static String label(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 120) throw invalid("Reviewed name and category must contain one to 120 characters");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw invalid("Reviewed labels must not contain control characters");
        return value.trim();
    }
    private static String normal(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static JSONObject copy(JSONObject data) {
        try { return new JSONObject(data.toString()); } catch (JSONException e) { throw invalid("Local workspace could not be copied"); }
    }
    private static JSONObject object(Object value, String label) {
        if (!(value instanceof JSONObject)) throw invalid(label + " must be an object"); return (JSONObject)value;
    }
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key); if (!(value instanceof String)) throw invalid("Planning field " + key + " must be text"); return (String)value;
    }
    private static long integer(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number) || !value.toString().matches("-?[0-9]+")) throw invalid("Planning field " + key + " must use integer pence");
        try { return new BigDecimal(value.toString()).longValueExact(); } catch (ArithmeticException e) { throw invalid("Planning value exceeds supported precision"); }
    }
    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException e) { throw invalid("Local planning data could not be encoded"); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
