package net.coreprotect.fabric;

import java.util.Set;

import net.coreprotect.fabric.command.CoCommand;
import net.coreprotect.fabric.config.CoreProtectConfig;
import net.coreprotect.fabric.container.ContainerTracker;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.event.BlockEventListener;
import net.coreprotect.fabric.event.EntityEventListener;
import net.coreprotect.fabric.event.MessageEventListener;
import net.coreprotect.fabric.event.SessionEventListener;
import net.coreprotect.fabric.i18n.Translator;
import net.coreprotect.fabric.inspect.Inspector;
import net.coreprotect.fabric.rollback.RollbackManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.NaturalBreakCause;
import net.coreprotect.fabric.util.TimeUtil;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreProtectFabric implements ModInitializer {
    public static final String MOD_ID = "coreprotect";
    public static final String MOD_VERSION = "1.0.0";
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
    private final RollbackManager rollbackManager = new RollbackManager();

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

    public RollbackManager rollbackManager() {
        return rollbackManager;
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
        this.config.load();
        this.translator.load(this.config.language);
        this.database.open();
        LOGGER.info("[CoreProtect] Enabled. Database: {}, default language: {}",
                this.config.databaseFile, this.config.language);
    }

    private void onServerStopping() {
        this.containerTracker.finalizeAll();
        this.database.close();
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
        cause.lastBreakPos = pos;
        mod.database.insertBlockAsync(mod.naturalLog(world, pos, oldState, Blocks.AIR.getDefaultState(), cause.type));
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
