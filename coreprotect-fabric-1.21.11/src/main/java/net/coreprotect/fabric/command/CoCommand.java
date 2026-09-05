package net.coreprotect.fabric.command;

import java.util.function.Predicate;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.i18n.Translator;
import net.coreprotect.fabric.rollback.RollbackManager;
import net.coreprotect.fabric.util.Messages;
import net.minecraft.block.BlockState;
import net.minecraft.command.permission.PermissionCheck;
import net.minecraft.entity.TntEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class CoCommand {

    private CoCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        CoreProtectConfig.Permissions perm = CoreProtectFabric.instance().config().permissions;
        Predicate<ServerCommandSource> lookupLevel = CommandManager.requirePermissionLevel(levelCheck(perm.lookupLevel));
        Predicate<ServerCommandSource> adminLevel = CommandManager.requirePermissionLevel(levelCheck(perm.adminLevel));

        LiteralArgumentBuilder<ServerCommandSource> root = literal("co");
        root.then(literal("help").requires(lookupLevel).executes(ctx -> help(ctx.getSource())));
        root.then(literal("status").requires(lookupLevel).executes(ctx -> status(ctx.getSource())));
        root.then(literal("inspect").requires(lookupLevel).executes(ctx -> inspect(ctx.getSource())));
        root.then(literal("lookup").requires(lookupLevel)
                .executes(ctx -> lookup(ctx.getSource(), ""))
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> lookup(ctx.getSource(), StringArgumentType.getString(ctx, "params")))));
        root.then(literal("rollback").requires(adminLevel)
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), false))));
        root.then(literal("restore").requires(adminLevel)
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> rollback(ctx.getSource(), StringArgumentType.getString(ctx, "params"), true))));
        root.then(literal("undo").requires(adminLevel).executes(ctx -> undo(ctx.getSource())));
        root.then(literal("purge").requires(adminLevel)
                .then(argument("params", StringArgumentType.greedyString())
                        .executes(ctx -> purge(ctx.getSource(), StringArgumentType.getString(ctx, "params")))));
        root.then(literal("reload").requires(adminLevel).executes(ctx -> reload(ctx.getSource())));
        root.then(literal("language").requires(lookupLevel)
                .executes(ctx -> languageList(ctx.getSource()))
                .then(literal("list").executes(ctx -> languageList(ctx.getSource())))
                .then(argument("code", StringArgumentType.word())
                        .executes(ctx -> languageSet(ctx.getSource(), StringArgumentType.getString(ctx, "code")))));
        root.then(literal("debug").requires(adminLevel)
                .then(literal("natural").executes(ctx -> debugNatural(ctx.getSource()))));

        dispatcher.register(root);
        // /coreprotect alias
        dispatcher.register(literal("coreprotect").redirect(dispatcher.getRoot().getChild("co")));
    }

    private static PermissionCheck levelCheck(int level) {
        return switch (level) {
            case 4 -> CommandManager.OWNERS_CHECK;
            case 3 -> CommandManager.ADMINS_CHECK;
            case 2 -> CommandManager.GAMEMASTERS_CHECK;
            case 1 -> CommandManager.MODERATORS_CHECK;
            default -> CommandManager.ALWAYS_PASS_CHECK;
        };
    }

    private static int help(ServerCommandSource source) {
        Messages.header(source, "coreprotect.help.header");
        Messages.plain(source, "coreprotect.help.line.help");
        Messages.plain(source, "coreprotect.help.line.inspect");
        Messages.plain(source, "coreprotect.help.line.lookup");
        Messages.plain(source, "coreprotect.help.line.rollback");
        Messages.plain(source, "coreprotect.help.line.restore");
        Messages.plain(source, "coreprotect.help.line.undo");
        Messages.plain(source, "coreprotect.help.line.purge");
        Messages.plain(source, "coreprotect.help.line.reload");
        Messages.plain(source, "coreprotect.help.line.status");
        Messages.plain(source, "coreprotect.help.line.language");
        Messages.plain(source, "coreprotect.help.line.time");
        return 1;
    }

    private static int status(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        DatabaseManager db = mod.database();
        Translator t = mod.translator();
        ServerPlayerEntity p = source.getPlayer();
        String on = t.get(p, "coreprotect.word.on");
        String off = t.get(p, "coreprotect.word.off");
        CoreProtectConfig.Logging log = mod.config().logging;
        Messages.header(source, "coreprotect.status.header", CoreProtectFabric.MOD_VERSION);
        Messages.plain(source, "coreprotect.status.database", mod.config().databaseFile);
        Messages.plain(source, "coreprotect.status.counts",
                db.count("co_block"), db.count("co_container"), db.count("co_entity"),
                db.count("co_session"), db.count("co_command"), db.count("co_chat"));
        Messages.plain(source, "coreprotect.status.language",
                t.defaultLanguage(), String.join(", ", t.available()));
        Messages.plain(source, "coreprotect.status.logging",
                log.block ? on : off, log.container ? on : off, log.entity ? on : off,
                log.chat ? on : off, log.command ? on : off, log.session ? on : off,
                log.natural ? on : off);
        return 1;
    }

    private static int inspect(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        boolean on = CoreProtectFabric.instance().inspector().toggle(player);
        Messages.cmd(source, on ? "coreprotect.inspect.on" : "coreprotect.inspect.off");
        return 1;
    }

    private static int lookup(ServerCommandSource source, String params) {
        ParamParser.ParseResult parse = ParamParser.parse(params, true);
        if (parse.errorKey() != null) {
            Messages.error(source, parse.errorKey(), (Object[]) parse.errorArgs());
            return 0;
        }
        Criteria c = parse.criteria();
        if (c.radius > 0 && c.center == null) {
            c.center = source.getPlayer() != null ? source.getPlayer().getBlockPos() : BlockPos.ORIGIN;
        }
        return LookupService.run(source, c);
    }

    private static int rollback(ServerCommandSource source, String params, boolean isRestore) {
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
            c.center = source.getPlayer() != null ? source.getPlayer().getBlockPos() : BlockPos.ORIGIN;
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
        Messages.cmd(source, isRestore ? "coreprotect.restore.complete" : "coreprotect.rollback.complete",
                summary.blocks(), summary.containers(), summary.itemOps(), summary.skipped());
        return 1;
    }

    private static int undo(ServerCommandSource source) {
        RollbackManager.Summary summary = CoreProtectFabric.instance().rollbackManager().undo(source);
        if (summary == null) {
            Messages.cmd(source, "coreprotect.undo.empty");
            return 0;
        }
        Messages.cmd(source, "coreprotect.undo.complete", summary.blocks());
        return 1;
    }

    private static int purge(ServerCommandSource source, String params) {
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
        Messages.cmd(source, "coreprotect.purge.complete", rows);
        return 1;
    }

    private static int reload(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        mod.config().load();
        mod.translator().load(mod.config().language);
        Messages.cmd(source, "coreprotect.reload.complete");
        return 1;
    }

    private static int languageList(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerPlayerEntity p = source.getPlayer();
        Messages.cmd(source, "coreprotect.language.current", mod.translator().localeOf(p));
        Messages.cmd(source, "coreprotect.language.list", String.join(", ", mod.translator().available()));
        return 1;
    }

    private static int languageSet(ServerCommandSource source, String code) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        if (code.equalsIgnoreCase("list")) {
            return languageList(source);
        }
        ServerPlayerEntity p = source.getPlayer();
        if (p == null) {
            Messages.error(source, "coreprotect.error.player_only");
            return 0;
        }
        Translator t = mod.translator();
        if (code.equalsIgnoreCase("auto") || code.equalsIgnoreCase("default")) {
            t.setOverride(p.getUuid(), null);
            mod.database().saveUserLanguageAsync(p.getGameProfile().name(), null);
            Messages.cmd(source, "coreprotect.language.reset");
            return 1;
        }
        if (!t.isAvailable(code)) {
            Messages.error(source, "coreprotect.error.unknown_language", code);
            return 0;
        }
        t.setOverride(p.getUuid(), code);
        mod.database().saveUserLanguageAsync(p.getGameProfile().name(), code);
        Messages.cmd(source, "coreprotect.language.set", code);
        return 1;
    }

    /**
     * Places fire/water/explosion scenarios to verify that natural block
     * events are logged (#fire, #water, #tnt). Useful after installation.
     */
    private static int debugNatural(ServerCommandSource source) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        ServerWorld world = mod.server().getOverworld();
        // Place scenarios around the world spawn.
        BlockPos ground = CoreProtectFabric.instance().server().getSpawnPoint().getPos().down();
        BlockPos fireBase = ground.add(5, 0, 5);
        BlockPos waterBase = ground.add(-5, 0, -5);
        BlockPos boom = ground.add(6, 1, 0);
        // 1.21.9+: chunks no longer tick without players, so force-load them for the test.
        world.setChunkForced(fireBase.getX() >> 4, fireBase.getZ() >> 4, true);
        world.setChunkForced(waterBase.getX() >> 4, waterBase.getZ() >> 4, true);
        // fire burns the wool above it
        world.setBlockState(fireBase, net.minecraft.block.Blocks.NETHERRACK.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
        world.setBlockState(fireBase.up(), net.minecraft.block.Blocks.FIRE.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
        world.setBlockState(fireBase.up(2), net.minecraft.block.Blocks.WHITE_WOOL.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
        // flowing water washes away the torch (kept far from the fire scenario)
        world.setBlockState(waterBase.up(), net.minecraft.block.Blocks.TORCH.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
        world.setBlockState(waterBase.up(2), net.minecraft.block.Blocks.WATER.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
        // direct TNT-style explosion destroys the surrounding grass
        TntEntity tnt = new TntEntity(world, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, null);
        world.spawnEntity(tnt);
        world.createExplosion(tnt, boom.getX() + 0.5, boom.getY(), boom.getZ() + 0.5, 4.0F,
                World.ExplosionSourceType.TNT);
        Messages.cmd(source, "coreprotect.debug.natural_started", fireBase.getX(), fireBase.getZ());
        return 1;
    }
}
