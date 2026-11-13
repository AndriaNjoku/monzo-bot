import org.monzobot.app.Finance;
import org.monzobot.app.Finance.Transaction;
import org.monzobot.app.Finance.Report;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public final class FinanceTest {
    private static int checks;
    private static void equal(Object actual, Object expected) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    private static void fails(Runnable runnable) {
        checks++;
        try { runnable.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected validation failure");
    }
    public static void main(String[] args) throws Exception {
        String fixture = new String(Files.readAllBytes(Path.of(args.length > 0 ? args[0] : "fixtures/synthetic.csv")), java.nio.charset.StandardCharsets.UTF_8);
        List<Transaction> tx = Finance.importCsv(fixture, List.of());
        equal(tx.size(), 7);
        equal(Finance.importCsv(fixture.replace("-54.28", "-£54.28"), List.of()).get(1).amount, -5428L);
        equal(Finance.importCsv(fixture.replace("-54.28", "\"-£1,234.56\""), List.of()).get(1).amount, -123456L);
        for (String display : List.of("-£12,34.56", "-1.234,56", "-$54.28", "(54.28)", "£-54.28", "-54.280")) {
            fails(() -> Finance.importCsv(fixture.replace("-54.28", "\"" + display + "\""), List.of()));
        }
        equal(Finance.minor("-0.01"), -1L);
        equal(Finance.minor("54.28"), 5428L);
        equal(Finance.minor("92233720368547758.07"), Long.MAX_VALUE);
        equal(Finance.minor("-92233720368547758.08"), Long.MIN_VALUE);
        for (String s : List.of("1.234", "1e3", "NaN", "+1", "1,000", "92233720368547758.08")) fails(() -> Finance.minor(s));
        equal(Finance.money(5428), "£54.28");
        equal(Finance.isoDate("29/02/2024"), "2024-02-29");
        equal(Finance.isoDate("2026-11-02"), "2026-11-02");
        for (String s : List.of("31/02/2026", "2026-02-29", "2026-13-01", "11/2/2026", "yesterday")) fails(() -> Finance.isoDate(s));
        equal(Finance.csv("\uFEFFa,b\r\n\"x,y\",\"a\"\"b\nc\""), List.of(List.of("a", "b"), List.of("x,y", "a\"b\nc")));
        for (String s : List.of("\"unclosed", "a\"x,b", "\"x\"junk,b", "\"x\"\"")) fails(() -> Finance.csv(s));
        equal(Finance.importCsv(fixture, tx).size(), 7);
        equal(tx.stream().filter(t -> t.name.equals("Demo Grocer")).count(), 4L);
        fails(() -> Finance.importCsv(fixture.replace("-54.28", "-55.28"), tx));
        equal(tx.get(1).amount, -5428L);
        fails(() -> Finance.importCsv(fixture.replace(",GBP,", ",EUR,"), List.of()));
        fails(() -> Finance.importCsv("Date,Amount\n2026-11-01,10", List.of()));
        fails(() -> Finance.importCsv(fixture + "bad,row\n", List.of()));
        fails(() -> Finance.importCsv(fixture.replace("Transaction ID,Date", "Date,Date"), List.of()));
        fails(() -> Finance.importCsv(fixture.replace("syn-002", ""), List.of()));
        List<List<String>> rows = new ArrayList<>(Finance.csv(fixture));
        rows.set(1, new ArrayList<>(rows.get(1).subList(0, 9)));
        equal(Finance.fromRows(rows, List.of()).size(), 7);
        Report report = Finance.summarize(tx, "2026-11-01", "2026-11-30", Map.of());
        equal(report.categories.get("Groceries"), 17339L);
        equal(report.inflows, 240425L);
        equal(report.outflows, 22854L);
        equal(report.net, 217571L);
        equal(report.count, 7);
        equal(report.daily.get("2026-11-08"), -425L);
        equal(Finance.summarize(tx, "2026-11-02", "2026-11-02", Map.of()).count, 1);
        fails(() -> Finance.summarize(tx, "2026-12-01", "2026-11-01", Map.of()));
        equal(Finance.summarize(tx, "2026-11-01", "2026-11-30", Map.of("syn-002", "__proto__")).categories.get("__proto__"), 5428L);
        equal(tx.get(1).raw.get("Category"), "Groceries");
        equal(Finance.alerts(report, Map.of("Groceries", 30000L), 80).size(), 0);
        equal(Finance.alerts(report, Map.of("Groceries", 20000L), 80).get(0).level, "threshold");
        equal(Finance.alerts(report, Map.of("Groceries", 17339L), 100).get(0).remaining, 0L);
        equal(Finance.alerts(report, Map.of("Groceries", 17338L), 100).get(0).level, "over");
        fails(() -> Finance.alerts(report, Map.of("Groceries", 0L), 80));
        fails(() -> Finance.alerts(report, Map.of("Groceries", 100L), 0));
        equal(Finance.alerts(report, Map.of("Groceries", Long.MAX_VALUE), 99).size(), 0);
        Transaction huge = new Transaction("synthetic-huge", "2026-11-01", "Synthetic", "Test", "", "GBP", Long.MAX_VALUE, Map.of());
        fails(() -> Finance.summarize(List.of(huge, huge), "2026-11-01", "2026-11-30", Map.of()));
        Transaction min = new Transaction("synthetic-min", "2026-11-01", "Synthetic", "Test", "", "GBP", Long.MIN_VALUE, Map.of());
        fails(() -> Finance.summarize(List.of(min), "2026-11-01", "2026-11-30", Map.of()));
        try { tx.get(1).raw.put("Name", "changed"); throw new AssertionError("Mutable raw evidence"); } catch (UnsupportedOperationException expected) { checks++; }
        try { report.categories.put("other", 10L); throw new AssertionError("Mutable report"); } catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("Finance domain: " + checks + " checks passed");
    }
}
