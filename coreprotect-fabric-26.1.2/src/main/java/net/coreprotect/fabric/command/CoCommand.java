package net.coreprotect.fabric.command;

import java.util.ArrayList;
import java.util.List;
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
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class CoCommand {

    private CoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("co");
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
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(ROLLBACK_KEYS))
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), false))));
        root.then(literal("restore").requires(node(Permissions.NODE_RESTORE))
                .then(argument("params", StringArgumentType.greedyString())
                        .suggests(paramSuggestions(ROLLBACK_KEYS))
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), true))));
        root.then(literal("undo").requires(node(Permissions.NODE_UNDO)).executes(ctx -> undo(ctx.getSource())));
        root.then(literal("purge").requires(node(Permissions.NODE_PURGE))
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
                .then(literal("natural").executes(ctx -> debugNatural(ctx.getSource()))));

        dispatcher.register(root);
        // /coreprotect alias
        dispatcher.register(literal("coreprotect").redirect(dispatcher.getRoot().getChild("co")));
    }

    private static Predicate<CommandSourceStack> node(String permission) {
        return s -> Permissions.has(s, permission);
    }

    // ------------------------------------------------------------------
    // Parameter suggestions
    // ------------------------------------------------------------------

    private static final List<String> LOOKUP_KEYS = List.of("u:", "t:", "a:", "r:", "b:", "e:", "p:");
    private static final List<String> ROLLBACK_KEYS = List.of("u:", "t:", "a:", "r:", "b:", "e:");
    private static final List<String> PURGE_KEYS = List.of("t:");
    private static final List<String> ONLINE_KEYS = List.of("t:", "p:");

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
        String remaining = builder.getRemaining();
        int cursor = input.length() - remaining.length();
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
        Messages.helpLine(source, "coreprotect.help.line.time");
        return 1;
    }

    private static int status(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        DatabaseManager db = mod.database();
        Translator t = mod.translator();
        ServerPlayer p = source.getPlayer();
        String on = t.get(p, "coreprotect.word.on");
        String off = t.get(p, "coreprotect.word.off");
        CoreProtectConfig.Logging log = mod.config().logging;
        Messages.title(source, "coreprotect.status.header");
        Messages.statusLine(source, "coreprotect.status.version", CoreProtectFabric.MOD_VERSION);
        Messages.statusLine(source, "coreprotect.status.database", String.valueOf(db.dbPath()));
        long[] counts = db.counts();
        Messages.statusLine(source, "coreprotect.status.counts",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], counts[6], counts[7]);
        Messages.statusLine(source, "coreprotect.status.language",
                t.defaultLanguage(), String.join(", ", t.available()));
        Messages.statusLine(source, "coreprotect.status.logging",
                log.block ? on : off, log.container ? on : off, log.entity ? on : off,
                log.chat ? on : off, log.command ? on : off, log.session ? on : off,
                log.natural ? on : off, log.hopper ? on : off, log.dispenser ? on : off,
                log.item ? on : off, log.signEdit ? on : off);
        Messages.statusLine(source, "coreprotect.status.permgroups",
                mod.config().permissionGroups.enabled ? on : off, Permissions.groupCount());
        CoreProtectConfig.DataRetention r = mod.config().dataRetention;
        if (r != null && r.enabled && r.maxDays > 0) {
            Messages.statusLine(source, "coreprotect.status.retention.on", r.maxDays);
        } else {
            Messages.statusLine(source, "coreprotect.status.retention.off");
        }
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
        List<DatabaseManager.MessageLog> logs = mod.database().querySessions(c, pageSize, offset);
        List<Component> rows = LookupService.formatOnlineRows(source,
                logs == null ? List.of() : logs);
        if (rows.isEmpty()) {
            Messages.cmd(source, "coreprotect.online.empty", c.user);
            return 1;
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
        if (c.radius > 0 && c.center == null) {
            c.center = source.getPlayer() != null ? source.getPlayer().blockPosition() : BlockPos.ZERO;
        }
        return LookupService.run(source, c);
    }

    private static int rollback(CommandSourceStack source, String params, boolean isRestore) {
        ParamParser.ParseResult parse = ParamParser.parse(params, true);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        if (c.user == null || c.user.isEmpty()) {
            Messages.error(source, "coreprotect.error.missing_user");
            return 0;
        }
        if (c.radius > 0 && c.center == null) {
            c.center = source.getPlayer() != null ? source.getPlayer().blockPosition() : BlockPos.ZERO;
        }
        Messages.cmd(source, isRestore ? "coreprotect.restore.start" : "coreprotect.rollback.start");
        RollbackManager.Summary summary;
        try {
            summary = CoreProtectFabric.instance().rollbackManager().rollback(source, c, isRestore);
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.error("[CoreProtect] Rollback command failed", e);
            Messages.error(source, "coreprotect.error.db", e.toString());
            return 0;
        }
        if (summary == null) {
            Messages.error(source, "coreprotect.rollback.too_many",
                    CoreProtectFabric.instance().config().rollback.maxBlocks);
            return 0;
        }
        if (summary.blocks() == 0 && summary.itemOps() == 0) {
            Messages.cmd(source, "coreprotect.rollback.empty");
            return 1;
        }
        Messages.success(source, isRestore ? "coreprotect.restore.complete" : "coreprotect.rollback.complete",
                summary.blocks(), summary.containers(), summary.itemOps(), summary.skipped());
        return 1;
    }

    private static int undo(CommandSourceStack source) {
        RollbackManager.Summary summary = CoreProtectFabric.instance().rollbackManager().undo(source);
        if (summary == null) {
            Messages.cmd(source, "coreprotect.undo.empty");
            return 0;
        }
        Messages.success(source, "coreprotect.undo.complete", summary.blocks());
        return 1;
    }

    private static int purge(CommandSourceStack source, String params) {
        ParamParser.ParseResult parse = ParamParser.parse(params, false);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        if (c.time <= 0) {
            Messages.error(source, "coreprotect.purge.require_time");
            return 0;
        }
        long rows = CoreProtectFabric.instance().database().purge(c.time);
        Messages.success(source, "coreprotect.purge.complete", rows);
        // compact the database file in the background after deleting old data
        CoreProtectFabric.instance().database().vacuumAsync();
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        mod.config().load();
        mod.translator().load(mod.config().language);
        Messages.success(source, "coreprotect.reload.complete");
        return 1;
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
            mod.database().saveUserLanguageAsync(p.getGameProfile().name(), null);
            Messages.cmd(source, "coreprotect.language.reset");
            return 1;
        }
        if (!t.isAvailable(code)) {
            Messages.error(source, "coreprotect.error.unknown_language", code);
            return 0;
        }
        t.setOverride(p.getUUID(), code);
        mod.database().saveUserLanguageAsync(p.getGameProfile().name(), code);
        Messages.cmd(source, "coreprotect.language.set", code);
        return 1;
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
        // 1.21.9+ / 26.x: chunks no longer tick without players. Force-load the whole test
        // area BEFORE placing anything, otherwise setBlock on an unloaded chunk is dropped.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                world.setChunkForced((ground.getX() >> 4) + cx, (ground.getZ() >> 4) + cz, true);
                world.getChunk((ground.getX() >> 4) + cx, (ground.getZ() >> 4) + cz); // load synchronously
            }
        }
        // fire burns the wool it sits on (kept far from the TNT scenario)
        BlockPos fireBase = ground.offset(-8, 0, 8);
        world.setBlock(fireBase, Blocks.WHITE_WOOL.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(fireBase.above(), Blocks.FIRE.defaultBlockState(), Block.UPDATE_ALL);
        // flowing water washes away the torch (kept far from the fire scenario)
        BlockPos waterBase = ground.offset(-5, 0, -5);
        world.setBlock(waterBase.above(), Blocks.TORCH.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(waterBase.above(2), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        // direct TNT-style explosion destroys the surrounding grass
        BlockPos boom = ground.offset(6, 1, 0);
        PrimedTnt tnt = new PrimedTnt(world, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, null);
        world.addFreshEntity(tnt);
        world.explode(tnt, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, 4.0F,
                Level.ExplosionInteraction.TNT);
        // hopper above a chest: its contents are pushed into the chest -> #hopper transactions
        BlockPos hopperChest = ground.offset(0, 0, 10);
        world.setBlock(hopperChest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(hopperChest.above(), Blocks.HOPPER.defaultBlockState(), Block.UPDATE_ALL);
        BlockEntity hopperBe = world.getBlockEntity(hopperChest.above());
        if (hopperBe instanceof HopperBlockEntity hopper) {
            hopper.setItem(0, new ItemStack(Items.DIAMOND, 3));
        }
        // powered dropper: ejects its contents forward -> #dropper transactions
        BlockPos dropperPos = ground.offset(0, 0, -10);
        world.setBlock(dropperPos, Blocks.DROPPER.defaultBlockState()
                .setValue(DispenserBlock.FACING, Direction.UP), Block.UPDATE_ALL);
        world.setBlock(dropperPos.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        BlockEntity dropperBe = world.getBlockEntity(dropperPos);
        if (dropperBe instanceof DropperBlockEntity dropper) {
            dropper.setItem(0, new ItemStack(Items.EMERALD, 4));
        }
        // powered dispenser: shoots arrows upward -> #dispenser transactions
        BlockPos dispenserPos = ground.offset(0, 0, -11);
        world.setBlock(dispenserPos, Blocks.DISPENSER.defaultBlockState()
                .setValue(DispenserBlock.FACING, Direction.UP), Block.UPDATE_ALL);
        world.setBlock(dispenserPos.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        BlockEntity dispenserBe = world.getBlockEntity(dispenserPos);
        if (dispenserBe instanceof DispenserBlockEntity dispenser) {
            dispenser.setItem(0, new ItemStack(Items.ARROW, 3));
        }
        // piston pushing a wool block (in the air, so the push always succeeds) -> #piston records
        BlockPos pistonBase = CoreProtectFabric.instance().server().getRespawnData().pos().offset(0, 1, 20);
        world.setBlock(pistonBase, Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        world.setBlock(pistonBase.north(), Blocks.WHITE_WOOL.defaultBlockState(), Block.UPDATE_ALL);
        world.setBlock(pistonBase.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        Messages.cmd(source, "coreprotect.debug.natural_started", fireBase.getX(), fireBase.getZ());
        return 1;
    }
}
