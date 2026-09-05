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
        for (int i = 0; i < 5; i++) {
            String group = m.group(i + 1);
            if (group != null) {
                total += Long.parseLong(group) * multipliers[i];
                any = true;
            }
        }
        return any ? total : null;
    }

    /** Compact "X ago" display, e.g. "3d ago", using the player's language. */
    public static String ago(Translator t, ServerPlayer player, long secondsAgo) {
        long v;
        String unit;
        if (secondsAgo >= 604800) {
            v = secondsAgo / 604800;
            unit = "w";
        } else if (secondsAgo >= 86400) {
            v = secondsAgo / 86400;
            unit = "d";
        } else if (secondsAgo >= 3600) {
            v = secondsAgo / 3600;
            unit = "h";
        } else if (secondsAgo >= 60) {
            v = secondsAgo / 60;
            unit = "m";
        } else {
            v = Math.max(secondsAgo, 0);
            unit = "s";
        }
        return t.get(player, "coreprotect.time.ago", v + t.get(player, "coreprotect.time." + unit));
    }

    /** Multi-unit duration display, e.g. "2w 5d 10h 30m 15s". */
    public static String formatDuration(Translator t, ServerPlayer player, long seconds) {
        long w = seconds / 604800;
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
}
