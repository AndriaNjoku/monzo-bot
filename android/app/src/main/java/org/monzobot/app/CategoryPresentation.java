package org.monzobot.app;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Local presentation defaults, not a claim about a bank's official branding or classifications. */
public final class CategoryPresentation {
    private CategoryPresentation() {}

    private static final Map<String, String> DEFAULTS;
    private static final String[] CUSTOM = {"🏷️", "🎯", "🧩", "🌱", "✨", "📌"};
    static {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("general", "💷"); values.put("uncategorised", "🏷️"); values.put("uncategorized", "🏷️");
        values.put("bills", "🧾"); values.put("eating out", "🍽️"); values.put("entertainment", "🎟️");
        values.put("expenses", "🧳"); values.put("family", "👪"); values.put("finances", "💰");
        values.put("gifts", "🎁"); values.put("groceries", "🛒"); values.put("holidays", "🏖️");
        values.put("income", "💷"); values.put("personal care", "🧴"); values.put("savings", "🐖");
        values.put("shopping", "🛍️"); values.put("subscriptions", "🔄"); values.put("transport", "🚆");
        DEFAULTS = Collections.unmodifiableMap(values);
    }

    public static final class Presentation {
        public final String category, emoji, emojiSource, accessibleLabel, movementKind;
        private Presentation(String category, String emoji, String emojiSource, String movementKind) {
            this.category = category; this.emoji = emoji; this.emojiSource = emojiSource;
            this.movementKind = movementKind;
            this.accessibleLabel = category + "; " + movementLabel(movementKind);
        }
    }

    /** Source row Emoji wins for a transaction; category customization applies when no source Emoji exists. */
    public static Presentation transaction(Finance.Transaction transaction,
                                            Map<String, String> categoryOverrides,
                                            Map<String, String> emojiOverrides) {
        Objects.requireNonNull(transaction, "transaction");
        String category = categoryOverrides == null ? null : categoryOverrides.get(transaction.id);
        if (category == null || category.trim().isEmpty()) category = transaction.category;
        else category = category.trim();
        category = categoryLabel(category);
        String source = transaction.raw.get("Emoji");
        boolean hasSource = source != null && !source.trim().isEmpty();
        String chosen = hasSource ? source : categoryEmoji(category, emojiOverrides);
        return new Presentation(category, chosen, hasSource ? "source_transaction"
                : hasOverride(category, emojiOverrides) ? "user_category" : "local_default", movementKind(transaction));
    }

    /** User category overrides are normalized for lookup; built-ins are UK-export category defaults. */
    public static String categoryEmoji(String category, Map<String, String> emojiOverrides) {
        String override = override(category, emojiOverrides);
        if (override != null) return validateEmoji(override);
        String normalized = normalize(category);
        String standard = DEFAULTS.get(normalized);
        return standard == null ? CUSTOM[Math.floorMod(normalized.hashCode(), CUSTOM.length)] : standard;
    }

    public static String categoryLabel(String category) {
        return category == null || category.trim().isEmpty() ? "Uncategorised" : category.trim();
    }

    /** Movement direction is independent of category and emoji; credits are deliberately unclassified. */
    public static String movementKind(Finance.Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if ("pot transfer".equals(normalize(transaction.type))) return "pot_movement";
        return transaction.amount < 0 ? "money_out" : transaction.amount > 0 ? "money_in_unclassified" : "zero_movement";
    }

    public static String movementLabel(String kind) {
        switch (kind) {
            case "pot_movement": return "pot movement, not external spending";
            case "money_out": return "money out";
            case "money_in_unclassified": return "money in, not automatically income or refund";
            case "zero_movement": return "no money movement";
            default: throw new IllegalArgumentException("Unknown movement kind");
        }
    }

    /** Reject text labels/control characters in user emoji settings without changing source evidence. */
    public static String validateEmoji(String value) {
        if (value == null || value.trim().isEmpty() || value.codePointCount(0, value.length()) > 24) {
            throw new IllegalArgumentException("Choose a short emoji or emoji sequence");
        }
        boolean symbol = false;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean allowed = type == Character.OTHER_SYMBOL || type == Character.MATH_SYMBOL
                    || type == Character.MODIFIER_SYMBOL || type == Character.CURRENCY_SYMBOL
                    || (cp >= 0x1F000 && cp <= 0x1FAFF)
                    || (cp >= 0xE0020 && cp <= 0xE007F)
                    || cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3
                    || (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
            if (!allowed) throw new IllegalArgumentException("Use an emoji, not a text label");
            symbol |= type == Character.OTHER_SYMBOL || type == Character.MODIFIER_SYMBOL
                    || (cp >= 0x1F000 && cp <= 0x1FAFF) || cp == 0x20E3;
        }
        if (!symbol) throw new IllegalArgumentException("Choose an emoji or emoji sequence");
        return value;
    }

    private static boolean hasOverride(String category, Map<String, String> overrides) { return override(category, overrides) != null; }
    private static String override(String category, Map<String, String> overrides) {
        if (overrides == null) return null;
        String normalized = normalize(category), found = null;
        for (Map.Entry<String, String> entry : overrides.entrySet()) {
            if (normalize(entry.getKey()).equals(normalized)) {
                if (found != null && !Objects.equals(found, entry.getValue())) throw new IllegalArgumentException("Conflicting category emoji settings");
                found = entry.getValue();
            }
        }
        return found;
    }
    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
