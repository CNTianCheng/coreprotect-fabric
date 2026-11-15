package net.coreprotect.fabric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import net.coreprotect.fabric.util.ItemStackCodec;
import net.coreprotect.fabric.util.NaturalBreakCause;
import net.coreprotect.fabric.util.TimeUtil;
import net.coreprotect.fabric.util.UpdateChecker;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreProtectFabric implements ModInitializer {
    public static final String MOD_ID = "coreprotect";
    public static final String MOD_VERSION = "1.9.4";
    public static final Logger LOGGER = LoggerFactory.getLogger("CoreProtect");

    /**
     * Natural causes that reach us through {@code World#setBlockState}: explosions,
     * endermen, pistons, fluid flow, fire, falling blocks, random ticks and trampled
     * farmland. Fire belongs here as well as in {@link #BREAK_CAUSES} because
     * {@code FireBlock} burns fuel by REPLACING the block through {@code setBlockState}
     * (only the removal of the fire blocks themselves goes through {@code removeBlock}).
     * Causes that only ever remove a block (leaf decay) stay in {@link #BREAK_CAUSES} only
     * — listing one in both sets would log every removal twice.
     */
    private static final Set<String> SET_STATE_CAUSES = Set.of(
            "#explosion", "#creeper", "#tnt", "#wither", "#end_crystal", "#enderman", "#piston",
            "#water", "#lava", "#fluid", "#fire",
            // L3-12 (falling blocks), L3-18 (random ticks), L3-14 (trampled farmland).
            "#gravity", "#random_tick", "#trample");
    private static final Set<String> BREAK_CAUSES = Set.of(
            "#fire", "#water", "#lava", "#fluid", "#decay", "#enderman", "#piston",
            // A random tick or a falling block can also remove its block through
            // World#breakBlock/removeBlock; the E6 duplicate check keeps that single-logged.
            "#gravity", "#random_tick", "#trample");
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
        // Closing the database only after every disconnect/stop callback has run keeps
        // session-leave rows and open-container diffs from being dropped.
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        LOGGER.info("[CoreProtect] Fabric version {} initialized.", MOD_VERSION);
    }

    private static Path crashMarkerPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric.crash-marker");
    }

    private void onServerStarted(MinecraftServer srv) {
        this.server = srv;
        // Crash detection: the marker only exists while the server is running. It is written
        // FIRST so a crash during start-up (config/database init) is still detected, and it is
        // only deleted in SERVER_STOPPED, i.e. after a clean shutdown completed.
        Path crashMarker = crashMarkerPath();
        boolean uncleanShutdown = Files.exists(crashMarker);
        try {
            Files.writeString(crashMarker, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            LOGGER.warn("[CoreProtect] Failed to write the crash marker", e);
        }
        if (uncleanShutdown) {
            LOGGER.warn("[CoreProtect] Previous shutdown was not clean (server crash detected); verifying the database...");
        }
        this.config.load();
        this.translator.load(this.config.language);
        this.database.open();
        if (uncleanShutdown) {
            this.database.quickCheck();
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
        // containers are finalized here while the world is still available; the database
        // itself is closed in onServerStopped() so the disconnect callbacks still land
        this.containerTracker.finalizeAll();
    }

    /** Runs after the world and all players are gone: flush the database and clear the marker. */
    private void onServerStopped(MinecraftServer srv) {
        this.database.close();
        try {
            Files.deleteIfExists(crashMarkerPath());
        } catch (Exception e) {
            LOGGER.warn("[CoreProtect] Failed to remove the crash marker", e);
        }
        this.server = null;
        LOGGER.info("[CoreProtect] Disabled.");
    }

    // ------------------------------------------------------------------
    // Packet-level audit hooks (commands, chat, sign edits)
    // ------------------------------------------------------------------

    /**
     * The record kinds driven by client packets. Each kind keeps its own budget (L3-09):
     * a client that edits signs in a loop must not squeeze command records out of the same
     * quota, and chat needs a limit of its own (L3-08). Every dropped record is counted so
     * {@code /co status} shows it instead of silently losing rows.
     */
    public enum LogKind {
        /** Chat messages; vanilla sends at most one per tick, a modified client does not. */
        CHAT(40),
        /** Commands. */
        COMMAND(60),
        /** Sign text edits. */
        SIGN(60);

        private final int limit;

        LogKind(int limit) {
            this.limit = limit;
        }
    }

    private static final long PACKET_LOG_WINDOW_MS = 60_000L;
    /** Tracked players before expired windows are pruned again. */
    private static final int PACKET_LOG_MAX_TRACKED = 256;
    /** Longest stored command or chat line; longer payloads are truncated (L3-07, L3-08). */
    private static final int MAX_TEXT_LENGTH = 256;

    /** Per kind and player {@code {windowStart, records}} counters. */
    private static final Map<LogKind, Map<UUID, long[]>> PACKET_LOG_WINDOWS = new ConcurrentHashMap<>();

    /**
     * Rate limit for the packet-driven hooks (B8). They are fed by client packets, so a
     * modified client could otherwise fill the audit tables with records that no vanilla
     * action backs. Returns {@code false} for records that must be dropped; the drop is
     * counted in the database, so it is visible in {@code /co status} (L3-09).
     */
    public static boolean allowPacketLog(UUID playerId, LogKind kind) {
        if (playerId == null || kind == null) return true;
        long now = System.currentTimeMillis();
        Map<UUID, long[]> windows = PACKET_LOG_WINDOWS.computeIfAbsent(kind, key -> new ConcurrentHashMap<>());
        if (windows.size() > PACKET_LOG_MAX_TRACKED) {
            windows.values().removeIf(window -> now - window[0] >= PACKET_LOG_WINDOW_MS);
        }
        long[] window = windows.computeIfAbsent(playerId, key -> new long[]{now, 0L});
        synchronized (window) {
            if (now - window[0] >= PACKET_LOG_WINDOW_MS) {
                window[0] = now;
                window[1] = 0L;
            }
            window[1]++;
            if (window[1] <= kind.limit) return true;
            if (window[1] == kind.limit + 1L) {
                LOGGER.warn("[CoreProtect] Throttling {} records for a player: more than {} entries per minute.",
                        kind.name().toLowerCase(Locale.ROOT), kind.limit);
            }
        }
        dropRecord();
        return false;
    }

    /** Counts one record this side threw away, so {@code /co status} is not blind (L3-09). */
    private static void dropRecord() {
        CoreProtectFabric mod = instance;
        if (mod == null) return;
        mod.database.countDroppedRow();
    }

    /**
     * Flattens a command or chat line before it is stored: embedded line breaks would break
     * the log layout and an unbounded length lets a client inflate every row (L3-07, L3-08).
     */
    public static String sanitizeText(String text) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= MAX_TEXT_LENGTH ? flat : flat.substring(0, MAX_TEXT_LENGTH);
    }

    // ------------------------------------------------------------------
    // Command hook (L3-20 / L5-03 / L3-07)
    // ------------------------------------------------------------------

    /**
     * Records a command where the command manager really executes it: {@code CommandManager#execute}
     * is the single point every source funnels through (console, RCON, command blocks, sign
     * commands and both player packet paths). Hooking the packet handlers instead recorded
     * commands that were rejected afterwards (L5-03: the signed path bails out on a chat-chain
     * failure, and tryHandleChat only queues) and missed every non-player source (L3-20).
     * Datapack functions do not pass here (they run through the function manager), so a
     * function that runs hundreds of commands cannot flood the table.
     *
     * <p>Commands on the config blacklist are skipped entirely - not stored and not counted
     * (L3-07). Player commands keep the per-player rate limit; console/RCON/command-block
     * commands are trusted.</p>
     */
    public static void hookCommandExecution(ServerCommandSource source, String command) {
        try {
            CoreProtectFabric mod = instance;
            if (mod == null || source == null || command == null) return;
            if (!mod.config.logging.command) return;
            if (!mod.config.shouldLogCommand(command)) return;
            String name;
            ServerPlayerEntity player = source.getPlayer();
            if (player != null) {
                if (!allowPacketLog(player.getUuid(), LogKind.COMMAND)) return;
                name = player.getGameProfile().getName();
            } else {
                name = source.getName();
                if (name == null || name.isBlank()) {
                    name = "#server";
                }
            }
            // the world the command was run in (L3-21): console/RCON commands carry the
            // overworld, player commands the dimension they stood in
            String wid = source.getWorld().getRegistryKey().getValue().toString();
            mod.database.insertCommandAsync(TimeUtil.now(), name, wid, sanitizeText(command));
        } catch (Throwable t) {
            // This runs inside the command manager; a logging failure must never break a command.
            LOGGER.warn("[CoreProtect] Failed to log a command: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // Mixin entry points for natural (non-player) block changes
    // ------------------------------------------------------------------

    public static void hookSetBlockState(World world, BlockPos pos, BlockState newState) {
        hookSetBlockState(world, pos, newState, 0);
    }

    /**
     * @param flags the vanilla {@code Block} flags of the write. A write carrying
     *              {@code Block#MOVED} is part of a piston move: the movement itself is
     *              recorded from the piston block entity (with the correct source and
     *              destination), so the source cell being cleared here stays silent - logging
     *              it as well would produce the same change twice (L3-01/L2-11).
     */
    public static void hookSetBlockState(World world, BlockPos pos, BlockState newState, int flags) {
        try {
            hookSetBlockStateInternal(world, pos, newState, flags);
        } catch (Throwable t) {
            // E16: this hook runs inside vanilla world code. A logging failure must never
            // abort the vanilla write, and must not skip the mixin RETURN that clears the
            // cause window either.
            LOGGER.warn("[CoreProtect] Failed to log a natural setBlockState at {}: {}", pos, t.toString());
        }
    }

    private static void hookSetBlockStateInternal(World world, BlockPos pos, BlockState newState, int flags) {
        CoreProtectFabric mod = instance;
        if (mod == null || world.isClient() || mod.server == null) return;
        if (!mod.config.logging.natural) return;
        if ((flags & Block.MOVED) != 0) return;
        NaturalBreakCause.Cause cause = NaturalBreakCause.get();
        if (cause == null || !SET_STATE_CAUSES.contains(cause.type)) return;
        // E11: the hook runs at HEAD because it needs the state BEFORE the write (at RETURN
        // World#getBlockState already returns the new state). The writes vanilla rejects in
        // that case have to be filtered here, otherwise they produce records for changes that
        // never happened: outside the load limit / in a debug world / a null state.
        // (The remaining rejection - WorldChunk#setBlockState returning null - only happens
        // for air-into-empty-section writes, which the "states are equal" check below drops.)
        if (newState == null || !world.isInBuildLimit(pos) || world.isDebugWorld()) return;
        BlockState oldState = world.getBlockState(pos);
        if (oldState.equals(newState)) return;
        // state-only changes (water level, piston extension, dropper "triggered", ...) are not
        // block changes; the same block type in and out is noise for a rollback log
        if (oldState.getBlock() == newState.getBlock()) return;
        // Delegated overloads of setBlockState report the same change twice; a genuine
        // second change at the same position (different states) is still logged.
        if (cause.alreadyLoggedSet(pos, oldState, newState)) return;
        // E6: World#removeBlock first fires the break hook and then delegates to setBlockState,
        // so the same removal reached both ledgers and was recorded twice. A delegated write
        // that is not air (the fluid state of a waterlogged block, ...) is a real second
        // change and stays logged.
        if (newState.isAir() && cause.alreadyLoggedBreak(pos, oldState)) return;
        // Piston mechanism noise: the head extending into air and the moving ghost /
        // head states themselves are not meaningful records ("piston changed air").
        if ("#piston".equals(cause.type)
                && (oldState.isAir() || isPistonPart(oldState) || isPistonPart(newState))) return;
        // Explosions also place fire inside this window; only their removals are logged.
        if (EXPLOSION_CAUSES.contains(cause.type) && !newState.isAir()) return;
        // Fire: a fire block is never a record of its own. Spreading into air (the common case
        // of FireBlock#scheduledTick) and a fire that burns out / is replaced are noise; only a
        // burn that consumes a real block (wood -> fire) is kept, and the "-> air" variant of a
        // burn goes through removeBlock, where the break hook records it and the E6 check above
        // suppresses the duplicate.
        if ("#fire".equals(cause.type)) {
            if (oldState.getBlock() instanceof AbstractFireBlock) return;
            if (oldState.isAir() && newState.getBlock() instanceof AbstractFireBlock) return;
        }
        // Water/lava flow constantly replaces air and fluid states; only actual block destruction is logged.
        if (FLUID_CAUSES.contains(cause.type)
                && (oldState.isAir() || oldState.getBlock() instanceof FluidBlock)) return;
        // Random ticks grow trees, mushrooms, crops and grass into AIR; those writes are not a
        // change of an existing block. Real random-tick damage (ice melting, snow thawing,
        // copper oxidising, coral dying, grass spreading) always replaces or removes one (L3-18).
        // A fire block is never recorded on its own (same policy as in the #fire window above).
        if ("#random_tick".equals(cause.type)
                && (oldState.isAir() || oldState.getBlock() instanceof AbstractFireBlock)) return;
        cause.lastSetPos = pos;
        cause.lastSetOld = oldState;
        cause.lastSetNew = newState;
        if (newState.isAir()) {
            // L3-05: a container that is blown up or pushed away keeps its contents in the
            // block entity, which vanishes with the block; capture them while they still exist.
            captureContainerBeforeRemoval(world, pos);
        }
        mod.database.insertBlockAsync(mod.naturalLog(world, pos, oldState, newState, cause.type, signMetaAt(world, pos)));
    }

    public static void hookBreakBlock(World world, BlockPos pos) {
        try {
            hookBreakBlockInternal(world, pos);
        } catch (Throwable t) {
            // E16: see hookSetBlockState - never throw into vanilla world code.
            LOGGER.warn("[CoreProtect] Failed to log a natural block removal at {}: {}", pos, t.toString());
        }
    }

    private static void hookBreakBlockInternal(World world, BlockPos pos) {
        CoreProtectFabric mod = instance;
        if (mod == null || world.isClient() || mod.server == null) return;
        if (!mod.config.logging.natural) return;
        NaturalBreakCause.Cause cause = NaturalBreakCause.get();
        if (cause == null || !BREAK_CAUSES.contains(cause.type)) return;
        // E11: same reasoning as hookSetBlockState - drop the removals vanilla would reject.
        if (!world.isInBuildLimit(pos) || world.isDebugWorld()) return;
        BlockState oldState = world.getBlockState(pos);
        if (oldState.isAir()) return;
        if (cause.alreadyLoggedBreak(pos, oldState)) return;
        // Fire spread also removes adjacent fire blocks; only real block burns are logged.
        if ("#fire".equals(cause.type) && oldState.getBlock() instanceof AbstractFireBlock) return;
        // Piston head / moving ghost removal is mechanism noise, not a real block change.
        if ("#piston".equals(cause.type) && isPistonPart(oldState)) return;
        cause.lastBreakPos = pos;
        cause.lastBreakOld = oldState;
        // L3-05: see hookSetBlockStateInternal - the contents have to be read before the block goes.
        captureContainerBeforeRemoval(world, pos);
        mod.database.insertBlockAsync(mod.naturalLog(world, pos, oldState, Blocks.AIR.getDefaultState(), cause.type,
                signMetaAt(world, pos)));
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
        try {
            logPistonMoveInternal(world, pos, state, blockEntity);
        } catch (Throwable t) {
            // E16: the mixin RETURN that calls this runs inside the vanilla piston tick, so a
            // logging failure here would break the piston itself.
            LOGGER.warn("[CoreProtect] Failed to log a piston movement at {}: {}", pos, t.toString());
        }
    }

    private static void logPistonMoveInternal(World world, BlockPos pos, BlockState state, PistonBlockEntity blockEntity) {
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
        // L3-01: the block entity lives at the DESTINATION of the block it moves.
        // PistonBlock#move clears the source at the moved row's own position and creates the
        // block entity at "source.offset(travel)" (PistonBlock.java:280 for the travel
        // direction, :298-304 for the write), so the source has to be derived from the travel
        // direction: extending moves along the facing, retracting against it.
        Direction facing = blockEntity.getFacing();
        boolean extending = blockEntity.isExtending();
        BlockPos to = pos;
        BlockPos from = extending ? pos.offset(facing.getOpposite()) : pos.offset(facing);
        // L3-05: a container being pushed away loses its contents with the block entity.
        captureContainerBeforeRemoval(world, from);
        // L2-11: both rows describe one atomic movement, so they carry the partner cell in the
        // meta. A lookup/rollback filter that only matches one of the two positions can use it
        // to replay (or restore) the pair together instead of copying or losing a block.
        String pair = pistonPairMeta(from, to);
        String wid = world.getRegistryKey().getValue().toString();
        long time = TimeUtil.now();
        mod.database.insertBlockAsync(new DatabaseManager.BlockLog(
                0L, time, "#piston", wid,
                from.getX(), from.getY(), from.getZ(),
                DatabaseManager.TYPE_NATURAL, BlockStateUtil.stringify(moved),
                BlockStateUtil.stringify(Blocks.AIR.getDefaultState()), "#piston", pair));
        mod.database.insertBlockAsync(new DatabaseManager.BlockLog(
                0L, time, "#piston", wid,
                to.getX(), to.getY(), to.getZ(),
                DatabaseManager.TYPE_NATURAL, BlockStateUtil.stringify(Blocks.AIR.getDefaultState()),
                BlockStateUtil.stringify(moved), "#piston", pair));
    }

    private static String pistonPairMeta(BlockPos from, BlockPos to) {
        JsonObject piston = new JsonObject();
        piston.add("from", posJson(from));
        piston.add("to", posJson(to));
        JsonObject payload = new JsonObject();
        payload.add("piston", piston);
        return payload.toString();
    }

    private static JsonArray posJson(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    // ------------------------------------------------------------------
    // Containers (L3-05) and double blocks (L3-15)
    // ------------------------------------------------------------------

    /**
     * Reads a container's contents before the block that holds them is replaced. The tracker
     * is a no-op for every block that is not a container, deduplicates within one tick and
     * never throws (it is called from inside vanilla world code).
     */
    private static void captureContainerBeforeRemoval(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld) || pos == null) return;
        CoreProtectFabric mod = instance;
        if (mod == null || !mod.config.logging.container) return;
        ContainerTracker.captureBeforeRemoval(serverWorld, pos);
    }

    /**
     * The other half of a tall plant, door or bed. Vanilla removes it through a neighbour
     * update ({@code TallPlantBlock.java:126}, {@code BedBlock.java:189}) or a creative break,
     * which never reached a hook, so half of the structure stayed unrecorded (L3-15).
     * Returns {@code null} for every other block.
     */
    public static BlockPos otherDoubleHalf(BlockState state, BlockPos pos) {
        if (state == null || pos == null) return null;
        Block block = state.getBlock();
        if (block instanceof TallPlantBlock) {
            return state.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos.up();
        }
        if (block instanceof DoorBlock) {
            return state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.down() : pos.up();
        }
        if (block instanceof BedBlock) {
            Direction facing = state.get(BedBlock.FACING);
            return state.get(BedBlock.PART) == BedPart.FOOT ? pos.offset(facing) : pos.offset(facing.getOpposite());
        }
        return null;
    }

    /** The state of the other half of the same double block, or {@code null}. */
    public static BlockState otherDoubleHalfState(BlockState state) {
        if (state == null) return null;
        Block block = state.getBlock();
        if (block instanceof TallPlantBlock) {
            DoubleBlockHalf half = state.get(TallPlantBlock.HALF);
            return state.with(TallPlantBlock.HALF,
                    half == DoubleBlockHalf.UPPER ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        }
        if (block instanceof DoorBlock) {
            DoubleBlockHalf half = state.get(DoorBlock.HALF);
            return state.with(DoorBlock.HALF,
                    half == DoubleBlockHalf.UPPER ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        }
        if (block instanceof BedBlock) {
            BedPart part = state.get(BedBlock.PART);
            return state.with(BedBlock.PART, part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT);
        }
        return null;
    }

    /**
     * Records the other half of a double block that vanilla removed together with the block a
     * player broke. Only the player path needs this: a natural removal happens inside the cause
     * window, where the neighbour update is logged by the {@code setBlockState} hook already.
     */
    public static void logDoubleHalf(ServerWorld world, String user, BlockPos pos, BlockState state) {
        if (world == null || user == null || pos == null || state == null) return;
        CoreProtectFabric mod = instance;
        if (mod == null || !mod.config.logging.block) return;
        BlockPos other = otherDoubleHalf(state, pos);
        BlockState otherState = otherDoubleHalfState(state);
        if (other == null || otherState == null) return;
        // Only when vanilla really removed it: a lone half leaves the position air as well, but
        // then the block was never part of a complete structure at that spot.
        if (!world.getBlockState(other).isAir()) return;
        mod.database.insertBlockAsync(new DatabaseManager.BlockLog(
                0L, TimeUtil.now(), user,
                world.getRegistryKey().getValue().toString(),
                other.getX(), other.getY(), other.getZ(),
                DatabaseManager.TYPE_BREAK,
                BlockStateUtil.stringify(otherState),
                BlockStateUtil.stringify(Blocks.AIR.getDefaultState()),
                "-", null));
    }

    // ------------------------------------------------------------------
    // Sign text (L3-11)
    // ------------------------------------------------------------------

    /**
     * JSON meta for a sign that stores BOTH faces: the top-level {@code side}/{@code lines}
     * keep working for {@code BlockStateUtil#applySignText} (front face restore), and the
     * {@code back} member carries the back face that used to be dropped (L3-11).
     */
    public static String signMeta(BlockEntity blockEntity) {
        if (!(blockEntity instanceof SignBlockEntity sign)) return null;
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("side", "front");
            payload.add("lines", signLines(sign.getFrontText()));
            JsonArray back = signLines(sign.getBackText());
            for (int i = 0; i < back.size(); i++) {
                if (!back.get(i).getAsString().isBlank()) {
                    JsonObject backPayload = new JsonObject();
                    backPayload.addProperty("side", "back");
                    backPayload.add("lines", back);
                    payload.add("back", backPayload);
                    break;
                }
            }
            return payload.toString();
        } catch (Throwable t) {
            // A broken sign must never make the block record disappear.
            return null;
        }
    }

    /** Sign meta of the block entity standing at {@code pos} at this moment, or {@code null}. */
    public static String signMetaAt(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld) || pos == null) return null;
        return signMeta(world.getBlockEntity(pos));
    }

    private static JsonArray signLines(SignText text) {
        JsonArray lines = new JsonArray();
        for (int i = 0; i < 4; i++) {
            lines.add(text.getMessage(i, false).getString());
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Destroyed entity containers (L3-16)
    // ------------------------------------------------------------------

    /** Last player that hit an entity, so the item records name the killer. Bounded on purpose. */
    private static final Map<UUID, String> ENTITY_ATTACKERS = new ConcurrentHashMap<>();
    private static final int ENTITY_ATTACKER_MAX = 512;

    /** Called from the attack callback, i.e. before the damage that may destroy the entity. */
    public static void noteEntityAttacker(Entity target, PlayerEntity attacker) {
        if (!(target instanceof Entity) || attacker == null) return;
        if (!(attacker instanceof ServerPlayerEntity serverPlayer)) return;
        try {
            if (ENTITY_ATTACKERS.size() > ENTITY_ATTACKER_MAX) {
                ENTITY_ATTACKERS.clear();
            }
            ENTITY_ATTACKERS.put(target.getUuid(), serverPlayer.getGameProfile().getName());
        } catch (Throwable t) {
            LOGGER.warn("[CoreProtect] Failed to remember an entity attacker: {}", t.toString());
        }
    }

    /**
     * Records the contents of a container entity (minecart with chest, chest boat, item frame)
     * that is being destroyed. The entity vanishes together with its items and no vanilla
     * event reports them (L3-16).
     */
    public static void hookEntityRemoval(Entity entity) {
        try {
            CoreProtectFabric mod = instance;
            if (mod == null || entity == null || !(entity.getWorld() instanceof ServerWorld world)) return;
            if (!mod.config.logging.item) return;
            List<ItemStack> stacks = entityContents(entity);
            if (stacks.isEmpty()) return;
            String user = ENTITY_ATTACKERS.remove(entity.getUuid());
            if (user == null) {
                user = "#entity";
            }
            BlockPos pos = entity.getBlockPos();
            String wid = world.getRegistryKey().getValue().toString();
            long time = TimeUtil.now();
            for (ItemStack stack : stacks) {
                mod.database.insertItemAsync(new DatabaseManager.ItemLog(
                        0, time, user, wid,
                        pos.getX(), pos.getY(), pos.getZ(), "-",
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
                        ItemStackCodec.encodeIdentity(stack, world.getRegistryManager())));
            }
        } catch (Throwable t) {
            LOGGER.warn("[CoreProtect] Failed to log the contents of a removed entity: {}", t.toString());
        }
    }

    private static List<ItemStack> entityContents(Entity entity) {
        List<ItemStack> stacks = new ArrayList<>();
        if (entity instanceof Inventory inventory) {
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.getStack(i);
                if (!stack.isEmpty()) {
                    stacks.add(stack.copy());
                }
            }
        } else if (entity instanceof ItemFrameEntity frame) {
            ItemStack held = frame.getHeldItemStack();
            if (!held.isEmpty()) {
                stacks.add(held.copy());
            }
        }
        return stacks;
    }

    /** Builds a natural (non-player) record; {@code meta} carries the sign faces when relevant. */
    private DatabaseManager.BlockLog naturalLog(World world, BlockPos pos, BlockState oldState, BlockState newState,
                                                String cause, String meta) {
        return new DatabaseManager.BlockLog(
                0L, TimeUtil.now(), cause,
                world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                2, BlockStateUtil.stringify(oldState), BlockStateUtil.stringify(newState),
                cause, meta);
    }
}
