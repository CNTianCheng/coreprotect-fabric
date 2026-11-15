package net.coreprotect.fabric.rollback;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.coreprotect.fabric.CoreProtectFabric;
import net.coreprotect.fabric.container.ContainerTracker;
import net.coreprotect.fabric.database.Criteria;
import net.coreprotect.fabric.database.DatabaseManager;
import net.coreprotect.fabric.util.BlockStateUtil;
import net.coreprotect.fabric.util.ItemStackCodec;
import net.coreprotect.fabric.util.Messages;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * Rollback / restore / undo engine, mirroring CoreProtect's semantics: every block position
 * replays all of the logs that touched it (rollback walks them newest first, restore oldest
 * first) and the container transactions of the same window are reversed afterwards, so the
 * world already holds the container block again when its contents are put back. The sign text
 * and the dropped item stacks are reverted last.
 */
public final class RollbackManager {
    /** Time budget for one slice on the server thread (4 ms), so a big rollback never
     *  freezes a tick: the rest of the work is re-scheduled onto the next tick. */
    private static final long SLICE_NANOS = 4_000_000L;

    /** Where {@code /co undo} is stored, so the record survives a restart. */
    private static final String UNDO_FILE = "coreprotect-fabric-undo.json";
    private static final int UNDO_FILE_VERSION = 1;

    /** A running job rewrites its undo record at most this often; the record is always written
     *  when the job ends, so an interrupted rollback loses at most five seconds of undo data. */
    private static final long UNDO_SAVE_NANOS = 5_000_000_000L;

    /** How far back a sign's previous text is looked up. */
    /**
     * Bound for the console/RCON plan query (see startRollback): these sources only see what is
     * printed before the command returns, and the indexed lookups are fast.
     */
    private static final long CONSOLE_TIMEOUT_MS = 10_000L;

    private static final int SIGN_HISTORY_LIMIT = 256;

    /** How many sign positions are resolved before the read pool is given back to other queries. */
    private static final int SIGN_HISTORY_BATCH = 64;

    /** Upper bound for the per-position sign history queries of one plan. */
    private static final int MAX_SIGN_HISTORY_QUERIES = 256;

    /** The sign history resolution stops after using the read pool for this long. */
    private static final long SIGN_HISTORY_BUDGET_NANOS = 50_000_000L;

    /**
     * How a rollback/restore writes a block state. {@code Block.NOTIFY_ALL} asks vanilla for the
     * full neighbour and callback cascade, and {@code FallingBlock#onBlockAdded} schedules its fall
     * tick: a column of sand, gravel or anvils restored in the wrong order collapsed before its own
     * row could be replayed (the fall happens outside the slice, so the expected-state check never
     * sees it). {@code FORCE_STATE} stores the state as-is and skips the neighbour update cascade
     * (1.21 gates {@code prepare}/{@code updateNeighbors} behind it), {@code SKIP_DROPS} keeps a
     * replaced container from dropping its contents (they were taken out and recorded first).
     * {@code NOTIFY_LISTENERS} is kept so the clients still see the change, and the block entity
     * callbacks are left enabled so a container block entity is still created and removed like
     * vanilla does it.
     *
     * <p>1.21 has no {@code Block.SKIP_BLOCK_ADDED_CALLBACK}: the flag that lets 1.21.2+ skip
     * {@code AbstractBlock#onBlockAdded} does not exist there, and {@code WorldChunk#setBlockState}
     * calls that hook unconditionally, so a restored falling block still gets its scheduled fall
     * tick. Nothing on the 1.21 side can suppress that.
     */
    private static final int STRICT_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE
            | Block.SKIP_DROPS;

    private final Map<UUID, LastOperation> lastOperations = new ConcurrentHashMap<>();

    /** The jobs that are still applying changes; a job leaves this set when it ends. */
    private final Set<Job> runningJobs = ConcurrentHashMap.newKeySet();

    /**
     * Writes the undo file off the tick thread. A multi-megabyte record (maxBlocks plus the
     * container, item and sign lists) used to be written from the slice loop, which blocked the
     * server thread every five seconds for the duration of the write.
     */
    private final ExecutorService undoWriter = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CoreProtect undo writer");
        thread.setDaemon(true);
        return thread;
    });

    /** True while a background write is queued, so a slice cannot pile up several of them. */
    private final AtomicBoolean undoSaveQueued = new AtomicBoolean();

    private volatile boolean undoLoaded;

    public RollbackManager() {
        // Registered here rather than in CoreProtectFabric (whose stopping handler only shuts
        // down the metrics/updater and finalizes the containers): a rollback that is still in
        // flight has to be finished and persisted before the server goes away, otherwise the
        // world keeps the part it already changed with no way to undo it.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
    }

    public record Summary(int blocks, int containers, int itemOps, int skipped) {
    }

    private record PosKey(String wid, int x, int y, int z) {
        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        static PosKey of(String wid, int x, int y, int z) {
            return new PosKey(wid, x, y, z);
        }
    }

    /**
     * One block change performed by a rollback/restore. {@code expected} is the state the
     * position holds afterwards (and the undo only fires while it still does), {@code apply}
     * is the state the undo writes back.
     */
    private record BlockUndo(String wid, BlockPos pos, String expected, String apply) {
    }

    /** One container item change performed by a rollback/restore, invertible by /co undo. */
    private record ItemUndo(String wid, BlockPos pos, String item, int amount, boolean wasAdded, String nbt) {
    }

    /**
     * A stack that was dropped on the ground by the rollback ({@code added}) or taken off the ground
     * by it; /co undo puts it back or removes it again. {@code nbt} carries the data components.
     * {@code container} marks a stack that was taken out of a container which the rollback then
     * replaced: the undo has to put it back into the container instead of deleting it from the
     * ground, otherwise rollback + undo emptied the container and lost its contents.
     */
    private record WorldItemUndo(String wid, BlockPos pos, String item, int amount, boolean added, String nbt,
                                 boolean container) {
    }

    /** Sign text a rollback/restore replaced; /co undo writes {@code text} back. */
    private record SignUndo(String wid, BlockPos pos, String text) {
    }

    private record LastOperation(String kind, List<BlockUndo> blocks, List<ItemUndo> items,
                                 List<WorldItemUndo> worldItems, List<SignUndo> signs) {
        boolean isEmpty() {
            return blocks.isEmpty() && items.isEmpty() && worldItems.isEmpty() && signs.isEmpty();
        }
    }

    /** A sign position matched by a rollback and the text that has to be written back
     *  ({@code text == null}: the text from before the oldest matched edit is unknown). */
    private record SignTarget(PosKey pos, String text) {
    }

    /** What the read pool prepared for a rollback: nothing here touches the world. */
    private record Plan(boolean tooLarge,
                        List<Map.Entry<PosKey, List<DatabaseManager.BlockLog>>> blocks,
                        List<Map.Entry<PosKey, List<DatabaseManager.ContainerLog>>> containers,
                        int containerGroups,
                        List<SignTarget> signs,
                        List<DatabaseManager.ItemLog> items,
                        List<DatabaseManager.EntityLog> entities) {
    }

    /** One rollback/restore/undo in flight, advanced one unit of work at a time. */
    private static final class Job {
        private final MinecraftServer server;
        private final ServerCommandSource source;
        private final ServerPlayerEntity operator;
        private final boolean isRestore;
        private final boolean undo;

        private List<Map.Entry<PosKey, List<DatabaseManager.ContainerLog>>> containers = List.of();
        private int containerGroups;
        private int groupIndex;
        private int entryIndex;
        /**
         * The per-position log chains of the matched blocks, newest row first. Every row is
         * replayed (newest to oldest for a rollback, oldest to newest for a restore) instead of
         * only the newest one, so a position that was changed several times ends up in the state
         * it had before the first replayed change.
         */
        private List<Map.Entry<PosKey, List<DatabaseManager.BlockLog>>> blocks = List.of();
        private int blockGroupIndex;
        private int blockRowIndex;
        private List<SignTarget> signs = List.of();
        private int signIndex;
        private List<DatabaseManager.ItemLog> items = List.of();
        private int itemIndex;
        private List<DatabaseManager.EntityLog> entities = List.of();
        private int entityIndex;

        private LastOperation operation;
        private int blockUndoIndex;
        private int undoIndex;
        private int signUndoIndex;
        private int worldItemIndex;

        private final List<BlockUndo> undos = new ArrayList<>();
        private final List<ItemUndo> itemUndos = new ArrayList<>();
        private final List<WorldItemUndo> worldItemUndos = new ArrayList<>();
        private final List<SignUndo> signUndos = new ArrayList<>();
        private int blocksApplied;
        private int itemOps;
        private int itemsApplied;
        /**
         * Sign texts that were written back. They are not blocks, so counting them as blocks made the
         * result line claim more blocks than were restored; they are reported on their own.
         */
        private int signOps;
        private int skipped;
        /** Positions left alone because their chunk was not loaded (never loaded for a rollback). */
        private int unloadedSkips;
        /** Piston rows whose partner row is not part of this plan; they are skipped, never half replayed. */
        private Set<Long> unpairedPistonRows = Set.of();
        private int pistonSkips;
        /** Container positions of which at least one row was really replayed (not just matched). */
        private int containerGroupsApplied;
        /** Whether the container group that is currently replayed moved at least one item. */
        private boolean containerGroupTouched;
        private long lastUndoSave;

        private Job(MinecraftServer server, ServerCommandSource source, ServerPlayerEntity operator,
                    boolean isRestore, boolean undo) {
            this.server = server;
            this.source = source;
            this.operator = operator;
            this.isRestore = isRestore;
            this.undo = undo;
            this.lastUndoSave = System.nanoTime();
        }
    }

    /**
     * Starts a rollback/restore. The queries run on the read pool and the world is
     * edited in 4 ms slices on the server thread, so the command returns immediately and
     * the server keeps ticking while a large area is restored. All user-visible messages
     * are sent from here.
     */
    public void startRollback(ServerCommandSource source, Criteria c, boolean isRestore) {
        CoreProtectFabric mod = CoreProtectFabric.instance();
        long limit = Math.max(1, mod.config().rollback.maxBlocks) + 1L;
        int maxBlocks = mod.config().rollback.maxBlocks;
        ServerPlayerEntity operator = source.getPlayer();
        if (isRunning(operator)) {
            // Only one operation per player: a second rollback started while the first one is
            // still running would replace the undo record it is still building, and /co undo
            // would then revert a mixture of both. A console/RCON rollback counts as its own
            // operator, so a second one cannot interleave with it either.
            Messages.cmd(source, "coreprotect.error.busy");
            return;
        }
        ActionScope scope = ActionScope.of(c.action);
        if (scope == ActionScope.UNSUPPORTED) {
            // The action filter used to reach co_block only, so an action this engine cannot revert
            // (a:#chat, a:#command, a:#session) filtered nothing at all and the command rolled back
            // every block of the window. Say so instead of doing the wrong thing.
            Messages.error(source, "coreprotect.error.invalid_action", c.action);
            return;
        }
        ensureUndoLoaded();

        // submitReadFuture rather than submitRead: a query that is rejected because the database is
        // shutting down has to reach the caller, not disappear behind a log line.
        CompletableFuture<Plan> future = mod.database().submitReadFuture(() -> {
            List<DatabaseManager.BlockLog> logs = scope.blocks
                    ? mod.database().queryBlocks(c, limit, 0) : List.of();
            if (logs == null) return null;
            List<DatabaseManager.ContainerLog> containerLogs = scope.containers
                    ? mod.database().queryContainers(c, limit, 0) : List.of();
            if (containerLogs == null) return null;
            List<DatabaseManager.SignLog> signLogs = scope.signs
                    ? mod.database().querySigns(c, limit, 0) : List.of();
            if (signLogs == null) return null;
            List<DatabaseManager.ItemLog> itemLogs = scope.items
                    ? mod.database().queryItems(c, limit, 0) : List.of();
            if (itemLogs == null) return null;
            List<DatabaseManager.EntityLog> entityLogs = scope.entities
                    ? mod.database().queryEntities(c, limit, 0) : List.of();
            if (entityLogs == null) return null;
            // The cap is on the whole plan. Capping every table on its own let a single command
            // apply about five times maxBlocks and still report success.
            long total = (long) logs.size() + containerLogs.size() + signLogs.size()
                    + itemLogs.size() + entityLogs.size();
            if (total > maxBlocks) {
                return new Plan(true, List.of(), List.of(), 0, List.of(), List.of(), List.of());
            }
            // One chain per position, newest row first, so every row can be replayed in turn.
            Map<PosKey, List<DatabaseManager.BlockLog>> chains = new LinkedHashMap<>();
            for (DatabaseManager.BlockLog l : logs) {
                if (Objects.equals(l.oldData(), l.newData())) continue;
                chains.computeIfAbsent(PosKey.of(l.wid(), l.x(), l.y(), l.z()), k -> new ArrayList<>()).add(l);
            }
            Map<PosKey, List<DatabaseManager.ContainerLog>> groups = groupContainers(containerLogs);
            return new Plan(false,
                    new ArrayList<>(chains.entrySet()),
                    new ArrayList<>(groups.entrySet()),
                    groups.size(),
                    resolveSignTargets(mod, signLogs, isRestore),
                    itemLogs,
                    entityLogs);
        });
        MinecraftServer server = mod.server();
        if (future == null) {
            // The read pool is already gone (server stopping), so nothing was queried at all.
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Rollback refused - the database is shutting down.");
            Messages.error(source, "coreprotect.error.db", "");
            return;
        }
        if (server == null) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Rollback refused - no server available.");
            Messages.error(source, "coreprotect.error.db", "");
            return;
        }
        if (operator == null) {
            // Console, RCON and command blocks only see what is printed before the command returns:
            // vanilla flushes their output buffer at that point, so an asynchronous plan left them
            // with an empty answer (the rollback itself still ran). Wait for the plan with a bound -
            // the queries are indexed lookups, and a timeout only means the answer moves to the log.
            try {
                Plan plan = future.get(CONSOLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                applyPlan(source, operator, server, isRestore, maxBlocks, plan);
                return;
            } catch (TimeoutException e) {
                Messages.cmd(source, isRestore ? "coreprotect.restore.start" : "coreprotect.rollback.start");
                Messages.error(source, "coreprotect.still_running");
                CoreProtectFabric.LOGGER.warn(
                        "[CoreProtect] Plan query is still running after {} ms; the operation will start when it is ready and its result is written to this log.",
                        CONSOLE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Messages.error(source, "coreprotect.error.db", "");
                return;
            } catch (ExecutionException e) {
                Messages.error(source, "coreprotect.error.db", "");
                return;
            }
        }
        future.thenAccept(plan -> {
            try {
                server.execute(() -> applyPlan(source, operator, server, isRestore, maxBlocks, plan));
            } catch (RuntimeException e) {
                // the server started stopping between the query and the result
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Rollback result discarded - the server is stopping.", e);
            }
        });
    }

    /** Turns a finished query into a job and starts it. Runs on the server thread. */
    private void applyPlan(ServerCommandSource source, ServerPlayerEntity operator, MinecraftServer server,
                           boolean isRestore, int maxBlocks, Plan plan) {
        if (plan == null) {
            // The JDBC/SQL reason is in the log. The caller gets the localized generic message: a
            // hard coded English sentence used to be passed into the {0} of every language, so a
            // Chinese player read "数据库错误：database read failed".
            Messages.error(source, "coreprotect.error.db", "");
            return;
        }
        if (plan.tooLarge()) {
            Messages.error(source, "coreprotect.rollback.too_many", maxBlocks);
            return;
        }
        // Announced only now: a plan that is rejected for its size never promised a rollback that
        // was not going to run.
        Messages.cmd(source, isRestore ? "coreprotect.restore.start" : "coreprotect.rollback.start");
        Job job = new Job(server, source, operator, isRestore, false);
        job.blocks = plan.blocks();
        job.containers = plan.containers();
        job.containerGroups = plan.containerGroups();
        job.signs = plan.signs();
        job.items = plan.items();
        job.entities = plan.entities();
        job.unpairedPistonRows = findUnpairedPistonRows(plan.blocks());
        runningJobs.add(job);
        slice(job);
    }

    /** Reverts the caller's last rollback/restore. Reports "nothing to undo" itself. */
    public void startUndo(ServerCommandSource source) {
        ServerPlayerEntity operator = source.getPlayer();
        ensureUndoLoaded();
        if (isRunning(operator)) {
            // The rollback of this player is still slicing, so its undo record is not finished yet:
            // undoing now would revert a partial record while the job keeps writing a new one.
            Messages.cmd(source, "coreprotect.error.busy");
            return;
        }
        UUID id = operator == null ? null : operator.getUuid();
        // The record is only read here; it is removed when the undo finished (see finish). Taking it
        // out of the map before the work ran meant a job that died halfway - an exception, a stop,
        // a crash - left the world half reverted with no record left to retry from.
        LastOperation op = id == null ? null : lastOperations.get(id);
        if (op == null || op.isEmpty()) {
            Messages.cmd(source, "coreprotect.undo.empty");
            return;
        }
        Job job = new Job(CoreProtectFabric.instance().server(), source, operator, false, true);
        job.operation = op;
        runningJobs.add(job);
        slice(job);
    }

    /**
     * Ends every job that is still in flight, keeping the part of the world it already changed
     * undoable: the slices that were scheduled for a later tick never run once the server stops.
     * Wired to SERVER_STOPPING by the constructor.
     */
    public void shutdown() {
        for (Job job : List.copyOf(runningJobs)) {
            runningJobs.remove(job);
            try {
                commitUndo(job);
                CoreProtectFabric.LOGGER.info(
                        "[CoreProtect] {} stopped by server shutdown: {} block(s), {} container(s), {} item(s) applied, {} skipped.",
                        job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroupsApplied,
                        job.itemOps, job.skipped);
            } catch (Exception e) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to store the undo record of an interrupted rollback", e);
            }
        }
        saveLastOperations();
    }

    private boolean isRunning(ServerPlayerEntity operator) {
        UUID id = operator == null ? null : operator.getUuid();
        for (Job job : runningJobs) {
            ServerPlayerEntity other = job.operator;
            if (id == null) {
                // console, RCON and command blocks share one "operator": refuse rather than let two
                // of their jobs interleave on the server thread
                if (other == null) return true;
            } else if (other != null && id.equals(other.getUuid())) {
                return true;
            }
        }
        return false;
    }

    /** Applies work until the slice budget runs out, then continues on a later tick. */
    private void slice(Job job) {
        long deadline = System.nanoTime() + SLICE_NANOS;
        do {
            boolean more;
            try {
                more = job.undo ? stepUndo(job) : stepRollback(job);
            } catch (Exception e) {
                // The raw exception (JDBC paths, SQL state, ...) is for the log only: the command
                // source - and with it a command block's log - gets the generic database error.
                CoreProtectFabric.LOGGER.error("[CoreProtect] Rollback failed", e);
                // The command source (and a command block's log) gets the localized generic
                // message; the argument stays empty so no English text lands in {0} of a
                // translated line (L4-16).
                Messages.error(job.source, "coreprotect.error.db", "");
                // Half of the world has already been changed, so the undo record has to be
                // written even though the job aborted.
                finish(job, true);
                return;
            }
            if (!more) {
                finish(job, false);
                return;
            }
        } while (System.nanoTime() < deadline);
        if (job.server == null) {
            // The server is already gone: nothing more can be applied. The rest of the plan stays
            // unapplied and the caller is told instead of getting a silent partial rollback.
            CoreProtectFabric.LOGGER.warn("[CoreProtect] {} stopped: no server available ({} block(s), {} container(s), {} item(s) applied, {} skipped).",
                    job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroupsApplied, job.itemOps, job.skipped);
            Messages.error(job.source, "coreprotect.error.db", "");
            finish(job, true);
            return;
        }
        // Keep the undo record on disk up to date while the job runs: a crash (or a kill -9)
        // halfway through a long rollback must not lose what was already applied. The record is
        // rewritten at most every five seconds and on a background thread, so neither the disk
        // nor the tick thread is hammered by every slice.
        long now = System.nanoTime();
        if (!job.undo && now - job.lastUndoSave >= UNDO_SAVE_NANOS) {
            job.lastUndoSave = now;
            commitUndo(job); // publishes the record in memory
            saveLastOperationsAsync();
        }
        job.server.execute(() -> slice(job)); // continue on the next tick
    }

    private void finish(Job job, boolean failed) {
        runningJobs.remove(job);
        if (job.undo) {
            if (job.operator != null) {
                if (!failed) {
                    // The undo replayed the record completely, so it is consumed now. Doing this at
                    // the start (before the work ran) lost the record whenever the job aborted.
                    lastOperations.remove(job.operator.getUuid());
                    saveLastOperations();
                } else {
                    // Half of the undo may be applied already. The record stays in memory and on
                    // disk, so /co undo can be run again instead of losing the way back.
                    CoreProtectFabric.LOGGER.warn(
                            "[CoreProtect] Undo stopped early; the record is kept so /co undo can be retried ({} block(s), {} item operation(s) reverted, {} skipped).",
                            job.blocksApplied, job.itemsApplied, job.skipped);
                }
            }
            if (!failed) {
                // The message format spells out the block count only, but the other counters are
                // passed as well: MessageFormat ignores unused arguments, so a translated line that
                // mentions the item operations, the sign texts or the skipped rows works without
                // touching the code again.
                Messages.success(job.source, "coreprotect.undo.complete",
                        job.blocksApplied, job.containerGroupsApplied, job.itemsApplied, job.skipped, job.signOps);
            }
            return;
        }
        // The undo record is always replaced: a container-only or fully-skipped rollback must
        // not leave an older operation armed for the next /co undo.
        commitUndo(job);
        if (failed) {
            return; // the error was already reported
        }
        if (job.blocksApplied == 0 && job.itemOps == 0 && job.signOps == 0 && job.skipped == 0) {
            Messages.cmd(job.source, "coreprotect.rollback.empty");
            return;
        }
        // "containers" counts the container positions that really moved items, not the positions the
        // query matched: a fully skipped container group used to be reported as restored.
        Messages.success(job.source, job.isRestore ? "coreprotect.restore.complete" : "coreprotect.rollback.complete",
                job.blocksApplied, job.containerGroupsApplied, job.itemOps, job.skipped, job.signOps);
        if (job.unloadedSkips > 0) {
            CoreProtectFabric.LOGGER.warn(
                    "[CoreProtect] {} left {} position(s) alone because their chunk was not loaded; run the command again for those.",
                    job.isRestore ? "Restore" : "Rollback", job.unloadedSkips);
        }
        if (job.pistonSkips > 0) {
            CoreProtectFabric.LOGGER.warn(
                    "[CoreProtect] {} skipped {} piston move(s) whose partner row was not part of the search result.",
                    job.isRestore ? "Restore" : "Rollback", job.pistonSkips);
        }
        if (job.source.getPlayer() == null) {
            // console and RCON only print their output while the command runs, so the result
            // of an asynchronous rollback is mirrored into the server log
            CoreProtectFabric.LOGGER.info("[CoreProtect] {} finished: {} block(s), {} container(s), {} item(s), {} sign(s), {} skipped.",
                    job.isRestore ? "Restore" : "Rollback", job.blocksApplied, job.containerGroupsApplied, job.itemOps,
                    job.signOps, job.skipped);
        }
    }

    /** Publishes the job's undo record in memory (for {@code /co undo}), without touching the disk. */
    private void publishUndo(Job job) {
        if (job.undo || job.operator == null) return;
        lastOperations.put(job.operator.getUuid(), new LastOperation(
                job.isRestore ? "restore" : "rollback",
                new ArrayList<>(job.undos), new ArrayList<>(job.itemUndos),
                new ArrayList<>(job.worldItemUndos), new ArrayList<>(job.signUndos)));
    }

    /** Publishes the job's undo record: in memory for {@code /co undo}, on disk for the next start. */
    private void commitUndo(Job job) {
        publishUndo(job);
        saveLastOperations();
    }

    /**
     * Writes the undo file on the background thread. The file is serialized from the live map when
     * the write actually runs, so a queued write can only ever store the newest state - never an
     * older snapshot that a later {@code commitUndo} already replaced.
     */
    private void saveLastOperationsAsync() {
        if (!undoSaveQueued.compareAndSet(false, true)) return;
        undoWriter.execute(() -> {
            try {
                saveLastOperations();
            } finally {
                undoSaveQueued.set(false);
            }
        });
    }

    /** One unit of rollback work. Returns false when there is nothing left to do. */
    private boolean stepRollback(Job job) {
        // The block rows run first: the container rows of the same window replay into the world the
        // rollback just restored. Reversed, a window that removed a container and touched its
        // contents ended with the container stage finding air - every container row was skipped and
        // the inventory was gone for good even though the block had been restored.
        if (job.blockGroupIndex < job.blocks.size()) {
            Map.Entry<PosKey, List<DatabaseManager.BlockLog>> entry = job.blocks.get(job.blockGroupIndex);
            List<DatabaseManager.BlockLog> chain = entry.getValue();
            // A rollback replays the chain from the newest row down, a restore from the oldest up.
            int row = job.isRestore ? chain.size() - 1 - job.blockRowIndex : job.blockRowIndex;
            DatabaseManager.BlockLog l = chain.get(row);
            job.blockRowIndex++;
            if (job.blockRowIndex >= chain.size()) {
                job.blockGroupIndex++;
                job.blockRowIndex = 0;
            }
            ServerWorld world = worldOf(job.server, l.wid());
            if (world == null) {
                job.skipped++;
                return true;
            }
            if (job.unpairedPistonRows.contains(l.id())) {
                // The other half of this piston move is not part of the plan: a b:/r: filter can keep
                // one of the two rows and drop the other, and so can the result limit. Replaying the
                // "from" row alone leaves the pushed block at its destination (a copy), replaying
                // only the "to" row deletes it.
                job.pistonSkips++;
                job.skipped++;
                return true;
            }
            BlockState target = BlockStateUtil.parseOrNull(job.server, job.isRestore ? l.newData() : l.oldData());
            BlockState expected = BlockStateUtil.parseOrNull(job.server, job.isRestore ? l.oldData() : l.newData());
            if (target == null || expected == null) {
                // A state that cannot be parsed (block mod removed, ...) must never be treated as
                // air: that would delete whatever stands there instead of skipping this row.
                job.skipped++;
                return true;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            if (!world.isChunkLoaded(pos)) {
                // getBlockState()/setBlockState() load - and generate - a chunk on demand, so a
                // rollback over an area nobody is in used to generate chunk after chunk and ignore
                // the 4 ms slice budget completely.
                job.unloadedSkips++;
                job.skipped++;
                return true;
            }
            BlockState current = world.getBlockState(pos);
            if (!current.equals(expected)) {
                if (current.equals(target)) {
                    // The world moved on since this row was written, or a newer row of the chain
                    // already produced this state (then the position simply needs no change). Either
                    // way this row cannot be replayed; the next, older one still can.
                    return true;
                }
                if (!isTransientMedium(expected) || !(current.isAir() || isTransientMedium(current))) {
                    job.skipped++;
                    return true;
                }
                // A #fire/#water row expects the transient medium it left behind (fire, flowing
                // water). That medium disappears on its own once the fire burnt out or the water
                // flowed away, so the position stayed "skipped" forever although the row is
                // replayable. Fall through and restore the block the row had before.
            }
            if (!target.isOf(current.getBlock())) {
                // Replacing a block that carries an inventory removes its block entity before
                // vanilla's onStateReplaced() could scatter the contents, so they have to be taken
                // out first - otherwise the container is emptied into nothing. They are dropped next
                // to the container and recorded as container items, so the undo puts them back
                // instead of taking them off the ground (L2-03).
                // Only the inventory owned by this very block: a merged double chest would also
                // scatter the half that survives the rollback into the world.
                Inventory inv = ownedInventory(world, pos);
                if (inv != null) {
                    ejectInventory(inv, world, pos, job, l.wid());
                }
            }
            world.setBlockState(pos, target, STRICT_FLAGS);
            BlockStateUtil.applySignText(world, pos, l.meta());
            job.blocksApplied++;
            job.undos.add(new BlockUndo(l.wid(), pos,
                    BlockStateUtil.stringify(target), BlockStateUtil.stringify(expected)));
            return true;
        }
        if (job.groupIndex < job.containers.size()) {
            Map.Entry<PosKey, List<DatabaseManager.ContainerLog>> entry = job.containers.get(job.groupIndex);
            List<DatabaseManager.ContainerLog> list = entry.getValue();
            ServerWorld world = worldOf(job.server, entry.getKey().wid());
            BlockPos pos = entry.getKey().pos();
            if (world == null || !world.isChunkLoaded(pos)) {
                if (world != null) {
                    job.unloadedSkips++;
                }
                job.skipped++;
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            // The merged 54 slot inventory of a double chest, i.e. what the tracker wrote its deltas
            // for. Reading the block entity at the row's position (the half the player opened) gave
            // only 27 slots, so the other half of every double chest transaction was never replayed.
            Inventory inv = ContainerTracker.containerAt(world, pos);
            if (inv == null) {
                // The position does not hold a container (any more), so its item transactions
                // cannot be reversed; without the counter the result line claimed
                // "containers: 0, skipped: 0" even though nothing was restored.
                job.skipped++;
                job.groupIndex++;
                job.entryIndex = 0;
                return true;
            }
            // A rollback replays the group from the newest row down, a restore from the oldest up:
            // replaying a restore newest-first ended in a different state whenever the same item had
            // been deposited and withdrawn more than once.
            int slot = job.isRestore ? list.size() - 1 - job.entryIndex : job.entryIndex;
            DatabaseManager.ContainerLog log = list.get(slot);
            job.entryIndex++;
            int done = reverseContainer(job, log, inv, world, job.isRestore);
            job.itemOps += done;
            if (done > 0) {
                // deposit: rollback removes -> undo adds; withdraw: rollback adds -> undo removes
                boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
                boolean added = job.isRestore == deposit;
                // Only what really moved is recorded: a row can describe 64 items while the container
                // still held 32, and an undo that puts all 64 back would invent the difference.
                job.itemUndos.add(new ItemUndo(log.wid(), pos, log.data(), done, added, log.nbt()));
                job.containerGroupTouched = true;
            } else {
                job.skipped++;
            }
            if (job.entryIndex >= list.size()) {
                if (job.containerGroupTouched) {
                    // the reported "containers" count only counts positions that really changed
                    job.containerGroupsApplied++;
                    job.containerGroupTouched = false;
                }
                // The container is final now: refresh the baseline of every player watching it,
                // so the change this rollback just made is not attributed to them later.
                ContainerTracker.rebaseline(world, pos);
                job.groupIndex++;
                job.entryIndex = 0;
            }
            return true;
        }
        if (job.signIndex < job.signs.size()) {
            SignTarget target = job.signs.get(job.signIndex++);
            ServerWorld world = worldOf(job.server, target.pos().wid());
            String meta = target.text();
            if (world == null || meta == null) {
                // co_sign only stores the text a row wrote, so the text from before the oldest
                // matched edit has to come from the position's own history; when that cannot be
                // determined the position is reported as skipped instead of guessed.
                job.skipped++;
                return true;
            }
            BlockPos pos = target.pos().pos();
            if (!world.isChunkLoaded(pos)) {
                job.unloadedSkips++;
                job.skipped++;
                return true;
            }
            String previous = BlockStateUtil.replaceSignText(world, pos, meta);
            if (previous == null) {
                job.skipped++; // no sign at that position (any more)
                return true;
            }
            job.signUndos.add(new SignUndo(target.pos().wid(), pos, previous));
            job.signOps++; // a sign text is not a block: it has its own counter
            return true;
        }
        if (job.itemIndex < job.items.size()) {
            DatabaseManager.ItemLog l = job.items.get(job.itemIndex++);
            ServerWorld world = worldOf(job.server, l.wid());
            if (world == null || job.isRestore || !"-".equals(l.action())) {
                // Only a dropped stack can be reverted without inventing items: re-applying a
                // pickup (restore) or undoing one (rollback of a '+') would hand out a second copy
                // of what the player already carries, so those rows are reported as skipped.
                job.skipped++;
                return true;
            }
            Identifier id = Identifier.tryParse(l.data());
            Item item = id == null ? null : Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                job.skipped++;
                return true;
            }
            BlockPos pos = new BlockPos(l.x(), l.y(), l.z());
            if (!world.isChunkLoaded(pos)) {
                job.unloadedSkips++;
                job.skipped++;
                return true;
            }
            // The row's data components describe the stack that was dropped; a row written before
            // they were stored falls back to a plain stack of that item.
            ItemStack template = ItemStackCodec.decode(l.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            int removed = removeDroppedItems(world, pos, template, l.amount());
            if (removed <= 0) {
                job.skipped++; // the stack is gone (picked up, burnt, despawned, moved)
                return true;
            }
            // container=false: this row is an item drop, so the undo picks the stack off the ground
            // instead of storing it in a container.
            job.worldItemUndos.add(new WorldItemUndo(l.wid(), pos, l.data(), removed, false, l.nbt(), false));
            job.itemOps++;
            return true;
        }
        if (job.entityIndex < job.entities.size()) {
            // A kill or a death is logged with the entity type and no NBT, so respawning it would
            // invent a blank mob (or a second boss) rather than restore what was killed. Those
            // rows are reported as skipped instead of being silently covered by "Rollback
            // complete".
            job.entityIndex++;
            job.skipped++;
            return true;
        }
        return false;
    }

    /**
     * One unit of undo work. Returns false when there is nothing left to do.
     * <p>
     * The order is the reverse of {@link #stepRollback}: the item changes the rollback made in a
     * container are reverted before its block is replaced, and the stacks the rollback emptied out of
     * a replaced container are put back last - the block stage has restored the container by then, so
     * they can go into it again instead of being deleted from the ground.
     */
    private boolean stepUndo(Job job) {
        LastOperation op = job.operation;
        if (job.undoIndex < op.items().size()) {
            ItemUndo u = op.items().get(job.undoIndex);
            job.undoIndex++;
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            if (!world.isChunkLoaded(u.pos())) {
                job.unloadedSkips++;
                job.skipped++;
                return true;
            }
            // containerAt gives the merged 54 slot inventory of a double chest - the inventory the
            // container rows were written for.
            Inventory inv = ContainerTracker.containerAt(world, u.pos());
            if (inv == null) {
                job.skipped++; // the container is not there (any more), so this change is lost
                return true;
            }
            Identifier id = Identifier.tryParse(u.item());
            if (id == null) {
                job.skipped++;
                return true;
            }
            Item item = Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                job.skipped++;
                return true;
            }
            // The stored data components decide what the stack actually was; without them an enchanted
            // item would be restored as a plain one and a filled shulker box as an empty one.
            ItemStack template = ItemStackCodec.decode(u.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            if (u.wasAdded()) {
                // the rollback put it into the container; the undo takes it out again. What the
                // player already removed stays removed - the undo never creates a stack to fill
                // the gap.
                removeItems(inv, template, u.amount());
            } else {
                // the rollback took it out; the undo puts it back, and an overflow is dropped
                // next to the container instead of vanishing
                addItems(inv, template.copyWithCount(u.amount()), world, u.pos(), null, null);
            }
            // the undo changed the container as well, so the watchers' baseline has to follow
            ContainerTracker.rebaseline(world, u.pos());
            job.itemsApplied++;
            return true;
        }
        if (job.signUndoIndex < op.signs().size()) {
            SignUndo u = op.signs().get(job.signUndoIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            if (!world.isChunkLoaded(u.pos())) {
                job.unloadedSkips++;
                return true;
            }
            if (BlockStateUtil.replaceSignText(world, u.pos(), u.text()) != null) {
                job.signOps++;
            }
            return true;
        }
        if (job.blockUndoIndex < op.blocks().size()) {
            // Undo runs backwards: the change a rollback made last has to be reverted first,
            // which also makes the replayed per-position chains come out right.
            BlockUndo u = op.blocks().get(op.blocks().size() - 1 - job.blockUndoIndex);
            job.blockUndoIndex++;
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            if (!world.isChunkLoaded(u.pos())) {
                job.unloadedSkips++;
                job.skipped++;
                return true;
            }
            BlockState expected = BlockStateUtil.parseOrNull(job.server, u.expected());
            BlockState apply = BlockStateUtil.parseOrNull(job.server, u.apply());
            if (expected == null || apply == null) {
                job.skipped++; // nothing to restore from
                return true;
            }
            BlockState current = world.getBlockState(u.pos());
            if (!current.equals(expected)) {
                job.skipped++; // the position changed since the rollback ran
                return true;
            }
            if (!apply.isOf(current.getBlock())) {
                // Undoing a rollback that placed a block replaces a block again. When the current
                // block carries an inventory, removing its block entity loses the contents, so take
                // them out first and record them - the rollback side already did this, the undo side
                // silently deleted whatever had been put in since (L2-07).
                Inventory inv = ownedInventory(world, u.pos());
                if (inv != null && hasItems(inv)) {
                    ejectInventory(inv, world, u.pos(), job, u.wid());
                    CoreProtectFabric.LOGGER.warn("[CoreProtect] Undo emptied the container it replaced at {} in {}; its contents were dropped next to it and recorded.",
                            u.pos(), u.wid());
                }
            }
            world.setBlockState(u.pos(), apply, STRICT_FLAGS);
            job.blocksApplied++;
            return true;
        }
        if (job.worldItemIndex < op.worldItems().size()) {
            WorldItemUndo u = op.worldItems().get(job.worldItemIndex++);
            ServerWorld world = worldOf(job.server, u.wid());
            if (world == null) return true;
            Identifier id = Identifier.tryParse(u.item());
            Item item = id == null ? null : Registries.ITEM.get(id);
            if (item == null || item == Items.AIR) return true;
            // The stored data components decide what the stack actually was; without them an enchanted
            // drop would come back as a plain one.
            ItemStack template = ItemStackCodec.decode(u.nbt(), world.getRegistryManager());
            if (template == null) {
                template = new ItemStack(item);
            }
            int amount = u.amount();
            if (u.added()) {
                if (u.container() && amount > 0) {
                    // The rollback emptied this container before it replaced the block, so the undo
                    // puts the stack back into the container. Deleting it from the ground alone left
                    // the container empty and the drop gone: rollback + undo lost the inventory
                    // (L2-03). This stage runs after the block stage, so the container is back.
                    Inventory inv = world.isChunkLoaded(u.pos()) ? ContainerTracker.containerAt(world, u.pos()) : null;
                    if (inv != null) {
                        int stored = storeInInventory(inv, template.copyWithCount(amount));
                        if (stored > 0) {
                            amount -= stored;
                            ContainerTracker.rebaseline(world, u.pos());
                        }
                    } else if (!world.isChunkLoaded(u.pos())) {
                        job.unloadedSkips++;
                    }
                    if (amount > 0) {
                        // what did not fit (or a container that is gone) is taken off the ground, so
                        // the undo can never duplicate a stack
                        removeDroppedItems(world, u.pos(), template, amount);
                    }
                } else {
                    // the rollback dropped these; the undo picks them off the ground again, and what
                    // was already taken stays taken
                    removeDroppedItems(world, u.pos(), template, amount);
                }
            } else {
                dropItems(world, u.pos(), template, amount);
            }
            job.itemsApplied++;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    /**
     * The tables a rollback/restore may replay, derived from the {@code a:} argument. Until now the
     * action only filtered {@code co_block}, so {@code /co rollback a:#kill} also replayed the
     * container accesses and sign edits of the window, and {@code a:#chat} replayed everything.
     */
    private enum ActionScope {
        ALL(true, true, true, true, true),
        BLOCKS(true, false, false, false, false),
        CONTAINERS(false, true, false, false, false),
        SIGNS(false, false, true, false, false),
        ITEMS(false, false, false, true, false),
        ENTITIES(false, false, false, false, true),
        /** The action names data this command cannot replay (chat, commands, sessions). */
        UNSUPPORTED(false, false, false, false, false);

        private final boolean blocks;
        private final boolean containers;
        private final boolean signs;
        private final boolean items;
        private final boolean entities;

        ActionScope(boolean blocks, boolean containers, boolean signs, boolean items, boolean entities) {
            this.blocks = blocks;
            this.containers = containers;
            this.signs = signs;
            this.items = items;
            this.entities = entities;
        }

        static ActionScope of(String action) {
            if (action == null || action.isBlank()) return ALL;
            String raw = action.toLowerCase(Locale.ROOT);
            boolean prefixed = raw.startsWith("#") || raw.startsWith("+") || raw.startsWith("-");
            String base = prefixed ? raw.substring(1) : raw;
            return switch (base) {
                // "block" is the parser's default and keeps the historical behaviour of replaying
                // every table; an explicit table or the +/- block filter only affects co_block.
                case "block", "blocks" -> prefixed ? BLOCKS : ALL;
                case "container", "containers" -> CONTAINERS;
                case "sign", "signs" -> SIGNS;
                case "item", "items" -> ITEMS;
                case "kill", "kills", "entity", "entities", "mob", "mobs", "death", "deaths" -> ENTITIES;
                case "chat", "chats", "command", "commands", "command_block", "session", "sessions" -> UNSUPPORTED;
                // any other "#<cause>" (fire, water, lava, tnt, explosion, piston, hopper, ...) can only
                // ever match a co_block row, which is what the query's action filter does
                default -> prefixed ? BLOCKS : UNSUPPORTED;
            };
        }
    }

    /**
     * Whether the state is the transient medium a #fire/#water/#lava row leaves behind. Fire burns out
     * and flowing water or lava disappears once it settles, so a row that expects such a state stayed
     * "skipped" forever although it can be replayed.
     */
    private static boolean isTransientMedium(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE)
                || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA);
    }

    /**
     * Finds the #piston rows whose counterpart is missing from the plan. A piston move is written as
     * two rows sharing one timestamp (the "from" and the "to" position, with swapped states), and a
     * b:/r: filter or the result limit can drop one of them. Replaying a single half either leaves the
     * moved block at its destination and puts a copy back at the source, or deletes it.
     */
    private static Set<Long> findUnpairedPistonRows(List<Map.Entry<PosKey, List<DatabaseManager.BlockLog>>> chains) {
        Map<String, List<DatabaseManager.BlockLog>> byMove = new LinkedHashMap<>();
        for (Map.Entry<PosKey, List<DatabaseManager.BlockLog>> entry : chains) {
            for (DatabaseManager.BlockLog log : entry.getValue()) {
                if (!"#piston".equals(log.action())) continue;
                byMove.computeIfAbsent(log.wid() + '@' + log.time(), k -> new ArrayList<>()).add(log);
            }
        }
        Set<Long> unpaired = new HashSet<>();
        for (List<DatabaseManager.BlockLog> rows : byMove.values()) {
            boolean[] paired = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                if (paired[i]) continue;
                for (int j = i + 1; j < rows.size(); j++) {
                    if (paired[j]) continue;
                    DatabaseManager.BlockLog a = rows.get(i);
                    DatabaseManager.BlockLog b = rows.get(j);
                    if (a.x() == b.x() && a.y() == b.y() && a.z() == b.z()) continue;
                    if (Objects.equals(a.oldData(), b.newData()) && Objects.equals(a.newData(), b.oldData())) {
                        paired[i] = true;
                        paired[j] = true;
                        break;
                    }
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                if (!paired[i]) unpaired.add(rows.get(i).id());
            }
        }
        return unpaired.isEmpty() ? Set.of() : unpaired;
    }

    // ------------------------------------------------------------------

    private Map<PosKey, List<DatabaseManager.ContainerLog>> groupContainers(List<DatabaseManager.ContainerLog> logs) {
        Map<PosKey, List<DatabaseManager.ContainerLog>> groups = new LinkedHashMap<>();
        if (logs == null) return groups;
        for (DatabaseManager.ContainerLog log : logs) {
            groups.computeIfAbsent(PosKey.of(log.wid(), log.x(), log.y(), log.z()), k -> new ArrayList<>()).add(log);
        }
        return groups;
    }

    /**
     * Resolves the text a sign has to be set to. co_sign stores the text a row wrote and nothing
     * else, so for a rollback the text of the position's next older row is the one the players
     * lost when the oldest matched row was written; a restore re-applies the newest matched row.
     * Positions whose "before" text is unknown stay {@code null} and are reported as skipped.
     */
    private static List<SignTarget> resolveSignTargets(CoreProtectFabric mod, List<DatabaseManager.SignLog> signLogs,
                                                       boolean isRestore) {
        List<SignTarget> targets = new ArrayList<>();
        if (signLogs == null || signLogs.isEmpty()) return targets;
        Map<PosKey, List<DatabaseManager.SignLog>> chains = new LinkedHashMap<>();
        for (DatabaseManager.SignLog log : signLogs) {
            chains.computeIfAbsent(PosKey.of(log.wid(), log.x(), log.y(), log.z()), k -> new ArrayList<>()).add(log);
        }
        long deadline = System.nanoTime() + SIGN_HISTORY_BUDGET_NANOS;
        int queries = 0;
        int deferred = 0;
        for (Map.Entry<PosKey, List<DatabaseManager.SignLog>> entry : chains.entrySet()) {
            List<DatabaseManager.SignLog> chain = entry.getValue();
            PosKey key = entry.getKey();
            String target = null;
            if (isRestore) {
                target = chain.get(0).data();
            } else {
                // One query per position inside the read pool used to mean 500 statements for 500
                // signs, with every other lookup of the server queued behind them. The per-position
                // queries are capped and paced now: the positions that no longer fit stay unresolved
                // (and are reported as skipped) instead of stalling the pool.
                boolean outOfBudget = queries > 0 && queries % SIGN_HISTORY_BATCH == 0
                        && System.nanoTime() > deadline;
                if (queries >= MAX_SIGN_HISTORY_QUERIES || outOfBudget) {
                    deferred++;
                    targets.add(new SignTarget(key, null));
                    continue;
                }
                queries++;
                DatabaseManager.SignLog oldest = chain.get(chain.size() - 1);
                List<DatabaseManager.SignLog> history = mod.database()
                        .querySignHistory(key.wid(), key.x(), key.y(), key.z(), SIGN_HISTORY_LIMIT);
                if (history != null) {
                    for (DatabaseManager.SignLog log : history) { // newest first
                        if (log.id() >= oldest.id()) continue;
                        // only the same face: the other side's text is no replacement
                        if (BlockStateUtil.signIsBack(log.data()) == BlockStateUtil.signIsBack(oldest.data())) {
                            target = log.data();
                        }
                        break;
                    }
                }
            }
            targets.add(new SignTarget(key, target));
        }
        if (deferred > 0) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Sign text of {} of {} position(s) could not be resolved within {} quer(y/ies)/{} ms; those positions are reported as skipped.",
                    deferred, chains.size(), queries, SIGN_HISTORY_BUDGET_NANOS / 1_000_000L);
        }
        return targets;
    }

    /**
     * Replays one container row and returns how many items really moved: 0 when nothing did, which
     * is what keeps {@code /co undo} from inventing them (a row may describe 64 items while the
     * container only still held 32).
     */
    private int reverseContainer(Job job, DatabaseManager.ContainerLog log, Inventory inv, ServerWorld world, boolean isRestore) {
        // deposit (+N): rollback removes N, restore adds N; withdraw (-N): rollback adds N, restore removes N.
        boolean deposit = log.type() == DatabaseManager.CONTAINER_DEPOSIT;
        boolean add = isRestore == deposit;
        Identifier id = Identifier.tryParse(log.data());
        if (id == null) return 0;
        Item item = Registries.ITEM.get(id);
        if (item == null || item == Items.AIR) return 0;
        BlockPos pos = new BlockPos(log.x(), log.y(), log.z());
        // The row's data components describe the stack that was moved; a row written before they were
        // stored falls back to a plain stack of that item.
        ItemStack template = ItemStackCodec.decode(log.nbt(), world.getRegistryManager());
        if (template == null) {
            template = new ItemStack(item);
        }
        if (add) {
            return addItems(inv, template.copyWithCount(log.amount()), world, pos, job, log.wid());
        }
        return removeItems(inv, template, log.amount());
    }

    /**
     * Puts {@code remaining} into the container and returns how much of it fit. What does not fit is
     * dropped next to the container and recorded, so the undo takes it away again instead of leaving
     * a second copy of it in the world.
     */
    private static int addItems(Inventory inv, ItemStack remaining, ServerWorld world, BlockPos pos,
                                Job job, String wid) {
        int stored = storeInInventory(inv, remaining);
        if (!remaining.isEmpty()) {
            if (job != null) {
                // container=false: the overflow lies on the ground, so the undo picks it up instead of
                // storing it in a container a second time
                job.worldItemUndos.add(new WorldItemUndo(wid, pos, Registries.ITEM.getId(remaining.getItem()).toString(),
                        remaining.getCount(), true, ItemStackCodec.encodeIdentity(remaining, world.getRegistryManager()), false));
            }
            dropStack(world, pos, remaining);
        }
        return stored;
    }

    /**
     * Stores as much of {@code remaining} as fits and returns the stored count; what does not fit stays
     * in {@code remaining}. The undo uses this to put a stack back into the container a rollback
     * emptied, where the part that does not fit still has to be handled.
     */
    private static int storeInInventory(Inventory inv, ItemStack remaining) {
        int stored = 0;
        for (int i = 0; i < inv.size() && !remaining.isEmpty(); i++) {
            ItemStack slot = inv.getStack(i);
            if (slot.isEmpty()) {
                if (!inv.isValid(i, remaining)) continue;
                // never place more than one stack's worth in a single slot
                int move = Math.min(remaining.getCount(), remaining.getMaxCount());
                inv.setStack(i, remaining.copyWithCount(move));
                remaining.decrement(move);
                stored += move;
            } else if (ItemStack.areItemsAndComponentsEqual(slot, remaining) && slot.getCount() < slot.getMaxCount()) {
                int move = Math.min(remaining.getCount(), slot.getMaxCount() - slot.getCount());
                slot.increment(move);
                // write the mutated stack back: setStack is what marks the inventory dirty, so a
                // save right after a rollback keeps the merged stack and viewers see it
                inv.setStack(i, slot);
                remaining.decrement(move);
                stored += move;
            }
        }
        return stored;
    }

    /** Whether the container holds anything at all. */
    private static boolean hasItems(Inventory inv) {
        for (int i = 0; i < inv.size(); i++) {
            if (!inv.getStack(i).isEmpty()) return true;
        }
        return false;
    }

    /**
     * Takes {@code amount} of the stack described by {@code template} out of the container and
     * returns how much was really taken. Only what the container holds is taken: the missing rest
     * used to be handed out as well, which invented items out of nothing (a rollback of a deposit
     * that was already partly withdrawn paid them out again). The data components are compared as
     * well, so taking an enchanted item cannot consume a plain one.
     */
    private static int removeItems(Inventory inv, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack slot = inv.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(slot, template)) {
                int take = Math.min(slot.getCount(), remaining);
                remaining -= take;
                if (take >= slot.getCount()) {
                    inv.setStack(i, ItemStack.EMPTY);
                } else {
                    slot.decrement(take);
                    // setStack, not a bare decrement: it marks the inventory dirty so the change
                    // survives a save and reaches the players who have the container open
                    inv.setStack(i, slot);
                }
            }
        }
        return amount - remaining;
    }

    /**
     * Empties a container that is about to be replaced. The block entity is removed before
     * vanilla's {@code onStateReplaced()} runs, so the contents would vanish; they are dropped next
     * to the container - where a player can pick them up - and recorded, so {@code /co undo} takes
     * them off the ground again instead of restoring an empty container and leaving a copy behind.
     * (Handing them to the command's operator was worse: a console rollback dropped them into the
     * void, and the items were never part of the undo record.)
     */
    /**
     * The inventory owned by the block at {@code pos} itself, never the other half of a double
     * chest: replacing one half of a chest must not empty the half that stays in place.
     */
    private static Inventory ownedInventory(ServerWorld world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof Inventory inv ? inv : null;
    }

    private static void ejectInventory(Inventory inv, ServerWorld world, BlockPos pos, Job job, String wid) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            inv.setStack(i, ItemStack.EMPTY);
            if (job != null) {
                // container=true: the undo puts the stack back into the container the rollback restored
                // instead of deleting it from the ground, so rollback + undo nets out to zero.
                job.worldItemUndos.add(new WorldItemUndo(wid, pos,
                        Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), true,
                        ItemStackCodec.encodeIdentity(stack, world.getRegistryManager()), true));
            }
            dropStack(world, pos, stack);
        }
    }

    /**
     * Removes up to {@code amount} of the stack described by {@code template} from the dropped stacks
     * inside one block. Only drops are reversible this way: the log stores the position and the item,
     * but not which entity it was, so stacks that were picked up or moved away are left alone and
     * reported. The data components are compared as well, so an enchanted drop cannot be satisfied
     * by a plain one lying next to it.
     */
    private static int removeDroppedItems(ServerWorld world, BlockPos pos, ItemStack template, int amount) {
        int remaining = amount;
        for (Entity entity : world.getOtherEntities(null, new Box(pos), e -> e instanceof ItemEntity)) {
            if (remaining <= 0) break;
            ItemEntity itemEntity = (ItemEntity) entity;
            ItemStack stack = itemEntity.getStack();
            if (!ItemStack.areItemsAndComponentsEqual(stack, template)) continue;
            int take = Math.min(stack.getCount(), remaining);
            remaining -= take;
            if (take >= stack.getCount()) {
                itemEntity.discard();
            } else {
                // the stack is a tracked value, so it has to be replaced instead of reduced
                itemEntity.setStack(stack.copyWithCount(stack.getCount() - take));
            }
        }
        return amount - remaining;
    }

    /** Drops {@code amount} of the stack described by {@code template}, split into legal stacks. */
    private static void dropItems(ServerWorld world, BlockPos pos, ItemStack template, int amount) {
        int max = Math.max(1, template.getMaxCount());
        int remaining = amount;
        while (remaining > 0) {
            int move = Math.min(remaining, max);
            dropItem(world, pos, template.copyWithCount(move));
            remaining -= move;
        }
    }

    /** Drops a whole stack, split into stacks the item can actually hold. */
    private static void dropStack(ServerWorld world, BlockPos pos, ItemStack stack) {
        dropItems(world, pos, stack, stack.getCount());
    }

    private static void dropItem(ServerWorld world, BlockPos pos, ItemStack stack) {
        world.spawnEntity(new ItemEntity(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack));
    }

    private static ServerWorld worldOf(MinecraftServer server, String wid) {
        if (server == null || wid == null) return null;
        Identifier id = Identifier.tryParse(wid);
        if (id == null) return null;
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    // ------------------------------------------------------------------
    // Undo store (finding C12: the record has to outlive the process)
    // ------------------------------------------------------------------

    private Path undoFilePath() {
        return FabricLoader.getInstance().getConfigDir().resolve(UNDO_FILE);
    }

    private synchronized void ensureUndoLoaded() {
        if (undoLoaded) return;
        undoLoaded = true;
        Path path = undoFilePath();
        if (!Files.exists(path)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            if (!parsed.isJsonObject()) return;
            JsonObject root = parsed.getAsJsonObject();
            JsonElement version = root.get("version");
            if (version != null && version.getAsInt() > UNDO_FILE_VERSION) {
                CoreProtectFabric.LOGGER.warn("[CoreProtect] {} was written by a newer version; /co undo starts empty", UNDO_FILE);
                return;
            }
            JsonElement players = root.get("players");
            if (players == null || !players.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> entry : players.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                UUID id;
                try {
                    id = UUID.fromString(entry.getKey());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                lastOperations.put(id, readOperation(entry.getValue().getAsJsonObject()));
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Could not read " + UNDO_FILE + "; /co undo starts empty", e);
        }
    }

    private synchronized void saveLastOperations() {
        ensureUndoLoaded(); // never drop a record an earlier session stored
        JsonObject root = new JsonObject();
        root.addProperty("version", UNDO_FILE_VERSION);
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, LastOperation> entry : lastOperations.entrySet()) {
            players.add(entry.getKey().toString(), writeOperation(entry.getValue()));
        }
        root.add("players", players);
        // Write a temporary file and move it into place: Files.writeString() truncated the record file
        // first, so a crash (or a force killed server) during the write left an empty or half written
        // file behind and every player's undo record was gone on the next start.
        Path target = undoFilePath();
        try {
            Path directory = target.getParent();
            if (directory != null) {
                Files.createDirectories(directory);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // not every filesystem supports an atomic replace; a single move is still better than
                // writing the record file in place
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            CoreProtectFabric.LOGGER.warn("[CoreProtect] Failed to store the undo record", e);
        }
    }

    private static JsonObject writeOperation(LastOperation op) {
        JsonObject out = new JsonObject();
        out.addProperty("kind", op.kind());
        JsonArray blocks = new JsonArray();
        for (BlockUndo u : op.blocks()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("at", u.expected());
            o.addProperty("to", u.apply());
            blocks.add(o);
        }
        out.add("blocks", blocks);
        JsonArray items = new JsonArray();
        for (ItemUndo u : op.items()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("item", u.item());
            o.addProperty("amount", u.amount());
            o.addProperty("added", u.wasAdded());
            if (u.nbt() != null) {
                // stored so an undo restores the same enchanted/filled stack the rollback moved
                o.addProperty("nbt", u.nbt());
            }
            items.add(o);
        }
        out.add("items", items);
        JsonArray worldItems = new JsonArray();
        for (WorldItemUndo u : op.worldItems()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("item", u.item());
            o.addProperty("amount", u.amount());
            o.addProperty("added", u.added());
            // whether the stack came out of a container the rollback replaced: the undo puts it back
            // into the restored container instead of deleting it from the ground
            o.addProperty("container", u.container());
            if (u.nbt() != null) {
                o.addProperty("nbt", u.nbt());
            }
            worldItems.add(o);
        }
        out.add("world_items", worldItems);
        JsonArray signs = new JsonArray();
        for (SignUndo u : op.signs()) {
            JsonObject o = position(u.wid(), u.pos());
            o.addProperty("text", u.text());
            signs.add(o);
        }
        out.add("signs", signs);
        return out;
    }

    private static LastOperation readOperation(JsonObject o) {
        String kind = string(o, "kind");
        List<BlockUndo> blocks = new ArrayList<>();
        for (JsonElement e : array(o, "blocks")) {
            JsonObject b = e.getAsJsonObject();
            blocks.add(new BlockUndo(string(b, "w"), position(b), string(b, "at"), string(b, "to")));
        }
        List<ItemUndo> items = new ArrayList<>();
        for (JsonElement e : array(o, "items")) {
            JsonObject b = e.getAsJsonObject();
            items.add(new ItemUndo(string(b, "w"), position(b), string(b, "item"), integer(b, "amount"), bool(b, "added"), string(b, "nbt")));
        }
        List<WorldItemUndo> worldItems = new ArrayList<>();
        for (JsonElement e : array(o, "world_items")) {
            JsonObject b = e.getAsJsonObject();
            worldItems.add(new WorldItemUndo(string(b, "w"), position(b), string(b, "item"), integer(b, "amount"),
                    bool(b, "added"), string(b, "nbt"), bool(b, "container")));
        }
        List<SignUndo> signs = new ArrayList<>();
        for (JsonElement e : array(o, "signs")) {
            JsonObject b = e.getAsJsonObject();
            signs.add(new SignUndo(string(b, "w"), position(b), string(b, "text")));
        }
        return new LastOperation(kind == null ? "rollback" : kind, blocks, items, worldItems, signs);
    }

    private static JsonObject position(String wid, BlockPos pos) {
        JsonObject o = new JsonObject();
        o.addProperty("w", wid);
        o.addProperty("x", pos.getX());
        o.addProperty("y", pos.getY());
        o.addProperty("z", pos.getZ());
        return o;
    }

    private static BlockPos position(JsonObject o) {
        return new BlockPos(integer(o, "x"), integer(o, "y"), integer(o, "z"));
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static int integer(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || !e.isJsonPrimitive() ? 0 : e.getAsInt();
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
