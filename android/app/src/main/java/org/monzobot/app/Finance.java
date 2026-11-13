package org.monzobot.app;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure, deterministic finance calculations; no Android, network, logging or storage APIs. */
public final class Finance {
    private Finance() {}

    private static final List<String> REQUIRED = List.of(
            "Transaction ID", "Date", "Name", "Category", "Amount", "Currency");
    private static final DateTimeFormatter UK_DATE = DateTimeFormatter
            .ofPattern("dd/MM/uuuu", Locale.UK).withResolverStyle(ResolverStyle.STRICT);

    /** Source evidence is immutable. Category corrections belong in a separate override map. */
    public static final class Transaction {
        public final String id, date, name, category, type, currency;
        public final long amount;
        public final Map<String, String> raw;

        public Transaction(String id, String date, String name, String category,
                           String type, String currency, long amount, Map<String, String> raw) {
            if (id == null || id.trim().isEmpty()) throw invalid("Missing transaction ID");
            if (!"GBP".equals(currency)) throw invalid("Only GBP account currency is supported");
            this.id = id.trim();
            this.date = isoDate(date);
            this.name = Objects.requireNonNull(name, "name");
            this.category = category == null || category.trim().isEmpty()
                    ? "Uncategorised" : category.trim();
            this.type = type == null ? "" : type;
            this.currency = currency;
            this.amount = amount;
            this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(raw, "raw")));
        }
    }

    /** Category and daily values are signed net outflow: debits positive, credits negative. */
    public static final class Report {
        public final long inflows, outflows, net;
        public final int count;
        public final Map<String, Long> categories, daily;

        private Report(long inflows, long outflows, int count,
                       Map<String, Long> categories, Map<String, Long> daily) {
            this.inflows = inflows;
            this.outflows = outflows;
            this.net = Math.subtractExact(inflows, outflows);
            this.count = count;
            this.categories = Collections.unmodifiableMap(new LinkedHashMap<>(categories));
            this.daily = Collections.unmodifiableMap(new LinkedHashMap<>(daily));
        }
    }

    public static final class Alert {
        public final String category, level;
        public final long spent, limit, remaining;

        private Alert(String category, long spent, long limit) {
            this.category = category;
            this.spent = spent;
            this.limit = limit;
            this.remaining = Math.subtractExact(limit, spent);
            this.level = spent > limit ? "over" : "threshold";
        }
    }

    /** Convert a decimal GBP amount to integer pence, rejecting rounding and overflow. */
    public static long minor(String value) {
        if (value == null || !value.trim().matches("-?[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw invalid("Amount must be a decimal with at most two decimal places");
        }
        try {
            return new BigDecimal(value.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw invalid("Amount exceeds supported precision");
        }
    }

    /** Parse UK GBP display values without ever guessing a decimal/grouping convention. */
    private static long sourceMinor(String value) {
        if (value == null) throw invalid("Missing amount");
        String text = value.trim();
        if (!text.matches("-?£?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) {
            throw invalid("Amount must use GBP decimal formatting with optional £ and UK thousands groups");
        }
        return minor(text.replace("£", "").replace(",", ""));
    }

    public static String money(long amount) {
        return NumberFormat.getCurrencyInstance(Locale.UK).format(BigDecimal.valueOf(amount, 2));
    }

    /** Accept an unambiguous ISO date or explicit UK day/month/year, with strict leap-day rules. */
    public static String isoDate(String value) {
        if (value == null) throw invalid("Missing date");
        String text = value.trim();
        try {
            if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).toString();
            }
            if (text.matches("[0-9]{2}/[0-9]{2}/[0-9]{4}")) {
                return LocalDate.parse(text, UK_DATE).toString();
            }
        } catch (DateTimeParseException e) {
            throw invalid("Invalid calendar date");
        }
        throw invalid("Date must use yyyy-MM-dd or dd/MM/yyyy");
    }

    /**
     * Import CSV atomically. Exact repeated IDs are idempotent; changed source rows are rejected
     * for explicit reconciliation. Similar payments with different IDs are always retained.
     */
    public static List<Transaction> importCsv(String text, List<Transaction> previous) {
        List<List<String>> rows = csv(text);
        if (!rows.isEmpty()) {
            int width = rows.get(0).size();
            for (int i = 1; i < rows.size(); i++) {
                if (rows.get(i).size() != width) throw rowError(i + 1, "Column count mismatch");
            }
        }
        return fromRows(rows, previous);
    }

    /**
     * Import Google Sheets values. Sheets omits trailing empty cells, so short rows are padded;
     * excess cells and missing required headers are rejected. No source data is mutated.
     */
    public static List<Transaction> fromRows(List<List<String>> rows, List<Transaction> previous) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) throw invalid("Missing headers");
        List<String> headers = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cell : rows.get(0)) {
            String header = cell == null ? "" : cell.trim();
            if (headers.isEmpty() && header.startsWith("\uFEFF")) header = header.substring(1);
            if (header.isEmpty() || !seen.add(header)) throw invalid("Empty or duplicate headers");
            headers.add(header);
        }
        for (String required : REQUIRED) {
            if (!seen.contains(required)) throw invalid("Missing column: " + required);
        }
        Map<String, Transaction> byId = new LinkedHashMap<>();
        if (previous != null) {
            for (Transaction transaction : previous) {
                if (transaction == null) throw invalid("Invalid previous transaction");
                Transaction duplicate = byId.putIfAbsent(transaction.id, transaction);
                if (duplicate != null && !sameEvidence(duplicate, transaction)) {
                    throw invalid("Conflicting previous transaction ID");
                }
            }
        }
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row == null) throw rowError(i + 1, "Missing row");
            if (row.stream().allMatch(v -> v == null || v.isEmpty())) continue;
            if (row.size() > headers.size()) throw rowError(i + 1, "Column count mismatch");
            Map<String, String> raw = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                String value = column < row.size() ? row.get(column) : "";
                raw.put(headers.get(column), value == null ? "" : value);
            }
            try {
                Transaction transaction = new Transaction(raw.get("Transaction ID"),
                        raw.get("Date"), raw.get("Name"), raw.get("Category"), raw.get("Type"),
                        raw.get("Currency"), sourceMinor(raw.get("Amount")), raw);
                Transaction duplicate = byId.get(transaction.id);
                if (duplicate != null && !sameEvidence(duplicate, transaction)) {
                    throw invalid("Conflicting transaction ID; import cancelled for review");
                }
                if (duplicate == null) byId.put(transaction.id, transaction);
            } catch (IllegalArgumentException e) {
                // Include a row number, never source IDs, notes, names or other private values.
                throw rowError(i + 1, e.getMessage());
            }
        }
        List<Transaction> result = new ArrayList<>(byId.values());
        result.sort(Comparator.comparing((Transaction t) -> t.date).thenComparing(t -> t.id));
        return Collections.unmodifiableList(result);
    }

    private static boolean sameEvidence(Transaction a, Transaction b) {
        return a.raw.equals(b.raw) && a.date.equals(b.date) && a.name.equals(b.name)
                && a.category.equals(b.category) && a.type.equals(b.type)
                && a.currency.equals(b.currency) && a.amount == b.amount;
    }

    /**
     * Inclusive period; no income/refund/transfer classification is inferred. Explicit source Type
     * values have not yet had accounting semantics verified, so no transfer is silently excluded.
     */
    public static Report summarize(List<Transaction> transactions, String start, String end,
                                   Map<String, String> overrides) {
        String from = isoDate(start), through = isoDate(end);
        if (from.compareTo(through) > 0) throw invalid("Start date must precede end date");
        Map<String, Long> categories = new TreeMap<>(), daily = new TreeMap<>();
        long inflows = 0, outflows = 0;
        int count = 0;
        try {
            for (Transaction transaction : Objects.requireNonNull(transactions, "transactions")) {
                if (transaction.date.compareTo(from) < 0 || transaction.date.compareTo(through) > 0) continue;
                count++;
                if (transaction.amount > 0) inflows = Math.addExact(inflows, transaction.amount);
                else outflows = Math.subtractExact(outflows, transaction.amount);
                String category = overrides == null ? null : overrides.get(transaction.id);
                if (category == null || category.trim().isEmpty()) category = transaction.category;
                else category = category.trim();
                long outflow = Math.negateExact(transaction.amount);
                categories.put(category, Math.addExact(categories.getOrDefault(category, 0L), outflow));
                daily.put(transaction.date, Math.addExact(daily.getOrDefault(transaction.date, 0L), outflow));
            }
            return new Report(inflows, outflows, count, categories, daily);
        } catch (ArithmeticException e) {
            throw invalid("Report totals exceed supported precision");
        }
    }

    public static List<Alert> alerts(Report report, Map<String, Long> budgets, int thresholdPercent) {
        if (thresholdPercent < 1 || thresholdPercent > 100) throw invalid("Threshold must be 1 to 100 percent");
        List<Alert> alerts = new ArrayList<>();
        for (Map.Entry<String, Long> budget : Objects.requireNonNull(budgets, "budgets").entrySet()) {
            String category = budget.getKey();
            Long limit = budget.getValue();
            if (category == null || category.trim().isEmpty() || limit == null || limit <= 0) {
                throw invalid("Budget requires a category and positive minor units");
            }
            long spent = report.categories.getOrDefault(category, 0L);
            // Integer ceiling without overflowing limit * percentage or using floating point.
            long threshold = BigInteger.valueOf(limit).multiply(BigInteger.valueOf(thresholdPercent))
                    .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValue(); // 0 < percentage <= 100, so result <= positive long budget.
            if (spent >= threshold) alerts.add(new Alert(category, spent, limit));
        }
        alerts.sort(Comparator.comparing(a -> a.category));
        return Collections.unmodifiableList(alerts);
    }

    /** RFC-style CSV parsing with quoted commas, escaped quotes, multiline cells and CRLF. */
    public static List<List<String>> csv(String text) {
        if (text == null) throw invalid("Missing CSV data");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false, closedQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closedQuote = true; }
                } else cell.append(c);
                continue;
            }
            if (c == ',' || c == '\n' || c == '\r') {
                row.add(cell.toString()); cell.setLength(0); closedQuote = false;
                if (c != ',') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    appendRow(rows, row); row = new ArrayList<>();
                }
            } else if (c == '"') {
                if (cell.length() != 0 || closedQuote) throw invalid("Malformed CSV quoting");
                quoted = true;
            } else {
                if (closedQuote) throw invalid("Unexpected text after closing CSV quote");
                cell.append(c);
            }
        }
        if (quoted) throw invalid("Unclosed CSV quote");
        row.add(cell.toString()); appendRow(rows, row);
        return rows;
    }

    private static void appendRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(v -> !v.isEmpty())) rows.add(row);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException rowError(int row, String message) {
        return invalid("Row " + row + ": " + message);
    }
}
