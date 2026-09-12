package net.coreprotect.fabric.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.coreprotect.fabric.i18n.Translator;
import net.minecraft.server.level.ServerPlayer;

public final class TimeUtil {
    private static final Pattern DURATION = Pattern.compile(
            "(?:(\\d+)w)?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?");

    private TimeUtil() {
    }

    public static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    /**
     * Parses a CoreProtect-style duration ({@code 2w5d10h30m15s}) or a plain number
     * of seconds. Returns {@code null} when the input is not a valid duration.
     */
    public static Long parseSeconds(String input) {
        if (input == null) return null;
        String s = input.trim().toLowerCase();
        if (s.isEmpty()) return null;
        if (s.matches("\\d+")) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher m = DURATION.matcher(s);
        if (!m.matches()) return null;
        long[] multipliers = {604800L, 86400L, 3600L, 60L, 1L};
        long total = 0;
        boolean any = false;
        try {
            for (int i = 0; i < 5; i++) {
                String group = m.group(i + 1);
                if (group != null) {
                    // Math.*Exact so an absurd value ("t:99999999999999999w") is rejected
                    // instead of overflowing into a bogus (possibly negative) duration
                    total = Math.addExact(total, Math.multiplyExact(Long.parseLong(group), multipliers[i]));
                    any = true;
                }
            }
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
        return any ? total : null;
    }

    /**
     * Plugin-style "X ago" display, e.g. "3.00 minutes ago" / "2.00 hours ago" /
     * "5.00 days ago", using the player's language.
     */
    public static String ago(Translator t, ServerPlayer player, long secondsAgo) {
        double minutes = secondsAgo / 60.0;
        double value;
        String unit;
        if (minutes < 60) {
            value = minutes;
            unit = t.get(player, "coreprotect.time.minutes");
        } else {
            double hours = minutes / 60;
            if (hours < 24) {
                value = hours;
                unit = t.get(player, "coreprotect.time.hours");
            } else {
                value = hours / 24;
                unit = t.get(player, "coreprotect.time.days");
            }
        }
        return t.get(player, "coreprotect.time.ago",
                new java.text.DecimalFormat("0.00").format(value) + unit);
    }

    /** Multi-unit duration display, e.g. "2w 5d 10h 30m 15s". */
    public static String formatDuration(Translator t, ServerPlayer player, long seconds) {        long w = seconds / 604800;
        long d = (seconds % 604800) / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        appendUnit(sb, w, t.get(player, "coreprotect.time.w"));
        appendUnit(sb, d, t.get(player, "coreprotect.time.d"));
        appendUnit(sb, h, t.get(player, "coreprotect.time.h"));
        appendUnit(sb, m, t.get(player, "coreprotect.time.m"));
        appendUnit(sb, s, t.get(player, "coreprotect.time.s"));
        return sb.toString().trim().isEmpty() ? "0" + t.get(player, "coreprotect.time.s") : sb.toString().trim();
    }

    private static void appendUnit(StringBuilder sb, long value, String unit) {
        if (value > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(value).append(unit);
        }
    }

    /** Absolute "yyyy-MM-dd HH:mm:ss" display of an epoch-seconds timestamp (server time zone). */
    public static String clock(long epochSeconds) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date(epochSeconds * 1000L));
    }
}
