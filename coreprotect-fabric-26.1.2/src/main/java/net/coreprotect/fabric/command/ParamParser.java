package net.coreprotect.fabric.command;

import java.util.ArrayList;
import java.util.List;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.util.TimeUtil;

/**
 * Parses CoreProtect-style key:value parameters ({@code u:Steve t:2h a:block r:10 b:stone e:#fire p:2}).
 * A bare number is treated as the page.
 */
public final class ParamParser {

    /** Largest accepted r: radius (blocks). */
    static final int MAX_RADIUS = 1000;
    /** Largest accepted p: page number. */
    static final int MAX_PAGE = 1000;

    private ParamParser() {
    }

    /**
     * A value the parser had to adjust (a clamped radius or page). The command reports
     * these so a silently narrowed scan can never be mistaken for a complete one.
     */
    public record Notice(String key, Object[] args) {
    }

    /**
     * Parse result. Besides the criteria the caller learns whether the user bounded the
     * selection themselves ({@code u:}, {@code t:} or {@code r:}) - destructive commands
     * require that, because {@code defaultTime} pre-fills {@code c.time} and a prefilled
     * value cannot be told apart from an explicit one.
     */
    public record ParseResult(Criteria criteria, String errorKey, Object[] errorArgs,
                              boolean explicitUser, boolean explicitTime, boolean explicitRadius,
                              List<Notice> notices) {
        static ParseResult ok(Criteria c, boolean user, boolean time, boolean radius, List<Notice> notices) {
            return new ParseResult(c, null, null, user, time, radius, List.copyOf(notices));
        }

        static ParseResult error(String key, Object... args) {
            return new ParseResult(null, key, args, false, false, false, List.of());
        }
    }

    public static ParseResult parse(String raw, boolean defaultTime) {
        return parse(raw, defaultTime, false);
    }

    /**
     * Same as {@link #parse(String, boolean)}, but when {@code bareAsUser} is set a
     * colon-less token is treated as the player name (used by /co online) instead of
     * a page number; numeric bare tokens still count as pages.
     */
    public static ParseResult parse(String raw, boolean defaultTime, boolean bareAsUser) {
        Criteria c = new Criteria();
        c.time = defaultTime
                ? TimeUtil.now() - CoreProtectFabric.instance().config().lookup.defaultTimeSeconds
                : 0;
        c.action = "block";
        c.page = 1;
        boolean explicitUser = false;
        boolean explicitTime = false;
        boolean explicitRadius = false;
        List<Notice> notices = new ArrayList<>();

        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.isEmpty()) {
            for (String token : trimmed.split("\\s+")) {
                if (token.isEmpty()) continue;
                int idx = token.indexOf(':');
                if (idx <= 0) {
                    Integer page = parseInt(token);
                    if (bareAsUser && page == null) {
                        c.user = token;
                        explicitUser = true;
                        continue;
                    }
                    if (page == null) return ParseResult.error("coreprotect.error.invalid_param", token);
                    c.page = page;
                    continue;
                }
                String k = token.substring(0, idx).toLowerCase();
                String v = token.substring(idx + 1);
                switch (k) {
                    case "u" -> {
                        c.user = v;
                        explicitUser = true;
                    }
                    case "t" -> {
                        Long seconds = TimeUtil.parseSeconds(v);
                        // a positive duration is required: t:0 would otherwise mean "everything"
                        if (seconds == null || seconds <= 0) return ParseResult.error("coreprotect.error.invalid_time", v);
                        c.time = TimeUtil.now() - seconds;
                        explicitTime = true;
                    }
                    case "a" -> c.action = v.toLowerCase();
                    case "r" -> {
                        Integer r = parseInt(v);
                        if (r == null || r <= 0) return ParseResult.error("coreprotect.error.invalid_radius", v);
                        // r: was only checked for being positive, so r:2147483647 made the
                        // distance filter scan and compare the whole database.
                        c.radius = Math.min(r, MAX_RADIUS);
                        explicitRadius = true;
                        if (r > MAX_RADIUS) {
                            notices.add(new Notice("coreprotect.notice.radius_clamped", new Object[]{r, MAX_RADIUS}));
                        }
                    }
                    case "b" -> c.block = v.toLowerCase();
                    case "e" -> c.exclude = v.toLowerCase();
                    case "p" -> {
                        Integer p = parseInt(v);
                        if (p == null || p <= 0) return ParseResult.error("coreprotect.error.invalid_page", v);
                        // A page number is used as a SQL OFFSET: unbounded values only waste
                        // a full scan (and p == Integer.MAX_VALUE overflowed the page footer).
                        c.page = Math.min(p, MAX_PAGE);
                        if (p > MAX_PAGE) {
                            notices.add(new Notice("coreprotect.notice.page_clamped", new Object[]{p, MAX_PAGE}));
                        }
                    }
                    default -> {
                        return ParseResult.error("coreprotect.error.invalid_param", token);
                    }
                }
            }
        }
        return ParseResult.ok(c, explicitUser, explicitTime, explicitRadius, notices);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
