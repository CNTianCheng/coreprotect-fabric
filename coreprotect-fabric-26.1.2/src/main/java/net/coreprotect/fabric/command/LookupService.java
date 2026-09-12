package net.coreprotect.fabric.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.i18n.Translator;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.Messages;
import net.coreprotect.fabric.util.TimeUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

/**
 * Shared lookup implementation. Rows are formatted like the CoreProtect plugin (v22+):
 * {@code <gray>time</gray> <green/red>+/-</green/red> <dark aqua>user</dark aqua> <white>verb</white> <dark aqua>subject</dark aqua>.}
 */
public final class LookupService {
    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST = new TypeToken<>() {
    };

    private static final Set<String> VALID_ACTIONS = Set.of(
            "block", "+block", "-block",
            "container", "+container", "-container", "#container",
            "kill", "kills", "#kill", "#kills", "death", "#death",
            "chat", "#chat", "command", "#command",
            "session", "+session", "-session", "#session",
            "item", "+item", "-item", "#item",
            "sign", "#sign");

    private LookupService() {
    }

    public static boolean isKnownAction(String action) {
        if (VALID_ACTIONS.contains(action)) return true;
        return action != null && action.startsWith("#");
    }

    public static int run(CommandSourceStack source, Criteria c) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod == null) return 0;
        String action = c.action == null ? "block" : c.action;
        if (!isKnownAction(action)) {
            Messages.error(source, "coreprotect.error.invalid_action", action);
            return 0;
        }

        int pageSize = mod.config().lookup.maxLines;
        long offset = Math.max(0, (long) (c.page - 1) * pageSize);
        List<Component> rows = new ArrayList<>();

        // 'container', 'kill', 'chat', ... are accepted without the leading '#' so the
        // CoreProtect-style "a:container" spelling works as expected.
        switch (action) {
            case "container", "+container", "-container", "#container" ->
                    rows.addAll(formatContainerRows(source, orEmpty(mod.database().queryContainers(c, pageSize, offset))));
            case "item", "+item", "-item", "#item" ->
                    rows.addAll(formatItemRows(source, orEmpty(mod.database().queryItems(c, pageSize, offset))));
            case "sign", "#sign" ->
                    rows.addAll(formatSignRows(source, orEmpty(mod.database().querySigns(c, pageSize, offset))));
            case "kill", "kills", "#kill", "#kills", "death", "#death" ->
                    rows.addAll(formatEntityRows(source, orEmpty(mod.database().queryEntities(c, pageSize, offset))));
            case "chat", "#chat" ->
                    rows.addAll(formatMessageRows(source, orEmpty(mod.database().queryChat(c, pageSize, offset))));
            case "command", "#command" ->
                    rows.addAll(formatMessageRows(source, orEmpty(mod.database().queryCommands(c, pageSize, offset))));
            case "session", "+session", "-session", "#session" ->
                    rows.addAll(formatSessionRows(source, orEmpty(mod.database().querySessions(c, pageSize, offset))));
            default ->
                    rows.addAll(formatBlockRows(source, orEmpty(mod.database().queryBlocks(c, pageSize, offset))));
        }

        // a failed read (timeout / SQL error) must never be reported as "no results"
        if (mod.database().readFailed()) {
            Messages.error(source, "coreprotect.error.db", "database read failed");
            return 0;
        }
        if (rows == null || rows.isEmpty()) {
            Messages.cmd(source, "coreprotect.lookup.empty");
            return 1;
        }
        Messages.cmd(source, "coreprotect.lookup.rows_found", rows.size());
        Messages.send(source, lookupHeader(source));
        for (Component row : rows) {
            Messages.send(source, row);
        }
        if (rows.size() >= pageSize) {
            Messages.footer(source, buildLookupCommand(c, c.page + 1), "coreprotect.lookup.footer", c.page + 1);
        }
        return 1;
    }

    /** Rebuilds the /co lookup command for the next page, carrying over all current filters. */
    private static String buildLookupCommand(Criteria c, int page) {
        StringBuilder sb = new StringBuilder("/co lookup");
        if (c.user != null && !c.user.isEmpty()) sb.append(" u:").append(c.user);
        long secondsAgo = TimeUtil.now() - c.time;
        if (secondsAgo > 0) sb.append(" t:").append(secondsAgo);
        if (c.action != null && !c.action.isEmpty() && !"block".equals(c.action)) sb.append(" a:").append(c.action);
        if (c.radius > 0) sb.append(" r:").append(c.radius);
        if (c.block != null && !c.block.isEmpty()) sb.append(" b:").append(c.block);
        if (c.exclude != null && !c.exclude.isEmpty()) sb.append(" e:").append(c.exclude);
        sb.append(" p:").append(page);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Row formatters (public so inspection mode can reuse them)
    // ------------------------------------------------------------------

    public static List<Component> formatBlockRows(CommandSourceStack source, List<DatabaseManager.BlockLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.BlockLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String user = causeName(t, p, l.user());
            String verb = blockVerb(t, p, l);
            String subject = BlockStateUtil.displayName(blockIdFor(l));
            String sign = signSuffix(l.meta());
            if (sign != null) {
                subject = subject + " [" + sign + "]";
            }
            boolean place = l.type() == DatabaseManager.TYPE_PLACE;
            rows.add(row(ago, place ? ChatFormatting.GREEN : ChatFormatting.RED, place ? "+" : "-",
                    user, verb, List.of(Component.literal(subject).withStyle(ChatFormatting.DARK_AQUA))));
        }
        return rows;
    }

    public static List<Component> formatContainerRows(CommandSourceStack source, List<DatabaseManager.ContainerLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.ContainerLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            boolean deposit = l.type() == DatabaseManager.CONTAINER_DEPOSIT;
            String verb = t.get(p, deposit ? "coreprotect.action.deposited" : "coreprotect.action.withdrew");
            rows.add(row(ago, deposit ? ChatFormatting.GREEN : ChatFormatting.RED, deposit ? "+" : "-",
                    causeName(t, p, l.user()), verb,
                    List.of(Component.literal(l.amount() + "x ").withStyle(ChatFormatting.WHITE),
                            Component.literal(BlockStateUtil.displayName(l.data())).withStyle(ChatFormatting.DARK_AQUA))));
        }
        return rows;
    }

    public static List<Component> formatItemRows(CommandSourceStack source, List<DatabaseManager.ItemLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.ItemLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            boolean pickup = "+".equals(l.action());
            String verb = t.get(p, pickup ? "coreprotect.action.picked_up" : "coreprotect.action.dropped");
            rows.add(row(ago, pickup ? ChatFormatting.GREEN : ChatFormatting.RED, pickup ? "+" : "-",
                    causeName(t, p, l.user()), verb,
                    List.of(Component.literal(l.amount() + "x ").withStyle(ChatFormatting.WHITE),
                            Component.literal(BlockStateUtil.displayName(l.data())).withStyle(ChatFormatting.DARK_AQUA))));
        }
        return rows;
    }

    public static List<Component> formatSignRows(CommandSourceStack source, List<DatabaseManager.SignLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.SignLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String verb = t.get(p, "coreprotect.action.sign_edited");
            rows.add(row(ago, ChatFormatting.WHITE, "-", causeName(t, p, l.user()), verb,
                    List.of(Component.literal("[" + signLines(l.data()) + "]").withStyle(ChatFormatting.DARK_AQUA))));
        }
        return rows;
    }

    public static List<Component> formatEntityRows(CommandSourceStack source, List<DatabaseManager.EntityLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.EntityLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String other = BlockStateUtil.displayName(l.data());
            if ("kill".equals(l.action())) {
                rows.add(row(ago, ChatFormatting.WHITE, "-", causeName(t, p, l.user()),
                        t.get(p, "coreprotect.action.killed"),
                        List.of(Component.literal(other).withStyle(ChatFormatting.DARK_AQUA))));
            } else {
                rows.add(row(ago, ChatFormatting.RED, "-", causeName(t, p, l.user()),
                        t.get(p, "coreprotect.action.killed_by"),
                        List.of(Component.literal(other).withStyle(ChatFormatting.DARK_AQUA))));
            }
        }
        return rows;
    }

    public static List<Component> formatSessionRows(CommandSourceStack source, List<DatabaseManager.MessageLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.MessageLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            rows.add(sessionRow(ago, t, p, l));
        }
        return rows;
    }

    /** Session rows for /co online: absolute clock times instead of relative "ago" values. */
    public static List<Component> formatOnlineRows(CommandSourceStack source, List<DatabaseManager.MessageLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        for (DatabaseManager.MessageLog l : logs) {
            rows.add(sessionRow(TimeUtil.clock(l.time()), t, p, l));
        }
        return rows;
    }

    public static List<Component> formatMessageRows(CommandSourceStack source, List<DatabaseManager.MessageLog> logs) {
        List<Component> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        for (DatabaseManager.MessageLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            rows.add(colonRow(ago, causeName(t, p, l.user()), l.message()));
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // Row builders (CoreProtect v22+ color scheme)
    // ------------------------------------------------------------------

    /** {@code <gray>time</gray> <tag> <dark aqua>user</dark aqua> <white>verb</white> <subject segments>.} */
    private static Component row(String time, ChatFormatting tagColor, String tag, String user, String verb, List<Component> subjects) {
        MutableComponent text = Component.literal(time).withStyle(ChatFormatting.GRAY);
        text.append(Component.literal(" ").withStyle(ChatFormatting.WHITE));
        text.append(Component.literal(tag).withStyle(tagColor));
        text.append(Component.literal(" ").withStyle(ChatFormatting.WHITE));
        text.append(Component.literal(user).withStyle(ChatFormatting.DARK_AQUA));
        text.append(Component.literal(" ").withStyle(ChatFormatting.WHITE));
        text.append(Component.literal(verb).withStyle(ChatFormatting.WHITE));
        for (Component subject : subjects) {
            text.append(Component.literal(" ").withStyle(ChatFormatting.WHITE));
            text.append(subject);
        }
        text.append(Component.literal(".").withStyle(ChatFormatting.WHITE));
        return text;
    }

    private static Component sessionRow(String time, Translator t, ServerPlayer p, DatabaseManager.MessageLog l) {
        boolean join = "+".equals(l.action());
        return row(time, join ? ChatFormatting.GREEN : ChatFormatting.RED, join ? "+" : "-",
                causeName(t, p, l.user()),
                t.get(p, join ? "coreprotect.action.session_join" : "coreprotect.action.session_leave"),
                List.of());
    }

    /** {@code <gray>time</gray> - <dark aqua>user</dark aqua>: <white>message</white>} (chat/command). */
    private static Component colonRow(String time, String user, String message) {
        return Component.literal(time).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal("-").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(user).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(message).withStyle(ChatFormatting.WHITE));
    }

    /** {@code ----- <dark aqua>CoreProtect | Lookup Results</dark aqua> -----}, as in the plugin. */
    private static Component lookupHeader(CommandSourceStack source) {
        String title = Messages.translate(source, "coreprotect.lookup.header.title");
        return Component.literal("----- ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal("CoreProtect").withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" | ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(title).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(" -----").withStyle(ChatFormatting.WHITE));
    }

    // ------------------------------------------------------------------

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blockVerb(Translator t, ServerPlayer p, DatabaseManager.BlockLog l) {
        if (l.type() == DatabaseManager.TYPE_PLACE) return t.get(p, "coreprotect.action.placed");
        if (l.type() == DatabaseManager.TYPE_BREAK) return t.get(p, "coreprotect.action.removed");
        if ("minecraft:air".equals(l.oldData())) return t.get(p, "coreprotect.action.placed");
        if ("minecraft:air".equals(l.newData())) return t.get(p, "coreprotect.action.destroyed");
        return t.get(p, "coreprotect.action.changed");
    }

    private static String blockIdFor(DatabaseManager.BlockLog l) {
        return "minecraft:air".equals(l.oldData()) ? l.newData() : l.oldData();
    }

    private static String causeName(Translator t, ServerPlayer p, String user) {
        if (user == null || !user.startsWith("#")) return user;
        String key = "coreprotect.cause." + user.substring(1);
        String localized = t.get(p, key);
        return localized.equals(key) ? user : localized;
    }

    private static String signSuffix(String meta) {
        if (meta == null || meta.isBlank()) return null;
        List<String> lines = BlockStateUtil.signLines(meta);
        if (lines == null) return null;
        List<String> nonBlank = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) nonBlank.add(line);
        }
        if (nonBlank.isEmpty()) return null;
        String text = String.join(" | ", nonBlank);
        return BlockStateUtil.signIsBack(meta) ? text + " (back)" : text;
    }

    private static String signLines(String meta) {
        String suffix = signSuffix(meta);
        return suffix == null ? "" : suffix;
    }
}
