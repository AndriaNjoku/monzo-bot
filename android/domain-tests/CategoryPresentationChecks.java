import org.monzobot.app.CategoryPresentation;
import org.monzobot.app.Finance;
import java.util.Map;

public final class CategoryPresentationChecks {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Category presentation check " + checks); }
    private static void fails(Runnable action) { checks++; try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected invalid emoji"); }
    private static Finance.Transaction transaction(long amount, String type, Map<String, String> raw) {
        return new Finance.Transaction("synthetic-id", "2026-11-01", "Synthetic merchant", "Groceries", type, "GBP", amount, raw);
    }
    public static void main(String[] args) {
        for (String category : new String[]{"General", "Bills", "Eating out", "Entertainment", "Expenses", "Family", "Finances", "Gifts", "Groceries", "Holidays", "Income", "Personal care", "Savings", "Shopping", "Subscriptions", "Transport"}) {
            String emoji = CategoryPresentation.categoryEmoji(category, Map.of());
            check(emoji.codePointCount(0, emoji.length()) >= 1);
            check(emoji.equals(CategoryPresentation.validateEmoji(emoji)));
        }
        check(CategoryPresentation.categoryEmoji(" groceries ", Map.of()).equals("🛒"));
        check(CategoryPresentation.categoryEmoji("Custom synthetic goal", Map.of()).equals(CategoryPresentation.categoryEmoji(" custom   SYNTHETIC goal ", Map.of())));
        check(CategoryPresentation.categoryEmoji("Groceries", Map.of(" groceries ", "🥦")).equals("🥦"));
        CategoryPresentation.Presentation source = CategoryPresentation.transaction(transaction(-1500, "Card payment", Map.of("Emoji", " 🥑️ ")), Map.of(), Map.of("Groceries", "🥦"));
        check(source.emoji.equals(" 🥑️ "));
        check(source.emojiSource.equals("source_transaction"));
        check(source.movementKind.equals("money_out"));
        check(source.accessibleLabel.equals("Groceries; money out"));
        check(!source.accessibleLabel.contains("🥑"));
        CategoryPresentation.Presentation custom = CategoryPresentation.transaction(transaction(-1500, "Card payment", Map.of("Emoji", "")), Map.of("synthetic-id", "Custom goal"), Map.of("Custom goal", "🎯"));
        check(custom.emoji.equals("🎯")); check(custom.category.equals("Custom goal")); check(custom.emojiSource.equals("user_category"));
        check(CategoryPresentation.transaction(transaction(1500, "Card payment", Map.of()), Map.of(), Map.of()).movementKind.equals("money_in_unclassified"));
        check(CategoryPresentation.transaction(transaction(0, "Card payment", Map.of()), Map.of(), Map.of()).movementKind.equals("zero_movement"));
        check(CategoryPresentation.transaction(transaction(-1500, " Pot   transfer ", Map.of()), Map.of(), Map.of()).movementKind.equals("pot_movement"));
        check(CategoryPresentation.movementLabel("money_in_unclassified").contains("not automatically income or refund"));
        check(CategoryPresentation.categoryLabel(null).equals("Uncategorised"));
        check(CategoryPresentation.categoryLabel("  Synthetic goal  ").equals("Synthetic goal"));
        for (String emoji : new String[]{"👩🏽‍💻", "👨‍👩‍👧‍👦", "🇬🇧", "1️⃣", "❤️", "🏳️‍🌈", "\uD83E\uDEE9", "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"}) check(CategoryPresentation.validateEmoji(emoji).equals(emoji));
        for (String invalid : new String[]{"", "text", "1", "$", "<script>", "🎯\n", "🎯".repeat(25)}) fails(() -> CategoryPresentation.validateEmoji(invalid));
        fails(() -> CategoryPresentation.categoryEmoji("Groceries", Map.of("Groceries", "🥦", " groceries ", "🛒")));
        System.out.println("Category presentation: " + checks + " checks passed");
    }
}
