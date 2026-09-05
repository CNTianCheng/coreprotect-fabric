package net.coreprotect.fabric.command;

import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.util.TimeUtil;

/**
 * Parses CoreProtect-style key:value parameters ({@code u:Steve t:2h a:block r:10 b:stone e:#fire p:2}).
 * A bare number is treated as the page.
 */
public final class ParamParser {

    private ParamParser() {
    }

    public record ParseResult(Criteria criteria, String errorKey, Object[] errorArgs) {
        static ParseResult ok(Criteria c) {
            return new ParseResult(c, null, null);
        }

        static ParseResult error(String key, Object... args) {
            return new ParseResult(null, key, args);
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

        String trimmed = raw == null ? "" : raw.trim();
        if (!trimmed.isEmpty()) {
            for (String token : trimmed.split("\\s+")) {
                if (token.isEmpty()) continue;
                int idx = token.indexOf(':');
                if (idx <= 0) {
                    Integer page = parseInt(token);
                    if (bareAsUser && page == null) {
                        c.user = token;
                        continue;
                    }
                    if (page == null) return ParseResult.error("coreprotect.error.invalid_param", token);
                    c.page = page;
                    continue;
                }
                String k = token.substring(0, idx).toLowerCase();
                String v = token.substring(idx + 1);
                switch (k) {
                    case "u" -> c.user = v;
                    case "t" -> {
                        Long seconds = TimeUtil.parseSeconds(v);
                        if (seconds == null) return ParseResult.error("coreprotect.error.invalid_time", v);
                        c.time = TimeUtil.now() - seconds;
                    }
                    case "a" -> c.action = v.toLowerCase();
                    case "r" -> {
                        Integer r = parseInt(v);
                        if (r == null || r <= 0) return ParseResult.error("coreprotect.error.invalid_radius", v);
                        c.radius = r;
                    }
                    case "b" -> c.block = v.toLowerCase();
                    case "e" -> c.exclude = v.toLowerCase();
                    case "p" -> {
                        Integer p = parseInt(v);
                        if (p == null || p <= 0) return ParseResult.error("coreprotect.error.invalid_page", v);
                        c.page = p;
                    }
                    default -> {
                        return ParseResult.error("coreprotect.error.invalid_param", token);
                    }
                }
            }
        }
        return ParseResult.ok(c);
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
