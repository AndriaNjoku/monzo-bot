package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Explicit user-owned planning edits and JSON persistence. Never edits bank/source transactions. */
public final class BudgetWorkspace {
    private BudgetWorkspace() {}
    public static final int VERSION = 1;

    public static final class Period {
        public final String start, end;
        private Period(LocalDate start, LocalDate end) {
            this.start = Finance.isoDate(start.toString()); this.end = Finance.isoDate(end.toString());
        }
    }

    /** A configured companionPlan is authoritative. Legacy budgets have no invented commitments or balance. */
    public static CompanionPlanner.Plan read(JSONObject data) {
        Objects.requireNonNull(data, "data");
        if (data.has("companionPlan") && !data.isNull("companionPlan")) {
            Object raw = data.opt("companionPlan");
            if (!(raw instanceof JSONObject)) throw invalid("Saved companion plan must be an object");
            return decode((JSONObject) raw);
        }
        List<CompanionPlanner.Budget> budgets = new ArrayList<>();
        Object legacy = data.opt("budgets");
        if (legacy != null && legacy != JSONObject.NULL && !(legacy instanceof JSONObject)) throw invalid("Legacy budgets must be an object");
        if (legacy instanceof JSONObject) {
            JSONObject items = (JSONObject) legacy;
            Iterator<String> categories = items.keys();
            while (categories.hasNext()) {
                String category = categories.next();
                budgets.add(new CompanionPlanner.Budget(category, string(data, "start"), string(data, "end"),
                        integer(items, category), false));
            }
        }
        return new CompanionPlanner.Plan(budgets, List.of(), null, "", 0, false, List.of());
    }

    public static CompanionPlanner.Plan decode(JSONObject plan) {
        Objects.requireNonNull(plan, "plan");
        if (integer(plan, "version") != VERSION) throw invalid("Unsupported companion plan version");
        if (!"GBP".equals(string(plan, "currency"))) throw invalid("Only GBP planning is supported");
        List<CompanionPlanner.Budget> budgets = new ArrayList<>();
        JSONArray budgetRows = array(plan, "budgets");
        for (int i = 0; i < budgetRows.length(); i++) {
            JSONObject budget = object(budgetRows.opt(i), "Budget");
            budgets.add(new CompanionPlanner.Budget(string(budget, "category"), string(budget, "start"),
                    string(budget, "end"), integer(budget, "limitPence"), bool(budget, "essential")));
        }
        List<CompanionPlanner.Commitment> commitments = new ArrayList<>();
        JSONArray commitmentRows = array(plan, "commitments");
        for (int i = 0; i < commitmentRows.length(); i++) {
            JSONObject c = object(commitmentRows.opt(i), "Commitment");
            commitments.add(new CompanionPlanner.Commitment(string(c, "label"), string(c, "dueDate"),
                    integer(c, "amountPence"), bool(c, "confirmed")));
        }
        List<String> priorities = new ArrayList<>();
        JSONArray priorityRows = array(plan, "priorities");
        for (int i = 0; i < priorityRows.length(); i++) {
            Object priority = priorityRows.opt(i);
            if (!(priority instanceof String)) throw invalid("Priorities must be text");
            priorities.add((String) priority);
        }
        if (!plan.has("reportedBalancePence")) throw invalid("Reported balance must be explicitly set or null");
        Long balance = plan.isNull("reportedBalancePence") ? null : integer(plan, "reportedBalancePence");
        String balanceDate = string(plan, "reportedBalanceDate");
        if (balance == null && !balanceDate.isEmpty()) throw invalid("A dated reported balance requires an amount");
        return new CompanionPlanner.Plan(budgets, commitments, balance, balanceDate,
                integer(plan, "reservePence"), bool(plan, "commitmentsComplete"), priorities);
    }

    public static JSONObject encode(CompanionPlanner.Plan plan) {
        Objects.requireNonNull(plan, "plan");
        JSONArray budgets = new JSONArray(), commitments = new JSONArray(), priorities = new JSONArray();
        for (CompanionPlanner.Budget budget : plan.budgets) {
            JSONObject item = new JSONObject();
            put(item, "category", budget.category); put(item, "start", budget.start); put(item, "end", budget.end);
            put(item, "limitPence", budget.limitPence); put(item, "essential", budget.essential); budgets.put(item);
        }
        for (CompanionPlanner.Commitment c : plan.commitments) {
            JSONObject item = new JSONObject();
            put(item, "label", c.label); put(item, "dueDate", c.dueDate); put(item, "amountPence", c.amountPence);
            put(item, "confirmed", c.confirmed); commitments.put(item);
        }
        for (String priority : plan.priorities) priorities.put(priority);
        JSONObject result = new JSONObject();
        put(result, "version", VERSION); put(result, "currency", "GBP"); put(result, "budgets", budgets);
        put(result, "commitments", commitments); put(result, "priorities", priorities);
        put(result, "reportedBalancePence", plan.reportedBalancePence == null ? JSONObject.NULL : plan.reportedBalancePence);
        put(result, "reportedBalanceDate", plan.reportedBalanceDate); put(result, "reservePence", plan.reservePence);
        put(result, "commitmentsComplete", plan.commitmentsComplete);
        return result;
    }

    /** Deep-copy all workspace data and replace only the local companionPlan. */
    public static JSONObject write(JSONObject data, CompanionPlanner.Plan plan) {
        JSONObject copy = copy(data); put(copy, "companionPlan", encode(plan)); return copy;
    }

    /** index=-1 creates; any other index updates an existing budget. Amount is explicit decimal GBP. */
    public static JSONObject upsertBudget(JSONObject data, int index, String category, String start,
                                          String end, String amountGbp, boolean essential) {
        return upsertBudgetPence(data, index, category, start, end, Finance.minor(amountGbp), essential);
    }

    public static JSONObject upsertBudgetPence(JSONObject data, int index, String category, String start,
                                               String end, long limitPence, boolean essential) {
        CompanionPlanner.Plan old = read(data);
        List<CompanionPlanner.Budget> budgets = new ArrayList<>(old.budgets);
        CompanionPlanner.Budget budget = new CompanionPlanner.Budget(category, start, end, limitPence, essential);
        if (index == -1) budgets.add(budget);
        else { index(index, budgets.size(), "Budget"); budgets.set(index, budget); }
        return write(data, rebuild(old, budgets, old.commitments, old.reportedBalancePence,
                old.reportedBalanceDate, old.reservePence, old.commitmentsComplete, old.priorities));
    }

    public static JSONObject removeBudget(JSONObject data, int index) {
        CompanionPlanner.Plan old = read(data);
        List<CompanionPlanner.Budget> budgets = new ArrayList<>(old.budgets);
        index(index, budgets.size(), "Budget"); budgets.remove(index);
        return write(data, rebuild(old, budgets, old.commitments, old.reportedBalancePence,
                old.reportedBalanceDate, old.reservePence, old.commitmentsComplete, old.priorities));
    }

    /** Context is user reported. Setting completeness never converts a balance to bank-verified data. */
    public static JSONObject updateContext(JSONObject data, Long reportedBalancePence, String reportedBalanceDate,
                                            long reservePence, boolean commitmentsComplete, List<String> priorities,
                                            List<CompanionPlanner.Commitment> commitments) {
        CompanionPlanner.Plan old = read(data);
        if (reportedBalancePence == null && reportedBalanceDate != null && !reportedBalanceDate.isEmpty()) {
            throw invalid("A dated reported balance requires an amount");
        }
        return write(data, rebuild(old, old.budgets, commitments, reportedBalancePence,
                reportedBalancePence == null ? "" : reportedBalanceDate, reservePence, commitmentsComplete, priorities));
    }

    /** Add or update an explicit upcoming commitment; unconfirmed entries stay unconfirmed. */
    public static JSONObject upsertCommitment(JSONObject data, int index, String label, String dueDate,
                                               String amountGbp, boolean confirmed) {
        CompanionPlanner.Plan old = read(data);
        List<CompanionPlanner.Commitment> commitments = new ArrayList<>(old.commitments);
        CompanionPlanner.Commitment value = new CompanionPlanner.Commitment(label, dueDate, Finance.minor(amountGbp), confirmed);
        if (index == -1) commitments.add(value);
        else { index(index, commitments.size(), "Commitment"); commitments.set(index, value); }
        // Adding/changing commitments invalidates any previous declaration that the list is complete.
        return write(data, rebuild(old, old.budgets, commitments, old.reportedBalancePence,
                old.reportedBalanceDate, old.reservePence, false, old.priorities));
    }

    public static JSONObject removeCommitment(JSONObject data, int index) {
        CompanionPlanner.Plan old = read(data);
        List<CompanionPlanner.Commitment> commitments = new ArrayList<>(old.commitments);
        index(index, commitments.size(), "Commitment"); commitments.remove(index);
        return write(data, rebuild(old, old.budgets, commitments, old.reportedBalancePence,
                old.reportedBalanceDate, old.reservePence, false, old.priorities));
    }

    public static Period calendarMonth(String anyDate) {
        LocalDate date = strictDate(anyDate);
        return new Period(date.withDayOfMonth(1), date.withDayOfMonth(date.lengthOfMonth()));
    }

    /** A monthly cycle starts on payday and ends the day before the next clamped payday. */
    public static Period monthlyPayCycle(String asOf, int payday) {
        if (payday < 1 || payday > 31) throw invalid("Monthly payday must be 1 to 31");
        LocalDate date = strictDate(asOf);
        YearMonth month = YearMonth.from(date);
        LocalDate start = month.atDay(Math.min(payday, month.lengthOfMonth()));
        if (date.isBefore(start)) month = month.minusMonths(1);
        start = month.atDay(Math.min(payday, month.lengthOfMonth()));
        YearMonth next = month.plusMonths(1);
        LocalDate nextPayday = next.atDay(Math.min(payday, next.lengthOfMonth()));
        return new Period(start, nextPayday.minusDays(1));
    }

    /** Fixed weekly/fortnightly/four-weekly or custom cycles, anchored to an explicit user date. */
    public static Period fixedPayCycle(String anchor, int cycleDays, String asOf) {
        if (cycleDays < 1 || cycleDays > 366) throw invalid("Cycle length must be 1 to 366 days");
        LocalDate first = strictDate(anchor), date = strictDate(asOf);
        long offset = Math.floorDiv(ChronoUnit.DAYS.between(first, date), cycleDays);
        LocalDate start = first.plusDays(Math.multiplyExact(offset, cycleDays));
        return new Period(start, start.plusDays(cycleDays - 1L));
    }

    private static CompanionPlanner.Plan rebuild(CompanionPlanner.Plan old, List<CompanionPlanner.Budget> budgets,
                                                  List<CompanionPlanner.Commitment> commitments, Long balance,
                                                  String balanceDate, long reserve, boolean complete, List<String> priorities) {
        return new CompanionPlanner.Plan(budgets, commitments, balance, balanceDate, reserve, complete, priorities);
    }
    private static JSONObject copy(JSONObject value) {
        Objects.requireNonNull(value, "data");
        try { return new JSONObject(value.toString()); }
        catch (JSONException e) { throw invalid("Workspace data could not be copied"); }
    }
    private static JSONObject object(Object value, String label) {
        if (!(value instanceof JSONObject)) throw invalid(label + " must be an object");
        return (JSONObject) value;
    }
    private static JSONArray array(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) throw invalid("Plan field " + key + " must be an array");
        return (JSONArray) value;
    }
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof String)) throw invalid("Plan field " + key + " must be text");
        return (String) value;
    }
    private static boolean bool(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Boolean)) throw invalid("Plan field " + key + " must be boolean");
        return (Boolean) value;
    }
    private static long integer(JSONObject object, String key) {
        Object value = object.opt(key);
        if (!(value instanceof Number) || !value.toString().matches("-?[0-9]+")) {
            throw invalid("Plan field " + key + " must be integer GBP pence");
        }
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException e) { throw invalid("Plan amount exceeds supported precision"); }
    }
    private static void put(JSONObject target, String key, Object value) {
        try { target.put(key, value); }
        catch (JSONException e) { throw invalid("Plan data could not be encoded"); }
    }
    private static void index(int index, int size, String label) {
        if (index < 0 || index >= size) throw invalid(label + " index is no longer available");
    }
    private static LocalDate strictDate(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid("Plan dates must use yyyy-MM-dd");
        return LocalDate.parse(Finance.isoDate(value));
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
