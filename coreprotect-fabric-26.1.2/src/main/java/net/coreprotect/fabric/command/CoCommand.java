package net.coreprotect.fabric.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.i18n.Translator;
import net.coreprotect.fabric.rollback.RollbackManager;
import net.coreprotect.fabric.util.Messages;
import net.coreprotect.fabric.util.Permissions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CoCommand {

    private CoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("co");
        // Bare /co used to answer with vanilla's "Incomplete command": the parent node had no
        // executes() of its own. It shows the help now, and so does every subcommand whose
        // parameters are missing (L4-13).
        root.executes(ctx -> help(ctx.getSource()));
        root.then(literal("help").requires(node(Permissions.NODE_HELP)).executes(ctx -> help(ctx.getSource())));
        root.then(literal("status").requires(node(Permissions.NODE_STATUS)).executes(ctx -> status(ctx.getSource())));
        root.then(literal("inspect").requires(node(Permissions.NODE_INSPECT))
                .executes(ctx -> inspect(ctx.getSource()))
                .then(literal("on").executes(ctx -> inspectOn(ctx.getSource())))
                .then(literal("off").executes(ctx -> inspectOff(ctx.getSource()))));
        // /co i is the CoreProtect-style shorthand for /co inspect
        root.then(literal("i").requires(node(Permissions.NODE_INSPECT))
                .executes(ctx -> inspect(ctx.getSource()))
                .then(literal("on").executes(ctx -> inspectOn(ctx.getSource())))
                .then(literal("off").executes(ctx -> inspectOff(ctx.getSource()))));
        root.then(literal("lookup").requires(node(Permissions.NODE_LOOKUP))
                .executes(ctx -> lookup(ctx.getSource(), ""))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(LOOKUP_KEYS))
                        .executes(ctx -> lookup(ctx.getSource(), StringArgumentType.getString(ctx, "params")))));
        // /co online: no args lists currently online players, with a player name it shows session times
        root.then(literal("online").requires(node(Permissions.NODE_ONLINE))
                .executes(ctx -> onlineList(ctx.getSource()))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(onlineSuggestions())
                        .executes(ctx -> onlineHistory(ctx.getSource(), StringArgumentType.getString(ctx, "params")))));
        root.then(literal("rollback").requires(node(Permissions.NODE_ROLLBACK))
                .executes(ctx -> usage(ctx.getSource(), ROLLBACK_USAGE))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(ROLLBACK_KEYS))
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), false))));
        root.then(literal("restore").requires(node(Permissions.NODE_RESTORE))
                .executes(ctx -> usage(ctx.getSource(), RESTORE_USAGE))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(ROLLBACK_KEYS))
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), true))));
        root.then(literal("undo").requires(node(Permissions.NODE_UNDO)).executes(ctx -> undo(ctx.getSource())));
        root.then(literal("purge").requires(node(Permissions.NODE_PURGE))
                .executes(ctx -> usage(ctx.getSource(), PURGE_USAGE))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(PURGE_KEYS))
                        .executes(ctx -> purge(ctx.getSource(), StringArgumentType.getString(ctx, "params")))));
        root.then(literal("reload").requires(node(Permissions.NODE_RELOAD)).executes(ctx -> reload(ctx.getSource())));
        root.then(literal("language").requires(node(Permissions.NODE_LANGUAGE))
                .executes(ctx -> languageList(ctx.getSource()))
                .then(literal("list").executes(ctx -> languageList(ctx.getSource())))
                .then(argument("code", StringArgumentType.word())
                        .suggests(languageSuggestions())
                        .executes(ctx -> languageSet(ctx.getSource(), StringArgumentType.getString(ctx, "code")))));
        root.then(literal("debug").requires(node(Permissions.NODE_DEBUG))
                .executes(ctx -> usage(ctx.getSource(), DEBUG_USAGE))
                .then(literal("natural").executes(ctx -> debugNatural(ctx.getSource()))));

        dispatcher.register(root);
        // /coreprotect alias
        dispatcher.register(literal("coreprotect").redirect(dispatcher.getRoot().getChild("co")));
    }

    private static Predicate<CommandSourceStack> node(String permission) {
        return s -> Permissions.has(s, permission);
    }

    private static int usage(CommandSourceStack source, String usage) {
        Messages.error(source, "coreprotect.error.usage", usage);
        return 0;
    }

    // ------------------------------------------------------------------
    // Parameter suggestions
    // ------------------------------------------------------------------

    private static final List<String> LOOKUP_KEYS = List.of("u:", "t:", "a:", "r:", "b:", "e:", "p:");
    private static final List<String> ROLLBACK_KEYS = List.of("u:", "t:", "a:", "r:", "b:", "e:");
    /**
     * /co purge accepted only t: and silently dropped every other filter, so "purge u:Steve
     * t:1d" deleted the whole server's history (L4-01). All filters the database can apply are
     * offered now and passed through to the database.
     */
    private static final List<String> PURGE_KEYS = List.of("u:", "t:", "a:", "r:", "b:", "e:");
    private static final List<String> ONLINE_KEYS = List.of("t:", "p:");

    // Command syntax only (no prose): shown through coreprotect.error.usage.
    private static final String ROLLBACK_USAGE = "/co rollback u:<user> t:<time> [r:<radius>] [a:<action>] [b:<block>] [e:<exclude>]";
    private static final String RESTORE_USAGE = "/co restore u:<user> t:<time> [r:<radius>] [a:<action>] [b:<block>] [e:<exclude>]";
    private static final String PURGE_USAGE = "/co purge t:<time> [u:<user>] [a:<action>] [b:<block>] [e:<exclude>] [r:<radius>]";
    private static final String DEBUG_USAGE = "/co debug natural";

    /** Prints the values the parser had to clamp, so a narrowed scan is never mistaken for a full one. */
    private static void reportNotices(CommandSourceStack source, ParamParser.ParseResult parse) {
        for (ParamParser.Notice notice : parse.notices()) {
            Messages.cmd(source, notice.key(), notice.args());
        }
    }

    /** Suggests key:value tokens (u:, t:, ...) and player names after u:. */
    private static SuggestionProvider<CommandSourceStack> paramSuggestions(List<String> keys) {
        return (ctx, builder) -> {
            Token token = token(builder);
            List<String> options = new ArrayList<>();
            if (token.current().startsWith("u:")) {
                for (String name : playerNames(ctx.getSource(), token.current().substring(2).toLowerCase())) {
                    options.add("u:" + name);
                }
            } else if (!token.current().contains(":")) {
                String lower = token.current().toLowerCase();
                for (String key : keys) {
                    if (key.startsWith(lower)) options.add(key);
                }
            }
            com.mojang.brigadier.suggestion.SuggestionsBuilder word = builder.createOffset(token.range().getStart());
            for (String option : options) {
                word.suggest(option);
            }
            builder.add(word);
            return builder.buildFuture();
        };
    }

    /** /co online: t:/p: keys plus bare player names. */
    private static SuggestionProvider<CommandSourceStack> onlineSuggestions() {
        return (ctx, builder) -> {
            Token token = token(builder);
            List<String> options = new ArrayList<>();
            if (token.current().startsWith("u:")) {
                for (String name : playerNames(ctx.getSource(), token.current().substring(2).toLowerCase())) {
                    options.add("u:" + name);
                }
            } else if (!token.current().contains(":")) {
                String lower = token.current().toLowerCase();
                for (String key : ONLINE_KEYS) {
                    if (key.startsWith(lower)) options.add(key);
                }
                for (String name : playerNames(ctx.getSource(), lower)) {
                    options.add(name);
                }
            }
            com.mojang.brigadier.suggestion.SuggestionsBuilder word = builder.createOffset(token.range().getStart());
            for (String option : options) {
                word.suggest(option);
            }
            builder.add(word);
            return builder.buildFuture();
        };
    }

    /** /co language: available codes plus auto/list. */
    private static SuggestionProvider<CommandSourceStack> languageSuggestions() {
        return (ctx, builder) -> {
            CoreProtectFabric mod = CoreProtectFabric.instance();
            if (mod != null) {
                for (String code : mod.translator().available()) {
                    builder.suggest(code);
                }
            }
            builder.suggest("auto");
            builder.suggest("list");
            return builder.buildFuture();
        };
    }

    /** The token currently being typed: text before it (ending with a space), the word itself, and its range. */
    private record Token(String prefix, String current, StringRange range) {
    }

    private static Token token(SuggestionsBuilder builder) {
        String input = builder.getInput();
        // brigadier builds suggestions for the end of the input; using
        // input.length() - remaining.length() here always yielded builder.getStart(),
        // so the token was always empty and key/player completion never matched
        int cursor = input.length();
        int start = Math.max(builder.getStart(), 0);
        if (start > cursor) start = cursor;
        String argText = input.substring(start, cursor);
        int lastSpace = argText.lastIndexOf(' ');
        String prefix = lastSpace >= 0 ? argText.substring(0, lastSpace + 1) : "";
        String current = argText.substring(lastSpace + 1);
        return new Token(prefix, current, StringRange.between(cursor - current.length(), cursor));
    }

    /** Online players plus recently seen users (newest first), filtered by the typed prefix. */
    private static List<String> playerNames(CommandSourceStack source, String partial) {
        List<String> names = new ArrayList<>();
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (mod != null && mod.server() != null) {
            for (ServerPlayer p : mod.server().getPlayerList().getPlayers()) {
                String name = p.getGameProfile().name();
                if (name.toLowerCase().startsWith(partial) && !names.contains(name)) {
                    names.add(name);
                }
            }
            for (String name : mod.database().recentUsers(40)) {
                if (name.startsWith("#")) continue;
                if (name.toLowerCase().startsWith(partial) && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names.size() > 20 ? names.subList(0, 20) : names;
    }

    private static int help(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        Messages.title(source, "coreprotect.help.header");
        Messages.helpLine(source, "coreprotect.help.line.help");
        Messages.helpLine(source, "coreprotect.help.line.inspect");
        Messages.helpLine(source, "coreprotect.help.line.lookup");
        Messages.helpLine(source, "coreprotect.help.line.online");
        Messages.helpLine(source, "coreprotect.help.line.rollback");
        Messages.helpLine(source, "coreprotect.help.line.restore");
        Messages.helpLine(source, "coreprotect.help.line.undo");
        Messages.helpLine(source, "coreprotect.help.line.purge");
        Messages.helpLine(source, "coreprotect.help.line.reload");
        Messages.helpLine(source, "coreprotect.help.line.status");
        Messages.helpLine(source, "coreprotect.help.line.language");
        // The list used to stop at these twelve lines: /co i, /coreprotect and the debug
        // scenario command were not discoverable, and no permission level was mentioned (L4-13).
        Messages.helpLine(source, "coreprotect.help.line.aliases");
        Messages.helpLine(source, "coreprotect.help.line.debug");
        Messages.helpLine(source, "coreprotect.help.line.time");
        if (mod != null) {
            CoreProtectConfig.Permissions perms = mod.config().permissions;
            Messages.cmd(source, "coreprotect.help.levels", perms.lookupLevel, perms.adminLevel);
        }
        return 1;
    }

    private static int status(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        DatabaseManager db = mod.database();
        Translator t = mod.translator();
        // The absolute database path, the eleven logging switches, the permission-group count and
        // the write statistics are operational details; they were shown to every lookupLevel
        // player (default 1 = moderators) instead of the admin tier (L4-18).
        boolean detailed = Permissions.has(source, Permissions.NODE_ADMIN);
        // The counts come from the budgeted, parallel implementation: the sequential variant has
        // no deadline at all, so eight serial COUNT(*) queries held a console command for as long
        // as the database needed (L4-09 / L1-08). The wait matches that budget instead of giving
        // up after 10 s while the counts keep running.
        LookupService.query(source, db::counts, counts -> {
            long[] rows;
            if (counts == null) {
                // the whole report failed: show "n/a" instead of a row of convincing zeros
                rows = new long[8];
                java.util.Arrays.fill(rows, -1L);
            } else {
                rows = counts;
            }
            Object[] shown = new Object[rows.length];
            for (int i = 0; i < rows.length; i++) {
                // -1 is a failed COUNT(*); printing it as a number hid the failure
                shown[i] = rows[i] < 0 ? "n/a" : Long.toString(rows[i]);
            }
            ServerPlayer p = source.getPlayer();
            Messages.title(source, "coreprotect.status.header");
            Messages.statusLine(source, "coreprotect.status.version", CoreProtectFabric.MOD_VERSION);
            if (detailed) {
                Messages.statusLine(source, "coreprotect.status.database", String.valueOf(db.dbPath()));
            }
            Messages.statusLine(source, "coreprotect.status.counts", shown);
            Messages.statusLine(source, "coreprotect.status.language",
                    t.defaultLanguage(), String.join(", ", t.available()));
            CoreProtectConfig.DataRetention r = mod.config().dataRetention;
            if (r != null && r.enabled && r.maxDays > 0) {
                Messages.statusLine(source, "coreprotect.status.retention.on", r.maxDays);
            } else {
                Messages.statusLine(source, "coreprotect.status.retention.off");
            }
            if (detailed) {
                String on = t.get(p, "coreprotect.word.on");
                String off = t.get(p, "coreprotect.word.off");
                CoreProtectConfig.Logging log = mod.config().logging;
                Messages.statusLine(source, "coreprotect.status.logging",
                        log.block ? on : off, log.container ? on : off, log.entity ? on : off,
                        log.chat ? on : off, log.command ? on : off, log.session ? on : off,
                        log.natural ? on : off, log.hopper ? on : off, log.dispenser ? on : off,
                        log.item ? on : off, log.signEdit ? on : off);
                Messages.statusLine(source, "coreprotect.status.permgroups",
                        mod.config().permissionGroups.enabled ? on : off, Permissions.groupCount());
                // Rows that were dropped (queue full / database closed) are otherwise invisible
                long[] writes = db.writeStats();
                Messages.statusLine(source, "coreprotect.status.writes", writes[0], writes[1], writes[2]);
            } else {
                Messages.cmd(source, "coreprotect.status.restricted", mod.config().permissions.adminLevel);
            }
        }, LookupService.STATUS_TIMEOUT_MS);
        return 1;
    }

    /** /co online without arguments: list players currently on the server. */
    private static int onlineList(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        List<ServerPlayer> players = mod.server().getPlayerList().getPlayers();
        Messages.title(source, "coreprotect.online.list.header", players.size());
        if (players.isEmpty()) {
            Messages.cmd(source, "coreprotect.online.list.empty");
            return 1;
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : players) {
            names.add(p.getGameProfile().name());
        }
        Messages.plain(source, "coreprotect.online.list.names", String.join(", ", names));
        return 1;
    }

    /** /co online <player> [t:<time>] [p:<page>]: show the player's join/leave times. */
    private static int onlineHistory(CommandSourceStack source, String params) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        // bare player names are allowed: /co online Steve t:1h
        ParamParser.ParseResult parse = ParamParser.parse(params, true, true);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        reportNotices(source, parse);
        if (c.user == null || c.user.isEmpty()) {
            Messages.error(source, "coreprotect.online.require_player");
            return 0;
        }
        boolean online = false;
        for (ServerPlayer p : mod.server().getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(c.user)) {
                online = true;
                break;
            }
        }
        Messages.cmd(source, online ? "coreprotect.online.currently_online" : "coreprotect.online.not_online", c.user);
        int pageSize = mod.config().lookup.maxLines;
        long offset = Math.max(0, (long) (c.page - 1) * pageSize);
        // The session query runs on the read pool; the listing is sent from the callback.
        LookupService.query(source, () -> mod.database().querySessions(c, pageSize, offset), logs -> {
            List<Component> rows = LookupService.formatOnlineRows(source, logs == null ? List.of() : logs);
            if (rows.isEmpty()) {
                Messages.cmd(source, "coreprotect.online.empty", c.user);
                return;
            }
            Messages.title(source, "coreprotect.online.header", c.user);
            for (Component row : rows) {
                Messages.send(source, row);
            }
            if (rows.size() >= pageSize) {
                StringBuilder sb = new StringBuilder("/co online ").append(c.user);
                long secondsAgo = net.coreprotect.fabric.util.TimeUtil.now() - c.time;
                if (secondsAgo > 0) sb.append(" t:").append(secondsAgo);
                sb.append(" p:").append(c.page + 1);
                Messages.footer(source, sb.toString(), "coreprotect.lookup.footer", c.page + 1);
            }
        });
        return 1;
    }

    private static int inspect(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        boolean on = CoreProtectFabric.instance().inspector().toggle(player);
        Messages.cmd(source, on ? "coreprotect.inspect.on" : "coreprotect.inspect.off");
        return 1;
    }

    private static int inspectOn(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        CoreProtectFabric.instance().inspector().enable(player);
        Messages.cmd(source, "coreprotect.inspect.on");
        return 1;
    }

    private static int inspectOff(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        CoreProtectFabric.instance().inspector().disable(player);
        Messages.cmd(source, "coreprotect.inspect.off");
        return 1;
    }

    private static int lookup(CommandSourceStack source, String params) {
        ParamParser.ParseResult parse = ParamParser.parse(params, true);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        reportNotices(source, parse);
        // The lookup permission used to depend on the command only, so a player allowed to read
        // blocks could read chat and command rows - which is where /login passwords live (L4-06).
        // The node for the selected action is checked instead.
        if (!Permissions.has(source, Permissions.lookupNodeFor(c.action))) {
            Messages.error(source, "coreprotect.error.permission");
            return 0;
        }
        if (!applyRadius(source, c)) return 0;
        return LookupService.run(source, c);
    }

    private static int rollback(CommandSourceStack source, String params, boolean isRestore) {
        ParamParser.ParseResult parse = ParamParser.parse(params, true);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        reportNotices(source, parse);
        // Like the CoreProtect plugin, u: is optional: a rollback/restore may target every
        // user within a time/radius/block filter. Requiring no filter at all is still refused.
        // c.time is pre-filled with the configured default window while parsing, so the time part
        // of that guard was always true: "/co rollback b:stone" silently rewrote every player's
        // blocks of the last week (L4-05). Only an explicit t: (or an explicit r:) counts now.
        if (c.user == null || c.user.isEmpty()) {
            c.user = null;
            boolean filtered = parse.explicitTime() || parse.explicitRadius();
            if (!filtered) {
                Messages.error(source, "coreprotect.error.missing_user");
                return 0;
            }
        }
        if (!applyRadius(source, c)) return 0;
        // The rollback runs asynchronously: the queries happen on the read pool and the world
        // is edited in short slices on the server thread, so a 5000-block rollback no longer
        // freezes the server. Completion and error messages are sent by RollbackManager, which
        // also prints "rollback started" - only once it knows the command is not refused
        // because another operation of the same caller is still running.
        CoreProtectFabric.instance().rollbackManager().startRollback(source, c, isRestore);
        return 1;
    }

    private static int undo(CommandSourceStack source) {
        CoreProtectFabric.instance().rollbackManager().startUndo(source);
        return 1;
    }

    /**
     * Fills in the center of an {@code r:} filter and scopes it to the caller's dimension.
     *
     * <p>Without a player the center used to default to {@code (0,0,0)} silently, so a console
     * {@code /co rollback r:10} hit the world spawn; and because the radius filter carried no
     * world condition, it reached the spawn of every dimension. Radius commands from console,
     * RCON and command blocks are refused now, and a player's radius stays in their world.
     */
    private static boolean applyRadius(CommandSourceStack source, Criteria c) {
        if (c == null || c.radius <= 0) return true;
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            Messages.error(source, "coreprotect.error.radius_needs_player");
            return false;
        }
        c.center = player.blockPosition();
        c.world = source.getLevel().dimension().identifier().toString();
        return true;
    }

    private static int purge(CommandSourceStack source, String params) {
        ParamParser.ParseResult parse = ParamParser.parse(params, false);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        reportNotices(source, parse);
        if (c.time <= 0) {
            Messages.error(source, "coreprotect.purge.require_time");
            return 0;
        }
        // r: needs a real center; without this the delete would quietly ignore the radius.
        if (!applyRadius(source, c)) return 0;
        // The whole filter set is handed to the database now. It used to be reduced to the time
        // cutoff alone, so "/co purge u:Steve t:1d" deleted every player's data of the last day
        // and reported it as a successful purge of the requested user (L4-01). Because the
        // operation cannot be undone, the effective scope is echoed first.
        Messages.cmd(source, "coreprotect.purge.scope", describePurgeScope(c));
        // Deleting old rows can take a while on a large database, so it runs on the read
        // pool and the result is reported from the callback instead of stalling the tick.
        LookupService.query(source,
                () -> CoreProtectFabric.instance().database().purge(c),
                rows -> {
                    if (rows == null) {
                        // A failing DELETE is a write problem; it used to be reported through the
                        // generic "database read failed" text (L1-15).
                        Messages.error(source, "coreprotect.purge.failed");
                        return;
                    }
                    Messages.success(source, "coreprotect.purge.complete", rows);
                    // compact the database file in the background after deleting old data
                    CoreProtectFabric.instance().database().vacuumAsync();
                });
        return 1;
    }

    /** ASCII summary of the filters a purge applies, e.g. {@code "t:86400s, u:Steve"}. */
    private static String describePurgeScope(Criteria c) {
        List<String> parts = new ArrayList<>();
        parts.add("t:" + (net.coreprotect.fabric.util.TimeUtil.now() - c.time) + "s");
        if (c.user != null && !c.user.isEmpty()) parts.add("u:" + c.user);
        if (c.action != null && !c.action.isEmpty() && !"block".equals(c.action)) parts.add("a:" + c.action);
        if (c.block != null) parts.add("b:" + c.block);
        if (c.exclude != null) parts.add("e:" + c.exclude);
        if (c.radius > 0) parts.add("r:" + c.radius);
        return String.join(", ", parts);
    }

    private static int reload(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        // A broken config file used to be reported as a successful reload, and the file's old
        // values stayed in place without the caller learning about it (L4-07).
        try {
            mod.config().load();
        } catch (RuntimeException e) {
            Messages.error(source, "coreprotect.reload.failed", describeFailure(e));
            return 0;
        }
        if (mod.config().lastLoadFailed()) {
            Messages.error(source, "coreprotect.reload.failed", String.valueOf(mod.config().lastLoadError()));
            return 0;
        }
        mod.translator().load(mod.config().language);
        // database.syncMode is applied to the live connections now instead of requiring a
        // restart (contract F-9.2).
        mod.database().applySyncMode();
        Messages.success(source, "coreprotect.reload.complete");
        // Settings that are only read at startup cannot be applied here; say so instead of
        // letting the caller believe the new values are active (L4-07).
        java.util.List<String> restart = mod.config().restartRequired();
        if (!restart.isEmpty()) {
            Messages.cmd(source, "coreprotect.reload.restart_required", String.join(", ", restart));
        }
        return 1;
    }

    /** Exception text for the reload error message; never the literal "null". */
    private static String describeFailure(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static int languageList(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerPlayer p = source.getPlayer();
        Messages.cmd(source, "coreprotect.language.current", mod.translator().localeOf(p));
        Messages.cmd(source, "coreprotect.language.list", String.join(", ", mod.translator().available()));
        return 1;
    }

    private static int languageSet(CommandSourceStack source, String code) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (code.equalsIgnoreCase("list")) {
            return languageList(source);
        }
        ServerPlayer p = source.getPlayer();
        if (p == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        Translator t = mod.translator();
        if (code.equalsIgnoreCase("auto") || code.equalsIgnoreCase("default")) {
            t.setOverride(p.getUUID(), null);
            mod.database().saveUserLanguageAsync(p.getGameProfile().name(), p.getUUID().toString(), null);
            Messages.cmd(source, "coreprotect.language.reset");
            return 1;
        }
        if (!t.isAvailable(code)) {
            Messages.error(source, "coreprotect.error.unknown_language", code);
            return 0;
        }
        t.setOverride(p.getUUID(), code);
        mod.database().saveUserLanguageAsync(p.getGameProfile().name(), p.getUUID().toString(), code);
        Messages.cmd(source, "coreprotect.language.set", code);
        return 1;
    }

    /** Forces (or releases) every chunk of the natural-test area, loading it synchronously. */
    private static void forceChunks(ServerLevel world, int minX, int maxX, int minZ, int maxZ, boolean forced) {
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                world.setChunkForced(cx, cz, forced);
                if (forced) {
                    world.getChunk(cx, cz);
                }
            }
        }
    }
    /**
     * Places fire/water/explosion scenarios to verify that natural block
     * events are logged (#fire, #water, #tnt). Useful after installation.
     */
    private static int debugNatural(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerLevel world = mod.server().overworld();
        // Place scenarios around the world spawn so they sit inside the always-ticking spawn chunks.
        BlockPos ground = CoreProtectFabric.instance().server().getRespawnData().pos().below();
        // Real fire, a real explosion and real fluids are created at the world spawn; say so
        // before touching anything instead of only reporting where afterwards (L4-08).
        Messages.cmd(source, "coreprotect.debug.natural_warning");
        int baseX = ground.getX() >> 4;
        int baseZ = ground.getZ() >> 4;
        BlockPos fireBase;
        // 1.21.9+: chunks no longer tick without players. Force-load the whole test area BEFORE
        // placing anything, otherwise a setBlock into an unloaded chunk is dropped - and keep it
        // loaded afterwards as well: the fire scenario only burns through its scheduled tick, so
        // releasing the chunks immediately meant #fire never showed up unless a player happened
        // to stand next to the fire. The piston scenario sits one chunk further out
        // (ground.add(0, 1, 20)), which the old nine-chunk window did not cover at all.
        final int minX = baseX - 1;
        final int maxX = baseX + 1;
        final int minZ = baseZ - 1;
        final int maxZ = baseZ + 2;
        forceChunks(world, minX, maxX, minZ, maxZ, true);
        try {
            fireBase = placeNaturalScenarios(world, ground);
        } finally {
            // A forced chunk flag is written into the save file, and the test chunks used to stay
            // forced forever (they even survived a server restart). They are released again after
            // sixty seconds instead (L4-08): fire only spreads through its scheduled tick (one every
            // 30-40 ticks) and the hopper/dropper chain needs a few moves, so sixty seconds is long
            // enough for both - and short enough to never become a permanent setting of the server.
            CompletableFuture.delayedExecutor(60L, TimeUnit.SECONDS).execute(() -> {
                CoreProtectFabric later = CoreProtectFabric.instance();
                if (later == null || later.server() == null) return;
                later.server().execute(() -> forceChunks(world, minX, maxX, minZ, maxZ, false));
            });
        }
        Messages.cmd(source, "coreprotect.debug.natural_started", fireBase.getX(), fireBase.getZ());
        return 1;
    }

    /** Places the fire/water/explosion/hopper/dropper/dispenser/piston scenarios; returns the fire base. */
    private static BlockPos placeNaturalScenarios(ServerLevel world, BlockPos ground) {
        // fire burns the wool it sits on (kept far from the TNT scenario)
        BlockPos fireBase = ground.offset(-8, 0, 8);
        world.setBlock(fireBase, net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(fireBase.above(), net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        // flowing water washes away the torch (kept far from the fire scenario)
        BlockPos waterBase = ground.offset(-5, 0, -5);
        world.setBlock(waterBase.above(), net.minecraft.world.level.block.Blocks.TORCH.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(waterBase.above(2), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        // direct TNT-style explosion destroys the surrounding grass
        BlockPos boom = ground.offset(6, 1, 0);
        PrimedTnt tnt = new PrimedTnt(world, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, null);
        world.addFreshEntity(tnt);
        world.explode(tnt, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, 4.0F,
                Level.ExplosionInteraction.TNT);
        // hopper above a chest: its contents are pushed into the chest -> #hopper transactions
        BlockPos hopperChest = ground.offset(0, 0, 10);
        world.setBlock(hopperChest, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(hopperChest.above(), net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        BlockEntity hopperBe = world.getBlockEntity(hopperChest.above());
        if (hopperBe instanceof net.minecraft.world.level.block.entity.HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3));
        }
        // powered dropper: ejects its contents forward -> #dropper transactions
        BlockPos dropperPos = ground.offset(0, 0, -10);
        world.setBlock(dropperPos, net.minecraft.world.level.block.Blocks.DROPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.UP), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(dropperPos.below(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        BlockEntity dropperBe = world.getBlockEntity(dropperPos);
        if (dropperBe instanceof net.minecraft.world.level.block.entity.DropperBlockEntity dropper) {
            dropper.setItem(0, new ItemStack(net.minecraft.world.item.Items.EMERALD, 4));
        }
        // powered dispenser: shoots arrows upward -> #dispenser transactions
        BlockPos dispenserPos = ground.offset(0, 0, -11);
        world.setBlock(dispenserPos, net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.UP), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(dispenserPos.below(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        BlockEntity dispenserBe = world.getBlockEntity(dispenserPos);
        if (dispenserBe instanceof net.minecraft.world.level.block.entity.DispenserBlockEntity dispenser) {
            dispenser.setItem(0, new ItemStack(net.minecraft.world.item.Items.ARROW, 3));
        }
        // piston pushing a wool block (in the air, so the push always succeeds) -> #piston records
        BlockPos pistonBase = CoreProtectFabric.instance().server().getRespawnData().pos().offset(0, 1, 20);
        // clear the pushed block and its destination first: leftovers from an earlier test
        // (an old piston head, for example) would make the push fail
        world.setBlock(pistonBase.north(2), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(pistonBase.north(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(pistonBase, net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, net.minecraft.core.Direction.NORTH), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(pistonBase.north(), net.minecraft.world.level.block.Blocks.WHITE_WOOL.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        world.setBlock(pistonBase.south(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_ALL);
        return fireBase;
    }
}
