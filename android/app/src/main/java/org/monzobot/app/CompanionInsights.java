package org.monzobot.app;

import java.time.LocalDate;
import java.time.LocalTime;
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

/** Full-history, read-only companion queries. No network, storage, logging, AI or financial actions. */
public final class CompanionInsights {
    private CompanionInsights() {}

    public static final class ActivityItem {
        public final Finance.Transaction source;
        public final String name, category, emoji, time, localAmount, localCurrency, notes, description, potName;
        public final boolean potMovement;
        /** A stable palette index, not a bank-supplied colour or merchant identity. */
        public final int categoryColorIndex;
        private ActivityItem(Finance.Transaction t, Map<String, String> overrides) {
            source = t;
            name = displayName(t);
            category = category(t, overrides);
            emoji = value(t, "Emoji").isEmpty() ? fallbackEmoji(category) : value(t, "Emoji");
            time = value(t, "Time");
            localAmount = value(t, "Local amount");
            localCurrency = value(t, "Local currency");
            notes = value(t, "Notes and #tags");
            description = value(t, "Description");
            potName = value(t, "Pot name");
            potMovement = isPotMovement(t);
            categoryColorIndex = (normalize(category).hashCode() & 0x7fffffff) % 8;
        }
    }

    public static final class ActivityPage {
        public final int total, offset;
        public final boolean hasMore;
        public final List<ActivityItem> items;
        private ActivityPage(int total, int offset, List<ActivityItem> items) {
            this.total = total;
            this.offset = offset;
            this.items = immutable(items);
            hasMore = (long) offset + items.size() < total;
        }
    }

    /**
     * An immutable browsing snapshot. Create a new session after sync or category edits, then
     * restart at offset zero. Keep one session across pages to avoid gaps/duplicates while the
     * caller's live transaction collection changes. Transaction source objects are immutable.
     */
    public static final class ActivitySession {
        private final List<Finance.Transaction> history;
        private final Map<String, String> overrides;
        public final int transactionCount;
        private ActivitySession(List<Finance.Transaction> history, Map<String, String> overrides) {
            Objects.requireNonNull(history, "transactions"); Objects.requireNonNull(overrides, "overrides");
            this.history = immutable(history);
            for (Finance.Transaction t : this.history) validate(t);
            this.overrides = immutableMap(overrides);
            transactionCount = this.history.size();
        }
        /** Offsets are scoped to this captured snapshot and the same search query. */
        public ActivityPage page(String query, int offset, int limit) {
            return activity(history, query, offset, limit, overrides);
        }
    }

    /** Capture while the caller holds any lock protecting its live list/map, if applicable. */
    public static ActivitySession browse(List<Finance.Transaction> history, Map<String, String> overrides) {
        return new ActivitySession(history, overrides);
    }

    public static final class Overview {
        public final int transactionCount, externalTransactionCount, potMovementCount;
        /** External account cashflow in GBP pence. Credits are not assumed to be income/refunds. */
        public final long moneyIn, moneyOut, netMovement, potMoneyIn, potMoneyOut;
        public final String firstDate, lastDate;
        public final Map<String, Long> categoryDebits, categoryCredits, categoryNetOutflow;
        /** Counts only: original currency amounts are deliberately never combined across currencies. */
        public final Map<String, Integer> originalCurrencyCounts;
        public final List<Merchant> merchants;
        private Overview(int count, int externalCount, int potCount, long in, long out,
                         long potIn, long potOut, String first, String last,
                         Map<String, Long> debits, Map<String, Long> credits,
                         Map<String, Integer> currencies, List<Merchant> merchants) {
            transactionCount = count; externalTransactionCount = externalCount; potMovementCount = potCount;
            moneyIn = in; moneyOut = out; netMovement = Math.subtractExact(in, out);
            potMoneyIn = potIn; potMoneyOut = potOut; firstDate = first; lastDate = last;
            categoryDebits = immutableMap(debits); categoryCredits = immutableMap(credits);
            Map<String, Long> net = new LinkedHashMap<>();
            for (String c : debits.keySet()) net.put(c, Math.subtractExact(debits.get(c), credits.getOrDefault(c, 0L)));
            for (String c : credits.keySet()) if (!net.containsKey(c)) net.put(c, Math.negateExact(credits.get(c)));
            categoryNetOutflow = immutableMap(net);
            originalCurrencyCounts = immutableMap(currencies); this.merchants = immutable(merchants);
        }
    }

    public static final class Merchant {
        public final String key, name, emoji, firstDate, lastDate;
        public final int transactionCount;
        public final long moneyIn, moneyOut, netOutflow;
        public final List<String> transactionIds;
        private Merchant(String key, List<Finance.Transaction> transactions) {
            this.key = key;
            List<Finance.Transaction> sorted = ordered(transactions);
            Finance.Transaction latest = sorted.get(0);
            name = displayName(latest);
            emoji = value(latest, "Emoji").isEmpty() ? fallbackEmoji(latest.category) : value(latest, "Emoji");
            firstDate = sorted.get(sorted.size() - 1).date; lastDate = latest.date;
            long in = 0, out = 0; List<String> ids = new ArrayList<>();
            for (Finance.Transaction t : sorted) {
                if (t.amount > 0) in = Math.addExact(in, t.amount);
                if (t.amount < 0) out = Math.addExact(out, Math.negateExact(t.amount));
                ids.add(t.id);
            }
            transactionCount = sorted.size(); moneyIn = in; moneyOut = out;
            netOutflow = Math.subtractExact(out, in); transactionIds = immutable(ids);
        }
    }

    /** An observed pattern for user review; never a confirmed bill, subscription or obligation. */
    public static final class RecurringCandidate {
        public final String merchantKey, name, emoji, cadence, lastSeen, nextEstimatedDate;
        public final long latestDebit;
        public final int evidenceCount;
        public final boolean estimated = true, requiresConfirmation = true, estimateInPast;
        public final List<String> evidenceTransactionIds;
        private RecurringCandidate(String key, List<Finance.Transaction> evidence, String cadence,
                                   LocalDate next, LocalDate today) {
            Finance.Transaction last = evidence.get(evidence.size() - 1);
            merchantKey = key; name = displayName(last);
            emoji = value(last, "Emoji").isEmpty() ? fallbackEmoji(last.category) : value(last, "Emoji");
            this.cadence = cadence; lastSeen = last.date; nextEstimatedDate = next.toString();
            latestDebit = Math.negateExact(last.amount); evidenceCount = evidence.size();
            estimateInPast = next.isBefore(today);
            List<String> ids = new ArrayList<>(); for (Finance.Transaction t : evidence) ids.add(t.id);
            evidenceTransactionIds = immutable(ids);
        }
    }

    /** Every imported row is available; dates are metadata, not a gate defining the dataset. */
    public static Overview overview(List<Finance.Transaction> transactions, Map<String, String> overrides) {
        Objects.requireNonNull(transactions, "transactions"); Objects.requireNonNull(overrides, "overrides");
        long in = 0, out = 0, potIn = 0, potOut = 0; int external = 0, pots = 0;
        String first = "", last = "";
        Map<String, Long> debits = new TreeMap<>(), credits = new TreeMap<>();
        Map<String, Integer> currencies = new TreeMap<>();
        for (Finance.Transaction t : transactions) {
            validate(t);
            if (first.isEmpty() || t.date.compareTo(first) < 0) first = t.date;
            if (last.isEmpty() || t.date.compareTo(last) > 0) last = t.date;
            String currency = value(t, "Local currency");
            if (!currency.isEmpty()) currencies.put(currency, Math.addExact(currencies.getOrDefault(currency, 0), 1));
            if (isPotMovement(t)) {
                pots = Math.addExact(pots, 1);
                if (t.amount > 0) potIn = Math.addExact(potIn, t.amount);
                if (t.amount < 0) potOut = Math.addExact(potOut, Math.negateExact(t.amount));
                continue;
            }
            external = Math.addExact(external, 1); String c = category(t, overrides);
            if (t.amount > 0) { in = Math.addExact(in, t.amount); add(credits, c, t.amount); }
            if (t.amount < 0) { long amount = Math.negateExact(t.amount); out = Math.addExact(out, amount); add(debits, c, amount); }
        }
        return new Overview(transactions.size(), external, pots, in, out, potIn, potOut, first, last,
                debits, credits, currencies, merchantSummaries(transactions));
    }

    /**
     * Offset and limit apply only to the returned page, never to total history or calculations.
     * The caller must keep the same history/correction snapshot and query across pages.
     * For a live collection updated by sync, prefer browse(...).page(...) instead.
     */
    public static ActivityPage activity(List<Finance.Transaction> transactions, String query, int offset,
                                        int limit, Map<String, String> overrides) {
        Objects.requireNonNull(transactions, "transactions"); Objects.requireNonNull(overrides, "overrides");
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid activity page");
        String q = normalize(query == null ? "" : query);
        if (q.length() > 500) throw new IllegalArgumentException("Search is too long");
        List<Finance.Transaction> matching = new ArrayList<>();
        for (Finance.Transaction t : transactions) {
            validate(t);
            if (q.isEmpty() || searchable(t, overrides).contains(q)) matching.add(t);
        }
        matching = ordered(matching); List<ActivityItem> page = new ArrayList<>();
        int end = (int) Math.min((long) matching.size(), (long) offset + limit);
        for (int i = Math.min(offset, matching.size()); i < end; i++) page.add(new ActivityItem(matching.get(i), overrides));
        return new ActivityPage(matching.size(), offset, page);
    }

    /** Conservative name grouping only; unrelated unnamed payments are never combined by type. */
    public static List<Merchant> merchantSummaries(List<Finance.Transaction> transactions) {
        Map<String, List<Finance.Transaction>> groups = new TreeMap<>();
        for (Finance.Transaction t : transactions) {
            validate(t); if (isPotMovement(t)) continue;
            String key = merchantKey(t); groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        List<Merchant> result = new ArrayList<>();
        for (Map.Entry<String, List<Finance.Transaction>> entry : groups.entrySet()) result.add(new Merchant(entry.getKey(), entry.getValue()));
        result.sort(Comparator.comparingLong((Merchant m) -> m.moneyOut).reversed().thenComparing(m -> m.key));
        return immutable(result);
    }

    public static List<RecurringCandidate> recurringCandidates(List<Finance.Transaction> transactions, LocalDate today) {
        Objects.requireNonNull(today, "today"); Map<String, List<Finance.Transaction>> groups = new TreeMap<>();
        for (Finance.Transaction t : transactions) {
            validate(t);
            if (t.amount >= 0 || isPotMovement(t) || normalize(t.name).isEmpty()
                    || LocalDate.parse(t.date).isAfter(today)) continue;
            groups.computeIfAbsent(merchantKey(t), k -> new ArrayList<>()).add(t);
        }
        List<RecurringCandidate> result = new ArrayList<>();
        for (Map.Entry<String, List<Finance.Transaction>> entry : groups.entrySet()) {
            List<Finance.Transaction> list = entry.getValue();
            list.sort(Comparator.comparing((Finance.Transaction t) -> t.date).thenComparing(t -> t.id));
            // Use at most the latest six rows, while rejecting same-day multiplicity in that evidence.
            List<Finance.Transaction> evidence = new ArrayList<>(list.subList(Math.max(0, list.size() - 6), list.size()));
            if (evidence.size() < 3 || hasRepeatedDates(evidence) || !similarAmounts(evidence)) continue;
            String cadence = cadence(evidence); if (cadence.isEmpty()) continue;
            LocalDate last = LocalDate.parse(evidence.get(evidence.size() - 1).date);
            int maxAge = cadence.equals("weekly") ? 14 : cadence.equals("fortnightly") ? 28 : 62;
            if (ChronoUnit.DAYS.between(last, today) > maxAge) continue;
            LocalDate next = cadence.equals("monthly") ? last.plusMonths(1)
                    : last.plusDays(cadence.equals("weekly") ? 7 : 14);
            result.add(new RecurringCandidate(entry.getKey(), evidence, cadence, next, today));
        }
        result.sort(Comparator.comparing((RecurringCandidate c) -> c.nextEstimatedDate).thenComparing(c -> c.merchantKey));
        return immutable(result);
    }

    public static boolean isPotMovement(Finance.Transaction t) { return "pot transfer".equals(normalize(t.type)); }

    public static String fallbackEmoji(String category) {
        switch (normalize(category)) {
            case "groceries": return "🍏";
            case "eating out": return "🍽";
            case "transport": return "🚆";
            case "shopping": return "🛍";
            case "bills": return "🧾";
            case "savings": return "🏦";
            case "income": return "↙";
            case "entertainment": return "🎟";
            case "holidays": return "🌍";
            default: return "•";
        }
    }

    private static boolean hasRepeatedDates(List<Finance.Transaction> evidence) {
        for (int i = 1; i < evidence.size(); i++) if (evidence.get(i).date.equals(evidence.get(i - 1).date)) return true;
        return false;
    }
    private static boolean similarAmounts(List<Finance.Transaction> evidence) {
        long min = Long.MAX_VALUE, max = 0;
        for (Finance.Transaction t : evidence) { long a = Math.negateExact(t.amount); min = Math.min(min, a); max = Math.max(max, a); }
        // Difference / minimum <= 10%, without overflow or rounding assumptions.
        return java.math.BigInteger.valueOf(max).subtract(java.math.BigInteger.valueOf(min))
                .multiply(java.math.BigInteger.TEN).compareTo(java.math.BigInteger.valueOf(min)) <= 0;
    }
    private static String cadence(List<Finance.Transaction> evidence) {
        boolean weekly = true, fortnightly = true, monthly = true;
        for (int i = 1; i < evidence.size(); i++) {
            LocalDate before = LocalDate.parse(evidence.get(i - 1).date), after = LocalDate.parse(evidence.get(i).date);
            long gap = ChronoUnit.DAYS.between(before, after);
            weekly &= gap >= 5 && gap <= 9;
            fortnightly &= gap >= 12 && gap <= 16;
            monthly &= ChronoUnit.MONTHS.between(before.withDayOfMonth(1), after.withDayOfMonth(1)) == 1
                    && Math.abs(ChronoUnit.DAYS.between(before.plusMonths(1), after)) <= 3;
        }
        return weekly ? "weekly" : fortnightly ? "fortnightly" : monthly ? "monthly" : "";
    }
    private static String searchable(Finance.Transaction t, Map<String, String> overrides) {
        return normalize(String.join("\n", displayName(t), category(t, overrides), value(t, "Notes and #tags"),
                value(t, "Description"), value(t, "Pot name"), t.type, value(t, "Local currency")));
    }
    private static String merchantKey(Finance.Transaction t) {
        String name = normalize(t.name); return name.isEmpty() ? "unnamed:" + t.id : "named:" + name;
    }
    private static String displayName(Finance.Transaction t) {
        return normalize(t.name).isEmpty() ? (normalize(t.type).isEmpty() ? "Transaction" : t.type.trim()) : t.name.trim();
    }
    private static String category(Finance.Transaction t, Map<String, String> overrides) {
        String c = overrides.get(t.id); return c == null || c.trim().isEmpty() ? t.category : c.trim();
    }
    private static String value(Finance.Transaction t, String key) {
        String v = t.raw.get(key); return v == null ? "" : v.trim();
    }
    private static String normalize(String text) { return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static void validate(Finance.Transaction t) { Objects.requireNonNull(t, "transaction"); if (!"GBP".equals(t.currency)) throw new IllegalArgumentException("Unsupported account currency"); }
    private static List<Finance.Transaction> ordered(List<Finance.Transaction> transactions) {
        List<Finance.Transaction> result = new ArrayList<>(transactions);
        result.sort(Comparator.comparing((Finance.Transaction t) -> t.date)
                .thenComparing(CompanionInsights::sortTime).thenComparing(t -> t.id).reversed());
        return result;
    }
    private static String sortTime(Finance.Transaction t) {
        try { return LocalTime.parse(value(t, "Time")).toString(); }
        catch (RuntimeException invalid) { return ""; }
    }
    private static void add(Map<String, Long> map, String key, long amount) { map.put(key, Math.addExact(map.getOrDefault(key, 0L), amount)); }
    private static <T> List<T> immutable(List<T> list) { return Collections.unmodifiableList(new ArrayList<>(list)); }
    private static <K, V> Map<K, V> immutableMap(Map<K, V> map) { return Collections.unmodifiableMap(new LinkedHashMap<>(map)); }
}
