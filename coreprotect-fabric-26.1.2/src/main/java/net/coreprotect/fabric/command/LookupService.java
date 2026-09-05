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
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared lookup implementation: formats DB rows into translated chat lines.
 * Used by /co lookup and by inspection mode.
 */
public final class LookupService {
    private static final Gson GSON = new Gson();
    private static final TypeToken<List<String>> STRING_LIST = new TypeToken<>() {
    };

    private static final Set<String> VALID_ACTIONS = Set.of(
            "block", "+block", "-block", "#container", "#kill", "#kills", "#chat",
            "#command", "#session", "+session", "-session");

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
        List<String> rows = new ArrayList<>();

        switch (action) {
            case "#container" ->
                    rows.addAll(formatContainerRows(source, orEmpty(mod.database().queryContainers(c, pageSize, offset)), 1));
            case "#kill", "#kills" ->
                    rows.addAll(formatEntityRows(source, orEmpty(mod.database().queryEntities(c, pageSize, offset)), 1));
            case "#chat" ->
                    rows.addAll(formatMessageRows(source, orEmpty(mod.database().queryChat(c, pageSize, offset)), "chat", 1));
            case "#command" ->
                    rows.addAll(formatMessageRows(source, orEmpty(mod.database().queryCommands(c, pageSize, offset)), "command", 1));
            case "#session", "+session", "-session" ->
                    rows.addAll(formatSessionRows(source, orEmpty(mod.database().querySessions(c, pageSize, offset)), 1));
            default ->
                    rows.addAll(formatBlockRows(source, orEmpty(mod.database().queryBlocks(c, pageSize, offset)), 1));
        }

        if (rows == null || rows.isEmpty()) {
            Messages.cmd(source, "coreprotect.lookup.empty");
            return 1;
        }
        Messages.header(source, "coreprotect.lookup.header", c.page, rows.size());
        for (String row : rows) {
            Messages.send(source, Component.literal(row).withStyle(ChatFormatting.GRAY));
        }
        if (rows.size() >= pageSize) {
            Messages.plain(source, "coreprotect.lookup.footer", c.page + 1);
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // Row formatters (public so inspection mode can reuse them)
    // ------------------------------------------------------------------

    public static List<String> formatBlockRows(CommandSourceStack source, List<DatabaseManager.BlockLog> logs, int startIndex) {
        List<String> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        int i = startIndex;
        for (DatabaseManager.BlockLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String user = causeName(t, p, l.user());
            String verb = blockVerb(t, p, l);
            String blockName = BlockStateUtil.displayName(blockIdFor(l));
            String sign = signSuffix(l.meta());
            if (sign != null) {
                rows.add(t.get(p, "coreprotect.row.sign", i, ago, user, verb, blockName, sign));
            } else {
                rows.add(t.get(p, "coreprotect.row.block", i, ago, user, verb, blockName));
            }
            i++;
        }
        return rows;
    }

    public static List<String> formatContainerRows(CommandSourceStack source, List<DatabaseManager.ContainerLog> logs, int startIndex) {
        List<String> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        int i = startIndex;
        for (DatabaseManager.ContainerLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String verb = l.type() == DatabaseManager.CONTAINER_DEPOSIT
                    ? t.get(p, "coreprotect.action.deposited")
                    : t.get(p, "coreprotect.action.withdrew");
            rows.add(t.get(p, "coreprotect.row.container", i, ago, l.user(), verb, l.amount(),
                    BlockStateUtil.displayName(l.data())));
            i++;
        }
        return rows;
    }

    public static List<String> formatEntityRows(CommandSourceStack source, List<DatabaseManager.EntityLog> logs, int startIndex) {
        List<String> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        int i = startIndex;
        for (DatabaseManager.EntityLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            String other = BlockStateUtil.displayName(l.data());
            if ("kill".equals(l.action())) {
                rows.add(t.get(p, "coreprotect.row.kill", i, ago, l.user(), other));
            } else {
                rows.add(t.get(p, "coreprotect.row.death", i, ago, l.user(), other));
            }
            i++;
        }
        return rows;
    }

    public static List<String> formatSessionRows(CommandSourceStack source, List<DatabaseManager.MessageLog> logs, int startIndex) {
        List<String> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        int i = startIndex;
        for (DatabaseManager.MessageLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            rows.add(t.get(p, "+".equals(l.action()) ? "coreprotect.row.session.join" : "coreprotect.row.session.leave",
                    i, ago, l.user()));
            i++;
        }
        return rows;
    }

    public static List<String> formatMessageRows(CommandSourceStack source, List<DatabaseManager.MessageLog> logs,
                                                 String kind, int startIndex) {
        List<String> rows = new ArrayList<>();
        Translator t = CoreProtectFabric.instance().translator();
        ServerPlayer p = source.getPlayer();
        long now = TimeUtil.now();
        int i = startIndex;
        String key = "chat".equals(kind) ? "coreprotect.row.chat" : "coreprotect.row.command";
        for (DatabaseManager.MessageLog l : logs) {
            String ago = TimeUtil.ago(t, p, Math.max(0, now - l.time()));
            rows.add(t.get(p, key, i, ago, l.user(), l.message()));
            i++;
        }
        return rows;
    }

    // ------------------------------------------------------------------

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String blockVerb(Translator t, ServerPlayer p, DatabaseManager.BlockLog l) {
        if (l.type() == DatabaseManager.TYPE_PLACE) return t.get(p, "coreprotect.action.placed");
        if (l.type() == DatabaseManager.TYPE_BREAK) return t.get(p, "coreprotect.action.removed");
        if ("minecraft:air".equals(l.newData())) return t.get(p, "coreprotect.action.destroyed");
        return t.get(p, "coreprotect.action.changed");
    }

    private static String blockIdFor(DatabaseManager.BlockLog l) {
        return l.type() == DatabaseManager.TYPE_PLACE ? l.newData() : l.oldData();
    }

    private static String causeName(Translator t, ServerPlayer p, String user) {
        if (user == null || !user.startsWith("#")) return user;
        String key = "coreprotect.cause." + user.substring(1);
        String localized = t.get(p, key);
        return localized.equals(key) ? user : localized;
    }

    private static String signSuffix(String meta) {
        if (meta == null || meta.isBlank()) return null;
        try {
            List<String> lines = GSON.fromJson(meta, STRING_LIST.getType());
            if (lines == null) return null;
            List<String> nonBlank = new ArrayList<>();
            for (String line : lines) {
                if (!line.isBlank()) nonBlank.add(line);
            }
            return nonBlank.isEmpty() ? null : String.join(" | ", nonBlank);
        } catch (Exception e) {
            return null;
        }
    }
}
