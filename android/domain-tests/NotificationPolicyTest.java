import org.monzobot.app.Finance;
import org.monzobot.app.NotificationPolicy;
import java.util.List;
import java.util.Map;

public final class NotificationPolicyTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Notification policy check failed: " + checks);
    }
    private static Finance.Alert alert(long spent, long limit, String category) {
        Finance.Transaction t = new Finance.Transaction("synthetic", "2026-11-04", "Synthetic merchant", category,
                "", "GBP", -spent, Map.of());
        return Finance.alerts(Finance.summarize(List.of(t), "2026-11-01", "2026-11-30", Map.of()),
                Map.of(category, limit), 80).get(0);
    }
    public static void main(String[] args) {
        check(NotificationPolicy.quiet(22, 22, 8));
        check(NotificationPolicy.quiet(0, 22, 8));
        check(NotificationPolicy.quiet(7, 22, 8));
        check(!NotificationPolicy.quiet(8, 22, 8));
        check(!NotificationPolicy.quiet(21, 22, 8));
        check(NotificationPolicy.quiet(9, 9, 17));
        check(NotificationPolicy.quiet(16, 9, 17));
        check(!NotificationPolicy.quiet(17, 9, 17));
        for (int hour = 0; hour < 24; hour++) check(!NotificationPolicy.quiet(hour, 0, 0));
        try { NotificationPolicy.quiet(24, 22, 8); throw new AssertionError("Invalid hour accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        long now = 1_800_000_000_000L;
        check(NotificationPolicy.due(now, 0, 0, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, now - NotificationPolicy.COOLDOWN_MILLIS + 1, 1, true, true, 12, 22, 8));
        check(NotificationPolicy.due(now, now - NotificationPolicy.COOLDOWN_MILLIS, 1, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, now + 1, 0, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, 3, true, true, 12, 22, 8));
        check(NotificationPolicy.due(now, 0, 2, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, 0, false, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, 0, true, false, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, 0, true, true, 22, 22, 8));
        check(!NotificationPolicy.due(now, 0, 0, true, true, 7, 22, 8));
        check(NotificationPolicy.due(now, 0, 0, true, true, 8, 22, 8));
        check(!NotificationPolicy.due(now, -1, 0, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(-1, 0, 0, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, -1, true, true, 12, 22, 8));
        check(!NotificationPolicy.due(now, 0, 0, true, true, 24, 22, 8));
        String first = NotificationPolicy.key("2026-11-01", "2026-11-30", alert(8500, 10000, "Private category"));
        check(first.matches("[0-9a-f]{64}"));
        check(!first.contains("Private category"));
        check(first.equals(NotificationPolicy.key("01/11/2026", "30/11/2026", alert(9000, 10000, "Private category"))));
        check(!first.equals(NotificationPolicy.key("2026-11-01", "2026-11-30", alert(10001, 10000, "Private category"))));
        check(!first.equals(NotificationPolicy.key("2026-11-01", "2026-11-30", alert(8500, 10001, "Private category"))));
        check(!first.equals(NotificationPolicy.key("2026-11-01", "2026-11-30", alert(8500, 10000, "Other category"))));
        check(!first.equals(NotificationPolicy.key("2026-10-01", "2026-11-30", alert(8500, 10000, "Private category"))));
        for (String freshness : new String[]{"recent", "stale", "unknown", "Private category", null}) {
            String body = NotificationPolicy.genericBody(freshness);
            check(!body.matches(".*[£$0-9].*"));
            check(!body.contains("Private category"));
            check(!body.contains("Synthetic merchant"));
            check(freshness != null && freshness.equals("recent") ? body.contains("budget alert") : body.contains("freshness check"));
        }
        System.out.println("Notification policy: " + checks + " checks passed");
    }
}
