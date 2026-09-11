package com.statsboard.stat;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Formats a raw stat value the way the vanilla Statistics screen does.
 *
 * <p>This exists because {@code Stat#format} routes through
 * {@code StatFormatter}'s process-wide {@code DecimalFormat} and
 * {@code NumberFormat} singletons, and neither is thread-safe. Vanilla only
 * ever formats on the client render thread; we also format on the server thread
 * for the plain-text /leaderboard output, so sharing those instances would be a
 * live data race. Every formatter here is held in a ThreadLocal instead.
 *
 * <p>Which formatter applies is decided from the stat id rather than by reading
 * the private formatter field off a Stat. That mapping is a fixed table in
 * Stats' static initializer, and the three rules below were checked against it
 * exhaustively: 15 distance stats (all {@code *_one_cm}), 5 time stats, 7
 * damage stats, and no id matching a rule that should not.
 *
 * <p>Output matches vanilla byte for byte, including the leading space before
 * each unit and the raw-double seconds fallback.
 */
public final class StatValueFormatter {
    /** Exactly the custom stats vanilla formats with StatFormatter.TIME. */
    private static final Set<String> TIME_STATS = Set.of(
            "play_time", "total_world_time", "time_since_death", "time_since_rest", "sneak_time");

    private static final ThreadLocal<DecimalFormat> DECIMAL = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("########0.00");
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format;
    });

    private static final ThreadLocal<NumberFormat> INTEGER =
            ThreadLocal.withInitial(() -> NumberFormat.getIntegerInstance(Locale.US));

    private StatValueFormatter() {
    }

    public static String format(StatKey key, int value) {
        if (!"minecraft".equals(key.typeId().getNamespace()) || !"custom".equals(key.typeId().getPath())) {
            return count(value);
        }

        String path = key.valueId().getPath();
        if (path.endsWith("_one_cm")) {
            return distance(value);
        }
        if (TIME_STATS.contains(path)) {
            return time(value);
        }
        if (path.startsWith("damage_")) {
            return divideByTen(value);
        }
        return count(value);
    }

    /** StatFormatter.DEFAULT. */
    private static String count(int value) {
        return INTEGER.get().format(value);
    }

    /** StatFormatter.DISTANCE - input is centimetres. */
    private static String distance(int centimetres) {
        double metres = centimetres / 100.0;
        double kilometres = metres / 1000.0;
        if (kilometres > 0.5) {
            return DECIMAL.get().format(kilometres) + " km";
        }
        if (metres > 0.5) {
            return DECIMAL.get().format(metres) + " m";
        }
        return centimetres + " cm";
    }

    /**
     * StatFormatter.TIME - input is ticks. Emits the single largest unit that
     * exceeds half of itself, not a composite. The sub-minute branch is a raw
     * double rather than a formatted one, matching vanilla exactly.
     */
    private static String time(int ticks) {
        double seconds = ticks / 20.0;
        double minutes = seconds / 60.0;
        double hours = minutes / 60.0;
        double days = hours / 24.0;
        double years = days / 365.0;

        if (years > 0.5) {
            return DECIMAL.get().format(years) + " y";
        }
        if (days > 0.5) {
            return DECIMAL.get().format(days) + " d";
        }
        if (hours > 0.5) {
            return DECIMAL.get().format(hours) + " h";
        }
        if (minutes > 0.5) {
            return DECIMAL.get().format(minutes) + " m";
        }
        return seconds + " s";
    }

    /** StatFormatter.DIVIDE_BY_TEN. */
    private static String divideByTen(int value) {
        return DECIMAL.get().format(value * 0.1);
    }
}
