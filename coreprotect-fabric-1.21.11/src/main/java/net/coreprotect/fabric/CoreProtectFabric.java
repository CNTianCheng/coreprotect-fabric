package net.coreprotect.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import net.coreprotect.fabric.command.CoCommand;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.coreprotect.fabric.container.ContainerTracker;
import net.coreprotect.fabric.container.DispenserTracker;
import net.coreprotect.fabric.container.HopperTracker;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.event.BlockEventListener;
import net.coreprotect.fabric.event.EntityEventListener;
import net.coreprotect.fabric.event.MessageEventListener;
import net.coreprotect.fabric.event.SessionEventListener;
import net.coreprotect.fabric.i18n.Translator;
import net.coreprotect.fabric.inspect.Inspector;
import net.coreprotect.fabric.rollback.RollbackManager;
import net.coreprotect.fabric.util.BStatsMetrics;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.NaturalBreakCause;
import net.coreprotect.fabric.util.TimeUtil;
import net.coreprotect.fabric.util.UpdateChecker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreProtectFabric implements ModInitializer {
    public static final String MOD_ID = "coreprotect";
    public static final String MOD_VERSION = "1.8.1";
    public static final Logger LOGGER = LoggerFactory.getLogger("CoreProtect");

    /**
     * Natural causes that reach us through {@code World#setBlockState} (explosions,
     * endermen, pistons). Causes that reach us through {@code breakBlock}/{@code removeBlock}
     * (fire, water/lava, leaf decay) are in {@link #BREAK_CAUSES}.
     */
    private static final Set<String> SET_STATE_CAUSES = Set.of(
            "#explosion", "#creeper", "#tnt", "#wither", "#end_crystal", "#enderman", "#piston",
            "#water", "#lava", "#fluid");
    private static final Set<String> BREAK_CAUSES = Set.of(
            "#fire", "#water", "#lava", "#fluid", "#decay", "#enderman", "#piston");
    private static final Set<String> EXPLOSION_CAUSES = Set.of(
            "#explosion", "#creeper", "#tnt", "#wither", "#end_crystal");
    private static final Set<String> FLUID_CAUSES = Set.of("#water", "#lava", "#fluid");

    private static CoreProtectFabric instance;

    private MinecraftServer server;
    private final CoreProtectConfig config = new CoreProtectConfig();
    private final DatabaseManager database = new DatabaseManager();
    private final Translator translator = new Translator();
    private final Inspector inspector = new Inspector();
    private final ContainerTracker containerTracker = new ContainerTracker();
    private final HopperTracker hopperTracker = new HopperTracker();
    private final DispenserTracker dispenserTracker = new DispenserTracker();
    private final RollbackManager rollbackManager = new RollbackManager();
    private BStatsMetrics bStatsMetrics;
    private UpdateChecker updateChecker;

    public static CoreProtectFabric instance() {
        return instance;
    }

    public MinecraftServer server() {
        return server;
    }

    public CoreProtectConfig config() {
        return config;
    }

    public DatabaseManager database() {
        return database;
    }

    public Translator translator() {
        return translator;
    }

    public Inspector inspector() {
        return inspector;
    }

    public ContainerTracker containerTracker() {
        return containerTracker;
    }

    public HopperTracker hopperTracker() {
        return hopperTracker;
    }

    public DispenserTracker dispenserTracker() {
        return dispenserTracker;
    }

    public RollbackManager rollbackManager() {
        return rollbackManager;
    }

    public UpdateChecker updateChecker() {
        return updateChecker;
    }

    @Override
    public void onInitialize() {
        instance = this;
        BlockEventListener.register();
        EntityEventListener.register();
        MessageEventListener.register();
        SessionEventListener.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CoCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> onServerStopping());
        LOGGER.info("[CoreProtect] Fabric version {} initialized.", MOD_VERSION);
    }

    private void onServerStarted(MinecraftServer srv) {
        this.server = srv;
        // Crash detection: the marker only exists while the server is running. If it is
        // still there from the previous run, the last shutdown was not clean (crash).
        Path crashMarker = FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric.crash-marker");
        boolean uncleanShutdown = Files.exists(crashMarker);
        if (uncleanShutdown) {
            LOGGER.warn("[CoreProtect] Previous shutdown was not clean (server crash detected); verifying the database...");
        }
        this.config.load();
        this.translator.load(this.config.language);
        this.database.open();
        if (uncleanShutdown) {
            this.database.quickCheck();
        }
        try {
            Files.writeString(crashMarker, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            LOGGER.warn("[CoreProtect] Failed to write the crash marker", e);
        }
        LOGGER.info("[CoreProtect] Enabled. Database: {}, default language: {}",
                this.database.dbPath(), this.config.language);
        CoreProtectConfig.DataRetention r = this.config.dataRetention;
        if (r != null && r.enabled && r.maxDays > 0) {
            long before = TimeUtil.now() - r.maxDays * 86400L;
            LOGGER.info("[CoreProtect] Data retention enabled: deleting rows older than {} day(s).", r.maxDays);
            this.database.purgeAsync(before, r.maxDays);
        }
        this.bStatsMetrics = new BStatsMetrics(this);
        this.bStatsMetrics.start();
        this.updateChecker = new UpdateChecker();
        this.updateChecker.start();
    }

    private void onServerStopping() {
        if (this.bStatsMetrics != null) {
            this.bStatsMetrics.shutdown();
        }
        if (this.updateChecker != null) {
            this.updateChecker.shutdown();
        }
        this.containerTracker.finalizeAll();
        this.database.close();
        // Graceful shutdown: remove the crash marker so the next start knows everything was closed cleanly.
        try {
            Files.deleteIfExists(FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric.crash-marker"));
        } catch (Exception e) {
            LOGGER.warn("[CoreProtect] Failed to remove the crash marker", e);
        }
        this.server = null;
        LOGGER.info("[CoreProtect] Disabled.");
    }

    // ------------------------------------------------------------------
    // Mixin entry points for natural (non-player) block changes
    // ------------------------------------------------------------------

    public static void hookSetBlockState(World world, BlockPos pos, BlockState newState) {
        CoreProtectFabric mod = instance;
        if (mod == null || world.isClient() || mod.server == null) return;
        if (!mod.config.logging.natural) return;
        NaturalBreakCause.Cause cause = NaturalBreakCause.get();
        if (cause == null || !SET_STATE_CAUSES.contains(cause.type)) return;
        if (cause.lastBreakPos != null && cause.lastBreakPos.equals(pos)) return;
        if (cause.lastSetPos != null && cause.lastSetPos.equals(pos)) return;
        BlockState oldState = world.getBlockState(pos);
        if (oldState.equals(newState)) return;
        // Piston mechanism noise: the head extending into air and the moving ghost /
        // head states themselves are not meaningful records ("piston changed air").
        if ("#piston".equals(cause.type)
                && (oldState.isAir() || isPistonPart(oldState) || isPistonPart(newState))) return;
        // Explosions also place fire inside this window; only their removals are logged.
        if (EXPLOSION_CAUSES.contains(cause.type) && !newState.isAir()) return;
        // Water/lava flow constantly replaces air and fluid states; only actual block destruction is logged.
        if (FLUID_CAUSES.contains(cause.type)
                && (oldState.isAir() || oldState.getBlock() instanceof FluidBlock)) return;
        cause.lastSetPos = pos;
        mod.database.insertBlockAsync(mod.naturalLog(world, pos, oldState, newState, cause.type));
    }

    public static void hookBreakBlock(World world, BlockPos pos) {
        CoreProtectFabric mod = instance;
        if (mod == null || world.isClient() || mod.server == null) return;
        if (!mod.config.logging.natural) return;
        NaturalBreakCause.Cause cause = NaturalBreakCause.get();
        if (cause == null || !BREAK_CAUSES.contains(cause.type)) return;
        if (cause.lastBreakPos != null && cause.lastBreakPos.equals(pos)) return;
        BlockState oldState = world.getBlockState(pos);
        if (oldState.isAir()) return;
        // Fire spread also removes adjacent fire blocks; only real block burns are logged.
        if ("#fire".equals(cause.type) && oldState.getBlock() instanceof AbstractFireBlock) return;
        // Piston head / moving ghost removal is mechanism noise, not a real block change.
        if ("#piston".equals(cause.type) && isPistonPart(oldState)) return;
        cause.lastBreakPos = pos;
        mod.database.insertBlockAsync(mod.naturalLog(world, pos, oldState, Blocks.AIR.getDefaultState(), cause.type));
    }

    private static boolean isPistonPart(BlockState state) {
        return state.getBlock() == Blocks.PISTON_HEAD || state.getBlock() == Blocks.MOVING_PISTON;
    }

    /** Each PistonBlockEntity performs exactly one movement; log it only once. */
    private static final java.util.WeakHashMap<PistonBlockEntity, Boolean> PISTON_LOGGED = new java.util.WeakHashMap<>();

    /**
     * Logs the block moved by a piston as two #piston records when the movement
     * completes: removed at the source, placed at the destination.
     */
    public static void logPistonMove(World world, BlockPos pos, BlockState state, PistonBlockEntity blockEntity) {
        CoreProtectFabric mod = instance;
        if (mod == null || world.isClient() || mod.server == null) return;
        if (!mod.config.logging.natural) return;
        // getProgress interpolates: tickDelta 0 = last tick's progress, 1 = current.
        // Log exactly on the tick that crosses 1.0; the cleanup tick repeats 1.0.
        if (blockEntity.getProgress(0.0f) >= 1.0f || blockEntity.getProgress(1.0f) < 1.0f) return;
        if (PISTON_LOGGED.put(blockEntity, Boolean.TRUE) != null) return;
        if (blockEntity.isSource()) return; // head-only movement (no block pushed)
        BlockState moved = blockEntity.getPushedBlock();
        if (moved == null || moved.isAir()) return;
        Direction facing = state.get(PistonBlock.FACING);
        boolean extending = blockEntity.isExtending();
        BlockPos from = extending ? pos.offset(facing) : pos.offset(facing, 2);
        BlockPos to = extending ? pos.offset(facing, 2) : pos.offset(facing);
        String wid = world.getRegistryKey().getValue().toString();
        long time = TimeUtil.now();
        mod.database.insertBlockAsync(new DatabaseManager.BlockLog(
                0L, time, "#piston", wid,
                from.getX(), from.getY(), from.getZ(),
                DatabaseManager.TYPE_NATURAL, BlockStateUtil.stringify(moved),
                BlockStateUtil.stringify(Blocks.AIR.getDefaultState()), "#piston", null));
        mod.database.insertBlockAsync(new DatabaseManager.BlockLog(
                0L, time, "#piston", wid,
                to.getX(), to.getY(), to.getZ(),
                DatabaseManager.TYPE_NATURAL, BlockStateUtil.stringify(Blocks.AIR.getDefaultState()),
                BlockStateUtil.stringify(moved), "#piston", null));
    }

    private DatabaseManager.BlockLog naturalLog(World world, BlockPos pos, BlockState oldState, BlockState newState, String cause) {
        return new DatabaseManager.BlockLog(
                0L, TimeUtil.now(), cause,
                world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                2, BlockStateUtil.stringify(oldState), BlockStateUtil.stringify(newState),
                cause, null);
    }
}
