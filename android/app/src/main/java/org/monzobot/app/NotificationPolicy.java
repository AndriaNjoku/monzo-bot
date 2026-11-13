package org.monzobot.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Deterministic notification eligibility. Scheduling, OS permissions and persistence stay outside. */
public final class NotificationPolicy {
    private NotificationPolicy() {}

    public static final long COOLDOWN_MILLIS = 6L * 60 * 60 * 1000;
    public static final int DAILY_CAP = 3;

    /** Start inclusive, end exclusive. Equal times disable quiet hours; overnight windows wrap. */
    public static boolean quiet(int currentHour, int startHour, int endHour) {
        if (!validHour(currentHour) || !validHour(startHour) || !validHour(endHour)) {
            throw new IllegalArgumentException("Hours must be between 0 and 23");
        }
        if (startHour == endHour) return false;
        if (startHour < endHour) return currentHour >= startHour && currentHour < endHour;
        return currentHour >= startHour || currentHour < endHour;
    }

    /**
     * Stable opaque event key: period + category + budget limit + severity, excluding current spend.
     * Callers should use the opaque key as notification tag and keep descriptions out of OS IDs.
     */
    public static String key(String periodStart, String periodEnd, Finance.Alert alert) {
        Objects.requireNonNull(alert, "alert");
        String start = Finance.isoDate(periodStart), end = Finance.isoDate(periodEnd);
        if (start.compareTo(end) > 0) throw new IllegalArgumentException("Invalid notification period");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            part(digest, "budget-event-v1");
            part(digest, start);
            part(digest, end);
            part(digest, alert.category);
            part(digest, Long.toString(alert.limit));
            part(digest, alert.level);
            byte[] hash = digest.digest();
            char[] hex = new char[hash.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < hash.length; i++) {
                int b = hash[i] & 0xff;
                hex[i * 2] = digits[b >>> 4];
                hex[i * 2 + 1] = digits[b & 0xf];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Evaluate a global six-hour cooldown and daily cap. Last-sent=0 means never sent.
     * Clock rollback and invalid ledger/settings values suppress delivery safely.
     * Caller owns resetting dailyCount in its user-local calendar day and persisting successful sends.
     */
    public static boolean due(long now, long lastSent, int dailyCount, boolean enabled,
                              boolean permission, int hour, int quietStart, int quietEnd) {
        if (!enabled || !permission || now < 0 || lastSent < 0 || lastSent > now
                || dailyCount < 0 || dailyCount >= DAILY_CAP || !validHour(hour)
                || !validHour(quietStart) || !validHour(quietEnd)) return false;
        if (quiet(hour, quietStart, quietEnd)) return false;
        return lastSent == 0 || now - lastSent >= COOLDOWN_MILLIS;
    }

    /** No raw transaction data, amounts, account values, categories or merchant names on lock screen. */
    public static String genericBody(String freshness) {
        return "recent".equals(freshness)
                ? "Open your private dashboard to review a budget alert."
                : "Your export needs a freshness check. Open your private dashboard.";
    }

    private static boolean validHour(int hour) { return hour >= 0 && hour <= 23; }

    private static void part(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "key field").getBytes(StandardCharsets.UTF_8);
        // Length-prefix each UTF-8 field so category separators cannot collide with adjacent fields.
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
