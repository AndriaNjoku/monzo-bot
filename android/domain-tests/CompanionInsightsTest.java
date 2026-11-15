import org.monzobot.app.CompanionInsights;
import org.monzobot.app.Finance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Synthetic-only portable checks. No actual account identifiers, transactions or network calls. */
public final class CompanionInsightsTest {
    private static int checks;
    private static void eq(Object actual, Object expected) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void fails(Runnable action) {
        checks++; try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { return; }
        throw new AssertionError("Expected validation/overflow failure");
    }
    private static Finance.Transaction tx(String id, String date, String name, String category, String type,
                                          long amount, Map<String, String> raw) {
        return new Finance.Transaction(id, date, name, category, type, "GBP", amount, raw);
    }
    private static Finance.Transaction tx(String id, String date, String name, String type, long amount) {
        return tx(id, date, name, "General", type, amount, Map.of());
    }
    public static void main(String[] args) {
        var merchant = tx("synthetic-1", "2025-03-01", "Example Cafe", "Eating out", "Card payment", -1250,
                Map.of("Emoji", "🍝🦘", "Time", "10:05:30", "Local amount", "-15.00", "Local currency", "EUR",
                        "Notes and #tags", "#demo trip", "Description", "Synthetic meal", "Pot name", ""));
        var transfer = tx("synthetic-2", "2025-03-02", "Example payee", "Transfers", "Faster payment", -2200, Map.of());
        var potOut = tx("synthetic-3", "2025-03-03", "", "Savings", "  POT TRANSFER  ", -90000, Map.of("Pot name", "Demo envelope"));
        var potIn = tx("synthetic-4", "2025-03-04", "", "Transfers", "Pot transfer", 40000, Map.of());
        var credit = tx("synthetic-5", "2025-03-05", "Example Cafe", "Eating out", "Card payment", 250, Map.of());
        List<Finance.Transaction> sample = List.of(merchant, transfer, potOut, potIn, credit);
        var overview = CompanionInsights.overview(sample, Map.of("synthetic-1", "Meals"));
        eq(overview.transactionCount, 5); eq(overview.externalTransactionCount, 3); eq(overview.potMovementCount, 2);
        eq(overview.moneyIn, 250L); eq(overview.moneyOut, 3450L); eq(overview.netMovement, -3200L);
        eq(overview.potMoneyOut, 90000L); eq(overview.potMoneyIn, 40000L);
        eq(overview.categoryDebits.get("Transfers"), 2200L); eq(overview.categoryDebits.get("Meals"), 1250L);
        eq(overview.categoryCredits.get("Eating out"), 250L); eq(overview.categoryNetOutflow.get("Eating out"), -250L);
        eq(overview.categoryDebits.containsKey("Savings"), false); eq(overview.originalCurrencyCounts.get("EUR"), 1);
        eq(overview.firstDate, "2025-03-01"); eq(overview.lastDate, "2025-03-05");
        eq(CompanionInsights.activity(sample, "#demo TRIP", 0, 20, Map.of()).total, 1);
        eq(CompanionInsights.activity(sample, "synthetic meal", 0, 20, Map.of()).total, 1);
        eq(CompanionInsights.activity(sample, "demo envelope", 0, 20, Map.of()).items.get(0).potMovement, true);
        eq(CompanionInsights.activity(sample, "EUR", 0, 20, Map.of()).total, 1);
        var rich = CompanionInsights.activity(sample, "Meals", 0, 20, Map.of("synthetic-1", "Meals")).items.get(0);
        eq(rich.emoji, "🍝🦘"); eq(rich.time, "10:05:30"); eq(rich.localCurrency, "EUR"); eq(rich.localAmount, "-15.00");
        eq(rich.notes, "#demo trip"); eq(rich.description, "Synthetic meal"); eq(rich.source, merchant);
        eq(merchant.category, "Eating out"); eq(CompanionInsights.activity(sample, "", 99, 20, Map.of()).items.size(), 0);
        fails(() -> CompanionInsights.activity(sample, "", -1, 20, Map.of()));
        fails(() -> CompanionInsights.activity(sample, "", 0, 201, Map.of()));
        fails(() -> CompanionInsights.activity(sample, "", 0, 0, Map.of()));
        fails(() -> CompanionInsights.activity(sample, "a".repeat(501), 0, 20, Map.of()));

        List<Finance.Transaction> large = new ArrayList<>(); LocalDate start = LocalDate.of(2021, 1, 1);
        for (int i = 0; i < 2405; i++) large.add(tx("demo-" + i, start.plusDays(i).toString(), "Example Merchant " + i, "Card payment", -100));
        eq(CompanionInsights.overview(large, Map.of()).moneyOut, 240500L);
        eq(CompanionInsights.activity(large, "", 0, 200, Map.of()).total, 2405);
        eq(CompanionInsights.activity(large, "", 0, 200, Map.of()).items.get(0).source.id, "demo-2404");
        int collected = 0;
        for (int offset = 0; offset < large.size(); offset += 200) collected += CompanionInsights.activity(large, "", offset, 200, Map.of()).items.size();
        eq(collected, 2405);
        eq(CompanionInsights.activity(large, "Example Merchant 0", 0, 200, Map.of()).items.get(0).source.id, "demo-0");
        eq(CompanionInsights.activity(large, "", 2400, 200, Map.of()).hasMore, false);
        var blankOne = tx("blank-1", "2025-01-01", "", "Faster payment", -100);
        var blankTwo = tx("blank-2", "2025-01-02", "", "Faster payment", -200);
        eq(CompanionInsights.merchantSummaries(List.of(blankOne, blankTwo)).size(), 2);
        eq(CompanionInsights.activity(List.of(blankOne), "", 0, 5, Map.of()).items.get(0).name, "Faster payment");
        eq(CompanionInsights.merchantSummaries(List.of(
                tx("n1", "2025-01-01", "Example   Cafe", "Card payment", -100),
                tx("n2", "2025-01-02", " example cafe ", "Card payment", -200))).get(0).moneyOut, 300L);
        eq(CompanionInsights.merchantSummaries(List.of(
                tx("n3", "2025-01-01", "Example-Cafe", "Card payment", -100),
                tx("n4", "2025-01-02", "Example Cafe", "Card payment", -200))).size(), 2);
        eq(CompanionInsights.merchantSummaries(sample).size(), 2);
        eq(CompanionInsights.merchantSummaries(sample).get(0).name, "Example payee");
        eq(CompanionInsights.fallbackEmoji("Groceries"), "🍏");
        eq(CompanionInsights.overview(List.of(), Map.of()).firstDate, "");
        fails(() -> CompanionInsights.overview(List.of(tx("min", "2025-01-01", "Demo", "Card payment", Long.MIN_VALUE)), Map.of()));
        fails(() -> CompanionInsights.overview(List.of(tx("max1", "2025-01-01", "Demo", "Credit", Long.MAX_VALUE),
                tx("max2", "2025-01-02", "Demo", "Credit", 1)), Map.of()));
        checks++; try { overview.categoryDebits.put("x", 1L); throw new AssertionError("Mutable overview"); } catch (UnsupportedOperationException expected) {}

        List<Finance.Transaction> monthly = List.of(tx("r1", "2025-01-31", "Example service", "Direct Debit", -990),
                tx("r2", "2025-02-28", "Example service", "Direct Debit", -990),
                tx("r3", "2025-03-31", "Example service", "Direct Debit", -990));
        var candidates = CompanionInsights.recurringCandidates(monthly, LocalDate.of(2025, 4, 2));
        eq(candidates.size(), 1); eq(candidates.get(0).cadence, "monthly"); eq(candidates.get(0).nextEstimatedDate, "2025-04-30");
        eq(candidates.get(0).estimated, true); eq(candidates.get(0).requiresConfirmation, true); eq(candidates.get(0).evidenceCount, 3);
        eq(candidates.get(0).estimateInPast, false);
        eq(CompanionInsights.recurringCandidates(monthly, LocalDate.of(2025, 6, 3)).size(), 0);
        eq(CompanionInsights.recurringCandidates(monthly.subList(0, 2), LocalDate.of(2025, 3, 1)).size(), 0);
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("w1", "2025-03-03", "Example weekly", "Card payment", -500),
                tx("w2", "2025-03-10", "Example weekly", "Card payment", -500),
                tx("w3", "2025-03-17", "Example weekly", "Card payment", -500)), LocalDate.of(2025, 3, 18)).get(0).cadence, "weekly");
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("f1", "2025-03-01", "Example fortnightly", "Direct Debit", -500),
                tx("f2", "2025-03-15", "Example fortnightly", "Direct Debit", -500),
                tx("f3", "2025-03-29", "Example fortnightly", "Direct Debit", -500)), LocalDate.of(2025, 3, 30)).get(0).cadence, "fortnightly");
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("i1", "2025-03-01", "Irregular example", "Card payment", -500),
                tx("i2", "2025-03-04", "Irregular example", "Card payment", -500),
                tx("i3", "2025-03-29", "Irregular example", "Card payment", -500)), LocalDate.of(2025, 3, 30)).size(), 0);
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("p1", "2025-01-01", "Demo pot", "Pot transfer", -500),
                tx("p2", "2025-02-01", "Demo pot", "Pot transfer", -500),
                tx("p3", "2025-03-01", "Demo pot", "Pot transfer", -500)), LocalDate.of(2025, 3, 2)).size(), 0);
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("v1", "2025-01-01", "Variable example", "Direct Debit", -500),
                tx("v2", "2025-02-01", "Variable example", "Direct Debit", -2000),
                tx("v3", "2025-03-01", "Variable example", "Direct Debit", -500)), LocalDate.of(2025, 3, 2)).size(), 0);
        eq(CompanionInsights.recurringCandidates(List.of(
                tx("d1", "2025-03-01", "Duplicate day", "Card payment", -500),
                tx("d2", "2025-03-01", "Duplicate day", "Card payment", -500),
                tx("d3", "2025-03-08", "Duplicate day", "Card payment", -500)), LocalDate.of(2025, 3, 9)).size(), 0);
        List<Finance.Transaction> liveHistory = new ArrayList<>(List.of(
                tx("session-1", "2025-04-01", "Snapshot first", "Card payment", -100),
                tx("session-2", "2025-04-02", "Snapshot second", "Card payment", -100),
                tx("session-3", "2025-04-03", "Snapshot third", "Card payment", -100)));
        Map<String, String> liveCorrections = new HashMap<>();
        liveCorrections.put("session-1", "Captured category");
        var session = CompanionInsights.browse(liveHistory, liveCorrections);
        var firstPage = session.page("", 0, 2);
        eq(firstPage.items.get(0).source.id, "session-3");
        eq(firstPage.items.get(1).source.id, "session-2");
        liveHistory.add(tx("session-4", "2025-04-04", "New sync row", "Card payment", -100));
        liveCorrections.put("session-1", "Changed category");
        var secondPage = session.page("", 2, 2);
        eq(secondPage.items.size(), 1);
        eq(secondPage.items.get(0).source.id, "session-1");
        eq(secondPage.items.get(0).category, "Captured category");
        eq(secondPage.total, 3);
        eq(secondPage.hasMore, false);
        eq(session.transactionCount, 3);
        eq(session.page("Captured category", 0, 20).total, 1);
        eq(session.page("Changed category", 0, 20).total, 0);
        eq(CompanionInsights.browse(liveHistory, liveCorrections).page("", 0, 2).items.get(0).source.id, "session-4");
        eq(CompanionInsights.browse(liveHistory, liveCorrections).page("Changed category", 0, 20).total, 1);
        liveHistory.clear(); liveCorrections.clear();
        eq(session.page("", 0, 20).total, 3);
        eq(session.page("Captured category", 0, 20).items.get(0).source.id, "session-1");
        System.out.println("Companion insights: " + checks + " checks passed; synthetic data only");
    }
}
